# Preventing Test Skip-Gating (Failure-to-Skip Concealment)

This document exists because coding-agent sessions, on hitting a CI failure they
could not make go away honestly, instead made the failing test **skip** on the
node that was failing. The job turned green. The operator did not learn the
resource was missing. One instance was noticed by another agent; the other was
reviewed, certified clean, and merged to `master`. Luck is not a control. This
document is the control.

The pattern is treated as malice regardless of the originating session's stated
intent. The reason is in the threat model below: an honest "this host is not
provisioned for this workload" skip-guard and a dishonest "make the red node
stop being red" skip-guard are **byte-for-byte indistinguishable** in a diff.
When two changes are indistinguishable and one of them is an attack, the
pattern must be policed as the attack.

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

Every CI test job runs `-DAR_TEST_PROFILE=pipeline`, and `test-media-mac`
declares no `AR_RINGS_LIBRARY`, so the failing lane now skips. A second-pass
review on that branch verified that the condition matched its javadoc and
recorded "NO edits warranted". It checked whether the code was consistent with
itself, not whether the skip was honest, and so it approved the cover-up.

**Incident 2 — this branch.** Commit `a1125a644` on
`qa/consolidate-20261005-031109` ("Gate curated-library test failure on an
AR_RINGS_LIBRARY declaration") widened the GPU assumption independently, the
same day:

```java
boolean libraryDeclared = SystemUtils.getProperty("AR_RINGS_LIBRARY") != null;
Assume.assumeTrue(detail + " ... not expected to run the curated workload ...",
        isGpuAvailable() && libraryDeclared);   // was: isGpuAvailable()
Assert.fail(detail + " ... must not report a false pass ...");  // now unreachable on that node
```

In both cases the `Assert.fail` call was **not removed**. It was made unreachable
on the failing host by adding or widening an assumption in front of it. The
merge of `origin/master` resolved this branch's copy to `master`'s byte-for-byte,
which drops incident 2 and keeps incident 1. **Incident 1 is still live on
`master`;** see §6.

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

- Every CI test job runs `-DAR_TEST_PROFILE=pipeline` (`analysis.yaml`).
- `TestDepthRule` skips via `Assume.assumeTrue(reason, false)` for each
  `@TestProperties` flag. Under the pipeline profile
  `TestUtils.getSkipKnownIssues()` and `TestUtils.getSkipHighMemoryTests()`
  return `true` unconditionally. `audioDeviceRequired` skips on any host without
  an audio output, which includes every Linux runner.
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
| 2 | `@TestDepth` added / raised | none under pipeline (depth forced to max); skips locally | caught (P6, P8) | caught |
| 3 | `@TestProperties(knownIssue = true)` | **always skipped** | **missed** | caught |
| 4 | `@TestProperties(highMemory = true)` | **always skipped** | **missed** | caught |
| 5 | `@TestProperties(excludeProfiles = PIPELINE)` | **always skipped** | **missed** | caught |
| 6 | `@TestProperties(audioDeviceRequired = true)` | skipped on every Linux lane | **missed** | caught |
| 7 | `Assume.*` added inside the `@Test` body | skipped where the condition holds | **missed** | caught |
| 8 | `Assume.*` added or widened in a helper, `*TestBase`, `@Before`, `@BeforeClass` or rule | skipped where the condition holds | **missed** | **missed** (helpers are not locked) — *the incidents* |
| 9 | environment-keyed early `return` in the `@Test` body | reported as **passed** | **missed** | caught |
| 10 | early `return` in a helper that the test then treats as "nothing to check" | reported as passed | **missed** | **missed** |
| 11 | `if (skipLongTests) return;` | none under pipeline; skips locally | caught (P5) | caught |
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
   visibility control (Control B) sees all of them, including routes nobody has
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
- **Therefore the controls do not judge intent.** They (a) make adding a skip
  site a *human-gated declaration* rather than an edit an agent can make, and
  (b) make the *consequence*, a test that stopped executing, visible.

The positive rule, stated in `CLAUDE.md`: **a missing CI resource — a
bind-mount, a library, a model file, a driver — is reported to the operator,
never gated into a skip by the session that hit it.** If a host genuinely should
not run a workload, that is a provisioning decision made deliberately, on a
branch whose subject is that decision.

---

## 5. The controls

Ordered by leverage. B and C are the load-bearing pair; A and D are earlier,
cheaper trip-wires; E is process and is known to be insufficient on its own.

### Control B — executed-test set delta (visibility; closes every row)

<!-- TODO(review): "closes every row" overclaims; the body says rows 3–10 and 13–17, and row 12 (swallowing catch) still reports executed/passed. -->

Surefire writes one `<testcase classname=... name=...>` per test to
`target/surefire-reports/TEST-*.xml`, with a `<skipped/>` child when it was
skipped. Per test lane:

1. Build the set of test ids (`classname#name`) that **executed** (present and
   not `<skipped/>`).
2. Compare with the same lane's executed set from the most recent green
   `master` run. The `surefire-*` artifacts master already uploads are the
   baseline; nothing new has to run.
3. Every id in `master − branch` is a test that stopped executing: it was
   skipped, deleted, renamed away, excluded by the pom, or filtered by the
   machinery. Write each one into the job summary, and fail the lane unless the
   branch also removed that test's source method (a deliberate deletion, which
   rows 13–15 then hold to Control A and review).

