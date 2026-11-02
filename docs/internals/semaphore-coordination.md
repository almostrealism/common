# Semaphore Coordination Model

Every dispatch of device or copy work in the framework is ordered by
`io.almostrealism.streams.Semaphore` values instead of host waits. This page states the
rules that model is meant to satisfy, how the backends implement it, and the Metal-specific
constraints that make it subtle. For the dispatch API itself (`accept(args, dependsOn)`),
see [backend-compilation-and-dispatch.md](backend-compilation-and-dispatch.md). For the caller's
side of the model (chain every submission, wait once at a host boundary, and never decide on
behalf of a backend whether to wait), see [asynchronous-submission.md](asynchronous-submission.md).

## Invariants

These are phrased so a reviewer can check a diff against them.

1. **Chain in, chain out.** A method that queues device or copy work accepts a
   `Semaphore dependsOn` and returns the work's completion `Semaphore` (`null` only for
   work that is synchronous by contract). It never discards a semaphore it received or
   produced.
2. **`waitFor()` is a boundary operation.** Host blocking is allowed only:
   - in user-facing entry points: `Runnable.run()`, `Evaluable.evaluate(...)`, and the
     trailing wait of a top-level `OperationList` execution;
   - inside a backend adapter, as the documented last-resort bridge for a foreign
     semaphore it cannot express natively;
   - in lifecycle safety code, such as a heap draining its pending kernels before
     freeing memory.
3. **Cross-context dependencies are the backend's job.** Orchestration code
   (`OperationList`, `AcceleratedOperation.apply`, `ProcessDetailsFactory`) passes
   semaphores through unchanged. Each `accept`/`copy`/`submit` implementation decides
   whether a dependency is natively ordered or must be bridged.
4. **Multiple dependencies compose.** "After all of these" is one primitive,
   `Semaphore.all(...)`, which first folds members through `Semaphore.merge` (so two
   completions of the same backend runner collapse into one) and only then builds a
   composite.
5. **Pending work holds no thread.** Work ordered after a completion is started by
   `Semaphore.onSettled`, which `then`, `onComplete`, `all` and the native dispatch are built on.
   A completion that is told when its work settles (`LatchSemaphore`, which every native,
   composite and CUDA completion is, and `DeferredSemaphore`) registers the callback and holds no
   thread; one that only progresses when waited for (`MetalSemaphore`, `CLSemaphore`) keeps the
   default, which waits on a `CALLBACK_EXECUTOR` thread and so also drives it. A chain of N
   submissions is therefore N registrations, not N parked threads (`ChainedSubmissionTest`).
6. **A host read of device-visible memory is a boundary.** `toDouble`/`toArray` at the top
   of the call stack is safe only because the caller waited. Internal machinery must never
   host-read memory whose producing semaphore it did not wait on or chain. This is the bug
   class behind "stale zeros" results: a semaphore dropped, read too early, or replaced by
   an unrelated wait.
