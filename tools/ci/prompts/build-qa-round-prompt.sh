#!/usr/bin/env bash
# ─── Build a QA-round prompt for the FlowTree agent ──────────────────
#
# The one prompt builder the reusable qa-round.yaml workflow runs. Every
# recurring QA round used to have its own build-<round>-prompt.sh that
# validated a fixed variable list and substituted it by hand; this builder
# replaces all of them with one that works from inputs.
#
# It renders either a framework built-in template chosen by a short selector,
# or a template the calling repository supplies by path — read as data from the
# caller's checkout and never executed, exactly as implement-plan.yaml reads a
# dispatched branch's plan. The round's variables are substituted and the
# builder fails if any ${...} placeholder survives, since nothing downstream
# inspects the prompt and a forgotten one would travel into the agent's
# instructions verbatim.
#
# Usage:
#   build-qa-round-prompt.sh <caller-root> <output-file>
#
# Required environment variables:
#   BRANCH          - branch name for the round's work
#   BASE_BRANCH     - base branch (the caller's default branch)
#   PROMPT          - a built-in selector (defect-hunt | doc-qa |
#                     consolidation | flaky-hunt), or the path, relative to
#                     <caller-root>, of a template the caller supplies
#
# Optional environment variables:
#   REPO_URL             - the round's repository, substituted as ${REPO_URL}
#   TEMPLATE_VARS        - JSON object of extra ${NAME} substitutions, e.g.
#                          {"REVIEW_SINCE":"14 days ago"}. Keys must be
#                          upper-case identifiers.
#   TEST_RUNNER_FRAGMENT - the published fragment a built-in template includes
#                          for test-runner / tooling guidance (default:
#                          test-runner-maven.txt). A non-Maven consumer passes
#                          test-runner-generic.txt (or another published one).
#
# Exit codes:
#   0 - prompt written successfully
#   1 - invalid arguments, missing env vars, or an unsubstituted placeholder

set -euo pipefail

CALLER_ROOT="${1:-}"
OUTPUT_FILE="${2:-}"

if [ -z "$CALLER_ROOT" ] || [ -z "$OUTPUT_FILE" ]; then
    echo "Usage: $0 <caller-root> <output-file>" >&2
    exit 1
fi

for var in BRANCH BASE_BRANCH PROMPT; do
    if [ -z "${!var:-}" ]; then
        echo "ERROR: ${var} is not set." >&2
        exit 1
    fi
done

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=tools/ci/prompts/prompt-render.sh
source "${SCRIPT_DIR}/prompt-render.sh"

# Always available to every template; REPO_URL is substituted even when empty,
# so a template that does not name it is unaffected.
export REPO_URL="${REPO_URL:-}"
export TEST_RUNNER_FRAGMENT="${TEST_RUNNER_FRAGMENT:-test-runner-maven.txt}"

# A built-in selector names a framework template; anything else is a path,
# relative to the caller's checkout, read as data. Reject an absolute path or
# one that climbs out of the checkout.
case "$PROMPT" in
    defect-hunt|doc-qa|consolidation|flaky-hunt)
        TEMPLATE="${SCRIPT_DIR}/${PROMPT}.txt"
        ;;
    /*|../*|*/../*|*/..)
        echo "ERROR: a caller template path must be relative and stay inside the checkout: ${PROMPT}" >&2
        exit 1
        ;;
    *)
        TEMPLATE="${CALLER_ROOT%/}/${PROMPT}"
        ;;
esac

if [ ! -f "$TEMPLATE" ]; then
    echo "ERROR: Template not found at ${TEMPLATE}" >&2
    exit 1
fi

# The names substituted into the template: the three the workflow always
# supplies, plus every key the caller listed in TEMPLATE_VARS.
NAMES=(BRANCH BASE_BRANCH REPO_URL)
# TODO(review): TEMPLATE_VARS keys are exported unchecked, so PATH, BRANCH or TEST_RUNNER_FRAGMENT can be overridden; reject reserved names.

if [ -n "${TEMPLATE_VARS:-}" ]; then
    # Captured by command substitution rather than read from a process
    # substitution, so that set -e fails the build when TEMPLATE_VARS is
    # rejected instead of rendering on without it.
    PAIRS="$(python3 -c '
import json, os, sys
raw = os.environ.get("TEMPLATE_VARS", "").strip()
if not raw:
    sys.exit(0)
try:
    data = json.loads(raw)
except ValueError as exc:
    sys.stderr.write("TEMPLATE_VARS is not valid JSON: %s\n" % exc)
    sys.exit(1)
if not isinstance(data, dict):
    sys.stderr.write("TEMPLATE_VARS must be a JSON object\n")
    sys.exit(1)
for key, value in data.items():
    if not (key.isidentifier() and key.isupper()):
        sys.stderr.write("TEMPLATE_VARS key is not an upper-case identifier: %r\n" % key)
        sys.exit(1)
    if not isinstance(value, str):
        sys.stderr.write("TEMPLATE_VARS value for %s must be a string\n" % key)
        sys.exit(1)
    if "\n" in value:
        sys.stderr.write("TEMPLATE_VARS value for %s must be a single line\n" % key)
        sys.exit(1)
    print("%s=%s" % (key, value))
')"
    while IFS= read -r pair; do
        [ -z "$pair" ] && continue
        key="${pair%%=*}"
        value="${pair#*=}"
        export "${key}=${value}"
        NAMES+=("$key")
    done <<< "$PAIRS"
fi

# Render to a temporary file and move it into place only once it is whole, so a
# failed render (a bad @include, say) never leaves a partial prompt behind.
RENDERED="$(mktemp)"
if ! render_prompt "$TEMPLATE" "${NAMES[@]}" > "$RENDERED"; then
    rm -f "$RENDERED"
    exit 1
fi

if grep -q '\${' "$RENDERED"; then
    echo "ERROR: an unsubstituted \${...} placeholder survived into the rendered prompt:" >&2
    grep -n '\${' "$RENDERED" >&2
    rm -f "$RENDERED"
    exit 1
fi

mv "$RENDERED" "$OUTPUT_FILE"
echo "QA-round prompt written to ${OUTPUT_FILE} ($(wc -l < "$OUTPUT_FILE") lines)"
