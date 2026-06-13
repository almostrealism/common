# Stable Audio 3 — Milestone 0 Findings

**Branch:** `feature/stable-audio-3-research`
**Companion to:** [`../STABLE_AUDIO_3.md`](../STABLE_AUDIO_3.md)
**Status:** Research — Objective 2 (codebase grounding) complete; Objective 1 (primary-source
reading) **resolved from the official GitHub package** (see §0). One residual item (the exact
per-variant numeric hyperparameters) remains gated behind Hugging Face authentication.
**Date:** 2026-06-13

This document grounds the claims in `STABLE_AUDIO_3.md` against (a) the real source code in this
repository and (b) the primary source for Stable Audio 3 — the official `Stability-AI/stable-audio-3`
package on GitHub (§0). The parent document was written largely from expectation and is explicit that
its architecture details are unverified estimates; the goal here is to replace expectation with
evidence wherever evidence is obtainable.

Every **infrastructure** claim cites the AR file and line that supports it. Every **SA3 architecture**
claim cites a `path:line` in the pinned GitHub commit (§0). The handful of scalars that genuinely
could not be obtained (they live in gated Hugging Face configs) are marked **GATED** rather than
guessed (§4).

---

## 0. Method and the Milestone 0 source-reading gate

Milestone 0 (parent doc §9, §12) requires reading the SA3 architecture in full. A prior session
left this **blocked** because `WebFetch` / `WebSearch` are denied in this environment and the
arXiv paper / Hugging Face model cards are past the assistant's knowledge cutoff. **This session
closed Objective 1 using a source that *is* reachable: the official Stability AI source code on
GitHub**, read directly rather than through the web tools.

**Primary source used (authoritative for engineering facts, over the paper):**

- Repository: `github.com/Stability-AI/stable-audio-3` (MIT-licensed).
- Cloned into a scratch directory **outside** the common working tree (`/tmp/sa3-src`), so it is
  never part of the common change set.
- Pinned commit: **`bccf5b7b75734c95a3049bb43bdbc7b3070a31bc`** ("Update README", 2026-06-08).
- Files read: the `stable_audio_3` package (`models/autoencoders.py`, `models/dit.py`,
  `models/transformer.py`, `models/conditioners.py`, `models/diffusion.py`, `models/inpainting.py`,
  `models/bottleneck.py`, `models/lora/{loader,utils}.py`, `inference/sampling.py`,
  `model.py`, `model_configs.py`, `loading_utils.py`, `factory.py`) plus
  `docs/guides/model-overview.md` and `docs/workflows/{inference,autoencoder,lora}.md`.

**All ten §1.2 open items are now answered** (§1.2 below), each with a `path:line` citation into
that pinned commit. Every citation in §1.2 is reproducible by cloning the repo at the commit above;
the cited paths are **package-relative** (e.g. `models/dit.py:13`) within that repo, *not* paths in
the common repository.

