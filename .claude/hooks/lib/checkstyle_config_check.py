#!/usr/bin/env python3
"""Decide whether a tool call is an attempt to modify a checkstyle configuration.

This module is the single source of truth for the "block edits to checkstyle
configuration" policy. It is invoked by:

  - .claude/hooks/block-checkstyle-edit.sh     (Claude Code, --stdin)
  - .opencode/plugins/block-checkstyle-edit.ts  (opencode, argv)

A checkstyle configuration is any file the maven-checkstyle-plugin or
similar tooling loads to define which rules to enforce. The two shapes
agents have used in past sessions to make violations disappear are:

  1. The main configuration file (checkstyle.xml at the repo root or in
     a module-local override).
  2. Suppression / exemption files (e.g. checkstyle-suppressions.xml,
     inline SuppressionFilter / SuppressionSingleFilter entries inside
     checkstyle.xml, or any *checkstyle*suppress*.xml file).

Both shapes are covered by the same matcher in `is_checkstyle_path`:
anything under a `/checkstyle/` directory, or any file whose name
contains both `checkstyle` and `suppress` and ends in `.xml`, in
addition to the canonical `checkstyle.xml` and
`checkstyle-suppressions.xml` basenames.

Two CLI entry points:

  python3 checkstyle_config_check.py <json>
      Used by the .ts adapter. <json> is a JSON object with `tool`
      (one of "bash", "write", "edit", "multiedit", "notebookedit")
      and either `command` (for bash) or `filePath`/`file_path`
      (for the file tools). Returns the Decision as a JSON object on
      stdout, exit 0 always. The .ts adapter does the harness-native
      rendering (throw on block).

  python3 checkstyle_config_check.py --stdin
      Used by the .sh adapter. Reads a Claude-Code-style hook payload
      from stdin (`{"tool_name": "...", "tool_input": {...}}`),
      computes the Decision, and renders natively (exit 2 + stderr
      on block, exit 0 on allow).

The Decision shape (always JSON):

    {
      "action":  "block" | "allow",
      "reason":  "str",   # shown to the model on block; printed to stderr
      "context": "str",   # unused (this policy is binary, no warn path)
      "stderr":  "str",   # always printed to stderr for the human
    }

The block message is intentionally written in ALL CAPS so the agent
cannot mistake it for ordinary guidance. See BLOCK_REASON below.
"""
import json
import os
import re
import shlex
import sys


# ---------------------------------------------------------------------------
# Path matching
# ---------------------------------------------------------------------------

# A path is a checkstyle configuration if its basename is one of the
# canonical filenames, or if it lives in a `/checkstyle/` directory, or
# if its name contains both `checkstyle` and `suppress` (covering
# module-local variants like `engine-ml-checkstyle-suppressions.xml`).
#
# Comparison is case-insensitive because some filesystems (e.g. macOS
# HFS+ default, Windows) treat CHECKSTYLE.XML the same as
# checkstyle.xml; the maven-checkstyle-plugin is also case-tolerant
# when resolving configLocation.
EXACT_BASENAMES = {
    "checkstyle.xml",
    "checkstyle-suppressions.xml",
    "checkstyle_checks.xml",
    "checkstyle-config.xml",
}


def is_checkstyle_path(path):
    """True if `path` is the path of a checkstyle configuration file.

    `path` may be absolute, relative, or `~`-expanded. Returns False
    on empty/None input. The match is case-insensitive on the
    basename and on the directory segment.
    """
    if not path:
        return False
    p = os.path.expanduser(str(path)).replace("\\", "/")
    if not p:
        return False
    lower = p.lower()
    basename = os.path.basename(lower)

    if basename in EXACT_BASENAMES:
        return True

    # Files inside a /checkstyle/ directory.  We check for the
    # directory segment specifically (rather than a substring of
    # `checkstyle`) so that a file called `mycheckstyle-rules.xml`
    # outside a checkstyle/ directory does not match by accident.
    if "/checkstyle/" in lower and lower.endswith(".xml"):
        return True

    # Filenames that combine `checkstyle` and `suppress` and end in
    # .xml — covers `module-checkstyle-suppressions.xml`,
    # `checkstyle-suppressions-legacy.xml`, etc.
    if basename.endswith(".xml") and "checkstyle" in basename and "suppress" in basename:
        return True

    return False


# ---------------------------------------------------------------------------
# Bash command analysis
# ---------------------------------------------------------------------------

