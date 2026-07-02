# Plan: Retire the Semaphore Technical Debt

## TL;DR

The framework has one intended coordination model — every dispatch accepts a
`dependsOn` `Semaphore` and returns a new completion `Semaphore`, and blocking
(`waitFor()`) happens only where an end user explicitly asked for a result
(`run()`, `evaluate()`) — but the implementation honors it unevenly. Today the
machinery lives between two worlds: Metal chains properly within its own runner,
while OpenCL blocks on every dispatch and returns no semaphore at all, the JNI
backend blocks twice, the async argument pipeline discards producer semaphores
and re-synchronizes through per-call background threads, cross-provider
replacement blocks inside `apply`, and `MemoryDataCopy` forces a full pipeline
drain because it is not `Submittable`.

Every "stale zeros" bug this branch has fought — including the
`DestinationEvaluable.request()` fix just landed — is a symptom of the same
disease: a place where a semaphore was dropped, read too early, or substituted
with a host wait. Closing these gaps is not one change; it is roughly six,
ordered below so each is independently testable and none regresses Metal
batching. The single most important enabler is small and comes first: make a
same-open-buffer Metal dependency **free** (it currently forces a commit), so
that chaining semaphores everywhere stops being a performance trade-off and
becomes correct-by-construction.

This document inventories every violation with file/line evidence, defines the
target invariants, maps intersections with the two related cleanups
(`DROP_OPERATION_OUTPUT_ARG.md` and destination-factory elimination), and
recommends a sequencing.

## Progress

- **Steps 1-2 (§4.1-4.2)**: done — `OperationListRunner` extracted and chaining;
  Metal same-open-buffer dependency is a no-op (`SemaphoreChainBatchingTest`).
- **Step 3 (§4.3)**: done — CL consumes (wait-list) and returns `CLSemaphore`
  (idempotent, guard-releasing); JNI dispatches from a coordinator without
  blocking the caller.
- **Step 4 (§4.4)**: done except the default flip — `MemoryDataCopy` deprecated
  and its direct construction sites migrated to `MemoryData.copyFrom` or the
  `copy(...)` helpers (4.4b); `MemoryReplacementManager` copies are chained
  `ComputeContext.copy` submittables and `AcceleratedOperation.apply` no longer
  contains any `waitFor` (4.4c); the aggregation and replacement copy-ins chain
  into `operator.accept` (4.4d). The 4.4a flag flip was landed and then
  **reverted to the safe defaults**: assignment-based layer input recording
  diverges `GradientDescentTests.linear2/linear4` (loss increases) even though
  `SyntheticDenseTrainingTest` passes — the
  `a(traverse(axis, out), traverse(axis, in))` recording route is not
  universally safe, and `MemoryDataCopy`'s opacity to optimization strategies
  is apparently load-bearing for correctness in some training graphs. The flip
  is blocked on root-causing that divergence (note
  `DefaultCellularLayer.enableMemoryDataCopy` is runtime-mutable, so the
  failing route can be reproduced in a test without a rebuild).
- **Step 5 (§4.5)**: primitives in place — `Semaphore.all(...)` merge and the
  shared `onComplete` callback executor (`SemaphoreCompositionTest`); the
  argument pipeline itself (delivering `(value, Semaphore)` downstream) remains.
- **Step 4.6 / 6 / 7**: not started.

---

## 1. Target invariants

These are the rules the codebase should satisfy when the debt is retired. They
are phrased so a reviewer can check any diff against them.

1. **Chain in, chain out.** Any method that queues device or copy work accepts
   `Semaphore dependsOn` and returns the work's completion `Semaphore`
   (possibly `null` only for genuinely-synchronous-by-contract work). No such
   method discards a semaphore it received or produced.
2. **`waitFor()` is a boundary operation.** Host blocking is permitted only:
   - in user-facing entry points: `Runnable.run()`, `Evaluable.evaluate(...)`,
     and the trailing wait of a top-level `OperationList` execution;
   - inside a backend adapter as the documented *last-resort bridge* for a
     foreign semaphore it cannot express natively (and each backend should
     shrink this case over time, see §4.6);
   - in lifecycle safety code (`Heap`/`HeapStage.destroy` draining
     `pendingKernels` before freeing memory — `Heap.java:791`).
