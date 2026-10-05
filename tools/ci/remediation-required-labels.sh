#!/usr/bin/env bash
# ─── Decide which Node an auto-resolve agent must run on ─────────────
#
# A test failure on the Metal lanes (test-mac, test-media-mac, and their
# matrix entries) can only be reproduced where Metal is available. An agent
# placed on a Node without it cannot run the failing tests, so it can only
# guess at a fix. When any such lane failed in the attempt being resolved,
# this pins the submission to a macOS Node.
#
# The decision is read from the GitHub API for the completed run, never from
# the staged request: that request was written by the pull request's own
# code, and where a job runs is not something it gets to choose (see
# submit-staged-request.sh).
#
# Failing to list the jobs is an error rather than "no labels". An unpinned
# submission for a Metal failure is the outcome this script exists to
# prevent, and it would look exactly like a correct one.
#
# Usage:
#   remediation-required-labels.sh
#
# Required environment variables:
#   GH_TOKEN      - token for the jobs query
#   REPO          - owner/repo of the run
#   RUN_ID        - the completed "Build and Test" run
#   RUN_ATTEMPT   - the attempt whose failures are being resolved
#
# Outputs (to stdout, and to $GITHUB_OUTPUT when set):
#   required_labels=<JSON object, or empty when any Node will do>
#
# Exit codes:
#   0 - decision made
#   1 - missing arguments, or the jobs could not be listed

set -euo pipefail

: "${REPO:?REPO is required}"
: "${RUN_ID:?RUN_ID is required}"
: "${RUN_ATTEMPT:?RUN_ATTEMPT is required}"

MACOS_LABELS='{"platform":"macos"}'

emit() {
    echo "required_labels=$1"
    if [ -n "${GITHUB_OUTPUT:-}" ]; then
        echo "required_labels=$1" >> "$GITHUB_OUTPUT"
    fi
}

FAILED_JOBS=$(gh api --paginate \
    "/repos/$REPO/actions/runs/$RUN_ID/attempts/$RUN_ATTEMPT/jobs" \
    --jq '.jobs[] | select(.conclusion == "failure" or .conclusion == "timed_out") | .name') || {
    echo "::error::Could not list the jobs of run $RUN_ID attempt $RUN_ATTEMPT — cannot tell whether a Metal lane failed"
    exit 1
}

while IFS= read -r name; do
    case "$name" in
        # test-mac, test-media-mac, and matrix entries such as "test-mac (2)".
        test-*mac|test-*mac\ *)
            echo "::notice::Metal lane failed ($name) — the agent must run on a macOS Node"
            emit "$MACOS_LABELS"
            exit 0
            ;;
    esac
done <<< "$FAILED_JOBS"

echo "::notice::No Metal lane failed — any Node may take the job"
emit ""
