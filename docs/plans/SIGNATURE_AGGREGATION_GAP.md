# Signature-Based Compiled-Program Reuse vs. Argument Aggregation

**Status:** open investigation (2026-06-05). This note captures a discussion so we can pick it
up later. See also [INSTRUCTION_CACHING.md](../../base/hardware/docs/INSTRUCTION_CACHING.md).

## The problem

The instruction-set cache reuses a compiled native kernel across structurally-identical
computations by keying on a structural **signature** (see `INSTRUCTION_CACHING.md`). When the
signature is `null`, caching is disabled and the kernel is recompiled — consuming a fresh
`GeneratedOperation` slot every time (the pool is a fixed, monotonically-consumed set of
pre-generated classes; see `NativeCompiler.reserveLibraryTarget`).

Measured: building the *same* computation twice in two instances does **not** reuse the compiled
program.

- `cp(a).add(cp(b))` → `signature=null`, and the second build compiles a fresh kernel
  (`getTotalInstructionSets` delta `first=1, second=1`).
- A full `AudioScene` render built twice with the **same** genome → `first=44, second=43` native
  programs (essentially no reuse).

This is the root cause behind the `test-media-mac` native-class exhaustion: the legacy audio
pipeline rebuilds scenes whose kernels never cache, so cumulative compilations climb past the
pool size.

## Why the signature is null — verified mechanism

1. `cp(x)` = `c(p(x))`. For a `PackedCollection` (a `Shape`), `p(x)` returns a
   `CollectionProviderProducer` (a by-reference provider), wrapped by `c()` in a
   `ReshapeProducer`.
