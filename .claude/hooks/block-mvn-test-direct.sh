#!/usr/bin/env bash
# PreToolUse — Bash: block a genuine direct `mvn test` invocation.
#
# Agents must use mcp__ar-test-runner__start_test_run to run tests.
# `mvn install -DskipTests`, `mvn compile`, `mvn clean install`, `mvn
# test-compile`, etc. are allowed.
#
# This hook used to block any command that merely *contained* the substring
# "mvn ... test" — which wrongly blocked `grep 'mvn test'`, `echo "mvn test"`,
# and `python3 -c "...mvn test..."`. It now tokenizes the command (so quoted
# strings are treated as data, not invocations) and only BLOCKS when it is
# certain a real `mvn` process is being launched that will actually run tests.
# When it cannot be certain (e.g. the command can't be parsed), it does NOT
# block — it emits a recommendation to use the test runner instead.
set -euo pipefail

INPUT=$(cat)
export MVN_HOOK_INPUT="$INPUT"

python3 <<'PY'
import os, sys, json, shlex, re

raw = os.environ.get("MVN_HOOK_INPUT", "")
try:
    command = json.loads(raw).get("tool_input", {}).get("command", "") or ""
except Exception:
    # Can't even parse the hook payload — never block on uncertainty.
    sys.exit(0)

if not command.strip():
    sys.exit(0)

# Tokens that reset us to "command position" (the next word is a command name).
OPERATORS = {"&&", "||", "|", "|&", ";", ";;", "&", "(", ")", "{", "}",
             "\n", "then", "do", "else", "elif", "fi", "done"}
# Prefixes that precede a command without consuming command position.
CMD_PREFIXES = {"!", "time", "nohup", "sudo", "env", "command", "exec",
                "builtin", "stdbuf", "nice", "ionice"}
ENV_ASSIGN = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*=")
SKIP = re.compile(r"-DskipTests|-Dmaven\.test\.skip(=true)?$|-Dmaven\.test\.skip\b")
# Phases / flags that mean "tests will actually execute".
TEST_PHASES = {"test", "integration-test"}
DASH_C = re.compile(r"^-[a-z]*c$")


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
        # Unbalanced quotes / heredoc body — we cannot be certain.
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

        # `tok` is a command name. Gather its argument tokens.
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
            # Recurse into an explicit `-c "<script>"` so a wrapped
            # `bash -c "mvn test"` is still caught.
            for k, a in enumerate(args):
                if DASH_C.match(a) and k + 1 < len(args):
                    inner = analyze(args[k + 1])
                    if inner == "block":
                        return "block"

        i = j
        in_cmd_position = True
    return "clear"


status = analyze(command)

if status == "block":
    sys.stderr.write(
        "BLOCKED: Direct 'mvn test' is not permitted for agents.\n\n"
        "Use the MCP test runner:\n"
        "  mcp__ar-test-runner__start_test_run\n"
        "    module: \"<module>\"\n"
        "    test_classes: [\"MyTest\"]\n\n"
        "Reason: Direct mvn test bypasses the controlled environment,\n"
        "runs at the wrong test depth, and produces unreliable results.\n"
    )
    sys.exit(2)

if status == "uncertain" and "mvn" in command and "test" in command:
    # Not certain this launches mvn tests, so don't block — just advise.
    note = ("This command couldn't be parsed well enough to confirm whether it "
            "launches `mvn test`. If you intend to run tests, use "
            "mcp__ar-test-runner__start_test_run instead of invoking mvn "
            "directly (it sets up the environment and correct test depth).")
    sys.stderr.write(note + "\n")
    print(json.dumps({
        "hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "additionalContext": note,
        }
    }))
    sys.exit(0)

sys.exit(0)
PY
