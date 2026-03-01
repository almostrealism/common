#!/usr/bin/env bash
# ─── Build the auto-resolve prompt for code policy violations ─────────
#
# Assembles a prompt for the coding agent when the Code Policy
# Enforcement step fails. This prompt explains the specific policies,
# forbids circumvention, and directs the agent to fix production code.
#
# Usage:
#   build-policy-violation-prompt.sh <output-file>
#
# Required environment variables:
#   BRANCH          - branch name where violations occurred
#   COMMIT_SHA      - commit SHA where violations occurred
#   RUN_URL         - URL to the GitHub Actions run
#
# Exit codes:
#   0 - prompt written successfully
#   1 - invalid arguments or missing env vars

set -euo pipefail

OUTPUT_FILE="${1:-}"

if [ -z "$OUTPUT_FILE" ]; then
    echo "Usage: $0 <output-file>" >&2
    exit 1
fi

for var in BRANCH COMMIT_SHA RUN_URL; do
    if [ -z "${!var:-}" ]; then
        echo "ERROR: ${var} is not set." >&2
        exit 1
    fi
done

cat > "$OUTPUT_FILE" <<'PROMPT'
## CODE POLICY VIOLATIONS DETECTED

The CI build for this branch failed because your code violates enforced code policies.
These policies exist to prevent patterns that defeat GPU acceleration, create
maintenance hazards, or violate architectural conventions.

### What you MUST NOT do — CHEATING WILL NOT BE TOLERATED

**You are absolutely forbidden from circumventing the policy enforcement system.**
Specifically, you MUST NOT:

- Modify `CodePolicyViolationDetector.java` (add exclusions, weaken patterns, etc.)
- Modify `DuplicateCodeDetector.java` (raise thresholds, add exceptions, etc.)
- Modify `TestTimeoutEnforcementScanner.java` (weaken scanning, add exceptions, etc.)
- Modify `CodePolicyEnforcementTest.java` (skip tests, add @Ignore, change assertions, etc.)
- Modify `detect-test-hiding.sh` or `check-quality-gates.sh`
- Add files or paths to any exclusion list in the enforcement infrastructure
- Add `@SuppressWarnings` annotations to hide violations
- Move or rename code to avoid detection patterns
- **Extract code into a separate method** to move a violation out of a loop's lexical scope.
  The detector uses lexical analysis. If you extract a `setMem()` call into a helper method
  so it no longer appears inside a `for` loop, that is **cheating** — the violation still
  exists at runtime. This INCLUDES naming the method to match initialization patterns
  (e.g., "prepare", "init", "create") to exploit the detector's whitelist.

**Example of cheating that WILL NOT be tolerated:**
```java
// CHEATING — extracting setMem into a method to hide it from the detector
for (Item item : items) {
    processItem(item);  // <-- setMem is hidden inside this method
}
private void processItem(Item item) {  // "process" or "prepare" matches whitelist
    item.getData().setMem(0, value);   // detector can't see this is inside a loop
}
```
The correct fix is to **eliminate the `setMem()` call entirely** by using the Producer
pattern, removing the call if it's redundant, or restructuring the data flow so that
CPU-side mutation is not needed.

Any modification to the enforcement infrastructure will be detected by the CI integrity
check and will cause the build to fail. The ONLY way to pass is to fix your code.

### Policies enforced

**1. PackedCollection GPU Memory Model**
`PackedCollection` is a handle to potentially GPU-resident memory. Code that uses CPU
loops with `setMem()`, `toDouble()`, `toArray()`, or `System.arraycopy` on
PackedCollection data defeats GPU parallelism and forces expensive CPU-GPU round trips.

**How to fix:** Use the Producer pattern:
```java
// WRONG — CPU loop
for (int i = 0; i < size; i++) {
    result.setMem(i, source.toDouble(i) * 2);
}

// CORRECT — GPU accelerated
CollectionProducer result = cp(source).multiply(2.0);
return result.evaluate();
```

**2. Features Interface Convention**
Interfaces named `*Features` must contain only `default` methods. They exist to provide
capabilities via composition, not to require implementations. If you need abstract
methods, use a different interface name.

**3. Test Timeout Enforcement**
Every `@Test` annotation must include a `timeout` parameter (e.g., `@Test(timeout = 30000)`).
Tests without timeouts can hang indefinitely and block CI runners.

**4. No Duplicate Code**
Blocks of 10+ identical lines across different source files are forbidden. Extract
shared logic into helper methods, utility classes, or use composition.

### Your task

1. Run the policy enforcement locally to see the exact violations:
   ```
   mcp__ar-test-runner__start_test_run module:"tools" test_classes:["CodePolicyEnforcementTest"] timeout_minutes:10
   ```

2. Read each violation — it tells you the file, line number, rule, and what to fix.

3. Fix the production code. Do not touch the enforcement infrastructure.

4. Re-run the enforcement test to confirm all violations are resolved.
PROMPT

# Append the dynamic portion
cat >> "$OUTPUT_FILE" <<EOF

### Branch context

Branch: ${BRANCH}
Commit: ${COMMIT_SHA}
CI run: ${RUN_URL}

Start by examining what this branch changed:
    git diff --stat origin/master...HEAD

Then run the enforcement test to see the specific violations.
EOF
