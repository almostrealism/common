# Stable Audio 3 — Milestone 0 Findings

**Branch:** `feature/stable-audio-3-research`
**Companion to:** [`../STABLE_AUDIO_3.md`](../STABLE_AUDIO_3.md)
**Status:** Research — Objective 2 (codebase grounding) complete; Objective 1 (primary-source
reading) **blocked** in this environment (see §0).
**Date:** 2026-06-13

This document grounds the claims in `STABLE_AUDIO_3.md` against (a) the real source code in this
repository and (b) the primary sources for Stable Audio 3. The parent document was written largely
from expectation and is explicit that its architecture details are unverified estimates; the goal
here is to replace expectation with evidence wherever evidence is obtainable.

Every infrastructure claim below cites the file and line that supports it. Every architecture claim
that could *not* be verified is marked **UNVERIFIED** rather than asserted.

---

## 0. Method and the Milestone 0 source-reading gate

Milestone 0 (parent doc §9, §12) requires reading **arXiv:2605.17991** in full and the Hugging
Face model cards for the open SA3 variants. **This could not be done in this session:** the
`WebFetch` and `WebSearch` tools are denied in this environment, and arXiv / Hugging Face are
external sources past the assistant's knowledge cutoff. No primary SA3 source was accessible.

Consequences and the integrity rule applied here:

- The SA3 *architecture* facts the parent doc lists (semantic-acoustic autoencoder design, the
  459M / 459M / 1.4B parameter split, "second-level control," `.safetensors` vs `.ckpt`, the
  weight-key prefix, the inpainting API, the real HF repo names, the corrected total parameter
  counts) **cannot be confirmed from this environment.** They are recorded below as **open items**,
  not as confirmed facts. Fabricating a "confirmed" reading of a paper that was not read would be a
  false-evidence pattern and is explicitly avoided.
- What *can* be established with certainty is the **existing SA-Open code baseline** that any SA3
  delta is measured against. That baseline is fully verifiable from source and is the substance of
  §2 (the reuse ledger). It is also the most useful thing to have nailed down before the paper is
  read, because it defines exactly which SA3 facts matter.

**To close Milestone 0, a follow-up session needs web access** (or a local copy of the paper and the
model `config.json` files) to answer the items in §1.2.

---

## 1. Architecture: verified baseline vs. open items

### 1.1 Verified — the existing SA-Open pipeline baseline (from source)

These are facts about the pipeline already in the repo, which SA3 integration extends. They are the
reference points for §1.2.

| Property | Value | Evidence |
|---|---|---|
| Audio format | 44,100 Hz, stereo (2 ch), float32 PCM | `OnnxAutoEncoder.java:54` (`SAMPLE_RATE=44100`); `AudioDiffusionGenerator.java:69,256-268` |
| AE input length | 524,288 samples per clip (`2048*256`) | `OnnxAutoEncoder.java:60` (`FRAME_COUNT=2048*256`); `DiffusionTransformer.java:102` (`SAMPLE_SIZE=524288`) |
| Latent shape | **64 channels × 256 frames** per ~11.9 s clip | `OnnxAutoEncoder.java:63,161,179` (`LATENT_DIMENSIONS=64`, `(64,256)`); `VAEBottleneck.java:58` (`LATENT_DIM=64`) |
| Latent downsample ratio | **2048×** (latent frame rate ≈ 21.5 Hz) | `DiffusionTransformer.java:105` (`DOWNSAMPLING_RATIO=2048`); `CompiledModelAutoEncoder` example latentSampleRate 21.5 |
| AE max duration | 11.0 s (fixed) | `OnnxAutoEncoder.java:51` (`MAX_DURATION=11.0`) |
| Text encoder | **T5, 128-token** max; scalar `seconds_total` | `OnnxAudioConditioner.java:40` (`T5_SEQ_LENGTH=128`), `:120-122` (inputs `input_ids`, `attention_mask`, `seconds_total`) |
| Conditioner outputs | cross-attention input, cross-attention mask, global cond | `AudioAttentionConditioner.java:63-111`; `OnnxAudioConditioner.java:128-137` |
| SA-Open reference DiT | ioChannels 64, embedDim 1536, depth 24, numHeads 24, patchSize 1, condTokenDim 768, globalCondDim 1536 | `DiffusionTransformer.java:69-79` (javadoc example); test configs in `DiffusionTransformerTests.java` |
| Diffusion objective in use | `"rf_denoiser"` (rectified flow) | `DiffusionTransformerTests.java:144,195,245,...`; `AudioGeneratorRefactoringTest.java:179` |
| Samplers available | DDIM (cosine DDPM schedule) **and** ping-pong (rectified flow) | `DDIMSamplingStrategy.java` + `DiffusionNoiseScheduler.java`; `PingPongSamplingStrategy.java:25-36` |
| AE compression structure | Conv1d → 5 Snake/weight-norm blocks → 128-ch → bottleneck → 64-ch mean | `OobleckAutoEncoder.java:37-64`; `VAEBottleneck.java:52-101`; `extract_stable_audio_autoencoder.py:202-402` |
| AE checkpoint format (SA Open) | `.ckpt` via `torch.load(...)['state_dict']`, weight prefix `pretransform.model.` | `extract_stable_audio_autoencoder.py:77-78,86,89` |

