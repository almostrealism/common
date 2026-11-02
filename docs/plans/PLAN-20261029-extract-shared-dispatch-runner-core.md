---
status: completed
created: 2026-10-29
closed: 2026-10-31
tracker: [b3b032a7-3081-4001-a692-1dad640e4ffa]
---

# Extract the shared dispatch machinery out of the per-backend ComputeContext runners

**Tracker task:** `b3b032a7-3081-4001-a692-1dad640e4ffa` — *Extract the shared dispatch
machinery (threading, queuing, failure propagation, teardown) out of the per-backend
ComputeContext runners* — targeted at the release this repository's master is building (the
tracker release whose name matches the version in the root `pom.xml`).


## Motivation

Every `ComputeContext` that compiles code and runs it on an accelerator needs the same
coordination machinery: a thread that owns submission; ordering a dispatch after a
dependency (same queue or foreign); holding work back and bounding how much; recording a
failure and delivering it to every waiter and dependent; telemetry about why work was
flushed; and draining in-flight work on destroy. Today that machinery exists four times
over, with Metal the most complete, CUDA a parallel re-implementation, and OpenCL and JNI
only partial. Each new correctness fix lands in one backend.

The clearest recent example is the killed-buffer work (`project/task-20261028-085707`,
task `ef3f7786`, plan `PLAN-20261028-metal-killed-buffer-fails-loud.md`): it added ~300
lines to `MetalCommandRunner` — a sticky ordered failure record, `requireNoFailure`,
`awaitInOrder`, `recordForeignFailure`, and the `errorOf` test seam — none of which is
about Metal. It is "a submission stream whose work can fail, where every later wait must
report it." The same branch fixed JNI separately and left CUDA and OpenCL unfixed; the
follow-up to bound that record (`b765c052`) is already Metal-only.

`io.almostrealism.concurrent.ConfinedExecutor` is the precedent for the shape this task
wants: it started inside the Metal runner and was extracted as a generic tool, with Metal
supplying only a per-task scope (`MTLAutoreleasePool::run`). This task reproduces that
extraction for the rest of the runner, so the backends share one model and each supplies
only what is native to it.

## Current state (grounding in the code)

Paths are under `base/hardware/src/main/java/org/almostrealism/hardware/`.

- **`metal/MetalCommandRunner`** — a `ConfinedExecutor` plus an `admission` lock. Batches
  dispatches into one open command buffer (`MAX_OPEN`), bounds held buffers (`MAX_HELD`),
  carries one `MTLEvent` timeline, bridges each foreign dependency with a fresh
  host-signalled `MTLEvent`, and holds the sticky ordered `RecordedFailure`
  (`requireNoFailure`/`recordFailure`/`recordForeignFailure`/`awaitInOrder`/`errorOf`) and
  the commit-cause counters.
- **`cuda/CudaStreamRunner`** — a completion thread, an **unbounded** `held` deque, a
  `dependencyWaits` pool that **parks a thread** in `head.dependsOn.waitFor()` (contrary to
  invariant 5 of `semaphore-coordination.md`), and per-completion `LatchSemaphore.fail`.
  When the foreign dependency of a held head fails, the head never launches and its own
  semaphore reports the failure — but `release(...)` then starts every same-runner submission
  held behind it with a `null` failure (`start(next, null)`), so **a submission that depended on
  the failed head launches and reports success**. This diverges from invariant 7 of
  `semaphore-coordination.md` ("a failure reaches every waiter downstream of it"), which Metal
  now satisfies through its sticky `RecordedFailure`: a failure recorded at a timeline value is
  reported by `requireNoFailure` for every wait at or after that value. The gap is specific to
  this never-launched-head path; work that launches and then faults is still caught, because
  each submission's event is synchronized on the completion thread. The class javadoc still
  states that a failure is not propagated through same-runner dependencies "as it is not on
  Metal," which is stale — Metal propagates a failure to every later dispatch of its runner
  (semaphore-coordination.md, invariant 7 and "A failed command buffer fails loudly").

