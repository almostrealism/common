#!/usr/bin/env bash
# ─── CI/workflow file lock ───────────────────────────────────────────
#
# A branch may not change the pipeline unless the pipeline is the declared
# subject of the branch. Files under .github/workflows/ and tools/ci/ may be
# changed only on a branch named ci/..., or by a commit carrying a
# controller-signed Sensitive-File-Bypass trailer. Agents have previously
# excluded failing tests from CI steps, removed quality gates and rewired
# job dependencies so that tests never ran; a change to the pipeline riding
# along with unrelated work is how that happens.
#
# The ci/ branch name is chosen before the work starts and is visible in the
# pull request, so it is a declaration that the change IS the pipeline,
# reviewed as such — it cannot be adopted after the fact to rescue a commit
# that was really about something else.
#
# The signed bypass: a commit whose message carries
# `Sensitive-File-Bypass: <job-id>=<signature>`, signed by the controller
# with AR_AGENT_BYPASS_SECRET, is authorised to change locked files. The
# authorisation belongs to that commit alone: the lock is lifted only when
# EVERY commit on the branch that touches a locked file carries its own valid
# trailer, so an earlier authorised job cannot cover a later, unrelated
# change. The secret is not in an agent's environment and the harness strips
# any agent-written trailer, so an agent cannot authorise itself (see
# SensitiveFileBypassTrailer in flowtree/runtime and
# verify-sensitive-bypass.sh). Without the secret no bypass is possible,
# which is the safe direction to fail.
#
# The check needs nothing but git, so the pipeline runs it in its first job
# (`changes`) and a violating branch fails before anything is built.
#
# Usage:
#   check-ci-file-lock.sh <base-branch>
#
# Exit codes:
#   0 - no locked file changed, or the branch is exempt
#   1 - the branch could not be diffed (no evidence is not a pass)
#   4 - BLOCKED: CI/workflow files changed outside a ci/ branch
#
# Outputs (to GITHUB_OUTPUT if available):
#   blocked=true|false
#   modified_ci_files=<count>

set -euo pipefail

BASE_BRANCH="${1:-}"

if [ -z "$BASE_BRANCH" ]; then
    echo "Usage: $0 <base-branch>" >&2
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERIFY_BYPASS="${SCRIPT_DIR}/verify-sensitive-bypass.sh"

CI_BRANCH_PATTERN='^ci/'
# Anchored at the start of a repository-relative path so that only the CI
# directories match and an unrelated path like vendor/tools/ci/example does
# not. .github/actions/ is covered as well, because the harness (FileStager)
# treats it as CI and the two enforcement sides must agree on the same scope.
LOCKED_PATH_PATTERN='^(\.github/workflows/|\.github/actions/|tools/ci/)'

output() {
    if [ -n "${GITHUB_OUTPUT:-}" ]; then
        echo "$1" >> "$GITHUB_OUTPUT"
    fi
}

# The workspace is usually a detached checkout of a pull request head,
# where `git branch --show-current` is empty, so the Actions environment
# is consulted first: GITHUB_HEAD_REF names the source branch of a pull
# request, GITHUB_REF_NAME the branch of a direct push.
current_branch() {
    if [ -n "${GITHUB_HEAD_REF:-}" ]; then
        printf '%s\n' "$GITHUB_HEAD_REF"
    elif [ -n "${GITHUB_REF_NAME:-}" ]; then
        printf '%s\n' "$GITHUB_REF_NAME"
    else
        git branch --show-current 2>/dev/null || true
    fi
}

# Whether every commit on the branch that touches a locked file carries its
# own valid trailer. A merge commit is judged by what it changes relative to
# all of its parents (`diff-tree -c`), so merging the base branch in does not
# count the base branch's own CI changes against this one. The verified job
# IDs are echoed for the audit trail.
every_locked_commit_is_signed() {
    local sha msgfile jobid signed=""

    [ -n "${AR_AGENT_BYPASS_SECRET:-}" ] || return 1
    [ -r "$VERIFY_BYPASS" ] || return 1

    msgfile=$(mktemp)
    trap 'rm -f "$msgfile"' RETURN

    local tree
    for sha in $(git rev-list "${BASE_BRANCH}..HEAD"); do
        # Capture the commit's changed paths first rather than piping straight
        # into grep: under `set -o pipefail` a `grep -q` that matches early
        # exits before consuming the stream, sending SIGPIPE to `git diff-tree`
        # and making the pipeline exit non-zero even on a match — a failure
        # `|| continue` would then swallow, skipping the lock for that commit.
        # A failed diff-tree is a reason to stop, not to pass.
        if ! tree=$(git diff-tree -c --no-commit-id --name-only -r "$sha" 2>&1); then
            echo "Cannot inspect commit ${sha} — the CI file lock cannot be checked:" >&2
            echo "$tree" >&2
            return 1
        fi
        grep -qE "$LOCKED_PATH_PATTERN" <<< "$tree" || continue
        git log -1 --format='%B' "$sha" > "$msgfile"
        if ! jobid=$(bash "$VERIFY_BYPASS" "$msgfile" 2>/dev/null); then
            echo "Commit ${sha} changes a CI/workflow file without a valid bypass trailer." >&2
            return 1
        fi
        signed="${signed:+$signed, }${jobid}"
    done

    [ -n "$signed" ] || return 1
    printf '%s\n' "$signed"
}

CURRENT_BRANCH="$(current_branch)"

# Renames are not detected, so moving a workflow file out of the locked
# directories still shows its disappearance from them.
if ! CHANGED=$(git diff --name-only --no-renames "${BASE_BRANCH}...HEAD" 2>&1); then
    echo "Cannot diff ${BASE_BRANCH}...HEAD — the CI file lock cannot be checked:" >&2
    echo "$CHANGED" >&2
    output "blocked=true"
    exit 1
fi

LOCKED=$(printf '%s\n' "$CHANGED" | grep -E "$LOCKED_PATH_PATTERN" || true)
LOCKED_COUNT=$(printf '%s\n' "$LOCKED" | grep -c '[^[:space:]]' || true)
output "modified_ci_files=$LOCKED_COUNT"

if [ "$LOCKED_COUNT" -eq 0 ]; then
    echo "No CI/workflow files changed."
    output "blocked=false"
    exit 0
fi

if [ -n "$CURRENT_BRANCH" ] && printf '%s\n' "$CURRENT_BRANCH" | grep -qE "$CI_BRANCH_PATTERN"; then
    echo "Branch ${CURRENT_BRANCH} is a CI branch — ${LOCKED_COUNT} CI/workflow file(s) may change on it."
    output "blocked=false"
    exit 0
fi

if JOB_IDS=$(every_locked_commit_is_signed); then
    echo "Sensitive-file bypass verified for every CI-changing commit (job ${JOB_IDS}) — the CI file lock is lifted."
    output "blocked=false"
    exit 0
fi

echo "::error::CI/workflow files changed on ${CURRENT_BRANCH:-this branch}, which is not a ci/... branch"
echo ""
echo "Files under .github/workflows/ and tools/ci/ may change only on a branch named"
echo "ci/..., chosen before the work starts because the pipeline is its subject."
echo "Move this change to such a branch, or revert it here."
echo ""
echo "Changed CI/workflow files:"
printf '%s\n' "$LOCKED" | sed 's/^/  - /'
output "blocked=true"
exit 4
