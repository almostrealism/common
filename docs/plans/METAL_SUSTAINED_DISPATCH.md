# Plan: Deferred Operation Completion (async dispatch into the ComputeContext)

## Status

**Redo landed (commit `42cadcc05`); performance follow-up attempt 2 tried and REVERTED — blocked
on a second-command-buffer completion stall (see "Attempt 2" below).** The first
implementation (PR #277) was rejected for systematically violating the platform's design —
read **"Failure pattern of the first attempt"** near the end of this document before changing
anything. The redo replaced it and mirrors the OpenCL provider exactly (see "Redo — the
correct implementation" below). What remains is one performance follow-up, analyzed in
"Performance follow-up" below.

### Redo — the correct implementation (done)

Mirrors `CLOperator`/`CLSemaphore`, no carve-outs:
- Native `MTLSharedEvent` (the `cl_event` analog): `MTL.cpp` `createSharedEvent` /
  `encodeSignalEvent` / `encodeWaitForEvent` / `releaseSharedEvent`, with typed wrappers
  `MTLEvent`, `MTLDevice.newSharedEvent()`, `MTLCommandBuffer.encode{Signal,WaitFor}Event`.
- `MetalSemaphore` (typed, `implements Semaphore`, mirrors `CLSemaphore`) — the **one**
  completion handle per dispatch; `waitFor()` commits its buffer if open then waits; carries
  the event+value so a dependent orders after it on the GPU.
- `MetalCommandRunner.submit(command, dependsOn, onComplete)` **always** returns a
  `MetalSemaphore`; a same-runner `MetalSemaphore` dependency → commit the open buffer +
  `encodeWaitForEvent` (GPU order, no host stall); independent dispatches share an open buffer
  and commit once at the consuming `waitFor`. No `enable*` flags.
- `MetalOperator.accept` **always** honors `dependsOn` (GPU-order a same-runner
  `MetalSemaphore`, else host `waitFor` a foreign one) and **always** returns the
  `MetalSemaphore`.
- Cross-stack carve-outs removed (`isCompletionDeferred`, `enableSemaphoreChaining`,
  `enableBatching`, the completion wrapper); `getSemaphore()` returns the one completion;
  logging restored; native handles typed (see `base/CLAUDE.md`).

Validated: forced `mtl` 73/73 correct (incl. the dependent `integersIndexAssignment`); native
43/43 (no regression); checkstyle + code_policy clean.

### Performance follow-up (in progress)

Microbenchmark is ~245 µs/dispatch vs a ~100 µs batched target. **Root cause:** the
`whenReady` executor is `ComputeContext.runLater` →
`Executors.newFixedThreadPool(KernelPreferences.getEvaluationParallelism())` — a *multi-thread*
pool. Independent operations' `whenReady` tasks (which do arg-loading **and** call
`runner.submit` to encode) race, so encodes can reach the runner out of submission order.
Metal same-buffer hazard tracking orders dependents by **encode order**, so an out-of-order
encode of a real read-after-write would be wrong. `AcceleratedProcessDetails.awaitReady()`
(waited by `run`/`submit`/`evaluate`/`request`) forces each dispatch to be fully issued before
the next — restoring order, but serializing the per-op encode and erasing the arg-loading
overlap that made batching fast.

The agreed model (confirmed with the owner): submission order **is** a valid dependency order
(the platform enforces dependency structure at creation time), so the invariant to preserve is
simply "**encode in submission order**" — no dependency analysis, no per-op host stall.
Within one Metal context: keep arg-loading parallel, order only the cheap encode, let the GPU
run independent kernels concurrently and hazard-track dependents; host-wait only at a *foreign*
ComputeContext boundary (where the penalty is acceptable).

**Attempt 1 (reverted — deadlocks).** Added a submission-ordinal turn gate
(`ComputeContext.reserveDispatch`/`awaitDispatch`/`completeDispatch`, driven by the runner) so
the `whenReady` callback waits its turn before encoding, with a forward-resolving completion so
`submit` is non-blocking and `awaitReady` removed. It **hangs**: `awaitDispatch` blocks a
*bounded* pool thread, and the per-op postprocessing `nextSemaphore.waitFor()`
(`AcceleratedOperation.apply`) blocks more pool threads inside `MetalCommandRunner.complete →
waitUntilCompleted`. With more concurrent dispatches than pool threads, the thread holding the
needed turn (or the buffer that must complete) can be queued behind blocked threads →
thread-pool starvation. Confirmed by jstack (threads parked in `complete`/`completeOnExecutor →
waitUntilCompleted`).

**The real requirement, learned from attempt 1: nothing that runs on the shared bounded pool may
block on the runner.** The deadlock-free shape is:
- The pool thread, after arg-loading, hands the encode to the runner **non-blocking** with its
  submission ordinal, and returns immediately.
- The runner (its own single thread) drains encodes in **ordinal order**, buffering out-of-order
  arrivals.
- `submit` returns a **promise** `MetalSemaphore` (created up front, its command-buffer/value
  resolved when the runner encodes it) — which also collapses the readiness latch into one
  self-resolving completion (closing the "two semaphores" comment for real).
- The per-op postprocessing wait must not run on the pool either, or must not commit-and-wait
  per op.

**Attempt 2 (tried — REVERTED; second-buffer completion hang).** The obstacle in attempt 1 was
that ordering needed a submission ordinal that `Execution.accept(args, dependsOn)` does not
carry. Attempt 2 avoided the ordinal entirely by using the completion `Semaphore` itself as the
cross-operation plumbing — exactly what the type exists for — adding **one general,
provider-agnostic capability** with no-op defaults so non-Metal providers were unaffected:

- `Semaphore.andThen(Runnable)` — the non-blocking, issuance-time dual of `waitFor`. Default runs
  the continuation immediately; `MetalSemaphore` overrode it to run when the dispatch is *encoded*.
- `ComputeContext.reserveDispatch()` — creates the dispatch's completion **up front** (default
  `null`; Metal returned an unresolved promise `MetalSemaphore`).
