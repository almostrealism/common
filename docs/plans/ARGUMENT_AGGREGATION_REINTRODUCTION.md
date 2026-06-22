# Argument Aggregation Reintroduction Plan

**Status:** proposed (2026-06-22, revised). Reintroduces the compile-time argument
aggregation removed in commit `5f0648eab`, in a form that is safe to reuse from the
instruction cache. Supersedes the count-gating idea in an earlier draft of this file (see
§3 — that idea was wrong). Related: [SIGNATURE_AGGREGATION_GAP.md](SIGNATURE_AGGREGATION_GAP.md)
(now partly stale; see §7).

## 1. The problem these tests expose

Two tests fail on Metal, pass on JNI:

- `engine/audio/.../BatchedChainSeamTest.chainSplitsAcrossWindowsWithBoundedSeam:113`
- `engine/audio/.../BatchedSssFromScalarsTest.testFromScalarsMatchesMaterialized:168`

Root cause from the Metal compiler:

```
program_source: error: 'buffer' attribute parameter is out of bounds: must be between 0 and 30
... Caused by: Failed to compile f_multiOrderFilter_*
```

Metal allows 31 buffer bind slots (`[[buffer(0..30)]]`). The fused
`buildBatchedSssChainPlacedFromScalars` path binds dozens of small per-note scalar
collections as separate buffers and exceeds it. The materialized path passes because it
binds far fewer.

### Compact reproduction (success indicator)

`engine/utils/.../hardware/test/KernelArgumentLimitTest.manySmallInputsExceedKernelArgumentLimit`
sums 48 distinct single-element collections in one kernel. Verified:

| Backend | Result |
|---|---|
| `native` (JNI) | passes (sum correct) |
| `mtl` (Metal) | fails: `'buffer' ... must be between 0 and 30`, `Failed to compile f_collectionAddComputation_*` |

This must pass on every backend once aggregation is restored.

## 2. The fix is compile-time, decided per argument — not by counting

The buffer count is a property of the compiled `Scope`. To lower it, multiple small inputs
must map to **one** kernel buffer, which is a property the generated kernel must have — i.e.
a **compile-time** transformation. The runtime replacement engine
(`MemoryReplacementManager`) runs after compilation and only swaps the *data* behind already
-declared arguments; it cannot reduce the bind-slot count, so it alone cannot fix this.

Crucially, the decision is made **per argument, at the moment the argument variable is
created**, exactly as the working `MemoryDataArgumentMap` on master does it — never by
inspecting a total. There is no "aggregate only if the Scope will have too many arguments"
gate, because that count does not exist until the Scope is built (see §3).

## 3. Why there is no count-based gate (retraction)

An earlier draft proposed aggregating only when a Scope "would exceed" the argument limit.
That is unimplementable. The argument count exists only **after** Scope construction; any
faithful predictor of it would have to reproduce Scope construction (simplification, dedup,
delegate folding) — i.e. it *is* Scope construction. The only literal alternatives are
(1) build the Scope, count, then build it again with aggregation on — doubling an expensive
process — or (2) a true estimator, which cannot exist. Aggregation must therefore be an
unconditional, local, per-argument transformation (default on for eligible small buffers),
not a globally gated one.

## 4. How the working aggregation (master) operates

`git show master:base/hardware/.../mem/MemoryDataArgumentMap.java` (436 lines). In
`get(key, p)`, for each requested argument:

- `generateArg = md.getMem().getProvider() != context.getDataContext().getKernelMemoryProvider()`
  — true when the buffer lives **outside device memory** (a host/JVM buffer that would have
  to be copied to the device anyway).
- If `generateArg` and `isAggregationTarget(md)` (aggregation enabled, `memLength <=
  maxAggregateLength`, JVM memory), `generateArgument(...)` folds `md` into a single shared
  `aggregateArgument` as a **delegate at offset `pos`**
  (`delegateProvider.getArgument(p, key, getAggregateArgument(p), pos)`), records
  `aggregatePositions[md] = pos`, grows `aggregateLength`, and registers the copy-in/out via
  `replacementMap.addReplacement(md, getAggregateSupplier(), pos)`.

Delegate variables are not separate kernel buffers — they resolve to offsets within
`aggregateArgument` — so N small inputs collapse to **one** `[[buffer(n)]]`. That is the
mechanism that lowers the slot count and fixes the Metal failure. No count is involved.

## 5. Why it was removed, and the real design problem

