---
status: completed
created: 2026-11-04
closed: 2026-11-05
tracker: [cfffa36b-bb97-4350-b15d-bc7bfc2ce48a]
---

# Skip the per-dispatch copy-in of a folded aggregate slice the kernel never reads

## Category

Performance.

## Motivation

The rolling (sliding) key/value cache (PR #818) made the self-hosted language model able to
decode past its trained context — the capability the whole self-hosted-LM arc points at — but it
landed *correct and slow*: on Metal both the rolling cache and the plain sliding window run at
roughly the same bytes/second, and the rolling decode issues about **245 argument-aggregation copy
dispatches per byte against the sliding window's ~123**. The cache is dispatch-bound, and the
mechanism is argument aggregation's per-dispatch copy-ins. The same mechanism caps the
synthetic-connectome simulation, whose state and delay-line tensors are all folded into the
aggregate rather than replaced.

Two increments of this copy path have already landed, both by reading the copy decision **off the
compiled kernel rather than from a declared property**:

- Copy-*back* is minimized. `MemoryDataArgumentMap.getWrittenSlices(Scope)` asks the compiled
  `Scope` (through `VariableReferenceSupport.containsAssignment(array, start, end)`) which folded slices
  the kernel may write, and the de-aggregation copies back only those. A slice the kernel only
  reads is unchanged and is not copied back.
- Read-only slices are copied in **once**. `isCopiedOnce` folds a read-only source whose
  written-ness is known into a single `OnceSubmittable.unordered()` group, so a model weight or a
  file-backed tensor is staged once for the lifetime of the argument map instead of before every
  dispatch (the copy-once-read-only work, PR #825).

What is still paid on every dispatch is one device copy for **every other** folded slice, and on
Metal each of those is a command-buffer blit. That per-dispatch copy *count* is what this task
cuts, by completing the read/write symmetry of the path.

This is the disciplined next step the Manager Log has named for several cycles. It builds directly
on the proven copy-back and copy-once mechanisms, removes a gate that sits under **both** live
proof-of-value tracks (readable self-description at length, and connectome-scale simulation), and
is grounded entirely in evidence read off the compiled code.

## The core idea

A folded slice the kernel **never reads** does not need to be copied into the aggregate at all: its
staged input value is dead — nothing the kernel generates ever reads it — so staging it before a
dispatch is wasted work. This is the exact dual of the copy-back minimization. Copy-back is
governed by whether the kernel **writes** a slice; copy-in should be governed by whether the kernel
**reads** it:

| slice is… | copy-in | copy-back |
|---|---|---|
| read, writable | every dispatch | every dispatch (when written) |
| read, read-only source, not written | **once** (existing) | never (existing) |
| **not read**, not copied back | **none** (this task) | never |
| **not read**, copied back | **none** for a dispatch with no copy-back; every copy-back dispatch | per the write analysis, unchanged |
| read/write set unknown | every dispatch (conservative) | every dispatch (conservative) |

Refinement made during implementation: an unread slice that *is* copied back keeps its copy-in on
every dispatch that copies back. The copy-back moves the whole slice, and nothing in the scope
proves the kernel writes every element of it (an assignment into a view of a small folded buffer,
or a single cache-row write, writes only part), so skipping its copy-in would copy stale aggregate
contents over the source's untouched elements. The map therefore builds two prepare lists once —
one for a dispatch that copies back and one for a dispatch that does not
(`getPrepareOperations(boolean)`) — and `AcceleratedOperation.apply` picks by the same
`output == null || enableStrictSideEffects` test that decides the copy-back.

Measured result (Metal, `slidingKeyValueCacheLongDecodeThroughput`): the rolling-cache decode still
reports ~245 dispatches/byte. About 92% of its aggregating dispatches copy back (runnables with no
explicit output), and the unread slices in them are the written destinations the refinement above
must keep copying in; the dispatches that do not copy back had no unread slice to skip. Reaching
the dispatch-count goal needs a proof that the kernel writes *every* element of a slice, which
depends on the per-dispatch work count and on reused size-generic kernels; that is tracked as a
separate follow-up task (a3725998-b6d6-4c20-bf71-7302227993ed) rather than extended here.

The common case this reaches is a folded slice the kernel treats as a pure destination — written
before it is ever read (a scratch or auxiliary-output buffer that happens to be small enough to
fold). Today such a slice is copied *in* every dispatch even though the staged value is immediately
overwritten; after this change it is copied in zero times and copied back as before.

Crucially, the decision is read off the compiled `Scope`, never declared by the computation. The
copy-back arc already established — and `INVESTIGATION-20261031-declared-property-shortcut.md`
records the owner rejecting — the shortcut of letting a computation *declare* which slices it
writes; the scope is the code that runs, so it is the only honest account of what the kernel reads
or writes. The read analysis must follow the same rule: a scope whose reads cannot be enumerated
reports every slice as read, so the fallback is always "copy everything in," never a silent skip.

## Scope

Concrete, actionable items:

1. **A position-bounded read query**, symmetric to the existing write query. `VariableReferenceSupport`
   exposes `containsAssignment(ArrayVariable, long, long)` — whether the code assigns to (writes)
   an element of the array in `[start, end)`. Add its read counterpart — whether the code
   references an element of the array in `[start, end)` in a non-destination (read) position. The
   machinery exists: `ExpressionReferences` already answers the unbounded
   `containsReference(Variable)`, and `containsAssignment` is precisely the position-bounded
   *lvalue* case; the new method is the position-bounded *rvalue* case. Place the method where its
   kin live — on the interface that owns the query and on `Scope`/`ExpressionReferences`, never as a
   private helper.

2. **`MemoryDataArgumentMap.getReadSlices(Scope)`**, mirroring `getWrittenSlices(Scope)`: the
   indices of the replacements whose aggregate slice the kernel may read.

3. **`limitCopyInTo(Scope)` / `setReadSlices(BitSet)`**, mirroring `limitCopyBackTo(Scope)` /
   `setWrittenSlices(BitSet)`, so an operation that reuses a kernel compiled against a different map
   records the read set alongside the compiled kernel (as the write set already is) and reproduces
   the copy-in skip. Like the copy-back setter, it must be fixed before the copy operations are
   built once.

4. **Partition the copy-ins in `ensureCopyOperations`** by the read set as well as the existing
   read-only/written split: a slice that is provably not read gets no copy-in (it is added to
   neither `prepareOperations` nor the copy-once group); every slice that is read keeps its current
   treatment (copy-once when the source is read-only and unwritten, every dispatch otherwise). When
   the read set is unknown, every slice is copied in, exactly as the write analysis conservatively
   reports every slice written.

5. **A backend-independent structural regression test** that pins the partition directly — not a
   timing threshold — so it cannot be gamed: a folded write-only (unread) slice receives zero
   per-dispatch copy-ins and holds its correct value afterward; a read-and-written slice still
   receives both a copy-in and a copy-back; a kernel whose read set cannot be enumerated copies
   every slice in.

Out of scope (separate follow-ups, the remaining levers of the same arc): coalescing the slices
that *must* still be copied every dispatch into a single multi-region copy on Metal (one blit
rather than N); and aggregating only when the folded-argument count is over a threshold so a
handful of arguments are not folded-then-copied at all.

## Approach

- Follow the copy-back implementation as the template end to end — the same evidence source
  (`Scope.containsAssignment` → add the read counterpart), the same "record it alongside the
  compiled kernel for a reusing map" shape (`limitCopyBackTo`/`setWrittenSlices` →
  `limitCopyInTo`/`setReadSlices`), the same "decide once in `ensureCopyOperations`, never per
  dispatch" placement. The `AcceleratedOperation.apply` call site already submits
  `argumentMap.getPrepareOperations()`; it needs no change beyond what a shorter prepare list
  implies.
- Prove the read query inert where it must be: a slice that `getWrittenSlices` reports as written
  but that is *also* read (a read-modify-write buffer — an accumulator, or a cache the kernel both
  updates and attends over) must remain copied in. The query answers "is any element in the range
  read," independent of whether it is also written, so a read-modify-write slice is correctly kept.
- Verify correctness on any backend with the existing aggregation tests (copy-back,
  aggregation-signature, compressed-row traversal, `CollectionDataMemoryProviderTest`) plus the new
  structural test. Then measure on a Metal node.
- Store the mechanism and the measured figures in memory as the work lands, and correct the
  argument-aggregation prose in `base/hardware/README.md` /
  `docs/internals/argument-binding-lifecycle.md` if the read/write symmetry changes what they
  describe.

## Success Criteria

- A folded slice the compiled kernel never reads is copied into the aggregate zero times per
  dispatch, pinned by the new backend-independent structural regression test; a read-and-written
  slice is unchanged; an unknown-read-set kernel copies everything in.
- The read decision is inferred from the compiled `Scope`, with no declared-property API and no way
  for a computation to contradict what its code does.
- All existing aggregation and argument-binding tests pass on any backend.
- On a Metal node, `CausalLanguageModelDecodeThroughputTest` reports a **lower dispatches/byte** (and
  no higher commits/byte) for the rolling-cache decode than before this change. That test is the one
  that profiles the Metal dispatch and command-buffer commit counts and logs the per-byte figures —
  it reports them rather than asserting them, because they vary by node and backend, so the win is
  read by comparing the logged figures against the pre-change baseline.
  **Not met by this change:** the measured rolling-cache decode still reports ~245 dispatches/byte
  (see "Measured result" above), so this branch delivers only the structural copy-in partition. The
  dispatch-count reduction needs the full-overwrite proof for copied-back slices, filed as tracker
  task a3725998-b6d6-4c20-bf71-7302227993ed.
- `ConnectomeNetworkTest` still reproduces the Brian2 reference spikes exactly, confirming the
  copy-in skip changes no simulation result. It is a correctness test and measures no copy or commit
  count; any connectome-side copy-count win is read from a separately profiled run, not from it.
- No test is weakened (no loosened tolerance, reduced dimension, raised `@TestDepth`, inflated
  timeout, or skip-gate) to reach any of the above.

## Dependencies

None blocking. It builds on the copy-back minimization (PR #783 and its successors) and the
copy-once-read-only work (PR #825), both merged. It is complementary to, and shrinks the remaining
surface of, the other per-dispatch-copy-count levers, which stay as follow-ups.

## Estimated Complexity

Small–medium. One new read-query method on the query-owning interface and `Scope`, three symmetric
methods on `MemoryDataArgumentMap`, a partition change in `ensureCopyOperations`, and one structural
test — a localized change on the template of work already proven on this exact path. The throughput
and command-buffer commit-count measurement needs a Metal node (macOS); correctness is verifiable on
any backend.
