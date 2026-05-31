#!/usr/bin/env python3
"""PreToolUse (Bash) hook: warn when an artifact-producing Maven goal is run.

A Maven invocation only builds the modules in its reactor. In particular,
`mvn <goal> -pl <module>` without `-am` does NOT rebuild that module's upstream
dependencies, and nothing in a partial build rebuilds the *downstream* modules
that depend on the built one. Every module not in the reactor keeps resolving the
artifact that is currently installed in ~/.m2 — which may be stale relative to the
working tree. This silently produces test/build results that do not reflect local
source edits in other modules.

When the agent is about to run such a command this hook emits an explanation plus
a table of the last-modified time of each of this repo's module artifacts in
~/.m2, so it is obvious which installed jars are stale.

The hook never blocks the command; it only injects context.
"""

import json
import os
import re
import shlex
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


def read_payload():
    try:
        return json.load(sys.stdin)
    except Exception:
        return {}


def detect_goals(command):
    """Return the set of artifact-producing goals in any `mvn ...` segment."""
    found = set()
    # Split into shell segments so `npm install && mvn -version` is not a match.
    for segment in re.split(r"(?:\|\||&&|[;&|\n])", command):
        try:
            tokens = shlex.split(segment)
        except ValueError:
            tokens = segment.split()
        if not tokens:
            continue
        has_mvn = any(t in MVN_TOKENS or t.endswith("/mvn") or t.endswith("/mvnw")
                      for t in tokens)
        if not has_mvn:
            continue
        for t in tokens:
            if t in ARTIFACT_GOALS:
                found.add(t)
    return found


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
    """Return (path, mtime) of the installed artifact, or (None, None)."""
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
            return (path, os.path.getmtime(path))
    return (None, None)


def build_report(goals, project_dir, home):
    modules = find_modules(project_dir)
    rows = []
    for rel, (group, artifact, version, packaging) in modules:
        path, mtime = m2_artifact(home, group, artifact, version, packaging)
        rows.append((rel, artifact, version, mtime))

    # Oldest installed first; missing artifacts at the very top.
    rows.sort(key=lambda r: (r[3] is not None, r[3] if r[3] else 0.0))

    name_w = max((len(r[1]) for r in rows), default=10)
    lines = []
    lines.append("Maven artifact-producing goal(s) detected: " + ", ".join(sorted(goals)))
    lines.append("")
    lines.append("This invocation only builds the modules in its reactor. With `-pl <module>` "
                 "and no `-am`, upstream dependencies are NOT rebuilt; downstream modules that "
                 "depend on the built module are NOT rebuilt either. Every module outside the "
                 "reactor keeps resolving the artifact already installed in ~/.m2, so local "
                 "source edits in other modules will NOT be reflected. To pick up edits in a "
                 "dependency before testing a consumer, install the dependency first (e.g. "
                 "`mvn install -DskipTests -pl <module> -am`).")
    lines.append("")
    lines.append("Installed artifact last-modified times in ~/.m2 (oldest first):")
    for rel, artifact, version, mtime in rows:
        when = (datetime.fromtimestamp(mtime).strftime("%Y-%m-%d %H:%M:%S")
                if mtime else "NOT INSTALLED      ")
        lines.append(f"  {when}  {artifact.ljust(name_w)}  {rel}")
    return "\n".join(lines)


def main():
    payload = read_payload()
    if payload.get("tool_name") != "Bash":
        sys.exit(0)
    command = (payload.get("tool_input") or {}).get("command", "")
    if not command:
        sys.exit(0)

    goals = detect_goals(command)
    if not goals:
        sys.exit(0)

    project_dir = (os.environ.get("CLAUDE_PROJECT_DIR")
                   or payload.get("cwd")
                   or os.getcwd())
    home = os.path.expanduser("~")

    try:
        report = build_report(goals, project_dir, home)
    except Exception as e:  # never break the command on a hook error
        report = ("Maven artifact-producing goal detected, but the staleness report "
                  "could not be generated: " + repr(e))

    out = {
        "hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "additionalContext": report,
        },
        "systemMessage": ("Maven build detected: this will not rebuild dependent modules. "
                          "See the ~/.m2 artifact staleness report in context."),
    }
    print(json.dumps(out))
    sys.exit(0)


if __name__ == "__main__":
    main()
