# Make sparse TraversalOrdering work through TraversableExpression

- **Tracker task:** [82bda0b7] Make sparse `TraversalOrdering` work through `TraversableExpression`
- **Release:** Common 0.76
- **Related task:** [87b95b81] Sparse traversal support (wants the same `IndexSet` machinery)
- **Phase context:** Phase 1, task 1 of `docs/plans/CONNECTOME_MODEL_ORGANISM.md`

## Motivation

The platform has two sparse representations and the task picks the better one: a
`PackedCollection` whose shape carries a sparse `TraversalOrdering`. It is the better design
because it keeps the data in device memory and reuses the ordinary collection machinery, where
`Tensor` lives on the host and grows unusable past a modest number of non-zeros. But the ordered
collection "cannot be relied on, because `TraversalOrdering` is poorly integrated with
`TraversableExpression`." Today an ordering only works when every index resolves to a compile-time
constant on the host; it cannot appear in generated kernel code, and no computation can even carry
an ordering through to its output.

Making an ordered (compact / sparse) collection behave correctly as the input to arbitrary
computations is the prerequisite for using sparse `PackedCollection`s as ordinary collections. That
capability is needed directly by the connectome model organism (the sparse weight matrix and its
matrix–vector product, Phase 1) and by sparse Jacobians in automatic differentiation. The closely
related task [87b95b81] "Sparse traversal support" depends on this integration landing first.

## Current state (what the code shows)

An ordering is applied in exactly two places, and the generated-code place is incomplete:

1. **Host read path** — `MemoryData.toDouble(int)` applies `getMemOrdering().indexOf(int)` and
   already treats a negative physical index as the sentinel for "absent" (`if (index < 0) return
   0.0`). `getMemOrdering()` composes a view's own `getDelegateOrdering()` with its delegate's
   ordering. `TraversalOrdering.indexOf(int)` defaults to
   `indexOf(IntegerConstant).intValue().orElseThrow()`, so the host path only works when the
   mapped index collapses to a constant.
2. **Generated-code path** — `CollectionVariable.getValueAt(Expression)` applies
   `getShape().getOrder().indexOf(index)` and then `reference(index)`. It has **no membership
   gate**: a mapped physical index of `-1` (the compact "absent" marker) reads the wrong memory
   element instead of yielding `0`. This is the one place generated code applies an ordering, and
   it is the integration surface the task is about.

The orderings themselves:

- `TraversalOrdering extends IndexSet`; its default `containsIndex(index)` is `indexOf(index) >=
  0`. `indexOf(Expression)` is the single abstract method.
- `ExplicitIndexTraversalOrdering extends IndexExpressionTraversalOrdering`, whose
  `indexOf(Expression) = indices.getValueAt(idx).toInt()` is **already symbolic** — a direct
  logical→physical lookup table — *provided* the backing `indices` collection is delivered to the
  kernel as an argument. `getLength()` returns the maximum index value rather than max+1 (the task
  asks to verify; see Approach).
- `IndexMaskTraversalOrdering.indexOf(Expression)` does `idx.intValue().orElseThrow()` and scans
  `mask.toArray()` on the host, so it throws for any non-constant kernel index and can never appear
  in generated code. Its mapping is an *inverse search*: the mask holds the logical positions that
  are present, so finding the physical slot for a logical index requires searching the mask.
- `RepeatTraversalOrdering.indexOf(idx) = idx.imod(length)` is already fully symbolic.

The pieces that refuse an ordered shape outright:

- `TraversableExpression.containsIndex` throws `UnsupportedOperationException` by default (TODO:
  exploit unique non-zero offset).
- `ReshapeProducer.containsIndex` throws `UnsupportedOperationException` when
  `shape.getOrder() != null`.
- `SlicingFeatures.pad(int[] axes, int depth, ...)` throws `UnsupportedOperationException` when
  `shape.getOrder() != null`.
- `CollectionProducerComputationBase`'s constructor sets `this.shape = outputShape.withOrder(null)`,
  stripping any ordering from the output, so no computation can produce an ordered result.

The failing tests are `CollectionOrderingTests.compactOrdering`, `maskOrdering` and
`repeatOrdering`, all `@TestProperties(knownIssue = true)`; they read through the host `valueAt`
path (`PackedCollection.valueAt` → `toDouble`). `repeatOrderingProduct` (a real computation over a
repeat-ordered input) already passes.

A further passing test in the same class is **not** `knownIssue`, and it bounds what is actually
broken on the host path:

