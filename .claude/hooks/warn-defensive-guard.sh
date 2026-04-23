#!/usr/bin/env bash
# PreToolUse — Edit/Write: scan the NEWLY INTRODUCED lines of Java source
# for patterns that commonly hide contract violations instead of fixing them.
#
# Pattern detection is heuristic; false positives are expected. This is a
# soft guard — it never blocks, it prints a loud reminder pointing at the
# specific lines and the fail-loud rule.
#
# Rule doc: .claude/hooks/rules/fail-loud.md
# Intended to catch my (the agent's) habit of adding `if (x > 0)` / `try { }
# catch { /* ignore */ }` style guards to silence exceptions rather than
# walk the stack back to the real producer of the bad value.

set -euo pipefail

INPUT=$(cat)

FILE_PATH=$(echo "$INPUT" | python3 -c "
import sys, json
data = json.load(sys.stdin)
print(data.get('tool_input', {}).get('file_path', ''))
" 2>/dev/null || echo "")

# Only Java source files
if [[ "$FILE_PATH" != *.java ]]; then
    exit 0
fi

# Skip test files — tests legitimately use defensive patterns to verify
# exceptions and boundary cases. The rule is about production code.
if [[ "$FILE_PATH" == *src/test/* ]]; then
    exit 0
fi

# Extract the content being added. For Edit, we want new_string; for Write
# we want content. Operate on the added text only.
ADDED=$(echo "$INPUT" | python3 -c "
import sys, json
data = json.load(sys.stdin)
ti = data.get('tool_input', {})
# Edit provides new_string; Write provides content
print(ti.get('new_string', ti.get('content', '')))
" 2>/dev/null || echo "")

if [[ -z "$ADDED" ]]; then
    exit 0
fi

# Patterns that almost always mean "I am tolerating bad state instead of
# finding where it came from." Each is a single regex matched line-by-line.
#
# Intentionally conservative: only very specific shapes to reduce noise.
# If we hit a real pattern, we print the matched lines and point at
# fail-loud.md.
declare -a HITS=()

# 1. Empty or near-empty catch blocks that swallow exceptions
while IFS= read -r line; do
    HITS+=("empty/trivial catch: $line")
done < <(echo "$ADDED" | grep -nE 'catch\s*\([A-Za-z0-9_.<>, ]+\)\s*\{\s*(//[^}]*|/\*[^}]*\*/)?\s*\}' || true)

# 2. catch { return null/false/empty/0; } — swallowing the failure and
#    handing callers a default
while IFS= read -r line; do
    HITS+=("catch-and-return-default: $line")
done < <(echo "$ADDED" | grep -nE 'catch\s*\([^)]+\)\s*\{\s*return\s+(null|false|0|-1|Optional\.empty\(\)|OptionalLong\.empty\(\)|Collections\.emptyList\(\))\s*;\s*\}' || true)

# 3. Math.max / Math.min clamping a freshly-computed value to a "plausible"
#    value — usually masking the producer. Requires a literal 0, 1, or -1
#    as one operand (anything else is probably a legitimate bound).
while IFS= read -r line; do
    HITS+=("clamp to literal: $line")
done < <(echo "$ADDED" | grep -nE 'Math\.(max|min)\s*\(\s*(0|1|-1|0L|1L)\s*,' || true)
while IFS= read -r line; do
    HITS+=("clamp to literal: $line")
done < <(echo "$ADDED" | grep -nE 'Math\.(max|min)\s*\([^,]+,\s*(0|1|-1|0L|1L)\s*\)' || true)

# 4. `if (x <= 0) return ...;` / `if (x == 0) return empty;` — the exact
#    shape that converts "unknown/invalid" into "silently default"
while IFS= read -r line; do
    HITS+=("zero-to-default short-circuit: $line")
done < <(echo "$ADDED" | grep -nE 'if\s*\([A-Za-z_][A-Za-z0-9_]*\s*(<=|<|==)\s*0[L]?\s*\)\s*\{?\s*return\s+(null|false|-1|0|Optional\.empty\(\)|OptionalLong\.empty\(\)|Collections\.emptyList\(\))' || true)

# 5. `.orElse(0)` on OptionalLong where the code then uses the value as a
#    divisor or iteration bound. We detect the `.orElse(0)` and trust the
#    reviewer (you) to confirm. This is the exact pattern I added to
#    Assignment.java — tolerating empty by substituting zero.
while IFS= read -r line; do
    HITS+=(".orElse(0) — substituting a default for 'unknown': $line")
done < <(echo "$ADDED" | grep -nE 'OptionalLong[^;]*\.orElse\s*\(\s*0[L]?\s*\)|\.orElse\s*\(\s*0[L]?\s*\)\s*[;,]' || true)

if [[ ${#HITS[@]} -eq 0 ]]; then
    exit 0
fi

cat >&2 <<EOF

╔══════════════════════════════════════════════════════════════════════╗
║  POSSIBLE DEFENSIVE-TOLERANCE PATTERN ADDED                          ║
╠══════════════════════════════════════════════════════════════════════╣
║  File: $FILE_PATH
║
║  The edit you are about to make contains patterns that historically  ║
║  have been used to MASK bugs instead of FIX them. Read the matched   ║
║  lines below and answer:                                             ║
║                                                                      ║
║    1. Where did the bad/empty/null value come from?                  ║
║    2. What does that producer's contract say about this case?        ║
║    3. If you add this guard, what happens to every OTHER caller      ║
║       that hits the same producer with the same bad value?           ║
║                                                                      ║
║  See: .claude/hooks/rules/fail-loud.md                                     ║
║                                                                      ║
║  Matched lines:                                                      ║
EOF

for h in "${HITS[@]}"; do
    # Truncate very long lines
    printf '║    %.140s\n' "  $h" >&2
done

cat >&2 <<'EOF'
║                                                                      ║
║  If after investigation the guard is still the right call, proceed.  ║
║  If you are adding the guard because you saw an exception and you    ║
║  want it to stop, STOP. Find the producer. Fix the producer.         ║
╚══════════════════════════════════════════════════════════════════════╝

EOF

# Inject the full rule doc so the agent has the reasoning in-context
# WITHOUT paying for it in every session where nothing defensive was added.
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RULE_DOC="$SCRIPT_DIR/rules/fail-loud.md"
if [[ -f "$RULE_DOC" ]]; then
    echo "── fail-loud.md (on-demand) ───────────────────────────────────────────" >&2
    cat "$RULE_DOC" >&2
    echo "── end fail-loud.md ───────────────────────────────────────────────────" >&2
fi

# Soft guard: always exit 0 so the edit proceeds. The reminder is visible
# in the user-facing transcript and in the agent's own tool output.
exit 0
