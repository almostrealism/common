# Semaphore Settlement Without Parked Threads

**Status:** deferred from PR #601. Attempted and reverted; needs a Mac to finish.

## The problem

`Semaphore.then`, `Semaphore.all` and the default `whenSettled`/`onComplete` each park a
`CALLBACK_EXECUTOR` thread in `waitFor()` per pending operation. A burst of operations
behind a long dependency therefore creates one platform thread per operation (Copilot
review thread on `DeferredSemaphore`, PR #601).

## What was tried

Commit b2a22101 made settlement callback-driven: a shared `LatchState` (count, failure,
settlement callbacks), a `CompositeSemaphore` for `Semaphore.all` whose settlement counted the
members' own `whenSettled`, and a `DeferredSemaphore` that started its work from its
dependency's settlement. Native and OpenCL tests passed, including a regression test showing
300 pending operations occupy no threads.

It broke Metal. Every Metal failure was a GPU watchdog timeout ("Caused GPU Timeout Error"),
after which macOS ignores all further submissions from the process and every later kernel
silently reads 0.0.

## Why Metal broke

Metal completion is driven, not observed:

- `MetalSemaphore.whenSettled` is passive by design: it joins the command buffer's completion
  callbacks and never commits the open buffer, to preserve batching.
- Those callbacks only run when some host thread drains the buffer (`MetalCommandRunner`
  `complete` / `destroy`), not when the GPU finishes.
- A Metal dispatch whose dependency is not one of the runner's own `MetalSemaphore`s is
  bridged: the command buffer waits on an `MTLEvent` that the host signals from
  `dependsOn.onComplete(...)`. A committed buffer that waits longer than the watchdog for that
  signal is killed.

The parked threads were what drove these waits. Removing them left bridges signalled late or
never. Two separate stalls were found:

1. **`MultiOrderFilterPerformanceTest.highPassPerformanceGPU`.** Diagnosed on a Mac with a
   thread dump. The kernel's dependency was a composite of two of the runner's own
   dispatches, so it was bridged. The signal came from a callback thread calling
   `composite.waitFor()`, which waits for each member through `runner.complete()`. The
   runner's single confined thread was meanwhile blocked in `waitUntilCompleted` on the
   bridged buffer itself (a host wait from the test thread), so the member waits queued
   behind it and the bridge signal arrived 5012 ms later, after the watchdog. Before b2a22101,
   `Semaphore.all` started member waiters eagerly, so they usually reached the runner first:
   the race existed but was usually won. A fix that moves the GPU wait in `complete()` off the
   confined thread cleared this stall.
2. **`SoftmaxTests` (test-mac group 0).** A second GPU timeout on `f_collectionProductComputation`
   / `f_assignment` remained after that fix; not yet diagnosed.

## Requirements for a second attempt

- Do it on a Mac, with a thread dump taken at each GPU timeout, and run test-mac group 0
  (SoftmaxTests, MatrixDeltaComputationTests, TriangleDataTest) plus
  MultiOrderFilterPerformanceTest before declaring it done.
- Anything that a bridge signal depends on must be driven, not observed: either the runner
  drives bridge dependencies itself (it knows which buffers a bridged buffer is waiting on),
  or passive semaphores expose an explicit "drive to completion" that settlement-driven code
  calls without parking a thread per operation.
- The runner must never block its confined thread on a GPU wait while host work that a bridge
  depends on needs that thread (stall 1 above). This is a latent defect even with parked
  threads; fix it on its own with a regression test that bridges a dispatch on `Semaphore.all`
  of two of the runner's own dispatches.
  **Done, with parked threads still in place:** `MetalCommandRunner.complete` waits for the GPU
  on the calling thread and only commits and drains on the confined thread. The eager member
  waiters had been winning this race most of the time, but not always: a finite-difference
  gradient test on test-mac lost it, and the watchdog-killed buffer silently dropped its
  copies. The regression test is `SemaphoreChainBatchingTest#bridgedDependencyOnOwnDispatchCompletes`,
  whose foreign dependency reaches the runner only after the host wait has started, so it
  fails deterministically without the fix.
- Keep MetalSemaphore batching: no host-forced commit per `whenSettled` registration.

## Update: same-runner composites no longer exist

`Semaphore.all` now folds members through `Semaphore.merge` before building a composite, and
`MetalSemaphore.merge` merges two completions of the same runner into the later one (carrying
the highest value from an earlier command buffer as `getPriorBufferValue()`, which
`MetalCommandRunner` still waits for when the dependent lands in the later completion's open
buffer). The composite of two of the runner's own dispatches in stall 1 is therefore a single
`MetalSemaphore` today: it is chained on the GPU, not bridged, and parks no thread. Composites
that remain are mixed ones (a Metal completion together with a native or foreign one), which is
where a second attempt still has to drive bridge dependencies rather than observe them.
