# Native zero-shot audio tagging: feasibility, memory and licence findings

This document records the evidence gathered to choose the model behind native zero-shot audio
tagging in `engine/ml`: a contrastive audio-text (CLAP-style) model that scores a clip against an
arbitrary, versioned list of text tags, so that the vocabulary can change without retraining.

**Summary.** The LAION CLAP2 family is real, Apache-2.0 licensed, and maps onto the framework's
existing transformer primitives. It is now ported natively (`org.almostrealism.ml.clap`) and
reproduces the reference model to a cosine similarity of at least 0.99999 on every probe clip and
tag, for the XXS (27M parameters), S (104M) and L (340M) sizes. On a 27-clip musical probe set
scored against 20 tags, the native port's accuracy is identical to the reference's, and the sizes
perform about the same; beside the SAME-S encoder, CLAP2-S adds 624 MB of device memory and tags
a 4 s clip in about 140 ms on Metal. Recommendation: build the full port on CLAP2,
starting from **CLAP2-S** for the shipped default (see "Recommendation"), with fixed windows of at
most 10 s, a vocabulary of about 150 tags written as captions, and scores calibrated per tag.

## What was built

| Piece | Where | What it does |
|---|---|---|
| Log-mel front end | `TemporalFeatures.logMelSpectrogram`, `reflectPad`, `htkMelFilterbank`, `WindowComputation.Type.HANN_PERIODIC` (`compute/time`) | A centered, reflect-padded STFT with a periodic Hann window, an HTK mel filterbank without area normalization, and `10 log10(max(x, 1e-10))`: the front end of most audio-language models, matching `torchaudio`. |
| STFT framing fix | `TemporalFeatures.stft` | Frames are now one indexed gather rather than a concatenation of per-frame subsets, whose kernel argument count exceeded the limit beyond about 27 frames. |
| Angle-table rotary embedding | `RotationFeatures.applyRotaryPositionEmbedding(input, angles)`, `rotaryAngles`; `sequence_rope` accepts a `[seq_len, n]` table | Expresses positions that are not the row index: CLAP2's axial (frequency x time) RoPE, and a register token left unrotated by a zero row. The inverse-frequency form now delegates to it. |
| Attention pooling | `/pdsl/attention_pool.pdsl` | Multi-head attention pooling with a learned latent query (timm `AttentionPoolLatent`), with and without query/key RMS norm. |
| The two towers | `/pdsl/clap2.pdsl`, `Clap2AudioEncoder`, `Clap2TextEncoder`, `Clap2Config`, `Clap2` | Audio: native front end, patching, NaFlex ViT with axial RoPE, SwiGLU and LayerScale, attention pool, projection head. Text: bidirectional transformer with RoPE and QK-norm, attention pool, projection. `Clap2` computes cosine similarities and logits. |
| Weight extraction | `engine/ml/scripts/extract_clap2_weights.py` | Converts any CLAP2 size; rearranges interleaved rotary pairs to split-half, swaps the gated halves of the audio feed-forward, folds the register token into the patch embedding. |
| Reference and measurement | `dump_clap2_reference.py`, `score_clap2_reference.py`, `Clap2TaggingSpikeTest` | Build the probe set and reference embeddings, report reference accuracy and threshold tables, and measure the native port's parity, accuracy, cost and memory. |

`Clap2SyntheticTest` proves without real weights that both towers build and give finite,
content-dependent embeddings, that padding never reaches a caption's embedding, that scoring is a
cosine, and that the patch layout and axial angles follow the reference. `Clap2TaggingSpikeTest`
is the real-weight run; it locates its inputs through `AR_CLAP2_WEIGHTS`, `AR_CLAP2_REFERENCES`
and `AR_SAME_WEIGHTS` and is reported as skipped where they are absent.

The text tower takes token ids. For the measurements on this page the GPT-2 (`r50k_base`)
byte-level BPE tokenizer was not ported: tag embeddings depend only on the vocabulary, so the
reference script produced the probe tags' ids. The tokenizer has since been ported:
`Clap2Tokenizer` reads the checkpoint's own tiktoken rank file through `TiktokenBPETokenizer`, a
`ByteLevelBPETokenizer`, with the `r50k_base` split pattern (`RegexPreTokenizer.r50k()`), and
`Clap2TextEncoder.encode(String)` embeds a caption given as text. See
[Text Tokenization](../../../docs/internals/text-tokenization.md).

## Checkpoint identity and the architecture question

