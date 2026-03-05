# COMPUTE_CONTEXT_CHOICE: Hardware Routing and Operation Isolation

## Decision Journal — MANDATORY

**You MUST document your reasoning in
[../journals/AUDIO_SCENE_LOOP.md](../journals/AUDIO_SCENE_LOOP.md).**

---

## Problem Statement

Profiled back-to-back testing of `AR_HARDWARE_DRIVER=*` vs `native` on the
AudioScene pipeline revealed that GPU dispatch overhead on small collection
operations (collectionProduct, collectionZeros) **completely offsets** GPU
gains on larger operations (multiOrderFilter convolution):

| Operation | Native (941a7cf8) | * Driver (3803d277) | Change |
|-----------|-------------------|---------------------|--------|
| multiOrderFilter | 9.2ms/tick | 1.2ms/tick | **-87%** |
| collectionProduct | 0.7ms/tick | 5.5ms/tick | **+686%** |
| collectionZeros | 0.6ms/tick | 4.4ms/tick | **+627%** |
| **Avg buffer time** | **143.33ms** | **137.46ms** | **-4%** |

Two distinct problems emerge:

1. **Isolation problem**: Why are operations like `collectionZeros` (which
   literally writes `0.0`) running as independent compiled JNI kernels at
   all? This is 1056 separate kernel invocations per buffer tick for scalar
   zero-writes. The question is not "which hardware should run this" — the
   question is "should this even be a compiled operation?"

2. **Routing problem**: When operations *do* need to be compiled, how does
   the system decide which hardware backend to use, and can that decision
   be improved to avoid sending small operations to GPU?

These are related but distinct: fixing (1) eliminates most of the overhead
regardless of (2), while fixing (2) is necessary for operations that
genuinely need to be compiled but are too small for GPU dispatch overhead.

---

## Concern 1: Unnecessary Isolation of Trivial Operations

### Root Cause Analysis

The 1056 `collectionZeros` invocations per tick come from
`CachedStateCell.tick()` (`graph/.../CachedStateCell.java:206–227`):

```java
@Override
public Supplier<Runnable> tick() {
    OperationList tick = new OperationList("CachedStateCell Tick");
    // ... copy cached value to output ...
    tick.add(reset(p(cachedValue)));  // ← 1056 of these
    return tick;
}
```

Where `reset()` in `CollectionCachedStateCell` (`graph/.../CollectionCachedStateCell.java:37–39`):

```java
@Override
public Supplier<Runnable> reset(Producer<PackedCollection> out) {
    return a(1, out, c(0));
}
```

This creates `new Assignment(1, out, c(0))` — a separate `Supplier<Runnable>`
per cell per tick. The AudioScene has ~88 `CachedStateCell` instances × 6
channels × 2 stereo sides = **1056 independent Assignment operations** that
each write a single `0.0` to a 1-element buffer.

These are NOT isolated by the `IsolatedProcess` system —
`CollectionZerosComputation.isolate()` explicitly throws
`UnsupportedOperationException`. They are independent because
`CachedStateCell.tick()` adds them as separate entries in an `OperationList`.
The `OperationList` has no cross-statement optimization that would recognize
"zeroing a buffer immediately before an unconditional overwrite" as redundant.

### Why This Matters

Each `Assignment(1, out, c(0))` compiles to a separate native kernel via
`Assignment.get() → super.get()` when the destination is not JVMMemory. This
means 1056 JNI calls per tick, each doing:
1. JNI transition (~1μs)
2. Load a single memory address
3. Write 0.0
4. JNI return

Total: ~1056 × 4μs ≈ **4.2ms/tick** on native, ~**4.4ms/tick** with `*`
driver (the slight increase is GPU dispatch probing overhead even for
operations that ultimately run on CPU).

The `collectionProduct` situation is similar: 1056 invocations per tick of
small scalar multiplications that are separate compiled kernels.

### Investigation Required

Before proposing solutions, the following must be understood:

#### Q1: Can `reset()` be eliminated entirely?

The `SummationCell` accumulator pattern is: accumulate via `push()` during
the tick, then at the end: copy `cachedValue → outValue`, reset
`cachedValue = 0`. The reset is necessary because `push()` does
read-modify-write: `cachedValue += protein`.

**But**: if the first `push()` of the next tick used `=` instead of `+=`
(assignment instead of accumulation), the reset would be unnecessary. This
would require knowing which `push()` is "first" — not trivial in the current
architecture where multiple upstream cells push independently.

