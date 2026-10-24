# Argument Binding Lifecycle

How a compiled operation's arguments are bound to memory and prepared for each
invocation, and the invariants that keep long-lived `Evaluable`s correct. The code is in
`AcceleratedOperation`, `AcceleratedComputationOperation` and `ProcessDetailsFactory`
(base/hardware). Their javadoc is the reference; this page explains how the pieces fit
together and why they are built the way they are.

## The two levels of state

1. **Bindings** — the mapping from the compiled `Scope`'s arguments (including aggregated
   arguments that share one buffer, with per-argument offsets) to the operation's
   producers. They are derived from a specific compilation, held by the operation, and
   reused across invocations. `load()` establishes them lazily.
2. **Per-invocation preparation** — for one call with a given output and arguments,
   `ProcessDetailsFactory` decides how each argument is delivered: already-resolved
   memory, a constant (served from the constant cache when `enableConstantCache` is on),
   or a producer that must be evaluated first. It then starts those evaluations and
   collects their completions.

There is no separate "prepare arguments" pass any more. Preparation happens inside
`AcceleratedOperation.apply` via `getProcessDetails`.

## Invariants

### Build preparation state completely, then publish it

`ProcessDetailsFactory.prepare` builds an immutable `PreparedArguments` snapshot entirely
in local variables and assigns it only once it is complete. `construct(PreparedArguments,
Semaphore)` works only on locals.

This matters because preparation can throw. One example is a kernel compile failure when
an aggregate kernel exceeds a device's buffer-argument limit, which Metal caps at about 31.
An earlier design published partially built arrays before the failing step. Every later
call with the same output and arguments then took a fast path over the half-built state
and failed with a `NullPointerException`, permanently poisoning the shared compiled
operation and hiding the real error. Working on locals also removed a reentrancy hazard,
where a nested invocation overwrote the outer invocation's in-progress state. The
regression guard is `ProcessDetailsFactoryRecoveryTest`.

The default `MemoryDataArgumentMap.maxAggregateLength` keeps kernels under the buffer
limit. Lowering it sends more raw arguments into the kernel signature, and nothing yet
checks the limit, so the failure surfaces as a driver error.

### Argument evaluation is ordered after the dependency

When an operation is dispatched with a `dependsOn` completion, *every* part of its
execution must follow it, including the evaluation of producer arguments, not only the
kernel launch. The streaming protocol therefore carries the dependency:
`StreamingEvaluable.request(Object[] args, Semaphore dependsOn)` is implemented by
`EvaluableStreamingAdapter`, `DestinationEvaluable`, `HardwareEvaluable` and
`AcceleratedComputationEvaluable`. The kernel then runs on `Semaphore.all` of the argument
completions and `dependsOn`.

Without this, a hoisted argument evaluation could read a buffer before the dispatch that
writes it had finished. On Metal that can happen before the writing command buffer has even
committed. The CPU backend's near-synchronous execution hides the bug, so it shows up as
stale or zero data only on GPU backends. Chaining argument preparation also removed many
host-forced commits per step.

A separate hazard affects fused operation lists. When several members fuse into one
compiled operation, an isolated subtree of a later member is evaluated before the fused
kernel runs, so it can read memory an earlier member writes. No semaphore can order a
kernel after itself, so `OperationList.subdivide()` splits the list at write→read
boundaries instead.

### Destroying a compilation clears the bindings derived from it

The instruction cache can evict a shared instruction set while operations that used it
are still alive, for example a static map of refresh kernels held for the whole JVM. When
that happens, `AcceleratedComputationOperation.resetInstructions` clears the instructions
*and* calls `resetArguments()`. On next use, `load()` rebinds against the new compilation,
which may have a different argument or aggregate layout.

Before this was fixed, an evicted operation kept its old bindings and ran a freshly
compiled kernel through them. It raised no exception and no backend error; it simply wrote
nothing, or wrote to the wrong memory. The failure appeared only in JVMs with a long
compilation history, such as a full CI test group. The rebind is covered by
`InstructionEvictionRebindTest`, and instruction-cache signature collisions throw
(`InstructionCacheCollisionEnforcementTest`).

Open issue: the eviction listener can fire while another thread is inside `apply` for the
same operation.

## Practical consequences

- Holding a compiled `Evaluable` for reuse is safe and recommended, because eviction is
  handled by rebinding. Do not add code that caches bindings outside the operation.
- When a computation returns `0.0` or stale data only on a GPU backend or only in a long
  test run, suspect a dropped dependency or a stale binding. Inspect the kernel and its
  argument offsets with `ar-profile-analyzer` before adding logging.
- A producer that reports `isConstant()` must not read mutable memory, because constant
  arguments may be served from `ProcessDetailsFactory`'s constant cache.

## See also

- [semaphore-coordination.md](semaphore-coordination.md)
- [native-runtime-lifecycle.md](native-runtime-lifecycle.md)
- [backend-compilation-and-dispatch.md](backend-compilation-and-dispatch.md)
- `base/hardware/docs/INSTRUCTION_CACHING.md`
