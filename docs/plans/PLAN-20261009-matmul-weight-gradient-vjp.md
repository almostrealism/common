# Compute matrix-multiply weight gradients without materializing the Jacobian

**Category:** Performance (directly unblocking Proof of Value)
**Branch:** `project/plan-20261009-171224`
**Requires:** a macOS / Metal node (see `plan-20261009-171224-workstream.yaml`)

## Motivation

The platform can now train a decoder-only transformer language model on its own documentation
end to end (`CausalLanguageModelTest.trainOnDocumentation`, landed in PR #620). Every primitive is
correct: `CausalLanguageModelTest.gradientsMatchFiniteDifferences` shows every weight's gradient
matching finite differences at the FP32 noise floor, weights round-trip through `StateDictionary`,
and the sliding-window generator is verified against a full forward pass. The infrastructure works.

But the model has never beaten its own baseline and has never written a meaningful byte. The
documentation run ends at **4.957 bits per byte against a 4.912 unigram baseline** — above it — and
greedy generation emits 96 spaces. The training doc
(`docs/internals/training-a-language-model.md`) diagnoses this precisely and without speculation,
and the diagnosis is not "the model is wrong" or "the recipe is wrong." It is throughput:

> *The step budget is the limit. 550 steps of batch 1 score about 35,000 target bytes in total.
> For reference, an interpolated count-based bigram fitted to the whole training region scores 3.78
> bits per byte on the held-out targets and a trigram 3.07, so a byte-level model of this corpus has
> a great deal of headroom; the transformer has not seen enough text to use it. More steps do not
> fit: the run takes about 34 minutes of a 38-minute test timeout.*

A step takes ~3.6 s, essentially all of it the backward pass (~3.2–4.2 s; the forward pass is
~0.03 s), and the test thread spends that time waiting on Metal command buffers — it is GPU work,
not host overhead or GC. The doc then names the exact cause and the exact fix:

> *The weight gradients of the generic backward pass (`DefaultGradientPropagation`, through
> `GradientFeatures`) are formed from the operator's full Jacobian with respect to each weight,
> multiplied by the output gradient and summed over outputs. For a matrix multiply that is an
> iteration space of output size times weight size times the inner dimension: about 17 billion
> terms for the 256 by 64 output projection, 9.7 billion for each fused 192 by 64 QKV weight, and
> 4.3 billion for each feed-forward weight, where the gradient itself needs about a million
> multiply-adds per weight. A weight gradient that uses the structure of the matrix multiply (the
> output gradient times the transposed input) would make each step orders of magnitude cheaper and
> is the change that would let this run train for long enough to approach its headroom.*

This is the single highest-leverage performance lever the platform has today, it is evidenced by
measurement, and it sits squarely on the path to the self-understanding goal: make the training
step cheap enough and the platform can, for the first time, train a model that beats its baseline
and writes a readable sentence about itself. Every prior cycle built toward exactly this.

## The defect, concretely

`DefaultGradientPropagation.propagate` forms each weight gradient as (paraphrased):

```java
function.get().delta(weights[i])          // FULL Jacobian, shape [outSize, weightSize]
    .reshape(outSize, weightSize)
    .traverse(1)
    .multiply(c(gradient).reshape(outSize).traverse(1).repeat(weightSize))  // × upstream gradient
    .traverse(0)
    .enumerate(1, 1)
    .sum(1)                                // contract over outputs
    .reshape(shape(weightSize))
    .each();
```

`GradientFeatures.combineGradient` does the structurally identical thing for the **input** gradient.
Both first build `delta(...)` — the dense `[outSize, inSize]` or `[outSize, weightSize]` Jacobian —
and then multiply by the upstream gradient and sum over the output axis.

For a matrix multiply `y = W · x` the Jacobian `∂y/∂W` is block-diagonal: `∂y_i/∂W_{jk}` is zero
unless `i = j`. Materializing it dense is where the 17-billion-term iteration space comes from. The
quantity actually wanted is the **vector-Jacobian product** (VJP): the upstream gradient `g`
contracted with the Jacobian, which for a matrix multiply is just

```
dW = g ⊗ x         (outer product; equivalently g · xᵀ)   — the weight gradient
dx = Wᵀ · g                                                 — the input gradient
```

both of which the platform can already express as `matmul`/`weightedSum` over the small operand
shapes — "about a million multiply-adds per weight," as the doc says, versus billions.

**Reduction over the sequence (and any batch) axis.** The equations above are for a single input
vector `x`. In the documented model the dense layers do not see one vector — they are applied over a
whole sequence. `CausalLanguageModel`'s output is `(seqLen, vocab)` with `seqLen = 64` (see
`CausalLanguageModelTest.SEQ_LEN` and the `assertOutputShape` helper), so a weight `W` is shared
across all `seqLen` positions and each position contributes to its gradient. The weight VJP is
therefore the **sum of the per-position outer products**, not a single one:

