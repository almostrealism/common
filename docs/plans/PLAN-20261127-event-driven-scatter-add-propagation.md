---
status: completed
created: 2026-11-27
closed: 2026-11-27
tracker: [eb28f816-bb3c-4d8e-a4ca-f945aa1d3684]
---

# Event-driven scatter-add propagation for sparse connectivity

- **Tracker task:** eb28f816-bb3c-4d8e-a4ca-f945aa1d3684 — "Event-driven
  scatter-add propagation for sparse connectivity"
- **Release:** the Common release under development (the version in the root `pom.xml`)
- **Follow-up of:** 66b57639 (closed) — "Sparse matrix type and sparse
  matrix–vector product with a segmented reduction"

## Motivation

The deterministic sparse product delivered by task 66b57639,
`SegmentedWeightedSumComputation` (`compute/algebra`,
`org.almostrealism.algebra.computations`), computes every row's full segmented
sum each step. For the signed compressed-sparse-row connectome `W[post, pre]`
(see `docs/internals/neuron-level-connectome-simulation.md`), one kernel thread
per postsynaptic neuron sums over every stored incoming edge, so the cost is
`O(E)` in the number of stored synapses **regardless of how many neurons
actually fired**.

Spiking activity is sparse: at a realistic firing rate only a small fraction of
presynaptic neurons spike on any given step, yet the forward product still
visits all `E` edges. When the activity vector is binary and sparse it is
cheaper to propagate only *from* the active presynaptic neurons — for each
spike, add that neuron's outgoing weights into the neurons it targets — so the
work scales with the number of active edges rather than with `E`.

The parent task listed "event-driven / scatter-add propagation" explicitly as
out of scope, to be filed as a follow-up when the deterministic product landed.
This task is that follow-up. It is a **later optimisation beside** the
deterministic CSR product, not a replacement: the forward product stays the
default, and the event-driven step is an additional path a caller chooses when
its activity vector is sparse.

## Scope

In scope:

1. **An atomic scatter-add write primitive.** A computation whose kernel threads
   add their contributions into *data-dependent* output positions, with
   concurrent writes to the same position resolved by an atomic
   add. No backend generates atomics today, so this is new code generation
   across the `LanguageOperations` backends (`base/code`, `base/hardware`,
   `base/hardware-js`).
2. **An event-driven propagation computation** in `compute/algebra`
   (`org.almostrealism.algebra.computations`), beside
   `SegmentedWeightedSumComputation`, that takes a sparse connectivity matrix
   and a binary activity vector and produces the same vector as
   `matmul(matrix, activity)` by pushing each active presynaptic neuron's
   outgoing weights into its targets through the scatter-add primitive.
3. **Parity tests** proving the event-driven step matches the deterministic
   segmented-sum product on a binary activity vector, over random sparse
   matrices including empty rows/columns and long columns.
4. **A low-activity benchmark** recording that the event-driven step is measured
   faster than the segmented sum at low activity.

Out of scope (named as dependencies / follow-ups below, not widened into this
task):

- The column/CSC enumeration of a CSR matrix needed to read each presynaptic
  neuron's *outgoing* edges — this is the sibling task **ce31398a**.
- Reduced deltas / autodiff through the event-driven path (the forward product
  itself is forward-only; the event-driven step is a performance variant of it).
- Active-index stream compaction (launching threads only for active neurons) —
  proposed as a refinement after a correct first version (see Open questions).
- Auto-selection of the event-driven path inside `MatrixFeatures.matmul`. The
  task says it is a path beside the deterministic product, so a caller selects
  it explicitly.

## Approach

### The operation, in terms of the existing representation

The connectome is `W[post, pre] = sign(pre) * count`, an `[N, N]`
`PackedCollection` whose `TraversalPolicy` carries a
`CompressedRowTraversalOrdering` and whose memory holds only the `E` stored
entries; `matmul(cp(W), spikes)` dispatches to `SegmentedWeightedSumComputation`
and yields the summed signed synaptic input at every neuron
(`docs/internals/neuron-level-connectome-simulation.md`, section 2). `W`'s CSR
ordering enumerates **rows (postsynaptic neurons)**: row `i` lists the *incoming*
edges `(pre = column, weight)` of neuron `i`.

