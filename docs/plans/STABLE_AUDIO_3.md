# Stable Audio 3 Open-Weight Model Integration

**Branch:** `feature/stable-audio-3-research`
**Tracker task:** `203194a7-3659-4f74-bfc9-d6461d7550b2`
**Release:** Common 0.75
**Paper:** arXiv:2605.17991
**Status:** Research — Implementation not started

---

## 1. Executive Summary

Stability AI released **Stable Audio 3.0** on May 20, 2026. Three of the four model variants
ship as open weights on Hugging Face. The fourth (Large, 2.7B) is API/enterprise-only and is
**out of scope** for this document.

| Variant     | Params | Weights | Use case                                      | Max duration |
|-------------|--------|---------|-----------------------------------------------|--------------|
| Small SFX   | 459M   | Open    | Sound effects, CPU-on-device                  | ~30 s        |
| Small       | 459M   | Open    | Music composition, CPU-on-device              | ~2 min       |
| Medium      | 1.4B   | Open    | Full compositions, M4 MacBook / H200          | 6 min 20 sec |
| Large       | 2.7B   | API only | **Out of scope**                             | —            |

Architecture: **semantic-acoustic autoencoder + latent diffusion**. Supports text-to-audio
generation, audio inpainting, multi-segment editing, track extension, and LoRA fine-tuning.
Training data is fully licensed and Creative Commons. License: Stability AI Community License
(free below $1M revenue; Enterprise above).

The most important fact for integration planning: **this codebase already has substantial
Stable Audio Open infrastructure** that SA3 builds on. The `OobleckAutoEncoder`,
`DiffusionTransformer`, `DiffusionSampler`, `LoRADiffusionTransformer`, `OnnxAutoEncoder`,
`OnnxDiffusionModel`, and `AudioAttentionConditioner` abstractions in `engine/ml` and
`extern/ml-onnx` are the direct ancestors of the SA3 pipeline. Integration work is
incremental, not greenfield.

---

## 2. Reference Implementation

### 2.1 Official Repositories

- **Inference/training:** The canonical Stability AI open-source repo is
  `https://github.com/Stability-AI/stable-audio-tools` — this is the same repo that hosted
  Stable Audio Open, and SA3 is expected to follow the same structure (verify: check for SA3
  model config `.json` files added after May 20, 2026).
- **HuggingFace weights (expected names — verify before use):**
  - `stabilityai/stable-audio-3-small-sfx`
  - `stabilityai/stable-audio-3-small`
  - `stabilityai/stable-audio-3-medium`
- **Paper:** arXiv:2605.17991 — read this before implementing anything. Architecture details,
  autoencoder design, and conditioning mechanism differences from SA Open are specified there.

### 2.2 Runtime Stack (SA Open reference — verify SA3 deltas)

The existing Python extraction scripts in this repo (`engine/ml/scripts/`) target PyTorch
and the `safetensors` or `.ckpt` checkpoint formats used by `stable-audio-tools`. SA Open
checkpoints were `.ckpt`; SA3 may use `.safetensors` (the industry trend and preferred format
for HuggingFace uploads). Verify the weight format for each variant before writing extraction
scripts.

Key Python dependencies already used in this repo's extraction scripts:
- `torch` (float/bfloat16 tensor ops and conversion)
- `safetensors` (preferred) or `pickle`-based `.ckpt` loading
- `grpcio-tools` for protobuf generation (see `engine/ml/scripts/generate_protobuf_python.sh`)
- `numpy` for tensor conversion

### 2.3 Existing ONNX Exports

No official ONNX exports for SA3 are known at time of writing. Community ONNX/CoreML/GGUF
ports should be checked on HuggingFace under the `stabilityai/stable-audio-3-*` repos once
they are confirmed. The `extern/ml-onnx` module already has the `OnnxAutoEncoder` and
`OnnxDiffusionModel` wrappers for the SA Open ONNX pipeline — these can serve SA3 ONNX
exports without code changes if the tensor interface is compatible.

### 2.4 Minimal Inference API Surface (SA Open pattern)

From the existing codebase, a text-to-audio call follows this pattern:

