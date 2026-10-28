---
status: proposed
created: 2026-10-28
closed:
tracker: [ef3f7786-a97d-4eaa-bcc6-8fdd942df903]
---

# Metal: a GPU-killed command buffer must fail its waiters and dependents

**Tracker task:** `ef3f7786-a97d-4eaa-bcc6-8fdd942df903` — *Metal: a command buffer
the GPU kills (watchdog timeout) must fail its waiters and dependents, not settle
them as if the work ran* — assigned to the release currently under development.

## Motivation

When Metal reports an error for a command buffer (for example
`kIOGPUCommandBufferCallbackErrorTimeout` after the GPU watchdog kills a stalled
buffer), the buffer's dispatches never ran, its destinations were never written,
and its timeline-event signal never fired. Today
`MetalCommandRunner.drainOldestCommitted` only emits a warning
(`commandBufferError="..." requester=... commitCount=... errorCompletions=N`) and
then runs the buffer's callbacks as normal. Every waiter and every dependent
therefore continues as though the work had succeeded, reading stale or
uninitialised memory — a silent-wrong-data result that violates the fail-loud
rule (`.claude/hooks/rules/fail-loud.md`): a failure has to reach whoever depends
on the result.

This is especially dangerous for the autoregressive / `SimulationRunner` pattern,
where the host blocks exactly once at the end of a long asynchronous chain: a
single killed buffer anywhere in the chain must make that final wait throw rather
than return a plausible-looking but wrong result.

## Current state (what already works, and where the failure is actually dropped)

The semaphore composition layer was hardened in the October `qa/performance`
work that is now on master, so the task's "dropped at several points" list is
partly already addressed. Grounding each point against the code as it stands:

- **`Semaphore.all` / `LatchSemaphore` (composite, including `DeferredSemaphore`
  members):** already correct. `Semaphore.all` waits for every member on a
  `CALLBACK_EXECUTOR` thread, records a member failure with
  `LatchSemaphore.fail(Throwable)` (only the first is kept), and
  `LatchSemaphore.waitFor()` rethrows it once the latch releases; a composite wait
  also settles its members directly and rethrows a member failure.
  `SemaphoreCompositionTest` (in `engine/utils`) proves this with a unit-level
  failing `Semaphore` (`RecordingCompletion`).
- **`DeferredSemaphore` (`Semaphore.then`):** already correct. A failed dependency
  is captured with `completeExceptionally`, the work does not run after it, and
  `waitFor()` rethrows the dependency or work failure (covered by
  `SemaphoreThenTest`).
