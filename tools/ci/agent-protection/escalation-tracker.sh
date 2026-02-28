#!/usr/bin/env bash
# ─── Escalation Circuit Breaker ─────────────────────────────────────
#
# Tracks how many times a specific test class has been dispatched for
# auto-resolution on a given branch. After a configurable number of
# failed attempts, it BLOCKS further dispatch and requires human
# intervention. This implements DECEPTION.md Countermeasure #6.
#
# The problem this solves: On feature/lora-gradients, NormTests failures
# were dispatched to agents 4 separate times. Each time the agent failed
# to fix them. Each time it consumed budget and produced deceptive
# output. This circuit breaker stops that cycle.
#
# State is stored in a tracking file (default: /tmp/ar-escalation-tracker.json)
# using a simple JSON format. The file persists across agent sessions on
# the same machine but is deliberately ephemeral — a machine restart
# clears the state, which is acceptable since the CI runners are
# long-lived.
#
# Usage:
#   escalation-tracker.sh check  <branch> <test-class>  [--max-attempts N]
#   escalation-tracker.sh record <branch> <test-class>
#   escalation-tracker.sh reset  <branch> [test-class]
#   escalation-tracker.sh report <branch>
#
# Commands:
#   check   - Check if dispatch is allowed. Exit 0 = allowed, Exit 2 = blocked.
#   record  - Record a dispatch attempt. Always exits 0.
#   reset   - Clear dispatch history for a branch (or specific test). Exits 0.
#   report  - Print dispatch history for a branch. Exits 0.
#
# Environment variables:
#   AR_ESCALATION_FILE  - Path to tracking file (default: /tmp/ar-escalation-tracker.json)
#   AR_MAX_ATTEMPTS     - Default max attempts before blocking (default: 2)

set -euo pipefail

COMMAND="${1:-}"
BRANCH="${2:-}"
TEST_CLASS="${3:-}"
MAX_ATTEMPTS_FLAG="${4:-}"

TRACKER_FILE="${AR_ESCALATION_FILE:-/tmp/ar-escalation-tracker.json}"
DEFAULT_MAX="${AR_MAX_ATTEMPTS:-2}"

usage() {
    echo "Usage:" >&2
    echo "  $0 check  <branch> <test-class>  [--max-attempts N]" >&2
    echo "  $0 record <branch> <test-class>" >&2
    echo "  $0 reset  <branch> [test-class]" >&2
    echo "  $0 report <branch>" >&2
    exit 1
}

if [ -z "$COMMAND" ] || [ -z "$BRANCH" ]; then
    usage
fi

# Ensure tracking file exists
if [ ! -f "$TRACKER_FILE" ]; then
    echo '{}' > "$TRACKER_FILE"
fi

# Normalize branch name to a JSON-safe key
BRANCH_KEY=$(echo "$BRANCH" | tr '/' '_' | tr -cd '[:alnum:]_-')