The forward product reads, for each post neuron `i`, its incoming edges and sums
`weight * activity[pre]`. The event-driven step inverts this: for each active
presynaptic neuron `j` (where `activity[j] != 0`), it reads `j`'s **outgoing**
edges — the entries of **column `j`** of `W` — and adds each `weight` into the
output at its target post neuron. Two distinct active presynaptic neurons may
have an edge to the same post neuron, so their kernel threads write the same
output position concurrently: this is the write collision that forces an atomic
add, and is why the deterministic (one-thread-per-output, no-atomics) product
cannot simply be rewritten.

Reading column `j` of a CSR matrix is exactly the compressed-sparse-column (CSC)
enumeration that sibling task **ce31398a** delivers: `transpose(W)` presented as
a row-enumerable sparse `PackedCollection` of `Wᵀ` (shape `(cols, rows)` with a
`CompressedRowTraversalOrdering`). The event-driven computation therefore
enumerates the rows of `Wᵀ` (the columns of `W`) — the same `OrderingSegments`
machinery `SegmentedWeightedSumComputation` already uses (`start`, `length`,
`offset`) — restricted to the active presynaptic neurons, and scatters each
entry's weight into `offset(position)` (its target post neuron).

### The atomic scatter-add primitive

`SegmentedWeightedSumComputation` extends `RepeatedProducerComputation`, where
one thread owns one output element and writes it exactly once (its Javadoc notes
"with no atomics"). The event-driven step is the opposite shape: a thread owns a
*source* (an active presynaptic neuron), loops its outgoing edges, and writes
*many* output positions — positions chosen at run time from the matrix ordering,
which collide across threads. This is a genuinely new write mode, so it needs a
primitive the backends can emit.

The write is `out[target] += contribution` executed atomically. Backend code
generation lives in the `LanguageOperations` hierarchy
(`base/code/.../io/almostrealism/lang/LanguageOperations.java` and its
subclasses): `MetalLanguageOperations`, `OpenCLLanguageOperations`,
`CudaLanguageOperations`, `CLanguageOperations`/`CJNILanguageOperations`
(native, all in `base/hardware`), and `JavaScriptLanguageOperations` (in
`base/hardware-js`, module `ar-hardware-js`). The primitive must be expressed in
the expression/scope layer (`base/code`, `io.almostrealism.expression` /
`io.almostrealism.scope`) as an atomic accumulate into a computed memory
position, then rendered per backend:

- **Metal:** native `atomic_fetch_add_explicit` on an `atomic_float` /
  `device atomic_float*` view of the output buffer (Metal is the primary
  validation backend).
- **CUDA:** native `atomicAdd(float*, float)`.
- **OpenCL:** no native float atomic add in the base profile — a
  `atomic_cmpxchg` compare-and-swap loop on the output reinterpreted as `uint`.
- **Native C / JavaScript:** depends on whether the generated kernel executes
  its global range concurrently or sequentially (Open question 1); a sequential
  kernel can accumulate with a plain `+=`, a concurrent one needs a true atomic.

The concrete Java surface (a new `Expression`/`Scope` node for an atomic
accumulate, versus a flag on the existing output-assignment path) is an
implementation decision for the primitive sub-task; whichever is chosen, it must
go through the backend-neutral expression layer and be *rendered* per backend,
not special-cased in the computation. The generated-code rule applies: the
primitive is a property of the expression tree, not a string the renderer
splices in.

### Where the new computation belongs

The event-driven computation is a general sparse operation — one domain
(bioscience) is today's consumer, but the primitive is not biology-specific — so
it belongs in `compute/algebra` (`org.almostrealism.algebra.computations`),
beside `SegmentedWeightedSumComputation`, and is exposed the same way: as a
computation that a feature method constructs. It is **not** auto-dispatched by
`matmul`; a caller that knows its activity vector is binary and sparse selects
it. Like the segmented sum it is an isolation target (it compiles to a kernel of
its own and its consumers read its output).

### First viable version and the low-activity win

A correct first version launches **one thread per presynaptic neuron**: an
inactive neuron (`activity[j] == 0`) does nothing; an active neuron loops its
outgoing edges (the enumeration of row `j` of `Wᵀ`) and atomic-adds each
`weight` into the target. Work is then `O(N)` thread launches plus
`O(active edges)` real work, against the forward product's `O(E)`; at low
activity `active edges << E`, which is the measured win. Launching threads only
for the active neurons (stream compaction / prefix sum) is a further
optimisation left as a follow-up, so the first version does not depend on a
compaction primitive existing.