Note on an internal inconsistency: `OobleckAutoEncoder.java:34-64` javadoc and the reference script's
encoder strides `[4,8,8,16,16]` (`extract_stable_audio_autoencoder.py:214`) imply a 65,536×
downsample, whereas the operative pipeline (ONNX wrapper + `DiffusionTransformer` +
`CompiledModelAutoEncoder` latent rate 21.5 Hz) is **2048×** with a 64×256 latent. The 2048× / 64×256
figure is the one used by the diffusion path and is the correct reference for SA3. The 65,536 figure
in the Oobleck javadoc appears to be stale documentation. (Existing-code issue; out of scope to fix
here, flagged for the SA-Open maintainers.)

### 1.2 Open items — require the paper / model cards (UNVERIFIED)

Each maps to a parent-doc §12 question. None can be resolved from this environment.

1. **Semantic-acoustic autoencoder architecture & latent shape.** Whether SA3 keeps 64×256 / 2048×
   / 44.1 kHz stereo, or changes channels/frame rate, and whether the "semantic" path is a separate
   encoder branch producing conditioning embeddings vs. a single restructured bottleneck. **UNVERIFIED.**
   *Why it matters:* drives whether `OobleckAutoEncoder`/`OobleckEncoder`/`OobleckDecoder`/`VAEBottleneck`
   can be reused (they hardcode 2-in / 128-mid / 64-latent / 5 blocks / Snake / weight-norm).

2. **DiT hyperparameters for Small, Small SFX, Medium** (embedDim, depth, numHeads, patchSize,
   condTokenDim, globalCondDim). **UNVERIFIED.** *Why it matters:* if SA3 keeps the SA-Open block
   structure, these are pure constructor arguments (§2, ledger row "DiffusionTransformer").

3. **Text conditioner** — still T5 (same 128-token length, same 768 output dim), or CLAP / T5-large /
   multi-modal. **UNVERIFIED.** *Why it matters:* the conditioner is **ONNX-only** in this repo (§2);
   a change means a new ONNX export and possibly new input wiring.

4. **"Second-level control" mechanism** — additional global-cond vector, different global-cond dim,
   or a new conditioning path. **UNVERIFIED.** *Why it matters:* affects `ConditionerOutput` and the
   `globalCond` contract.

5. **Audio-inpainting API at the model level** — a mask tensor as an *additional DiT input* vs.
   latent-sampling-level masking. **UNVERIFIED.** *Why it matters:* `DiffusionModel.forward` is fixed
   at 4 inputs (`x, t, crossAttnCond, globalCond`, `DiffusionModel.java:47-49`); an extra mask input
   would not fit the interface or the ONNX `OnnxDiffusionModel` 4-input signature
   (`OnnxDiffusionModel.java:87-90`).

6. **Checkpoint format & weight-key prefix** — `.safetensors` vs `.ckpt`; is the AE prefix still
   `pretransform.model.`. **UNVERIFIED.** *Why it matters:* the existing extractor uses
   `torch.load` + `pretransform.model.` (`extract_stable_audio_autoencoder.py:77,86`); safetensors or
   a renamed prefix changes the extraction script.

7. **Real HF repo names & public availability** of Small SFX / Small / Medium. **UNVERIFIED** — the
   parent doc's `stabilityai/stable-audio-3-*` names are flagged there as guesses and remain guesses.