- **`Semaphore.onComplete(Runnable)` (and the static
  `Semaphore.onComplete(Semaphore, Runnable)`):** **a gap the task names and the
  composition layer does not close.** The default runs `waitFor(); r.run();` on a
  `CALLBACK_EXECUTOR` thread. If `waitFor()` throws, `r` never runs and the
  exception ends the callback thread's task with nobody to observe it. The
  `then` javadoc says this explicitly ("Unlike `onComplete`, a failure … is not
  lost"), and `SemaphoreThenTest` asserts the callback is skipped on failure.
  This matters here because of the bridge, next.
- **Host-signaled bridges (`MetalCommandRunner.encode`):** **a real gap.** A Metal
  dispatch with a foreign dependency encodes a GPU wait on a fresh per-bridge
  `MTLEvent` and signals it from `foreign.onComplete(() -> bridge.signal(1))`. If
  the foreign completion *fails* — which is exactly what a `NativeExecution` latch
  will do once gap 2 below is fixed — the signal never runs, the committed Metal
  buffer waits on the GPU until the watchdog kills it (~5 s), and the eventual
  failure reported to Metal's waiters is a GPU timeout, not the foreign cause.
  The native-to-Metal direction therefore does not fail loudly or promptly even
  after the two fixes the original plan proposed.
- **`MetalSemaphore.waitFor()` / `MetalCommandRunner.complete()`:** **the main
  gap.** `MetalSemaphore.waitFor()` calls `runner.complete(commandBuffer,
  requester)`, which commits (if open), waits for that buffer on the calling
  thread, and then drains via `drainThrough` → `drainOldestCommitted`.
  `drainOldestCommitted` detects `buffer.isError()` but only warns; it never
  throws and never records the failure durably. So:
  - the draining waiter returns normally even on an error;
  - a *second, concurrent* waiter on the same buffer finds it already removed from
    `committed` (`drainThrough`'s `committed.indexOf(...)` is `-1`, so it drains
    nothing) and returns normally;
  - a *later* waiter finds the buffer already drained (`commitForWait` returns
    `null`, `complete` returns early) and returns normally.
- **`NativeExecution`:** **a real gap.** `NativeExecution.coordinate` runs
  `dependsOn.waitFor()` and the worker dispatches inside `try/catch` blocks that
  `warn(...)` and then count the `DefaultLatchSemaphore` down **without calling
  `latch.fail(e)`**. A native op chained after a failed completion therefore skips
  its work (the dependency threw before workers were submitted) yet its own latch
  reaches zero with no recorded failure, so its `waitFor()` reports success. A
  worker whose `inst.apply` throws is likewise only warned about.
- **`Semaphore.whenSettled(Runnable)`:** swallows a `RuntimeException` from
  `waitFor()` **by design** — it is the settlement-observation / resource-release
  callback (it must run on both the success and failure paths, and its javadoc
  states the failure is reported to the semaphore's own waiters).
  `MetalSemaphore.whenSettled` overrides it passively: it joins the buffer's
  drain callbacks, which run whether or not the buffer errored. This is a "drop
  point" only when a `whenSettled` callback is the *sole* consumer of a
  completion; see open questions.

## Scope

In scope:

1. **Make the Metal source fail loudly and durably.** When a committed buffer
   completes with `isError()`, record the failure on the runner (not only on the
   stack of the one draining thread) and make `MetalCommandRunner.complete()`
   throw for every caller whose waited dispatch is in or after the errored buffer
   in the serial queue — covering concurrent waiters, later waiters, and
   same-runner GPU dependents. The thrown exception carries the Metal error text
   and the requester.
2. **Record native failures on the completion latch.** In `NativeExecution`, a
   failed `dependsOn` wait and a failed worker dispatch call `latch.fail(e)` so the
   returned `DefaultLatchSemaphore.waitFor()` rethrows, instead of only warning.
3. **Make a failed foreign dependency reach a bridged Metal dispatch.** The bridge
   in `MetalCommandRunner.encode` must not depend on `onComplete`'s
   success-only callback: on a foreign failure it must still release the GPU wait
   and the failure must be reported by the bridged dispatch's waiters (see
   Approach §3).
4. **Confirm (and lock in with tests) that the composition layer already
   propagates** a source failure through `then`, `Semaphore.all` /
   composites and `DeferredSemaphore`.
5. **Documentation.** State how a failed completion propagates in
   `docs/internals/semaphore-coordination.md`, and in a new
   `docs/internals/asynchronous-submission.md` or folded into
   `semaphore-coordination.md` — the home is the subject of an open question
   below, so this item delivers one of those two, not necessarily a new file.

Out of scope (named as dependencies / open questions, not widened into here):

- Fixing the *stall* that produces a killed buffer in the first place — that is the
  related task `c9e4c3e7-65b6-4128-8656-fe93e19fe626` (still open, in the
  backlog). This task only makes a killed buffer, however produced, fail loudly.
- Changing `whenSettled`'s swallow semantics (resource release). See open
  questions.
- Changing the generic `Semaphore.onComplete(Runnable)` contract for every caller.
  Only the bridge's use of it is in scope; whether `onComplete` itself should
  surface failures is an open question.
- Any change to the one-open-buffer invariant or batching.

## Approach

Production changes are in `base/hardware` (`MetalCommandRunner`,
`MetalSemaphore`, `NativeExecution`). No change is expected in `base/relation`
(`Semaphore`, `LatchSemaphore`, `DeferredSemaphore`) or `base/code`
(`DefaultLatchSemaphore`, which already inherits `fail(Throwable)`), unless the
`onComplete` open question is answered "change the contract". The docs are under
`docs/internals/`.

### 1. `MetalCommandRunner` — durable error state + throwing `complete()`

`base/hardware/src/main/java/org/almostrealism/hardware/metal/MetalCommandRunner.java`

- **Give each committed buffer its timeline range.** `CommittedBuffer` today holds
  only the `MTLCommandBuffer` and its callbacks. Record, at
  `commitOpenOnExecutor` time, the first and last timeline values of the
  dispatches it holds (the last is the `signaled` value it is made to signal;
  the first is one more than the previous buffer's last value). Keying on the
  buffer's **last** value alone would be wrong: a waiter for an *earlier*
  dispatch in the errored buffer has a smaller value and would not be covered.
- **Record the error durably.** The command queue is serial, and an errored
  buffer never signals its value, so every later buffer that encoded a wait for
  it can never complete normally either. Record the first error as runner state:
  the errored buffer's *first* timeline value, plus a `HardwareException` built
  from `buffer.getError()` and the requester's display name. `errorCompletions`
  already counts these; keep the existing `warn(...)` as the loud log line next
  to the recorded failure (the task permits keeping the warning "as a log line
  next to the propagated failure").
- **Drain first, then surface.** In `drainOldestCommitted`, when
  `buffer.isError()`, record the failure, then run `c.drain()` exactly as today
  (memory release, bridge-event release, `whenSettled` callbacks); the throw must
  not skip it. Note that `destroyExecutor` also drains through
  `drainOldestCommitted`; whether `destroy()` should throw a recorded error is an
  open question below — the simplest rule is that `drainOldestCommitted` only
  *records*, and only `complete()` *throws*.
- **Pass the waited value into `complete`.** `complete(MTLCommandBuffer,
  OperationMetadata)` receives no timeline value today, so it cannot decide
  whether an already-drained buffer was at or after the error. Either add a
  `long value` parameter (and change `MetalSemaphore.waitFor()` to pass
  `getValue()`), or have `MetalSemaphore.waitFor()` pass itself. The public
  `complete(MTLCommandBuffer)` overload has no callers in the repository and can
  follow suit. **`MetalSemaphore.waitFor()` therefore does change**, contrary to
  the earlier draft of this plan.
- **Throw for every waiter at or after the error.** `complete` throws the
  recorded `HardwareException` whenever the waited value is ≥ the recorded first
  errored value — before waiting (the buffer is already known bad), after the
  drain, and on today's early-`return` path where `commitForWait` returns `null`.
- **Detect an earlier error without a second watchdog kill.** As written today,
  `complete()` calls `waitUntilCompleted()` on the *target* buffer only and
  drains older buffers afterwards. If an older buffer was killed, a target
  buffer that encoded a GPU wait for one of its values stalls until the watchdog
  kills *it* too (~5 s more), and only then is the older error drained and seen.
  To make dependents fail promptly, `complete()` should wait, on the calling
  thread, for each committed buffer from the oldest up to the target in queue
  order and check `isError()` after each, stopping at the first error (then
  draining through it on the executor and throwing). The executor-confined
  `committed` list means the ordered snapshot must be taken in the
  `commitForWait` task, with each buffer kept retained by a `Waiter` while the
  calling thread waits on it. Without this step the success criterion "every
  later completion throws" still holds, but only after one extra watchdog
  timeout per stalled dependent buffer — state which behaviour is accepted.
- Interrupted waiters keep today's behaviour (withdraw and return without
  throwing; the error stays recorded for the next waiter).

Keying the recorded error by the monotonic timeline value keeps the rule honest
for merged completions (`MetalSemaphore.merge` keeps the later dispatch's value,
which is ≥ any value it covers) and respects the `base/CLAUDE.md` rule against
keying collections on native handles — no `MTLCommandBuffer` is used as a map key.

### 2. `NativeExecution` — record failures on the latch

`base/hardware/src/main/java/org/almostrealism/hardware/jni/NativeExecution.java`

- In `coordinate(...)`, the worker task's `catch (Exception e)` (currently
  `warn(...)` + `finally countDown`) also calls `latch.fail(e)` before counting
  down.
- The coordinator-level `catch (Exception e)` around the dependency wait and worker
  submission (currently `warn(getName() + " dispatch failed", e)`) also calls
  `latch.fail(e)`. This is the Metal-to-native path: when `dependsOn` is a Metal
  completion that now throws, the throw is recorded on this op's latch, so its
  `waitFor()` rethrows and anything chained after it sees the failure.
- **Pitfall:** `coordinate` itself calls `latch.waitFor()` twice — inside
  `recordDuration` and in its `finally` block, before `guard.release()`. Once a
  failure is recorded, `LatchSemaphore.waitFor()` rethrows it, so the `finally`
  would throw and **skip `guard.release()`**, leaking the kernel memory
  reservation, and the throw would land unobserved on a `dispatchExecutor`
  thread. The coordinator's own waits must therefore not rethrow (wait on the
  count without the rethrow, or release the guard in a nested `finally`), and the
  coordinator must not count the latch down twice for workers that already did.