The copy plan lives in `MemoryDataReplacementMap` (master, 150 lines), keyed by the
**concrete `MemoryData` seen during this compilation** (`MemoryDataRef(original)`;
`getPreprocess`/`getPostprocess` emit `MemoryDataCopy` between `original` and the
aggregate slice). That binding is **reuse-blind**: when the compiled `InstructionSet` is
reused by a different but structurally identical `Process` (the whole point of the
instruction cache), the actual data differs, but the baked copy plan still points at the
original compilation's buffers. To stay correct, caching was *proactively disabled* for any
aggregating computation — `CollectionProviderProducer.signature()` returned `null` for
aggregation targets. With the old 1 MB default that meant ~everything, which is the caching
collapse documented in `SIGNATURE_AGGREGATION_GAP.md`.

So the real problem is **not** "when to aggregate" and **not** signatures per se. It is that
the aggregation copy plan was expressed in a vocabulary (concrete `MemoryData`) that the
reuse path cannot re-resolve.

## 6. The design: merge aggregation into the reuse substitution

There are two replacement systems, and they must agree, so unify them:

- **Reuse substitution** — `ProcessArgumentMap`
  (`base/hardware/.../arguments/ProcessArgumentMap.java`) maps each compiled-scope
  `ArrayVariable` to its **Process-tree position** (`ProcessTreePositionKey`) via `match()`.
  On reuse, `AcceleratedComputationOperation.load()` (lines 465-488) builds a fresh
  `ProcessArgumentMap(manager.getArgumentMap())`, calls `putSubstitutions(thisComputation)`,
  and `setEvaluator(map)`; `getEvaluable(arg)` then resolves slot → position → **this
  invocation's** producer → value. Keyed by structural position, so it is reuse-safe.
- **Aggregation substitution** — currently expressed in concrete `MemoryData`, which is why
  it is not reuse-safe.

**Plan:** express the aggregate *layout* in the same position vocabulary and resolve its
copy plan through `ProcessArgumentMap`:

1. **Compile time (restore, per argument).** Keep master's `generateArgument` collapse: small
   off-device inputs become delegates into one `aggregateArgument`. But instead of recording
   the copy plan against `MemoryData`, record a structural **aggregate layout**: an ordered
   list of slices, each `(ProcessTreePositionKey position, int offset, int length,
   boolean output)`. Store this layout on the instruction-set manager **alongside**
   `ProcessArgumentMap` / `ScopeInstructionsManager.argumentMap`, so it is shared and cached
   with the kernel.
2. **Dispatch time (per invocation, reuse-safe).** When building the process details for an
   invocation, walk the aggregate layout and, for each slice, resolve the **current** source
   via the invocation's `ProcessArgumentMap` (`getProducerForPosition` / `getEvaluable`),
   then emit a `MemoryDataCopy` into a **per-invocation** aggregate buffer at `offset`
   (copy-in); for `output` slices, emit the reverse after the kernel (copy-back). The
   aggregate buffer is allocated per dispatch via the existing
   `AcceleratedOperation.createAggregatedInput` (`AcceleratedOperation.java:262`), and the
   copies run through the existing `MemoryReplacementManager` / `MemoryDataCopy` /
   `AcceleratedProcessDetails` prepare(`:545`)/postprocess(`:574`) machinery.

Because every aggregated slice's source is resolved through the *same* position-based
substitution used for ordinary arguments, **a reused kernel copies the right data
automatically.** Aggregation and reuse are now the same mechanism.

### 6.1 The signature problem dissolves

Once the copy plan is reuse-safe, there is no reason to disable caching for aggregating
computations — so we simply stop nulling `CollectionProviderProducer.signature()` for
aggregation targets. The signature work `SIGNATURE_AGGREGATION_GAP.md` worried about becomes
unnecessary: aggregation behaves identically across structurally identical computations, so
the cached kernel is correct to reuse. (Do not invest in clever aggregate-aware signatures;
a correct, reuse-consistent copy plan makes them moot.)

### 6.2 Inputs vs. outputs

Inputs need copy-in only. A written output bound through the aggregate needs copy-back and
forces a per-op completion wait (already modelled by the `processing` branch in
`AcceleratedOperation.apply`, `:541-575`). The `output` flag on each layout slice drives
this. The failing tests are all read-only scalar inputs, so input aggregation alone fixes
them; output aggregation can follow if a workload needs it.

## 7. What exists vs. what to (re)build

Reuse as-is:
- `MemoryDataCopy` host↔device copy primitive (`MemoryDataCopy.java:306`).
- `AcceleratedOperation.createAggregatedInput` (`:262`) per-dispatch device temp allocator.
- `AcceleratedProcessDetails` prepare/postprocess execution (`:238/:245`, `apply :545/:574`).
- `ProcessArgumentMap` position-based resolution (`getEvaluable`, `getProducerForPosition`).

