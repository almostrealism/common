---
status: completed
created: 2026-10-26
closed: 2026-10-26
tracker: [66b57639-42ae-423f-bbde-ae93406228ac]
---

# Sparse matrix type and sparse matrix–vector product with a segmented reduction

- **Tracker task:** [66b57639-42ae-423f-bbde-ae93406228ac] Sparse matrix type and sparse matrix–vector product with a segmented reduction
- **Release:** Common 0.76
- **Phase context:** Phase 1, task 4 of `docs/plans/CONNECTOME_MODEL_ORGANISM.md` ("Sparse connectivity: compressed rows plus a segmented reduction")
- **Estimated complexity:** Large

## Motivation

The platform has no sparse matrix type and no sparse matrix–vector product. `Tensor` (nested-list
host storage) does not scale past modest non-zero counts, and a dense 166k × 166k matrix is about
110 GB in FP32. Gather by an index array already exists (a collection read through a
`TraversalOrdering`, or `CollectionFeatures.c(shape, collection, index)`), but nothing reduces
per-element values into variable-length segments, and no backend generates atomics.

This is the core capability for running a 166k-neuron connectome: one simulation step's synaptic
input is `input[i] = Σ weights[e] · activity[sources[e]]` for the edges `e` of postsynaptic neuron
`i`. That is a gather (which exists) followed by a reduction over a data-dependent range (which does
not). It is also the building block for graph models, sparse attention and sparse Jacobians.

The design direction the connectome plan commits to, and that this task implements, is a
**compressed-sparse-row (CSR)** matrix plus a **segmented reduction**: one thread per output row,
deterministic, writing each output exactly once, with no atomics. That fits the framework's
one-thread-per-output kernel model and is portable to every backend.

## Scope

Concretely, this task adds, in `compute/algebra`:

1. **A CSR sparse matrix type** holding three arrays — `rowOffsets[n+1]`, `columns[nnz]` and
   `values[nnz]` — as `PackedCollection`s. The type is a genuine new concept (a sparse matrix), so
   it is a new class, `SparseMatrix`, in `org.almostrealism.algebra`. It carries its logical shape
   `(rows, cols)` and the three backing collections, validates their mutual consistency at
   construction, and exposes the product below as a method on itself (per the project rule that
   behaviour belongs on the type whose state it reads — there is no separate exporter/utility
   class).

2. **A Producer for `y = A·x`** computed as a gather followed by a segmented reduction over each
   row's range. `SparseMatrix.multiply(Producer<PackedCollection> x)` returns a
   `CollectionProducer<PackedCollection>` of shape `(rows)` (or `(rows, 1)` to mirror the dense
   `matmul` column-vector convention — see Open Question 3). The reduction is backed by a new
   computation class in `org.almostrealism.algebra.computations` (see Approach).

3. **Integer-exact addressing** for the index arrays, using the `SplitIndexTraversalOrdering`
   delivered by the blocking task, so that row offsets (and any flat address into `values`/`columns`
   that can exceed `2^24`) stay exact on FP32 backends.

4. **Tests** proving parity with dense `matmul`, correct handling of degenerate rows, a large-scale
   footprint/throughput check, and integer-exact behaviour above `2^24`.

**Explicitly out of scope** (the task says so; file as follow-ups when this lands — see Open
Questions):

- The transposed product (CSC), which gives the input gradient.
- Reduced deltas (`delta(target, ReduceAxis, weight)`) for both the input and the values. Both have
  structural forms, so no full Jacobian is needed.
- Event-driven / scatter-add propagation.

This task also does **not** depend on the sparse-`TraversalOrdering`/`TraversableExpression`
integration (task 82bda0b7). The product must not wait on it; see Dependencies.

## Approach

### Where the pieces are today

- **Dense reference.** `MatrixFeatures.matmul(Producer matrix, Producer vector)` (a default method
  on the `MatrixFeatures` mixin) is the parity reference. Its vector path is
  `multiply(...).traverse(axis).sum()`; its large path routes to `weightedSum`.
- **The proven native-loop precedent.** `LoopedWeightedSumComputation`
  (`org.almostrealism.algebra.computations`) extends `AggregatedProducerComputation` and generates a
  **native `Repeated` outer loop** over a fixed `int outerCount` plus an **unrolled inner sum** over
  a fixed `int innerCount`. It drives the gather through two functional interfaces, `InputIndexer`
  and `WeightIndexer`, each returning an index `Expression`, and reads operands with
  `args[n].getValueAt(index)`. `isIsolationTarget` returns `true` so `getScope()` (the native loop)
  is used instead of full unrolling. This is the exact shape of the segmented reduction, except that
  it carries only two operands (input, weights); CSR needs a third (the `columns` index array), read
  **inside** the loop to form the gather index.
