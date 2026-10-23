#!/usr/bin/env bash
# ─── Post-deploy canary: run one real job, on the real fleet ─────────
#
# Every other layer of agent verification runs against a fake agent in a unit
# test. This one does not. It submits a genuine job through the controller's
# public submit endpoint, lets a genuine agent on the deployed fleet run it,
# and then checks the git remote for the result.
#
# It exists because of a specific failure. A policy change made every job in
# the fleet die before publishing anything. The whole test suite was green
# throughout, a large change was merged and deployed against the outage, and
# the outage continued unchanged for a full working day. Nothing in the
# pipeline exercised a real job on a real agent, so nothing could tell.
#
# ── The oracle is git, never the job's own report ───────────────────
#
# The controller's job status is the thing that lied. A job can report
# `success` and publish nothing: that combination is the outage's exact
# signature, and it is reported here as its own distinct error rather than
# folded into a generic failure, because it means something different and
# far worse than a job that failed honestly.
#
# So the only evidence accepted is a nonce, generated here, arriving in a
# commit on the remote. The agent cannot produce that without the entire
# path — clone, edit, stage, commit, push — actually working.
#
# ── The canary branch is deliberately not named ci/* ────────────────
#
# A branch named `ci/...` is granted permissions ordinary branches do not
# get. Running the canary on one would verify a path most jobs never take.
#
# ── Still queued is not failed ──────────────────────────────────────
#
# A deploy reopens job intake, and the merge-triggered QA rounds that were
# held off by the drain are submitted at the same moment as the canary. The
# canary can then sit in the queue behind them for a long time without
# anything being wrong with the fleet. So the wait is long (an hour), and if
# it still runs out, the controller's pending view is asked whether the job
# is merely waiting for a worker. "Still queued" is reported as its own
# outcome — the deploy is UNVERIFIED, which is not a pass and not a broken
# fleet — and a job the controller has given up on as LOST is a failure.
#
# ── Commits accumulate on purpose ───────────────────────────────────
#
# The branch is never reset. Each deploy appends one commit, so
# `git log origin/<branch>` is the standing record of every deploy that was
# proven to produce a working agent, and the gap after a bad one is visible.
#
# Usage:
#   post-deploy-canary.sh run       submit, wait, then verify (the CI path)
#   post-deploy-canary.sh verify    verify a nonce already submitted
#   post-deploy-canary.sh selftest  prove the verifier both accepts a nonce
#                                   that landed and rejects one that did not,
#                                   against a scratch repository, and that a
#                                   queued job is told apart from a lost one
#                                   (offline, no agent run, no cost)
#
# Environment:
#   CONTROLLER_URL        controller base URL (default http://localhost:7780)
#   CANARY_BRANCH         branch the agent works on (default agent-canary)
#   CANARY_REPO_DIR       working repository to fetch from (default: this one)
#   CANARY_REMOTE         remote to fetch (default origin)
#   CANARY_BASE_BRANCH    base branch          (default master)
#   CANARY_NONCE          nonce to use/verify  (default: generated)
#   CANARY_TIMEOUT_SECONDS  give up waiting after this long (default 3600)
#   CANARY_POLL_SECONDS   seconds between polls (default 20)
#   REPO_URL              repository to submit against (default: this one)
#   CF_ACCESS_CLIENT_ID / CF_ACCESS_CLIENT_SECRET  passed through to submit
#
# Exit status of `run`: 0 when the nonce landed; 3 when the job was still
# queued when the wait ran out (UNVERIFIED); 1 for every failure. Under GitHub
# Actions the outcome is also written to $GITHUB_OUTPUT as `outcome=`
# (published, queued, lost, silent-no-op or failed), so the alert that
# follows can say which.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"

CONTROLLER_URL="${CONTROLLER_URL:-http://localhost:7780}"
CONTROLLER_URL="${CONTROLLER_URL%/}"
CANARY_BRANCH="${CANARY_BRANCH:-agent-canary}"
CANARY_BASE_BRANCH="${CANARY_BASE_BRANCH:-master}"
CANARY_TIMEOUT_SECONDS="${CANARY_TIMEOUT_SECONDS:-3600}"
CANARY_POLL_SECONDS="${CANARY_POLL_SECONDS:-20}"

# The file the agent is asked to write. Kept inside the e2e tooling so its
# purpose is obvious to anyone who finds it in a diff.
CANARY_FILE="tools/ci/agent-e2e/canary/last-run.txt"

die() { echo "::error::$*" >&2; exit 1; }

# Records the run's outcome for the workflow step that follows, when there is
# one to read it.
report_outcome() {
    [ -n "${GITHUB_OUTPUT:-}" ] && echo "outcome=$1" >> "$GITHUB_OUTPUT"
    return 0
}