- `Execution.accept(args, dependsOn, completion)` — a 3-arg overload (default delegates to the
  2-arg form; Metal overrode it to **resolve** the pre-created promise when the dispatch issued).

The intended flow: `AcceleratedOperation.apply` calls `reserveDispatch()`, publishes the promise
synchronously, passes it into 3-arg `accept`; `OperationList`'s runner threads each member's
completion into the next member's `dependsOn`; `MetalCommandRunner.submit` becomes non-blocking
(`executor.execute`) and registers the encode via `dependsOn.andThen(...)`, a cascade that orders
the chain on the runner's single thread with no pool-thread blocking; `awaitReady` removed.

**The symptom.** Forced `mtl` `OperationSemaphoreTests#sum`/`sumPowers` and
`OperationDispatchBatchingTests#manySmallDispatches` **hang**. Tracing the runner (one log line
per encode/commit/wait) showed the *first* command buffer commits and completes
(`waitUntilCompleted` returns), but whenever a *second* command buffer is created, committed and
waited, it **never completes** — the runner's single executor thread parks in native
`waitUntilCompleted` forever (jstack: `RUNNABLE`, burning CPU), and the host blocks behind it.
A native diagnostic that polled `MTLCommandBuffer.status()` turned the hang into a SIGSEGV in
`objc_msgSend` from `waitUntilCompleted` — the buffer was *over-released* (a queue-autoreleased
command buffer freed once it completes, then messaged again by the poll); that crash was a
diagnostic artifact, but it confirms the symptom is command-buffer-lifetime/completion-sensitive.

**Bisection (definitive — done on a quiesced GPU, ruling out contention).** Starting from a
known-good baseline and adding one change at a time:

1. *Promise `MetalSemaphore` + non-blocking runner alone (apply-path at HEAD, no `reserveDispatch`,
   no chaining, `awaitReady` kept):* **PASSES** `sum` in ~2.8 s and batches (`openCount` up to
   144), at ~48 µs/dispatch — a real improvement. So the promise object, the 3-arg `accept`, the
   `andThen` cascade and even the non-blocking `executor.execute` handoff are **not** the problem.
2. *Add `reserveDispatch` (publish the completion before `whenReady` and pass it into 3-arg
   `accept`):* **breaks batching** (`openCount` drops to 1 — one committed buffer per dispatch) and
   `sum` **hangs** on the second buffer. So `reserveDispatch`/early-publication is one trigger and
   was abandoned.
