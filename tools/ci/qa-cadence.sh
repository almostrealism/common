#!/usr/bin/env bash
# ─── Decide whether a recurring QA job should be submitted ────────────
#
# Shared by every recurring quality job (documentation review, defect
# hunt, ...) and by the planning job, which uses it to keep one planning
# round open at a time. Each such job creates a branch named
# "<prefix><UTC timestamp>", registers a workstream for it, and opens a
# PR if the agent finds anything. This script answers one question:
# should another one start right now?
#
# Three conditions, in order:
#
#   1. A PR from a previous run of this job is still open. Then there is
#      nothing to start — the previous round has not been dealt with,
#      and stacking another on top of it is what produced dozens of
#      abandoned branches and workstreams.
#
#   2. (Only when PR_GRACE_HOURS is set.) A previous run's branch is
#      younger than PR_GRACE_HOURS and has never had a PR. The agent
#      opens its PR only when it finishes, so a round that is still
#      working is invisible to condition 1. A job whose only spacing is
#      condition 1 (MIN_INTERVAL_DAYS=0) needs this, or a second merge
#      landing while the agent works starts a second round beside it.
#      The window bounds how long a round whose agent never opened a PR
#      can hold the job off.
#
#   3. The most recent run of this job is younger than MIN_INTERVAL_DAYS.
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
#   PR_GRACE_HOURS      - hours a branch without any PR still counts as
#                         an open round (default: 0, condition 2 off)
#   IGNORE_INTERVAL     - "true" bypasses condition 3 only; an open or
#                         in-progress round still holds the job off
#   FORCE               - "true" bypasses every condition
#   GITHUB_REPOSITORY   - owner/repo, for the open-PR query
#   GITHUB_TOKEN        - token for the open-PR query
#   GITHUB_API_URL      - GitHub API base (default: https://api.github.com)
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
PR_GRACE_HOURS="${PR_GRACE_HOURS:-0}"
GITHUB_API_URL="${GITHUB_API_URL:-https://api.github.com}"
REMOTE="${REMOTE:-origin}"

emit() {
    echo "run=$1"
    echo "reason=$2"
    if [ -n "${GITHUB_OUTPUT:-}" ]; then
        echo "run=$1" >> "$GITHUB_OUTPUT"
        echo "reason=$2" >> "$GITHUB_OUTPUT"
    fi
}

