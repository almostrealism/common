#!/usr/bin/env bash
# guard-pom-read.sh — PreToolUse hook
#
# Fires whenever Claude reads a pom.xml file. Injects a mandatory reminder
# about bidirectional dependency verification and runs an automatic check that
# prints which modules reference the pom.xml being read — so Claude always has
# both sides of the dependency graph immediately available.
#
# Exit 0  → allow the tool call (after printing injected content)

set -euo pipefail

INPUT="$(cat)"
FILE_PATH="$(echo "$INPUT" | python3 -c "
import sys, json
d = json.load(sys.stdin)
inp = d.get('tool_input', {})
print(inp.get('file_path', inp.get('path', '')))
" 2>/dev/null || true)"

# Only fire for pom.xml files
if ! echo "$FILE_PATH" | grep -q "pom\.xml$"; then
    exit 0
fi

# Extract the module name from the path (the directory name containing pom.xml)
MODULE_DIR="$(dirname "$FILE_PATH")"
MODULE_NAME="$(basename "$MODULE_DIR")"

# Try to find the artifactId in this pom.xml
ARTIFACT_ID=""
if [ -f "$FILE_PATH" ]; then
    ARTIFACT_ID="$(grep -o '<artifactId>ar-[^<]*</artifactId>' "$FILE_PATH" 2>/dev/null | head -1 | sed 's/<[^>]*>//g' || true)"
fi

cat <<HEADER
╔══════════════════════════════════════════════════════════════════════════════╗
║  DEPENDENCY VERIFICATION REMINDER — AUTO-INJECTED by guard-pom-read hook  ║
╚══════════════════════════════════════════════════════════════════════════════╝

You are reading: $FILE_PATH
Module directory: $MODULE_DIR
Detected artifactId: ${ARTIFACT_ID:-"(not detected — check <artifactId> tag)"}

MANDATORY: Before drawing ANY conclusions about this module's relationships:

  1. Check what THIS MODULE depends on:
     grep -o '<artifactId>ar-[^<]*</artifactId>' $FILE_PATH

  2. Check what depends ON THIS MODULE (search ALL pom.xml files):
     grep -rl '${ARTIFACT_ID:-ar-MODULENAME}' \$(find . -name pom.xml)

  DIRECTION MATTERS:
    "A depends on B" means A's pom.xml lists B as a <dependency>
    "B is consumed by A" means the same thing
    These are NOT the same as "B depends on A"

  NEVER say "nothing depends on X" after checking only one direction.
  NEVER say "X depends on Y" without quoting the pom.xml <dependency> tag.

HEADER

# Auto-run: find all pom.xml files that reference this artifact
if [ -n "$ARTIFACT_ID" ]; then
    REPO_ROOT="$(cd "$(dirname "$FILE_PATH")/../.." && pwd 2>/dev/null || echo ".")"
    # Try to find repo root by looking for parent pom.xml
    CANDIDATE="$MODULE_DIR"
    for i in 1 2 3 4 5; do
        CANDIDATE="$(dirname "$CANDIDATE")"
        if [ -f "$CANDIDATE/pom.xml" ] && grep -q "<modules>" "$CANDIDATE/pom.xml" 2>/dev/null; then
            REPO_ROOT="$CANDIDATE"
            break
        fi
    done

    echo "AUTO-CHECK: Modules that declare <dependency> on $ARTIFACT_ID:"
    echo "─────────────────────────────────────────────────────────────"
    CONSUMERS="$(grep -rl "<artifactId>$ARTIFACT_ID</artifactId>" "$REPO_ROOT" \
        --include="pom.xml" 2>/dev/null | grep -v "^$FILE_PATH$" || true)"
    if [ -z "$CONSUMERS" ]; then
        echo "  (none found — $ARTIFACT_ID has no consumers in this repo)"
    else
        echo "$CONSUMERS" | while read -r f; do
            DIR="$(dirname "$f")"
            CONSUMER_ID="$(grep -o '<artifactId>ar-[^<]*</artifactId>' "$f" 2>/dev/null | head -1 | sed 's/<[^>]*>//g' || true)"
            echo "  → $f  [artifactId: ${CONSUMER_ID:-unknown}]"
        done
    fi
    echo ""

    echo "AUTO-CHECK: Dependencies declared IN $FILE_PATH:"
    echo "─────────────────────────────────────────────────────────────"
    if [ -f "$FILE_PATH" ]; then
        grep -o '<artifactId>ar-[^<]*</artifactId>' "$FILE_PATH" 2>/dev/null \
            | sed 's/<[^>]*>//g' \
            | grep -v "^$ARTIFACT_ID$" \
            | sort -u \
            | while read -r dep; do echo "  → $dep"; done || true
        echo ""
    fi
fi

cat <<FOOTER
╔══════════════════════════════════════════════════════════════════════════════╗
║  END DEPENDENCY VERIFICATION REMINDER                                       ║
║  The auto-check above shows BOTH directions. Use it.                       ║
╚══════════════════════════════════════════════════════════════════════════════╝
FOOTER

exit 0
