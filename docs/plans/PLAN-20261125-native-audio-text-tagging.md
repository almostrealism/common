---
status: proposed
created: 2026-11-25
closed:
tracker: [ff43ddd4-57d8-4320-a392-21b863519928]
---

# Native zero-shot audio–text (CLAP-style) tagging model in `engine/ml`

**Tracker task:** `ff43ddd4-57d8-4320-a392-21b863519928` — *Native zero-shot
audio–text (CLAP-style) tagging model in engine/ml*
**Release:** the Common release under development (the version in the root `pom.xml`).

## Motivation

Common has no audio classifier, no tagging head and no CLAP-style audio–text
model. A zero-shot audio–text tagger is a general framework capability: any
application can score an audio clip against an arbitrary, versioned list of text
tags it chooses, with no retraining when that vocabulary changes. That
generality is why the capability belongs in the framework, even though its first
consumer is a downstream client application. The no-ONNX rule holds: it must run
natively.

The blocking spike `036acf9b` is complete and merged. It settled the shape with
evidence (`engine/ml/docs/audio-tagging-feasibility.md`): the LAION **CLAP2**
family is real, Apache-2.0, and maps cleanly onto the framework's existing
transformer primitives. The spike already ported both towers numerically
(native audio and text embeddings reproduce the reference to a cosine of at
least 0.99999 for the XXS/S/L sizes), and recommended **CLAP2-S**
(`laion/clap2-small-audioxl-s1B`, 104M params) as the shipped default. What the
spike explicitly left for "the full port" is the part that turns the two towers
into a *usable tagger*:

> *"Follow-ups for the full port: the native GPT-2 tokenizer, multi-caption tag
> averaging, fixed-window segmentation, and an application-facing tagging API.
> The full port is worth committing to in this shape: the hard part (a
> numerically exact native CLAP) is done."*

This task is exactly that full port. The model exists; it cannot yet be *called*
with a caller-supplied tag list because the text tower still takes pre-computed
token ids from the Python reference script, and there is no entry point that
takes a waveform of arbitrary length plus a list of tag strings and returns
per-tag scores.

## Scope

Build the application-facing native tagging capability on top of the merged
CLAP2 port, in the existing `org.almostrealism.ml.clap` package of `engine/ml`.
Concretely, four pieces and their tests:

1. **Native GPT-2 (`r50k_base`) byte-level BPE tokenizer.** The CLAP2 text tower
   uses the GPT-2 `r50k_base` vocabulary (50,260 rows, special tokens BOS 50259 /
   EOS 50257 / PAD 50258 — the constants already in `Clap2Config`). Today the
   probe tags' token ids are produced by `open_clip`'s tokenizer in
   `dump_clap2_reference.py`; the framework cannot tokenize a caller's tag string
   itself. Add a concrete `ByteLevelBPETokenizer` subclass that loads the
   `r50k_base` vocabulary and merges and applies GPT-2 pre-tokenization, plus a
   weight-extraction script under `engine/ml/scripts/` that exports that
   vocabulary to a byte-level binary format the Java tokenizer reads (a new
   format and script — distinct from the `SentencePieceBPETokenizer` `ARTK`
   path, which rejects byte-level tokenizers; see Approach piece 1).

