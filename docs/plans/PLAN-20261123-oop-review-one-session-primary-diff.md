---
status: completed
created: 2026-11-23
closed: 2026-11-23
tracker: [a18d1b09-4fd0-4fb1-82d8-40014e873403]
---

# Cap OOP design review at one session per job, scoped to the primary diff

Plans tracker task **a18d1b09-4fd0-4fb1-82d8-40014e873403** —
*"Cap OOP design review at one session per job and keep it to the primary phase's diff"* —
in release **Common 0.78**.

## Motivation

The risk-gated object-oriented design review (`ObjectOrientedDesignRule`,
`ObjectOrientedRiskGate`, `ObjectOrientedReviewLedger` in `flowtree/runtime`) was
meant to run as **one session per job**, capped at `MAX_CLASSES`. On a recent
performance job it instead ran three successive sessions — about two hours of a
4h20m run — and grew a 5-file performance change into a cross-cutting API refactor
of the computation framework:

- Session 1 reviewed the primary phase's 3 classes and introduced new code
  (a new `createScope(...)` method on the base computation class).
- Because that new code showed up as *changed* in the working tree, the gate
  flagged it, and session 2 reviewed the base class and rewrote five subclasses.
- Those edits were in turn flagged, so session 3 reviewed four more classes and
  changed an iteration-step contract across several implementations.

The review kept re-reviewing its own output. Some findings were real, but they
belonged in follow-up tasks on a focused branch, not in a performance PR. This
task makes the review evaluate only the diff as it stood when the primary phase
ended, run a single session, and scope its edits to the code the primary phase
wrote — filing follow-ups for anything else.

## Root cause (grounded in the code)

`ObjectOrientedDesignRule` selects classes to review in `selection(CodingAgentJob)`,
which memoizes into the `selection` field. After each review session,
`onCorrectionAttempted(...)` sets `selection = null`, so the next `isViolated(...)`
call recomputes the selection from the **live working tree** via
`getChangedClasses(job)` → `job.extractChangedFilePaths()`. The review session's
own edits are in that working tree, so:

1. a class the review *created* appears as a new changed class, the
   `ObjectOrientedRiskGate` flags it (e.g. `NEW_CLASS`, `NEW_STATIC`), and it
   becomes a fresh candidate;
2. a class the review *edited* gets a new git blob id, so its ledger verdict no
   longer matches and it looks unsettled again.

Either way, `unreviewed(job)` is non-empty, `isViolated(...)` returns `true`, and
`EnforcementRunner`'s correction loop launches another session. The loop repeats
until `reviewed.size()` reaches `MAX_CLASSES` or the tree stops changing.

The single fact behind the blowup: **the gate is re-run against a tree the review
has been mutating.** Freezing the candidate set to the diff as it stood
before the first review session removes the feedback loop within a job (Scope
bullets 1 and 3 describe what that snapshot contains and what it does not stop).

## Scope

Contained to `flowtree/runtime`. No new job property travels over the wire, no
new Maven module, and no change to the `objectOrientedReviewEnabled` flag or its
submission path. Concretely:

1. **Freeze the gated candidate set on first evaluation.**
   `ObjectOrientedDesignRule.selection` is computed once — on the rule's first
   evaluation — and never recomputed. The gate therefore sees only the classes
   the branch changed and the lines it added **as of that moment**; classes the
   review creates or edits afterward are never re-gated.

   That moment is **not** strictly primary-phase end. `EnforcementRunner.buildActiveRules()`
   adds the rule after `enforce-changes`, `ReviewRule` (the second-pass review),
   `DeduplicationRule`, `OrganizationalPlacementRule`, `PostCompletionCommandRule`,
   `MavenDependencyProtectionRule` and the custom rules, so the frozen snapshot
   also contains whatever those correction sessions changed. Freezing on first
   evaluation is enough to break the self-feeding loop, which is the cause of the
   incident; matching the task's literal "primary phase's diff" would need the
   changed paths captured when the primary phase ends. See open question 5.

2. **One session per job, in the common case by construction.** With the
   selection frozen to `N ≤ MAX_CLASSES` flagged classes, a single session covers
   all of them and each is marked reviewed, so `isViolated(...)` is then `false`.
   The existing retry-for-unsettled behaviour (a flagged class that received no
   verdict line is re-offered) is preserved but can no longer grow the candidate
   set, because the set is frozen. When more than `MAX_CLASSES` classes are
   flagged, the first session reviews `MAX_CLASSES` and the
   `reviewed.size() < MAX_CLASSES` guard stops any further session — exactly the
   cap the task asks for.