3. *Drop `reserveDispatch` again, keep the non-blocking runner:* `sum` passes (batches, avoids a
   second buffer) but `OperationDispatchBatchingTests#manySmallDispatches` still **hangs** — its
   per-dispatch `waitFor` (the `processing` branch of `apply` / inline `DestinationEvaluable`
   waits) commits one buffer per dispatch, so it hits the second-buffer path and stalls.
4. *Blocking encode vs non-blocking, with/without the now-unused `encodeSignalEvent`:* **no
   change** — the second buffer hangs either way. GPU contention also ruled out (other CI runners
   stopped; still deterministic).

**The unresolved core.** Any workload that creates a **second committed command buffer** under
the promise/runner shape stalls in `waitUntilCompleted` on that buffer, while the redo (HEAD) —
whose `submit`/`commit`/`complete`/`completeOnExecutor` are byte-identical at the executor level —
completes the same second buffer in ~3 s. The redo's only material differences are: `submit`
blocks the caller on the encode via `await(executor.submit(...).get())`, and a same-runner
dependent commits the open buffer and uses `encodeWaitForEvent` to wait the prior buffer's event
(so consecutive buffers are GPU-ordered). The promise design removed both. Why a fresh second
buffer that is committed and waited never completes — when the first one does and the redo's does
— was **not isolated** by black-box bisection; it needs Metal-level command-buffer error
introspection (retain the buffer across the wait, read `buffer.status()`/`buffer.error()` without
the autorelease pitfall, or attach a completion handler) and likely the owner's read on whether
two independent committed buffers on this serial queue need the event-wait the redo encodes.

**Current state:** reverted to the redo (HEAD) — correct, flag-free, already collapses
command-buffer *count* (the stall-avoidance goal). The non-blocking promise runner is a proven
~2–4× win for *batched* workloads but is blocked on the second-buffer completion stall for
per-dispatch-`waitFor` workloads. All attempt-2 code was reverted so the tree carries no unused
API or half-working path; the design and bisection above stand as the starting point for a future
attempt.

### Original framing

The motivating outcome is **sustained Metal dispatch**, but the work is a
**general, provider-agnostic** change: move an operation's completion barrier (the "wait")
down into the `ComputeContext` provider, exposed through an executable-side contract that
any `ParallelProcess` uses and every provider honors — *not* opts into.

Scope here is **Options A + C** (group submission + async completion/chaining). Kernel
**fusion (Option B)** — collapsing many operations into one kernel — is deliberately out of
scope; it is entangled with the (complex) rules for when a `Computation` graph becomes one
kernel program vs many, which we will discuss separately.

## Motivating problem (Metal)

Continuous real-time `AudioScene` rendering sustains indefinitely on the **native (CPU)**
backend but **stalls on Metal after ~2300–2560 kernel dispatches**: the host parks forever
in `AcceleratedComputationOperation.waitFor` → `MTL.waitUntilCompleted` (a committed command
buffer never reports completion) while the process burns 100% CPU in the driver.

Ruled out, with evidence: not a Java leak (native uses the same orchestration and never
stalls); not GC (<0.2 s total); not tracked GPU memory (`MetalMemoryProvider` flat ~625 MB);
not process RSS (flat ~1.46 GB); not the metal-cpp autorelease leak (command buffers are now
drained per dispatch via `MTL.autoreleasePoolPush/Pop` in `MetalCommandRunner` — correct
hygiene, but the stall persists); not deterministic (stall buffer varies 1792/2304/2560 → a
*cumulative* effect of dispatch **count**). Conclusion: a driver-internal resource tied to
the number of command buffers / submissions accumulates and wedges the pipeline.

Today `MetalOperator` submits **one `MTLCommandBuffer` per kernel dispatch** and **blocks**
on each (`// TODO  This should actually return a Semaphore rather than blocking`). A single
tick issues many kernels → the count climbs fast. Fewer, batched submissions + not blocking
per dispatch is the fix — and it is the same mechanism that lowers per-dispatch overhead on
*every* backend.

## The general goal

Let an operation's **completion wait live in the provider**. The host should be able to
*submit* work and only *await* it at a boundary where a result is actually consumed, while
consecutive operations chain on each other inside the provider (GPU-side), not via host
round-trips.

