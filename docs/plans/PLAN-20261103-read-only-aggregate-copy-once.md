---
status: proposed
created: 2026-11-03
closed:
tracker: [faca1b4d-dee7-4428-bc8b-1333973312b7]
---

# Copy read-only aggregate slices once per argument map instead of before every dispatch

## Category

Performance.

## Motivation

The self-hosted language model now decodes past its trained context with a rolling
key/value cache (PR #818, merged 2026-11-03). The cache is correct — it matches the
sliding window byte-for-byte within the trained context, and past `seqLen` implements
per-layer window (Mistral-style) attention: token-for-token identical to the sliding
window for a single-block model, and for a deeper model equivalent only in its eviction
property (a token stops affecting the output once every block's window has moved past
it), as `SlidingKeyValueCacheAutoregressiveModelTest` pins. But the throughput win it was
meant to deliver did not materialize: on Metal both paths run at ~200–260 bytes/second, and the
per-byte decode issues roughly **245 Metal dispatches against the sliding window's 123**.
The decode is dispatch-bound, not compute-bound, and the dispatches that dominate are
argument-aggregation copies.

The same bottleneck caps a second proof-of-value track. The neuron-level connectome
reproduces a published whole-brain experiment spike-for-spike (`ConnectomeNetwork`,
`studio/bioscience`), and a recent performance round (`qa/performance-20261102-014254`)
made file-backed read-only arguments cheaper on the **replacement** path — the Metal
sugar-circuit step went 38.0 s → 11.8 s. But the *synthetic* connectome (40 neurons,
111 edges) saw no improvement, because every one of its tensors is small enough to be
*aggregated* rather than replaced, and the aggregation path still pays the cost that round
removed from replacement.

Both tracks are gated by one mechanical inefficiency: the aggregation copy-in runs before
**every** dispatch, even for arguments whose contents cannot have changed and whose
aggregate slice the kernel never writes. This is the kind of allocation/transfer pattern the
Performance category exists to remove: a known, measured bottleneck on the critical path of
the platform's two most distinctive capabilities — the model that describes itself, and the
simulation of a living brain. Cutting it is a direct step toward "self-description usable at
length" and toward connectome-scale simulation running at a rate that makes larger
reproductions practical.

## Background — how argument aggregation copies today

`MemoryDataArgumentMap` folds each kernel argument of at most `maxAggregateLength` (1024)
elements into a single aggregate buffer, so a fused kernel with many small inputs stays
under the compute context's buffer-argument limit (Metal's ~31, the native compiler's
parameter limit). Around each dispatch:

- **Copy-in.** `getPrepareOperations()` returns one copy per folded root (built once in
  `ensureCopyOperations()`, reused across dispatches). `AcceleratedOperation.apply` submits
  all of them before the kernel, ordered after the operation's dependency.
- **Copy-out.** Only slices the compiled scope may write are copied back. `isCopiedBack(int)`
  already excludes a read-only root and (when `writtenSlices` is known from
  `limitCopyBackTo`/`setWrittenSlices`) any slice the kernel does not write. So the
  copy-*back* is already minimized; the copy-*in* is not.

At a single decode position nearly every argument is small and therefore aggregated:
64-element activations, norm weights, the 1-element position, the 256-element scores, the
1024-element rotary table. On Metal each copy-in is a compute dispatch, so the copy-ins
dominate the per-byte dispatch count. For the read-only ones — model weights and the
file-backed (`PROTOBUF`) tensors `StateDictionary` locates — the copy is pure waste after
the first: the source cannot change, the aggregate buffer persists for the life of the map
(and under instruction reuse each operation gets its own map and buffer), and the kernel
never writes that slice.

Worse on Metal, a read-only root is often file-backed memory the context cannot move itself,
so `MetalComputeContext.copy` falls back to a host copy deferred through `Semaphore.then`.
The kernel then depends on a foreign `DeferredSemaphore`, `MetalCommandRunner` bridges it —
closing the open command buffer, holding the next, and parking a callback thread in
`MetalSemaphore.waitFor` (a host-forced commit). Every such copy-in is a bridged command
buffer, every dispatch, every token.

## The invariant that makes "copy once" correct

A folded copy-in may be reduced to a single copy — made on the first dispatch only — when
both hold:

1. **The source is read-only** (`MemoryData.isReadOnly()`, resolved through the root
   delegate's provider). Its contents cannot change between dispatches, so the aggregate
   slice stays correct after the first copy.
2. **The kernel does not write that slice** — `writtenSlices` is known (set before the first
   dispatch via `limitCopyBackTo`/`setWrittenSlices`) and excludes this replacement's index.
   Then the kernel never overwrites the slice in the aggregate buffer, so it still holds the
   copied-in value on the next dispatch.

When `writtenSlices` is unknown, the per-dispatch copy is kept — exactly as the copy-*back*
is kept conservatively today. The condition is the deliberate conjunction of both facts, not
read-only alone: it is read off the same compiled-scope evidence (`getWrittenSlices(Scope)`)
that already governs copy-back, so a new representation cannot silently become stale.

The first copy of such a slice can also be made **unordered**
(`Submittable.unordered()`), the way `MemoryReplacementManager.stage()` already issues its
read-only copy: the aggregate buffer is freshly allocated for this map, and no prior work
produces a read-only source, so neither a read-after-write nor a write-after-read hazard can
arise. Its completion still covers the operation's dependency, so the first kernel is ordered
exactly as it is today. Issuing it unordered is what removes the bridged command buffer on
Metal — the host round trip, not merely the device copy.

## Scope

1. **Partition the copy-ins in `MemoryDataArgumentMap`.** In `ensureCopyOperations()`,
   classify each replacement's copy-in as *copy-once* (read-only source **and** a known
   `writtenSlices` that excludes it) or *per-dispatch* (everything else). Build the
   copy-once copies `unordered()`. Keep the existing `copyInOperations` list aligned with
   `replacements` for any indexed caller, and expose the per-dispatch subset built once, the
   way `writableCopyOutOperations` is already held separately from `copyOutOperations`.
