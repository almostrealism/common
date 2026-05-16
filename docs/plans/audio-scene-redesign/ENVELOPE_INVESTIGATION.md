# Envelope Investigation — Volume Envelope and Filter Envelope in Isolation

**Date:** 2026-05-11
**Branch:** `feature/audio-scene-redesign`
**Status:** Part 1 of two-part Phase 3 replanning. Findings here feed Part 2's
design revisions. **No architectural recommendations are made in this memo.**

This memo consolidates the Phase 1 investigation findings (locations,
shapes, and application semantics for the volume and filter envelopes in
production code today) and the E1 / E2 / E3 benchmark results that
quantify the batched per-row envelope cost.

---

## Volume Envelope

### Where it lives

- `studio/music/src/main/java/org/almostrealism/music/filter/ParameterizedVolumeEnvelope.java`
  — the per-note volume envelope class. The `Filter` inner class (line 95)
  is a `NoteAudioFilter` whose `apply(audio, duration, automationLevel)`
  body lives at lines 149–207.
- `engine/audio/src/main/java/org/almostrealism/audio/filter/AudioProcessingUtils.java`
  — exposes the shared compiled volume envelope evaluable. Field
  `volumeEnv` is declared at line 56; it is compiled in the static
  initializer at lines 80–85 and returned by `getVolumeEnv()` at lines
  131–133.

### Envelope shape

The volume envelope is **ADSR**, parameterised by four scalar values
(attack, decay, sustain, release) per note. It is **not** pre-materialised
as a `PackedCollection` of per-sample gains; instead, the envelope curve
is computed **inside the kernel** from those four scalar ADSR parameters
plus the audio's duration. Concretely:

- `AudioProcessingUtils` constructs `volumeFactor = envelope(duration,
  attack, decay, sustain, release)` (lines 80–83) and wraps it with
  `sampling(sampleRate, MAX_SECONDS, () -> volumeFactor.getResultant(v(shape(-1), 0)))`
  (lines 84–85). The compiled `Evaluable<PackedCollection>` takes the
  audio tensor as input 0 and the ADSR scalars as inputs 1–5.
- `ParameterizedVolumeEnvelope.Filter.apply()` (lines 149–207) materialises
  four single-element `PackedCollection`s (`a`, `d`, `s`, `r`), computes
  the adjusted ADSR values from the automation level, sets the scalars,
  and calls `AudioProcessingUtils.getVolumeEnv().evaluate(audioData,
  dr, a, d, s, r)` at lines 198–199. The kernel does the per-sample math.

So the on-disk representation of the envelope at evaluation time is **four
scalars**, not a `[T]` gain tensor.

### How it applies

The compiled volume envelope kernel takes a `[T]` audio buffer and
multiplies it by the sampled ADSR curve generated in-kernel. The output
shape mirrors the input shape. From the kernel's point of view it is a
single fused operation (`audio * envelope_curve_at_sample_i`), not two
separate steps.

### Automation level handling

The automation level (a single scalar producer per note evaluation) is
pulled into Java at line 160 (`al.toDouble(0)`) and multiplied into a
linear adjustment factor `adj = adjustmentBase + adjustmentAutomation *
al.toDouble(0)` (line 163, `adjustmentBase = 0.8`, `adjustmentAutomation =
0.01`). The adjustment scales the sustain (clamped to [0.25, 1.0], lines
170–176) and the release (clamped to ≤70% of duration, lines 183–186)
**in Java before the kernel runs**. Attack and decay are not multiplied by
`adj`. The adjusted scalars are then written into the `s` and `r` buffers
and passed to the kernel.

### Stacking and the production call site

The production stack is at `studio/music/src/main/java/org/almostrealism/music/pattern/PatternElementFactory.java`,
lines 266–271. Per melodic note:

- **MAIN voicing:** `main = filterEnvelope.apply(...)` then `main =
  volumeEnvelope.apply(main, ...)` — filter first, volume on top.
- **WET voicing:** same order — filter first, then volume.