3. **Cross-context dependencies are the backend's job, not the orchestrator's.**
   Orchestration code (`OperationList`, `AcceleratedOperation.apply`,
   `ProcessDetailsFactory`) always passes semaphores through; each
   `accept`/`copy`/`submit` implementation decides natively-ordered vs. bridged.
4. **Multiple dependencies compose.** There is one primitive for "after all of
   these" (a composite/merge `Semaphore`), used by the argument pipeline,
   aggregation copy groups, and `OperationList` joins — instead of ad-hoc
   `onComplete` threads or serial waits.
5. **Completion callbacks do not each cost a thread.** `Semaphore.onComplete`
   (today: `new Thread(...)` per call, `Semaphore.java:56-61`) is backed by a
   shared executor and, where the backend supports it, by native completion
   callbacks rather than a parked waiter.
6. **A host read of device-visible memory is a boundary.** `toDouble`/`toArray`
   at the top of the call stack are only safe because the caller waited; the
   convention stays, but the plan below never *creates* a path where internal
   machinery host-reads memory whose producing semaphore it did not wait or
   chain (this is exactly the bug class fixed in `DestinationEvaluable.request`).

## 2. Inventory — where the model is violated today

Every production `waitFor()`/blocking site in `base/`, classified. ✅ = a
legitimate boundary under invariant 2; ❌ = debt to retire; ⚠️ = legitimate today
but should be re-shaped by this plan.

### 2.1 Backend dispatch (`Execution.accept` implementations)

| Site | Behavior | Verdict |
|---|---|---|
| `MetalOperator.accept` (`MetalOperator.java:229-231`) | Same-runner `MetalSemaphore` → GPU-ordered via event wait; foreign semaphore → host `waitFor()` before encode | ⚠️ best-in-class today; foreign bridge can become non-blocking (§4.6) |
| `CLOperator.accept` (`CLOperator.java:221, 273-289, 307`) | **Blocks on `dependsOn` unconditionally** (making its own `cl_event` wait-list branch dead); `processEvent` calls `clWaitForEvents` inline (`CLComputeContext.java:414`), so every dispatch is synchronous; **returns `null`** — the `CLSemaphore` return is commented out as a TODO | ❌ the worst offender: CL neither consumes nor produces semaphores |
| `NativeExecution.accept` (`NativeExecution.java:228, 279`) | Blocks on `dependsOn` (has a TODO admitting it), then dispatches to a pool but **blocks on its own latch before returning** — the returned semaphore is always already-fired | ❌ doubly synchronous; the returned latch is theater |
| `ExternalInstructionSet.apply` (`ExternalInstructionSet.java:103`) | Blocks on `dependsOn`, runs a subprocess synchronously | ⚠️ subprocess is inherently synchronous; acceptable if it *returns* a fired semaphore honestly and stops pretending otherwise |

### 2.2 The async argument pipeline (the source of this branch's bugs)

`ProcessDetailsFactory.construct` (`ProcessDetailsFactory.java:494-555`)
evaluates producer arguments through `StreamingEvaluable.request()`, and results
arrive via `AcceleratedProcessDetails.result(index, value)`. The semaphore
handling here is the deepest debt:

- **The producer's completion semaphore is discarded at delivery.** The
  downstream consumer is `Consumer<Object>` — value only
  (`ProcessDetailsFactory.java:573-574`). Ordering is enforced by
  `getSemaphore().onComplete(...)` — i.e. **a background thread that host-waits**
  (`Semaphore.java:56-61`) — in `AcceleratedComputationEvaluable.request`
  (`:454-459`) and `DestinationEvaluable.request` (`:341-...`). This is a
  disguised `waitFor()` per async argument, and it is fragile: the
  `awaitReady()` guard had to be added in three places
  (`AcceleratedComputationEvaluable.evaluate`, `DestinationEvaluable.evaluate`,
  and — this week, after a full investigation — `DestinationEvaluable.request`)
  because reading `getSemaphore()` too early returns the readiness latch, which
  fires at *encode* time, not completion. The invariant-respecting design makes
  this whole bug class unrepresentable: deliver `(value, completionSemaphore)`
  downstream and merge argument semaphores into the operator's `dependsOn`.