| Candidate | Exists | Licence of the weights | Architecture | Native port |
|---|---|---|---|---|
| `laion/clap2-large-audioxl-s1B` (and XXS, XS, S, M) | Yes, released 2026-10, not gated, safetensors | Apache-2.0 | NaFlex ViT over log-mel patches (axial RoPE, RMSNorm, SwiGLU, LayerScale, register token, attention pool); bidirectional RoPE text transformer with QK-norm | **Done** for every size |
| `laion/larger_clap_music`, `laion/larger_clap_music_and_speech` | Yes, not gated | Apache-2.0 | HTSAT (Swin, shifted 2-D windows, patch merging) + RoBERTa | Not ported: needs Swin attention, patch merging, relative position bias and a post-norm RoBERTa stack, none of which exist |
| `microsoft/msclap` | Yes, `.pth` only | MS-PL (Hugging Face card) | HTSAT or CNN audio + GPT-2 text | Not evaluated |
| Supervised head on the SAME-S latent | — | — | Needs labelled musical data and a training run | Not evaluated (see below) |

The plan expected a real CLAP checkpoint not to map onto the existing primitives (HTSAT +
RoBERTa). CLAP2 removes that obstacle: both towers are pre-norm transformers of the kind
`engine/ml` already assembles, so the port reused `sequence_attention`, `glu_feed_forward`,
`rmsnorm`, `stack` and the checkpoint binding unchanged, and added only the angle-table rotary
embedding, attention pooling and the log-mel front end, each of them general.

The SAME-S fallback (shape B) was not evaluated. It needs labelled data for every tag and a
training run, rests on the unmeasured assumption that a reconstruction latent separates musical
tags, and gives up the retraining-free vocabulary. With a licence-clear zero-shot model running
natively, there is no remaining reason to pursue it.

## Probe set

27 clips scored against 20 tags. Every clip is one of:

- **One-shots (22):** 19 NSynth notes from the NSynth test split (CC BY 4.0), chosen by sorted
  file name between MIDI pitch 48 and 72: two notes of different instruments for each of bass,
  brass, guitar, keyboard, mallet, organ, reed, string and vocal, and one flute note (the script
  takes up to two notes per family, each of a distinct source or instrument, and finds only one
  flute note that qualifies); plus a kick, a snare and a closed hi-hat synthesized by the script.
- **Loops (3):** a two-bar drum loop at 120 BPM (synthesized), a keyboard arpeggio and a bass line
  sequenced from NSynth notes of one instrument.
- **Stems (2):** a string chord and a guitar line sequenced from NSynth notes. (A vocal stem is in
  the plan of the script but no NSynth vocal instrument has the needed pitches, so it is absent.)

Tags (written as captions, as the model card advises): kick drum, snare drum, hi-hat, drum beat,
bass, electric guitar, acoustic guitar, piano, organ, synth lead, synth pad, strings, brass, flute,
reed, mallet, vocal, bell, arpeggio, ambient. Each clip lists the tags that describe it (an NSynth
keyboard note accepts piano, synth lead, synth pad or organ; a guitar note either guitar tag).

The set is reproducible from public data and the script, and ships no audio. It is small and
leans on isolated synthetic or studio notes: the accuracy figures below are a qualitative check
and a comparison between models, not a benchmark.

## Accuracy

Top-1 counts the clips whose best tag describes them; top-3 the clips with a describing tag among
their best three. "Centered" subtracts each tag's mean score over the clips before ranking, the
calibration the CLAP2 model card recommends. Chance is 2/27 (top-1) and about 6/27 (top-3).

| Model | Raw top-1 | Raw top-3 | Centered top-1 | Centered top-3 | Source |
|---|---|---|---|---|---|
| CLAP2-XXS (27M) | 9/27 | 18/27 | 13/27 | 19/27 | native = reference |
| CLAP2-S (104M) | 8/27 | 16/27 | 15/27 | 21/27 | native = reference |
| CLAP2-M (153M) | 9/27 | 17/27 | 14/27 | 21/27 | reference |
| CLAP2-L (340M) | 7/27 | 19/27 | 12/27 | 21/27 | native = reference |
| `larger_clap_music_and_speech` | 11/27 | 18/27 | 13/27 | 21/27 | reference (`transformers`) |
| `larger_clap_music` | 2/27 | 7/27 | 2/27 | 5/27 | reference (`transformers`), see note |

By kind, for CLAP2-L centered: one-shots 10/22 top-1 and 17/22 top-3, loops 1/3 and 2/3, stems
1/2 and 2/2. The native towers reproduce the reference embeddings (worst audio cosine over the
clips: 0.9999997 for XXS, 0.9999983 for S, 0.9999963 for L; worst text cosine over the tags at
least 0.9999999999997 for all three), so the native rankings are the reference rankings. The
native log-mel spectrogram differs from the reference front end by 0.003 dB on average and at
most 0.12 dB (bins above -80 dB).