7. **A failure reaches every waiter downstream of it.** Work that did not run must never
   settle as if it had. A failed completion rethrows from `waitFor()`, and so does every
   completion ordered after it:
   - `Semaphore.all` and its composites record a member failure (`LatchSemaphore.fail`) and
     rethrow it; `then` (`DeferredSemaphore`) does not start its work after a failed
     dependency and rethrows the failure (`SemaphoreCompositionTest`, `SemaphoreThenTest`).
   - `onComplete(Runnable)` is success-only: its callback does not run after a failure, and the
     failure goes to the semaphore's waiters, not to the callback. `whenSettled`/`onSettled`
     run on both paths and leave the failure to the waiters. Ordering that must carry a failure
     forward therefore uses `then`, `all` or a backend dispatch, never `onComplete`.
   - A native dispatch records a failure of its dependency, or of a worker, on its latch
     before the latch reaches zero, so its completion rethrows it and a dispatch chained after
     it does not run (`NativeExecutionFailureTest`).
   - Within one backend runner, a failure at submission ordinal `K` fails every wait at or after
     `K`. Metal and CUDA share this rule through one `FailureRecord` (see "The shared runner
     core" below): a Metal command buffer that completes in error, a CUDA submission whose
     foreign dependency, launch or completion event failed, and a failed foreign dependency of a
     Metal bridge are all recorded the same way.
   - An OpenCL command that did not execute (a negative `CL_EVENT_COMMAND_EXECUTION_STATUS`)
     fails every wait on its `CLSemaphore`, and a dependent ordered after it through an event wait
     list is failed by OpenCL itself, or, once the failed event has been released, refused when it
     is enqueued (`CLSemaphoreFailureTest`).

`ComputeContext.copy(source, destination, dependsOn)` is the template for the model: the
context chooses both the copy mechanism and its sequencing, and returns the completion.

## How the backends implement it

- **Metal** chains within its own `MetalCommandRunner`. A dependency on a dispatch in the
  runner's *open* command buffer costs nothing: Metal's in-buffer hazard tracking already
  orders same-buffer read-after-write, so no commit or event wait is encoded. This is what
  makes "chain everywhere" free for all-Metal graphs instead of forcing a commit per
  dispatch (covered by `SemaphoreChainBatchingTest`). `MetalSemaphore.merge` merges two
  completions of the same runner into the later one, carrying the earlier buffer's value
  forward so the runner still waits for it.
- **OpenCL** consumes dependencies through event wait lists and returns a `CLSemaphore`. A
  dependency from another backend cannot be placed in a wait list, so the whole dispatch is
  deferred until it settles through `Semaphore.then`, without blocking the caller; argument
  preparation is deferred with it. User events would let the command queue express that
  dependency directly but are not implemented yet.
- **Native (JNI)** never blocks the caller and never holds a thread while its dependency is
  pending: `NativeExecution` starts its coordinator from `dependsOn.onSettled`, and the coordinator
  runs the parallel workers and counts the dispatch's latch down.
- **CUDA** uses `CudaStreamRunner` with CUDA events. A dependency on an earlier submission of
  the same runner is satisfied by the stream's order; a foreign dependency holds the submission,
  and every submission after it, until a callback registered with the dependency's
  `onSettled` releases them, so no runner thread is parked for it. See the `hardware.cuda`
  package documentation.

## The shared runner core

Metal and CUDA runners are built on one backend-independent core in the `ctx` package of
`base/hardware`: the core owns the coordination, and each runner *is* a `SubmissionStream` (it
extends the abstract class) that supplies only what is native to it (Metal encodes into command
buffers and signals an `MTLSharedEvent`; CUDA launches on a `CUStream` and synchronizes
`CUEvent`s) and identifies its own thread by implementing `isStreamThread()`. The coordination
steps a runner drives (taking an ordinal, admission, closing, holding and releasing) are
protected members of the stream, so they are reachable only from the runner itself.

- **`SubmissionStream`** owns the monotonic submission ordinal (a Metal dispatch's timeline value,
  a CUDA submission's sequence), same-stream ordering (`ordersAfter`) and merging into the later
  completion (`later`), the guard that rejects a wait on the stream's own thread, admission versus
  destruction (a submission is admitted under the same lock the runner closes the stream under,
  so none is admitted after the runner found itself idle), and the hold bound (how many units of
  work may wait for a foreign dependency before a further submission that would add one waits on
  its submitting thread: `MAX_HELD` command buffers on Metal, `MAX_HELD` submissions on CUDA).
  A runner never waits for room on its own thread: Metal refuses submission from its executor
  thread outright, and CUDA holds a submission made from a completion callback beyond the bound,
  since the dependency that frees room may need the completion thread to observe earlier work.
  It also owns the idle-commit policy (`closeEarlyIfIdle`, `minIdleCommit`,
  `getIdleCommitCount`): a runner that holds submissions back in a batch the device does not see
  until it is committed closes that batch early, once it holds the threshold, when the device has
  run out of work. The runner supplies only whether its device is idle (`canCommitToIdleDevice`)
  and how a batch is closed (`closeOpenBatch`), as overrides of those protected hooks;
  Metal uses it for its open command buffer, while CUDA launches every submission as it is
  admitted and so never holds work back from an idle device.
  Completions of a stream implement `StreamCompletion`, which records a failure at its own
  ordinal (`recordFailure`) and reports the recorded failure that covers it
  (`getRecordedFailure`).
- **`FailureRecord`** is the one failure model: a failure recorded at ordinal `K` is reported by
  every wait at or after `K`, the lowest recorded ordinal wins, and each waiter receives its own
  exception carrying the recorded cause. Recording a failure directly is also the test seam
  (`FailureRecordTest`, `SubmissionStreamTest`, `CudaStreamRunnerSyncTest`).
- **Coarse propagation.** A same-stream dependency is dropped at submission, so a runner cannot
  tell a true dependent of a failed submission from independent work that merely follows it, and
  reports both as failed. A false failure for work that ran is recoverable; success for work that
  read inputs that were never produced is not.
- **Reset policy.** The record is never cleared. Clearing it after a later clean completion would
  only be safe if a device that killed one submission were known to run every later one
  correctly, which is not established for any backend. A runner that has recorded a failure is
  poisoned for its lifetime; recovering means destroying its context and creating another.
- **Teardown contract.** Destroying a context destroys its runner first, which drains what is in
  flight and runs every completion callback before the context's queue or stream is released,
  and the runner then refuses further work (`MetalComputeContextTeardownTest`,
  `CudaStreamRunnerSyncTest`).

OpenCL does not need the ordinal record, because the command queue already propagates a failure
to every command whose wait list names a failed event. The native (JNI) dispatch has no queue at
all: each `NativeExecution` records its own and its dependency's failure on its latch, which is
the degenerate case of the same rule. `ExternalExecution`, which runs a separate process and waits
for its dependency on the submitting thread before starting it, is not built on the core.

## Metal: completion is driven, not observed

Metal's completion model makes bridged dependencies fragile, and any change to settlement
must respect it:

- `MetalSemaphore.whenSettled` is passive by design. It joins the command buffer's
  completion callbacks and never commits the open buffer, so batching is preserved.
- Those callbacks run only when a host thread drains the buffer
  (`MetalCommandRunner.complete`/`destroy`), not when the GPU finishes.
- A Metal dispatch whose dependency is *not* one of the runner's own `MetalSemaphore`s is
  **bridged**: its command buffer waits on an `MTLEvent` that the host signals from
  `dependsOn.onSettled(...)`. If a committed buffer waits longer than the GPU watchdog for
  that signal, macOS kills it, its dispatches never run, and dependents read stale or `0.0`
  values. `MetalCommandRunner` therefore separates closing a buffer from committing it: a closed
  buffer is committed only once every bridge in it has been signaled, in the order buffers were
  closed, so the GPU never waits for the host however far ahead of the foreign work a caller
  submits. A command queue allows a bounded number of uncompleted command buffers, and opening
  one beyond it blocks the runner's thread. Every buffer closed behind one that waits for a
  bridge waits as well, whether a bridge, the `MAX_OPEN` bound or a host wait closed it, so
  once `MAX_HELD` buffers wait, any dispatch that would open a new buffer (bridged or not)
  waits on its submitting thread for the oldest to be committed. A dispatch that joins the
  still-open buffer never waits.

Two rules follow:

- **Anything a bridge signal depends on must be actively driven to completion.** If
  settlement only observes passive semaphores, the bridge is signalled late or never. An
  attempt to make composite settlement fully callback-driven, with no parked threads,
  passed on the native and OpenCL backends but caused watchdog timeouts on Metal for
  exactly this reason.
- **The runner must never block its confined thread on a GPU wait while host work that a
  bridge depends on needs that thread.** `MetalCommandRunner.complete` therefore waits for
  the GPU on the *calling* thread and uses the confined thread only to commit and drain
  (regression test: `SemaphoreChainBatchingTest#bridgedDependencyOnOwnDispatchCompletes`).

### A failed command buffer fails loudly

A command buffer that Metal reports as failed (a GPU fault, or the watchdog killing a stalled
buffer) ran none of its dispatches and never signaled its timeline value. Detecting this is the
job of `MetalCommandRunner`, through its single error-status read (`errorOf`):

- The failure is **recorded in the runner's `FailureRecord`**, keyed by the timeline value of the
  failed buffer's first dispatch (the lowest such value if several fail), not only reported to the
  thread that happened to drain the buffer. The existing `commandBufferError=` warning is still
  logged beside it.
- `MetalCommandRunner.complete` — and so `MetalSemaphore.waitFor()` — throws a
  `HardwareException` carrying the Metal error text for **every** wait for a dispatch at or after
  that value: the draining waiter, a concurrent waiter, one that arrives after the buffer was
  drained, an earlier dispatch of the same buffer, and every later dispatch of the runner.
- The record is **sticky** and never cleared (see the reset policy above). A later buffer that encoded a GPU wait for a failed value can still
  complete without an error of its own, because the shared timeline event is released by any
  higher value that an independent buffer signals; its own status cannot be trusted, so only
  the record reports it. The cost is that an independent later dispatch, whose work did run, is
  also reported as failed: a runner that has lost work stays failed.
- A waiter waits for every committed buffer up to its own **in queue order** and stops at the
  first that failed, so a dependent of a killed buffer throws without waiting for its own buffer
  to be killed in turn.
- Draining is unchanged: a failed buffer's callbacks run and its memory and bridge events are
  released exactly as for one that executed. `destroy()` records a failure but does not throw.
- A **bridged** dispatch is released when its foreign dependency settles, whether that work
  completed or failed, so the GPU never waits for a watchdog kill. If the foreign work failed,
  its failure is recorded for the bridged dispatch's value before the bridge is signaled, so a
  wait for the bridged dispatch throws the foreign cause.

`MetalCommandFailureTest` covers these cases, including the native-to-Metal and Metal-to-native
directions.

When diagnosing a Metal stall, take a thread dump at the GPU timeout and validate on a Mac
with test-mac group 0 and `MultiOrderFilterPerformanceTest` before declaring a settlement
change done.

## See also

- [backend-compilation-and-dispatch.md](backend-compilation-and-dispatch.md)
- [native-runtime-lifecycle.md](native-runtime-lifecycle.md)
- [argument-binding-lifecycle.md](argument-binding-lifecycle.md)
