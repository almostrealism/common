# Stable Audio 3 — Phase 1 Component Plan

**Branch:** `feature/stable-audio-3-research`
**Companion to:** [`MILESTONE_0_FINDINGS.md`](MILESTONE_0_FINDINGS.md) and [`../STABLE_AUDIO_3.md`](../STABLE_AUDIO_3.md)
**Status:** Design — research/planning only. No source, `.proto`, or `pom.xml` changes are made by this task.
**Date:** 2026-06-13

---

## 0. Purpose and primary goal

Milestone 0 produced a code-grounded architecture record and a reuse-vs-build ledger
([`MILESTONE_0_FINDINGS.md`](MILESTONE_0_FINDINGS.md) §1–§2). This document turns that ledger into a
**Phase 1 component plan**.

**PRIMARY GOAL: reusable framework ML tooling.** Every building block below is specified first as a
*general AR capability* that earns its place in the framework on its own merits, and only second as a
Stable Audio 3 part. **SA3 Small (`small-music` / `small-sfx`) fixed-length text-to-audio is the
integration target that PROVES each block** — it is the acceptance harness, not the reason the block
exists. Wherever a capability can be expressed by **extending or generalizing an existing AR
component**, that is preferred over a bespoke SA3 class; and improving a shared component is preferred
over swapping in a one-off to get a quick result.

Phase 1 deliberately targets the **two SA3 Small DiTs** (433M, `embed 1024 / depth 20 / heads 16`,
`differential=false`) on the **SAME-S autoencoder** (266M), with **fixed-length** generation. Medium
(1.4B, differential attention), variable length, inpainting at the application level, and LoRA are out
of Phase-1 scope (§7).

### 0.1 Ledger drift corrected against current source (this session)

Every AR claim below was re-read against the working tree on this branch. Three framing items in the
task brief / ledger have drifted from the code and are corrected here; the implementation workstream
should treat **this section as authoritative** over the older wording.

| Claim as previously framed | Verified state of the code | Correction |
|---|---|---|
| "SoftNorm bottleneck implementing the existing **AutoEncoder bottleneck interface**." | `VAEBottleneck` (`engine/ml/src/main/java/org/almostrealism/ml/audio/VAEBottleneck.java:52`) is a **concrete class** implementing `LayerFeatures` — there is **no `Bottleneck` interface** anywhere in the tree (`grep 'interface Bottleneck'` → none). | There is nothing to "implement." Phase 1 **introduces a new general `Bottleneck` interface** (a new-general-primitive decision, §2 Block C1) that both the existing `VAEBottleneck` and the new `SoftNormBottleneck` satisfy. |
| "SAME-S … implementing the existing **AutoEncoder interface** so the downstream WaveData / decode paths are unchanged." | `OobleckAutoEncoder` (`OobleckAutoEncoder.java:85`) implements **`LayerFeatures`, not `AutoEncoder`**. The `AutoEncoder` contract (`AutoEncoder.java:32`) is satisfied at the studio layer by **`CompiledModelAutoEncoder`** (`studio/compose/src/main/java/org/almostrealism/studio/ml/CompiledModelAutoEncoder.java:59`), which wraps compiled encoder/decoder `CompiledModel`s. | SAME-S does **not** need its own `AutoEncoder` implementation. It builds encoder/decoder `Block`s, compiles them, and is wrapped by the **existing** `CompiledModelAutoEncoder` (REUSE, unchanged). That is what keeps `WaveData`/decode paths unchanged (§2 Block C3). |
| "Differential attention is required by SA3 medium **ONLY** — plan it as a Phase-2 extension." | True **for the DiT** (`small-*` configs `attn_kwargs.differential=false`; `medium=true`). But the **SAME autoencoder** uses differential attention in **both** SAME-S and SAME-L (`model.encoder.config.differential=true`, `dyt=true` — [`MILESTONE_0_FINDINGS.md`](MILESTONE_0_FINDINGS.md) §1.3). | Phase 1 builds **SAME-S**, so the **differential-attention and DyT primitives are Phase-1 scope after all** — driven by the autoencoder, not the DiT. The DiT smalls still avoid them. This *strengthens* the reuse thesis: the shared attention/norm primitives are built once in Phase 1 for the AE and the Phase-2 DiT-medium reuses them with no rework. |

