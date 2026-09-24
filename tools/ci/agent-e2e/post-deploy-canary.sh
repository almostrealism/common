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
#                                   against a scratch repository (offline,
#                                   no agent run, no cost)
#
# Environment:
#   CONTROLLER_URL        controller base URL (default http://localhost:7780)
#   CANARY_BRANCH         branch the agent works on (default agent-canary)
#   CANARY_REPO_DIR       working repository to fetch from (default: this one)
#   CANARY_REMOTE         remote to fetch (default origin)
#   CANARY_BASE_BRANCH    base branch          (default master)
#   CANARY_NONCE          nonce to use/verify  (default: generated)
#   CANARY_TIMEOUT_SECONDS  give up waiting after this long (default 1800)
#   CANARY_POLL_SECONDS   seconds between polls (default 20)
#   REPO_URL              repository to submit against (default: this one)
#   CF_ACCESS_CLIENT_ID / CF_ACCESS_CLIENT_SECRET  passed through to submit
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"

CONTROLLER_URL="${CONTROLLER_URL:-http://localhost:7780}"
CONTROLLER_URL="${CONTROLLER_URL%/}"
CANARY_BRANCH="${CANARY_BRANCH:-agent-canary}"
CANARY_BASE_BRANCH="${CANARY_BASE_BRANCH:-master}"
CANARY_TIMEOUT_SECONDS="${CANARY_TIMEOUT_SECONDS:-1800}"
CANARY_POLL_SECONDS="${CANARY_POLL_SECONDS:-20}"

# The file the agent is asked to write. Kept inside the e2e tooling so its
# purpose is obvious to anyone who finds it in a diff.
CANARY_FILE="tools/ci/agent-e2e/canary/last-run.txt"

die() { echo "::error::$*" >&2; exit 1; }

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

SUBMIT_OUT="$("${REPO_ROOT}/tools/ci/submit-agent-job.sh" "$PROMPT_FILE" 2>&1)"
SUBMIT_RC=$?
echo "$SUBMIT_OUT"

[ "$SUBMIT_RC" -eq 0 ] || die "Canary submission failed — the controller did not accept a job."

JOB_ID="$(printf '%s' "$SUBMIT_OUT" | sed -n 's/^job_id=//p' | head -1)"

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

    now=$(date +%s)
    if [ "$now" -ge "$deadline" ]; then
        TERMINAL_STATUS="timed-out-waiting"
        echo "::warning::Canary job ${JOB_ID} still ${status:-unknown} after ${CANARY_TIMEOUT_SECONDS}s"
        break
    fi

    echo "  ${status:-unknown} — $(( deadline - now ))s left"
    sleep "$CANARY_POLL_SECONDS"
done

echo "Job ${JOB_ID} reported: ${TERMINAL_STATUS}"

# ── Verify against the remote ───────────────────────────────────────

echo "Checking ${CANARY_BRANCH} for the nonce"

if verify_nonce_landed "$NONCE" "$CANARY_BRANCH"; then
    echo "::notice::Canary passed — job ${JOB_ID} published ${NONCE} to ${CANARY_BRANCH}"
    exit 0
fi

# Nothing landed. Which way the job reported changes what this means.
case "$(printf '%s' "$TERMINAL_STATUS" | tr '[:upper:]' '[:lower:]')" in
success|succeeded|complete|completed)
    die "SILENT NO-OP: job ${JOB_ID} reported '${TERMINAL_STATUS}' but nothing reached ${CANARY_BRANCH}. A job that reports success while publishing nothing is the failure this canary was built for — the fleet is lying about its results and no ordinary green build will show it. Treat this deploy as broken."
    ;;
*)
    die "Canary failed: job ${JOB_ID} ended '${TERMINAL_STATUS}' and nothing reached ${CANARY_BRANCH}. The deployed fleet cannot complete a two-line change. Treat this deploy as broken."
    ;;
esac
