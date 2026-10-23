# Integer-exact index storage for gather and sparse addressing on FP32 backends

**Tracker task:** `2c0793a7-141c-40bb-8253-a2dda02ebaa2` — "Integer-exact index storage for
gather and sparse addressing on FP32 backends"
**Release:** Common 0.76

## Motivation

Index data — gather indices, sparse row offsets, edge-list source/target neuron ids — is carried
today in ordinary floating-point `PackedCollection`s. On an FP32 backend (Metal), a `float`
represents integers exactly only up to 2^24 (16,777,216). Any index value above that is rounded the
moment it is stored, before any kernel runs.

This is already a hard limit for the work that depends on this task. The MaleCNS connectome has
about 125M synapses and an edge list with tens of millions of entries; its CSR `rowOffsets` run well
past 2^24. A sparse matrix–vector product, connectome ingest, and in general any gather over a large
table cannot address their data on Metal while index values live in FP32 memory
(`docs/plans/CONNECTOME_MODEL_ORGANISM.md`, Phase 1 task 2).

The precedent for the fix already exists. `CollectionFeatures.rows` gathers whole rows by computing
each element's *address* on integer expressions inside the kernel — `row.multiply(rowLength).add(...)`
on `Expression<Integer>` values — so the address stays exact above 2^24; its Javadoc states that a
flat index carried as a floating-point value would round. The gap this task closes is the same
rounding, but in the index *data* rather than in the address arithmetic: `rows` still reads its
`rowIndex` value with `args[2].getValueAt(...).toInt()`, and if that stored value already exceeds
2^24 on FP32, it was rounded at storage time.

## Chosen representation: hi/lo split across two FP32 lanes

Three options were on the table (task description). The plan recommends the **hi/lo split**, and the
reasons for rejecting the other two are part of the recommendation.

An integer index `v` is stored as two values, each individually exact in FP32:

```
hi = v / BASE        (integer division)
lo = v mod BASE
v  = hi * BASE + lo
```

with `BASE` a power of two chosen so both lanes stay at or below 2^24. The reconstruction
`hi * BASE + lo` is performed on **integer expressions** inside the kernel (each lane read, then
`.toInt()`, then `.multiply(BASE).add(...)`), exactly as `rows` computes addresses — so the value
never passes back through an FP32 representation and stays exact end to end.

Recommended `BASE = 2^12` (4096): for any index below 2^31 the high lane is below 2^19 and the low
lane below 2^12, both far under 2^24, giving a comfortable exact range up to 2^36. Any power of two
in `[2^12, 2^24]` is valid; the implementer should fix the constant as a named contract on the type
that owns the representation (not an ad-hoc literal at each call site).

Reconstruction is built on `Expression<Integer>` arithmetic, which is exact to 2^31. Tens of
millions of edges (and the full 125M synapse count) are well under 2^31, so `int` reconstruction is
sufficient for this task. If a future table needs indices above 2^31, the same scheme extends with
`toLong` / long arithmetic; that extension is noted, not implemented here.

**Why not integer-typed memory (option 1).** `Precision` (`base/code`) is fundamentally a
floating-point enum — FP16/FP32/FP64, each defined by byte width, epsilon, min value, and a
`typeName()` of `bfloat`/`float`/`double`. Introducing an integer buffer dtype would mean a new
element type threaded through `MemoryProvider`, every backend's kernel argument binding and code
generation (Metal, OpenCL, native, CUDA), and the read path in `Expression`/`TraversableExpression`.
That is a large, cross-cutting change with risk in all four backends, and it is out of proportion to
this task. The split representation needs none of it: it is ordinary FP32 memory read with ordinary
integer expression arithmetic, so it already works on every backend.

