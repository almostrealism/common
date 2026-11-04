#!/usr/bin/env bash
# ─── Fetch the most recent master CI test failures as a baseline ─────
#
# For classifying auto-resolve failures as regression-vs-pre-existing
# WITHOUT re-running: a test that fails on the
# branch but passes on master's most recent CI result is a regression; one that
# fails on master too is pre-existing. This downloads the Surefire reports from
# the most recent completed "Build and Test" run on origin/master and parses
# them with parse-surefire-failures.sh, writing the failing-test list to
# <output-file> in the same block format classify-failures.py reads.
#
# Best-effort: it NEVER fails the caller. With no master run, no artifacts, no
# gh, or any tool error, it leaves an empty baseline, and classify-failures.py
# then treats every non-environmental failure as a regression — today's
# behaviour, which over-assigns to the branch rather than dropping a failure.
#
# Usage:
#   fetch-master-test-baseline.sh <output-file>
#
# Environment:
#   GH_TOKEN            - token gh authenticates with (required for gh)
#   GITHUB_REPOSITORY   - owner/repo (defaults from the Actions environment)
#   BASELINE_WORKFLOW   - workflow file to read (default: analysis.yaml)
#   BASELINE_BRANCH     - branch whose runs to read (default: master)

# Deliberately not `set -e`: every failure below is tolerated, not fatal.
set -uo pipefail

OUTPUT_FILE="${1:-}"
if [ -z "$OUTPUT_FILE" ]; then
    echo "Usage: $0 <output-file>" >&2
    exit 1
fi

# An empty baseline is the safe default the whole script falls back to.
: > "$OUTPUT_FILE"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO="${GITHUB_REPOSITORY:-}"
WORKFLOW="${BASELINE_WORKFLOW:-analysis.yaml}"
BRANCH="${BASELINE_BRANCH:-master}"

if ! command -v gh >/dev/null 2>&1; then
    echo "::notice::gh unavailable — master test baseline is empty"
    exit 0
fi

RUN_ID=$(gh run list --repo "$REPO" --workflow "$WORKFLOW" --branch "$BRANCH" \
    --status completed --limit 1 --json databaseId --jq '.[0].databaseId' \
    2>/dev/null || true)
if [ -z "$RUN_ID" ]; then
    echo "::notice::no completed ${WORKFLOW} run on ${BRANCH} — baseline empty"
    exit 0
fi

WORKDIR="$(mktemp -d)"
gh run download "$RUN_ID" --repo "$REPO" --pattern 'surefire-*' \
    --dir "$WORKDIR" >/dev/null 2>&1 || true

if ! find "$WORKDIR" -name 'TEST-*.xml' -print -quit 2>/dev/null | grep -q .; then
    echo "::notice::no Surefire reports on ${BRANCH} run ${RUN_ID} — baseline empty"
    rm -rf "$WORKDIR"
    exit 0
fi

"${SCRIPT_DIR}/parse-surefire-failures.sh" "$WORKDIR" "$OUTPUT_FILE" \
    >/dev/null 2>&1 || : > "$OUTPUT_FILE"
rm -rf "$WORKDIR"

COUNT=$(grep -c '^- ' "$OUTPUT_FILE" 2>/dev/null || true)
echo "::notice::master test baseline: ${COUNT:-0} failing test(s) from ${BRANCH} run ${RUN_ID}"
exit 0
