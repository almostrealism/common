# Kernel and Memory Documentation Gaps

## Background

On 2026-05-04, an investigation into a Rings desktop crash
(NULL deref inside `Java_org_almostrealism_generated_GeneratedOperation9_apply`)
produced three substantively wrong hypotheses before the maintainer corrected
them. Each wrong turn stemmed from missing or misleading documentation about
how the AR framework actually behaves, not from misreading the evidence. This
document enumerates the gaps and proposes where documentation should live so
that a future investigator (human or LLM agent) reaches correct hypotheses
without burning iterations on ones that are mechanically impossible.

## Audience

Engineers and AI agents asked to debug a native crash whose Java-side stack
ends in a `GeneratedOperationN.apply` JNI call.

## Misunderstanding 1: Generated kernel dylib lifetime

### What was assumed
The dylibs at
`~/Library/Caches/<app>/liborg.almostrealism.generated.GeneratedOperationN.dylib`
persist across JVM restarts. A stale dylib left over from an older build
could be loaded by a newer Java class with a different argument layout,
producing a kernel that dereferences garbage. Recommended diagnostic:
"clear the kernel cache and rerun."

### What is actually true
These dylibs are destroyed and rebuilt every JVM start. There is no
cross-execution reuse. A "stale cache" cannot be the cause of a kernel-side
crash. Clearing the cache is a no-op as a diagnostic.

### Where documentation should live
- `common/base/hardware/src/main/java/org/almostrealism/hardware/jni/NativeCompiler.java`
  — class-level Javadoc covering the cache lifecycle: location, when entries
  are written, when (and by what mechanism) they are removed, and the
  guarantee that no entry survives JVM termination.
- `common/base/hardware/src/main/java/org/almostrealism/generated/BaseGeneratedOperation.java`
  — class-level Javadoc clarifying that the pre-allocated
  `GeneratedOperationN.java` source classes are reservation slots, not
  stored kernels. The compiled artifact behind each slot is generated fresh
  per JVM run.
- `common/docs/internals/KERNEL_CACHE.md` (new) — single-page reference:
  cache directory, key/identity scheme, the exact lifecycle hook that
  clears it, what is and is not preserved across runs.

### What the docs should specifically state
- The directory is purged (or otherwise invalidated) at JVM startup. State
  the precise mechanism — startup hook, lazy-clear-on-first-reservation,
  filesystem-level — so a debugger can verify the behavior.
- Investigators encountering a native crash inside `GeneratedOperationN.apply`
  can rule out "stale dylib from prior build."
- If/when this changes (e.g., we add cross-run caching for compile-time
  performance), the docs are the place to update so this hypothesis becomes
  valid again.

## Misunderstanding 2: Off-heap memory budget

### What was assumed
A "1024MB off-heap" figure surfaced in CLAUDE.md was treated as a
framework-enforced budget. Therefore a flood of concurrent feature jobs +
ONNX inference could exhaust 1024MB, and an internal allocator could return
zero pointers on exhaustion that propagate into kernels.

### What is actually true
- The actual off-heap budget is much larger than 1024MB.
- The framework does **not** enforce a max via automatic GC of off-heap data.
- JVM GC can release off-heap memory whose backing Java reference holder
  becomes unreachable (the holder's finalization or `Cleaner` releases the
  native block), but this is a per-object lifetime mechanism, not budget
  enforcement.
- "Allocator returns null on exhaustion" is not a documented behavior of
  this framework's allocators. Exhaustion is reported as allocation failure
  (exception) or as OS-level OOM, not as a silent zero-valued pointer.

### Where documentation should live
- `common/base/hardware/src/main/java/org/almostrealism/hardware/Hardware.java`
  (or wherever the configured off-heap RAM is read and used) — Javadoc
  describing what the configured number means in practice and what enforces
  it.
- `common/base/hardware/src/main/java/org/almostrealism/hardware/MemoryData.java`
  — class-level Javadoc describing the relationship between a Java
  reference and its underlying native memory, the lifetime contract
  (released when GC reclaims the holder), and the explicit statement that
  there is no separate "automatic GC by bytes used" budget.
- `common/docs/internals/OFF_HEAP_MEMORY.md` (new) — short reference:
  - Where the configured limit is read from
  - What enforces it (or that nothing does)
  - The actual failure mode under exhaustion (and how to recognize it in a
    crash report)
  - The relationship between Java GC and off-heap free
  - The role of `KernelMemoryGuard.acquireFor(data)` — what it does protect
    against, what it doesn't

### What the docs should specifically state
- The default and configurable off-heap RAM ceilings, with a pointer to
  where they are read.
- That hitting the ceiling does not silently corrupt pointers — name the
  actual failure modes.
- That a `MemoryData` whose holder is GC-eligible may have its native
  backing freed at any GC cycle; this is the actual race that an
  investigator should suspect when a kernel sees a stale pointer (the
  pointer is still numerically what it was, but the page underneath has
  been unmapped by the allocator).

## Misunderstanding 3: HPC kernel codegen and the absence of "null"