Both filter and volume envelopes are wrappers: the outer (volume)
envelope's `apply()` pulls from the inner (filter) envelope's `apply()`,
which pulls from raw audio. So when `volume.evaluate()` is called, it
forces the filter to evaluate first. The effective application order on
raw audio is **filter, then volume**. Drum / non-melodic notes skip the
filter and may skip the volume envelope depending on the
`enableFilterEnvelope` / `enableVolumeEnvelope` flags.

---

## Filter Envelope

### Where it lives

- `studio/music/src/main/java/org/almostrealism/music/filter/ParameterizedFilterEnvelope.java`
  — the per-note filter envelope class. The `Filter` inner class (line 84)
  is a `NoteAudioFilter` whose `apply(audio, duration, automationLevel)`
  body lives at lines 128–155.
- `engine/audio/src/main/java/org/almostrealism/audio/filter/MultiOrderFilterEnvelopeProcessor.java`
  — the shared compiled cutoff-and-filter processor. Two compiled
  producers are held as fields:
  - `cutoffEnvelope` (line 109), built at line 145 as
    `sampling(sampleRate, () -> envelope.get().getResultant(c(filterPeak)))`
    — converts ADSR scalars to a per-sample cutoff frequency array.
  - `multiOrderFilter` (line 112), built at lines 146–149 as
    `lowPass(v(shape(-1, maxFrames), 0), v(shape(-1, maxFrames), 1),
    sampleRate, filterOrder).get()` — multi-order FIR low-pass with a
    per-sample cutoff.
- `engine/audio/src/main/java/org/almostrealism/audio/filter/AudioProcessingUtils.java`
  — `filterEnv` field (line 53) holds a singleton
  `MultiOrderFilterEnvelopeProcessor` (line 95) when
  `enableMultiOrderFilter == true`. Returned by `getFilterEnv()` at lines
  140–142.

### Envelope shape

The filter envelope is **ADSR scalars → per-sample cutoff frequency
array**. Unlike the volume envelope, the cutoff curve **is** materialised
as a `PackedCollection`: `MultiOrderFilterEnvelopeProcessor` pre-allocates
a `[maxFrames]` buffer at construction (line 134, field at line 91), and
on every `process()` call it slices `cutoff.range(shape(frames))` (line
310) and writes the cutoff envelope into it via
`cutoffEnvelope.into(cf.traverseEach()).evaluate()` (line 311). The peak
of the envelope maps to `filterPeak = 20000 Hz` (line 73).

The ADSR is parameterised by four scalars (attack, decay, sustain,
release) per note, scaled by mode-specific maxima from
`ParameterizedFilterEnvelope.Mode` (line 178+) and adjusted by the
automation level. The shape returned by the apply() body matches the
input audio shape (line 134: `result = factory().apply(shape.getTotalSize()).reshape(shape)`).

### How it applies

`MultiOrderFilterEnvelopeProcessor.process(input, output)` at lines
299–314 runs the two compiled producers in sequence per call:

1. Generate the cutoff frequency array: `cutoffEnvelope.into(cf.traverseEach()).evaluate()`.
2. Apply the FIR low-pass: `multiOrderFilter.into(output.traverse(1)).evaluate(input.traverse(0), cf.traverse(0))`.

The filter primitive is the same `MultiOrderFilter` exposed via
`TemporalFeatures.lowPass(...)` — a multi-order FIR with per-sample
coefficient inputs.

### Automation level handling

Inside `ParameterizedFilterEnvelope.Filter.apply()` (lines 128–155):

