# CUDA Async Runner — Follow-up to PR #621

**Status:** open; to be done on a reused `feature/cuda-async-runner` branch
**Context:** PR #621 ("Make CudaStreamRunner asynchronous") is being merged to
master by hand. CI last ran against `f257f3204`. The local branch head adds
`5d7b7bd3e` (the `MatrixMathTests` validation rewrite) and the staleness-hook plan.
The last Copilot review (on `f257f3204`) ended at **"Changes recommended"** with
five open findings. This document lists everything that was left unresolved, so
that the follow-up reaches a version a reviewer would merge.

Related: the Phase 3 section of `docs/plans/CUDA_BACKEND.md`.

---

## 1. Why a follow-up is needed

Before PR #621, `CudaStreamRunner.submit` was synchronous, so nothing referenced by
a submission could outlive the call. Now `submit` returns before the GPU finishes,
and a submission can be **held** indefinitely behind a foreign dependency. Every
lifetime that used to be implicitly bounded by the call is now unbounded. Most of
the open findings are instances of that one change. Each of them was confirmed
against the code in the PR thread, and then deferred because a fix could not be
verified without a CUDA device. The follow-up has a device (the DGX Spark), so the
items in §2 should be fixed and tested there under `native,cuda`, not deferred
again.

## 2. Must fix (memory and lifetime safety)

### 2.1 CUDA modules unloaded while their kernels are in flight
Copilot thread r4200095255, confirmed in r4200245302.

`cuModuleUnload` does not wait for pending kernels. Three paths unload a module
that may still have kernels running:

- **Context teardown, which happens every time.** `CudaComputeContext.destroy()`
  destroys every `CudaOperatorMap` (and so `CudaProgram.destroy()`, which calls
  `cuModuleUnload`) *before* it calls `runner.destroy()`, which is what drains the
  stream. Ordinary shutdown can therefore unload a module under a running kernel.
- **Instruction-set replacement.** `CudaComputeContext.deliver()` destroys the
  existing map for a duplicate key.
- **Any other caller of `CudaOperatorMap.destroy()`,** such as instruction-set
  cache eviction.

Fix:

1. **Teardown:** in `CudaComputeContext.destroy()`, call `runner.destroy()` (which
   drains and joins) **before** destroying the instruction sets. This is trivial
   and should be done first.
2. **Replacement and eviction:** make each `CudaProgram` outlive its in-flight
   launches.
   - `CudaOperator.accept()` takes a use count on its program before `submit()` and
     releases it in the submission's `onComplete`.
   - `CudaProgram.destroy()` marks the program as dying and unloads only when the
     count reaches zero.
   - This is preferred over a runner-wide drain at every unload site, because it
     stalls nothing else.
   - If a drain primitive is wanted anyway, the cheapest correct one is to submit a
     no-op behind everything and wait for it:
     `submit(DRAIN, stream -> { }, null, null).waitFor()`. It queues behind any held
     submissions, so it completes only after all earlier work.
3. **Device test (`native,cuda`):** hold a kernel behind an uncompleted
   `DefaultLatchSemaphore`, replace or destroy its instruction set, release the
   latch, and assert that the kernel ran correctly and the module is unloaded
   afterwards.

### 2.2 Held kernels protected only by an expiring execution reservation
Copilot thread r4200095210, confirmed in r4200243993.

`CudaOperator.accept()` takes a plain `KernelMemoryGuard.acquire(data)` before
`submit()`. Execution reservations are subject to the deferred-release backstop
(about 30 s). A submission held behind a foreign dependency can outlive it, so an
argument destroyed meanwhile can be freed before the kernel launches.

Fix: mirror `CLOperator.accept()` (`cl/CLOperator.java`, the scheduling-lease
section):

- When `dependsOn` is foreign (not a same-runner `CudaSemaphore`), take an
  `acquireScheduled` lease and resolve the arguments through the lease's detached
  references *at launch*. Argument binding moves into the submitted command.
- Hand off to a per-dispatch `acquire()` execution reservation when the command
  runs, and release the lease then.
- On the dependency-failure path, release the lease from `onComplete`.
- `CudaComputeContext.copy()` already does the lease half of this (commit
  `ecbe22ff8`) and is the in-package model.

Device test: hold a kernel behind a latch, destroy an argument collection, wait
past the backstop, release the latch, and assert a correct result with no
use-after-free.

### 2.3 A waiter on a foreign dependency survives `destroy()`
Copilot thread r4199802149, confirmed in r4200009250.

`awaitDependency` blocks a `Semaphore.CALLBACK_EXECUTOR` thread in
`dependsOn.waitFor()`. Those threads are non-daemon. `destroy()` clears `held` but
cannot cancel the wait, so a dependency that never settles leaks a thread that can
keep the JVM alive. The device-free test
`destroyAbandonsHeldSubmissionReportingDestroyed` leaves exactly such a waiter
behind.

Fix:

- Run dependency waits on a runner-owned executor of **daemon** threads, and keep
  the `Future` of the current head's wait.