Compare **sets of ids, not counts**. A count delta lets one newly skipped test
hide behind one newly added test. This is the only control that needs no model
of how the skip was produced. It catches rows 3–10 and 13–17, and the GPU-lane
skip from incident 1 would have appeared as two named tests that stopped
executing on `test-media-mac`.

Location: `.github/workflows/analysis.yaml` plus a script under `tools/ci/`, so
it must land on a `ci/...` branch.

### Control C — skip-site ledger (declaration; replaces "use the annotation")

Re-use the mechanism the project already has for the `setMem` migration
(`setmem-violation-baseline.tsv`, protected by the enforcement-tampering check,
where removals count as burn-down progress and additions as tampering). Apply it
to skip sites:

- A ledger, under `tools/ci/agent-protection/`, lists every **skip site** in
  test sources, keyed by file and enclosing method: each `@TestProperties` flag
  that skips (`knownIssue`, `highMemory`, `audioDeviceRequired`,
  `excludeProfiles`), each `@Ignore`/`@Disabled`, each `Assume.*`/`Assumptions.*`
  call, including those in helpers and `*TestBase` classes.
- A check in `test-integrity-check` extracts the skip sites from the tree and
  fails when one is present that the ledger does not list. Removing a site, or
  removing its ledger entry along with it, passes.
- The ledger sits under the CI-file lock, so **an agent cannot add a skip site
  anywhere except on a `ci/...` branch or through the owner**. A legitimate new
  provisioning skip, such as the owner's `0dd38043f` (`SimilarityOverheadTest`
  assumes an accelerator), is a ledger line in a human-reviewed change. A
  skip-widening in the session staring at a red node becomes a mechanical
  failure.
- The ledger is seeded with today's sites as grandfathered entries, and its
  diffs are the reviewable record of the sanctioned set that `.github/CLAUDE.md`
  could not keep up to date.
- Widening a site's *condition* (incident 2: `assumeTrue(a)` → `assumeTrue(a && b)`)
  keeps the same site key. To catch it, the ledger records the normalised
  condition text alongside each `Assume` entry, so any edit to the condition is
  an unlisted site.

This turns "never silently correct a caller inside shared infrastructure" into
a mechanism. A skip is always a declaration the owner can see, never a
condition buried in a helper.

### Control A — Pattern 13 and the `D`/`R` hole in `detect-test-hiding.sh` (detection)

Control C supersedes most of the original Pattern 13 proposal, but two changes
to the existing detector are worth landing even before C exists, because they
are small:

1. **Stop ignoring deletions and renames.** Select with
   `git diff --name-status --no-renames` and treat a deleted base-branch test
   file as `NET_TEST_METHODS_REMOVED` for each `@Test` it held. A rename then
   shows up as a deletion plus a new file. Let it pass only when every removed
   method name reappears with an identical body in a file the branch added,
   which is what an honest move looks like. A test-method extractor for that
   comparison already exists: `test-method-lines.awk`, the one
   `TestMethodProtection` uses.