```java
// 1. Load weights and build model
StateDictionary weights = new StateDictionary(weightsDir);
AutoEncoder vae = new OobleckAutoEncoder(weights, batchSize, seqLength);
DiffusionModel dit = new DiffusionTransformer(/* config from weights */);

// 2. Condition on text and duration
AudioAttentionConditioner conditioner = /* OnnxAudioConditioner or native */;
long[] tokenIds = tokenizer.encode("upbeat electronic music");
ConditionerOutput cond = conditioner.runConditioners(tokenIds, 30.0 /* seconds */);

// 3. Sample latents
DiffusionSampler sampler = new DiffusionSampler(dit, strategy, numSteps, latentShape);
PackedCollection latents = sampler.sample(seed,
    cond.getCrossAttentionInput(), cond.getGlobalCond());

// 4. Decode to audio
PackedCollection audio = vae.decode(latents).evaluate();
// audio shape: (2, sampleRate * durationSeconds) — stereo, 44.1 kHz
```

The SA3 API surface follows the same four-step structure. Changes (to verify from paper) are
likely inside the `AutoEncoder` implementation and the `DiffusionTransformer` configuration,
not in the `DiffusionSampler` or `AudioAttentionConditioner` contracts.

---

## 3. Architecture and Weights

### 3.1 Autoencoder: Semantic-Acoustic vs. Oobleck

SA Open used the **Oobleck** autoencoder: Conv1d input → 5 EncoderBlocks (Snake activation +
strided Conv1d for downsampling) → linear bottleneck → 5 DecoderBlocks (Snake + transposed
Conv1d for upsampling) → Conv1d output. Total compression: 524,288 samples → 256 latent
frames (ratio ≈ 2048×). Latent dimension: 64 channels. Sample rate: 44,100 Hz, stereo.

SA3 introduces a **semantic-acoustic** autoencoder. Per the announcement, this combines a
semantic (content-level) representation with an acoustic (fine-grained) representation in the
latent space. The exact architecture change — whether it is a modified Oobleck, a two-stage
encoder, or an entirely different design — must be read from arXiv:2605.17991 before
implementing. Key questions to answer from the paper:

- Does the latent shape change (dimensions, frame rate)?
- Is there a separate semantic encoder that produces conditioning embeddings, or is it a
  single unified encoder with a differently-structured bottleneck?
- Are the Snake activations, strided Conv1d structure, and weight normalization preserved?

**Working assumption (update after reading paper):** the acoustic decoder interface matches
SA Open — output is 44.1 kHz stereo PCM — so `WaveData` and `WaveOutput` infrastructure
in `engine/audio` requires no changes.

The existing reference implementation code and extraction script:
- `engine/ml/src/main/java/org/almostrealism/ml/audio/OobleckAutoEncoder.java`
- `engine/ml/src/main/java/org/almostrealism/ml/audio/OobleckEncoder.java`
- `engine/ml/src/main/java/org/almostrealism/ml/audio/OobleckDecoder.java`
- `engine/ml/src/main/java/org/almostrealism/ml/audio/VAEBottleneck.java`
- `engine/ml/scripts/extract_stable_audio_autoencoder.py`

### 3.2 Diffusion Transformer (DiT)

SA Open used a DiT with configurable depth/heads, prepended timestep and global conditioning
tokens, optional cross-attention for text, and QK-norm. The existing
`DiffusionTransformer.java` constructor parameters:

```
ioChannels, embedDim, depth, numHeads, patchSize,
condTokenDim, globalCondDim, diffusionObjective, audioSeqLen, condSeqLen
```

SA3 Small (459M) is likely similar depth/width to SA Open's DiT. SA3 Medium (1.4B) is a
larger variant — wider embedding dimension or deeper stack. Exact hyperparameters must be
read from the model config JSONs in the HuggingFace repos or the paper. The existing
`DiffusionTransformer.java` is parameterized and should accommodate both variants without
code changes; only the config values need to change.

### 3.3 Text Conditioning

SA Open used a **T5** text encoder (128-token max) paired with a duration/seconds scalar
fed as a global conditioning vector. The existing `OnnxAudioConditioner` implements this:

```java
// extern/ml-onnx/src/main/java/org/almostrealism/ml/audio/OnnxAudioConditioner.java
ConditionerOutput runConditioners(long[] tokenIds, double durationSeconds)
```

SA3 may introduce:
- A **CLAP** audio-language encoder alongside T5 (for audio-conditioned generation)
- Extended duration/timing control beyond a scalar seconds value (the announcement mentions
  "second-level control")
- Audio inpainting conditioning (masked audio as an additional input)