2. **Submit the copy-once copies exactly once.** The first dispatch submits copy-once +
   per-dispatch (ordering unchanged from today); every later dispatch submits only the
   per-dispatch copies. The map owns this one-time decision — a per-operation map, mutated
   under `AcceleratedOperation.apply`'s existing synchronization — so the `apply` call site
   stays a single request to the map for "the copy-ins to run before this dispatch," with no
   new per-dispatch branching logic leaking into `apply`.
3. **Leave copy-back untouched.** `isCopiedBack`/`getPostprocessOperations` already minimize
   copy-back; this plan changes only copy-in.
4. **Respect the migration constraint.** Do not migrate the root. Small read-only roots must
   stay `PROTOBUF` — pinned by `CollectionDataMemoryProviderTest#smallDeferredComputesWithoutMigrating`.
5. **Document the copy-once rule** in the `MemoryDataArgumentMap` class/method Javadoc,
   alongside the existing account of copy-back minimization, so the invariant lives in the
   code, not only here.

Out of scope (complementary tracker follow-up `38f78778`): cutting the *number* of
per-dispatch device copies for the slices that genuinely must be copied every dispatch —
aggregating only when a kernel would otherwise exceed the buffer-argument limit, skipping the
copy-in of a destination the kernel fully overwrites and never reads, or encoding a kernel's
copy-ins as one multi-region copy dispatch. That task counts device copies; this one removes
the redundant read-only copies and their host round trips. Doing this one first shrinks the
set the other must still address and establishes the "copy-once" machinery it can build on.

## Approach

- Drive the design from the invariant above; prove each clause against the compiled scope
  (`getWrittenSlices`) and the provider's `isReadOnly`, never from a declared property.
- Keep the change inside `MemoryDataArgumentMap` and the one `AcceleratedOperation.apply`
  call site. The map is the right owner: it already holds the aggregate buffer, the
  replacements, the written-slice set, and the once-built copy lists.
- Mirror the proven `MemoryReplacementManager` read-only pattern (unordered first copy) so
  there is one account of why a read-only copy need not be ordered, not two divergent ones.
- Validate correctness on any backend with the existing aggregation tests; validate the win
  on Metal (see Success Criteria). Add a regression test that pins the copy-once behavior
  structurally — e.g. that a repeated dispatch of an operation with a read-only aggregated
  argument submits fewer copy-ins on the second dispatch than on the first, while a writable
  aggregated argument is still copied in every dispatch — so the reduction cannot silently
  regress and cannot be gamed by a timing threshold.

## Success Criteria

1. On Metal (`-DAR_HARDWARE_DRIVER=mtl`), `ConnectomeNetworkTest#matchesBrian2Spikes` and
   `#silentWithoutInput` (synthetic connectome) show a reduction clearly outside run-to-run
   noise, with `MetalCommandRunner.getBridgeCommitCount()` staying flat across their steps.
2. On Metal, `CausalLanguageModelDecodeThroughputTest.keyValueCacheDecodeThroughput` reports
   a lower cache `dispatchesPerByte` than before this change (progress toward, and
   complementary to, `38f78778`'s goal of beating the sliding window outright).
3. A new backend-independent regression test pins the copy-once vs per-dispatch distinction
   described in Approach.
4. `CollectionDataMemoryProviderTest`, `AggregateCopyBackTest`, `AggregationSignatureTests`
   and `CompressedRowTraversalOrderingTests` still pass. No existing test is weakened.
5. Build validator clean (checkstyle, code_policy, test_timeouts, duplicate_code, javadoc).

## Dependencies

None in code. The copy-back minimization machinery this builds on
(`writtenSlices`, `limitCopyBackTo`, `getWrittenSlices`) is already on `master`. The
throughput and bridged-commit measurements require a Metal (macOS) node — see the
accompanying `plan-20261103-041617-workstream.yaml`. Correctness is verifiable on any
backend.

## Estimated Complexity

Small–medium. The change is localized to one class (`MemoryDataArgumentMap`) and one call
site (`AcceleratedOperation.apply`), guided by an invariant already used for copy-back and
already used, in the sibling `MemoryReplacementManager`, for unordered read-only copies. The
breadth is in verification: the existing aggregation tests on any backend, plus the Metal
measurements, plus the new structural regression test.
