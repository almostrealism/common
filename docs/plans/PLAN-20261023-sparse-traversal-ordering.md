---
status: completed
created: 2026-10-23
closed: 2026-10-25
tracker: [82bda0b7-fce1-4805-8a3c-51beaf156508]
---

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
capability matters to the connectome model organism and to sparse Jacobians in automatic
differentiation. It is not, however, on the critical path of the connectome's sparse
matrix–vector product. `CONNECTOME_MODEL_ORGANISM.md` ("Sparse connectivity") states that the
product "does not need a compact ordering to work, so it is not blocked on fixing the ordering
integration". The sparse-matrix task [66b57639] likewise says "the product must not wait on that
integration", and leaves open whether its type is backed by a `TraversalOrdering` at all. The
closely related task [87b95b81] "Sparse traversal support" wants the same `IndexSet` machinery.
Neither task lists this one in its tracker `blocked_by`.

## Current state (what the code shows)

Key source files (by module):

| Module | Files |
|--------|-------|
| `base/code` (`io.almostrealism.collect`) | `CollectionVariable`, `TraversalOrdering`, `IndexSet`, `IndexExpressionTraversalOrdering`, `RepeatTraversalOrdering`, `TraversableExpression`, `CollectionExpression`, `Shape`, `TraversalPolicy` |
| `base/hardware` (`org.almostrealism.hardware`) | `MemoryData`, `mem/MemoryDataAdapter` |
| `compute/algebra` (`org.almostrealism.collect`) | `ExplicitIndexTraversalOrdering`, `IndexMaskTraversalOrdering`, `SlicingFeatures`, `computations/ReshapeProducer`, `computations/CollectionProducerComputationBase`, `computations/CollectionProvider`, `PackedCollection` |
| `engine/utils` (tests) | `org.almostrealism.collect.test.CollectionOrderingTests` |

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
  kernel as an argument. Today it is not: `indices` is a `PackedCollection`, so the call reaches
  `MemoryData.getValueAt(Expression)`, which only accepts a constant position and throws
  otherwise. `getLength()` returns the maximum index value rather than max+1 (the task asks to
  verify; see Approach).
- `IndexMaskTraversalOrdering.indexOf(Expression)` does `idx.intValue().orElseThrow()` and scans
  `mask.toArray()` on the host, so it throws for any non-constant kernel index and can never appear
  in generated code. Its mapping is an *inverse search*: the mask holds the logical positions that
  are present, so finding the physical slot for a logical index requires searching the mask.
- `RepeatTraversalOrdering.indexOf(idx) = idx.imod(length)` is already fully symbolic.

The pieces that refuse an ordered shape outright:

- `TraversableExpression.containsIndex` throws `UnsupportedOperationException` by default (TODO:
  exploit unique non-zero offset). Not every type reaches that default:
  - `CollectionExpression` overrides it with `Shape.super.containsIndex(index)`, and
    `Shape.containsIndex` is `getShape().getOrder().containsIndex(index)`. So `CollectionVariable`
    (which implements `CollectionExpression`) **already delegates membership to its ordering**.
    That is the delegation scope item 2 proposes. But it dereferences `getOrder()` without a null
    check, so it throws a `NullPointerException` for an unordered shape. Today the generated-code
    membership gate (scope item 1) could only call it when an ordering is present.
  - `MemoryDataAdapter` (and so `PackedCollection`) overrides it only to call
    `MemoryData.super.containsIndex`, which is the throwing `TraversableExpression` default.
  - Other overrides (`TraversableExpressionComputation`, `CollectionProducerComputationBase`,
    `InputStub`, `ArithmeticSequenceExpression`, `ConditionalExpressionBase`) either compute
    membership themselves or forward to a supertype default.
  - Apart from overrides that forward to another `containsIndex` (the ones above, plus
    `ConditionalIndexExpression.containsIndex(int)`), no production code calls
    `IndexSet.containsIndex` today. The other `containsIndex(...)` calls in `base/code`'s
    `kernel`, `sequence`, `expression` and `scope` packages go to unrelated methods,
    `containsIndex(Index)` on expressions and `IndexValues.containsIndex(String)`. So the item 1
    gate would be the method's first real consumer.