These changes affect the `AudioAttentionConditioner` interface and potentially require a new
implementation class. The interface itself is defined in:
`engine/ml/src/main/java/org/almostrealism/ml/audio/AudioAttentionConditioner.java`

### 3.4 Variable-Length Output

SA Open was fixed-length (11 seconds for the standard checkpoint). SA3 Medium supports up to
6 min 20 sec. Variable-length generation requires:
- A latent shape that is not hardcoded at construction time
- A `DiffusionSampler` that can accept a dynamic `latentShape` parameter at inference time
- The `OobleckAutoEncoder.getMaximumDuration()` method to reflect the new limit

The existing `DiffusionSampler` already accepts `latentShape` as a constructor parameter.
Whether it can be changed per call (vs. per-instance) needs to be verified from
`DiffusionSampler.java:173–207`.

### 3.5 Weight File Sizes (Estimates — Verify on HuggingFace)

| Variant   | Params | Estimated FP32 size | Estimated BF16 size |
|-----------|--------|---------------------|---------------------|
| Small SFX | 459M   | ~1.8 GB             | ~0.9 GB             |
| Small     | 459M   | ~1.8 GB             | ~0.9 GB             |
| Medium    | 1.4B   | ~5.5 GB             | ~2.8 GB             |

Actual file sizes depend on whether weights ship in FP32 or BF16 and whether autoencoder
and DiT weights are bundled in a single checkpoint or separate files. SA Open shipped a
single `.ckpt`; SA3 may split the autoencoder and DiT for modularity.

### 3.6 Output Format

SA Open output: 44,100 Hz, stereo (2 channels), float32 PCM. Latent: 64 channels, 256 frames
per 11 seconds. This is the format consumed by `WaveData` throughout `engine/audio`.
SA3 output format is expected to be identical at the audio boundary — verify from paper.

### 3.7 LoRA

The existing `LoRADiffusionTransformer.java` extends `DiffusionTransformer` with
`AdapterConfig.forAudioDiffusion()` (rank=8, attention projections only). SA3 ships with
official LoRA fine-tuning support. Whether the adapter format (key naming, rank conventions)
is compatible with the existing implementation needs verification from the SA3 training
scripts in `stable-audio-tools`.

---

## 4. Fit with Common

### 4.1 Existing Audio Diffusion Infrastructure

This is the most important section for implementation planning. The codebase already contains
a complete audio diffusion pipeline that was built for Stable Audio Open. SA3 integration
reuses the majority of it.

**Core model classes** (`engine/ml/src/main/java/org/almostrealism/ml/audio/`):

| Class | Role | SA3 status |
|---|---|---|
| `DiffusionModel.java` | Interface: `forward(x, t, crossAttnCond, globalCond)` | Reuse unchanged |
| `DiffusionTransformer.java` | Concrete DiT implementation | Reuse; configure with SA3 hyperparams |
| `DiffusionSampler.java` | Owns sampling loop; DDIM + IMG2IMG via `sampleFrom()` | Reuse; verify variable-length |
| `AutoEncoder.java` | Interface: `encode()`, `decode()`, `getSampleRate()` | Reuse unchanged |
| `OobleckAutoEncoder.java` | SA Open Oobleck VAE | Extend or replace if SA3 VAE differs |
| `OobleckEncoder.java` / `OobleckDecoder.java` | Conv1d + Snake + strided blocks | Extend if needed |
| `VAEBottleneck.java` | Linear bottleneck projection | Check against SA3 bottleneck |
| `AudioAttentionConditioner.java` | Interface: `runConditioners(tokenIds, duration)` | May need extension for CLAP/inpainting |
| `LoRADiffusionTransformer.java` | LoRA adapters for DiT | Verify adapter key compatibility |
| `DiffusionNoiseScheduler.java` | Cosine schedule | Verify SA3 schedule matches |
| `DDIMSamplingStrategy.java` | DDIM step | Reuse |
| `PingPongSamplingStrategy.java` | Ping-pong strategy | Reuse |

**ONNX wrappers** (`extern/ml-onnx/src/main/java/org/almostrealism/ml/audio/`):

| Class | Role | SA3 status |
|---|---|---|
| `OnnxAutoEncoder.java` | ONNX VAE encode/decode | Reuse if SA3 ONNX export matches tensor interface |
| `OnnxDiffusionModel.java` | ONNX DiT forward pass | Reuse unchanged |
| `OnnxAudioConditioner.java` | T5 conditioner via ONNX | May need extension for CLAP |

