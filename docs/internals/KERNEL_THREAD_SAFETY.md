# Generated-Kernel Thread Safety

**Audience:** Engineers and AI agents reasoning about whether a single
generated kernel instance can be invoked concurrently, and what state (if any)
it carries between invocations.

The `NativeInstructionSet` interface historically deferred this question — its
Javadoc said only that "the underlying native code must be thread-safe if this
is required." For the kernels the framework actually ships (the generated
`GeneratedOperationN` classes), the answer can be stated positively, provided
three separate concerns are kept distinct: **compiled-function reentrancy**,
**wrapper configuration state**, and **shared-buffer / metrics races**.

## What is actually true

### The compiled function is reentrant after configuration

The generated C function behind a `GeneratedOperationN.apply` carries **no
per-instance mutable state of its own**: it has no static scratch buffers, no
captured closures, and no invocation-specific mutable globals. (The generated
header does define a process-wide `M_PI_F` constant, initialized once to
`M_PI` and never written by a kernel.) Every *data* region it reads or
writes is a caller-supplied argument pointer passed in per invocation — the
only non-argument value it reads is that read-only `M_PI_F` constant, which
carries no invocation state (see the argument-marshalling contract in
`NativeInstructionSet` and
the value semantics in [CODEGEN_VALUE_SEMANTICS.md](CODEGEN_VALUE_SEMANTICS.md)).
Once the instruction set is configured and loaded, invoking its `apply` is
reentrant with respect to the compiled function itself.

### But the wrapper holds configuration state

The Java wrapper `BaseGeneratedOperation` holds mutable fields —
`context`, `metadata`, and `parallelism` — set through
`setComputeContext`, `setMetadata`, and `setParallelism`. These are
**configuration**, expected to be set once during setup (before the instruction
set is used) and then left alone. Reentrancy applies to `apply` **after**
configuration is complete; it does **not** license mutating these fields from
another thread while kernels are running. Treat configuration as a
happens-before setup phase, not as something to interleave with dispatch.

### And two shared-state concerns remain

- **Metrics counter (benign race).** `NativeInstructionSet.apply(long, long,
  MemoryData...)` does `NativeComputeContext.totalInvocations++` on a **static,
  non-atomic** field. Concurrent invocations can lose an increment. This is a
  diagnostics/telemetry counter only — it affects the reported invocation total,
  never the correctness of a kernel's output.
- **Shared output buffers (real hazard).** Reentrancy of the compiled function
  does **not** make it safe for two threads to invoke the same instruction set
  with **overlapping output `MemoryData`**. `MemoryData` documents that
  concurrent access is not thread-safe; two kernels writing the same backing
  region race regardless of how reentrant the function is. Correctness under
  concurrency is a property of *disjoint arguments*, not of the kernel.

## Summary

| Concern | Concurrent-safe? | Why |
|---|---|---|
| Compiled `apply` function body | Yes, after configuration | No per-instance mutable native state; args are per-call |
| `setComputeContext` / `setMetadata` / `setParallelism` | No | Configuration phase; not to be interleaved with dispatch |
| `NativeComputeContext.totalInvocations++` | Race, but benign | Non-atomic metrics counter; no effect on results |
| Two `apply` calls with overlapping output buffers | No | `MemoryData` is not safe under concurrent access |

## What cannot happen

- A generated kernel **cannot** corrupt another kernel's state through hidden
  shared native state, because it holds none — the only state shared between
  two invocations is whatever `MemoryData` the caller passes to both.
- A concurrent thread **cannot** null or wipe a pointer field inside a running
  kernel — there is no such field and no null to write. See
  [CODEGEN_VALUE_SEMANTICS.md](CODEGEN_VALUE_SEMANTICS.md).

## Debugging consequence

If concurrent invocations produce wrong (not crashing) results, suspect
**overlapping output buffers**, not a re-entrancy defect in the compiled
function. If the invocation *count* looks off under heavy concurrency, that is
the non-atomic metrics counter and is not a correctness problem. Do not attribute
a wrong value to "shared scratch state in the kernel."

## Related source

| Concern | Source identifier |
|---|---|
| Interface contract and dispatch | `org.almostrealism.hardware.jni.NativeInstructionSet` |
| Wrapper configuration fields | `org.almostrealism.generated.BaseGeneratedOperation` |
| Non-atomic invocation counter | `NativeComputeContext.totalInvocations` |
| Concurrent-access caveat | `org.almostrealism.hardware.MemoryData` |

## What would make these statements false

If generated kernels ever gain per-instance mutable native state (static scratch
buffers, persistent globals), or if `BaseGeneratedOperation`'s configuration is
made mutable during dispatch, the reentrancy guarantee above no longer holds and
this page must be revised.