No other ledger verdict changed. The prepend-only DiT conditioning constraint the brief asked us to
confirm is verified at `DiffusionTransformer.java:85-89` (class javadoc "uses **prepended
conditioning** rather than adaptive layer normalization (AdaLayerNorm)") and in code at
`prependConditioning(...)` (`DiffusionTransformer.java:380-392`), invoked from `addTransformerBlocks`
only when `globalCondDim > 0` (`:440-442`).

### 0.2 Verified AR extension points (reused as the seams for every block below)

| Seam | Evidence | What it enables |
|---|---|---|
| `DiffusionTransformer.getProjectionFactory()` (protected/overridable) | `DiffusionTransformer.java:641-643`; overridden by `LoRADiffusionTransformer.java:167-170` | Per-projection behaviour swap **without touching the block-assembly code** — the precedent for adding a conditioning *mode* by subclass/override rather than fork. |
| `DiffusionTransformer.addTransformerBlocks(...)` and `prependConditioning(...)` (protected) | `DiffusionTransformer.java:404-526`, `:380-392` | The conditioning and per-block wiring are already isolated, overridable methods — the place adaLN / memory-token / local-add wiring attaches. |
| `AttentionFeatures.transformerBlock(...)` (default, three overloads) | `engine/ml/src/main/java/org/almostrealism/ml/AttentionFeatures.java:1499, 1578, 1662` (the `ProjectionFactory` overload is the base) | The single shared transformer-block builder used by the DiT. The attention **variant** seam (standard vs differential) and the **modulation** seam (none vs adaLN) attach here, so every consumer inherits them. |
| `DiffusionTransformerFeatures.fourierFeatures(...)` / `timestepEmbedding(...)` (default) | `engine/ml/src/main/java/org/almostrealism/ml/audio/DiffusionTransformerFeatures.java:55-109` | Learned Fourier embedding of a scalar already exists — the `NumberConditioner` (§2 Block D1) generalizes exactly this, it does not reinvent it. |
| `AudioAttentionConditioner` + `ConditionerOutput` (interface) | `AudioAttentionConditioner.java:39-111` | The 3-tensor conditioning contract (cross-attn input, cross-attn mask, global cond) is general enough to carry the T5Gemma + duration wiring with **no interface change**. |
| `CompiledModelAutoEncoder` (concrete `AutoEncoder`) | `CompiledModelAutoEncoder.java:59-141` | Bridges any compiled encoder/decoder pair to `AutoEncoder` — the SAME-S adapter, free. |

---

## 1. How to read each building block

Each block is specified with the five required facets:

- **(a) General capability & reuse** — what the block is as framework tooling, and where it is useful
  beyond SA3.
- **(b) AR anchor & verdict** — the exact existing interface it implements or component it extends,
  confirmed against current source with `file:line`, plus an explicit **extend-in-place** vs
  **new-general-primitive** verdict and the justification.
- **(c) API surface** — key class/method signatures and configuration parameters, in AR's existing
  idioms (Producer/Block/Features-mixin/StateDictionary).
- **(d) Standalone unit-test strategy** — how the capability is proven **independently of SA3**
  (synthetic weights / reference vectors), per the project's "synthetic test" convention
  (`engine/ml/CLAUDE.md`).
- **(e) Dependency position** — where it sits in the build order (§3).

A consolidated dependency graph and the SA3-Small end-to-end proof follow in §3–§5.

---

## 2. Building blocks

The blocks are grouped: **A** shared attention/norm primitives (foundation), **B** DiT transformer
extensions, **C** autoencoder primitives, **D** conditioners, **E** the weight extractor. Within the
text the hardest items are flagged explicitly (and collected in §6).

---

### Block A — Shared attention & normalization primitives

These are pure framework primitives. They are pulled into Phase 1 by the **SAME-S autoencoder**
(which is differential + DyT) and are reused later by the DiT-medium and by any future transformer in
the framework. Building them first is the core of the "reusable tooling, proven by SA3" strategy.

#### A1. Differential attention as an attention *variant*

- **(a) General capability & reuse.** Differential attention (two softmax attention maps subtracted
  with a learned, depth-scaled λ) is a general transformer attention variant from the *Differential
  Transformer* line of work. As an AR primitive it is reusable by **any** attention consumer —
  LLMs in `engine/ml`, the DiT-medium, the SAME autoencoder. It is not SA3-specific.
- **(b) AR anchor & verdict.** Anchor: `AttentionFeatures.transformerBlock(...)` and the underlying
  self-attention construction (`AttentionFeatures.java:1662-1687+`), which today build a single
  `to_qkv` of width `dim*3` and one softmax. **Verdict: extend-in-place at the `AttentionFeatures`
  layer via a new *attention-variant* seam** (a small `enum`/config threaded through
  `transformerBlock`), **not** a new parallel block class. Justification: a forked
  `differentialTransformerBlock` would duplicate the entire 200-line block builder (RoPE, QK-norm,
  cross-attn, FFN, residuals) — exactly the duplication `engine/ml/CLAUDE.md` forbids. The only
  delta is the attention sub-computation; that is what must be parameterized.
- **(c) API surface.** Introduce a minimal config rather than a boolean (so future variants don't
  re-break the signature):

  ```java
  // engine/ml/src/main/java/org/almostrealism/ml/AttentionFeatures.java (extend in place)
  enum AttentionVariant { STANDARD, DIFFERENTIAL }

  // New overload of the self-attention helper used inside transformerBlock(...):
  // qkv width is dim*3 for STANDARD, dim*5 for DIFFERENTIAL; lambda is a learned per-head scalar set.
  default Block selfAttention(int batchSize, int dim, int seqLen, int heads,
                              AttentionVariant variant,
                              PackedCollection qkv, PackedCollection wo,
                              PackedCollection qNormWeight, PackedCollection qNormBias,
                              PackedCollection kNormWeight, PackedCollection kNormBias,
                              PackedCollection invFreq,
                              PackedCollection diffLambda,     // null when STANDARD
                              ProjectionFactory projectionFactory);
  ```
  The existing `transformerBlock(...)` overloads keep their signatures and default
  `variant = STANDARD`, `diffLambda = null`, so **every current caller is unaffected**. A new
  overload threads `AttentionVariant` for the differential consumers.
- **(d) Standalone unit-test strategy.** `DifferentialAttentionTest extends TestSuiteBase` (in
  `engine/ml`): build a one-block transformer with tiny synthetic dims (`dim=8, heads=2, seqLen=4`)
  and a `diffLambda` set to **0** — proving that *differential attention with λ=0 reduces exactly to
  standard attention* (assert element-wise equality against the `STANDARD` path within tight
  tolerance). A second case with λ≠0 asserts the documented subtraction of the two softmax maps on a
  hand-computable 2×2 example. No SA3 weights involved.
- **(e) Dependency position.** Foundation tier (Tier 0). Required by Block C2 (SAME-S) in Phase 1 and
  by the DiT-medium in Phase 2.

#### A2. DynamicTanh (DyT) as a general normalization option

- **(a) General capability & reuse.** DyT replaces LayerNorm/RMSNorm with `weight ⊙ tanh(α·x) + bias`
  (learned scalar α, per-channel affine), removing the reduction/normalization statistics. It is a
  drop-in norm usable by any layer in the framework, exactly like the existing `rmsnorm`/`norm`.
- **(b) AR anchor & verdict.** Anchor: the existing norm family in `AttentionFeatures` /
  `LayerFeatures` — `norm(weight, bias)` (LayerNorm) and `rmsnorm(...)` are already generalized norm
  builders (see `engine/ml/CLAUDE.md` "Normalization Layers"). **Verdict: extend-in-place — add
  `dynamicTanh(...)` alongside the existing norm builders** in the same mixin. Justification: DyT *is*
  a normalization layer; per the project's method-placement rule it belongs with its siblings, not in
  a new utility class. It composes from existing `CollectionFeatures` ops (`tanh`, `multiply`, `add`)
  — no new compute primitive needed.
- **(c) API surface.**
  ```java
  // engine/ml AttentionFeatures/LayerFeatures (extend in place), AR idiom:
  // y = weight ⊙ tanh(alpha * x) + bias  ; alpha is a learned scalar (shape [1]), weight/bias per channel.
  default Block dynamicTanh(PackedCollection alpha, PackedCollection weight, PackedCollection bias);
  ```
- **(d) Standalone unit-test strategy.** `DynamicTanhTest extends TestSuiteBase`: feed a known input
  vector, set `alpha=1, weight=1, bias=0`, assert output equals `tanh(input)` element-wise; then a
  second case with non-trivial α/weight/bias against a hand-computed reference. Pure, no SA3.
- **(e) Dependency position.** Foundation tier (Tier 0). Required by Block C2 (SAME-S) in Phase 1.

---

### Block B — Shared DiT transformer extensions

These extend the existing `DiffusionTransformer` so the released SA3 checkpoints load and run. The
smalls need **adaLN conditioning**, **64 memory/register tokens**, and the **local-add inpaint cond
path**; they do **not** need differential attention (Block A1 is exercised only by the AE in Phase 1).
For plain fixed-length text-to-audio the local-add path is present-but-inert, so it is built to keep
the released weights loadable, not because Phase-1 generation drives it.

#### B1. adaLN conditioning as a configurable mode (alongside prepend)

- **(a) General capability & reuse.** adaLN-Zero modulation (per-block scale/shift/gate produced from
  a global conditioning vector) is the dominant conditioning scheme for modern DiTs (image and audio).
  Adding it as a *mode* on AR's diffusion transformer makes the framework able to load the entire
  adaLN-DiT family, not just SA3.
- **(b) AR anchor & verdict.** Anchor: `DiffusionTransformer` conditioning path — the prepend-only
  constraint at `DiffusionTransformer.java:85-89` and `prependConditioning(...)` (`:380-392`), wired
  in `addTransformerBlocks(...)` (`:440-442`). adaLN modulation must also reach **inside** each block
  (scale/shift around the two sub-layers), which today happens in
  `AttentionFeatures.transformerBlock(...)`. **Verdict: extend-in-place, in two coordinated places** —
  (1) a `ConditioningMode { PREPEND, ADALN }` config on `DiffusionTransformer`; (2) an optional
  per-block modulation input on `transformerBlock(...)`. Justification: the class already documents
  prepend as *a* choice and isolates it behind overridable protected methods — adding a sibling mode
  is the intended evolution, and the `getProjectionFactory()` precedent (`:641-643`,
  `LoRADiffusionTransformer.java:167-170`) shows the established "configure behaviour via an
  overridable hook, don't fork the class" pattern. A `StableAudio3DiT extends DiffusionTransformer`
  subclass is **not** warranted — the modulation is general, not SA3-specific.
- **(c) API surface.**
  ```java
  // engine/ml DiffusionTransformer (extend in place): new optional config, default PREPEND.
  enum ConditioningMode { PREPEND, ADALN }

  // Constructor gains an overload carrying the mode (existing constructors delegate with PREPEND):
  DiffusionTransformer(int ioChannels, int embedDim, int depth, int numHeads, int patchSize,
                       int condTokenDim, int globalCondDim, String diffusionObjective,
                       ConditioningMode conditioningMode, StateDictionary stateDictionary);

  // Inside AttentionFeatures.transformerBlock(...): optional modulation block producing
  // 6*dim (scale/shift/gate for attn + ff) from the global cond; null => no modulation (prepend path).
  //   modulated_sublayer(x) = x + gate * sublayer(scale ⊙ norm(x) + shift)
  ```
  Weight keys consumed in ADALN mode (per [`MILESTONE_0_FINDINGS.md`](MILESTONE_0_FINDINGS.md) §1.2-2):
  per-block `...layers.N.to_scale_shift_gate.weight` (`6*dim`) and the global-cond embedder
  `model.model.to_global_embed.*` already loaded today (`DiffusionTransformer.java:286-296`).
- **(d) Standalone unit-test strategy.** `AdaLNConditioningTest extends TestSuiteBase`: a 1-block
  synthetic DiT with `to_scale_shift_gate` weights set so that **scale=1, shift=0, gate=1** — assert
  the ADALN forward output equals a plain pre-norm residual block (modulation is identity). A second
  case sets gate=0 and asserts the block becomes the identity function (output == input). Proves the
  modulation algebra with no SA3 weights.
- **(e) Dependency position.** Tier 1 (DiT extensions). Independent of Block A.

#### B2. Learned memory / register tokens

- **(a) General capability & reuse.** Learned "register" tokens prepended to a transformer sequence
  (here `num_memory_tokens=64`) are a general, model-agnostic capability (registers stabilize
  attention; common across modern ViT/DiT). Reusable by any AR transformer.
- **(b) AR anchor & verdict.** Anchor: `DiffusionTransformer.prependConditioning(...)`
  (`:380-392`) already prepends **one** computed token and the output stage strips prepended tokens
  generically (`:326-331`, `seqLen - audioSeqLen`). **Verdict: extend-in-place** — generalize the
  prepend/strip to prepend *N* tokens, where the conditioning token (prepend mode) and the K learned
  register tokens are concatenated. Justification: the prepend-and-strip machinery is already
  parameterized by `seqLen - audioSeqLen`; only the token source and count generalize. A new "learned
  constant tokens" `Block` is the one genuinely new, reusable sub-piece (a learned parameter broadcast
  to `[batch, K, dim]`).