# ── Queue state ─────────────────────────────────────────────────────
#
# Reads a workstream's pending-jobs listing (GET /api/workstreams/{id}/jobs/pending)
# on stdin and prints the state the controller derived for JOB_ID: SUBMITTED
# while it waits for a worker, LOST once it has waited past the controller's
# threshold, nothing when the job is not pending at all. Separate from the
# HTTP call so the selftest can prove it against fixed listings.
pending_state() {
    jq -r --arg id "$1" \
        'if type == "array" then (map(select(.jobId == $id)) | .[0].state // empty) else empty end' \
        2>/dev/null
}

# ── Verification ────────────────────────────────────────────────────
#
# Separate from submission so it can be exercised without an agent run. The
# selftest calls exactly this function with a nonce that was never submitted
# and requires it to fail; a verifier that cannot be watched rejecting
# something is not evidence of anything.
verify_nonce_landed() {
    local nonce="$1" branch="$2" content
    local repo="${CANARY_REPO_DIR:-$REPO_ROOT}" remote="${CANARY_REMOTE:-origin}"

    if ! git -C "$repo" fetch --quiet "$remote" "$branch" 2>/dev/null; then
        echo "  branch ${branch} does not exist on the remote"
        return 1
    fi

    content="$(git -C "$repo" show "FETCH_HEAD:${CANARY_FILE}" 2>/dev/null)"
    if [ -z "$content" ]; then
        echo "  ${CANARY_FILE} is not present on ${branch}"
        return 1
    fi

    if ! printf '%s' "$content" | grep -qF -- "$nonce"; then
        echo "  ${CANARY_FILE} exists but does not contain this run's nonce."
        echo "  It holds: $(printf '%s' "$content" | head -3 | tr '\n' ' ')"
        echo "  A stale nonce means an earlier deploy's commit is still the"
        echo "  newest one: this deploy's agent published nothing."
        return 1
    fi

    return 0
}

case "${1:-run}" in
verify)
    nonce="${CANARY_NONCE:-}"
    [ -n "$nonce" ] || die "verify needs CANARY_NONCE"
    if verify_nonce_landed "$nonce" "$CANARY_BRANCH"; then
        echo "::notice::Canary nonce ${nonce} is present on ${CANARY_BRANCH}"
        exit 0
    fi
    die "Canary nonce ${nonce} did not reach ${CANARY_BRANCH}"
    ;;

selftest)
    # No agent, no controller, no cost, no network — a scratch bare repo
    # stands in for the remote.
    #
    # Both directions are checked, because either one alone is worthless. A
    # verifier that rejects everything passes a negative-only selftest while
    # failing every real deploy; a verifier that accepts everything passes a
    # positive-only selftest while certifying a fleet that publishes nothing.
    # The second is the dangerous one, and it is exactly the shape of the
    # bug this whole system exists to catch, so it is checked first.
    echo "Canary selftest: the verifier must accept a landed nonce and reject an absent one."

    scratch="$(mktemp -d)"
    trap 'rm -rf "$scratch"' EXIT

    landed="canary-selftest-landed-$$"
    absent="canary-selftest-absent-$$"

    (
        set -e
        git init --quiet --bare "${scratch}/remote.git"
        git init --quiet "${scratch}/work"
        cd "${scratch}/work"
        git config user.email canary@localhost
        git config user.name Canary
        git remote add origin "${scratch}/remote.git"
        mkdir -p "$(dirname "$CANARY_FILE")"
        printf '%s\ndeployed-sha=selftest\n' "$landed" > "$CANARY_FILE"
        git add "$CANARY_FILE"
        git commit --quiet -m "canary selftest fixture"
        git push --quiet origin "HEAD:refs/heads/${CANARY_BRANCH}"
    ) || die "SELFTEST FAILED: could not build the scratch repository fixture"

    export CANARY_REPO_DIR="${scratch}/work"
    export CANARY_REMOTE=origin

    if ! verify_nonce_landed "$landed" "$CANARY_BRANCH"; then
        die "SELFTEST FAILED: the verifier rejected a nonce that IS present on the branch. It would fail every deploy regardless of whether the fleet works."
    fi
    echo "  accepts a nonce that landed"

    # The rejections are expected here, so their diagnostics are suppressed.
    # Printed, they make a passing selftest read like a run that hit errors,
    # and a check whose success looks like failure gets ignored.
    if verify_nonce_landed "$absent" "$CANARY_BRANCH" >/dev/null 2>&1; then
        die "SELFTEST FAILED: the verifier accepted a nonce that was never committed. It cannot distinguish a working deploy from a broken one, and every green canary it has ever reported is meaningless."
    fi
    echo "  rejects a nonce that did not"

    if verify_nonce_landed "$landed" "branch-that-does-not-exist-$$" >/dev/null 2>&1; then
        die "SELFTEST FAILED: the verifier accepted a branch that does not exist on the remote."
    fi
    echo "  rejects a branch that does not exist"

    # A queued canary must not read as a failed one, and a lost one must not
    # read as merely queued. Both directions, against fixed listings.
    if command -v jq >/dev/null 2>&1; then
        listing='[{"jobId":"other","state":"SUBMITTED"},{"jobId":"queued-job","state":"SUBMITTED"},{"jobId":"lost-job","state":"LOST"}]'
        [ "$(printf '%s' "$listing" | pending_state queued-job)" = "SUBMITTED" ] \
            || die "SELFTEST FAILED: a job waiting for a worker was not read as SUBMITTED. A canary queued behind other work would be reported as a broken fleet."
        echo "  reads a queued job as still queued"
        [ "$(printf '%s' "$listing" | pending_state lost-job)" = "LOST" ] \
            || die "SELFTEST FAILED: a job the controller marked LOST was not read as lost."
        echo "  reads a lost job as lost"
        [ -z "$(printf '%s' "$listing" | pending_state started-job)" ] \
            || die "SELFTEST FAILED: a job absent from the pending listing was read as pending."
        [ -z "$(printf '%s' '{"ok":false}' | pending_state queued-job)" ] \
            || die "SELFTEST FAILED: an error body was read as a pending job."
        echo "  reads a job that is not pending, or an error body, as not queued"
    else
        die "SELFTEST FAILED: jq is required to read the controller's pending view."
    fi

    echo "::notice::Canary selftest passed — the verifier discriminates in both directions"
    exit 0
    ;;

