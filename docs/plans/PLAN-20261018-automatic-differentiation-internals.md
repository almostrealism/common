# Document Automatic Differentiation from `delta` to the Backward Pass

**Category:** Documentation
**Branch:** `project/plan-20261018-013621`
**Estimated complexity:** Medium (one new internals page of roughly 400–600 lines, index and
cross-link updates, targeted Javadoc fixes; no production behaviour changes)

## Motivation

Automatic differentiation is what makes the computation graph a *learning* system rather than only
a fast one. Every training run on the platform, including the self-hosted language model trained on
the platform's own documentation, depends on it. And it is the subsystem that changed most in the
last two weeks:

- The matrix-multiply work (PR #628) replaced a materialized-Jacobian weight gradient with reduced
  deltas: a caller names the axis it will contract with `ReduceAxis` (`OUT` for the vector-Jacobian
  product, `IN` for the Jacobian-vector product, `NONE` for the Jacobian itself), and each
  computation produces that reduced delta from its own structure. That made a training step about
  ten times cheaper and let the LM beat its unigram baseline (4.194 against 4.912 bits per byte).
- The delta consolidation (PRs #661 and #666) removed `MultiTermDeltaStrategy`, the delta-alternate
  mechanism, `VectorJacobianProduct`, `attemptDelta`, `combineGradient` and `grad`, and left
  `CollectionProducer.delta(target, axis, weight)` as the only public derivative method. Inside a
  computation the derivative machinery is now four protected extension points of
  `CollectionProducerComputationBase`, applied in a documented order: `deltaDependent` (the
  computation's own rule), `deltaElement` (an element-wise partial of one input), `deltaPartial`
  (a delta with one input held as a variable through `InputStub`), and `deltaMatrix` (the
  computation's own Jacobian, last resort). The full-Jacobian contraction moved to
  `GradientFeatures.jacobianContraction` beside `jacobianGradient`.

The design is now stable, and nothing explains it as a whole. `consult` confirms there is no
internals page for automatic differentiation. What exists is scattered:

- the `org.almostrealism.calculus` `package-info` (one paragraph) and the per-method Javadoc on
  `CollectionProducer.delta` and `CollectionProducerComputationBase`;
- a three-line example in `compute/algebra/README.md` ("Automatic Differentiation");
- the "Weight gradients of dense layers" section of `docs/internals/training-a-language-model.md`,
  which explains the reduced delta only as far as a dense layer needs it;
- nothing at all on the expression level (`CollectionExpression.delta` and the per-`Expression`
  rules in `base/code`, `TraversableDeltaComputation`, `TransitiveDeltaExpressionComputation`, the
  sparse `ExpressionMatrix`) or on how the layer system turns deltas into a backward pass
  (`BackPropagation`, `DefaultGradientPropagation`, `BackPropagationCell`, `Learning`,
  `ParameterUpdate`, `CompiledModel.backward`, `ModelOptimizer`).

The internals corpus already tells the forward story end to end (Producer → process tree → process
optimization → backend compilation → native runtime lifecycle). The backward story is the missing
half. Without it, a contributor or an agent adding a computation cannot know which extension point
to override, whether a new operator will silently fall back to a full Jacobian (the
`convDeltaMedium` and 17-billion-term output-projection costs are both that fallback), or how a
gradient actually reaches an optimizer. For the self-understanding goal it matters doubly: the
mechanism by which the platform *learns* is precisely what a model trained on this corpus should be
able to explain about itself.

## Scope

1. **New page `docs/internals/automatic-differentiation.md`** covering, in this order:
   1. *The contract.* `CollectionProducer.delta(target, axis, weight)` and its convenience
      overloads; the three `ReduceAxis` values with the exact shapes of the results (`J` shaped
      `(out, in)`, `gᵀJ` shaped like the target, `J t` shaped like the output); why the axis is
      named by the caller and not inferred. Short, compilable examples.
   2. *Identity cases and the default.* What the `CollectionProducer.delta` default answers by
      identity alone (target is this producer, target absent from the graph) and when it contracts
      the Jacobian via `GradientFeatures.jacobianContraction`; how `ReshapeProducer` defers to it.
   3. *Inside a computation: the four extension points.* `deltaDependent`, `deltaElement`,
      `deltaPartial`, `deltaMatrix`, the order in which they apply, `isChainRuleSupported`, and the
      role of `InputStub`. A worked example per kind of rule, drawn from real computations: the
      reshape pass-through, addition (`CollectionAddComputation`), the product rule
      (`CollectionProductComputation`), the quotient rule (`CollectionQuotientComputation`), sum
      and repeat (`CollectionSumComputation`, `PackedCollectionRepeat`), and the selection cases
      (`CollectionMaxComputation`, subset/pad). A decision guide: "I am adding a computation —
      which point do I override, and how do I verify it?"
   4. *How a matrix multiply's gradient falls out of its parts.* Brief, linking to the training
      page rather than repeating it; state which `matmul` lowering (the weighted sum used for
      outputs wider than 1000, `WeightedSumComputation`) still has no reduced form and therefore
      contracts its Jacobian.
   5. *The expression level.* `CollectionExpression.delta` and the per-expression rules in
      `base/code` (`Exponent`, `Logarithm`, `Max`, `Mask`, `ProductCollectionExpression`,
      `IndexProjectionExpression`, `SubsetTraversalWeightedSumExpression`, ...);
      `TraversableDeltaComputation` and `TransitiveDeltaExpressionComputation`; how
      `ExpressionMatrix` represents a sparse Jacobian and why its enumeration space (for example
      2^34 positions for the 256 by 64 output projection) is the cost a reduced delta avoids.
   6. *From deltas to a backward pass.* How a layer's backward is built: `BackPropagation` /
      `DefaultGradientPropagation` (input gradient and every trainable weight's gradient as the
      `OUT`-reduced delta weighted by the upstream gradient; frozen weights are never
      differentiated), `BackPropagationCell`, `Learning` and `ParameterUpdate`, gradient routing
      through `LayerRoutingFeatures` and `BranchBlock`, `CompiledModel.backward`, and the loss and
      `ModelOptimizer` at the top. One diagram of the forward and backward cells of a two-layer
      model.
   7. *Verifying a derivative.* The existing test patterns: finite differences
      (`CausalLanguageModelTest.gradientsMatchFiniteDifferences`, `GradientTestFeatures`), A/B
      against the forced Jacobian and the host definition (`DenseVectorJacobianProductTest`), the
      rule tests (`ReducedDeltaTest`, `ReducedDeltaRulesTest`, `TraversableDeltaComputationTests`,
      `DeltaFeaturesTests`), and the graph-size check that a reduced delta is no larger than the
      operator it differentiates. Which `ar-profile-analyzer` views reveal an accidental
      full-Jacobian fallback.
   8. *Known limits, stated as of the time of writing.* Each verified against source or an
      existing test and recorded, not fixed: the `jacobianGradient` disagreement with the
      definition for the 256 by 64 output projection on Metal (undiagnosed; a 2^32 index-overflow
      hypothesis is on record), the weighted-sum lowering's missing reduced form, the learned-lambda
      gradient gap marked in the differential attention layers, and any K/V gradient wiring gap
      that still holds in `sequenceCrossAttention`, `TransformerResamplingFeatures` or
      `DifferentialAttentionFeatures`. A claim that no longer holds is dropped, not repeated.
2. **Cross-links and indexes.** Add the page to `llms.txt` (internals section, beside the forward
   pipeline pages) and to whatever index lists the internals pages in `docs/README.md`; link it from
   the "Automatic Differentiation" section of `compute/algebra/README.md`, the backpropagation
   material of `domain/graph/README.md`, `training-a-language-model.md` ("Weight gradients of dense
   layers"), `training-loop-examples.md`, and `end-to-end-computation.md` or
   `computation-graph-to-process-tree.md` where they end the forward story. Regenerate `llms-full.txt`
   with `scripts/generate-llms-full.sh` if `QUICK_REFERENCE.md` or `CLAUDE.md`-derived content is
   touched.
3. **Javadoc pass on the classes the page names.** Every public type named above has a class-level
   Javadoc that is accurate after the consolidation: no reference to a removed symbol
   (`MultiTermDeltaStrategy`, `DeltaAlternate`, `VectorJacobianProduct`, `attemptDelta`,
   `combineGradient`, `grad`, `differentiate`, `elementwisePartial`, `isolatedDelta`, `jacobian` as
   a method name), and `package-info` for `org.almostrealism.calculus` and
   `org.almostrealism.layers` points to the right entry points. Fix stale Javadoc in place; do not
   change behaviour.
4. **QUICK_REFERENCE.** If `docs/QUICK_REFERENCE.md` has a differentiation entry, bring it in line
   with the single `delta` method and `ReduceAxis`; if it has none, add a short one.

Out of scope: diagnosing or fixing any autodiff defect, adding reduced forms to computations that
lack them, renaming or restructuring the extension points, and any change to tests other than new
example-backed tests described under Approach.

## Approach

1. **Consult and recall first.** `consult` on `CollectionProducer.delta`, `ReduceAxis`,
   `DefaultGradientPropagation`, `ExpressionMatrix`; `memory_recall` for the delta-consolidation and
   matmul-VJP branches, whose review memories record subtle behaviours (for example the
   `jacobianGradient` `outSize` change in `e20913caf` and the variable-count partial-delta fix in
   `a82688188`).
2. **Read the source whole, not by grep.** `CollectionProducer` (the `delta` family),
   `CollectionProducerComputationBase` (the extension points), `GradientFeatures`, `ReduceAxis`,
   `InputStub`, every computation listed under Scope 1.3 and 1.5, and the `layers` backward classes.
   Keep an outline of each large file and cite stable identifiers (class and method names) only —
   never source line numbers (see `docs/CLAUDE.md`).
3. **Every claim is verified.** A statement about which rule a computation applies, which shape a
   result has, or which path falls back to the Jacobian is either read directly from source or
   demonstrated by an existing test. Where neither exists for a claim the page needs, add a small,
   new test in `engine/utils` (extending `TestSuiteBase`, with a `timeout`) that pins it, for
   example that `delta(x, ReduceAxis.OUT, g)` of a sum has the target's shape. New tests only; no
   existing test is edited.
4. **Examples compile.** Code snippets use the real API; any non-trivial snippet is mirrored by one
   of the tests above so it cannot drift silently.
5. **Defects are recorded, not fixed.** Anything found wrong while documenting goes into the
   "Known limits" section and a `memory_store` entry, and, if new, a tracker follow-up task.
6. **Validate.** `mcp__ar-build-validator__start_validation` (checkstyle, code_policy,
   test_timeouts, duplicate_code) and run any new test class with `mcp__ar-test-runner`. The work
   runs on any node: every claim it documents is backend-independent or already measured and
   recorded in `training-a-language-model.md`, so no Metal requirement is set for this workstream.

## Success Criteria

- `docs/internals/automatic-differentiation.md` exists and answers, without reading source:
  "what does `delta` return for each `ReduceAxis`", "which extension point do I override for a new
  computation, and in what order are they tried", "how does a dense layer's weight gradient avoid
  the Jacobian", "where does the Jacobian fallback still happen", "how does a gradient get from the
  loss to the optimizer", and "how do I test a derivative".
- `consult` on "how does automatic differentiation work" and on "which delta extension point should
  a new computation override" cites the new page.
- No document or Javadoc in the repository names a symbol removed by the delta consolidation (a
  repository grep for the removed names, outside `docs/plans/` and `docs/journals/`, is empty).
- Every known limit stated on the page is backed by a test, a source comment, or the training page's
  measurement.
- Build validator reports zero violations; any new tests pass.
- No line-number citations; no reference to `docs/plans/` from durable docs or code.

## Dependencies

None outstanding. The delta consolidation (PRs #661, #666) and the reduced-delta work (PR #628)
have both merged to `master`, so the page documents a settled design. If a further consolidation
commit lands mid-task (the matmul plan's revision note lists cleanup that has since been done), the
page follows `master`.

## What This Sets Up

- **Correctness:** a precise account of the sparse-Jacobian path is the prerequisite for
  diagnosing the `jacobianGradient` output-projection defect, which should be the next plan in this
  area.
- **Performance:** reduced forms for the remaining Jacobian-contracting computations (the
  weighted-sum `matmul` lowering first), each now a well-defined task against a documented contract.
- **Proof of value:** the LM track's next steps — non-degenerate generation, batch > 1, larger
  context — all add or change differentiated computations; a documented contract keeps those changes
  from reintroducing silent Jacobian fallbacks.
