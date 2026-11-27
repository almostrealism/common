---
status: proposed
created: 2026-11-27
closed:
tracker: [eb28f816-bb3c-4d8e-a4ca-f945aa1d3684]
---

# Event-driven scatter-add propagation for sparse connectivity

- **Tracker task:** eb28f816-bb3c-4d8e-a4ca-f945aa1d3684 — "Event-driven
  scatter-add propagation for sparse connectivity"
- **Release:** Common 0.78
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
   across the `LanguageOperations` backends (`base/hardware`, `base/code`).
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
(native), and `JavaScriptLanguageOperations`. The primitive must be expressed in
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
   sparse matrices of several shapes and densities, including rows/columns with
   zero entries and very long columns, on Metal (and native where the native
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

- **ce31398a — "Transposed (CSC) sparse matrix–vector product" (Common 0.78,
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
   native float atomic add and that a `atomic_cmpxchg` CAS loop is the intended
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
