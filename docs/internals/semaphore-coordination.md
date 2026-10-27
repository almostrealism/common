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
  dependency from another backend is waited for on the host before enqueuing. User events
  would remove that wait but are not implemented yet.
- **Native (JNI)** never blocks the caller and never holds a thread while its dependency is
  pending: `NativeExecution` starts its coordinator from `dependsOn.onSettled`, and the coordinator
  runs the parallel workers and counts the dispatch's latch down.
- **CUDA** uses `CudaStreamRunner` with CUDA events; see the `hardware.cuda` package
  documentation.

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
  one beyond it blocks the runner's thread, so at most `MAX_HELD` buffers wait for bridges; a
  further bridged dispatch waits on its submitting thread for the oldest to be committed.

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

When diagnosing a Metal stall, take a thread dump at the GPU timeout and validate on a Mac
with test-mac group 0 and `MultiOrderFilterPerformanceTest` before declaring a settlement
change done.

## See also

- [backend-compilation-and-dispatch.md](backend-compilation-and-dispatch.md)
- [native-runtime-lifecycle.md](native-runtime-lifecycle.md)
- [argument-binding-lifecycle.md](argument-binding-lifecycle.md)
