# Test Skip-Gating Prevention — Controls Plan

This is the planning half of the test skip-gating work. The durable reference —
what skip-gating is, the two incidents that motivated it, why every existing
control missed them, the inventory of every route that stops a test executing in
CI, and the threat model — lives in
[docs/internals/test-skip-gating-prevention.md](../internals/test-skip-gating-prevention.md).
Read that first; this document assumes its route inventory (routes are referenced
below by the same row numbers) and its threat model.

What is planned here is the set of controls that close the gaps the reference
document identifies, the current state of each, and the verification each needs
before it lands. Items are tracked here, not in `docs/internals/`, because they
are proposed and outstanding rather than describing the tree as it is today.

---

## The controls

Ordered by leverage. B and C are the load-bearing pair; A and D are earlier,
cheaper trip-wires; E is process and is known to be insufficient on its own.

### Control B — executed-test set delta (visibility; closes every route that stops a test executing)

Surefire writes one `<testcase classname=... name=...>` per test to
`target/surefire-reports/TEST-*.xml`, with a `<skipped/>` child when it was
skipped. Per test lane:

1. Build the set of test ids (`classname#name`) that **executed** (present and
   not `<skipped/>`).
2. Compare with the same lane's executed set from the newest successful
   `master` run that **actually executed that lane and still has its
   `surefire-*` artifact**. The most recent green run is not enough: the test
   jobs are layer-gated (a lane such as `test-media-mac` is skipped when no
   relevant layer changed), and the artifacts expire after seven days, so a
   green run may carry no baseline for the lane. When no usable baseline
   exists, the control fails closed — the lane is reported as unverified and
   fails — until a baseline is produced by running that lane on `master`
   (for example a scheduled or manually dispatched master run); it never
   passes for lack of something to compare against.
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
   and add Pattern 13 for added `Assume.*`/`Assumptions.*` calls — qualified or
   statically imported — within `ADDED_IN_EXISTING`. Note that
   `ADDED_IN_EXISTING` already covers helper methods: it is keyed on any method
   that existed on the base branch, not only `@Test` methods. So this pattern
   fires on incident 2's widened `assumeTrue` line, and on incident 1's added
   `assumeFalse`. Environment-keyed early `return`s (rows 9–10) are deliberately
   left out of this textual pattern: they cannot be distinguished from an
   ordinary early `return` without unacceptable false positives, and are covered
   instead by Control B's executed-set delta.