### What was assumed
A generated kernel could have shared mutable state where a pointer-typed
field is "wiped to null" mid-invocation by another thread.

### What is actually true
The AR HPC compute platform does **not** permit "null" to be used in
assignments. The codegen language has no syntax for clearing a pointer.
What the platform does allow is assigning a numeric value (e.g. `0.0`),
which is a value, not a pointer-erase operation. Pointer arguments enter a
kernel from outside (via the JNI boundary) and are not produced, modified,
or zeroed inside the kernel.

### Consequences for crash investigation
- A pointer dereference of `0x0` inside a generated kernel implies one of:
  1. A pointer argument was 0 at the JNI boundary (Java caller bug), or
  2. An arithmetic operation produced 0 (e.g., out-of-range offset wrapping
     or subtracting a base from itself).
- "The kernel zeroed a pointer internally" is not a possible failure mode
  and should not be entertained.
- This makes the crash signature actionable: focus first on what was passed
  in.

### Where documentation should live
- The HPC IR / codegen module (whichever class produces the assignment and
  expression nodes) — class-level Javadoc spelling out the value semantics:
  numeric values only, no null/nullable pointer concept, no `pointer = null`
  primitive.
- `common/docs/internals/CODEGEN_VALUE_SEMANTICS.md` (new) — page
  describing:
  - The grammar of allowed assignments
  - What is rejected at codegen time, with examples
  - The contract that pointer arguments are read-only references to memory
    owned outside the kernel
  - The implications for debugging crashes inside generated kernels

### What the docs should specifically state
- A short worked example: a Java-level expression that "looks like nulling
  a pointer" maps to either an exception at codegen time or to a numeric
  zero-write to a value field — and the difference is observable.
- The investigation playbook: when you see `far: 0x0` inside a kernel,
  start at the Java caller, not the kernel body.

## Misunderstanding 4 (lighter, related to #3): kernel thread-safety

### What was assumed
Even though the maintainer agrees that ONNX-induced pressure plausibly
contributes (their #5), the assistant initially also proposed "shared
scratch buffer in the generated kernel" as a candidate. This is partially
ruled out by #3 above, but the framework's actual position on per-kernel
state isn't documented anywhere obvious.

### What needs to be stated
- Whether a `NativeInstructionSet` instance carries any per-instance
  mutable state in its underlying compiled function (scratch buffers,
  static globals, captured closures).
- Whether a single instance's `apply()` is safe to invoke concurrently from
  multiple threads (the `NativeInstructionSet` interface Javadoc currently
  says "Multiple threads can invoke apply concurrently on the same
  instruction set. The underlying native code must be thread-safe if this
  is required" — this defers the question without answering it for the
  *generated* kernels we actually ship). What guarantees does AR's codegen
  make about reentrancy?

### Where
- `common/base/hardware/src/main/java/org/almostrealism/hardware/jni/NativeInstructionSet.java`
  — replace the deferral in the `Thread Safety` section with a positive
  statement about generated kernels, since those are the only
  implementations that exist in production.
- `common/docs/internals/KERNEL_THREAD_SAFETY.md` (new, optional) —
  consolidates the answer if it gets long.

## Misunderstanding 5 (also lighter): "ONNX-induced pressure" as a real category

The maintainer agreed that "ONNX-induced pressure" is a plausible cause
family. That phrase is intuitive but not documented. There is no doc
explaining what specifically gets pressured, by what mechanism, when ONNX
is loaded.

### Where
- `common/engine/ml/onnx/...` (whichever module hosts the ONNX integration)
  — module-level docs describing:
  - Whether ONNX shares any allocator, threadpool, or memory provider with
    AR compute kernels
  - Whether ONNX inference and AR-generated kernels can run concurrently on
    the same `pool-N-thread-X` (yes/no/conditions)
  - What "pressure" means concretely (CPU contention, allocator
    contention, memory bandwidth, thermals)

This is the lowest-priority gap; without it, a future investigator can
still get to the right diagnosis through evidence alone.

## Suggested ordering for the agent that picks this up

1. **Misunderstanding 3** (codegen value semantics) — the highest-leverage
   gap. Without this, an investigator from a typical Java/JVM background
   will keep proposing "null mutation inside kernel" hypotheses that are
   mechanically impossible.
2. **Misunderstanding 1** (kernel cache lifetime) — easy to state, removes
   one full hypothesis class.
3. **Misunderstanding 2** (off-heap budget) — requires more care because
   the docs need to distinguish four things that are easy to conflate
   (configured limit, actual enforcement, JVM-GC-driven free, exhaustion
   failure mode).
4. **Misunderstanding 4** (kernel thread-safety) — promote the existing
   deferral in `NativeInstructionSet.java` to a real answer for generated
   kernels.
5. **Misunderstanding 5** (ONNX pressure) — only if convenient.

## Out of scope for this plan

This document captures documentation gaps surfaced by one investigation. It
is not a survey of all undocumented framework behavior. The follow-on agent
should use the gaps above as concrete starting points; if writing those
docs reveals further adjacent gaps, capture those in their own follow-up
plan rather than expanding this one.
