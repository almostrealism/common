#!/usr/bin/env bash
# ─── Decide whether a recurring QA job should be submitted ────────────
#
# Shared by every recurring quality job (documentation review, defect
# hunt). Each such job creates a branch named
# "<prefix><UTC timestamp>", registers a workstream for it, and opens a
# PR if the agent finds anything. This script answers one question:
# should another one start right now?
#
# Two conditions, in order:
#
#   1. A PR from a previous run of this job is still open. Then there is
#      nothing to start — the previous round has not been dealt with,
#      and stacking another on top of it is what produced dozens of
#      abandoned branches and workstreams.
#
#   2. The most recent run of this job is younger than MIN_INTERVAL_DAYS.
#      Then it is simply too soon.
#
# Otherwise the job runs.
#
# The cadence is derived from the branches themselves rather than from a
# marker in the GitHub Actions cache. The cache is not durable enough for
# this: it is evicted under repository pressure and is unavailable when
# the cache service is unreachable, and each miss silently authorises an
# extra run. The branch list is the same state the job already produces,
# it cannot drift from reality, and the timestamp is in the branch name.
#
# Usage:
#   qa-cadence.sh
#
# Required environment variables:
#   BRANCH_PREFIX       - e.g. "qa/docs-" or "qa/defect-"
#
# Optional environment variables:
#   MIN_INTERVAL_DAYS   - minimum days between runs (default: 7)
#   FORCE               - "true" bypasses both conditions
#   GITHUB_REPOSITORY   - owner/repo, for the open-PR query
#   GITHUB_TOKEN        - token for the open-PR query
#   REMOTE              - git remote to inspect (default: origin)
#
# Outputs (to stdout, and to $GITHUB_OUTPUT when set):
#   run=true|false
#   reason=<short machine-readable reason>
#
# Exit codes:
#   0 - decision made (check run=)
#   1 - invalid arguments

set -euo pipefail

if [ -z "${BRANCH_PREFIX:-}" ]; then
    echo "ERROR: BRANCH_PREFIX is not set." >&2
    exit 1
fi

MIN_INTERVAL_DAYS="${MIN_INTERVAL_DAYS:-7}"
REMOTE="${REMOTE:-origin}"

emit() {
    echo "run=$1"
    echo "reason=$2"
    if [ -n "${GITHUB_OUTPUT:-}" ]; then
        echo "run=$1" >> "$GITHUB_OUTPUT"
        echo "reason=$2" >> "$GITHUB_OUTPUT"
    fi
}

if [ "${FORCE:-false}" = "true" ]; then
    echo "::notice::Force mode — bypassing the open-PR and interval checks"
    emit true forced
    exit 0
fi

# ─── Condition 1: a PR from a previous run is still open ─────────────
#
# Failure to reach the API is deliberately NOT treated as "no open PR".
# Guessing "none" authorises a run, which is the direction that created
# the backlog; guessing "some" only delays one.
if [ -n "${GITHUB_REPOSITORY:-}" ] && [ -n "${GITHUB_TOKEN:-}" ]; then
    PR_JSON=$(curl -sS -f \
        -H "Authorization: Bearer ${GITHUB_TOKEN}" \
        -H "Accept: application/vnd.github+json" \
        "https://api.github.com/repos/${GITHUB_REPOSITORY}/pulls?state=open&per_page=100") \
        || {
            echo "::warning::Could not list open PRs — assuming one is open and skipping."
            emit false pr-query-failed
            exit 0
        }

    OPEN_COUNT=$(echo "$PR_JSON" \
        | jq --arg p "$BRANCH_PREFIX" '[.[] | select(.head.ref | startswith($p))] | length')

    if [ "$OPEN_COUNT" -gt 0 ]; then
        OPEN_REFS=$(echo "$PR_JSON" \
            | jq -r --arg p "$BRANCH_PREFIX" \
                '[.[] | select(.head.ref | startswith($p)) | "#\(.number) \(.head.ref)"] | join(", ")')
        echo "::notice::A previous run is still open (${OPEN_REFS}) — skipping. Review or close it first."
        emit false pr-open
        exit 0
    fi
else
    echo "::warning::GITHUB_REPOSITORY/GITHUB_TOKEN unset — skipping the open-PR check"
fi

# ─── Condition 2: the last run is younger than the interval ──────────
#
# The branch name carries its own creation time, so the newest branch
# name sorts lexically to the newest run.
LATEST_BRANCH=$(git ls-remote --heads "$REMOTE" "${BRANCH_PREFIX}*" 2>/dev/null \
    | sed 's|.*refs/heads/||' | sort | tail -1 || true)

if [ -z "$LATEST_BRANCH" ]; then
    echo "::notice::No previous ${BRANCH_PREFIX}* branch — first run"
    emit true first-run
    exit 0
fi

# "<prefix>YYYYMMDD-HHMMSS" -> "YYYYMMDD"
STAMP="${LATEST_BRANCH#"$BRANCH_PREFIX"}"
DAY="${STAMP%%-*}"

if ! echo "$DAY" | grep -Eq '^[0-9]{8}$'; then
    echo "::warning::Could not read a date from ${LATEST_BRANCH} — treating as due"
    emit true unparseable-branch-date
    exit 0
fi

# date(1) differs between BSD (macOS runners) and GNU (Linux runners).
if date -j >/dev/null 2>&1; then
    LAST_EPOCH=$(date -j -f "%Y%m%d" "$DAY" "+%s")
else
    LAST_EPOCH=$(date -u -d "$DAY" "+%s")
fi

NOW_EPOCH=$(date -u "+%s")
AGE_DAYS=$(( (NOW_EPOCH - LAST_EPOCH) / 86400 ))

echo "::notice::Most recent run: ${LATEST_BRANCH} (${AGE_DAYS} days ago); minimum interval ${MIN_INTERVAL_DAYS} days"

if [ "$AGE_DAYS" -lt "$MIN_INTERVAL_DAYS" ]; then
    emit false too-recent
else
    emit true due
fi