2. **Tag vocabulary with multi-caption averaging.** A tag is one or more short
   caption sentences (the CLAP2 model card's guidance); its text embedding is the
   normalized mean of its captions' embeddings, computed once per vocabulary
   version. This is a genuine new concept — a *versioned set of tags with
   precomputed text embeddings* — so it is a named type, not a loose helper.

3. **Fixed-window audio segmentation.** The spike's memory finding is that each
   distinct clip length compiles a separate tower with its own activation
   buffers, and that activation memory grows with window length. So the tagger
   compiles **one** fixed window (at most 10 s per the finding), splits a longer
   clip into windows, and averages the window embeddings; a shorter clip is
   padded to the window. This replaces "compile a new tower per clip length".

4. **Application-facing tagging API.** An entry point that takes a `PackedCollection`
   waveform of arbitrary length and a caller-supplied tag vocabulary (or a plain
   `List<String>` of tags, each defaulting to a single caption) and returns a
   per-tag score. It composes pieces 1–3 and the already-present
   `Clap2.similarity` / `Clap2.logits`.

**Out of scope** (named as dependencies / follow-ups, not widened into this
task):

- **The SAME-S supervised-head fallback (shape B).** The task description names
  it as a "licensed/memory fallback", but the spike resolved the licence and
  memory questions in CLAP2's favour and explicitly declined shape B (it needs
  labelled data and a training run, and gives up the retraining-free vocabulary).
  The task defers the final shape to the spike ("The exact checkpoint and shape
  are fixed by the preceding spike"), so this plan follows the spike: CLAP2-S, no
  SAME-S head. See open question 1.
- **A shipped default tag vocabulary.** The API is *caller-supplied* tags; the
  framework ships the mechanism, not a fixed ~150-tag list. The ~100–200 band and
  the per-tag calibration thresholds in the spike are guidance for a consumer
  that owns a library, not framework constants. See open questions 3 and 4.
- **Consumer-side plumbing** (wiring the tagger into `WaveDataFeatureProvider` /
  the audio-library tag store). That belongs to the consuming module and its own
  tasks, not to this framework capability.
- **Short-clip kernel-count optimisation** and the in-flight layer-output
  buffer-reuse work. The spike measured both as the cost/memory levers; neither
  is modified here.
- **New Maven modules.** All work lands in the existing `engine/ml` module and
  `engine/ml/scripts/`.

## Approach

### Where the new behaviour belongs

Everything lands in `engine/ml`, mostly in the existing
`org.almostrealism.ml.clap` package that already holds `Clap2`,
`Clap2Config`, `Clap2AudioEncoder` and `Clap2TextEncoder`.

**(1) Tokenizer.** `ByteLevelBPETokenizer`
(`org.almostrealism.ml.tokenization`) is already the abstract byte-level BPE
pipeline; `RegexPreTokenizer` already applies the GPT-2 pre-tokenization pattern
through its no-argument constructor (`new RegexPreTokenizer()`, the private
`createGPT2Pattern()` behind it), which is how `Qwen3Tokenizer` — the worked
example of a concrete subclass — obtains it (construct with a `RegexPreTokenizer`,
load vocabulary and merges, override the special-token ids). The CLAP2 tokenizer
is a new subclass of the same base — a vocabulary/merges-loading class with the
CLAP2 special-token ids from `Clap2Config` — **not** a new tokenizer algorithm.
Place it beside the model it serves (`org.almostrealism.ml.clap`) since it is
specific to the CLAP2 `r50k_base` vocabulary; it implements the general
`Tokenizer` contract (which the abstract base already implements) and adds
nothing CLAP-specific to the base class.

   The vocabulary and merges are exported by a new script under
   `engine/ml/scripts/`. This export is **not** a reuse of `export_tokenizer.py`,
   and the plan should not describe it as one: that script and its `ARTK` binary
   serve `SentencePieceBPETokenizer`, and its `validate_pipeline` *explicitly
   rejects* a byte-level (GPT-2/Qwen family) pre-tokenizer — exactly `r50k_base` —
   because the whole-text Java `ARTK` reader does not remap bytes the way a
   byte-level BPE does (see `test_byte_level_pre_tokenizer_is_rejected` in
   `test_export_tokenizer.py`). The byte-level lineage instead reads a format each
   subclass defines for itself: the abstract base declares no file-loading method,
   and `Qwen3Tokenizer` loads a little-endian vocabulary binary (per-token score
   plus length-prefixed UTF-8 bytes) alongside a separate HuggingFace-style
   `merges.txt`. No committed script under `engine/ml/scripts/` produces that
   pair today, so defining the export format (or giving the base a shared byte-level
   loader) and writing its script is genuinely new work, not a thin reuse — see
   open question 2. The `r50k_base` vocabulary and merges come from `open_clip` /
   `tiktoken` in the extraction environment, exactly where `dump_clap2_reference.py`
   gets them today, so the exported ids stay consistent with the reference dump.

**(2) Tag vocabulary.** A new type in `org.almostrealism.ml.clap` representing a
versioned tag vocabulary: each tag carries one or more caption strings, and the
type holds (or lazily computes, once) the normalized mean text embedding per tag
by tokenizing each caption with piece (1) and encoding it through
`Clap2TextEncoder.encode`. Because tag embeddings depend only on the vocabulary
(not the clip), they are computed once per vocabulary version and reused across
clips — matching the feasibility doc's "computed once per vocabulary version".
Averaging and normalization are expressed as Producers (`MatrixFeatures` /
`CollectionFeatures`), consistent with the rest of the port — no Java
element-wise math.

**(3) Fixed-window segmentation.** `Clap2AudioEncoder` already compiles its tower
for a given sample count (`frontEnd(samples)` / `block(timePatches)` keyed off
`Clap2Config.timePatches(samples)`). Add a fixed-window encode on the audio
encoder: compile the tower once for the configured window's sample count, split a
longer waveform into windows (padding the final/short window to the window
length), encode each, and average the window embeddings into one clip embedding.
The window length is a config value (default ≤10 s per the memory finding). This
is the audio encoder's own capability (it owns how a clip becomes an embedding),
so it is a method there, not an external helper.

**(4) Tagging API.** The scoring capability is the model's, so the entry point is
a method on `Clap2` (which already exposes `getAudioEncoder()`,
`getTextEncoder()`, `similarity` and `logits`): given a waveform and a tag
vocabulary, encode the clip via (3), take the vocabulary's per-tag text
embeddings via (2), and return per-tag scores via the existing `similarity`
(raw cosine) — with `logits` available when a caller wants the temperature-scaled
form. The result is a per-tag score keyed by the caller's tag identity. A
convenience overload accepts a `List<String>` (one caption per tag) and builds a
default vocabulary. The method owns its collaborators — the caller passes tags
and audio, never a compiled sub-model — consistent with the project's
"own your collaborators" rule.

### Reference assembly pattern

Loading follows the module's existing "load `StateDictionary` → build layers →
compile" pattern that `Clap2` already uses; nothing about weight loading changes.
The native path removes the only remaining dependency on the Python side at
inference time (the token ids), so a caller needs just the extracted CLAP2-S
weights and the exported tokenizer vocabulary.

## Success criteria

A reviewer can confirm the task is done when:

1. **The full native path runs with no reference ids and no ONNX.** A call of the
   form `clap2.score(waveform, List.of("kick drum", "piano", ...))` (or the
   vocabulary overload) returns a per-tag score, with the tag strings tokenized
   by the new native tokenizer — not by the Python reference script.

2. **Tokenizer parity.** The native `r50k_base` tokenizer reproduces, exactly, the
   token ids that `dump_clap2_reference.py`'s `open_clip` tokenizer produces for
   the probe captions. This is a deterministic, weight-free check and should run
   in CI if the exported vocabulary can be a committed test resource; otherwise it
   is gated like the real-weight run (see open question 2).

3. **Synthetic-weight test passes in CI.** Extending `TestSuiteBase` and following
   `Clap2SyntheticTest`, a test proves the tagger wiring — tokenizer →
   multi-caption averaging → fixed-window segmentation → scoring — builds and
   produces finite, content-dependent, deterministic scores without real weights
   (window averaging equals the single-window result for a clip that fits one
   window; two distinct clips give distinct scores; padding never changes a short
   clip's score).

4. **Real-weight ranking test (the task's "done when").** A gated test scores a
   few known musical samples against a caller-supplied tag list through the full
   native path and asserts the expected tags rank above irrelevant ones (e.g. the
   describing tag is within the clip's top-k), consistent with the spike's probe
   results (drums rank correctly; centered top-3 is strong). It is gated via
   `requireLocalResource` on `AR_CLAP2_WEIGHTS` (and the sample source), reported
   **skipped** where the weights are absent — the honest-skip shape in
   `docs/internals/test-skip-gating-prevention.md`. It complements, and does not
   weaken, the existing `Clap2TaggingSpikeTest`.

5. **The build validator is green** (`checkstyle`, `code_policy`, `test_timeouts`,
   `duplicate_code`), and the new Java respects the Producer pattern (no
   `.evaluate()`/`.toDouble()` in computation code — the spike noted
   `CodePolicyViolationDetector` flags any such call in `engine/ml` main code).

Tests run narrowly, e.g.
`mcp__ar-test-runner__start_test_run module:"engine/ml"
test_classes:["Clap2TaggingTest"]` (the new synthetic/parity class).

## Dependencies

- **Metal / macOS node.** Confirming criterion 4 — that real tags actually rank
  correctly — requires running CLAP2-S on real weights, which the spike measured
  only on Metal, and the audio-tower activation memory makes the backend choice
  material. So the implementation session must run on a macOS FlowTree node.
  Recorded in `docs/plans/task-20261125-180145-workstream.yaml`
  (`requiredLabels: platform: macos`). The CI synthetic and tokenizer-parity
  checks do not need it.
- **CLAP2-S weights and the `r50k_base` vocabulary source.** The extraction and
  real-weight test need the CLAP2-S safetensors (Apache-2.0, not gated) and the
  `open_clip` / `tiktoken` environment that `engine/ml/scripts/README.md`
  documents, to export the tokenizer vocabulary and dump the reference ids.
- **A reproducible musical sample source for the ranking test.** The spike's
  probe set is reproducible from public NSynth data and a script and ships no
  audio; the ranking test should reuse that path so it carries no copyrighted
  audio into the repo (see open question 5).

## Open questions

1. **SAME-S fallback.** The task description still names a "supervised
   classification head on the existing SAME-S audio embedding" as the
   licensed/memory fallback, but the spike cleared CLAP2's licence and memory and
   declined shape B. This plan treats shape B as out of scope per the spike's
   decision. If the owner wants the task description made precise, the planner can
   update *this* task to reflect that the spike fixed CLAP2-S and retired the
   SAME-S fallback. Confirm before implementation.
2. **The byte-level export format, and whether the exported vocabulary is a
   committed test resource.** Two linked decisions. First, the export is new
   work, not a reuse of `export_tokenizer.py` (whose `ARTK` reader rejects the
   byte-level `r50k_base` family — see Approach piece 1): the implementer must
   either define a byte-level vocabulary+merges binary for the new tokenizer to
   read, or give `ByteLevelBPETokenizer` a shared byte-level loader (the base
   declares none today; `Qwen3Tokenizer` reads its own little-endian binary plus a
   `merges.txt`). Pinning that format is a prerequisite the owner should settle
   before implementation, and it is the natural home for the `Qwen3Tokenizer`
   "should it implement the `Tokenizer` interface" follow-up already in the
   backlog. Second, the GPT-2 `r50k_base` vocabulary is ~50k tokens (a
   one-to-two-MB binary); shipping it as a test resource lets the tokenizer-parity
   check (criterion 2) run in CI without gating, otherwise that check is gated with
   the real-weight run. The owner should confirm whether a vocabulary file of that
   size is acceptable in the module's test resources.
3. **What does the API return — raw cosine, logits, or centered scores?** The
   spike's useful thresholds are *per-tag centered* scores (subtract each tag's
   mean over a library), which presume a library the framework does not have.
   This plan proposes the framework API return the raw cosine similarity (with
   logits available), and documents per-tag centering/thresholds as a consumer
   responsibility. Confirm this split is what the owner wants.
4. **Caption-averaging convention.** Average normalized caption embeddings, or
   average then normalize? Match whatever `open_clip` / the CLAP2 reference does
   so native tag embeddings stay faithful; the reference script
   (`dump_clap2_reference.py`) is the authority and should be checked at
   implementation time.
5. **Ranking-test sample set and assertion strength.** Confirm the ranking test
   reuses the spike's reproducible NSynth-derived probe, and agree the assertion
   (e.g. "describing tag within top-3 for the clearly-separable clips such as
   drums") — the spike's small-N probe shows single sustained pitched notes are
   often confused, so a top-1 assertion over all clips would be too strong.
6. **Follow-ups filed by the spike overlap this task.** The spike's recommendation
   listed four follow-ups (native tokenizer, multi-caption averaging, fixed-window
   segmentation, tagging API) that together *are* this task. Checking the tracker:
   one of the four was filed as a separate open backlog task — *"Native GPT-2
   (r50k_base) byte-level BPE tokenizer for the CLAP2 text tower"*, which is
   exactly piece 1 — while multi-caption averaging, fixed-window segmentation and
   the tagging API were not filed separately. The owner should fold (or close) that
   tokenizer task into this one before implementation so piece 1 is not worked
   twice. Two further open backlog tasks touch this work's edges rather than
   duplicating it: *"Bound the per-length compiled-model cache of
   Clap2AudioEncoder"* (piece 3 compiles a single fixed window, which reduces but
   does not by itself bound that cache) and *"Validate CLAP2 caption token ids
   against the vocabulary before the embedding gather"* (the native tokenizer of
   piece 1 is what produces those ids). Neither is in this task's scope, but the
   owner should be aware they interact with it.

## Estimated complexity

**Medium.** The numerically-exact native CLAP2 port — the hard part — is already
merged. What remains builds on existing primitives: a tokenizer subclass of an
existing abstract base (with the GPT-2 pattern already present), a versioned
vocabulary value type, a fixed-window encode on the existing audio encoder, and a
scoring method on the existing `Clap2`, plus a synthetic/parity CI test and a
gated real-weight ranking test. No new architecture, no training, no new module.
The one piece without an existing template is the byte-level tokenizer export
(open question 2): the base declares no byte-level file format and the `ARTK`
exporter rejects this family, so the format and its script are defined from
scratch — a bounded but non-trivial task that the complexity estimate assumes is
settled up front rather than discovered mid-implementation.
