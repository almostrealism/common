#!/usr/bin/env python3
"""Measure the inline `//` commentary a change adds to a source file.

This module is the single source of truth for the "inline comments do not
replace javadoc" policy. It is invoked by:

- .claude/hooks/block-excessive-comments.sh  (PreToolUse: Write/Edit/MultiEdit)
- .claude/hooks/check-source-writes.sh       (PostToolUse: Bash)

The rule (from CLAUDE.md and repeated PR review): important design information
belongs in method- or class-level JAVADOC, which is indexed and searchable.
Inline `//` comments are only for a brief explanation of nuance the outside
world does not need to know, or to mark steps in a hard-to-follow sequence.

The measurement is deliberately hard to sidestep: it looks at the TOTAL volume
of inline-comment text, the longest run of consecutive `//` lines, AND the
longest single `//` comment, so cramming the narration onto one long line or
re-splitting it does not get around the limit. Javadoc (`/** ... */` and its
`*` lines) is exempt; it is the sanctioned home for prose.

Usage:

    python3 inline_comment_check.py --stdin

reads a PreToolUse payload (``{"tool_input": {...}}``) and measures the text
the edit introduces. Exit 0 allows, exit 2 blocks with the reason on stderr.
"""

import json
import sys

#: Consecutive full-line `//` comments permitted.
MAX_RUN = 4

#: Characters of text permitted in a single `//` comment.
MAX_LINE = 180

#: Total characters of inline-comment text permitted in one change.
MAX_TOTAL = 300

#: Files whose comments this policy governs.
SOURCE_EXTENSIONS = (".java", ".pdsl", ".proto", ".kt", ".c", ".h", ".hpp",
                     ".cpp", ".cc", ".js", ".ts")

#: How many offending lines to quote back.
SAMPLE_LIMIT = 12


def governs(path):
    """Return whether this policy applies to the file at the given path."""
    return path.endswith(SOURCE_EXTENSIONS)


def comment_text(line):
    """Return the `//` comment text on this line, or None.

    A `//` preceded by a colon is part of a URL rather than the start of a
    comment, so it is skipped rather than treated as one.
    """
    i = 0
    while True:
        idx = line.find("//", i)
        if idx == -1:
            return None
        if idx > 0 and line[idx - 1] == ":":
            i = idx + 2
            continue
        return line[idx + 2:].strip()


def measure(fragments):
    """Measure the inline commentary across the given blocks of added text.

    Args:
        fragments: added text, as an iterable of strings.

    Returns:
        dict with ``worst_run``, ``worst_line``, ``total`` and ``sample``.
    """
    worst_run = 0
    worst_line = 0
    total = 0
    sample = []

    for content in fragments:
        run = 0

        for raw in content.splitlines():
            stripped = raw.strip()

            if stripped.startswith(("*", "/*", "/**", "*/")):
                run = 0
                continue

            text = comment_text(raw)
            if text is None:
                run = 0
                continue

            total += len(text)
            worst_line = max(worst_line, len(text))
            if len(sample) < SAMPLE_LIMIT:
                sample.append(stripped)

            if stripped.startswith("//"):
                run += 1
                worst_run = max(worst_run, run)
            else:
                run = 0

    return {"worst_run": worst_run, "worst_line": worst_line,
            "total": total, "sample": sample}


def reasons(measurement):
    """Return the reasons the measurement exceeds the policy, if any."""
    found = []

    if measurement["worst_run"] >= MAX_RUN:
        found.append("a run of %d consecutive // comment lines (max %d)"
                     % (measurement["worst_run"], MAX_RUN - 1))
    if measurement["worst_line"] > MAX_LINE:
        found.append("a single // comment of %d chars (max %d)"
                     % (measurement["worst_line"], MAX_LINE))
    if measurement["total"] > MAX_TOTAL:
        found.append("%d chars of // comment text in one edit (max %d)"
                     % (measurement["total"], MAX_TOTAL))

    return found


def block_reason(where, measurement, found):
    """Render the message shown to the model when the policy is exceeded."""
    shown = "\n".join("  | " + s for s in measurement["sample"])

    return (
        "BLOCKED: EXCESSIVE OR MISPLACED INLINE COMMENTS in " + where + "\n\n"
        "This change adds " + "; ".join(found) + ".\n\n"
        + shown + "\n\n"
        "WHY: Important information about design belongs in method- or class-level JAVADOC,\n"
        "which is indexed and searchable. Inline // comments are ONLY for a brief explanation\n"
        "of nuance the outside world does not need to know, or to mark steps in a hard-to-follow\n"
        "sequence. Do not use them to hide design information that should be searchable javadoc,\n"
        "or to narrate what you were doing or why while you worked - notes nobody will care about\n"
        "a week from now. Write comments that would still make sense to a reader ten years from now.\n\n"
        "This measures TOTAL comment volume, run length, and single-line length, so cramming the\n"
        "text onto one long line or re-splitting it will NOT get around it. The fix is to RELOCATE\n"
        "the content, not reshape it:\n"
        "  - genuine design or rationale  -> the method or class javadoc\n"
        "  - a note about your own task/thinking -> delete it\n"
        "  - keep any remaining inline // comment to a line or two of true nuance.\n"
    )


def added_text(tool_input):
    """Return the blocks of text an edit introduces, across the edit tools."""
    fragments = []

    if isinstance(tool_input.get("content"), str):
        fragments.append(tool_input["content"])
    if isinstance(tool_input.get("new_string"), str):
        fragments.append(tool_input["new_string"])
    for edit in tool_input.get("edits", []) or []:
        if isinstance(edit, dict) and isinstance(edit.get("new_string"), str):
            fragments.append(edit["new_string"])
    if isinstance(tool_input.get("new_source"), str):
        fragments.append(tool_input["new_source"])

    return fragments


def decide(where, fragments):
    """Return the block reason for the given added text, or None to allow."""
    if not governs(where):
        return None

    measurement = measure(fragments)
    found = reasons(measurement)
    if not found:
        return None

    return block_reason(where, measurement, found)


def main(argv=None):
    """Read a PreToolUse payload from stdin and decide on the edit."""
    argv = list(sys.argv[1:] if argv is None else argv)
    if not argv or argv[0] != "--stdin":
        sys.stderr.write("usage: inline_comment_check.py --stdin\n")
        return 1

    try:
        data = json.loads(sys.stdin.read())
    except Exception:
        return 0

    tool_input = data.get("tool_input", {}) or {}
    reason = decide(tool_input.get("file_path", "") or "",
                    added_text(tool_input))
    if reason is None:
        return 0

    sys.stderr.write(reason)
    return 2


if __name__ == "__main__":
    sys.exit(main())