- **(c) API surface.**
  ```java
  // engine/ml: a general learned-token block (reusable primitive), AR idiom:
  // emits a [batch, numTokens, dim] constant from a learned [numTokens, dim] parameter.
  default Block learnedTokens(int batchSize, int numTokens, int dim, PackedCollection tokenWeights);

  // DiffusionTransformer config gains: int numMemoryTokens (default 0); prepend/strip count becomes
  //   prepended = (globalCondDim>0 && PREPEND ? 1 : 0) + numMemoryTokens.
  ```
  Weight key: `model.model.transformer.memory_tokens` (shape `[64, dim]`) per
  [`MILESTONE_0_FINDINGS.md`](MILESTONE_0_FINDINGS.md) §1.2-2.
- **(d) Standalone unit-test strategy.** `LearnedTokensTest extends TestSuiteBase`: assert
  `learnedTokens` broadcasts a known `[K,dim]` parameter to `[batch,K,dim]` exactly; then a
  round-trip test that prepends K tokens and strips them, asserting the recovered tensor equals the
  original audio-token tensor (proves prepend/strip symmetry for N>1). No SA3 weights.
- **(e) Dependency position.** Tier 1. Independent of Block A; composes with B1.

#### B3. Local-additive conditioning path (inpaint cond) — present-but-inert in Phase 1

- **(a) General capability & reuse.** A per-block *additive* conditioning input (project an external
  `[B, C, L]` control tensor to `embedDim` and add it to the hidden state at every block) is a general
  conditioning mechanism (ControlNet-style local conditioning, inpaint masks, etc.). Reusable beyond
  SA3.
