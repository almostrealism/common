# Plan: @TestDepth Standardization, TestProperties Annotation, and TestSuiteBase Compliance

**Date:** 2026-03-10 (revised 2026-03-11)
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

Additionally, test properties like `skipLongTests`, `skipHighMemTests`, and
`skipKnownIssues` are evaluated via inline `if` guards scattered across test methods.
This pattern is error-prone (easy to forget), not machine-readable, and inconsistent
with the declarative annotation model established by `@TestDepth`.

This task connects to the larger vision in two ways:

1. **Faster iteration cycles** — When CI can skip expensive tests for routine changes,
   development velocity increases. This matters for both human contributors and AI
   agents working on the platform.
2. **Self-describing test infrastructure** — Declarative annotations (`@TestDepth`,
   `@TestProperties`) make the test suite's cost structure and requirements explicit
   and machine-readable. A model reasoning about the codebase can understand which
   tests are expensive and why, enabling smarter test selection strategies.

## Scope

### Phase 1: Create @TestProperties Annotation and Enforcement (HIGH PRIORITY)

Create a `@TestProperties` annotation that replaces the inline `if (skipX) return;`
pattern with a declarative mechanism enforced by `TestDepthRule`.

**New annotation:**

```java
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface TestProperties {
    boolean longRunning() default false;
    boolean highMemory() default false;
    boolean knownIssue() default false;
}
```

**Enforcement in `TestDepthRule.apply()`:** When a method is annotated with
`@TestProperties`, the rule checks the corresponding `TestSettings` flags and skips
the test via `Assume.assumeTrue()` — identical to how `@TestDepth` is enforced today.

**Migration scope (inline guards to remove):**

| Flag | Test files | Occurrences |
|------|-----------|-------------|
| `skipLongTests` | ~10 | ~21 |
| `skipHighMemTests` | 1 | 1 |
| `skipKnownIssues` | ~33 | ~44 |

### Phase 2: Annotate Expensive Timeout-Bearing Tests (HIGH PRIORITY)

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

### Phase 3: Fix TestSuiteBase Violations (MEDIUM PRIORITY)

Fix test classes that do not extend `TestSuiteBase`. There are 36 such classes.
For each:

1. If the class has no base class, change it to extend `TestSuiteBase`
2. If the class extends a custom base class (e.g., `AudioSceneTestBase`), verify
   that the base class extends `TestSuiteBase`. If not, fix the base class.
3. If the class only implements `TestFeatures`, replace with `extends TestSuiteBase`

**Caution:** Changing a base class like `AudioSceneTestBase` to extend `TestSuiteBase`
may introduce `@Before`/`@After` lifecycle behavior that existing tests don't expect.
Verify each base class change doesn't break existing tests.

**Key targets:**

- `ChordProgressionManagerTest.java` — No base class at all
- `Conv1dCorrectnessTest.java` — Implements `LayerFeatures` only
- `AsyncResultDeliveryTest.java` — Implements `TestFeatures` only
- `MidiCCSourceTest.java`, `MidiInputListenerTest.java` — Implement `TestFeatures`
- `SpatialDrawingTest.java` — No base class
- Multiple `studio/compose` tests extending `AudioSceneTestBase` — Verify
  `AudioSceneTestBase` extends `TestSuiteBase`

### Phase 4: Migrate Inline Guards to @TestProperties

Replace all `if (skipLongTests) return;`, `if (skipHighMemTests) return;`, and
`if (skipKnownIssues) return;` guards with `@TestProperties` annotations. This is
mechanical: for each occurrence, add the annotation to the method and remove the
guard line.

**Example migration:**

Before:
```java
@Test
public void myTest() {
    if (skipKnownIssues) return;
    // test body
}
```

After:
```java
@Test
@TestProperties(knownIssue = true)
public void myTest() {
    // test body
}
```

Methods with multiple flags get multiple properties:
```java
@TestProperties(longRunning = true, knownIssue = true)
```

## Approach

All changes are made interactively in a supervised session due to CI agent protection
rules that prohibit automated agents from modifying test files on the base branch.

1. **Phase 1** — Create `TestProperties.java` annotation and update `TestDepthRule`
   to enforce it. Build to verify.

2. **Phase 2** — Annotate expensive timeout-bearing tests with `@TestDepth`. This
   is mechanical: read each file, identify methods with timeouts >= 60s, add annotation.

3. **Phase 3** — Fix TestSuiteBase violations. For each class, determine the correct
   fix and apply it. Verify compilation after each base class change.

4. **Phase 4** — Migrate inline guards to `@TestProperties`. Work module by module.
   After each module, verify compilation.

5. **Verify the build** — Run `mvn clean install -DskipTests` to confirm all
   changes compile correctly.

6. **Run sample tests** — Execute a few modified test classes at different
   `AR_TEST_DEPTH` levels and with different skip flags to verify both `@TestDepth`
   and `@TestProperties` filtering work as expected.

## Success Criteria

1. `@TestProperties` annotation exists and is enforced by `TestDepthRule`
2. All `if (skipLongTests) return;` / `if (skipHighMemTests) return;` /
   `if (skipKnownIssues) return;` guards are replaced with `@TestProperties`
3. All test methods with explicit timeouts >= 60 seconds have `@TestDepth` annotations
4. All test classes that can reasonably extend `TestSuiteBase` do so (target: reduce
   the 36 violations to < 10, accounting for legitimate exceptions)
5. `mvn clean install -DskipTests` passes with all changes applied
6. Running tests with `AR_TEST_DEPTH=1` correctly skips `@TestDepth(2)` methods
7. Running tests with `AR_KNOWN_ISSUES=false` correctly skips `@TestProperties(knownIssue = true)` methods

## Dependencies

- None. This task builds on the existing `TestSuiteBase` and `@TestDepth` infrastructure
  which is already in place and working.

## Status

**In Progress** — Working interactively in a supervised session. Previous automated
attempt was reverted due to CI agent protection rules.

## Notes

- Do NOT change test logic or assertions — only add annotations, fix class hierarchy,
  and remove inline guards
- Do NOT modify tests that are intentionally lightweight (even if they lack `@TestDepth`,
  fast tests don't need it)
- When uncertain about depth value, prefer `@TestDepth(2)` as the default for tests
  with 60+ second timeouts
- The `@TestDepth` import is `org.almostrealism.util.TestDepth`
- The `@TestProperties` import will be `org.almostrealism.util.TestProperties`
- Phase 3 base class changes may introduce lifecycle side effects — test after each change