**Studio integration** (`studio/compose/src/main/java/org/almostrealism/studio/ml/`):

| Class | Role | SA3 status |
|---|---|---|
| `AudioDiffusionGenerator.java` | Wraps sampler + VAE decode; `generate(seed)` | Reuse; may need duration param |
| `AudioComposer.java` | Latent-space blending; `implements Factor<PackedCollection>` | Reuse |
| `AudioModulator.java` | Audio modulation patterns | Reuse |

**Weight management**:
- `StateDictionary.java` (`engine/ml`) — identical protobuf weight loading as all other models
- `engine/ml/scripts/extract_stable_audio_autoencoder.py` — existing SA Open extraction
  script; extend/fork for SA3 format differences

### 4.2 Where New Code Lands

Following the module placement rules from `CLAUDE.md`: model inference logic belongs in
`engine/ml`; composition and generation orchestration belong in `studio/compose`.

| New artifact | Location | Notes |
|---|---|---|
| `StableAudio3Config.java` | `engine/ml/src/main/java/org/almostrealism/ml/audio/` | Per-variant config (Small, SmallSfx, Medium) |
| `StableAudio3Conditioner.java` (if needed) | `engine/ml/src/main/java/org/almostrealism/ml/audio/` | If CLAP or inpainting needs a new conditioner |
| `StableAudio3Generator.java` (if needed) | `studio/compose/src/main/java/org/almostrealism/studio/ml/` | Only if `AudioDiffusionGenerator` can't accommodate variable-length without modification |
| `extract_stable_audio_3_weights.py` | `engine/ml/scripts/` | Fork of `extract_stable_audio_autoencoder.py`; handles SA3 checkpoint format |

If `DiffusionTransformer` and the existing `OobleckAutoEncoder` accommodate SA3 without
changes, no new Java classes are needed for Phase 1 — only configuration wiring and a
weight extraction script.

---

## 5. Dependency Surface

### 5.1 Maven Dependencies

No new Maven dependencies are expected for the core integration path. All required libraries
are already present:
- `ar-ml` — `DiffusionTransformer`, `DiffusionSampler`, `OobleckAutoEncoder`, `StateDictionary`
- `ar-ml-onnx` — `OnnxAutoEncoder`, `OnnxDiffusionModel`, `OnnxAudioConditioner`
- `ar-audio` — `WaveData`, `WaveOutput`, playback infrastructure
- `ar-compose` (studio) — `AudioDiffusionGenerator`, `AudioComposer`

If CLAP conditioning requires a new text/audio encoder model that cannot be loaded via ONNX
or the existing T5 infrastructure, a new dependency may be needed. Do not add it to any
`pom.xml` — follow the CLAUDE.md rule: write the code assuming the dependency exists, run
`mvn compile`, and report the failure to the project owner.

### 5.2 Inference Architecture: Native vs. ONNX vs. Python Sidecar

The codebase supports two inference paths for audio diffusion:

**Path A — Native (PDSL-compiled):** `OobleckAutoEncoder` + `DiffusionTransformer` load
weights via `StateDictionary` and compile to GPU kernels via the AR hardware layer. This is
the primary path for production use — it uses Metal/OpenCL acceleration and supports
automatic differentiation. Python is used only for weight extraction (offline, one-time).

**Path B — ONNX Runtime:** `OnnxAutoEncoder` + `OnnxDiffusionModel` + `OnnxAudioConditioner`
use ONNX Runtime for all inference. No GPU kernel compilation required; works wherever ONNX
Runtime is available. Lower peak performance but simpler deployment.

**No Python sidecar pattern exists in this codebase.** `ar-utils-http` provides REST client
infrastructure, but no existing model integration uses it for inference. Do not introduce a
Python sidecar for SA3.

**Recommendation:** Start with Path A (native) because:
1. All infrastructure already exists and is exercised by the SA Open pipeline.
2. Path A is required for LoRA fine-tuning (gradients are not available through ONNX Runtime).
3. The CPU-on-device requirement for Small/SmallSfx variants is satisfied by the AR CPU backend.
4. Path B (ONNX) can be added as an alternative deployment option in a later phase once
   Stability AI or the community publishes SA3 ONNX exports.

