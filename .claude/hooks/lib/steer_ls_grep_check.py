#!/usr/bin/env python3
"""Decide whether a bash tool call is a simple `ls` or `grep`/`rg`
invocation that should be steered to the structured `glob` / `grep`
tools.

This module is the single source of truth for the "steer bash `ls`/`grep`
to structured tools" policy. It is invoked by:

  - .claude/hooks/steer-ls-grep.sh       (Claude Code, --stdin)
  - .opencode/plugins/steer-ls-grep.ts   (opencode, argv)

The decision is a HARD BLOCK on simple substitutable uses:

  - `ls <path>` with no flags that change output (no `-l`, `-la`, `-lh`,
    `-F`, etc.) and a single positional path argument: steered to the
    `glob` tool. The structured `glob` tool returns path objects
    instead of whitespace-separated strings, supports include/exclude
    patterns, and skips the agent's temptation to chain `ls` with
    `head`/`grep` post-processing.

  - `grep <pattern> <file>` (or `rg <pattern> <file>`) with no pipes,
    no xargs, no perl regex (`-P`), no fixed-string-only mismatch,
    no `--include`/`--exclude` (i.e. the case the structured `grep`
    tool already handles cleanly): steered to the structured `grep`
    tool. The structured tool returns ripgrep-style
    `path:line:content` matches, which the model can cite directly.

We deliberately do NOT block compound shell features the structured
tools cannot express:

  - `ls -la`, `ls -lh`, `ls -lR` (output-format flags the agent may
    legitimately want) are allowed.
  - `ls -laR | head` (piped to other tools) is allowed.
  - `grep -P ...` (perl regex, unsupported by the structured grep
    tool) is allowed.
  - `grep ... | xargs ...` (multi-stage processing) is allowed.
  - `grep -r ...` (simple recursive search) is blocked when it is a
    substitutable use of the structured `grep` tool.
  - `grep --include ...` / `--exclude` (filters the structured tool
    doesn't expose): allowed.
    doesn't expose): allowed.

The hard block is intentional. The retros repeatedly show that
soft warnings do not change behavior — agents using `bash ls` know
the `glob` tool exists, know `bash grep` is suboptimal, and still
fall back to bash unless blocked. The lesson: a soft warn is
documentation; a hard block is policy. See the workstream's
self-improvement memories for the underlying evidence.

Two CLI entry points:

  python3 steer_ls_grep_check.py <command>
      Used by the .ts adapter. Returns the Decision as a JSON
      object on stdout, exit 0 always. The .ts adapter does the
      harness-native rendering (throw on block).

  python3 steer_ls_grep_check.py --stdin
      Used by the .sh adapter. Reads a Claude-Code-style hook
      payload from stdin (one JSON object: {"tool_input":
      {"command": "..."}}), computes the Decision, and renders
      natively (exit 2 + stderr on block, exit 0 on allow).

The Decision shape (always JSON):

    {
      "action":  "block" | "allow",
      "reason":  "str",   # shown to the model on block; printed to stderr
      "context": "str",   # unused (this policy is binary, no warn path)
      "stderr":  "str",   # always printed to stderr for the human
    }
"""
import json
import re
import shlex
import sys


# ---------------------------------------------------------------------------
# Block message
# ---------------------------------------------------------------------------

BLOCK_REASON_TEMPLATE = (
    "BLOCKED: {tool} is a simple substitutable use of a structured tool.\n"
    "\n"
    "Use the structured tool instead:\n"
    "  - For directory listing / file matching, use the `glob` tool.\n"
    "  - For text search, use the `grep` tool.\n"
    "\n"
    "The structured tools return ripgrep-style `path:line:content` rows\n"
    "and structured path objects that you can cite directly. `bash` is\n"
    "only needed when you need shell features the structured tools cannot\n"
    "express (pipes, xargs, perl-regex `-P`, output-format flags, etc.).\n"
    "\n"
    "Command: {command}\n"
)


# ---------------------------------------------------------------------------
# ls detection
# ---------------------------------------------------------------------------

# Flags that change `ls` output beyond a simple name list — when
# any of these is present, the agent has a legitimate need for the
# shell's `ls` (e.g. permissions, sizes, timestamps, recursive walk,
# human-readable sizes, file-type indicator, inode, sort-by-time,
# color, etc.) and we should NOT block.
#
# We intentionally do NOT include `-a` (show hidden) or `-1` (one
# per line) in this set. The structured `glob` tool's include
# patterns cover hidden files (a leading-dot path component) and
# returns a list of paths, so even those cases are substitutable.
LS_FORMAT_FLAGS = frozenset({
    "-l",        # long format
    "--long",
    "-h",        # human-readable sizes (almost always with -l)
    "--human-readable",
    "-F", "--classify",   # append file-type indicator
    "-R", "--recursive",
    "-d", "--directory",  # list directories themselves, not contents
    "-i", "--inode",
    "-n", "--numeric-uid-gid",
    "-o",        # like -l but no group
    "-g",        # like -l but no owner
    "--time", "--time-style", "--full-time",
    "-t",        # sort by time
    "-S",        # sort by size
    "-X",        # sort by extension
    "-r",        # reverse sort
    "--color", "--color=always", "--color=auto", "--color=never",
    "-c",        # sort by ctime / show ctime
    "-u",        # sort by atime / show atime
    "--author",
    "--block-size",
    "-Z", "--context",     # SELinux context
    "-s", "--size",        # print block size
    "--quoting-style",
    "--indicator-style",
    "--format",             # explicit format word
    "-k",        # block-size in KB
    "-B", "--binary-prefix",
    "-w", "--width",
    "--tabsize",
    "-p",                  # append slash to dirs
    "-A", "--almost-all",
    "-T", "--tabsize",
})


