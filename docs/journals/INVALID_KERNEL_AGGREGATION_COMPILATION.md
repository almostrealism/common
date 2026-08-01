# How the Invalid Kernel Was Compiled

Companion to `INVALID_KERNEL_AGGREGATION.md`. Explains how the LayerNorm kernel
`f_collectionAddComputation_3771` (compile B) came to read `gamma`/`beta` at the baked offsets
`+288`/`+320` inside the input buffer, and why a second compile of the identical graph
(`f_collectionAddComputation_2678`, compile A) reads them from a separate buffer instead.

## What is verified directly from the kernel source

- Both kernels evaluate the same expression for the LayerNorm `Computation` `C` (see the other file).
- In compile B the operands `x`, `gamma`, `beta` are all read from one buffer `_3754_v2040`: `x` at
  base offset, `gamma` at `base + 288`, `beta` at `base + 320`. The buffer's base (`o1 = offsetArr[1]`)
  is a runtime argument; the `+288`/`+320` are **compile-time integer literals** in the generated
  expression. The kernel reads the true element count into `_3754_v2040Size = sizeArr[1]` and never
  uses it.
- In compile A the operands `x` (`_2661_v1197`) and `gamma`/`beta` (`_2675_v1213`, `gamma` at `+0`,
  `beta` at `+32`) are in separate buffers.

So the two compiles bake **different operand offsets** into the kernel for the same `Computation`.

## Where the baked offset comes from — argument aggregation

The baked `+288` is an **aggregate position**. The path:

1. When the LayerNorm `Computation` generates its `Scope`, each operand is resolved to a kernel
   argument through `MemoryDataArgumentMap.get(key, p)`
   (`base/hardware/.../mem/MemoryDataArgumentMap.java`).
2. `get()` asks `isAggregationTarget(md)` whether the operand's reservation should be folded into a
   shared aggregate buffer (aggregation exists because some backends cap kernel argument count). In the
   committed code this is **residence-based**: it aggregates a reservation that is currently
   `JVMMemoryProvider`-backed (host) and not yet device-resident.
3. For each aggregated reservation, `generateArgument(...)` assigns it the next slot in the aggregate:
   `aggregatePositions.put(new MemoryDataRef(md), pos)` where `pos = aggregateLength`, then grows
   `aggregateLength += md.getMemLength()`. The argument it returns is a *delegate into the aggregate at
   `pos`*, so the generated read expression becomes `aggregateBuffer[pos + index]`.
4. `pos` is a value known at scope-generation time, so it is emitted into the kernel as the literal you
   see on line: `_3754_v2040[(gid % 32) + o1 + 288]`. The buffer base `o1` stays a runtime argument;
   the slot offset `288` is frozen.

In compile B the aggregate slot order placed the 288-element input first (slots `[0..287]`) and then
`gamma` (slot `288`) and `beta` (slot `320`) — hence `+288`/`+320`. In compile A a *different* set of
reservations was aggregated (the input was a standalone argument; only `gamma`+`beta` were aggregated,
at slots `0`/`32`) — hence `+0`/`+32` in a separate buffer.

## Why the two compiles disagree

The set/order of aggregated reservations — and therefore every baked `pos` — is a function of the
`isAggregationTarget` decision, which depends on **where each reservation currently lives** (host vs
device). The two compiles share the same weight and input reservations; after the first compile runs,
those reservations have migrated host→device, so the residence-based decision yields a **different
aggregate composition** on the second compile. Different composition ⇒ different baked `pos` ⇒ the same
`Computation` compiles to two kernels with different operand offsets, at least one of which addresses
memory that does not hold the intended `gamma`/`beta`.

## Proof that aggregation is the cause (not reuse, not signatures)

This profile pair was captured with `ScopeSettings.enableInstructionSetReuse = false` — no kernel is
cached or reused; every kernel above was compiled fresh. The divergence is therefore independent of the
instruction-set cache. The controlling variable is aggregation: compiling with
`AR_HARDWARE_OFF_HEAP_SIZE=0` (which forces every reservation provider-owned and disables aggregation
entirely) makes the two compiles byte-identical and reliably deterministic across many samples, whereas
with aggregation enabled the cross-compile difference reproduces in 3–4 of 5 repetitions.

## The fix direction

The aggregate slot decision must not depend on transient residence, and — more fundamentally — a
kernel's operand offsets must be derived from the operands' actual reservations/sizes (the `sizeArr`
the kernel already receives but ignores) rather than frozen from whichever aggregate composition
happened at this compile. Then any aggregate composition yields a kernel that still computes `C`.