- `ReshapeProducer.containsIndex` throws `UnsupportedOperationException` when
  `shape.getOrder() != null`. When the shape is unordered and the wrapped producer is a
  `TraversableExpression`, it returns `producer.getValueAt(index)`: the element's *value*, through
  a raw cast, not a membership test. Item 4 changes this method, so the implementer should decide
  whether that branch is intended ("non-zero means present") or a defect.
- `SlicingFeatures.pad(int[] axes, int depth, ...)` throws `UnsupportedOperationException` when
  `shape.getOrder() != null`.
- `CollectionProducerComputationBase`'s constructor sets `this.shape = outputShape.withOrder(null)`,
  stripping any ordering from the output, so no computation can produce an ordered result.
- `CollectionProvider.into` (the flat memory-transfer copy) refuses any source or destination that
  carries a `TraversalOrdering`, either on its shape or inherited through
  `MemoryData.getMemOrdering()`. It refuses even when both sides share the identical ordering,
  because the ordering can map onto a sparse backing index. Its message says to "apply the
  reordering as a computation instead". That refusal is deliberate and already covered by
  `CollectionOrderingTests` (`orderedSourceIntoPlainDestinationIsRefused` and its siblings), so this
  plan does not change it. The computation path it points to is the one this plan makes work.

Shape identity ignores the ordering. `TraversalPolicy.equals` compares dimensions, dimension order,
rates and traversal axis, but not `getOrder()`, and `hashCode` hashes only the dimensions. An
ordered shape and a plain shape of the same size therefore compare equal (asserted by
`orderedSourceIntoPlainDestinationIsRefused`). `TraversalPolicy.isRegular()` does return `false`
for an ordered shape, and `CollectionProvider` compares the orderings explicitly for that reason.
See Open Question 7 for what this means once ordered inputs reach kernels.

### What actually fails today (reproduced 2026-10-23)

Both paths were run on a macOS node (Metal + JNI) against freshly installed artifacts for
`engine/utils` and everything upstream of it. The results contradict the task's premise that
"three tests fail":

- **The host path works.** `CollectionOrderingTests.compactOrdering`, `maskOrdering` and
  `repeatOrdering` are still flagged `@TestProperties(knownIssue = true)`, but **all three pass**
  when they are run. They read through the host `valueAt` path (`PackedCollection.valueAt` →
  `toDouble`), so absent `-1` markers, the mask's host scan and `imod` all give the right values on
  the host. The flags date from March 2026, when `CollectionOrderingTests` first carried
  `@TestProperties`. Before that, each test opened with an inline `if (skipKnownIssues) return;`
  guard, so the known-issue status is older than the flags. Nobody has re-evaluated it since.
  For example, the flags were left in place after the September 2026 commit *Fix double
  application of delegate ordering in MemoryData.toDouble()* changed the host method these tests
  exercise. Which change made each test pass was not determined. That commit's message says the
  double application never affected `repeatOrdering`. The non-`knownIssue` siblings
  `repeatOrderingProduct` and `explicitIndexOrderingAppliedOnce` also pass.
  To reproduce: `TestUtils.getSkipKnownIssues()` skips a `knownIssue` test whenever long tests
  are skipped. Outside the `pipeline` profile that is the case unless `AR_LONG_TESTS` is enabled
  or `AR_TEST_DEPTH` is above 10 (the default depth is 9). `AR_KNOWN_ISSUES` itself defaults to
  enabled, so setting it explicitly is harmless but not required. With the MCP test runner, pass
  `jvm_args: ["-DAR_LONG_TESTS=enabled", "-DAR_KNOWN_ISSUES=enabled"]`. The value must be
  `enabled`; `SystemUtils.isEnabled` rejects `true` with an `IllegalArgumentException`. Without
  `AR_LONG_TESTS`, the tests are reported as *skipped*, not run.
  Under the CI `pipeline` profile, `getSkipKnownIssues()` returns true unconditionally, so CI
  does not run these three tests. That is why their passing went unnoticed. It is also why
  removing the flags makes a real difference: CI starts guarding the host path.