def _strip_ls_flag(token):
    """Return the flag portion of an `ls` short-form flag cluster.

    `ls -la` is one token. The structured tools cover `-a`, so
    the only thing that would block is `-l` (or any of the
    format-changing flags in LS_FORMAT_FLAGS). We treat the
    token as "has a format flag" if ANY of its characters
    appear in a format flag.

    This is a permissive check: if `-l` is anywhere in the
    cluster, the command is allowed through. We never block
    silently — an allow is the safe fallback.
    """
    if not token.startswith("-"):
        return None
    if token.startswith("--"):
        return token  # long form
    # Short cluster: -la, -lh, etc.
    return token


def _ls_has_format_flag(tokens):
    """True if any token in `tokens` is an `ls` flag that changes output.

    The check handles three forms:
      1. `-l` (single short flag)
      2. `-la` (clustered short flags)
      3. `--long` (long form)
    """
    for tok in tokens:
        if not tok.startswith("-"):
            continue
        if tok in LS_FORMAT_FLAGS:
            return True
        # Clustered short flag: e.g. `-la`, `-lh`, `-lA`.
        if tok.startswith("--"):
            # `--color=always` is in the set as a literal; we already
            # handled that above. For other `--<name>` forms, we
            # don't bother parsing — none of the substitutable `ls`
            # uses need long flags.
            continue
        # Short cluster (e.g. -la): check each character against the
        # first letter of every short flag in LS_FORMAT_FLAGS.
        chars = set(tok[1:])
        short_firsts = {f[1] for f in LS_FORMAT_FLAGS if f.startswith("-") and not f.startswith("--") and len(f) == 2}
        if chars & short_firsts:
            return True
    return False


def _is_simple_ls(tokens):
    """True if the token sequence is a simple substitutable `ls`.

    "Simple" = one `ls` invocation (no `&&`/`||`/`;`/pipe/etc.),
    no format-changing flags, and at most one positional argument
    (a path or none).
    """
    if not tokens:
        return False
    if tokens[0] not in ("ls", "/bin/ls", "/usr/bin/ls"):
        return False
    rest = tokens[1:]
    if _ls_has_format_flag(rest):
        return False
    # Allow at most one positional path argument. Bare `ls` with no
    # args is a list-cwd case — also substitutable by `glob("*")`.
    positional = [t for t in rest if not t.startswith("-")]
    if len(positional) > 1:
        return False
    return True


# ---------------------------------------------------------------------------
# grep / rg detection
# ---------------------------------------------------------------------------

# Flags that prevent the simple-substitute case. Any of these in a
# `grep`/`rg` command means the agent has a legitimate need for the
# shell form (perl-regex, recursive walk with custom filters, fixed
# strings, count-only, byte-offset, etc.).
GREP_RG_SKIP_FLAGS = frozenset({
    "-P", "--perl-regexp",          # perl regex — structured tool uses ripgrep
    "--pcre2",                      # ripgrep's PCRE2 flavor — same reason
    "-F", "--fixed-strings",        # fixed strings (structured tool supports, but skip in this policy)
    "-Z", "--null",
    "-c", "--count",                # count-only output
    "--include", "--exclude", "--exclude-dir",
    "-L", "--files-without-match",
    "-l", "--files-with-matches",   # files-only output (similar to count)
    "--binary-files",
    "--label",
    "-T", "--initial-tab",
    "-A", "-B", "-C",               # context lines (output post-processing)
    "--before-context", "--after-context",
    "-m", "--max-count",
    "--mmap",
    "-d", "--directories",
    "--devices",
    "-D", "--devices=skip",
    "-o", "--only-matching",        # byte offsets
    "-q", "--quiet", "--silent",
    "-b", "--byte-offset",
    "-H", "--with-filename",
    "-I",                        # ignore binary
    "--line-buffered",
    "--null-data",
})


# Tokens that are valid positional arguments after a flag, e.g.
# `-e <pattern>`, `-f <file>`. The structured grep tool takes ONE
# pattern arg; anything more elaborate is a legitimate shell use.
def _grep_has_skip_flag(tokens):
    """True if any token in `tokens` is a flag that disqualifies the
    simple-substitute case.

    Handles three forms:
      1. `--pcre2`               (bare long form)
      2. `--include=*.java`      (long form with `=value`)
      3. `-P`                    (bare short form)
    """
    for tok in tokens:
        if tok in GREP_RG_SKIP_FLAGS:
            return True
        # Long-form with `=value`: `--include=*.java` matches the
        # prefix `--include`.
        if "=" in tok and tok.startswith("--"):
            prefix = tok.split("=", 1)[0]
            if prefix in GREP_RG_SKIP_FLAGS:
                return True
    return False