**Alternative**: Could the accumulator use a double-buffer pattern? Swap
`cachedValue` and `outValue` pointers instead of copy+reset. This eliminates
both the copy and the zero-write. The "stale data" problem is avoided because
the old `outValue` (now `cachedValue`) will be completely overwritten by
the accumulation of the new tick's pushes. **However**, this only works if
every tick's pushes completely define the value — if some ticks have no
pushes, the accumulator needs to be zero. Study whether this invariant holds
in the AudioScene pipeline.

#### Q2: Can `Assignment(1, out, c(0))` short-circuit without compilation?

`Assignment.get()` already has a JVMMemory short-circuit path (line 361–368):
```java
if (d.getMem() instanceof JVMMemory) {
    double v = s.getAsDouble();
    int len = getCount() * memLength;
    return () -> IntStream.range(0, len).parallel().forEach(i -> d.setMem(i, v));
}
```

This doesn't fire on hardware-backed memory. Could a similar short-circuit
be added for `MetalMemoryProvider` / shared memory? On Apple Silicon with
unified memory, `d.setMem(0, 0.0)` writes directly to the same physical
memory the GPU uses. The question is whether the `setMem` path works
correctly for metal-backed `PackedCollection` without violating memory
coherence guarantees.

#### Q3: Can the OperationList fuse trivial operations?

The current compilation path treats each `OperationList` entry as an
independent compiled operation. If `OperationList` had a fusion pass that
recognized `Assignment(1, out, c(0))` entries and batched them into a single
multi-destination zero-fill kernel, the 1056 JNI calls could collapse to 1.

This intersects with Goal 3 of the AudioScene plan (kernel fusion). The
constraint is that `OperationList.enableAutomaticOptimization` and
`enableSegmenting` must NOT be changed globally. Any fusion would need to be
targeted.

#### Q4: What about `collectionProduct`?

The 1056 `collectionProduct` invocations need the same analysis. Are these
also scalar operations from the cell graph? If so, the same elimination or
batching strategies apply. If they involve actual array data, the answer is
different.

### Approach Options (Prioritized)

**Option A: Eliminate the zero-write entirely** (highest impact, most complex)

Restructure `CachedStateCell` to use a swap-based double-buffer instead of
copy+reset. This eliminates both the copy kernel and the zero kernel per
cell per tick (saving 2112 kernel invocations). Requires proving that the
accumulator invariant (all values overwritten each tick) holds.

**Option B: JVM-side short-circuit for scalar constant assignments** (moderate
impact, safe)

Extend `Assignment.get()` to handle hardware-backed memory for scalar constant
writes. For `count == 1` and `memLength == 1` with a constant source, perform
`d.setMem(0, 0.0)` directly from Java without compiling a native kernel. This
avoids JNI overhead entirely. Risk: memory coherence with GPU. On Apple
Silicon unified memory this should be safe; verify with other architectures.

**Option C: Batch trivial operations in OperationList** (moderate impact,
broader applicability)

Add a targeted optimization pass to `OperationList` that collects all
`Assignment(1, out, c(constant))` entries and replaces them with a single
batch kernel. This is useful beyond the AudioScene pipeline — any cell graph
would benefit.

---

## Concern 2: Hardware Driver Routing for Compiled Operations

### Current Routing Logic

`DefaultComputer.getContext()` (`hardware/.../DefaultComputer.java:462–484`):

```java
long count = Countable.countLong(c);
boolean fixed = Countable.isFixedCount(c);
boolean sequential = fixed && count == 1;
boolean accelerator = !fixed || count > 128;
```

Decision:
- `count == 1 && fixed` → `sequential = true` → prefers CPU
- `count > 128 || !fixed` → `accelerator = true` → prefers GPU (Metal first, then CL)
- `count` in [2, 128] and `fixed` → neither sequential nor accelerator → falls through to first available context

`Hardware.getDataContext()` (`hardware/.../Hardware.java:1126–1177`):
- If `accelerator`: Metal → CL → fallback
- If `sequential`: JNI/Native → fallback
- Otherwise: first available (which is JNI on macOS with `*` driver)

### Why Small Operations Get GPU Dispatch Overhead

Even though `collectionZeros` and `collectionProduct` have `count == 1`, the
profile shows them being dramatically slower with `*` driver. There are
several possible explanations:

1. **The `count` used by `DefaultComputer.getContext()` may not be `1`** for
   these operations. If the `Assignment` wrapping doesn't properly delegate
   `Countable.countLong()`, the count could be `!fixed` (unknown), triggering
   `accelerator = true`.

