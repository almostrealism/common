#!/usr/bin/env python3
"""PreToolUse (Bash) hook: verdict on installed-artifact staleness before Maven runs.

A Maven invocation only builds the modules in its reactor. In particular,
`mvn <goal> -pl <module>` without `-am` does NOT rebuild that module's upstream
dependencies, and nothing in a partial build rebuilds the *downstream* modules
that depend on the built one. Every module not in the reactor keeps resolving the
artifact that is currently installed in the local repository — which may have
been built from a DIFFERENT branch than the working tree. This silently produces
test/build results that are evidence about the wrong code.

The hook judges the directory the invocation will actually install into and
resolve from, as resolved by tools/mcp/common/maven_repository.py — the same
reading the ar-test-runner preflight uses:

- `-Dmaven.repo.local` on the command line, in MAVEN_ARGS or in MAVEN_OPTS;
- otherwise FLOWTREE_MAVEN_LOCAL_REPOSITORY, which a FlowTree job exports;
- otherwise ~/.m2/repository;
- with the split local repository (MAVEN_ARGS in a FlowTree job) the install
  directory is the repository's localPrefix subdirectory.

It compares each installed module artifact there against the time of the most
recent branch switch (the last `checkout:` entry in the reflog):

- When every artifact postdates the last branch switch, it emits one quiet line.
- When nothing is installed there yet (a fresh install space), it says so in one
  short line: Maven will fail to resolve those modules, not resolve old code.
- When any artifact predates the switch, it emits a loud warning naming the
  directory, the stale artifacts, the branch switch that invalidated them, and
  what the command will (and will not) rebuild.

Every message names the directory it looked in, so a wrong lookup is visible.

The hook fires for Bash commands containing artifact-producing Maven goals, and
also for the ar-test-runner and ar-build-validator MCP tools, whose Maven
subprocesses run out-of-band (inheriting this environment, with no repository
flag of their own) and would otherwise bypass the check entirely.

The hook never blocks the command; it only injects context.
"""

import json
import os
import re
import shlex
import subprocess
import sys
from datetime import datetime
from xml.etree import ElementTree

# The shared reading of the local repository layout (tools/mcp/common).
_COMMON_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                           "..", "..", "tools", "mcp", "common")
if _COMMON_DIR not in sys.path:
    sys.path.insert(0, _COMMON_DIR)
# TODO(review): an ImportError here exits with a traceback on every Bash call; the hook should degrade quietly.
import maven_repository  # noqa: E402

# Maven lifecycle goals that compile sources and/or write/install artifacts.
ARTIFACT_GOALS = {
    "compile", "test-compile", "testCompile",
    "process-classes", "process-test-classes",
    "test", "integration-test", "package", "verify",
    "install", "deploy",
}

# Goals that write the built artifacts into the local repository.
INSTALL_GOALS = {"install", "deploy"}

MVN_TOKENS = {"mvn", "mvnw", "./mvnw", "mvn.cmd"}

# Environment variables a command may set inline that change the repository.
MAVEN_ENV_VARS = {"MAVEN_ARGS", "MAVEN_OPTS", maven_repository.LOCAL_REPOSITORY_ENV}

# How many stale or missing artifacts to list individually before summarizing.
STALE_LIST_LIMIT = 12

_ASSIGNMENT = re.compile(r"^([A-Za-z_][A-Za-z0-9_]*)=(.*)$", re.S)