- `AcceleratedProcessDetails` already distinguishes the readiness latch from the
  completion semaphore (`AcceleratedProcessDetails.java:127-151`); what it lacks
  is a slot for *per-argument* completions to feed the dispatch.

### 2.3 Orchestration

| Site | Behavior | Verdict |
|---|---|---|
| `OperationList.Runner.run` (`OperationList.java:1357-1402`) | Submittable members issued with **`submit(null)`** — the pending semaphore is deliberately not threaded, relying on same-provider issue order / Metal in-buffer hazard tracking. Correct only when consecutive members share a provider; under `AR_HARDWARE_DRIVER=*` a Metal member followed by a native member has **no ordering at all** (latent hazard, flagged during the `test-mac` investigation) | ❌ chain `pending` as `dependsOn`; requires §4.1 first so all-Metal lists keep batching |
| `OperationList.Runner.run` (`:1387, 1401`) | Waits before a non-`Submittable` member and at list end | ✅ (end) / ⚠️ (mid-list — shrinks as members become submittable, §4.4) |
| `AcceleratedOperation.apply` (`AcceleratedOperation.java:606-612`) | Cross-provider replacement: `nextSemaphore.waitFor()` **inside apply**, then host-mediated copy-back | ❌ re-express postprocess as chained `ComputeContext.copy(…, dependsOn=kernel)`; the copy machinery for this now exists on this branch |
| `AcceleratedOperation.apply` (`:581-583`) | Aggregation copy-in: `Submittable.submit(prepareOps, dependsOn)` — **return value discarded**; kernel chains on the caller's `dependsOn`, not on the copies | ⚠️ safe only by the copy-context == kernel-context invariant; chain the returned semaphore into `operator.accept` to make it safe by construction |
| `Assignment.Runner.run` (`Assignment.java:588-589`) | `submit(null)` + wait | ✅ boundary (`run()`); `submit` chains properly |
| `MemoryDataCopy` (`MemoryDataCopy.java:144`) | **Not `Submittable`** — as an optimized-list member it forces `pending.waitFor()` and then a synchronous host copy | ❌ route through `ComputeContext.copy(src, dst, dependsOn)` and implement `Submittable` |
| `AbstractComputeContext.copy` (`AbstractComputeContext.java:176`) / `MetalComputeContext.copy` (`:234`) | Fallback waits `dependsOn` then `setMem`; Metal bridges foreign deps by host wait | ✅ as designed — this *is* the backend's documented last-resort bridge (invariant 2); Metal's foreign bridge can improve later (§4.6) |
| `Heap.HeapStage.destroy` (`Heap.java:791`) | Drains `pendingKernels` | ✅ lifecycle safety |
| `OperationAdapter.waitFor(Semaphore)` (`OperationAdapter.java:141`) | Null-safe wait helper used by boundary methods | ✅ helper, fine |

### 2.4 What already works (the pattern to copy)

- `AcceleratedOperation.submit(dependsOn)` (`AcceleratedOperation.java:379-397`):
  dispatch, `awaitReady()`, return the real completion — no host wait.
- `MetalCommandRunner.submit` (`MetalCommandRunner.java:123-158`): same-runner
  dependency → commit + GPU event wait; open-buffer batching; `complete()`
  commits-if-open then waits. `AssignmentSemaphoreChainTest` proves the chain
  primitive end-to-end.
- `ComputeContext.copy(source, destination, dependsOn)` (this branch): the copy
  mechanism *and* sequencing are chosen by the context. This is the template
  the rest of the cleanup generalizes.

## 3. The one economic obstacle: chaining currently defeats Metal batching

The reason `OperationList.Runner` (and others) pass `null` instead of the
pending semaphore is not laziness — it is that `MetalCommandRunner.submit`
treats a dependency that lives in its **own open buffer** as a cross-buffer
dependency: it commits the open buffer and encodes an event wait
(`MetalCommandRunner.java:131-141`). Thread every semaphore through today and
every Metal dispatch commits its own buffer — sustained-dispatch batching gone.

