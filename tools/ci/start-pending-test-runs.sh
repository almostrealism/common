#!/usr/bin/env bash
# ─── Start the test runs that open pull requests are waiting for ────
#
# A pull request cannot merge until a test run (a deployment of its branch to
# the `test` environment) has passed on its head commit. The FlowTree
# controller starts that run when the branch's review loop settles, but only
# for a workstream that carries the settled-CI trigger, and only on a job that
# ends after the trigger was set. A pull request whose loop settled earlier
# waits forever. This script is the catch-up a person runs with their own `gh`
# login; CI never depends on it.
#
# It does four things, in order, and reports what it would do unless --apply
# is given:
#
#   1. Re-runs master's latest push run of "Build and Test" when it was
#      cancelled (a non-test deployment used to cancel it from the queue). A
#      re-run on master gets a concurrency group of its own, so nothing
#      displaces it.
#   2. For every open pull request against master whose head branch is in
#      this repository, creates a `test` deployment of that branch, unless
#      its head commit already has one. A branch whose analysis.yaml predates
#      the validate/test split has no deployment trigger; it is reported, not
#      deployed, and must merge master first.
#   3. Reports whether "All Checks" and "Test Checks" are required status
#      checks on master. With --require-test-checks it adds "Test Checks"
#      (an addition: the existing required checks are kept).
#   4. Prints the steps that need someone with access to the controller host.
#
# Never deploys a fork's commit: a fork pull request is skipped, and every
# deployment names a branch of this repository, never a SHA or a pull ref
# (see "Starting a test run" in .github/CLAUDE.md).
#
# Usage:
#   tools/ci/start-pending-test-runs.sh                    # report only
#   tools/ci/start-pending-test-runs.sh --apply            # do it
#   tools/ci/start-pending-test-runs.sh --apply --require-test-checks
#
# Environment:
#   REPO  - owner/name (default: almostrealism/common)
#   BASE  - base branch (default: master)
#
# Requires `gh` (logged in with repo and deployment write access) and `jq`.

set -euo pipefail

REPO="${REPO:-almostrealism/common}"
BASE="${BASE:-master}"
APPLY=false
REQUIRE_TEST_CHECKS=false

for arg in "$@"; do
    case "$arg" in
        --apply) APPLY=true ;;
        --require-test-checks) REQUIRE_TEST_CHECKS=true ;;
        -h|--help) sed -n '2,42p' "$0"; exit 0 ;;
        *) echo "Unknown argument: $arg" >&2; exit 2 ;;
    esac
done

for tool in gh jq; do
    command -v "$tool" >/dev/null || { echo "ERROR: $tool is not installed." >&2; exit 1; }
done
gh auth status >/dev/null 2>&1 || { echo "ERROR: gh is not logged in (run: gh auth login)." >&2; exit 1; }

# A marker that exists only in an analysis.yaml with the deployment trigger:
# the job that cancels superseded test runs came with the validate/test split.
SPLIT_MARKER="cancel-superseded-test-runs"

if [ "$APPLY" = "true" ]; then
    echo "Mode: apply"
else
    echo "Mode: report only (pass --apply to make changes)"
fi
echo "Repository: $REPO (base: $BASE)"

# Runs a mutating command, or prints it in report mode.
act() {
    if [ "$APPLY" = "true" ]; then
        "$@"
    else
        echo "    would run: $*"
    fi
}

# ─── 1. Master's latest push run ────────────────────────────────────
echo
echo "== 1. Master's latest push run =="
LATEST=$(gh api "repos/$REPO/actions/workflows/analysis.yaml/runs?branch=$BASE&event=push&per_page=1" \
    --jq '.workflow_runs[0] | [.id, .status, (.conclusion // ""), .head_sha[0:9], .display_title] | @tsv')
if [ -z "$LATEST" ]; then
    echo "  No push run found."
else
    IFS=$'\t' read -r RUN_ID RUN_STATUS RUN_CONCLUSION RUN_SHA RUN_TITLE <<< "$LATEST"
    echo "  Run $RUN_ID ($RUN_SHA, $RUN_TITLE): $RUN_STATUS ${RUN_CONCLUSION}"
    if [ "$RUN_STATUS" = "completed" ] && [ "$RUN_CONCLUSION" = "cancelled" ]; then
        echo "  Cancelled: re-running it."
        act gh run rerun "$RUN_ID" --repo "$REPO"
    else
        echo "  Nothing to do."
    fi
