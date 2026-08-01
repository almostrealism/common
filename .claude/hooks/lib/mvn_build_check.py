#!/usr/bin/env python3
"""Decide whether a bash tool call is an "is the build clean?" mvn invocation.

This module is the single source of truth for the soft steer that
nudges agents toward ``ar-build-validator`` for build-cleanliness
checks. It is invoked by:

  - .opencode/plugins/warn-mvn-build.ts (opencode, via argv)

The Claude hook ``.claude/hooks/mvn-artifact-staleness.py`` already
emits a richer staleness report for any artifact-producing Maven
goal; this module is the opencode-side counterpart that produces a
short, output-mutation-friendly steer instead. Both hooks coexist:
the Claude staleness report stays as a full table injected as
``additionalContext``; this short note is what opencode appends to
the bash output stream after the command runs.

Two CLI entry points, mirroring ``mvn_test_check.py``:

  python3 mvn_build_check.py <command>
      Used by the .ts adapter. Returns the Decision as a JSON object
      on stdout, exit 0 always. The .ts adapter does the
      harness-native rendering (append-to-output on warn).

  python3 mvn_build_check.py --stdin
      Reserved for a future Claude-Code shell adapter if one is
      ever needed. Reads a Claude-Code-style hook payload from
      stdin and renders the steer (no-op for now; the existing
      staleness hook already covers Claude).

The Decision shape (always JSON):

    {
      "action":  "warn" | "allow",
      "reason":  "str",   # unused (we never block here)
      "context": "str",   # injected into the model's next turn on warn
      "stderr":  "str",   # printed to stderr for the human
    }

The intent of the steer is light-touch: it never blocks, never
modifies command flags, and never interferes with valid uses of
``mvn install -DskipTests`` (e.g., dependency seeding, packaging
for downstream consumers, CI setup). It only reminds the agent
that the structured ``ar-build-validator`` MCP tool is a better
fit than ``mvn -q | tail`` for "is the build clean?" checks.
"""

import json
import re
import shlex
import sys


# Maven lifecycle goals that produce / install / compile artifacts.
# This list mirrors ``mvn-artifact-staleness.py``'s ``ARTIFACT_GOALS``.
ARTIFACT_GOALS = frozenset({
    "compile",
    "test-compile",
    "testCompile",
    "process-classes",
    "process-test-classes",
    "package",
    "verify",
    "install",
    "deploy",
})


# Tokens that name a Maven launcher. ``./mvnw`` and ``mvnw`` are
# treated the same as ``mvn``.
MVN_TOKENS = frozenset({"mvn", "mvnw", "./mvnw", "mvn.cmd"})


WARN_NOTE = (
    "This invocation runs an artifact-producing Maven goal "
    "({goals}). For a structured \"is the build clean?\" check, prefer "
    "the ar-build-validator MCP tool — it returns per-check pass/fail "
    "and structured violation counts, instead of free-form log tail.\n\n"
    "  mcp__ar-build-validator__start_validation\n"
    "    checks: [\"checkstyle\"]               # fastest: no build at all\n"
    "    # OR omit checks for the default suite (checkstyle, code_policy,\n"
    "    # test_timeouts, duplicate_code), which runs `mvn install "
    "-DskipTests` once and then each check.\n\n"
    "Use `skip_build: true` when the project is already compiled and "
    "you only changed source files. Bare `mvn install`/`mvn compile` is "
    "fine when you genuinely need to seed local artifacts or build "
    "downstream artifacts — this note is informational only and does "
    "not block the command."
)


def _detect_artifact_goals_in_tokens(tokens):
    """Return artifact goals found in a token list (one shell segment)."""
    if not tokens:
        return set()
    has_mvn = any(
        t in MVN_TOKENS or t.endswith("/mvn") or t.endswith("/mvnw")
        for t in tokens
    )
    if not has_mvn:
        return set()
    return {t for t in tokens if t in ARTIFACT_GOALS}


def detect_artifact_goals(command):
    """Return the set of artifact-producing Maven goals in ``command``.

    The command is split into shell segments using a conservative
    splitter so chained commands like ``npm install && mvn install``
    correctly attribute the ``install`` goal to the ``mvn`` segment
    rather than the ``npm`` one. Each segment is tokenised with
    :mod:`shlex`; segments that fail to parse are split on whitespace
    as a fallback (the original ``mvn-artifact-staleness.py``
    behaviour).
    """
    if not command:
        return set()
    found = set()
    for segment in re.split(r"(?:\|\||&&|[;&|\n])", command):
        try:
            tokens = shlex.split(segment)
        except ValueError:
            tokens = segment.split()
        found.update(_detect_artifact_goals_in_tokens(tokens))
    return found


def decide(command):
    """Return a Decision dict for a command string.

    A non-empty set of artifact goals produces ``action="warn"`` with
    the steer message in ``context`` and ``stderr``. Anything else
    produces ``action="allow"`` with empty fields.

    The function is intentionally pure: same input, same output.
    """
    goals = detect_artifact_goals(command or "")
    if not goals:
        return {"action": "allow", "reason": "", "context": "", "stderr": ""}

    note = WARN_NOTE.format(goals=", ".join(sorted(goals)))
    return {
        "action": "warn",
        "reason": "",
        "context": note,
        "stderr": note,
    }


# --------------------------------------------------------------------- #
# CLI entry points
# --------------------------------------------------------------------- #


def _read_stdin_command():
    """Read a Claude-Code-style hook payload from stdin."""
    raw = sys.stdin.read()
    try:
        payload = json.loads(raw)
    except Exception:
        return ""
    return (payload.get("tool_input", {}) or {}).get("command", "") or ""


def _render_harness_native(decision):
    """Render a Decision in the Claude Code hook contract.

    Always exits 0 — this hook never blocks. On warn, emits both the
    stderr banner (for the human) and a ``hookSpecificOutput`` JSON
    block on stdout (for the model's next turn).
    """
    action = decision.get("action")
    context = decision.get("context", "") or ""
    stderr_msg = decision.get("stderr", "") or ""

    if action == "warn":
        if stderr_msg:
            sys.stderr.write(stderr_msg + "\n")
        print(json.dumps({
            "hookSpecificOutput": {
                "hookEventName": "PreToolUse",
                "additionalContext": context,
            }
        }))
        sys.exit(0)

    sys.exit(0)


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    if argv and argv[0] == "--stdin":
        command = _read_stdin_command()
        _render_harness_native(decide(command))
        return

    if not argv:
        sys.stderr.write("usage: mvn_build_check.py <command> | --stdin\n")
        sys.exit(2)

    command = argv[0]
    print(json.dumps(decide(command)))
    sys.exit(0)


if __name__ == "__main__":
    main()