- The synchronous path (`enableExecutor == false`) already propagates by
  rethrowing from `accept` (both a `dependsOn.waitFor()` failure and an
  `inst.apply` failure); confirm no regression.

### 3. Bridge — signal and fail on a foreign failure

`MetalCommandRunner.encode`

- Replace `foreign.onComplete(() -> bridge.signal(1))` with a settlement path that
  runs on success *and* failure (the shape of `Semaphore.whenSettled`, but one
  that also captures the throwable). On failure, record the foreign failure as
  the runner's error for the bridged dispatch's value (same mechanism as §1) and
  still signal the bridge, so the GPU does not wait for a watchdog kill. The
  bridged dispatch will then execute on inputs that were never produced; that is
  acceptable only because every waiter at or after its value throws.
- Alternative (smaller, slower): leave the bridge as is and accept that a failed
  foreign dependency surfaces as a watchdog-timeout error ~5 s later, with the
  foreign cause lost. Recorded as an open question; the recommendation is the
  settlement-based bridge.

### 4. Composition layer — confirm + test only

No production change expected in `Semaphore` / `LatchSemaphore` /
`DeferredSemaphore`. The work here is tests (below) that drive a real Metal and a
real native chain to the point of a recorded failure and assert the downstream
`waitFor()` throws.