case "$COMMAND" in
    check)
        if [ -z "$TEST_CLASS" ]; then
            usage
        fi

        # Parse --max-attempts flag
        MAX_ATTEMPTS="$DEFAULT_MAX"
        if [ "$MAX_ATTEMPTS_FLAG" = "--max-attempts" ] && [ -n "${5:-}" ]; then
            MAX_ATTEMPTS="${5}"
        fi

        # Look up dispatch count
        CURRENT_COUNT=$(jq -r \
            --arg branch "$BRANCH_KEY" \
            --arg test "$TEST_CLASS" \
            '.[$branch][$test].count // 0' \
            "$TRACKER_FILE" 2>/dev/null || echo "0")

        if [ "$CURRENT_COUNT" -ge "$MAX_ATTEMPTS" ]; then
            LAST_DISPATCH=$(jq -r \
                --arg branch "$BRANCH_KEY" \
                --arg test "$TEST_CLASS" \
                '.[$branch][$test].last_dispatch // "unknown"' \
                "$TRACKER_FILE" 2>/dev/null || echo "unknown")

            echo "╔══════════════════════════════════════════════════════════════════╗"
            echo "║  CIRCUIT BREAKER TRIPPED: DISPATCH BLOCKED                      ║"
            echo "╠══════════════════════════════════════════════════════════════════╣"
            echo "║  Test class: ${TEST_CLASS}"
            echo "║  Branch:     ${BRANCH}"
            echo "║  Attempts:   ${CURRENT_COUNT} / ${MAX_ATTEMPTS} (max)"
            echo "║  Last:       ${LAST_DISPATCH}"
            echo "║                                                                ║"
            echo "║  This test has been dispatched to agents ${CURRENT_COUNT} time(s)      ║"
            echo "║  without being fixed. Further automated dispatch is blocked.   ║"
            echo "║  Manual intervention is required.                              ║"
            echo "║                                                                ║"
            echo "║  To reset: $0 reset ${BRANCH} ${TEST_CLASS}"
            echo "╚══════════════════════════════════════════════════════════════════╝"

            if [ -n "${GITHUB_OUTPUT:-}" ]; then
                echo "blocked=true" >> "$GITHUB_OUTPUT"
                echo "dispatch_count=$CURRENT_COUNT" >> "$GITHUB_OUTPUT"
                echo "max_attempts=$MAX_ATTEMPTS" >> "$GITHUB_OUTPUT"
            fi

            exit 2
        else
            REMAINING=$((MAX_ATTEMPTS - CURRENT_COUNT))
            echo "Dispatch allowed: ${TEST_CLASS} on ${BRANCH} (${CURRENT_COUNT}/${MAX_ATTEMPTS} attempts used, ${REMAINING} remaining)"

            if [ -n "${GITHUB_OUTPUT:-}" ]; then
                echo "blocked=false" >> "$GITHUB_OUTPUT"
                echo "dispatch_count=$CURRENT_COUNT" >> "$GITHUB_OUTPUT"
                echo "max_attempts=$MAX_ATTEMPTS" >> "$GITHUB_OUTPUT"
            fi

            exit 0
        fi
        ;;

    record)
        if [ -z "$TEST_CLASS" ]; then
            usage
        fi

        TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

        # Update the tracking file atomically
        TMP_FILE=$(mktemp)
        jq \
            --arg branch "$BRANCH_KEY" \
            --arg test "$TEST_CLASS" \
            --arg ts "$TIMESTAMP" \
            '
            .[$branch] //= {} |
            .[$branch][$test] //= {"count": 0, "dispatches": []} |
            .[$branch][$test].count += 1 |
            .[$branch][$test].last_dispatch = $ts |
            .[$branch][$test].dispatches += [$ts]
            ' \
            "$TRACKER_FILE" > "$TMP_FILE" && mv "$TMP_FILE" "$TRACKER_FILE"

        NEW_COUNT=$(jq -r \
            --arg branch "$BRANCH_KEY" \
            --arg test "$TEST_CLASS" \
            '.[$branch][$test].count' \
            "$TRACKER_FILE")

        echo "Recorded dispatch: ${TEST_CLASS} on ${BRANCH} (attempt #${NEW_COUNT})"
        ;;

    reset)
        TMP_FILE=$(mktemp)
        if [ -n "$TEST_CLASS" ]; then
            # Reset specific test class
            jq \
                --arg branch "$BRANCH_KEY" \
                --arg test "$TEST_CLASS" \
                'del(.[$branch][$test])' \
                "$TRACKER_FILE" > "$TMP_FILE" && mv "$TMP_FILE" "$TRACKER_FILE"
            echo "Reset dispatch history for ${TEST_CLASS} on ${BRANCH}"
        else
            # Reset entire branch
            jq \
                --arg branch "$BRANCH_KEY" \
                'del(.[$branch])' \
                "$TRACKER_FILE" > "$TMP_FILE" && mv "$TMP_FILE" "$TRACKER_FILE"
            echo "Reset all dispatch history for ${BRANCH}"
        fi
        ;;

    report)
        BRANCH_DATA=$(jq -r \
            --arg branch "$BRANCH_KEY" \
            '.[$branch] // {}' \
            "$TRACKER_FILE" 2>/dev/null || echo "{}")

        if [ "$BRANCH_DATA" = "{}" ]; then
            echo "No dispatch history for ${BRANCH}"
        else
            echo "Dispatch history for ${BRANCH}:"
            echo "$BRANCH_DATA" | jq -r '
                to_entries[] |
                "  \(.key): \(.value.count) dispatch(es), last: \(.value.last_dispatch)"
            '
        fi
        ;;

    *)
        echo "Unknown command: $COMMAND" >&2
        usage
        ;;
esac