## Architectural framing (read this before touching code)

`Process<P, T> extends Supplier<T>` is the general "supplies something executable"
abstraction; `ParallelProcess` is its parallel form. **`T` is the executable**, and it
differs by use:

| Supplier (a `Process`) | Executable (`T`) | Async executable interface |
|---|---|---|
| `Producer` | `Evaluable<T>` | `StreamingEvaluable<T>` (via `Evaluable.async()`) — **exists** |
| `OperationList` (and other `OperationComputation`s) | `Runnable` | **none yet** ("StreamingRunnable" gap) |

Consequences that constrain the design:

- **The async contract belongs on the executable interface (`T`), not on a concrete
  supplier.** `StreamingEvaluable` is an *interface*, so "solve it for `StreamingEvaluable`"
  ≈ "solve it for everyone that produces one." `OperationList` is a *concrete class* — one
  specific strategy for "combine these operations (as one kernel or many)"; solving it *in*
  `OperationList` solves it only for that class. Do **not** anchor the solution there.
- The work must serve **any `ParallelProcess`**. `OperationList` is just the first *consumer*
  we exercise; the mechanism it uses must be the general one.
- There is an asymmetry to resolve: the `Producer`/`Evaluable` side already has the async
  interface (`StreamingEvaluable`); the operation/`Runnable` side does not. Part of this work
  is providing that analog (or a unified executable-side completion contract) so both sides,
  and thus any `ParallelProcess`, can participate.

## The provider model (how a provider participates, generally)

A **provider-owned `Semaphore`** represents one operation's completion, with two capabilities:

1. **chain** — a later dispatch can be enqueued to *wait on* a prior `Semaphore` **inside the
   provider** (no host block). This already exists as the `dependsOn` parameter:
   `CLOperator` passes it straight into `clEnqueueNDRangeKernel` as a `cl_event` wait-list.
2. **await** — the host blocks on a `Semaphore` only at a consumption boundary
   (`AcceleratedOperation.waitFor`).

Opt-in by construction:

- A provider that supports async returns a **live** `Semaphore` backed by its native
  primitive: OpenCL `cl_event` (`CLSemaphore` — built, see the commented-out
  `// return new CLSemaphore(...)` in `CLOperator`), Metal `MTLSharedEvent` /
  command-buffer completion handler, etc.
- A provider that does not returns an **already-completed** `Semaphore` → the trailing
  `waitFor` is a no-op and behavior is exactly as today (synchronous; e.g. native/CPU).

So the calling convention is identical for every backend (chain via `dependsOn`, barrier via
`waitFor`); participation is opt-in; non-participants degrade transparently. **The platform
expresses the dependency graph in Java (Semaphores / the streaming executable); each
`ComputeContext` lowers it into its native primitive.** That is what "moving the wait into
the provider" means.

## Substrate that already exists

- `AcceleratedOperation.run()` is already `apply()` (which returns an
  `AcceleratedProcessDetails` carrying a `Semaphore`) followed by `waitFor(semaphore)` —
  **dispatch and wait are already separate calls**.
- Operators already accept `dependsOn`; `CLOperator` already chains via `cl_event`
  (the async return is commented out, currently synchronous via `processEvent`). This is the
  clearest existing *model* of provider-side chaining — but Apple OpenCL is deprecated and is
  not the default executor here, so it is a reference only, not the implementation target.
- `Evaluable.async()` → `StreamingEvaluable` (push model: `request(args)` returns
  immediately, result delivered to `setDownstream(consumer)`).
- The `HeapStage` pending-kernel hooks exist for future async operators.

## Approach: Options A + C combined

- **C — async completion / chaining.** Make `apply()` return the provider's *live*
  `Semaphore` instead of waiting internally; thread `dependsOn` so consecutive operations
  chain provider-side. Default `Semaphore` = already-completed → synchronous, unchanged.
- **A — group submission + single barrier.** A `ParallelProcess`'s executable dispatches its
  group with each member chaining on the prior, and the host issues **one** `waitFor` at the
  boundary. On Metal that boundary is also where one command buffer wraps the group; on CL,
  one `clFinish`.

Together: the whole group costs ~one submission + one wait instead of N host round-trips,
and (on Metal) ~one command buffer instead of N.