3. **Record the review's own edits without re-gating them.** After the session,
   for each reviewed class, record its *post-session* content (re-hashed blob id)
   under its settled verdict in the `ObjectOrientedReviewLedger`, so a later job
   skips the now-settled content instead of re-reviewing the edit. This records
   the review's edits (task bullet "record the review's own edits") while the
   freeze guarantees they are not re-gated in this job.

   This covers only the classes that were reviewed. `CodingAgentJob.extractChangedFilePaths()`
   lists every file that differs from `origin/<base>`, so the *next* job on the
   same branch sees all of the branch's changes again. A class the review
   *created*, or a class outside the candidate set that the review *edited* (the
   incident's five rewritten subclasses), has no ledger entry and will be gated
   in that next job: the freeze stops the cascade within a job but not across
   jobs. The STEP 3 scope rule (bullet 4) is what keeps such edits from
   happening in the first place. See open question 6.

4. **Scope rule in the review prompt.** Rewrite `buildReviewPrompt(...)` STEP 3.
   Today it tells the agent to "Fix every violation NOW, even when the fix is a
   refactor that touches other files … Do not defer to a memory, a TODO or a
   follow-up." That is the behaviour this task reverses. The new STEP 3:
   - fix violations in the code the primary phase wrote or changed (the flagged
     classes' own primary-diff additions);
   - for a violation in surrounding code the fix would need to touch (a
     superclass, callers, sibling classes outside the primary diff), file a
     follow-up task with `tracker_create_task` (project id of the task's project,
     title, description naming the class and the violation) rather than editing
     it in this job;
   - keep the review's edits proportionate to the primary diff: if a fix would
     grow the change well beyond the primary diff, stop and file a follow-up.

5. **Proportionality signal.** In `onCorrectionAttempted(...)`, compare the review
   session's added/changed line count against the primary diff size (the added
   lines across the frozen candidates). When the ratio exceeds a small multiple
   (a named constant, e.g. 3×), emit a job status warning so a disproportionate
   review is visible even if the agent ignored the prompt. This is a signal, not
   a hard stop.

   The review session's own line count is not available today: the changes are
   uncommitted, and the rule's existing diff helper reads the added lines of a
   class against `origin/<base>`, which includes the primary phase's lines. The
   implementation therefore takes a line-count baseline in
   `buildCorrectionPrompt(...)` (for example the summed `git diff --numstat
   origin/<base>` of the working tree) and subtracts it from the same figure in
   `onCorrectionAttempted(...)`. This is an approximation (a rewritten line
   counts once, and deletions are not counted); the plan accepts that, since
   the figure is only a warning.

6. **Visibility.** Each review session already logs its class list in
   `buildCorrectionPrompt(...)`; promote this to a job phase/status message so it
   is visible in the job record (the task notes this is "already partly done").
   Track and report a **cumulative OOP-review wall-clock time and USD cost**: the
   rule times each session between `buildCorrectionPrompt(...)` (start) and
   `onCorrectionAttempted(...)` (end) and reads the cost delta from
   `CodingAgentJob.getCostTracker()`, accumulating both across sessions, and emits
   a cumulative figure (e.g. "object-oriented review: K classes, Mm Ss, $X.XX").
   The ordering supports this: `EnforcementRunner` calls `buildCorrectionPrompt(...)`,
   then `CodingAgentJob.runCorrectionSession(...)` (whose agent run records its
   cost with `JobCostTracker.record(...)`), then `onCorrectionAttempted(...)`.
   When `JobCostTracker.isIncomplete()` is `true` (a session was killed for
   inactivity before reporting its cost), the reported figure is a lower bound
   and the message should say so.

### Out of scope

- The historical findings from the triggering incident (a frame-0 clobber in
  `RepeatedProducerComputationAdapter`; subclasses dropping compute requirements).
  If still live, they are separate bug tasks, not part of this change. See open
  questions.
- Any change to what travels over the wire or to the per-job enable flag.

## Approach

All production changes are in `flowtree/runtime/src/main/java/io/flowtree/jobs/`.

- **`ObjectOrientedDesignRule`** (primary change):
  - Remove the `selection = null` reset from `onCorrectionAttempted(...)` so the
    memoized `selection` becomes the frozen first-evaluation snapshot; keep the
    `pending = null` reset. Update the javadoc on `selection(...)` /
    `onCorrectionAttempted(...)` to state the snapshot semantics (the current
    javadoc's "clears the memoized selection so the next check re-scans" is
    exactly the behaviour being removed), and the `selection` field comment,
    which today reads "Memoized selection for the current working-tree state;
    cleared after each session" — the freeze makes both halves of that stale.
  - In `onCorrectionAttempted(...)`, in addition to recording the snapshot
    candidate under its settled verdict, re-hash each reviewed class and record
    its post-session blob id under the same verdict (bullet 3). Factor blob
    hashing so both the snapshot and the post-session hash use one path — the
    existing `blobId(job, path)` already does this.
  - Add session timing and cost-delta accumulation fields and a cumulative report
    (bullet 6); add the proportionality comparison and warning (bullet 5).
  - Rewrite STEP 3 of `buildReviewPrompt(...)` (bullet 4). The "even when the fix
    is a refactor that touches other files" / "Do not defer to a memory, a TODO or
    a follow-up" text is in that STEP 3 string literal, not the javadoc — it is the
    behaviour this task reverses. Separately adjust the class javadoc, which
    advertises the same stance in prose (the review's "mandate is to fix what it
    finds"), so it describes the new primary-diff-scoped, follow-up-filing behaviour.
- **`EnforcementRunner`**: the inline comment where the rule is added reads
  "One session per changed class", which is already stale (the rule reviews all
  flagged classes together in one session, up to `MAX_CLASSES`, not one session
  per class) and becomes more so once the frozen snapshot makes one-session-per-job
  the common case. Correct it to describe the frozen-snapshot, one-session-per-job
  behaviour this change establishes.
- **`JobCostTracker`**: add a `total()` accessor that sums the per-model costs, so
  the rule can read a single job-wide cost figure before and after a session. The
  method belongs here because this class owns the cost maps (it already exposes
  `costForModel`, which the job wraps as `CodingAgentJob.getCostForModel(modelKey)`
  for `RetrospectivePhase` to read — not called on the tracker directly). How the
  rule reaches the new figure is open question 7.
- **`flowtree/runtime/docs/coding-agent.md`** (Object-Oriented Design Review
  section): update to describe one session over the frozen pre-review
  diff, the scope rule (fix primary-diff code, file follow-ups otherwise), and the
  cumulative time/cost reporting. The `objectOrientedReviewEnabled` row of the
  same file's job-property table ("a single design-review session over the
  changed production classes a deterministic risk gate flags") and the matching
  `object_oriented_review_enabled` row in `tools/mcp/manager/README.md` (Optional
  Enforcement Phases) state the same one-session behaviour; check both still
  read correctly after the change. Do not reference this plan document from the
  durable doc.

The job's wire/record path (`CodingAgentJobFactory`, `CodingAgentJobCodec`,
`workstream_submit_tools.py`, the HTTP submit) is untouched: the behaviour change
needs no new serialized property. If the cumulative time/cost is to appear in the
structured completion payload rather than only in status messages, that touches
`CodingAgentJobEvent` — see open questions.

## Success criteria

A reviewer confirms the task is done when:

1. **Review-introduced flags do not start a second session.** A new unit test in
   `ObjectOrientedDesignRuleTest` (using the existing `StubJob` whose
   `extractChangedFilePaths()` returns a mutable list, and the `FakeRule` that
   stubs `inspect(...)` and the ledger): run one session over the flagged
   primary-diff classes, then mutate the stub's changed list to add a class that
   only a review edit would have produced, and assert `isViolated(job)` is
   `false` — the frozen selection never admits it. This is the task's first
   "Done when".
2. **The review prompt states the scope rule.** Extend the prompt-content test
   (today `promptListsFlaggedClassesWithSignals`) to assert the STEP 3 text tells
   the agent to fix primary-diff code and to file follow-up tasks (not fix) for
   surrounding code. This is the task's second "Done when".
3. **Cumulative time/cost and the proportionality signal** are covered by a test
   asserting the rule reports a cumulative figure after a session and warns when
   the review edit is disproportionate to the primary diff (using a cost-tracker
   stub and canned line counts).
4. **Existing behaviour preserved**: `reviewsFlaggedClassesInOneSession`,
   `classWithoutVerdictIsReviewedAgain`, `verdictPersistsAcrossJobsUntilContentChanges`,
   `primaryCommitMessageSurvivesReview`, and the rule-ordering tests still pass.
   `classWithoutVerdictIsReviewedAgain` keeps passing because the freeze only
   stops *new* candidates appearing; a frozen candidate with no verdict is still
   re-offered.
5. `ObjectOrientedRiskGateTest` and `ObjectOrientedReviewLedgerTest` still pass
   (the ledger gains no new behaviour beyond an extra recorded blob id).
6. Build validator (checkstyle, code_policy, test_timeouts, duplicate_code) is
   clean, and `mvn install -DskipTests` succeeds.

Tests to run (bounded selection): `ObjectOrientedDesignRuleTest`,
`ObjectOrientedRiskGateTest`, `ObjectOrientedReviewLedgerTest`, and (for the
cost-tracker accessor) `JobCostTrackerTest`, extended with a case for `total()`.
The Python `test_oop_review_follow_up_disabled.py` is unaffected (no wire change)
but can be run to confirm.

## Dependencies

- None blocking. The task's `blocked_by` list is empty. It builds on the merged
  risk-gate + ledger + one-session-over-flagged-classes work already in
  `flowtree/runtime` (the classes named above exist on master).

## Open questions

1. **Strict single session vs. frozen-set retry.** This plan achieves one session
   in the common case and bounds the pathological case via the frozen set and the
   `MAX_CLASSES` guard, but preserves the existing "re-offer a flagged class that
   got no verdict" retry (so `classWithoutVerdictIsReviewedAgain` stays valid). If
   the approver wants a *hard* one-session cap (never a second session even for an
   unsettled primary-diff class), that would change
   `classWithoutVerdictIsReviewedAgain`'s expectation — an intended behaviour
   change to an existing test, which is allowed, but should be decided explicitly.
2. **Where cumulative time/cost surfaces.** Status/phase messages are the minimal,
   no-wire option. If it must also appear in the structured job completion record,
   that pulls in `CodingAgentJobEvent` and should be confirmed as in scope.
3. **Follow-up task creation inside a review session.** The scope rule asks the
   review agent to file follow-ups with `tracker_create_task`. Confirm the review
   session is granted the ar-manager tracker tools (it runs with ar-manager
   available for memory writes); if not, the follow-up instruction needs the tool
   enabled for that session, or the rule records the follow-up some other durable
   way.
4. **Historical incident findings.** Whether the frame-0 clobber and dropped
   compute-requirement findings from the triggering job are still live and should
   be filed as separate bug tasks is for a person to decide; they are out of this
   task's scope.
5. **Snapshot point: first evaluation or primary-phase end.** The freeze takes
   its snapshot when the rule is first evaluated, which is after the second-pass
   review, deduplication and placement sessions have also edited the tree (see
   Scope bullet 1). Their edits are therefore gated and offered for review too,
   and they count toward the "primary diff" baseline of the proportionality
   signal. If the approver wants the literal primary-phase diff, the changed
   paths must be captured when the primary phase ends (for example by
   `EnforcementRunner` before its first rule, or by the job) and handed to the
   rule, which is a larger change than removing the reset.
6. **Cross-job re-gating.** The freeze is per job. A later job on the same
   branch recomputes the changed set against `origin/<base>` and will gate any
   class the review created or edited that was not itself a reviewed candidate
   (Scope bullet 3). Options: rely on the STEP 3 scope rule to keep review edits
   inside the candidate classes; also record in the ledger every file whose blob
   changed during the review session; or accept a one-time review of those
   classes in the next job. The plan currently takes the first option.
7. **How the rule reads the job-wide cost.** Bullet 6 has the rule read the cost
   delta through `CodingAgentJob.getCostTracker().total()`. That reaches through the
   job to call one method on the tracker, which is the shape this very review flags
   in other code. The established pattern is a job-level wrapper: `RetrospectivePhase`
   reads `CodingAgentJob.getCostForModel(modelKey)`, not `getCostTracker().costForModel(...)`,
   and `getCostTracker()` itself is used only by `CodingAgentJobEvent` at the
   serialization boundary. A parallel `CodingAgentJob.getTotalCost()` wrapping
   `JobCostTracker.total()` would keep the rule from reaching through the accessor and
   match how cost is read elsewhere. Decide whether to add that wrapper or let the rule
   call `getCostTracker().total()` directly.

## Estimated complexity

**Medium.** The core mechanism change is small and localized (freeze the
selection), but the prompt rewrite, proportionality signal, cumulative time/cost
reporting, the `JobCostTracker.total()` accessor, the doc update, and three new or
extended tests make it a cohesive medium-sized change within one module.
