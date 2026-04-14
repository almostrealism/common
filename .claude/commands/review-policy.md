# Code Policy Review

Perform a full code policy review of changed files. Checks:
- Producer pattern (no .evaluate() or .toDouble() in computation layers)
- PackedCollection CPU usage (no setMem loops, no arraycopy)
- Naming conventions (*Cell implements Cell, *Block implements Block)
- Features interfaces (only default methods allowed)
- No System.out — use ConsoleFeatures
- No code duplication (CodePolicyEnforcementTest#enforceNoDuplicateCode)

## Steps

1. `mcp__ar-consultant__recall` with query "producer pattern enforcement policy violations"
2. Run the build validator for all policy checks:
   ```
   mcp__ar-build-validator__start_validation checks:["checkstyle","code_policy","test_timeouts","duplicate_code"]
   ```
   Poll status with `mcp__ar-build-validator__get_validation_status`, then call
   `mcp__ar-build-validator__get_validation_violations` for structured file:line results.
   Use `skip_build:true` if the project is already compiled.
3. Report violations with specific file:line locations and suggested fixes
4. Store findings in memory: `mcp__ar-consultant__remember`

$ARGUMENTS
