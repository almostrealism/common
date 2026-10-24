#!/usr/bin/env python3
"""Decide whether a bash tool call is a broad pytest invocation.

Companion to mvn_test_check.py, with the same structure (tokenizer, CLI
contract, decide() shape) so the two read side by side. It applies the
"Test Execution Limits" rule of the root CLAUDE.md to pytest: a bounded
selection is an ordinary check, an unbounded one is the suite.

  allowed   explicit test files (path ending in .py) and node ids
            (file.py::test_name), at most MAX_TEST_FILES distinct files and
            MAX_NODE_IDS node ids per invocation
  blocked   no positional at all (pytest collects from the current
            directory), a directory or any other non-file positional, or a
            selection above either cap

A test file counts as one "class" against the cap, the way naming a class to
Surefire does: it bounds which tests can run without bounding how many cases
the file holds.

Same two CLI entry points as mvn_test_check.py:

  python3 pytest_broad_run_check.py <command>
      Returns the Decision as JSON on stdout, exit 0 always.

  python3 pytest_broad_run_check.py --stdin
      Reads a Claude-Code-style hook payload from stdin, computes the
      Decision, and renders natively (exit 2 + stderr on block, exit 0
      on allow). This check never warns: a command that cannot be
      tokenized is allowed, and the pytest forms it looks for are simple
      enough to parse unambiguously when it can.
"""
import json
import re
import shlex
import sys


OPERATORS = {"&&", "||", "|", "|&", ";", ";;", "&", "(", ")", "{", "}",
             "\n", "then", "do", "else", "elif", "fi", "done"}

CMD_PREFIXES = {"!", "time", "nohup", "sudo", "env", "command", "exec",
                "builtin", "stdbuf", "nice", "ionice"}

ENV_ASSIGN = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*=")

DASH_C = re.compile(r"^-[a-z]*c$")

# Matches the root CLAUDE.md caps (and execution_limits._MAX_TEST_CLASSES /
# _MAX_TEST_METHODS for Maven).
MAX_TEST_FILES = 5
MAX_NODE_IDS = 40

# Interpreter executable base names, including versioned forms such as
# "python3.11", so "python3.11 -m pytest tests/" is recognized. Mirrors
# execution_limits._PYTHON_INTERPRETER_PATTERN.
PYTHON_INTERPRETER = re.compile(r"python(?:\d+(?:\.\d+)*)?$")

# Interpreter flags that take no operand and so can precede -m, e.g.
# "python3 -O -m pytest tests/". Mirrors execution_limits._PYTHON_NOARG_FLAGS.
PYTHON_NOARG_FLAGS = {
    "-O", "-OO", "-B", "-b", "-bb", "-d", "-E", "-h", "-i", "-I",
    "-q", "-s", "-S", "-t", "-tt", "-u", "-v", "-x", "-3", "-R",
}

# pytest options whose value is the next token, so that value is not
# mistaken for a test path ("-k test_bar", "-p no:cacheprovider").
PYTEST_VALUE_OPTIONS = {
    "-k", "-m", "-p", "-c", "-o", "-W", "-n", "--rootdir", "--confcutdir",
    "--basetemp", "--deselect", "--ignore", "--ignore-glob", "--junitxml",
    "--junit-xml", "--maxfail", "--tb", "--durations", "--log-level",
    "--override-ini", "--import-mode", "--capture", "--cov", "--timeout",
}

BLOCK_REASON = (
    "BLOCKED: this pytest invocation is not a bounded test selection.\n\n"
    "Name the tests you are checking: at most {files} test files or {ids}\n"
    "node ids per invocation, for example\n"
    "  python3 -m pytest path/to/test_file.py\n"
    "  python3 -m pytest path/to/test_file.py::TestClass::test_name\n\n"
    "Reason: pytest with no path, or with a directory, collects every test\n"
    "it finds there. Broad verification belongs to CI.\n"
).format(files=MAX_TEST_FILES, ids=MAX_NODE_IDS)


def pytest_arguments(tokens):
    """Return the arguments after the pytest entry point, or None if the
    command is not pytest, py.test or "python -m pytest"."""
    if not tokens:
        return None
    base = tokens[0].rsplit("/", 1)[-1]
    if base in ("pytest", "py.test"):
        return tokens[1:]
    if PYTHON_INTERPRETER.match(base):
        k = 1
        while k < len(tokens) and tokens[k] in PYTHON_NOARG_FLAGS:
            k += 1
        if k + 1 < len(tokens) and tokens[k] == "-m" and tokens[k + 1] == "pytest":
            return tokens[k + 2:]
    return None


def positionals(args):
    """The test-path arguments, skipping options and their values."""
    result = []
    skip_next = False
    for a in args:
        if skip_next:
            skip_next = False
        elif a.startswith("-"):
            skip_next = a in PYTEST_VALUE_OPTIONS
        else:
            result.append(a)
    return result


def is_bounded(args):
    """True if every test path is a .py file or a node id, within the caps."""
    paths = positionals(args)
    if not paths:
        return False
    files = set()
    node_ids = 0
    for p in paths:
        file_part = p.split("::", 1)[0]
        if not file_part.endswith(".py"):
            return False
        files.add(file_part)
        if "::" in p:
            node_ids += 1
    return len(files) <= MAX_TEST_FILES and node_ids <= MAX_NODE_IDS


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

        pytest_args = pytest_arguments([tok] + args)
        if pytest_args is not None:
            if not is_bounded(pytest_args):
                return "block"
        elif tok.rsplit("/", 1)[-1] in {"bash", "sh", "zsh", "dash", "ksh"}:
            for k, a in enumerate(args):
                if DASH_C.match(a) and k + 1 < len(args):
                    if analyze(args[k + 1]) == "block":
                        return "block"

        i = j
        in_cmd_position = True
    return "clear"


def decide(command):
    """Return a Decision dict (the mvn_test_check.decide() shape)."""
    if command and command.strip() and analyze(command) == "block":
        return {
            "action": "block",
            "reason": BLOCK_REASON,
            "context": "",
            "stderr": BLOCK_REASON,
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
    """Render a Decision in the Claude Code hook contract."""
    if decision.get("action") == "block":
        sys.stderr.write(decision.get("reason", "") or "")
        sys.exit(2)
    sys.exit(0)


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    if argv and argv[0] == "--stdin":
        _render_harness_native(decide(_read_stdin_command()))
        return

    if not argv:
        sys.stderr.write("usage: pytest_broad_run_check.py <command> | --stdin\n")
        sys.exit(2)

    print(json.dumps(decide(argv[0])))
    sys.exit(0)


if __name__ == "__main__":
    main()