- **The generated-code path fails for every index-table ordering, not only sparse ones.** A
  scratch reproduction ran `c(k).multiply(cp(ordered)).evaluate()` over each input, in its own
  JVM. All three inputs failed at kernel compilation with `HardwareException: Cannot compile
  multiply`, raised from `CollectionVariable.getValueAt`:
  - `ExplicitIndexTraversalOrdering` compact `pack(0, -1, -1, 1)`, **and the full permutation
    `pack(2, 0, 3, 1)`**. The cause is an `UnsupportedOperationException` from
    `MemoryData.getValueAt(Expression)` (`pos.intValue().orElseThrow(...)`), reached through
    `IndexExpressionTraversalOrdering.indexOf`. The backing `indices` is a `PackedCollection`, and
    its `getValueAt` can only read a constant position. This is the "backing collection is not a
    kernel argument" gap described under Approach, observed directly.
  - `IndexMaskTraversalOrdering` `pack(0, 3)`. The cause is a `NoSuchElementException` from
    `idx.intValue().orElseThrow()` in `IndexMaskTraversalOrdering.indexOf`.

  Of the orderings `CollectionOrderingTests` exercises, only `RepeatTraversalOrdering` survives a
  kernel today: `repeatOrderingProduct` passes, because its `indexOf` is pure `imod`. (When
  several of these probes share one JVM, the first compile failure is reported for all of them,
  so isolate each case when reproducing.)