But the runner *already relies on* Metal in-buffer hazard tracking to order
same-buffer read-after-write dependencies (that is the stated justification in
`OperationList.java:1363-1371`). If that reliance is sound, then a dependency on
a dispatch in the *same open buffer* requires **no commit and no event wait at
all** — it is already ordered. Making `MetalCommandRunner.submit` recognize
`dependency.getCommandBuffer() == openBuffer` as a no-op (instead of a forced
commit) makes semaphore chaining *free* for the all-Metal case, which converts
"chain everywhere" from a performance trade-off into pure correctness.

**Verification required before relying on this:** in-buffer hazard tracking
holds for `MTLBuffer`s with default `MTLHazardTrackingModeTracked` allocation.
Confirm `MetalMemoryProvider` does not allocate untracked resources or
heap-sub-allocated buffers, and add a regression test in the
`AssignmentSemaphoreChainTest` style: two same-buffer dispatches with a real
RAW dependency, chained, asserting correct results *and* (via a commit counter
on the runner) that no intermediate commit occurred.

## 4. The gaps, in recommended order

Each step is independently buildable, testable under the `AR_HARDWARE_DRIVER=*`
matrix (the config that actually catches cross-context bugs — single-backend
runs cannot), and reversible before the next.

### 4.1 Metal: same-open-buffer dependency becomes free

As §3. Small, isolated to `MetalCommandRunner.submit`, unlocks everything else.
Also the natural moment to extract `OperationList.Runner` into its own file
(`OperationList.java` is at 1439 lines against a 1500 limit; the `Runner` is the
cohesive unit to move) so the next step lands in a reviewable home.

### 4.2 `OperationList.Runner` chains `pending` into `submit`

Replace `s.submit(null)` with `s.submit(pending)` (`OperationList.java:1375,
1380`). After 4.1: all-Metal lists behave identically (no extra commits);
mixed-context lists become correct — the exact `submit(null)` risk identified
during the `test-mac` investigation. Non-submittable members keep their
pre-wait until 4.4 shrinks that set. The end-of-list wait stays (boundary).

### 4.3 Backend honesty: CL returns and consumes semaphores; JNI stops double-blocking

- **CL**: delete the unconditional `dependsOn.waitFor()` (`CLOperator.java:221`)
  in favor of the already-written `cl_event` wait-list branch (foreign
  semaphores: host wait as bridge, same as Metal); stop calling
  `clWaitForEvents` inline in `processEvent` for the dispatch path; return
  `new CLSemaphore(context, event, profile)` (the commented-out TODO at
  `CLOperator.java:293-294`). `CLSemaphore.waitFor` → `processEvent` already
  exists (`CLSemaphore.java:92`). Note `clSetKernelArg`/`argCache` make
  `accept` `synchronized` — enqueue-then-return is still a large win even if
  argument setup stays serialized.
- **JNI**: keep the (bridged) `dependsOn` handling but move it *onto the worker*
  — schedule the pool tasks to begin with the wait rather than blocking the
  submitting thread — and **return the latch without waiting on it**
  (`NativeExecution.java:261-263` TODO). Downstream code already treats a
  `DefaultLatchSemaphore` as a first-class completion. The `KernelMemoryGuard`
  acquire/release must move inside the async span.
- **External**: leave synchronous, but document it as such and return an
  already-fired semaphore for uniformity.

This step has the broadest blast radius in *tests* (anything that assumed
native/CL completion-on-return). It must be gated on the `driver=*` matrix and
on `OperationSemaphoreTests` / `OperationDispatchBatchingTests`, and it is why
4.2 (orchestrator chaining) must land first — otherwise making JNI truly async
would surface every latent `submit(null)` ordering hole at once.

### 4.4 Copies chain — by retiring `MemoryDataCopy`, not by improving it

The original draft of this step made `MemoryDataCopy` `Submittable`. The better
move (owner direction, confirmed by experiment) is to *reduce its use* until it
can be deprecated: `Assignment` is the tool for assigning one value to another,
and it already recognizes the plain-memory-copy case (the provider-to-provider
short-circuit through `ComputeContext.copy` in `Assignment.Runner`,
`Assignment.java:563-598`) — a chaining-correct copy with none of
`MemoryDataCopy`'s opacity.

