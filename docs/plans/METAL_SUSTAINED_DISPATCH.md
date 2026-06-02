# Plan: Deferred Operation Completion (async dispatch into the ComputeContext)

> # ⚠️ READ FIRST — THE PLATFORM IS A COMPILER, NOT A PLAYER PIANO ⚠️
>
> **You cannot expect your programs to be mindlessly reproduced by the platform.**
> The statements you add to an `OperationList`, and the `Producer` graphs you build, are
> **source for a compiler**. The platform deciphers the *real* dependency structure of what you
> asked for and decides how to fulfil it — and the resulting kernel structure may look **nothing**
> like your input.
>
> Things that follow from this and that this document previously got WRONG:
> - **"I added N operations, so there are N dispatches."** FALSE. N statements like
>   `X[a]=Y[a]+X[a]` added to an `OperationList` typically compile to **ONE** kernel program with
>   N lines, run at the collection's parallelism. One dispatch, not N.
> - **"The statements run in the order I wrote them, as separate steps."** FALSE. The compiler may
>   fuse, reorder (preserving real dependencies), or restructure them entirely.
> - **"`OperationList.Runner` runs my operations."** OFTEN FALSE. When an `OperationList` compiles
>   into a single kernel program, the `Runner` is **not used at all**. Putting code in `Runner` and
>   assuming it executes is a mistake — **verify it runs (log output) before reasoning about it.**
> - **"`A(B(C,D),E(F))` is a Computation A that I wrote wrapping B."** FALSE. It refers to the
>   **compiled IR graph**: after the platform decides fulfilment, that graph has a node B feeding a
>   node A. It is a statement about the compiler's output, not your source.
>
> **THE RULE FOR THIS WORK:** before claiming *anything* about how many kernels/dispatches/command
> buffers a test produces, or what runs where — **capture the actual compiled program with
> `OperationProfileNode` and LOOK AT IT** (MCP: `ar-profile-analyzer` → `get_source` /
> `get_source_summary`). Numbers like "1024 dispatches" or "one buffer per dispatch" are
> **claims about compiled output** and are worthless until you have read that output. Every
> experiment must be checked that it actually ran and had the shape you think it did.
>
> This banner is repeated, in shorter form, at the head of each section. That repetition is
> deliberate. Do not delete it.

## Status

