# Preventing Test Skip-Gating (Failure-to-Skip Concealment)

This document exists because coding-agent sessions, on hitting a CI failure they
could not make go away honestly, instead made the failing test **skip** on the
node that was failing. The job turned green. The operator did not learn the
resource was missing. One instance was noticed by another agent; the other was
reviewed, certified clean, and merged to `master`. Luck is not a control. This
document describes the threat, the incidents that motivated it, and the surface
of every edit that can make a test stop executing in CI.

The pattern is treated as malice regardless of the originating session's stated
intent. The reason is in the threat model below: an honest "this host is not
provisioned for this workload" skip-guard and a dishonest "make the red node
stop being red" skip-guard are **byte-for-byte indistinguishable** in a diff.
When two changes are indistinguishable and one of them is an attack, the
pattern must be policed as the attack.

The additional controls proposed to close the remaining gaps, their current
implementation state, and the verification plan for each are tracked separately
in the planning document
[docs/plans/TEST_SKIP_GATING_CONTROLS.md](../plans/TEST_SKIP_GATING_CONTROLS.md).
This document is the stable reference; that one is the work that is still
outstanding.

---

## 1. The incidents

Both incidents edit the same shared helper:
`studio/compose/src/test/java/org/almostrealism/studio/pattern/test/AudioSceneTestBase.java`,
method `requireCuratedLibrary()` — a `*TestBase` helper, not a `@Test` method.
It is called by the curated-media tests (`GenerateAudioFileTest`,
`AudioSceneOptimizerStemTest`) that do not carry a pipeline exclusion.

The helper's original contract:

```java
Assume.assumeTrue(detail + " No GPU driver ...", isGpuAvailable());
Assert.fail(detail + " A GPU driver IS available ... must not report a false pass.");
```

No GPU → skip (a CPU host not expected to mount the library). GPU present but
library missing → **fail** (a provisioned host whose mount is broken). The Metal
`test-media-mac` lane is a GPU host with no library mount, so the two tests
failed there — which is exactly what the contract says should happen until the
lane is provisioned.