run) ;;
*)  die "Usage: $0 [run|verify|selftest]" ;;
esac

# ── Submit ──────────────────────────────────────────────────────────

command -v jq >/dev/null 2>&1 || die "jq is required"

NONCE="${CANARY_NONCE:-canary-$(date -u +%Y%m%dT%H%M%SZ)-${GITHUB_RUN_ID:-local}-${RANDOM}}"
DEPLOYED_SHA="$(git -C "$REPO_ROOT" rev-parse HEAD 2>/dev/null || echo unknown)"

echo "───────────────────────────────────────────────────────────"
echo "Post-deploy canary"
echo "  controller : ${CONTROLLER_URL}"
echo "  branch     : ${CANARY_BRANCH} (from ${CANARY_BASE_BRANCH})"
echo "  nonce      : ${NONCE}"
echo "  deployed   : ${DEPLOYED_SHA}"
echo "───────────────────────────────────────────────────────────"

PROMPT_FILE="$(mktemp)"
trap 'rm -f "$PROMPT_FILE"' EXIT

# The task is deliberately trivial. The canary is not measuring whether an
# agent can solve a problem; it is measuring whether the machinery around the
# agent — clone, edit, stage, commit message, commit, push — carries a result
# out to the remote at all. A hard task would add failure modes that are not
# the ones under test.
cat > "$PROMPT_FILE" <<PROMPT_EOF
This is an automated post-deploy verification job. It confirms that the agent
fleet can carry a change through to a published commit.

Make exactly one change:

Create or overwrite the file ${CANARY_FILE} so that its entire contents are
the following two lines:

${NONCE}
deployed-sha=${DEPLOYED_SHA}

Create the containing directory if it does not exist. Change nothing else —
no other files, no formatting fixes, no improvements of any kind.

Then write commit.txt describing the change, as you would for any commit.

The verification that follows this job checks the remote branch for the exact
nonce above. If the file does not reach the remote, the deploy is reported as
broken, so do not finish without the change staged.
PROMPT_EOF

export BRANCH="$CANARY_BRANCH"
export BASE_BRANCH="$CANARY_BASE_BRANCH"
export CONTROLLER_URL
export DESCRIPTION="Post-deploy agent canary"
# The point is a published commit, so a run that changes nothing is a failure
# and should be retried rather than reported as success.
export ENFORCE_CHANGES=true
export AUTO_CREATE_PR=false
export CREATE_WORKSTREAM=true
# Small budget: the task is two lines in one file. A canary that can spend a
# real job's budget turns an agent-side hang into an expensive one.
export MAX_TURNS="${CANARY_MAX_TURNS:-30}"
export MAX_BUDGET_USD="${CANARY_MAX_BUDGET_USD:-5}"
# No review phase and no object-oriented design review: the change is a
# two-line file write with nothing to review, and each extra phase is another
# agent session that can stall or fail for reasons that say nothing about
# whether the fleet publishes.
export REVIEW_ENABLED=false
export OBJECT_ORIENTED_REVIEW_ENABLED=false

SUBMIT_OUT="$("${REPO_ROOT}/tools/ci/submit-agent-job.sh" "$PROMPT_FILE" 2>&1)"
SUBMIT_RC=$?
echo "$SUBMIT_OUT"

