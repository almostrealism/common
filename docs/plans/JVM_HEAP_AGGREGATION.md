# Small Values on the JVM Heap, Aggregated Once Per Kernel Program

## Why this exists

`AR_HARDWARE_OFF_HEAP_SIZE` decides which `MemoryData` live on the JVM heap rather than in
native or device memory. It was 1024 elements and is now 0 (`8c5f0d03d`, 2026-06-29), so
every value, however small, is a native allocation. On CUDA with shared memory every one of
those is a `cuMemAllocManaged`, and every release is a `cuMemFree` that synchronizes the
device. PR #623's CI timeouts (`SyntheticDenseTrainingTest`, `NormTests`) came from that.

Raising the threshold back to 1024 does not help today. It was tried on this branch:
`denseClassification` still timed out at 720 s, because a heap-backed argument now costs, on
**every dispatch**, a fresh device allocation, a copy in, a copy out and a free (see
"Current behaviour"). This document describes the design under which heap residency pays
off, compares it with what exists, lists the constraints that the current code breaks, and
lays out a sequence of changes that would get there.

The `AllocationCache` added to `CudaMemoryProvider` on this branch is a mitigation for the
allocation cost. It is not the design described here, and it does not remove the copies.

## The intended model

Take audio rendered by one kernel program with many small parameters (oscillator
frequencies, envelope levels, filter coefficients, each a handful of numbers):

1. **Creation.** Each parameter is created on its own, as setup proceeds, and is stored on
   the JVM heap. Creating it costs no native allocation and no device interaction.
2. **Backend selection.** When the kernel program is composed and its backend is chosen,
   the small arguments are gathered into **one** aggregate allocation on that backend's
   memory provider.
3. **Adoption.** Each parameter's value is copied into its slice of the aggregate **once**.
   From then on the aggregate is the source of truth for that parameter.
4. **Execution.** The kernel runs many thousands of times (for example, 1024 frames per run)
   against the aggregate. No dispatch copies anything.
5. **Host access.** When a parameter is read or written from the host (a user turns a
   knob), that one access goes to, or through, the parameter's slice of the aggregate. This
   is the only time a copy happens, and it is rare.
6. **Retirement.** When the kernel program is retired, each parameter's current value is
   copied back to its own heap memory and the aggregate is freed. The parameters are then
   ordinary heap values again, ready to be aggregated by the next program that uses them.

What this costs: one allocation and N small copies per program **lifetime**, plus one copy
per host access. What the current code costs: one allocation, N copies in, N copies out and
one free per **dispatch**.

## Current behaviour

There are two mechanisms. Both allocate through `AcceleratedOperation.createAggregatedInput`,
which allocates on the kernel provider (since `b4b23f0c5`).

### A. Compile-time folding (`MemoryDataArgumentMap`)

- `AcceleratedOperation.prepareScope()` builds the map. `MemoryDataArgumentMap.get` folds a
  `Provider` of `MemoryData` into the aggregate when `isAggregationTarget` holds. Today that
  check is size only (`<= maxAggregateLength`, 1024 elements), on purpose, so that
  instruction-set reuse is not affected by where the data lives.
- The aggregate belongs to the operation instance. It is allocated lazily, and reused
  operations rebuild it through `rebindAggregateForReuse()` at the positions recorded in
  `postCompile()`. It is destroyed in `resetArguments()`.
- **Copy-in runs on every `apply`** (`getPrepareOperations()`).
- **Copy-out runs on every `apply` when `output == null`**, and never runs when an explicit
  output is given unless `AR_HARDWARE_STRICT_SIDE_EFFECTS` is on. In that case the kernel's
  writes to folded slices are discarded.
- The original `MemoryData` is never rebound. It stays on its own memory, and the aggregate
  is a per-dispatch staging copy of it.

### B. Per-dispatch replacement (`MemoryReplacementManager`)

- `ProcessDetailsFactory.construct` creates a **new manager for every dispatch**.
- `AcceleratedProcessDetails.checkReady()` replaces each argument whose provider is not the
  kernel provider and whose size is at most 1M elements with a **new temporary** from
  `createAggregatedInput`. It copies in, copies out (unless the root is read-only), and frees
  the temporary when the completion settles (`d632bbeeb`).
- This is the path a heap-backed argument takes whenever it was not folded by A. That
  includes positional and `ProducerArgumentReference` arguments, results evaluated at
  dispatch time, and anything over the fold limit. With the off-heap threshold above zero,
  this is where the per-dispatch `cuMemAllocManaged` / copy / `cuMemFree` measured on this
  branch comes from.

### What the per-dispatch copies cost under shared memory