8. **Parameter-count totals.** The task states external coverage treats 459M/459M/1.4B as
   *DiT-only* and puts *total* sizes (DiT + semantic-acoustic AE + text encoder) higher (~567M small,
   ~2.25B medium). This is internally plausible — a total is necessarily ≥ the DiT-only figure, and a
   ~108M autoencoder+encoder delta for the small model is reasonable — **but the actual numbers are
   UNVERIFIED here.** They drive the memory-scale planning in parent §3.5 / Risk 5 and must be read
   from the paper/cards before that planning is treated as final. As a rough sanity anchor only: an
   SA-Open-style 1536-dim / 24-layer DiT is itself on the order of ~1B parameters, so a "Small 459M"
   SA3 DiT is materially smaller than the SA-Open reference DiT — worth confirming against the cards.

9. **LoRA adapter examples / community fine-tunes** for Phase 3 validation. **UNVERIFIED / unavailable.**

10. **License in-code attribution** beyond documentation. **UNVERIFIED** (requires the current
    license text from the model card).

---

## 2. Infrastructure reuse-vs-build ledger (grounded in source)

Legend for **Verdict**: **REUSE** = usable for SA3 with configuration only; **REUSE\*** = usable
only if a specific SA3 assumption holds (stated); **EXTEND** = needs additive code; **BUILD** = needs
new code regardless.