class Invocation:
    """One Maven run the hook judges: where it installs, and what it rebuilds.

    Attributes:
        location: maven_repository.InstallLocation for the run.
        goals: The artifact-producing goals named on the command line.
        projects: The `-pl` selection, or None when the full reactor builds.
        also_make: True when `-am` rebuilds the selection's dependencies.
        also_make_dependents: True when `-amd` rebuilds the selection's dependents.
        out_of_band: Name of an MCP tool running Maven, or None for a command line.
    """

    def __init__(self, location, goals=(), projects=None, also_make=False,
                 also_make_dependents=False, out_of_band=None):
        self.location = location
        self.goals = set(goals)
        self.projects = projects
        self.also_make = also_make
        self.also_make_dependents = also_make_dependents
        self.out_of_band = out_of_band

    @classmethod
    def from_tokens(cls, tokens, environ):
        """Return the Invocation for one shell segment, or None if it runs no Maven goal.

        Leading `NAME=value` assignments of the Maven environment variables
        (`MAVEN_OPTS=... mvn ...`) apply to this run only, as in the shell.
        """
        index = next((i for i, t in enumerate(tokens)
                      if t in MVN_TOKENS or t.endswith("/mvn") or t.endswith("/mvnw")),
                     None)
        if index is None:
            return None
        env = dict(environ)
        for token in tokens[:index]:
            match = _ASSIGNMENT.match(token)
            if match and match.group(1) in MAVEN_ENV_VARS:
                env[match.group(1)] = match.group(2)
        args = tokens[index + 1:]
        goals = {t for t in args if t in ARTIFACT_GOALS}
        if not goals:
            return None
        return cls(maven_repository.install_location(args, env), goals,
                   projects=_projects(args),
                   also_make=any(t in ("-am", "--also-make") for t in args),
                   also_make_dependents=any(t in ("-amd", "--also-make-dependents")
                                            for t in args))

    def installs(self):
        """True when this run writes its modules into the install directory."""
        return bool(self.goals & INSTALL_GOALS)

    def partial(self):
        """True when the run leaves its selection's dependencies unrebuilt."""
        return self.out_of_band is not None or (self.projects is not None
                                                and not self.also_make)

    def refreshes_all(self):
        """True when this run reinstalls every module of the reactor."""
        return self.out_of_band is None and self.projects is None and self.installs()

    def scope_statement(self):
        """Say what this run rebuilds, and what keeps resolving the install directory."""
        root = self.location.root
        if self.out_of_band is not None:
            return (f"{self.out_of_band} runs Maven in its own process with this environment. "
                    "It does not rebuild modules outside the one it runs; they resolve "
                    f"whatever is installed in {root}.")
        if self.projects is None:
            if self.installs():
                return ("This command builds and installs every module in the reactor, "
                        "which refreshes all of them — but any test results gathered "
                        "BEFORE this build finished are suspect.")
            return ("This command builds every module in the reactor, so within this run "
                    "the modules resolve each other from the reactor. It does not install, "
                    f"so {root} keeps its current artifacts for later runs.")
        named = ", ".join(f"`{p}`" for p in self.projects) or "the selected modules"
        parts = [named]
        if self.also_make:
            parts.append("the modules they depend on (`-am`)")
        if self.also_make_dependents:
            parts.append("the modules that depend on them (`-amd`)")
        verb = "rebuilds and installs" if self.installs() else "rebuilds"
        text = f"This command {verb} only " + " and ".join(parts) + ". "
        if self.also_make:
            text += f"Every other module still resolves its artifact from {root}."
        else:
            text += (f"Their dependencies are NOT rebuilt (no `-am`) and resolve from {root}; "
                     "to pick up edits in a dependency first, install it "
                     "(e.g. `mvn install -DskipTests -pl <module> -am`).")
        return text

    def refresh_command(self):
        """The command that reinstalls every module into this run's install directory."""
        command = "mvn clean install -DskipTests"
        if self.location.origin.startswith(
                f"{maven_repository.REPO_LOCAL_PROPERTY} from the command line"):
            command += f" -D{maven_repository.REPO_LOCAL_PROPERTY}={self.location.repository}"
        return command


def _projects(args):
    """Return the modules selected with `-pl`/`--projects`, or None for the full reactor."""
    selected = None
    for i, token in enumerate(args):
        value = None
        if token in ("-pl", "--projects") and i + 1 < len(args):
            value = args[i + 1]
        elif token.startswith("--projects="):
            value = token.split("=", 1)[1]
        elif token.startswith("-pl") and len(token) > 3:
            value = token[3:].lstrip("=")
        if value is not None:
            selected = (selected or []) + [p for p in value.split(",") if p]
    return selected


def read_payload():
    try:
        return json.load(sys.stdin)
    except Exception:
        return {}


def mvn_invocations(command, environ):
    """Return an Invocation for each shell segment that runs an artifact-producing goal."""
    invocations = []
    for segment in re.split(r"(?:\|\||&&|[;&|\n])", command):
        try:
            tokens = shlex.split(segment)
        except ValueError:
            tokens = segment.split()
        invocation = Invocation.from_tokens(tokens, environ) if tokens else None
        if invocation is not None:
            invocations.append(invocation)
    return invocations


def _strip_ns(tag):
    return tag.split("}", 1)[-1] if "}" in tag else tag


def _child_text(elem, name):
    for c in elem:
        if _strip_ns(c.tag) == name:
            return (c.text or "").strip()
    return None


def _parent_block(root):
    for c in root:
        if _strip_ns(c.tag) == "parent":
            return c
    return None


def parse_module(pom_path):
    """Return (groupId, artifactId, version, packaging) resolving parent inheritance."""
    try:
        root = ElementTree.parse(pom_path).getroot()
    except Exception:
        return None
    artifact = _child_text(root, "artifactId")
    if not artifact:
        return None
    group = _child_text(root, "groupId")
    version = _child_text(root, "version")
    packaging = _child_text(root, "packaging") or "jar"
    parent = _parent_block(root)
    if parent is not None:
        if not group:
            group = _child_text(parent, "groupId")
        if not version:
            version = _child_text(parent, "version")
    return (group, artifact, version, packaging)