- `destroy()` cancels that future with interruption.
- `release()` already ignores a head that is no longer at the front of `held`, so
  a cancelled wait that returns late does nothing. (`LatchSemaphore.waitFor()`
  restores the interrupt and returns.)
- Extend the existing destroy test to assert that no waiter thread survives.

## 3. Should fix (robustness of the runner state machine)

### 3.1 An interrupted completion thread leaves the runner accepting work
Copilot thread r4199802032, partly addressed in `073bfe639`.

On interrupt, `observeCompletions` now settles every queued completion. But it
exits without making the runner terminal, so a later `submit()` enqueues onto a
dead consumer and its semaphore never settles. Fix: on interrupt, take the
monitor, set `destroyed`, settle everything queued (including anything that
slipped in), and then exit. Later submissions are refused through
`Submission.refuse()`.

### 3.2 A concurrent `destroy()` returns before teardown completes
This was "previously missed" by Copilot on `8285ac0f4`, at
`CudaStreamRunner.java:333`.

The second caller sees `destroyed` and returns while the first is still joining or
releasing the stream. Fix: track teardown completion separately, for example with
a `CountDownLatch` counted down at the end of the first `destroy()`, and have later
callers wait on it.

### 3.3 Two contract questions closed without a code change
The automated reviewer marked these threads resolved as design decisions for the
maintainer. Decide them explicitly:

- **Destroy is once-only even if teardown fails** (r4199802187, r4200009702). If
  `stream.synchronize()` throws, the stream is never released and a later
  `destroy()` does nothing.
  - **Recommendation:** release the stream in a `finally`, so a failed drain still
    frees it, and keep destroy once-only.
- **Immediate-launch failure runs its callback out of order** (r4199802107,
  r4200008627). A synchronous launch failure runs `onComplete` inline, possibly
  before the callbacks of earlier, still-running submissions.
  - **Recommendation:** keep the synchronous throw, and document that the ordering
    guarantee covers completions the completion thread observes. Callers' callbacks
    are independent resource releases, so the order between them does not matter.
    Alternatively, wait for the completion queue to drain before running the inline
    callback.

### 3.4 Memory migration in the deferred device copy
This was "previously missed" by Copilot at `CudaComputeContext.java:168`.

`copy()` casts the lease-resolved regions to `CudaMemory` at launch. An earlier
review judged migration between submit and launch unreachable for raw copy
operands. Even so, a defensive check is cheap: if either resolved region is no
longer `CudaMemory`, fall back to the host-mediated copy. Make it part of the §2.2
work.

## 4. CI findings from the last PR run (`f257f3204`)

| Job | Failure | Caused by this branch? | Follow-up |
|---|---|---|---|
| `test-cuda (7)` | `MatrixMathTests.matmulLarge:96->matmul:242` timed out (30 s) | **Fixed on the local head, not in the tested commit.** Line 242 is the old per-element `valueAt` loop. `5d7b7bd3e` replaces it, and locally it passes in 18.9 s under `native,cuda`. | Confirm on the first master run after the merge. |
| `test-cuda (0)` | `SimilarityOverheadTest.pairwiseSimilarityAtScale` timed out (600 s) | **No.** The same timeout occurs on master `f71477176` (synchronous runner), job 112583904318. | See §4.1. |
| `test-cuda (4)` | Failed; the job log is not retrievable (HTTP 404) and no failed step was reported | Unknown | Re-run, and check whether the runner was lost. |
| `test-media-cl (6)` | `RealTimeRendererCorrectnessTest.testBufferSizeConsistency:103`, buffer 4096 against 1024 diff of 20.8% | **No, by code path.** The branch changes only CUDA classes, their device-free tests, docs and `MatrixMathTests`. None of that loads under `AR_HARDWARE_DRIVER=native,cl`. On master `f71477176` this lane was skipped, so it cannot be shown to be pre-existing. | Hand to whoever owns CL and media lane stability. |

Master `f71477176` also fails `test-cl (4)` (`NormTests.backwardsMedium`,
0.00173 > 0.001), plus `test-cuda (1)`, `(5)` and `(7)`. These are independent of
this branch, but they are part of the state the merge lands on.

### 4.1 `SimilarityOverheadTest` does not have a passing configuration on CUDA
The emergency change on master gated `pairwiseSimilarityAtScale` to run only where
a GPU is available, on the premise that the accelerated path meets the tight 600 s
bound. That holds on Metal but not under `native,cuda`, where it times out with
both the synchronous and the asynchronous runner. The CUDA lane is informational
today, so this does not block anything, but it must be resolved before CUDA joins
the gate.

Investigate the per-call cost on CUDA with `ar-profile-analyzer` (Rule 3b): where
the roughly 500K small evaluations spend their time (kernel launch, host reads of
managed memory, completion handoff). Do not change the timeout.

## 5. Remaining Phase 3 items (from the plan)