- **(b) AR anchor & verdict.** Anchor: `DiffusionModel.forward(x, t, crossAttnCond, globalCond)`
  (`DiffusionModel.java:47-49`) — a **fixed 4-input** contract — and the per-block assembly in
  `transformerBlock(...)`. Per [`MILESTONE_0_FINDINGS.md`](MILESTONE_0_FINDINGS.md) §1.2-5 / §2.2 the
  released `diffusion_cond_inpaint` checkpoints route inpainting through `local_add_cond`
  (`local_add_cond_dim=257`, projected `Linear(257→embedDim)`, **added at every block**), and
  **`io_channels` stays 256** — it is *not* a 5th forward arg and *not* channel concatenation.
  **Verdict: extend-in-place as an optional additive per-block input** — for Phase 1 it is wired only
  enough that the released weights **load and validate** (the `to_local_add_cond` projection key is
  consumed, not orphaned), with the local cond defaulting to the inert all-pass value for plain
  text-to-audio. Justification: `validateWeights()` (`DiffusionTransformer.java:707-711`) throws on
  any unused weight key, so the projection weight must be consumed even when generation does not use
  inpainting. Building the *application-level* inpaint API (mask construction, multi-region) is
  **Phase 3** — only the loadable additive seam is Phase-1 scope.
  - **Open verification item (carried from Milestone 0):** the all-ones-vs-all-zeros mask convention
    for plain T2A is still flagged unresolved in the findings (review memories `464955a7`,
    and the `TODO(review)` at `MILESTONE_0_FINDINGS.md:224-227`). Phase 1 must confirm, from
    `model.py:281-296` in the SA3 source, the inert local-cond value for full generation before
    relying on "present-but-inert." This is a correctness gate for B3, not a blocker for A/C/D.
- **(c) API surface.**
  ```java
  // DiffusionTransformer config gains: int localAddCondDim (default 0 => path absent).
  // When >0, a Linear(localAddCondDim -> embedDim) is applied to the local cond and its output is
  // added to each block's hidden state. Key: model.model.transformer.to_local_add_cond.weight.
  // Phase 1: local cond supplied as the inert value (per the verification item above); no public
  // inpaint API yet.
  ```
- **(d) Standalone unit-test strategy.** `LocalAddCondTest extends TestSuiteBase`: with the projection
  weight set to zero, assert the block output is identical to the no-local-cond path (additive
  identity); with a known projection and a known local cond, assert the per-block addition matches a
  hand-computed reference on tiny dims. Proves the seam without SA3 weights and without an inpaint API.
- **(e) Dependency position.** Tier 1. Independent of Block A.

---

### Block C — Autoencoder primitives (none exist in AR audio today)

AR's only audio AE is Oobleck (Conv1d + Snake + weight-norm), structurally unrelated to SAME. These
three sub-blocks are the genuinely new modeling work. **C2 is the highest-difficulty item in the
entire plan (§6).**

#### C1. `Bottleneck` interface + `SoftNormBottleneck`

- **(a) General capability & reuse.** A *bottleneck* is the general "encoder-output → latent" stage of
  any autoencoder (VAE mean-split, SoftNorm, FSQ, etc.). Today AR has exactly one, hardcoded as a
  class. Factoring a `Bottleneck` interface makes bottlenecks pluggable across all AR autoencoders.
  SoftNorm itself (a learned per-channel affine transform of the latent,
  `latent = (x * scalingFactor + bias) / runningStd`) is a general bottleneck reusable by any future
  AE. Despite the name, the inference-time transform is this affine rather than an L2 normalization
  onto a hypersphere — the "soft" normalization is induced during training by a KL-style
  regularization loss, leaving only the per-channel affine and an optional running-standard-deviation
  rescale at inference.
- **(b) AR anchor & verdict.** Anchor: `VAEBottleneck` (`VAEBottleneck.java:52`), a concrete class
  implementing `LayerFeatures` that exposes `getBottleneck() : Block` plus `getInputDim()/getOutputDim()`.
  **There is no `Bottleneck` interface** (verified: no match for `interface Bottleneck` in the tree).
  **Verdict: new-general-primitive (a small `Bottleneck` interface) + extend-in-place (retrofit
  `VAEBottleneck` to implement it).** Justification: this is the textbook "extract an interface from
  the one existing implementation when adding the second" move; it avoids a SoftNorm one-off and gives
  the AE assembly (C3) a single type to depend on. The interface is tiny and matches the shape of what
  `VAEBottleneck` already offers.
- **(c) API surface.**
  ```java
  // engine/ml/src/main/java/org/almostrealism/ml/audio/Bottleneck.java  (NEW general interface)
  public interface Bottleneck {
      Block bottleneck(int batchSize, int seqLength);   // encoder-output -> latent, as a Block
      int getInputDim();
      int getOutputDim();
  }
  // VAEBottleneck implements Bottleneck (retrofit; existing 128->64 mean-split unchanged).

  // SoftNormBottleneck implements Bottleneck (NEW):
  //   latent = (x * scalingFactor + bias) / runningStd   (dim 256 -> 256): a learned per-channel
  //   affine with an optional learned scalar standard-deviation divisor.
  public class SoftNormBottleneck implements Bottleneck, LayerFeatures { /* dim=256 */ }
  ```
- **(d) Standalone unit-test strategy.** `SoftNormBottleneckTest extends TestSuiteBase`: feed a known
  `[B,256,L]` tensor; assert each output element equals the per-channel affine reference
  `(x * scalingFactor + bias) / runningStd` (within tolerance), covering both the with- and
  without-`runningStd` configurations. A separate `BottleneckInterfaceTest` asserts the retrofitted
  `VAEBottleneck` still produces its original 128→64 mean output (regression guard). No SA3 weights.
- **(e) Dependency position.** Tier 1 (AE primitives). Independent of Block A.

#### C2. Transformer-resampling block / learned-resampling primitive — **HARDEST ITEM**

- **(a) General capability & reuse.** A *learned-resampling transformer block* changes sequence length
  by a `stride` using a learned `new_tokens` mechanism (instead of strided convolution), wrapped
  around RoPE self-attention with norm + GLU FFN. This is the novel core of SAME and a genuinely
  general primitive: any transformer-based encoder/decoder that must up/down-sample a sequence
  (audio AEs, latent up/down-samplers, hierarchical transformers) can reuse it. **This is the single
  most valuable reusable artifact in the plan** and also the riskiest.
- **(b) AR anchor & verdict.** Anchors that compose it: `AttentionFeatures.transformerBlock(...)`
  (`AttentionFeatures.java:1499+`) for the attention/FFN core, Block A1 (differential) and A2 (DyT)
  for SAME's variant/norm, RoPE/`invFreq` already in the block, and a `WNConv1d` 1×1 channel map
  (weight-norm conv — the extractor already computes weight-norm folding,
  `extract_stable_audio_autoencoder.py:180-192`, so it can be pre-folded offline). **Verdict:
  new-general-primitive (`transformerResamplingBlock`) assembled almost entirely from extended
  existing parts.** Justification: the learned-resampling (sequence-length-changing `new_tokens`)
  mechanism has **no analog** in AR — it must be built. But everything around it (attention, FFN, RoPE,
  norm) is reused, and the new piece is expressed as a general Block, not a SAME-specific class.
