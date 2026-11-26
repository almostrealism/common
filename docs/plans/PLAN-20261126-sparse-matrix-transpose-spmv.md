---
status: proposed
created: 2026-11-26
closed:
tracker: [ce31398a-93de-4294-9a7b-1087d52258c1]
---

# Transposed (CSC) sparse matrix–vector product

**Tracker task:** `ce31398a-93de-4294-9a7b-1087d52258c1` —
*Transposed (CSC) sparse matrix–vector product for SparseMatrix*
**Release:** Common 0.78

## Motivation

The forward sparse product `y = A·x` is already compiled to a single kernel that visits
only the stored entries: `MatrixFeatures.matmul` recognises a matrix whose
`TraversalOrdering` enumerates its rows (a `CompressedRowTraversalOrdering`, i.e. a
compressed-sparse-row matrix) and builds a `SegmentedWeightedSumComputation`, whose native
loop bound is each row's data-dependent length.

The transposed product `Aᵀ·g` is the **input gradient** of that product. It has the same
structural form — a segmented reduction, now over each *column's* range instead of each
row's — so it can be computed with the same cost and the same machinery, visiting only the
stored entries. Today there is no way to form it cheaply: a consumer that needs `Aᵀ·g`
would have to densify `A` (`rows · cols` reads, each a bounded row search) and multiply the
dense transpose, which defeats the purpose of the sparse representation and is impossible at
all once the logical size of `A` exceeds the range of an `int`. This task closes that gap by
deriving the compressed-sparse-column (CSC) arrays of `A` and reusing the existing segmented
reduction. No full Jacobian is formed.

## Background: what the predecessor delivered, and a correction to the task text

The task description (written as a follow-up of task `66b57639`, PR #758) says
"`SparseMatrix` … computes `y = A·x`" and asks for "`A.transpose().multiply(g)`". **That
premise is out of date.** PR #758's follow-up work (commit `ab0e370b7`, *"Support sparse
collections through a CSR TraversalOrdering; remove SparseMatrix"*) **deleted the
`SparseMatrix` type**. The current model is:

- A sparse matrix is an ordinary `PackedCollection` whose `TraversalPolicy` carries a
  `CompressedRowTraversalOrdering` (`compute/algebra`, `org.almostrealism.collect`). The
  ordering holds the CSR index structure — `rowOffsets` and the `columns` of each entry,
  both in the integer-exact split layout of `SplitIndexTraversalOrdering` — and the
  collection's memory holds only the entries. (See
  `docs/internals/dynamic-indexing.md` § *Sparse Matrices*.)
- `MatrixFeatures.matmul(matrix, vector)` dispatches to `SegmentedWeightedSumComputation`
  when `shape(matrix).getOrder().segments(cols)` is present.
- `SegmentedWeightedSumComputation` reads each entry at its **physical position** with
  `getArgumentForInput(matrix).reference(position)`, deliberately bypassing the ordering —
  this is correct precisely because the matrix's memory holds only the entries in CSR order.
  (Making `reference()` ordering-aware was explicitly rejected in PR #882 review to protect
  this path.)

So there is no object on which to call `.transpose().multiply(g)`. The task must be
restated in terms of the current model: produce the **transposed sparse matrix** — a
`PackedCollection` + `CompressedRowTraversalOrdering` describing `Aᵀ` — and multiply it with
`matmul`, matching `matmul(transpose(denseForm(A)), g)`. This plan proposes updating the
tracker task's description to this effect (see *Open questions*; the correction has already
been applied to the task so a reviewer reads the accurate version).

## Key insight

**The transpose of a CSR matrix is itself a CSR matrix** — of the transpose. Writing the CSC
structure of `A` (column pointers + the row of each entry) *is* writing the CSR structure of
`Aᵀ` (which has shape `(cols, rows)`). Therefore, if `Aᵀ` is presented as a row-enumerable
sparse `PackedCollection`, `matmul(Aᵀ, g)` dispatches to the **existing**
`SegmentedWeightedSumComputation` with no new computation and no change to the matmul
routing: the reduction simply runs over each column's range.

