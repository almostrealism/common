---
status: completed
created: 2026-11-26
closed: 2026-11-26
tracker: [cb9de664-d506-4005-af54-104bd85afd47]
---

# Reduced deltas for the sparse matrix product (input and values)

**Tracker task:** `cb9de664-d506-4005-af54-104bd85afd47` —
*Reduced deltas for the sparse matrix product (input and values)*
**Release:** the release under development (the version in the root `pom.xml`)

## Motivation

The forward sparse product `y = A·x` already compiles to a single kernel that visits only the
stored entries: `MatrixFeatures.matmul` recognises a matrix whose `TraversalOrdering`
enumerates its rows (a `CompressedRowTraversalOrdering`, i.e. a compressed-sparse-row matrix)
and builds a `SegmentedWeightedSumComputation`, whose native loop bound is each row's
data-dependent length.

`SegmentedWeightedSumComputation` has **no structural derivative today.** It overrides none
of the derivative extension points, so any consumer that differentiates through the sparse
product falls through `CollectionProducerComputationBase.deltaDependent` to
`jacobianContraction` — the full Jacobian fallback. For a matrix whose logical size can
exceed the range of an `int` (the whole point of the sparse representation) this is both
ruinously large and, past that bound, impossible to form at all. The product is therefore
usable in a forward pass but not in a backward one, which keeps a sparse weight matrix out of
any trained model.

Both gradients the backward pass needs have the **same structural form as the forward
product** and need no Jacobian:

- the **input** gradient `∂L/∂x` is the transposed product `Aᵀ·g` of the upstream gradient
  `g` — a segmented reduction over each *column's* range instead of each row's;
- the **values** gradient `∂L/∂entries` is `g[row(k)] · x[columns[k]]` for each stored entry
  `k` — a gather with no reduction, because each entry contributes to exactly one output row.

This task gives `SegmentedWeightedSumComputation` those two reduced deltas, so the sparse
product participates in automatic differentiation at the same cost as the forward product.

## Background: the current model, and a correction to the task text

