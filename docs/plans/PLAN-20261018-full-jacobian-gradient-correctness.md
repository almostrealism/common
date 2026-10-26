# Fix the nondeterministic full-Jacobian weight gradient for the output projection

**Category:** Code Quality (correctness — a silent wrong-gradient defect in the autodiff engine)
**Branch:** `project/plan-20261018-174738`
**Estimated complexity:** Medium
**Requires:** a macOS / Metal node (see `docs/plans/plan-20261018-174738-workstream.yaml` and *Dependencies*)

---

## Motivation

Automatic differentiation is how the platform *learns*: every training step, every gradient,
every future language-model step runs through `CollectionProducer.delta`. The matmul family now
contracts its Jacobian structurally (the reduced-delta / VJP work, PRs #620, #628, #661, #666),
but **every operator without a structural reduced form still falls back to
`GradientFeatures.jacobianGradient`** — it materializes the full Jacobian and contracts it. That
fallback is the reference the whole reduced-delta test suite is checked against. It is supposed to
be the ground truth.

It is not. For the 256×64 output projection of the self-hosted language model, over a 64-row
sequence, `jacobianGradient` produces a gradient that **disagrees with the definition in about a
tenth of its 16,384 entries, and does so nondeterministically** — two profiled runs on Metal gave
1,728 and 1,536 wrong entries, the errors always beginning at weight row 64. The 192×64 and 128×64
projections are correct. This is recorded honestly in the tree today: a `TODO(review)` on
`GradientFeatures.jacobianGradient`, the "Known limits" section of
`docs/internals/automatic-differentiation.md`, the "Weight gradients of dense layers" section of
`docs/internals/training-a-language-model.md` (which says the cause "is not yet diagnosed"),
and `DenseVectorJacobianProductTest.outputProjection` (its javadoc describes the defect). That test
logs the disagreement count but does **not** assert on the full-Jacobian weight gradient at this
size: for the weight it asserts only the reduced delta against the definition (the input gradient
is still asserted against the full Jacobian).

The cost of leaving it is real and compounding:

- **The platform's first self-hosted training runs trained the output projection with partly wrong
  gradients.** `trainOnDocumentation` ran before the structural VJP existed, so its output
  projection learned from this fallback. The VJP now bypasses the defect for matmul, but the
  fallback is still the path for every operator that has no structural reduced form (see the last
  bullet for which operators that is).
- **It is the reference every reduced-delta test trusts.** A wrong ground truth means a future
  reduced delta could be declared correct against a value that is itself wrong, at any size where
  the fallback misbehaves. `DenseVectorJacobianProductTest` already had to special-case the output
  projection out of the comparison; the next operator that needs the fallback has no such escape.
- **It is nondeterministic, which is the worst failure mode for a correctness reference.** A test
  that passes four times and fails the fifth erodes trust in the whole suite and in CI.
- **Other fallback gradients over large iteration spaces may share the defect.** The fallback is
  selected by structure, not by size: `CollectionProducerComputationBase.deltaDependent` contracts
  the full Jacobian (`jacobianContraction` → `jacobianGradient` for `ReduceAxis.OUT`) only when no
  element-wise, input, or chain-rule form applies, and `WeightedSumComputation.deltaDependent`
  defers to it only when the input and weights are the same producer or `transposedSum` cannot
  transpose the traversal — for example a convolution's sliding window. So a large operator that
  has a structural reduced form does not take this path at all. Which fallback users run at an
  iteration space comparable to the output projection's (convolution being the obvious candidate)
  has not been checked.

This is foundation work in the first category that has genuine work left (documentation is
excellent and complete through the autodiff internals page that just landed). A learning system
whose reference gradient is silently, intermittently wrong cannot be trusted to train anything —
least of all a model we hope will one day study the platform itself.

## Background: what is actually known (follow this evidence, do not re-theorize)

A reviewer note first suggested a **32-bit index overflow**: the kernel's iteration space is
16,384 outputs × 16,384 weight entries × 64 = 2³⁴, the first wrong row (64) is weight flat index
4096 = 2¹², and 2¹² × 2¹⁴ × 2⁶ = 2³², so the errors appear to begin exactly where a flattened
(weight, output, inner) index passes 2³². That is a plausible-sounding hypothesis and it is written
into the `TODO(review)` comment — **but the subsequent profiling contradicts it, and the plan must
start from the profiling, not the arithmetic.**

What `ar-profile-analyzer` actually found (captured in the `bugs` memory namespace, branch
`project/plan-20261009-171224`, 2026-10-10):

- The failure is **nondeterministic** across runs (1,728 vs 1,536 wrong of 16,384). A deterministic
  32-bit overflow would miscompute the *same* entries every run. Nondeterminism points away from a
  pure index-type bug and toward **ordering / synchronization / residence**.
- The offending kernel is the sum node (`f_collectionSumComputation_67` in the captured profile):
  it sums 16,384 outputs × 64 diagonal-masked terms, **reading a materialized ~1M-element
  `f_packedCollectionRepeat_42` buffer shaped (64, 256, 64)**. The suspicion recorded is a
  sync/ordering problem between the kernel that *writes* that repeat buffer and the ~1.5 s sum
  kernel that *reads* it.
- **An untrusted lead, not a precedent:** the layernorm gamma/beta "invalid kernel" journal
  (`docs/journals/INVALID_KERNEL_AGGREGATION.md`, with `INVALID_KERNEL_AGGREGATION_COMPILATION.md`
  and `COMPILE_DETERMINISM.md`; `bugs` memory, branch `feature/ml-dit-memory-tokens`, 2026-06-21)
  claims that a kernel read operands from a **baked aggregate slot offset** tied to transient
  host/device residence, so one computation compiled to two kernels reading different memory, and
  that disabling argument aggregation restored determinism. **That diagnosis has been retracted.**
  `docs/journals/CONCESSION.md` states that the journal's central claim is wrong — the two
  "divergent" kernels were a mismatched comparison of `pre_norm` against `ff_norm` and are identical
  once paired correctly — and that the cited journals are raw data, not a diagnosis. Nothing in the
  tree therefore establishes an aggregation defect, and this plan must not reason from one. The
  journal is useful only for its method (pairing `get_source` output with argument bindings) and for
  naming where aggregation lives: `MemoryDataArgumentMap` (`enableArgumentAggregation`,
  `isAggregationTarget`) in `base/hardware`, with the per-kernel aggregate layout carried on
  `ScopeInstructionsManager` (`aggregatePositions`). (The journals toggle aggregation with
  `AR_HARDWARE_OFF_HEAP_SIZE=0`, which only switched it off as a side effect back when the off-heap
  threshold defaulted to 1024. The default is now `0` — `Hardware.DEFAULT_OFF_HEAP_SIZE` — so that
  setting is a no-op, and aggregation is toggled directly by `AR_HARDWARE_ARGUMENT_AGGREGATION`.)
  The reason to test aggregation first is the evidence *here* — nondeterminism, and a large
  materialized buffer read by a long-running kernel — and the fact that the toggle is a cheap,
  single-variable experiment; not any prior finding.

The leads are testable and can be separated quickly:

1. **Aggregation / residence** (favored by the nondeterminism): disable aggregation by setting
   `AR_HARDWARE_ARGUMENT_AGGREGATION` to a disabled value (it defaults to enabled; the flag maps to
   `MemoryDataArgumentMap.enableArgumentAggregation`) and re-run. If the gradient becomes
   deterministic and correct, the root cause is in how operands of the repeat/sum kernels are
   addressed under aggregation, in the `MemoryDataArgumentMap` family — confirm it from the two
   kernels' sources and argument bindings, not from the toggle alone.
2. **Write/read ordering** (the suspicion recorded in the 2026-10-10 memory): if disabling
   aggregation does *not* fix it, the remaining nondeterministic mechanism is the sum kernel reading
   the repeat buffer before the kernel that writes it has completed. Check it by forcing the repeat
   buffer to be fully evaluated (and the queue synchronized) before the sum kernel runs, and by
   reading the operation tree (`list_children`) for how the two kernels are sequenced.
3. **Index typing** (the original note): a pure 32-bit overflow would be deterministic, so it cannot
   explain the run-to-run variation on its own; inspect it only if 1 and 2 are ruled out, via
   `ar-profile-analyzer get_source` on the sum node, checking whether any flattened
   `(weight, output, inner)` index is computed in a type that overflows at 2³¹/2³².

Either way, the mechanical rule applies: **read the generated kernel source and its argument
bindings with `ar-profile-analyzer` before adding any `log()` probe or asserting a mechanism.** No
speculation; follow the evidence.

## Scope

Concrete, bounded deliverables:

1. **Diagnose the root cause** of the nondeterministic disagreement for the 256×64 / 64-row output
   projection, using `ar-profile-analyzer` on a captured profile of exactly that gradient
   (`DenseVectorJacobianProductTest.outputProjection` already builds the graph; add a profiled
   reproduction if no saved profile covers it). Separate the leads above, starting with the
   `AR_HARDWARE_ARGUMENT_AGGREGATION` (disabled) experiment. Record the finding (kernel node, the buffer and
   argument bindings involved, the mechanism) in a `docs/journals/` entry and in the `bugs` memory
   namespace before changing any code.

2. **Fix the defect at its root**, in the layer the evidence points to:
   - If aggregation/residence: the fix belongs in the `base/hardware` argument-mapping machinery
     (`MemoryDataArgumentMap` and the kernel-ordering it feeds), so that a kernel's operand offsets
     derive from the operands' actual reservations/sizes rather than a frozen aggregate slot, and
     the read of a materialized buffer is correctly ordered after its write. Prefer repairing that
     path over adding a special case.
   - If write/read ordering: the fix belongs where kernel dependencies are sequenced for a
     materialized intermediate, so that every consumer of such a buffer waits on its producer.
   - If index typing: the fix belongs in the expression/index-generation layer (widen the index
     type for iteration spaces that exceed 2³¹, at the point where the flattened index is formed, not
     per-call-site).
   - In every case, fix the general mechanism so **every** operator taking the fallback benefits;
     do not narrow the fix to the output-projection shape.

3. **Turn the defect into a permanent, asserted regression test.** Change
   `DenseVectorJacobianProductTest.outputProjection` (and the `jacobianReliable=false` branch of
   `assertGradientsMatch`) so the full-Jacobian weight gradient for the 256×64 / 64-row projection
   is **asserted** to agree with the definition, not merely logged — and asserted to agree on
   repeated evaluation, so the nondeterminism cannot silently return. This tightens an existing test
   (allowed and required); it does not weaken one. Only make this assertion once the fix is in and
   green: a measured, honest failure with a precise finding is an acceptable outcome if the root
   cause proves larger than one session, in which case the assertion stays logged and the finding is
   handed to a follow-up plan.

4. **Check whether convolution, the most likely other large fallback user, shares the defect.**
   The existing coverage cannot answer this on its own: `RepeatedDeltaComputationTests.convDeltaSmall`
   and `convDeltaMedium` take the unreduced Jacobian (`ReduceAxis.NONE`), not `jacobianGradient`;
   `convDeltaGradSmall` takes the `ReduceAxis.OUT` delta but only logs the result's shape, with no
   comparison to an independent reference and no repeated evaluation; and `convDeltaGradLarge` is
   marked `knownIssue`. So this item needs (a) confirmation, from the operation tree, of which path a
   convolution's `ReduceAxis.OUT` delta actually takes (`WeightedSumComputation` declines to form a
   transposed sum for a sliding window, but the general `deltaDependent` may still find an
   element-wise or chain-rule form before contracting the Jacobian), and (b) a **new** test that
   compares that delta, evaluated several times on the Metal node, against a reference computed on
   the host from the convolution's definition — the same A/B/reference shape
   `DenseVectorJacobianProductTest` uses. Record the result as reporting; fixing a second operator
   family is out of scope unless the root-cause fix from item 2 already covers it.

