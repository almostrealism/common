#!/usr/bin/env bash
# PostToolUse — Edit/Write: when a Java test file is modified, scan for
# @Test methods that don't contain an assertion. A test that only prints
# to stdout or inspects state without asserting cannot fail — it is a
# demo, not a test.
#
# Rule doc: .claude/hooks/rules/fail-loud.md
# Intended to catch my (the agent's) habit of writing tests whose sole
# contribution is a System.out.println that the reviewer is expected to
# squint at. Those aren't tests.

set -euo pipefail

INPUT=$(cat)

FILE_PATH=$(echo "$INPUT" | python3 -c "
import sys, json
data = json.load(sys.stdin)
print(data.get('tool_input', {}).get('file_path', ''))
" 2>/dev/null || echo "")

# Only Java test files
if [[ "$FILE_PATH" != *.java ]]; then
    exit 0
fi
if [[ "$FILE_PATH" != *src/test/* ]] && [[ "$FILE_PATH" != *Test.java ]] && [[ "$FILE_PATH" != *Tests.java ]]; then
    exit 0
fi

# PostToolUse runs after the file is on disk. Scan the current file.
if [[ ! -f "$FILE_PATH" ]]; then
    exit 0
fi

# Find every @Test method and check its body for assertion calls. We use
# awk for this because it handles multi-line method bodies with brace
# nesting without resorting to a real Java parser.
#
# Assertion indicators: Assert.* calls, bare assert*(, Assertions.*
# (JUnit 5), org.hamcrest matchers (assertThat), throw new AssertionError,
# assumeTrue (skip), delegation to helpers whose names suggest assertion
# (containing "assert" or "verify" or "expect").
BAD_METHODS=$(awk '
  BEGIN {
    in_method = 0
    depth = 0
    method_has_assert = 0
    method_line = 0
    method_name = ""
    test_pending = 0
  }
  /^[[:space:]]*@Test([[:space:]]|$|\()/ {
    test_pending = 1
    next
  }
  test_pending && /public[[:space:]]+void[[:space:]]+[A-Za-z_][A-Za-z0-9_]*[[:space:]]*\(/ {
    method_line = NR
    match($0, /public[[:space:]]+void[[:space:]]+[A-Za-z_][A-Za-z0-9_]*/)
    method_name = substr($0, RSTART, RLENGTH)
    sub(/public[[:space:]]+void[[:space:]]+/, "", method_name)
    in_method = 1
    method_has_assert = 0
    depth = 0
    test_pending = 0
  }
  in_method {
    n = gsub(/\{/, "{")
    m = gsub(/\}/, "}")
    depth += n - m
    if (/Assert\./ || /assertThat/ || /Assertions\./ ||
        /throw[[:space:]]+new[[:space:]]+AssertionError/ ||
        /(^|[^A-Za-z0-9_])assert[A-Z][A-Za-z0-9_]*[[:space:]]*\(/ ||
        /(^|[^A-Za-z0-9_])assume[A-Z][A-Za-z0-9_]*[[:space:]]*\(/ ||
        /(^|[^A-Za-z0-9_])verify[A-Z][A-Za-z0-9_]*[[:space:]]*\(/ ||
        /(^|[^A-Za-z0-9_])expect[A-Z][A-Za-z0-9_]*[[:space:]]*\(/ ||
        /(^|[^A-Za-z0-9_])check[A-Z][A-Za-z0-9_]*[[:space:]]*\(/ ||
        /(^|[^A-Za-z0-9_])assertions[[:space:]]*\(/) {
      method_has_assert = 1
    }
    if (depth <= 0 && /\}/) {
      if (!method_has_assert) {
        printf "  line %d: %s — no assertion/throw detected\n", method_line, method_name
      }
      in_method = 0
      method_has_assert = 0
    }
  }
' "$FILE_PATH" 2>/dev/null || true)

if [[ -z "$BAD_METHODS" ]]; then
    exit 0
fi

cat >&2 <<EOF

╔══════════════════════════════════════════════════════════════════════╗
║  TEST METHODS WITHOUT DETECTABLE ASSERTIONS                          ║
╠══════════════════════════════════════════════════════════════════════╣
║  File: $FILE_PATH
║                                                                      ║
║  The listed @Test methods do not appear to contain any of:           ║
║    Assert.*   assertThat(...)   Assertions.*                         ║
║    throw new AssertionError(...)                                     ║
║    assertFoo(...)   assumeFoo(...)                                   ║
║    verifyFoo(...)   expectFoo(...)   checkFoo(...)                   ║
║                                                                      ║
║  A test that cannot fail is a demo. Either:                          ║
║    1. Add an assertion that encodes what you believe should be true; ║
║    2. Delegate to a helper method whose name starts with assert*,    ║
║       verify*, expect*, or check* (then this scanner trusts it);     ║
║    3. Delete the test.                                               ║
║                                                                      ║
║  Matches (heuristic; false positives possible):                      ║
EOF

while IFS= read -r line; do
    [[ -z "$line" ]] && continue
    printf '║  %.136s\n' "$line" >&2
done <<< "$BAD_METHODS"

cat >&2 <<'EOF'
║                                                                      ║
║  See: .claude/hooks/rules/fail-loud.md                                     ║
╚══════════════════════════════════════════════════════════════════════╝

EOF

exit 0
