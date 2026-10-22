# Train the K/V projections of cross-attention and transformer resampling

**Category:** Code Quality (correctness — a silent zero-gradient defect in the attention backward pass)

**Branch:** `project/plan-20261021-012939`

**Estimated Complexity:** Medium for cross-attention; the resampling site may be larger (see "Open
questions")

---

## Motivation

A learning platform is only as trustworthy as its gradients. The 2026-10-18 cycle treated a
*nondeterministically* wrong weight gradient as the most consequential kind of foundation defect and
spent a whole cycle on it. This plan addresses a defect of the same class, but *deterministic* and
*total*: two attention paths silently deliver **exactly zero** gradient to their key and value
projection weights, so those weights never train.

The self-attention paths were already repaired. When the first self-hosted language model was trained
(`PLAN-20260930-self-hosted-tiny-lm.md`), `SequenceAttentionGradientTest` proved that the buffered
K/V path left the key slice of the fused projection with an analytic gradient of exactly `0` while the
numeric gradient was large, and `sequenceAttention` was rewritten to build a layer of the PDSL asset
`SEQUENCE_ATTENTION_ASSET` (`/pdsl/sequence_attention.pdsl`), whose `keys` and `values` branches are
the second operands of the `sdpa_scores` / `sdpa_context` products, so gradients flow to all three
slices. Differential self-attention was repaired the same way: `differentialSequenceAttention` builds
a layer of `/pdsl/differential_attention.pdsl`, and `DifferentialAttentionAssetTest`
(`everySectionOfTheProjectionReceivesItsGradient`) proves that all five sections of its fused
projection receive finite-difference-correct gradients. `docs/internals/training-a-language-model.md`
("Gradient wiring in sequence attention") already records both repairs.

Two sites still buffer through fixed tensors and read them back as constants, so they keep the
original defect:

- **`sequenceCrossAttention`** (in `SequenceAttentionFeatures`, the fully specified overload taking a
  `NormalizationType`):

  ```java
  // SequenceAttentionFeatures.sequenceCrossAttention
  // TODO(review): K/V buffering drops gradients to toKvWeight; feed K and V to the products as branch operands, as sequence_attention.pdsl does
  PackedCollection kTensor = new PackedCollection(shape(batchSize, heads, contextSeqLen, dimHead));
  PackedCollection vTensor = new PackedCollection(shape(batchSize, heads, contextSeqLen, dimHead));

  k.andThen(into(kTensor));
  v.andThen(into(vTensor));

  crossAttention.add(scaledDotProductAttention(
          batchSize, querySeqLen, contextSeqLen,
          heads, dimHead, kTensor, vTensor, attentionScores));
  ```

  The Javadoc of the `PackedCollection`-valued `scaledDotProductAttention` is explicit about the
  consequence: *"The keys and values enter the computation as constants, so no gradient flows to
  whatever produced them."* Both halves of `toKvWeight` (the K rows and the V rows), and the K
  normalization weights, therefore receive no gradient.

- **`TransformerResamplingFeatures.resamplingLayerBlock`**, which materializes `k1Tensor`, `vTensor`,
  `q2Tensor` and `map2Tensor` (the second attention map, computed on the `K2` branch from `cp(q2Tensor)`
  and `cp(vTensor)`). Every operand except the main-path `Q1` is read through `cp(...)`, so of the five
  sections of its fused `to_qkv` projection only `Q1` receives a gradient; `K1`, `V`, `Q2` and `K2` (the
  last through `map2Tensor`) receive none, nor do the shared query/key `dynamicTanh` normalization
  weights on those paths.

### Why this matters to the vision

Cross-attention is how a model attends to *conditioning* — a text prompt, a context sequence, an
earlier modality. `sequenceCrossAttention` is the conditioning mechanism of the `DiffusionTransformer`
family (reached through `transformerBlock` in `TransformerBlockFeatures` when `crossAttend` is set).
With K/V gradients dropped, a conditional diffusion model trained on this platform **cannot learn how
to read its own conditioning** — the query projection adapts, but the key/value projections that
decide *what in the context is relevant* stay frozen at their initialization. That is a silent cap on
every conditional generation workload the platform hopes to demonstrate, and it is invisible: training
runs, the loss falls (the rest of the network compensates), and nothing reports that a whole
projection is dead.

`TransformerResamplingFeatures` is the transformer-resampling autoencoder (SAME) of the audio
diffusion track (`STABLE_AUDIO_3.md`, `METAL_RESAMPLING_NAN_PLAN.md`). Today it is used only for
inference from a loaded checkpoint (`SAMEResamplingParityTest`); nothing on `master` trains it, so its
defect has no current victim — it becomes one the moment anyone fine-tunes the autoencoder.

## Scope

Concrete, in priority order. The committed deliverable is item 1.

1. **`sequenceCrossAttention` (committed deliverable).** Replace the `into(kTensor)`/`into(vTensor)`
   buffering with wiring that feeds K and V to the score and context products as graph operands, so
   gradients reach both halves of `toKvWeight` and the K normalization weights, and remove the
   `TODO(review)`. Unlike self-attention, the K and V here are computed on a branch of a *different*
   block — `contextInput`, which `DiffusionTransformer` registers with `Model.addInput(condEmbed)` and
   `AttentionTests.sequenceCrossAttentionCompare` runs as a separate `Model` — so the PDSL `branch`
   construct, which branches the calling layer's own input, does not apply as-is. The first step is to
   establish how the gradient of the attention products can reach that branch at all (see "Open
   questions" 1); the wiring follows from that answer.

2. **`TransformerResamplingFeatures.resamplingLayerBlock`.** Make the K1, V, Q2 and K2 sections train.
   The structure is the same as differential attention without a learned lambda
   (`map1·V − map2·V`, with `V` shared by both maps), and `/pdsl/differential_attention.pdsl` has
   already shown how that sharing is expressed with correct gradients: `accum_blocks` runs the two maps
   as sibling blocks over the same projected input, each map reads its own `values` branch of the
   shared `V` section, and the backward pass sums both contributions into that section. Follow that
   template rather than inventing a branch fan-out mechanism. The site differs from the differential
   asset in ways the implementer must account for: DyT (`dynamicTanh`) query/key normalization, the
   partial rotary embedding of `applyRotaryPositionEmbedding`, a chunk-count batch dimension
   (`numChunks`, while `sequenceAttentionArguments` and the PDSL-backed `scaledDotProductAttention`
   reject a batch other than 1), and the deliberate materialization that bounds kernel size at full SAME
   dimensions (see "Open questions" 2). If that last constraint cannot be met with gradient-carrying
   operands, stop at an evidenced finding and a follow-up rather than regress the parity test.

3. **Tests.** Add per-slice finite-difference gradient tests modeled on `SequenceAttentionGradientTest`
   and `DifferentialAttentionAssetTest` (central differences; scalar loss `L = sum(output * G)`;
   `ModelTestFeatures.gradientRecorder` captures the analytic gradient instead of applying it; compared
   per slice). At minimum a `SequenceCrossAttentionGradientTest` asserting a non-zero,
   finite-difference-correct gradient on the K half and the V half of `toKvWeight`, on `toQWeight` and
   `toOutWeight`, and on the K normalization weights. Where item 2 is fixed, cover each of the five
   `to_qkv` sections. Where it is deferred, record the gap in the follow-up, not in a test that is made
   to pass by reducing dimensions, inflating tolerance or skip-gating.

4. **Documentation.** Update the "Gradient wiring in sequence attention" section of
   `docs/internals/training-a-language-model.md`, whose last sentence currently names
   `sequenceCrossAttention` and `TransformerResamplingFeatures` as the remaining buffered paths, so it
   states which of them now train their K/V projections.

**Out of scope:** the learned differential lambda. `differential_attention.pdsl` applies it with
`scale(lambda, 1)`, which reads it as a constant, so the parameters of `differentialLambda` receive no
gradient (a `TODO(review)` marks the stage in each of its three layers). It is the same class of
defect, but a different operand (a per-head scalar producer, not a K/V branch); it should be filed as
its own item rather than folded into this one.

## Approach

- **Follow the proven templates, do not invent.** The repaired self-attention layers
  (`sequence_attention.pdsl`) and the differential layers (`differential_attention.pdsl`, including
  `accum_blocks` for an operand shared by two maps) are the existing patterns for feeding computed K/V
  as graph operands. There is currently no `Block`-valued `scaledDotProductAttention` overload — the
  self-attention repair replaced Java assembly with the PDSL asset instead of adding one. If item 1
  needs a new entry point, place it where the next consumer will look (alongside
  `scaledDotProductAttention` / `SDPA_ASSET`), not as a cross-attention-specific helper.
- **Gate correctness by finite differences, not by "it looks trained."** Every weight claimed fixed is
  proven against a central finite-difference estimate per slice, at the FP32 noise floor, exactly as
  `SequenceAttentionGradientTest` does. A fix is complete only when the previously-zero slice matches.
- **Keep forward parity.** `AttentionTests.sequenceCrossAttentionCompare` (reference data, `diff < 1e-5`)
  and `SAMEResamplingParityTest` define the forward behaviour of the two sites; both must pass
  unchanged after the rewiring.
- **No weakening to go green.** This sits on correctness tests. Reducing dimensions, inflating
  tolerances, raising `@TestDepth`, or converting a failing path to a skip is out of bounds and fails
  `test-integrity-check`.

## Success Criteria

- `sequenceCrossAttention` delivers a finite-difference-correct, non-zero gradient to the K rows and
  the V rows of `toKvWeight` and to the K normalization weights; a new
  `SequenceCrossAttentionGradientTest` asserts it (on `master` those gradients are exactly zero).
- The `TODO(review)` at the `sequenceCrossAttention` buffering site is removed, not merely annotated,
  and no `into(...)` buffer remains on the K/V path of that method.
- `AttentionTests.sequenceCrossAttentionCompare` and
  `DiffusionTransformerTests.forwardPassWithCrossAttention` still pass.
- `resamplingLayerBlock` either trains all five `to_qkv` sections (verified per section, with
  `SAMEResamplingParityTest` still passing) or its remaining limitation is recorded as an evidenced
  follow-up task, with no test weakened to hide it.
- `docs/internals/training-a-language-model.md` accurately states which attention paths train K/V and
  which are limited.
- `mcp__ar-build-validator__start_validation` is clean (checkstyle, code_policy, test_timeouts,
  duplicate_code); the new and existing attention gradient tests pass (`SequenceAttentionGradientTest`,
  `DifferentialAttentionAssetTest`, the new cross-attention test, and any resampling test touched), run
  as a bounded selection.

## Dependencies

No new Maven module, no pom.xml change, no dependency addition. The test templates
(`SequenceAttentionGradientTest`, `DifferentialAttentionAssetTest`) and the wiring templates
(`sequence_attention.pdsl`, `differential_attention.pdsl`, `sdpa.pdsl`) exist on `master`. Item 1
depends on the answer to "Open questions" 1, which may require a change in `domain/graph` (`Model` /
`CompiledModel`) rather than in `engine/ml` alone.

## Open questions

1. **Does the backward pass reach an auxiliary model input at all?** `sequenceCrossAttention` computes
   K and V on `contextInput.branch()`. In `DiffusionTransformer` that block is `condEmbed`, registered
   with `Model.addInput`; in `AttentionTests.sequenceCrossAttentionCompare` it belongs to a separate
   `Model` compiled and run before the main one. `Model.backward()` returns `blocks.getBackward()` —
   the backward cell of the main chain only — and `CompiledModel.compile` creates an `InputManager`
   per auxiliary input for the forward pass but its returned input gradient is for the first block
   only. The `into(...)` buffer is also what hands the context's K/V across to the main path, so
   removing it may break the forward wiring, not just the gradient. The earlier framing of this site as
   "the clean case — the proven `compose(...)` wiring applies with no framework change" is therefore not
   established.

   The question has two parts, and the code suggests they may have different answers:

   - *Into the context branch (`toKvWeight`, the K normalization weights).* `Block.branch()` appends a
     `BranchBlock` to `contextInput` and returns a `SequentialBlock` child whose backward cell
     `BranchBlock.append` wires to the branch's shared `aggregator`. The K/V sub-blocks are ordinary
     layers inside that child, and a layer computes and applies its weight gradient when its own
     backward cell is pushed. So if the score and context products push their gradient into the
     backward cells of the `k` and `v` sub-blocks, `toKvWeight` may train with no `Model` change. That
     is the part this plan needs.
   - *Beyond the branch (`condEmbed`'s own `condProjWeight1`/`condProjWeight2`, and anything else
     upstream).* `BranchBlock.getBackward()` sends the accumulated `aggregator` gradient upstream, and
     then clears it, only when the `BranchBlock`'s own backward cell is pushed. Nothing pushes it for an
     auxiliary input: there is no main-chain consumer after the branch point, and `Model.backward()`
     never reaches `condEmbed`. On a reading of the code, then, gradient pushed into the child
     accumulates in the aggregator and is never forwarded or cleared. That would leave the
     conditioning projection frozen, and the buffer would grow from step to step. This is inferred from
     the code, not yet observed. This plan's success criteria do not cover it either way.

   The implementer should first build a minimal two-input model (an auxiliary input with a dense layer
   on a `branch()` of it, feeding a product on the main path through `compose`) and check, by finite
   differences, both the branch dense layer's weight and a dense layer placed on the auxiliary input
   *before* the branch point. If the first fails, the real deliverable is "backward through auxiliary
   inputs", a `domain/graph` change that may warrant re-scoping this plan before any attention code
   changes. If only the second fails, item 1 can proceed. The upstream gap, and whether the
   never-cleared aggregator is a problem, should then be filed as its own `domain/graph` item, not
   folded into this one silently.
2. **Is the resampling materialization load-bearing?** The comments in `resamplingLayerBlock` state
   that the `into(...)` leaves exist to keep every compiled kernel bounded — fusing both differential
   maps and the shared `V` in one kernel "times out at full SAME dimensions" — and that the layout
   mirrors an older form of `sequenceAttention`. Replacing them with gradient-carrying branches may
   reintroduce that compile-time blow-up. `SAMEResamplingParityTest` is the arbiter. If it regresses,
   prefer the follow-up over a broad change to kernel isolation.
3. **Is fixing resampling worth doing now?** No code on `master` trains the SAME autoencoder; it is
   loaded from a checkpoint. Item 2 is preventive. The approver may prefer to defer it to the cycle
   that first fine-tunes the autoencoder, and keep this cycle to item 1.

## What comes after

- Anything deferred from items 1–2 becomes the next correctness item; so does the learned differential
  lambda (above).
- Proof of value then resumes on a now-trustworthy conditional attention backward pass: sampling
  quality for the self-hosted LM (expose top-p through the sliding-window generator —
  `SlidingWindowAutoregressiveModel` calls `sampleToken` with a top-p of `1.0`), a single-position
  KV-cache decode, and conditional diffusion training that can actually learn its conditioning.