- `explicitIndexOrderingAppliedOnce` reads a four-element *permutation* (`ExplicitIndexTraversalOrdering`
  over `pack(2, 0, 3, 1)`) through `toDouble` and asserts each element round-trips. Confirmed passing
  (run 2026-10-23). So on the host, `ExplicitIndexTraversalOrdering.indexOf(int)` *does* collapse its
  `indices.getValueAt(const)` read to a constant — a full permutation with no absent slots works today.
- That the permutation passes while `compactOrdering` (the same host `ExplicitIndex` path, but with
  `-1` absent markers) and `maskOrdering` (constant indices, so the host `orElseThrow` scan is
  satisfiable) are `knownIssue` means the host failures are **not** explained by the
  constant-collapse limitation in item 1 — that condition is met for all three. The remaining
  difference is the absent-marker (`-1`) and compact-sizing handling, which points at the host
  absence gate in `toDouble` and/or the `getLength()` sizing below, not at symbolic `indexOf`. The
  implementer must root-cause each host failure against the code (these tests are filtered out of a
  normal run as `knownIssue`, so reproduce them explicitly) rather than assume the generated-code
  fixes below will turn them green — see Open Question 5 and the scope↔done-when note in Scope.

## Scope

In scope — everything needed for an ordered collection to be a correct *input* to computations:

1. **A symbolic membership/absence gate in the generated-code path.** `CollectionVariable.getValueAt`
   must gate the ordered read on membership: when `getShape().getOrder().containsIndex(index)` is
   false, the value is `0`; otherwise read `reference(order.indexOf(index))`. This fixes the compact
   `-1`-means-absent semantics inside kernels, mirroring what the host path already does.
2. **A working default `TraversableExpression.containsIndex`.** Replace the throwing default with a
   real implementation so an ordered `TraversableExpression` can answer membership in generated
   code. The natural definitions are: when the expression carries an ordering, delegate to the
   ordering's `containsIndex`; otherwise fall back to a bounds test against the shape's size (and/or
   the unique-non-zero-offset analysis the existing TODO names). Decide the single correct default
   and override where a type knows better.
3. **Symbolic `indexOf` for the sparse orderings.**
   - `ExplicitIndexTraversalOrdering` (direct table): confirm it already produces a symbolic
     `indexOf`, and make its backing index collection reach the kernel as an argument (the core
     integration work — see Approach). Verify/fix `getLength()` to `max + 1`.
   - `IndexMaskTraversalOrdering` (inverse search): rewrite `indexOf(Expression)` to build a
     symbolic search over the device-resident mask (no `orElseThrow`, no `toArray()` host scan), so
     it can appear in generated code. This is the "a compact sparse ordering can be expressed
     symbolically: a device-resident index/mask collection, not a host scan" criterion.
4. **Let ordered shapes pass through `ReshapeProducer.containsIndex` and `SlicingFeatures.pad`**
   instead of throwing, now that the ordering can answer `containsIndex`/`indexOf` symbolically.
5. **The ordered-output decision.** Decide whether computations may *produce* ordered outputs
   (`CollectionProducerComputationBase.withOrder(null)`), implement it or record the decision in
   Javadoc on the stripping site. See Open Questions — the recommended decision is to **keep the
   strip and document why**, because producing a compact/sparse output is a scatter/segmented
   reduction, which is the separate Phase 1 task [87b95b81]/[66b57639], not this one.
6. **Tests:** remove the three `knownIssue` flags once the tests pass, and add new tests for an
   ordered collection as the input to element-wise, reshape, subset, pad and matmul computations,
   each run on Metal and native.

**scope↔done-when note.** Items 1–3 above are generated-code / kernel-argument work, but the three
`knownIssue` tests in success criterion 1 are host reads (`PackedCollection.valueAt` → `toDouble`),
and the host absence gate (`index < 0 → 0`) already exists in `MemoryData.toDouble`. So item 1 (the
*generated-code* gate) does not by itself make those host tests pass. The implementer should expect
the compact/mask host fixes to live on the host read path or in `getLength()` sizing (item 3's
`max + 1`), and must confirm the exact cause first (Open Question 5). The done-when therefore
needs a host-path fix wired to it explicitly; today the scope lists only the generated-code surfaces
by name, so the approver should treat "the three `knownIssue` tests pass" and "`indexOf` renders
symbolically in a kernel" as two distinct deliverables rather than one.

Out of scope (named as dependencies / follow-ups, not widened into here):

- The sparse matrix type and the segmented-reduction sparse matrix–vector product
  ([66b57639]) — it consumes this integration.
- Avoiding computation/storage of values outside an index set for delta outputs — that is
  [87b95b81] "Sparse traversal support", which this unblocks.
