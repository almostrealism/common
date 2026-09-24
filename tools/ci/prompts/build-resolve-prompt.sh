#!/usr/bin/env bash
# ─── Build the auto-resolve prompt for the FlowTree agent ───────────
#
# Reads a failure list file and assembles a natural-language prompt
# for the coding agent.
#
# Usage:
#   build-resolve-prompt.sh <failures-file> <output-file>
#
# Required environment variables:
#   FAILURE_COUNT   - number of failures (for the prompt text)
#   BRANCH          - branch name where failures occurred
#   COMMIT_SHA      - commit SHA where failures occurred
#
# Exit codes:
#   0 - prompt written successfully
#   1 - invalid arguments or missing env vars

set -euo pipefail

FAILURES_FILE="${1:-}"
OUTPUT_FILE="${2:-}"

if [ -z "$FAILURES_FILE" ] || [ -z "$OUTPUT_FILE" ]; then
    echo "Usage: $0 <failures-file> <output-file>" >&2
    exit 1
fi

for var in FAILURE_COUNT BRANCH COMMIT_SHA; do
    if [ -z "${!var:-}" ]; then
        echo "ERROR: ${var} is not set." >&2
        exit 1
    fi
done

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=prompt-render.sh
source "${SCRIPT_DIR}/prompt-render.sh"

FAILURE_LIST=$(cat "$FAILURES_FILE")

cat > "$OUTPUT_FILE" <<'PROMPT_HEADER'
## YOU BROKE THESE TESTS. YOU MUST FIX THEM.

**This is not a request. This is not optional. The tests listed below PASS on master
and FAIL on your branch. The changes on your branch caused these failures. Your ONLY
job is to fix the production code so these tests pass again.**

### What you are NOT allowed to do

**Conclude that no fix is needed.** The tests fail. A fix IS needed. Period.

**Claim the tests "pass individually" as proof of nothing.** CI may run these tests
together with others (shared state, ordering, resource contention), so a pass in
isolation does not by itself rule out an ordering-dependent cause. Reproduce and
verify with the exact test_methods commands listed below; if you have reason to
believe the failure is order-dependent, say so explicitly rather than running the
whole module suite yourself -- that investigation belongs to CI.

**Blame the CI environment or call it "transient."** These failures are reproducible.
They pass on master, they fail on this branch. Every time. If you claim otherwise
without actually running the CI command locally and showing it succeeds, you are wrong.

**Browse GitHub Actions or other CI pages with WebFetch.** You do not have permission
to access GitHub web pages and you do not need to. Everything you need is in this
prompt, in the branch diff, and in the pull request's review threads — which the
GitHub MCP tools read for you, as the next section explains.

**Look at a single commit and declare it "fine."** The problem is the ENTIRE set of
changes between origin/master and this branch. Run `git diff origin/master...HEAD` to
see what changed. Do not cherry-pick one commit to review.

### What you MUST do

**Understand the full branch delta.** Run:
    git diff --stat origin/master...HEAD
This shows EVERY file changed on this branch relative to master. The test failures
were caused by one or more of these changes.

**Read the failing test(s).** Understand what they assert.

**Trace from the test to the production code.** Find what the test calls, read that
code, and identify where the branch changes broke the expected behavior.

**Fix the production code.** Make the minimal change needed so the tests pass.

