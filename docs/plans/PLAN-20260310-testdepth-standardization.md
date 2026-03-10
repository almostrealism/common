# Plan: @TestDepth Standardization and TestSuiteBase Compliance

**Date:** 2026-03-10
**Category:** Code Quality
**Estimated Complexity:** Medium

## Motivation

The Almost Realism test suite has 1,898 test methods across 284 test classes. The
`@TestDepth` annotation mechanism allows CI pipelines to filter expensive tests by
setting `AR_TEST_DEPTH`, ensuring fast feedback loops for routine changes while still
running comprehensive tests on demand.

However, adoption is critically low:

- **Only 99 of 1,898 test methods (5.2%) have `@TestDepth` annotations**
- **36 of 284 test classes (12.7%) do not extend `TestSuiteBase`**, bypassing depth
  filtering entirely
- **20+ expensive tests with 60-120 second timeouts lack any depth annotation**,
  meaning they run unconditionally regardless of `AR_TEST_DEPTH`

The infrastructure is sound — 87.3% of test classes already extend `TestSuiteBase` —
but the annotation mechanism is severely underutilized. This means CI cannot
effectively distinguish quick smoke tests from expensive integration tests, leading
to longer pipeline times and slower developer feedback.

This task connects to the larger vision in two ways:

1. **Faster iteration cycles** — When CI can skip expensive tests for routine changes,
   development velocity increases. This matters for both human contributors and AI
   agents working on the platform.
2. **Self-describing test infrastructure** — `@TestDepth` annotations make the test
   suite's cost structure explicit and machine-readable. A model reasoning about the
   codebase can understand which tests are expensive and why, enabling smarter test
   selection strategies.

## Scope

### Phase 1: Annotate Expensive Timeout-Bearing Tests (HIGH PRIORITY)

Add `@TestDepth` annotations to all test methods that have explicit timeouts >= 60
seconds but lack depth annotations. These are the most impactful targets because
they are both expensive and currently unfiltered.

**Target files (identified by investigation):**

| File | Module | Methods | Timeout |
|------|--------|---------|---------|
| `GrainTest.java` | studio/music | 3 methods | 120s |
| `SequenceTest.java` | studio/music | 5+ methods | 60-120s |
| `EnvelopeTests.java` | studio/music | 14 methods | 60s |
| `AggressiveFineTuningTest.java` | studio/compose | 3+ methods | varies |
| `OobleckLayerValidationTest.java` | engine/ml | 1+ methods | 120s |
| `SimilarityOverheadTest.java` | engine/utils | 9 methods | 120s |
| `LoopedSumDiagnosticTest.java` | engine/utils | 3 methods | 60s |
| `AudioSceneRealTimeCorrectnessTest.java` | studio/compose | multiple | varies |

**Depth value assignment convention:**

- `@TestDepth(1)` — Moderately expensive: 10-30 second timeouts, significant
  computation but no external resources (model weights, audio files)
- `@TestDepth(2)` — Expensive: 30-120 second timeouts, file I/O, model compilation,
  audio rendering
- `@TestDepth(3)` — Very expensive: 120+ second timeouts, model training, inference
  chains, large dataset processing
- `skipLongTests` guard — Tests taking 30+ minutes (existing convention per
  `docs/internals/test-examples.md`)

### Phase 2: Fix TestSuiteBase Violations (MEDIUM PRIORITY)

Fix test classes that do not extend `TestSuiteBase`. There are 36 such classes.
For each:

1. If the class has no base class, change it to extend `TestSuiteBase`
2. If the class extends a custom base class (e.g., `AudioSceneTestBase`), verify
   that the base class extends `TestSuiteBase`. If not, fix the base class.
3. If the class only implements `TestFeatures`, replace with `extends TestSuiteBase`

**Key targets:**

- `ChordProgressionManagerTest.java` — No base class at all
- `Conv1dCorrectnessTest.java` — Implements `LayerFeatures` only
- `AsyncResultDeliveryTest.java` — Implements `TestFeatures` only
- `MidiCCSourceTest.java`, `MidiInputListenerTest.java` — Implement `TestFeatures`
- `SpatialDrawingTest.java` — No base class
- Multiple `studio/compose` tests extending `AudioSceneTestBase` — Verify
  `AudioSceneTestBase` extends `TestSuiteBase`

### Phase 3: Systematic Review of Remaining Tests (LOWER PRIORITY)

After Phases 1 and 2, perform a broader review of test methods that lack
`@TestDepth` but may still warrant annotation:

- Tests that load files from disk (audio samples, model weights)
- Tests that perform network operations
- Tests that compile complex expression trees (kernel compilation overhead)
- Tests with `Thread.sleep()` calls

This phase is lower priority because the highest-impact tests are covered in
Phase 1. The remaining tests are likely quick enough to run unconditionally.

## Approach

1. **Start with Phase 1** — This is mechanical and high-impact. For each file:
   - Read the test class
   - Identify methods with explicit timeouts >= 60 seconds
   - Add `@TestDepth(N)` with the appropriate depth value
   - Ensure the `@TestDepth` import is present

2. **Move to Phase 2** — For each non-compliant class:
   - Determine the correct fix (extend TestSuiteBase, fix base class, etc.)
   - Apply the fix
   - Verify the class still compiles

3. **Verify the build** — Run `mvn clean install -DskipTests` to confirm all
   changes compile correctly.

4. **Run a sample test** — Execute a few of the modified test classes at different
   `AR_TEST_DEPTH` levels to verify filtering works as expected.

## Success Criteria

1. All test methods with explicit timeouts >= 60 seconds have `@TestDepth` annotations
2. All test classes that can reasonably extend `TestSuiteBase` do so (target: reduce
   the 36 violations to < 10, accounting for legitimate exceptions)
3. `@TestDepth` adoption increases from 5.2% to at least 10% (covering the most
   expensive tests)
4. `mvn clean install -DskipTests` passes with all changes applied
5. Running tests with `AR_TEST_DEPTH=1` correctly skips the newly-annotated expensive
   tests

## Dependencies

- None. This task builds on the existing `TestSuiteBase` and `@TestDepth` infrastructure
  which is already in place and working.

## Status

**Reverted** — The Phase 1 and Phase 2 changes were reverted because CI agent
protection rules prohibit agents from modifying test files that exist on the
base branch (see `tools/ci/agent-protection/validate-agent-commit.sh`). The
`@TestDepth` annotations and `TestSuiteBase` fixes must be applied by a human
contributor or through a branch where the test files are not protected.

## Notes

- Do NOT change test logic or assertions — only add annotations and fix class hierarchy
- Do NOT modify tests that are intentionally lightweight (even if they lack `@TestDepth`,
  fast tests don't need it)
- When uncertain about depth value, prefer `@TestDepth(2)` as the default for tests
  with 60+ second timeouts
- The `@TestDepth` import is `org.almostrealism.util.TestDepth` (verify before adding)