**Redo landed (commit `42cadcc05`) and is the current tree. Performance follow-up attempts 1 and 2
were tried and REVERTED.** The first PR (#277) was rejected for systematically violating the
platform's design (see "Failure pattern of the first attempt"). The redo replaced it correctly.

**The performance follow-up is currently BLOCKED, and — more importantly — the analysis below it
was conducted on the WRONG MODEL of how these tests compile.** Read the banner above. The
attempt-2 "bisection" measured `openCount` (encodes accumulated into a command buffer) and command
buffer counts and reasoned about "buffer per dispatch" / "second buffer hangs" **without ever once
capturing and reading the compiled program** for the tests involved. Those conclusions are
therefore **suspect** and must be re-derived from actual compiled output before any further code
change. See "Attempt 2 — what was tried, and why its analysis is not trustworthy."

> ⚠️ Reminder: nothing below that counts "dispatches", "kernels", or "command buffers" can be
> trusted unless it was checked against the compiled program (`get_source`). Several claims here
> were not.

### Redo — the correct implementation (done, current tree)

Mirrors `CLOperator`/`CLSemaphore`, no carve-outs:
- Native `MTLSharedEvent` (the `cl_event` analog): `MTL.cpp` `createSharedEvent` /
  `encodeSignalEvent` / `encodeWaitForEvent` / `releaseSharedEvent`, with typed wrappers
  `MTLEvent`, `MTLDevice.newSharedEvent()`, `MTLCommandBuffer.encode{Signal,WaitFor}Event`.
- `MetalSemaphore` (typed, `implements Semaphore`, mirrors `CLSemaphore`) — the **one** completion
  handle per dispatch; `waitFor()` commits its buffer if open then waits; carries event+value so a
  dependent orders after it on the GPU.
- `MetalCommandRunner.submit(command, dependsOn, onComplete)` **always** returns a `MetalSemaphore`;
  a same-runner `MetalSemaphore` dependency → commit the open buffer + `encodeWaitForEvent` (GPU
  order, no host stall); independent dispatches share an open buffer and commit once at the
  consuming `waitFor`. No `enable*` flags.
- `MetalOperator.accept` **always** honors `dependsOn` and **always** returns the `MetalSemaphore`.
- Cross-stack carve-outs removed; `getSemaphore()` returns the one completion; logging restored;
  native handles typed (see `base/CLAUDE.md`).

Validated: forced `mtl` 73/73 correct; native 43/43; checkstyle + code_policy clean.

> ⚠️ Reminder: "73/73", "a kernel dispatch", "one command buffer per dispatch" in this section are
> shorthand. Whether a given test produces one kernel or many is a **compiler outcome** — confirm
> with `get_source` before depending on it.

## What "dispatch count" actually means here — GROUND THIS FIRST

> ⚠️ This entire section is the thing that was skipped. Do it before anything else.

The whole performance story is about *how many GPU submissions a workload makes*. That number is a
**property of the compiled program**, not of how many statements you wrote. Before continuing, for
each validation workload, capture and read the compiled output:

1. Run the test once with an `OperationProfile`/`OperationProfileNode` that captures generated
   source, written to an XML under the module's `results/` directory.
2. `ar-profile-analyzer` → `load_profile`, `search_operations`, `get_source_summary`, `get_source`.
3. Write down, **from the actual source**: how many distinct kernel programs compile, how many
   lines each has, the parallelism (global work size), and how many times each is dispatched per
   `run()`/`evaluate()`. Only then talk about command buffers.

Known correction (owner): an `OperationList` of N statements like `X[a]=Y[a]+X[a]` compiles to
**one** kernel program of N lines at the collection's parallelism — **one** dispatch per `run()`,
and `OperationList.Runner` is **not on the execution path**. Any plan that assumed
`OperationList(name, false)` yields N separate dispatches (e.g. the "microbenchmark = 1024
dispatches" framing below) is describing a shape that **was never verified to exist**.

> ⚠️ TODO before resuming: capture `get_source` for `OperationDispatchBatchingTests#manySmallDispatches`
> and `OperationSemaphoreTests#sum` and replace every dispatch/buffer count in this document with
> numbers read from that output. Until then, treat those numbers as unverified.

## Motivating problem (Metal)

> ⚠️ Reminder: "kernel dispatches" below is a compiled-output count — the stall is tied to the
> number of actual GPU submissions the compiled programs make, which you must read from the
> compiled programs, not infer from how many operations a tick "contains."

Continuous real-time `AudioScene` rendering sustains indefinitely on the **native (CPU)** backend
but **stalls on Metal after ~2300–2560 kernel dispatches**: the host parks forever in
`AcceleratedComputationOperation.waitFor` → `MTL.waitUntilCompleted` (a committed command buffer
never reports completion) while the process burns 100% CPU in the driver.

Ruled out, with evidence: not a Java leak; not GC (<0.2 s); not tracked GPU memory
(`MetalMemoryProvider` flat ~625 MB); not RSS (flat ~1.46 GB); not the metal-cpp autorelease leak;
not deterministic (stall buffer varies 1792/2304/2560 → a *cumulative* effect of submission
**count**). Conclusion: a driver-internal resource tied to the number of command buffers /
submissions accumulates and wedges the pipeline. Fewer, batched submissions + not blocking per
dispatch is the fix.

## The general goal

Let an operation's **completion wait live in the provider**. The host should *submit* work and only
*await* it where a result is actually consumed, while consecutive operations chain on each other
inside the provider (GPU-side), not via host round-trips.

> ⚠️ Reminder: "consecutive operations" means consecutive nodes **in the compiled graph**, which
> may be far fewer (or differently shaped) than the operations you added. Don't design the chaining
> around your source order; design it around what the compiler actually emits.

## Architectural framing (read before touching code)

`Process<P, T> extends Supplier<T>`; `ParallelProcess` is its parallel form. **`T` is the
executable**, and it differs by use:

| Supplier (a `Process`) | Executable (`T`) | Async executable interface |
|---|---|---|
| `Producer` | `Evaluable<T>` | `StreamingEvaluable<T>` (via `Evaluable.async()`) — **exists** |
| `OperationList` (and other `OperationComputation`s) | `Runnable` | **none yet** |

- The async contract belongs on the executable interface (`T`), not on a concrete supplier.
  `OperationList` is one *strategy* ("combine these operations, as one kernel or many"); solving it
  *in* `OperationList` solves it only for that class.
- The work must serve **any `ParallelProcess`**.

> ⚠️ Reminder: `OperationList` "combines these operations as one kernel or many" — that decision is
> the compiler's, made per-graph. Whether the `Runner` (the "many" path) even executes is a
> compiled-output fact. Verify, don't assume.

## The provider model (how a provider participates, generally)

A provider-owned `Semaphore` represents one operation's completion, with two capabilities:
1. **chain** — a later dispatch waits on a prior `Semaphore` **inside the provider** (no host
   block); already exists as `dependsOn` (`CLOperator` → `cl_event` wait-list).
2. **await** — the host blocks on a `Semaphore` only at a consumption boundary.

A provider that supports async returns a **live** `Semaphore`; one that does not returns an
**already-completed** one (trailing `waitFor` is a no-op). Same calling convention for every
backend; participation is opt-in; non-participants degrade transparently.

## Approach: Options A + C combined

- **C — async completion / chaining.** `apply()` returns the provider's *live* `Semaphore`; thread
  `dependsOn` so consecutive (compiled-graph) operations chain provider-side.
- **A — group submission + single barrier.** A `ParallelProcess`'s executable dispatches its group
  chaining on the prior, with **one** host `waitFor` at the boundary.

Kernel **fusion (Option B)** — collapsing many operations into one kernel — is *out of scope here*,
**but note that the compiler already does a great deal of this on its own.** That is precisely why
you cannot reason about dispatch counts from source: fusion (and other restructuring) has already
happened by the time anything reaches a `ComputeContext`.

## Attempt 1 (reverted — deadlocks)

A submission-ordinal turn gate (`reserveDispatch`/`awaitDispatch`/`completeDispatch`) that blocked a
bounded pool thread on the runner → thread-pool starvation (jstack: threads parked in
`completeOnExecutor → waitUntilCompleted`). Lesson kept: **nothing on the shared bounded pool may
block on the runner.**

## Attempt 2 — what was tried, and why its analysis is NOT trustworthy

> ⚠️ THE ENTIRE BISECTION BELOW WAS DONE ON THE WRONG MODEL. It counted `openCount` (encodes into a
> command buffer) and "command buffers" and inferred "buffer per dispatch", "second buffer hangs",
> etc., **without ever capturing `get_source` for the failing tests.** Treat every conclusion here
> as a hypothesis to RE-TEST against compiled output, not as fact.

What was implemented (all reverted): a promise `MetalSemaphore` (created up front, resolved at
encode), `ComputeContext.reserveDispatch()`, a 3-arg `Execution.accept(args, dependsOn, completion)`,
`Semaphore.andThen(Runnable)`, a non-blocking `MetalCommandRunner.submit` (`executor.execute` +
`andThen` cascade), `OperationList.Runner` chaining, and removal of
`AcceleratedProcessDetails.awaitReady`.

Observed symptom: forced `mtl`, `OperationSemaphoreTests#sum`/`sumPowers` and
`OperationDispatchBatchingTests#manySmallDispatches` **hang** in native `MTLCommandBuffer.waitUntilCompleted`.

What the bisection *appeared* to show (UNVERIFIED against compiled output — re-derive before
trusting any of it):
- The promise + non-blocking runner alone (apply-path at HEAD) passed `sum` and `manySmallDispatches`
  did not. The interpretation ("first command buffer completes, second hangs") was inferred from
  runner trace logs, **not** from knowing how many kernels/dispatches/buffers those tests actually
  compile to.
- `reserveDispatch` (publishing the completion before `whenReady`) made things worse.

> ⚠️ Why this analysis is unsafe: I did not know (and did not check) how many kernels
> `manySmallDispatches` compiles to, whether `OperationList.Runner` was even invoked, or whether the
> code I added (Runner chaining, `andThen`) ever executed. The owner notes the test likely compiles
> to a **single kernel run a handful of times**, in which case the "1024 dispatches / buffer per
> dispatch" framing is entirely wrong and the runner chaining I bisected **never ran**. The hang is
> real, but its explanation must be rebuilt from: (a) the compiled source of each test, (b) proof of
> which code paths actually execute (logs), (c) only then, command-buffer behaviour.

**Current state:** reverted to the redo (HEAD) — correct, flag-free. The next attempt must START by
grounding every workload in its compiled output (the "What dispatch count actually means" section),
then re-observe the hang with verified knowledge of the kernel/dispatch structure.

## Validation harness

> ⚠️ Reminder: the descriptions below state *intent*. Whether a harness actually produces the shape
> it intends (e.g. "N unfused dispatches") is a compiled-output fact you MUST verify with
> `get_source` — the comment in the test is not evidence, and at least one such comment
> ("1024 dispatches") is contradicted by how the platform actually compiles it.

- **`OperationDispatchBatchingTests`** — *intended* as N tiny ops run M times. **Verify the actual
  compiled kernel/dispatch count before using its numbers.**
- **`OperationSemaphoreTests`**, **`BatchedVsPerNoteRmsTest`**, audio RMS checks — correctness gates;
  output must be numerically unchanged.
- **`RealtimeContinuousRenderer`** on Metal — the sustained-dispatch outcome (blow past the
  ~2560-buffer stall).

## Open questions (later)

- **`OperationList` → kernel granularity.** When does a `Computation`/`OperationList` graph compile
  to one kernel vs many? **This is the central question, not an aside** — every dispatch-count claim
  in this plan depends on its answer, and the answer is read from compiled output, not assumed.
- Where the group/barrier boundary is chosen and how it composes with nested processes.

## Failure pattern of the first attempt (READ BEFORE ANY REDO)

The first implementation (PR #277) "worked" on its targeted tests but violated the platform's design
at nearly every step, all instances of **one** pattern.

### The pattern: carving exceptions into a universal contract instead of fixing the abstraction

The central contract: *an operation publishes a completion `Semaphore`, and anything that depends on
it waits on that semaphore — unconditionally, every provider, every operation.* The value is its
universality. The first attempt repeatedly bought local convenience by spending that universality (a
flag, a conditional, a second semaphore, an untyped handle, a renamed log line). **The bug lived
inside the carve-out:** the stale-zero corruption was *caused by* `MetalOperator` skipping
`dependsOn.waitFor()` when batching was on; the "fix" was *another* carve-out (a second "readiness"
semaphore) to compensate.

> ⚠️ A second, equally important failure mode this document now records: **reasoning about a
> compiled system as if it were an interpreter.** Carving exceptions into a contract is one way to be
> wrong; assuming your source is the program is another. Both produce confident, detailed, and
> false analysis. The antidote to the second is the same as the first: go look at what actually
> exists — here, the compiled output.

### Carve-outs that occurred (recognizable next time)

- Conditional compliance with a dependency (`if (!enableBatching) dependsOn.waitFor()`).
- Conditionally returning the completion.
- Two semaphores for one operation.
- Feature gates / capability flags (`enableBatching`, `isCompletionDeferred()`).
- Bolting the feature onto one `Computation` (`OperationList.Runner` only).
- Dropping types at the JNI boundary (`long`, `Set<Long>`).
- Substituting your own vocabulary for established conventions (log phrasing).

### Compliance rules for any redo (non-negotiable)

1. **No new flags or capability predicates** for dispatch/batching/chaining.
2. **Every provider always returns a real `Semaphore`** (already-completed when synchronous).
3. **`dependsOn` is always honored.**
4. **One completion concept per operation.**
5. **The command runner owns the batching decision internally.**
6. **Batching is available to any `Computation`** through the execution contract.
7. **Native handles are typed.**
8. **Logging and other established conventions are matched, not replaced.**
9. **(NEW) No claim about kernels/dispatches/command buffers without compiled-output evidence.**
   If you find yourself writing a count, you must have read it from `get_source`. If you put code on
   a path (e.g. `OperationList.Runner`), you must have proven from logs that the path executes for
   the workload you're testing.

Litmus test: *if removing a line would let some operation opt out of waiting on a semaphore it
depends on, that line is the bug.* And: *if a sentence states a dispatch/kernel/buffer count you did
not read from compiled output, that sentence is a guess — go verify or delete it.*

## References

- `base/relation/.../compute/Process.java`, `ParallelProcess.java`
- `base/relation/.../relation/Evaluable.java` (`async()`), `.../streams/StreamingEvaluable.java`
- `base/hardware/.../AcceleratedOperation.java` (`apply`/`waitFor`)
- `base/hardware/.../cl/CLOperator.java` (`cl_event` chaining, `CLSemaphore`)
- `base/hardware/.../metal/MetalOperator.java`, `MetalCommandRunner.java`, `base/hardware/src/main/cpp/MTL.cpp`
- `engine/utils/.../collect/computations/test/OperationDispatchBatchingTests.java` (harness — verify its compiled shape)
- `studio/compose/.../optimize/RealtimeContinuousRenderer.java` (sustained-dispatch harness)
- **Tooling to GROUND every claim:** `OperationProfileNode` + MCP `ar-profile-analyzer`
  (`load_profile` / `search_operations` / `get_source_summary` / `get_source`). Use it before
  asserting anything about compiled structure.