- **(c) API surface.**
  ```java
  // engine/ml (NEW general primitive), AR idiom — config object, not a long arg list:
  public class ResamplingConfig {
      int dim, heads, dimHeads;       // 64 dim-heads per SAME config
      int stride;                     // sequence-length change factor (down in encoder, up in decoder)
      AttentionVariant variant;       // DIFFERENTIAL for SAME (Block A1)
      NormType norm;                  // DYT for SAME (Block A2)
      double ffMult;                  // GLU/SwiGLU expansion
      AttentionWindow window;         // CHUNKED(chunkSize, midpointShift) [SAME-S] | SLIDING(window) [SAME-L]
  }
  // Builds a Block mapping [B, dim, Lin] -> [B, dim, Lin*stride or Lin/stride] using learned
  // new_tokens resampling + transformerBlock(...) under the hood. Weights via StateDictionary keys
  // model.encoder/decoder... (remapped by Block E).
  default Block transformerResamplingBlock(int batchSize, int seqLen, ResamplingConfig config,
                                           StateDictionary weights, String keyPrefix);
  ```
  Phase 1 implements `AttentionWindow.CHUNKED` (SAME-S). `SLIDING` (SAME-L) is stubbed for Phase 2;
  the window strategy is a config value so SLIDING lands without re-opening the block.
- **(d) Standalone unit-test strategy — how to validate the hardest item in isolation.**
  1. **Shape/identity contract first.** `TransformerResamplingShapeTest extends TestSuiteBase`: with
     synthetic weights, assert a down-block with `stride=16` maps `L → L/16` and an up-block maps
     `L → L*16`, for several `L`. With the resampling parameters set to an identity configuration
     (`stride=1`), assert the block degenerates to a plain `transformerBlock` output (reuses A1/A2
     identity cases). This proves the *plumbing* with zero numerical-reference risk.
  2. **Numerical parity against a captured reference.** The decisive isolation test mirrors the
     existing AE extractor's reference-vector pattern
     (`extract_stable_audio_autoencoder.py:64-72, 125-173` write `test_input.bin` / `*_output.bin`).
     Block E (extractor) is extended to **dump per-stage SAME activations** for a fixed seeded input
     from the reference PyTorch forward; the Java test loads `test_input.bin` and asserts the Java
     `transformerResamplingBlock` output matches `resampling_stage_k.bin` within tolerance — **one
     SAME stage at a time**, before the full AE is assembled. This localizes any discrepancy to a
     single block and is the only credible way to de-risk C2.
  3. **Attention-window equivalence.** A small case where `chunkSize >= seqLen` must equal full
     attention; assert `CHUNKED` with an oversized chunk equals the dense path (catches window bugs
     without needing the full model).
- **(e) Dependency position.** Tier 2 — depends on A1, A2, and (for keys) Block E. Gates C3.

#### C3. SAME-S assembly satisfying `AutoEncoder` (via the existing adapter)

- **(a) General capability & reuse.** Compose the C1/C2 general parts into the SAME-S encoder and
  decoder, then expose them through the framework's standard audio-AE contract so every downstream
  audio path (diffusion latent I/O, `WaveData` decode) treats SAME exactly like Oobleck.
- **(b) AR anchor & verdict.** Anchor: the `AutoEncoder` interface (`AutoEncoder.java:32-69`:
  `encode`/`decode` as `Producer<PackedCollection>`, plus `getSampleRate`/`getLatentSampleRate`/
  `getMaximumDuration`) and the existing concrete bridge `CompiledModelAutoEncoder`
  (`CompiledModelAutoEncoder.java:59-141`). **Verdict: REUSE the `AutoEncoder` interface and the
  `CompiledModelAutoEncoder` adapter unchanged; build only the SAME-S encoder/decoder `Block`s and a
  thin assembly.** Justification (corrects the brief): there is no need for a `StableAudio3AutoEncoder
  implements AutoEncoder` class — `OobleckAutoEncoder` itself never implements `AutoEncoder`
  (`OobleckAutoEncoder.java:85` is `LayerFeatures`); the contract is met by compiling encoder/decoder
  and wrapping with `CompiledModelAutoEncoder`. SAME-S follows the identical pattern, so the WaveData/
  decode paths are unchanged **by construction**. The patched pretransform (patch_size 256, folding
  256 samples → 512 channels) is a fixed reshape/`WNConv1d`, included in the encoder/decoder Blocks.
  - **Latent contract for the diffusion path:** SAME-S latent is **256-ch, downsampling_ratio 4096,
    ≈10.77 latent frames/s** ([`MILESTONE_0_FINDINGS.md`](MILESTONE_0_FINDINGS.md) §1.3) — so
    `CompiledModelAutoEncoder` is constructed with `latentSampleRate ≈ 10.77` and the DiT
    `ioChannels=256` (not the SA-Open 64). This is wiring, not new code.
- **(c) API surface.**
  ```java
  // engine/ml: SAME-S encoder/decoder as Blocks built from transformerResamplingBlock(...) + SoftNormBottleneck.
  //   SAMEEncoder(StateDictionary, batchSize, audioLen) -> Block ([B,2,L] -> [B,256,L/4096])
  //   SAMEDecoder(StateDictionary, batchSize, latentLen) -> Block ([B,256,Lz] -> [B,2,Lz*4096])
  // studio/compose assembly (REUSE existing adapter, no new AutoEncoder impl):
  AutoEncoder same = new CompiledModelAutoEncoder(
        sameEncoderModel.compile(false), sameDecoderModel.compile(false),
        44100.0, 44100.0 / 4096.0 /* ≈10.77 */, 120.0 /* SAME-S sample_size/sr */);
  ```
- **(d) Standalone unit-test strategy.** `SAMEAutoEncoderRoundTripTest extends TestSuiteBase`: with
  real SAME-S weights (extracted via Block E), encode→decode the reference `test_input.bin` and assert
  reconstruction error below a tolerance derived from the reference PyTorch encode→decode
  (`decoder_output.bin`-style reference, generated by the extended extractor). A shape test (no real
  weights) asserts `[B,2,L] → [B,256,L/4096] → [B,2,L]`. Independent of the DiT and of conditioning.
- **(e) Dependency position.** Tier 3 — depends on C1, C2, Block E. Consumed by the SA3-Small proof
  (§4) for decode.

---

### Block D — Conditioners

#### D1. `NumberConditioner` (learned scalar → embedding, dual-routed)

- **(a) General capability & reuse.** A learned embedding of a scalar value (here `seconds_total`,
  `min 0 / max 384`, expo Fourier features) usable as **both** a cross-attention token **and** a
  global-conditioning vector. This is a general conditioning primitive — any model that conditions on
  a continuous control (duration, tempo, loudness, CFG scale) can reuse it.
