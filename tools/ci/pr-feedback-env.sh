#!/usr/bin/env bash
# ─── Report a PR's outstanding reviewer feedback for a submission ─────
#
# Fetches a pull request's reviews and review threads and prints, on stdout,
#   feedback_outstanding=true|false
# for the caller to turn into the FEEDBACK_OUTSTANDING env the submit scripts
# forward to the controller. It also writes one `summary <TAB> url` line per
# outstanding finding to $FEEDBACK_FINDINGS_FILE (when set), for the submission
# to carry into the budget-pause ping.
#
# The "is this finding accounted for" rule is NOT re-derived here: this script
# fetches the data and hands it to tools/ci/pr-feedback-status.py, which reuses
# tools/mcp/manager/github_api.pr_outstanding_findings — the one place every
# consumer agrees on what "outstanding" means.
#
# It prints nothing (and exits 0) when it cannot determine the state — no gh,
# the script missing from this checkout, or a failed fetch. The caller then
# leaves FEEDBACK_OUTSTANDING unset, and the controller, unable to tell whether
# feedback is outstanding, dispatches rather than skips (the fail-open the
# incident requires).
#
# Usage:
#   REPO=owner/repo PR=123 [FEEDBACK_FINDINGS_FILE=path] pr-feedback-env.sh
set -euo pipefail

: "${REPO:?REPO (owner/repo) is required}"
: "${PR:?PR number is required}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

command -v gh >/dev/null 2>&1 || exit 0
command -v python3 >/dev/null 2>&1 || exit 0
[ -f "$SCRIPT_DIR/pr-feedback-status.py" ] || exit 0

reviews_file="$(mktemp)"
threads_file="$(mktemp)"
trap 'rm -f "$reviews_file" "$threads_file"' EXIT

if ! gh api "repos/${REPO}/pulls/${PR}/reviews" --paginate > "$reviews_file" 2>/dev/null; then
    exit 0
fi

owner="${REPO%%/*}"
name="${REPO##*/}"
# TODO(review): reviewThreads(first:100)/comments(first:50) are not paginated;
# a PR past those limits can under-report findings and strand one (skip wrongly).
threads_query='query($owner:String!,$repo:String!,$pr:Int!){repository(owner:$owner,name:$repo){pullRequest(number:$pr){reviewThreads(first:100){nodes{isResolved comments(first:50){nodes{author{login} createdAt body url}}}}}}}'
if ! gh api graphql -f query="$threads_query" \
        -F owner="$owner" -F repo="$name" -F pr="$PR" > "$threads_file" 2>/dev/null; then
    exit 0
fi

python3 "$SCRIPT_DIR/pr-feedback-status.py" "$reviews_file" "$threads_file"