| Component | File (evidence) | Verdict | SA3 delta required |
|---|---|---|---|
| `DiffusionModel` (interface) | `engine/ml/.../audio/DiffusionModel.java:47-49` | REUSE\* | 4-input `forward(x,t,crossAttnCond,globalCond)` is fixed. Reuse holds **unless** inpainting needs a 5th mask input → then EXTEND interface. |
| `DiffusionTransformer` | `DiffusionTransformer.java:222-225` (full ctor), `:411-521` (weight keys) | REUSE\* | All of {ioChannels, embedDim, depth, numHeads, patchSize, condTokenDim, globalCondDim, audioSeqLen, condSeqLen} are constructor args → Small **and** Medium are config-only **iff** SA3 keeps the same block structure: prepended conditioning (not AdaLN, `:380-392`), QK-norm (`:454-457`), RoPE (`:415-416`), GLU FFN with `2*hiddenDim` (`:482-488`), and the exact key layout `model.model.transformer.layers.N.*`. Any of those changing → EXTEND/BUILD. Caveat: `batchSize` is a **static** field (`:111`). |
| `DiffusionSampler` | `DiffusionSampler.java:72,94-102` (final `latentShape`), `:154-161`, `:173-216` | REUSE for fixed length; **EXTEND for variable length** | `latentShape` is final, set at construction. `sample()` uses it; `sampleFrom()` (the cited 173-207) is img2img and **rejects** any start latent of a different shape (`:184-187`). There is **no per-call shape**. Variable-length SA3 output needs either a new `DiffusionSampler` per length (re-instantiation; the model recompiles per shape) or a new `sample(..., latentShape)` overload. → resolves parent §3.4 / Risk 2: **per-call shape is NOT supported today.** |
| `AutoEncoder` (interface) | `AutoEncoder.java:32-69` | REUSE | `encode`/`decode`/`getSampleRate`/`getLatentSampleRate`/`getMaximumDuration`. Stable enough that a new SA3 AE implementation can satisfy it without interface change. |
| `OobleckAutoEncoder` | `OobleckAutoEncoder.java:112-128` | REUSE\* / likely **BUILD** | Hardcodes 2-in, 5 blocks, 128-mid, 64-latent, the Oobleck block stack. Reuse only if SA3's "semantic-acoustic" AE is architecturally Oobleck with the same shapes (open item §1.2-1). The name strongly implies an added semantic branch → expect **BUILD `StableAudio3AutoEncoder implements AutoEncoder`**. |
| `OobleckEncoder` / `OobleckDecoder` | (same package) | REUSE\* | Same condition as above; reusable as sub-blocks only if SA3 acoustic path matches Oobleck. |
| `VAEBottleneck` | `VAEBottleneck.java:55-101` | REUSE\* | Hardcoded 128→64 mean-split, inference-only (no reparameterization). Reuse only if SA3 latent is 64-ch from a 128-ch encoder; otherwise BUILD. |
| `AudioAttentionConditioner` (interface) | `AudioAttentionConditioner.java:39-111` | REUSE / EXTEND | `runConditioners(long[] tokenIds, double durationSeconds)` + 3-tensor `ConditionerOutput`. Reuse if SA3 stays text+duration. CLAP/inpainting/second-level control → EXTEND (additive: new default method and/or an extra `ConditionerOutput` field). |
| `OnnxAutoEncoder` | `extern/ml-onnx/.../OnnxAutoEncoder.java:48-181` | REUSE\* | Reusable **iff** a SA3 ONNX AE export uses inputs `audio` / `sampled` and shapes `(2,FRAME_COUNT)`↔`(64,256)`. Hardcoded `LATENT_DIMENSIONS=64`, `FRAME_COUNT=2048*256`, `MAX_DURATION=11.0` (`:51,60,63`) → different latent/length means a code change, not just an export. |
| `OnnxDiffusionModel` | `OnnxDiffusionModel.java:87-90` | REUSE\* | Reusable with a SA3 ONNX DiT export whose inputs are exactly `x, t, cross_attn_cond, global_cond`. An inpainting mask input would require a 5th tensor → EXTEND. |
| `OnnxAudioConditioner` | `OnnxAudioConditioner.java:37-144` | REUSE\* | Reusable with a SA3 T5 conditioner ONNX export using `input_ids`/`attention_mask`/`seconds_total` and 128-token length. CLAP / different inputs → BUILD a new conditioner export+wrapper. **Note: this is the only concrete conditioner; there is no native path.** |
| `LoRADiffusionTransformer` | `LoRADiffusionTransformer.java:201-248` | REUSE for AR-trained adapters; **BUILD converter** for SA3 adapters | Bundle keys are **positional** `lora.<i>.A` / `lora.<i>.B` in an AR `ModelBundle` proto, indexed by `loraLayers` insertion order. SA3 / stable-audio-tools LoRA is named-module safetensors (e.g. `...self_attn.to_q.lora_A.weight`). **Not directly loadable.** A loader must parse safetensors, map named modules → the insertion-order indices that `getProjectionFactory()` registers, match rank to `AdapterConfig` (default 8), and build a `ModelBundle`. → resolves parent §3.7 / Phase 3: **keys are NOT compatible as-is.** |
| `DiffusionNoiseScheduler` | `DiffusionNoiseScheduler.java:68-142` | REUSE\* | Discrete **cosine DDPM** schedule (1000 steps), used by the DDIM path. Reuse only if SA3 uses a comparable cosine/v schedule. If SA3 is flow-matching/rectified-flow end-to-end, the **PingPong** path (below) is the relevant reuse, not this scheduler. |
| `DDIMSamplingStrategy` | `DDIMSamplingStrategy.java` | REUSE\* | Pairs with the cosine scheduler. See scheduler caveat. |
| `PingPongSamplingStrategy` | `PingPongSamplingStrategy.java:25-70` | REUSE | **Rectified-flow sampler** (sigmoid log-SNR schedule, `x_{t-1}=(1-σ)·denoised+σ·noise`). This is the likely-relevant SA3 sampler given the `rf_denoiser` objective already used in tests. Strong reuse point the parent doc undersells. |
| `AudioDiffusionGenerator` | `studio/compose/.../AudioDiffusionGenerator.java:88-139,182-190` | REUSE for fixed length; EXTEND for variable length / duration | Constructs the `DiffusionSampler` from a fixed `latentShape` in its constructor and hardcodes 2-ch decode + 44100 (`:256-268`). Variable-length needs the same change as `DiffusionSampler`. |
| `AudioComposer` | `studio/compose/.../AudioComposer.java` | REUSE | Latent-space blending; latent-shape agnostic at the API level. |
| `CompiledModelAutoEncoder` | `studio/compose/.../CompiledModelAutoEncoder.java:59-90` | REUSE | **Native-path `AutoEncoder` adapter** wrapping compiled encoder/decoder models — the bridge that lets a native Oobleck (or SA3) AE satisfy `AutoEncoder`. The parent doc omits this; it is the native counterpart to `OnnxAutoEncoder`. |
| `StateDictionary` | `engine/ml/.../StateDictionary.java:50-130` | REUSE | Loads a directory of protobuf `CollectionLibraryData` files into a key→`PackedCollection` map. Format-agnostic to the model; SA3 weights work once extracted to this format. |
| `WaveData` / `WaveOutput` | `engine/audio` | REUSE | 44.1 kHz stereo boundary unchanged (assuming SA3 audio output format is unchanged — open item, but low risk). |
| `extract_stable_audio_autoencoder.py` | `engine/ml/scripts/extract_stable_audio_autoencoder.py:74-122` | **EXTEND / partial** | Extracts **only the autoencoder** (`pretransform.model.encoder`/`decoder`), via `torch.load` `.ckpt`, stripping `pretransform.model.`. It does **not** extract the DiT (`model.model.transformer.*`) or the T5 conditioner. So "fork this script" understates Milestone 1: SA3 needs (a) AE extraction (possibly safetensors), (b) **new** DiT extraction to the `model.model.*` keys `DiffusionTransformer` expects, and (c) a conditioner path (ONNX export of the text encoder, since the conditioner is ONNX-only). |