# Seconds since the epoch of a UTC "YYYYMMDD" day and "HHMMSS" time.
# date(1) differs between BSD (macOS runners) and GNU (Linux runners).
#
# BSD date fills any field the format leaves out from the current
# wall-clock time rather than zero, so the time is always passed in
# full — a day alone once silently carried today's time-of-day and
# corrupted every age computed from it.
stamp_epoch() {
    local day="$1" time="$2"
    if date -j >/dev/null 2>&1; then
        date -u -j -f "%Y%m%d %H%M%S" "$day $time" "+%s"
    else
        date -u -d "$day ${time:0:2}:${time:2:2}:${time:4:2}" "+%s"
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
# The listing is paged: the API caps a page at 100, and the round we are
# looking for is the OLDEST matching PR, so it is the one a single first
# page would drop. Missing it authorises a run while a round is still
# open — the failure this condition exists to catch.
PER_PAGE=100
MAX_PAGES=20

if [ -n "${GITHUB_REPOSITORY:-}" ] && [ -n "${GITHUB_TOKEN:-}" ]; then
    PAGE=1
    OPEN_REFS=""
    while [ "$PAGE" -le "$MAX_PAGES" ]; do
        PR_JSON=$(curl -sS -f \
            -H "Authorization: Bearer ${GITHUB_TOKEN}" \
            -H "Accept: application/vnd.github+json" \
            "${GITHUB_API_URL}/repos/${GITHUB_REPOSITORY}/pulls?state=open&per_page=${PER_PAGE}&page=${PAGE}") \
            || {
                echo "::warning::Could not list open PRs — assuming one is open and skipping."
                emit false pr-query-failed
                exit 0
            }

        OPEN_REFS=$(echo "$PR_JSON" \
            | jq -r --arg p "$BRANCH_PREFIX" \
                '[.[] | select(.head.ref | startswith($p)) | "#\(.number) \(.head.ref)"] | join(", ")')

        # Stop at the first match: one open round is enough to skip, and
        # there is no reason to keep paging to enumerate the rest.
        [ -n "$OPEN_REFS" ] && break

        PAGE_COUNT=$(echo "$PR_JSON" | jq 'length')
        [ "$PAGE_COUNT" -lt "$PER_PAGE" ] && break
        PAGE=$((PAGE + 1))
    done

    if [ "$PAGE" -gt "$MAX_PAGES" ]; then
        echo "::warning::Stopped after ${MAX_PAGES} pages of open PRs without reaching the end — assuming one is open and skipping."
        emit false pr-page-limit
        exit 0
    fi

    if [ -n "$OPEN_REFS" ]; then
        echo "::notice::A previous run is still open (${OPEN_REFS}) — skipping. Review or close it first."
        emit false pr-open
        exit 0
    fi
else
    echo "::warning::GITHUB_REPOSITORY/GITHUB_TOKEN unset — skipping the open-PR check"
fi

ALL_BRANCHES=$(git ls-remote --heads "$REMOTE" "${BRANCH_PREFIX}*" 2>/dev/null \
    | sed 's|.*refs/heads/||' || true)

# ─── Condition 2: a recent branch has not opened its PR yet ──────────
#
# Only names carrying a full "YYYYMMDD-HHMMSS" stamp can be aged to the
# hour; a name without one is left to condition 3's handling.
#
# As in condition 1, a failed query is read as "a round is open".
if [ "$PR_GRACE_HOURS" -gt 0 ]; then
    if [ -n "${GITHUB_REPOSITORY:-}" ] && [ -n "${GITHUB_TOKEN:-}" ]; then
        NOW_EPOCH=$(date -u "+%s")
        OWNER="${GITHUB_REPOSITORY%%/*}"
        for BRANCH in $ALL_BRANCHES; do
            FULL_STAMP=$(echo "${BRANCH#"$BRANCH_PREFIX"}" | grep -oE '^[0-9]{8}-[0-9]{6}' || true)
            [ -z "$FULL_STAMP" ] && continue

            BRANCH_EPOCH=$(stamp_epoch "${FULL_STAMP:0:8}" "${FULL_STAMP:9:6}")
            AGE_HOURS=$(( (NOW_EPOCH - BRANCH_EPOCH) / 3600 ))
            [ "$AGE_HOURS" -ge "$PR_GRACE_HOURS" ] && continue

            BRANCH_PRS=$(curl -sS -f -G \
                -H "Authorization: Bearer ${GITHUB_TOKEN}" \
                -H "Accept: application/vnd.github+json" \
                --data-urlencode "state=all" \
                --data-urlencode "head=${OWNER}:${BRANCH}" \
                "${GITHUB_API_URL}/repos/${GITHUB_REPOSITORY}/pulls") \
                || {
                    echo "::warning::Could not look up the PR for ${BRANCH} — assuming its round is open and skipping."
                    emit false pr-query-failed
                    exit 0
                }

            if [ "$(echo "$BRANCH_PRS" | jq 'length')" -eq 0 ]; then
                echo "::notice::${BRANCH} is ${AGE_HOURS}h old and has not opened its PR yet — skipping; its round is still in progress."
                emit false awaiting-pr
                exit 0
            fi
        done
    else
        echo "::warning::GITHUB_REPOSITORY/GITHUB_TOKEN unset — skipping the awaiting-PR check"
    fi
fi

# ─── Condition 3: the last run is younger than the interval ──────────
#
# The branch name carries its own creation time, so among the names that
# carry a readable date the newest sorts last.
#
# Only dated names are considered. Taking the lexically-last name outright
# would let a single name without a date mask every real run: anything
# beginning with a letter sorts after "2026...", so one such branch would
# be read as "the most recent run", fail to parse, and fall through to
# "treat as due" — holding the gate permanently open, which is the exact
# failure this script exists to prevent.
#
# IGNORE_INTERVAL lifts only this condition. It is the narrower override a
# manual dispatch reaches for when a round is wanted sooner than the
# schedule allows: unlike FORCE, it never stacks a round on top of one that
# is still open.

if [ "${IGNORE_INTERVAL:-false}" = "true" ]; then
    echo "::notice::Interval override — skipping the ${MIN_INTERVAL_DAYS}-day minimum interval"
    emit true interval-ignored
    exit 0
fi

if [ -z "$ALL_BRANCHES" ]; then
    echo "::notice::No previous ${BRANCH_PREFIX}* branch — first run"
    emit true first-run
    exit 0
fi

# "<prefix>YYYYMMDD-HHMMSS" -> "YYYYMMDD-HHMMSS", keeping only dated names.
STAMP=$(echo "$ALL_BRANCHES" | sed "s|^${BRANCH_PREFIX}||" \
    | grep -E '^[0-9]{8}-' | sort | tail -1 || true)

if [ -z "$STAMP" ]; then
    echo "::warning::No ${BRANCH_PREFIX}* branch carries a readable date — treating as due"
    emit true unparseable-branch-date
    exit 0
fi

LATEST_BRANCH="${BRANCH_PREFIX}${STAMP}"
DAY="${STAMP:0:8}"

# Ages are counted in whole days from midnight, not from the run's time.
LAST_EPOCH=$(stamp_epoch "$DAY" 000000)

NOW_EPOCH=$(date -u "+%s")
AGE_DAYS=$(( (NOW_EPOCH - LAST_EPOCH) / 86400 ))

echo "::notice::Most recent run: ${LATEST_BRANCH} (${AGE_DAYS} days ago); minimum interval ${MIN_INTERVAL_DAYS} days"

if [ "$AGE_DAYS" -lt "$MIN_INTERVAL_DAYS" ]; then
    emit false too-recent
else
    emit true due
fi