Two properties of this shape set it apart from the deterministic forward
product, and the implementation must handle both explicitly:

- **The output must be zeroed before the scatter.** Atomic accumulation adds
  into whatever the output buffer already holds, and a destination reused
  across simulation steps holds the previous step's input. The forward product
  never has this problem because each thread assigns its element exactly once.
  The event-driven step therefore needs a defined reset of the output to zero,
  ordered before the scatter kernel, every time it is evaluated (Open
  question 6).
- **The kernel range is the source count, not the output count.** The kernel
  launches one thread per presynaptic neuron (`cols` of `W`, the rows of `Wᵀ`)
  while its output has one element per postsynaptic neuron (`rows` of `W`).
  For the square connectome these happen to be equal, which can hide a range
  bug; the parity test must include non-square matrices so a computation that
  silently sizes its kernel by its output shape fails.

## Success criteria

A reviewer can confirm the task is done when:

1. A new computation in `compute/algebra`
   (`org.almostrealism.algebra.computations`) produces, for a sparse
   connectivity matrix and a binary activity vector, the same output as
   `matmul(cp(matrix), activity)`.
2. The atomic scatter-add primitive is generated by the Metal backend and
   defined for the other backends per the strategies above, exercised through
   the new computation (not only in a unit of its own).
3. **Parity test** (new test class under
   `engine/utils/.../org/almostrealism/collect/computations/test`, beside
   `CompressedRowOrderedComputationTests`): on a binary activity vector, the
   event-driven result equals the segmented-sum product **exactly** over random
   sparse matrices of several shapes (including non-square) and densities,
   including rows/columns with zero entries and very long columns, on Metal
   (and native where the native
   path supports the write). The parity is exact because the connectome's
   weights are integer-valued (`sign * count`) and the activity is binary, so
   every partial sum is integer and FP32-exact up to `2^24` regardless of the
   order in which the atomic adds accumulate.
4. **Low-activity benchmark** (a `@TestDepth`-gated or `skipLongTests` test):
   at a low active fraction the event-driven step is measured faster than
   `SegmentedWeightedSumComputation` on the same matrix, with the per-step time
   of both paths recorded. The benchmark also records the activity fraction at
   which the two cross over, so the regime where the event-driven path helps is
   documented rather than merely asserted.
5. Index exactness: target positions and entry offsets above `2^24` are handled
   exactly (the split integer-exact layout of `SplitIndexTraversalOrdering`, which
   `CompressedRowTraversalOrdering` already uses for its row offsets and columns),
   so the primitive is correct at connectome scale.

## Dependencies

