#!/usr/bin/env python3
"""Decide whether a bash tool call is trying to read the native compiler's
GENERATED OUTPUT with a raw shell command, and steer it to the
ar-profile-analyzer MCP tool instead.

This module is the single source of truth for the "steer raw reads of
generated kernel / instruction-set / OperationProfile artifacts to the
profile analyzer" policy. It is invoked by:

  - .claude/hooks/steer-generated-code-read.sh      (Claude Code, --stdin)
  - .opencode/plugins/steer-generated-code-read.ts  (opencode, argv)

Why this exists
---------------
The framework compiles Producer graphs to native kernels (Metal / OpenCL /
JNI C). The generated source is dumped to disk as
``<dir>/{jni,mtl}_instruction_set_<N>.c`` (see HardwareOperator
``instructionSetOutputDir`` / MetalProgram ``recordInstructionSet`` /
NativeCompiler), and execution timing + the generated source per operation
node is captured in OperationProfile XML files (e.g.
``engine/utils/target/test-profiles/*.xml``).

The ar-profile-analyzer MCP tool exists specifically to read these:
``list_profiles`` -> ``load_profile`` -> ``search_operations`` ->
``get_source`` / ``get_source_summary`` return the generated source for a
named operation node, with argument info and structural summaries. Grepping
or cat-ing the raw dumps loses the node->source mapping, the timing context,
and the structural analysis the tool provides — and agents (per retros, and
the session that prompted this hook) keep reaching for ``find -name '*.metal'``
/ ``cat results/<id>/*instruction_set*.c`` out of habit instead.

A soft warning does not change the behavior. This is a HARD BLOCK on the
substitutable case: a read/search/list command (cat, grep, find, ls, sed,
awk, head, tail, strings, ...) whose target is a generated artifact.

What is NOT blocked
-------------------
  - Reading framework SOURCE under ``src/`` that merely mentions these
    concepts (e.g. ``grep instruction_set base/hardware/src/.../*.java``):
    the artifact patterns key on generated *output* names/paths, not words.
  - Build / run / VCS commands (``mvn``, ``java``, ``git`` ...): they may
    write a ``results/`` dir but do not read generated artifacts by name.
  - Anything without a generated-artifact reference.

The Decision shape (always JSON):

    {
      "action":  "block" | "allow",
      "reason":  "str",   # shown to the model on block; printed to stderr
      "context": "str",   # unused (binary policy, no warn path)
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
    "BLOCKED: reading generated compiler output with a raw shell command.\n"
    "\n"
    "Generated kernels / instruction-set dumps / OperationProfile XML are not\n"
    "meant to be read with find/grep/cat/ls — that loses the operation-node\n"
    "-> source mapping, timing context, and structural analysis. Use the\n"
    "ar-profile-analyzer MCP tool instead:\n"
    "\n"
    "  1. mcp__ar-profile-analyzer__list_profiles(directory=\"engine/utils/target/test-profiles\")\n"
    "  2. mcp__ar-profile-analyzer__search_operations(path, pattern)   # find the node\n"
    "  3. mcp__ar-profile-analyzer__get_source(path, node_key)         # generated source\n"
    "     mcp__ar-profile-analyzer__get_source_summary(path, node_key) # structure only\n"
    "\n"
    "If no profile captures your case, run the model with an OperationProfile\n"
    "and save it to XML (see engine/utils ProductDeltaIsolationTest), then\n"
    "point the analyzer at that XML.\n"
    "\n"
    "Command: {command}\n"
)


# ---------------------------------------------------------------------------
# Generated-artifact detection
# ---------------------------------------------------------------------------

# References that identify the native compiler's OUTPUT (not its source).
# Case-insensitive. These are chosen to match generated *file names / paths*,
# which do not appear when reading framework source under src/.
# Each pattern is written to match a PATH/GLOB reference to a generated file,
# not the bare word appearing as a search term. So `grep "test-profiles" Foo.java`
# (searching source) is allowed, while `cat .../test-profiles/x.xml` is blocked.
ARTIFACT_PATTERNS = (
    # jni_instruction_set_<N>.c / mtl_instruction_set_<N>.c dumps. The
    # trailing digits are what make this an OUTPUT file rather than the word
    # "instruction set" appearing in source/prose.
    re.compile(r"instruction_set_\d", re.IGNORECASE),
    # "instruction_set" only when paired with a code extension nearby
    # (e.g. a glob "*instruction_set*.c").
    re.compile(r"instruction_set[^\s'\"]*\.(?:c|cc|cpp|metal|cl)\b", re.IGNORECASE),
    # OperationProfile XML output directory, as a path (trailing slash) — not
    # the word "test-profiles" used as a search pattern.
    re.compile(r"test-profiles/", re.IGNORECASE),
    # generated Metal / OpenCL kernel source, as a path or glob (preceded by a
    # word char, '*', or '/'): "*.metal", "foo.metal", "dir/bar.cl".
    re.compile(r"[\w*/]\.metal\b", re.IGNORECASE),
    re.compile(r"[\w*/]\.cl\b", re.IGNORECASE),
    # a generated file under any results/ dir.
    re.compile(r"results/[^\s'\"]*\.(?:c|cc|cpp|metal|cl|xml)\b", re.IGNORECASE),
    # an 8-hex test-runner / instruction-set run directory (results/ce5cdd31).
    re.compile(r"results/[0-9a-f]{8}\b", re.IGNORECASE),
)


# Command bases that READ file contents or LOCATE files to read. When one of
# these is in command position and a generated artifact is referenced, the
# call is a substitutable raw read of generated output.
READ_BASES = frozenset({
    "cat", "head", "tail", "sed", "awk", "gawk", "grep", "egrep", "fgrep",
    "rg", "ag", "ack", "pcregrep", "less", "more", "bat", "strings", "xxd",
    "od", "hexdump", "nl", "tac", "find", "fd", "ls", "tree", "column",
    "cut", "wc", "diff", "view", "vim", "vi", "nano", "open", "cot",
})

# Operators that split a compound command into segments.
_SEGMENT_SPLIT = re.compile(r"\|\||&&|;|\||\n")


def _references_artifact(command):
    """True if the command text references a generated compiler artifact."""
    return any(p.search(command) for p in ARTIFACT_PATTERNS)


def _has_read_base(command):
    """True if any segment of the (possibly compound) command is invoked with
    a read/search/list executable in command position.

    Falls back to a permissive scan when the command cannot be tokenized:
    we only need ONE segment to look like a read of an artifact, and the
    artifact gate (checked separately) already makes a false positive here
    unlikely to matter.
    """
    for segment in _SEGMENT_SPLIT.split(command):
        segment = segment.strip()
        if not segment:
            continue
        try:
            tokens = shlex.split(segment, comments=False, posix=True)
        except ValueError:
            # Unparseable segment: do a cheap word-boundary scan for a base.
            first = segment.split()[:1]
            tokens = first
        if not tokens:
            continue
        # Skip leading env-assignments (VAR=value) and `command`/`sudo`.
        idx = 0
        while idx < len(tokens) and ("=" in tokens[idx] and not tokens[idx].startswith("-")):
            idx += 1
        if idx >= len(tokens):
            continue
        base = tokens[idx].rsplit("/", 1)[-1]
        if base in READ_BASES:
            return True
    return False


# ---------------------------------------------------------------------------
# Top-level decide
# ---------------------------------------------------------------------------

def decide(command):
    """Return a Decision dict for a command string.

    Block only when BOTH hold: the command references a generated artifact
    AND it is invoked through a read/search/list executable. Either alone is
    allowed (so build commands that write a results/ dir, or a stray mention
    of an artifact path in an echo, pass through).
    """
    if not command or not command.strip():
        return {"action": "allow", "reason": "", "context": "", "stderr": ""}

    if not _references_artifact(command):
        return {"action": "allow", "reason": "", "context": "", "stderr": ""}

    if not _has_read_base(command):
        return {"action": "allow", "reason": "", "context": "", "stderr": ""}

    reason = BLOCK_REASON_TEMPLATE.format(command=command)
    return {"action": "block", "reason": reason, "context": "", "stderr": reason}


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
        sys.stderr.write("usage: steer_generated_code_check.py <command> | --stdin\n")
        sys.exit(2)

    command = argv[0]
    print(json.dumps(decide(command)))
    sys.exit(0)


if __name__ == "__main__":
    main()
