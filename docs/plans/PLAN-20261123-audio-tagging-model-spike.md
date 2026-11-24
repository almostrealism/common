---
status: completed
created: 2026-11-23
closed: 2026-11-23
tracker: [036acf9b-c40f-4dbc-a7ad-71e34f564ef2]
---

# Native zero-shot audio-tagging model: feasibility, memory and licence spike

**Tracker task:** `036acf9b-c40f-4dbc-a7ad-71e34f564ef2` — *Tagging-model
feasibility, memory and licence spike for native zero-shot audio tagging*
**Release:** Common 0.78

## Motivation

Common has no audio classifier, no tagging head, and no CLAP-style audio–text
model today. A zero-shot audio–text (CLAP-style) model is a general framework
capability: it lets an application score audio against an arbitrary, *versioned*
text vocabulary it defines, with no retraining when that vocabulary changes.
Because every candidate is new `engine/ml` work, the model's shape must be
settled by evidence before the full native port is committed.

The owner's open questions are concrete and currently unanswered:

1. **Checkpoint identity and cost** — is `laion/clap2-large-audioxl-s1B` a real
   checkpoint and worth its cost, versus a music-tuned LAION checkpoint
   (`larger_clap_music` / `music_audioset`), versus Microsoft CLAP, versus a
   fallback supervised head on the SAME-S embedding the engine already loads?
2. **Device-memory headroom** — how much device memory do the audio and text
   towers need *beside* the already-loaded SAME-S encoder? This ties directly to
   the memory cap the in-flight buffer-reuse work is settling.
3. **Mac inference cost** — what is the per-clip inference cost on Metal?
4. **Usefulness thresholds** — what tag score and matching-sample count make a
   suggestion useful, and what vocabulary size (within the ~100–200 band) is
   appropriate?

This is an **investigation / spike**: the deliverable is a short native
prototype plus a *written findings document*, not a production model.

## Scope

This task is a spike. Concretely it will produce:

- **A short native prototype under `engine/ml` (`org.almostrealism.ml`)** that
  ports one small CLAP audio+text tower far enough to score a handful of real
  musical samples (one-shots, loops, stems) against ~20 tags. "Far enough" means
  enough of the two towers and the shared projection to produce a cosine
  similarity between an audio clip and a text tag — not a trained, optimised, or
  production-complete model.
- **A written findings document** (durable location — not `docs/plans/`, since
  plans are temporary; see "Where the findings land") recording:
  - tagging accuracy on **musical** samples specifically (one-shots, loops,
    stems), with the ~20-tag probe set and the handful of samples used;
  - Mac (Metal) inference cost per clip;
  - device memory the two towers require **beside** the already-loaded SAME-S
    encoder;
  - confirmation that the chosen checkpoint's licence permits **commercial
    desktop distribution**;
  - a concrete recommendation: final checkpoint, vocabulary size within the
    ~100–200 band, and provisional suggestion thresholds (tag score + matching
    sample count).
- **A follow-up recommendation** on whether the full native port is worth
  committing to, and in which of the candidate shapes.

**Out of scope** (name as dependencies / follow-ups, do not widen into this
task):

- The full, production-quality native CLAP port (training, optimisation, the
  complete vocabulary, the application-facing tagging API). That is the work
  this spike exists to de-risk and is filed separately once the shape is chosen.
- Any change to the SAME-S encoder, the buffer-reuse memory-cap work
  or the existing SA3 pipeline. The spike *measures against* the
  already-loaded SAME-S encoder; it does not modify it.
- New Maven modules. The prototype lives inside the existing `engine/ml`
  module's `org.almostrealism.ml` package. If the work appears to need a new
  module, stop and report it — do not create one.

## Approach

### Existing primitives the prototype builds on

The investigation confirmed what `engine/ml` already provides and where the
gaps are:

- `AttentionFeatures` (`engine/ml/.../ml/AttentionFeatures.java`) — generalized
  multi-head attention with GQA, QK-Norm, cross-attention and RoPE wiring. Any
  new transformer tower reuses these methods rather than adding model-specific
  copies.
- `TransformerConfig` (`engine/ml/.../ml/TransformerConfig.java`) — carries
  `dim`, `hiddenDim`, `layerCount`, `headCount`, `kvHeadCount`, `vocabSize`,
  `seqLen`. A CLAP tower config extends this pattern.