**The one residual item that could NOT be obtained.** The exact *trained, per-variant* numeric
hyperparameters (each DiT's `embed_dim` / `depth` / `num_heads`; each SAME's stride list and channel
multipliers; the precise weight-key dumps) do **not** live in the GitHub package — the package reads
them at load time from a `model_config.json` inside each **Hugging Face** repo (`model_configs.py:54-107`).
Those HF repos are **gated**: anonymous access returns `HTTP 401` ("Access to model … is restricted.
You must … be authenticated"). With no HF credentials in this environment, the numeric configs could
not be retrieved. They are listed precisely in §4 so a follow-up session (or a credentialed
maintainer) can drop them in. Per the integrity rule, **no numbers are invented** — the
architecture-level answers below come from source; the per-variant scalars are marked **GATED**.

Note the arXiv references are, per the task, citation-only and the **repo source is treated as
authoritative over the paper** for engineering facts: 2605.17991 (Stable Audio 3) and 2605.18613 (SAME).

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

### 1.2 Resolved from SA3 source (commit `bccf5b7b`)

Each item maps to a parent-doc §12 question. All ten are now answered from the GitHub package; the
only scalars left open are the **GATED** per-variant numbers (§4). Citations are package-relative
into `Stability-AI/stable-audio-3@bccf5b7b`.

1. **SAME autoencoder architecture & latent shape — RESOLVED. It is entirely new code, not Oobleck.**
   The autoencoder is **SAME (Semantic-Acoustic Music Encoder)**. `SAMEEncoder` / `SAMEDecoder`
   (`models/autoencoders.py:225,290`) are **transformer-based resampling autoencoders**, structurally
   unrelated to SA-Open's Conv1d + Snake Oobleck stack. Each is a stack of `TransformerResamplingBlock`
   (`models/autoencoders.py:34`): a `WNConv1d` 1×1 channel mapping plus `TransformerBlock`s with RoPE,
   DyT/RMS-norm, optional **differential attention**, a GLU/SwiGLU FFN, and a **learned `new_tokens`
   resampling mechanism** (`:75,141-148`) that changes sequence length by `stride` — followed by a final
   `Linear` to the latent dim (`:262,318`). The bottleneck is a `SoftNormBottleneck`
   (`models/bottleneck.py:4`), **not** a mean/log-var VAE split. **Latent: 256-dim, stereo (2 ch),
   44.1 kHz, downsampling_ratio 4096** (`docs/guides/model-overview.md:41`,
   `docs/workflows/autoencoder.md:17`): a 10 s stereo clip → **216 × 256** latent (≈ 21.6 latent
   frames/s — essentially the SA-Open latent frame rate, but **4× the channel depth: 64 → 256**).
<!-- TODO(review): verify downsampling_ratio; "4096" is inconsistent with the 216-frame/21.6-Hz
     figures in this paragraph (44100/4096 ≈ 10.77 Hz → ~108 frames for 10 s; 216 frames and
     21.6 Hz both match 2048×, not 4096×). Same figure repeated in §2.2 OobleckAutoEncoder row.
     Re-read /tmp/sa3-src docs/guides/model-overview.md:41 to confirm the actual value. -->
   Two variants: **SAME-S (266M)** uses *chunked attention with midpoint shift* for CPU/edge;
   **SAME-L (1.7B)** uses *sliding-window attention* and needs a GPU/flash-attention
   (`docs/guides/model-overview.md:53-64`).
   *Consequence:* AR's `OobleckAutoEncoder` / `OobleckEncoder` / `OobleckDecoder` / `VAEBottleneck`
   (Conv1d + Snake + weight-norm, 128→64 mean bottleneck) **cannot be reused** — a new
   `StableAudio3AutoEncoder` must be **BUILT**, and it is a *large* build (transformer resampling,
   sliding/chunked attention, learned resampling tokens, SoftNorm bottleneck), none of which exist in
   the AR audio code. This confirms the external note that "the SAME decoder is new code, not the
   SA-Open VAE."

2. **DiT hyperparameters & block structure — RESOLVED at the structural level; per-variant scalars GATED.**
   The DiT is `models/dit.py:13` `DiffusionTransformer`, wrapped by `DiTWrapper.model`
   (`models/diffusion.py:200-211`) inside `ConditionedDiffusionModelWrapper.model`
   (`models/diffusion.py:28,54`). Its transformer is a `ContinuousTransformer`
   (`models/dit.py:117`). Structural facts that matter for reuse:
   - **Conditioning is prepended, not AdaLN, by default** (`global_cond_type` default `"prepend"`,
     `models/dit.py:27`; the timestep embedding is added to the global cond and prepended,
     `:243-262`). AdaLN is an *option*, not the default. → matches AR's prepend assumption.
   - `diffusion_objective` includes `"rf_denoiser"` and `"rectified_flow"` (`models/dit.py:30`),
     matching the `rf_denoiser` objective AR's tests already exercise.
   - Attention uses a **fused `to_qkv`** (`dim*3`, or **`dim*5` when differential attention is on**),
     `to_out`, and a **GLU/SwiGLU `ff`** (`models/transformer.py:421-444,549-560`). QK-norm options
     include `'dyt'` (DynamicTanh) (`:577`).
   - Patch size, cross-attention (`cond_token_dim>0`), global cond projection are all constructor
     args (`models/dit.py:14-35`).
   - **Variants** (`docs/guides/model-overview.md:98-105`, `README.md:19-22`): `small-music` and
     `small-sfx` = **433M** DiT on **SAME-S**, max ~120 s; `medium` = **1.4B** DiT on **SAME-L**, max
     ~380 s; `large` = 2.7B, **API-only / out of scope**.
   *Consequence:* the SA-Open `model.model.transformer.layers.N.*` key path **holds** (item 5), so the
   variants are config-only **iff** SA3's released DiTs use the plain (non-differential) block with
   standard QK-norm. **If they use differential attention (`to_qkv` = `dim*5`) or DyT QK-norm, AR's
   block and key layout will not match → EXTEND.** Which of these the released checkpoints use is in
   the **GATED** `model_config.json` (§4), along with the exact `embed_dim` / `depth` / `num_heads`.

3. **Text conditioner — RESOLVED: it changed from plain T5 to T5Gemma; CLAP is *not* the conditioner.**
   `models/conditioners.py:157` `T5GemmaConditioner` uses **`google/t5gemma-b-b-ul2`**, output
   **768-dim**, **`max_length = 128`** (`:159-182`), cross-attention. It is **not** plain T5 and
   **not** CLAP — CLAP appears only as a *training loss* in adversarial post-training
   (`docs/guides/model-overview.md:96`), never as an inference conditioner.
   *Consequence:* AR's `OnnxAudioConditioner` (a T5 ONNX export) must be replaced by a **T5Gemma-encoder
   export**. The output contract (token embeddings → cross-attention) is the same shape (768-d, 128
   tokens), but the model weights/graph differ, so this is a new conditioner export, not a drop-in.

4. **"Second-level control" — RESOLVED: it is duration-in-seconds via a learned `NumberConditioner`.**
   Duration is encoded by `NumberConditioner` / `NumberEmbedder` (`models/conditioners.py:95-155`) —
   a learned Fourier/positional embedding of the seconds value — and fed as a **global conditioning**
   vector (alongside the timestep embed), *not* through the text encoder. The user-facing control is
   simply `duration` in seconds (`docs/workflows/inference.md:48`,
   `docs/guides/model-overview.md:73`). There is no separate richer "second-level" path beyond this
   learned duration embedder.
   *Consequence:* in SA-Open the duration scalar was an *input to the conditioner ONNX*; in SA3 it is
   its **own learned global-cond embedder**. AR's conditioner contract must produce this duration
   global-cond separately from the text cross-attention.

5. **Audio-inpainting / continuation API — RESOLVED: input-channel concatenation, not a 5th input and
   not pure sampler masking.** Inpainting is wired as **two `input_concat` conditioning tensors**
   (`model.py:280-296`, surfaced via `ConditionedDiffusionModelWrapper.get_conditioning_inputs`
   `models/diffusion.py:142-145`):
   - `inpaint_mask` — shape `(B, 1, latent_len)`, **1 = provided/keep, 0 = regenerate**, interpolated
     from audio-sample resolution down to latent resolution (`model.py:276-285`).
   - `inpaint_masked_input` — shape `(B, latent_dim, latent_len)`, the **SAME-encoded** source audio
     multiplied by that mask (`model.py:287-296`).
   Both are **concatenated to the noisy latent `x` along the *channel* dim before the transformer**
   (`models/dit.py:217-222`: `x = torch.cat([x, input_concat_cond], dim=1)`). So the **released
   checkpoints are inherently inpaint-capable** (a `diffusion_cond_inpaint`-type model): the DiT's
   input channel count is `latent_dim` (noise) + `latent_dim` (masked input) + `1` (mask). For plain
   text-to-audio those concat channels are zeros (`model.py:281-293`). Multiple non-contiguous regions
   are encoded as several zeroed spans in one mask (lists of `inpaint_mask_start/end_seconds`,
   `docs/workflows/inference.md:99-111`); continuation is just an inpaint mask that starts at the end
   of the source audio (`:113-127`).
   *Consequence:* this does **not** fit AR's 4-input `DiffusionModel.forward(x, t, crossAttnCond,
   globalCond)` (`DiffusionModel.java:47-49`) as a fifth argument, and it is **not** the
   latent-sampling-level masking the parent doc speculated about. AR must feed the masked latent + the
   mask as **extra input channels** of the DiT — i.e. either pre-concatenate them into `x` (keeping the
   4-arg forward but requiring an inpaint-capable checkpoint with the larger `io_channels`) or extend
   the model interface. This sharpens the `DiffusionSampler.sampleFrom` extension the ledger calls for:
   the mask input is a **latent-resolution 1-channel mask plus a latent_dim-channel masked latent**,
   both at the DiT input, not a guidance-time overlay.

6. **Checkpoint format & weight-key prefix — RESOLVED.** Each repo ships **`model.safetensors`** +
   **`model_config.json`** (`model_configs.py:54-85`). In a full DiT checkpoint the autoencoder is
   nested under **`pretransform.model.*`** (`loading_utils.py:41-49`) — the **same prefix SA-Open
   used** — while standalone SAME repos (`stabilityai/SAME-S`, `SAME-L`) carry **bare** AE keys
   (`loading_utils.py:43-49`). The DiT weights live under `model.*` within the wrapper, i.e. the
   transformer is `model.model.transformer.*` (item 2).
   *Consequence for AR's extractor:* the AE prefix `pretransform.model.` **still holds**, but the
   format is **safetensors** (`safe_open`, not `torch.load` on a `.ckpt`), and the DiT extraction to
   `model.model.*` is **new** (as the prior session already flagged for the AE-only script).

7. **Real HF repo names & availability — RESOLVED (names) / GATED (download).** The registry
   (`model_configs.py:54-107`) confirms: post-trained `stabilityai/stable-audio-3-small-music`,
   `stabilityai/stable-audio-3-small-sfx`, `stabilityai/stable-audio-3-medium`; base
   `…-small-music-base`, `…-small-sfx-base`, `…-medium-base`; autoencoders `stabilityai/SAME-S`,
   `stabilityai/SAME-L`. README links these and points to a `stable-audio-3-extra` collection for the
   base + SAME + optimized variants (`README.md:19-24`). All of these are **gated**: anonymous
   `git`/`huggingface_hub`/`curl` access returns `HTTP 401` (see §0, §4).

8. **Parameter-count totals — partially RESOLVED.** The published figures are now confirmed as
   **DiT-only**: 433M (small-music / small-sfx) and 1.4B (medium) (`docs/guides/model-overview.md:100-105`,
   `README.md:19-22`) — note the prior doc's "459M" was an estimate; the source says **433M**. The
   autoencoders are **separate** and sizeable: **SAME-S 266M**, **SAME-L 1.7B**
   (`docs/guides/model-overview.md:55-59`). The text encoder (`google/t5gemma-b-b-ul2`) is an
   additional frozen model. So an at-rest *small* deployment is ≈ 433M (DiT) + 266M (SAME-S) + the
   T5Gemma encoder, and *medium* is ≈ 1.4B (DiT) + 1.7B (SAME-L) + T5Gemma — i.e. **the autoencoder
   dominates the medium footprint** (1.7B AE vs 1.4B DiT). The exact byte sizes still depend on the
   GATED per-tensor dtypes, but the component split is now firm and should drive the memory-scale
   planning in parent §3.5 / Risk 5. The README's measured peak VRAM is a useful anchor: `small`
   ≈ 1.7–2.4 GB, `medium` ≈ 5.1–6.5 GB (unchunked decode) (`README.md:30-39`).

9. **LoRA adapter examples / community fine-tunes — RESOLVED at the format level (item 6 of §2).**
   The on-disk format, adapter family, and config metadata are fully specified in source
   (`models/lora/{loader,utils}.py`); a concrete community adapter to test against is still external
   (the LoRA guide points to third-party tooling, `docs/workflows/lora.md:46`), but the format is no
   longer unknown.

10. **License in-code attribution — note.** SA3 is MIT-licensed *as code* (the GitHub `LICENSE`), but
    the **weights** are governed by the Stability AI Community License referenced from the gated model
    cards (not readable here). The parent doc's licensing analysis (Common ships no weights, so the
    weight license does not constrain Common's code) is unaffected; any in-output attribution
    requirement would attach to the application that ships the weights, not to Common. The exact
    current weight-license text remains behind the gate.

---

## 2. Infrastructure reuse-vs-build ledger (grounded in source)

Legend for **Verdict**: **REUSE** = usable for SA3 with configuration only; **REUSE\*** = usable
only if a specific SA3 assumption holds (stated); **EXTEND** = needs additive code; **BUILD** = needs
new code regardless.

> **Update (this session):** the table below was written when the SA3-side assumptions in the
> **REUSE\*** rows were still unknown. Those assumptions are now **resolved** against SA3 source
> (§1.2). The table is left intact as the audit of the *AR baseline*; **§2.2 records every verdict
> that the resolved SA3 facts now settle or change.** Where a row's "SA3 delta" speculates ("the name
> strongly implies…", "an extra mask input would…"), read §2.2 for the confirmed answer.

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

### 2.2 Verdict updates after reading SA3 source (commit `bccf5b7b`)

These supersede the speculative "SA3 delta" cells above wherever they differ.

| Component | Prior verdict | **Settled verdict** | What the source decided (§1.2 item) |
|---|---|---|---|
| `OobleckAutoEncoder` / `OobleckEncoder` / `OobleckDecoder` / `VAEBottleneck` | REUSE\* / "likely BUILD" | **BUILD (large)** | SA3's AE is **SAME**, a transformer-resampling autoencoder (sliding/chunked attention, learned resampling tokens, `SoftNormBottleneck`), **not** Oobleck. None of the AR Oobleck/Snake/Conv1d AE code applies. A new `StableAudio3AutoEncoder implements AutoEncoder` is required, and it is a substantial modeling effort, **not** a config tweak. Latent is **256-ch** (vs SA-Open's 64), `downsampling_ratio` 4096, 44.1 kHz stereo, ≈ 21.6 latent frames/s (10 s stereo → 216×256). (§1.2-1) |
| `DiffusionTransformer` | REUSE\* | **REUSE\* — condition now precise** | Key path `model.model.transformer.layers.N.*` **confirmed** and default conditioning is **prepend, not AdaLN** — both AR assumptions hold. Remaining condition: SA3's released DiT must use the *plain* block (standard QK-norm, non-differential `to_qkv`=`dim*3`, GLU FFN). If it uses **differential attention** (`to_qkv`=`dim*5`) or **DyT** QK-norm → **EXTEND**. The deciding scalars/flags are GATED (§4). (§1.2-2) |
| `DiffusionModel` (interface) + `OnnxDiffusionModel` | REUSE\* ("EXTEND if 5th mask input") | **EXTEND — but as input *channels*, not a 5th arg** | Inpainting is **input-channel concatenation**: the masked SAME latent (`latent_dim` ch) + a 1-ch mask are concatenated to the noisy latent **before** the transformer. The 4-arg `forward(x,t,crossAttnCond,globalCond)` can stay **iff** AR pre-concatenates these into `x` and uses an inpaint-capable checkpoint with the larger `io_channels`; otherwise extend the interface. Not a guidance-time overlay. (§1.2-5) |
| `AudioAttentionConditioner` + `OnnxAudioConditioner` | REUSE / EXTEND ("CLAP?") | **BUILD new conditioner export; EXTEND interface** | Text encoder changed **T5 → T5Gemma** (`google/t5gemma-b-b-ul2`, 768-d, 128 tok) → a **new** encoder export, not the existing T5 ONNX. CLAP is **not** a conditioner (training-loss only). Duration is a **separate learned `NumberConditioner`** global-cond, not an ONNX conditioner input. The `runConditioners(tokenIds, durationSeconds)` contract must split into a T5Gemma cross-attn path + a learned duration global-cond. (§1.2-3, §1.2-4) |
| `PingPongSamplingStrategy` | REUSE ("likely-relevant") | **REUSE — match CONFIRMED**; schedule is the EXTEND | SA3's default sampler for `rf_denoiser` is `sample_flow_pingpong`, update `x=(1-t_next)·denoised + t_next·noise` — **identical** to AR's PingPong. 8 steps (post-trained) / ~50 (-base). **However** SA3 warps the linear `t∈[1,0]` schedule with a **distribution shift** (default `LogSNRShift`; `FluxDistributionShift` available) and supports **per-element** schedules for variable length — AR's fixed sigmoid log-SNR schedule must be extended to match and to do varlen. img2img mixing `init_data·(1-σ)+noise·σ` matches AR `sampleFrom`. (§1.2 / sampler) |
| `DiffusionNoiseScheduler` / `DDIMSamplingStrategy` | REUSE\* | **Not on the SA3 path** | SA3 is rectified-flow end-to-end (`rf_denoiser` → pingpong). The discrete cosine-DDPM scheduler + DDIM are **not** used by SA3; the PingPong path is the relevant reuse. |
| `LoRADiffusionTransformer` | BUILD converter | **BUILD converter — and AR can represent only `adapter_type=="lora"`** | SA3 LoRA keys are `…parametrizations.weight.0.{lora_A,lora_B,M_xs,magnitude,magnitude_r,magnitude_c}` in safetensors with a `lora_config` JSON in metadata (rank default 16). The adapter **family** is `lora`, `dora-rows`(default)/`dora-cols`, `bora`, `lora-xs`, and `-xs` hybrids — applied to **both** the DiT and the conditioner. AR's positional `lora.<i>.A/.B` rank-8 standard LoRA maps **only** to `adapter_type=="lora"`; DoRA/BoRA/LoRA-XS need new math, not just a key remap. (§1.2-9, item 6 below) |
| `extract_stable_audio_autoencoder.py` | EXTEND / partial | **EXTEND — format & scope confirmed** | AE prefix `pretransform.model.` **still holds**, but the format is **safetensors** (`safe_open`, not `torch.load` `.ckpt`). DiT extraction to `model.model.*` and a T5Gemma-encoder export are **new**. (§1.2-6) |

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

6. **§3.1 / Risk 1 — "semantic-acoustic autoencoder may be a modified Oobleck."** Confirmed **not**
   Oobleck: SAME is a *transformer-resampling* autoencoder with a `SoftNormBottleneck` (§1.2-1). The
   AE stack is a **BUILD**, and a larger one than the parent doc anticipated.

7. **§2.4 / §3.3 — "SA3 may add CLAP alongside T5."** The text conditioner is **T5Gemma**, not T5 and
   not CLAP; CLAP is only an adversarial-post-training *loss* (§1.2-3). Duration is a separate learned
   `NumberConditioner` global-cond (§1.2-4).

8. **§3.5 — parameter counts.** The open-variant figures are **DiT-only 433M / 433M / 1.4B** (parent
   doc's "459M" was an estimate), and the **autoencoder is a separate, large model** (SAME-S 266M,
   SAME-L 1.7B) that dominates the medium footprint (§1.2-8). Memory planning must budget DiT + SAME +
   T5Gemma, not the DiT figure alone.

9. **Inpainting (§3.3 / Phase 3 / Risk 3).** Resolved as **input-channel concatenation** of a
   latent-resolution mask + masked SAME latent, not a sampler-level mask and not a fifth `forward`
   argument (§1.2-5).

---

## 4. What remains to clear Milestone 0

Objective 1 is **resolved at the architecture level** from `Stability-AI/stable-audio-3@bccf5b7b`
(§0, §1.2, §2.2). What is **still genuinely unobtainable in this environment** is narrow and specific:

1. **The exact per-variant `model_config.json` (GATED behind Hugging Face auth).** The trained scalars
   live only in the HF repos, which return `HTTP 401` (gated) to anonymous `git` /
   `huggingface_hub` / `curl`. Needed, per repo, the small text files only (skip the multi-GB weights,
   e.g. `GIT_LFS_SKIP_SMUDGE=1`):
   - `stabilityai/stable-audio-3-small-music` → `model_config.json` (+ `model.safetensors` *index*/header only)
   - `stabilityai/stable-audio-3-small-sfx` → `model_config.json`
   - `stabilityai/stable-audio-3-medium` → `model_config.json`
   - `stabilityai/SAME-S` and `stabilityai/SAME-L` → `model_config.json`
   - (optionally the matching `-base` repos for LoRA-training parity)

   From each `model_config.json`, extract for the **DiT**: `embed_dim`, `depth`, `num_heads`,
   `patch_size`, `cond_token_dim`, `global_cond_dim`, `io_channels` (note the inpaint concat channels),
   `diffusion_objective`, and the attention flags (`differential`, `qk_norm` = `dyt`/`rms`/…) that
   decide REUSE-vs-EXTEND for `DiffusionTransformer` (§2.2). For **SAME**: `latent_dim` (expect 256),
   `strides` / `c_mults` / `transformer_depths`, `sliding_window` vs chunked, and `downsampling_ratio`.
   A credentialed maintainer should commit these JSONs into the branch as local reference so a
   follow-up session can finalize the per-variant configs without network access.

2. **A concrete SA3 LoRA adapter** to validate the converter end-to-end (format is fully known from
   source, §1.2-9; only a real sample file is external).

3. **The current weight-license text** from the gated model cards (does not affect Common's code,
   which ships no weights; §1.2-10).

Everything else the parent doc gated on — autoencoder architecture, conditioner identity, the
inpainting mechanism, the sampler match, checkpoint format and weight-key layout, the adapter family
— **is answered above from source** and no longer blocks design work.
