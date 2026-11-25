---
status: completed
created: 2026-11-25
closed: 2026-11-25
tracker: [99084539-5161-483e-9fd5-61aa01d9f513]
---

# Fail clearly when a kernel's argument count exceeds the device buffer limit

Plans tracker task **99084539-5161-483e-9fd5-61aa01d9f513** —
*"Fail clearly when a kernel's argument count exceeds the device buffer limit"* —
in the release currently under development (the `Common` release whose name matches the
version in the root `pom.xml`).

## Motivation

A Metal compute function binds its arguments through a buffer argument table with 31 entries, two
of which `MetalOperator.accept` reserves for the per-argument offsets and sizes bound after the
last argument, so a kernel may declare at most 29 arguments (`MetalOperator.MAX_ARGUMENTS`). A
kernel that declares more cannot compile.

Argument aggregation exists to keep fused kernels under that table: it folds eligible small roots
(those with `memLength <= MemoryDataArgumentMap.maxAggregateLength`, default 1024) into one
aggregate buffer. With the default threshold the decode and connectome workloads stay well under
the limit. But eligibility is a size test, so **lowering `maxAggregateLength` removes roots from
the aggregate and inflates the kernel's raw argument count**: a kernel that compiled at the
default threshold can, at a low one, declare more arguments than the device allows. When that
happens the failure should be a descriptive exception that names the operation and its argument
count — not an obscure backend/driver error raised far from its cause.

The task also calls for an audit in the same area. `ProcessDetailsFactory`'s constant cache
(`enableConstantCache`, default on) evaluates an argument once and caches the resulting
`MemoryData` whenever the argument's `Evaluable.isConstant()` reports `true`, reusing it on every
later execution. If a producer reports `isConstant()` while actually reading memory that mutates
between executions, the cache serves a stale value and the computation is silently wrong. The task
asks that every `isConstant()` implementation be checked against this contract and any that reads
mutable memory be fixed (or the findings listed in the PR).

## Current state (what already exists)

The compile/bind-time check the task's first half describes **already landed**, as part of the
argument-limit-aware folding work merged into `master` on 2026-11-16 (design:
`docs/plans/PLAN-20261105-aggregate-only-over-argument-limit.md`). Specifically:

- `ComputeContext.getArgumentLimit()` returns an `OptionalInt` (empty = "no known limit"), and
  `ComputeContext.fitsArgumentLimit(int)` decides whether a structural bound fits. Metal reports
  `MetalOperator.MAX_ARGUMENTS` (29); OpenCL, CUDA and the native backend report no limit.
- `AcceleratedOperation.verifyArgumentLimit(int argumentCount)` throws a `HardwareException` that
  names the operation (`getName()`), the declared argument count, the limit, and which of the
  three causes applies (folding present but insufficient; folding unavailable; or a bound that
  under-reported scope-prepared arguments). It is a no-op on a context with no known limit.
- `AcceleratedComputationOperation.postCompile()` calls `verifyArgumentLimit(...)` on the compiled
  scope's argument count, and discards the scope (resetting arguments, destroying the compiler) if
  it fails, so the operation is left uncompiled rather than returning a rejected scope.
- `ArgumentLimitFoldTest` (engine/utils) already pins the three failure causes through a
  delegating `LimitedComputeContext` that supplies a limit on any backend
  (`unfoldedKernelIsVerifiedAgainstTheLimit`, `foldingKernelIsVerifiedAgainstTheLimit`,
  `kernelThatCannotFoldIsVerifiedAgainstTheLimit`, `kernelFailingVerificationIsNotLeftCompiled`).

What is **not** yet covered is exactly the scenario the task names as its motivating failure and
its "done when": a test that **lowers `maxAggregateLength`** so that a kernel which would otherwise
fit is pushed past the limit, and asserts it gets the descriptive exception. The existing tests
reach the "folding insufficient" cause by disabling aggregation outright or by calling
`verify(...)` directly, never by shrinking the eligibility threshold.