So the broken surface is exactly the generated-code path (Current state item 2) and the ordering
`indexOf` implementations behind it. The host path that the `knownIssue` tests exercise is not
broken.

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
   and override where a type knows better. The ordering-delegation half already exists for every
   `CollectionExpression` through `Shape.containsIndex`. What is missing there is the
   unordered-shape fallback, because `Shape.containsIndex` throws a `NullPointerException` when
   `getOrder()` is `null`. `MemoryData`/`PackedCollection` still reach the throwing default (see
   Current state).
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
6. **Tests:** remove the three stale `knownIssue` flags (the tests already pass; see "What actually
   fails today"). Add new tests for an ordered collection as the input to element-wise, reshape,
   subset, pad and matmul computations, each run on Metal and native. Include kernel-input
   counterparts of `compactOrdering` and `maskOrdering`, and a full-permutation
   `ExplicitIndexTraversalOrdering` case, because those are the reads that fail today.

**scope↔done-when note.** The task's done-when names the three `knownIssue` tests as the evidence
of success. Those tests already pass on the host path, so removing their flags proves nothing about
this work. It can (and arguably should) be done independently of it. The real evidence is the new
kernel-input tests in item 6. Today they fail at compile time for every index-table ordering, and
items 1–3 are what make them pass. Success criterion 1 below is written accordingly.

Out of scope (related work and follow-ups, not widened into here):

- The sparse matrix type and the segmented-reduction sparse matrix–vector product
  ([66b57639]). It may use this integration if it chooses an ordering-backed type, but it is
  explicitly not waiting on it.
- Avoiding computation/storage of values outside an index set for delta outputs — that is
  [87b95b81] "Sparse traversal support", which builds on the same machinery.
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

The mechanism is already confirmed (see "What actually fails today"). For an
`ExplicitIndexTraversalOrdering`, `IndexExpressionTraversalOrdering.indexOf` calls
`indices.getValueAt(idx)` on the backing `PackedCollection`, and `MemoryData.getValueAt(Expression)`
throws for any non-constant position. The kernel therefore fails to *compile*, and today there is no
generated source to inspect. Once the argument binding exists and kernels compile, any remaining
wrong-value / `0.0` problem (for example, a missing membership gate reading the wrong slot for a `-1`
marker) must be investigated with `ar-profile-analyzer` before anything else, per the project rule:
wire a small profiled reproduction (an ordered input into `c(2).multiply(...)`), run it under
`AR_HARDWARE_DRIVER=mtl`, and read the generated kernel source and argument offsets with
`get_source`.

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

1. `CollectionOrderingTests.compactOrdering`, `maskOrdering` and `repeatOrdering` carry no
   `@TestProperties(knownIssue = true)` flag and pass. (They already pass, so removing the stale
   flags is a strengthening that is allowed.) **In addition**, `c(k).multiply(cp(x))` over `x`
   ordered by the same compact `ExplicitIndexTraversalOrdering`, `IndexMaskTraversalOrdering` and a
   full-permutation `ExplicitIndexTraversalOrdering` compiles and returns the expected values. Each
   of these currently fails with `HardwareException: Cannot compile multiply`. This half of the
   criterion is what this task's code changes actually satisfy.
2. A compact sparse ordering is expressed symbolically — its `indexOf` renders as a read of a
   device-resident index/mask collection in the generated kernel source (verifiable via
   `ar-profile-analyzer get_source`), with no `orElseThrow`/host `toArray()` scan on the kernel
   path.
3. New tests show an ordered collection giving correct results as the input to **element-wise,
   reshape, subset, pad and matmul** computations, each run on **Metal and native**. These are new
   tests, most naturally added to `CollectionOrderingTests` (engine/utils) or a sibling; each
   `@Test` carries a `timeout`. Each test asserts that the input it builds still carries its
   ordering (`getShape().getOrder()` or `getMemOrdering()` is non-null), because shape equality
   ignores the ordering and can silently drop it (Open Question 7).
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
- No blocking tracker tasks (`blocked_by` is empty). Nothing in the tracker is blocked by this task
  either. [66b57639] is blocked only by [2c0793a7], and [87b95b81] has no blockers. Nothing
  downstream is held up waiting on this plan, so scheduling it is the approver's call. Its value is
  general: it makes ordered collections usable as kernel inputs.

## Open questions

1. **Should computations produce ordered outputs?** Recommended: **no, for this task** — keep
   `withOrder(null)` and document the reason (producing a compact/sparse output is a
   scatter/segmented reduction owned by the sparse-matrix task [66b57639] / sparse-traversal task
   [87b95b81]). The approver should confirm this scoping, since the alternative (implementing
   ordered outputs here) is a materially larger change and risks overlapping [87b95b81].
2. **What is the one correct default for `TraversableExpression.containsIndex`?** Membership against
   the shape size, delegation to a carried ordering, or the unique-non-zero-offset analysis the TODO
   names — or a composition. This needs the implementer's judgement against the more than 20 types
   that implement or extend it; the plan commits only to replacing the throw with a correct,
   interface-honouring default. Two facts narrow the choice. First, `Shape.containsIndex` already
   delegates to the ordering for `CollectionExpression` types, so the new default must at least
   agree with it, and `Shape.containsIndex` itself needs a null-order fallback. Second, the
   existing overrides do not agree on what "contains" means. `TraversalOrdering` means "has a
   physical slot" (`indexOf >= 0`). `ArithmeticSequenceExpression` documents "the index holds a
   non-zero value". `ReshapeProducer` returns the element's value. For the item 1 gate both readings
   give the same answer (an absent element is `0`). They differ for a present element whose stored
   value is `0`, so the default's Javadoc should pick one meaning and state it.
3. **Index precision on FP32/Metal.** The ordered tests here use tiny shapes, so float indices are
   exact. A production sparse matrix needs integer-exact indices ([2c0793a7]). Confirm the new
   tests stay within 2^24 so a float-index ordering is not silently validated at a scale it cannot
   support; note the limitation where the ordering stores indices.
4. **Is `getLength()` returning max (not max+1) genuinely a bug, or relied upon somewhere?** The
   plan treats it as a bug; the implementer must grep callers of `TraversalOrdering.getLength()` and
   the `PackedCollection` allocation path before changing it, and store the finding. Note
   `MemoryData.getDelegatedLength()` already consumes `getDelegateOrdering().getLength()` (falling
   back to `getMemLength()` when the ordering declares no length), so delegated views are exposed to
   this value. The short length does not break the existing host tests. For `compactOrdering`, the
   bound check `0 + 1 <= 2` passes, and the tests do pass. It does, however, under-report the
   physical extent to every bound check that relies on it.
   The other two orderings support the "bug" reading. `TraversalOrdering.getLength()` is documented
   only as "the number of elements covered", which could mean logical or physical elements, but both
   consumers use it as a *physical* size. `getDelegatedLength()` feeds the
   `offset + getDelegatedLength() > getMemLength()` bound check that `MemoryDataAdapter.setDelegate`
   runs against the delegate, and `PackedCollection` uses `shape.getOrder().getLength()` to size the
   memory it allocates. `RepeatTraversalOrdering` returns its period and `IndexMaskTraversalOrdering`
   returns the mask length, and both are the number of physical slots the ordering addresses.
   `ExplicitIndexTraversalOrdering` returns the maximum index, one slot short. For `compactOrdering`'s
   `[0, -1, -1, 1]` that is 1 where 2 slots are addressed. So the fix should make the interface
   Javadoc state the physical-slot meaning, as well as changing the one implementation that departs
   from it.
5. **Should the stale `knownIssue` flags be removed separately, ahead of this plan?** *(Resolved
   on 2026-10-23: the host failures do not exist.)* Earlier review passes speculated about why
   `compactOrdering`, `maskOrdering` and `repeatOrdering` fail on the host. When run with known
   issues enabled (see "What actually fails today" for the flags), all three pass. The open part
   is for the approver to decide. Removing the three flags is a small test-only strengthening that
   does not depend on any of the generated-code work. It could land on its own, so that CI starts
   guarding the host path now, rather than waiting for this Large task. If it is left in this
   task's scope, the implementer should remove the flags first, so that a regression of the host
   path during the generated-code work is caught.
6. **How costly is a symbolic `IndexMaskTraversalOrdering.indexOf` in a kernel?** The inverse search
   has no closed form: finding the physical slot for a logical index means scanning the mask, so a
   symbolic version renders as an O(mask length) search per lookup inside the kernel. That is
   acceptable for the small, filtered masks this task tests, but it does not scale to the large
   sparse matrix of the connectome work. The design in `CONNECTOME_MODEL_ORGANISM.md` builds that
   matrix in CSR form, as a gather by an index array followed by a segmented reduction. That design
   has no mask inverse search. Whether the sparse type is backed by a `TraversalOrdering` at all
   (and if so, by the direct-table `ExplicitIndexTraversalOrdering`) is left to [66b57639]. The
   approver should treat the symbolic mask `indexOf` as a correctness feature for small masks, and
   confirm that no large-scale consumer is expected to route through it.
7. **Should `TraversalPolicy.equals` compare the ordering?** It does not today (see Current
   state), and several production shortcuts trust it to mean "same layout". The clearest cases are
   in `PackedCollection`. `reshape(TraversalPolicy)` returns `this` when `getShape().equals(shape)`,
   and `range(TraversalPolicy, int)` returns `this` when `start == 0` and the shapes are equal. So
   reshaping or ranging a plain collection into an ordered shape of the same dimensions hands back
   the plain collection, and the requested ordering is lost without an error.
   `CollectionProducerComputationBase` reuses an existing output bank on shape equality, and
   `ComputationScopeCompiler` and `CollectionProducerComputation` also compare shapes with
   `equals`. These are harmless while ordered shapes stay on the host. They matter once scope item
   4 lets ordered shapes pass through reshape and item 6 tests reshape and subset inputs: such a
   test could pass against a collection that has silently lost its ordering. The options are (a)
   make `equals` (and `hashCode`) include `getOrder()`, which changes identity for every caller,
   or (b) leave `equals` alone and add explicit ordering checks at the shortcuts, as
   `CollectionProvider` already does. The implementer should audit the `equals` call sites before
   writing the reshape and subset tests, and each new test should assert that the ordering is
   still present on the input it builds.

No new tracker tasks were filed: every out-of-scope item above is an already-filed Phase 1 task
([66b57639], [87b95b81], [2c0793a7]) named as related work, so there is nothing new to triage.

## Estimated complexity

**Large.** The work spans three modules (`base/code`, `base/hardware`, `compute/algebra`) and the
engine/utils test module, and its hardest part — delivering an ordering's backing collection into
the kernel argument graph so `indexOf`/`containsIndex` render symbolically — is a genuine change to
how orderings integrate with code generation, not a set of local fixes. The symbolic inverse search
for `IndexMaskTraversalOrdering` and the dual-backend (Metal + native) test matrix add further
breadth. If the approver wants it split, the natural seam is: (a) the generated-code membership
gate + symbolic `indexOf` + `containsIndex` default + the kernel-input element-wise tests for
compact, mask and permutation orderings (the core), then
(b) the reshape/pad/matmul input coverage and the ordered-output decision as a follow-on, since (b)
only becomes testable once (a) lands.