Observations:

- **Drums are recognized; sustained pitched notes are confused.** Kick, hi-hat and the drum loop
  rank correctly; single sustained notes of brass, reed, strings, flute and vocals are most often
  ranked "organ" first, with the correct family second or third. Calibration per tag (centering)
  removes much of that bias.
- **Size buys little here.** S has the best centered top-1 (15/27) and ties M and L on centered
  top-3 (21/27); L is not better than XXS on this probe, though the model card's benchmarks
  (MAEB-30: 46.2 for XXS, 51.8 for S, 53.6 for L) say it is better in general. The probe is too
  small to separate the sizes reliably.
- **`larger_clap_music` produced unusable embeddings** through `transformers` (every cosine near
  zero; median 0.007), so its row says nothing about the checkpoint itself. This was not
  investigated further; `larger_clap_music_and_speech`, loaded the same way, works.
- The first-generation HTSAT model ties CLAP2-L on centered top-3 and spreads its scores wider,
  but porting it needs Swin attention and RoBERTa, and it gives no accuracy advantage.

## Inference cost on Metal

Warm wall-clock per clip on an Apple-silicon Mac with `AR_HARDWARE_DRIVER` unset (Metal), the
mean of three encodes after one warm-up encode; the first encode of a new clip length also
compiles the tower for that length.

| Clip length (audio tokens) | CLAP2-XXS | CLAP2-S | CLAP2-L |
|---|---|---|---|
| 1 s (57) | 62 ms | 94 ms | 85 ms |
| 4 s (209) | 77 ms | 143 ms | 124-130 ms |
| 10 s (505) | 183 ms | 312 ms | 300-382 ms |
| 30 s (1505) | 545 ms | 1,178 ms | did not fit (see memory) |
| first encode of a length (compile) | 0.7-3.1 s | 3.2-4.0 s | 2.4-6.4 s |

Short clips cost about the same at every size: the time is dominated by the number of kernels
dispatched per encode, not by the arithmetic, so S is no faster than L below 10 s in these runs
(runs on a shared build machine vary by tens of milliseconds). A caption embeds in a few
milliseconds and is computed once per vocabulary version. Making short-clip inference cheaper is a
kernel-count question (fusing the per-layer operations), not a model-size question.

## Device memory beside SAME-S

Measured by `Clap2TaggingSpikeTest.deviceMemoryBesideSameEncoder` in a fresh JVM: the SAME-S
encoder is loaded, compiled and run once; then both CLAP2 towers are loaded, compiled and run once
on a 4 s clip, and the bytes `MetalMemoryProvider.getAllocatedMemory()` reports are read after
each step.

| Step | CLAP2-XXS | CLAP2-S | CLAP2-L |
|---|---|---|---|
| SAME-S encoder, compiled and run | 421 MB | 421 MB | 421 MB |
| + text tower, compiled and run (its weights) | +69 MB (76 MB) | +217 MB (248 MB) | +709 MB (694 MB) |
| + audio tower, compiled and run on 4 s (its weights) | +142 MB (33 MB) | +407 MB (167 MB) | +1,363 MB (666 MB) |
| + scoring | negligible | negligible | negligible |
| **CLAP2 total beside SAME-S** | **211 MB** | **624 MB** | **2.07 GB** |

(A tower can read slightly below its weights because buffers released by an earlier step are
reused; the figures are allocated bytes at each step, not a peak.)

The audio tower's memory grows with the clip length, because the framework keeps one output buffer
per layer and attention holds `[heads, tokens, tokens]` scores: for L the audio tower alone
reaches 4.2 GB at 10 s, and a 30 s clip does not fit under the 16 GB tracked ceiling of
`AR_HARDWARE_MEMORY_SCALE=6`; for S the audio tower takes 1.9 GB at 10 s and 10.3 GB at 30 s, and
for XXS 0.7 GB and 5.8 GB. Each distinct clip length is a
separately compiled tower with its own buffers, so the full port should compile one fixed window
length, not one per clip.

Against the cap: at `AR_HARDWARE_MEMORY_SCALE=6` (16 GB tracked) or 7 (32 GB), CLAP2-S or smaller
on 10 s windows fits comfortably beside SAME-S; CLAP2-L on 4 s windows fits but takes 2 GB. The
in-flight work to reuse layer output buffers during inference targets exactly the activation
term above, so it would shrink the long-window figures most; the weight term (33 MB to 666 MB
for the audio tower, 76 MB to 694 MB for the text tower, most of it the 50,260-row token
embedding) is unaffected by it. The text tower need not stay resident at all: once a vocabulary's
tag embeddings are computed and stored, only the audio tower is needed to tag new clips.