The `isConstant()` audit has not been done.

## Scope

In scope:

1. **Lowered-`maxAggregateLength` regression test (Part 1 "done when").** Add one test that saves
   `MemoryDataArgumentMap.maxAggregateLength`, sets it low enough that a kernel with several small
   inputs can no longer fold enough of them to fit a known argument limit, compiles the operation,
   and asserts the `HardwareException` from `verifyArgumentLimit` naming the operation and the
   argument count. Restore the threshold in a `finally` block. This runs on any backend through the
   existing `LimitedComputeContext` test helper.

2. **Confirm the lowered-threshold path end-to-end.** Before writing the test, confirm by
   inspection and a scratch run that lowering `maxAggregateLength` under a known limit actually
   routes through `verifyArgumentLimit` with the "too few of its arguments are small enough to
   fold" cause (folding is still enabled; it simply cannot fold enough). If the path instead makes
   folding unnecessary or reaches a different cause, the test asserts the cause that actually
   fires — the requirement is a *descriptive exception naming the operation and count*, not a
   specific cause string.

3. **`isConstant()` audit (Part 2).** Enumerate every `isConstant()` implementation reachable by an
   `Evaluable` that can become a kernel argument, trace what each returns `true` for, and judge
   whether any can report constant while its evaluation reads memory whose contents change between
   executions. The known implementations to cover:
   - `Computable.isConstant()` (default `false`);
   - `Provider` / `FixedEvaluable` (inherit the `false` default — confirm they are not overridden
     elsewhere to claim constant while wrapping a `PackedCollection`);
   - `AcceleratedComputationEvaluable.isConstant()` → `getComputation().isConstant()`;
   - `HardwareEvaluable.isConstant()` → `getKernel().getValue().isConstant()`;
   - `ReshapeProducer.isConstant()` → delegate;
   - `TraversableExpressionComputation.isConstant()` → all inputs after the destination;
   - `CollectionProducerComputation`'s inner operator → `op.isConstant()`;
   - `CollectionProducerComputation.IsolatedProcess.isConstant()` → delegate (`op.isConstant()`);
   - any leaf producer that forces `true` (none found in a first pass; the enumeration must be
     exhaustive, since `isConstant()` propagates from leaves).

   Fix any implementation that reports constant while reading mutable memory. If the audit finds
   nothing to fix — the likely outcome given the first pass — record the enumeration and the
   reasoning in the PR description so a reviewer can see the contract was checked, not skipped.

Out of scope (name as dependencies / follow-ups, do not widen this change):

- Re-deriving or changing the fold policy, the aggregate copy machinery, `getArgumentBound`, or the
  per-backend limits — all delivered and tested by the 2026-11-16 work.
- Adding an argument limit to OpenCL/CUDA/native. Those report "no known limit" by design (no
  evidence-backed number); inventing one is explicitly forbidden by the prior plan.
- Any substantial fix to a broken `isConstant()` implementation the audit might uncover. The previous
  release's task review already flagged "split isConstant audit out"; if the audit finds a defect whose fix
  is more than a localized correction, file it as its own tracker task rather than growing this
  branch (see Open questions).

## Approach

- **Part 1 test** lives beside the existing coverage in
  `engine/utils/src/test/java/org/almostrealism/hardware/test/ArgumentLimitFoldTest.java`. Reuse its
  `LimitedComputeContext`, its `FoldProbe`/`run(...)` helpers, and its small-input `a(dest, sumOfInputs(inputs(N)))`
  construction. The new method mirrors `kernelThatCannotFoldIsVerifiedAgainstTheLimit` but reaches
  the overflow by lowering `MemoryDataArgumentMap.maxAggregateLength` (so the small roots become
  ineligible and folding has too little to fold) instead of by disabling aggregation. It asserts
  the exception message contains the operation name and the argument count. Because the limit is
  supplied through the test context, the test runs under the native backend in CI; no Metal device
  is needed.
