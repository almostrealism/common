#!/usr/bin/env python3
"""Decide whether a bash tool call is a direct `mvn test` invocation.

This module is the single source of truth for that decision. It is
invoked by:

  - .claude/hooks/block-mvn-test-direct.sh   (Claude Code, via --stdin)
  - .opencode/plugins/block-mvn-test-direct.ts (opencode, via argv)

The decision logic is the analysis that used to live inline in
.block-mvn-test-direct.sh, extracted into a pure function so both
harnesses share one implementation.

Two CLI entry points:

  python3 mvn_test_check.py <command>
      Used by the .ts adapter. Returns the Decision as a JSON object
      on stdout, exit 0 always. The .ts adapter does the
      harness-native rendering (throw on block, mutate output.output
      on warn).

  python3 mvn_test_check.py --stdin
      Used by the .sh adapter. Reads a Claude-Code-style hook
      payload from stdin (one JSON object: {"tool_input":
      {"command": "..."}}), computes the Decision, and renders
      natively (exit 2 + stderr on block, exit 0 + JSON
      additionalContext on warn, exit 0 on allow).

The Decision shape (always JSON, no trailing newline is required but
recommended for readability):

    {
      "action":  "block" | "allow" | "warn",
      "reason":  "str",   # shown to the model on block; printed to stderr
      "context": "str",   # injected into the model's next turn on warn
      "stderr":  "str",   # always printed to stderr for the human
    }

The `--stdin` mode writes the `reason`/`context` strings directly to
the harness's expected channels (stderr for the block reason;
stderr + stdout JSON for the warn context) rather than reformatting
them, so the .sh adapter stays a one-liner. The argv mode emits the
Decision JSON so the .ts adapter can choose its own rendering.
"""
import json
import os
import re
import shlex
import sys


OPERATORS = {"&&", "||", "|", "|&", ";", ";;", "&", "(", ")", "{", "}",
             "\n", "then", "do", "else", "elif", "fi", "done"}

CMD_PREFIXES = {"!", "time", "nohup", "sudo", "env", "command", "exec",
                "builtin", "stdbuf", "nice", "ionice"}

ENV_ASSIGN = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*=")

SKIP = re.compile(r"-DskipTests|-Dmaven\.test\.skip(=true)?$|-Dmaven\.test\.skip\b")

TEST_PHASES = {"test", "integration-test"}

DASH_C = re.compile(r"^-[a-z]*c$")

BLOCK_REASON = (
    "BLOCKED: Direct 'mvn test' is not permitted for agents.\n\n"
    "Use the MCP test runner:\n"
    "  mcp__ar-test-runner__start_test_run\n"
    "    module: \"<module>\"\n"
    "    test_classes: [\"MyTest\"]\n\n"
    "Reason: Direct mvn test bypasses the controlled environment,\n"
    "runs at the wrong test depth, and produces unreliable results.\n"
)

WARN_NOTE = (
    "This command couldn't be parsed well enough to confirm whether it "
    "launches `mvn test`. If you intend to run tests, use "
    "mcp__ar-test-runner__start_test_run instead of invoking mvn "
    "directly (it sets up the environment and correct test depth)."
)


def runs_tests(args):
    """True if these mvn arguments will execute tests and aren't skipped."""
    if any(SKIP.search(a) for a in args):
        return False
    return any(a in TEST_PHASES or a.startswith("-Dtest=") for a in args)


def analyze(cmd):
    """Return 'block', 'uncertain', or 'clear' for a command string."""
    try:
        tokens = shlex.split(cmd, comments=False, posix=True)
    except ValueError:
        return "uncertain"

    i, n = 0, len(tokens)
    in_cmd_position = True
    while i < n:
        tok = tokens[i]
        if tok in OPERATORS:
            in_cmd_position = True
            i += 1
            continue
        if in_cmd_position and (ENV_ASSIGN.match(tok) or tok in CMD_PREFIXES):
            i += 1
            continue
        if not in_cmd_position:
            i += 1
            continue

        j = i + 1
        args = []
        while j < n and tokens[j] not in OPERATORS:
            args.append(tokens[j])
            j += 1

        base = tok.rsplit("/", 1)[-1]
        if base == "mvn":
            if runs_tests(args):
                return "block"
        elif base in {"bash", "sh", "zsh", "dash", "ksh"}:
            for k, a in enumerate(args):
                if DASH_C.match(a) and k + 1 < len(args):
                    inner = analyze(args[k + 1])
                    if inner == "block":
                        return "block"

        i = j
        in_cmd_position = True
    return "clear"


def decide(command):
    """Return a Decision dict for a command string.

    The result is harness-neutral; each adapter renders it in its own
    native way (exit code + stderr for Claude Code; throw / mutate
    output.output for opencode).
    """
    if not command or not command.strip():
        return {"action": "allow", "reason": "", "context": "", "stderr": ""}

    status = analyze(command)

    if status == "block":
        return {
            "action": "block",
            "reason": BLOCK_REASON,
            "context": "",
            "stderr": BLOCK_REASON,
        }

    if status == "uncertain" and "mvn" in command and "test" in command:
        return {
            "action": "warn",
            "reason": "",
            "context": WARN_NOTE,
            "stderr": WARN_NOTE,
        }

    return {"action": "allow", "reason": "", "context": "", "stderr": ""}


def _read_stdin_command():
    """Read a Claude-Code-style hook payload from stdin and return the command."""
    raw = sys.stdin.read()
    try:
        payload = json.loads(raw)
    except Exception:
        return ""
    return (payload.get("tool_input", {}) or {}).get("command", "") or ""


def _render_harness_native(decision):
    """Render a Decision in the Claude Code hook contract (exit code + stderr + stdout JSON)."""
    action = decision.get("action")
    reason = decision.get("reason", "") or ""
    context = decision.get("context", "") or ""
    stderr_msg = decision.get("stderr", "") or ""

    if action == "block":
        sys.stderr.write(reason)
        sys.exit(2)

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
        sys.stderr.write("usage: mvn_test_check.py <command> | --stdin\n")
        sys.exit(2)

    command = argv[0]
    print(json.dumps(decide(command)))
    sys.exit(0)


if __name__ == "__main__":
    main()