- **(b) AR anchor & verdict.** Anchor: `DiffusionTransformerFeatures.fourierFeatures(...)` and
  `timestepEmbedding(...)` (`DiffusionTransformerFeatures.java:55-109`) — AR **already** builds a
  learned Fourier-feature MLP of a scalar (for the timestep). **Verdict: new-general-primitive
  (`NumberConditioner`) that reuses `fourierFeatures(...)` internally**, rather than extending the
  timestep block (whose role is the diffusion timestep, not arbitrary conditioning). Justification:
  the math is the existing Fourier-MLP; the new, reusable concept is "a conditioner that emits one
  embedding routed to two consumers." Building it on `fourierFeatures` avoids duplicating the Fourier
  projection (the `engine/ml/CLAUDE.md` no-duplication rule).
- **(c) API surface.**
  ```java
  // engine/ml/src/main/java/org/almostrealism/ml/audio/ (NEW general primitive)
  public class NumberConditioner {
      NumberConditioner(double minVal, double maxVal, int outDim,
                        PackedCollection fourierWeights, PackedCollection proj0, PackedCollection proj2);
      // Produces a [batch, outDim] embedding from a scalar; the SAME embedding is supplied
      // to the conditioner output's cross-attention token slot AND the global-cond slot.
      // TODO(review): embed() should return Producer<PackedCollection>, not PackedCollection —
      // returning a raw collection performs an implicit evaluate() and violates the AR Producer pattern.
      // Align with DiffusionTransformerFeatures.fourierFeatures() return type before implementation.
      PackedCollection embed(double value);   // value clamped/normalized to [minVal, maxVal]
  }
  ```
- **(d) Standalone unit-test strategy.** `NumberConditionerTest extends TestSuiteBase`: assert
  monotonic, deterministic embeddings for a sweep of values; assert `value=minVal` and `value=maxVal`
  map to distinct, stable vectors; assert the same embedding object is what gets routed to both slots
  (identity of the dual-route). With `fourierWeights` set to a known matrix, compare against a
  hand-computed Fourier-MLP reference. No T5Gemma, no SA3 DiT.
- **(e) Dependency position.** Tier 1 — independent of A/B/C. Consumed by D2 and the proof (§4).

#### D2. T5Gemma text conditioner implementing `AudioAttentionConditioner`

- **(a) General capability & reuse.** A text-to-audio conditioner that emits the framework's standard
  3-tensor conditioning output from tokenized text plus a duration scalar. The **reusable wiring**
  (assemble cross-attn input + mask + global cond into `ConditionerOutput`) is separated from the
  **model-specific encoder export** (the T5Gemma graph), so the wiring is reusable for any future
  text encoder and only the encoder export changes per model.
- **(b) AR anchor & verdict.** Anchor: `AudioAttentionConditioner` (`AudioAttentionConditioner.java:39-55`)
  and its `ConditionerOutput` (`:63-111`, three tensors: cross-attn input, cross-attn mask, global
  cond). **Verdict: implement the existing interface (REUSE, no interface change); the encoder is a
  new export.** Justification: the 3-tensor contract already carries everything SA3 needs — the
  cross-attention input is `[T5Gemma prompt tokens (256, 768) ; duration token (1, 768)]`
  (`cross_attention_cond_ids=[prompt, seconds_total]`), and the global cond is the duration embedding
  (`global_cond_ids=[seconds_total]`). No richer interface is required for fixed-length T2A, so the
  Risk-3 interface-extension speculation in the parent doc does **not** trigger in Phase 1. The
  separation point: a reusable `runConditioners(...)` body that calls (i) a pluggable text-encoder
  step and (ii) the `NumberConditioner` (D1), versus the model-specific T5Gemma encoder export itself.
  - **Backend note.** The only existing concrete conditioner is `OnnxAudioConditioner` (ONNX, T5,
    128-token — [`MILESTONE_0_FINDINGS.md`](MILESTONE_0_FINDINGS.md) §1.1, §2). SA3 needs T5Gemma at
    **256 tokens, 768-dim**. The encoder export (ONNX of `t5gemma-b-b-ul2`, or native) is **model-
    specific new work**; the conditioner wiring around it is the reusable part. Choosing ONNX vs
    native for the T5Gemma encoder is an implementation decision deferred to Phase-1 build start; the
    `AudioAttentionConditioner` seam makes the choice invisible to downstream code.
- **(c) API surface.**
  ```java
  // engine/ml (native wiring) — implements the existing interface unchanged:
  public class T5GemmaConditioner implements AudioAttentionConditioner {
      // tokenIds: T5Gemma tokens (<=256); durationSeconds -> NumberConditioner (D1).
      ConditionerOutput runConditioners(long[] tokenIds, double durationSeconds);
      // crossAttentionInput: [1, 257, 768] = 256 prompt-token embeds ++ 1 duration token
      // crossAttentionMask : [1, 257]
      // globalCond         : [1, 768] duration embedding (adaLN global cond)
  }
  ```
- **(d) Standalone unit-test strategy.** Two layers. (1) `ConditionerWiringTest extends TestSuiteBase`
  with a **stub text encoder** (returns fixed token embeddings): assert the assembled
  `ConditionerOutput` has shapes `[1,257,768]` / `[1,257]` / `[1,768]`, that the duration token equals
  the `NumberConditioner` output, and that the mask zeroes padding positions — proving the reusable
  wiring with **no T5Gemma weights**. (2) `T5GemmaEncoderParityTest` (real weights, gated like other
  validation tests): compare the encoder export's token embeddings against a captured PyTorch
  reference for a fixed prompt. The wiring test is the SA3-independent proof; the parity test is the
  model-specific check.
- **(e) Dependency position.** Tier 2 — depends on D1. Consumed by the proof (§4).

---

### Block E — Generalized safetensors → StateDictionary extractor

- **(a) General capability & reuse.** A configurable Python extractor that reads a `.safetensors`
  checkpoint and writes AR `StateDictionary` protobuf shards, driven by a **key-remapping spec**
  (prefix strip / rename / shape checks / optional weight-norm folding / bf16→fp32). Reusable for any
  future safetensors model AR wants to load, not just SA3.
- **(b) AR anchor & verdict.** Anchor: `engine/ml/scripts/extract_stable_audio_autoencoder.py` — today
  it is **AE-only**, reads `.ckpt` via `torch.load` (`:74-78`), strips the fixed prefix
  `pretransform.model.` (`:86-89`), serializes to the same protobuf the DiT/AE loaders expect
  (`numpy_to_collection_data` / `write_protobuf_file`, `:35-61`), and folds weight-norm
  (`:180-192`). **Verdict: generalize-in-place into a config-driven extractor** (the protobuf
  serialization core is already general and stays; the *source format* and *key mapping* become
  configurable). Justification: the serialization is reusable as-is; only the front-end (safetensors
  `safe_open` instead of `torch.load`) and the remap table are new. Per the project's
  `extract_<model>_weights.py` convention (`engine/ml/CLAUDE.md`), the SA3 entry point is a thin config
  over the generalized core, not a fork. *(Note: writing the script is **build-phase** work; this task
  designs it only.)*