### 5.3 Weight Hosting Strategy

**On-demand download from Hugging Face** is the correct strategy — identical to all other
models in the codebase. There is no weight bundling and no vendor-supplied path.

Pattern:
1. User downloads weights from HuggingFace using the standard `huggingface-cli` workflow.
2. User runs the extraction script (`extract_stable_audio_3_weights.py`) to convert to
   `StateDictionary` protobuf format.
3. Java code loads the protobuf directory via `new StateDictionary(weightsDir)`.

This matches the pattern for SA Open, Qwen3, Moonbeam, and SkyTNT. No Java-side download
logic is needed.

---

## 6. Licensing

### 6.1 Stability AI Community License

Stable Audio 3 is released under the **Stability AI Community License Agreement**. Key
clauses relevant to Common:

- **Revenue threshold:** Free for individual, research, and commercial use below **$1M annual
  revenue**. Above this threshold, an Enterprise License is required. Common itself is an
  open-source framework; whether deploying Common in a product triggers the threshold depends
  on the revenue of the entity deploying it, not on the framework's own revenue. Document this
  clearly in usage notes.

- **Attribution:** Users of the weights must attribute Stability AI in any public-facing
  output (e.g., generated audio served to end users). Common does not generate audio
  autonomously — it provides a library. Attribution obligations fall on the application layer,
  not on Common itself. Include a note in `StableAudio3Config.java` javadoc.

- **Weight redistribution:** Redistribution of the model weights themselves is subject to the
  Community License restrictions. Common does **not** bundle weights — it only provides
  integration code — so this clause does not constrain what Common ships.

- **Derivative model clauses:** Fine-tuned models derived from SA3 weights (via LoRA or
  otherwise) are subject to the same license. LoRA adapters trained by Common users are their
  own responsibility.

- **Prohibited uses:** The license prohibits uses that violate applicable law, generate
  content that infringes third-party IP, or are used for harmful purposes. These constraints
  apply to end users, not to the framework.

**Conclusion:** Common can integrate SA3 without triggering Enterprise License requirements,
provided it does not bundle weights and clearly documents that applications using SA3 weights
for commercial purposes above $1M revenue must obtain an Enterprise License from Stability AI.

### 6.2 HuggingFace Redistribution

The weight files on HuggingFace are governed by the same Community License. Downloading for
use is permitted. Common integration code does not redistribute weights, so HuggingFace
redistribution terms do not apply to Common's codebase.

### 6.3 Action Item

Read the full text of the Stability AI Community License from the HuggingFace model card
before finalizing any redistribution-adjacent functionality (e.g., if a future feature
auto-downloads weights). Flag any clause that was added or changed after May 20, 2026.

---

## 7. Phased Delivery Plan

### Phase 1: Small/SFX Inference Only (Native Path, Fixed Duration)

**Scope:**
- Extract SA3 Small and Small SFX weights to `StateDictionary` protobuf format.
- Configure `DiffusionTransformer` with SA3-Small hyperparameters (embedDim, depth, numHeads,
  condTokenDim, etc. — read from paper or model config JSON).
- Verify `OobleckAutoEncoder` is compatible with SA3 autoencoder, or implement
  `StableAudio3AutoEncoder` if the semantic-acoustic design differs.
- Wire text conditioning via the existing `OnnxAudioConditioner` (T5) if SA3 uses the same
  T5 interface, or implement `StableAudio3Conditioner` if a new conditioner model is needed.
- Add `StableAudio3Config.java` with Small and SmallSfx variant configs.
- Validate: run a text-to-audio call for a 30-second clip, decode to stereo 44.1 kHz WAV,
  listen for plausible output.
- Update `extract_stable_audio_autoencoder.py` or create `extract_stable_audio_3_weights.py`
  to handle SA3 checkpoint format.

**Entry conditions:** arXiv:2605.17991 has been read; SA3 Small HuggingFace repo is confirmed;
weight files have been downloaded locally.

**Estimated effort:** 3–5 days. Most infrastructure exists; work is configuration + any
autoencoder delta + weight extraction script.

**Does not include:** variable-length output, Medium variant, audio inpainting, LoRA.

### Phase 2: Medium Variant + Variable-Length Output

