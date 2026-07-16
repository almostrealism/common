#!/usr/bin/env python3
"""PreToolUse (Bash) hook: verdict on ~/.m2 artifact staleness before Maven runs.

A Maven invocation only builds the modules in its reactor. In particular,
`mvn <goal> -pl <module>` without `-am` does NOT rebuild that module's upstream
dependencies, and nothing in a partial build rebuilds the *downstream* modules
that depend on the built one. Every module not in the reactor keeps resolving the
artifact that is currently installed in ~/.m2 — which may have been built from a
DIFFERENT branch than the working tree. This silently produces test/build results
that are evidence about the wrong code.

Earlier versions of this hook printed a passive table of artifact timestamps on
every Maven call. That table was routinely skimmed past, and the exact failure it
existed to prevent happened anyway (see the warning text below). This version
computes a verdict instead: it compares each installed artifact against the time
of the most recent branch switch (the last `checkout:` entry in the reflog).

- When every artifact postdates the last branch switch, it emits one quiet line.
- When any artifact predates it, it emits a loud warning naming the stale
  artifacts, the branch switch that invalidated them, the concrete consequence,
  and the refresh command.

The hook fires for Bash commands containing artifact-producing Maven goals, and
also for the ar-test-runner and ar-build-validator MCP tools, whose Maven
subprocesses run out-of-band and would otherwise bypass the check entirely —
which is exactly how the incident described in the warning text happened.

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

# Maven lifecycle goals that compile sources and/or write/install artifacts.
ARTIFACT_GOALS = {
    "compile", "test-compile", "testCompile",
    "process-classes", "process-test-classes",
    "test", "integration-test", "package", "verify",
    "install", "deploy",
}

MVN_TOKENS = {"mvn", "mvnw", "./mvnw", "mvn.cmd"}

# How many stale artifacts to list individually before summarizing the rest.
STALE_LIST_LIMIT = 12


def read_payload():
    try:
        return json.load(sys.stdin)
    except Exception:
        return {}


def mvn_segments(command):
    """Yield token lists for each shell segment that invokes Maven."""
    for segment in re.split(r"(?:\|\||&&|[;&|\n])", command):
        try:
            tokens = shlex.split(segment)
        except ValueError:
            tokens = segment.split()
        if not tokens:
            continue
        if any(t in MVN_TOKENS or t.endswith("/mvn") or t.endswith("/mvnw")
               for t in tokens):
            yield tokens


def detect_goals(command):
    """Return (goals, partial_reactor) across every `mvn ...` segment.

    partial_reactor is True when any Maven segment restricts the reactor with
    -pl/--projects without also passing -am/--also-make.
    """
    found = set()
    partial = False
    for tokens in mvn_segments(command):
        for t in tokens:
            if t in ARTIFACT_GOALS:
                found.add(t)
        has_pl = any(t in ("-pl", "--projects") or t.startswith("-pl=")
                     for t in tokens)
        has_am = any(t in ("-am", "--also-make") for t in tokens)
        if has_pl and not has_am:
            partial = True
    return found, partial


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


def m2_artifact(home, group, artifact, version, packaging):
    """Return (path, install_mtime) of the installed artifact, or (None, None).

    The install time is taken from the version directory's _remote.repositories
    marker when available: Maven copies pom artifacts preserving the source
    file's modification time, so the artifact file itself can carry a timestamp
    far older than the install that wrote it.
    """
    if not (group and artifact and version):
        return (None, None)
    base = os.path.join(home, ".m2", "repository", *group.split("."),
                        artifact, version)
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


def artifact_rows(project_dir, home):
    rows = []
    for rel, (group, artifact, version, packaging) in find_modules(project_dir):
        path, mtime = m2_artifact(home, group, artifact, version, packaging)
        rows.append((rel, artifact, mtime))
    # Oldest installed first; missing artifacts at the very top.
    rows.sort(key=lambda r: (r[2] is not None, r[2] if r[2] else 0.0))
    return rows


def fmt_time(unix_time):
    return datetime.fromtimestamp(unix_time).strftime("%Y-%m-%d %H:%M")


def partial_reactor_caveat():
    return ("Reactor caveat: with `-pl <module>` and no `-am`, upstream dependencies "
            "are NOT rebuilt and downstream consumers are NOT rebuilt; modules outside "
            "the reactor resolve whatever is installed in ~/.m2. To pick up edits in a "
            "dependency before testing a consumer, install the dependency first "
            "(e.g. `mvn install -DskipTests -pl <module> -am`).")


def stale_warning(stale, fresh_count, switch, full_refresh):
    switch_time, from_ref, to_ref = switch
    total = len(stale) + fresh_count
    lines = []
    lines.append("*** STALE ~/.m2 ARTIFACTS "
                 f"({len(stale)} of {total} module artifacts predate the current checkout) ***")
    lines.append("")
    lines.append(f"HEAD moved from {from_ref} to {to_ref} at {fmt_time(switch_time)}, but the "
                 "artifacts below were installed BEFORE that. They were built from the "
                 "PREVIOUS branch's source. Any Maven run that resolves them from ~/.m2 "
                 "produces results about the wrong code.")
    lines.append("")
    lines.append("Why you should care: a test that \"passes locally\" against stale artifacts "
                 "proves nothing about this branch. Real incident (2026-07-14, this repo): a "
                 "CI failure investigation ran engine/ml tests that resolved an ar-graph jar "
                 "built from the previously checked out branch — the failure falsely "
                 "\"did not reproduce\" and hours went into wrong hypotheses; after a clean "
                 "rebuild it reproduced immediately, compiler diagnostic and all.")
    lines.append("")
    if full_refresh:
        lines.append("This command builds the full reactor, which will refresh all of them — "
                     "but any test results gathered BEFORE this build finished are suspect.")
    else:
        lines.append("Refresh before trusting any build or test result:")
        lines.append("  mvn clean install -DskipTests")
    lines.append("")
    lines.append(f"Stale artifacts (installed before {fmt_time(switch_time)}):")
    for rel, artifact, mtime in stale[:STALE_LIST_LIMIT]:
        when = fmt_time(mtime) if mtime else "NOT INSTALLED   "
        lines.append(f"  {when}  {artifact}  ({rel})")
    if len(stale) > STALE_LIST_LIMIT:
        lines.append(f"  ... and {len(stale) - STALE_LIST_LIMIT} more")
    return "\n".join(lines)


def build_report(project_dir, home, partial):
    switch = last_branch_switch(project_dir)
    rows = artifact_rows(project_dir, home)

    if switch is None:
        # No branch-switch baseline available; fall back to the full table.
        lines = ["No branch-switch history available to judge ~/.m2 staleness. "
                 "Installed artifact times (oldest first):"]
        for rel, artifact, mtime in rows:
            when = fmt_time(mtime) if mtime else "NOT INSTALLED   "
            lines.append(f"  {when}  {artifact}  ({rel})")
        if partial:
            lines.append("")
            lines.append(partial_reactor_caveat())
        return "\n".join(lines), "Maven build detected; could not judge ~/.m2 staleness."

    switch_time = switch[0]
    stale = [r for r in rows if r[2] is None or r[2] < switch_time]
    fresh_count = len(rows) - len(stale)

    if not stale:
        message = (f"~/.m2 staleness check: all {len(rows)} module artifacts are newer than "
                   f"the current checkout ({switch[2]} at {fmt_time(switch_time)}).")
        if partial:
            message += "\n\n" + partial_reactor_caveat()
        return message, "Maven build detected; ~/.m2 artifacts are current for this branch."

    report = stale_warning(stale, fresh_count, switch, full_refresh=not partial)
    if partial:
        report += "\n\n" + partial_reactor_caveat()
    summary = (f"STALE ~/.m2 ARTIFACTS: {len(stale)} of {len(rows)} predate the current "
               "checkout — build/test results will reflect the previous branch. See context.")
    return report, summary


# MCP tools that invoke Maven in their own subprocess, outside any Bash command.
# Their builds are always partial from this hook's perspective: they never
# refresh the whole reactor, so a stale ~/.m2 must be fixed before trusting them.
MCP_MAVEN_TOOLS = {
    "mcp__ar-test-runner__start_test_run",
    "mcp__ar-build-validator__start_validation",
}


def main():
    payload = read_payload()
    tool = payload.get("tool_name")

    if tool in MCP_MAVEN_TOOLS:
        partial = True
    elif tool == "Bash":
        command = (payload.get("tool_input") or {}).get("command", "")
        if not command:
            sys.exit(0)
        goals, partial = detect_goals(command)
        if not goals:
            sys.exit(0)
    else:
        sys.exit(0)

    project_dir = (os.environ.get("CLAUDE_PROJECT_DIR")
                   or payload.get("cwd")
                   or os.getcwd())
    home = os.path.expanduser("~")

    try:
        report, summary = build_report(project_dir, home, partial)
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
