---
status: proposed
created: 2026-10-28
closed:
tracker: [ef3f7786-a97d-4eaa-bcc6-8fdd942df903]
---

# Metal: a GPU-killed command buffer must fail its waiters and dependents

**Tracker task:** `ef3f7786-a97d-4eaa-bcc6-8fdd942df903` — *Metal: a command buffer
the GPU kills (watchdog timeout) must fail its waiters and dependents, not settle
them as if the work ran* — release **Common 0.76**.

## Motivation

When Metal reports an error for a command buffer (for example
`kIOGPUCommandBufferCallbackErrorTimeout` after the GPU watchdog kills a stalled
buffer), the buffer's dispatches never ran, its destinations were never written,
and its timeline-event signals never fired. Today
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
  members):** already correct. `Semaphore.all` settles every member on a
  `CALLBACK_EXECUTOR` thread, records the first member failure with
  `LatchSemaphore.fail(Throwable)`, and `LatchSemaphore.waitFor()` rethrows it
  once the latch releases; a composite wait also settles its members directly and
  rethrows a member failure. `SemaphoreCompositionTest` proves this with a
  unit-level failing `Semaphore` (`RecordingCompletion`).
- **`DeferredSemaphore` (`Semaphore.then`):** already correct. A failed dependency
  is captured with `completeExceptionally`, the work does not run after it, and
  `waitFor()` rethrows the dependency or work failure.
