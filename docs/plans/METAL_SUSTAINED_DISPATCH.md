# Plan: Deferred Operation Completion (async dispatch into the ComputeContext)

## Status

Active design. The motivating outcome is **sustained Metal dispatch**, but the work is a
**general, provider-agnostic** change: move an operation's completion barrier (the "wait")
down into the `ComputeContext` provider, exposed through an executable-side contract that
any `ParallelProcess` can use and any provider can opt into.

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
   microbenchmark). So `OperationList.enableSemaphoreChaining` **defaults off**
   (`AR_HARDWARE_SEMAPHORE_CHAINING`) and is meant to be enabled only for async GPU providers.
   Harness + operation tests green with it off (no regression).
3. **Metal provider opt-in (the payoff) — REMAINING.** Provide Metal's device-completion
   `Semaphore` (`MTLSharedEvent` / command-buffer completion handler) and wrap a chained group
   in **one**
   command buffer committed once. With step 2 in place this defers the wait on Metal —
   fewer command buffers + one host wait. Validate on Metal (harness µs/dispatch drops; the
   `RealtimeContinuousRenderer` sustained-dispatch outcome below).

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
