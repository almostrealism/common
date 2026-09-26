# Kernel and Memory Documentation Gaps

## Status

Four of the five gaps this plan catalogued (originally surfaced by a 2026-05-04
native-crash investigation) have been closed by
`docs/internals/native-runtime-lifecycle.md` and the accompanying Javadoc on
`NativeCompiler`, `BaseGeneratedOperation`, `NativeInstructionSet`, `Hardware`,
`KernelMemoryGuard`, and `MemoryData`:

1. **Generated kernel dylib lifetime** — covered (§1 of the internals doc):
   with the default `LinkedLibraryGenerator` and a single writer per library
   directory, libraries are overwritten before load, never reused across runs,
   and there is no delete-on-start. A custom caching generator, or concurrent
   JVMs sharing one directory (no inter-process lock, JVM-local name counter),
   can load stale or conflicting bytes; §1 states these conditions.
2. **Off-heap memory budget** — covered (§2): the `precision.bytes() * 2^N * 64MB`
   ceiling, which providers enforce it, and the `HardwareException` failure mode.
3. **Codegen value semantics** — covered (§5): numeric-only assignments, no
   pointer-null concept, and the crash-triage consequence.
4. **Kernel thread-safety / reentrancy** — covered (§4), per backend: JNI
   generated kernel instances are safe to invoke concurrently (and
   `NativeInstructionSet` now states this positively instead of deferring);
   OpenCL operators are thread-local with a `synchronized` `accept`, and Metal
   operators serialize concurrent calls on one instance.

Only the fifth, lower-priority gap remains open; it was explicitly out of scope
for the lifecycle documentation task and is retained here.

## Misunderstanding 5 (open): "ONNX-induced pressure" as a real category

An earlier investigation agreed that "ONNX-induced pressure" is a plausible
cause family for native instability under load, but the phrase is intuitive
rather than documented. There is no doc explaining what specifically gets
pressured, by what mechanism, when ONNX is loaded.

### Where documentation should live

- `extern/ml-onnx/...` (the module hosting the ONNX integration) — module-level
  docs describing:
  - Whether ONNX shares any allocator, threadpool, or memory provider with the
    AR compute kernels.
  - Whether ONNX inference and AR-generated kernels can run concurrently on the
    same worker thread (yes/no/conditions).
  - What "pressure" means concretely (CPU contention, allocator contention,
    memory bandwidth, thermals).

This is the lowest-priority gap; without it, a future investigator can still
reach the right diagnosis through evidence alone. It should be closed only if
the `extern/ml-onnx` source answers these questions directly; otherwise it is a
research task in its own right.
