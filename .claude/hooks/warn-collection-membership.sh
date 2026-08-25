#!/usr/bin/env bash
# PreToolUse — Edit/Write: catch collection membership being re-expressed as
# explicit offset arithmetic instead of asking the collection for its member.
#
# A PackedCollection shaped [members, size] already knows how to hand back
# member i: collection.get(i). Writing collection.range(shape(size), i * size)
# restates that by hand, and wrapping it in a private accessor
# (scalarColumn(int), rowOf(int), columnAt(int) ...) spreads a second, weaker
# vocabulary for membership across the codebase. When the shape is flat,
# reshape first and then index — the shape is the thing that should be fixed.
#
# Two shapes are flagged:
#   1. range(shape(X), i * X)          — membership written as arithmetic
#   2. a one-line private method whose whole body is such a range() call
#
# Soft guard: never blocks, expected to produce occasional false positives.
# A range() whose offset is NOT a multiple of the member size is a genuine
# sub-range (a prefix, a window, a placement offset) and is not flagged.
#
# Rule doc: .claude/hooks/rules/collection-membership.md

set -euo pipefail

INPUT=$(cat)

FILE_PATH=$(echo "$INPUT" | python3 -c "
import sys, json
print(json.load(sys.stdin).get('tool_input', {}).get('file_path', ''))
" 2>/dev/null || echo "")

if [[ "$FILE_PATH" != *.java ]]; then
    exit 0
fi

ADDED=$(echo "$INPUT" | python3 -c "
import sys, json
ti = json.load(sys.stdin).get('tool_input', {})
print(ti.get('new_string', ti.get('content', '')))
" 2>/dev/null || echo "")

if [[ -z "$ADDED" ]]; then
    exit 0
fi

FINDINGS=$(printf '%s' "$ADDED" | python3 -c "
import sys, re

src = sys.stdin.read()
lines = src.splitlines()
out = []

# range(shape(EXPR), IDX * EXPR) — the member size repeated as the stride.
member = re.compile(
    r'\.range\(\s*shape\(\s*([A-Za-z0-9_.()]+)\s*\)\s*,\s*([A-Za-z0-9_.()]+)\s*\*\s*([A-Za-z0-9_.()]+)\s*\)')

for i, line in enumerate(lines, 1):
    for m in member.finditer(line):
        size, a, b = m.group(1), m.group(2), m.group(3)
        if size == b or size == a:
            out.append((i, line.strip(), 'membership'))

# A private accessor whose entire body is one such range() call.
decl = re.compile(r'private\s+PackedCollection\s+(\w+)\s*\(\s*int\s+\w+\s*\)\s*\{')
for i, line in enumerate(lines):
    d = decl.search(line)
    if not d:
        continue
    body = lines[i + 1] if i + 1 < len(lines) else ''
    if '.range(' in body and 'return' in body:
        out.append((i + 1, d.group(1) + '(int)', 'accessor'))

for ln, text, kind in out:
    print(f'{kind}\t{ln}\t{text}')
" 2>/dev/null || echo "")

if [[ -z "$FINDINGS" ]]; then
    exit 0
fi

{
    echo "COLLECTION MEMBERSHIP RE-IMPLEMENTED in $FILE_PATH"
    echo
    while IFS=$'\t' read -r kind ln text; do
        [[ -z "$kind" ]] && continue
        if [[ "$kind" == "accessor" ]]; then
            echo "  line $ln: private accessor '$text' wraps a range() call"
        else
            echo "  line $ln: $text"
        fi
    done <<< "$FINDINGS"
    echo
    echo "A collection shaped [members, size] hands back member i as:"
    echo
    echo "    collection.get(i)"
    echo
    echo "range(shape(size), i * size) restates that by hand. If the collection is"
    echo "flat, the shape is what should be fixed — reshape, then index:"
    echo
    echo "    collection.reshape(shape(members, size)).get(i)"
    echo
    echo "Do NOT wrap either form in a private accessor. Naming it scalarColumn(),"
    echo "rowOf() or columnAt() creates a second vocabulary for membership that the"
    echo "next reader has to learn, for something the collection already expresses."
    echo
    echo "If the offset is genuinely NOT a member boundary — a prefix of a member, a"
    echo "sliding window, a placement offset — range() is correct and this is a false"
    echo "positive. Say so and move on."
} >&2

exit 0