```
dW = Σ_t g_t ⊗ x_t     over every position t (and every batch element, once batch > 1)
dx_t = Wᵀ · g_t         per position
```

The full-Jacobian path already performs this reduction implicitly (it contracts and sums over the
whole output axis, which spans every position). The structure-aware VJP must reproduce it exactly:
dropping the position/batch reduction would omit contributions and yield the wrong weight gradient.
This is the single most important thing the equality gate below must catch, so its representative
cases must use sequence-shaped inputs — not a single vector — for every shape tested.

This is exactly Approach 2 ("Vector-Jacobian Products") in `docs/plans/SPARSE_GRADIENTS.md`: pass
the aggregation intent (the upstream gradient) *into* the differentiation step so the contracted
result is produced directly, instead of forming a full Jacobian and contracting afterward.

## Scope

Deliberately narrow, to capture the whole payoff for the LM training step while leaving every other
gradient path bit-for-bit unchanged. The `feature/lora-gradients` history is the cautionary tale:
broad changes to the sparse-Jacobian machinery (subset/concat/projection and the masked-`Sum`
reordering) repeatedly triggered `convDeltaMedium` blow-ups and add/revert loops. This task does
**not** touch that machinery. It adds a structure-aware VJP for the matrix-multiply family only,
reached through a clean capability check with a fallback to the existing code for everything else.

In scope:

1. **A VJP capability on the matrix-multiply computation.** Give the computation(s) that back
   `matmul`/`dense` a method that, given an operand to differentiate and the
   upstream output gradient, returns the contracted gradient directly (`Σ_t g_t ⊗ x_t` summed over
   every sequence/batch position for the weight operand, `Wᵀ · g` per position for the input
   operand) expressed as ordinary `CollectionProducer` operations. Note that `matmul` does **not**
   lower to a single computation type: for a matrix-by-vector or batch-of-vectors product with an
   output dimension of 1000 or fewer it returns a `multiply(...).traverse(...).sum()` graph, and
   only for larger outputs (or a genuine matrix-by-matrix product) does it reach
   `weightedSum("matmul", ...)` (a `WeightedSumComputation`). The documented model's dense layers
   feed sequence rows through the vector path, so the 256×64 / 192×64 / 128×64 weights of interest
   may well take the `multiply`/`sum` lowering rather than `WeightedSumComputation`. The capability
   must therefore attach to whichever computation the targeted workload actually produces — see the
   Open questions, and confirm it with the profile (Approach 1) before choosing where the method
   lives.
   Place it on the type that owns the matrix-multiply concept, as a general capability — not as a
   private helper on a layer or a `DefaultGradientPropagation` special case. Follow the existing
   `attemptDelta` precedent: a method that returns the optimized form when it applies and `null`
   when it does not.

2. **Route the backward pass through it.** In `DefaultGradientPropagation.propagate` (weight
   gradient) and `GradientFeatures.combineGradient` (input gradient), attempt the VJP first and fall
   back to the current `delta(...)`-then-contract path when the operator is not a recognized
   matrix multiply. The fallback must be the *exact* current code, so any non-matmul operator —
   every convolution, every element-wise op, every attention sub-op that is not a plain matmul —
   behaves identically to today. No flag that changes default behavior for unrelated graphs.
   Note that these call sites do **not** hold the bare matmul: `DefaultGradientPropagation` computes
   `function.get().delta(weights[i])` on `operator.getResultant(input)`, and `LayerFeatures.dense`
   builds that operator as `matmul(p(weights), input).add(bias).reshape(outputShape)` — so the
   producer `delta` is called on is the output `reshape` (or the bias `add`), with the matmul one or
   two levels down. A capability that only exists on the inner matmul computation is therefore not
   visible at the call site unless `delta` carries it up through the reshape/bias-add wrappers; see
   the Open questions.