- **(c) API surface (design).**
  ```text
  Generalized core (reusable):
    load_safetensors(path) -> dict[str, ndarray]      # safe_open, bf16->fp32
    remap(state, rules)    -> dict[str, ndarray]      # rules: list of (match, rename, optional transform)
    write_state_dictionary(state, out_dir)            # existing protobuf shard writer (unchanged core)

  SA3 config (per-target remap rules), handling BOTH key paths called out by the brief:
    DiT  : keep  "model.model.transformer.*"          (the keys DiffusionTransformer expects)
           map   "model.model.{to_cond_embed,to_global_embed,to_timestep_embed,preprocess_conv,
                   postprocess_conv}.*", plus NEW adaLN/memory/local-add keys (Block B)
    AE   : strip "pretransform.model."                (embedded AE; same prefix SA-Open used)
           OR    bare keys for standalone SAME-S/SAME-L repos
    Cond : export T5Gemma encoder separately (model-specific; not a StateDictionary remap)

  Reference dumps (extends the existing reference-output feature, :64-173):
    optionally emit per-stage SAME activations for the C2 isolation tests (test_input.bin,
    resampling_stage_k.bin, encoder_output.bin, decoder_output.bin).
  ```
- **(d) Standalone unit-test strategy.** `python -m pytest` over the generalized core with a
  **synthetic** safetensors file: assert (i) round-trip of a known tensor through
  `remap`+`write_state_dictionary` reloads (via a tiny `StateDictionary` read) with identical values
  and shape; (ii) a remap rule renames/strips prefixes as specified; (iii) bf16 input is upcast to
  fp32. No SA3 weights needed for the core tests; a separate gated test runs the real SA3 remap and
  asserts the expected key set is produced.