## Licence

- **CLAP2 weights:** Apache-2.0, stated in each model card's metadata and licence section
  (`laion/clap2-*-audioxl-s1B`). This permits commercial use and redistribution, including in a
  desktop application, with the licence notice.
- **CLAP2 training data (LAION-Audio-XL):** per-clip licences. Of 366M clips, 4.36M are hosted
  as audio in three licence classes (2.1M `open`: CC0/CC BY/permissive; 0.99M `sharealike`;
  1.27M `noncommercial`: CC BY-NC and similar academic sets), and the remaining 361.6M are links
  to YouTube and web media under their owners' terms. About 94% of samples are said to carry a
  Creative Commons licence. Most captions were generated by audio-language models (Qwen3-Omni and
  MOSS-Audio).
- **First-generation LAION CLAP:** Apache-2.0 weights, training data likewise of mixed licence.
  **MSCLAP:** MS-PL on its Hugging Face card.
- **SAME-S** (already shipped) is under its own Stability AI community licence; nothing here
  changes that.

The commercial-distribution decision rests on the **weights' licence**, which is cleared:
Apache-2.0 places no field-of-use restriction on the released model. The training data includes
non-commercial and third-party material, which is the norm for public audio-text models and is
not a term of the weights' licence; whether that is acceptable is a policy question for the
project owner, recorded here rather than resolved.

## Recommendation

1. **Checkpoint: CLAP2, default size S** (`laion/clap2-small-audioxl-s1B`, 104M parameters,
   Apache-2.0, verified natively above). On the probe it is the best size (centered top-1 15/27,
   top-3 21/27) while L adds 1.4 GB beside SAME-S for no gain; the model card's benchmarks put S
   within two points of L (MAEB-30 51.8 vs 53.6) and well above XXS (46.2). S costs 624 MB beside
   SAME-S on 4 s windows and about 1.9 GB on 10 s windows. The native port loads every size the
   same way, so moving to XXS (for low-memory machines) or to M or L later is a weights change,
   not a code change.
2. **Windows of at most 10 s**, compiled once, with longer material split and its window
   embeddings averaged (the model card's advice for audio over 30 s, and the memory figures
   above argue for 10 s).
3. **Vocabulary of about 150 tags**, within the 100-200 band. Write each tag as one or more short
   caption sentences and average a tag's caption embeddings; avoid bare labels and negations. A
   larger vocabulary costs almost nothing at inference (one dot product per tag) but dilutes the
   top ranks, and the probe already shows near neighbours (organ, synth pad, synth lead)
   competing.
4. **Provisional suggestion thresholds.** Raw cosine similarities are a poor threshold (precision
   at most 0.36 until only a handful of pairs remain, for both XXS and L). Calibrate per tag:
   subtract the tag's mean score over the library and suggest a tag for a sample when its
   **centered score is at least 0.05** (S: 12 of 270 pairs at 0.053, precision 0.58; L: 17 pairs
   at 0.063, precision 0.65; M: 21 pairs at 0.050, precision 0.62) **and** it is among the
   sample's top 3. Suggest a tag for the library when **at least 3 samples** meet that
   bar. These numbers come from 27 clips and should be re-fitted on a larger labelled set before
   they are relied on.
5. **Follow-ups for the full port:** the native GPT-2 tokenizer, multi-caption tag averaging,
   fixed-window segmentation, and an application-facing tagging API. The full port is worth
   committing to in this shape: the hard part (a numerically exact native CLAP) is done.

## Reproducing

```bash
# Reference dumps (see engine/ml/scripts/README.md for the environment)
python engine/ml/scripts/dump_clap2_reference.py reference laion/clap2-large-audioxl-s1B \
    --nsynth /data/nsynth/test-00000-of-00001.parquet --out /data/clap2-large
python engine/ml/scripts/score_clap2_reference.py /data/clap2-large/references
python engine/ml/scripts/extract_sa3_weights.py SAME-S/model.safetensors /data/same-s --target ae --ae-mode standalone
```

Then run `Clap2TaggingSpikeTest` with `-DAR_CLAP2_WEIGHTS=/data/clap2-large/weights
-DAR_CLAP2_REFERENCES=/data/clap2-large/references -DAR_SAME_WEIGHTS=/data/same-s`
(`-DAR_HARDWARE_MEMORY_SCALE=6` for the 10 s and 30 s cost measurement); each test writes its
figures to `engine/ml/results/clap2_*.out`.