3. **Correctness gate.** The VJP result must equal the full-Jacobian result, not merely "look
   trained." Add an A/B equality test that computes both forms for representative shapes
   (the output projection 256×64, a fused QKV 192×64, a feed-forward 128×64) and asserts they agree
   to the FP32 noise floor. Each case must feed a **sequence-shaped input** (the model's `seqLen` of
   64 positions, not a single vector), so the test exercises the position/batch reduction
   `dW = Σ_t g_t ⊗ x_t` — a VJP that computed only one position's outer product would still pass a
   single-vector test but produce the wrong weight gradient in training. The reference side of the
   comparison must be the **full-Jacobian result, forced**: once Scope item 2 makes both call sites
   prefer the VJP for every recognized matmul, the legacy path is no longer reachable through the
   normal gradient API, so a test that simply calls that API twice would compare the VJP against
   itself and prove nothing. The test must therefore force the exact fallback when computing its
   reference — a separate baseline entry point, or a test-only switch that disables the capability
   check — so the quantity it compares against is genuinely the unchanged `delta(...)`-then-contract
   gradient and the asserted equality really is VJP-against-full-Jacobian. Also confirm
   `CausalLanguageModelTest.gradientsMatchFiniteDifferences`
   still passes unchanged (it differentiates the whole assembled model, so it exercises the new
   path through every weight). These are the specification; they may not be weakened.

4. **Measurement.** Using `ar-profile-analyzer` and the run's own timing, report warm step time and
   per-epoch time before and after, on the documentation configuration, on Metal. State the speedup
   as measured, not as "orders of magnitude" by assertion.

5. **Cash the payoff.** A cheaper step does **not** train the model for longer on its own. The test
   fixes the step count: `trainOnDocumentation` calls `optimizer.optimize(EPOCHS)` with `EPOCHS = 5`
   and `TRAIN_WINDOWS = 110`, so it runs exactly 5 × 110 = 550 steps regardless of how fast each one
   is. With a faster kernel the current configuration simply *finishes sooner* and leaves the rest
   of the 38-minute budget unused; the held-out score would be unchanged. Turning the speedup into a
   better model therefore requires an **explicit, measured** increase in the training budget —
   raising `EPOCHS` and/or `TRAIN_WINDOWS` so the freed time is spent on more steps. Note that the
   window spacing is derived from the step count (`trainStride = spanningStride(EPOCHS * TRAIN_WINDOWS)`),
   so a larger budget reads more windows and covers the training region more densely rather than
   re-reading the same 550. Make the budget change deliberately, report the old and new (steps,
   wall-clock, bits-per-byte) side by side, and keep the comparison apples-to-apples (same corpus,
   same held-out windows, same unigram baseline). Two honest outcomes, both acceptable:
   - With the enlarged budget the run now scores **below** the 4.912 unigram baseline and greedy
     generation produces more than one distinct byte: the acceptance assertions in
     `CausalLanguageModelTest` turn green *for the right reason* (a genuinely better model, measured
     apples-to-apples), and `training-a-language-model.md` is updated with the new budget, timing,
     curve, and generated sample.
   - The step is measurably cheaper and the budget was enlarged to use it, but the baseline is still
     not beaten: record the new throughput, the new budget, and the new best held-out figure, and
     hand the recipe/scale question (still more epochs, larger context/depth, batch > 1) to a
     follow-up plan with evidence. This is a finding, not a failure.

Out of scope (and must stay untouched): the subset/concat/projection sparse-Jacobian family and the
masked-`Sum` reordering machinery from `feature/lora-gradients`; `sequenceCrossAttention`,
`TransformerResamplingFeatures` and `DifferentialAttentionFeatures` K/V gradient wiring; a
single-position KV-cache decode path; batch > 1 in `scaledDotProductAttention`. Any of these may be
the *next* plan; none is this one.