- `RotationFeatures` (`engine/ml/.../ml/RotationFeatures.java`) — RoPE
  (`computeRopeFreqs`, `ropeRotation`, `applyRotaryPositionEmbedding`).
- `StateDictionary` (`engine/ml/.../ml/StateDictionary.java`) — SafeTensors and
  protobuf weight loading, lazy materialisation, `group(prefix)` hierarchical
  views, `require(key)` with nearest-key diagnostics. Weight extraction and
  remapping happen in `engine/ml/scripts/safetensors_extractor.py` (generalized
  `load_safetensors` / `remap` / `write_state_dictionary` core).
- `T5GemmaEncoder` (`engine/ml/.../ml/t5gemma/T5GemmaEncoder.java`) +
  `SentencePieceBPETokenizer` (`engine/ml/.../ml/tokenization/`) — an existing
  bidirectional text encoder and its tokenizer, wired from
  `/pdsl/t5gemma_encoder.pdsl`.
- `ByteLevelBPETokenizer` (`engine/ml/.../ml/tokenization/ByteLevelBPETokenizer.java`,
  with `ByteLevelEncoder` and `RegexPreTokenizer`) — the abstract byte-level BPE
  pipeline used for GPT-2-family vocabularies. RoBERTa and GPT-2, the usual CLAP
  text towers, both use byte-level BPE, so the tokenizer side of the text tower
  is a new vocabulary/merges subclass, not a new tokenizer algorithm.
- `TemporalFeatures` (`compute/time/.../time/TemporalFeatures.java`, module
  `ar-time`) — `stft(fftSize, hopSize, windowType, signal, ...)` with a Hann
  window and `melFilterBank(fftSize, sampleRate, numMelBands, fMin, fMax,
  powerSpectrum)`, covered by `STFTComputationTest` and `MelFilterBankTest` in
  `engine/utils`. `ar-ml` reaches `ar-time` transitively through `ar-utils`
  (those two tests compile in `engine/utils`), although nothing in `engine/ml`
  uses it yet. These are the building blocks of a log-mel front end; whether
  their filter shape (HTK vs Slaney mel scale, normalisation) and framing match
  a given CLAP checkpoint's preprocessing has not been checked and must be
  verified against the reference preprocessing.
- Model-assembly reference: `Qwen3` / `Llama2` / `StableAudio3` all follow
  "load `StateDictionary` → build layers in a loop → compile", the pattern the
  prototype's loader follows.
- The **already-loaded SAME-S encoder** is `stabilityai/SAME-S`, the SA3 audio
  autoencoder (`SAMEAutoEncoder`), exercised by `SAMEResamplingParityTest` and
  the `dump_same_references.py` / `extract_sa3_weights.py` scripts documented in
  `engine/ml/scripts/README.md`. This is the tower the two CLAP towers must sit
  *beside* in device memory. Note that `SAMEAutoEncoder` is an *autoencoder*:
  its encoder emits a `[batch, latentDim, samples / ratio]` reconstruction
  latent, not a pooled semantic embedding.
- **Existing per-sample embedding plumbing** (`engine/audio`, `studio/compose`) —
  what a tagging model would plug into, and what shape (B) would consume:
  - `WaveDataFeatureProvider` (`engine/audio/.../audio/data/`) computes a
    feature tensor per sample and names its model through
    `getModelIdentifier()`; `WaveDetails` keeps one `WaveFeatures` per model
    identifier, so features from a new model sit beside the old ones (see
    "Feature data model provenance" in `engine/audio/docs/AUDIO_LIBRARY.md`).
  - `AutoEncoderFeatureProvider` (`studio/compose/.../studio/ml/`) is the
    existing provider that encodes a sample through an `AutoEncoder` and stores
    the transposed latent `[frames, bins]` as the sample's features.
  - `WaveDetails.meanPooledEmbedding()` and `AudioLibrary`'s embedding-store
    path mean-pool the active model's features into one vector per sample for
    nearest-neighbour search.

  So "the SAME-S embedding" in shape (B) concretely means the mean-pooled
  autoencoder latent this plumbing already produces, when the application
  loads SAME-S as its autoencoder. A CLAP audio tower, if ported, would most
  naturally be exposed as another `WaveDataFeatureProvider` with its own model
  identifier rather than a parallel feature store.

