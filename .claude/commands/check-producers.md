# Check Producer Pattern Compliance

Scan the codebase for Producer pattern violations. The Almost Realism framework requires
that ALL computation be expressed as `CollectionProducer` compositions — Java is the
orchestration language, not the execution language.

## Steps

1. Call `mcp__ar-consultant__consult` with question about the file or change in question
   and keywords like `["ProducerPatternDetector", "evaluate in computation"]`

2. Run the build validator to check code policy:
   ```
   mcp__ar-build-validator__start_validation checks:["code_policy"]
   ```
   Poll `mcp__ar-build-validator__get_validation_status` until complete, then call
   `mcp__ar-build-validator__get_validation_violations` for structured file:line results.
   Pass `skip_build:true` if the project is already compiled.

3. For each violation found, diagnose and fix using the Producer pattern:

   ```java
   // WRONG: host-side math
   for (int i = 0; i < size; i++) {
       result.setMem(i, weights.toDouble(i) * input.toDouble(i));
   }

   // CORRECT: Producer composition
   CollectionProducer result = cp(weights).multiply(cp(input));
   ```

4. For pipeline boundaries where .evaluate() is genuinely needed (data loaders,
   sampling loop boundaries, test methods), add the file name to the appropriate
   allowlist in `ProducerPatternDetector`.

$ARGUMENTS