[ "$SUBMIT_RC" -eq 0 ] || die "Canary submission failed — the controller did not accept a job."

JOB_ID="$(printf '%s' "$SUBMIT_OUT" | sed -n 's/^job_id=//p' | head -1)"
WORKSTREAM_ID="$(printf '%s' "$SUBMIT_OUT" | sed -n 's/^workstream_id=//p' | head -1)"

# submit-agent-job.sh exits 0 for a skipped or rejected submission. For its
# own callers that is right — a skipped auto-resolve is not an error. For the
# canary it is fatal: no job ran, so the deploy is unverified, and reporting
# that as success is the exact substitution of "nothing went wrong" for
# "the thing worked" that this script exists to prevent.
[ -n "$JOB_ID" ] || die "Canary submission returned no job id (skipped or rejected). The deploy is UNVERIFIED — this is not a pass."

echo "::notice::Canary job ${JOB_ID} submitted; waiting for it to finish"

# ── Wait ────────────────────────────────────────────────────────────

TERMINAL_STATUS=""
deadline=$(( $(date +%s) + CANARY_TIMEOUT_SECONDS ))

while :; do
    body="$(curl -fsS "${CONTROLLER_URL}/api/jobs/${JOB_ID}" 2>/dev/null)"
    status="$(printf '%s' "$body" | jq -r '.status // empty' 2>/dev/null)"

    case "$(printf '%s' "$status" | tr '[:upper:]' '[:lower:]')" in
        success|succeeded|complete|completed|failed|error|cancelled|canceled|timeout)
            TERMINAL_STATUS="$status"
            break ;;
    esac

    queue=""
    if [ -n "$WORKSTREAM_ID" ]; then
        queue="$(curl -fsS "${CONTROLLER_URL}/api/workstreams/${WORKSTREAM_ID}/jobs/pending" 2>/dev/null \
            | pending_state "$JOB_ID")"
    fi

    now=$(date +%s)
    if [ "$now" -ge "$deadline" ]; then
        case "$queue" in
            SUBMITTED) TERMINAL_STATUS="still-queued" ;;
            LOST)      TERMINAL_STATUS="lost" ;;
            *)         TERMINAL_STATUS="timed-out-waiting" ;;
        esac
        echo "::warning::Canary job ${JOB_ID} still ${queue:-${status:-unknown}} after ${CANARY_TIMEOUT_SECONDS}s"
        break
    fi

    case "$queue" in
        SUBMITTED) echo "  queued, waiting for a worker — $(( deadline - now ))s left" ;;
        LOST)      echo "  queued and marked LOST by the controller — $(( deadline - now ))s left" ;;
        *)         echo "  ${status:-unknown} — $(( deadline - now ))s left" ;;
    esac
    sleep "$CANARY_POLL_SECONDS"
done

echo "Job ${JOB_ID} reported: ${TERMINAL_STATUS}"

# ── Verify against the remote ───────────────────────────────────────

echo "Checking ${CANARY_BRANCH} for the nonce"

if verify_nonce_landed "$NONCE" "$CANARY_BRANCH"; then
    report_outcome published
    echo "::notice::Canary passed — job ${JOB_ID} published ${NONCE} to ${CANARY_BRANCH}"
    exit 0
fi

# Nothing landed. Which way the job reported changes what this means.
case "$(printf '%s' "$TERMINAL_STATUS" | tr '[:upper:]' '[:lower:]')" in
still-queued)
    report_outcome queued
    echo "::error::UNVERIFIED, NOT FAILED: canary job ${JOB_ID} was still queued after ${CANARY_TIMEOUT_SECONDS}s, waiting for a worker. Nothing about the deployed fleet has been observed yet, so this deploy is neither proven working nor proven broken. Check the queue (other jobs submitted when intake reopened) and run the canary again." >&2
    exit 3
    ;;
lost)
    report_outcome lost
    die "Canary job ${JOB_ID} is LOST: the controller accepted it, no worker ever started it, and the controller has stopped expecting one to. No agent picked up work after this deploy. Treat this deploy as broken."
    ;;
success|succeeded|complete|completed)
    report_outcome silent-no-op
    die "SILENT NO-OP: job ${JOB_ID} reported '${TERMINAL_STATUS}' but nothing reached ${CANARY_BRANCH}. A job that reports success while publishing nothing is the failure this canary was built for — the fleet is lying about its results and no ordinary green build will show it. Treat this deploy as broken."
    ;;
*)
    report_outcome failed
    die "Canary failed: job ${JOB_ID} ended '${TERMINAL_STATUS}' and nothing reached ${CANARY_BRANCH}. The deployed fleet cannot complete a two-line change. Treat this deploy as broken."
    ;;
esac