### The architectural gap the spike must resolve

The task names the existing text/attention primitives as the base, but a real
CLAP checkpoint does **not** map onto them cleanly, and surfacing that honestly
is part of the spike:

- CLAP's **audio tower** is typically HTSAT — a Swin-style windowed-attention
  transformer over a **log-mel spectrogram front end** — or a PANN CNN. The
  framework has STFT and mel-filterbank building blocks (`TemporalFeatures`,
  above) but no assembled log-mel front end matched to any CLAP checkpoint's
  preprocessing, and no Swin attention: the "sliding window" attention
  `AttentionFeatures` lists for Gemma-family models is 1-D causal windowing over
  a token sequence, not Swin's 2-D shifted-window attention with patch merging
  and relative position bias.
- CLAP's **text tower** is typically RoBERTa or GPT-2, **not** T5Gemma /
  SentencePiece. The tokenizer algorithm (byte-level BPE) already exists in
  `ByteLevelBPETokenizer`; the encoder stack (learned absolute position
  embeddings, post-LayerNorm for RoBERTa, GELU MLP) does not match the
  RoPE/RMSNorm-oriented models `engine/ml` currently assembles and must be
  checked against what `AttentionFeatures` and `LayerFeatures` can express.

The spike therefore evaluates two distinct shapes and the prototype is built for
whichever the evidence favours:

- **(A) Port a small real CLAP tower natively** — port one checkpoint's audio
  tower (mel front end + its attention stack) and text tower far enough to score
  clips against tags, reusing `AttentionFeatures`/`StateDictionary` where the
  architecture allows and noting every primitive that has to be added.
- **(B) Fallback supervised head on the SAME-S embedding** — reuse the
  already-loaded SAME-S audio embedding and learn/attach a lightweight head that
  scores against a fixed tag vocabulary. This reuses far more existing
  infrastructure and adds the least device memory, at the cost of the
  "arbitrary, retraining-free vocabulary" property that motivates CLAP.
  Unlike (A), (B) needs **labelled training data** for the fixed vocabulary and
  a training run, which must go through `ModelOptimizer` (it owns training
  loops). It also assumes the SAME-S reconstruction latent carries enough
  semantic information for tags to be separable, which nobody has measured
  (see open question 6).

The findings document states which shape the evidence recommends and why.

### Candidate checkpoints and the licence question

The spike evaluates and compares:

- `laion/clap2-large-audioxl-s1B` — **first verify it is a real, downloadable
  checkpoint**; the task flags this as uncertain.
- LAION music-tuned checkpoints — `larger_clap_music` / `music_audioset`.
- Microsoft CLAP (MSCLAP).
- The SAME-S supervised-head fallback (shape B above).

For each, the commercial-desktop-distribution licence is confirmed **from the
checkpoint's own licence**, cited in the findings — not assumed. A candidate
whose licence does not clear commercial desktop distribution is disqualified
regardless of its accuracy.

### Measurement method

- **Accuracy:** score the handful of real musical samples (one-shots, loops,
  stems) against the ~20-tag probe set; report per-sample top tags and whether
  they are musically sensible. This is a qualitative/small-N spike measurement,
  not a benchmark — state the sample set and tags explicitly so a reviewer can
  judge representativeness.
- **Mac inference cost:** measure per-clip wall-clock on Metal
  (`AR_HARDWARE_DRIVER=mtl`), warm-up excluded.
- **Device memory beside SAME-S:** load the SAME-S encoder, then load the two
  CLAP towers, and report the incremental device memory the towers add —
  expressed against the memory cap the in-flight buffer-reuse work is
  settling, and the `AR_HARDWARE_MEMORY_SCALE` tier it implies (the tiers are
  tabulated in `base/hardware/README.md`). Read the figure from
  `MetalMemoryProvider.getAllocatedMemory()` after loading SAME-S, after loading
  the weights of each tower, and at the peak of one scoring pass, so both the
  resident-weight cost and the activation peak are reported. The absolute
  figures do not depend on the buffer-reuse work; only their comparison against
  its cap does. (Released-buffer reuse through `AllocationCache` is currently
  adopted only by `CudaMemoryProvider`, so on Metal nothing is held back from
  `getAllocatedMemory()` by a reuse cache today.)
