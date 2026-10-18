# Fix the nondeterministic full-Jacobian weight gradient for the output projection

**Category:** Code Quality (correctness — a silent wrong-gradient defect in the autodiff engine)
**Branch:** `project/plan-20261018-174738`
**Estimated complexity:** Medium
**Requires:** a macOS / Metal node (see `project/plan-20261018-174738-workstream.yaml` and *Dependencies*)

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
`GradientFeatures.jacobianGradient`, the "known limits" prose in
`docs/internals/training-a-language-model.md` and `docs/internals/automatic-differentiation.md`,
and `DenseVectorJacobianProductTest.outputProjection`, which logs the disagreement count but does
**not** assert on the full-Jacobian weight gradient at this size (it asserts only the reduced delta
against the definition).

The cost of leaving it is real and compounding:

- **The platform's first self-hosted training runs trained the output projection with partly wrong
  gradients.** `trainOnDocumentation` ran before the structural VJP existed, so its output
  projection learned from this fallback. The VJP now bypasses the defect for matmul, but the
  fallback is still the only path for every non-matmul operator.
- **It is the reference every reduced-delta test trusts.** A wrong ground truth means a future
  reduced delta could be declared correct against a value that is itself wrong, at any size where
  the fallback misbehaves. `DenseVectorJacobianProductTest` already had to special-case the output
  projection out of the comparison; the next operator that needs the fallback has no such escape.
- **It is nondeterministic, which is the worst failure mode for a correctness reference.** A test
  that passes four times and fails the fifth erodes trust in the whole suite and in CI.
- **Other large-shape gradients may share the defect.** Convolution and any operator over a large
  iteration space take the same fallback; nobody has checked whether they are affected.

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
- A closely related, already-diagnosed defect exists: the layernorm gamma/beta "invalid kernel"
  (`bugs` memory, branch `feature/ml-dit-memory-tokens`, 2026-06-21,
  `docs/journals/INVALID_KERNEL_AGGREGATION.md`). There, a kernel read operands from a **baked
  aggregate slot offset** that depended on transient host/device **residence**, so the *same*
  computation compiled to two kernels reading different memory — and setting
  `AR_HARDWARE_OFF_HEAP_SIZE=0` (aggregation disabled) made the result deterministic and correct.
  The aggregation machinery lives in `MemoryDataArgumentMap` (`isAggregationTarget`,
  `aggregatePositions`) in `base/hardware`. The nondeterminism signature here is the same family of
  symptom, and that is the first lead to run down.

The two leads are testable and mutually exclusive enough to separate quickly:

1. **Aggregation / residence** (favored by the nondeterminism): disable aggregation
   (`AR_HARDWARE_OFF_HEAP_SIZE=0`) and re-run. If the gradient becomes deterministic and correct,
   the root cause is in how operands of the repeat/sum kernels are addressed under aggregation, in
   the `MemoryDataArgumentMap` family — exactly the class of bug the journal already documents.
2. **Index typing** (the original note): if disabling aggregation does *not* fix it, inspect the
   generated index expressions via `ar-profile-analyzer get_source` on the sum node and check
   whether any flattened `(weight, output, inner)` index is computed in a type that overflows at
   2³¹/2³².

Either way, the mechanical rule applies: **read the generated kernel source and its argument
bindings with `ar-profile-analyzer` before adding any `log()` probe or asserting a mechanism.** No
speculation; follow the evidence.

## Scope

Concrete, bounded deliverables:

1. **Diagnose the root cause** of the nondeterministic disagreement for the 256×64 / 64-row output
   projection, using `ar-profile-analyzer` on a captured profile of exactly that gradient
   (`DenseVectorJacobianProductTest.outputProjection` already builds the graph; add a profiled
   reproduction if no saved profile covers it). Separate the two leads above with the
   `AR_HARDWARE_OFF_HEAP_SIZE=0` experiment first. Record the finding (kernel node, the buffer and
   argument bindings involved, the mechanism) in a `docs/journals/` entry and in the `bugs` memory
   namespace before changing any code.

2. **Fix the defect at its root**, in the layer the evidence points to:
   - If aggregation/residence: the fix belongs in the `base/hardware` argument-mapping machinery
     (`MemoryDataArgumentMap` and the kernel-ordering it feeds), so that a kernel's operand offsets
     derive from the operands' actual reservations/sizes rather than a frozen aggregate slot, and
     the read of a materialized buffer is correctly ordered after its write. This is the same fix
     direction the layernorm journal names; prefer generalizing/repairing that path over adding a
     special case.
   - If index typing: the fix belongs in the expression/index-generation layer (widen the index
     type for iteration spaces that exceed 2³¹, at the point where the flattened index is formed, not
     per-call-site).
   - In both cases, fix the general mechanism so **every** operator taking the fallback benefits;
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