def find_modules(project_dir):
    modules = []
    skip = {"target", ".git", "node_modules", ".idea"}
    for dirpath, dirnames, filenames in os.walk(project_dir):
        dirnames[:] = [d for d in dirnames if d not in skip]
        if "pom.xml" in filenames:
            pom = os.path.join(dirpath, "pom.xml")
            info = parse_module(pom)
            if info:
                rel = os.path.relpath(dirpath, project_dir)
                modules.append((rel, info))
    return modules


def installed_artifact(root, group, artifact, version, packaging):
    """Return (path, install_mtime) of the artifact installed under root, or (None, None).

    root is the directory Maven installs into for the judged invocation (see
    maven_repository.install_location). The install time is taken from the
    version directory's _remote.repositories marker when available: Maven
    copies pom artifacts preserving the source file's modification time, so
    the artifact file itself can carry a timestamp far older than the install
    that wrote it.
    """
    if not (group and artifact and version):
        return (None, None)
    base = os.path.join(str(root), *group.split("."), artifact, version)
    candidates = []
    if packaging and packaging != "jar":
        candidates.append(os.path.join(base, f"{artifact}-{version}.{packaging}"))
    candidates.append(os.path.join(base, f"{artifact}-{version}.jar"))
    candidates.append(os.path.join(base, f"{artifact}-{version}.pom"))
    for path in candidates:
        if os.path.isfile(path):
            marker = os.path.join(base, "_remote.repositories")
            if os.path.isfile(marker):
                return (path, os.path.getmtime(marker))
            return (path, os.path.getmtime(path))
    return (None, None)


def last_branch_switch(project_dir):
    """Return (unix_time, from_ref, to_ref) for the most recent checkout, or None."""
    try:
        result = subprocess.run(
            ["git", "reflog", "--date=unix"],
            cwd=project_dir, capture_output=True, text=True, timeout=10)
    except Exception:
        return None
    if result.returncode != 0:
        return None
    pattern = re.compile(
        r"HEAD@\{(\d+)\}: checkout: moving from (\S+) to (\S+)")
    for line in result.stdout.splitlines():
        m = pattern.search(line)
        if m:
            return (int(m.group(1)), m.group(2), m.group(3))
    return None


def artifact_rows(project_dir, root):
    """Return (module dir, artifactId, install mtime or None) for every module, oldest first."""
    rows = []
    for rel, (group, artifact, version, packaging) in find_modules(project_dir):
        path, mtime = installed_artifact(root, group, artifact, version, packaging)
        rows.append((rel, artifact, mtime))
    rows.sort(key=lambda r: (r[2] is not None, r[2] if r[2] else 0.0))
    return rows


def fmt_time(unix_time):
    return datetime.fromtimestamp(unix_time).strftime("%Y-%m-%d %H:%M")


def looked_in(location):
    """Name the directory judged and why it was chosen."""
    return f"{location.root} ({location.origin})"


def listing(rows, label):
    """Render up to STALE_LIST_LIMIT rows, labelling missing artifacts."""
    lines = []
    for rel, artifact, mtime in rows[:STALE_LIST_LIMIT]:
        when = fmt_time(mtime) if mtime else label
        lines.append(f"  {when}  {artifact}  ({rel})")
    if len(rows) > STALE_LIST_LIMIT:
        lines.append(f"  ... and {len(rows) - STALE_LIST_LIMIT} more")
    return lines


def stale_warning(invocation, stale, missing, total, switch):
    switch_time, from_ref, to_ref = switch
    root = invocation.location.root
    lines = [f"*** STALE INSTALLED ARTIFACTS ({len(stale)} of {total} module artifacts "
             "predate the current checkout) ***",
             "",
             f"Looked in {looked_in(invocation.location)}.",
             "",
             f"HEAD moved from {from_ref} to {to_ref} at {fmt_time(switch_time)}, but the "
             "artifacts below were installed BEFORE that. They were built from the "
             f"PREVIOUS branch's source. Any Maven run that resolves them from {root} "
             "produces results about the wrong code.",
             "",
             "Why you should care: a test that \"passes locally\" against stale artifacts "
             "proves nothing about this branch. Real incident (2026-07-14, this repo): a "
             "CI failure investigation ran engine/ml tests that resolved an ar-graph jar "
             "built from the previously checked out branch — the failure falsely "
             "\"did not reproduce\" and hours went into wrong hypotheses; after a clean "
             "rebuild it reproduced immediately, compiler diagnostic and all.",
             "",
             invocation.scope_statement()]
    if not invocation.refreshes_all():
        lines.append("Refresh before trusting any build or test result: "
                     f"`{invocation.refresh_command()}`")
    lines.append("")
    lines.append(f"Stale artifacts (installed before {fmt_time(switch_time)}):")
    lines.extend(listing(stale, ""))
    if missing:
        lines.append("")
        lines.append(f"Not installed in {root} at all ({len(missing)}):")
        lines.extend(listing(missing, "NOT INSTALLED   "))
    return "\n".join(lines)


