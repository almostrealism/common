# Train the K/V projections of cross-attention, resampling, and differential attention

**Category:** Code Quality (correctness — a silent zero-gradient defect in the attention backward pass)

**Branch:** `project/plan-20261021-012939`

**Estimated Complexity:** Medium

---

## Motivation

A learning platform is only as trustworthy as its gradients. The 2026-10-18 cycle treated a
*nondeterministically* wrong weight gradient as the most consequential kind of foundation defect and
spent a whole cycle on it. This plan addresses a defect of the same class, but *deterministic* and
*total*: three attention mechanisms silently deliver **exactly zero** gradient to their key and value
projection weights, so those weights never train.

The self-attention path was already repaired. When the first self-hosted language model was trained
(`PLAN-20260930-self-hosted-tiny-lm.md`), `SequenceAttentionGradientTest` proved that the buffered
K/V path left the key slice of the fused projection with an analytic gradient of exactly `0` while the
numeric gradient was large, and `sequenceAttention` was rewritten to route Q/K/V through a single PDSL
asset (`SEQUENCE_ATTENTION_ASSET`) so gradients flow to all three slices. The fix stopped there. Its
siblings — `sequenceCrossAttention`, `TransformerResamplingFeatures.resamplingLayerBlock`, and
`DifferentialAttentionFeatures` — still buffer K and V through fixed tensors and read them back as
constants, so they keep the original defect. Each carries the same pattern:

```java
// SequenceAttentionFeatures.sequenceCrossAttention
// TODO(review): K/V buffering drops gradients to toKvWeight; feed K and V to the products
//               as branch operands, as sequence_attention.pdsl does
PackedCollection kTensor = new PackedCollection(shape(batchSize, heads, contextSeqLen, dimHead));
PackedCollection vTensor = new PackedCollection(shape(batchSize, heads, contextSeqLen, dimHead));
k.andThen(into(kTensor));
v.andThen(into(vTensor));
crossAttention.add(scaledDotProductAttention(
        batchSize, querySeqLen, contextSeqLen, heads, dimHead, kTensor, vTensor, attentionScores));
```

The Javadoc of the `PackedCollection`-valued `scaledDotProductAttention` is explicit about the
consequence: *"The keys and values enter the computation as constants, so no gradient flows to
whatever produced them."*

### Why this matters to the vision

Cross-attention is how a model attends to *conditioning* — a text prompt, a context sequence, an
earlier modality. `sequenceCrossAttention` is the conditioning mechanism of the `DiffusionTransformer`
family, and `TransformerResamplingFeatures` is the windowed/chunked resampling stack the audio
diffusion track (`STABLE_AUDIO_3.md`, `METAL_RESAMPLING_NAN_PLAN.md`) builds on. With K/V gradients
dropped, a conditional diffusion model trained on this platform **cannot learn how to read its own
conditioning** — the query projection adapts, but the key/value projections that decide *what in the
context is relevant* stay frozen at their initialization. That is a silent cap on every conditional
generation workload the platform hopes to demonstrate, and it is invisible: training runs, the loss
falls (the rest of the network compensates), and nothing reports that a whole projection is dead.

Fixing it makes the backward pass honest across the entire attention family, not just the one path
the language model happened to exercise first — exactly the "fix the general mechanism, not the one
shape" standard the gradient-correctness cycle set.

## Scope

Concrete, in priority order. The committed deliverable is item 1; items 2–3 are addressed in the same
cycle, with an explicit, honest fallback when the shared framework limitation blocks a clean fix.

1. **`sequenceCrossAttention` (the clean case, committed deliverable).** Here K feeds only the score
   product and V feeds only the context product — each branch output feeds exactly one consumer, so
   the proven `compose(...)`-based wiring applies directly with no framework change. Replace the
   `into(kTensor)`/`into(vTensor)` buffering with a path that feeds K and V to the attention products
   as graph operands (a `Block`-valued `scaledDotProductAttention` overload wired via `compose`, the
   same mechanism `sequenceAttention` uses), so gradients reach `toKvWeight`. Remove the
   `TODO(review)`.

2. **`TransformerResamplingFeatures.resamplingLayerBlock`.** This site buffers `k1`, `v`, `q2`, `k2`
   and — critically — reads `cp(vTensor)` into **two** attention maps (`attn1` and `attn2`). That is
   the known "a branch's output can feed exactly one product" limitation: `compose(...)` replaces the
   branch's forward receptor, so a single V branch cannot currently fan out to two products with
   gradient accumulation. Diagnose precisely which of the buffered operands feed one consumer (fixable
   now, as in item 1) versus the V-fan-out that needs a shared-branch enhancement. Fix the
   one-consumer operands; for the V fan-out, either implement a minimal branch-fan-out with correct
   gradient accumulation if it is a narrow, well-contained change, or record it as a precise,
   evidenced follow-up with a failing (`@Disabled`-free) characterization captured in the gradient
   test as a documented known limit.