- A `CudaSemaphore` from another CUDA context is still treated as foreign, and so
  is bridged on the host. Use `cuStreamWaitEvent` on its event when it has been
  launched.
- Events are created and destroyed per launch. Pool them only if profiles show
  that event creation matters.
- Managed memory handed to the JNI delegate (decision D4), which would also remove
  the per-element read cost that motivated the `MatrixMathTests` rewrite.

## 6. Progress on the follow-up branch

The follow-up is on `feature/cuda-async-runner`, restarted from master `031b6c2aa`.

**Implemented:**
- **§2.1:**
  - `CudaComputeContext.destroy()` now destroys the runner (draining the stream) before it
    destroys the instruction sets.
  - `CudaProgram.beginLaunch()` and `endLaunch()` count in-flight launches.
    `CudaProgram.destroy()` stops new launches at once, but defers `cuModuleUnload` until the
    last in-flight launch ends.
- **§2.2, and a finding beyond the review:**
  - `CudaOperator.accept` prepared its arguments *before* submitting, even under a foreign
    dependency. So `prepareArguments` could copy an input that the dependency was still
    writing. That is a correctness bug, not only a lifetime one.
  - `CLOperator` already defers the whole dispatch for this reason. Its deferral (a scheduling
    lease plus `Semaphore.then`) is now `HardwareOperator.dispatchAfter`, used by both
    `CLOperator` (a pure extraction with no behavior change) and `CudaOperator`.
  - `CudaStreamRunner.ordersAfter(Semaphore)` decides which dependencies the stream already
    orders after.
  - A kernel submitted with no dependency can still be held, behind a held copy. A new
    overload, `submit(..., onHeld, onComplete)`, lets `CudaOperator` add a never-expiring
    scheduling lease only in that case, so the common path costs nothing.
- **§2.3:** dependency waits run on a runner-owned pool of daemon threads, and stopping the
  runner cancels the current wait.
- **§3.1 and §3.2:**
  - `stopAccepting()` is shared by `destroy()` and by an interrupt of the completion thread,
    so an interrupted runner refuses later work.
  - `destroy()` tears down once, under a separate lock, so a concurrent caller waits for the
    teardown to finish.
- **§3.3 (destroy):** `stream.release()` now runs in a `finally` after the final synchronize.

**Found while doing this, fixed for CUDA, still open for CL:** operators are reused per thread
and per key, and `AcceleratedOperation.setupOperator` sets the global work size and offset on the
operator before every `accept`. A dispatch deferred behind a foreign dependency read them only when
it ran, so a later request on the same thread could change the size of an earlier, still-deferred
dispatch. `CudaOperator.accept` now captures both when the dispatch is requested. `CLOperator`
reads them in `dispatch()` and enqueue and has the same exposure. It predates this branch, and CL
gates master, so it is left for a separate change with CL-device verification.

**Not changed:**
- The runner's FIFO hold semantics. They match Metal, where a bridged buffer holds every
  later buffer.
- The §3.4 copy migration check.

**Second pass:**
- **§3.3 (callback order), decided and implemented:** a submission whose immediate launch
  fails now goes through the ordered completion queue like any other. The submitting thread
  waits for it to settle in its turn before it throws, so the synchronous throw still comes
  after `onComplete` has run, and callbacks run strictly in submission order. The wait happens
  only on that failure path. A submission made from a completion callback cannot wait for its
  own turn, so its failure is thrown at once and its callback still runs in order.
- **`CudaSemaphore.merge`:** each `CudaSemaphore` carries a sequence number assigned at
  submission. `merge` returns the later of two completions from the same runner, so
  `Semaphore.all` over CUDA completions folds to one completion that a dependent CUDA kernel
  orders after on the stream, instead of a foreign composite that is deferred through
  `dispatchAfter`.
- **The new device test** (`inputDestroyedWhileDependencyPendingIsStillRead`) now runs wherever
  a GPU accelerator is available, not only with CUDA, so CI shows whether Metal and OpenCL
  protect an input destroyed during a deferred dispatch.

**Earlier note (now addressed):** master added `Semaphore.merge`, which
`Semaphore.all` uses to fold completions from one provider before it builds a host-side
composite. `MetalSemaphore` implements it, but `CudaSemaphore` does not yet. So `all()` over
CUDA completions becomes a foreign composite, which a dependent CUDA kernel then defers on
through `dispatchAfter`. The result is correct but slower than it needs to be.

Implementing `merge` correctly needs completions to settle in submission order, so the later
completion can stand for both. The inline callback on an immediate-launch failure (§3.3)
breaks that order. Decide §3.3 first.

## 7. Suggested order

1. §2.1 step 1 (teardown order). It is a one-line reorder.
2. §2.2 together with §3.4 (scheduling lease in `CudaOperator`, and the defensive
   copy check), with device tests.
3. §2.1 step 2 (program use count), with a device test.
4. §2.3, §3.1 and §3.2 (runner state machine), extending the device-free tests in
   `CudaStreamRunnerSyncTest`.
5. §3.3 decisions, then §4.1 investigation.
