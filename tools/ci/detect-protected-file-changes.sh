#!/usr/bin/env bash
# ─── Grant a remediation job the right to edit the protected files its ───
#     branch already changes
#
# The automatic remediation jobs (auto-review, auto-resolve, auto-resolve-python)
# run with the default sensitive-file protections, so a branch whose own work is
# in a protected file — anything under .claude/, or a CI/workflow file on a
# non-ci/ branch — produces a review that can read the findings but cannot fix
# them. This script looks at what the branch changes relative to its base and,
# when that set includes a protected file, emits the operator grant the agent
# needs to edit that kind of file. A branch that changes no protected file is
# left with today's protections.
#
# Trusted by construction: the changed-file list comes from the GitHub compare
# API, read with the workflow's own token, never from the pull request's
# checkout or a staged request (which the submit path treats as untrusted). It
# runs in the same trusted jobs that call submit-staged-request.sh /
# submit-agent-job.sh, before the submit step, and writes the grants to
# $GITHUB_ENV so submit-agent-job.sh picks them up from the environment.
#
# The two grants map to the two gates that stand in the way:
#   - A CI/workflow file (.github/workflows/, .github/actions/, tools/ci/) is
#     blocked by the harness CI-file staging lock and check-ci-file-lock.sh on
#     any branch not named ci/..., so a branch that changes one and is not a
#     ci/ branch gets SENSITIVE_FILE_PROTECTION_ENABLED=false (the controller
#     then signs the Sensitive-File-Bypass trailer). A ci/ branch is already
#     exempt from both locks, so it needs no grant for these.
#   - Anything under .claude/ is blocked by the agent runtime's own per-call
#     refusal of writes it deems sensitive, which no branch name lifts, so a
#     branch that changes one gets BYPASS_AGENT_PERMISSION_PROMPTS=true.
# Test files (src/test/, src/it/) are governed by PROTECT_TEST_FILES, a separate
# per-job decision, and are deliberately not considered here.
#
# Usage:
#   detect-protected-file-changes.sh
#
# Required environment:
#   BRANCH            - the branch the job will work on (the compare head)
#   BASE_BRANCH       - the branch to compare against
#   GITHUB_REPOSITORY - owner/repo, for the compare API
#   GH_TOKEN          - a token with contents:read (gh reads it)
#
# Optional environment:
#   HEAD_SHA          - the exact head commit to compare; defaults to BRANCH
#
# Output:
#   Appends SENSITIVE_FILE_PROTECTION_ENABLED / BYPASS_AGENT_PERMISSION_PROMPTS
#   to $GITHUB_ENV when a grant is warranted, and notes what it granted.
#
# Exit codes:
#   0 - evaluated (whether or not a grant was emitted)
#   1 - a required input was missing, or the changed files could not be listed
#       (no evidence is not the same as "no protected files changed", so the
#       grant is withheld and the step fails rather than silently under-granting)

set -euo pipefail

: "${BRANCH:?BRANCH is required}"
: "${BASE_BRANCH:?BASE_BRANCH is required}"
: "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"

HEAD_REF="${HEAD_SHA:-$BRANCH}"

# Anchored at the start of a repository-relative path, matching
# check-ci-file-lock.sh's LOCKED_PATH_PATTERN and the harness's CI-file scope.
CI_FILE_PATTERN='^(\.github/workflows/|\.github/actions/|tools/ci/)'
CLAUDE_FILE_PATTERN='^\.claude/'

# --paginate splits the comparison by commits (gh asks for 100 per page), and
# the compare API lists the changed files on the first page only, so a branch
# with more than 100 commits yields later pages without a file list. The filter
# treats a missing list as empty; `.files[]` alone would fail on those pages and
# withhold the grant from every long branch.
# TODO(review): the compare API returns at most 300 files, so a protected file past that cap goes ungranted (fail-safe, but silent).
if ! CHANGED=$(gh api --paginate \
        "repos/${GITHUB_REPOSITORY}/compare/${BASE_BRANCH}...${HEAD_REF}" \
        --jq '(.files // [])[].filename' 2>&1); then
    echo "::error::Could not list the files ${BRANCH} changes relative to ${BASE_BRANCH}; withholding the sensitive-file grant"
    echo "$CHANGED" >&2
    exit 1
fi

emit() {
    if [ -n "${GITHUB_ENV:-}" ]; then
        echo "$1=$2" >> "$GITHUB_ENV"
    fi
    echo "::notice::Branch ${BRANCH} changes a protected file — granting $1=$2"
}

# Match against the changed-file list with a here-string rather than a pipe.
# Under `set -o pipefail`, `grep -q` can close the pipe the moment it sees a
# match, which delivers SIGPIPE to an upstream `printf` still writing a large
# list; pipefail would then surface that 141 as the whole expression's status
# and the grant would be silently skipped. A here-string has no upstream
# process to signal, so the early exit is harmless.

# A CI/workflow file blocked unless the branch declares the pipeline as its
# subject with a ci/ name. On a ci/ branch the locks are already lifted.
if grep -qE "$CI_FILE_PATTERN" <<< "$CHANGED"; then
    if [[ "$BRANCH" == ci/* ]]; then
        echo "::notice::Branch ${BRANCH} changes CI/workflow files but is a ci/ branch, which is already exempt — no sensitive-file grant needed for them"
    else
        emit SENSITIVE_FILE_PROTECTION_ENABLED false
    fi
fi

# Anything under .claude/ is refused by the agent runtime regardless of branch.
if grep -qE "$CLAUDE_FILE_PATTERN" <<< "$CHANGED"; then
    emit BYPASS_AGENT_PERMISSION_PROMPTS true
fi