```bash
# ── Pattern 13: skip-guard added or widened in an EXISTING method ──
# Matches both qualified (Assume.assumeTrue) and statically imported (assumeTrue)
# assumption calls, so a static import cannot slip a guard past the detector.
ADDED_ASSUME=$(echo "$ADDED_IN_EXISTING" \
    | grep -cE '\b(Assume\.|Assumptions\.)?assume[A-Za-z]*[[:space:]]*\(' || true)
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

Both greps match only when the `assume…(` or `@TestProperties(` token is itself
on an added line. A widening split across lines — the opener left on an unchanged
line and only a continuation changed, e.g. a `&& libraryDeclared` appended to the
second line of a multiline `Assume.assumeTrue(` call, or a `knownIssue = true`
added on a continuation line of an existing multiline `@TestProperties(` block —
carries no `assume…(`/`@TestProperties(` token on its added lines, so Pattern 13
does not see it. This is a known textual blind spot, the same class as the
environment-keyed `return` above: Pattern 13 is a cheap best-effort check, not a
complete one. The multiline widening is closed by Control C's normalised skip-site
records (each `Assume` condition's normalised text and each `@TestProperties` flag,
extracted from the whole annotation block rather than a single diff line) and by
Control B's executed-set delta — neither depends on which line a change lands on.

Fixtures for `test-detect-test-hiding.sh`: a single-line widened `assumeTrue(x)` →
`assumeTrue(x && y)` in a base helper (flagged); `@TestProperties(knownIssue = true)`
added on one line to a base test (flagged); a deleted base test file (flagged); a
pure `git mv` of a test file (not flagged); a brand-new helper using `assumeTrue`
(not flagged); a multiline `Assume.assumeTrue(` whose condition grows only on a
continuation line, and a `knownIssue = true` added on a continuation line of an
existing multiline `@TestProperties(` block (both NOT flagged by Pattern 13 — the
blind spot above; they belong to the Control B and Control C fixtures instead).

Under the CI-file lock: must land on a `ci/...` branch, like B and C.

### Control D — harness-side skip refusal (prevention, before a commit exists)

`FileStager` already refuses CI files and, under `protectTestFiles`, edited
base-branch test methods, at staging time, before the commit exists. It is the
one place a control acts *on the agent session itself*. Two extensions:

1. **Compare skip-site records always, not only under the test lock.** For every
   staged test source that existed at the merge-base, extract its skip-site
   records (Control C's extraction, including each `Assume` entry's normalised
   condition text) from the merge-base and from the working tree, and refuse to
   stage a file that introduces a record absent at the merge-base in a base-branch
   method — a new skip site, or a *widened condition* on an existing one
   (incident 2: `assumeTrue(a)` → `assumeTrue(a && b)`, which keeps the site key
   but changes its condition). Say why, in the same way the CI-file lock reports a
   refused file. Comparing a bare site *count* would miss the widening, since the
   number of sites is unchanged; comparing records catches it, which is what the
   verification plan below requires even without `protectTestFiles`. The agent
   learns at once that a skip is not an available move, rather than learning it
   from a red pipeline after the commit.
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

## Current state and what is outstanding

Done on `qa/consolidate-20261005-031109`:

- `CLAUDE.md`: the "Skip-gating" entry in the deception catalogue, the
  known-gap note on `detect-test-hiding.sh`, and the positive rule (a missing
  resource is reported, never gated into a skip).
- The reference document,
  [docs/internals/test-skip-gating-prevention.md](../internals/test-skip-gating-prevention.md),
  and this planning document.
- `.github/CLAUDE.md`: no longer claims that only three methods are excluded
  from the pipeline profile.

Done elsewhere: PR #616 removed incident 1's `pipeline && !mountDeclared`
assumption from `requireCuratedLibrary()`, restoring the contract in which only
a CPU-only host skips and any GPU host without the curated library fails. Any
future decision that a GPU lane should not run the curated-library tests is a
provisioning decision for the owner, to be recorded as a Control C ledger entry
rather than as a helper condition.

Outstanding, in recommended order:

| Item | Where it lands | Why |
| --- | --- | --- |
| Control B — executed-test set delta | `ci/...` branch | Closes every route of the inventory that stops a test executing (rows 3–10 and 13–17), including ones not yet thought of |
| Control C — skip-site ledger | `ci/...` branch | Turns every new skip into a human-gated declaration |
| Control A — `D`/`R` selection fix and Pattern 13 | `ci/...` branch | Small; closes rows 3–8 and 13–15 in the existing detector |
| Control D — harness skip refusal | `feature/...` branch in `flowtree/runtime` | Stops the move inside the session, before a commit exists |
| Python skip markers (row 19) in `detect-python-test-hiding.sh` | `ci/...` branch | Same rule, Python side |
| Protect the skip machinery (row 16): add `TestUtils`, `TestDepthRule`, `TestSuiteBase`, `TestSettings` to the enforcement-tampering list | `ci/...` branch | Editing the skip machinery skips any test |

Do not weaken, exempt or disable any existing enforcement to make any task pass.
If a task seems to require it, abandon the task and report it. That is exactly
the failure mode the reference document exists to prevent.

---

## Verification plan for the follow-ups

1. Control A: run the new fixtures through `test-detect-test-hiding.sh`. Confirm
   the detector fires on both `8601782bc`'s and `a1125a644`'s diffs against
   their parents, and stays silent for a pure `git mv` and for a new helper that
   uses `assumeTrue`. Run `test-branch-checks.sh` to confirm the existing twelve
   patterns do not regress.
2. Control B: against a stored `master` surefire artifact, confirm the delta
   names a test moved to `@Disabled`, to `knownIssue = true` (both single-line
   and on a continuation line of a multiline `@TestProperties(` block), behind a
   widened helper assumption (both single-line and split across lines), and into
   a deleted file. Confirm it is silent when the executed set is unchanged, and
   when a test is newly added.
3. Control C: seed the ledger from the current tree, then confirm that adding an
   `Assume` to any helper fails, removing one passes, and editing a ledgered
   condition fails — including a condition widened only on a continuation line of
   a multiline `Assume`/`@TestProperties` block, which the normalised-record
   extraction must catch where the line-based Pattern 13 does not.
4. Control D: in `FileStager` unit tests, confirm a widened helper assumption is
   refused at staging with and without `protectTestFiles` — including a widening
   that changes only a continuation line, which a site-count comparison would
   miss — and that a merge-carried skip site from the base branch is staged.
