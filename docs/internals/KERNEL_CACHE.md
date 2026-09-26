# On-Disk Generated-Kernel Cache

**Audience:** Engineers and AI agents debugging a native crash inside a
`GeneratedOperationN.apply` JNI call who are tempted to blame "a stale kernel
from a previous build."

This page documents the **on-disk** artifacts produced by native compilation:
the generated `.c` source files and the compiled shared libraries
(`.so`/`.dylib`) that back each `GeneratedOperationN` class. It is deliberately
distinct from the **in-JVM signature cache** documented in
[INSTRUCTION_CACHING.md](../../base/hardware/docs/INSTRUCTION_CACHING.md); see
[Two distinct caches](#two-distinct-caches) below.

## What is actually true

Native compilation writes files into a library directory and loads them via
`System.load`. The relevant behavior of `NativeCompiler` is:

- **Directory.** `NativeCompiler.factory()` resolves the library directory from
  `AR_HARDWARE_LIBS` if set, otherwise from `SystemUtils.getExtensionsPath()`.
  The directory is created if it does not exist; it is **not** emptied or
  recreated. Files written on a previous run are still present on disk when a
  new JVM starts.
- **Filenames are derived from the class name, not from content.** For a target
  class `GeneratedOperationN`, `getInputFile` writes `GeneratedOperationN.c` and
  `getOutputFile` writes the library named by `AR_HARDWARE_LIB_FORMAT`
  (`libGeneratedOperationN.dylib` / `.so`). Compiling the same class name again
  **overwrites** those files in place.
- **Compilation always precedes load.** `compileAndLoad` calls `compile(...)`
  — which writes the `.c` and invokes the toolchain to produce the library —
  and only then calls `System.load(name)`. There is no path that loads a
  library without first (re)compiling it in the current run.
- **`destroy()` is a no-op.** `NativeCompiler.destroy()` does not delete
  anything; loaded libraries remain mapped for the life of the JVM
  (`NativeInstructionSet.destroy()` is likewise a no-op).

### Why "stale dylib" is still not a valid hypothesis

Even though the files persist on disk, a prior run's compiled artifact is never
executed by a later run without being regenerated first, because of how target
names are handed out. The counter behind `reserveLibraryTarget()`
(`NativeCompiler`'s static `runnableCount`) **starts at 0 in every JVM** and
counts up as targets are reserved. Reserving target *N* loads
`org.almostrealism.generated.GeneratedOperationN`; the very next step compiles
fresh C into `GeneratedOperationN.c` and rebuilds
`libGeneratedOperationN.<ext>`, **overwriting** whatever a previous run left at
that path, and only then loads it.

So the guarantee an investigator needs is:

> The compiled artifact loaded for `GeneratedOperationN` in a given run is
> **always the one this run just generated** — never a leftover from an earlier
> build. The stale file on disk is overwritten before it can be loaded.

The mechanism is **overwrite-before-load**, not a startup purge. (An earlier
framing claimed the directory is "purged at JVM startup"; that is not what the
code does — no purge or invalidation hook exists. The correct and sufficient
invariant is that each run recompiles into the same name before loading it.)

## Two distinct caches

There are two separate things called a "cache" at this boundary; conflating
them produces wrong hypotheses.

| | On-disk kernel artifacts (this page) | In-JVM signature cache ([INSTRUCTION_CACHING.md](../../base/hardware/docs/INSTRUCTION_CACHING.md)) |
|---|---|---|
| What it stores | `.c` sources and compiled `.so`/`.dylib` files | Compiled `InstructionSet` instances keyed by structural signature |
| Where it lives | The library directory on disk | JVM heap (`InstructionSetManager`, `FrequencyCache`) |
| Lifetime | Files persist on disk; overwritten by name each run | Lives and dies with the JVM; evicted per cache policy |
| Cross-run reuse | None in effect — recompiled before load | None — heap-only |
| Purpose | Hold the JNI-loadable native library for a target | Avoid recompiling structurally identical scopes within one run |

The in-JVM cache decides whether a scope must be compiled *again within this
run*. The on-disk artifacts are where a compilation, once it happens, lands.
Neither survives as an *executable* into a later run's dispatch.

## What cannot happen

- A native crash in `GeneratedOperationN.apply` **cannot** be caused by "a
  stale dylib from a prior build with a different argument layout." The library
  loaded this run was compiled this run from this run's `Scope`.
- Clearing/deleting the library directory **cannot** fix a kernel-side crash as
  a diagnostic — the files are regenerated on the next run regardless.

## Debugging consequence

If the Java stack ends in `GeneratedOperationN.apply` and the crash is a bad
dereference, do **not** "clear the kernel cache and rerun." Instead, treat the
generated source as current and read it directly with `ar-profile-analyzer`
(`get_source`) to see the actual argument bindings and offsets, and follow the
`0x0` playbook in [CODEGEN_VALUE_SEMANTICS.md](CODEGEN_VALUE_SEMANTICS.md):
start from what the Java caller passed in.

## Related source

| Concern | Source identifier |
|---|---|
| Directory resolution, overwrite, no-op destroy | `org.almostrealism.hardware.jni.NativeCompiler` |
| Per-run target index reset | `NativeCompiler.reserveLibraryTarget()` / static `runnableCount` |
| Reservation slots vs. stored kernels | `org.almostrealism.generated.BaseGeneratedOperation` |
| Library stays loaded; no-op destroy | `NativeInstructionSet.destroy()` |

## What would make these statements false

If cross-run caching is ever added (e.g. skip recompilation when a matching
`.so`/`.dylib` already exists on disk), the overwrite-before-load guarantee no
longer holds and "stale dylib from a prior build" becomes a valid hypothesis
again. Update this page at that point.
