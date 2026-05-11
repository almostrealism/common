# Pattern System — Phase 3 Implementation Design

**Status:** Design document, May 2026 (branch `feature/audio-scene-redesign`).
**Revised:** 2026-05-11 — after the envelope investigation
([ENVELOPE_INVESTIGATION.md](ENVELOPE_INVESTIGATION.md)) corrected the
original framing.
**Source data:** `PatternRenderingFloorBenchmark` (Additions 1–6 plus the
E1 / E2 / E3 envelope measurements from May 2026).
**Companion docs:** [PATTERN_RENDERING_FLOOR.md](PATTERN_RENDERING_FLOOR.md),
[ENVELOPE_INVESTIGATION.md](ENVELOPE_INVESTIGATION.md),
[AUDIO_SCENE_REDESIGN.md](../AUDIO_SCENE_REDESIGN.md) (Phase 3 section).

This document describes **how the current per-note pattern rendering path moves
to the batched architecture validated by the benchmarks.** It is the bridge
between the measurements and the production code that has to be restructured.
The benchmarks prove the kernel cost. This document proves the implementation
plan.

It does not write code. It identifies what changes, where, and in what order;
it names the central design decisions and recommends a position on each; and
it flags the questions implementation will surface that the design cannot
pre-answer.

The intended reader is the agent (or human) implementing Phase 3. Reading this
end-to-end takes ~25 minutes; after, the implementation can begin without
re-deriving the design from the benchmarks and current code.

---

## What changed in this revision

The original draft of this document treated the per-tick chain as **3 kernels**
(resample + a single "volume envelope" + accumulate-reduce) and kept the
"FIR filter" out of pattern rendering on the grounds that the per-channel
arrangement-level FIR should stay downstream of pattern accumulation.

The envelope investigation revealed that framing was wrong in three ways:

1. **Production runs *two* envelopes per note, not one.** The volume envelope
   (`ParameterizedVolumeEnvelope` → `AudioProcessingUtils.volumeEnv` shared
   compiled kernel) **and** the filter envelope (`ParameterizedFilterEnvelope`
   → `MultiOrderFilterEnvelopeProcessor` singleton, per-sample cutoff array
   driving a lowpass). The per-note filter envelope is a real kernel in the
   current per-note chain; the original draft did not see it.
2. **"FIR" in the original draft was the wrong filter.** The original
   recommendation "FIR stays downstream of pattern accumulation" was about the
   per-CHANNEL arrangement-level FIR in
   `MixdownManager` / `EfxManager` — which legitimately stays downstream. It
   did not address the per-NOTE filter envelope at all. The per-note filter
   envelope **cannot move downstream of accumulation** without losing its
   per-note shaping, so it has to live in the batched per-tick chain.
3. **The per-note filter envelope batches successfully** via the padded-row
   strategy through the existing `TemporalFeatures.lowPass` PDSL primitive
   (E2). And the combined filter+volume chain runs *faster* than the sum of
   the two batched independently at production density (E3), so the design
   should target one composed `CollectionProducer` for both envelopes.

This revision keeps the cutover-strategy, edge-case, and substrate framings
where they were already serviceable, and rewrites Section 1 (kernel mapping),
Section 4 (substrate decision), and the summary table to reflect the
corrected picture. Recommendation labels (3A, 3B, 3C) are preserved with
their meanings clarified: see §1.5 for what 3A means *now* — per-channel
arrangement filter stays downstream — versus what it meant in the original
draft — per-channel-or-per-note FIR conflated, the per-channel position
recommended without addressing the per-note filter envelope at all.

---

## Contents

