# Project Manager Log

The living journal of the Almost Realism project management process: current thinking about
where the platform is heading, why specific tasks are chosen, and how each piece of work
connects to the larger vision. This is a snapshot of current thinking, not an archive — older
entries are condensed or pruned to keep it under roughly 50,000 characters.

---

## Current State

### Strategic Direction

1. **Foundation** — documentation, clean code, comprehensive tests
2. **Performance** — hardware-accelerated computation that rivals dedicated frameworks
3. **Proof of Value** — model replication, audio generation, distributed training
4. **Self-Understanding** — training modest language models locally that understand the
   platform itself, so the software grows alongside its own capabilities

### Task Categories (priority order)

1. Documentation — can the platform be understood by humans and AI systems?
2. Code Quality — is the codebase clean, consistent, well-tested?
3. Performance — can it handle real workloads efficiently?
4. Proof of Value — can we demonstrate compelling capabilities?

---

## Planning History

### 2026-10-18 — Fix the nondeterministic full-Jacobian weight gradient for the output projection

**Category:** Code Quality (correctness — a silent wrong-gradient defect in the autodiff engine)
**Branch:** `project/plan-20261018-174738`
**Plan:** [`PLAN-20261018-full-jacobian-gradient-correctness.md`](PLAN-20261018-full-jacobian-gradient-correctness.md)

#### What changed since last cycle