The migration switches already exist, at three levels:
- `MemoryDataFeatures.enableAssignmentCopy` (`MemoryDataFeatures.java:153`)
  routes the `copy(...)` helpers to `Assignment`. Note it is an *interface
  field* — implicitly `public static final`, a compile-time switch — and its
  javadoc says "Default: true" while the value is `false`: it was evidently
  flipped back after an earlier breakage, leaving the doc stale.
- `CodeFeatures.copy` (`domain/graph`, `CodeFeatures.java:300-318`) overrides
  it shape-aware (`traverseEach` both sides, `Assignment(1, target, source)`).
- `DefaultCellularLayer.enableMemoryDataCopy` (`DefaultCellularLayer.java:61`,
  mutable, default `true`) selects copy-vs-assignment for layer input recording
  via `LayerFeatures.into` (`LayerFeatures.java:466-495`) — the coupling that
  historically broke ML tests when the flip was attempted.

**Experiment (2026-07-01, this branch):** with both switches flipped
(`enableAssignmentCopy=true`, `enableMemoryDataCopy=false`),
`SyntheticDenseTrainingTest`, `ProductDeltaIsolationTest`, and the semaphore /
collection regression set pass under `AR_HARDWARE_DRIVER=*` — 90 tests, 0
failures. The historical isolation/optimization breakage did not reproduce with
today's `Assignment`. This was a 7-class sample; the flip itself must ride its
own PR through the full matrix (`engine/ml` groups especially) before being
trusted.

Revised sub-steps:
- **4.4a** Flip both switches in their own PR (full matrix, all drivers, mac
  jobs). On green: mark `MemoryDataCopy` and the `MemoryDataCopy`-returning
  branches of the `copy(...)` helpers `@Deprecated`, and fix the stale
  `enableAssignmentCopy` javadoc.
- **4.4b** Migrate the direct construction sites that bypass the helpers —
  `PackedCollection` copy-constructor (`PackedCollection.java:229`),
  `CollectionProvider.into` (`CollectionProvider.java:163`),
  `WaveOutput.export` (`WaveOutput.java:359`), `FilterEnvelopeProcessor`
  (`:150-152`), `DefaultChannelSectionFactory` (`:315-317`) — to `Assignment`
  (or direct `ComputeContext.copy` where both sides are resolved `MemoryData`).
- **4.4c** `MemoryReplacementManager`'s Temp Prep/Post copies
  (`MemoryReplacementManager.java:289-290`) become chained submittables via
  `ComputeContext.copy(…, dependsOn)`, letting `AcceleratedOperation.apply`
  replace the `nextSemaphore.waitFor()` + synchronous postprocess (`:606-612`)
  with `copyOut = Submittable.submit(postprocess, nextSemaphore)` — the same
  shape the aggregation copy-out already has (`:618-625`).
- **4.4d** Chain the aggregation copy-in result into the kernel:
  `Semaphore copyIn = Submittable.submit(prepareOps, dependsOn);` then
  `operator.accept(input, copyIn != null ? copyIn : dependsOn)` (`:581-593`).
  Free on Metal after 4.1 (same open buffer); converts an invariant-by-comment
  into an invariant-by-construction.
- Once 4.4a-b hold, `MemoryDataCopy` has no callers outside 4.4c's internal
  machinery; when 4.4c lands it can be removed outright.

### 4.5 The argument pipeline carries semaphores

The structural fix for the `request()`/stale-zeros bug class:

- Extend the streaming delivery so a producer hands downstream
  `(value, completion Semaphore)` — e.g. `AcceleratedProcessDetails.result(index,
  value, Semaphore completion)` retaining the old signature for synchronous
  producers.