The task description (a follow-up of task `66b57639`, PR #758) refers to
`SparseMatrix.multiply`. **That type no longer exists:** PR #758's follow-up work
(commit `ab0e370b7`, *"Support sparse collections through a CSR TraversalOrdering; remove
SparseMatrix"*) deleted `SparseMatrix`. The current model, which this plan is written
against, is:

- A sparse matrix is an ordinary `PackedCollection` whose `TraversalPolicy` carries a
  `CompressedRowTraversalOrdering` (`compute/algebra`, `org.almostrealism.collect`). The
  ordering holds the CSR index structure — `rowOffsets` and the `columns` of each entry, both
  in the integer-exact split layout of `SplitIndexTraversalOrdering` — and the collection's
  memory holds only the entries, in row-major (CSR) physical order.
- `MatrixFeatures.matmul(matrix, input)` dispatches to `SegmentedWeightedSumComputation` when
  `shape(matrix).getOrder().segments(cols)` is present.
- `SegmentedWeightedSumComputation` reads each entry at its **physical position** with
  `getArgumentForInput(matrix).reference(position)`, deliberately bypassing the ordering, and
  gathers the input column through `segments.offset(position)`. The output has shape
  `(rows, width)`, where `width` is the number of input columns (1 for a vector).

The task's "input" and "values" framing is still exactly right; only the `SparseMatrix.multiply`
reference is stale. The plan proposes a matching correction to the tracker description (see
*Open questions*), which has already been applied so a reviewer reads the accurate version.

## What the blocking task delivers

The input gradient is the transposed product `Aᵀ·g`. Forming that cheaply is the whole subject
of the **sibling task `ce31398a`** (*"Transposed (CSC) sparse matrix–vector product"*,
PR #1103, design `docs/plans/PLAN-20261126-sparse-matrix-transpose-spmv.md`), which produces
the transpose of a CSR matrix as a row-enumerable sparse `PackedCollection` (entries reordered
into column-major order under a `CompressedRowTraversalOrdering` of shape `(cols, rows)`), so
that `matmul(transpose(A), g)` routes to the **existing** `SegmentedWeightedSumComputation`
with no change to matmul routing. That task names this one as the automatic-differentiation
follow-up it is the building block for.

**The input delta of this task is built on that transpose.** It must land after `ce31398a`, or
be rebased onto it. The values delta does not depend on it (see *Dependencies*).

## Scope

In `compute/algebra`, give `SegmentedWeightedSumComputation` a structural reduced delta by
overriding `deltaDependent(target, axis, weight)`:

1. **Input target (`x`), `ReduceAxis.OUT`.** When the target matches the input operand, return
   the transposed sparse product of the upstream gradient: `matmul(transpose(A), g)`, where `A`
   is this computation's matrix operand (with its CSR ordering) and `transpose` is the building
   block from `ce31398a`. With `g` of shape `(rows, width)` the result has shape `(cols, width)`
   — the shape of `x`, which is what `ReduceAxis.OUT` requires (the target's shape). No Jacobian
   is formed; the delta is itself a `SegmentedWeightedSumComputation`.

2. **Values target (entries), `ReduceAxis.OUT`.** When the target matches the matrix operand,
   return the per-entry gather `result[k] = Σ_w g[row(k), w] · x[columns[k], w]`, of shape equal
   to the entry collection (`m` values). This is a gather with no reduction: `columns[k]` is read
   through the ordering, `row(k)` through the per-entry row index described in *Approach*, and
   `g` and `x` are gathered at those positions and multiplied (summed over `width` when
   `width > 1`). Expressed entirely with the Producer pattern / index-projection orderings — no
   Java element-wise loop.

3. **Everything else defers to `super.deltaDependent(target, axis, weight)`**, so an unrelated
   target still returns zero and the un-handled axes keep their current behaviour.

**Forward-mode (`ReduceAxis.IN`) — recommended, see open question 2.** Both targets also have a
cheap `IN` (Jacobian-vector product) form that reuses the forward product, with no Jacobian:
the input tangent gives `A·t = matmul(A, t)`, and the values tangent gives a segmented reduction
of `t` against `x` (structurally `matmul(t-as-sparse, x)`). Implementing `IN` alongside `OUT`
keeps the override from silently falling back to `jacobianContraction` for forward mode. The
task's "Done when" concerns gradients (reverse mode, `OUT`); `IN` is proposed as a natural
completion rather than a separate task.

**Out of scope:**

- The full Jacobian (`ReduceAxis.NONE`). It is the one form that genuinely cannot be produced
  for a matrix whose logical size exceeds `int`, and the backward pass never asks for it. The
  override leaves `NONE` to `super` (today that throws `UnsupportedOperationException` via the
  default `deltaMatrix`); this is documented as a known limit, not silently contracted.
- Any change to `matmul` routing, to `reference()` / ordering semantics (explicitly rejected in
  PR #882 review), or to the transpose mechanism itself (that is `ce31398a`).

## Approach

Grounded in the code as it is.

- **`SegmentedWeightedSumComputation`** (`compute/algebra`,
  `org.almostrealism.algebra.computations`) overrides `deltaDependent`. It already holds the
  matrix and input operands (`getInputs().get(MATRIX)` / `get(INPUT)`), the matrix's
  `TraversalOrdering order`, the `width`, and the `segmentLength`. The override identifies which
  operand the target matches (as the base's `deltaInput` does, via `matchInput`/`refersTo`),
  then builds the corresponding reduced-delta producer. The delta rule belongs on this class —
  it is the derivative of *this* computation's structure — consistent with the project's
  method-placement rules, mirroring how `WeightedSumComputation`/`LoopedWeightedSumComputation`
  carry their own `deltaDependent`/`deltaMatrix`.

- **Input delta** reuses `MatrixFeatures.matmul(transpose(A), weight)`. `transpose(A)` is the
  `ce31398a` building block; because its result carries a CSR ordering whose `segments` is
  present, the `matmul` already routes it to `SegmentedWeightedSumComputation`, inheriting every
  operand `width` the forward product supports. This is why the input delta waits on `ce31398a`.

- **Values delta** needs the **row of each stored entry** in physical (CSR) order — the one
  piece not already exposed. Entries of row `i` occupy contiguous positions
  `rowOffsets[i] .. rowOffsets[i+1]-1`, so a per-entry row-index array is a one-time host-side
  pass over `rowOffsets` (assign `i` to that range), uploaded in the `SplitIndexTraversalOrdering`
  split layout so positions above `2^24` stay exact. This mirrors the host-side index
  construction `CompressedRowTraversalOrdering.of(...)` and the `ce31398a` transpose already
  perform. **Placement:** this capability belongs on `CompressedRowTraversalOrdering`, which owns
  the index structure and already reads its arrays back to the host for `getMaxRowLength()` /
  `getLength()` — a method that exposes the per-entry row (as an array or a readable ordering),
  not a new helper class. `columns[k]` is already available through `offset(position)`. The
  values delta then reads `g` through the row index and `x` through the columns, multiplies, and
  sums over `width`, all as producers.

- **No new Maven module, no new utility class.** The two new pieces are a method on
  `SegmentedWeightedSumComputation` (its delta) and a per-entry row accessor on
  `CompressedRowTraversalOrdering` (its index structure).

## Success criteria

A reviewer confirms, in a new test class (sibling of `CompressedRowOrderedComputationTests`
under `engine/utils/src/test/.../collect/computations/test`, extending `TestSuiteBase`, each
`@Test` with a `timeout`, iterating the same `backends()` list so **native and Metal** both
run):

1. **Input gradient matches the dense reference and finite differences.** For a sparse `A`,
   `matmul(A, x).delta(x, OUT, g)` equals `matmul(transpose(denseForm(A)), g)` element for
   element — where `denseForm(A)` reads `A` densely through its ordering — and matches central
   differences of a scalar loss, on every available backend. Checked for a vector `x` and the
   wider operand shapes `matmul` supports (`width > 1`), and for matrices with **empty and long
   rows and columns**.
2. **Values gradient matches the dense reference and finite differences.** The `OUT` delta with
   respect to the entry collection equals, per stored entry `k`, the dense weight gradient
   `g[row(k)] · x[columns[k]]` (summed over `width`) — i.e. the outer product `g ⊗ x` restricted
   to the stored positions — and matches central differences. Same backends, same matrix cases.
3. **No Jacobian is materialised.** A graph-size check analogous to
   `DenseVectorJacobianProductTest.assertNoLargerThanForward`: no computation in either reduced
   delta's graph is larger than the forward `SegmentedWeightedSumComputation`. Where practical,
   assert the input delta is itself a `SegmentedWeightedSumComputation` (mirroring the forward
   `matmulEnumeratesRows` assertion).
4. **Integer-exactness** analogous to `matmulRowVectorBeyondIntRange`: a matrix whose entry
   count / row index exceeds `2^24` gives exact gradients (split layout), with the logical size
   of `A` allowed to exceed `int`.
5. **Build-validator clean** (checkstyle, code-policy, test-timeouts, duplicate-code); new
   public code carries javadoc; no `var`, no `@SuppressWarnings`, no in-Java element-wise math.

## Dependencies

- **Blocked by task `ce31398a`** (Transposed (CSC) sparse matrix–vector product, PR #1103) for
  the **input** delta, which is `matmul(transpose(A), g)`. The implementation of this task should
  land after `ce31398a` merges, or be based on it. The **values** delta is independent of
  `ce31398a` and could be implemented first if the two deltas are split (see open question 1).
- **Machine:** the "Done when" requires verification on **Metal**, which only macOS FlowTree
  nodes provide. `docs/plans/task-20261126-183415-workstream.yaml` requests
  `requiredLabels: platform: macos` so the implementation round runs where both the native and
  Metal backends are available.

## Open questions

1. **Split the two deltas, given the dependency?** The values delta needs nothing from
   `ce31398a`; the input delta needs its transpose. One option is to land the values delta first
   (unblocked) and the input delta once `ce31398a` merges. Recommended only if `ce31398a` is slow
   to land; otherwise a single change is cleaner and a reviewer sees both halves of the derivative
   together. The approver should decide whether to keep this as one task or file the values half
   separately.
2. **Implement `ReduceAxis.IN` as well as `OUT`?** The task's "Done when" is about gradients
   (reverse mode). Forward mode has an equally cheap reduced form for both targets (both reduce to
   a forward segmented product). Recommended, to avoid a silent `jacobianContraction` fallback for
   `IN`, but it widens the test matrix; the approver may prefer to scope it to `OUT` and file `IN`
   as a follow-up.
3. **How does the override obtain the transposed matrix?** This depends on the public surface
   `ce31398a` settles (its open question 1: a method returning the transposed `PackedCollection`
   vs. a fused `MatrixFeatures` helper). The input delta should call whichever `ce31398a` lands;
   this plan assumes a `transpose(A)` that yields a row-enumerable sparse `PackedCollection`.
4. **Per-entry row accessor shape on `CompressedRowTraversalOrdering`.** Expose the row of each
   entry as an uploaded split-layout array read through a `SplitIndexTraversalOrdering` (an
   index-projection the values delta gathers `g` through), reusing the split layout for exactness.
   The exact method name/return type is an implementation detail for the implementer, constrained
   only by the placement rule (it lives on the ordering).

## Estimated complexity

**Medium.** The derivative rules are small and structural (one override on
`SegmentedWeightedSumComputation`, one accessor on `CompressedRowTraversalOrdering`) and reuse
existing machinery (`matmul`, the segmented reduction, the split layout). The cost is in the
cross-backend correctness tests (native + Metal, empty/long rows and columns, `width > 1`,
`2^24` exactness) and in the ordering of work behind the `ce31398a` dependency for the input
delta.

## Implementation notes

- The branch is based on `ce31398a` by merging its branch (PR #1103), as *Dependencies* allows.
- **Open question 3 (how the input delta gets the transpose).** After the owner's review of
  PR #1103 and PR #1104, there is no CSR-specific transpose API. The input delta is the product of
  the ordinary `CollectionProducer.transpose()` of the matrix with `g`. For a CSR ordering that
  transpose dispatches through `TraversalOrdering.transpose(rows, cols)` to
  `CompressedRowTraversalOrdering.transpose()`, a CSR ordering of `Aᵀ` over the *same* memory
  (each member's physical position through `OrderingSegments.position`), so the delta is a
  `SegmentedWeightedSumComputation` that reads the matrix producer in place and copies nothing.
- **Open question 4 (per-entry row accessor).** `TraversalOrdering.memberSegments(segmentLength)`
  gives a split-layout `SplitIndexTraversalOrdering` holding the row of each entry. The values
  delta is a new `SegmentedOuterProductComputation` (a sampled dense–dense product). Its output
  has one element per stored entry. Both accessors are default methods on `TraversalOrdering`,
  so the delta never casts to the CSR implementation.
- **Open question 2.** `ReduceAxis.IN` is implemented for both targets.
- Tests are in `CompressedRowDeltaTests` (engine/utils). They compare against dense references
  and central differences on JNI and Metal, and include a depth-2 case with more than `2^24`
  rows. One gap remains: for a batch of row vectors, the dense transpose's own reduced delta
  still contracts its Jacobian. That transpose sits on the input side, and its size is the input
  size squared, not the matrix size. It is tracked as follow-up task 97721a1c.
