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
- Run as targeted selections, one module per test-runner invocation (at most 5 classes each; these
  classes span three modules, so they cannot be run in a single invocation):
  - `engine/utils`: `AggregateCopyInTest`, `AggregateCopyBackTest`,
    `InstructionCacheCollisionEnforcementTest`, `ProcessDetailsFactoryRecoveryTest`, and the new
    structural test;
  - `engine/ml`: `ReadOnlyAggregateCopyTest`;
  - `studio/bioscience`: `ConnectomeNetworkTest`, as a correctness check on a heavily folded
    workload.
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

## Assessment (2026-11-10)

**Recommendation: do not implement the plan as written. Implement a narrower, all-or-nothing
variant (Variant B below) instead.** The plan's diagnosis is right, and its goal is reachable:
with no folding at all, the rolling-cache decode drops from 245 to 112 dispatches/byte, below the
sliding window. The part that costs the most, though, is folding only the overflow, smallest
first, with the decision deferred until the argument set is known. That part buys almost
nothing on any measured workload. It is also the only part that can break the determinism
constraint.

This is a recommendation, not a decision. The owner reserved the decision on this plan — it was
put on hold pending the instruction-cache benchmark, which this assessment now uses — so the
lifecycle status is left `proposed` and awaits their call rather than being closed here. (An
`abandoned`/`closed` plan is removed by the plan sweep 14 days later, which would delete this
assessment before the owner acts on it.) The recommended disposition is to re-scope tracker task
`7bf0af0f` to Variant B, or to approve Variant B as its own plan and abandon this one — see
"Open questions for the owner" below.

### Open questions for the owner

1. **Disposition.** This assessment recommends Variant B over Variant A (the plan as written).
   Acting on it means one of: re-scope tracker task `7bf0af0f` to Variant B and keep this document
   as its plan of record; or spin Variant B into its own plan and abandon this one. Either is the
   owner's call; the plan is held `proposed` until then so the assessment is not swept away.
2. **Variant B's one unmeasured number.** Whether every decode kernel's *structural* argument
   bound (before de-duplication) is at most 29, as the de-duplicated count already is. The
   "Smallest experiment that decides B" section says how to settle it. If a decode kernel's
   structural bound over-counts aliased roots, the next step is to put root sharing into the
   signature — not to adopt Variant A.
3. **Determinism.** Variant A's fold decision depends on the distinct-root count, which the
   signature does not encode, so it can throw an `AGGREGATE_LAYOUT` collision between two
   computations that share a signature — the constraint the owner stated. Variant B avoids this by
   construction. Confirm this reading before any Variant A work is scheduled.

### Evidence gathered

All runs used this branch, after confirming that `origin/master` (including the benchmark) is
merged into it. Production code was not changed. Every comparison flips the existing switch
`AR_HARDWARE_ARGUMENT_AGGREGATION`. With that switch disabled, every kernel folds nothing. That
is exactly what both variants do to a kernel under the limit, so this one switch measures the
plan's end point without implementing it.

| Run | Command (all on an Apple Silicon host, Metal available) |
|---|---|
| Benchmark, baseline | `docs/internals/instruction-cache-benchmark/baseline.json` (seed 1729, population 500) |
| Benchmark, nothing folded | same command plus `-DAR_HARDWARE_ARGUMENT_AGGREGATION=disabled`, compared with `scripts/compare-cache-benchmark.py` (fingerprints equal) |
| Decode, baseline | `CausalLanguageModelDecodeThroughputTest#keyValueCacheDecodeThroughput` and `#slidingKeyValueCacheLongDecodeThroughput` |
| Decode, nothing folded | the same two methods with `-DAR_HARDWARE_ARGUMENT_AGGREGATION=disabled` |
| Connectome, nothing folded | `ConnectomeNetworkTest#matchesBrian2Spikes`, `#sugarCircuitMatchesBrian2Spikes`, `#poissonInputDrivesItsTargets`, `#silentWithoutInput` on Metal with aggregation disabled: 4/4 pass |

