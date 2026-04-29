#!/usr/bin/env bash
# PreToolUse — Bash: block direct `mvn test`.
# Agents must use mcp__ar-test-runner__start_test_run instead.
# `mvn install -DskipTests`, `mvn compile`, and `mvn clean install` are allowed.
set -euo pipefail

INPUT=$(cat)
COMMAND=$(echo "$INPUT" | python3 -c "
import sys, json
data = json.load(sys.stdin)
print(data.get('tool_input', {}).get('command', ''))
" 2>/dev/null || echo "")

# Match `mvn test` as a standalone lifecycle phase.
# Excludes: -DskipTests, test-compile, test-jar (legitimate compile/package steps).
if echo "$COMMAND" | grep -qE 'mvn\b.*\btest\b' && \
   ! echo "$COMMAND" | grep -qE '\-DskipTests|\-Dmaven\.test\.skip|test-compile|test-jar'; then
    # Allow `mvn ... -Dtest=SomeClass` only if it also has -DskipTests (compile check)
    echo "BLOCKED: Direct 'mvn test' is not permitted for agents." >&2
    echo "" >&2
    echo "Use the MCP test runner:" >&2
    echo "  mcp__ar-test-runner__start_test_run" >&2
    echo "    module: \"<module>\"" >&2
    echo "    test_classes: [\"MyTest\"]" >&2
    echo "" >&2
    echo "Reason: Direct mvn test bypasses the controlled environment," >&2
    echo "runs at wrong test depth, and produces unreliable results." >&2
    exit 2
fi

exit 0
