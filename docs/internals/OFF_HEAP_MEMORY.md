# Off-Heap Memory Model

**Audience:** Engineers and AI agents debugging a native crash or an
out-of-memory condition at the Java↔native boundary who need to reason about
how off-heap memory is bounded, enforced, and freed.

This page exists to separate four things that are easy to conflate. Conflating
them produces the specific wrong hypotheses that this documentation was written
to prevent — chiefly "the off-heap budget was exhausted and the allocator
returned a null pointer that propagated into a kernel." That does not happen;
see [What cannot happen](#what-cannot-happen).

## Four distinct concepts

### 1. The configured limits (there are two, not one)

Two independent environment settings feed the memory model; do not treat either
as "the off-heap budget."

- **`AR_HARDWARE_MEMORY_SCALE`** (default `4`) sets the maximum *reservation*.
  `Hardware` reads it into `MEMORY_SCALE` and computes
  `maxReservation = 2^MEMORY_SCALE * 64,000,000` **elements**. Each backend
  turns that into a byte ceiling: `memoryMax = precision.bytes() * maxReservation`
  (see `NativeMemoryProvider`, `CLMemoryProvider`, `MetalMemoryProvider`, and
  the `HardwareDataContext.getMaxReservation()` plumbing).
- **`AR_HARDWARE_OFF_HEAP_SIZE`** (default `Hardware.DEFAULT_OFF_HEAP_SIZE`,
  which is `0`) is a *separate* value read by `Hardware.getOffHeapSize()` and
  passed to the CL and Metal data contexts (`CLDataContext`, `MetalDataContext`)
  as `offHeapSize`: a *provider-selection threshold*. Allocations smaller than
  it are served by the alternate JVM-heap provider; larger ones go to the main
  backend provider. It is **not** a buffer size, **not** the allocation ceiling,
  and **not** derived from the memory scale.

A note on the historical "1024MB off-heap" figure: it is neither of these. The
actual reservation ceiling is `precision.bytes() * 2^scale * 64MB`, which at the
default scale is multiple gigabytes — far larger than 1024MB — and it is a
*reservation* limit, not a fixed off-heap arena.

### 2. What actually enforces the limit

The per-backend `MemoryProvider` enforces `memoryMax` at allocation time. Each
provider's `allocate`/`buffer` method performs an explicit check before
allocating:

```java
if (memoryUsed + sizeOf > memoryMax) {
    throw new HardwareException("Memory max reached");
}
```

(`NativeMemoryProvider.allocate`, `MetalMemoryProvider.buffer`,
`CLMemoryProvider.allocate`.) There is **no** background "automatic GC by bytes
used" that watches a running total and frees off-heap data to stay under a
budget. Nothing sweeps memory to make room; an allocation that would exceed the
ceiling is simply rejected.

### 3. How native memory is actually freed

Off-heap blocks are freed by two mechanisms, neither of which is
`Cleaner`/finalization:

- **Phantom-reference reclamation.** `HardwareMemoryProvider` registers each
  allocation as a `NativeRef` (a `java.lang.ref.PhantomReference`) with a
  `ReferenceQueue`. When the Java `RAM` holder becomes unreachable and is
  collected, the reference is enqueued; two background daemon threads (a submit
  thread that drains the queue and a process thread that frees largest-first)
  perform the actual native deallocation. This is a **per-object lifetime
  mechanism**, not budget enforcement — it releases a block because *its holder
  died*, not because *total usage is high*.
- **Explicit deallocation.** A caller (or the provider's own destroy path) may
  call `deallocate` directly; direct-buffer-backed allocations additionally
  rely on the JVM's own direct-buffer reclamation.

Both paths decrement the provider's `memoryUsed`, which is what frees headroom
for future allocations.

### 4. The real exhaustion failure mode

When the ceiling is hit, the provider throws
`HardwareException("Memory max reached")`. That is the signal to recognize in a
crash report — an exception on the allocation path, or an OS-level OOM if the
process outgrows physical/committed memory. Reservation exhaustion **never** surfaces as a
silently returned zero/null pointer that later gets dereferenced inside a
kernel. (A distinct, OS-level failure is not covered by this check: in calloc
mode `NativeMemoryProvider` returns the `Malloc` result without testing it for
zero, so a genuine `calloc` failure could yield a `0` address.)

## The race an investigator should actually suspect

The genuinely dangerous condition is **not** budget exhaustion; it is
**use-after-free**: a pointer that is still *numerically* what it was, but whose
backing page has been unmapped because its `RAM` holder became GC-eligible and
the phantom-reference threads freed it — potentially *while a kernel that
captured the raw pointer is still running*.

The framework defends this in two places:

- **`KernelMemoryGuard`** ref-counts the native addresses a kernel is actively
  using. `KernelMemoryGuard.acquireFor(data)` (called around dispatch) holds a
  strong reference to each argument's `RAM` and increments a per-address count;
  `releaseFor` decrements it. `HardwareMemoryProvider` consults
  `canDeallocate(address)` and holds back a free while a kernel is still using
  the block. What it protects: a block whose holder went unreachable *mid-kernel*
  is not freed until the kernel releases it. What it does **not** protect: an
  argument it cannot resolve to a `RAM` (it warns and proceeds unguarded), or a
  pointer already captured as a bare `long` outside the guard's view.
- **Pre-dispatch pointer validation.** `NativeInstructionSet.apply` re-reads and
  checks every `getContentPointer()` immediately before the native call, turning
  a would-be `SIGSEGV` into a named `NullPointerException` if a block was
  already unmapped by dispatch time.

## What cannot happen

- The framework's allocators **cannot** return a silent zero/null pointer on
  *reservation* exhaustion; that throws `HardwareException`. (An OS-level
  `calloc` failure in calloc mode is not checked — see above.)
- There is **no** "automatic GC by bytes used" budget that frees live off-heap
  data to stay under a limit. Freeing is driven by holder reachability and
  explicit deallocation only.
- A `0x0` dereferenced inside a kernel is therefore **not** evidence of budget
  exhaustion. See [CODEGEN_VALUE_SEMANTICS.md](CODEGEN_VALUE_SEMANTICS.md) for
  the two ways a zero can actually reach a kernel.

## Debugging consequence

- On `HardwareException: Memory max reached`, raise `AR_HARDWARE_MEMORY_SCALE`
  (it is exponential — increase by 1–2, not to a huge value) or reduce live
  allocation; this is a *reservation-ceiling* condition, not a leak by itself.
- On a `0x0`/`SIGSEGV` inside a kernel, suspect **use-after-free** (a holder
  freed while its pointer was in flight), not exhaustion. Check whether an
  argument's `RAM` was destroyed before dispatch, and whether the dispatch was
  bracketed by `KernelMemoryGuard`.

## Related source

| Concern | Source identifier |
|---|---|
| Reservation ceiling from scale | `Hardware` (`MEMORY_SCALE`, `maxReservation`, `getMemoryScale()`) |
| CL/Metal JVM-heap provider threshold | `Hardware.getOffHeapSize`, `Hardware.DEFAULT_OFF_HEAP_SIZE` |
| Per-backend enforcement | `NativeMemoryProvider.allocate`, `CLMemoryProvider`, `MetalMemoryProvider` |
| Phantom-reference free path | `org.almostrealism.hardware.mem.HardwareMemoryProvider`, `NativeRef` |
| Free-while-kernel-running guard | `org.almostrealism.hardware.mem.KernelMemoryGuard` |
| Pre-dispatch pointer validation | `NativeInstructionSet.apply(long, RAM[], int[], int[], int, long)` |

## What would make these statements false

If a byte-budget-driven eviction mechanism, or an allocator that returns a
sentinel pointer on exhaustion, is ever introduced, update this page — the
"exhaustion is always an exception, never a null pointer" invariant would no
longer hold.