Measured on this branch (`SyntheticDenseTrainingTest.denseClassification`, `native,cuda`,
integrated CUDA device, quiet machine): 574 s with shared memory disabled, 634 s with it on
and `AllocationCache` in place (the second run included about two minutes of JFR
profiling). A JFR sample of the shared run shows `cuMemAllocManaged` almost gone, but
`CU.memcpyDtoD` from `CudaMemoryProvider.setMem` is the dominant native frame. Its callers
are `MemoryDataArgumentMap.copyOperation` and `Assignment$Runner` copies. These are issued
by the **native** context, whose `AbstractComputeContext.copy` fallback calls `setFrom` on a
callback thread, because the CPU kernels now run against CUDA managed memory. So every
mechanism-A copy that used to be a host memcpy is now a synchronous driver call. The
long-lived model removes these copies altogether.

A copy between two managed allocations is now made by the host over direct buffer views
(`CUDeviceBuffer.copyFrom`), with no driver call. With that in place the same test takes
435 s: sharing is now faster than not sharing. The copies themselves still run on every
dispatch.

### C. Migration (`HardwareOperator.reassignMemory`)

An argument on a provider the operator does not support is **permanently moved** with
`MemoryData.reallocate`. `MemoryDataAdapter` keeps the old memory as a version
(`memVersions`) and copies back when it is moved back to that provider. In practice B
replaces small heap arguments before the operator sees them, so C applies only to large
ones.

## History

Copies on every dispatch go back to the beginning. The long-lived model above has never
existed in this repository.

| Commit | Date | Change |
|---|---|---|
| `1e6b132c6` | 2022-12-11 | Aggregation introduced; "copied into a consolidated MemoryData before evaluation, and copied back after evaluation" around every `operator.accept` |
| `35481e4d6` | 2022-12-24 | Dual providers: small values on the heap, large on the device, migrating "back and forth as necessary" |
| `ad5dfdd46` | 2023-12-07 | Aggregation limited to heap-backed values (`JVMMemoryProvider`), at most 1M elements |
| `786f0e930` | 2025-09-03 | `MemoryReplacementManager` extracted, one per dispatch |
| `5f0648eab` | 2026-06-20 | Argument aggregation removed |
| `9732295ff`, `a6c5213ac`, `13ce711c5` | 2026-06-23 | Reintroduced: eligibility by size only, limit lowered to 1024, per-operation aggregate rebound under reuse, copy-out added |
| `b4b23f0c5` | 2026-06-24 | Aggregates allocated on the kernel provider |
| `8c5f0d03d` | 2026-06-29 | `AR_HARDWARE_OFF_HEAP_SIZE` default lowered from 1024 to 0 |
| `451b25951`, `a3b20e285` | 2026-06-29 to 07-02 | Copies became Semaphore-chained submissions |
| `cc014f8c5` | 2026-09-04 | Copy-out to read-only memory skipped |
| `cecc8bc73` | 2026-09-16 | Folded-destination copy-back experiment reverted |
| `d632bbeeb` | 2026-10-01 | Per-dispatch replacement temporaries freed (about 20k temporaries, roughly 14 GB, had leaked in 12 s) |
| `306d6470a` | 2026-10-07 | Shared memory on unified accelerators, so that `native,cuda` evaluation stops staging through CUDA aggregates |

The off-heap threshold went to zero three days after aggregation was reintroduced with
per-dispatch copies and kernel-provider aggregates. **[Inference]** The threshold was
lowered because, under the reintroduced design, every heap argument paid the staging cost
on every dispatch. With nothing on the heap, mechanism B has almost nothing to replace.

## Constraints the model needs, and where they are broken

Each constraint is something the intended model depends on. The note under it says how the
current code breaks it.

1. **The aggregate lives as long as the kernel program, not one dispatch.**
   Broken by B: a temporary for every dispatch, by design. A's aggregate does live per
   operation, but it is used as staging.

2. **While a program is live, its aggregate is the source of truth for every folded value.**
   Broken by A and B: the original is never rebound, so the aggregate has to be refreshed
   from it on every dispatch, and the original has to be refreshed from the aggregate after
   every dispatch. `MemoryData` has no state that says "my current value is in that slice of
   that aggregate".

3. **Copies are triggered by host access, not by dispatch.**
   Broken: dispatch is the only trigger there is. Host reads and writes (`toDouble`,
   `setMem`, `toArray`) go straight to `getMem()` with no hook to sync from or to a lent
   slice.

4. **Kernel writes to a folded value are never lost.**
   Broken by A's default: with an explicit output and strict side effects off, writes to
   folded slices are dropped. This is part of why heap residency was considered unreliable:
   whether an argument is folded or not changes what the host later reads. In the intended
   model, writes stay in the aggregate (the source of truth) and are read through it, so
   there is nothing to drop.

5. **Eligibility is decided once, when the backend is chosen.**
   Broken by B: it decides per dispatch from the argument's current provider, so the same
   heap value is staged again on every call. A decides at compile time, which is correct,
   but its rule ignores residency. That was deliberate (stable instruction-set reuse) and
   has to stay true: the fold positions must not depend on where values live.

