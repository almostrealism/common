#!/usr/bin/env bash
# PreToolUse - Write/Edit/MultiEdit/NotebookEdit: block an edit that adds a wall of
# inline `//` commentary to a source file.
#
# The rule this enforces (from CLAUDE.md and repeated PR review): important design
# information belongs in method- or class-level JAVADOC, which is indexed and
# searchable. Inline `//` comments are only for a brief explanation of nuance the
# outside world does not need to know, or to mark steps in a hard-to-follow sequence.
# They must NOT be used to hide searchable design information, nor to narrate what the
# author was doing or thinking while they worked.
#
# It is deliberately hard to sidestep: it measures the TOTAL volume of inline-comment
# text in the edit, the longest run of consecutive `//` lines, AND the longest single
# `//` comment - so cramming the narration onto one long line or re-splitting it does
# not get around the limit. Javadoc (/** ... */ and its `*` lines) is exempt; it is
# the sanctioned home for prose. Python `#` comments are out of scope.
#
# Exit 0  -> allow
# Exit 2  -> BLOCK (reason on stderr is shown to the model)
set -euo pipefail

INPUT=$(cat)

python3 - "$INPUT" <<'PY'
import json
import sys

MAX_RUN = 4     # consecutive full-line `//` comments
MAX_LINE = 180  # characters of text in a single `//` comment
MAX_TOTAL = 300 # total characters of inline-comment text added in one edit

try:
    data = json.loads(sys.argv[1])
except Exception:
    sys.exit(0)

tool_input = data.get("tool_input", {}) or {}
file_path = tool_input.get("file_path", "") or ""

exts = (".java", ".pdsl", ".proto", ".kt", ".c", ".h", ".hpp", ".cpp", ".cc",
        ".js", ".ts")
if not file_path.endswith(exts):
    sys.exit(0)


def comment_text(line):
    """The `//` comment text on this line (ignoring `://` URLs), or None."""
    i = 0
    while True:
        idx = line.find("//", i)
        if idx == -1:
            return None
        if idx > 0 and line[idx - 1] == ":":   # part of http:// etc.
            i = idx + 2
            continue
        return line[idx + 2:].strip()


fragments = []
if isinstance(tool_input.get("content"), str):
    fragments.append(tool_input["content"])            # Write
if isinstance(tool_input.get("new_string"), str):
    fragments.append(tool_input["new_string"])         # Edit
for edit in tool_input.get("edits", []) or []:         # MultiEdit
    if isinstance(edit, dict) and isinstance(edit.get("new_string"), str):
        fragments.append(edit["new_string"])
if isinstance(tool_input.get("new_source"), str):
    fragments.append(tool_input["new_source"])         # NotebookEdit

worst_run = 0
worst_line = 0
total = 0
sample = []
for content in fragments:
    run = 0
    for raw in content.splitlines():
        stripped = raw.strip()
        # Javadoc / block-comment prose is exempt - it is the right place for design info.
        if stripped.startswith(("*", "/*", "/**", "*/")):
            run = 0
            continue
        text = comment_text(raw)
        if text is None:
            run = 0
            continue
        total += len(text)
        worst_line = max(worst_line, len(text))
        if len(sample) < 12:
            sample.append(stripped)
        if stripped.startswith("//"):
            run += 1
            worst_run = max(worst_run, run)
        else:
            run = 0  # trailing comment - counts toward total, breaks the full-line run

if worst_run < MAX_RUN and worst_line <= MAX_LINE and total <= MAX_TOTAL:
    sys.exit(0)

reasons = []
if worst_run >= MAX_RUN:
    reasons.append("a run of %d consecutive // comment lines (max %d)" % (worst_run, MAX_RUN - 1))
if worst_line > MAX_LINE:
    reasons.append("a single // comment of %d chars (max %d)" % (worst_line, MAX_LINE))
if total > MAX_TOTAL:
    reasons.append("%d chars of // comment text in one edit (max %d)" % (total, MAX_TOTAL))

shown = "\n".join("  | " + s for s in sample)
sys.stderr.write(
    "BLOCKED: EXCESSIVE OR MISPLACED INLINE COMMENTS in " + file_path + "\n\n"
    "This edit adds " + "; ".join(reasons) + ".\n\n"
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
sys.exit(2)
PY