5. **Update the documentation** that currently records the defect as a known limit: the "Known
   limits" section of `docs/internals/automatic-differentiation.md` (its first item), the "Weight
   gradients of dense layers" section of `docs/internals/training-a-language-model.md` (the
   paragraph beginning "The full-Jacobian weight gradient is wrong for the output projection"),
   and the javadoc of `DenseVectorJacobianProductTest.outputProjection`; and remove the
   `TODO(review)` comment on `GradientFeatures.jacobianGradient`. State what the cause was and
   that it is fixed and regression-tested. The training page's statement that the documentation
   runs trained the output projection with partly wrong gradients is history and stays true.

Explicitly **out of scope:** any change to the structural reduced-delta / VJP path (it is already
correct and bypasses this bug); re-running `trainOnDocumentation` (that is the proof-of-value track,
a later cycle); broad convolution-gradient rework beyond the reporting check in item 4.

## Approach

1. **Recall and read first.** Load the `bugs` memories cited above (the 2026-10-10 Metal Jacobian
   note and the 2026-06-21 layernorm aggregation note), read `docs/journals/CONCESSION.md` before
   `docs/journals/INVALID_KERNEL_AGGREGATION.md` (the former retracts the latter's diagnosis), and
   read `GradientFeatures.jacobianGradient`, `DenseVectorJacobianProductTest`, and
   `MemoryDataArgumentMap`. Confirm the current behavior by
   running `DenseVectorJacobianProductTest.outputProjection` on the Metal node and observing the
   logged `jacobianDisagreements` count over several runs (the test evaluates the full-Jacobian
   gradient once per run, so repetition means re-running it).

2. **Capture a profile** of the output-projection gradient (reuse the test's graph; save an
   `OperationProfile` XML under the module's `results/` if none exists) and inspect the sum node and
   the repeat buffer it reads with `ar-profile-analyzer` (`list_children`, `get_source`,
   `get_source_summary`) — argument offsets/sizes especially.

3. **Separate the leads** with the `AR_HARDWARE_ARGUMENT_AGGREGATION` (disabled) experiment, then
   the forced-ordering check. Decide among aggregation, ordering and index typing from the results
   and the kernel source, not from the arithmetic.

4. **Fix at the root** per item 2 of Scope, in the layer the evidence names. Keep the change
   general and minimal; honor the memory-model and OO-placement rules (any new behavior
   goes on the type that owns the concept, not a helper).

5. **Verify narrowly.** Run `DenseVectorJacobianProductTest` (the whole class is a bounded, single
   class — within limits) on the Metal node, repeatedly, to confirm determinism and agreement, plus
   the new convolution test for item 4. Run the build validator (checkstyle, code_policy,
   test_timeouts, duplicate_code). Do not run broad suites — leave full verification to CI.

6. **Document and record** per items 3 and 5 of Scope; store a `bugs` memory with the confirmed root
   cause and fix.

## Success Criteria

- The root cause of the nondeterministic 256×64 / 64-row full-Jacobian weight-gradient disagreement
  is identified from the generated kernel (not theorized), and recorded in a `docs/journals/` entry.
- `GradientFeatures.jacobianGradient` produces, for that projection, a gradient that agrees with the
  definition to the FP32 noise floor on **every** evaluation on the Metal node (no nondeterminism).
- `DenseVectorJacobianProductTest.outputProjection` asserts that agreement (the full-Jacobian weight
  gradient is now a reliable reference at this size), and the assertion holds across repeated runs.
- The fix is at the general mechanism, so other operators taking the fallback benefit; the
  convolution check in Scope item 4 — the path confirmed and a new reference-comparing test run
  repeatedly on the Metal node — is done and its outcome recorded.
- The `TODO(review)` and the "known limits" doc entries are updated to describe the resolved cause.
- The build validator passes; the targeted tests pass on the Metal node.

If an honest, bounded effort cannot fully resolve a root cause that proves larger than one session,
the acceptable outcome is a precise, evidenced finding (the exact kernel/argument mechanism) handed
to a follow-up plan, with the test assertion left as it is today (logged, not weakened). Weakening
the test, the tolerance, the dimensions, `@TestDepth`, or the timeout to produce green is out of
bounds and would fail `test-integrity-check`.

## Dependencies

- **A macOS / Metal node.** The defect has only been measured on Metal (whether other backends are
  affected is unknown — see *Open questions*), and both reproduction and verification require
  `ar-profile-analyzer` on a Metal-captured profile. The accompanying
  `docs/plans/plan-20261018-174738-workstream.yaml` requests `platform: macos`.
- The autodiff internals page (`docs/internals/automatic-differentiation.md`) — **already landed**
  (PR #670); it was named the prerequisite for this diagnosis in the prior Manager Log entry.
- No new modules, no `pom.xml` changes, no dependency additions.

## Estimated Complexity

**Medium.** The failing case, the diagnostic artifacts, the candidate root causes, and the exact
test to tighten are all already identified. There is no verified precedent to borrow a fix from
(the layernorm aggregation journal's diagnosis was retracted), so the diagnosis starts from this
kernel's own source and bindings. The risk is that the root cause lies in shared
argument-mapping/ordering machinery whose fix must stay byte-identical for every other kernel — so
the work is careful and must be verified against the existing reduced-delta suite, but it is bounded
and does not touch the structural VJP path.

## Open questions

- **Is the defect Metal-specific?** Only Metal runs have been measured. Running
  `DenseVectorJacobianProductTest.outputProjection` once on the JNI/OpenCL backend, if a node offers
  one, would say whether the cause is backend-specific (synchronization, residence) or in the
  generated program itself, before any fix is chosen.
- **Does the aggregation toggle change more than aggregation?** Disabling
  `AR_HARDWARE_ARGUMENT_AGGREGATION` also changes how many kernel arguments there are and how
  buffers are bound, so a change in outcome narrows the search to argument binding but is not by
  itself a diagnosis; the plan relies on the kernel source to finish it.
- **Is convolution's reduced delta reaching the fallback at all?** Scope item 4 assumes it might;
  if the operation tree shows convolution's `ReduceAxis.OUT` delta never calls `jacobianGradient`,
  the new test is still worth adding as coverage, but it no longer tests this defect, and another
  large fallback user would need to be found to check generality.
- **Should repeated-evaluation assertions be a pattern, not a one-off?** The test change in Scope
  item 3 asserts determinism for one shape. Whether other gradient-reference tests should also
  evaluate more than once is a broader decision for the reviewer, not something this plan does.