6. **A value belongs to at most one live aggregate at a time, or its sharing is explicit.**
   Not addressed today. A parameter shared by two concurrently live programs on different
   backends cannot have two sources of truth. Today this is "solved" by copying around every
   dispatch. In the intended model it needs a rule: a second program borrows from the first
   program's aggregate slice when it is on the same provider. Otherwise the value is
   reconciled out of the first aggregate before it is adopted by the second.

7. **Retirement writes back before the aggregate is freed.**
   Broken: `resetArguments()` / `MemoryDataArgumentMap.destroy()` free the aggregate and
   assume the original is already current, which is only true because of the per-dispatch
   copy-out.

8. **Reconciliation is ordered against in-flight kernels.**
   Partly in place. Copies are already Semaphore-chained (`451b25951`, `a3b20e285`) and
   protected by `KernelMemoryGuard` leases. A host access to a lent slice must wait for (or
   be ordered after) the kernels that write it, which is the same as the existing
   `Semaphore` ordering for outputs. Retirement must wait for the last dispatch to settle.

## Building blocks that already exist

- **Delegation.** `MemoryDataAdapter.setDelegate(MemoryData, int, TraversalOrdering)` makes a
  value a view onto a slice of another. `getMem()` then resolves through the delegate, so
  every host read and write reaches the aggregate with no extra hook. Today a root with its
  own memory warns if it is given a delegate ("has a delegate, but also directly reserved
  memory"); that would have to become a supported state while lent.
- **Memory versions.** `MemoryDataAdapter.memVersions` already keeps a value's memory on one
  provider while it lives on another, and copies back when it returns. That is the
  retirement write-back, in a form that already works for `reallocate`.
- **Stable fold positions.** `MemoryDataArgumentMap` records `Replacement(root, pos)`, and
  `rebindAggregateForReuse()` checks the positions. That is the slice assignment the
  adoption step needs.
- **Ordered asynchronous copies.** `ComputeContext.copy(src, dst, dependsOn)` and
  `Submittable` chains.
- **Kernel leases.** `KernelMemoryGuard` keeps a value's memory alive while a kernel uses it.

## Proposed sequence

Each step is useful on its own and can be measured before the next one starts.

1. **Make mechanism B per program, not per dispatch.** Keep the replacement temporaries on
   the operation, keyed by root, and reuse them across dispatches while the root and its
   size are unchanged. Copies still run every dispatch, but the allocation and the free go
   away. On CUDA this removes the managed allocation churn without relying on
   `AllocationCache`. This is a small, contained change in `ProcessDetailsFactory`,
   `AcceleratedProcessDetails` and `MemoryReplacementManager`.

2. **Lend folded values to the aggregate (A).** On the first dispatch after compile, copy
   each folded root into its slice. Then keep its heap memory as a version, and make it
   delegate to the aggregate slice. Drop the per-dispatch copy-in and copy-out for lent
   values; constraint 4 then holds by construction, and `enableStrictSideEffects` becomes
   unnecessary for them. On `resetArguments()`, wait for the last dispatch to settle, copy
   each slice back, clear the delegate, and restore the heap memory (constraint 7).

3. **Route host access through the lent slice, ordered after in-flight kernels.** Delegation
   already routes the access. What remains is ordering: a host read must wait for kernels
   that write the slice, and a host write must not race a running kernel. The existing
   output `Semaphore` ordering is the model for this.

4. **Apply the same lending to B's arguments.** Once step 2 works, arguments that reach B
   (positional, `ProducerArgumentReference`) can be lent to the per-program temporaries
   from step 1 in the same way, so their copies stop as well.

5. **Decide the sharing rule (constraint 6).** If a value is already lent to a live
   aggregate on the same provider, a second program uses that slice. On a different
   provider, the value is reconciled out of the first aggregate before the second program
   adopts it, and the first program's dispatch fails fast if it is still live. This has to
   be settled before step 2 is enabled by default.

6. **Raise `AR_HARDWARE_OFF_HEAP_SIZE` back to 1024**, once steps 1 to 3 are in and
   measured. Measure `SyntheticDenseTrainingTest.denseClassification`,
   `SimilarityOverheadTest.pairwiseSimilarityAtScale` and an audio rendering test on each
   backend (`native`, `cl`, `mtl`, `native,cuda`), with shared memory on and off.

## Open questions

- Step 2 changes what `getMem()` returns for a lent value. Code that compares providers
  (`getMem().getProvider() != target` in B, `reassignMemory` in C) will see the aggregate's
  provider. That is the desired effect, but every such comparison should be audited.
- Does any caller depend on reading a folded value's heap memory directly, bypassing
  `getMem()`? `JVMMemory` exposes its array; any direct use of it would read stale data
  while the value is lent.
- How is "the kernel program is retired" signalled for programs that are cached and reused
  (`AR_INSTRUCTION_SET_REUSE`)? A reused operation instance has its own aggregate, so
  retirement is per operation instance, but this needs confirming against
  `resetInstructions()`.
- Should lending be limited to values below the off-heap threshold, or should it apply to
  every folded value? Lending a native value to a native aggregate on the same provider
  saves the same copies.