- **`cl/CLSemaphore`** — `waitFor()` delegates to `CLComputeContext.processEvent`, which waits
  with `clWaitForEvents` but never reads `CL_EVENT_COMMAND_EXECUTION_STATUS`, so a failed OpenCL
  event settles as success; if `clWaitForEvents` throws, the event release (`clReleaseEvent`) and
  `waitFor()`'s own `processed` guard are both skipped.
- **`jni/NativeExecution`** — records failures at the latch level
  (`DefaultLatchSemaphore.fail`/`awaitCountDown`), started from `dependsOn.onSettled` with no
  parked thread; its two static pools are never shut down.
- **`external/ExternalExecution.accept`** — waits on the host (`dependsOn.waitFor()`) before
  submitting, which `asynchronous-submission.md` forbids.
- **Thread sources** are five and separate: the per-context pool in `ctx/AbstractComputeContext`
  (`shutdown()` without `awaitTermination`), `NativeExecution`'s static pools, CUDA's
  `dependencyWaits` and completion thread, Metal's `ConfinedExecutor`, and
  `Semaphore.CALLBACK_EXECUTOR`.
- **Teardown** is inconsistent: `MetalComputeContext.destroy()` releases the queue but never
  destroys its `MetalCommandRunner` (the thread outlives the context, and the queue is
  released under a live runner); `CudaComputeContext.destroy()` does destroy its runner; CL
  and Native drain nothing.

The `ctx/` package already exists under `base/hardware` and houses `AbstractComputeContext`,
so it is the natural home for the shared tools.

A unifying observation drives the design: a failure recorded at a submission ordinal `K`,
where **every completion of the runner at ordinal ≥ K reports it**, is correct for *both*
Metal (the timeline value) and CUDA (the sequence number) same-runner ordering — and it is
exactly the fix the CUDA same-stream bug needs. The two failure models are one model.

## Scope

This task extracts the **backend-independent coordination** into shared tools in `base/hardware`
`ctx/` (with the GPU-independent failure primitive a candidate for `base/code` next to
`ConfinedExecutor` — see open questions), and rebuilds the backends on them so each supplies
only what is native to it:

- **Metal:** command-buffer encoding, `MTLSharedEvent`, autorelease pools, buffer status.
- **CUDA:** streams, events, context binding.
- **OpenCL:** queues and event status.
- **JNI:** the worker fan-out.

Native code (`MTL.cpp`, the CUDA/OpenCL JNI) stays as it is. No new Maven module is created.
Submission semantics do not change: callers still chain `submit(dependsOn)` and wait once;
there is no caller-side cap, no `instanceof` on a `Semaphore`, and no second submit method.

**In scope — the shared tools** (names are proposals, deciding them exactly is part of the
work):

1. **An ordered failure record** (`FailureRecord`). Sticky, keyed by submission ordinal, with
   the rule "a failure at ordinal `K` reports for every ordinal ≥ K"; records foreign failures
   at the bridged submission's ordinal; carries a defined **bound/reset policy** (this absorbs
   `b765c052`); exposes a **test seam** for injecting a failure deterministically. Every
   completion a stream issues consults it.
2. **A submission-stream core** (`SubmissionStream` / runner base). A monotonic submission
   ordinal; a shared `ordersAfter` and the "same runner, keep the later ordinal" `merge`
   helper; an off-stream-thread wait guard (today `requireOffConfinedThread` /
   `requireOffCompletionThread`); admission-versus-destroy coordination; a **bounded** hold
   queue for submissions waiting on a foreign dependency; drain-and-fail-everything on destroy;
   and the failure record above.
3. **A dependency-bridge policy.** Ordering native work after a foreign `Semaphore` through
   `onSettled` without parking a thread, as Metal's bridge and `NativeExecution` already do —
   so CUDA stops parking a `dependencyWaits` thread.
4. **A hold/flush policy with bounds and cause telemetry.** Metal's `MAX_OPEN`/`MAX_HELD` and
   commit-cause counters, generalised to "batch" and "held" limits that a backend may use (CUDA
   adopts the held bound) or ignore.
5. **Thread sourcing.** One place that decides which executor runs callbacks, dependency waits
   and coordination, with an explicit lifecycle, replacing the five sources above.

**In scope — the backends built on the tools:**