The only thing that must change physically is the **order of the entry values**.
`SegmentedWeightedSumComputation` iterates a *contiguous* range `start(i) + 0 … start(i) +
length(i) − 1` and uses that single index both to read the entry (`reference(position)`) and
to find the gather offset (`segments.offset(position)`). A column's entries are scattered
across CSR memory, so a column can never be a contiguous range of CSR physical positions.
Hence the transposed matrix's memory must hold the entries **reordered into column-major
(CSC) order**; then enumeration index equals physical position again, exactly as in the
forward case, and the existing kernel is correct verbatim.

## Scope

In `compute/algebra`:

1. **Derive the CSC structure.** Add a `transpose()` capability to
   `CompressedRowTraversalOrdering` that produces, from its host-available index arrays, the
   CSC arrays of `A`:
   - `colOffsets` — `cols + 1` column pointers (the counting-sort prefix sums of the column
     histogram);
   - `rowIndices` — for each column-major slot, the row of that entry (becomes the `columns`
     array of the transpose ordering);
   - `perm` — for each column-major slot, the CSR physical position of that entry (the
     permutation that reorders the entry values).

   It returns a new `CompressedRowTraversalOrdering` for the `(cols, rows)` transpose, built
   from `colOffsets` and `rowIndices` via the existing `of(int, int[], int[])` factory (so it
   inherits that factory's validation and split-layout upload), and makes the permutation
   available so the entries can be reordered.

2. **Reorder the entries (Producer pattern, int-exact).** Obtain the transposed entry
   collection by reading the original entries through a `SplitIndexTraversalOrdering` over
   `perm` — a gather expressed as a producer, never a Java `for`/`setMem` loop — so that
   permutation indices above `2^24` stay exact (there can be up to `m` entries). Attach the
   transpose ordering to the result, yielding the transposed sparse `PackedCollection`.

3. **Public entry point.** Expose obtaining the transposed sparse matrix so a caller can
   write `matmul(transpose(A), g)` (or equivalent) and reach the segmented reduction. The
   orchestration that drives the counting sort and permutation belongs on
   `CompressedRowTraversalOrdering` (which owns the index structure and the `perm`); the entry
   gather uses the Producer pattern. The exact surface (a method that returns the transposed
   `PackedCollection`, vs. a `MatrixFeatures` helper) is an open question below.

**Out of scope (named as follow-ups, not widened into this task):**

- Wiring the structural transpose into automatic differentiation, so
  `SegmentedWeightedSumComputation`'s *input gradient* automatically uses `Aᵀ·g` instead of a
  dense Jacobian. The task's "Done when" requires only that the explicit transposed product
  matches the dense reference. The automatic-gradient wiring is already tracked by task
  `cb9de664` (*"Reduced deltas for the sparse matrix product (input and values)"*), which this
  task gives the building block for.
- Any change to `reference()` / ordering semantics (explicitly rejected in PR #882).

## Approach

Grounded in the code as it is:

- **`CompressedRowTraversalOrdering`** (`compute/algebra`, `org.almostrealism.collect`) gains
  `transpose()`. It already reads its index arrays back to the host for `getMaxRowLength()`
  and `getLength()` (`rowOffsets.getPositions()` / `getColumns()` decoded via
  `SplitIndexTraversalOrdering`), so computing the column histogram, its prefix sums, and the
  `rowIndices`/`perm` arrays is a host-side counting sort over those arrays — the same kind of
  one-time host index construction that `of(int, int[], int[])` already performs. The new
  ordering is built with the existing `of(cols' = rows, int[] colOffsets, int[] rowIndices)`
  factory so no new validation or upload path is introduced. The empty-matrix guard (the
  single unused column kept when there are zero entries) is reused.

- **Entry reordering** uses `SplitIndexTraversalOrdering` exactly as the documented gather
  pattern does: `new PackedCollection(shape(m), 0, entries, 0, SplitIndexTraversalOrdering.of(perm))`
  is a view whose every read applies the permutation; materialising the transposed entries is
  one gather kernel, not an element-by-element copy. This is where the `2^24` exactness is
  earned, and it is the same mechanism the forward CSR arrays already rely on.

- **`MatrixFeatures.matmul` is unchanged.** Because the transposed matrix carries a
  `CompressedRowTraversalOrdering` whose `segments(cols')` is present, `matmul(Aᵀ, g)` already
  routes to `SegmentedWeightedSumComputation` through the existing branch. The transposed
  product therefore inherits every operand shape the forward product already supports (vector,
  column vector, batch of row vectors, matrix).

- **Placement rationale.** Transposing a CSR index structure is an operation on the ordering,
  so it lives on `CompressedRowTraversalOrdering` (not a new utility class, per the project's
  method-placement rules). The entry values are data, reordered through the Producer pattern.

## Success criteria

A reviewer confirms:

1. A new test class (sibling of `CompressedRowOrderedComputationTests` under
   `engine/utils/src/test/.../collect/computations/test`) asserts that the transposed product
   matches the dense reference: for a sparse `A`, `matmul(transpose(A), g)` equals
   `matmul(transpose(denseForm(A)), g)` element for element, where `denseForm(A)` is `A` read
   densely (e.g. `cp(A).multiply(1.0)`). Checked:
   - on **every available backend** (CPU/native and GPU/Metal), as the existing sparse tests do;
   - on random matrices including **empty rows and empty columns** (a column with no entries
     must produce a zero output row of `Aᵀ`) and **long rows and long columns**;
   - for both a vector `g` and the operand shapes `matmul` supports.
2. An **int-exactness** test analogous to `matmulRowVectorBeyondIntRange`: a matrix whose
   entry count / permutation indices exceed `2^24` gives the exact transposed product (split
   layout), with the logical size of `Aᵀ` allowed to exceed `int`.
3. `matmul(transpose(A), g)` is a `SegmentedWeightedSumComputation` (the transpose reuses the
   segmented reduction rather than densifying), mirroring the `matmulEnumeratesRows` assertion.
4. Build-validator clean (checkstyle, code-policy, test-timeouts, duplicate-code); new public
   code carries javadoc; no `var`, no `@SuppressWarnings`, no in-Java element-wise math.

## Dependencies

- Blocked by: none (the forward CSR product and `SegmentedWeightedSumComputation` are on
  master).
- **Machine:** the "Done when" requires verification on **Metal**, which only macOS FlowTree
  nodes provide. A `docs/plans/task-20261126-173259-workstream.yaml` requests
  `requiredLabels: platform: macos` so the implementation round runs where both the native
  and Metal backends are available.

## Open questions

1. **Public surface for the transpose.** Two candidates: (a) a method that returns the
   transposed sparse `PackedCollection` given the original (orchestrated from
   `CompressedRowTraversalOrdering`, which owns `perm`); (b) a `MatrixFeatures` helper that
   both transposes and multiplies. Option (a) is more composable (`matmul` stays the single
   product entry point) and keeps the index logic on the ordering; recommended unless review
   prefers a single fused call. Either way, no new utility/exporter class is introduced.
2. **Lazy vs. materialised entries.** The entry reorder could stay a lazy gather producer or
   be materialised once at transpose-construction time (like `of(...)` uploads its arrays).
   Materialising keeps the transposed matrix a concrete entry-packed `PackedCollection`, which
   is what `SegmentedWeightedSumComputation`'s `reference(position)` expects and what the
   `matmulComputedMatrix`/`matmulReshapedMatrix` cases already stress for computed operands.
   The implementer should pick the form that keeps `shape(matrix).getOrder()` the CSR-of-`Aᵀ`
   ordering (not the gather ordering) so matmul routing still fires; this is the main
   implementation risk and should be settled first.
3. **Task description correction.** The tracker task still names a non-existent `SparseMatrix`
   and `A.transpose().multiply(g)`. This plan proposes updating the description to the
   `PackedCollection` + `CompressedRowTraversalOrdering` model and the `matmul(transpose(A),
   g)` framing, preserving the original "Done when" intent. (The autodiff follow-up already
   exists as task `cb9de664` — see *Scope*.)

## Estimated complexity

**Medium.** The computation and matmul routing are reused unchanged; the new work is a
host-side counting-sort transpose on the ordering plus an int-exact entry gather, with the
main risk being the lazy-vs-materialised entry decision (open question 2) and thorough tests
across backends and the `2^24` boundary.