- **The gather.** The blocking task (2c0793a7, merged) established that a gather is nothing more than
  a collection read through a `TraversalOrdering`: `SplitIndexTraversalOrdering`
  (`org.almostrealism.collect`) stores each `int` position split across two FP32 lanes
  (`hi = floorDiv(v, BASE)`, `lo = floorMod(v, BASE)`, `BASE = 2^12`) in an `(n, 2)` collection, and
  `indexOf` reconstructs `hi·BASE + lo` on **integer** expressions in the kernel, exact across the
  full `int` range. `PackedCollection.ofSplitIndices(int...)` builds the lane collection in one
  transfer. See `docs/internals/dynamic-indexing.md`.
- **The data-dependent-bound question.** `Repeated` (`io.almostrealism.scope`) accepts an arbitrary
  `Expression<Boolean>` condition and an `Expression<Integer>` interval, so a loop bounded by
  `k < rowOffsets[i+1]` is expressible *at the scope level*. But every **higher-level** native-loop
  path in the framework (`AggregatedProducerComputation` and its subclasses) fixes the iteration
  count as a Java `int` at construction. A genuinely data-dependent bound is therefore new territory:
  the loop condition would have to read `rowOffsets` through `getValueAt` and the loop would run a
  per-thread-variable number of iterations.
- **Masking for padding.** `Mask.of(Expression<Boolean>, Expression value)`
  (`io.almostrealism.expression`) returns the value when the predicate holds and `0` otherwise — the
  mechanism for zeroing padded entries in a fixed-length loop.

### The "first investigation" the task asks for

The task frames a decision: *can the kernel loop take a data-dependent bound? If not, implement
degree-binned padded rows (ELL)?* The plan resolves it as a short, bounded investigation at the
start of implementation, with ELL as the committed fallback so the task cannot stall:

1. **Prototype a data-dependent `Repeated` bound.** Build a minimal computation whose outer loop
   condition is `k < rowOffsets[i+1]` with the index initialised to `rowOffsets[i]`, reading both
   endpoints through the integer-exact ordering, and verify it compiles and runs correctly on **both
   native and Metal**. If it holds up (correct results, acceptable compile time, no reuse/signature
   hazard from the data-dependent condition), it is the preferred implementation: true CSR, no
   padding, no wasted work on long rows.

2. **If the data-dependent bound is not robust, implement ELL (degree-binned padded rows).** Bucket
   rows into a few bins by in-degree; within a bin, every row is padded to that bin's fixed length
   `L_b`, so the reduction over `L_b` is a fixed-count native loop — exactly what
   `AggregatedProducerComputation`/`LoopedWeightedSumComputation` already generate and what CI has
   exercised. Padded entries carry a value of `0` (or are gated with `Mask`), so they contribute
   nothing. Binning keeps the wasted work bounded (fly in-degrees span a handful to several thousand,
   so a single global `max` padding would be wasteful, but a few bins are not). This is the
   lower-risk path and the one the connectome plan names as the fallback.

The recommendation to the approver: pursue (1) first because it avoids padding entirely, but treat
(2) as the definition of done if (1) does not generalise cleanly across both backends. The choice,
and the measured reason for it, is recorded in the `SparseMatrix` Javadoc.

### The new computation

Whichever loop form wins, the reduction is a new `CollectionProducerComputation` in
`org.almostrealism.algebra.computations` — provisionally `SegmentedWeightedSumComputation` —
modelled on `LoopedWeightedSumComputation` but carrying **three** operands (`x`, `values`,
`columns`) and one output row per kernel thread. Per output row `i`:

```
y[i] = Σ (k in row i)  values[k] · x[ columns[k] ]
```

- `columns[k]` is read through the integer-exact ordering (or plainly when `cols < 2^24`) and used
  as the gather index into `x`.
- `values[k]` is read directly.
- The loop runs over row `i`'s range: data-dependent `[rowOffsets[i], rowOffsets[i+1])` in form (1),
  or `[0, L_b)` offset by the row's bin base in form (2).
- `rowOffsets` is read through `SplitIndexTraversalOrdering` so offsets past `2^24` are exact.