## First implementation steps (correctness first; measurements later)

> The CI box is currently also running earlier jobs, so timing will fluctuate — these first
> steps are about *working + correct*, gated by tests, not about benchmarks.

> **Target the Metal backend, not CL.** Measured here with the microbenchmark below:
> JNI/native ≈ 159 µs/dispatch (synchronous CPU — nothing to defer), Metal ≈ 1193 µs/dispatch
> (the real target — worst per-dispatch overhead), CL ≈ 613 µs/dispatch (Apple-**deprecated**,
> and not the default executor). Apple OpenCL is a compatibility shim; the `cl_event` chaining
> in `CLOperator` is a useful *model* for the contract but is not an implementation or
> validation target. JNI is the synchronous correctness baseline; **Metal is where the work
> pays off**.

1. **(DONE) Per-operation completion threading.** `apply(output, args, dependsOn)` passes
   `dependsOn` into `operator.accept(...)`, and the operator's returned device-completion
   semaphore is adopted as the process completion (`AcceleratedProcessDetails.getSemaphore()`
   prefers it). Default unchanged (no provider returns a live semaphore yet).
2. **(DONE) Executable-side async contract + composite chaining (provider-agnostic).**
   Added `io.almostrealism.concurrent.Submittable` (the operation/`Runnable` analog of
   `StreamingEvaluable`): `Semaphore submit(Semaphore dependsOn)`. `AcceleratedOperation`
   implements it (`run()` is `submit(null)` + wait). The `OperationList` `Runner` consumes it
   generically (`instanceof Submittable`) — chains each child's completion into the next's
   `dependsOn`, one trailing wait; non-`Submittable` members barrier-then-run, preserving
   order.
   **Critical constraint discovered:** chaining is *only* sound/beneficial when the provider
   returns a **live device completion** that the next dispatch waits on inside the provider.
   On synchronous providers the completion is a host latch counted down by the
   `Hardware.isAsync()` arg-loading executor; threading it through `dependsOn.waitFor()`
   serializes across executor tasks and is catastrophically slow (observed ~800× on the
   microbenchmark; root cause is the `dependsOn.waitFor()` at `NativeExecution.accept`, which
   the original author flagged with a `TODO  We can do better than forcing this method to
   block`).
   **Resolution — provider-declared deferral (the "any platform can participate" hook):**
   `ComputeContext.isCompletionDeferred()` (default `false`) lets a provider opt in;
   `MetalComputeContext` returns `MetalCommandRunner.enableBatching`. `Submittable` exposes the
   same predicate (default `false`), overridden by `AcceleratedOperation` to read its context.
   The `Runner` chains a member **only** when `isCompletionDeferred()` is true; every other
   member runs sequentially exactly as before. This honors the `Submittable` contract's existing
   promise that synchronous providers "degrade transparently to sequential synchronous
   execution." The provider gate is correct and verified inert on synchronous providers: with
   both flags briefly defaulted on, native = 92.95 µs/dispatch (no regression — chaining inert on
   JNI), `*` = 141.72 µs (routes to JNI, inert, no regression), `mtl` = 97.05 µs (batches), and
   `MatrixMathTests` 10/10 green on Metal.
   **Root cause of the broad-run correctness gap — FOUND and FIXED (encode ordering).**
   The stale-zero failures (e.g. `CollectionComputationTests.integersIndexAssignmentOperation`,
   `0.0 != 3.5`) were **not** command-buffer mis-ordering and **not** a stale host read.
   Proven by two diagnostics: forcing every dispatch into its own command buffer (no batching)
   still failed, and forcing all dispatches to share one buffer (no batch breaks) passed — so
   Metal's same-buffer hazard tracking orders dependent kernels correctly. The real bug: when
   batching is on, `OperationList.Runner` chains members via `Submittable.submit()`, but
   `submit()` returned *before the kernel was encoded* (encoding happens in the async
   `AcceleratedProcessDetails.whenReady` callback), so a dependent op2 could be encoded/dispatched
   before op1 — reading stale inputs. **Fix:** `AcceleratedProcessDetails.awaitReady()` waits the
   host-readiness latch (encode done, commit still deferred) and `AcceleratedOperation.submit()`
   calls it before returning, so chained members are encoded in order. Validated: 7 collection/
   matrix/kernel classes forced to `mtl` with `AR_METAL_BATCH=enabled
   AR_HARDWARE_SEMAPHORE_CHAINING=enabled` go from 2 batching failures to **0** (the remaining 7
   `FourierTransformTests` "Failed to compile f_fourierTransform_*" errors are **pre-existing** —
   they fail with batching off too — as is the `Convolution2dDeltaComputationTests` compile-hang).
   The completion-wait fix in `getSemaphore()` is retained (commit-before-read for the run/evaluate
   path). The dependency-gating experiment (per-op read/write hazard tracking in the runner) was
   removed as unnecessary once `awaitReady` was in place.
   **Defaults now ON** (`AR_METAL_BATCH` and `AR_HARDWARE_SEMAPHORE_CHAINING` both `orElse(true)`).
   Validated under the test-mac backend config `AR_HARDWARE_DRIVER=*`: the previously-failing
   collection/matrix/kernel/enumerate/repeated/Fourier classes pass **81/81** (the forced-`mtl`
   Fourier compile errors do not occur under `*`, where those ops route to JNI), and forced `mtl`
   with batching is correctness-clean. The provider gate keeps chaining inert on JNI, so the
   ubuntu/native CI jobs are unaffected.
   **PERF CAVEAT (follow-up, non-blocking):** `awaitReady` serializes per-op encode, which erased
   most of the per-dispatch *latency* speedup (microbenchmark forced `mtl`: batched ON ≈ 205 µs vs
   OFF ≈ 216 µs, versus 97 vs 209 before the fix). Crucially batched-ON is still ≈ OFF, so enabling
   the default adds **no slowdown** versus the prior state; batching also still reduces
   command-buffer *count* (the original stall-avoidance goal). Recovering the latency win needs
   encode-ordering that doesn't block arg-loading (order only dependent members) — left as a
   follow-up that does not block the default.

   **(historical) Defaults reverted to OFF — broad-run correctness gap.** A full multi-backend
   (`AR_HARDWARE_DRIVER=*`) suite run with the defaults on produced stale-zero reads in many
   evaluate-based tests (`CollectionComputationTests.integersIndex`, `CollectionEnumerateTests
   .transpose`, `SphereTest.discriminantKernel/intersectionKernel`, several
   `FourierTransformTests`) **plus a 240 s timeout in `SyntheticActivationTrainingTest
   .denseWithSiLU`**. The failures do **not** reproduce when those same classes run in isolation
   (forced `mtl` or `*`, both green), so the cause is not a single missing flush but
   deferred-commit state that survives across operations/tests in a shared JVM (an uncommitted
   open command buffer / executor / autorelease pool) and a hang in long training chains.
   `MetalOperator.run()`/`AcceleratedComputationEvaluable.evaluate()` already wait on
   `process.getSemaphore()`, so the gap is that with batching the waited semaphore can be the
   host-readiness latch rather than the deferred `FlushSemaphore` (a race set during the
   `whenReady` callback) — i.e. the result is read before the batch commits. **Both flags now
   default off again; batching remains validated and opt-in (`AR_METAL_BATCH=enabled` +
   `AR_HARDWARE_SEMAPHORE_CHAINING=enabled`).** Next: make the host read of Metal memory flush
   the open command buffer (or make the deferred completion the authoritative wait), then
   reproduce the full-suite failures before re-enabling.