**Scope:**
- Add `StableAudio3Config.Medium` variant (1.4B DiT config).
- Extend `DiffusionSampler` (or `AudioDiffusionGenerator`) to accept duration as a
  per-call parameter rather than a fixed construction-time latent shape, enabling variable-
  length output up to 6 min 20 sec.
- Verify memory requirements for Medium (expected: `AR_HARDWARE_MEMORY_SCALE=6` or higher).
- Test on M4 MacBook and H200 targets; confirm the advertised generation speed.
- Add `StableAudio3Generator.java` in `studio/compose` if `AudioDiffusionGenerator` needs
  structural changes (prefer modifying the existing class).

**Estimated effort:** 3–4 days.

**Depends on:** Phase 1 complete; paper read for variable-length latent shape details.

### Phase 3: LoRA Fine-Tuning + Audio Inpainting

**Scope:**
- Verify `LoRADiffusionTransformer` adapter key format matches SA3 training outputs.
- If keys differ, add a key-remapping step in `LoRADiffusionTransformer.loadAdaptersBundle()`.
- Implement audio inpainting: extend `DiffusionSampler.sampleFrom()` to accept a masked
  audio input (the existing `strength` parameter covers IMG2IMG; inpainting requires a mask
  tensor as an additional input).
- Multi-segment editing and track extension build on inpainting; plan separately once
  inpainting is working.

**Estimated effort:** 4–6 days. Inpainting requires careful mask handling in latent space.

**Depends on:** Phase 2 complete; SA3 LoRA adapter format confirmed from `stable-audio-tools`.

---

## 8. Weight Extraction Script Design

Following the pattern of `engine/ml/scripts/extract_stable_audio_autoencoder.py`:

**New file:** `engine/ml/scripts/extract_stable_audio_3_weights.py`

```
Usage:
  python extract_stable_audio_3_weights.py \
      <path/to/sa3/checkpoint_or_safetensors_dir> \
      <output_dir> \
      [--variant small|small-sfx|medium]

Output structure (parallel to SA Open):
  output_dir/
    weights/
      autoencoder_weights.pb      # encoder + decoder
      conditioner_weights.pb      # T5 or CLAP text encoder
      dit_weights.pb              # diffusion transformer (may be multiple files if >1GB)
    reference/
      test_input.bin
      encoder_output.bin
      decoder_output.bin
```

Key differences from the SA Open script:
1. SA3 may use `.safetensors` format — use `safetensors.safe_open()` instead of `torch.load()`.
2. Weight key prefix may differ from `pretransform.model.` — inspect the checkpoint first.
3. The semantic-acoustic VAE may have additional weight groups not present in Oobleck.
4. BF16 → FP32 conversion may be needed (SA Open was FP32; SA3 may ship BF16).

The protobuf serialization pattern is identical to the existing script and requires no changes
to the `collections_pb2` bindings.

---

## 9. Ordered Implementation Milestones

### Milestone 0: Architecture Research (Prerequisite)
1. Read arXiv:2605.17991 in full. Document: autoencoder architecture, latent shape,
   conditioning mechanism, DiT hyperparameters for all three variants, variable-length
   mechanism. Store findings in workstream memory before writing any code.
2. Confirm HuggingFace repo names and download SA3 Small weights locally.
3. Inspect checkpoint format (`.ckpt` vs `.safetensors`) and weight key structure.
4. Determine whether SA3 conditioner is still T5 or has changed.

### Milestone 1: Weight Extraction Script
1. Write `extract_stable_audio_3_weights.py` based on checkpoint inspection from M0.
2. Test: verify `.pb` output files load via `StateDictionary`.
3. Verify weight shapes match paper-specified architecture.

### Milestone 2: Config and Compatibility Check
1. Write `StableAudio3Config.java` with Small, SmallSfx, and Medium variant classes.
2. Instantiate `DiffusionTransformer` with SA3 Small config and random weights; run a
   synthetic forward pass to verify shape compatibility.
3. Check `OobleckAutoEncoder` against SA3 VAE — if architecturally identical, no changes
   needed; if different, design `StableAudio3AutoEncoder`.

### Milestone 3: Conditioner
1. If SA3 uses the same T5 conditioner format as SA Open, `OnnxAudioConditioner` is reused
   unchanged. Verify this with real weights.
2. If SA3 uses CLAP or a different conditioner, implement `StableAudio3Conditioner` in
   `engine/ml/src/main/java/org/almostrealism/ml/audio/` implementing
   `AudioAttentionConditioner`.

