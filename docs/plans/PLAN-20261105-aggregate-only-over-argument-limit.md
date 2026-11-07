---
status: proposed
created: 2026-11-05
closed:
tracker: [7bf0af0f-6afc-4ae1-9b61-b49587531d9c]
---

# Fold kernel arguments into the aggregate only when the kernel would exceed its argument limit

**Category:** Performance

## Motivation

Argument aggregation exists for one hard reason, which `MemoryDataArgumentMap.enableArgumentAggregation`'s
own Javadoc states: a fused kernel with many small inputs must stay under its compute context's
buffer-argument limit or it cannot be compiled at all. Metal binds at most 29 kernel arguments
(its 31-entry buffer table, less the offsets and sizes bound after the last argument; see
`MetalOperator.accept`).

The fold decision does not use that requirement. `MemoryDataArgumentMap.get` folds **every**
non-kernel-owned root argument whose memory is at most `maxAggregateLength` (1024) elements, one
`get` call at a time, whatever the kernel's argument count. A kernel with four small inputs gets an
aggregate it never needed. Each folded slice then costs a device copy around each dispatch: a copy-in
before it and, for a writable slice the kernel writes, a copy-back after it. On Metal each copy is a
command-buffer blit.

The last three performance cycles reduced the *number of copies per folded slice*:

- copy-back limited to the slices the compiled `Scope` writes (PR #783 and successors);
- read-only slices copied in once (PR #825);
- unread slices not copied in when nothing copies them back (PR #839).

Each was correct, structurally tested, and left the measurement roughly where it was. On Metal,
`CausalLanguageModelDecodeThroughputTest#slidingKeyValueCacheLongDecodeThroughput` still reports
about **245 dispatches/byte** for the rolling KV-cache decode, against the sliding window's ~123.
PR #839's measured result explains why: about 92% of the aggregating dispatches copy back, and the
slices left in them are written destinations that must keep their copy-in. The follow-up filed
there (tracker `a3725998`) would remove those copy-ins with a proof that the kernel overwrites every
element of a slice. That proof has to reason about per-dispatch work counts and size-generic reused
kernels, and it would still leave every copy-back in place.

This plan changes the question from *how few copies a folded slice needs* to *whether a slice
should be folded at all*. A slice that is not folded is a kernel argument in its own right and
needs no copies. If the decode kernels are under Metal's limit, as the small fused operations of a
one-position decode step should be, this removes their aggregate copies entirely: copy-ins and
copy-backs, read and unread, writable and read-only.

That matters for both proof-of-value tracks the copy path has capped:

- the self-hosted LM's long-form self-description, which is where the rolling cache was supposed
  to beat the sliding window;
- the connectome simulation, whose tensors are folded every step.

Every kernel the platform dispatches pays this copy cost, so the win is general, not specific to one
model.

## Scope

1. **An argument limit on the compute context.** Add an accessor to `ComputeContext` (in
   `base/code`) that reports the most kernel arguments a program compiled for that context may
   declare, or "no known limit".
   - Metal reports 29, derived from the buffer table as `MetalOperator` describes it. Keep that
     description and the value in one place, so the Javadoc and the number cannot drift apart.
   - OpenCL, CUDA and the native (JNI/C) backend each report a limit only where there is evidence
     for it: a device query, a documented compiler limit, or a measured failure. A backend with no
     evidence reports "no known limit".
   - Do **not** guess a number. An invented limit that is too high reintroduces the compile
     failure that aggregation exists to prevent.
2. **Fold only the overflow.** When the context has a known limit, `MemoryDataArgumentMap` folds
   only as many eligible arguments as it takes to bring the kernel's argument count under the
   limit, counting the aggregate argument itself.
   - Folding prefers the smallest eligible roots, which keeps the aggregate and its copies small.
     Ties break by first-registration order, so the choice is deterministic.
   - A kernel already under the limit folds nothing.
   - With no known limit, behaviour is exactly what it is today: every eligible argument is folded.
     That keeps the native backend, where the parameter limit is unmeasured, safe by construction.
   - `enableArgumentAggregation = false` still turns folding off completely.
   - `maxAggregateLength` still bounds which arguments are eligible.
3. **Deterministic, replayable decision.** `AcceleratedComputationOperation.rebindAggregateForReuse`
   replays the fold over a reusing computation and requires its positions to match the compiled
   `AggregateLayout`. The new decision must therefore depend only on things that fold replay sees
   identically: the set of eligible roots, their delegated lengths, their registration order, and
   the context's limit. It must never depend on where data currently lives or on run-time state.
   The collision check stays unconditional and unchanged in meaning.
4. **Decide once the argument set is known.** Today `get` folds incrementally, so a fold decision
   is made before the kernel's full argument count exists. The implementation must make the
   decision once the set of root arguments for the scope is known. The plan does not prescribe
   the mechanism. Plausible options:
   - deferred `ArrayVariable` resolution, where `get` registers a root and the folded or standalone
     binding is fixed when preparation completes;
   - a counting pre-pass over the computation's inputs, using the same root-delegate de-duplication
     that `get` performs.

   Whichever is chosen, it must keep `prepareScope` single-pass from the caller's point of view and
   must not compile the scope twice. Put the decision on `MemoryDataArgumentMap`, the type that owns
   the concept, not in a helper.
5. **Structural regression test (any backend).** In `engine/utils`, beside `AggregateCopyInTest` and
   `AggregateCopyBackTest`, add a test that pins the policy against a map built with an explicit
   limit, with no timing threshold. Cases:
   - under the limit, nothing is folded;
   - over the limit by *k*, exactly the *k*+1 smallest eligible roots are folded (the aggregate
     itself takes a slot);
   - with no known limit, every eligible root is folded, as today;
   - replay reproduces the layout, and a computation whose fold differs is still reported as a
     collision.

   The test supplies the limit through the context or map constructor, not by mutating a global.
6. **Measurement on Metal.** Run `CausalLanguageModelDecodeThroughputTest#keyValueCacheDecodeThroughput`
   and `#slidingKeyValueCacheLongDecodeThroughput`, and record dispatches/byte, commits/byte and
   bytes/second before and after in this plan's implementation-status section. Log each decode
   kernel's argument count and folded count (`describeAggregate`) so the result is explained, not
   just reported.
7. **Docs.** Update the "Argument Aggregation" section of `base/hardware/README.md`, the Javadoc of
   `enableArgumentAggregation` / `maxAggregateLength` / `createArgumentMap`, and the paragraph in
   `docs/internals/argument-binding-lifecycle.md` that says "nothing yet checks the limit". After
   this change something does check it, and the failure mode changes: a backend with a known limit
   can no longer exceed it through folding.

## Approach

- Start from the measurement. Before changing the policy, instrument one decode step on Metal and
  record, per aggregating kernel, how many root arguments it has and how many it folds. If most
  decode kernels are under 29 arguments, the expected effect is large: the aggregate copies of
  those kernels disappear. If they are over, the overflow-only fold still removes the copies of the
  largest eligible slices. Say which case holds in the plan.
- Follow the rule the copy arc has kept throughout: decide from structure the compiled program
  actually has, never from a property a computation declares (see
  `INVESTIGATION-20261031-declared-property-shortcut.md`). The argument count of a kernel *is* its
  structure, so the rule holds here.
- Do not change the slice-copy machinery. `SliceCopy`, `getReadSlices` / `getWrittenSlices`, the
  copy-once partition and `AggregateLayout` stay as they are and still govern whatever is folded.
  The plan reduces what reaches them. Per the owner's review on PR #839, keep the single filtered
  `List<SliceCopy>` and do not reintroduce cached copy lists.
- Several tests disable aggregation to obtain clean chaining or commit counts:
  `SemaphoreChainBatchingTest`, `AssignmentSemaphoreChainTest`, `MetalTimelineSignalTest`,
  `FixedArgumentBindingTest`, `DestinationReuseTest`. Run them unchanged; they must still pass. Do
  not remove their toggles: those tests specify behaviour with aggregation off, which remains a
  supported configuration.
- Run, at most 5 classes per invocation:
  - `AggregateCopyInTest`, `AggregateCopyBackTest`, `InstructionCacheCollisionEnforcementTest`,
    `ReadOnlyAggregateCopyTest`, `ProcessDetailsFactoryRecoveryTest`;
  - the new structural test;
  - `ConnectomeNetworkTest`, as a correctness check on a heavily folded workload.
- Then take the Metal measurement.

## Success Criteria

- `ComputeContext` reports an argument limit. Metal returns 29; every other backend returns
  either an evidence-backed value or "no known limit".
- With a known limit, a kernel under it folds no arguments, and a kernel over it folds exactly the
  overflow, choosing deterministically, smallest first.
- With no known limit, or with aggregation disabled, behaviour is byte-for-byte what it was.
- The new structural test passes on any backend. The existing aggregation, reuse, collision and
  aggregation-disabled tests pass unmodified.
- No compiled kernel on Metal exceeds the buffer table. `ProcessDetailsFactoryRecoveryTest` and
  the decode, connectome and attention tests compile and pass.
- On Metal, the rolling-cache decode reports **fewer dispatches/byte than ~245**, with no more
  commits/byte, and the plan records the per-kernel fold counts that explain the change. Goal:
  rolling-cache dispatches/byte at or below the sliding window's. If that is not reached, the plan
  says what remains and why, without moving the goal.
- No test is weakened: no loosened tolerance, reduced dimension, raised `@TestDepth`, inflated
  timeout, skip gate, or deleted toggle.

## Dependencies

None blocking. It builds on the copy-back, copy-once and skip-unread work (PRs #783, #825, #839),
all merged, and leaves their machinery intact for the arguments that are still folded. It
supersedes the need for tracker `a3725998` for every kernel under its limit. That task stays open
for kernels that must still fold a written destination, to be re-evaluated against this plan's
measurement. The single-blit multi-region Metal copy likewise remains a follow-up for whatever
copies survive.

## Machine

Correctness and the structural test run on any backend. The dispatch-count and commit-count
measurement, and the evidence that no kernel exceeds Metal's buffer table, need a Metal device,
which only macOS nodes provide. The workstream settings file beside this plan requires
`platform: macos`.

## Estimated Complexity

Medium. The pieces are:

- one accessor on `ComputeContext`, with a Metal implementation;
- a fold decision in `MemoryDataArgumentMap` that moves from per-`get` to once-per-preparation,
  which is the delicate part, because the variables handed out during preparation must resolve to
  folded or standalone bindings consistently with replay;
- one structural test;
- doc updates;
- the Metal measurement.