1. [Per-kernel mapping: current code → batched architecture](#1-per-kernel-mapping)
   - §1.1 Resample
   - §1.2 Filter envelope (new section)
   - §1.3 Volume envelope (corrected framing)
   - §1.4 Accumulate-reduce
   - §1.5 Per-channel arrangement filter (renamed; out of the per-tick batched chain)
   - §1.6 Where caching fits
   - §1.7 Java-side gather cost (Path B1) — measured (added 2026-05-11)
2. [Variable-note-scheduling](#2-variable-note-scheduling)
3. [Cutover strategy](#3-cutover-strategy)
4. [PDSL vs `CollectionProducer` for the new substrate](#4-substrate)
5. [Edge cases and complications](#5-edge-cases)
6. [Scope: Phase 3 vs deferred](#6-scope)
7. [Open questions](#7-open-questions)

---

<a id="1-per-kernel-mapping"></a>
## 1. Per-Kernel Mapping: Current Code → Batched Architecture

The benchmarks measure a **four-kernel chain** for the per-tick batched
producer applied to all N notes for one tick:

```
resample → filter envelope (per-note) → volume envelope (per-note) → accumulate-reduce
```

The production code applies the same logical operations today, but each lives
in a different file with its own scheduling and call-site idioms. The first
job of Phase 3 is to identify each kernel in current code, its current shape,
its target shape, and the restructuring needed to bridge them.

The per-channel arrangement-level FIR in `MixdownManager` / `EfxManager` is a
**separate concern** that is *not* part of the per-tick batched chain — it
operates on already-summed pattern output, not on individual notes. It is
discussed in §1.5 for completeness and to clear up the original draft's
confusion of terms; it is otherwise out of scope for Phase 3.

Citations below are file path + symbol (class/method) + central line range.
Line numbers should be treated as a reading aid, not a stable target — the
implementer should locate methods by name, not by line.

---

### 1.1 Resample (Kernel 1) — Linear lerp from `[SOURCE_SIZE]` to `[NOTE_SIZE]`

**Where it lives today:**
[`engine/audio/.../notes/NoteAudioProvider.java`](../../../engine/audio/src/main/java/org/almostrealism/audio/notes/NoteAudioProvider.java),
`computeAudio(NoteAudioKey)` (around line 259). It is **not a Producer kernel
today.** The method returns
`() -> args -> provider.getChannelData(channel, ratio, sampleRate)` — a thin
`Producer` wrapper around a native call into the `WaveDataProvider` that
returns a fully-resampled `PackedCollection`. The result is cached in the
static `audioCache` (`CacheManager<PackedCollection>`, max 200 entries) keyed
by `NoteAudioKey(targetPitch, channel)`. Cache hits return the captured
`PackedCollection` directly. The resampled buffer is wrapped as a constant
via `c(getShape(key), audioCache.get(...))` (line 247) for downstream Producers
to consume.

**Current input/output shape:**
- Per-note. Input: `[SOURCE_SIZE]` (varies per note source, typically
  O(seconds × sample_rate)). Output: `[resampled_count]` (depends on pitch
  ratio).
- Granularity: one resample per `(target_pitch, channel)` pair across the
  entire arrangement. Heavy cache reuse when many notes share a pitch class.

**Target input/output shape (batched):**
- Per-tick. Input: `[SOURCE_SIZE]` source plus a per-row index tensor
  `[N, NOTE_SIZE]` of fractional gather positions (or `[N]` of pitch ratios
  the kernel turns into per-row indices).
- Output: `[N, NOTE_SIZE]` resampled audio.
- The benchmark's `buildBatchedResampleVolume`
  ([`PatternRenderingFloorBenchmark.java:688–711`](../../../engine/audio/src/test/java/org/almostrealism/audio/benchmark/PatternRenderingFloorBenchmark.java))
  is the reference: a single gather kernel that maps every flat output index
  back to `(note, sample)` via mod/floor and reads from the source at
  `sample_idx × resample_ratio`. The benchmark uses a single source and single
  ratio; production needs per-row source and per-row ratio.

**Restructuring required:**
- **Move resample out of `WaveDataProvider` and into a `Producer` kernel.**
  This is the largest single change for kernel 1. Today the native call sits
  behind the `WaveDataProvider` interface — there is no Producer-compiled
  resample primitive. Add one (or place it on a class that already represents
  a sample bank — see §5.5).
- **Express per-note pitch ratio as a tensor input.** Instead of one ratio per
  cache key, build a `[N]` ratio buffer per tick. The kernel reads
  `ratio[note_idx]` and gathers `source[floor(sample_idx × ratio[note_idx])]`
  with linear interpolation between consecutive samples.
- **Handle per-note source selection.** Production notes draw from different
  sample sources (different `WaveDataProvider`s for different drums or
  instruments). The benchmark uses one. The batched kernel needs either
  (a) a single packed source bank `[total_samples_across_all_sources]` with
  per-note offsets and lengths, or (b) one batched kernel per source bank
  with the notes pre-grouped by source. Option (b) is closer to how
  `NoteAudioChoice` already partitions notes; option (a) is denser but
  requires a bank-construction step per tick.
- **Pitch-ratio cache replaces audio cache (see §5.5).** The current
  `audioCache` is keyed on `(pitch, channel)` and caches the resampled audio.
  In the batched form the resample is recomputed every tick (cheap inside a
  batched kernel). The cache's role shifts from "avoid resampling" to "avoid
  recomputing the resampled buffer for sustained notes that span ticks" —
  which is what `NoteAudioCache` already does at the post-render level.

**Notes for the implementer:**
- The benchmark's resample uses `RESAMPLE_RATIO = SOURCE_SIZE / NOTE_SIZE = 2.0`
  uniformly. Production has per-note variation; the kernel arithmetic is
  identical but the ratio comes from a per-note input rather than a constant.
- The lerp itself is two gathers + one fmadd; the JNI cost dwarfs the
  arithmetic (Addition 2). Don't over-optimize the math.

**No PDSL primitive exists for resample.** This is the one kernel where the
substrate decision (§4) leans toward raw `CollectionProducer` rather than a
PDSL declaration: there is no existing per-row gather-and-lerp primitive in
PDSL, and adding one is a non-trivial expansion of the PDSL substrate.

---

### 1.2 Filter Envelope (Kernel 2) — Per-note time-varying lowpass

**(New section — the original draft missed this kernel entirely.)**

**Where it lives today:**
[`studio/music/.../filter/ParameterizedFilterEnvelope.java`](../../../studio/music/src/main/java/org/almostrealism/music/filter/ParameterizedFilterEnvelope.java),
inner class `Filter` (line 84). The `Filter` is a `NoteAudioFilter` whose
`apply(audio, duration, automationLevel)` body lives at lines 128–155. It
delegates to a shared singleton
[`engine/audio/.../filter/MultiOrderFilterEnvelopeProcessor.java`](../../../engine/audio/src/main/java/org/almostrealism/audio/filter/MultiOrderFilterEnvelopeProcessor.java)
held in
[`engine/audio/.../filter/AudioProcessingUtils.java`](../../../engine/audio/src/main/java/org/almostrealism/audio/filter/AudioProcessingUtils.java)
(field `filterEnv` line 53, constructed at line 95, returned by
`getFilterEnv()` lines 140–142).

The production stack order
([`PatternElementFactory.java:265–271`](../../../studio/music/src/main/java/org/almostrealism/music/pattern/PatternElementFactory.java))
is **filter envelope first, then volume envelope** — for both MAIN and WET
voicings of melodic notes (drum / non-melodic notes skip the filter envelope
and may skip the volume envelope, gated by `enableFilterEnvelope` and
`enableVolumeEnvelope`).

**What the kernel does:**
- ADSR scalars (attack, decay, sustain, release per note, scaled by
  mode-specific maxima and adjusted by the automation level) drive a per-sample
  cutoff frequency curve.
- The curve is materialised into a `[maxFrames]` buffer via the compiled
  `cutoffEnvelope` producer (lines 145, 311 of `MultiOrderFilterEnvelopeProcessor`).
- The audio is then lowpass-filtered using `multiOrderFilter`
  (`lowPass(v(shape(-1, maxFrames), 0), v(shape(-1, maxFrames), 1), sampleRate, filterOrder).get()`,
  lines 146–149) with the per-sample cutoff array as a producer-valued input.
- Filter order is 40; the cutoff envelope peaks at `filterPeak = 20000 Hz`
  (line 73). The ADSR shape modulates the envelope between 0 Hz and
  filterPeak.

**Current input/output shape:**
- Per-note. Input: one note's audio at `[NOTE_SIZE]`. Output: same shape,
  lowpass-filtered with the per-sample cutoff.
- Granularity: `process()` runs **two** compiled `evaluate()` calls per note
  (lines 311–313): one to materialise the cutoff envelope, one to apply the
  lowpass. Each is a separate JNI dispatch.

**Target input/output shape (batched):**
- Input: `[N, NOTE_SIZE]` audio (from kernel 1 output) plus a `[N, NOTE_SIZE]`
  per-sample cutoff envelope (one row per note).
- Output: `[N, NOTE_SIZE]` filtered audio.
- The batched form measured by **E2** uses the **padded-row strategy**: each
  row of audio and each row of cutoff is padded by `FILTER_ORDER/2 = 20` zero
  samples on each side, producing `[N, NOTE_SIZE + FILTER_ORDER]`, then
  flattened to `[N * (NOTE_SIZE + FILTER_ORDER)]` and run through one
  `lowPass(...)` call. The per-row pad zones absorb the FIR's boundary samples
  so cross-row bleed lands in padding, not in note signal.
- **E2 measured numbers** (M1 Ultra, JNI/Metal, NOTE_SIZE=1024,
  FILTER_ORDER=40):
  - 16 notes/m (512 notes): 1.68 ms — 55× under 92.9 ms threshold
  - 32 notes/m (1,024 notes): 3.01 ms — 31× under
  - 64 notes/m (2,048 notes): **5.07 ms — 18× under**
  - 128 notes/m (4,096 notes): 5.83 ms — 16× under
  - 256 notes/m (8,192 notes): 11.19 ms — 8.3× under
  - Speedup: 148–344× over sequential per-note JNI dispatch.

**Restructuring required:**

The per-row filter envelope is **the change with the most moving pieces** in
Phase 3, because (a) the existing `MultiOrderFilterEnvelopeProcessor` is
sized for single-row use, (b) the production filter class wraps the call in
the same hybrid Producer/Java pattern as the volume envelope (see §5.7),
and (c) the cutoff envelope generation is itself a `[NOTE_SIZE]` producer
call per note that needs to be batched into `[N, NOTE_SIZE]`.

- **Build the cutoff envelope tensor per-row.** Per tick, produce a
  `[N, NOTE_SIZE]` cutoff tensor where row `n` is `note n`'s ADSR-shaped
  cutoff curve. The simplest path: replicate the existing
  `cutoffEnvelope = sampling(sampleRate, () -> envelope.get().getResultant(c(filterPeak)))`
  expression so it consumes `[N]` ADSR scalars per parameter (attack `[N]`,
  decay `[N]`, sustain `[N]`, release `[N]`, duration `[N]`) and produces
  `[N, NOTE_SIZE]`. The `envelope()` builder used by the existing factory
  is shape-agnostic; the per-row tensor form is a straightforward generalisation
  rather than a new primitive.
- **Apply lowpass through `TemporalFeatures.lowPass` on the padded flat
  signal.** E2 demonstrated this works against the **existing PDSL `lowPass`
  primitive** unmodified — the producer-valued cutoff is read by the same
  `kernel(context)` indexing logic that already serves single-row use. The
  per-row coefficient code path at
  [`MultiOrderFilter.java:273–274`](../../../compute/time/src/main/java/org/almostrealism/time/computations/MultiOrderFilter.java)
  is **not** the mechanism used here; per-row independence is preserved
  entirely by the pad zones. Memory overhead at production filter order is
  ~4% (40 padding samples per 1024 note samples).
- **Refactor `ParameterizedFilterEnvelope.Filter.apply()` to pure Producer
  composition.** Today the `apply()` body (lines 128–155) calls
  `audio.get().evaluate()`, `duration.get().evaluate()`,
  `automationLevel.get().evaluate()` to materialise inputs, then invokes
  `processor.process(audioData, result)` — itself a two-`evaluate` body.
  The batched Phase 3 form composes the cutoff-envelope kernel and the
  lowpass kernel into one `CollectionProducer` graph that takes ADSR scalars,
  audio, and automation level as Producer inputs and returns the
  filtered audio as a Producer output. See §5.7 for the hybrid-pattern
  edge case.

**Notes for the implementer:**
- The existing `MultiOrderFilterEnvelopeProcessor.process(input, output)`
  (lines 299–314) pre-allocates a `[maxFrames]` cutoff buffer (line 91).
  For the batched form, that buffer becomes `[N, maxFrames]` — or, more
  precisely, the cutoff envelope is regenerated each tick as part of the
  batched producer rather than held in a long-lived field.
- The `filterPeak = 20000 Hz` ceiling and `filterOrder = 40` are
  configuration constants — preserve them as Producer constants in the
  batched form. They are not per-note inputs.
- **Automation level multiplies sustain and release in Java today**
  (lines 140, 150–151) before the ADSR scalars are set on the processor.
  Either (a) move this scaling into the producer graph (multiply on the
  scalar tensors before they enter the envelope kernel) or (b) materialise
  the adjusted `[N]` scalar tensors in Java once per tick before the batched
  dispatch. (a) is the cleaner expression; (b) is the simpler first cut.
  Recommend (b) for the first integration milestone and treat (a) as a
  follow-on once the path is stable.

---

### 1.3 Volume Envelope (Kernel 3) — Multiply by ADSR-shaped curve

**(Corrected from the original draft — the volume envelope is not a
pre-materialised `[T]` gain tensor; it's a scalar-ADSR-fed kernel.)**

**Where it lives today:**
[`studio/music/.../filter/ParameterizedVolumeEnvelope.java`](../../../studio/music/src/main/java/org/almostrealism/music/filter/ParameterizedVolumeEnvelope.java),
inner class `Filter` (line 95). The `Filter` is a `NoteAudioFilter` whose
`apply(audio, duration, automationLevel)` body lives at lines 149–207.
It delegates to the shared compiled evaluable
`AudioProcessingUtils.volumeEnv` (field at line 56, compiled in the static
initializer at lines 80–85, returned by `getVolumeEnv()` at lines 131–133).

**What the kernel does:**
- ADSR is parameterised by **four scalar values** (attack, decay, sustain,
  release) per note. The envelope curve is **not** pre-materialised as a
  `[T]` gain tensor — it is computed inside the kernel from those four
  scalars plus the audio's duration.
- The compiled `volumeEnv` is built as
  `sampling(sampleRate, MAX_SECONDS, () -> envelope(duration, attack, decay, sustain, release).getResultant(v(shape(-1), 0)))`
  (`AudioProcessingUtils.java:80–85`). The compiled `Evaluable<PackedCollection>`
  takes the audio tensor as input 0 and the ADSR scalars as inputs 1–5.
- The kernel runs once per note today: one JNI dispatch that computes the
  envelope curve from the scalars in-kernel and multiplies by the audio.
- Automation level scales sustain and release in Java before the kernel runs
  (lines 160–186 of `ParameterizedVolumeEnvelope.Filter.apply()`); attack and
  decay are not scaled. Adjustment factor is `0.8 + 0.01 × al` — a much
  weaker effect than the filter envelope's `0.8 + 0.5 × al`.

**Current input/output shape:**
- Per-note. Input: one note's audio at `[NOTE_SIZE]` plus four scalar ADSR
  parameters. Output: same shape, ADSR-multiplied.

**Target input/output shape (batched):**
- Input: `[N, NOTE_SIZE]` audio (from kernel 2 output — the filter envelope
  applied first per production order) plus `[N]` scalar tensors for each of
  the four ADSR parameters and for duration.
- Output: `[N, NOTE_SIZE]`.
- The batched form measured by **E1** is the simplest case: per-row audio
  multiplied by per-row envelope. **E1 measured numbers** (M1 Ultra,
  JNI/Metal, NOTE_SIZE=1024):
  - 16 notes/m (512 notes): 0.55 ms — 169× under threshold
  - 32 notes/m (1,024 notes): 0.72 ms — 129× under
  - 64 notes/m (2,048 notes): **0.89 ms — 104× under**
  - 128 notes/m (4,096 notes): 2.48 ms — 37× under
  - 256 notes/m (8,192 notes): 3.31 ms — 28× under
  - Speedup: 314–813× over sequential per-note dispatch.

E1 demonstrated that volume envelope batching is **trivially safe** —
elementwise multiply has no cross-row coupling, and per-row independence is
preserved by construction.

**Restructuring required:**
- **Detach the envelope from `NoteAudioFilter.apply`.** The filter abstraction
  applies on a single Producer. The batched form needs to operate on
  `[N, NOTE_SIZE]`. Either:
  - (a) Build the per-row envelope tensor offline (Java side) by sampling the
    ADSR curve per row and pass it as a Producer-constant input to the batched
    kernel, **or**
  - (b) Generalise the existing `volumeEnv` kernel composition so it consumes
    `[N]` ADSR scalar tensors and produces the per-row envelope inside the
    kernel — same kernel shape as today, just one row dimension wider.
  - **Recommend (b).** The existing `volumeEnv` is built from the same
    `envelope()` factory that the filter envelope's `cutoffEnvelope` uses.
    Generalising the inputs from `shape(1)` scalars to `shape(N)` tensors is
    a one-line composition change; the kernel itself runs per-element with no
    cross-row coupling. The per-row ADSR materialisation in (a) would be a
    Java-side cost the batched form is trying to eliminate.
- **Pick a representation for automation level.** Today's
  `automationLevel.getResultant(c(actualTime).add(time))` in
  `ScaleTraversalStrategy.createRenderedNote` is a per-note `Factor` that
  resolves to a Producer of the time-varying gain. The batched form needs
  `[N]` adjusted scalars per tick. Either materialise the adjustment in Java
  before the batched dispatch, or push the multiplication inside the batched
  producer graph as a per-row scalar multiply on the sustain/release tensors.
  Recommend the in-graph multiplication for symmetry with the filter envelope
  case (§1.2).

**Notes for the implementer:**
- The compiled `volumeEnv` evaluable is held as a static `Evaluable` (line
  56). In the batched form, replace its use site (line 198–199 of
  `ParameterizedVolumeEnvelope.Filter.apply()`) with composition into a
  larger producer graph rather than a direct `.evaluate()` call. See §5.7
  for the hybrid-pattern edge case.
- **Stacking with the filter envelope is automatic via composition.** When
  the filter envelope's output `[N, NOTE_SIZE]` Producer is the audio input
  to the volume envelope's Producer, the framework fuses both into one
  kernel — exactly the pattern E3 measured.

---

### 1.4 Accumulate (Kernel 4) — Sum into output buffer

**Where it lives today:**
[`PatternFeatures.java`](../../../studio/music/src/main/java/org/almostrealism/music/pattern/PatternFeatures.java)
`sumToDestination` (line 180–208). Calls
`AudioProcessingUtils.getSum().sum(destination.range(shape, destOffset), audio.range(shape, sourceOffset))`
once per note. Each call is a separate JNI kernel dispatch with `overlapLength`
elements.

**Current input/output shape:**
- Per-note. Input: one note's audio at `[noteLength]`; destination range at
  `[overlapLength]`. Output: the destination buffer gains the note's
  contribution at `[destOffset .. destOffset + overlapLength)`.

**Target input/output shape (batched):**
- One reduction over `[N, NOTE_SIZE]` → `[NOTE_SIZE]` (when all N notes
  contribute to the same per-tick output slot). Addition 6 confirmed this:
  `permute(reshape(out, [N, NOTE_SIZE]), 1, 0).traverse(1).sum().reshape([NOTE_SIZE])`
  is fused into the upstream batched kernel — reduction is essentially free.

**Why this is now believed cheap at scale:** Addition 6 measured tile vs
reduce as parity (within 7%) at all densities up to 128 notes/m, and reduce
*faster* than tile at 256 notes/m. The framework fuses
`permute + traverse(1).sum()` into the upstream batched kernel; there is no
intermediate `[N × NOTE_SIZE]` materialisation.

**Restructuring required:**
- **Remove per-note `AudioProcessingUtils.getSum().sum(...)` calls.** They
  become one kernel-fused reduction per pattern layer per tick.
- **Solve note-position scatter.** The batched reduction collapses N notes
  that start at the *same* destination offset. In production, notes start
  at *different* offsets within a tick (see §2). Three positions:
  - (a) **Pad each note's row to span the full tick width**
    (`[NOTE_SIZE_TICK]`) with zeros before/after the note's actual content;
    the reduction sums all notes' shifted rows into one tick buffer. Memory:
    `O(N × tick_width)`.
  - (b) **Sort notes into sub-batches by their start offset within the
    tick;** emit one batched reduction per sub-batch into the same
    destination buffer at the correct offset (Java-side accumulator handles
    sub-batch combination).
  - (c) **Use a scatter-add primitive:** emit `[N, NOTE_SIZE]` plus a
    `[N]` per-note destination-offset tensor; a scatter-add kernel writes
    each row into the destination buffer at the per-note offset.

Position (a) is the simplest expression and matches the benchmark, but memory
grows with `tick_width` instead of `NOTE_SIZE`. Position (c) is the cleanest
but relies on a scatter-add primitive that may not exist as a fused-with-
reduction operation in the framework today. Position (b) is the safe middle.

**Recommendation: position (a) for the first cut**, as long as tick widths
are modest (32 measures × samples-per-measure is the typical production
tick). If profiling shows the pad-zero memory cost is meaningful, move to
(c) once the scatter-add primitive is validated. Re-evaluate during
implementation.

---

### 1.5 Per-channel arrangement filter (renamed; out of the per-tick batched chain)

**(Renamed from "downstream FIR" / kernel 3 in the original draft. This
section clarifies what was confused; the recommendation itself stands.)**

**Where it lives:**
[`studio/compose/.../arrange/MixdownManager.java`](../../../studio/compose/src/main/java/org/almostrealism/studio/arrange/MixdownManager.java)
and the PDSL `mixdown_main` / `mixdown_channel` layers
([`engine/ml/src/main/resources/pdsl/audio/mixdown_channel.pdsl`](../../../engine/ml/src/main/resources/pdsl/audio/mixdown_channel.pdsl)).
The per-channel filtering also lives in
[`studio/compose/.../arrange/EfxManager.java`](../../../studio/compose/src/main/java/org/almostrealism/studio/arrange/EfxManager.java)
(`applyFilter()` around line 291+, filter order 40 at line 60, lowpass/highpass
selection at lines 316–325).

**What it is:**
- Per-channel FIR lowpass / highpass with **static coefficients selected per
  channel by genome state**. Cutoff is not time-varying within a tick — it's
  the same coefficients for the full tick (or longer, depending on
  automation).
- Runs **once per channel per tick** after the pattern accumulator has
  summed all notes for the channel. Receives the channel's summed buffer
  (the output of kernel 4 plus any other contributors), not individual notes.

**Why it is distinct from the per-note filter envelope:**

| | Per-note filter envelope (§1.2) | Per-channel arrangement filter (§1.5) |
|---|---|---|
| Lives in | `ParameterizedFilterEnvelope.Filter.apply()` | `MixdownManager.createCells()` / `EfxManager.applyFilter()` |
| Scope | Per-note | Per-channel |
| Cutoff | Time-varying within the note (ADSR sweep, 0 → 20 kHz) | Static FIR coefficients selected by genome |
| Rate of change | Per-sample | Per-tick (or longer) |
| Order | 40 | 40 |
| When | Inside the per-tick batched chain — before accumulation | Outside the per-tick batched chain — after accumulation |
| Phase 3 status | New batched kernel (§1.2) | Unchanged; stays downstream |

The original draft of this document said "FIR stays downstream of pattern
accumulation" and recommended position 3A. That recommendation **is still
correct for the per-channel arrangement filter** — it should stay downstream.
The mistake was in not recognising that the per-channel filter is *not* the
same thing as the per-note filter envelope, and in framing the FIR as one
decision when there were two filters in play.

**Recommendation: leave the per-channel filter where it is.** No Phase 3
change. The cutover to PDSL for this filter (via `mixdown_channel.pdsl`)
is a Phase 1 concern in the broader `AUDIO_SCENE_REDESIGN.md` plan, not a
Phase 3 concern.

**Implication:** Phase 3's batched per-tick chain is **4 kernels**:
resample, filter envelope, volume envelope, accumulate-reduce. The
per-channel arrangement filter is a 5th processing step that happens
downstream of pattern accumulation and is not part of this chain.

---

### 1.6 Where caching fits

The current `NoteAudioCache`
([`studio/music/.../pattern/NoteAudioCache.java`](../../../studio/music/src/main/java/org/almostrealism/music/pattern/NoteAudioCache.java))
caches **the post-evaluate `PackedCollection` of each note's full audio**,
keyed by absolute frame offset. The cache exists because notes can span
multiple buffer ticks: when the next tick re-renders, the cached audio is
summed without re-evaluating the producer.

In the batched form, **the per-tick batched dispatch is so fast (~5–13 ms
at production density with both envelopes — E3) that caching individual
notes within a tick is uneconomical** — even building the cache costs more
than recomputing. But cross-tick caching still has a role: if a note spans
4 ticks, the same `[noteLength]` audio is read by 4 ticks' accumulate
passes. Two options:

- **Demote `NoteAudioCache` to a per-note cross-tick cache only.** Notes whose
  duration > `tick_width` get materialised once into a long buffer; the per-
  tick accumulate reads from that buffer (offset by the appropriate amount).
  Notes with duration ≤ `tick_width` go through the batched per-tick chain
  without hitting the cache.
- **Remove `NoteAudioCache` entirely; have the batched chain recompute long
  notes each tick.** Simpler, costs O(tick_width × N_overlapping_long_notes)
  per tick — likely fine given the batched chain's speed.

Recommend the first option (preserve `NoteAudioCache` for genuinely long
notes, remove its per-tick role). Implementation can spot-check the second
option once metrics are in place; if the per-tick cost on production-scale
arrangements stays well below threshold without it, drop the cache.

The static `audioCache` in `NoteAudioProvider` is a different concern
(resampled sample buffers, not rendered note audio). It can stay — sample
resample results are still useful as Producer-constant inputs to the batched
kernel. See §5.5.

---

### 1.7 Java-side gather cost (Path B1) — measured

**(Added 2026-05-11 from Addition 7 of
[PATTERN_RENDERING_FLOOR.md](PATTERN_RENDERING_FLOOR.md). The §1.1–§1.6
kernel-mapping sections describe what the per-tick batched chain consumes.
This section pins down what it costs to *build* those inputs from genome
state per tick.)**

Two architectural paths could turn genome state into the
`[N, NOTE_SIZE + filterOrder]` audio tensor the batched chain consumes:

- **Path A — kernel-internal selection.** Push the genome-decoding decision
  process (which notes exist at which time, source selection, pitch ratio
  resolution) into the kernel itself. The branchy decision logic is well
  outside what PDSL/`CollectionProducer` programs are designed for. **Out of
  scope for Phase 3.**
- **Path B — Java-side gather, kernel-side compute.** Java continues to
  decide which notes exist, resolve sources, and compute scalar parameters;
  it then assembles the `[N, NOTE_SIZE + filterOrder]` audio tensor and the
  eleven `[N]` scalar tensors (pitch ratio, 4 volume-envelope ADSR scalars,
  4 filter-envelope ADSR scalars, automation level, tick-relative start
  offset). The kernel runs on the prepared tensors. **This is the Phase 3
  path.**

Within Path B, four sub-options exist for how the audio tensor gets
assembled:

- **B1 — Per-note `System.arraycopy`** from cached resampled source buffers
  into row `n` of the audio tensor.
- B2 — Gather indirection (single packed source bank with per-note offset
  tensor).
- B3 — Pre-staged source buffers as Producer constants.
- B4 — Compile kernel against a fixed source set per "neighborhood" of ticks
  with respecialisation when the set changes.

**Addition 7 measures B1.** Setup: 16 source buffers, lengths 2048–8192
samples, per-note metadata generated outside the timed loop. The timed
inner loop (1) gathers each note's `NOTE_SIZE` samples via
`System.arraycopy` into a reusable Java `double[]`, (2) `setMem`'s the
buffer into the audio collection, (3) allocates + `setMem`'s the eleven
`[N]` scalar tensors.

**Measured numbers (Darwin/Mac runner, framework auto-selected backend; see
caveats in Addition 7):**

| notes/m | Total notes | B1 gather alone (ms) | B1 + E3-like kernel (ms) | vs threshold (gather + kernel) |
|---|---|---|---|---|
| 16 | 512 | 1.29 | 3.95 | 23.5× BELOW |
| 32 | 1,024 | 2.77 | 6.13 | 15.2× BELOW |
| **64** | **2,048** | **4.90** | **10.36** | **9.0× BELOW** |
| 128 | 4,096 | 9.05 | 13.59 | 6.8× BELOW |
| 256 | 8,192 | 15.37 | 31.16 | 3.0× BELOW |

**Conclusion: B1 delivers a viable Path B at production density on Mac.**
The Phase 3 design's central per-tick latency assumption holds:
gather + kernel runs comfortably below the 92.9 ms threshold at every
density tested. At 64 notes/m (the production-typical density), the per-tick
budget breaks down as roughly 5 ms gather + 5 ms kernel = 10 ms total —
just over 10% of the threshold.

**Implications for Phase 3 implementation:**

- **Default to B1** for the first integration milestone of the batched
  path. The other sub-options (B2/B3/B4) are not needed to clear the
  threshold and add implementation complexity (gather indirection requires
  a per-tick offset-tensor primitive; pre-staged Producer-constant source
  buffers complicate the source-pool lifecycle; per-neighborhood compile
  reintroduces a compile cost that bucketing in §2 is already paying once
  per bucket).
- **The gather is half the per-tick cost.** Both halves matter — the design
  cannot optimise either to zero. Gather optimisations attack roughly the
  same ms count as kernel optimisations.
- **Scalar-tensor allocation is not the bottleneck.** The audio buffer
  `setMem` dominates over the eleven small-tensor allocations. The eleven
  scalar tensors per tick add microseconds; the audio buffer push adds
  milliseconds. If profiling-driven optimisation becomes necessary, focus
  on the audio buffer (sharing, reuse, or partial updates), not on the
  scalar tensors.
- **256 notes/m has high variance** (std-dev 22.6 ms vs sub-3 ms at all
  other densities). At the upper end of bucket sizing this density may
  warrant additional attention — either narrower bucketing around 128
  notes/m (so the heaviest tick rarely overflows into the 256 bucket) or a
  closer look at GC pressure from per-iteration scalar-tensor allocations.

**What this section does not measure:**

- **Linux/JNI numbers.** This iteration ran on a Mac runner (Darwin
  platform); the framework's auto-selected backend benefited from
  "Hardware[JNI]: Enabling shared memory via MetalMemoryProvider" — the
  `setMem` push into native memory is via Metal shared memory rather than
  a full-blown copy. A Linux/JNI-only environment (no Metal, no OpenCL)
  has no shared-memory optimisation; its `setMem` cost is expected to be
  modestly higher (the audio buffer push is a real copy in that case).
  A follow-up iteration should run the same benchmark on a Linux container
  and append numbers to Addition 7 alongside these.
- **Per-tick envelope materialisation cost.** Measurement 2 pre-builds the
  per-row envelopes outside the timed loop. The realistic per-tick path
  builds envelopes from the `[N]` ADSR scalars either in-kernel (§1.2,
  §1.3, §5.7's recommended path) or via a Java-side materialisation pass.
  The §5.7 pure-Producer envelope refactor moves this into the batched
  Producer graph — the marginal in-kernel cost is the small downstream of
  the existing E1/E2/E3 measurements (envelope-from-scalar is a row-wise
  fmadd, well within the framework's existing fusion). A follow-on
  iteration can measure this directly once the §5.7 refactor is in place.
- **B2/B3/B4 sub-options.** Not measured. The B1 numbers do not justify the
  effort; they should be measured only if Linux/JNI gather costs are
  unexpectedly high, or if profiling shows GC pressure from per-tick
  allocations is real.

---

<a id="2-variable-note-scheduling"></a>
## 2. The Variable-Note-Scheduling Problem

The benchmark uses a fixed N per measurement. Production has variable N per
tick: a typical 32-measure tick contains a different number of notes
depending on pattern density, section activity, and the genome's per-layer
choices. Phase 3 has to handle this without (a) recompiling the producer
every tick, or (b) leaving 99% of the speedup on the table by padding to a
worst-case fixed N.

### Three candidate approaches

**A. Pad every tick to a fixed maximum N.** Pick `N_MAX` large enough that
production density never exceeds it (e.g., 256 notes/measure × 32 = 8192).
Silent rows pad to N_MAX; the reduction sums them but they contribute zero.

- **Pro:** One compiled producer for the entire program lifetime. Reuse is
  perfect across ticks. No per-tick compilation cost.
- **Pro:** Memory predictable.
- **Con:** Wastes compute on padded silent rows. At low densities (16 notes/m
  = 512 notes) padding to 8192 means ~94% of the work is silent. E3 numbers
  say the 256 npm batched chain runs in ~12.69 ms; if 16 npm pays the same
  ~12.69 ms instead of dropping to ~2.49 ms, that's still well under threshold
  but uses 3–4× the energy of necessary.
- **Con:** Per-row inputs (pitch ratio, source offset, ADSR scalars,
  automation-adjusted sustain/release) must be padded to N_MAX every tick.

**B. Recompile the producer per N.** Each tick with a different N rebuilds
the batched kernel.

- **Pro:** No padding waste; every tick uses exactly the compute it needs.
- **Con:** **Catastrophic.** Compilation is the new bottleneck. Benchmark
  compile times for the batched chain at production densities are in the
  hundreds of milliseconds (`PatternRenderingFloorBenchmark` logs
  `compileStart` / `compileMs` per density). 100ms+ per tick wipes out every
  gain the batched form delivers.

**C. Bucket N to a small fixed set (16/32/64/128/256/512 notes per measure
× 32 = 6 distinct values). Each tick ceiling-rounds to the next bucket and
pads to that bucket's N.**

- **Pro:** One compiled producer per bucket, reused across ticks. Six
  compiled producers total per pattern layer.
- **Pro:** Padding waste bounded — worst case is 2× when N is just over a
  bucket boundary (e.g., 33 notes pads to 64).
- **Pro:** No per-tick compilation cost after warmup.
- **Con:** Six times the compiled-kernel memory vs option A. Compilation cost
  paid up-front, not per-tick.

**D. (Proposed during reading.) Single compiled producer with N as a Producer
input via an `[N_MAX]` mask tensor.** The kernel always runs N_MAX rows but
ignores rows where `mask[row] == 0`. Same shape as A, but with a runtime
gate rather than implicit zero contribution from silent envelopes.

- **Pro:** Single compiled producer; no per-tick recompile.
- **Con:** Equivalent to A in compute cost (rows still run); the mask is just
  a more explicit zero-out. May or may not be faster than A's implicit zeros
  depending on whether the framework branch-predicts the mask.

### Recommendation: C (bucketing) for production; A (worst-case pad) for the first implementation milestone.

The first integration milestone wants to validate the end-to-end batched
path with the simplest scheduling — A's worst-case pad has the fewest moving
parts. Once acoustic equivalence is verified and the batched path is the
live route, swap to C (bucketing) for the production cost profile. The
bucket boundaries should match the existing `NOTES_PER_MEASURE_VALUES`
benchmark densities (16, 32, 64, 128, 256) — the kernel has been measured
at exactly those points and the cost is known for both envelopes (E1/E2)
and the combined chain (E3).

**Envelope kernels are bucketing-compatible.** Both volume and filter
envelope kernels have shape `[N, NOTE_SIZE]` with N being a structural input
to the compiled producer. Each bucket's compiled producer carries its own
N; runtime inputs are per-row scalars/tensors at the bucket's N. No envelope
restructure is needed for bucketing — the same shape works for every
bucket.

**Open question for implementation:** what's the cost of compilation per
bucket? The implementation should measure compile time per bucket on
production hardware before settling on the bucket count. If compile takes
seconds, fewer buckets (3 instead of 6) may be better. If milliseconds,
finer-grained bucketing (8 buckets) may be worth the memory.

---

<a id="3-cutover-strategy"></a>
## 3. Cutover Strategy

Three candidate approaches:

**A. Side-by-side (feature flag).** Build the new batched path alongside
the old per-note path; route via a runtime flag (system property,
`SystemUtils.isEnabled("AR_PATTERN_BATCHED")`). The flag defaults to off;
flip to on once acoustic equivalence is verified. Easy rollback, easy
A/B comparison.

- **Pro:** Implementation can ship landed-but-disabled; the rest of the team
  proceeds while the implementer iterates without breaking master.
- **Pro:** Acoustic-equivalence testing has both paths present in the same
  binary — verifiable on identical fixtures.
- **Con:** Maintaining both paths during transition. Each new orchestration
  change has to update both.

**B. Incremental kernel-by-kernel replacement.** Replace kernel 4 (accumulate)
first — a per-tick reduction instead of per-note `getSum().sum(...)` calls.
Then kernel 3 (volume envelope), then kernel 2 (filter envelope), then
kernel 1 (resample). Each milestone leaves acoustic output valid.

- **Pro:** Smaller diffs, each independently verifiable.
- **Con:** The win is JNI-dispatch elimination; replacing one kernel at a
  time doesn't deliver until enough kernels are folded into one batched
  producer. Until then, the per-note Producer is still being evaluated N
  times — no speedup.
- **Con:** Each milestone leaves a half-batched, half-per-note path that's
  harder to reason about than either endpoint.

**C. Atomic replacement.** One PR replaces the whole per-tick pattern
rendering path with the batched version. Smaller total diff, larger blast
radius.

- **Pro:** Cleaner end state — no legacy per-note path to maintain.
- **Con:** If anything's wrong, pattern rendering breaks until reverted.
  Acoustic regressions might only surface on specific genome configurations.

### Recommendation: A (side-by-side with feature flag).

The combination of "acoustic correctness is critical," "the new path is
substantial enough to ship in pieces," and "rollback should be one config
change" makes the flag worth the duplication cost during transition. The
flag should be `AR_PATTERN_BATCHED` (default off) read at `PatternLayerManager`
construction. The new path lives in a new class/method named for what it
does (`BatchedPatternRenderer`, or a sibling method `sumBatched` on
`PatternLayerManager` — see §6 for placement).

**Verification mechanism:**

The verification must cover **three distinct scopes**, since they are
independently changed by Phase 3:

1. **Per-note filter envelope** — new batched kernel in §1.2. Compare
   per-note vs per-tick batched output of the filter envelope alone on a
   deterministic input. Tolerance is loose at first (energy within ~5%) and
   tightens as the path stabilises.
2. **Per-note volume envelope** — new batched kernel in §1.3. Same shape of
   comparison as (1), independently.
3. **Per-channel arrangement filter** — unchanged in §1.5, but the
   pattern-accumulator output feeding it is now the batched-reduce output
   rather than the per-note-sum output. The downstream filter must still
   produce equivalent output on the same fixture. This is covered by running
   existing per-channel tests with the flag on/off.

Concretely:

1. Build a **`PatternRenderingBatchedVerificationTest`** (or
   `PatternRenderingPdslVerificationTest`) modeled after the existing
   [`MixdownManagerPdslVerificationTest`](../../../studio/compose/src/test/java/org/almostrealism/studio/ml/test/MixdownManagerPdslVerificationTest.java).
   The latter compares Java and PDSL DSP paths via per-channel energy +
   sum-of-squared-difference, writing WAVs to `results/pdsl-audio-dsp/` for
   audit. The new test compares the per-note path (flag off) and the
   batched path (flag on) on the same `AudioScene` fixtures, with the same
   genome, the same pattern shape, the same destination buffer. The test
   must isolate each envelope's contribution as well as the full chain:
   a fixture with the filter envelope only, one with the volume envelope
   only, and one with both — to localise any divergence.
2. **Reuse existing tests.** The pattern-rendering tests in
   `studio/compose/src/test/java/.../pattern/` (`RealTimeRenderingTest`,
   `HeapPatternRenderingTest`, `AudioSceneRealTimeTest`,
   `AudioSceneRealTimeCorrectnessTest`, `AudioSceneMultiGenomeTest`,
   `AudioSceneBufferConsolidationTest`, `AudioSceneBaselineTest`,
   `OptimizerSceneDiagnosticTest`, `PatternFactoryTest`,
   `AudioSceneOptimizationTest`) all run against the per-note path today.
   Phase 3 should run **each of these tests with the flag both off and on**;
   the batched path passes when it produces equivalent output on every
   existing test that exercises pattern rendering. CI can parametrize this
   once the cutover is on a stable footing.
3. **Per-tick latency assertion.** Add a smoke benchmark that runs one tick
   through the batched path and asserts `tick_ms < 92.9 × 0.5` (well below
   ratio-of-1 with margin). This prevents quiet regressions where someone
   adds a Java-side step that re-introduces per-note JNI dispatches.

**Flag removal.** Once the batched path has shipped, the verification suite
is green across CI runs, and a release window has passed, remove the flag
and delete the per-note `PatternFeatures.render` path. This is a separate
PR — do not bundle it with the cutover.

---

<a id="4-substrate"></a>
## 4. PDSL vs Raw `CollectionProducer`

**(Significantly updated. The envelope investigation revealed that the
existing PDSL `lowPass` primitive and the existing `volumeEnv` kernel
composition already handle the two envelope kernels — the substrate decision
shrinks to just the resample case.)**

The benchmark uses raw `CollectionProducer` composition for resample
(`buildBatchedResampleVolume`) and the existing PDSL `lowPass` primitive
for the filter envelope (E2's batched form is built on
`TemporalFeatures.lowPass`). The DSP path (Phase 1) is moving to PDSL. The
long-term trajectory in
[`AUDIO_SCENE_REDESIGN.md`](../AUDIO_SCENE_REDESIGN.md) Section 5 / Phase 4
is "PDSL for everything that benefits from being a tensor operation" —
including pattern rendering eventually.

The substrate question is now best answered per-kernel:

| Kernel | What's already available | Substrate position |
|---|---|---|
| §1.1 Resample | Nothing — no per-row gather-and-lerp primitive in PDSL today | Raw `CollectionProducer` for Phase 3; PDSL primitive deferred |
| §1.2 Filter envelope | **Existing PDSL `lowPass` primitive** (validated by E2); existing `envelope()` factory for cutoff curve | PDSL primitive directly; no new primitives needed |
| §1.3 Volume envelope | **Existing `AudioProcessingUtils.volumeEnv` kernel composition** built from the existing `envelope()` factory (validated by E1) | Existing kernel composition (raw `CollectionProducer`); generalising it to per-row inputs is a one-line composition change, not a new primitive |
| §1.4 Accumulate-reduce | Existing reduction (`permute + traverse(1).sum()`) validated by Addition 6 | Existing primitive |

This means:

- **The two envelope kernels do not need new PDSL primitives.** Both can be
  expressed today: the filter envelope via `lowPass(...)` on a padded flat
  signal with a producer-valued cutoff (the exact pattern E2 measured); the
  volume envelope via the existing `envelope()` factory composed with
  `sampling(...)` (the exact pattern `AudioProcessingUtils.volumeEnv` already
  uses, generalised to `[N]` ADSR tensors).
- **Only resample is genuinely missing.** Adding a per-row gather-and-lerp
  primitive to PDSL is a non-trivial expansion that should not be on Phase
  3's critical path. Use raw `CollectionProducer` for resample.

### Recommendation: hybrid — PDSL where the primitives exist, raw `CollectionProducer` for resample.

The original draft recommended option B (raw `CollectionProducer` for the
whole chain) on the grounds that PDSL lacked primitives. With the envelope
investigation's clarification — that `lowPass` and the existing volume
envelope composition already work — the substrate decision becomes more
nuanced:

- **Filter envelope:** Build through the existing `TemporalFeatures.lowPass`
  call (which is the same primitive PDSL exposes as `lowpass`). This is the
  same expression used in production today via
  `MultiOrderFilterEnvelopeProcessor`, with the inputs generalised from
  single-row to per-row tensors.
- **Volume envelope:** Build through the existing `envelope()` factory
  composed via `sampling(...)`, generalised from `shape(1)` ADSR scalars to
  `shape(N)` ADSR tensors. This is the same expression used in production
  today via `AudioProcessingUtils.volumeEnv`.
- **Resample:** Raw `CollectionProducer` gather-and-lerp, matching the
  benchmark's `buildBatchedResampleVolume`. Add a PDSL `resample` primitive
  as a follow-on if it turns out to be useful elsewhere, but Phase 3 does
  not need it.
- **Accumulate-reduce:** Existing `permute + traverse(1).sum()` composition,
  matching Addition 6.

The composed chain is a single `CollectionProducer<PackedCollection>` per
bucket (§2). Whether the pieces are written in raw `CollectionProducer`
expression or PDSL declaration is a writing-style choice — the framework
fuses them either way (the E3 measurements demonstrate this with `lowPass`
composed with `multiply`).

**Implementation guidance:** Group the kernel-construction calls into one
method that takes the per-tick inputs and returns the composed
`CollectionProducer<PackedCollection>`. The caller (the new orchestration)
holds a compiled `Evaluable`. When (and if) the resample primitive moves
to PDSL, the call site changes; the orchestration above and the genome
plumbing below do not.

The PDSL conversion of resample is **Phase 4**, not Phase 3 — see §6
(scope). The filter envelope and volume envelope are *already* on the PDSL
substrate (via `lowPass` and the existing kernel composition); there is no
"PDSL conversion later" pending for them.

---

<a id="5-edge-cases"></a>
## 5. Edge Cases and Complications

Each of the following is easy to skip in a happy-path design and expensive
to retrofit later.

### 5.1 Polyphony — multiple notes at the same time position

In the per-note path each note independently produces audio and adds to the
destination; concurrent notes have identical destination offsets but
independent producers, no special handling required. In the batched path,
polyphony is **natural and free**: each polyphonic voice is just another
row in the `[N, NOTE_SIZE]` batched tensor, and the reduction sums them. The
current code's `ScaleTraversalStrategy.CHORD` already emits multiple
`RenderedNoteAudio` entries per `PatternElement` for chord positions; those
become rows just like any other note.

**Verify during implementation:** that the batched accumulator's reduction
correctly handles same-offset multiple notes (the row-sum is right by
construction, but the destination-offset mapping has to put them in the same
slot). This is testable with a fixture: a `CHORD` pattern element with 3
scale positions should produce the same destination audio as 3 separate
`PatternElement`s at the same position with one scale position each.

### 5.2 Tail decay — notes that ring past their nominal end

A note's audio can ring past `NOTE_SIZE`: envelope release, filter resonance,
reverb tail, and (in some sample-based note types) a non-trivial decay tail
in the source. The current path handles this via
`noteLength = audio.getShape().getCount()` (`PatternFeatures.sumToDestination`
line 182) — the full evaluated note buffer is summed up to the tick
boundary, with overlap computed against `endFrame`. Notes that span multiple
ticks are cached (`NoteAudioCache`) and accumulated across ticks.

The batched form has to preserve this:

- For notes whose `expectedFrameCount ≤ NOTE_SIZE_TICK` (typical short
  notes), the row is `[NOTE_SIZE_TICK]` zero-padded and the reduction works
  as designed.
- For notes whose `expectedFrameCount > NOTE_SIZE_TICK` (sustained, long-tail
  notes), the design has options:
  - (a) Render the note's *full* audio into a separate per-note buffer (one
    batched pass per tick range it spans), keep the buffer in `NoteAudioCache`
    by the note's start offset, and during accumulation read from it at the
    correct per-tick offset.
  - (b) Truncate at the tick boundary (acoustically wrong; loses release tail
    and filter-envelope ringing).
  - (c) Allow a note's row to span more than one tick — render
    N notes × `[K * NOTE_SIZE_TICK]` where K is the max tick-span across
    notes. Wasteful if only a few notes have long tails.

**Recommend (a):** keep `NoteAudioCache` for genuinely-long notes (its
existing role), with the per-tick batched chain reading from the cached
buffer for any note whose duration exceeds the tick. This preserves the
cache's purpose (cross-tick reuse) without making it the per-tick fast path.

**Note for the filter envelope (§1.2):** the filter envelope's release
phase can extend past the note's nominal end if attack+decay+release > note
duration; the filter ringing tail at note end can also extend a few samples
past the last sample of the audio buffer. Both are absorbed by zero-padding
the row to `NOTE_SIZE_TICK` and letting the envelope's natural fade-to-zero
take effect.

### 5.3 Multi-layer rendering

`PatternLayerManager` may have up to 32 layers per pattern
(`MAX_LAYERS = 32`, `PatternLayerManager.java:122`). The benchmark used one
layer. In production, each `PatternLayer` contributes its own elements to
the per-tick render.

Two positions:

- (a) **Each layer renders independently into the destination** (current
  behavior). Per layer per tick: gather notes, build batched producer,
  evaluate, sum into destination. Per pattern: N_layers batched dispatches
  per tick. At one batched dispatch per layer the cost is
  `layers × ~5 ms ≈ 160 ms` worst case for the full filter+volume chain at
  64 notes/m — at the upper end of the threshold (the E3 5.15 ms number is
  at one layer × 64 notes/m). Per-layer cost scales with total notes, not
  layers — a 32-layer arrangement with the same total notes across layers
  is ~the same cost as a 1-layer arrangement (one bigger batched dispatch).
- (b) **All layers fold into one super-batched producer per tick.** All
  notes from all layers in one tensor, one batched dispatch per pattern per
  tick. More aggressive batching; same speedup ceiling because each layer's
  contribution is independent anyway.

**Recommend (a) for Phase 3.** Per-layer batching is sufficient to clear
the threshold by a comfortable margin: total notes across all layers is the
load-bearing input, and that drives the bucket sizing in §2. Per-pattern
batching is a possible optimization for Phase 3+ if profiling shows the
per-layer JNI dispatches are a measurable cost.

### 5.4 Note duration variance

Notes have different durations (`PatternElement.getEffectiveDuration` resolves
to a per-note seconds value, multiplied by sample rate to get
`expectedFrameCount`). The batched form assumes a uniform `NOTE_SIZE` per
row.

Options:

- **Short notes (`duration < NOTE_SIZE_TICK`):** Render into
  `[NOTE_SIZE_TICK]` row, zero-pad after the note ends. The envelope shape
  handles release-tail fade. This is the natural form.
- **Long notes (`duration > NOTE_SIZE_TICK`):** See §5.2 — render to the
  cache, read from cache for each spanning tick.
- **Notes with explicit `frameCount` arguments:** Today the per-note path
  passes `frameCount` to `note.getProducer(frameCount)` so the
  resample/envelope kernels know how many frames to produce. The batched
  form needs to either produce uniform `NOTE_SIZE_TICK` rows (zero-padded
  for shorter notes) or per-row variable lengths. Uniform is the natural
  form for a single batched dispatch; the row-mask (option D in §2) or the
  implicit envelope zero (envelope value 0 outside the active range)
  accomplishes the same thing.

**Recommend:** uniform-width rows, per-note duration encoded in the
envelope shapes (both volume *and* filter envelopes drop their effect to
zero past the note's natural end — the volume envelope's release brings
gain to zero, the filter envelope's release brings cutoff to zero which
silences high-frequency content but does not silence the row entirely; the
volume envelope is what produces the row's actual fade-to-silence).
This means no separate "duration" tensor — the envelopes are the duration.

### 5.5 Sample caching

Current behavior: `NoteAudioProvider` caches resampled `PackedCollection`
buffers in the static `audioCache` (max 200 entries, LRU), keyed by
`NoteAudioKey(target_pitch, channel)`. Cache hit returns the captured
collection; cache miss invokes the native resample. This cache has high
hit rates in practice because note pitch classes recur (drum kit per kit,
melody within a scale).

In the batched form, the resample is folded into the per-tick kernel —
every tick recomputes every note's resample. **Is the cache still useful?**

Three positions:

- **(a) Keep the cache.** Cached resampled buffers serve as Producer
  constants fed into the batched kernel. The kernel reads from the cached
  source buffer per-row (no recomputation), saving the resample arithmetic.
  **Pro:** No regression on the kernel cost.
- **(b) Drop the cache; batched kernel recomputes resample.** The kernel
  reads from the raw source buffer; resample math runs every tick.
  **Pro:** Simpler state; no LRU bookkeeping.
  **Con:** Recomputes O(N × NOTE_SIZE) lerp ops per tick.
- **(c) Repurpose the cache.** Keep the resampled-source-buffer cache for
  the source bank construction. The pitch ratio applies per-tick from the
  live ratio tensor.

**Recommend (a) — keep the cache.** The cache's purpose (don't recompute
the linear lerp for the same `(pitch, channel)` pair) holds in the batched
form too: the kernel reads from the cached buffer rather than the raw
source. The cache produces one resampled `PackedCollection` per
`(pitch, channel)` pair; the batched kernel gathers from those buffers at
per-row sample offsets. No cache change required.

The per-note `NoteAudioCache` (different cache, post-evaluate audio) is
discussed in §1.6: demote to cross-tick-only.

### 5.6 Existing tests and acoustic validation

Pattern-rendering tests in `studio/compose/src/test/java/.../pattern/`:
`RealTimeRenderingTest`, `HeapPatternRenderingTest`,
`AudioSceneRealTimeTest`, `AudioSceneRealTimeCorrectnessTest`,
`AudioSceneMultiGenomeTest`, `AudioSceneBufferConsolidationTest`,
`AudioSceneBaselineTest`, `OptimizerSceneDiagnosticTest`,
`PatternFactoryTest`, `AudioSceneOptimizationTest`. All must keep passing
through the cutover.

Add one new test class:

- **`PatternRenderingBatchedVerificationTest`** (new, in
  `studio/compose/src/test/java/.../pattern/test/`). Modeled after
  `MixdownManagerPdslVerificationTest`. Renders the same arrangement (the
  benchmark used random sources; this test wants a deterministic small
  fixture so per-sample comparisons are tractable) via both paths, compares
  per-channel energy + sum-of-squared difference, writes WAVs to
  `results/pattern-batched-v/` for audit. Three fixtures to localise
  divergence: (i) volume envelope only, filter envelope disabled;
  (ii) filter envelope only, volume envelope disabled; (iii) both
  envelopes active (production stack). Initial tolerance loose
  (energy ratios within ~5%, since the reduction reorders float adds and
  the resample arithmetic may differ at ULP); tighten as the path stabilizes.

`HeapPatternRenderingTest` exists for a reason: the per-note path's
`Heap.stage(...)` pattern releases evaluated audio after each note. The
batched path has different memory dynamics — one batched buffer per tick,
released at tick end. The test should be reviewed to confirm the new
lifecycle matches its expectations or updated to match the new model.

### 5.7 Hybrid Producer/Java implementation in the existing filter classes

**(New edge case — surfaced by the envelope investigation, not in the
original draft.)**

Both production envelope classes wrap their kernel calls in a hybrid
Producer/Java pattern that violates the pure-Producer model:

- **`ParameterizedVolumeEnvelope.Filter.apply()`** (lines 149–207) returns
  a `Producer<PackedCollection>` whose body calls
  `audio.get().evaluate()`, `duration.get().evaluate()`,
  `automationLevel.get().evaluate()` to materialise inputs, computes adjusted
  ADSR scalars in Java, sets the scalars into single-element
  `PackedCollection` buffers, and then invokes the kernel via
  `AudioProcessingUtils.getVolumeEnv().evaluate(audioData, dr, a, d, s, r)`
  (lines 198–199). The producer wrapper around the `.evaluate()` call is
  effectively a Java-orchestrated kernel dispatch — the body is **not** a
  pure declarative producer.
- **`ParameterizedFilterEnvelope.Filter.apply()`** (lines 128–155) follows
  the same pattern: `audio.get().evaluate()`, `duration.get().evaluate()`,
  `automationLevel.get().evaluate()`, compute adjustments in Java, then
  invoke `processor.process(audioData, result)` (line 152) — which itself
  runs two compiled `.evaluate()` calls back-to-back.

When `PatternElementFactory` stacks `volume(filter(audio))` (the production
stack at `PatternElementFactory.java:266–271`), calling `.evaluate()` on the
outer (volume) wrapper triggers `audio.get().evaluate()`, which forces the
inner (filter) wrapper to evaluate — and that wrapper calls
`audioData = audio.get().evaluate()` for the raw note audio, then runs the
filter processor's two kernels, returns the filtered buffer, which the
volume wrapper then materialises and feeds into its own kernel. **Four
separate `evaluate()` boundaries per note** (plus the duration / automation
pulls) — this is precisely the per-note JNI dispatch cost Phase 3 is trying
to eliminate.

**Implications for Phase 3 implementation:**

The Phase 3 batched form **must** refactor both `apply()` bodies to
**pure Producer composition**. The hybrid pattern is incompatible with
batched per-tick dispatch — composing a batched producer means handing the
framework one composed `CollectionProducer<PackedCollection>` graph that
takes ADSR scalars, audio, and automation level as Producer inputs and
returns the filtered audio as a Producer output.

Concretely:

- **`apply()` returns a composed `CollectionProducer<PackedCollection>`**,
  not a `Producer` whose body calls `.evaluate()`.
- **ADSR scalar adjustments live in the Producer graph** (or in a per-tick
  Java-side materialisation that runs once before the batched dispatch, see
  §1.2 / §1.3 — both are acceptable, but the `.evaluate()` calls inside
  `apply()` are not).
- **Automation level handling lives in the Producer graph** or in the
  per-tick pre-dispatch materialisation, by the same logic.

This is a real Phase 3 work item, not a footnote. The refactor likely
requires changes to:

- `ParameterizedVolumeEnvelope.Filter.apply()` — body becomes Producer
  composition.
- `ParameterizedFilterEnvelope.Filter.apply()` — body becomes Producer
  composition.
- `MultiOrderFilterEnvelopeProcessor.process(input, output)` — the
  pre-allocated `[maxFrames]` cutoff buffer (line 91) and the two
  back-to-back `evaluate()` calls (lines 311–313) become a composed
  `CollectionProducer` graph that produces filtered audio from raw audio +
  ADSR scalars. The pre-allocated buffer disappears (the cutoff is an
  intermediate inside the batched producer, not a long-lived field).
- `AudioProcessingUtils.getVolumeEnv()` / `getFilterEnv()` — the static
  shared compiled `Evaluable` / processor singletons remain useful for the
  legacy per-note path (during the flag's transition window) but the
  batched path composes the underlying kernel definitions directly into its
  producer graph rather than calling `.evaluate()` on the shared
  evaluables.

**Verify during implementation** that the Producer-composed forms produce
acoustically equivalent output to the existing hybrid forms on the
verification fixtures (§3, §5.6). Per-tick acoustic equivalence is the
threshold.

### 5.8 Per-note `frameCount` and the offset arg

The current `RenderedNoteAudio.producerFactory` takes a `frameCount` int and
sets a `PackedCollection offsetArg` to the start frame. The signature
independence (`PatternFeatures.render` line 124–129) is intentional: the
compiled kernel is reused across different start-frame positions because
the offset is a runtime data argument, not a structural parameter.

In the batched form, the `offsetArg` per note becomes a `[N]` offset tensor
input to the batched producer. The same signature-independence property
holds: one compiled batched producer reused across ticks, with the offset
tensor varying per tick. The producer's compiled signature depends on N
(or the N-bucket), not on the specific offset values.

The renaming/relocation: `RenderedNoteAudio` itself probably stops being a
per-note holder of a `producerFactory` and becomes a per-note holder of
inputs (offset, pitch ratio, ADSR scalars per envelope, automation level,
source bank reference) that the batched renderer harvests into `[N]`
tensors. Or replace `RenderedNoteAudio` with a flatter struct (or a builder
accumulating `[N]` lists) consumed by the batched renderer. Implementation
choice.

---

<a id="6-scope"></a>
## 6. Scope: Phase 3 In-Scope vs Deferred

### In scope for Phase 3

- **Batched per-tick chain end-to-end for typical pattern shapes.** Four
  kernels — resample, filter envelope, volume envelope, accumulate-reduce
  — composed into one `CollectionProducer` per pattern layer per tick.
- **Per-channel arrangement filter stays downstream as today** (§1.5). No
  change to `MixdownManager` / `EfxManager` for this filter as part of
  Phase 3.
- **Variable-note-scheduling at production tick rates.** Bucketing (option
  C in §2) for production; the first integration milestone is allowed to
  use worst-case padding (option A) to land the path; the bucketing pass
  follows before the flag is flipped on by default.
- **Acoustic equivalence with the current path.** Verified by a new
  `PatternRenderingBatchedVerificationTest` modeled on
  `MixdownManagerPdslVerificationTest`, with three sub-fixtures (volume
  only / filter only / both envelopes). Existing pattern-rendering tests
  (`studio/compose/.../pattern/test/`) all keep passing with the flag both
  off and on.
- **Side-by-side cutover via `AR_PATTERN_BATCHED` flag.** New batched path
  added alongside; flag default off; flipped to on once verified.
- **`NoteAudioCache` reshaped to cross-tick-only.** Per-tick caching role
  removed; cross-tick caching role for long-tail notes retained.
- **Per-note resample becomes a Producer kernel.** Native call extracted
  from `WaveDataProvider.getChannelData` into a `CollectionProducer` that
  composes with the batched chain. The `NoteAudioProvider.audioCache` keeps
  its current role of caching the resampled `PackedCollection` per
  `(pitch, channel)`.
- **Pure-Producer refactor of `ParameterizedVolumeEnvelope.Filter.apply()`
  and `ParameterizedFilterEnvelope.Filter.apply()`** (§5.7). The hybrid
  Producer/Java pattern in both classes is incompatible with batched per-
  tick dispatch and must be replaced with composed
  `CollectionProducer<PackedCollection>` graphs.
- **`PatternRenderingFloorBenchmark` becomes the production-shape benchmark.**
  Keep the existing benchmark, add a "production fixture" variant that uses
  the real `PatternLayerManager` orchestration and the new batched path —
  one tick measured end-to-end.

### Deferred to follow-on

- **PDSL `resample` primitive.** §1.1 / §4 — raw `CollectionProducer` is
  fine for Phase 3. Promote to a PDSL primitive in a follow-on if needed.
- **Per-note arrangement-level filter as a new musical capability.** Not in
  production today; new product surface, not a refactor. (Position 3C from
  the original draft remains as a possible future addition; it is not
  Phase 3 work.)
- **Per-channel arrangement filter cutover from Cell-based to PDSL-based.**
  Tracked separately in `AUDIO_SCENE_REDESIGN.md` Phase 1. Phase 3 leaves
  this alone.
- **Per-pattern (across-layers) super-batching.** §5.3 option (b). Possible
  Phase 3+ optimization if profiling shows the per-layer dispatches are a
  measurable cost.
- **Streaming / low-latency render path.** Pattern rendering at sub-tick
  granularity. Not in scope: the realtime target is ratio-of-1 (per-tick),
  not per-buffer.
- **Removal of the old per-note `PatternFeatures.render` path.** Happens
  once the batched path has shipped, verified, and burned in. Separate PR.
- **Flag removal.** Same as above. Separate PR.
- **Pattern variant batching (vector-space exploration).** Mentioned in
  `AUDIO_SCENE_REDESIGN.md` Section 4B as a Phase 3+ extension. Out of
  Phase 3 scope; if the batched compilation graph is shaped right, batching
  across pattern variants is a re-batch dimension rather than a new
  architecture, so this can be added later without restructuring.

---

<a id="7-open-questions"></a>
## 7. Open Questions (Things The Design Cannot Pre-Answer)

These are flagged honestly because the design's view of them is incomplete
and implementation will surface the real shape:

- **Compilation cost per bucket (§2).** Production hardware compile times for
  the batched chain at each bucket density have not been measured (the
  benchmark logs `compileMs` per density, but only in the M1 Ultra
  environment, and only with a one-time JIT cost — not amortised across
  the realistic frequency of bucket compilations). The bucket-count
  recommendation (6 buckets at 16/32/64/128/256/512) assumes compile times
  are seconds-or-less per bucket — if hundreds of milliseconds, refine. The
  implementer should measure this on a production-equivalent machine before
  settling on bucket boundaries.

- **Reduction with variable destination offset (§1.4).** The recommendation
  (option (a): pad rows to tick width and reduce) assumes the
  `permute + traverse(1).sum()` approach Addition 6 validated scales to
  tick widths. The benchmark used `NOTE_SIZE = 1024`; production tick width
  is `samples_per_measure × 32`, an order of magnitude larger. Behavior at
  tick-width reduction sizes has not been benchmarked. Spot-check during
  implementation.

- **Long-note round-trip through `NoteAudioCache` (§5.2).** The
  recommendation (option (a): cache long notes, read from cache per-tick)
  preserves the current behavior, but how the batched chain reads from a
  cached `PackedCollection` at a per-row offset is not a kernel pattern the
  benchmark exercises. May need a separate "cached-tail" branch in the
  per-tick producer, gated on whether any spanning notes are present in
  the tick.

- **Acoustic equivalence tolerance.** The reduction reorders floating-point
  additions; the resample-as-Producer arithmetic may differ from the cached
  native form at ULP; the pure-Producer envelope refactor (§5.7) may
  differ in the order of multiplications versus today's hybrid form. The
  right tolerance for `PatternRenderingBatchedVerificationTest` is not
  known a priori. Start at ~5% energy match, run the existing pattern test
  suite against both paths, tighten if no real divergences are found.

- **Source bank packing (§1.1, last bullet).** Whether to pack all note
  sources into one buffer (option (a)) or group notes by source (option
  (b)) depends on production note-source counts and how often the same
  source recurs across a tick. Implementation should profile both on a
  real arrangement before locking in.

- **`Heap.stage(...)` and the batched path's memory model.** Current path
  uses `Heap.stage` per-note (`PatternFeatures.render` line 133/154) to
  release evaluated audio after sum-to-destination. The batched path's
  per-tick buffer has a different lifecycle: one per tick, released at
  tick end. Whether `Heap.stage` wraps the batched evaluate, or is
  replaced by an explicit `try-with-resources` on the batched buffer, is
  an implementation choice. Both work; pick the one that matches the
  surrounding code style.

### Closed by the envelope investigation

- **(Closed) Per-row filter envelope batching feasibility.** E2 verified
  that the existing PDSL `lowPass` primitive accepts a Producer-valued
  per-sample cutoff flattened to `[N * paddedNoteSize]` and produces the
  right per-row result when per-row pad zones are zeroed. No
  `MultiOrderFilter` change required. The 8.3× headroom at 256 notes/m
  (11.19 ms vs 92.9 ms threshold) is the tightest of the three envelope
  benchmarks but comfortable.

- **(Closed) Multi-filter chain fusion in the batched form.** E3 verified
  kernel fusion of the filter+volume envelope pair. At production density
  (64+ notes/m) the combined batched chain runs **faster** than the sum
  of the two batched independently, saving 0.81–1.81 ms across densities.
  Stacking the two envelopes into a composed producer Just Works under
  the framework's fusion.

- **(Closed) FIR position in the batched chain.** In the original draft this
  was three positions (3A keep downstream, 3B per-note, 3C new per-note
  capability) and a tangle of conflated meanings. The envelope investigation
  separates the per-note filter envelope (now §1.2 — in the batched chain)
  from the per-channel arrangement filter (now §1.5 — stays downstream).
  3A's recommendation (per-channel stays downstream) remains correct for
  the per-channel filter. 3B and 3C were aimed at a per-note filter
  capability that does not exist in production today and is deferred to a
  separate future design (§6 deferred list).

---

## Reading order for the implementing agent

1. This document (you're here).
2. [ENVELOPE_INVESTIGATION.md](ENVELOPE_INVESTIGATION.md) — the source of
   truth for what the two envelope kernels do, where they live in production
   code, and the E1 / E2 / E3 measured numbers.
3. [PATTERN_RENDERING_FLOOR.md](PATTERN_RENDERING_FLOOR.md) — the broader
   benchmark results that justify the architectural choices (Additions 1–6
   plus the appended envelope measurements).
4. [`PatternRenderingFloorBenchmark.java`](../../../engine/audio/src/test/java/org/almostrealism/audio/benchmark/PatternRenderingFloorBenchmark.java)
   — concrete kernel construction code (`buildBatchedResampleVolume`,
   `buildBatchedPaddedFirChain`, `benchmarkVolumeEnvelopeBatched`,
   `benchmarkFilterEnvelopeBatched`, `benchmarkCombinedEnvelopeChainBatched`,
   the reduction-accumulate test).
5. [`ParameterizedVolumeEnvelope.java`](../../../studio/music/src/main/java/org/almostrealism/music/filter/ParameterizedVolumeEnvelope.java)
   and
   [`ParameterizedFilterEnvelope.java`](../../../studio/music/src/main/java/org/almostrealism/music/filter/ParameterizedFilterEnvelope.java)
   — the two filter classes whose `apply()` bodies need the pure-Producer
   refactor (§5.7).
6. [`MultiOrderFilterEnvelopeProcessor.java`](../../../engine/audio/src/main/java/org/almostrealism/audio/filter/MultiOrderFilterEnvelopeProcessor.java)
   and
   [`AudioProcessingUtils.java`](../../../engine/audio/src/main/java/org/almostrealism/audio/filter/AudioProcessingUtils.java)
   — the shared compiled envelope evaluables and the singleton processor.
7. [`PatternFeatures.java`](../../../studio/music/src/main/java/org/almostrealism/music/pattern/PatternFeatures.java)
   `render` — the per-note path that needs to coexist (then retire) with
   the new batched path.
8. [`PatternLayerManager.java`](../../../studio/music/src/main/java/org/almostrealism/music/pattern/PatternLayerManager.java)
   `sumInternal` — the orchestrator that calls `render` and is the new
   batched path's entry point.
9. [`PatternElementFactory.java`](../../../studio/music/src/main/java/org/almostrealism/music/pattern/PatternElementFactory.java)
   lines 265–271 — the production stack composition (filter envelope first,
   then volume envelope; both MAIN and WET voicings; gated by
   `enableFilterEnvelope` and `enableVolumeEnvelope` flags).
10. [`MixdownManagerPdslVerificationTest.java`](../../../studio/compose/src/test/java/org/almostrealism/studio/ml/test/MixdownManagerPdslVerificationTest.java)
    — the precedent for the new verification test.

---

## Summary

| Decision | Recommended position | Why |
|---|---|---|
| Per-kernel scope (§1) | **4 kernels in batched chain**: resample, filter envelope (per-note), volume envelope (per-note), accumulate-reduce | E2 + E3 confirmed per-note filter envelope batches via padded `lowPass`; per-channel arrangement filter is a separate concern that stays downstream |
| Resample placement (§1.1) | Move resample from native `WaveDataProvider` call into a `Producer` kernel | Required for batching; `audioCache` keeps caching the source buffers |
| Filter envelope batching (§1.2) | Padded-row strategy through existing `lowPass` primitive; pure-Producer refactor of `ParameterizedFilterEnvelope.Filter.apply()` | E2 measured 5.07 ms at 64 notes/m, 18× under threshold; no `MultiOrderFilter` changes needed |
| Volume envelope batching (§1.3) | Generalise existing `volumeEnv` kernel composition to `[N]` ADSR tensors; pure-Producer refactor of `ParameterizedVolumeEnvelope.Filter.apply()` | E1 measured 0.89 ms at 64 notes/m, 104× under threshold; elementwise, trivially safe |
| Accumulate-reduce (§1.4) | `permute + traverse(1).sum()` reduction folded into upstream kernel; pad rows to tick width for first cut | Addition 6 showed reduce ≈ tile up to 128 npm, reduce faster at 256 npm |
| Per-channel arrangement filter (§1.5) | **Stays downstream of pattern accumulation** (no Phase 3 change) | Distinct concern from per-note filter envelope; original draft's "FIR stays downstream" recommendation was about this filter all along |
| Variable N (§2) | Bucket to {16, 32, 64, 128, 256, 512} × 32 measures, ceiling-round per tick | No per-tick recompile; bounded padding waste; matches benchmark densities |
| Cutover (§3) | Side-by-side with `AR_PATTERN_BATCHED` flag, default off | Easy rollback; both paths verifiable in same binary |
| Substrate (§4) | **PDSL primitives where they exist** (`lowPass` for filter envelope, existing `envelope()` composition for volume envelope); raw `CollectionProducer` for resample | Only resample is missing as a PDSL primitive; the original "PDSL conversion later" framing shrinks to just the resample case |
| Verification (§3, §5.6) | New `PatternRenderingBatchedVerificationTest` with three fixtures (volume-only, filter-only, both) + flag-parametrized existing tests | Each envelope's contribution localised; per-channel filter equivalence covered by existing tests |
| Hybrid Producer/Java refactor (§5.7) | Pure Producer composition in both `apply()` bodies + `MultiOrderFilterEnvelopeProcessor.process()` | Required for batched per-tick dispatch; identified by envelope investigation as a real Phase 3 work item |
| `NoteAudioCache` (§1.6, §5.2) | Demote to cross-tick-only | Per-tick caching uneconomical when per-tick is ~5–13 ms |
| Tail decay (§5.2) | Cache long notes; per-tick chain reads from cache at offset | Preserves existing behavior; uses `NoteAudioCache` for its actual job |
| Multi-layer (§5.3) | One batched dispatch per layer per tick (no super-batching) | Total notes across layers is the load-bearing input, not layer count |
| Sample cache (§5.5) | Keep `NoteAudioProvider.audioCache` as-is | Resampled `(pitch, channel)` buffers still useful as Producer constants |

The design is honest about what it can predict (the four kernels, the
verification pattern, the cutover mechanic, the cache role, the pure-Producer
refactor surface) and what it cannot (compile costs at scale, ULP-level
acoustic equivalence, optimal source bank packing, tick-width reduction
behavior). Implementation will fill in the unknowns. The design has set them
up to be fillable in.