def _strip_flag_value(tokens, flag):
    """Return the tokens that follow a value-taking flag.

    For each occurrence of `flag` in `tokens`, the next token
    (if any) is a value, not a separate arg. The simple-grep
    detection counts only the *non-value* tokens.
    """
    out = []
    i = 0
    while i < len(tokens):
        tok = tokens[i]
        out.append(tok)
        if tok == flag and i + 1 < len(tokens):
            # Skip the next token (the value).
            i += 2
            continue
        i += 1
    return out


def _is_simple_grep_like(tokens, base):
    """True if the token sequence is a simple substitutable `grep` or `rg`.

    "Simple" = one grep/rg invocation, no operator or pipe operators
    in the body, no skip flags, at most one pattern positional,
    and at least one file positional (so we know the agent
    wasn't pattern-matching against stdin).
    """
    if not tokens:
        return False
    if base not in ("grep", "egrep", "fgrep", "rg"):
        return False
    rest = tokens[1:]
    # Strip value-taking flag values out of the rest before the
    # "is this a skip flag" check, so `-e PATTERN file` isn't
    # mis-counted.
    rest = _strip_flag_value(rest, "-e")
    rest = _strip_flag_value(rest, "-f")
    if _grep_has_skip_flag(rest):
        return False
    # Count positionals: tokens not starting with `-`.
    positional = [t for t in rest if not t.startswith("-")]
    # We need at least 1 pattern + 1 file. The structured tool
    # requires a path; `<pattern> file` is the minimum.
    if len(positional) < 2:
        return False
    # If there are more than 2 positionals, the agent is doing
    # a multi-file search; the structured tool handles that with
    # a path glob, so it's still substitutable (and therefore blocked).
    return True


# ---------------------------------------------------------------------------
# Top-level analyze
# ---------------------------------------------------------------------------

OPERATORS = {"&&", "||", "|", "|&", ";", ";;", "&", "(", ")", "{", "}"}


def _is_simple_substitutable(command):
    """True if `command` is a simple `ls` or `grep`/`rg` call that the
    structured `glob` / `grep` tool covers.

    Returns the tool name (`"ls"`, `"grep"`, `"rg"`) on a match, or
    None when the command is not a simple substitutable use (and
    should be allowed through).
    """
    try:
        tokens = shlex.split(command, comments=False, posix=True)
    except ValueError:
        return None
    if not tokens:
        return None
    # Walk the tokens looking for a command-position executable that
    # is `ls`/`grep`/`rg`. Compound commands (`a && b`, `a | b`) are
    # detected by operator tokens; if any operator is present in the
    # top-level, we treat the command as "compound" and skip the
    # check (the structured tool can't express the compound).
    for tok in tokens:
        if tok in OPERATORS:
            return None

    base = tokens[0].rsplit("/", 1)[-1]
    if base in ("ls",):
        if _is_simple_ls(tokens):
            return "ls"
        return None
    if base in ("grep", "egrep", "fgrep", "rg"):
        if _is_simple_grep_like(tokens, base):
            return base
        return None
    return None


# ---------------------------------------------------------------------------
# Top-level decide
# ---------------------------------------------------------------------------

def decide(command):
    """Return a Decision dict for a command string.

    The result is harness-neutral; each adapter renders it in its
    own native way (exit code + stderr for Claude Code; throw for
    opencode).
    """
    if not command or not command.strip():
        return {"action": "allow", "reason": "", "context": "", "stderr": ""}

    tool = _is_simple_substitutable(command)
    if tool is None:
        return {"action": "allow", "reason": "", "context": "", "stderr": ""}

    target = "`" + tool + "`"
    if tool == "ls":
        target_tool = "`glob`"
    else:
        target_tool = "`grep`"
    reason = BLOCK_REASON_TEMPLATE.format(tool=target_tool, command=command)
    return {
        "action": "block",
        "reason": reason,
        "context": "",
        "stderr": reason,
    }


# ---------------------------------------------------------------------------
# CLI / --stdin adapters
# ---------------------------------------------------------------------------

def _read_stdin_command():
    """Read a Claude-Code-style hook payload from stdin and return the command."""
    raw = sys.stdin.read()
    try:
        payload = json.loads(raw)
    except Exception:
        return ""
    return (payload.get("tool_input", {}) or {}).get("command", "") or ""


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
        command = _read_stdin_command()
        _render_harness_native(decide(command))
        return

    if not argv:
        sys.stderr.write("usage: steer_ls_grep_check.py <command> | --stdin\n")
        sys.exit(2)

    command = argv[0]
    print(json.dumps(decide(command)))
    sys.exit(0)


if __name__ == "__main__":
    main()