- Integer-exact index storage on FP32 backends ([2c0793a7]): orderings store indices as floating
  point, and Metal is exact only to 2^24. For the small shapes in this task's tests that is not a
  problem, but a production sparse matrix needs [2c0793a7]. Flagged, not solved here.

## Approach

### The central integration gap

The ordering lives on the `TraversalPolicy` (the shape), which is detached from the computation's
argument graph. `ExplicitIndexTraversalOrdering`/`IndexMaskTraversalOrdering` hold a backing
`PackedCollection` (or `TraversableExpression`) of indices/mask, but that collection is not
registered as a kernel argument. That is why `indexOf` only works when it collapses to a host
constant: there is no bound variable to reference in the generated source. The core of the work is
making an ordering's backing index/mask collection a first-class kernel argument, so
`order.indexOf(index)` renders as a memory read of that argument inside the kernel.

Confirm the exact mechanism with `ar-profile-analyzer` before writing code: wire a small profiled
reproduction (an ordered input into `c(2).multiply(...)`), run it under `AR_HARDWARE_DRIVER=mtl`,
and read the generated kernel source and argument offsets with `get_source`. The compact/mask
read bug is read directly off the generated source; it is not to be theorised about. The
implementer must use `ar-profile-analyzer` first for every wrong-value/0.0 investigation per the
project rule.

Likely home for the argument binding: the ordering (or a `TraversableExpression` adapter over it)
must participate in `prepareScope`/argument provisioning the way a `CollectionVariable`'s producer
does. `IndexExpressionTraversalOrdering` already models an ordering as reading from a
`TraversableExpression`, which is the right abstraction; the gap is delivering that expression's
collection into the scope. The design must honour the `TraversalOrdering`/`TraversableExpression`
interfaces — no `instanceof` on a particular ordering implementation in `CollectionVariable`; the
membership gate and index remap go through `containsIndex`/`indexOf`, which every ordering answers.

### Method/behaviour placement

- The membership gate belongs in `CollectionVariable.getValueAt` (the one place ordering is applied
  in generated code), expressed through `TraversalOrdering.containsIndex`/`indexOf` — not special
  cased per ordering type.
- `containsIndex` as a default belongs on `TraversableExpression` (where the throwing TODO is), and
  on `TraversalOrdering` the default already exists (`indexOf >= 0`).
- The symbolic inverse search belongs on `IndexMaskTraversalOrdering` itself (it is that type's
  `indexOf`), not in a helper.
- `getLength()` correctness belongs on `ExplicitIndexTraversalOrdering`.
- Reshape/pad fixes belong on `ReshapeProducer` and `SlicingFeatures` respectively.

### The `getLength()` verification

For indices `[2,0,3,1]` the backing memory needs 4 slots, so the covered length is `max+1 = 4`,
but `getLength()` returns `max() = 3`. This bites when a collection allocates its own backing by
the order length (`PackedCollection`'s required-length computation uses
`shape.getOrder().getLength()`). The delegate-mode tests do not exercise it (they size from the
delegate), which is why it has gone unnoticed. The plan treats this as a real off-by-one to fix to
`max + 1`, with a direct `getLength()` test and its Javadoc corrected (the current Javadoc
documents the buggy behaviour). The implementer confirms against the allocation path before
changing it.

## Success criteria

A reviewer can confirm the task is done when:

1. `CollectionOrderingTests.compactOrdering`, `maskOrdering` and `repeatOrdering` pass with the
   `@TestProperties(knownIssue = true)` flag removed. (Removing a `knownIssue` flag from a now-passing
   test is a strengthening, not a weakening, and is allowed.)
2. A compact sparse ordering is expressed symbolically — its `indexOf` renders as a read of a
   device-resident index/mask collection in the generated kernel source (verifiable via
   `ar-profile-analyzer get_source`), with no `orElseThrow`/host `toArray()` scan on the kernel
   path.
3. New tests show an ordered collection giving correct results as the input to **element-wise,
   reshape, subset, pad and matmul** computations, each run on **Metal and native**. These are new
   tests, most naturally added to `CollectionOrderingTests` (engine/utils) or a sibling; each
   `@Test` carries a `timeout`.
4. `ReshapeProducer.containsIndex` and `SlicingFeatures.pad(int[] axes, ...)` no longer throw for an
   ordered shape, and are covered by the new reshape/pad tests above.
5. The ordered-output question is resolved: either computations can produce ordered outputs (with a
   test), or `CollectionProducerComputationBase`'s `withOrder(null)` strip carries Javadoc stating
   the decision and its reason.
6. `mcp__ar-build-validator__start_validation` is clean (checkstyle, code_policy, test_timeouts,
   duplicate_code), and the targeted test classes pass on both backends.