Decode measurements (Metal; counts are deterministic; bytes/second come from one run each and
are noisy):

| Generator | Dispatches/byte (on → off) | Commits/byte (on → off) | Bytes/s (on → off) |
|---|---|---|---|
| sliding window (context) | 123.0 → 114.0 | 7.02 → 7.02 | 194 → 218 |
| bounded KV cache | 245.1 → 112.1 | 5.05 → 3.06 | 237 → 310 |
| sliding window (3 × context) | 123.0 → 114.0 | 7.01 → 7.01 | 228 → 228 |
| rolling KV cache (3 × context) | 245.0 → 112.0 | 4.65 → 3.02 | 257 → 324 |

Kernel invocations per byte are unchanged (80.0 and 85.0). Both greedy continuations, and the
within-context agreement assertion, are unchanged. No decode kernel failed Metal compilation
with nothing folded, and a 30-argument Metal kernel does fail (see below). So **every kernel the
decode compiles for Metal has at most 29 arguments**, and the plan's "under the limit" case is
the only case the decode exercises.

The branch's `decisions` memory could not be read: the ar-manager service rejected this session's
credentials. The owner's constraint is taken as stated in the request for this assessment. Fold
decisions must be deterministic for every computation that shares a signature and compute
context. Each decision must either be reflected in the signature or come from a real dry-run
compile. Otherwise a mismatch surfaces as an `AGGREGATE_LAYOUT` collision in
`rebindAggregateForReuse`.

### What the baseline shows, and where it departs from the plan's assumptions

1. **The benchmark's workloads are not the workloads the plan was written for.** The plan is
   motivated by copy-back: about 92% of the decode's aggregating dispatches copy back written
   destinations. The benchmark records **zero copy-backs in every scenario**, because
   `evaluate()` supplies an explicit output and the default side-effect policy
   (`enableStrictSideEffects = false`) then skips copy-back. The benchmark can therefore price
   the copy-ins and the structural effects of a fold policy. It says nothing about the copy-back
   cost the plan set out to remove. The decode tests are the measurement for that.
2. **Kernels are far below the limit.** Unfolded, the largest kernel in `generated`,
   `size-generic`, `aliasing` and `aggregate-threshold` has 6 arguments. In `generated`, 771 of
   869 kernel requests have 2 arguments, typically the output plus the aggregate. The plan
   expected the decode kernels to be small; the benchmark shows that ordinary generated kernels
   are small too.
3. **`Process::optimize` decides kernel boundaries independently of folding.** The
   `argument-limit` chains at 16 elements (folded) and at 2048 elements (unfolded) split into
   the same number of kernels, at most 10 arguments each. The nothing-folded run left every
   instance's kernel count unchanged. Only a single n-ary computation (`add(List)`) exceeds the
   limit.
4. **Metal's 29 is confirmed empirically.** In the baseline, `flat-sum-of-28` (29 arguments)
   compiles on Metal and `flat-sum-of-29` (30 arguments) fails. JNI compiled 41 arguments
   without failing, which is evidence of headroom but not a limit.
5. **Folding prevents a failure in exactly one benchmark case.** That case is the flat sum of 29
   or more *small* inputs, 4 templates × 2 instances. With nothing folded these become 8
   additional Metal failures (8 → 16, half of them the `ProcessArgumentMap.match`
   `NullPointerException` follow-up). The 8 baseline failures have 2048-element inputs. No fold
   policy can help them, because those inputs are not eligible.
6. **Today's fold is context-independent.** Every deterministic JNI metric equals its MTL
   counterpart. Any limit-aware policy makes Metal and the native backend diverge. The cache
   tolerates this, because it is keyed by context. Behaviour, and the tests that observe it,
   will differ between a macOS host and a Linux host.