- **`MetalSemaphore.waitFor()` / `MetalCommandRunner.complete()`:** **the real
  gap.** `MetalSemaphore.waitFor()` calls `runner.complete(commandBuffer,
  requester)`, which commits and waits and then drains via `drainThrough` →
  `drainOldestCommitted`. `drainOldestCommitted` detects `buffer.isError()` but
  only warns; it never throws and never records the failure durably. So:
  - the draining waiter returns normally even on an error;
  - a *second, concurrent* waiter on the same buffer finds it already removed from
    `committed` (`drainThrough`'s `committed.indexOf(...) == -1`) and returns
    normally;
  - a *later* waiter finds the buffer already drained (`commitForWait` returns
    `null`, `complete` returns early) and returns normally.
- **`NativeExecution`:** **a real gap.** `NativeExecution.coordinate` runs
  `dependsOn.waitFor()` and the worker dispatches inside `try/catch` blocks that
  `warn(...)` and then count the `DefaultLatchSemaphore` down **without calling
  `latch.fail(e)`**. A native op chained after a failed completion therefore skips
  its work (the dependency threw before workers were submitted) yet its own latch
  reaches zero with no recorded failure, so its `waitFor()` reports success.
- **`Semaphore.whenSettled(Runnable)`:** swallows a `RuntimeException` from
  `waitFor()` **by design** — it is the settlement-observation / resource-release
  callback (it must run on both the success and failure paths, and the comment
  states the failure is reported to the semaphore's own waiters). This is a "drop
  point" only when a `whenSettled` callback is the *sole* consumer of a
  completion; see open questions.

## Scope

In scope:

1. **Make the Metal source fail loudly and durably.** When a committed buffer
   completes with `isError()`, record the failure on the runner (not only on the
   stack of the one draining thread) and make `MetalCommandRunner.complete()`
   throw for every caller whose waited buffer is at or after the errored buffer in
   the serial queue — covering concurrent waiters, later waiters, and
   same-runner GPU dependents (whose completion value is strictly after the
   errored one). The thrown exception carries the Metal error text and the
   requester.
2. **Record native failures on the completion latch.** In `NativeExecution`, a
   failed `dependsOn` wait and a failed worker dispatch call `latch.fail(e)` so the
   returned `DefaultLatchSemaphore.waitFor()` rethrows, instead of only warning.
3. **Confirm (and lock in with tests) that the composition layer already
   propagates** a source failure through `then`, `onComplete`/composite,
   `Semaphore.all`, `DeferredSemaphore`, and bridges to and from other backends.
4. **Documentation.** State how a failed completion propagates in
   `docs/internals/semaphore-coordination.md` and in
   `docs/internals/asynchronous-submission.md`.

Out of scope (named as dependencies / open questions, not widened into here):

- Fixing the *stall* that produces a killed buffer in the first place — that is the
  related task `c9e4c3e7-...` (still open, in the backlog). This task only makes a
  killed buffer, however produced, fail loudly.
- Changing `whenSettled`'s swallow semantics (resource release). See open
  questions.
- Any change to the one-open-buffer invariant, batching, or bridge mechanics.

## Approach

All production changes are in `base/hardware` and `base/code`/`base/relation`; the
docs are under `docs/internals/`.

### 1. `MetalCommandRunner` — durable error state + throwing `complete()`

`base/hardware/.../metal/MetalCommandRunner.java`

- Record the error durably. The command queue is serial and, per the semaphore
  docs, after a watchdog kill the process's later submissions are ignored and
  kernels silently read `0.0` — so an error poisons that runner from the errored
  buffer onward. Record the first error as runner state: the errored buffer's
  timeline value (the monotonic `signaled` value its completion carried) and a
  built `HardwareException` carrying `buffer.getError()` and the requester's
  display name. `errorCompletions` already counts these; keep the existing
  `warn(...)` as the loud log line next to the recorded failure (the task permits
  keeping the warning "as a log line next to the propagated failure").
- In `drainOldestCommitted`, when `buffer.isError()`, build and store that failure
  before/alongside running the buffer's callbacks. Draining the callbacks must
  still happen (memory release, bridge-event release) exactly as today — do not let
  the new throw skip `c.drain()`; record the failure, drain, then surface it.
- Make `complete(MTLCommandBuffer, OperationMetadata)` throw the recorded
  `HardwareException` whenever the buffer it was asked to wait for is at or after
  the recorded error position, including the case where that buffer has already
  been drained (today's early `return` on a `null` waiter). Because the error is
  recorded on the runner keyed by timeline value, every waiter of the errored
  buffer *and* every same-runner dependent (whose `MetalSemaphore.getValue()` is
  strictly greater) throws deterministically — no second watchdog timeout is
  needed to surface the cascade.
- `MetalSemaphore.waitFor()` needs no change beyond delegating to the now-throwing
  `complete()`; it already passes its `requester`. Confirm `whenComplete` /
  `whenSettled` (passive callback registration) keep running on the error path, as
  they do today (a Metal buffer runs its completion callbacks whether or not it
  errored).

Keying the recorded error by the monotonic timeline value keeps the rule honest
for merged completions (`MetalSemaphore.merge` carries the later value, and a
`priorBufferValue` ≥ the error would still be covered) and respects the project
rule against keying collections on native handles — no `MTLCommandBuffer` is used
as a map key.

### 2. `NativeExecution` — record failures on the latch

`base/hardware/.../jni/NativeExecution.java`

- In `coordinate(...)`, the worker task's `catch (Exception e)` (currently
  `warn(...)` + `finally countDown`) also calls `latch.fail(e)` before counting
  down.
- The coordinator-level `catch (Exception e)` around the dependency wait and worker
  submission (currently `warn(getName() + " dispatch failed", e)`) also calls
  `latch.fail(e)`. This is the Metal-to-native path: when `dependsOn` is a Metal
  completion that now throws, the throw is recorded on this op's latch, so its
  `waitFor()` rethrows and anything chained after it sees the failure.
- The synchronous path (`enableExecutor == false`) already propagates by rethrowing
  from `accept`; confirm no regression. `DefaultLatchSemaphore` already exposes
  `fail(Throwable)` (inherited from `LatchSemaphore`), so no new API is needed.

### 3. Composition layer — confirm + test only

No production change expected in `Semaphore` / `LatchSemaphore` /
`DeferredSemaphore`. The work here is tests (below) that drive a real Metal and a
real native chain to the point of a recorded failure and assert the downstream
`waitFor()` throws.

### 4. Documentation

- `docs/internals/semaphore-coordination.md` — add the failure-propagation rule to
  the invariants and the "Metal: completion is driven, not observed" section: an
  errored command buffer records a failure on its runner; `complete()`/`waitFor()`
  throw for that buffer and every later one; `Semaphore.all`/`then`/composites
  rethrow a member/dependency failure; native dispatch records a dependency or
  worker failure on its latch. Reference classes by name (no line numbers, per
  `docs/CLAUDE.md`).
- `docs/internals/asynchronous-submission.md` — this file does **not exist yet**
  (see open questions). Create it as the home for "how asynchronous submission and
  its completion/failure model work" (the material currently living only in the
  `MetalCommandRunner` class javadoc), and state the same failure-propagation
  contract, cross-linked with `semaphore-coordination.md` and
  `backend-compilation-and-dispatch.md`.

## Success criteria

A reviewer can confirm the task is done when:

- `MetalCommandRunner.drainOldestCommitted` no longer settles an errored buffer
  silently: it records the failure, and `complete()`/`MetalSemaphore.waitFor()`
  throw a `HardwareException` carrying the Metal error text and requester for the
  errored buffer and every later completion of the runner.
- `NativeExecution` records a failed dependency or worker on its latch; a native op
  chained after a failed completion rethrows from `waitFor()` instead of reporting
  success.
- Tests prove the end-to-end behaviour (all extend `TestSuiteBase`, each `@Test`
  carries a `timeout`):
  - **Metal-to-Metal chain** (macOS): a real `MetalCommandRunner` chain in which
    the head buffer is made to complete in error; a downstream dependent's
    `waitFor()` (and a single trailing host wait in the `SimulationRunner` shape)
    throws with the error text. Lives alongside `SemaphoreChainBatchingTest` /
    `MetalCompletionCallbackTest` in `engine/utils`.
  - **Metal-to-native chain** (macOS): a `NativeExecution` dispatch chained
    (`dependsOn`) on a Metal completion that completes in error throws from its
    latch's `waitFor()` and does not run its workers.
  - **Native failure propagation** (any backend, no macOS): a `NativeExecution`
    dispatch chained on a unit-level failing `Semaphore`, and one whose worker
    throws, both surface the failure from `waitFor()` — the parts that need no
    Metal device.
  - **Composition regression** (any backend): extend `SemaphoreCompositionTest` /
    add coverage that a failing member/dependency propagates through `then`,
    `onComplete`-composite and `Semaphore.all` (the real composition code), per
    the task's "a unit-level failing Semaphore is fine … must still go through the
    real composition code."
- `docs/internals/semaphore-coordination.md` and
  `docs/internals/asynchronous-submission.md` state how a failed completion
  propagates.
- `mcp__ar-build-validator__start_validation` is clean (checkstyle, code_policy,
  test_timeouts, duplicate_code), and the targeted tests above pass (the Metal
  tests on a macOS node).

## Dependencies and open questions

- **Forcing a Metal error deterministically.** A real watchdog timeout (~5 s, the
  `c9e4c3e7` bridged-stall shape) is slow and flaky to rely on in a test. The plan
  assumes a small, deterministic **test seam** to mark a committed buffer's
  completion as errored (for example an overridable `isError`/error-status hook on
  `MetalCommandRunner` or `MTLCommandBuffer`, exercised by a test subclass), so the
  test drives the *real* `complete`/`drainThrough`/`waitFor` composition code
  without depending on the GPU watchdog. **Open question for the reviewer:** is a
  test seam acceptable, or should the Metal-to-Metal/Metal-to-native tests instead
  reproduce a genuine watchdog kill (slower, order-of-5 s, and potentially flaky)?
  This choice does not change the production fix, only how the macOS tests trigger
  the error.
- **`asynchronous-submission.md` does not exist.** The task names it as a doc to
  update. The plan proposes creating it (the "asynchronous submission" concept
  currently lives only in the `MetalCommandRunner` class javadoc). **Confirm**
  that creating this new internals doc is intended, versus folding the material
  into the existing `backend-compilation-and-dispatch.md`.
- **`whenSettled` swallow.** `whenSettled(Runnable)` deliberately swallows the
  failure because it is the resource-release / settlement-observation callback and
  must run on both paths; rethrowing would throw on a `CALLBACK_EXECUTOR` thread
  with no catcher and could skip the cleanup. The plan keeps that behaviour and
  relies on *waiters* (plus the loud `warn` backstop for the no-waiter case) to
  surface failures. If the task genuinely requires a settlement-callback *owner* to
  observe the failure, that is a signature addition (e.g. an overload taking a
  `Consumer<Throwable>`). **Open question:** leave `whenSettled` as cleanup-only
  (recommended), or add a failure-observing overload?
- **Task description corrections.** The description references a `PendingBridges`
  path that is not a current class name (the bridge path is the host-signaled
  `MTLEvent` in `MetalCommandRunner.encode`), and names `Semaphore.onSettled` where
  the method is `whenSettled`. These have been noted as a planning clarification on
  the tracker task; the plan uses the accurate names.
- **Platform.** Implementation verification requires a macOS node: a
  `MetalCommandRunner` can only be constructed against a real `MTLCommandQueue` /
  Metal device. The accompanying `docs/plans/task-20261028-085707-workstream.yaml`
  pins `platform: macos` so implementation runs where Metal is available. The
  native-only and composition-only tests still run on any node in CI.

## Estimated complexity

**Medium.** The production change is small and localised (two files in
`base/hardware`, plus docs), and the composition layer already does the hard part.
The complexity is concentrated in (a) recording the Metal error durably so *every*
waiter and dependent fails deterministically rather than only the draining thread,
without disturbing buffer drain/release or batching, and (b) exercising the real
Metal composition code from a test without depending on a flaky GPU watchdog.