- **(e) Dependency position.** Tier 0/1 tooling — produces the weights every model block loads. In
  practice built **early** (it gates C2's numerical tests and C3/B/D real-weight runs).

---

## 3. Dependency order

```
Tier 0  (foundation primitives, no AE/DiT deps)
  A1 Differential attention variant   ─┐
  A2 DynamicTanh norm                  ─┤
  E  Generalized extractor (core)      ─┘     (E also gates every real-weight test)

Tier 1  (build on Tier 0; mutually independent)
  B1 adaLN mode            B2 memory tokens     B3 local-add seam     (DiT extensions)
  C1 Bottleneck iface + SoftNormBottleneck                            (AE primitive)
  D1 NumberConditioner                                                (conditioner)

Tier 2
  C2 transformerResamplingBlock   (HARDEST; needs A1, A2, E-refs)
  D2 T5GemmaConditioner           (needs D1)

Tier 3
  C3 SAME-S assembly via CompiledModelAutoEncoder   (needs C1, C2, E)
  B* DiT-small wired with adaLN + memory tokens + local-add seam (needs B1,B2,B3, E)

Tier 4  (integration)
  §4 SA3-Small end-to-end proof   (needs C3, B*, D2, + reused DiffusionSampler/PingPong)
```

Critical path: **A1/A2 → C2 → C3** (the autoencoder) is the long pole; **B1/B2/B3 → DiT-small** runs in
parallel; **D1 → D2** runs in parallel. Block E should be stood up first because it unblocks every
real-weight test and the C2 reference vectors.

---

## 4. SA3-Small end-to-end integration proof

The proof consumes the blocks and demonstrates the reusable tooling on the fixed-length
`small-music` / `small-sfx` target. It reuses **unchanged**: `DiffusionSampler`
(`DiffusionSampler.java:60`), `PingPongSamplingStrategy` (`PingPongSamplingStrategy.java:36` — the
confirmed rectified-flow match, [`MILESTONE_0_FINDINGS.md`](MILESTONE_0_FINDINGS.md) §2.2),
`StateDictionary`, `CompiledModelAutoEncoder`, and `WaveData`/`WaveOutput`.

```java
// 1. Weights (Block E): safetensors -> StateDictionary for DiT, SAME-S; T5Gemma encoder exported.
StateDictionary ditW  = new StateDictionary(ditDir);     // model.model.transformer.* (+ adaLN/memory/local-add)
StateDictionary aeW   = new StateDictionary(sameDir);    // SAME-S encoder/decoder

// 2. Autoencoder (Block C3 over C1+C2), satisfied by the EXISTING adapter:
AutoEncoder vae = new CompiledModelAutoEncoder(
        sameEncoder.compile(false), sameDecoder.compile(false),
        44100.0, 44100.0 / 4096.0, 120.0);               // 256-ch latent, ~10.77 Hz

// 3. DiT (Block B over DiffusionTransformer): adaLN mode + 64 memory tokens + local-add seam.
DiffusionTransformer dit = new DiffusionTransformer(
        256, 1024, 20, 16, /*patchSize*/ 1, /*condTokenDim*/ 768, /*globalCondDim*/ 768,
        "rf_denoiser", ConditioningMode.ADALN, ditW);    // numMemoryTokens=64, localAddCondDim=257 via config

// 4. Conditioning (Blocks D2+D1): T5Gemma cross-attn (256 tok) + duration dual-routed.
AudioAttentionConditioner cond = new T5GemmaConditioner(/* encoder export */, /* NumberConditioner */);
ConditionerOutput c = cond.runConditioners(tokenizer.encode("upbeat electronic music"), 30.0);

// 5. Sample (REUSED loop + rectified-flow strategy) at the SAME-S latent shape:
DiffusionSampler sampler = new DiffusionSampler(
        dit, new PingPongSamplingStrategy(), /*numSteps*/ 8,
        shape(1, 256, latentFramesFor(30.0)));            // fixed length; per-call shape is Phase 2
PackedCollection latent = sampler.sample(seed, c.getCrossAttentionInput(), c.getGlobalCond());

// 6. Decode (REUSED path): latent -> stereo 44.1 kHz PCM -> WaveData.
PackedCollection audio = vae.decode(cp(latent)).evaluate();   // .evaluate() at the pipeline boundary only
```

**Acceptance criteria for Phase 1.**
1. Each Block's standalone test (§2 (d)) passes **before** the end-to-end run — the blocks are proven
   independently of SA3 first.
2. The end-to-end call produces **plausible audio** (not silence, not noise) for a known prompt at a
   fixed 30 s length, decoded to stereo 44.1 kHz, for **both** `small-music` and `small-sfx` weights.
3. `mcp__ar-build-validator__start_validation` reports no new code-policy / checkstyle / duplicate-code
   violations (the Producer-pattern and no-duplication rules are load-bearing for these blocks).
4. The B3 local-add mask convention (the carried `TODO(review)` item) is resolved and the released
   inpaint-capable weights **load and `validateWeights()` passes** with the local cond inert.

The proof's only role is to *exercise* the reusable blocks. Nothing in §4 introduces an SA3-specific
abstraction that the blocks themselves don't already provide; the SA3 specifics are confined to config
values (dims, key prefixes, latent rate) and weight files.

---

## 5. Overall build sequence

1. **Block E core** (generalized extractor) — stand up first; it unblocks every real-weight test and
   the C2 reference vectors. Add the SA3 remap config and the SAME per-stage activation dumps.
2. **Block A1 + A2** (differential attention variant, DyT norm) — foundation primitives with synthetic
   identity tests; no model weights.
3. **Block C1** (Bottleneck interface + SoftNormBottleneck; retrofit VAEBottleneck) in parallel with
   **B1/B2/B3** (DiT extensions) and **D1** (NumberConditioner) — all Tier-1, mutually independent.
4. **Block C2** (transformerResamplingBlock) — the hard item; gate it on the shape/identity tests,
   then the per-stage numerical-parity tests from step 1's reference dumps, **one SAME stage at a
   time**, before assembling the AE.
5. **Block D2** (T5GemmaConditioner) — wiring test with a stub encoder, then the encoder export +
   parity test.
6. **Block C3** (SAME-S assembly) — round-trip reconstruction test against the reference.
7. **DiT-small** wired with adaLN + memory tokens + local-add seam — load real small weights, run a
   synthetic forward, confirm `validateWeights()` is clean.
8. **§4 end-to-end proof** for `small-music` and `small-sfx`, then build-validator.

Each step ends with its standalone test green before the next begins, so a failure localizes to one
block. Store progress to workstream memory at each block boundary (the project's "store as it happens"
rule).

---

## 6. Hard items — call out for sequencing & resourcing

Ranked by difficulty/risk so the implementation workstream can resource them deliberately:

1. **C2 — `transformerResamplingBlock` (the learned-resampling primitive). HARDEST.** It is the one
   piece with no AR analog (sequence-length-changing learned `new_tokens`), it bundles differential
   attention (A1), DyT (A2), RoPE, GLU FFN, and a windowed-attention strategy (chunked for SAME-S),
   and it is on the autoencoder critical path. **De-risk by isolation:** validate shape/identity with
   synthetic weights first, then numerical parity **per SAME stage** against extractor-dumped
   reference activations (§2 C2 (d)) before the AE is assembled. Resource this as its own multi-step
   sub-effort; do not couple its bring-up to the DiT work.
2. **C3 — SAME-S full-AE numerical correctness.** Even with C2 correct per-stage, end-to-end
   encode→decode reconstruction error against the reference is the integration risk (patched
   pretransform folding, decoder upsampling, SoftNorm scale). Gate on the round-trip test.
3. **B1 — adaLN mode.** Touches two coordinated seams (DiT config + per-block modulation in
   `transformerBlock`). Lower modeling risk than C2 but higher blast radius (the block builder is
   shared by all DiT consumers); the identity tests (scale=1/shift=0/gate=1, then gate=0) are the
   guardrail.
4. **D2 — T5Gemma encoder export.** Risk is in the model-specific encoder graph (256-token,
   `t5gemma-b-b-ul2`), not the reusable wiring. Keep the two separated so a wiring bug and an encoder
   bug never masquerade as each other.
5. **B3 mask convention (verification gate).** Small but unresolved (the carried `TODO(review)`);
   confirm from SA3 `model.py` before relying on "present-but-inert."

Blocks **A1, A2, B2, C1, D1, E-core** are comparatively low-risk (synthetic-testable, small, or
mostly-reuse) and can be parallelized across contributors.

---

## 7. Continuity — out of Phase-1 scope (noted so it lands without rework)

- **Variable-length output (Phase 2).** `DiffusionSampler.latentShape` is **final/construction-time**
  and `sampleFrom(...)` rejects a differently-shaped start latent (`DiffusionSampler.java:184-187`) —
  there is **no per-call shape today** ([`MILESTONE_0_FINDINGS.md`](MILESTONE_0_FINDINGS.md) §2.1). A
  per-call `latentShape` overload (or per-length re-instantiation) is a Phase-2 **extend**, plus the
  schedule **distribution-shift warp** (default `LogSNRShift`) and per-element schedules on the
  otherwise-matching `PingPongSamplingStrategy` (`:73-93`). Building the SAME latent at a configurable
  length in Phase 1 (it already is) keeps this a clean extend.
- **DiT-medium (Phase 2)** = the Phase-1 DiT extensions **plus differential attention (A1)** — which
  Phase 1 already builds for the AE, so medium is config + the larger `1536/24/24` dims with **no new
  attention work**. This is the payoff of building A1 once.
- **Application-level inpainting (Phase 3).** The B3 additive seam is built in Phase 1 (loadable,
  inert); the mask-construction / multi-region / continuation **API** is Phase 3.
- **LoRA adapter-family converter (Phase 3).** AR's positional `lora.<i>.A/.B` bundle
  (`LoRADiffusionTransformer.java:201-248`) maps only `adapter_type=="lora"`; SA3's named-module
  safetensors with DoRA/BoRA/LoRA-XS families need a converter + new adapter math
  ([`MILESTONE_0_FINDINGS.md`](MILESTONE_0_FINDINGS.md) §2.2). Out of Phase-1 scope entirely.

---

## 8. Summary

Phase 1 delivers **eleven reusable framework artifacts** — differential-attention variant (A1), DyT
norm (A2), adaLN mode (B1), learned register tokens (B2), local-additive cond seam (B3), `Bottleneck`
interface + `SoftNormBottleneck` (C1), the `transformerResamplingBlock` learned-resampling primitive
(C2, hardest), SAME-S assembly via the existing `AutoEncoder` adapter (C3), `NumberConditioner` (D1),
`T5GemmaConditioner` on the existing `AudioAttentionConditioner` interface (D2), and the generalized
safetensors extractor (E). Nine of the eleven either implement an existing AR interface unchanged or
extend an existing component in place; the two genuinely new primitives (`Bottleneck`,
`transformerResamplingBlock`) are general by design, not SA3 shims. SA3 Small fixed-length
text-to-audio is the integration proof that each block works — and the reused `DiffusionSampler` +
rectified-flow `PingPongSamplingStrategy` + `CompiledModelAutoEncoder` + `WaveData` confirm how much of
the existing pipeline carries straight over.