- **Part 2 audit** is an investigation plus, if warranted, a code fix. The relevant cache site is
  `ProcessDetailsFactory` (the `enableConstantCache && kernelSize > 0 && kernelArgEvaluables[i].isConstant()`
  branch that evaluates and caches `kernelArgs[i]`). The audit must treat `isConstant()` as a
  *declared property* and verify it against what the evaluation actually reads — the same class of
  reasoning recorded in `docs/plans/INVESTIGATION-20261031-declared-property-shortcut.md`. Any fix
  belongs on the type whose `isConstant()` is wrong (correcting what it reports), never on the
  cache site second-guessing the contract.
- No `pom.xml` changes, no new modules. If the audit finds a defect requiring a fix, that fix stays
  on the type that owns `isConstant()`.

## Success criteria

A reviewer can confirm the task is done when:

- **The Part 1 regression test exists and passes**: with `maxAggregateLength` lowered under a known
  argument limit, compiling the over-limit operation raises a `HardwareException` whose message
  names the operation and its argument count; the threshold is restored afterward; the test passes
  on the native backend (CI) with no Metal device. Removing the `verifyArgumentLimit` call makes the
  new test fail.
- **The existing argument-limit tests still pass unmodified**: `ArgumentLimitFoldTest`,
  `AggregateCopyInTest`, `AggregateCopyBackTest`, `InstructionCacheCollisionEnforcementTest`,
  `ProcessDetailsFactoryRecoveryTest` (run as a bounded selection in engine/utils, ≤5 classes per
  invocation, each with a timeout).
- **The `isConstant()` audit is complete**: every implementation reachable as a kernel argument is
  enumerated with its constant condition and a verdict, and the PR description lists the findings.
  Any implementation found to report constant while reading mutable memory is fixed, with a test
  that would have caught it (a constant-cache round-trip: evaluate, mutate the backing memory,
  re-evaluate, and assert the result tracks the mutation). If nothing needs fixing, the PR states
  so with the enumeration as evidence.
- The build validator passes (checkstyle, code_policy, test_timeouts, duplicate_code); every new
  `@Test` carries a timeout.

## Dependencies

- Builds directly on the argument-limit-aware folding work merged 2026-11-16
  (`PLAN-20261105-aggregate-only-over-argument-limit.md`): `ComputeContext.getArgumentLimit()` /
  `fitsArgumentLimit`, `AcceleratedOperation.verifyArgumentLimit`, and the `postCompile` wiring. No
  blocking dependency remains; that work is on `master`.
- No dependency on a Metal device: the mechanism and all tests are backend-agnostic through the
  `LimitedComputeContext` helper. No `*-workstream.yaml` file is written for this branch.

## Open questions

1. **Is the Part 1 half already satisfied, modulo the test?** The compile-time check and its
   exception landed on 2026-11-16, after this task was written, so the task's description ("nothing
   checks — the failure surfaces as an obscure driver error") is now stale. This plan treats the
   remaining Part 1 work as the single regression test the "done when" demands plus confirmation of
   the lowered-threshold path. If the approver reads Part 1 as already complete, the task collapses
   to Part 2 plus that one test. The task description should be corrected to reflect the landed
   mechanism (planning will note this on the tracker task).
2. **Where does the `isConstant()` audit's fixes go?** The previous release's task review recommends splitting the
   `isConstant()` audit out of this task. The task's own "done when" allows findings to be *listed*
   rather than fixed. Proposed disposition: perform the audit here and list findings in the PR; if a
   non-trivial fix is discovered, file it as a separate tracker task (named here and in the PR) and
   reference it, rather than growing this branch. The approver should confirm this split or ask for
   the fix inline.
3. **Should the Part 1 test also run on a real Metal device?** The descriptive exception is
   backend-independent, so the CI (native) test proves the behaviour. A Metal run would additionally
   prove the real driver error is pre-empted, but needs a macOS node. Proposed: keep the test
   backend-agnostic (no machine requirement); if the approver wants the Metal confirmation, add it
   as a follow-up with a `platform: macos` workstream setting.