- The automation level scalar is read at line 138 (`al.toDouble(0)`).
- An adjustment factor is computed in Java at line 140 as `adj =
  adjustmentBase + adjustmentAutomation * al.toDouble(0)` (`adjustmentBase
  = 0.8`, `adjustmentAutomation = 0.5` — much stronger automation effect
  than the volume envelope's 0.01).
- The adjustment multiplies sustain and release (lines 150–151) before
  setting them on the processor. Attack and decay are not multiplied.

### Cutoff capping

The cutoff envelope output is scaled to peak at
`MultiOrderFilterEnvelopeProcessor.filterPeak` (default 20000 Hz, line
73), so the per-sample cutoff array is **capped at filterPeak by
construction** — the ADSR shape modulates the envelope between 0 Hz and
filterPeak, with the sustain level controlling the fraction of filterPeak
held during sustain.

---

## Per-Note vs Per-Channel Filter — Distinct

The prior Phase 3 design conflated the "filter envelope" with what it
called "downstream FIR." The investigation found these are **genuinely
distinct** filters operating at different scopes:

- **Per-note filter envelope** lives in
  `ParameterizedFilterEnvelope.Filter.apply()` and runs **once per note
  evaluation**. The cutoff is **time-varying within the note**
  (per-sample ADSR sweep capped at 20 kHz). It runs before the per-note
  volume envelope (see `PatternElementFactory.java:266-271`).

- **Per-channel arrangement filter** lives in
  `studio/compose/src/main/java/org/almostrealism/studio/arrange/EfxManager.java`,
  applied in `applyFilter(...)` at line 291+. It uses the same
  `MultiOrderFilter` primitive (filter order 40, see line 60) but with
  **static lowpass / highpass coefficient sets** selected by a binary
  decision (lines 316–325). It runs **once per channel mix tick**, not
  per note.

Different lifetimes, different cutoff representations (per-sample tensor
vs static FIR coefficients), different scopes (per note vs per channel).
Phase 3 batching of the per-note filter envelope does not require any
change to the per-channel filter, and vice-versa. The prior design's
"downstream FIR" naming masked this distinction.

---

## Filter Stacking and the Hybrid Producer / Java Implementation

A separate concern that surfaced during the Phase 1 reading: both
envelope classes return a `Producer<PackedCollection>` from `apply()`
but the producer's body calls `.evaluate()` on its inputs immediately:

- `ParameterizedVolumeEnvelope.Filter.apply()` lines 157–207: the
  returned lambda `() -> args -> {...}` opens by calling
  `audio.get().evaluate()` (line 158), `duration.get().evaluate()` (line
  159), `automationLevel.get().evaluate()` (line 160) — and then invokes
  the kernel via `AudioProcessingUtils.getVolumeEnv().evaluate(...)`
  (line 198–199).
- `ParameterizedFilterEnvelope.Filter.apply()` lines 131–154: same
  pattern — `audio.get().evaluate()` (line 132), `duration.get().evaluate()`
  (line 137), `automationLevel.get().evaluate()` (line 138), then
  `processor.process(audioData, result)` (line 152).

This means each envelope `apply()` is a **producer wrapper around a
Java-orchestrated `evaluate` call**, not a pure declarative producer.
When `PatternElementFactory` stacks `volume(filter(audio))`, calling
`evaluate()` on the outer (volume) wrapper triggers `audio.get().evaluate()`
which forces the inner (filter) wrapper to evaluate — and that wrapper
in turn calls `audioData = audio.get().evaluate()` for the raw note
audio, then runs the filter processor's two kernels, returns the
filtered buffer, which the volume wrapper then materialises and feeds
into its own kernel. Two separate `evaluate()` boundaries per note
(plus the duration / automation pulls). This is a Producer-pattern
compliance issue: the wrappers are doing computation in Java that
should be expressible in the producer DAG. Flagged here as a
**separate concern** — Part 2 of the replanning may choose to address
it or not.

---

## Critical Structural Finding — Revised After E2

Prior to running E1/E2/E3, the question of whether the existing PDSL
filter primitives could be batched with **per-row** cutoff envelopes
was unverified. Two specific concerns motivated E2:

1. **The filter envelope primitive is sized for single-row use today.**
   `MultiOrderFilterEnvelopeProcessor` pre-allocates a `[maxFrames]`
   cutoff buffer (line 91, single dimension), and its compiled producers
   are built against `shape(-1, maxFrames)` per-input (lines 146–148).
   Production calls it once per note with a `[NOTE_SIZE]` audio buffer.
   It does not accept an `[N, NOTE_SIZE]` batched input as written.

2. **The per-row indexing path in `MultiOrderFilter` (lines 273-274) was
   never exercised against a per-sample cutoff tensor.**
   `compute/time/src/main/java/org/almostrealism/time/computations/MultiOrderFilter.java`:

   ```java
   Expression coeff = coefficients.getShape().getDimensions() == 1 ?
       coefficients.getValueAt(i) : coefficients.getValue(kernel(), i);
   ```

   The 1-dimensional branch is the one used today by the per-channel
   `EfxManager` filter (with a `[filterOrder+1]` static coefficient
   vector). E2 exercises the same 1-D branch with the cutoff tensor
   flattened to a single `[N * paddedNoteSize]` 1-D signal, relying on
   per-row pad zones to absorb the FIR's boundary samples rather than
   on a per-row coefficient code path.

**Revised claim (post-E2): the batched per-row filter envelope works
with zero `MultiOrderFilter` modification.** The strategy is identical
in shape to the static-coefficient FIR batching demonstrated by
Addition 5 of `PATTERN_RENDERING_FLOOR.md` — pad each row, flatten to
1-D, run `lowPass(...)` once, reshape back. The producer-valued cutoff
input is transparent to the indexing logic: from `MultiOrderFilter`'s
perspective the cutoff is just another 1-D signal read by `kernel(context)`
at the same sample index as the input audio.

Memory overhead at production filter order is ~4 % (40 padding samples
per 1024 note samples). All five density points produced numerically
plausible results under the batched form. The 8 × under-threshold
headroom at the highest density (256 notes/measure, 11.19 ms) is the
tightest of the three benchmarks but still gives substantial
operational margin.

See the **E2 — Filter Envelope Alone, Batched** results below for the
full numbers that motivate this revision.

---

## Benchmark Results — E1, E2, E3

The three benchmark methods live in
`engine/audio/src/test/java/org/almostrealism/audio/benchmark/PatternRenderingFloorBenchmark.java`:

- `benchmarkVolumeEnvelopeBatched()` — E1 (line 1109)
- `benchmarkFilterEnvelopeBatched()` — E2 (line 1196)
- `benchmarkCombinedEnvelopeChainBatched()` — E3 (line 1320)

Densities tested: 16 / 32 / 64 / 128 / 256 notes per measure × 32
measures (= 512 / 1024 / 2048 / 4096 / 8192 total notes per tick). The
realtime threshold for a 32-measure tick is **92.9 ms**.

Platform context for the measurements below: macOS, M1 Ultra. Three
backends register during init (`Hardware[JNI]`, `Hardware[MTL]`,
`Hardware[CL]`). The compiled kernels run on the default backend
selected by AR_HARDWARE_DRIVER (unset → auto-selected). Linux/JNI
numbers from prior `PATTERN_RENDERING_FLOOR.md` are referenced where
the shape is comparable.

### E1 — Volume Envelope Alone, Batched (`benchmarkVolumeEnvelopeBatched`)

**Setup.** Sequential per-note kernel is `cp(audio).multiply(cp(envelope)).get()`
applied to a `[NOTE_SIZE]` audio buffer with a `[NOTE_SIZE]` envelope.
Batched form is `cp(perRowAudio).reshape(N*NOTE_SIZE) * cp(perRowEnvelopes).reshape(N*NOTE_SIZE)`
in one `evaluate()`. Per-row envelopes are distinct ADSR shapes (lines
1453–1467 of the benchmark).

| notes/measure | Total notes | Seq per-note (ms) | Seq total (ms) | Batched mean (ms) | Batched amortized (ms/note) | Speedup | vs 92.9ms threshold |
|---:|---:|---:|---:|---:|---:|---:|---|
| 16 | 512 | 0.3402 | 174.17 | **0.55** | 0.0011 | 314.5× | 169× under |
| 32 | 1,024 | 0.3217 | 329.41 | **0.72** | 0.0007 | 459.5× | 129× under |
| 64 | 2,048 | 0.3231 | 661.64 | **0.89** | 0.0004 | 743.6× | 104× under |
| 128 | 4,096 | 0.3294 | 1,349.07 | **2.48** | 0.0006 | 544.4× | 37× under |
| 256 | 8,192 | 0.3281 | 2,687.69 | **3.31** | 0.0004 | 813.1× | 28× under |

Compilation cost grows linearly with batch size (14ms @ 512 → 122ms @ 8192).
Compilation is one-time per tick shape; runtime cost is the per-tick
floor. All densities run >25× under the 92.9 ms realtime threshold.

**Finding.** Volume envelope batching is **trivially safe** —
elementwise multiply has no cross-row coupling, and per-row independence
is preserved by construction. Sequential per-note JNI overhead dominates
the 0.32 ms/note sequential cost; the batched amortized cost (0.0004 –
0.0011 ms/note) is essentially pure arithmetic. Run: `50afd39f`,
duration 127.9 s, exit 0.

### E2 — Filter Envelope Alone, Batched (`benchmarkFilterEnvelopeBatched`)

**Setup.** Sequential per-note kernel is
`lowPass(traverseEach(cp(audio)), cp(seqCutoff), SAMPLE_RATE, FILTER_ORDER).get()`
where `seqCutoff` is a `[NOTE_SIZE]` ADSR-shaped cutoff envelope. Batched
form uses the **padded-row strategy**: each row of audio and each row of
cutoff is padded by `FILTER_ORDER/2 = 20` zero samples on each side,
producing `[N, NOTE_SIZE + FILTER_ORDER] = [N, 1064]`, then flattened to
`[N * 1064]` and run through one `lowPass(...)` call. The per-row pad
zones absorb the FIR's boundary samples so cross-row bleed lands in
padding, not in note signal.

| notes/measure | Total notes | Seq per-note (ms) | Seq total (ms) | Batched mean (ms) | Batched amortized (ms/note) | Speedup | vs 92.9ms threshold |
|---:|---:|---:|---:|---:|---:|---:|---|
| 16 | 512 | 0.4862 | 248.92 | **1.68** | 0.0033 | 148.6× | 55× under |
| 32 | 1,024 | 0.4715 | 482.78 | **3.01** | 0.0029 | 160.2× | 31× under |
| 64 | 2,048 | 0.4692 | 961.00 | **5.07** | 0.0025 | 189.7× | 18× under |
| 128 | 4,096 | 0.4719 | 1,932.72 | **5.83** | 0.0014 | 331.8× | 16× under |
| 256 | 8,192 | 0.4697 | 3,847.56 | **11.19** | 0.0014 | 343.8× | 8.3× under |

Compilation cost: 26 ms @ 512 → 131 ms @ 8192 — comparable to E1.

**Finding — the batched filter envelope works.** This is the
empirically critical answer the prior session left unresolved. The
existing PDSL `lowPass(...)` primitive accepts a Producer-valued
per-sample cutoff input flattened to `[N * paddedNoteSize]` and produces
the right per-row result when the per-row pad zones are zeroed. **No
`MultiOrderFilter` change is required.** The per-row coefficient code
path at `MultiOrderFilter.java:273-274` is not the mechanism used here;
instead the cutoff *tensor itself* varies per sample, and per-row
independence is preserved purely by the pad zones.

The padded-row strategy used by Addition 5 of the broader benchmark
(static-coefficient FIR batching) transfers directly to the
producer-valued cutoff case. ~4 % memory overhead at production filter
order (40 / 1024).

The 8 × headroom at 256 notes/measure is the tightest of the three
benchmarks but still comfortable. Sequential per-note JNI cost again
dominates (~0.47 ms/note); batched amortized cost is 1.4–3.3 μs/note.
Run: `5337f5c1`, duration 155.6 s, exit 0.

### E3 — Combined Volume + Filter Envelope Chain, Batched (`benchmarkCombinedEnvelopeChainBatched`)

**Setup.** Sequential per-note kernel composes both envelopes:
`lowPass(traverseEach(cp(audio)), cp(seqCutoff), SAMPLE_RATE, FILTER_ORDER).reshape(NOTE_SIZE).multiply(cp(seqEnvelope)).get()` —
filter first, then volume multiply. This mirrors the production stack
order from `PatternElementFactory.java:266-271`. Batched form is the
chained version: pad+lowPass on a flat `[N * paddedNoteSize]` then
trim back to `[N, NOTE_SIZE]` and elementwise multiply by the
per-row volume envelope (lines 1383–1398 of the benchmark).

| notes/measure | Total notes | Seq per-note (ms) | Seq total (ms) | Batched mean (ms) | Batched amortized (ms/note) | Speedup | vs 92.9ms threshold |
|---:|---:|---:|---:|---:|---:|---:|---|
| 16 | 512 | 0.8245 | 422.17 | **2.49** | 0.0049 | 169.5× | 37× under |
| 32 | 1,024 | 0.7911 | 810.13 | **3.80** | 0.0037 | 213.4× | 24× under |
| 64 | 2,048 | 0.7998 | 1,638.09 | **5.15** | 0.0025 | 317.8× | 18× under |
| 128 | 4,096 | 0.7849 | 3,214.92 | **7.00** | 0.0017 | 459.2× | 13× under |
| 256 | 8,192 | 0.8859 | 7,257.55 | **12.69** | 0.0015 | 571.8× | 7.3× under |

Note the higher StdDev on the 256-note sequential row (1,613.39 ms,
P95 = 11,729.93 ms vs Median = 6,882.33 ms) — runaway tail latency at
8,192 sequential JNI dispatches. The batched form is much more
deterministic (P95 = 13.08 ms vs Mean = 12.69 ms at the same density).

**Finding — kernel fusion is real.** Comparing E3 batched cost to
`E1 + E2` batched cost at each density:

| Density | E1 (ms) | E2 (ms) | E1+E2 sum (ms) | E3 measured (ms) | Fusion savings |
|---:|---:|---:|---:|---:|---:|
| 16 | 0.55 | 1.68 | 2.23 | 2.49 | +0.26 |
| 32 | 0.72 | 3.01 | 3.73 | 3.80 | +0.07 |
| 64 | 0.89 | 5.07 | 5.96 | 5.15 | **−0.81** |
| 128 | 2.48 | 5.83 | 8.31 | 7.00 | **−1.31** |
| 256 | 3.31 | 11.19 | 14.50 | 12.69 | **−1.81** |

At low densities the chain pays a small composition overhead; at 64+
densities the framework's kernel fusion (already documented in the
broader `PATTERN_RENDERING_FLOOR.md` as a 1.73× factor on a 4-kernel
chain) drives the combined cost **below** the sum of the parts. At
production density (64+ notes/measure) the volume + filter envelope
pair runs as effectively one fused kernel.

The 7 × headroom at 256 notes/measure is the lowest of the three
benchmarks but still provides usable margin. Run: `32e8d4d3`, duration
273.6 s, exit 0.

---

## Summary

All three benchmarks executed end-to-end on `feature/audio-scene-redesign`,
exit 0, no failures. The Phase 1 investigation findings (consolidated
above) plus the E1/E2/E3 measurements answer the central pre-benchmark
question:

- **E1 confirms** volume envelope batching is trivially safe and
  delivers >25× headroom under threshold at all densities.
- **E2 resolves** the "is per-row filter envelope batchable?" question
  posed by the Critical Structural Finding section: yes, via the
  padded-row strategy through the existing `lowPass(...)` primitive,
  no `MultiOrderFilter` changes required, with 8× headroom at 256
  notes/measure.
- **E3 demonstrates** the two envelopes compose under batching with
  kernel fusion — at production density (64+ notes/measure) the
  combined chain runs faster than `E1 + E2` separately.

Architectural recommendations and design-document revisions are
deliberately **out of scope** for this memo. Part 2 of the replanning
will integrate these findings into the Phase 3 design.