### 5. Documentation

- `docs/internals/semaphore-coordination.md` — add the failure-propagation rule to
  the "Invariants" section and to "Metal: completion is driven, not observed": an
  errored command buffer records a failure on its runner; `complete()`/`waitFor()`
  throw for that buffer and every later one; `Semaphore.all`/`then`/composites
  rethrow a member/dependency failure; `onComplete` does not (and the bridge no
  longer relies on it); native dispatch records a dependency or worker failure on
  its latch. Reference classes by name (no line numbers, per `docs/CLAUDE.md`).
- `docs/internals/asynchronous-submission.md` — this file does **not exist yet**
  (see open questions). If the open question resolves to create it, it becomes the
  home for "how asynchronous submission and its completion/failure model work" (the
  material currently living only in the `MetalCommandRunner` class javadoc and in
  `semaphore-coordination.md`), stating the same failure-propagation contract,
  cross-linked with `semaphore-coordination.md` and
  `backend-compilation-and-dispatch.md` (whose "Execution" section describes
  `accept(Object[], Semaphore dependsOn)` chaining). If it resolves to fold the
  material in instead, the contract goes into `semaphore-coordination.md` and no new
  file is created.

## Success criteria

A reviewer can confirm the task is done when:

- `MetalCommandRunner.drainOldestCommitted` no longer settles an errored buffer
  silently: it records the failure, and `complete()`/`MetalSemaphore.waitFor()`
  throw a `HardwareException` carrying the Metal error text and requester for
  every dispatch in the errored buffer (not only its last) and every later
  completion of the runner — including a waiter that arrives after the buffer
  was drained.