## Dependencies

- **Metal hardware (macOS node).** The done-when explicitly requires the new tests to run on Metal
  as well as native. Only macOS FlowTree nodes provide Metal, so implementation must run on a macOS
  node. Recorded in `docs/plans/task-20261023-010642-workstream.yaml`.
- No blocking tracker tasks (`blocked_by` is empty). This task is itself the blocker for the sparse
  matrix work ([66b57639]) and for [87b95b81].

## Open questions

1. **Should computations produce ordered outputs?** Recommended: **no, for this task** — keep
   `withOrder(null)` and document the reason (producing a compact/sparse output is a
   scatter/segmented reduction owned by the sparse-matrix task [66b57639] / sparse-traversal task
   [87b95b81]). The approver should confirm this scoping, since the alternative (implementing
   ordered outputs here) is a materially larger change and risks overlapping [87b95b81].
2. **What is the one correct default for `TraversableExpression.containsIndex`?** Membership against
   the shape size, delegation to a carried ordering, or the unique-non-zero-offset analysis the TODO
   names — or a composition. This needs the implementer's judgement against the ~20 implementors;
   the plan commits only to replacing the throw with a correct, interface-honouring default.
3. **Index precision on FP32/Metal.** The ordered tests here use tiny shapes, so float indices are
   exact. A production sparse matrix needs integer-exact indices ([2c0793a7]). Confirm the new
   tests stay within 2^24 so a float-index ordering is not silently validated at a scale it cannot
   support; note the limitation where the ordering stores indices.
4. **Is `getLength()` returning max (not max+1) genuinely a bug, or relied upon somewhere?** The
   plan treats it as a bug; the implementer must grep callers of `TraversalOrdering.getLength()` and
   the `PackedCollection` allocation path before changing it, and store the finding. Note
   `MemoryData.getDelegatedLength()` already consumes `getDelegateOrdering().getLength()` (falling
   back to `getMemLength()` when the ordering declares no length), so the three `knownIssue` tests
   (which build delegated views) may already be exposed to this value — a reason to settle the
   `getLength()` fix before the host tests, not after.
5. **Why exactly do `compactOrdering` and `maskOrdering` fail on the host today?** The sibling
   `explicitIndexOrderingAppliedOnce` reads a full permutation through the same host `ExplicitIndex`
   path and passes (confirmed 2026-10-23), and `maskOrdering` uses only constant indices, so the
   constant-collapse limitation (Current state item 1) is satisfied for all three — it does not
   explain the failures. The real cause is unconfirmed and is most likely the `-1` absent-marker
   handling on the host read path and/or the `getLength()` sizing (Open Question 4), *not* symbolic
   `indexOf`. The implementer must reproduce each failure (the tests are filtered out of a normal run
   as `knownIssue`) and root-cause it against the code before assuming any scope item fixes it. This
   is the load-bearing question for success criterion 1: the plan's scope names generated-code
   surfaces, but the done-when is three host reads.
6. **How costly is a symbolic `IndexMaskTraversalOrdering.indexOf` in a kernel?** The inverse search
   has no closed form: finding the physical slot for a logical index means scanning the mask, so a
   symbolic version renders as an O(mask length) search per lookup inside the kernel. That is
   acceptable for the small, filtered masks this task tests, but it does not scale to the large
   sparse matrix of the connectome work — which the design in `docs/plans/CONNECTOME_MODEL_ORGANISM.md`
   builds on the **direct-table** form (CSR / gather, i.e. `ExplicitIndexTraversalOrdering`), not on
   a mask inverse search. The approver should treat the symbolic mask `indexOf` as a correctness
   feature for small masks, and confirm that no large-scale consumer is expected to route through it.

No new tracker tasks were filed: every out-of-scope item above is an already-filed Phase 1 task
([66b57639], [87b95b81], [2c0793a7]) named as a dependency, so there is nothing new to triage.

## Estimated complexity

**Large.** The work spans three modules (`base/code`, `base/hardware`, `compute/algebra`) and the
engine/utils test module, and its hardest part — delivering an ordering's backing collection into
the kernel argument graph so `indexOf`/`containsIndex` render symbolically — is a genuine change to
how orderings integrate with code generation, not a set of local fixes. The symbolic inverse search
for `IndexMaskTraversalOrdering` and the dual-backend (Metal + native) test matrix add further
breadth. If the approver wants it split, the natural seam is: (a) the generated-code membership
gate + symbolic `indexOf` + `containsIndex` default + the three `knownIssue` tests (the core), then
(b) the reshape/pad/matmul input coverage and the ordered-output decision as a follow-on, since (b)
only becomes testable once (a) lands.