- **Thresholds:** from the accuracy probe, propose a provisional minimum tag
  score and a minimum matching-sample count that together make a useful
  suggestion, and a vocabulary size within the ~100–200 band.

### Where the prototype and findings land

- Prototype Java under `engine/ml/src/main/java/org/almostrealism/ml/` (a new
  sub-package if warranted, e.g. `org.almostrealism.ml.tagging`; the existing
  `org.almostrealism.ml.audio` package already holds `StableAudio3` and
  `SAMEAutoEncoder` and is the alternative), with weight extraction added to
  `engine/ml/scripts/` following the `safetensors_extractor.py` /
  `extract_sa3_weights.py` pattern.
- A synthetic-weight test under `engine/ml/src/test/java/...` extending
  `TestSuiteBase` proves the tower compiles and produces finite scores in CI;
  any real-weight accuracy/cost/memory run looks for its weights through a
  system property / environment variable (checked in that order, as
  `System.getProperty("AR_X", System.getenv("AR_X"))`) and calls
  `requireLocalResource(description, "AR_X", available)` from `TestFeatures`,
  exactly as `SAMEResamplingParityTest` (`AR_SAME_WEIGHTS`) and
  `StableAudio3ConditionerParityTest` (`AR_SA3_CONDITIONER_WEIGHTS`,
  `AR_T5GEMMA_WEIGHTS`) do. That call fails on a host that declares the
  property but lacks the weights, and is reported as *skipped* elsewhere — the
  honest-skip shape `docs/internals/test-skip-gating-prevention.md` describes.
  Those tests do not use `skipLongTests`; add it only if the real-weight run is
  also long-running.
- The written findings document is **durable** output and must live outside
  `docs/plans/` (plans are temporary and get swept). An appropriate home is a
  module-level or internals doc — e.g.
  `engine/ml/docs/audio-tagging-feasibility.md` or
  `docs/internals/audio-tagging-feasibility.md` — decided at implementation time
  per `docs/CLAUDE.md`. (See open question 1.)

## Success criteria

A reviewer can confirm the task is done when the findings document records, each
backed by evidence from the prototype run:

1. **Accuracy** on musical samples (one-shots, loops, stems) against the ~20-tag
   probe set, with the sample set and tags listed.
2. **Device memory** the two towers add **beside** the already-loaded SAME-S
   encoder, related to the in-flight buffer-reuse memory cap.
3. **Mac (Metal) inference cost** per clip.
4. A **cleared commercial-desktop-distribution licence** for the recommended
   checkpoint, cited from the checkpoint's own licence.
5. A **concrete recommendation**: final checkpoint (or the SAME-S fallback),
   vocabulary size within the ~100–200 band, and provisional suggestion
   thresholds (tag score + matching-sample count).

And in the code:

6. The prototype compiles and a **synthetic-weight test** (extending
   `TestSuiteBase`) passes in CI, proving the tower builds and produces finite
   scores without real weights. Run it narrowly via
   `mcp__ar-test-runner__start_test_run module:"engine/ml"
   test_classes:["<PrototypeTest>"]`.
7. Any real-weight run is **gated** through `requireLocalResource` on a
   property/env-var weights path (plus `skipLongTests` if it is long-running),
   so CI does not require gated checkpoints and reports the run as skipped,
   not passed, where the weights are absent.

Because this is a spike, the bar is "the findings answer the four open questions
with evidence and a clear recommendation", not "a production tagging model
exists".

## Dependencies

- **Metal / macOS node.** Mac inference cost and device-memory-beside-SAME-S are
  Metal measurements, so the implementation must run on a macOS FlowTree node.
  Recorded in `docs/plans/task-20261123-190324-workstream.yaml`
  (`requiredLabels: platform: macos`).
- **Gated Hugging Face checkpoints.** SAME-S and the CLAP candidates are gated
  downloads needing an HF token with access (see `engine/ml/scripts/README.md`).
  The implementation session needs that token available, and network access to
  fetch at least the safetensors headers for the licence/key verification.
- **The in-flight buffer-reuse memory-cap work.** The device-memory finding is
  expressed relative to the cap that work is settling; the spike reads from it
  but does not modify it. That work is not identified anywhere in this
  repository's `docs/` (the only buffer-reuse mechanism in the tree,
  `AllocationCache`, is used only by the CUDA provider), so the person approving
  this plan should name it — see open question 7.