Rebuild (was removed in `5f0648eab`), adapting from master:
- The per-argument collapse in `MemoryDataArgumentMap.get` / `generateArgument`
  (delegate/`delegateOffset` into one `aggregateArgument`).
- A **position-keyed** aggregate layout (replacing `MemoryDataReplacementMap`'s
  `MemoryData`-keyed plan), stored alongside the `ProcessArgumentMap`.
- The dispatch-time copy-plan builder that resolves layout slices through the invocation's
  `ProcessArgumentMap`.

Then **remove** the proactive caching disable (the `signature()` null for aggregation
targets) rather than reintroduce it.

Stale docs to fix afterward: `base/hardware/README.md:507-544`,
`base/hardware/docs/INSTRUCTION_CACHING.md:193-194` still describe the old
`isAggregationTarget`/env-flag behaviour.

## 8. Configuration

- `aggregateMaxLength` — max element count of an aggregable buffer (default ~1024, the
  requirement's "small"; configurable). The eligibility predicate stays "off-device + small",
  not a count.
- An on/off master switch (default on) for diagnosis/rollback.
- (`AR_HARDWARE_OFF_HEAP_SIZE` still governs whether small reservations are off-device at all,
  `Hardware.java:1119` — interacts with the "off-device" half of eligibility.)

## 9. Implementation phases

1. **Restore the collapse (correctness of the fix).** Reinstate master's compile-time
   aggregation (per-argument, into one `aggregateArgument`), *initially keeping* the old
   `MemoryData`-keyed copy plan and the caching disable. Confirm `KernelArgumentLimitTest`
   then `BatchedChainSeamTest` / `BatchedSssFromScalarsTest` pass on `mtl` and stay green on
   `native`. This proves the buffer-collapse is the fix, in isolation from the reuse work.
2. **Make the copy plan position-keyed.** Replace the `MemoryData`-keyed plan with the
   structural aggregate layout stored beside `ProcessArgumentMap`, and build the dispatch-time
   copies by resolving slices through the invocation's `ProcessArgumentMap`. Verify the three
   tests still pass.
3. **Re-enable caching.** Remove the `signature()` null for aggregation targets. Verify an
   `AudioScene` render built twice with the same genome reuses compiled kernels (the failure
   that motivated the original removal), and the three tests still pass on both backends.
4. **Config + docs.** Expose §8 settings; refresh the stale references in §7.
5. **(Optional)** output aggregation.

## 10. Success criteria

- `KernelArgumentLimitTest.manySmallInputsExceedKernelArgumentLimit` passes on `native` and `mtl`.
- `BatchedChainSeamTest.chainSplitsAcrossWindowsWithBoundedSeam` and
  `BatchedSssFromScalarsTest.testFromScalarsMatchesMaterialized` pass on `mtl` (still green on `native`).
- An `AudioScene` render built twice with the same genome reuses compiled kernels (caching not
  regressed) — with aggregation on and the proactive disable removed.

## 11. Key code references

- Master working aggregation (to adapt): `git show master:base/hardware/.../mem/MemoryDataArgumentMap.java`
  (`get`, `generateArgument`, `isAggregationTarget`), `MemoryDataReplacementMap.java`, `MemoryDataRef.java`.
- Reuse substitution: `base/hardware/.../arguments/ProcessArgumentMap.java`
  (`addChildren`/`match`, `getEvaluable:341`, `getProducerForPosition:294`, `putSubstitutions:320`);
  `base/hardware/.../instructions/ScopeInstructionsManager.java` (`argumentMap`,
  `populateArgumentMap:234`, `getScopeArguments`); `AcceleratedComputationOperation.load:465-488`.
- Runtime copy machinery to reuse: `base/hardware/.../mem/MemoryReplacementManager.java`;
  `AcceleratedOperation.createAggregatedInput:262`, `apply` prepare `:545` / postprocess `:574`;
  `AcceleratedProcessDetails` (`getPrepare:238`, `getPostprocess:245`, `isEmpty:252`);
  `MemoryDataCopy.java:306`.
- Metal limit (the constraint): `base/hardware/.../metal/MetalOperator.java:246-262` (binding +
  2 reserved slots); `MetalCommandRunner.MAX_ARGS = 512` (`:58`, runtime-only, unrelated to the
  real 31-slot limit).
- Compile-time arg count (FYI only, not used for gating): `base/code/.../OperationAdapter.java:81`.