**Reproduce the failure locally, then verify your fix.** Use the MCP test runner to
run only the specific failing test(s) listed below, one at a time. Broad verification
(a whole module's suite, a CI shard) belongs to CI, not this session. If each failing
test now passes, your fix works. If one still fails, keep investigating.

---

## ABSOLUTE RULE: DO NOT MODIFY EXISTING TESTS TO HIDE FAILURES

Before you touch ANY test file, you MUST determine whether the test existed on the
base branch (master) before this branch was created. Run:

    git log --oneline origin/master -- <path/to/TestFile.java>

**DO NOT modify tests that exist on the base branch.** Specifically, do not:
- Change @TestDepth values, timeout values, dimensions, or tolerances in base-branch tests
- Add @Ignore, @Disabled, skipLongTests, or try/catch blocks to base-branch tests
- Reduce numeric constants or increase tolerance/epsilon values in base-branch tests
- Modify ANY file under .github/workflows/ or tools/ci/
- Claim "the test is wrong" - if the test exists on master, it is the specification
- Claim "the test is flaky" - if CI reports failure, the failure is real
- Claim "the failure is unrelated to my changes" without PROOF (see below)

**Tests that exist on the base branch are the specification. Fix the production code.**
**Modifications to base-branch test methods will be automatically rejected by
validate-agent-commit.sh.** You MAY fix tests that your branch introduced, you MAY
add new test methods to an existing test class, and you MAY edit the fixtures and
helpers of a test class — none of those can hide the failure of an existing test.

---

## AUTOMATED ENFORCEMENT

Your commit will be validated by `validate-agent-commit.sh` which BLOCKS:
1. Changes to, or removal of, test methods that exist on the base branch (exit code 2)
2. Modifications to CI/workflow files (exit code 4)
3. Commits with no production code, branch-new test, or newly added test method when
   fixing test failures (exit code 3)

Additionally, `detect-test-hiding.sh` checks for 12 specific evasion patterns
including TestDepth escalation, timeout inflation, dimension reduction,
tolerance weakening, assertion removal, and numeric literal shrinkage.

**There is no way around these checks. They are mechanical, not judgment-based.**


---

PROMPT_HEADER

append_prompt_fragment pr-feedback.txt "$OUTPUT_FILE" BRANCH

# ── Determine which modules contain failures and build CI commands ──
# Parse class#method names from the failure list and map each class to its
# Maven module. The CI command section names the SPECIFIC failing method via
# test_methods so the agent reproduces narrowly, one test at a time -- never
# a bare module run (a whole-module suite) and never a bare class selector
# (every method in that class), both of which agents may never run; see
# ci/test-execution-limits.
FAILING_MODULES=""
# Maps a module name to its space-joined "Class#method" pairs. An associative
# array (not `eval "MODULE_METHODS_${module}=..."`) so a crafted failure name
# is stored as data, never reparsed as shell code -- a nested Java test class
# name like `Outer$InnerTest` would otherwise be expanded again by `eval` as
# a `$InnerTest` variable reference, and a crafted name could inject a command
# substitution.
declare -A MODULE_METHODS_MAP
while IFS= read -r line; do
    # Only process lines that start with "- " (test name lines).
    # Skip exception details, stack traces, and blank lines.
    case "$line" in
        "- "*)
            full_name=$(echo "$line" | sed 's/^- //')
            class_name="${full_name%%#*}"
            method_name="${full_name#*#}"
            # Resolve to a source file
            src_file=$(find . -path "*/src/test/java*/${class_name##*.}.java" -print -quit 2>/dev/null)
            if [ -n "$src_file" ]; then
                # Extract the module from the path (first directory component under ./)
                module=$(echo "$src_file" | sed 's|^\./||' | cut -d/ -f1)
                if ! echo "$FAILING_MODULES" | grep -qw "$module"; then
                    FAILING_MODULES="${FAILING_MODULES:+$FAILING_MODULES }$module"
                fi
                short_class="${class_name##*.}"
                pair="${short_class}#${method_name}"
                existing="${MODULE_METHODS_MAP[$module]:-}"
                if ! echo "$existing" | grep -qw "$pair"; then
                    MODULE_METHODS_MAP[$module]="${existing:+$existing }${pair}"
                fi
            fi
            ;;
    esac
done < "$FAILURES_FILE"

# Build CI reproduction commands for each failing module: one invocation per
# failing method, never a bare module-wide run, never a bare class selector
# (which would run every method in that class), and never several methods
# grouped into one test_methods list -- one test per invocation, same as
# every other surface this rule covers.
CI_COMMANDS=""
for module in $FAILING_MODULES; do
    pairs="${MODULE_METHODS_MAP[$module]:-}"
    CI_COMMANDS="${CI_COMMANDS}