4. **Check whether convolution and other large-shape fallbacks share the defect.** Run the existing
   `RepeatedDeltaComputationTests` / convolution-delta coverage under the same conditions and record
   the result (as reporting — fixing a second operator family is out of scope unless the same
   one-line root-cause fix already covers it).

5. **Update the documentation** that currently records the defect as a known limit: the "known
   limits" sections of `docs/internals/automatic-differentiation.md` and
   `docs/internals/training-a-language-model.md`, and remove the `TODO(review)` comment on
   `GradientFeatures.jacobianGradient`. State what the cause was and that it is fixed and
   regression-tested.

Explicitly **out of scope:** any change to the structural reduced-delta / VJP path (it is already
correct and bypasses this bug); re-running `trainOnDocumentation` (that is the proof-of-value track,
a later cycle); broad convolution-gradient rework beyond the reporting check in item 4.

## Approach

1. **Recall and read first.** Load the `bugs` memories cited above (the 2026-10-10 Metal Jacobian
   note and the 2026-06-21 layernorm aggregation journal), read
   `docs/journals/INVALID_KERNEL_AGGREGATION.md`, and read `GradientFeatures.jacobianGradient`,
   `DenseVectorJacobianProductTest`, and `MemoryDataArgumentMap`. Confirm the current behavior by
   running `DenseVectorJacobianProductTest.outputProjection` on the Metal node and observing the
   logged `jacobianDisagreements` count over several evaluations.

2. **Capture a profile** of the output-projection gradient (reuse the test's graph; save an
   `OperationProfile` XML under the module's `results/` if none exists) and inspect the sum node and
   the repeat buffer it reads with `ar-profile-analyzer` (`list_children`, `get_source`,
   `get_source_summary`) — argument offsets/sizes especially.

3. **Separate the leads** with the `AR_HARDWARE_OFF_HEAP_SIZE=0` experiment. Decide aggregation vs
   index-typing from the result, not from the arithmetic.

4. **Fix at the root** per item 2 of Scope, in `base/hardware` or the index-generation layer. Keep
   the change general and minimal; honor the memory-model and OO-placement rules (any new behavior
   goes on the type that owns the concept, not a helper).

5. **Verify narrowly.** Run `DenseVectorJacobianProductTest` (the whole class is a bounded, single
   class — within limits) on the Metal node, repeatedly, to confirm determinism and agreement, plus
   the convolution-delta check for item 4. Run the build validator (checkstyle, code_policy,
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
  convolution-delta check in Scope item 4 is run and its outcome recorded.
- The `TODO(review)` and the "known limits" doc entries are updated to describe the resolved cause.
- The build validator passes; the targeted tests pass on the Metal node.

If an honest, bounded effort cannot fully resolve a root cause that proves larger than one session,
the acceptable outcome is a precise, evidenced finding (the exact kernel/argument mechanism) handed
to a follow-up plan, with the test assertion left as it is today (logged, not weakened). Weakening
the test, the tolerance, the dimensions, `@TestDepth`, or the timeout to produce green is out of
bounds and would fail `test-integrity-check`.

## Dependencies

- **A macOS / Metal node.** The defect is observed only on Metal, its nondeterminism is tied to the
  Metal backend's memory residence/aggregation behavior, and both reproduction and verification
  require `ar-profile-analyzer` on a Metal-captured profile. The accompanying
  `project/plan-20261018-174738-workstream.yaml` requests `platform: macos`.
- The autodiff internals page (`docs/internals/automatic-differentiation.md`) — **already landed**
  (PR #670); it was named the prerequisite for this diagnosis in the prior Manager Log entry.
- No new modules, no `pom.xml` changes, no dependency additions.

## Estimated Complexity

**Medium.** The failing case, the diagnostic artifacts, the two candidate root causes, and the exact
test to tighten are all already identified; a closely analogous aggregation defect is already
journaled with a named fix direction. The risk is that the root cause lies in shared
argument-mapping/ordering machinery whose fix must stay byte-identical for every other kernel — so
the work is careful and must be verified against the existing reduced-delta suite, but it is bounded
and does not touch the structural VJP path.
