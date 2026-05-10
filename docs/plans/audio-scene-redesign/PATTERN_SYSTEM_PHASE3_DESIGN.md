# Pattern System — Phase 3 Implementation Design

**Status:** Design document, May 2026 (branch `feature/audio-scene-redesign`).
**Source data:** `PatternRenderingFloorBenchmark` (Additions 1–6, May 2026).
**Companion docs:** [PATTERN_RENDERING_FLOOR.md](PATTERN_RENDERING_FLOOR.md),
[AUDIO_SCENE_REDESIGN.md](../AUDIO_SCENE_REDESIGN.md) (Phase 3 section).

This document describes **how the current per-note pattern rendering path moves to
the batched architecture validated by the benchmark.** It is the bridge between the
benchmark result ("batched 4-kernel padded-FIR chain runs in 1.89ms at 64 notes/m
on Linux/JNI CPU — 49× under threshold") and the production code that has to be
restructured to reach that shape. The benchmark proves the kernel cost. This
document proves the implementation plan.

It does not write code. It identifies what changes, where, and in what order; it
names the central design decisions and recommends a position on each; and it
flags the questions implementation will surface that the design cannot pre-answer.

The intended reader is the agent (or human) implementing Phase 3. Reading this
end-to-end takes ~25 minutes; after, the implementation can begin without
re-deriving the design from the benchmark and current code.

---

## Contents

1. [Per-kernel mapping: current code → batched architecture](#1-per-kernel-mapping)
2. [Variable-note-scheduling](#2-variable-note-scheduling)
3. [Cutover strategy](#3-cutover-strategy)
4. [PDSL vs `CollectionProducer` for the new substrate](#4-substrate)
5. [Edge cases and complications](#5-edge-cases)
6. [Scope: Phase 3 vs deferred](#6-scope)
7. [Open questions](#7-open-questions)

---

<a id="1-per-kernel-mapping"></a>
## 1. Per-Kernel Mapping: Current Code → Batched Architecture

The benchmark uses a four-kernel chain (resample → volume envelope → FIR → accumulate)
applied to all N notes for one tick in a single compiled `Producer`. The current
production code applies the same logical operations, but each lives in a different
file with its own scheduling and call-site idioms. The first job of Phase 3 is to
identify each kernel in current code, its current shape, its target shape, and the
restructuring needed to bridge them.

Citations below are file path + symbol (class/method) + the central line range.
Line numbers should be treated as a reading aid, not a stable target — the
implementer should locate the methods by name, not by line.

### 1.1 Resample (Kernel 1) — Linear lerp from `[SOURCE_SIZE]` to `[NOTE_SIZE]`

**Where it lives today:**
[`engine/audio/.../notes/NoteAudioProvider.java`](../../../engine/audio/src/main/java/org/almostrealism/audio/notes/NoteAudioProvider.java),
`computeAudio(NoteAudioKey)` (around line 259). It is **not a Producer kernel
today.** The method returns
`() -> args -> provider.getChannelData(channel, ratio, sampleRate)` — a thin
`Producer` wrapper around a native call into the `WaveDataProvider` that returns
a fully-resampled `PackedCollection`. The result is cached in the static
`audioCache` (`CacheManager<PackedCollection>`, max 200 entries) keyed by
`NoteAudioKey(targetPitch, channel)`. Cache hits return the captured
`PackedCollection` directly. The resampled buffer is then wrapped as a constant
via `c(getShape(key), audioCache.get(...))` (line 247) for downstream Producers
to consume.

**Current input/output shape:**
- Per-note. Input: `[SOURCE_SIZE]` (varies per note source, typically O(seconds × sample_rate)).
  Output: `[resampled_count]` (depends on pitch ratio).
- Granularity: one resample per `(target_pitch, channel)` pair across the entire
  arrangement. Heavy cache reuse when many notes share a pitch class.

**Target input/output shape (batched):**
- Per-tick. Input: `[SOURCE_SIZE]` source plus a per-row index tensor
  `[N, NOTE_SIZE]` of fractional gather positions (or `[N]` of pitch ratios that
  the kernel turns into per-row indices).
- Output: `[N, NOTE_SIZE]` resampled audio.
- The benchmark's `buildBatchedResampleVolume`
  ([`PatternRenderingFloorBenchmark.java:688–711`](../../../engine/audio/src/test/java/org/almostrealism/audio/benchmark/PatternRenderingFloorBenchmark.java))
  is the reference: a single gather kernel that maps every flat output index back
  to `(note, sample)` via mod/floor and reads from the source at `sample_idx ×
  resample_ratio`. The benchmark uses a single source and single ratio; production
  needs per-row source and per-row ratio.

**Restructuring required:**
- **Move resample out of `WaveDataProvider` and into a `Producer` kernel.** This
  is the largest single change for kernel 1. Today the native call sits behind
  the `WaveDataProvider` interface — there is no Producer-compiled resample
  primitive. Add one (or place it on a class that already represents a sample
  bank — see §1.5 caching for why this matters).
- **Express per-note pitch ratio as a tensor input.** Instead of one ratio per
  cache key, build a `[N]` ratio buffer per tick. The kernel reads
  `ratio[note_idx]` and gathers `source[floor(sample_idx × ratio[note_idx])]`
  with linear interpolation between consecutive samples.
- **Handle per-note source selection.** Production notes draw from different
  sample sources (different `WaveDataProvider`s for different drums or
  instruments). The benchmark uses one. The batched kernel needs either
  (a) a single packed source bank `[total_samples_across_all_sources]` with
  per-note offsets and lengths, or (b) one batched kernel per source bank with
  the notes pre-grouped by source. Option (b) is closer to how `NoteAudioChoice`
  already partitions notes; option (a) is denser but requires a bank-construction
  step per tick.
- **Pitch-ratio cache replaces audio cache (see §5.5).** The current `audioCache`
  is keyed on `(pitch, channel)` and caches the resampled audio. In the batched
  form, the resample is recomputed every tick (cheap inside a batched kernel).
  The cache's role shifts from "avoid resampling" to "avoid recomputing the
  resampled buffer for sustained notes that span ticks" — which is what
  `NoteAudioCache` already does at the post-render level.

**Notes for the implementer:**
- The benchmark's resample uses `RESAMPLE_RATIO = SOURCE_SIZE / NOTE_SIZE = 2.0`
  uniformly. Production has per-note variation; the kernel arithmetic is
  identical, but the ratio comes from a per-note input rather than a constant.
- The lerp itself is two gathers + one fmadd; the JNI cost dwarfs the arithmetic
  (Addition 2). Don't over-optimize the math.

---

### 1.2 Volume Envelope (Kernel 2) — Multiply by ADSR

**Where it lives today:**
[`studio/music/.../notes/PatternNoteLayer.java`](../../../studio/music/src/main/java/org/almostrealism/music/notes/PatternNoteLayer.java)
factory method (line 131–133):
`filter = (audio, duration, automationLevel) -> factor.getResultant(audio)`,
applied by
[`studio/music/.../notes/PatternNoteAudioAdapter.java`](../../../studio/music/src/main/java/org/almostrealism/music/notes/PatternNoteAudioAdapter.java)
`computeAudio` (line 90–119). The composed Producer is
`sampling(sampleRate, offset, frameCount, () -> getFilter().apply(delegate.getAudio(...), c(noteDuration), automationLevel.getResultant(c(0.0))))`.
ADSR shapes specifically are passed in via `Factor<PackedCollection>` from the
genome (`automationLevel`) and applied via element-wise multiply.

**Current input/output shape:**
- Per-note Producer applied once per `.evaluate()` call. Input shape matches the
  note's audio (the result of resample, after caching). Output shape is the same
  as input — element-wise multiply.

**Target input/output shape (batched):**
- Input: `[N, NOTE_SIZE]` (from kernel 1 output) and either a tiled `[N, NOTE_SIZE]`
  envelope or a `[NOTE_SIZE]` envelope expanded by broadcast.
- Output: `[N, NOTE_SIZE]`.
- The benchmark's
  `tiledEnvelope = cp(envelope).repeat(batchSize).reshape(shape(totalSamples))`
  (`PatternRenderingFloorBenchmark.java:709`) is the reference. In production,
  per-note ADSR (different attack/decay/sustain/release per note's automation
  parameters) needs a per-row envelope rather than one shared envelope; that's
  a `[N, NOTE_SIZE]` envelope tensor.

**Restructuring required:**
- **Detach the envelope from `NoteAudioFilter.apply`.** The filter abstraction
  applies on a single Producer. The batched form needs to operate on `[N, M]`.
  Either:
  - (a) Build the per-row envelope tensor offline (Java side) and pass it as a
    Producer-constant input to the batched kernel, or
  - (b) Compute the envelope inside the batched kernel from per-row
    `(attack, decay, sustain, release)` parameters — a richer kernel but cleaner
    for variable note durations (see §5.4).
- **Pick a representation for automation level.** Today's `automationLevel.getResultant(c(actualTime).add(time))`
  in `ScaleTraversalStrategy.createRenderedNote` is a per-note `Factor` that
  resolves to a Producer of the time-varying gain. The batched form needs
  `[N, NOTE_SIZE]` gain values per tick. Either materialize them per-row in Java
  before the batched dispatch, or push the automation evaluation inside the
  batched kernel as a per-row computation. Recommend (a) for the first cut and
  treat (b) as an optimization if the per-tick materialization becomes a
  measurable Java cost.

**Notes for the implementer:**
- `PatternNoteLayer`'s filter chain already supports multiple stacked filters
  (multiple `PatternNoteLayer`s composed). The batched form must preserve this:
  every active filter in the layer chain composes into the batched producer
  graph. For Phase 3's minimum-viable form (see §3 sequencing), start with the
  single envelope filter and pin the multi-filter case as a follow-on.

---

### 1.3 FIR Filter (Kernel 3) — Lowpass `MultiOrderFilter`

**Where it lives today:** Not in pattern rendering. The per-channel FIR lowpass
lives downstream — in the DSP path through
[`studio/compose/.../arrange/MixdownManager.java`](../../../studio/compose/src/main/java/org/almostrealism/studio/arrange/MixdownManager.java)
and the PDSL `mixdown_main` / `mixdown_channel` layers
([`engine/ml/src/main/resources/pdsl/audio/mixdown_channel.pdsl`](../../../engine/ml/src/main/resources/pdsl/audio/mixdown_channel.pdsl)).
The benchmark folds it into the per-note batched chain to measure what would
happen if the per-channel FIR moved upstream of accumulation (or if a per-note
FIR were added). Today no per-note FIR runs inside `PatternFeatures.render`.

**Current input/output shape (DSP path):** `[channel_signal_size]` per channel
per tick. Applied once after the pattern accumulator has summed all notes for
the channel.

**Target input/output shape (batched, if folded upstream):** `[N, NOTE_SIZE + 2 *
filterOrder/2]` (padded) → `[N, NOTE_SIZE]` (post-filter, after trimming the
pad or after the reduction takes the centre). The benchmark's
`buildBatchedPaddedFirChain` (`PatternRenderingFloorBenchmark.java:935–954`) is
the reference.

**Restructuring required (and the design decision):**

Three positions are tenable here, and the design should pick one:

- **3A. Keep FIR downstream of pattern accumulation (status quo).** Phase 3 only
  batches the per-note chain *up to* accumulation; the per-channel FIR runs once
  per channel per tick after the pattern accumulator finishes. **Pro:** smallest
  change to the DSP path; per-channel FIR is already once per tick, so there is
  no JNI-dispatch problem to solve. **Con:** the benchmark's 1.89ms number assumes
  FIR is in the batched chain — moving it out doesn't make pattern rendering
  slower, but the "single Producer per tick" goal becomes "one batched Producer
  for pattern + one for FIR." Not a regression.
- **3B. Fold FIR into the per-note batched chain.** Each note's audio gets its
  own filter pass before accumulation. **Pro:** truly one batched Producer per
  pattern layer per tick. **Con:** changes acoustic semantics — today a single
  per-channel FIR receives the sum of all notes; in the per-note form each note
  is filtered independently. For a linear filter the math is the same (LTI: filter
  of sum = sum of filters), but only if the per-note FIR has identical
  coefficients to the per-channel FIR. The moment a per-note filter with
  per-note coefficients is desired, the position becomes asymmetric.
- **3C. Add a per-note FIR as a new musical capability.** The benchmark
  inadvertently demonstrates this: per-note filtering with arbitrary coefficient
  banks is now affordable. **Pro:** new sonic capability — per-note timbral
  shaping (e.g., velocity-dependent brightness). **Con:** new product surface,
  not just a refactor; should be its own design.

**Recommendation: 3A for Phase 3.** Keeping the per-channel FIR downstream
preserves acoustic equivalence with the current path and minimizes the cutover
surface area. The benchmark's padded-FIR demonstration is valuable as evidence
that per-row FIR is technically feasible, but Phase 3 should not also be the
moment that FIR's musical role changes. Revisit 3B/3C as a follow-on once 3A
ships.

**Implication:** Phase 3's batched per-tick chain is **3 kernels, not 4**:
resample, envelope, accumulate. The benchmark's 4-kernel numbers (1.89ms at 64
notes/m) are a strict upper bound; the 3-kernel form at the same density is
faster still (Addition 4 shows ~0.90ms for a 2-kernel form — the floor for the
3-kernel form is somewhere between 0.90ms and the 6.39ms Addition 3 number,
which included a tile-and-add output that the reduction form replaces).

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

**Restructuring required:**
- **Remove per-note `AudioProcessingUtils.getSum().sum(...)` calls.** They become
  one kernel-fused reduction per pattern layer per tick.
- **Solve note-position scatter.** The batched reduction collapses N notes that
  start at the *same* destination offset. In production, notes start at
  *different* offsets within a tick (see §2). Three positions for handling this:
  - (a) Pad each note's row to span the full tick width (`[NOTE_SIZE_TICK]`)
    with zeros before/after the note's actual content; the reduction sums all
    notes' shifted rows into one tick buffer. Memory: `O(N × tick_width)`.
  - (b) Sort notes into sub-batches by their start offset within the tick; emit
    one batched reduction per sub-batch into the same destination buffer at the
    correct offset (Java-side accumulator handles sub-batch combination).
  - (c) Use a scatter-add primitive: emit `[N, NOTE_SIZE]` plus a
    `[N]` per-note destination-offset tensor; a scatter-add kernel writes each
    row into the destination buffer at the per-note offset.

Position (a) is the simplest expression and matches the benchmark, but memory
grows with `tick_width` instead of `NOTE_SIZE`. Position (c) is the cleanest but
relies on a scatter-add primitive that may not exist as a fused-with-reduction
operation in the framework today. Position (b) is the safe middle.

**Recommendation: position (a) for the first cut**, as long as tick widths are
modest (32 measures × samples-per-measure is the typical production tick). If
profiling shows the pad-zero memory cost is meaningful, move to (c) once the
scatter-add primitive is validated. Re-evaluate during implementation.

---

### 1.5 Where caching fits

The current `NoteAudioCache` (`studio/music/.../pattern/NoteAudioCache.java`)
caches **the post-evaluate `PackedCollection` of each note's full audio**, keyed
by absolute frame offset. The cache exists because notes can span multiple
buffer ticks: when the next tick re-renders, the cached audio is summed without
re-evaluating the producer.

In the batched form, **the per-tick batched dispatch is so fast (~1ms) that
caching individual notes within a tick is uneconomical** — even building the
cache costs more than recomputing. But cross-tick caching still has a role: if
a note spans 4 ticks, the same `[noteLength]` audio is read by 4 ticks'
accumulate passes. Two options:

- **Demote `NoteAudioCache` to a per-note cross-tick cache only.** Notes whose
  duration > `tick_width` get materialized once into a long buffer; the per-tick
  accumulate reads from that buffer (offset by the appropriate amount). Notes
  with duration ≤ `tick_width` go through the batched per-tick chain without
  hitting the cache.
- **Remove `NoteAudioCache` entirely; have the batched chain recompute long
  notes each tick.** Simpler, costs O(tick_width × N_overlapping_long_notes)
  per tick — likely fine given the batched chain's speed.

Recommend the first option (preserve `NoteAudioCache` for genuinely long notes,
remove its per-tick role). Implementation can spot-check the second option once
metrics are in place; if the per-tick cost on production-scale arrangements
stays well below threshold without it, drop the cache.

The static `audioCache` in `NoteAudioProvider` is a different concern (resampled
sample buffers, not rendered note audio). It can stay — sample resample results
are still useful as Producer-constant inputs to the batched kernel. See §5.5.

---

<a id="2-variable-note-scheduling"></a>
## 2. The Variable-Note-Scheduling Problem

The benchmark uses a fixed N per measurement. Production has variable N per
tick: a typical 32-measure tick contains a different number of notes depending
on pattern density, section activity, and the genome's per-layer choices.
Phase 3 has to handle this without (a) recompiling the producer every tick, or
(b) leaving 99% of the speedup on the table by padding to a worst-case fixed N.

### Three candidate approaches

**A. Pad every tick to a fixed maximum N.** Pick `N_MAX` large enough that
production density never exceeds it (e.g., 256 notes/measure × 32 = 8192).
Silent rows pad to N_MAX; the reduction sums them but they contribute zero.

- **Pro:** One compiled producer for the entire program lifetime. Reuse is
  perfect across ticks. No per-tick compilation cost.
- **Pro:** Memory predictable.
- **Con:** Wastes compute on padded silent rows. At low densities (16 notes/m =
  512 notes) padding to 8192 means ~94% of the work is silent. Addition 6
  numbers say the 256 npm batched chain runs in ~2.5ms; if 16 npm pays the same
  ~2.5ms instead of dropping to ~0.7ms, that's still well under threshold but
  uses 3–4× the energy of necessary.
- **Con:** Per-row inputs (pitch ratio, source offset, envelope parameters)
  must be padded to N_MAX every tick.

**B. Recompile the producer per N.** Each tick with a different N rebuilds the
batched kernel.

- **Pro:** No padding waste; every tick uses exactly the compute it needs.
- **Con:** **Catastrophic.** Compilation is the new bottleneck. Benchmark
  compile times for the batched chain at production densities are in the
  hundreds of milliseconds (`PatternRenderingFloorBenchmark` logs
  `compileStart`/`compileMs` per density). 100ms+ per tick wipes out every gain
  the batched form delivers.

**C. Bucket N to a small fixed set (16/32/64/128/256/512 notes per measure ×
32 = 6 distinct values). Each tick ceiling-rounds to the next bucket and pads
to that bucket's N.**

- **Pro:** One compiled producer per bucket, reused across ticks. Six compiled
  producers total per pattern layer.
- **Pro:** Padding waste bounded — worst case is 2× when N is just over a
  bucket boundary (e.g., 33 notes pads to 64).
- **Pro:** No per-tick compilation cost after warmup.
- **Con:** Six times the compiled-kernel memory vs option A. Compilation cost
  paid up-front, not per-tick.

**D. (Proposed during reading.) Single compiled producer with N as a Producer
input via an `[N_MAX]` mask tensor.** The kernel always runs N_MAX rows but
ignores rows where `mask[row] == 0`. Same shape as A, but with a runtime gate
rather than implicit zero contribution from silent envelopes.

- **Pro:** Single compiled producer; no per-tick recompile.
- **Con:** Equivalent to A in compute cost (rows still run); the mask is just
  a more explicit zero-out. May or may not be faster than A's implicit zeros
  depending on whether the framework branch-predicts the mask.

### Recommendation: C (bucketing) for production; A (worst-case pad) for the
### first implementation milestone.

The first integration milestone wants to validate the end-to-end batched path
with the simplest scheduling — A's worst-case pad has the fewest moving parts.
Once acoustic equivalence is verified and the batched path is the live route,
swap to C (bucketing) for the production cost profile. The bucket boundaries
should match the existing `NOTES_PER_MEASURE_VALUES` benchmark densities
(16, 32, 64, 128, 256) — the kernel has been measured at exactly those points
and the cost is known.

**Open question for implementation:** what's the cost of compilation per bucket?
The implementation should measure compile time per bucket on production
hardware before settling on the bucket count. If compile takes seconds, fewer
buckets (3 instead of 6) may be better. If milliseconds, finer-grained
bucketing (8 buckets) may be worth the memory.

---

<a id="3-cutover-strategy"></a>
## 3. Cutover Strategy

Three candidate approaches:

**A. Side-by-side (feature flag).** Build the new batched path alongside the
old per-note path; route via a runtime flag (system property,
`SystemUtils.isEnabled("AR_PATTERN_BATCHED")`). The flag defaults to off; flip
to on once acoustic equivalence is verified. Easy rollback, easy A/B comparison.

- **Pro:** Implementation can ship landed-but-disabled; the rest of the team
  proceeds while the implementer iterates without breaking master.
- **Pro:** Acoustic-equivalence testing has both paths present in the same
  binary — verifiable on identical fixtures.
- **Con:** Maintaining both paths during transition. Each new orchestration
  change has to update both.

**B. Incremental kernel-by-kernel replacement.** Replace kernel 4 (accumulate)
first — a per-tick reduction instead of per-note `getSum().sum(...)` calls.
Then kernel 2 (envelope), then kernel 1 (resample). Each milestone leaves
acoustic output valid.

- **Pro:** Smaller diffs, each independently verifiable.
- **Con:** The win is JNI-dispatch elimination; replacing one kernel at a time
  doesn't deliver until enough kernels are folded into one batched producer.
  Until then, the per-note Producer is still being evaluated N times — no
  speedup.
- **Con:** Each milestone leaves a half-batched, half-per-note path that's
  harder to reason about than either endpoint.

**C. Atomic replacement.** One PR replaces the whole per-tick pattern rendering
path with the batched version. Smaller total diff, larger blast radius.

- **Pro:** Cleaner end state — no legacy per-note path to maintain.
- **Con:** If anything's wrong, pattern rendering breaks until reverted.
  Acoustic regressions might only surface on specific genome configurations.

### Recommendation: A (side-by-side with feature flag).

The combination of "acoustic correctness is critical," "the new path is
substantial enough to ship in pieces," and "rollback should be one config
change" makes the flag worth the duplication cost during transition. The flag
should be `AR_PATTERN_BATCHED` (default off) read at `PatternLayerManager`
construction. The new path lives in a new class/method named for what it does
(`BatchedPatternRenderer`, or a sibling method `sumBatched` on `PatternLayerManager`
— see §6 for placement).

**Verification mechanism:**
1. Build a **`PatternRenderingPdslVerificationTest`** (or
   `PatternRenderingBatchedVerificationTest`) modeled after the existing
   [`MixdownManagerPdslVerificationTest`](../../../studio/compose/src/test/java/org/almostrealism/studio/ml/test/MixdownManagerPdslVerificationTest.java).
   The latter compares Java and PDSL DSP paths via per-channel energy + sum-of-
   squared-difference, writing WAVs to `results/pdsl-audio-dsp/` for audit. The
   new test compares the per-note path (flag off) and the batched path
   (flag on) on the same `AudioScene` fixtures, with the same genome, the same
   pattern shape, the same destination buffer. Tolerance starts loose
   (energy ratios within ~5%, since float reordering will produce small
   numerical drift) and tightens as the implementation matures.
2. **Reuse existing tests.** The pattern-rendering tests in
   `studio/compose/src/test/java/.../pattern/` (`RealTimeRenderingTest`,
   `HeapPatternRenderingTest`, `AudioSceneRealTimeTest`,
   `AudioSceneRealTimeCorrectnessTest`, `AudioSceneMultiGenomeTest`,
   `AudioSceneBufferConsolidationTest`, `AudioSceneBaselineTest`,
   `OptimizerSceneDiagnosticTest`, `PatternFactoryTest`,
   `AudioSceneOptimizationTest`) all run against the per-note path today. Phase
   3 should run **each of these tests with the flag both off and on**; the
   batched path passes when it produces equivalent output on every existing test
   that exercises pattern rendering. CI can parametrize this once the cutover
   is on a stable footing.
3. **Per-tick latency assertion.** Add a smoke benchmark that runs one tick
   through the batched path and asserts `tick_ms < 92.9 × 0.5` (well below
   ratio-of-1 with margin). This prevents quiet regressions where someone adds
   a Java-side step that re-introduces per-note JNI dispatches.

**Flag removal.** Once the batched path has shipped, the verification suite is
green across CI runs, and a release window has passed, remove the flag and
delete the per-note `PatternFeatures.render` path. This is a separate PR — do
not bundle it with the cutover.

---

<a id="4-substrate"></a>
## 4. PDSL vs Raw `CollectionProducer`

The benchmark uses raw `CollectionProducer` composition
(`buildBatchedResampleVolume`, `buildBatchedPaddedFirChain`). The DSP path
(Phase 1) is moving to PDSL. The long-term trajectory in
[`AUDIO_SCENE_REDESIGN.md`](../AUDIO_SCENE_REDESIGN.md) Section 5 / Phase 4 is
"PDSL for everything that benefits from being a tensor operation" — including
pattern rendering eventually.

Three positions:

**A. PDSL from the start.** Build the new pattern rendering as a PDSL program,
co-locating with the DSP cutover (Phase 1) substrate.

- **Pro:** Aligns with the long-term direction stated in the redesign plan.
- **Pro:** Composes naturally with DSP — both halves of the pipeline are
  declarative.
- **Con:** **PDSL primitives needed by pattern rendering don't yet exist.**
  Specifically, no PDSL primitive performs the per-row gather-and-lerp that
  kernel 1 needs (linear resample with per-row pitch ratio); no PDSL primitive
  performs per-row scatter-into-destination at variable offsets (kernel 4); no
  PDSL syntax expresses N-row batching with N varying per call.
- **Con:** Building those primitives is a non-trivial expansion of the PDSL
  substrate — weeks of work that compounds with Phase 3.

**B. Raw `CollectionProducer` for now, PDSL later.** Mirror what the benchmark
validated. Pattern rendering uses the same `CollectionProducer` API that
`PatternRenderingFloorBenchmark` already exercises.

- **Pro:** Lower implementation risk — every benchmark call site translates
  directly into production.
- **Pro:** The implementer is not also building PDSL primitives.
- **Con:** A future migration to PDSL is required to reach Phase 4. The
  migration is a second-pass cost.

**C. Hybrid.** Express the batched per-tick chain in raw `CollectionProducer`;
add hooks so the chain can later be replaced by a PDSL block without touching
the `PatternLayerManager` orchestration. Specifically, define a narrow
interface (`PerTickBatchedRenderer`) that today wraps a `CollectionProducer`
and tomorrow can wrap a PDSL `Block`.

- **Pro:** Captures the benefits of B (cheap implementation) plus an explicit
  migration path to A.
- **Con:** Interface design overhead — easy to get the boundary in the wrong
  place if the PDSL form isn't sketched.

### Recommendation: B (raw `CollectionProducer`) with light future-proofing.

The PDSL substrate (Phase 1) hasn't yet shipped a per-row gather primitive or
N-variable batching; adding those alongside the pattern cutover risks
overcommitting Phase 3. The benchmark code is the closest thing to a working
prototype that Phase 3 has — translating it to production has the smallest
implementation surface.

Two light future-proofing measures (don't grow into option C):

1. **Group the kernel-construction calls into one method** that takes the
   per-tick inputs and returns a `CollectionProducer<PackedCollection>`. The
   caller (the new orchestration) holds a compiled `Evaluable`. When the time
   comes to swap in PDSL, the call site changes — the orchestration above and
   the genome plumbing below do not.
2. **Document the assumed PDSL primitive surface** (resample with per-row
   ratio, scatter-add at per-row offset, N-variable batching) in this design
   doc (above). When the PDSL substrate adds them, the migration becomes
   mechanical.

The PDSL conversion of pattern rendering is **Phase 4**, not Phase 3 — see §6
(scope). Trying to do both at once is the failure mode.

---

<a id="5-edge-cases"></a>
## 5. Edge Cases and Complications

Each of the following is easy to skip in a happy-path design and expensive to
retrofit later.

### 5.1 Polyphony — multiple notes at the same time position

In the per-note path each note independently produces audio and adds to the
destination; concurrent notes have identical destination offsets but independent
producers, no special handling required. In the batched path, polyphony is
**natural and free**: each polyphonic voice is just another row in the
`[N, NOTE_SIZE]` batched tensor, and the reduction sums them. The current
code's `ScaleTraversalStrategy.CHORD` already emits multiple `RenderedNoteAudio`
entries per `PatternElement` for chord positions; those become rows just like
any other note.

**Verify during implementation:** that the batched accumulator's reduction
correctly handles same-offset multiple notes (the row-sum is right by
construction, but the destination-offset mapping has to put them in the same
slot). This is testable with a fixture: a `CHORD` pattern element with 3 scale
positions should produce the same destination audio as 3 separate
`PatternElement`s at the same position with one scale position each.

### 5.2 Tail decay — notes that ring past their nominal end

A note's audio can ring past `NOTE_SIZE`: envelope release, filter resonance,
reverb tail, and (in some sample-based note types) a non-trivial decay tail
in the source. The current path handles this via `noteLength = audio.getShape().getCount()`
(`PatternFeatures.sumToDestination` line 182) — the full evaluated note buffer
is summed up to the tick boundary, with overlap computed against `endFrame`.
Notes that span multiple ticks are cached (`NoteAudioCache`) and accumulated
across ticks.

The batched form has to preserve this:

- For notes whose `expectedFrameCount ≤ NOTE_SIZE_TICK` (typical short notes),
  the row is `[NOTE_SIZE_TICK]` zero-padded and the reduction works as designed.
- For notes whose `expectedFrameCount > NOTE_SIZE_TICK` (sustained, long-tail
  notes), the design has options:
  - (a) Render the note's *full* audio into a separate per-note buffer (one
    batched pass per tick range it spans), keep the buffer in `NoteAudioCache`
    by the note's start offset, and during accumulation read from it at the
    correct per-tick offset.
  - (b) Truncate at the tick boundary (acoustically wrong; loses release tail).
  - (c) Allow a note's row to span more than one tick — render N notes ×
    `[K * NOTE_SIZE_TICK]` where K is the max tick-span across notes. Wasteful
    if only a few notes have long tails.

**Recommend (a):** keep `NoteAudioCache` for genuinely-long notes (its
existing role), with the per-tick batched chain reading from the cached buffer
for any note whose duration exceeds the tick. This preserves the cache's
purpose (cross-tick reuse) without making it the per-tick fast path.

### 5.3 Multi-layer rendering

`PatternLayerManager` may have up to 32 layers per pattern (`MAX_LAYERS = 32`,
`PatternLayerManager.java:122`). The benchmark used one layer. In production,
each `PatternLayer` contributes its own elements to the per-tick render.

Two positions:

- (a) **Each layer renders independently into the destination** (current
  behavior). Per layer per tick: gather notes, build batched producer,
  evaluate, sum into destination. Per pattern: N_layers batched dispatches per
  tick. At one batched dispatch per layer the cost is `layers × ~1ms ≈ 32ms`
  worst case — still well under threshold.
- (b) **All layers fold into one super-batched producer per tick.** All notes
  from all layers in one tensor, one batched dispatch per pattern per tick.
  More aggressive batching; same speedup ceiling because each layer's contribution
  is independent anyway.

**Recommend (a) for Phase 3.** Per-layer batching is sufficient to clear the
threshold by a wide margin. Per-pattern batching is a possible optimization for
Phase 3+ if profiling shows the per-layer JNI dispatches are still a measurable
cost. Don't optimize what doesn't need optimizing.

### 5.4 Note duration variance

Notes have different durations (`PatternElement.getEffectiveDuration` resolves
to a per-note seconds value, multiplied by sample rate to get
`expectedFrameCount`). The batched form assumes a uniform `NOTE_SIZE` per row.

Options:

- **Short notes (`duration < NOTE_SIZE_TICK`):** Render into `[NOTE_SIZE_TICK]`
  row, zero-pad after the note ends. The envelope shape handles release-tail
  fade. This is the natural form.
- **Long notes (`duration > NOTE_SIZE_TICK`):** See §5.2 — render to the cache,
  read from cache for each spanning tick.
- **Notes with explicit `frameCount` arguments:** Today the per-note path
  passes `frameCount` to `note.getProducer(frameCount)` so the resample/envelope
  kernels know how many frames to produce. The batched form needs to either
  produce uniform `NOTE_SIZE_TICK` rows (zero-padded for shorter notes) or per-
  row variable lengths. Uniform is the natural form for a single batched
  dispatch; the row-mask (option D in §2) or the implicit envelope zero
  (envelope value 0 outside the active range) accomplishes the same thing.

**Recommend:** uniform-width rows, per-note duration encoded in the envelope
shape (so the envelope drops to zero past the note's natural end). This means
no separate "duration" tensor — the envelope is the duration.

### 5.5 Sample caching

Current behavior: `NoteAudioProvider` caches resampled `PackedCollection`
buffers in the static `audioCache` (max 200 entries, LRU), keyed by
`NoteAudioKey(target_pitch, channel)`. Cache hit returns the captured
collection; cache miss invokes the native resample. This cache has high hit
rates in practice because note pitch classes recur (drum kit per kit, melody
within a scale).

In the batched form, the resample is folded into the per-tick kernel — every
tick recomputes every note's resample. **Is the cache still useful?**

Three positions:

- **(a) Keep the cache.** Cached resampled buffers serve as Producer constants
  fed into the batched kernel. The kernel reads from the cached source buffer
  per-row (no recomputation), saving the resample arithmetic. **Pro:** No
  regression on the kernel cost.
- **(b) Drop the cache; batched kernel recomputes resample.** The kernel reads
  from the raw source buffer; resample math runs every tick. **Pro:** Simpler
  state; no LRU bookkeeping. **Con:** Recomputes O(N × NOTE_SIZE) lerp ops per
  tick.
- **(c) Repurpose the cache.** Keep the resampled-source-buffer cache for the
  source bank construction. The pitch ratio applies per-tick from the live
  ratio tensor.

**Recommend (a) — keep the cache.** The cache's purpose (don't recompute the
linear lerp for the same `(pitch, channel)` pair) holds in the batched form
too: the kernel reads from the cached buffer rather than the raw source. The
cache produces one resampled `PackedCollection` per `(pitch, channel)` pair;
the batched kernel gathers from those buffers at per-row sample offsets. No
cache change required.

The per-note `NoteAudioCache` (different cache, post-evaluate audio) is
discussed in §1.5: demote to cross-tick-only.

### 5.6 Existing tests and acoustic validation

Pattern-rendering tests in `studio/compose/src/test/java/.../pattern/`:
`RealTimeRenderingTest`, `HeapPatternRenderingTest`, `AudioSceneRealTimeTest`,
`AudioSceneRealTimeCorrectnessTest`, `AudioSceneMultiGenomeTest`,
`AudioSceneBufferConsolidationTest`, `AudioSceneBaselineTest`,
`OptimizerSceneDiagnosticTest`, `PatternFactoryTest`,
`AudioSceneOptimizationTest`. All must keep passing through the cutover.

Add one new test class:

- **`PatternRenderingBatchedVerificationTest`** (new, in
  `studio/compose/src/test/java/.../pattern/test/`). Modeled after
  `MixdownManagerPdslVerificationTest`. Renders the same arrangement (the
  benchmark used random sources; this test wants a deterministic small fixture
  so per-sample comparisons are tractable) via both paths, compares per-channel
  energy + sum-of-squared difference, writes WAVs to `results/pattern-batched-v/`
  for audit. Initial tolerance loose (energy ratios within ~5%, since
  the reduction reorders float adds and the resample arithmetic may differ at
  ULP); tighten as the path stabilizes.

`HeapPatternRenderingTest` exists for a reason: the per-note path's
`Heap.stage(...)` pattern releases evaluated audio after each note. The
batched path has different memory dynamics — one batched buffer per tick,
released at tick end. The test should be reviewed to confirm the new lifecycle
matches its expectations or updated to match the new model.

### 5.7 Per-note `frameCount` and the offset arg

The current `RenderedNoteAudio.producerFactory` takes a `frameCount` int and
sets a `PackedCollection offsetArg` to the start frame. The signature
independence (`PatternFeatures.render` line 124–129) is intentional: the
compiled kernel is reused across different start-frame positions because the
offset is a runtime data argument, not a structural parameter.

In the batched form, the `offsetArg` per note becomes a `[N]` offset tensor
input to the batched producer. The same signature-independence property holds:
one compiled batched producer reused across ticks, with the offset tensor
varying per tick. The producer's compiled signature depends on N (or the
N-bucket), not on the specific offset values.

The renaming/relocation: `RenderedNoteAudio` itself probably stops being a
per-note holder of a `producerFactory` and becomes a per-note holder of inputs
(offset, pitch ratio, envelope params, source bank reference) that the batched
renderer harvests into `[N]` tensors. Or replace `RenderedNoteAudio` with a
flatter struct (or a builder accumulating `[N]` lists) consumed by the batched
renderer. Implementation choice.

---

<a id="6-scope"></a>
## 6. Scope: Phase 3 In-Scope vs Deferred

### In scope for Phase 3

- **Batched per-tick chain end-to-end for typical pattern shapes.** Three
  kernels (resample, envelope, accumulate-reduce) — FIR stays downstream of
  pattern accumulation (decision 3A in §1.3).
- **Variable-note-scheduling at production tick rates.** Bucketing (option C
  in §2) for production; the first integration milestone is allowed to use
  worst-case padding (option A) to land the path; the bucketing pass follows
  before the flag is flipped on by default.
- **Acoustic equivalence with the current path.** Verified by a new
  `PatternRenderingBatchedVerificationTest` modeled on
  `MixdownManagerPdslVerificationTest`. Existing pattern-rendering tests
  (`studio/compose/.../pattern/test/`) all keep passing with the flag both off
  and on.
- **Side-by-side cutover via `AR_PATTERN_BATCHED` flag.** New batched path
  added alongside; flag default off; flipped to on once verified.
- **`NoteAudioCache` reshaped to cross-tick-only.** Per-tick caching role
  removed; cross-tick caching role for long-tail notes retained.
- **Per-note resample becomes a Producer kernel.** Native call extracted from
  `WaveDataProvider.getChannelData` into a `CollectionProducer` that composes
  with the batched chain. The `NoteAudioProvider.audioCache` keeps its
  current role of caching the resampled `PackedCollection` per
  `(pitch, channel)`.
- **`PatternRenderingFloorBenchmark` becomes the production-shape benchmark.**
  Keep the existing benchmark, add a "production fixture" variant that uses
  the real `PatternLayerManager` orchestration and the new batched path —
  one tick measured end-to-end.

### Deferred to follow-on

- **Pattern rendering moves to PDSL.** Trajectory goal (Phase 4), not Phase 3.
  Section 4 above explains why.
- **Per-note FIR (new musical capability).** Position 3C in §1.3. New product
  surface, not a refactor — its own design.
- **Per-pattern (across-layers) super-batching.** Section 5.3 option (b).
  Possible Phase 3+ optimization if profiling shows the per-layer dispatches
  are a measurable cost.
- **Streaming / low-latency render path.** Pattern rendering at sub-tick
  granularity. Not in scope: the realtime target is ratio-of-1 (per-tick), not
  per-buffer.
- **Removal of the old per-note `PatternFeatures.render` path.** Happens once
  the batched path has shipped, verified, and burned in. Separate PR.
- **Flag removal.** Same as above. Separate PR.
- **Pattern variant batching (vector-space exploration).** Mentioned in
  `AUDIO_SCENE_REDESIGN.md` Section 4B as a Phase 3+ extension. Out of Phase 3
  scope; if the batched compilation graph is shaped right, batching across
  pattern variants is a re-batch dimension rather than a new architecture,
  so this can be added later without restructuring.

---

<a id="7-open-questions"></a>
## 7. Open Questions (Things The Design Cannot Pre-Answer)

These are flagged honestly because the design's view of them is incomplete and
implementation will surface the real shape:

- **Compilation cost per bucket (§2).** Production hardware compile times for
  the batched chain at each bucket density haven't been measured. The
  bucket-count recommendation (6 buckets at 16/32/64/128/256/512) assumes
  compile times are seconds-or-less — if hundreds of milliseconds, refine.
  The implementer should measure this on a production-equivalent machine
  before settling on bucket boundaries.

- **Reduction with variable destination offset (§1.4).** The recommendation
  (option (a): pad rows to tick width and reduce) assumes the
  `permute + traverse(1).sum()` approach Addition 6 validated scales to tick
  widths. The benchmark used `NOTE_SIZE = 1024`; production tick width is
  `samples_per_measure × 32`, an order of magnitude larger. Behavior at
  tick-width reduction sizes hasn't been benchmarked. Spot-check during
  implementation.

- **Long-note round-trip through `NoteAudioCache` (§5.2).** The recommendation
  (option (a): cache long notes, read from cache per-tick) preserves the
  current behavior, but how the batched chain reads from a cached
  `PackedCollection` at a per-row offset isn't a kernel pattern the benchmark
  exercises. May need a separate "cached-tail" branch in the per-tick
  producer, gated on whether any spanning notes are present in the tick.

- **Multi-filter chains (§1.2 note).** `PatternNoteLayer` permits stacking
  multiple `NoteAudioFilter`s. The benchmark uses one filter (volume
  envelope). The composition of multiple stacked filters in the batched form
  is mechanical but unverified — each filter's `apply` becomes a kernel
  composed into the batched producer. Verify during implementation that the
  framework correctly fuses multiple filter applies in one batched dispatch
  (it almost certainly does — kernel fusion is observed throughout the
  benchmark — but worth confirming on the first multi-filter test).

- **`Heap.stage(...)` and the batched path's memory model.** Current path uses
  `Heap.stage` per-note (`PatternFeatures.render` line 133/154) to release
  evaluated audio after sum-to-destination. The batched path's per-tick
  buffer has a different lifecycle: one per tick, released at tick end.
  Whether `Heap.stage` wraps the batched evaluate, or is replaced by an
  explicit `try-with-resources` on the batched buffer, is an implementation
  choice. Both work; pick the one that matches the surrounding code style.

- **Acoustic equivalence tolerance.** The reduction reorders floating-point
  additions; the resample-as-Producer arithmetic may differ from the cached
  native form at ULP. The right tolerance for
  `PatternRenderingBatchedVerificationTest` isn't known a priori. Start at
  ~5% energy match, run the existing pattern test suite against both paths,
  tighten if no real divergences are found.

- **Source bank packing (§1.1, last bullet).** Whether to pack all note
  sources into one buffer (option (a)) or group notes by source (option (b))
  depends on production note-source counts and how often the same source
  recurs across a tick. Implementation should profile both on a real
  arrangement before locking in.

---

## Reading order for the implementing agent

1. This document (you're here).
2. [PATTERN_RENDERING_FLOOR.md](PATTERN_RENDERING_FLOOR.md) — the benchmark
   results that justify the architectural choices.
3. [`PatternRenderingFloorBenchmark.java`](../../../engine/audio/src/test/java/org/almostrealism/audio/benchmark/PatternRenderingFloorBenchmark.java)
   — concrete kernel construction code (`buildBatchedResampleVolume`,
   `buildBatchedPaddedFirChain`, the reduction-accumulate test).
4. [`PatternFeatures.java`](../../../studio/music/src/main/java/org/almostrealism/music/pattern/PatternFeatures.java)
   `render` — the per-note path that needs to coexist (then retire) with the
   new batched path.
5. [`PatternLayerManager.java`](../../../studio/music/src/main/java/org/almostrealism/music/pattern/PatternLayerManager.java)
   `sumInternal` — the orchestrator that calls `render` and is the new
   batched path's entry point.
6. [`MixdownManagerPdslVerificationTest.java`](../../../studio/compose/src/test/java/org/almostrealism/studio/ml/test/MixdownManagerPdslVerificationTest.java)
   — the precedent for the new verification test.

---

## Summary

| Decision | Recommended position | Why |
|---|---|---|
| Per-kernel scope (§1) | 3 kernels in batched chain: resample, envelope, accumulate-reduce | FIR stays downstream (decision 3A) — smallest cutover, preserves acoustic semantics |
| Resample placement (§1.1) | Move resample from native `WaveDataProvider` call into a `Producer` kernel | Required for batching; `audioCache` keeps caching the source buffers |
| Variable N (§2) | Bucket to {16, 32, 64, 128, 256, 512} × 32 measures, ceiling-round per tick | No per-tick recompile; bounded padding waste; matches benchmark densities |
| Cutover (§3) | Side-by-side with `AR_PATTERN_BATCHED` flag, default off | Easy rollback; both paths verifiable in same binary |
| Substrate (§4) | Raw `CollectionProducer`, not PDSL (for Phase 3) | PDSL lacks the per-row gather/scatter primitives; PDSL conversion is Phase 4 |
| Verification (§3, §5.6) | New `PatternRenderingBatchedVerificationTest` modeled on `MixdownManagerPdslVerificationTest` + flag-parametrized existing tests | Acoustic-equivalence pattern is established; reusing it is one less novel thing |
| `NoteAudioCache` (§1.5, §5.2) | Demote to cross-tick-only | Per-tick caching is uneconomical when per-tick is ~1ms |
| Tail decay (§5.2) | Cache long notes; per-tick chain reads from cache at offset | Preserves existing behavior; uses `NoteAudioCache` for its actual job |
| Multi-layer (§5.3) | One batched dispatch per layer per tick (no super-batching) | 32 × 1ms = 32ms — well under threshold; cross-layer batching is an optimization |
| Sample cache (§5.5) | Keep `NoteAudioProvider.audioCache` as-is | Resampled `(pitch, channel)` buffers still useful as Producer constants |

The design is honest about what it can predict (the kernels, the verification
pattern, the cutover mechanic, the cache role) and what it can't (compile
costs at scale, ULP-level acoustic equivalence, optimal source bank packing).
Implementation will fill in the unknowns. The design has set them up to be
fillable in.