- `AcceleratedProcessDetails` accumulates argument completions; when the
  operation dispatches, it merges them (with the caller's `dependsOn`) into the
  effective `dependsOn` passed to `operator.accept`. This needs the composite
  primitive: `Semaphore.all(List<Semaphore>)` (a small
  `DefaultLatchSemaphore`-based merge, or per-backend specialization later —
  Metal can express "after several event values" natively).
- `AcceleratedComputationEvaluable.request` and `DestinationEvaluable.request`
  then deliver immediately after `awaitReady()` with the semaphore attached —
  **no `onComplete` background-thread wait at all**. The `awaitReady()` fix
  just landed becomes redundant (kept, harmless) and `Hardware.isAsync()`
  stops changing correctness, only latency.
- While here: `Semaphore.onComplete`'s thread-per-call default
  (`Semaphore.java:56-61`) moves to a shared executor. Careful with
  `MetalCommandRunner`'s single-thread executor: a callback that runs *on* a
  runner's executor must never `waitFor()` a semaphore of the same runner
  (deadlock); the shared pool must be distinct from all runner executors.

### 4.6 (Optional, later) Non-blocking foreign bridges

Today a foreign-context dependency is bridged by a host wait inside the backend
(`MetalOperator.java:231`, `MetalComputeContext.copy:234`,
`AbstractComputeContext.copy:176`). That is compliant with invariant 2, but two
backends can do better eventually:

- **Metal**: `MTLSharedEvent` supports host-side signaling. Encode a wait on a
  fresh event value, and signal it from the foreign semaphore's completion
  callback — the submitting thread never blocks. (Needs `MTL.cpp` bindings for
  `setSignaledValue`; the blit/event plumbing added on this branch is most of
  the groundwork.)
- **CL**: user events (`clCreateUserEvent`) in the wait-list, completed from the
  foreign callback.

Do not start here; it is the highest-effort/lowest-urgency piece and depends on
4.5's callback infrastructure.

## 5. Intersections with the other planned cleanups

### 5.1 `DROP_OPERATION_OUTPUT_ARG.md` (remove `output` from `apply`)

- **Order: Semaphore work first (4.1-4.5), then the output-arg plan.** The
  output-arg plan's end state is `apply(Object[] args, Semaphore dependsOn)` —
  it *assumes* the semaphore parameter is load-bearing everywhere; landing the
  chaining first means that flip happens on a dispatch path with one
  well-understood synchronization story.
- The output-arg plan deletes `DestinationEvaluable`'s kernel branch. Step 4.5
  removes the last subtle behavior in `DestinationEvaluable.request` (the
  `awaitReady` + `onComplete` dance), making that deletion mechanical instead of
  risky — the very method we just had to bugfix disappears with no semantics to
  preserve.
- The copy-out side-effect policy (`output == null` keying, §1.4b of that plan)
  is touched by 4.4 (postprocess becomes chained). Do 4.4 with the policy
  *as-is* keyed on `output`, and let the output-arg plan re-key it to `args[0]`
  afterward — two independent, reviewable changes to the same lines rather than
  one entangled change.

### 5.2 Destination-factory elimination (`MemoryDataDestinationProducer` / `PassThroughProducer` slot 0)

- Mostly orthogonal to the semaphore work, with one connection: destination
  buffers created by `createDestination` inside `ProcessDetailsFactory`
  (`:520-527`) are exactly the buffers whose producing kernels the argument
  pipeline (4.5) must chain. Nothing in 4.5 assumes *how* the destination was
  created, so the hybrid-destination-producer design in the output-arg plan
  (its §2.1) can proceed independently afterward.
- Recommended slot: after the output-arg plan's steps 1-2 prototype, per its own
  §8 recommendation. It is the least urgent of the three effort streams.

### 5.3 `OperationList` extraction

Do it as part of 4.1/4.2 (the Runner is what changes; extract, then change).
This also satisfies the standing file-length advisory on `OperationList.java`.

## 6. Validation strategy (every step)

1. `mvn clean install -DskipTests` (per CLAUDE.md; not `compile`).
2. The `driver=*` matrix is mandatory: the entire bug class is invisible under
   single-backend runs. Minimum per step:
   `CollectionMathTests`, `PackedCollectionMapTests`, `CollectionEnumerateTests`,
   `AssignmentRunnerTest`, `AssignmentSemaphoreChainTest`,
   `AggregateOutputCopyTest`, `OperationSemaphoreTests`,
   `OperationDispatchBatchingTests` under `AR_HARDWARE_DRIVER=*`, then the CI
   group runs (`AR_TEST_GROUP=k/3`) before merging.
3. New tests to write as the work proceeds:
   - same-open-buffer chained dependency executes with zero intermediate
     commits (4.1);
   - mixed-context `OperationList` (Metal producer → native consumer member)
     produces correct results (4.2) — this is the regression test for the
     `submit(null)` hazard;
   - CL chain test mirroring `AssignmentSemaphoreChainTest` (4.3);
   - `MemoryDataCopy` chained inside an optimized list (4.4);
   - an async-argument chain test: Metal-computed argument feeding a native
     kernel with `AR_HARDWARE_ASYNC` forced both ways (4.5) — the regression
     test for the `DestinationEvaluable.request` bug.
4. `AR_HARDWARE_KERNEL_LOG=enabled` now prints per-argument memory identity,
   offset, and first value for Metal *and* JNI dispatches (added during the
   `test-mac` investigation) — use it to verify ordering claims with data, not
   reasoning. Extend `CLOperator` with the same argument logging as part of 4.3.
5. Performance guard: `OperationDispatchBatchingTests` (and, if available, the
   profile analyzer's timing breakdown on a sustained-dispatch profile) before
   and after 4.1/4.2 to prove Metal batching was preserved.

## 7. Risks

- **Removing waits exposes latent races elsewhere.** Every wait deleted in
  4.3-4.5 is a wait some other code may accidentally depend on. Mitigation:
  strict step ordering (orchestrator chains *before* backends go async), the
  `driver=*` matrix, and landing each step as its own PR.
- **Metal hazard-tracking assumption (4.1).** If `MetalMemoryProvider` ever
  moves to heap sub-allocation or untracked resources, in-buffer ordering
  vanishes silently. Encode the assumption in a test now; revisit if the
  allocator changes.
- **Deadlocks via callbacks (4.5).** Completion callbacks running on a shared
  executor must never block on semaphores tied to a single-thread runner
  executor that is itself saturated. Keep the callback pool separate from all
  runner executors and forbid `waitFor` in runner-executor tasks (assertable).
- **`Heap` lifecycle vs. longer chains.** As more work becomes deferred,
  `Heap.addPendingKernel` coverage must keep up — every semaphore that becomes
  the tail of a chain guarding memory reads must be registered, or stage
  teardown can free memory under in-flight kernels. 4.4 and 4.5 must add their
  new tail semaphores the same way `apply` already does
  (`AcceleratedOperation.java:597, 634-637`).
- **CL behavioral shift (4.3).** CL has been synchronous forever; making it
  asynchronous changes timing everywhere CL is the selected backend. If risk
  budget is tight, split 4.3 into CL-consumes (wait-list) first and CL-produces
  (return semaphore) second.

## 8. Recommended sequencing (summary)

| # | Change | Size | Unblocks |
|---|---|---|---|
| 1 | Metal same-open-buffer dependency = no-op; extract `OperationList.Runner` to its own file | S | everything |
| 2 | Runner chains `pending` → `submit(dependsOn)` | S | mixed-context lists correct |
| 3 | CL consumes + returns semaphores; JNI single honest async span; External returns fired semaphore | M | uniform backend contract |
| 4 | Retire `MemoryDataCopy` use (flag flip + deprecation + direct-site migration); replacement prepare/postprocess via `ComputeContext.copy(…, dependsOn)`; chain aggregation copy-in into `accept` | M | `apply` loses its internal `waitFor`; `MemoryDataCopy` becomes removable |
| 5 | Argument pipeline carries `(value, Semaphore)`; `Semaphore.all(...)`; shared `onComplete` executor | M/L | retires the stale-zeros bug class; de-risks deleting `DestinationEvaluable` |
| 6 | `DROP_OPERATION_OUTPUT_ARG` plan (its own phased steps) | L | single dispatch path `apply(args, dependsOn)` |
| 7 | Destination-factory elimination (hybrid destination producer) | L | slot-0 convention, no special destination machinery |
| 8 | (Optional) non-blocking foreign bridges (MTLSharedEvent host signal, CL user events) | M | zero host blocking anywhere internal |

Steps 1-2 are low-risk and can ride with the current branch's follow-up work;
step 3 is where behavior genuinely changes; steps 4-5 finish the invariants;
6-7 are the pre-existing cleanup plans, now on firmer ground; 8 is discretionary.