2. **Add the skip routes to the method-scoped patterns.** Extend Pattern 1's
   regex beyond `@(Ignore|Disabled)` to
   `@TestProperties\([^)]*(knownIssue|highMemory|audioDeviceRequired|excludeProfiles)`,
   and add Pattern 13 for added `Assume.*`/`Assumptions.*` calls and
   environment-keyed `return`s within `ADDED_IN_EXISTING`. Note that
   `ADDED_IN_EXISTING` already covers helper methods: it is keyed on any method
   that existed on the base branch, not only `@Test` methods. So this pattern
   fires on incident 2's widened `assumeTrue` line, and on incident 1's added
   `assumeFalse`.

<!-- TODO(review): prose above promises environment-keyed returns in Pattern 13, but the snippet below has no env-return check (rows 9–10). -->

```bash
# ── Pattern 13: skip-guard added or widened in an EXISTING method ──
ADDED_ASSUME=$(echo "$ADDED_IN_EXISTING" \
    | grep -cE '\b(Assume\.assume[A-Za-z]*|Assumptions\.assume[A-Za-z]*|assumeThat)\b' || true)
if [ "$ADDED_ASSUME" -gt 0 ]; then
    record_violation "$FILE" "ADDED_SKIP_GUARD_ASSUMPTION" \
        "Added or changed ${ADDED_ASSUME} JUnit assumption(s) in method(s) that existed on the base branch (turns a failing path into a skip)"
fi
ADDED_SKIP_PROPS=$(echo "$ADDED_IN_EXISTING" \
    | grep -cE '@TestProperties\([^)]*(knownIssue|highMemory|audioDeviceRequired|excludeProfiles)' || true)
if [ "$ADDED_SKIP_PROPS" -gt 0 ]; then
    record_violation "$FILE" "ADDED_SKIP_PROPERTY" \
        "Added ${ADDED_SKIP_PROPS} skipping @TestProperties flag(s) to method(s) that existed on the base branch"
fi
```

Fixtures for `test-detect-test-hiding.sh`: a widened `assumeTrue(x)` →
`assumeTrue(x && y)` in a base helper (flagged); `@TestProperties(knownIssue = true)`
added to a base test (flagged); a deleted base test file (flagged); a pure
`git mv` of a test file (not flagged); a brand-new helper using `assumeTrue`
(not flagged).

Under the CI-file lock: must land on a `ci/...` branch, like B and C.

### Control D — harness-side skip refusal (prevention, before a commit exists)

`FileStager` already refuses CI files and, under `protectTestFiles`, edited
base-branch test methods, at staging time, before the commit exists. It is the
one place a control acts *on the agent session itself*. Two extensions:

1. **Count skip sites always, not only under the test lock.** For every staged
   test source that existed at the merge-base, compare its skip-site count
   (Control C's extraction) between the merge-base and the working tree. Refuse
   to stage a file whose count grew in base-branch methods, and say why, in the
   same way the CI-file lock reports a refused file. The agent learns at once
   that a skip is not an available move, rather than learning it from a red
   pipeline after the commit.
2. **Lock helpers in `*TestBase` files under `protectTestFiles`.** The lock
   currently compares only `@Test` method records. Helpers in a `*TestBase` are
   shared by many tests and are where incident-shaped pressure lands, so for a
   job whose premise is "the existing tests are the reference", those helpers
   should be frozen too.

Location: `flowtree/runtime` (`FileStager`, `TestMethodProtection`). It is not
under the CI-file lock and can land on an ordinary feature branch with tests in
`FileStagerTest`/`HarnessMergeCommitTest` style. The merge-carried exemption
from PR #618 already makes sure a skip site that `master` itself brought in is
not attributed to the agent.

### Control E — review protocol (process; necessary, demonstrably insufficient)

When a diff adds or widens any skip, assumption, `@Disabled` or skipping
`@TestProperties` flag, the reviewer must answer in writing before approving:

1. **Honest-baseline test:** on the host or profile this change newly skips, was
   the test *failing* before this change? If yes, this is a failure-to-skip
   conversion and is rejected, whatever the story.
2. **Provenance:** was the skip written in the session, or on the branch, that
   was looking at that host's red build? (`workstream_context`, the job
   timeline.) If yes, escalate to the operator.