2. **Shared memory overhead**: With `AR_HARDWARE_DRIVER=*`, `Hardware[JNI]`
   enables shared memory via `MetalMemoryProvider`. Even operations running
   on CPU may pay overhead for memory synchronization with the GPU memory
   subsystem.

3. **Context selection overhead**: The routing decision itself (calling
   `getContext()` 1056 times per tick) adds overhead compared to the native-
   only path where there's only one context.

### Investigation Required

#### Q5: What `count` do collectionZeros and collectionProduct report?

Add logging or instrumentation to `DefaultComputer.getContext()` to verify
what `Countable.countLong()` returns for these operations. If it returns
`!fixed` or a count > 1, the GPU routing is being triggered incorrectly.

#### Q6: Does MetalMemoryProvider shared memory add overhead to JNI operations?

The `*` driver enables `MetalMemoryProvider` for JNI's shared memory. This
may change the memory allocation path or add synchronization overhead for
ALL operations, not just GPU ones. Compare allocation and access patterns
between `native` and `*` driver for JNI-routed operations.

#### Q7: Is the routing threshold (128) appropriate?

The current threshold sends anything with `count > 128` to GPU. The profile
shows multiOrderFilter (with 41-tap FIR convolution, likely `count` in the
thousands) benefits enormously from GPU, but there may be operations in the
[129, 1000] range that have enough GPU dispatch overhead to offset parallelism
gains.

### Approach Options (Prioritized)

**Option D: Verify and fix count reporting** (highest priority)

If `Countable.countLong()` returns incorrect values for `Assignment` or
small computations, fixing this is the simplest and most impactful change.
It would ensure the existing routing logic works as designed.

**Option E: Cost-aware routing threshold**

Replace the fixed `count > 128` threshold with a cost model that considers:
- Operation compute cost (from the Expression complexity system)
- Estimated GPU dispatch overhead (~100μs per kernel launch)
- Data transfer size
- Whether the operation is part of a batch (amortized dispatch)

This is analogous to the Expression compute cost system — teaching the
routing layer what operations actually cost on each backend, rather than
using a one-dimensional proxy (count).

**Option F: Per-OperationList routing context**

Currently, `DefaultComputer.pushRequirements()` sets a thread-local
requirement stack. An `OperationList` could analyze its children and set
requirements intelligently: "all these 1056 Assignment operations should
use JNI; these 17 convolution kernels should use Metal." This gives the
compilation system a chance to make batch routing decisions rather than
per-operation decisions.

---

## Recommended Investigation Order

1. **Q4**: Identify what `collectionProduct` actually does in the AudioScene.
   If it's also a trivial scalar operation, Concern 1 solutions apply to both.

2. **Q1/Q2**: Determine whether zero-writes can be eliminated or
   short-circuited. This has the highest impact (eliminates 1056+ kernel
   invocations entirely).

3. **Q5**: Verify what `count` these operations report. If routing is wrong,
   fix it first (Option D) — this is the cheapest fix with broadest impact.

4. **Q6**: Understand MetalMemoryProvider shared memory overhead. This
   affects all operations under `*` driver.

5. Only after (1)–(4) are answered: design the routing improvement
   (Options E/F) based on empirical understanding of where the overhead
   actually comes from.

---

## Key Source Files

| File | Purpose |
|------|---------|
| `graph/.../CachedStateCell.java` | `tick()` adds `reset()` per cell (line 206–227) |
| `graph/.../CollectionCachedStateCell.java` | `reset()` = `a(1, out, c(0))` (line 37–39) |
| `graph/.../SummationCell.java` | `push()` = read-modify-write accumulation |
| `hardware/.../Assignment.java` | `get()` compilation path (line 329–397) |
| `hardware/.../DefaultComputer.java` | `getContext()` routing (line 462–484) |
| `hardware/.../Hardware.java` | Driver init and context selection (line 502–560, 1126–1177) |
| `code/.../ComputableParallelProcess.java` | `isIsolationTarget()` (line 86–106) |
| `code/.../ComputeRequirement.java` | Backend enum |

## Related Documents

- [AUDIO_SCENE_LOOP.md](AUDIO_SCENE_LOOP.md) — AudioScene performance plan (Goals 3, 5)
- [AUDIO_PERFORMANCE.md](AUDIO_PERFORMANCE.md) — Full performance analysis
- [Decision Journal](../journals/AUDIO_SCENE_LOOP.md) — MANDATORY