- **ce31398a — "Transposed (CSC) sparse matrix–vector product" (same release,
  open, PR #1103, plan `docs/plans/PLAN-20261126-sparse-matrix-transpose-spmv.md`
  — on PR #1103's branch; not yet on `master`, so a reader on `master` will not
  find that file until #1103 merges).**
  The event-driven step reads each active presynaptic neuron's *outgoing* edges,
  which is the column (CSC) enumeration of the CSR connectome. ce31398a delivers
  `transpose()` on `CompressedRowTraversalOrdering` producing `Wᵀ` as a
  row-enumerable sparse `PackedCollection`, which this task's computation
  enumerates. This task should build on that rather than duplicate the
  column-enumeration machinery. If ce31398a has not landed when implementation
  starts, the approver should decide whether to block on it or to carry a
  minimal column view forward in this task (and fold it into ce31398a later).
- **Metal node** for atomics generation, correctness under real concurrency, and
  the benchmark (Success criteria 4). The workstream is pinned to macOS
  (`requiredLabels platform: macos`) for exactly this reason.

## Open questions

1. **Native / JavaScript kernel concurrency.** Does the native (`CLanguage`/JNI)
   kernel execute its global range concurrently (needing a true atomic) or
   sequentially (a plain accumulate suffices)? The implementer's first
   investigation should establish this before choosing the native write path.
   The parent task required parity "on Metal and native", so the native path
   must at least produce correct results even if its performance characteristics
   differ.
2. **OpenCL and Metal float-atomic availability.** Confirm the base OpenCL profile lacks a
   native float atomic add and that an `atomic_cmpxchg` CAS loop is the intended
   fallback, or whether an extension is assumed available. The same question
   applies to Metal: `atomic_float` is not available in every Metal Shading
   Language version, so the implementer should confirm the language version the
   Metal backend compiles against supports it before relying on it, and say what
   happens on a device that does not.
3. **First version vs compaction.** Is the one-thread-per-presynaptic-neuron
   version (with inactive threads returning early) an acceptable first
   deliverable, with stream compaction filed as a separate follow-up? The plan
   assumes yes.
4. **Floating-point determinism beyond the connectome.** Parity is exact only
   because the weights are integer-valued and the activity is binary. For a
   general real-valued sparse matrix the atomic-add order is non-deterministic,
   so results would match the deterministic product only within an FP tolerance.
   The plan scopes the exact-parity guarantee to the integer/binary case and
   documents this limitation; the approver should confirm that is the intended
   contract.
5. **Public surface.** Expose the computation through a `MatrixFeatures` method
   (e.g. an explicitly-named event-driven product) or as a directly-constructed
   computation? Either way it must not be auto-selected by `matmul`.
6. **Where the output reset lives.** The scatter needs its output zeroed before
   every evaluation (see "First viable version"). The reset and the scatter are
   necessarily two distinct operations: the reset writes every output position
   exactly once, the scatter then atomic-accumulates into those positions, and
   the second must observe the first as complete. What cannot work is *fusing*
   them into a single kernel pass over the source range — a reset interleaved
   with the scatter in one thread grid, where one thread zeroes an output
   position while another is already accumulating into it, because threads
   cannot be ordered across the whole range without a global barrier. That
   fused form is the one this plan rejects. The two viable choices both keep
   reset and scatter as separate operations; they differ only in who assembles
   them:
   - A separate zeroing computation that the caller composes before the scatter.
   - The scatter computation is itself a composite operation (an
     `OperationList`) of a reset sub-operation followed by the scatter
     sub-operation, so a caller gets the reset for free.

   Either way the two operations stay separate and the kernel boundary between
   them is still the optimizer's to choose — each sub-operation is an isolation
   target in its own right (`isIsolationTarget`), and neither the caller nor the
   composite calls `isolate()` or otherwise splits the graph into kernels by
   hand. This is consistent with the rule that only `Process.optimize` decides
   kernel boundaries: composing two operations is not the same as deciding where
   a kernel begins. The primitive sub-task (A) should decide between the two,
   because every future consumer of the atomic accumulate faces the same
   requirement.


### Recommended split

This is a large task carrying a novel cross-cutting primitive. It is cleaner to
land in two reviewable pieces, both still under this tracker task (or the second
filed as a follow-up once the first lands):

- **(A) The atomic scatter-add primitive.** The expression/scope node and its
  per-backend rendering, with a focused test that exercises concurrent additive
  writes into colliding positions directly. This is the hard, cross-cutting part
  and is independently verifiable.
- **(B) The event-driven propagation computation.** Built on (A) and on the CSC
  enumeration from ce31398a, with the parity test and the low-activity
  benchmark.

The approver should decide whether (A) warrants its own tracker task; if so, it
becomes a dependency of this one and should be filed before implementation
starts.

## Estimated complexity

**Large.** The event-driven computation itself is moderate, but it depends on a
genuinely new backend capability (atomic scatter-add across Metal/OpenCL/CUDA/
native) that touches the expression/scope layer and every `LanguageOperations`
backend, and on the CSC enumeration from a sibling task that is not yet landed.

## Outcome

Implemented on this branch; both pieces of the recommended split landed together.

- **Primitive (A).** `AtomicAccumulation` (`base/code`, `io.almostrealism.code`) is a
  `Statement` — deliberately not an `ExpressionAssignment`, so no assignment rewrite (such as
  accumulator promotion) can treat the element as thread-owned. It renders through the new
  `LanguageOperations.atomicAccumulation(destination, value)`, whose default *refuses*
  (`UnsupportedOperationException`) rather than emitting a lossy read-modify-write:
  - Metal: `metal::atomic_fetch_add_explicit` on a `device metal::atomic_float *` view, relaxed
    order; FP32 only (Metal has no FP64), FP64 refused. Compiles and runs on the macOS node, so
    the default Metal language version supports `atomic_float` there; a compiler predating
    MSL 3.0 rejects the kernel at compile time rather than losing updates (Open question 2).
  - CUDA: `atomicAdd` (float; double on compute capability 6.0+).
  - OpenCL: CAS loop, `atomic_cmpxchg` on a `uint` view (FP32) or `atom_cmpxchg` on a `ulong`
    view (FP64, requires `cl_khr_int64_base_atomics`); no float-atomic extension is assumed.
  - Native C / JNI: the native kernel runs its global range on several host threads
    (`CJNIPrintWriter` strides `global_id` by the parallelism), so a sequential `+=` is not safe
    (Open question 1). It renders a CAS loop with the `__atomic` builtins on an integer view of
    the element's width (32 or 64 bits); other widths are refused.
  - JavaScript: refused (default) — the backend generates code only and has no concurrent
    kernel execution to define an atomic against.
- **Computation (B).** `SegmentedScatterAddComputation` (`compute/algebra`,
  `org.almostrealism.algebra.computations`) is an operation that adds
  `matrix[p] * activity[j]` into `destination[offset(p)]` for the entries of every source `j`
  whose activity is non-zero. Its kernel range is the source count (`getCountLong`), not the
  destination size. It takes the source-major matrix (rows are sources, i.e. `transpose(W)`)
  directly, so it does not depend on ce31398a: a caller holding only `W` uses that task's
  `transpose()` once it lands, and the tests build both CSR layouts from the same entries.
  The activity is multiplied in, so the step is correct for any activity, not only binary.
- **Public surface (Open question 5).** `MatrixFeatures.scatterAdd(destination, matrix,
  activity)` returns the accumulating operation; `MatrixFeatures.scatterProduct(...)` returns the
  product. Neither is chosen by `matmul`.
- **Output reset (Open question 6).** `scatterProduct` is an `OperationList` constructed with
  compilation disabled, holding an in-place `a(destination, zeros(...))` of the *same*
  destination producer followed by the scatter. `isComputation()` is therefore false and the
  two members are dispatched as separate operations, in order (`OperationListRunner`), so the
  scatter observes the reset as complete and the two are never fused into one kernel. Remaining
  gap: `OperationList.flatten()` of a *compiling* parent would inline the two members; that is
  documented on `scatterProduct` and filed as a follow-up.
- **Exactness (Open question 4).** Exact parity is claimed only when every partial sum is
  exactly representable; the tests bound the weights to integers in `[-5, 4]` with binary
  activity, so every partial sum is at most `5 * sources` in magnitude, far below `2^24`.
  (The test weights are integers in `[-5, 4]` produced on the device; the weights of the
  transpose are gathered from them by a product with a permutation matrix.)
- **Compaction (Open question 3).** Not done; inactive threads return immediately.

Tests (`engine/utils`):

- `SegmentedScatterAddComputationTests`: exact parity against both the segmented-sum `matmul`
  and a reference product, on CPU (JNI) and GPU (Metal), for square, tall and wide matrices,
  very sparse/low-activity and dense/full-activity cases, each with an empty row, an empty
  column and a full (very long) column; 8192 sources colliding on one target; reset of a reused
  destination holding stale values over two passes; accumulation semantics of `scatterAdd`;
  the two-operation structure and source-count kernel range; rejection of a dense matrix. A
  mutation that rendered the C and Metal accumulation as a plain `dest = dest + v` failed the
  collision and dense-parity tests.
- `LanguageOperationsTest`: the rendering of every backend and the refusals.
- `lowActivityBenchmark` (`@TestDepth(2)`, `skipLongTests`): 50,000 neurons × 400 synapses
  (20M entries) on Metal. Per step, segmented sum vs event-driven:
  0.1% active 4.53 ms vs 0.88 ms; 1% 4.84 vs 0.92; 10% 5.10 vs 1.56; 25% 4.34 vs 2.30;
  50% 4.32 vs 4.95; 100% 4.78 vs 10.56 — the crossover lies between 25% and 50% activity.
  At 2M entries both paths were dispatch-bound (about 1 ms), so the benchmark uses the
  larger matrix.
- Index exactness (Success criterion 5): positions and offsets are read through the bound
  `CompressedRowTraversalOrdering`, i.e. its split integer-exact layout, exactly as the
  forward product reads them; no new index arithmetic is introduced. The benchmark's matrix
  stores 20M entries, so entry positions beyond `2^24` are read, and its results are checked
  against the forward product at every fraction.
