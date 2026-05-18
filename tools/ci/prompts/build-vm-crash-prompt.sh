#!/usr/bin/env bash
# ─── Build the auto-resolve prompt for a JVM crash ────────────────
#
# Assembles a natural-language prompt telling the coding agent that
# a test JVM crashed (OOM, SIGABRT, etc.) rather than producing
# normal test failures. Includes crash details from Maven output
# and .dumpstream files.
#
# Usage:
#   build-vm-crash-prompt.sh <crash-reports-dir> <output-file>
#
# Required environment variables:
#   BRANCH          - branch name where the crash occurred
#   COMMIT_SHA      - commit SHA where the crash occurred
#
# Exit codes:
#   0 - prompt written successfully
#   1 - invalid arguments or missing env vars

set -euo pipefail

CRASH_DIR="${1:-}"
OUTPUT_FILE="${2:-}"

if [ -z "$CRASH_DIR" ] || [ -z "$OUTPUT_FILE" ]; then
    echo "Usage: $0 <crash-reports-dir> <output-file>" >&2
    exit 1
fi

for var in BRANCH COMMIT_SHA; do
    if [ -z "${!var:-}" ]; then
        echo "ERROR: ${var} is not set." >&2
        exit 1
    fi
done

# ── Read crash summary if available ────────────────────────────────
CRASH_SUMMARY=""
if [ -f "${CRASH_DIR}/crash-summary.txt" ]; then
    CRASH_SUMMARY=$(cat "${CRASH_DIR}/crash-summary.txt")
fi

# ── Extract crashed test class names from the summary ──────────────
CRASHED_CLASSES=""
if [ -n "$CRASH_SUMMARY" ]; then
    # Surefire reports crashed classes like:
    #   "Crashed tests: org.almostrealism.SomeTest"
    #   Or in error lines referencing test classes
    CRASHED_CLASSES=$(echo "$CRASH_SUMMARY" | grep -oP '(?:Crashed tests?:\s*|org\.almostrealism\.\S*Test)\S*' | \
        sed 's/^Crashed tests*:\s*//' | sort -u | tr '\n' ' ' || true)
fi

# ── Determine modules from crashed classes ─────────────────────────
CRASHED_MODULES=""
for class in $CRASHED_CLASSES; do
    # Map package prefixes to modules
    case "$class" in
        *ml.*|*model.*|*network.*) module="ml" ;;
        *audio.*) module="audio" ;;
        *music.*) module="music" ;;
        *compose.*) module="compose" ;;
        *) module="utils" ;;
    esac
    if ! echo "$CRASHED_MODULES" | grep -qw "$module" 2>/dev/null; then
        CRASHED_MODULES="${CRASHED_MODULES:+$CRASHED_MODULES }$module"
    fi
done

# ── Build MCP test runner commands ─────────────────────────────────
CI_COMMANDS=""
if [ -n "$CRASHED_CLASSES" ]; then
    for module in $CRASHED_MODULES; do
        # Collect classes for this module
        classes_for_module=""
        for class in $CRASHED_CLASSES; do
            case "$class" in
                *ml.*|*model.*|*network.*)
                    [ "$module" = "ml" ] && classes_for_module="${classes_for_module:+$classes_for_module, }\"$class\"" ;;
                *audio.*)
                    [ "$module" = "audio" ] && classes_for_module="${classes_for_module:+$classes_for_module, }\"$class\"" ;;
                *music.*)
                    [ "$module" = "music" ] && classes_for_module="${classes_for_module:+$classes_for_module, }\"$class\"" ;;
                *compose.*)
                    [ "$module" = "compose" ] && classes_for_module="${classes_for_module:+$classes_for_module, }\"$class\"" ;;
                *)
                    [ "$module" = "utils" ] && classes_for_module="${classes_for_module:+$classes_for_module, }\"$class\"" ;;
            esac
        done

        profile_arg=""
        if [ "$module" = "ml" ]; then
            profile_arg=" profile:\"pipeline\""
        fi

        CI_COMMANDS="${CI_COMMANDS}
  mcp__ar-test-runner__start_test_run module:\"${module}\"${profile_arg} jmx_monitoring:true test_classes:[${classes_for_module}]"
    done
fi

if [ -z "$CI_COMMANDS" ]; then
    CI_COMMANDS="
  Could not auto-detect crashed test classes. Examine the crash report below,
  identify which module the crashed test belongs to, and run:
    mcp__ar-test-runner__start_test_run module:\"<module>\" jmx_monitoring:true
  For ML module tests, add profile:\"pipeline\"."