# The minimum write-mutate signal: a redirect, a tee, an in-place sed,
# or a copy/move/install whose destination could be the checkstyle
# file.  We deliberately err on the side of *more* matches here
# (matching `cat >` redundantly with the `>` pattern, for example)
# because false positives cost the agent one extra "that command
# wasn't actually a write" message whereas a false negative is the
# cheat going through.
BASH_WRITE_HINT = re.compile(
    r"(?:"
    r"(?<!['\"])>>(?!['\"])"    # append redirect (whitespace optional)
    r"|(?<!['\"])>(?!['\"])"    # write redirect (whitespace optional)
    r"|\btee\b"                   # tee
    r"|\bsed\s+-i\b"              # in-place sed
    r"|\bdd\s+of="                 # dd output file
    r"|\bcp\s"                     # copy
    r"|\bmv\s"                     # move / rename
    r"|\binstall\s"                # install(1) copy
    r"|\bcat\s+>"                  # cat into redirect
    r"|\bcat\s+>>"                 # cat into append
    r")"
)


def _tokens_that_could_be_paths(command):
    """Yield every shlex token from `command` that could be a path.

    A token "could be a path" if it ends in `.xml` or if it is
    recognized as a redirect target (the argument to `>`, `>>`, or
    `<>`).  We also strip any surrounding quotes so that
    `> "checkstyle.xml"` and `> checkstyle.xml` both match.
    """
    try:
        tokens = shlex.split(command, comments=False, posix=True)
    except ValueError:
        # Unbalanced quotes.  Fall back to whitespace splitting —
        # slightly noisier but never silent.
        tokens = command.split()

    for tok in tokens:
        clean = tok.strip("'\"")
        if not clean:
            continue
        lower = clean.lower()
        if lower.endswith(".xml"):
            yield clean
        # Handle key=value tokens like `of=checkstyle.xml` (e.g. dd of=...)
        if "=" in clean:
            _, value = clean.split("=", 1)
            if value and value.lower().endswith(".xml"):
                yield value.strip("'\"")
        # A redirect target is the token after `>`, `>>`, `<>`, `2>`, `2>>`, etc.
    # Walk the raw command again to catch redirect targets that shlex
    # may have detached (e.g. `>  checkstyle.xml` with extra spaces).
    for m in re.finditer(r"(?:\d?>>|\d?>|<>)\s*(\S+)", command):
        yield m.group(1).strip("'\"")

def bash_command_targets_checkstyle(command):
    """True if `command` references any checkstyle config file path.

    Does NOT consider the *intent* (read vs. write) — see
    `bash_command_writes_checkstyle` for the full check.
    """
    if not command or not command.strip():
        return False
    for candidate in _tokens_that_could_be_paths(command):
        if is_checkstyle_path(candidate):
            return True
    return False


def bash_command_writes_checkstyle(command):
    """True if `command` both references a checkstyle config AND has a
    write/mutate intent (redirect, tee, sed -i, cp, mv, etc.).

    The "AND" is important: a `cat checkstyle.xml` (read) is fine,
    a `grep checkstyle.xml` (search) is fine, but a `> checkstyle.xml`
    (overwrite) is the cheat and must be blocked.
    """
    if not command or not command.strip():
        return False
    if not bash_command_targets_checkstyle(command):
        return False
    return bool(BASH_WRITE_HINT.search(command))


# ---------------------------------------------------------------------------
# Top-level decide
# ---------------------------------------------------------------------------

BLOCK_REASON_TEMPLATE = (
    "BLOCKED: MODIFYING CHECKSTYLE CONFIGURATION IS FORBIDDEN.\n"
    "\n"
    "THIS INCLUDES:\n"
    "  - checkstyle.xml (root or any module-local copy)\n"
    "  - Any checkstyle suppression file (checkstyle-suppressions.xml,\n"
    "    or any *checkstyle*suppress*.xml, or any .xml under a\n"
    "    checkstyle/ directory)\n"
    "  - Any other file the maven-checkstyle-plugin would load\n"
    "\n"
    "ADDING EXEMPTIONS, SUPPRESSIONS, OR DISABLING/RELAXING RULES IS\n"
    "NEVER ACCEPTABLE. THERE IS NO EXCUSE. AN EXEMPTION YOU ADD IS NOT\n"
    "A FIX, IT IS A CHEAT.\n"
    "\n"
    "IF THE TASK SEEMS IMPOSSIBLE WITHOUT MODIFYING CHECKSTYLE:\n"
    "  - YOU MUST ABANDON THE TASK\n"
    "  - DECLARE IT IMPOSSIBLE\n"
    "  - REPORT WHAT YOU TRIED AND WHY IT COULD NOT COMPLY\n"
    "\n"
    "DECLARING FAILURE IS ALWAYS PREFERABLE TO TAMPERING WITH\n"
    "ENFORCEMENT. ABANDONING THE TASK IS THE SANCTIONED, EXPECTED,\n"
    "CORRECT OUTCOME -- NOT A LAST RESORT.\n"
    "\n"
    "THE CORRECT ACTION IS TO FIX THE CODE TO COMPLY WITH THE RULES,\n"
    "OR STOP.\n"
    "\n"
    "Target: {target}\n"
    "Tool:   {tool}\n"
)