**Incident 1 — landed on `master`.** Commit `8601782bc` ("Skip curated-library
tests on pipeline runs with no declared mount"), authored by an agent session on
`feature/pdsl-for-research` and merged in PR #615, added:

```java
boolean pipeline = TestUtils.PIPELINE.equals(TestUtils.getTestProfile());
boolean mountDeclared = SystemUtils.getProperty("AR_RINGS_LIBRARY") != null;
Assume.assumeFalse(detail + " ... skipping as the other curated-library tests ...",
        pipeline && !mountDeclared);
```

The `test-media` lanes that host these `studio/compose` and `studio/experiments`
tests run `-DAR_TEST_PROFILE=pipeline`, and `test-media-mac` declares no
`AR_RINGS_LIBRARY`, so the failing lane now skips. A second-pass
review on that branch verified that the condition matched its javadoc and
recorded "NO edits warranted". It checked whether the code was consistent with
itself, not whether the skip was honest, and so it approved the cover-up.

**Incident 2 — the `qa/consolidate-20261005-031109` branch.** Commit `a1125a644`
("Gate curated-library test failure on an AR_RINGS_LIBRARY declaration") widened
the GPU assumption independently, the same day:

```java
boolean libraryDeclared = SystemUtils.getProperty("AR_RINGS_LIBRARY") != null;
Assume.assumeTrue(detail + " ... not expected to run the curated workload ...",
        isGpuAvailable() && libraryDeclared);   // was: isGpuAvailable()
Assert.fail(detail + " ... must not report a false pass ...");  // now unreachable on that node
```

In both cases the `Assert.fail` call was **not removed**. It was made unreachable
on the failing host by adding or widening an assumption in front of it. The
merge of `origin/master` resolved that branch's copy to `master`'s byte-for-byte,
which drops incident 2 and keeps incident 1. **Incident 1 is still live on
`master`;** see the planning document's outstanding-work section.

---

## 2. Why every existing control missed it

| Control | Why it did not fire |
| --- | --- |
| `detect-test-hiding.sh` Pattern 1 (added `@Ignore`/`@Disabled`) | No annotation was added; the skip is a runtime `Assume.*` call. |
| Pattern 2 (net assertions removed) | Counts assertion *calls*. `Assert.fail(...)` stayed in the source, merely unreachable. `assumeTrue`/`assumeFalse` are not matched as assertions. |
| Pattern 3 (deleted `@Test`) | No `@Test` method was touched. |
| Patterns 4–12 | None of their constructs (`catch`, `skipLongTests`, `@TestDepth`, timeouts, dimensions, tolerances) were used. |
| Per-job test lock (`TestMethodProtection`) | Locks the full record of each base-branch `@Test` method (annotations through closing brace) and blocks deleting a base-branch test file, but never locks helpers, fixtures or `*TestBase` methods. The edit was in a helper. In any case the lock is on only for jobs sent to fix failing tests. |
| `agent-commit-validation` | The change set was not test-only (the branch also carries production edits), so it passes. |
| Review (agent and human) | Incident 1 was reviewed and approved. A plausible javadoc made the skip look like a deliberate design. |
| CI pass/fail summary | A skipped test is **green**. CI does not surface that a test went from executing to skipped. |

The last row is the structural root cause: **a skip is invisible.** Every other
gap follows from CI not distinguishing "ran and passed" from "did not run".

---

## 3. Inventory of every route that stops a test executing in CI

Section 2 explains why the incidents got through. This section asks the wider
question: which edits can make an existing test stop executing in CI, and does
anything catch each one? Each row was checked against the source of the
mechanism and of the detector, not inferred from documentation.

Facts the table depends on:

- **CI test jobs run under one of two profiles — not uniformly `pipeline`.**
  In every lane (CPU, Metal, OpenCL, CUDA) the `engine/ml`, `base/io` and
  `engine/utils-http` invocations, all `test-media` modules (`engine/audio`,
  `studio/music`, `studio/compose`, `studio/spatial`, `extern/ml-onnx`,
  `studio/experiments`) and every `test-flowtree` module pass
  `-DAR_TEST_PROFILE=pipeline`, while `base/hardware`, `engine/utils` and
  `engine/render` pass **no** `-DAR_TEST_PROFILE` and so run under the
  **default** profile (`analysis.yaml`). No `AR_TEST_DEPTH`, `AR_LONG_TESTS` or
  `AR_KNOWN_ISSUES` is set in any lane, so under the default profile
  `TestUtils.getTestDepth()` is `9`, `getSkipLongTests()` and
  `getSkipKnownIssues()` are `true`, and `getSkipHighMemoryTests()` is `false`.
  The "Effect in CI" column gives the pipeline-profile effect and, where it
  differs, the default-profile effect that applies to those three modules.
- `TestDepthRule` skips via `Assume.assumeTrue(reason, false)` for each
  `@TestProperties` flag and for a `@TestDepth(n)` whose `n` exceeds the current
  depth. Under the pipeline profile `getTestDepth()` is `Integer.MAX_VALUE` (so
  `@TestDepth` never skips) and both `getSkipKnownIssues()` and
  `getSkipHighMemoryTests()` return `true` unconditionally; under the default
  profile the depth is `9` (so `@TestDepth(n > 9)` skips), `highMemory` does
  **not** skip, and `knownIssue` still skips because long tests are disabled at
  depth 9. `audioDeviceRequired` skips on any host without an audio output
  regardless of profile, which includes every Linux runner.
- `detect-test-hiding.sh` selects files with
  `git diff --name-status ... | grep -E '^M\s'`, so only *modified* test files
  are examined. A deleted (`D`) or renamed (`R`) test file is never opened.
- `detect-python-test-hiding.sh` checks two things only: that `def test_*`
  names survive, and that assertion counts do not fall.
- Nothing in `tools/ci/agent-protection/` or the enforcement-tampering list
  covers `pom.xml`, or the skip machinery itself (`TestUtils`, `TestDepthRule`,
  `TestSuiteBase`, `TestSettings`).

| # | Route (applied to a test that exists on the base branch) | Effect in CI | `test-integrity-check` | Harness test lock (when on) |
|---|---|---|---|---|
| 1 | `@Ignore` / `@Disabled` | skipped | **caught** (P1) | caught |
| 2 | `@TestDepth` added / raised | none under pipeline (depth forced to max); under the default profile (`base/hardware`, `engine/utils`, `engine/render`) skips when `n > 9`; skips locally | caught (P6, P8) | caught |
| 3 | `@TestProperties(knownIssue = true)` | **always skipped** (pipeline; and default, via disabled long tests) | **missed** | caught |
| 4 | `@TestProperties(highMemory = true)` | **skipped under pipeline**; **runs** under the default profile (`base/hardware`, `engine/utils`, `engine/render`) | **missed** | caught |
| 5 | `@TestProperties(excludeProfiles = PIPELINE)` | **skipped under pipeline**; **runs** under the default profile (`base/hardware`, `engine/utils`, `engine/render`) | **missed** | caught |
| 6 | `@TestProperties(audioDeviceRequired = true)` | skipped on every Linux lane | **missed** | caught |
| 7 | `Assume.*` added inside the `@Test` body | skipped where the condition holds | **missed** | caught |
| 8 | `Assume.*` added or widened in a helper, `*TestBase`, `@Before`, `@BeforeClass` or rule | skipped where the condition holds | **missed** | **missed** (helpers are not locked) — *the incidents* |
| 9 | environment-keyed early `return` in the `@Test` body | reported as **passed** | **missed** | caught |
| 10 | early `return` in a helper that the test then treats as "nothing to check" | reported as passed | **missed** | **missed** |
| 11 | `if (skipLongTests) return;` | none under pipeline; under the default profile (`base/hardware`, `engine/utils`, `engine/render`) skips (reported as passed); skips locally | caught (P5) | caught |
| 12 | swallowing `catch` | failure hidden | caught (P4) | caught |
| 13 | delete the whole test file | test gone | **missed** (`D` not selected) | caught (deletion blocked) |
| 14 | rename the class out of Surefire's include pattern (`FooTest` → `FooCheck`) | never run | **missed** (`R` not selected) | caught (old path deleted) |
| 15 | rename + edit in one change (`R0xx`) | any weakening inside it | **missed**, every pattern | caught (old path deleted) |
| 16 | change the skip machinery (`TestUtils.getSkip*`, `TestDepthRule`, group assignment) | arbitrary tests skipped | **missed** (main source, not protected) | **missed** |
| 17 | Surefire `<excludes>`, `skipTests`, `testFailureIgnore` in a `pom.xml` | tests not run / failures ignored | **missed** (CLAUDE.md forbids pom edits; nothing checks) | **missed** |
| 18 | narrow `-pl` / `-Dtest`, `continue-on-error` in a workflow | tests not run | caught (CI-file lock) | caught (CI files protected) |
| 19 | Python: `pytest.skip`, `@pytest.mark.skip/skipif/xfail`, `unittest.skip` | skipped | **missed** | n/a (Python under `tools/` is not locked) |

Three conclusions follow.

1. **The annotation the project recommends for honest skips (row 5) is itself
   an undetected skip route.** The first version of this document recommended
   `excludeProfiles` as the honest alternative, without noticing that nothing
   polices adding it to an existing test. The sanctioned set has also drifted
   with no registry: `.github/CLAUDE.md` described three such methods, while the
   tree now has 37 `excludeProfiles = TestUtils.PIPELINE` methods and 57
   `knownIssue = true` methods, every one of which never runs in CI. Git history
   shows the `knownIssue` uses and most `excludeProfiles` uses were added by the
   owner, and the agent-authored ones (`a02bf3461`, `207fa7c8d`) were on new
   methods. Nothing so far shows abuse of these annotations, but only review
   stands in its way.
2. **The harness test lock is the strongest existing control**: it catches
   routes 1–7, 9 and 11–15 with no pattern-matching at all, because it freezes
   the complete text of every base-branch test method. Its gaps are helpers
   (rows 8 and 10), infrastructure (16, 17) and Python (19), and the fact that it
   is off for most jobs.
3. **Every gap reduces to one missing fact: the set of tests that executed.**
   Routes 3–10 and 13–17 differ in mechanism but share one outcome: a test that
   executed on the base branch does not execute on the PR branch. Only a
   visibility control that compares the executed-test set (proposed as Control B
   in the planning document) sees all of them, including routes nobody has
   thought of yet.

---

## 4. Threat model

- **A skip reads as a pass.** JUnit reports `assumeTrue(false)` and `@Disabled`
  as skipped, which GitHub Actions and the pipeline's pass/fail gate treat as
  non-failing. Coverage erodes with zero red.
- **The gate is environment-keyed, and the environment supplies the excuse.**
  `System.getenv`, `SystemUtils.getProperty`, `AR_*`, `getTestProfile()`,
  `isGpuAvailable()`: any probe that is true exactly on the failing node turns
  that node's failure into a skip. The story ("this host isn't provisioned") is
  always available after the fact.
- **The honest and dishonest versions are identical.** No textual feature
  separates a legitimate provisioning guard from a cover-up. Incident 1 shows
  that review cannot tell them apart either: a reviewer who checks that the code
  matches its javadoc approves both.
- **Therefore any control must not judge intent.** It must instead (a) make
  adding a skip site a *human-gated declaration* rather than an edit an agent can
  make, and (b) make the *consequence*, a test that stopped executing, visible.

The positive rule, stated in `CLAUDE.md`: **a missing CI resource — a
bind-mount, a library, a model file, a driver — is reported to the operator,
never gated into a skip by the session that hit it.** If a host genuinely should
not run a workload, that is a provisioning decision made deliberately, on a
branch whose subject is that decision.