### Milestone 4: End-to-End Inference (Phase 1 acceptance)
1. Load real SA3 Small weights, run 30-second text-to-audio generation, decode to WAV.
2. Verify output: plausible audio (not silence, not noise) for a prompt like
   "upbeat jazz piano, 120 bpm".
3. Run `mcp__ar-build-validator__start_validation` — confirm no new code policy violations.
4. Run relevant unit tests (diffusion sampler, autoencoder encode/decode, conditioner output
   shape).

### Milestone 5: Variable-Length and Medium (Phase 2 acceptance)
1. Extend sampler / generator for per-call latent shape.
2. Load SA3 Medium weights, generate a 2-minute clip.
3. Verify memory usage stays within `AR_HARDWARE_MEMORY_SCALE=6` on test hardware.

### Milestone 6: LoRA (Phase 3 acceptance)
1. Verify adapter key compatibility with `LoRADiffusionTransformer`.
2. Test: load a community SA3 LoRA adapter and confirm it changes generation output.
3. Test: fine-tune a minimal adapter on a small dataset using `ModelOptimizer`.

---

## 10. Risks and Mitigations

### Risk 1: Semantic-Acoustic Autoencoder Incompatible with Oobleck
**Description:** SA3's "semantic-acoustic" autoencoder may be architecturally distinct from
the Oobleck design (different layer structure, different activation functions, two-stage
encoding). `OobleckAutoEncoder` may not accommodate it without significant changes.

**Mitigation:** Read arXiv:2605.17991 first (Milestone 0). If incompatible, implement a
new class (`StableAudio3AutoEncoder`) implementing the existing `AutoEncoder` interface —
no interface changes required. Extraction script will need updating regardless.

### Risk 2: Variable-Length Latent Shape Requires DiffusionSampler Refactor
**Description:** `DiffusionSampler` currently takes `latentShape` at construction time. If
SA3 Medium needs variable-length output per inference call, the sampler API must change.

**Mitigation:** Check `DiffusionSampler.java` lines 173–207 — the `sampleFrom()` overload
may already allow dynamic shape. If not, add an overloaded `sample(seed, cond, globalCond,
latentShape)` method without removing the existing constructor parameter.

### Risk 3: Conditioner Format Changed (T5 → CLAP or Multi-Modal)
**Description:** SA3's "second-level control" and inpainting support may require a richer
conditioning interface than `runConditioners(long[] tokenIds, double durationSeconds)`.

**Mitigation:** The `AudioAttentionConditioner` interface can be extended with a default
method or a new subinterface. The `ConditionerOutput` record can be extended with an
inpainting mask field. Existing callers are unaffected by additive interface changes.

### Risk 4: Small Variants Require CPU-Only Path
**Description:** SA3 Small is advertised as "CPU-only, on-device." The AR hardware layer
auto-selects a backend; no explicit CPU-only mode exists today. On M1/M2/M4 Macs the CPU
Metal backend may be sufficient. On servers without a GPU, the CPU BLAS backend is used.

**Mitigation:** Test Small generation on a CPU-only environment. If performance is
unacceptable, investigate ONNX Runtime (Path B) for the Small variants — ONNX Runtime has
CPU kernels optimized for transformer inference.

### Risk 5: Medium Model Memory Requirements
**Description:** SA3 Medium (1.4B params) at FP32 is ~5.5 GB. Combined with audio buffers
and intermediate activations, the full pipeline may require `AR_HARDWARE_MEMORY_SCALE=7`
(~32 GB). This may not be available in all test environments.

**Mitigation:** Test with BF16 weights (halves memory). If `HardwareException: Memory max
reached` occurs, document the minimum required memory scale in `StableAudio3Config`.

### Risk 6: Community License Revenue Threshold Ambiguity
**Description:** The $1M revenue threshold's definition of "annual revenue" in the context
of an open-source library used in multiple products may be ambiguous.

**Mitigation:** Include a prominent note in `StableAudio3Config.java` javadoc directing
commercial users to read the full license and contact Stability AI if their use case is near
the threshold. Common itself is not a commercial product and does not generate revenue.

### Risk 7: HuggingFace Repos Not Yet Public at Implementation Start
**Description:** As of research date (May 2026), the HuggingFace weight repos are assumed
open but have not been verified to be accessible. Gate all implementation work on confirming
repo access.