fi

# ─── 2. Test runs for open pull requests ────────────────────────────
echo
echo "== 2. Test runs for open pull requests =="
DEPLOYED=0
# A newline-separated list, not an array: under `set -u` the system bash on
# macOS (3.2) treats an empty array's expansion as an unbound variable.
NEEDS_MERGE=""
PRS=$(gh api --paginate "repos/$REPO/pulls?state=open&base=$BASE&per_page=100" \
    --jq '.[] | [.number, .head.ref, .head.sha, .head.repo.full_name // ""] | @tsv')

while IFS=$'\t' read -r NUMBER BRANCH SHA HEAD_REPO; do
    [ -z "${NUMBER:-}" ] && continue
    LABEL="  #$NUMBER $BRANCH (${SHA:0:9})"
    if [ "$HEAD_REPO" != "$REPO" ]; then
        echo "$LABEL: fork ($HEAD_REPO), skipped (a fork's commit is never deployed)"
        continue
    fi

    EXISTING=$(gh api "repos/$REPO/deployments?sha=$SHA&environment=test&per_page=1" --jq 'length')
    if [ "$EXISTING" != "0" ]; then
        echo "$LABEL: head already has a test deployment"
        continue
    fi

    WORKFLOW=$(gh api -H "Accept: application/vnd.github.raw" \
        "repos/$REPO/contents/.github/workflows/analysis.yaml?ref=$SHA" 2>/dev/null || true)
    case "$WORKFLOW" in
        *"$SPLIT_MARKER"*) ;;
        *)
            echo "$LABEL: analysis.yaml predates the validate/test split; merge $BASE into it first"
            NEEDS_MERGE="${NEEDS_MERGE}    #$NUMBER $BRANCH"$'\n'
            continue
            ;;
    esac

    echo "$LABEL: starting a test run"
    BODY=$(jq -cn --arg ref "$BRANCH" \
        '{ref: $ref, environment: "test", auto_merge: false,
          required_contexts: [], transient_environment: true,
          description: "Test run started by start-pending-test-runs.sh"}')
    if [ "$APPLY" = "true" ]; then
        ID=$(echo "$BODY" | gh api --method POST "repos/$REPO/deployments" --input - --jq '.id')
        echo "    deployment $ID"
    else
        echo "    would POST repos/$REPO/deployments $BODY"
    fi
    DEPLOYED=$((DEPLOYED + 1))
done <<< "$PRS"

echo "  Test runs $( [ "$APPLY" = "true" ] && echo started || echo to start ): $DEPLOYED"
if [ -n "$NEEDS_MERGE" ]; then
    echo "  Branches that must merge $BASE before they can have a test run:"
    printf '%s' "$NEEDS_MERGE"
fi

# ─── 3. Required status checks on master ────────────────────────────
echo
echo "== 3. Required status checks on $BASE =="
REQUIRED=$(gh api "repos/$REPO/branches/$BASE/protection/required_status_checks" \
    --jq '[(.contexts // [])[], ((.checks // [])[] | .context)] | unique | .[]' 2>/dev/null || true)
if [ -z "$REQUIRED" ]; then
    echo "  Could not read branch protection (it needs admin access), or none is set."
else
    for check in "All Checks" "Test Checks"; do
        if printf '%s\n' "$REQUIRED" | grep -qx "$check"; then
            echo "  $check: required"
        else
            echo "  $check: NOT required"
            if [ "$check" = "Test Checks" ] && [ "$REQUIRE_TEST_CHECKS" = "true" ]; then
                act gh api --method POST \
                    "repos/$REPO/branches/$BASE/protection/required_status_checks/contexts" \
                    -f "contexts[]=Test Checks"
            elif [ "$check" = "Test Checks" ]; then
                echo "    Pass --require-test-checks to add it. GitHub only lists a check"
                echo "    that has reported in the last 7 days; the master run from step 1"
                echo "    reports it."
            fi
        fi
    done
fi

# ─── 4. Steps outside GitHub ────────────────────────────────────────
echo
echo "== 4. Remaining steps that need the controller host =="
cat <<'EOF'
  - Redeploy the controller stack (Actions > Deploy Controller Stack). The
    change that gives a workstream created by a job submission its settled-CI
    trigger takes effect only on the redeployed controller.
  - The controller creates test deployments with the workstream
    organisation's GitHub token. That token needs Deployments: read and
    write on this repository, or every settled-CI trigger fails.
EOF