The existing `LoopedWeightedSumComputation` is not merely subclassed because its two-operand
constructor and its `InputIndexer`/`WeightIndexer` (which produce *arithmetic* indices, not reads of
a third array) cannot express a gather whose index is itself read from device memory. The new class
generalises the same native-loop/unrolled-inner-sum machinery to that case; shared structure (the
`Repeated` emission, the accumulator fold, the signature discipline that distinguishes kernels with
different index arithmetic) is inherited from `AggregatedProducerComputation`, not duplicated.

### The type-vs-ordering decision (documented in Javadoc)

`SparseMatrix` **holds its three arrays directly** rather than being a collection read through a
sparse `TraversalOrdering`.

*Correction (PR review, 2026-10-27).* The two reasons originally given here did not justify the
choice. The first was out of date when the plan was written: the ordering integration (task
82bda0b7) closed on 2026-10-25, before this plan was created, and ordered collections are correct
kernel inputs on master. The second ("CSR is three arrays, not a reordering of one collection")
describes the representation already chosen rather than testing the alternative, and it conflates
the storage of an ordering's map with the data it orders. The reasons that do hold, for any CSR
ordering, are:

- **Scale.** The logical element count of an ordered collection is an `int`: `PackedCollection`
  rejects a shape of more than `Integer.MAX_VALUE` elements, and kernels address logical elements
  through `int` sizes. A 166,000 × 166,000 matrix has about 2.8 × 10^10 logical elements, so it
  cannot be expressed as an ordered `(rows, cols)` collection at all.
- **Direction of the map.** An ordering maps logical index → physical position, so any consumer
  (including dense `matmul`) reads every logical element: `rows × cols` reads per product. The
  product needs the inverse map, entry → logical position, which is exactly `rowOffsets` and
  `columns`.
- **No closed form.** The logical → physical map of CSR is a search within a row. The one
  searching ordering, `IndexMaskTraversalOrdering`, renders one conditional per stored entry into
  every read and is documented as suitable for small masks only.

The ordering machinery is still used where it fits: the index arrays are stored in the
integer-exact layout of `SplitIndexTraversalOrdering`. The same reasons are stated in the
`SparseMatrix` Javadoc.

### Placement

`SparseMatrix` is general linear algebra, not connectome-specific, so it belongs in `compute/algebra`
(`org.almostrealism.algebra`), as the task and the connectome plan both state. The product is a
method on `SparseMatrix` (behaviour on the type whose state it reads); the reduction computation goes
in `org.almostrealism.algebra.computations` beside `WeightedSumComputation` and
`LoopedWeightedSumComputation`. No new Maven module is created.

## Success criteria

A reviewer will know the task is done when:

1. **Dense parity.** On random sparse matrices of several shapes and densities, `A.multiply(x)`
   matches `matmul(denseA, x)` within tolerance, where `denseA` is the sparse matrix materialised to
   dense. The suite includes **rows with zero non-zeros** and **very long rows**, run on **both
   Metal and native** backends. Tests extend `TestSuiteBase`; each `@Test` carries an explicit
   timeout.

2. **Degenerate rows.** Empty rows produce `0`; a matrix of all-empty rows produces the zero vector;
   a single dense row behaves like a dense dot product. (Covered within the parity suite.)

3. **Large-scale check.** About 166k rows and tens of millions of non-zeros: memory footprint and
   time per product are recorded. This is `@TestDepth`-gated or `skipLongTests`-guarded so it does
   not run in the ordinary CI shard, and it must fit the CI memory envelope when it does run
   (configurable via `AR_HARDWARE_MEMORY_SCALE`).

4. **Integer-exact offsets above `2^24`.** A product whose row offsets exceed `2^24` returns exactly
   the right result, built on the `SplitIndexTraversalOrdering` representation. Following the lesson
   recorded in `dynamic-indexing.md`, the test uses a payload that is exact and distinct between
   adjacent addresses (not `data[i] = i`, which rounds just as a float index would), so exactness is
   observable through *which* element is read. Memory sized to fit CI.

5. **Quality gates.** `mvn clean install -DskipTests` succeeds; the build validator passes
   (checkstyle, code_policy, test_timeouts, duplicate_code); no Producer-pattern violations (all
   arithmetic is expressed as Producers/Expressions — no Java-side element loops, no `toDouble`/
   `toFloat` in computation code).

## Dependencies

- **Blocking task 2c0793a7 — Integer-exact index storage (CLOSED, merged).** Delivered
  `SplitIndexTraversalOrdering`, `PackedCollection.ofSplitIndices`, and `dynamic-indexing.md`. This
  task builds directly on it for the index arrays. Verified present on `master`.