**Mitigation:** Milestone 0 is explicitly a prerequisites gate. No code is written until
weights are confirmed downloadable and architecture is read from the paper.

---

## 11. What We Reuse vs. Build New

### Fully Reused (no changes needed)

| Component | Location | Confidence |
|---|---|---|
| `DiffusionModel` interface | `engine/ml/.../audio/DiffusionModel.java` | High |
| `DiffusionTransformer` | `engine/ml/.../audio/DiffusionTransformer.java` | High (config only changes) |
| `DiffusionSampler` | `engine/ml/.../audio/DiffusionSampler.java` | High for fixed-length; verify for variable-length |
| `AutoEncoder` interface | `engine/ml/.../audio/AutoEncoder.java` | High |
| `AudioAttentionConditioner` interface | `engine/ml/.../audio/AudioAttentionConditioner.java` | Medium (may need extension for inpainting) |
| `OnnxAutoEncoder` | `extern/ml-onnx/.../audio/OnnxAutoEncoder.java` | Medium (depends on ONNX export availability) |
| `OnnxDiffusionModel` | `extern/ml-onnx/.../audio/OnnxDiffusionModel.java` | Medium |
| `LoRADiffusionTransformer` | `engine/ml/.../audio/LoRADiffusionTransformer.java` | Medium (key format TBD) |
| `AudioDiffusionGenerator` | `studio/compose/.../ml/AudioDiffusionGenerator.java` | Medium (duration param may need addition) |
| `AudioComposer` | `studio/compose/.../ml/AudioComposer.java` | High |
| `StateDictionary` | `engine/ml/.../StateDictionary.java` | High |
| `WaveData`, `WaveOutput` | `engine/audio` | High |

### Built New (this integration)

| Component | Location | Trigger |
|---|---|---|
| `StableAudio3Config.java` | `engine/ml/.../audio/` | New per-variant hyperparameter config |
| `extract_stable_audio_3_weights.py` | `engine/ml/scripts/` | SA3 checkpoint format may differ from SA Open |
| `StableAudio3AutoEncoder.java` | `engine/ml/.../audio/` | Only if semantic-acoustic VAE differs from Oobleck |
| `StableAudio3Conditioner.java` | `engine/ml/.../audio/` | Only if conditioner is not T5 |

The key principle: prefer configuring existing classes over creating new ones. New classes
are only created if an architectural incompatibility forces it.

---

## 12. Open Questions and Blockers

These must be resolved before writing any implementation code. Each is a hard blocker for
at least one phase.

1. **What does the semantic-acoustic autoencoder look like architecturally?**
   Read arXiv:2605.17991. Determine: is it Oobleck with a modified bottleneck, a two-stage
   system, or something else? Does the latent shape (64 channels × 256 frames per 11 sec)
   change for SA3?

2. **What are the DiT hyperparameters for each open variant?**
   Read the paper and/or the model config JSONs in the HuggingFace repos. Need: embedDim,
   depth, numHeads, patchSize, condTokenDim, globalCondDim for Small and Medium.

3. **Is the text conditioner still T5 with the same token-length and output dimension?**
   Or has SA3 changed to CLAP, T5-large, or a multi-modal conditioner?

4. **What is the "second-level control" mechanism?**
   Is it an additional conditioning vector, a different global_cond dimension, or a new
   conditioning path? This affects `AudioAttentionConditioner.ConditionerOutput`.

5. **What is the audio inpainting API at the model level?**
   Does inpainting require a binary mask tensor as an additional DiT input, or is it
   implemented at the latent sampling level (masked noise + strong guidance)?

6. **Are the HuggingFace repos publicly accessible?**
   Confirm repo names and access. Download weights as the first action before any code work.

7. **What checkpoint format does SA3 use — `.safetensors` or `.ckpt`?**
   And is the autoencoder weight prefix still `pretransform.model.` or changed?

8. **Are there LoRA adapter examples or community fine-tunes available?**
   Needed for Phase 3 verification. Without a real LoRA adapter to test, the adapter loading
   code cannot be validated.

9. **What memory scale is required for SA3 Medium on the target test hardware?**
   Profile with `mcp__ar-jmx__get_heap_summary` after loading weights.

10. **Does the Community License require any in-code attribution beyond documentation?**
    Read the full license text; check if any notice must appear in generated output or in the
    distribution.