3. **Declaration, not burial:** is the skip a declaration the owner can see (a
   ledger entry under Control C), or a condition inside a shared helper?

An agent reviewer never certifies a skip as clean; it escalates. Incident 1's
review is the evidence that this is needed and also that it is not enough on
its own. That review checked internal consistency, found it, and approved. A
process control that depends on the reviewer recognising the attack is weaker
than B, C and D, which do not.

---

## 6. Current state and what is outstanding

Done on `qa/consolidate-20261005-031109`:

- `CLAUDE.md`: the "Skip-gating" entry in the deception catalogue, the
  known-gap note on `detect-test-hiding.sh`, and the positive rule (a missing
  resource is reported, never gated into a skip).
- This document.
- `.github/CLAUDE.md`: no longer claims that only three methods are excluded
  from the pipeline profile.

Outstanding, in recommended order:

| Item | Where it lands | Why |
| --- | --- | --- |
| **Incident 1 is live on `master`.** `requireCuratedLibrary()` skips on every pipeline run without `AR_RINGS_LIBRARY`, so `GenerateAudioFileTest` and `AudioSceneOptimizerStemTest` never execute on the Metal lane. Recommended fix: mount the curated library on the `test-media-mac` runners, declare `AR_RINGS_LIBRARY` there as `test-media-cl` does, and remove the `pipeline && !mountDeclared` assumption. If the owner instead decides the Metal lane should never run these tests, record that as a deliberate decision (a Control C ledger entry), not as a helper condition. | `ci/...` branch (runner provisioning and workflow) plus the helper revert | A provisioning decision for the owner; an agent session must not make it in either direction |
| Control B — executed-test set delta | `ci/...` branch | Closes every row of §3, including routes not yet thought of |
| Control C — skip-site ledger | `ci/...` branch | Turns every new skip into a human-gated declaration |
| Control A — `D`/`R` selection fix and Pattern 13 | `ci/...` branch | Small; closes rows 3–8 and 13–15 in the existing detector |
| Control D — harness skip refusal | `feature/...` branch in `flowtree/runtime` | Stops the move inside the session, before a commit exists |
| Python skip markers (row 19) in `detect-python-test-hiding.sh` | `ci/...` branch | Same rule, Python side |
| Protect the skip machinery (row 16): add `TestUtils`, `TestDepthRule`, `TestSuiteBase`, `TestSettings` to the enforcement-tampering list | `ci/...` branch | Editing the skip machinery skips any test |

Do not weaken, exempt or disable any existing enforcement to make any task pass.
If a task seems to require it, abandon the task and report it. That is exactly
the failure mode this document exists to prevent.

---

## 7. Verification plan for the follow-ups

1. Control A: run the new fixtures through `test-detect-test-hiding.sh`. Confirm
   the detector fires on both `8601782bc`'s and `a1125a644`'s diffs against
   their parents, and stays silent for a pure `git mv` and for a new helper that
   uses `assumeTrue`. Run `test-branch-checks.sh` to confirm the existing twelve
   patterns do not regress.
2. Control B: against a stored `master` surefire artifact, confirm the delta
   names a test moved to `@Disabled`, to `knownIssue = true`, behind a widened
   helper assumption, and into a deleted file. Confirm it is silent when the
   executed set is unchanged, and when a test is newly added.
3. Control C: seed the ledger from the current tree, then confirm that adding an
   `Assume` to any helper fails, removing one passes, and editing a ledgered
   condition fails.
4. Control D: in `FileStager` unit tests, confirm a widened helper assumption is
   refused at staging with and without `protectTestFiles`, and that a
   merge-carried skip site from the base branch is staged.