Module: ${module}"
    for pair in $pairs; do
        pair_class="${pair%%#*}"
        pair_method="${pair#*#}"
        if [ "$module" = "ml" ]; then
            CI_COMMANDS="${CI_COMMANDS}
  mcp__ar-test-runner__start_test_run module:\"${module}\" test_methods:[{\"class\":\"${pair_class}\",\"method\":\"${pair_method}\"}] profile:\"pipeline\""
        else
            CI_COMMANDS="${CI_COMMANDS}
  mcp__ar-test-runner__start_test_run module:\"${module}\" test_methods:[{\"class\":\"${pair_class}\",\"method\":\"${pair_method}\"}]"
        fi
    done
done

# If we couldn't determine modules, provide a generic fallback
if [ -z "$CI_COMMANDS" ]; then
    CI_COMMANDS="
Could not auto-detect failing modules. Examine the failing test class#method names
below, find which module they belong to (utils, ml, audio, music, compose), and run
ONLY that specific method:
  mcp__ar-test-runner__start_test_run module:\"<module>\" test_methods:[{\"class\":\"<FailingClass>\",\"method\":\"<failingMethod>\"}]
For ML module tests, add profile:\"pipeline\". Never omit test_methods, and never pass
a bare class with no method -- either runs the module's or class's whole suite, which
is not permitted."
fi

# Now append the dynamic portion
cat >> "$OUTPUT_FILE" <<EOF
## Failing tests

The following ${FAILURE_COUNT} test failure(s) were discovered on branch "${BRANCH}".
Each failure includes the exception type, message, and a truncated stack trace from the
Surefire XML report. **Use these details to understand what went wrong** — the exception
type and message tell you exactly what assertion or error occurred.

${FAILURE_LIST}

These tests PASS on origin/master. They FAIL on this branch. The branch changes broke them.

## How to reproduce (REQUIRED)

You MUST reproduce the failure locally before attempting a fix and after applying
your fix. Use the MCP test runner with these exact commands -- one invocation per
failing method via \`test_methods\`, never a bare module run and never a bare
class selector (which would run every method in that class):
${CI_COMMANDS}

Run only the specific failing test(s), one at a time. Do NOT run the module's whole
suite and do NOT reference \`AR_TEST_GROUP\`/\`AR_TEST_GROUPS\` to reproduce CI's shard
ordering -- if a failure genuinely only manifests alongside other tests (shared state,
ordering), say so explicitly in your summary instead of running the whole suite
yourself; broad verification is CI's job.

## Investigation steps

1. **Read the exception details above.** The exception type and message tell you
   exactly what failed. An \`AssertionError\` with \`expected 5 but was 3\` is a
   completely different problem from a \`NullPointerException\` in production code.
   Start your investigation from the specific error, not from guessing.

2. **See what this branch changed:**
       git diff --stat origin/master...HEAD
   This is the complete set of changes that could have caused the failures.

3. **Read the failing test(s).** Understand what they assert.

4. **Trace the failure to your branch's changes.** Use the stack trace to identify
   which production code threw the exception, then check whether any of the branch
   changes affected that code path.

5. **Fix the production code.** Make the minimal change needed.

6. **Verify by re-running the specific failing test(s) above, one at a time.** If
   each one now passes, your fix works.

7. **Run the build validator to confirm your fix doesn't introduce new style or policy issues:**
   \`\`\`
   mcp__ar-build-validator__start_validation skip_build:true
   \`\`\`
   This checks checkstyle, code policy, test timeouts, and duplicate code without re-running
   the full build (since the project is already compiled at this point).
   Poll \`mcp__ar-build-validator__get_validation_status\` until done, then check
   \`mcp__ar-build-validator__get_validation_violations\` for any new violations.

**Remember: if a test exists on the base branch, the test is the specification — fix
the production code. Modifications to base-branch test files or CI files will be rejected.**
EOF