- **Labelled musical data (shape B only).** A supervised head needs labelled
  samples for every tag in the vocabulary; nothing in the repository provides
  them.

## Open questions

1. **Findings-doc home.** The findings are durable and must not live in
   `docs/plans/`. Which durable location — `engine/ml/docs/…`,
   `docs/internals/…`, or the `engine/ml/README.md` — does the owner prefer?
   (Default proposed: `engine/ml/docs/audio-tagging-feasibility.md`. No
   `engine/ml/docs/` directory exists yet — `engine/audio/docs/` is the only
   engine module with one — so the default creates it; `engine/ml/README.md`
   or `docs/internals/` avoids that.)
2. **Port-real-tower (A) vs SAME-S fallback (B) as the primary deliverable.**
   The task lists the native CLAP tower first but also names the SAME-S
   supervised head as a candidate. If porting a real CLAP audio tower requires
   substantial new primitives (a checkpoint-matched log-mel front end assembled
   from `TemporalFeatures`, Swin shifted-window attention with patch merging,
   a RoBERTa/GPT-2 encoder stack and vocabulary), is the spike expected to add
   enough of those to score clips, or to pivot to the SAME-S fallback and report
   the port cost as a finding? This materially affects the size of the
   prototype. A cheaper middle path worth considering: verify that the chosen
   checkpoint's projected audio and text embeddings can be reproduced natively
   for the ~20 tags and handful of samples using reference activations dumped
   from the Python model (the pattern `dump_same_references.py` and
   `dump_t5gemma_reference.py` already follow), porting the text tower first
   because tag embeddings can be computed once per vocabulary version.
3. **Is `laion/clap2-large-audioxl-s1B` real?** The task itself flags this as
   unverified. If it does not exist, the spike proceeds with the music-tuned
   LAION / Microsoft CLAP candidates and records the non-existence as a finding.
4. **Sample set provenance.** Which real musical samples (one-shots, loops,
   stems) should the accuracy probe use, and from where, so the measurement is
   representative and reproducible without shipping copyrighted audio into the
   repo?
5. **Follow-up filing.** If the spike recommends proceeding, the full native
   port should be filed as a new tracker task. (No follow-up tasks were filed
   during planning; the port task is created when the spike's recommendation is
   approved.)
6. **Is shape (B) in scope at all?** The out-of-scope list excludes training,
   but (B) is a trained head: it needs labelled data and a `ModelOptimizer`
   run. It also rests on the unmeasured assumption that a reconstruction
   autoencoder latent (SAME-S), mean-pooled, separates musical tags. If (B)
   stays a candidate, the spike should at minimum report a linear-probe result
   on whatever labelled set is available; otherwise (B) should be recorded as
   "not evaluated" rather than recommended on infrastructure-reuse grounds
   alone.
7. **Which buffer-reuse memory-cap work?** The plan measures against "the cap
   the in-flight buffer-reuse work is settling", but no document or code in this
   repository names that cap for Metal. The approver should identify the work
   (or the cap value) so success criterion 2 is checkable; until then the
   findings should report absolute bytes and the `AR_HARDWARE_MEMORY_SCALE`
   tier.
8. **Origin of the ~100–200 vocabulary band and of the thresholds' purpose.**
   The band comes from the task, not from any measurement; the spike can only
   propose a size within it. "Matching-sample count" presumes a library-level
   suggestion feature (a tag is suggested when at least N library samples score
   above a threshold) that does not exist in the framework; the approver should
   confirm that is the intended use so the thresholds are framed correctly.
9. **Licence of weights vs. training data.** A checkpoint's own licence may
   permit commercial use while its training data (e.g. AudioSet-derived or
   scraped audio–caption corpora) carries separate terms. The findings should
   state both, and say which one the commercial-distribution decision rests
   on.

## Estimated complexity

**Large.** Even as a spike, this spans a native transformer-tower prototype,
weight extraction scripting, a mel/audio front end or a SAME-S head, Metal
measurement, and a multi-checkpoint licence review. The architectural-gap open
question (2) could swing the prototype's size significantly; resolving it before
implementation is the single most useful thing a reviewer can do.