**Why not relative offsets within bounded blocks (option 3).** Block-relative offsets are tailored
to CSR row addressing and do not generalize to an arbitrary gather over a large flat table (the
task's other named use). They also still need the per-block base offsets to be exact, which
themselves exceed 2^24 for a tens-of-millions-of-entries array — so the problem reappears one level
up unless the base offsets are themselves stored exactly, which is what the hi/lo split provides
directly.

The split is also the least disruptive to the "do not silently change the precision of existing
collections" constraint: it is an **opt-in** representation for index data. Ordinary collections are
untouched; only index data that a caller explicitly stores split is read back exactly.

## Scope

Within this task:

1. **A split-index representation and its exact reconstruction** (`base/code`, with the storage
   helper in `base/collect`). The concept — an integer index stored across two FP32 lanes — is a
   genuine new type, not a utility: it owns the `BASE` constant, the host-side encode, and the
   in-kernel reconstruction expression. Sibling in spirit to `IndexExpressionTraversalOrdering`,
   whose `indexOf(idx)` is exactly the single-lane form `indices.getValueAt(idx).toInt()`.

2. **A `TraversalOrdering` that reads the split representation** — a sibling of
   `IndexExpressionTraversalOrdering` whose `indexOf(idx)` returns the reconstructed
   `Expression<Integer>`. This is what makes the representation usable through the symbolic indexing
   path the task names (`IndexExpressionTraversalOrdering`, and downstream the sparse
   `TraversableExpression` work).

3. **A gather overload in `CollectionFeatures`** that reads indices from the split representation.
   The existing gather is `c(TraversalPolicy shape, Producer collection, Producer index)`, built as a
   `DefaultTraversableExpressionComputation("valueAtIndex", ...)` whose body is
   `args[1].getValueAt(args[2].getValueAt(idx).toInt())`. The new overload substitutes the exact
   reconstruction for the inner `getValueAt(idx).toInt()`, leaving the rest identical. Follow the
   placement rule: the overload belongs on `CollectionFeatures` beside the existing gather and
   `rows`, named by what it is.

4. **A host-side store path** that encodes an `int[]`/`long[]` of indices into the split FP32 layout
   in one contiguous buffer and uploads it with a single bulk fill (no per-element `setMem`/JNI loop)
   — the legitimate top-of-stack ingest boundary, like weight loading. Placed on `PackedCollection`
   (`base/collect`).

5. **Documentation** of the representation in `docs/internals/dynamic-indexing.md` (the page that
   already documents gather, `traverseEach`, and index producers), including the exactness guarantee,
   the `BASE` choice, and when to prefer it over a plain float index.

Explicitly out of scope (named as dependencies / later tasks below): the sparse matrix type and
sparse matrix–vector product (`66b57639`), integer dtypes in `SafetensorsReference` and
`collections.proto` (`3e0043d3`), and the sparse `TraversalOrdering` / `TraversableExpression`
integration (`82bda0b7`). This task delivers only the exact index storage/read and its use in
gather; it unblocks those, and must not widen into them.

## Approach, grounded in the code

Files involved:

- `base/code/.../io/almostrealism/collect/IndexExpressionTraversalOrdering.java` — the single-lane
  precedent; the new split ordering is its sibling and lives beside it in `base/code`.
- `base/code/.../io/almostrealism/code/Precision.java` — confirms memory is float-only
  (FP16/FP32/FP64); the rationale for rejecting integer-typed memory.
- `compute/algebra/.../collect/CollectionFeatures.java` — `rows` (the integer-address-arithmetic
  precedent) and `c(shape, collection, index)` (the gather to extend). The new overload is added
  here.
- `compute/algebra/.../collect/ExplicitIndexTraversalOrdering.java` — extends
  `IndexExpressionTraversalOrdering`; informs how a split ordering would compute its length
  (its `getLength()` reads the max index on the host via `doubleStream`).
- `compute/algebra/.../collect/PackedCollection.java` — home of the bulk store/fill used by the host
  encode path.
- `docs/internals/dynamic-indexing.md` — documentation target.

New behaviour belongs:

- The reconstruction expression and the `TraversalOrdering` in **`base/code`** — "it is a property of
  collections and expressions" (CONNECTOME plan placement table), reading index data symbolically.
- The gather overload in **`compute/algebra`** (`CollectionFeatures`), beside the gather it mirrors.
- The host encode on **`PackedCollection`** (`base/collect`).

All reconstruction is Producer/Expression composition — no Java-side element math — so it compiles to
the same single kernel the existing gather does and satisfies `CodePolicyViolationDetector`.

## Success criteria

A reviewer confirms the task is done when:

1. **Reconstruction exactness.** A split-index collection holding values above 2^24 (e.g. `2^24`,
   `2^24 + 1`, `2^24 + 5`, and a ~5×10^7 value) reconstructs to exactly those integers, verified by a
   test whose output is the reconstructed index, on **native and Metal**. A control storing the same
   values in a plain FP32 index collection rounds `2^24 + 1` to `2^24`, demonstrating the defect the
   feature removes. (The control asserts the *known-bad* behaviour; it is not weakened.)

2. **End-to-end gather exactness.** With a data collection of `N = 2^24 + K` elements (`K` small),
   built as `integers(0, N)` so `data[i] == i` — about 67 MB in FP32, which fits CI and the default
   `AR_HARDWARE_MEMORY_SCALE` — a gather at split indices `{2^24, 2^24 + 1, 2^24 + 5}` returns exactly
   those values on **native and Metal**, while the existing float-index gather returns the rounded
   (wrong) element. This is the task's "Done when" acceptance test.

3. Tests live in `engine/utils` `CollectionComputationTests` (or a dedicated sibling test class in
   the same package), extend `TestSuiteBase`, and each `@Test` carries a `timeout`. Run them with the
   MCP test runner targeting those classes/methods, on a macOS node so Metal is exercised.

4. `docs/internals/dynamic-indexing.md` documents the representation.

5. `mcp__ar-build-validator__start_validation` is clean (checkstyle, code_policy, test_timeouts,
   duplicate_code) and `mvn clean install -DskipTests` succeeds.

## Dependencies and open questions

- **Metal node required for acceptance.** Criterion 1–2 require verification on Metal (FP32), which
  only macOS FlowTree nodes provide. The implementation workstream must run on a macOS node — see the
  accompanying `docs/plans/task-20261023-135029-workstream.yaml`.
- **Layout: interleaved `(n, 2)` vs planar `(2, n)` vs two collections.** Recommend interleaved
  `(n, 2)` so one index array is one collection, with `hi` at `idx*2` and `lo` at `idx*2+1`; the
  reviewer should confirm, since planar can be marginally cheaper to construct from two producers.
- **`BASE` value.** Recommend `2^12`; any power of two in `[2^12, 2^24]` works. Fix it as a named
  constant on the representation type.
- **Reconstruction width.** `int` (exact to 2^31) covers the connectome and this task. Indices above
  2^31 would need a `long` reconstruction; noted as an extension, not built here.
- **Relationship to the symbolic sparse path.** The task names
  `IndexExpressionTraversalOrdering`; the split `TraversalOrdering` (scope item 2) is included so the
  representation is usable both through the direct gather and through the symbolic ordering path. The
  broader sparse `TraversalOrdering`/`TraversableExpression` integration is a separate task
  (`82bda0b7`) and is not a prerequisite for the gather deliverable.
- **Downstream.** This unblocks the sparse matrix type and sparse matrix–vector product
  (`66b57639`), which stores CSR `rowOffsets`/`sources` with this representation. No scope from that
  task is taken on here.

No defect or follow-up work outside this task was found during planning, so no new tracker task was
filed.

## Estimated complexity

**Medium.** The representation, reconstruction, gather overload, host encode, and tests are each
small and localized, and the reconstruction reuses the exact integer-expression pattern `rows`
already establishes. The medium rating reflects the cross-module touch (`base/code`, `base/collect`,
`compute/algebra`) and the requirement to verify exactness on a real FP32 (Metal) backend rather than
only on native.