## Approach

1. **Confirm the shapes first (`ar-profile-analyzer`).** Load the existing documentation-run profile
   (or wire a small profiled reproduction of the backward pass if none is current) and read off the
   recorded operation shapes that identify the expensive weight-gradient kernels — the doc notes
   that `OperationProfileNode` records Metal dispatch time, not kernel execution time, so the
   *shapes*, not the per-op milliseconds, are what name the cost. Verify the `[outSize, weightSize]`
   Jacobian materialization is present exactly where the code above predicts, before changing
   anything. This is Rule 3b: look inside the kernel before theorizing.

2. **Add the VJP method** on the matrix-multiply computation/producer, expressed entirely as
   `CollectionProducer` compositions (no Java arithmetic, no `evaluate()` inside the graph). Derive
   `dW` and `dx` from the operand shapes and the upstream-gradient shape. Keep the metadata-style
   `operation`/label argument first if the method logs or names anything, per the project
   conventions.

3. **Wire the two call sites** to try the VJP and fall back. Keep the fallback code byte-identical
   to the current path so a reviewer can see non-matmul behavior is unchanged.

4. **Prove equality, then measure, then re-run** in that order. Do not declare the step cheaper
   from the diff; measure it. Do not declare the baseline beaten from a single noisy run; score all
   83 held-out windows apples-to-apples against the same-corpus unigram entropy, exactly as the
   current test does.

5. **Validate** with the build validator (`checkstyle`, `code_policy`, `test_timeouts`,
   `duplicate_code`) and the targeted fast tests (`CausalLanguageModelTest`'s fast methods,
   `NextTokenDatasetTest`, the new A/B equality test, and any matrix/gradient tests in
   `compute/algebra` that import the changed computation) before declaring done.

## Success Criteria

- A structure-aware VJP weight/input gradient exists for the matrix-multiply family, placed as a
  general capability on the type that owns matrix multiplication, and reached by
  `DefaultGradientPropagation` and `GradientFeatures.combineGradient` with an exact fallback for
  every non-matmul operator.
- An A/B test asserts the VJP gradient equals the full-Jacobian gradient to the FP32 noise floor on
  the output-projection, QKV, and feed-forward shapes — with the reference side computed through the
  **forced** exact fallback (a separate baseline entry point or a test-only switch), not the
  auto-selected path that now prefers the VJP — and
  `CausalLanguageModelTest.gradientsMatchFiniteDifferences` still passes unchanged.
- Measured warm step time and per-epoch time on the documentation configuration (Metal), before and
  after, reported with the profile that backs them — a real, stated speedup.
- `CausalLanguageModelTest.trainOnDocumentation` re-run on the cheaper step with an explicitly
  enlarged training budget (`EPOCHS`/`TRAIN_WINDOWS`) so the freed wall-clock is spent on more
  steps — the old and new (steps, wall-clock, bits-per-byte) reported side by side — with the
  outcome recorded honestly in `training-a-language-model.md`: either the baseline is beaten and the
  acceptance assertions pass for the right reason, or the new throughput and best held-out figure
  are documented and the remaining gap is handed to a follow-up plan.
- No change to the sparse-Jacobian/`Sum`-reordering machinery; `RepeatedDeltaComputationTests`
  (the `convDelta*` family) is not regressed.
- Build validator clean; no weakened assertion, tolerance, dimension, `@TestDepth`, or timeout
  anywhere (`test-integrity-check` must pass).

## Dependencies

None blocking. Builds directly on PR #620 (the assembled `CausalLanguageModel`, `NextTokenDataset`,
the finite-difference gradient test, and the documentation run) and on the existing
`matmul`/`dense`/`weightedSum` infrastructure in `MatrixFeatures`. Independent of the in-flight
CUDA async-runner work and of the dormant `feature/lora-gradients` surface.

## Estimated Complexity