3. **`DifferentialAttentionFeatures`.** The differential *self*-attention variant was already fixed via
   the PDSL asset; confirm this from source and a per-slice test. Any remaining differential path that
   still buffers V into two maps shares item 2's fan-out limitation and is handled the same way:
   fix what feeds one consumer, scope the fan-out honestly.

4. **Tests.** Add per-slice finite-difference gradient tests modeled on `SequenceAttentionGradientTest`
   (central differences; scalar loss `L = sum(output * G)`; `gradientRecorder` captures the analytic
   gradient instead of applying it; compared per Q/K/V slice). At minimum a
   `SequenceCrossAttentionGradientTest` asserting a non-zero, finite-difference-correct gradient on the
   K/V slice of `toKvWeight`. Where item 2/3 are fixed, extend coverage to those weights; where a
   fan-out is deferred, the test documents the known limit without weakening (no reduced dimensions,
   no inflated tolerance, no skip-gating).

5. **Documentation.** Update the "Gradient wiring in sequence attention" section of
   `docs/internals/training-a-language-model.md` to record which attention paths now train their K/V
   projections and which (if any) remain limited by branch fan-out, so the internals corpus continues
   to track reality.

## Approach

- **Follow the proven template, do not invent.** `sequenceAttention`'s repair and the single-token
  `compose("context", v, ...)` path in `AttentionFeatures` are the two existing patterns for feeding
  computed K/V as graph operands. Reuse them; add a `Block`-valued `scaledDotProductAttention` overload
  only if one does not already fit, and place it on the feature interface that owns the concept (it is
  not cross-attention-specific).
- **Gate correctness by finite differences, not by "it looks trained."** Every weight claimed fixed is
  proven against a central finite-difference estimate per slice, at the FP32 noise floor, exactly as
  `SequenceAttentionGradientTest` does. A fix is complete only when the previously-zero slice matches.
- **One mechanism, every site.** Prefer a single shared wiring change over three copies. If the clean
  cases (item 1, and the one-consumer operands of items 2–3) can share one `Block`-valued attention
  entry point, use it; do not duplicate the `compose` plumbing per site.
- **Honest scope on the fan-out.** The V-feeds-two-products limitation is a real framework constraint
  (`SequentialBlock` receptor replacement). If enabling a branch to fan out to two products with
  correct gradient accumulation is narrow and well-contained, do it; if it reaches into the layer/
  routing core, stop at a precise evidenced finding and a follow-up plan rather than a risky broad
  change. This is the discipline that kept the matmul-VJP cycle out of the `convDeltaMedium`
  add/revert loop.
- **No weakening to go green.** This sits on correctness tests. Reducing dimensions, inflating
  tolerances, raising `@TestDepth`, or converting a failing path to a skip is out of bounds and fails
  `test-integrity-check`.

## Success Criteria

- `sequenceCrossAttention` delivers a finite-difference-correct, non-zero gradient to the K and V rows
  of `toKvWeight`; a new `SequenceCrossAttentionGradientTest` asserts it (previously the K/V slice
  would be exactly zero).
- The `TODO(review)` at the `sequenceCrossAttention` buffering site is removed, not merely annotated.
- The resampling and differential paths either train their K/V projections (verified per slice) or
  have their remaining limitation recorded with an evidenced characterization and a follow-up, with no
  test weakened to hide it.
- `docs/internals/training-a-language-model.md` accurately states which attention paths train K/V and
  which are limited.
- `mcp__ar-build-validator__start_validation` is clean (checkstyle, code_policy, test_timeouts,
  duplicate_code); the new and existing `engine/ml` attention gradient tests pass
  (`SequenceAttentionGradientTest`, the new cross-attention test, and any resampling/differential
  test touched), run as a bounded selection.

## Dependencies

None. The fix template (`sequenceAttention` via the PDSL asset, `compose`-wired K/V) and the test
template (`SequenceAttentionGradientTest`) both already exist on `master`. No new Maven module, no
pom.xml change, no dependency addition.

## What comes after

- If the branch fan-out (V → two products with gradient accumulation) is deferred, it becomes the next
  correctness item and unblocks full training of the resampling and differential stacks.
- Proof of value then resumes on a now-trustworthy conditional attention backward pass: sampling
  quality for the self-hosted LM (expose top-p through the sliding-window generator — `sampleToken`
  already takes a top-p argument the generator hardcodes to `1.0`), a single-position KV-cache decode,
  and conditional diffusion training that can actually learn its conditioning.