def _format_block_reason(tool, target):
    return BLOCK_REASON_TEMPLATE.format(tool=tool or "<unknown>", target=target or "<unknown>")


def decide(tool, tool_input):
    """Return a Decision dict for a (tool, tool_input) pair.

    `tool` is the tool name (case-insensitive). Recognized values:
      - "bash"          : tool_input["command"] is the shell command
      - "write"         : tool_input["file_path"] / tool_input["filePath"]
      - "edit"          : tool_input["file_path"] / tool_input["filePath"]
      - "multiedit"     : tool_input["file_path"] / tool_input["filePath"]
      - "notebookedit"  : tool_input["file_path"] / tool_input["filePath"]

    For unknown tool names, returns "allow" with a non-empty stderr
    so a typo in the adapter cannot accidentally block the agent.
    """
    if tool is None:
        tool = ""
    if tool_input is None:
        tool_input = {}
    tool_lc = str(tool).strip().lower()

    if tool_lc in ("write", "edit", "multiedit", "notebookedit"):
        path = (
            tool_input.get("file_path")
            or tool_input.get("filePath")
            or tool_input.get("filepath")
            or ""
        )
        if not path:
            return {"action": "allow", "reason": "", "context": "", "stderr": ""}
        if is_checkstyle_path(path):
            reason = _format_block_reason(tool_lc, path)
            return {
                "action": "block",
                "reason": reason,
                "context": "",
                "stderr": reason,
            }
        return {"action": "allow", "reason": "", "context": "", "stderr": ""}

    if tool_lc == "bash":
        command = tool_input.get("command") or ""
        if not command.strip():
            return {"action": "allow", "reason": "", "context": "", "stderr": ""}
        if bash_command_writes_checkstyle(command):
            reason = _format_block_reason("bash", command)
            return {
                "action": "block",
                "reason": reason,
                "context": "",
                "stderr": reason,
            }
        return {"action": "allow", "reason": "", "context": "", "stderr": ""}

    return {
        "action": "allow",
        "reason": "",
        "context": "",
        "stderr": f"checkstyle_config_check: unknown tool {tool!r}",
    }


def decide_from_payload(payload):
    """Decide from a unified payload shape used by the .ts adapter.

    `payload` is a dict with at least `tool`, and either `command`
    (for bash) or `filePath`/`file_path` (for file tools).
    """
    if not isinstance(payload, dict):
        return {"action": "allow", "reason": "", "context": "", "stderr": "non-dict payload"}
    tool = payload.get("tool") or ""
    # Accept Claude-Code-style (file_path) and opencode-style
    # (filePath) key names for the file tools.
    normalized = {
        "command": payload.get("command") or "",
        "file_path": (
            payload.get("file_path")
            or payload.get("filePath")
            or payload.get("filepath")
            or ""
        ),
    }
    return decide(tool, normalized)


# ---------------------------------------------------------------------------
# CLI / --stdin adapters
# ---------------------------------------------------------------------------

def _read_stdin_payload():
    """Read a Claude-Code-style hook payload from stdin and return
    (tool_name, tool_input)."""
    raw = sys.stdin.read()
    try:
        data = json.loads(raw)
    except Exception:
        return ("", {})
    if not isinstance(data, dict):
        return ("", {})
    tool = data.get("tool_name") or data.get("tool") or ""
    tool_input = data.get("tool_input") or {}
    if not isinstance(tool_input, dict):
        tool_input = {}
    return (tool, tool_input)


def _render_harness_native(decision):
    """Render a Decision in the Claude Code hook contract (exit code + stderr)."""
    action = decision.get("action")
    reason = decision.get("reason", "") or ""
    stderr_msg = decision.get("stderr", "") or ""

    if action == "block":
        sys.stderr.write(reason)
        sys.exit(2)

    if stderr_msg:
        sys.stderr.write(stderr_msg)
    sys.exit(0)


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    if argv and argv[0] == "--stdin":
        tool, tool_input = _read_stdin_payload()
        _render_harness_native(decide(tool, tool_input))
        return

    if not argv:
        sys.stderr.write(
            "usage: checkstyle_config_check.py '<json-payload>' | --stdin\n"
        )
        sys.exit(2)

    # argv mode: a single JSON payload string.
    raw = argv[0]
    try:
        payload = json.loads(raw)
    except Exception as e:
        sys.stderr.write(f"checkstyle_config_check: invalid JSON payload: {e}\n")
        sys.exit(2)
    print(json.dumps(decide_from_payload(payload)))
    sys.exit(0)


if __name__ == "__main__":
    main()