7. **The signature over-partitions for folding's sake.** `CollectionProviderProducer.signature()`
   appends `&aggRoot=<root length>` to every eligible leaf. In `aggregate-threshold`, the 27
   root-length combinations compile 71 kernels (hit rate 0.4453). With nothing folded, which
   drops the suffix, they compile 19 (hit rate 0.8516, native compile time ×0.33). In
   `generated` the suffix costs nothing (0.6628 either way), because there every root is the
   size of its view.

### Variants considered

- **A — the plan as written.** On a context with a known limit, fold only the overflow, choosing
  the smallest eligible roots first, with the decision made once the kernel's argument set is
  known.
- **B — all-or-nothing on a structural bound.** On a context with a known limit, an operation
  folds nothing when its *structural* argument bound fits the limit. Otherwise it folds exactly
  as today. The structural bound is the number of argument slots of its `Process` tree before
  any de-duplication by memory or value identity, plus the output. De-duplication only lowers
  the real count, so a bound that fits guarantees a kernel that fits. The decision sits where
  the operation creates its argument map: `AcceleratedOperation.createArgumentMap()`, through
  the existing `isArgumentAggregationSupported()` hook, passing a `null` aggregate generator.
  `MemoryDataArgumentMap.get` keeps its per-call fold contract, so there is no deferred
  resolution. `rebindAggregateForReuse` calls the same `createArgumentMap()`, so the reuser
  reaches the same decision. With no known limit, nothing changes.
- **C — optional, after B.** Move the `aggRoot` suffix out of leaf signatures, and append the
  fold layout to the kernel signature only for kernels that do fold. This is a signature change
  with its own collision argument. It is listed only because the nothing-folded run gives its
  upper bound.

### Predictions per scenario and context

For the **JNI context, every metric under A and B equals the baseline** (high confidence). The
native backend has no evidence-backed limit, and both variants leave "no known limit" exactly as
it is today. The table covers MTL. "Measured" means the value equals the nothing-folded run.
That holds wherever no kernel is near the limit, because there A, B and nothing-folded make the
same decision for every kernel, and A and B keep today's signatures.

