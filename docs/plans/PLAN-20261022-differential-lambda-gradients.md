# Train the learned lambda of differential attention

**Category:** Code Quality (correctness — a silent zero-gradient defect in the attention backward pass)

**Branch:** `project/plan-20261022-134809`

**Estimated Complexity:** Medium (bounded by finite-difference tests; may touch `domain/graph` if the fix
is in the scale/update path rather than in `engine/ml` alone — see "Open questions")

---

## Motivation

A learning platform is only as trustworthy as its gradients. The last three correctness cycles made
that the standing rule: the 2026-10-18 cycle fixed a *nondeterministically* wrong reference gradient,
and the 2026-10-21 cycle (PR #697) fixed a *deterministic, total* zero-gradient defect in
`sequenceCrossAttention`, wiring K and V through the graph as branch operands instead of buffering them
through fixed tensors and reading them back as constants. That cycle set an explicit standard — "make
the attention backward pass honest across the whole family, not just the one path the language model
exercised first" — and it closed with a named list of what it deferred. The **learned differential
lambda** is the first item on that list.

Differential attention (the *Differential Transformer*) computes two softmax attention maps over one
sequence and subtracts the second from the first in proportion to a **learned per-head lambda**, so the
attention both maps pay to irrelevant positions cancels:

```
out = W_o · merge_heads( map1·V − λ ⊙ map2·V )
```

`λ` is the defining mechanism of the architecture: it is *how much* of the second map to subtract, and
it is meant to be learned, per head, from a re-parameterization. `DifferentialAttentionFeatures`
computes it exactly as the paper specifies (`differentialLambda`):

```java
// DifferentialAttentionFeatures.differentialLambda
// λ = exp(λ_q1 · λ_k1) − exp(λ_q2 · λ_k2) + λ_init   (dot products over the trailing dim)
```

from four learned vectors `λ_q1, λ_k1, λ_q2, λ_k2` (each `[heads, lambdaDim]`). The three
`differential_attention*` layers of `/pdsl/differential_attention.pdsl` apply the result with
`scale(lambda, 1)`, and the file documents the consequence plainly:

> "It is applied with `scale(lambda, 1)`, which reads lambda as a constant factor; when lambda is a
> learned re-parameterization its parameters therefore receive no gradient (a `TODO(review)` marks the
> stage in each of the three layers)."

So the four lambda vectors are **frozen at their initialization**. Training runs, the loss falls (the
projections and the rest of the network compensate), and nothing reports that the one parameter that
makes differential attention *differential* never moves. This is the same class of silent defect the
last two cycles treated as top priority — the only difference is the operand: a per-head scalar
re-parameterization rather than a K/V branch.

### Why this matters to the vision

`differential_attention_qk_layernorm` is, by the asset's own documentation, "the normalization of the
Stable Audio diffusion transformer's attention" — differential attention is on the audio-diffusion
proof-of-value track (`STABLE_AUDIO_3.md`). Today no code on `master` *trains* it (it is exercised only
by `DifferentialAttentionTest`, `DifferentialAttentionAssetTest` and `CausalSequenceAttentionTest`), so
the defect has no current victim; it becomes one the moment anyone fine-tunes a differential-attention
model. Fixing it now is cheaper and safer than discovering a dead lambda mid-training-run later, and it
is the honest completion of the "whole attention family" arc the cross-attention cycle began: after
this, every learnable operand of every attention variant the platform ships receives its gradient.

## Scope

Concrete, in priority order. The committed deliverable is item 1.

1. **Make the learned lambda train (committed deliverable).** The four vectors that
   `differentialLambda` combines must receive a finite-difference-correct, non-zero gradient when the
   differential attention block's backward pass runs, for all three normalization families
   (`differential_attention`, `_qk_layernorm`, `_qk_rmsnorm`). The forward value of `λ` and of the whole
   block must be unchanged. The first step is a diagnosis (see "Open questions" 1) that establishes
   *where* the gradient is lost, because the forward multiply already reads `λ` as a graph operand —
   `LayerFeatures.scale(shape, axis, factors)` builds `c(input).multiply(broadcast(shape, axis,
   factors))` — so the defect is not obviously in the arithmetic. The wiring follows from that answer.

2. **Remove the three `TODO(review)` markers** at the `scale(lambda, 1)` stage of the three layers in
   `/pdsl/differential_attention.pdsl`, and the "receives no gradient" paragraph in its header — *only*
   once item 1 is proven, not merely annotated. Update the "lambda holds one weight per head" paragraph
   to state that the re-parameterization now trains.

3. **Tests.** Add a per-vector finite-difference gradient test modeled on
   `DifferentialAttentionAssetTest.everySectionOfTheProjectionReceivesItsGradient` (central differences;
   scalar loss `L = sum(output · G)`; `ModelTestFeatures.gradientRecorder` captures the analytic
   gradient instead of applying it; compared per weight). It must assert a non-zero,
   finite-difference-correct gradient on each of `λ_q1, λ_k1, λ_q2, λ_k2`, for at least the no-norm
   family, and on `master` those gradients are absent or exactly zero. Where a normalization family is
   not covered, say so; do not make a test pass by reducing dimensions, inflating tolerance or
   skip-gating.

4. **Documentation.** Update the "Gradient wiring in sequence attention" section of
   `docs/internals/training-a-language-model.md`, which currently describes the lambda-weighted,
   negated second map as the mechanism by which all five sections of the fused projection receive
   gradients but does not note that the lambda re-parameterization itself is frozen, so it states
   that the lambda re-parameterization now trains (or records precisely what remains, if item 1
   stops at a finding). The section names only `TransformerResamplingFeatures` as a remaining
   buffered/constant site today, so the lambda's frozen status should be added, not merely amended.

**Out of scope:** `TransformerResamplingFeatures.resamplingLayerBlock` (the other deferred correctness
item — K1/V/Q2/K2 still buffered; preventive, gated on a possible SAME-dimension compile blow-up per the
2026-10-21 plan's "Open question 2"). It is a different site with a different risk and belongs in its own
cycle. This plan is the lambda only.

## Approach

- **Diagnose before wiring.** Unlike the K/V defect — where the fix was visibly "stop calling `into(...)`
  and feed the branch" — here the forward multiply already takes `λ` as an operand, so the first task is
  to find where training is lost. Build a minimal model whose only trainable values are a `scale(factors,
  axis)` layer's `factors` (a producer over a small `PackedCollection`) and check by central finite
  differences whether that collection receives a gradient at all, and whether `ModelOptimizer` /
  `setParameterUpdate` reaches it. Two outcomes:
  - *The multiply carries the gradient but nothing updates the vectors.* Then the defect is that
    `differentialLambda` hands the block an **opaque producer** (`cp(λ_q1)…`) that is not a trainable
    layer, so no `ParameterUpdate` is ever wired to its collections — structurally the same gap that
    `BranchBlock`-implements-`Learning` closed for the K/V context branch in PR #697. The fix is to make
    the lambda re-parameterization a trainable sub-block of the differential attention block (weights
    `λ_q1…λ_k2`, forward `exp(q1·k1) − exp(q2·k2) + λ_init`), wired so its backward cell receives the
    gradient the `scale` stage pushes. This is an `engine/ml` change and likely an API change to
    `differentialSequenceAttention` (see "Open questions" 2).
  - *`scale`/`broadcast` severs the gradient.* Then the fix is in `LayerFeatures.scale(shape, axis,
    factors)` (`domain/graph`) or in how the PDSL binds `lambda`, and it is shared by every `scale(…, axis)`
    caller (the padding-value mask among them) — so a `domain/graph` fix must keep those callers correct.
  The diagnosis decides which, and the plan does not presume the answer.
- **Follow the proven template, do not invent.** PR #697 made an operand trainable by giving its producer
  a backward cell that `setParameterUpdate` reaches (`BranchBlock implements Learning`; `Model`
  propagates `ParameterUpdate` to auxiliary inputs that are `Learning`). If item 1 needs the lambda
  re-parameterization to become a trainable block, place that capability where the next consumer will look
  — a trainable scalar re-parameterization is not differential-attention-specific, so prefer a general
  home (a `LayerFeatures`/`graph` construct) over a private helper on `DifferentialAttentionFeatures`, and
  defend the placement in review.
- **Own the collaborators.** If the block must own the lambda parameters' update lifecycle, the honest
  contract is that the block builds the re-parameterization from the four vectors (or takes a lambda
  *block*), not that a caller passes an opaque `Producer` the block cannot train. Any change to
  `differentialSequenceAttention`'s signature must keep `λ = 0 ⇒ standard SDPA` and must not silently
  correct a caller — the caller declares the lambda parameters explicitly.
- **Gate correctness by finite differences, not by "it looks trained."** Every vector claimed fixed is
  proven against a central finite-difference estimate at the FP32 noise floor, exactly as
  `everySectionOfTheProjectionReceivesItsGradient` proves the five projection sections.
- **Keep forward parity.** `DifferentialAttentionAssetTest.assertMatches` (asset vs Java assembly vs host
  reference, `diff` within float rounding) and `DifferentialAttentionTest` define the forward behaviour;
  all must pass unchanged after the rewiring.
- **No weakening to go green.** This sits on correctness tests. Reducing dimensions, inflating tolerances,
  raising `@TestDepth`, or converting a failing path to a skip is out of bounds and fails
  `test-integrity-check`.

## Success Criteria

- Each of `λ_q1, λ_k1, λ_q2, λ_k2` receives a finite-difference-correct, non-zero gradient from a
  differential attention backward pass (at least the no-norm family), asserted by a new test modeled on
  `everySectionOfTheProjectionReceivesItsGradient`; on `master` those gradients are absent/zero.
- The three `TODO(review)` markers and the "receives no gradient" prose in
  `/pdsl/differential_attention.pdsl` are removed, not merely annotated.
- `DifferentialAttentionAssetTest` (`everySectionOfTheProjectionReceivesItsGradient`, `assertMatches` and
  the learned-lambda case), `DifferentialAttentionTest` and `CausalSequenceAttentionTest`'s differential
  path still pass; `λ = 0` still reduces to standard scaled-dot-product attention.
- If item 1 must stop at a finding (e.g. the fix proves to be a larger `domain/graph` change than this
  cycle can land safely), the remaining limitation is recorded as an evidenced follow-up task, with no
  test weakened to hide it.
- `docs/internals/training-a-language-model.md` accurately states whether the differential lambda trains.
- `mcp__ar-build-validator__start_validation` is clean (checkstyle, code_policy, test_timeouts,
  duplicate_code); the new and existing differential-attention tests pass, run as a bounded selection.

## Dependencies

No new Maven module, no `pom.xml` change, no dependency addition. The test template
(`everySectionOfTheProjectionReceivesItsGradient`, `ModelTestFeatures.gradientRecorder`), the trainability
template (PR #697: `BranchBlock implements Learning`, `Model` propagating `ParameterUpdate`), the PDSL
asset and `LayerFeatures.scale` all exist on `master`. Item 1 may require a change in `domain/graph`
(`LayerFeatures.scale` or a trainable-scalar construct) rather than in `engine/ml` alone; the diagnosis
decides.

## Open questions

1. **Where is the lambda gradient lost — in the arithmetic, or in the update path?** The forward applies
   `λ` as a graph operand (`multiply(input, broadcast(shape, axis, factors))`), so the arithmetic may
   already carry a gradient to `factors`; yet the asset documents the parameters as receiving none. The
   most likely reading is that `differentialLambda` returns an *opaque producer* over `PackedCollection`
   vectors that are never registered as trainable (no backward cell, no `ParameterUpdate`), exactly the
   structural gap PR #697 closed for the K/V context branch — but this is inferred from the code and must
   be **established by a minimal finite-difference experiment before any attention code changes**, as the
   cross-attention plan established its Open question 1. If instead `scale`/`broadcast` severs the
   gradient, the fix is in `domain/graph` and shared by every `scale(…, axis)` caller.

2. **Does the fix change `differentialSequenceAttention`'s contract?** If the lambda re-parameterization
   must become a trainable sub-block the block owns, the method can no longer take an opaque
   `Producer<PackedCollection> diffLambda` and still train it. Options: take the four vectors and build
   the re-parameterization inside the block; or take a lambda *block*. Either must preserve the existing
   overloads' behaviour, the `λ = 0 ⇒ standard SDPA` identity, and the validation that rejects a lambda of
   the wrong size (`lambdaIsRequired`, `lambdaOfAnotherSizeIsRejected`) — without silently adjusting a
   caller. This is a design question to settle (and defend in review) before writing the fix, not a
   licence to bypass the `Producer` interface.

3. **Is a general trainable-scalar-reparameterization construct warranted?** A learned scalar combined
   from vectors and broadcast over an axis is not specific to differential attention. If item 1 adds such
   a capability, it belongs at the highest rung where it still makes sense (a `graph`/`LayerFeatures`
   construct), not as a private helper — placement is a generality decision made before writing.

## What comes after

- The other deferred correctness item — `TransformerResamplingFeatures.resamplingLayerBlock`'s buffered
  K1/V/Q2/K2 — becomes the next correctness candidate, best taken in the cycle that first fine-tunes the
  SAME autoencoder (its `into(...)` leaves bound kernel size at full SAME dimensions, so a naive rewiring
  may reintroduce the compile blow-up `SAMEResamplingParityTest` guards against).
- Proof of value then resumes on a now fully-honest attention backward pass: sampling quality for the
  self-hosted LM (expose top-p through the sliding-window generator — `SlidingWindowAutoregressiveModel`
  calls `sampleToken` with a top-p of `1.0`), a single-position KV-cache decode, and conditional diffusion
  training that can actually learn both its conditioning (K/V, fixed in PR #697) and its differential
  cancellation (λ, this plan).

## Note on the machine

This runs on any backend. The differential-attention gradient tests use small dimensions and central
finite differences with no macOS/Metal or long-test gating (as `SequenceAttentionGradientTest` and
`everySectionOfTheProjectionReceivesItsGradient` already do), so no workstream settings file is written
for this branch.