3. **Metal command-buffer batching — DONE (measured 2.1× on the microbenchmark).**
   - **(DONE) Encode/commit split.** `MetalCommand` now *encodes only* (`encode(cmdBuf)`);
     `MetalCommandRunner` owns the command-buffer lifecycle. With batching off
     (`AR_METAL_BATCH`, default off) each command commits+waits immediately (legacy behaviour,
     validated: 6 Metal tests green, no regression). With batching on it encodes into one open
     command buffer (one encoder per command → Metal hazard tracking orders dependents) and
     commits at the trailing completion wait (`completionSemaphore().waitFor()`) or the
     `maxBatchSize` cap. Memory lifetime is handled: the `onCommit` callback (which releases the
     kernel's `KernelMemoryGuard` and fences its references) runs only after the buffer it was
     encoded into commits+completes; the autorelease pool spans the whole batch.
   - **(DONE) Per-command argument binding via `setBytes`.** The shared, reused `offset`/`size`
     argument buffers — which under deferred commit would make every batched kernel read the
     *last* command's offsets/sizes at GPU-execution time — are gone. Per-dispatch offsets/sizes
     are now inlined into the command with a new native `MTL_setBytes`
     (`MTL::ComputeCommandEncoder::setBytes`), surfaced as `MTL.setBytes(long,int,int[])` and
     `MTLComputeCommandEncoder.setBytes(int,int[])`, called from `MetalOperator.accept`. Metal
     copies the values into the command at encode time, so each batched command captures its own
     args — no shared buffer to corrupt. `MetalCommandRunner` no longer allocates the `offset`/
     `size` buffers.
   - **(MEASURED) Microbenchmark result.** `OperationDispatchBatchingTests` forced to Metal
     (`-DAR_HARDWARE_DRIVER=mtl`, 1024 dispatches): batching **off** = 209.89 µs/dispatch;
     batching **on** (`-DAR_METAL_BATCH=enabled -DAR_HARDWARE_SEMAPHORE_CHAINING=enabled`,
     note the values are `enabled`/`disabled` per `SystemUtils.isEnabled`, *not* true/false) =
     100.43 µs/dispatch (**~2.1×**), correctness preserved (output == 2×input asserted).
     Checkstyle + code_policy clean.
   - **(REMAINING) Sustained-dispatch confirmation.** Run `RealtimeContinuousRenderer` on Metal
     with batching on to confirm it blows past the ~2560-buffer stall (see below).

## Validation harness

- **Microbenchmark (new):** `engine/utils/.../OperationDispatchBatchingTests` — N tiny
  independent ops (`OperationList(name, false)`, intentionally **unfused**), run M times,
  reports µs/dispatch and asserts correctness. Force the backend with
  `-DAR_HARDWARE_DRIVER=mtl|native|cl`. Baselines (1024 dispatches): **native ≈ 159 µs**,
  default `"*"` ≈ 261 µs (executes on JNI, *not* CL), **CL ≈ 613 µs**, **Metal ≈ 1193 µs**.
  Metal is the target. Raising the iteration count reproduces the Metal stall.
- **Correctness gate:** every step must keep this test (and the existing `OperationSemaphoreTests`,
  `BatchedVsPerNoteRmsTest`, audio RMS checks) green — output must be numerically unchanged.
- **Sustained-dispatch outcome:** `RealtimeContinuousRenderer` on Metal
  (`-DAR_RT_CHANNELS=0 -DAR_PATTERN_CACHE_PERSIST=true -DAR_RT_PACE_RATE=0`) must blow past
  ~2560 buffers with `slow=0`; then multi-channel (`-DAR_RT_CHANNELS=0,1,2,3,4,5`) reaches
  ratio < 1.0 on the GPU.

## Open questions (later)

- **OperationList → kernel granularity.** When does a `Computation`/`OperationList` graph
  compile to one kernel vs many? Those rules interact with grouping/chaining and with
  fusion (Option B); to be discussed before extending beyond A + C.
- Where the group/barrier boundary is chosen (per `OperationList`, per tick, per
  `StreamingEvaluable.request`) and how it composes with nested processes.

## Failure pattern of the first attempt (READ THIS BEFORE THE REDO)

The first implementation of this plan (PR #277) was rejected. It "worked" in the narrow
sense that the targeted tests passed, but it did so by violating the platform's design at
nearly every step. Every individual mistake is an instance of **one** pattern, and that
pattern must be understood before re-attempting, because it is seductive and it will recur.

### The pattern: carving exceptions into a universal contract instead of fixing the abstraction

The platform runs on **universal contracts**. The central one here:

> An operation publishes a completion `Semaphore`, and anything that depends on the
> operation waits on that semaphore. Unconditionally. Every provider, every operation.

The contract's value *is* its universality — ordering and correctness hold by construction
because there are no exceptions. When the clean contract did not obviously fit the batching
case, the first attempt repeatedly bought local convenience by **spending that
universality**: a flag here, a conditional there, a second semaphore, an untyped handle, a
renamed log line. Each is a place where some code is *exempted* from the rule.

This is not a style problem. **The bug lived inside the carve-out.** The stale-zero
corruption that consumed the entire debugging effort was *caused by* `MetalOperator`
skipping `dependsOn.waitFor()` when batching was on (it was handed a dependency and refused
to honor it). The "fix" was then *another* carve-out — a second "readiness" semaphore plus
`awaitReady()` — to compensate for the first. Patching holes in a contract with more holes.

### The carve-outs that occurred (so they are recognizable next time)

- **Conditional compliance with a dependency.** `if (!enableBatching) dependsOn.waitFor()` —
  refusing to wait on a semaphore you were told you depend on. There is never a reason to
  ignore a `dependsOn`. If waiting should be cheap, make the *semaphore* cheap (an
  already-completed semaphore), not the *wait* conditional.
- **Conditionally returning the completion.** `return enableBatching ? completionSemaphore() : null`.
  If a caller is handed a semaphore it must use it. If no semaphore should exist, the
  **command runner** must be the thing smart enough not to create one — the decision lives in
  the provider, never as a flag at the call site.
- **Two semaphores for one operation.** A "readiness latch" *and* a "completion semaphore".
  An operation has exactly one completion. Two is the visible residue of an unsolved problem;
  make the single semaphore mean the right thing.
- **Feature gates / capability flags.** `enableBatching`, `enableSemaphoreChaining`,
  `ComputeContext.isCompletionDeferred()`. These exist *to enable* special-casing; they are
  the machinery of non-compliance. A provider participates by honoring the contract, not by
  advertising an opt-out.
- **Bolting the feature onto one Computation.** Batching reachable only through
  `OperationList.Runner`. Batching is a property of the **execution/dispatch contract** so
  that *any* `Computation` benefits; it is not a privilege of one class.
- **Dropping types at the JNI boundary.** Raw `long` native pointers and `Set<Long>`. A
  native object gets a Java type that represents it. Using JNI is not a license to stop
  caring about types.
- **Substituting your own vocabulary for established conventions.** Rewriting the CL/JNI/Metal
  data-context log language instead of matching the existing "Using … for …" phrasing — done,
  ironically, while performing a *consistency* task. Conform to what exists; do not invent a
  parallel standard.

### Compliance rules for the redo (non-negotiable)

1. **No new flags or capability predicates** to control dispatch/batching/chaining behavior.
   If you reach for one, the abstraction is wrong — fix the abstraction.
2. **Every provider always returns a real `Semaphore`** — an already-completed one when the
   work was synchronous — so callers can wait **unconditionally** and the synchronous case is
   simply a free wait.
3. **`dependsOn` is always honored.** No code conditionally skips a dependency wait.
4. **One completion concept per operation.** No readiness/completion duality.
5. **The command runner owns the batching decision internally.** No caller branches on whether
   batching is active.
6. **Batching is available to any `Computation`** through the execution contract, not special-cased
   to `OperationList`.
7. **Native handles are typed.** No raw `long`/`Set<Long>` standing in for objects.
8. **Logging and other established conventions are matched, not replaced.**

The litmus test for the redo: *if removing a line would let some operation opt out of waiting
on a semaphore it depends on, that line is the bug — delete it and make the provider correct.*

## References

- `base/relation/src/main/java/io/almostrealism/compute/Process.java`, `ParallelProcess.java`
- `base/relation/src/main/java/io/almostrealism/relation/Evaluable.java` (`async()`),
  `base/relation/src/main/java/io/almostrealism/streams/StreamingEvaluable.java`
- `base/hardware/src/main/java/org/almostrealism/hardware/AcceleratedOperation.java` (`apply`/`waitFor`)
- `base/hardware/src/main/java/org/almostrealism/hardware/cl/CLOperator.java` (`cl_event` chaining, `CLSemaphore`)
- `base/hardware/src/main/java/org/almostrealism/hardware/metal/MetalOperator.java`,
  `MetalCommandRunner.java`, `base/hardware/src/main/cpp/MTL.cpp`
- `engine/utils/src/test/java/org/almostrealism/collect/computations/test/OperationDispatchBatchingTests.java` (harness)
- `studio/compose/src/main/java/org/almostrealism/studio/optimize/RealtimeContinuousRenderer.java` (sustained-dispatch harness)
</content>