fi

# ── Write the prompt ───────────────────────────────────────────────
cat > "$OUTPUT_FILE" <<'PROMPT_HEADER'
## THIS IS A JVM CRASH — NOT A BUILD FAILURE OR TEST FAILURE

**A test JVM process crashed during execution.** The JVM exited abnormally (e.g.,
OutOfMemoryError, SIGABRT, SIGSEGV, System.exit) before completing its test run.
Because the JVM crashed, Maven Surefire could not produce XML test reports — that
is why there are no parseable test failures.

**This is NOT a compilation error.** Do NOT run `mvn install -DskipTests`.
**This is NOT a normal test failure.** The JVM died before tests could report results.

---

## ABSOLUTE RULE: DO NOT MODIFY EXISTING TESTS TO HIDE FAILURES

Before you touch ANY test file, you MUST determine whether the test existed on the
base branch (master) before this branch was created. Run:

    git log --oneline origin/master -- <path/to/TestFile.java>

**DO NOT modify tests that exist on the base branch.** The test is correct.
Your branch broke it. Fix the production code, not the test.

---

## AUTOMATED ENFORCEMENT

Your commit will be validated by `validate-agent-commit.sh` which BLOCKS:
1. Modifications to test files that exist on the base branch (exit code 2)
2. Modifications to CI/workflow files (exit code 4)
3. Commits with no production code or branch-new test changes when fixing test failures (exit code 3)

**There is no way around these checks. They are mechanical, not judgment-based.**

---

PROMPT_HEADER

cat >> "$OUTPUT_FILE" <<EOF
## Crash details

Branch: "${BRANCH}" (commit ${COMMIT_SHA})

${CRASH_SUMMARY}

---

## Common JVM crash causes

| Exit Code | Signal  | Likely Cause |
|-----------|---------|--------------|
| 137       | SIGKILL | Out of memory (OOM killer) |
| 134       | SIGABRT | JVM abort (assertion failure, native crash) |
| 139       | SIGSEGV | Segmentation fault (JNI/native code bug) |
| 1         | —       | Unhandled exception or System.exit(1) |
| 143       | SIGTERM | Process terminated externally |

If the crash is memory-related (exit code 137 or OutOfMemoryError in the output),
the root cause is almost always that branch changes increased memory consumption
beyond the configured limit (AR_HARDWARE_MEMORY_SCALE=7 → ~32GB with FP32).

---

## How to reproduce and diagnose (REQUIRED)

Use the MCP test runner with **JMX monitoring enabled** to get memory diagnostics:
${CI_COMMANDS}

JMX monitoring gives you access to:
- \`get_heap_summary\` — heap pool sizes and GC totals
- \`get_class_histogram\` — live object counts by class
- \`start_jfr_recording\` / \`get_allocation_report\` — allocation profiling
- \`get_memory_timeline\` — heap growth trend over time

**Use these tools to identify what is consuming memory** rather than guessing.

---

## Investigation steps

1. **Read the crash details above.** Identify the exit code and any crashed test
   class names. The exit code tells you the crash category (see table above).

2. **See what this branch changed:**
       git diff --stat origin/master...HEAD
   Focus on changes that could increase memory usage: new allocations, larger
   tensors, removed cleanup, changed loop bounds, new model layers.

3. **Reproduce with JMX monitoring.** Use the command above. While the test runs,
   take heap snapshots to identify what is growing:
   - \`get_heap_summary\` — check if old gen is filling up
   - \`get_class_histogram\` — find which classes have the most instances/bytes
   - \`start_memory_monitor\` then \`get_memory_timeline\` — watch growth rate

4. **Trace the memory growth to your branch's changes.** The histogram will show
   which object types are accumulating. Cross-reference with the branch diff to
   find the code path that creates them.

5. **Fix the production code.** Common fixes:
   - Reduce intermediate tensor allocations
   - Add proper cleanup/destroy calls for PackedCollection objects
   - Fix resource leaks (unclosed Evaluable instances, leaked OperationList)
   - Reduce model size parameters that were inadvertently increased
   - Use AR_HARDWARE_MEMORY_SCALE appropriately if the test genuinely needs more

6. **Verify by running the full test with the commands above.** The test must
   complete without crashing.

**Remember: if a test exists on the base branch, the test is the specification — fix
the production code. Modifications to base-branch test files or CI files will be rejected.**
EOF