- A same-runner dependent of a killed buffer throws within one watchdog interval
  of the original kill, not after a second kill of its own buffer (or, if the
  reviewer accepts the slower behaviour, the plan's §1 note is updated to say so).
- `NativeExecution` records a failed dependency or worker on its latch; a native op
  chained after a failed completion rethrows from `waitFor()` instead of reporting
  success, and its `KernelMemoryGuard` reservation is still released.
- A Metal dispatch bridged on a failing foreign completion throws from
  `waitFor()` with the foreign cause, without a GPU watchdog kill (or the
  alternative in §3 is explicitly chosen).
- Tests prove the end-to-end behaviour (all extend `TestSuiteBase`, each `@Test`
  carries a `timeout`):
  - **Metal-to-Metal chain** (macOS): a real `MetalCommandRunner` chain in which
    the head buffer is made to complete in error; a downstream dependent's
    `waitFor()` (and a single trailing host wait in the `SimulationRunner` shape)
    throws with the error text, and a waiter for an earlier dispatch of the same
    errored buffer also throws. Lives alongside `SemaphoreChainBatchingTest` /
    `MetalCompletionCallbackTest` in `engine/utils`
    (`org.almostrealism.hardware.test`).
  - **Metal-to-native chain** (macOS): a `NativeExecution` dispatch chained
    (`dependsOn`) on a Metal completion that completes in error throws from its
    latch's `waitFor()` and does not run its workers.
  - **Native-to-Metal chain** (macOS): a Metal dispatch bridged on a failing
    native completion throws promptly with the native cause.
  - **Native failure propagation** (any backend, no macOS): a `NativeExecution`
    dispatch chained on a unit-level failing `Semaphore`, and one whose worker
    throws, both surface the failure from `waitFor()` — the parts that need no
    Metal device.
  - **Composition regression** (any backend): `SemaphoreCompositionTest` already
    covers a failing member through `Semaphore.all`, and `SemaphoreThenTest`
    covers a failing dependency or work through `then` and asserts that
    `onComplete` skips its callback on failure while `whenSettled` runs it; add
    only what is missing (e.g. a `DeferredSemaphore` whose
    dependency is a composite with a failing member), going through the real
    composition code per the task's "a unit-level failing Semaphore is fine …
    must still go through the real composition code."
- The failure-propagation contract is documented in
  `docs/internals/semaphore-coordination.md`, and — per the resolution of the
  `asynchronous-submission.md` open question below — either in a new
  `docs/internals/asynchronous-submission.md` or folded into
  `semaphore-coordination.md`.
- `mcp__ar-build-validator__start_validation` is clean (checkstyle, code_policy,
  test_timeouts, duplicate_code), and the targeted tests above pass (the Metal
  tests on a macOS node).

## Dependencies and open questions

- **Forcing a Metal error deterministically.** A real watchdog timeout (~5 s, the
  `c9e4c3e7` bridged-stall shape) is slow and flaky to rely on in a test.
  `MTLCommandBuffer.isError()`/`getError()` are thin JNI wrappers with no override
  point today, and `MetalCommandRunner` creates its buffers itself via
  `queue.commandBuffer()`. The plan therefore assumes a small, deterministic
  **test seam** to mark a committed buffer's completion as errored (for example a
  protected error-status hook on `MetalCommandRunner`, exercised by a test
  subclass), so the test drives the *real* `complete`/`drainThrough`/`waitFor`
  composition code without depending on the GPU watchdog. A cheaper real-error
  option is a bridged dispatch whose foreign `Semaphore` never completes — the
  exact cycle `c9e4c3e7` describes — which produces a genuine kill in ~5 s.
  **Open question for the reviewer:** is a test seam acceptable, or should the
  Metal tests reproduce a genuine watchdog kill (slower, ~5 s per test, and
  dependent on the host's watchdog behaviour)? This choice does not change the
  production fix, only how the macOS tests trigger the error.
- **What happens to later buffers after a kill.** `semaphore-coordination.md`
  states that after a watchdog kill "the process's later submissions are ignored
  and kernels silently read `0.0`". If that is true process-wide (not only for
  buffers that waited on the killed buffer's value), then *other* runners and
  even other contexts are also poisoned, and recording the error per runner is
  not enough. **Open question:** has this been confirmed, and should a recorded
  Metal error be device-wide rather than runner-wide?
- **`destroy()` with a recorded error.** `destroy()` drains through the same
  `drainOldestCommitted`. Should destroying a runner whose buffers errored throw
  (loud, but turns teardown into a failure path for every caller of
  `MetalCommandRunner.destroy()`), or only log? The recommendation is: record and
  log during destroy, throw only from `complete()`.
- **`asynchronous-submission.md` does not exist.** The task names it as a doc to
  update. The plan proposes creating it. **Confirm** that creating this new
  internals doc is intended, versus folding the material into the existing
  `semaphore-coordination.md` (which already covers settlement) or
  `backend-compilation-and-dispatch.md`. Folding it into
  `semaphore-coordination.md` avoids a third document describing the same model.
- **`onComplete` contract.** The task lists `onComplete` among the compositions
  that must propagate a failure. Its default implementation cannot: it has no
  returned completion to carry the failure, and its callback is success-only. The
  plan fixes only the bridge's use of it. **Open question:** should `onComplete`
  be documented as success-only (and its other callers audited for work that
  silently never runs after a failure), or should it gain a failure-observing
  variant?
- **`whenSettled` swallow.** `whenSettled(Runnable)` deliberately swallows the
  failure because it is the resource-release / settlement-observation callback and
  must run on both paths; rethrowing would throw on a `CALLBACK_EXECUTOR` thread
  with no catcher and could skip the cleanup. The plan keeps that behaviour and
  relies on *waiters* (plus the loud `warn` backstop for the no-waiter case) to
  surface failures. If the task genuinely requires a settlement-callback *owner* to
  observe the failure, that is a signature addition (e.g. an overload taking a
  `Consumer<Throwable>`), which §3's bridge would also want. **Open question:**
  leave `whenSettled` as cleanup-only (recommended), or add a failure-observing
  overload and use it for the bridge?
- **Task description corrections.** The description references a `PendingBridges`
  path that is not a current class name (the bridge path is the host-signaled
  `MTLEvent` in `MetalCommandRunner.encode`), and names `Semaphore.onSettled` where
  the method is `whenSettled`. These have been noted as a planning clarification on
  the tracker task; the plan uses the accurate names. The clarification on the
  task also says the composition layer "already propagates failures"; that is
  true of `all`/`then`/`DeferredSemaphore` but not of `onComplete`, which the
  bridge uses (see Current state).
- **Platform.** Implementation verification requires a macOS node: a
  `MetalCommandRunner` can only be constructed against a real `MTLCommandQueue` /
  Metal device. The accompanying `docs/plans/task-20261028-085707-workstream.yaml`
  pins `platform: macos` so implementation runs where Metal is available. The
  native-only and composition-only tests still run on any node in CI.

## Estimated complexity

**Medium.** The production change is localised to `base/hardware`
(`MetalCommandRunner`, `MetalSemaphore`, `NativeExecution`) plus docs, and the
composition layer already does most of the propagation. The complexity is
concentrated in (a) recording the Metal error durably and detecting it in queue
order so *every* waiter and dependent fails promptly rather than only the draining
thread, without disturbing buffer drain/release or batching, (b) the bridge's
failure path, (c) keeping `NativeExecution`'s memory-guard release intact once its
latch rethrows, and (d) exercising the real Metal composition code from a test
without depending on a flaky GPU watchdog.