### 2.1 Resolution of the three flagged open items

- **Per-call latent shape in `DiffusionSampler`?** **No.** Construction-time only; `sampleFrom`
  rejects shape mismatches (`DiffusionSampler.java:184-187`). Variable-length SA3 is an **EXTEND**.
- **Is `DiffusionTransformer` sufficient for Small *and* Medium by config alone?** **Yes for the
  hyperparameters** (all are constructor args, `:222-225`), **conditional on** SA3 keeping the SA-Open
  DiT block structure and the `model.model.transformer.layers.N.*` weight-key layout. If SA3 changes
  blocks (AdaLN, different FFN, renamed keys), it becomes EXTEND/BUILD. Watch the static `batchSize`.
- **Is the LoRA adapter key format compatible with SA3?** **No.** Positional `lora.<i>.A/.B` in an AR
  `ModelBundle`; SA3 LoRA is named-module safetensors. A **BUILD** converter is required.

---

## 3. Corrections to `STABLE_AUDIO_3.md`

The following parent-doc statements are contradicted by the source and should be read with these
corrections (the parent remains a planning doc; this companion is the corrected record):

1. **§3.4 / §11 / Risk 2 — "The existing `DiffusionSampler` already accepts `latentShape` … whether
   it can be changed per call needs to be verified from `DiffusionSampler.java:173–207`."** Verified:
   lines 173-207 are `sampleFrom()` (img2img), which **rejects** a differently-shaped start latent
   (`:184-187`). `latentShape` is a **final construction-time field**. Per-call shape is **not**
   supported; variable length requires a code change. The "may already allow dynamic shape"
   mitigation in Risk 2 is incorrect.

2. **§2.4 / §3.3 — "`AudioAttentionConditioner conditioner = OnnxAudioConditioner or native`."**
   There is **no native conditioner**. `OnnxAudioConditioner` (ONNX) is the only concrete
   implementation; text conditioning depends on `extern/ml-onnx`.

3. **§8 / §11 — "fork `extract_stable_audio_autoencoder.py`."** That script extracts the
   **autoencoder only**. No existing script extracts the DiT or conditioner weights; Milestone 1
   must add those.

4. **§3.7 — LoRA adapter compatibility "needs verification."** Verified incompatible (positional vs
   named keys); a converter is required.

5. **Additions:** `PingPongSamplingStrategy` is a ready **rectified-flow** sampler (relevant given the
   `rf_denoiser` objective); `CompiledModelAutoEncoder` is the native `AutoEncoder` adapter. Both
   strengthen the reuse story and were missing from the parent ledger.

---

## 4. What remains to clear Milestone 0

1. Obtain web access (or local copies) and read arXiv:2605.17991 + the three model cards.
2. Answer the ten open items in §1.2 — especially the autoencoder architecture (decides REUSE vs
   BUILD for the whole AE stack), the conditioner identity (T5 vs CLAP/multimodal), the inpainting
   API shape (interface impact), and the true total parameter counts (memory planning).
3. Confirm the real HF repo names and that Small SFX / Small / Medium are publicly downloadable,
   then inspect one checkpoint to settle format (`.safetensors` vs `.ckpt`) and the weight-key prefix.