## Why the implementation request appeared to produce nothing

This section answers a direct question from the owner: when asked to implement this plan, why did
the agent sessions on this branch appear to do nothing? It is reconstructed from the branch's
commit history and the workstream's job and message record, not from recollection.

### What actually happened, session by session (all on 2026-11-25)

1. **Planning job (morning).** Wrote this plan with `status: proposed` and changed no code. That
   was correct for a planning job. Its second-pass review also changed nothing.
2. **Documentation-review job (about an hour later).** This is the session that *did nothing*. It
   was a documentation review of the branch's changes, not an implementation job. Its prompt said
   not to implement any plan, so it checked every claim in this document against the source,
   found them all accurate, made no edits and wrote no commit. Its final message said the
   implementation "remains for the person who approves the plan". If this job was submitted to
   *implement* the plan, the request never reached the agent in a form it acted on: the prompt it
   ran under was the review template, and the agent obeyed that template's "do not implement"
   rule instead of noticing that the plan was why the job existed.
3. **Implementation job (afternoon).** This session did implement the plan, but its whole
   result was **two test files and edits to this document, with no production code changed**:
   - `ArgumentLimitFoldTest.loweredAggregateThresholdIsVerifiedAgainstTheLimit` (Part 1);
   - `ConstantArgumentCacheAuditTest` (a guard for the Part 2 audit);
   - this plan set to `status: completed`.

   The branch diff against `master` is this document plus those two test files, nothing else.

### Why the result looks like "nothing"

- **The plan predicted an empty diff, and the implementation delivered it.** The plan says the
  Part 1 mechanism (`verifyArgumentLimit`, `ComputeContext.getArgumentLimit()`, the `postCompile`
  wiring) was already on `master`, so Part 1 shrank to one regression test. It also says the
  `isConstant()` audit would *likely find nothing to fix*. The implementing session followed
  that framing: it added the test, decided no `isConstant()` implementation was defective,
  added a guard test, and closed the plan. That is "doing the plan", but it changes no behaviour,
  and a reader expecting a production change would reasonably say nothing was done.
- **The audit's findings were recorded where a reader of the branch does not look.** The
  enumeration of `isConstant()` implementations and their verdicts was put in the pull request
  description and in workstream memory, not in this document or the code. Someone reviewing the
  diff sees two tests and a status change, with no visible evidence that the audit happened.
- **The planning stage shrank the task, and no later session pushed back.** The tracker task asked
  for a clear failure when a kernel exceeds the device argument limit. Because a related change had
  already landed, planning reduced that to "add one test". No session asked the owner whether the
  task should be closed as already satisfied, or whether more was wanted (for example, a Metal
  confirmation, Open question 3, which was left as an optional follow-up). Open questions 1–3
  were written for an approver, but nobody waited for or recorded the approver's answers; the
  implementing session picked the proposed answers itself.
- **Review sessions confirmed rather than questioned.** Every review pass on this branch (four)
  reported "clean, no edits warranted". None asked whether an implementation that changes only
  tests met the owner's intent.

### What should have happened instead

- The documentation-review job should have said clearly, in its first status message, that it
  was a review job and would not implement the plan, so that a misrouted implementation request
  was visible straight away instead of looking like a refusal.
- The implementing session should have told the owner, *before* closing the plan, that this plan
  as written produces no production change. It should have asked whether that was acceptable or
  whether the task should be closed as already satisfied by the earlier work.
- The `isConstant()` audit enumeration (each implementation, its constant condition, its verdict)
  should be in this document, next to the checklist in Scope item 3, so the evidence for "no fix
  needed" is part of the branch rather than only in the PR description.

## Estimated complexity

**Small.** Part 1 is one regression test over an already-implemented and already-tested mechanism.
Part 2 is a bounded audit of roughly a dozen `isConstant()` implementations whose first pass shows
no obvious defect (the risky leaves — `Provider`/`FixedEvaluable` — inherit the safe `false`
default); any fix found would be localized, and a larger fix is split out per the task review.