- **Not a dependency: task 82bda0b7 (sparse `TraversalOrdering` through `TraversableExpression`).**
  The connectome plan and the task description both state the product does not need a compact
  ordering and must not wait on that integration. (That integration had in fact already landed
  when this plan was written; see the corrected type-vs-ordering decision above for why
  `SparseMatrix` still holds its arrays directly.)
- **Platform: macOS node required for implementation.** The "done when" criteria require parity *on
  Metal*, and Metal is available only on macOS FlowTree nodes. The large-scale check also wants a
  measurement per backend. A `requiredLabels: platform: macos` setting is filed beside this plan
  (`docs/plans/task-20261026-165818-workstream.yaml`) so the implementation workstream runs on a
  matching machine.

## Open questions

1. **Data-dependent loop bound vs ELL — which ships?** The plan recommends attempting the
   data-dependent `Repeated` bound first and committing to ELL if it does not generalise across
   native and Metal. The approver should confirm this ordering, and in particular whether a correct
   ELL implementation alone is an acceptable "done" if the data-dependent bound proves fragile (the
   task's framing suggests yes).

2. **ELL bin count and boundaries.** If ELL is used, how many degree bins, and chosen how (fixed
   boundaries, quantiles of the degree distribution, powers of two)? This trades padding waste
   against the number of distinct kernels compiled. Suggest deciding empirically against the MaleCNS
   degree distribution during implementation and documenting the choice.

3. **Output shape convention.** Should `multiply(x)` return `(rows)` or `(rows, 1)`? Dense `matmul`
   is not uniformly shaped on its matrix–vector path: the `weightedSum` path (large outputs) returns
   a 1-D `(rows)` result for a 1-D vector input, and a `(n, 1)` column vector is trimmed to a 1-D
   input before reaching that path, so it too yields `(rows)`; only the zero-vector shortcut returns
   `(rows, 1)`. So `(rows)` both matches the dense large-output path and is the connectome's natural
   per-step shape, while the `(rows, 1)` column-vector form surfaces only in the shortcut paths.
   Recommend `(rows)` with reshape parity in the tests; approver to confirm.

4. **Which index arrays need the split representation?** `rowOffsets` and flat addresses into
   `values`/`columns` can exceed `2^24` at tens of millions of non-zeros and must be integer-exact.
   `columns` *values* are presynaptic neuron ids (`< cols`, ~166k `< 2^24`) and can stay plain.
   Confirm this split-vs-plain boundary, and whether `SparseMatrix` should choose it automatically
   from `nnz`/`cols` or require the caller to say.

5. **Construction / ingest surface.** How are the three arrays supplied — raw `PackedCollection`s, a
   COO-to-CSR builder, or both? Full connectome ingest is a separate task (2218ffdf); this task needs
   only enough of a constructor to build test matrices and to accept pre-built CSR arrays. Keep the
   ingest surface minimal here.

## Follow-ups to file when this lands

As the task directs, file these as new tracker tasks in the Common project (noting PR/plan links):

- **Transposed (CSC) sparse matrix–vector product** — the input gradient of `y = A·x`; has a
  structural form, no full Jacobian.
- **Reduced deltas for the sparse product** — `delta(target, ReduceAxis, weight)` for both the input
  and the values; structural forms for both.
- **Event-driven / scatter-add propagation** — the cheaper-at-low-firing-rate alternative; needs a
  scatter-add primitive and is a later optimisation.

## Outcome

- **Open Question 1:** the data-dependent `Repeated` bound shipped; ELL was not needed. It
  compiles and runs correctly on Metal (FP32) and native (FP64).
- **Open Question 3:** `multiply(x)` returns `(rows)` for a vector; an operand of shape
  `(cols, n)` yields `(rows, n)`.
- **Open Question 4:** both `rowOffsets` and `columns` use the split layout, so neither the
  offsets nor the column count is limited to `2^24`.
- **Open Question 5:** `SparseMatrix.of(rows, cols, int[] rowOffsets, int[] columns,
  PackedCollection values)` takes host index structure (uploaded through
  `PackedCollection.ofSplitIndices`) and a device collection of values, as the host→device write
  policy requires; the constructor accepts pre-built device collections.
- **Scale:** 166,000 rows, about 19.9M non-zeros: about 240 MB and 5.6 ms per product on Metal,
  480 MB and 13 ms per product on native.
- **Follow-ups filed:** ce31398a (CSC transposed product), cb9de664 (reduced deltas),
  eb28f816 (event-driven scatter-add).