| Scenario (MTL) | Metric | Baseline | A | B | Confidence and reasoning |
|---|---|---|---|---|---|
| generated | hit rate | 0.6628 | 0.6628 | 0.6628 | High. Signatures are unchanged, and hits depend only on signatures. C gives 0.6628 too (measured). |
| | collision rate | 0 | 0 | 0 | High. No kernel is near the limit, so no decision can differ between computations sharing a signature. |
| | failures | 0 | 0 | 0 | High. Max 6 arguments. |
| | arguments/kernel | 2.2152 (6) | 3.4499 (6) | 3.4499 (6) | High (measured). |
| | folded/compiled kernel | 1.7577 (5) | 0 | 0 | High (measured). |
| | copies in per aggregating dispatch | 3.0754 | none | none | High. No aggregating dispatch remains; copy-ins over three evaluations go 4770 → 0. |
| | kernels/computation | 1.7380 | 1.7380 | 1.7380 | High (measured; optimize does not see folding). |
| size-generic | hit / collisions / failures | 0.57 / 0 / 0 | unchanged | unchanged | High, as for generated. |
| | arguments/kernel; folded | 2.24; 1.9767 | 3.32; 0 | 3.32; 0 | High (measured). |
| | copies in per aggregating dispatch; kernels/computation | 3.16; 1.7857 | none; 1.7857 | none; 1.7857 | High. |
| aliasing | hit / collisions / failures | 0.5714 / 0 / 0 | unchanged | unchanged | High for this population: max 4 arguments. See the correctness section for what this scenario cannot see. |
| | arguments/kernel; folded | 2.2857; 0.7667 | 2.5714; 0 | 2.5714; 0 | High (measured). |
| | copies; kernels/computation | 1.9524; 1.6667 | none; 1.6667 | none; 1.6667 | High. |
| aggregate-threshold | hit rate | 0.4453 | 0.4453 | 0.4453 | High. The `aggRoot` suffix stays, so 71 kernels are still compiled although none of them folds. C: 0.8516 (measured upper bound). |
| | collisions / failures | 0 / 0 | 0 / 0 | 0 / 0 | High. |
| | arguments/kernel; folded | 2.4688; 1.7746 | 3.0; 0 | 3.0; 0 | High (measured). |
| | copies; kernels/computation | 2.1724; 2.0 | none; 2.0 | none; 2.0 | High. |
| argument-limit | hit rate | 0.7595 | 0.7595 | 0.7595 | High. Failures, and therefore requests, are as in the baseline. |
| | collisions | 0 | 0 | 0 | High. Every instance is `DISTINCT`, and repeats are identical. |
| | failures | 8 | 8 | 8 | High. The failing kernels have ineligible 2048-element inputs. Nothing-folded gives 16. |
| | arguments/kernel | 6.3141 (29) | 10.68 (29) | 9.99 (29) | High for B; medium-high for A. A folds `n − 27` roots of the flat sums with `n` ≥ 29 and leaves each at exactly 29, which assumes its count matches `get`'s de-duplication exactly. |
| | folded/compiled kernel | 5.94 (40) | 0.32 (13) | 1.82 (40) | Same reasoning. |
| | copies in per aggregating dispatch | 9.75 | 5.75 | 32.75 | High. Only the four small flat sums aggregate, at 2/3/5/13 slices (A) or 29/30/32/40 (B). First-evaluation copy-ins go 1560 → 46 (A) and 1560 → 262 (B). |
| | kernels/computation | 2.7857 | 2.7857 | 2.7857 | High. |
| decode (cache paths) | dispatches/byte | 245 | 112 | 112 if the structural bound fits | High for A (measured: every decode kernel fits). Medium for B. B reaches the measured value only if no decode kernel's *structural* bound exceeds 29 while its de-duplicated count fits, for example by referring to one root through many leaves. This is the open question; see the experiment. |
| | commits/byte | 4.65 (rolling) | 3.02 | 3.02 under the same condition | As above. |

### Consequences for the platform

**Correctness.**

- *A adds a collision class that the benchmark cannot see.* A's decision depends on how many
  *distinct roots* a kernel has, including ineligible ones. The signature does not encode root
  identity: a provider leaf is `offset:memLength|shape` (plus `aggRoot` when eligible). Take two
  computations that are identical except that two large leaves at offset 0 with the same length
  refer to two different roots in one and to the same root in the other. They share a signature
  but differ by one argument. At the limit boundary A folds in one and not in the other, and the
  reuser's replay throws `AGGREGATE_LAYOUT`. The collision is detected, not silent, so it
  surfaces as a failure. Still, it is exactly the mismatch the owner's constraint forbids. Every
  `aliasing` case differs in offset or root length, or (`SAME_VIEW`) is already distinguished,
  and every `argument-limit` instance is `DISTINCT`, so the baseline's 0 collisions say nothing
  about it. To make A safe, root sharing would have to enter the signature, or the decision
  would have to come from a dry-run whose inputs are themselves in the signature. Either is a
  larger change than the plan budgets.
- *B adds no collision class.* Its decision is a function of the `Process` tree, which the
  signature encodes, and of the context, which keys the cache. A kernel that does not fold has
  an empty layout for every computation sharing its signature. That also removes, for those
  kernels, today's sensitivity of the layout to small-root aliasing.
- *A changes `MemoryDataArgumentMap.get`; B does not.* `AggregateCopyInTest`,
  `AggregateCopyBackTest` and `ReadOnlyAggregateCopyTest` call `get` directly on a map built by
  `MemoryDataArgumentMap.create(Hardware.getLocalHardware().getComputeContext(), …)`, and use
  each folded variable immediately. Under A's deferred resolution on a Metal host, those maps
  would learn a limit of 29 and fold nothing. The plan's criterion that the existing tests "pass
  unmodified" is in tension with its own mechanism. B leaves those maps untouched, because the
  decision is made by the operation before it creates the map.