- **Metal** and **CUDA** (the closest pair, the task's minimum) rebuilt on the core; the
  duplication (`ordersAfter`, `merge`, hold, fail-on-destroy, off-thread guard) gone from both;
  the two failure models collapsed into one; `MetalComputeContext.destroy()` destroying and
  draining its runner.
- **OpenCL** adopts the failure record (`CLSemaphore.waitFor` reads the event execution status
  and records a failure; the guard and event are released even when the wait throws). Whether
  OpenCL adopts the full stream core depends on how much of its ordering is already native
  (open question).
- **JNI/Native and External** are assessed as degenerate cases with no native queue; whichever
  does not fit is documented with the reason and a follow-up task is filed.

**Out of scope (named as dependencies / open questions, not widened in):**

- Changing `Semaphore.onComplete`/`whenSettled` contracts (unchanged from the killed-buffer plan).
- Any change to the one-open-buffer invariant, Metal's batching cadence, or the native code.
- Fixing the *stall* that produces a killed buffer (`c9e4c3e7`, already a separate task).
- Fixing `ExternalExecution`'s host wait, if it is judged not to fit the core (see open questions).

## Approach

The work is naturally staged. Implementation lands on this one branch / pull request, so the
phases are the recommended implementation order and review structure, not separate PRs — but
each phase is independently reviewable, and the reviewer may prefer to split them into dependent
tracker sub-tasks (see open questions).

### Phase A — Unify the failure model

Extract `FailureRecord` from Metal's `RecordedFailure` + `failure` reference +
`requireNoFailure`/`recordFailure`/`recordForeignFailure`. It holds the lowest-ordinal recorded
failure, reports it for every ordinal at or after it, records a foreign failure at a given
ordinal, and defines the bound/reset policy that `b765c052` asks for. The error-injection seam
(Metal's `errorOf`, the JNI `ScriptedInstructionSet`, CUDA's `RecordingStream`) is expressed as
one test seam on the record or the stream.

- **Metal** replaces its private failure machinery with the shared record, behaviour-preserving
  (same sticky, ordered-by-timeline-value semantics; the commit-cause warning stays beside it).
- **CUDA** adopts it so a failure at sequence `K` fails every completion at sequence ≥ K: this
  removes the `release(...)` path that starts same-runner submissions held behind a failed foreign
  head with a `null` failure, and fixes the dependent-of-a-failed-head-reports-success defect. The
  stale class javadoc is corrected. (This also poisons independent work held behind the failure —
  see the CUDA over-propagation open question — so the coarse-vs-precise decision must be settled
  before this lands.)
- **OpenCL** adopts it on the `CLSemaphore.waitFor` → `CLComputeContext.processEvent` path: read
  `CL_EVENT_COMMAND_EXECUTION_STATUS`, record a failure for a failed event, and release the event
  (and settle `waitFor()`'s `processed` guard) on the throwing path too.
- **JNI** already records at the latch; it consults the same record where a native op is chained
  after a failed completion.

This phase alone delivers most of the task's correctness wins and absorbs `b765c052`.

### Phase B — Extract the submission-stream core

Introduce `SubmissionStream` (in `ctx/`) as the shared runner base, following the
`ConfinedExecutor` precedent: a template whose backend hooks are *launch/encode*, *native
ordering of a same-runner dependency*, *completion observation*, and the *per-task scope*. The
core owns the submission ordinal, `ordersAfter`, the `merge` helper, the off-thread wait guard,
admission-versus-destroy, the **bounded** hold queue, drain-on-destroy, and the `FailureRecord`.

- **CudaStreamRunner** composes the core directly: its stream launch is the backend hook, its
  completion thread is the completion-observation hook, and its `held` deque becomes the core's
  bounded hold queue (closing the unbounded-held gap).
- **MetalCommandRunner** composes the core and keeps its command-buffer batching —
  the open buffer, `MAX_OPEN`, the per-buffer timeline signal, the `CommittedBuffer`/`committed`
  machinery, and the per-bridge `MTLEvent` — as Metal-specific state *atop* the core rather than
  forcing buffer batching into the shared base (see open questions on how far to unify).

The dependency-bridge policy (Phase-B part) moves the foreign-dependency ordering to
`foreign.onSettled(...)` for every backend, so CUDA no longer parks a `dependencyWaits` thread.

### Phase C — Thread sourcing and teardown

Introduce one thread source with an explicit lifecycle (the task's "one place that decides which
executor runs callbacks, dependency waits and coordination"). Wire `AbstractComputeContext`, the
runners, `NativeExecution` and the `CALLBACK_EXECUTOR` consumers to it, so teardown is uniform.

- `MetalComputeContext.destroy()` destroys and drains its `MetalCommandRunner` before releasing
  the queue (today it does neither), matching `CudaComputeContext`.
- Assess `ExternalExecution` (host wait before submit) and `NativeExecution` (no native queue,
  the worker fan-out as the "launch") as degenerate streams. Document whichever does not fit and
  file a follow-up.

### Phase D — Documentation

Fold the one shared model into `docs/internals/semaphore-coordination.md` and
`docs/internals/asynchronous-submission.md`: one failure-propagation rule, one hold/bridge model,
one teardown contract. Fix the stale statements the task names — the `CudaStreamRunner` class
javadoc ("Metal doesn't propagate failures either") and the claim in `semaphore-coordination.md`
that OpenCL waits for foreign dependencies on the host (true only until OpenCL adopts the bridge
policy). Reference classes by name, never by line number (per `docs/CLAUDE.md`).

## Success criteria

A reviewer can confirm the task is done when:

- The shared tools exist in `base/hardware` `ctx/` (failure record possibly in `base/code`), with
  **GPU-independent tests** that drive the real coordination code through **scripted or injected**
  failures — not a real watchdog kill.
- **Metal and CUDA are built on them.** The duplication the task lists (`ordersAfter`, `merge`,
  the hold queue, fail-on-destroy, the off-thread wait guard) is gone from both runners, and the
  two failure models are one.
- The single-backend fixes reach every adopting backend:
  - a failure fails every later waiter and dependent — **CUDA same-stream** (the `null`-failure
    bug gone), **OpenCL** event status;
  - the runner is **destroyed and drained on context destroy** — **Metal**;
  - foreign dependencies **do not park a thread** — **CUDA**.
- **No backend loses behaviour or performance.** Metal's batching (commits per dispatch) and the
  per-buffer timeline signal do not regress, shown with the commit-cause counters
  (`getCommitCount`, `getMaxOpenCommitCount`, `getHostCompleteCommitCount`,
  `getBridgeCommitCount`, `meanBatchSize`) via `SemaphoreChainBatchingTest` and
  `CommitCauseMeasurementTest`.
- These tests pass, plus a failure test for each adopting backend:
  - device-free (any node): `NativeExecutionFailureTest`, `SemaphoreCompositionTest`,
    `SemaphoreThenTest`, `CudaStreamRunnerTest`, `CudaStreamRunnerSyncTest`, `ChainedSubmissionTest`,
    and new tests for the shared `FailureRecord`/`SubmissionStream` using injected failures;
  - macOS: `MetalCommandFailureTest`, `SemaphoreChainBatchingTest`, `MetalCompletionCallbackTest`,
    `CommitCauseMeasurementTest`;
  - a CUDA same-stream failure test (via the device-free `RecordingStream` seam) proving a dependent
    of a failed submission now throws, and an OpenCL event-status failure test (seam or device).
- `semaphore-coordination.md` and `asynchronous-submission.md` describe one shared model, and the
  stale statements (`CudaStreamRunner` javadoc; the OpenCL host-wait claim) are fixed.
- Any backend left out is documented with the reason and a follow-up task is filed.
- `mcp__ar-build-validator__start_validation` is clean (checkstyle, code_policy, test_timeouts,
  duplicate_code).

## Dependencies and open questions

- **Size — this is a large task; recommend staging it.** The four phases above are independently
  reviewable. The reviewer should decide whether to carry them as one pull request on this branch
  (the default of this workflow) or to split Phases A–D into **dependent tracker sub-tasks** (A →
  B → C → D). Phase A (the failure-model unification) is the highest-value, most self-contained
  unit and is the recommended first landing regardless.
- **Placement of the GPU-independent pieces.** `FailureRecord` (and possibly the ordinal/`merge`
  helper) have no hardware dependency and could live in `base/code` next to `ConfinedExecutor`,
  where they are testable without `base/hardware`. The stream core references `Semaphore`
  (`base/relation`), `OperationMetadata` (`base/code`) and `DistributionMetric` (`base/io`), all
  below `base/hardware`, so it may live in `base/code` too — but `ctx/` keeps it next to
  `AbstractComputeContext` and the backends. **Decision needed:** `base/hardware` `ctx/` for all,
  or split the pure concurrency primitive down to `base/code`.
- **The bound/reset policy for the sticky record (absorbs `b765c052`).** The killed-buffer plan
  left this open: is a poisoned runner permanent (and then discarded and rebuilt by its context),
  or is the record cleared once a later submission that depended on nothing failed completes
  cleanly? This is only safe if the "later submissions are ignored after a watchdog kill" claim in
  `semaphore-coordination.md` is false, which was itself unconfirmed. **Decision needed**, and it
  must be the single policy for all adopting backends. `b765c052` should be closed as absorbed, or
  re-scoped to "define the shared reset policy"; this plan does not modify that task.
- **CUDA over-propagation once it adopts the sticky record.** Today CUDA's `release(...)` lets a
  submission merely *ordered behind* a failed foreign head — one that does not depend on the head —
  launch and settle correctly. A "failure at ordinal `K` reports for every ordinal ≥ K" record
  poisons *every* submission held behind the failure, independent work included, which is the cost
  Metal already documents as accepted ("an independent later dispatch, whose work did run, is also
  reported as failed"). For a genuine dependent this is the fix the plan wants; for independent
  held-behind work it is a behaviour change from current CUDA. Because the same-runner dependency
  is dropped to `null` at submit time (`ordersAfter`), the runner cannot tell a true dependent from
  an order-held independent once a submission is held, so coarse poison is the only option unless
  dependency identity is retained. **Decision needed:** accept the coarse poison for CUDA (matching
  Metal), or retain enough dependency identity to fail only true dependents.
- **How far to unify Metal's batching into the core.** Recommendation: the core unifies the
  coordination skeleton (ordinal, `ordersAfter`/`merge`, off-thread guard, admission/destroy,
  bounded hold, failure record, thread source, drain-on-destroy); Metal's command-buffer batching
  stays Metal-specific state that *composes* the core. Forcing the open-buffer/`MAX_OPEN`/per-buffer
  timeline model into the shared base risks the batching regression the task forbids. **Confirm**
  this boundary.
- **Does `ExternalExecution` fit?** It waits on the host before submitting, which the async-submission
  contract forbids. Fixing it is a behaviour change to a backend the task only asks to *assess*.
  **Decision needed:** fix it within this task as a degenerate no-queue stream, or document it as
  not-fitting and file a follow-up.
- **Device availability for verification.** Metal needs a macOS node (the accompanying
  `task-20261029-080142-workstream.yaml` pins `platform: macos`, as the killed-buffer task did).
  CUDA real-device verification needs a CUDA host, which is not available in the standard CI/sandbox
  (Linux aarch64, no `nvcc`); CUDA correctness therefore relies on the device-free `RecordingStream`
  tests plus the owner's CUDA host. The OpenCL event-status test needs an OpenCL device or a new
  seam. **Confirm** the device-free seams are accepted as the primary proof for CUDA and OpenCL.
- **Thread-sourcing lifecycle.** Unifying `NativeExecution`'s process-wide static pools with the
  per-context pools is a lifecycle change (process-wide versus per-context ownership). **Decision
  needed** on the ownership model before Phase C.
- **Related tasks:** `ef3f7786` (closed — delivered the Metal failure machinery this generalises),
  `b765c052` (absorbed — see above), `82d74af7` (callback-driven settlement for mixed composites),
  `c406152b` (CUDA host holding behind native dependencies), `deae6dec` (CUDA cross-context events).

## Estimated complexity

**Large.** The extraction spans five backends and the `ctx/` layer, must collapse four failure
models into one without regressing Metal's batching or the per-buffer timeline signal, changes
teardown and thread-source ownership, and touches two internals documents. The individual phases
are medium, but the whole is large and is best landed as a reviewed sequence (Phase A first).