**Medium–large.** The VJP derivation and its two call-site integrations are a bounded, well-located
change, and the correctness gate is an equality assertion against code that already exists. The size
lives in the care required: proving exact equality, keeping the non-matmul fallback untouched to
avoid the `convDelta` blow-up that bit earlier gradient work, and the ~34-minute Metal training
re-run that validates the payoff. Scoped to the matrix-multiply family only, it is achievable in a
focused session on a macOS/Metal node.

## Open questions (for the approver / implementer)

- **Where exactly the VJP method lives.** `matmul` has more than one lowering, so "the computation
  `matmul` returns" is not a single type. The matrix-by-matrix (and large-output) path builds a
  `weightedSum`/`WeightedSumComputation`; the matrix-by-vector / batch-of-vectors path with output
  ≤ 1000 builds a `multiply(...).traverse(...).sum()` graph instead, and `LayerFeatures.dense`
  reshapes its input to sequence rows and calls `matmul`, so the documented model's weights may take
  the latter path. A capability added only to `WeightedSumComputation` would then miss the main
  workload, which would silently take the full-Jacobian fallback and leave the payoff on the table.
  The implementer must confirm — from the profile (Approach 1), not by assumption — which
  computation each targeted weight actually produces, and place the method so both lowerings in use
  are covered (either a shared capability both producers expose, or a marker attached before the
  product is lowered so it survives whichever graph is built). The method should still sit wherever
  the delta of that producer is already decided (alongside `attemptDelta` / the producer's own
  `delta` override), so both the weight and input call sites reach it through one capability check.
- **Whether the capability is discoverable through the dense wrappers.** Even once the method is on
  the right computation, the backward-pass call sites never see that computation directly. For a
  dense layer, `LayerFeatures.dense`'s forward operator is
  `matmul(p(weights), input).add(bias).reshape(outputShape)`, and `DefaultGradientPropagation`
  calls `function.get().delta(weights[i])` on the outermost producer — the `reshape` (and, when a
  bias is present, a bias `add` beneath it), not the matmul. So a marker or VJP method attached only
  to the inner matmul / `weightedSum` / `multiply`-`sum` node is invisible at the point `delta` is
  invoked, and the dense weights — the main documented workload — would silently take the
  full-Jacobian fallback. The implementer must therefore make the capability propagate up through
  the bias-add and the output reshape (so the enclosing `delta` surfaces the inner matmul's VJP when,
  and only when, those wrappers are shape-preserving pass-throughs for the weight operand), or
  recognize the matmul during delta propagation at the point the chain reaches it. Either way the
  A/B equality gate must run against the gradient of the **whole dense operator**
  (reshape ∘ add ∘ matmul), not a bare `matmul`, so a capability that fails to survive the wrappers
  is caught as a fallback rather than mistaken for a pass.
- **How "matrix multiply" is recognized.** The fallback is only safe if recognition is precise —
  it must fire for the dense/matmul weight products and nothing else. A bare type check on
  `WeightedSumComputation` is **not** sufficient: that computation is shared by convolution,
  attention and custom tensor contractions (see its own Javadoc), and `scaledDotProduct` constructs
  it directly via `weightedSum("scaledDotProduct", ...)`. Worse, the operation name passed to
  `AlgebraFeatures.weightedSum` ("matmul" vs "scaledDotProduct") is currently **discarded** — it is
  not plumbed into the `WeightedSumComputation` constructor — so there is no stored marker to
  distinguish a matmul from an attention score today. Recognizing matmul therefore requires either
  (a) a dedicated, complete structural predicate that matches only the matmul lowerings, or (b)
  plumbing an explicit matmul marker/metadata through `weightedSum` (and onto the `multiply`/`sum`
  vector-path graph) so it can be checked downstream. Whichever is chosen, every non-matmul operator
  must return `null` and fall back to the exact current path, so an unrelated op can never be
  misrecognized as a matmul and given a wrong `Wᵀg`/outer-product gradient.
- **Whether the budget re-run clears the baseline.** The doc's headroom numbers (bigram 3.78,
  trigram 3.07 on the held-out targets) say a better-trained byte model should clear 4.912 with
  margin, but the exact number of extra steps the speedup buys, and whether coverage or late-update
  noise then becomes the next limit, can only be known by running it. The plan treats a measured
  speedup with a documented remaining gap as a valid outcome.