- *Both change one observable behaviour.* Today, a kernel given an explicit output under the
  default side-effect policy drops its writes to *folded* buffers (no copy-back). It keeps its
  writes to unfolded ones. So a side effect currently survives or not depending on whether its
  buffer has ≤ 1024 elements. On Metal both variants make every non-folding kernel behave as
  unfolded: its writes land. That is more consistent, and the decode and connectome results are
  unchanged by it. It is a behaviour difference between Metal and the native backend, and the
  implementation should state it in `enableStrictSideEffects`' Javadoc.

**Cache behaviour.** A and B leave the hit rate and signatures unchanged. The `aggRoot`
over-partitioning remains, and becomes pure waste on Metal, where almost nothing folds any more:
`aggregate-threshold` compiles 71 kernels where 19 would do. Variant C recovers that (0.45 →
0.85 measured), but it is a separate change and is not needed for the copy win.

**Copy costs.** On the decode, the whole aggregate copy traffic goes: 245 → 112 dispatches/byte
on the rolling cache, now *below* the sliding window, and commits/byte down by a third. This
meets the plan's goal and makes tracker `a3725998` (proving full overwrites to drop copy-ins)
unnecessary on Metal. On the benchmark, every copy-in in four of five scenarios goes. The only
place A beats B is a kernel with more than 29 arguments and small inputs. There, A copies the
overflow (46 slices per evaluation in `argument-limit`) where B copies everything (262). No
measured real workload has such a kernel. The cost of that marginal gain is A's whole delicate
part: deferred variable resolution, smallest-first ordering, partial layouts, and the collision
class above.

### The argument

The plan rests on two claims: decode kernels are under the limit, and not folding them removes
the copies that cap decode throughput. Both are now measured, not assumed. Every decode kernel
fits, and removing the fold more than halves dispatches/byte with identical output. That justifies
investing. What it does not justify is the overflow-only mechanism. The benchmark shows that
`Process::optimize` already keeps ordinary kernels at 10 arguments or fewer. Only single n-ary
computations reach the limit, and for those today's fold-everything rule is adequate. The one
thing the plan adds over "fold nothing when it fits" is the one thing that collides with the
owner's determinism constraint. Variant B keeps the measured win, uses an existing hook, and is
deterministic by construction.

### Smallest experiment that decides B

One number is not yet measured: whether B's *structural* bound (before de-duplication) is at
most 29 for every decode kernel, as the de-duplicated count is. Decide it this way:

1. Implement B behind the existing hook, with Metal reporting 29 and every other backend
   reporting no limit. This is a small change. Alternatively, log each Metal kernel's structural
   bound next to its argument count for one decode step.
2. Run the two decode methods above and the benchmark at seed 1729, population 500. Compare the
   benchmark with `scripts/compare-cache-benchmark.py` against the baseline.

**Success:**

- rolling-cache dispatches/byte ≤ 114 (expected 112) and commits/byte ≤ 3.1, with the
  continuations identical;
- every MTL benchmark row equals the B column above exactly: 0 collisions, 8 failures (not 16),
  `argument-limit` copies in per aggregating dispatch 32.75;
- every JNI row byte-identical to the baseline;
- `AggregateCopyInTest`, `AggregateCopyBackTest`, `ReadOnlyAggregateCopyTest`,
  `InstructionCacheCollisionEnforcementTest` and `ProcessDetailsFactoryRecoveryTest` pass
  unmodified, and the four `ConnectomeNetworkTest` methods pass.

**Failure:**

- any collision;
- any MTL failure count above 8;
- rolling-cache dispatches/byte above ~150, meaning some decode kernel's structural bound
  over-counts aliased roots.

On the last outcome, the next step is to make root sharing part of the signature. It is not to
adopt A, which has the same dependence on root identity without a signature that carries it.