The autodiff internals page landed (PR #670, with follow-ups #667/#673): the backward half of the
execution story is now documented end to end, and the prior entry named that page as the
**prerequisite** for the correctness work below. So the gate it set is now open. Everything else the
last entry promised is still queued behind it: non-degenerate LM generation (greedy decoding still
repeats one byte), the weighted-sum `matmul` reduced form, the `io.almostrealism.collect` split.

#### Category assessment (priority order)

- **Documentation — excellent, and newly complete where it mattered.** The internals corpus is 38
  pages and now includes `automatic-differentiation.md`; `consult` finds no flagship gap. The only
  standing items (the ONNX-pressure note, line-number hygiene) are low-priority and owned by
  continuous QA. Moved on.
- **Code quality — the category, because of a specific, evidenced correctness defect.** It is not a
  style or coverage gap: `GradientFeatures.jacobianGradient`, the full-Jacobian fallback that is the
  *reference every reduced-delta test is checked against*, produces a **nondeterministically wrong**
  weight gradient for the 256×64 output projection over 64 rows — ~1/10 of 16,384 entries, 1,728 on
  one profiled Metal run and 1,536 on another, always from weight row 64 onward. It is recorded
  honestly today (a `TODO(review)`, the "known limits" prose, and
  `DenseVectorJacobianProductTest.outputProjection` logging the disagreement instead of asserting
  it), and it was explicitly deferred to "its own plan" by the VJP review. This is that plan.

#### Why this task

A learning system whose *reference gradient* is silently, intermittently wrong is the most
consequential kind of foundation defect: the platform's first self-hosted training runs trained the
output projection from this path, every non-matmul operator still falls back to it, and it is the
ground truth the whole reduced-delta suite trusts. Correctness of the differentiation engine sits in
the second category and ranks above performance and proof of value — and this is a concrete,
falsifiable instance of it, now unblocked by the documentation it needed.

The plan follows evidence, not the first hypothesis. The `TODO(review)` guesses a 32-bit index
overflow (2³⁴ iteration space, errors beginning at flat index 2³²), but the profiling already done
*contradicts* that: a deterministic overflow would miscompute the same entries every run, and this
does not. The nondeterminism, and the ~1M-element `(64,256,64)` repeat buffer the sum kernel reads,
point instead at argument binding / aggregation or at the ordering of the kernel that writes that
buffer and the kernel that reads it. The layernorm "invalid kernel" journal
(`INVALID_KERNEL_AGGREGATION.md`) is cited only as an untrusted lead: `docs/journals/CONCESSION.md`
retracts its central claim (the "divergent" kernels were a mismatched comparison), so no aggregation
defect is established and the plan does not reason from one. So the plan separates the leads with
cheap single-variable experiments — disabling aggregation via `AR_HARDWARE_ARGUMENT_AGGREGATION`,
then forcing the write/read ordering — reads the generated kernel with `ar-profile-analyzer` before
theorizing, fixes the general mechanism (not the one shape), and tightens the existing test to
*assert* agreement on repeated evaluation. Weakening the test to go green is out of bounds and the
plan says so.

#### Balance across categories

The last several cycles alternated foundation documentation with proof-of-value milestones, and the
proof-of-value track (a trained LM that beats its baseline, PR #628) is one honest step from
speaking. But it cannot speak on a gradient that is sometimes wrong — and the degenerate single-byte
generation may itself be a symptom of an output projection trained on this very defect. Fixing the
reference gradient is the right move before the next generation push: it is cheaper now (the
failing kernel is already profiled and the candidate mechanisms named), it protects every later LM step, and it keeps the platform
honest about its own learning machinery.

#### What comes next

1. This plan: diagnose the nondeterministic output-projection fallback on Metal via
   `ar-profile-analyzer`, fix it at the general argument-mapping/ordering (or index-typing) layer,
   assert the agreement in `DenseVectorJacobianProductTest`, check convolution for the same defect
   with a new test against a host reference (the existing convolution-delta tests only log shapes),
   and update the "known limits" docs and the `TODO(review)`.
2. **Proof of value — non-degenerate generation**, revisited with a now-trustworthy gradient:
   temperature/top-p in the sliding-window generator, a KV-cache decode, more steps/larger context.
   Re-run `trainOnDocumentation` to see whether the corrected gradient moves the held-out score.
3. **Performance:** the reduced form for the weighted-sum `matmul` lowering (outputs wider than 1000),
   the last Jacobian-contracting path on a dense layer.
4. Still open: the ONNX-pressure doc note; the `io.almostrealism.collect` package split; letting a
   trained model read the platform's own *source*.

### 2026-10-17 — Document automatic differentiation from `delta` to the backward pass

**Category:** Documentation
**Branch:** `project/plan-20261018-013621`
**Plan:** [`PLAN-20261018-automatic-differentiation-internals.md`](PLAN-20261018-automatic-differentiation-internals.md)

#### What changed since last cycle

The matrix-multiply plan landed (PR #628) and paid off: a training step is about ten times cheaper
(3.6 s → 0.35 s), and `trainOnDocumentation` now **beats its unigram baseline, 4.194 against 4.912
bits per byte** over all 83 held-out windows. Two findings along the way: `matmul` and the dense
bias paired axes by size when sizes coincided (fixed), and the full-Jacobian weight gradient is
itself wrong for the 256×64 output projection on Metal (recorded, undiagnosed). Greedy generation
is still a single repeated byte, so the test's generation assertion still fails. Then the
maintainer consolidated the derivative API (PRs #661, #666): the matmul-specific
`VectorJacobianProduct` became a general reduced delta, `CollectionProducer.delta(target, axis,
weight)` is the only public derivative method, and computations customize it through four protected
extension points (`deltaDependent`, `deltaElement`, `deltaPartial`, `deltaMatrix`). Small tracked
tasks (projection factories, `NumberConditioner`) and the QA retrospective round also landed.

#### Category assessment (priority order)

- **Documentation — a real gap, and it decided the cycle.** The forward story is fully documented;
  the backward story is not. `consult` confirms there is no internals page for automatic
  differentiation. What exists is a `package-info` paragraph, a three-line README example, and the
  dense-layer section of the training page. Nothing covers the extension points and their order,
  the expression-level rules and `ExpressionMatrix`, or how `DefaultGradientPropagation` and the
  backward cells turn deltas into a training step. The API has just been redesigned, so this is the
  moment when the knowledge exists and the page is cheapest to write.
- Code quality, performance and proof of value all have candidates (below). I did not choose any
  of them, because documentation comes first and this gap is genuine.

#### Why this task

Autodiff is how the platform learns, and it is the mechanism a model trained on this corpus should
be able to explain about itself. It is also where the costliest silent failures live. A new
operator without a reduced form falls back to a full Jacobian without any warning (the
17-billion-term output projection and `convDeltaMedium` were both that fallback). A wrong sparse
Jacobian trained the LM's output projection with partly wrong gradients for weeks. A documented
contract ("which extension point, in what order, how to verify it, where the fallback still
happens") protects every later LM step from both. The plan requires every claim to be read from
source or pinned by a test. Defects found along the way are recorded, not fixed.

#### Balance across categories

The last four cycles went to performance and proof of value, and they produced real results. This
cycle goes back to the foundation, and not by rote: the subsystem those cycles rebuilt now needs
its own page. It is a medium-sized task that runs on any node.

#### What comes next

1. This plan: `docs/internals/automatic-differentiation.md`, cross-links, a Javadoc pass for names
   the consolidation removed, and a "known limits" section.
2. **Correctness:** diagnose the `jacobianGradient` disagreement for the 256×64 output projection
   (a 2^32 index-overflow hypothesis is on record). The new page is the prerequisite.
3. **Proof of value:** non-degenerate generation from the trained LM. The baseline is beaten, but
   greedy decoding still repeats one byte. Next come recipe and scale: batch > 1 in
   `scaledDotProductAttention`, more steps, a larger context or depth. Then temperature/top-p in
   the sliding-window generator, and a KV-cache decode.
4. **Performance:** a reduced form for the weighted-sum `matmul` lowering (outputs wider than
   1000), the last Jacobian-contracting path on a dense layer.
5. Still open: the ONNX-pressure doc note; the `io.almostrealism.collect` package split; letting a
   trained model read the platform's own *source*.

### 2026-10-09 — Compute matrix-multiply weight gradients without materializing the Jacobian

**Category:** Performance (directly unblocking Proof of Value)
**Branch:** `project/plan-20261009-171224`
**Plan:** [`PLAN-20261009-matmul-weight-gradient-vjp.md`](PLAN-20261009-matmul-weight-gradient-vjp.md)

#### What changed since last cycle

The last cycle's plan landed (PR #620). The self-hosted LM infrastructure is now real and correct:
`CausalLanguageModel` is a `Model`, `SlidingWindowAutoregressiveModel` generates from it and is
verified against a full forward pass, `CausalLanguageModelTest.gradientsMatchFiniteDifferences`
shows every weight's gradient matching finite differences at the FP32 noise floor, and weights
round-trip through `StateDictionary`. PR #620 also carried the `ObjectOrientedDesignRule`
enforcement backstop. But the two headline acceptance criteria are still *unmet, and recorded
honestly in the tree*: `trainOnDocumentation` scores **4.957 bits/byte against a 4.912 unigram
baseline** (above it, so the assertion fails) and greedy generation emits **96 spaces**. The CUDA
async-runner track (PR #623) and continuous QA (today's `qa/docs`, `qa/defect`, `qa/consolidate`,
`qa/pdsl`, `qa/performance` branches) have been landing in parallel.

#### Category assessment (priority order)

- **Documentation — excellent.** The internals corpus tells the whole story, and
  `training-a-language-model.md` is a model of honest, measured writing: it documents the failing
  run, the recipe sweep, the coverage lever, the per-step cost breakdown, and names the fix itself.
  No flagship gap. Moved on.
- **Code quality — strong and owned.** The continuous QA pipeline merges fixes daily and
  enforcement is mechanical; PR #620 added `ObjectOrientedDesignRule` as a per-class design
  backstop. The one standing minor item (`io.almostrealism.collect` split) is unchanged and not a
  flagship. Moved on.
- **Performance — the category, and the lever is named by the platform's own documentation.**
  `training-a-language-model.md` states it outright: the generic backward pass forms each weight
  gradient from the operator's *full Jacobian* (about 17 billion terms for the 256×64 output
  projection) then contracts it, a step takes ~3.6 s of which the backward pass is ~3.2–4.2 s of
  GPU work, and "a weight gradient that uses the structure of the matrix multiply (the output
  gradient times the transposed input) would make each step orders of magnitude cheaper and is the
  change that would let this run train for long enough to approach its headroom."

#### Why this task

It is the honest next step named by the platform itself, and it is the highest-leverage performance
work available. The step cost — not the model, not the recipe, both of which the finite-difference
test and the recipe sweep have cleared — is what keeps the LM at the unigram rate: 550 batch-1 steps
score only ~35 kB of a corpus whose bigram headroom is 3.78 bits/byte. Make the matrix-multiply
weight gradient a structure-aware vector-Jacobian product (`g ⊗ x` instead of a materialized
`[outSize, weightSize]` Jacobian — exactly Approach 2 in `SPARSE_GRADIENTS.md`) and, with the
training budget (`EPOCHS`/`TRAIN_WINDOWS`) raised to spend the freed time, the same 38-minute budget
buys many more steps. This is a Performance task whose payoff is the Proof-of-Value
milestone two cycles have been reaching for: a model that finally beats its baseline and writes a
readable byte about itself.

The scope is deliberately narrow, because the history here is unforgiving. The `feature/lora-gradients`
branch's broad sparse-Jacobian and masked-`Sum` reordering work repeatedly triggered the
`convDeltaMedium` blow-up and the add/revert loop this log exists to prevent. This plan touches
*only* the matrix-multiply family, behind a precise capability check with a byte-identical fallback
for every other operator, so convolution and the rest of the backward pass are provably unchanged.
Correctness is gated by A/B equality against the existing full-Jacobian result, not by "it looks
trained." Turning the acceptance test green is allowed only by a genuinely better model measured
apples-to-apples; a measured speedup with a documented remaining gap is an acceptable, honest
outcome that hands the recipe/scale question to the next plan.

#### Balance across categories

Foundations stay covered by continuous QA. The last several cycles alternated foundation work with
proof-of-value milestones; this cycle spends a focused performance investment to cash the milestone
the LM track has been one step short of since September. It is the "whichever per-step backward cost
the training-run profile names" item that the 2026-09-30 and 2026-10-05 "what comes next" lists both
deferred — now promoted to its own plan, with the profile in hand to name it.

#### What comes next

1. This plan, if approved: the VJP weight/input gradient for matrix multiply, its two call-site
   integrations with exact fallback, the A/B equality gate, a measured step-time speedup, and a
   re-run of `trainOnDocumentation` whose outcome is recorded honestly in the training doc.
2. If the baseline is beaten: **sampling quality and longer generation** (temperature/top-p already
   exist in `AutoregressiveModel`; expose top-p through the sliding-window generator), and a proper
   single-position **KV-cache decode** (needs the fused `qkv` split into Q/K/V views and the rotary
   inputs derived from `rope_inv_freq`).
3. **Scale:** batch > 1 in `scaledDotProductAttention`, larger context/depth, and the K/V gradient
   wiring still missing in `sequenceCrossAttention`, `TransformerResamplingFeatures` and
   `DifferentialAttentionFeatures`.
4. A later cycle: let a trained model read the platform's own *source*, the deeper self-understanding
   aim. Still open from prior cycles: the ONNX-pressure doc note; the `io.almostrealism.collect`
   package split.

### 2026-10-05 — Make the self-hosted LM beat its unigram baseline and generate text

**Category:** Proof of Value
**Branch:** `project/plan-20261005-185050`
**Plan:** [`PLAN-20261005-self-hosted-lm-beat-baseline-and-generate.md`](PLAN-20261005-self-hosted-lm-beat-baseline-and-generate.md)

#### What changed since last cycle

Two proof-of-value tracks landed. The from-scratch self-hosted LM merged (PR #596): the platform
trained a decoder-only transformer on its own docs, end to end, every gradient through the normal
graph machinery, weights round-tripped through `StateDictionary`. Separately, the
PDSL-for-research / Qwen interpretability track merged most recently (PR #615) — a different track
(reading a real Qwen checkpoint on one readable page), not a continuation of the from-scratch LM.

But the self-hosted LM stopped one step short of its own promise, and it stopped before producing a
single byte of text. Both gaps are recorded honestly in the tree:
- `CausalLanguageModelTest.trainOnDocumentation` asserts held-out `finalBits < unigramBits` and
  **fails**: 4.691 bits/byte held-out against a 4.600 unigram baseline
  (`NextTokenDataset.scoredTargetEntropyBits()` over 12 scored windows). It is long-running and
  excluded from the CI pipeline profile, so it does not break CI — but the last cycle's headline
  success criterion is unmet. The earlier "0.22 below baseline" figure was a measurement error
  (12 windows vs whole-region entropy), corrected to this honest number last cycle.
- The model has **never generated text**. The general `AutoregressiveModel` sampling infrastructure
  exists and is sound, but `CausalLanguageModel` only builds the full-sequence training model; there
  is no path from the trained weights to text.

#### Category assessment (priority order)

- **Documentation — excellent.** Internals corpus tells the whole story and now includes
  `training-a-language-model.md`; the one standing gap (ONNX-induced pressure) is low-priority and
  out of scope. The only doc this task touches is the training page, updated as its own deliverable.
- **Code quality — strong and owned** by the continuous QA pipeline; enforcement is mechanical. No
  neglected flagship.
- **Performance — one deferred lever** (13 small reductions compiled as native kernels, ~250 ms
  each). The log has deliberately deferred it to *follow* a real training run. This task produces
  exactly such a run and is the right place to re-check, via `ar-profile-analyzer`, whether it
  matters in practice — as reporting only.
- **Proof of value — the category.** A known-failing acceptance test and a model that has never
  spoken are precisely the concrete, falsifiable gaps this category exists to close.

#### Why this task

It is the honest completion of last cycle's milestone and the direct next step named by the training
doc itself ("scoring every held-out window (82 at this stride), or training further, are the next
steps"). The headroom is evidenced: a training-fitted unigram scores 4.78 on the scored targets (the
model beats that), while an interpolated bigram scores ~3.85 on the held-out region — so a byte-level
transformer that models local structure should clear the unigram bar with margin. The task pairs the
quality fix with the first generation path, so the outcome is not just a green test but the platform
writing text about itself for the first time — the first readable artifact of the self-understanding
goal.

Because this sits on top of a known-failing acceptance test, the plan is explicit that the only
acceptable way to turn it green is a genuinely better model measured apples-to-apples; weakening the
assertion, the baseline, the dimensions, `@TestDepth` or the timeout is out of bounds and would fail
`test-integrity-check`. If honest effort within the memory/time budget cannot clear the bar, the
correct outcome is an evidenced finding for a revised plan, not a green test.

#### Balance across categories

Foundations stay covered by continuous QA. The last several cycles alternated foundation work with
proof-of-value measurement; this cycle cashes the training milestone it set up — closing the loop
rather than opening a new front. The deferred compile-time lever is revisited only as a measurement
by-product, keeping the cycle focused.

#### What comes next

1. This plan, if approved: representative all-window held-out measurement, a tuned training recipe
   that clears the baseline with margin, a generation path on `CausalLanguageModel` with a
   deterministic generation test, and a rewritten training doc with a generation section. The plan's
   "Open questions" record the risks a reviewer added: the run is ~34 min against a 38-min test
   timeout, scoring all 82 held-out windows every epoch could exceed it, and the margin needed to
   call the result "comfortable" is not yet fixed.
2. **Sampling quality and longer generation** once the first path works (temperature sampling exists
   in `AutoregressiveModel`; top-p exists in its static `sampleToken` but is not exposed by the
   `of(...)` factory); a proper KV-cache decode if the first path was sliding-window (that needs the
   fused `qkv` weight split into Q/K/V views and the rotary inputs derived from `rope_inv_freq`).
3. **Scale:** batch > 1 in `scaledDotProductAttention`, larger context/depth, and whichever per-step
   backward cost the training-run profile names (the standing small-reduction native-compile lever).
4. A later cycle: let a trained model read the platform's own *source*, the deeper self-understanding
   aim. Still open from prior cycles: the ONNX-pressure doc note; the `io.almostrealism.collect`
   package split.

### 2026-09-30 — Train a tiny byte-level causal transformer on the platform's own docs

**Category:** Proof of Value
**Branch:** `project/plan-20260930-180401`
**Plan:** [`PLAN-20260930-self-hosted-tiny-lm.md`](PLAN-20260930-self-hosted-tiny-lm.md)

#### What changed since last cycle

The fine-tuning re-baseline landed (PR #582) and changed the picture more than expected.
`FINE_TUNE_FAIL.md` no longer says "infeasible". The cold first training step of a 1–3-block LoRA
`DiffusionTransformer` is now 13.6–20.8 s across embed 8 → 256 (February: 8 to over 44 min). It
does not grow with embed, and each block adds about 1 s. The February `delta()` scope-error blocker
no longer reproduces. Getting there also fixed two outright training failures (a
`DiffusionTrainingDataset` shape check and an instruction-cache collision at embed ≥ 64) and one
dominant analysis cost (`ExpressionMatrix.uniqueNonZeroOffset`).

#### Category assessment (priority order)

- **Documentation.** Still excellent. The one catalogued gap (the ONNX-pressure note) is a
  low-priority research item. Moved on.
- **Code quality.** Strong, and owned daily by the QA pipeline. The `io.almostrealism.collect`
  split remains minor. One thing did show up: `AttentionFeatures.java` is 1826 lines, above the
  1500-line recommendation. The plan requires that the new work not grow it and invites moving the
  sequence-attention family into its own mixin. That fixes the problem where the change is made,
  without spending a cycle on it.
- **Performance.** The re-baseline names one evidenced lever: 13 small reductions compiled as
  native kernels at about 250 ms each, roughly 3 s of a 7.9 s cold backward. It is real but it is a
  one-time cost of seconds per JVM, and it no longer stands between the platform and any workload.
  Whether it matters for a real training run is best learned by *running* one. So I deferred it
  to follow the proof-of-value run rather than lead it.
- **Proof of value.** This is where the cycle goes. It is the step the log has promised since March
  ("if the verdict flips toward feasible, scope the minimal end-to-end self-hosted training run"),
  and the verdict has flipped.

#### What the survey found

Performance no longer blocks a self-hosted LM; missing capability does. The platform has never
trained a transformer language model from scratch, and the pieces next-token training needs are
missing or unverified. Full-sequence attention (`sequenceAttention` /
`scaledDotProductAttention`) has **no causal mask**; `causalMask` is the single-position KV-cache
variant. `sequenceAttention` routes K and V through fixed buffers read back as constants, so
**gradients may reach only the Q slice** of the QKV weight, and nothing has tested this per slice.
There is **no trainable embedding layer** (`rows()` gradients are untested; Qwen3 looks embeddings
up on the host). There is **no byte tokenizer**: the text tokenizers need an external model
vocabulary (SentencePiece, Qwen3 BPE, or arrays passed to `BPE`), and the fixed-mapping MIDI
tokenizers do not handle text. There is also **no text dataset**. Cross-entropy (`logSoftmax` +
`NegativeLogLikelihood`) is **not quite ready** for a `(seqLen, vocab)` output:
`NegativeLogLikelihood.loss` averages over rows but its `gradient` is the unnormalized `-target`,
so backpropagation would follow the summed loss (64× the reported loss's gradient at context 64).
Existing users train single-row outputs, where this does not show; the plan adds the `1 / rows`
normalization and a multi-row gradient test as a prerequisite of the training run.
`StateDictionary` checkpointing is ready.

#### Why this task

It turns "training is feasible" into "the platform trained a language model on itself," which is
the first concrete artifact of the self-understanding goal. It puts the risky gradient checks
(K/V slices, embedding table) first, so the plan either proceeds or pauses early at a precise,
evidenced finding that goes back for a revised plan. The primitives it adds (causal sequence
attention, trainable embedding, byte tokenizer, text dataset) are general, and every later
LM-training step reuses them. The bar is modest and falsifiable: held-out bits-per-byte below the
unigram baseline, weights round-tripped through `StateDictionary`.

#### Balance across categories

Three of the last four cycles went to documentation and performance measurement. That was the
right foundation, but proof of value has been deferred since March. This cycle spends the
compile-time win on the platform's actual reason for existing. The foundation categories stay
covered by the continuous QA pipeline.

#### What comes next

1. This plan executes, gradient checks first. If K/V gradients need core autodiff work near
   `feature/lora-gradients`, the recommended path is to split that fix into its own plan.
2. **Sampling:** wrap the trained weights in `AutoregressiveModel` and generate text, the first
   time the platform "speaks" about itself.
3. **Scale:** batch > 1 in `scaledDotProductAttention`, larger context and depth, and whichever
   per-step cost the training-run profile names. The small-reduction native-compile lever is the
   standing candidate.
4. Still open: the ONNX-pressure documentation note; the `io.almostrealism.collect` package split;
   a feasibility threshold for production-scale fine-tuning.

### 2026-09-29 — Re-baseline LoRA fine-tuning backward-pass compile scaling

**Category:** Performance
**Branch:** `project/plan-20260929-043714`
**Plan:** [`PLAN-20260929-finetune-compile-scaling-rebaseline.md`](PLAN-20260929-finetune-compile-scaling-rebaseline.md)

#### Category assessment (priority order)

- **Documentation — excellent.** Every layer/domain/engine module has a README; `docs/internals/`
  now holds 36 pages spanning the whole story (Producer → process tree → process optimization →
  backend compilation/dispatch → `native-runtime-lifecycle`, which landed last cycle). The only
  catalogued gap left is the low-priority ONNX-induced-pressure research note in
  `KERNEL_AND_MEMORY_DOCUMENTATION_GAPS.md`, explicitly "a research task in its own right." Not a
  flagship. Moved on.
- **Code quality — strong and actively owned.** The continuous QA pipeline (`qa/defect-*`,
  `qa/consolidate-*`, `qa/coverage-*`) merges fixes daily, and enforcement (checkstyle, code_policy,
  duplicate_code, test-integrity) is mechanical. The one flagged structural debt (split
  `io.almostrealism.collect`) is real but minor and high-churn. No neglected, high-value flagship
  here. Moved on.
- **Performance — the category.** The compile-time front saw a decisive win: the kernel-series /
  `Expression.sequence` analysis that dominated expression simplification was rebuilt on
  `feature/cl-profile-perf` (2026-09-08), taking `convDeltaSmall` 172 s → 1.15 s and `upsample`
  233 s → 4 s (see `CONVOLUTION_COMPILE_TIME.md` §Resolution). That is genuine open leverage — see
  below.

#### The finding that decided it

`FINE_TUNE_FAIL.md` is the platform's authoritative verdict on training feasibility, and it says, in
bold, that production-scale LoRA fine-tuning is **infeasible** — backward-pass compile time scaling
super-linearly, projected to "days, not minutes" at embed=1024/depth=16. That measurement is from
**February 2026**. Its stage-detail profile lists `expressionCacheMatch` (~1375.6 s) as by far
the largest accumulated entry and the kernel-series / `Sum.simplify → getSeries →
Expression.sequence` analysis (`kernelSeries`, ~150 s) as a much smaller one. Both are
non-exclusive `stageDetailTime` accumulations (the first is listed at 268 % of the 512.8 s run),
so neither is a wall-clock share and they should not be ranked as exact levers. Both fronts have
since moved, by *different* work, and it is worth keeping them straight: the September
`ExplicitExpressionMatrix` cache-bypass and on-demand-entry changes attacked `expressionCacheMatch`
directly (isolated `testSingleAttentionBackward` ~186 s → ~25 s → 3.6 s on Metal across the two
changes), while the `feature/cl-profile-perf` kernel-series rebuild (`convDeltaSmall`
172 s → 1.15 s, ~150×) attacked `kernelSeries`. The ~150× convolution figure therefore is *not* a
prediction for the fine-tuning backward pass. Nobody has re-measured the transformer backward pass against either.
The verdict that gates the entire proof-of-value trajectory is very likely stale — and stale in the
pessimistic direction.

Separately confirmed while investigating: frozen-weight gradient pruning already exists at the
layer level (`DefaultGradientPropagation` only calls `delta()` on the trainable weight list; LoRA
excludes base weights), so the remaining backward-compile cost is the *expression-tree size* of
differentiating the trainable weights through the deep forward graph — precisely the territory
both the `ExplicitExpressionMatrix` cache work and the kernel-series work attack. That reinforces
the premise that the old numbers should move.

#### Why this task

It recovers the truth about the platform's most strategically important performance number, using
the measurement harness that already exists (`AggressiveFineTuningTest`), against optimizations
already on `master`. It is deliberately scoped to measure → profile (via `ar-profile-analyzer`) →
re-baseline `FINE_TUNE_FAIL.md` → name the next lever, with an *only-if-clean* conditional step to
land one small optimization. Crucially it stays clear of the in-flight `feature/lora-gradients`
core-autodiff work (sparse Jacobians, `Sum` reordering, memoization gating) — avoiding the
add/revert collision that has bitten this area before.

#### Balance across categories

Foundations are in good shape and under continuous ownership. The right move now is not to open
another deep front but to *cash in* the compile-time win the platform already has — establishing
what training is now possible so the next cycle can plan against reality.

#### What comes next

1. This plan executes: first the additive `AggressiveFineTuningTest` harness change (individually
   selectable, bounded scaling and profiled methods, each excluded from the CI pipeline profile;
   separate cold/warm columns; profile written under the module's `results/`), then current scaling
   numbers, a fresh embed=64 backward-compile
   profile, a rewritten `FINE_TUNE_FAIL.md`, and a named next lever. Open risk: the profiled run
   may still hit the `IndexProjectionProducerComputation.delta()` scope error recorded in
   `FINE_TUNE_FAIL.md`, in which case the profile-driven deliverables fall back to wall-clock data
   and a partial profile (the new profiled method saves the profile even when the run fails).
   Before execution, the approver fixes the feasibility threshold (target configuration and
   compile budget); without one, the plan reports timings and growth rates with no feasibility label.
2. If the verdict flips toward feasible: scope the **minimal end-to-end self-hosted training run** —
   a tiny model trained on the platform's own docs/source via `ModelOptimizer` — the first concrete
   step toward software that studies itself. (This was item 4 of the prior cycle's "what next".)
3. If a clean compile-time lever was identified but deferred, promote it to its own performance plan.
4. Still open from prior cycles: the ONNX-pressure documentation note; the `io.almostrealism.collect`
   package split as a code-quality candidate.

### 2026-09-26 — Native Runtime Lifecycle Documentation

**Category:** Documentation
**Branch:** `project/plan-20260926-172935`
**Plan:** [`PLAN-20260926-native-runtime-lifecycle-docs.md`](PLAN-20260926-native-runtime-lifecycle-docs.md)

#### Context: the log was restarted

The previous log was deleted on 2026-04-06 together with a batch of completed or obsolete
plans. Between April and now, planning happened through many focused plan documents in
`docs/plans/` and continuous QA agents (`qa/docs-*`, `qa/defect-*`, `qa/coverage-*`,
`qa/performance-*`, `qa/consolidate-*`) that merge small fixes daily. This entry restarts the
narrative. What carried over from the old log, condensed:

- **March 2026:** the compilation-pipeline internals docs (graph → process tree, process
  optimization, backend compilation and dispatch) and the ML inference pipeline doc were planned
  and have since landed in `docs/internals/`. TODO/FIXME work pivoted from mass triage to fixing
  items individually. The module layer review (`docs/MODULE_REVIEW.md`) reorganized modules and
  deferred structural debt (split `io.almostrealism.collect` package, JobOutput migration).
- **April–September 2026:** heavy investment in agent infrastructure (FlowTree, ar-manager,
  enforcement hooks, test-integrity checks), kernel correctness investigations (see
  `docs/journals/`), audio/PDSL work, and Stable Audio 3 / Qwen performance tracks.

#### What I investigated

- `docs/internals/` covers the compile side of the pipeline well.
- The hardware README (~1150 lines) covers memory *usage patterns* thoroughly.
- `docs/plans/KERNEL_AND_MEMORY_DOCUMENTATION_GAPS.md` (May 2026) lists five gaps exposed when a
  native-crash investigation in a client application produced three mechanically impossible
  hypotheses. I checked each against source today: none is closed. `NativeInstructionSet` still
  defers the thread-safety question; `NativeCompiler` has no library-lifetime Javadoc (and the
  default library directory, `SystemUtils.getExtensionsPath()`, has no visible delete-on-start —
  the plan's claim that libraries are "destroyed every JVM start" is itself unverified);
  the `MEMORY_SCALE`-derived limit is enforced in `MetalMemoryProvider`, `CLMemoryProvider` and
  `NativeMemoryProvider` (the two GPU providers throw a "Memory Max Reached" `HardwareException`,
  `NativeMemoryProvider` a "Memory max reached" one), but no doc says
  so; there is no internals page for any of it.
- Recent QA documentation passes fix individual stale claims (line-number citations, a
  non-existent `PackedCollection.load(File)`), but they do not create missing conceptual docs.

#### Why this task

Documentation is the first category, and this is its most consequential remaining gap: the
*back half* of the execution story — what happens to a compiled kernel and the memory it reads
after compilation. It is the part people and agents get wrong under pressure (crash triage),
and the part any future kernel-caching or compile-time optimization must reason about. The
plan requires every claim to be verified against source or an empirical run, and to record —
not fix — any defect found, keeping the task a pure documentation task.

#### Balance across categories

Documentation of the conceptual core is now nearly complete; after this task the internals
corpus tells the whole story from Producer to released memory. Code quality is being handled
continuously by QA agents. The next planning cycles should lean toward **performance**
(compile-time and kernel reuse, where an accurate lifecycle doc is a prerequisite) and then
**proof-of-value** — in particular a small, self-hosted language-model training run, which is
the most direct step toward the self-understanding goal.

#### What comes next

1. This plan is executed: the native runtime lifecycle internals page and its Javadoc have landed,
   and `KERNEL_AND_MEMORY_DOCUMENTATION_GAPS.md` is trimmed to its one remaining, out-of-scope entry.
   Follow up on that entry — the ONNX-induced-pressure gap — as a separate documentation task in
   `extern/ml-onnx/`, then delete the gap document.
2. Revisit the deferred structural debt from `docs/MODULE_REVIEW.md` (split
   `io.almostrealism.collect` package) as a code-quality candidate.
3. Performance: compile-time reduction building on `CONVOLUTION_COMPILE_TIME.md`.
4. Proof of value: scope a minimal end-to-end training run of a tiny transformer on platform
   documentation using `ModelOptimizer`.