def build_report(project_dir, invocation):
    """Return (context for the model, one-line summary) for one invocation."""
    location = invocation.location
    root = location.root
    switch = last_branch_switch(project_dir)
    rows = artifact_rows(project_dir, root)
    missing = [r for r in rows if r[2] is None]
    installed = [r for r in rows if r[2] is not None]

    if rows and not installed:
        message = (f"Maven artifact check: nothing is installed yet in {looked_in(location)} "
                   "— a fresh install space, not stale artifacts. A module this run does "
                   "not build fails to resolve rather than resolving old code.")
        if invocation.refreshes_all():
            message += " This command installs every module there."
        else:
            message += f" To install everything: `{invocation.refresh_command()}`."
        return message, f"Maven build detected; nothing installed yet in {root}."

    if switch is None:
        lines = ["No branch-switch history available to judge staleness in "
                 f"{looked_in(location)}. Installed artifact times (oldest first):"]
        lines.extend(listing(rows, "NOT INSTALLED   "))
        if invocation.partial():
            lines.append("")
            lines.append(invocation.scope_statement())
        return "\n".join(lines), f"Maven build detected; could not judge staleness in {root}."

    switch_time = switch[0]
    stale = [r for r in installed if r[2] < switch_time]

    if stale:
        report = stale_warning(invocation, stale, missing, len(rows), switch)
        summary = (f"STALE INSTALLED ARTIFACTS in {root}: {len(stale)} of {len(rows)} predate "
                   "the current checkout — build/test results will reflect the previous "
                   "branch. See context.")
        return report, summary

    message = (f"Maven artifact check in {looked_in(location)}: all {len(installed)} "
               "installed module artifacts are newer than the current checkout "
               f"({switch[2]} at {fmt_time(switch_time)}).")
    if missing:
        names = ", ".join(r[1] for r in missing[:STALE_LIST_LIMIT])
        if len(missing) > STALE_LIST_LIMIT:
            names += f" and {len(missing) - STALE_LIST_LIMIT} more"
        message += f" Not installed there: {names}."
    if invocation.partial():
        message += "\n\n" + invocation.scope_statement()
    return message, f"Maven build detected; installed artifacts in {root} are current for this branch."


# MCP tools that invoke Maven in their own subprocess, outside any Bash command.
# They inherit this environment and add no repository flag, and never refresh
# the whole reactor, so a stale install directory must be fixed before trusting them.
MCP_MAVEN_TOOLS = {
    "mcp__ar-test-runner__start_test_run": "The ar-test-runner",
    "mcp__ar-build-validator__start_validation": "The ar-build-validator",
}


def invocations_for(payload, environ):
    """Return the Invocations the tool call in payload will run."""
    tool = payload.get("tool_name")
    if tool in MCP_MAVEN_TOOLS:
        return [Invocation(maven_repository.install_location((), environ),
                           out_of_band=MCP_MAVEN_TOOLS[tool])]
    if tool == "Bash":
        command = (payload.get("tool_input") or {}).get("command", "")
        return mvn_invocations(command, environ) if command else []
    return []


def main():
    payload = read_payload()
    project_dir = (os.environ.get("CLAUDE_PROJECT_DIR")
                   or payload.get("cwd")
                   or os.getcwd())
    invocations = invocations_for(payload, os.environ)
    if not invocations:
        sys.exit(0)
    try:
        reports, summaries, seen = [], [], set()
        for invocation in invocations:
            key = (invocation.location.root, invocation.scope_statement())
            if key in seen:
                continue
            seen.add(key)
            report, summary = build_report(project_dir, invocation)
            reports.append(report)
            summaries.append(summary)
        report, summary = "\n\n".join(reports), " ".join(dict.fromkeys(summaries))
    except Exception as e:  # never break the command on a hook error
        report = ("Maven artifact-producing goal detected, but the staleness report "
                  "could not be generated: " + repr(e))
        summary = "Maven build detected; staleness report unavailable."

    out = {
        "hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "additionalContext": report,
        },
        "systemMessage": summary,
    }
    print(json.dumps(out))
    sys.exit(0)


if __name__ == "__main__":
    main()