2. `CollectionProviderProducer.signature()`
   (`compute/algebra/.../computations/CollectionProviderProducer.java`):
   - **returns `null` when the referenced buffer is an argument-aggregation target**
     (`MemoryDataArgumentMap.isAggregationTarget`). The code comment admits this is a known
     shortfall: *"It should actually be possible to compute a valid signature for this anyway,
     but because argument aggregation for Computations depends on the other Computation
     arguments, it requires more information than is available here."*
   - otherwise returns `offset + ":" + memLength + "|" + shapeDetail` (note: this embeds the
     buffer's offset/length — a separate, lesser reuse problem; see INSTRUCTION_CACHING.md).
3. `ReshapeProducer.signature()` forwards the leaf signature; `ProducerComputationBase.signature()`
   nulls the **entire** enclosing computation if any input signature is null. So one
   aggregation-target leaf nulls the whole graph's signature.

### What makes a buffer an aggregation target

`MemoryDataArgumentMap.isAggregationTarget(md)` is true when:

- argument aggregation is enabled (`AR_HARDWARE_ARGUMENT_AGGREGATION`, default **true**), **and**
- `md.getMemLength() <= maxAggregateLength` (`AR_HARDWARE_AGGREGATE_MAX`, default **1 MB** — so
  essentially every small buffer), **and**
- the buffer lives in **JVM memory** (`JVMMemoryProvider`), unless off-heap aggregation is
  enabled (`AR_HARDWARE_OFF_HEAP_AGGREGATION`, default false).

So: **JVM-memory buffers always become aggregation targets** (as long as aggregation is on).

### What puts a buffer in JVM memory

`DataContext.getMemoryProvider(int size)` (e.g. `MetalDataContext.getMemoryProvider`):

```java
return size < offHeapSize ? getAltMemoryProvider() /* JVM heap */ : getMemoryProvider() /* device */;
```

`offHeapSize` defaults to **1024** (`AR_HARDWARE_OFF_HEAP_SIZE`; documented as bytes — unit to be
re-confirmed against the call site). So **small allocations go to JVM memory → become aggregation
targets → null signature → never cache.**

## Why we should NOT just compute signatures for aggregated inputs

Aggregation packs several small buffers into one consolidated kernel argument. The position an
input lands at depends on the *other* inputs being aggregated: `a + b + c` might bind
`a=aggregate[0], b=aggregate[1], c=aggregate[2]`, or a different order, or different indices
entirely if unrelated aggregated values are present elsewhere. That variation makes a stable,
structural signature for an aggregated input a fool's errand. Better to **avoid aggregating the
inputs we want to reuse**, not to try to sign them.

## Proposed directions (to investigate)

### (1) Lower the JVM/off-heap threshold so reusable inputs aren't aggregated

If the buffers that break signatures are reasonably sized, we could lower `offHeapSize` so they
land in accelerator memory instead of JVM memory, avoiding aggregation (and the null signature).

**The deciding question:** *when a signature is given up because the arguments are aggregation
targets, how large are those arguments?*

- If they are **1–2 elements** (scalars), we're stuck: to keep them out of JVM/aggregation we'd
  have to store lots of tiny collections in accelerator memory, which we specifically want to
  avoid.
- If they are **larger (e.g. 16+)**, we can simply set `offHeapSize` low enough that they go to
  accelerator memory and become signable.

**Constraint:** we do *not* want to store lots of very small collections in accelerator memory.

→ **Action:** instrument the null-signature branch to record the size distribution of the
aggregation-target buffers during a real `AudioScene` render, then decide whether a lower
threshold is viable.

### (2) Activate aggregation only when we would otherwise exceed the kernel argument limit

Aggregation exists largely because compute contexts cap kernel arguments (Metal allows ~30
total). If we only aggregate when a scope would otherwise exceed, say, **24** arguments, most
computations would keep un-aggregated (signable) inputs.

**Difficulty:** the final argument count is hard to know until the `Scope` is fully formed and
rendered to kernel code. We'd need a cheap, conservative *estimate* of argument count at compile
time to gate aggregation dynamically.

→ **Action:** look for an inexpensive way to estimate the eventual argument count of a `Scope`
before deciding to aggregate.

## Measurement (2026-06-05)

Instrumented the null-signature branch to histogram the `memLength` of aggregation-target
buffers during a 2-measure single-channel `AudioScene` render (`CompileReuseDiagnosticTest`):

```
memLength => count : { 1: 13684, 2: 4444, 41: 1744, 128: 88 }
total = 19960    size<=2 = 18128  (91%)
```

**Conclusion: direction (1) is not viable on its own.** The buffers that break signatures are
overwhelmingly **scalars (size 1–2)** — gene values, per-tick automation, coefficients, etc. To
keep size-1 (8-byte FP64) scalars out of JVM memory / aggregation, `offHeapSize` would have to be
≤ 8 bytes, i.e. push essentially *everything* into accelerator memory — exactly the
"lots of tiny collections in accelerator memory" outcome we want to avoid. The larger buckets
(41, 128) are a small minority.

So the legacy Cell pipeline's heavy use of tiny per-value JVM collections is fundamentally
incompatible with the threshold approach. Remaining options:

- **(2) dynamic aggregation gating** (only aggregate when the argument limit would be exceeded) —
  would let these scalars stay un-aggregated and signable in the common case. Needs a cheap
  compile-time argument-count estimate.
- **PDSL alignment:** PDSL passes values as producer arguments and tends toward fewer, larger
  collections, so it should not generate the 18k+ scalar aggregation targets the Cell path does.
  Worth confirming empirically that an equivalent PDSL render caches.

## Next steps

1. ~~Measure aggregation-target buffer sizes~~ — done (above); threshold approach (1) ruled out.
2. Prototype direction (2): a conservative compile-time estimate of a `Scope`'s eventual argument
   count, used to gate aggregation (only aggregate when approaching the ~24/30 limit).
3. Confirm a PDSL render does not produce the scalar-aggregation-target explosion (build the same
   PDSL model twice; expect the second build to reuse).

## Relevant code

- `compute/algebra/.../computations/CollectionProviderProducer.java` — leaf `signature()`.
- `base/hardware/.../mem/MemoryDataArgumentMap.java` — `isAggregationTarget`, `maxAggregateLength`.
- `base/hardware/.../metal/MetalDataContext.java`, `.../cl/CLDataContext.java` — `getMemoryProvider(size)`, `offHeapSize`.
- `base/hardware/.../Hardware.java` — `getOffHeapSize` (`AR_HARDWARE_OFF_HEAP_SIZE`, default 1024).
- `base/hardware/.../jni/NativeCompiler.java` — `reserveLibraryTarget`, `getTotalInstructionSets`.
