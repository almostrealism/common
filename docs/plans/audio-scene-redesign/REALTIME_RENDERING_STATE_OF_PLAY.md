# Real-Time AudioScene Rendering — State of Play (master)

> **Purpose.** A brief, honest summary of where the real-time AudioScene effort
> stands *on master* (the deeper experimental branch is deliberately left out of
> scope here). It exists so we can stop reasoning incrementally and start
> reasoning about a whole-pipeline redesign. Companion file:
> [`REALTIME_RENDERING_DIAGRAMS.md`](REALTIME_RENDERING_DIAGRAMS.md).

---

## 1. The goal, stated plainly

Render an `AudioScene` fast enough that the audio for the next chunk of time is
always ready before the speaker needs it — "ratio-of-1" (render time per tick ≤
the audio duration of that tick). At 44.1 kHz with a 4096-frame tick the budget
is **~92.9 ms/tick**. We are not chasing sub-millisecond latency; we are chasing
*keep-up*.

## 2. What already exists on master (the parts that work)

These are real and merged — the redesign builds *on* them, not around them.

- **Buffered streaming already exists.** `AudioScene.runnerRealTime(output,
  channels, bufferSize)` (`studio/compose/.../AudioScene.java`) returns a
  `TemporalCellular` that renders **one buffer at a time**, not the whole song
  offline. Each buffer is two phases:
  1. **Setup phase (Java):** `PatternAudioBuffer.prepareBatch()` →
     `PatternSystemManager.sum()` → `PatternLayerManager.sumInternal()` →
     `PatternFeatures.render()` — pattern generation *and* per-note audio
     synthesis happen here, on the host.
  2. **Tick phase (compiled):** a per-frame loop applies effects/mixdown and
     writes the output buffer.
- **`BufferedOutputScheduler`** (`engine/audio`) is a working real-time pump:
  read → process → write, with buffer-group safety pausing, degraded-mode
  detection, and a rendering-gap metric. The a3 "play frames in real time"
  machinery is largely solved.
- **The PDSL audio DSP substrate landed on master.** The whole DSP/mixdown half
  is now expressible declaratively: per-channel kernel splitting (`for each
  channel`), `repeat`/`route`/`sum_channels`, stateful `biquad`/`delay`/`lfo`,
  and **producer-valued arguments** (`producer([shape])`) for genome/automation.
  This makes the a3 layer compileable as `Block`s rather than hand-wired
  `CellList`s. (See `PDSL_AUDIO_DSP.md`, `AUDIO_SCENE_REDESIGN.md` §2A.)
- **Per-buffer plumbing was hardened:** `consolidateRenderBuffers()` (single
  contiguous backing buffer for all pattern outputs), `EfxManager`
  filter-buffer consolidation, and `warmNoteCache()` (pre-compile note kernels
  before the real-time loop starts).
- **A batched-render path is prototyped.** `BatchedPatternRenderer` exists with
  an `enableBatched` flag on the `PatternFeatures`/`PatternLayerManager` render
  dispatch, plus a Metal codegen fix (the `floor((long)global_id)` overload
  ambiguity) so the batched kernels compile on Apple GPUs.

## 3. What the profiling actually says

Measured on a 32-measure render, M1 Ultra, 4096-frame tick
(`AUDIO_SCENE_REDESIGN.md` §1, `PATTERN_RENDERING_FLOOR.md`):

| Component | Time/tick | Fraction |
|-----------|-----------|----------|
| **Pattern generation** (`sumInternal` + `PatternFeatures.render`) | ~278 ms | **~89%** |
| DSP / mixdown kernel | ~33 ms | ~11% |
| **Total** | **~311 ms** | (3.3× over the 92.9 ms budget) |

And the decomposition of the 278 ms is the punchline:

- It is **not** arithmetic and **not** allocation. At 64 notes/measure,
  **~99.4% of the per-note cost is JNI dispatch overhead** — the cost of
  crossing the Java↔native boundary once per note via
  `note.getProducer(-1).evaluate()` in `PatternFeatures.renderPerNote()`.
- The `PatternRenderingFloorBenchmark` showed that composing **all** of a tick's
  notes into a **single** `CollectionProducer` evaluation drops the kernel cost
  by **100–1500×**, landing every chain variant well under 92.9 ms even on the
  slowest (Linux/JNI CPU) backend. The 4-kernel batched chain *with* FIR runs in
  1.89 ms; a 2-kernel form in 0.90 ms.

The compute is essentially free. **The dispatch frequency is the whole problem.**

## 4. Why the incremental approach has stalled

Each increment has been correct and individually worth doing — and none of them
touch the load-bearing cost:

- **GC-during-render (Phenomenon Y)** caused 5–10× per-tick spikes. Mitigated by
  `System.gc()` at the genome boundary + buffer consolidation. *Attacks pauses,
  not the 278 ms.*
- **Heap retention (Phenomenon X)** — `NoteAudioProvider.audioCache` and Metal
  kernel artifacts retained across scenes. Partially addressed. *Attacks memory
  growth, not the 278 ms.*
- **Cache hit-rate / allocation churn** in `NoteAudioCache` /
  `RenderedNoteAudio`. Each cache hit removes *one* dispatch. *Linear nibbling
  at a cost that is structurally N-dispatches-per-tick.*
- **`Process.optimized()`** was tried on the per-note chain and made it **~15%
  slower** — the optimizer is for graphs with reuse/isolation targets, and the
  per-note chain is a flat pipeline.

The pattern is always the same: we keep optimizing *inside* the
"one-evaluate-per-note" structure. The benchmark says the structure itself is
the cost. No amount of caching, consolidation, or GC tuning changes
N-dispatches-per-tick into 1-dispatch-per-tick. **That requires a structural
change, which is why the increments converge to a wall.**

## 5. The three layers, and why our pieces resist them

The redesign target is three layers with very different economics:

| Layer | What it does | Cheapness | How far ahead it can run | Today's home |
|-------|--------------|-----------|--------------------------|--------------|
| **a1 — pattern generation** | Decide *which* notes exist: positions, pitches, durations, ADSR/automation scalars | Cheap (pure scheduling) | **Minutes** | First half of `PatternLayerManager.sumInternal` (frame→measure, which repetitions/elements overlap, `getNoteDestinations`) |
| **a2 — per-note audio rendering** | Synthesize each note's samples: resample + volume env + filter env + automation | Costly | **Seconds** | `PatternFeatures.renderPerNote` + `RenderedNoteAudio` + `Parameterized{Volume,Filter}Envelope` |
| **a3 — frame-buffer rendering** | Mix the live notes into N frames and play them | Most performance-sensitive | **Just-in-time** | Compiled per-frame loop: `MixdownManager`/`EfxManager` (→ PDSL `Block`s), pumped by `BufferedOutputScheduler` |

**We have every individual piece.** What we lack is the ability to run them on
*different clocks*. Today a1 and a2 are fused into one synchronous per-buffer
`prepareBatch()` call, and a3 cannot start until that call returns. The
`CollectionProducer` pattern is a *pull* graph evaluated to completion at one
instant — it does not naturally express "a1 ran 90 s ago, a2 ran 2 s ago, a3 is
running now, and they hand off through rolling buffers." Each layer wants its own
granularity (whole-arrangement / few-seconds-of-notes / one frame-buffer) and its
own cadence, and the single-`evaluate()` model collapses all three into one.

## 6. Where the redesign has to focus

1. **Split a1 from a2.** Make scheduling produce a *plain data* note schedule
   (offsets, pitch ratios, ADSR/automation tensors) far ahead of time — no
   `evaluate()` in this layer.
2. **Batch a2 (the required structural fix, "Phase 3").** Replace
   N-evaluates-per-tick with **one** `CollectionProducer` per pattern-layer per
   tick, fed by the a1 schedule, written into a rolling pattern-audio buffer a
   few seconds ahead of playback. The benchmark proves this is both necessary
   and sufficient. The open design question is **variable note scheduling**
   (different start/length/pitch per note) as a static graph — pad+mask vs
   pitch-class tiling vs fixed-shape batches.
3. **Keep a3 compiled and decoupled.** Drive `MixdownManager`/`EfxManager` (as
   PDSL `Block`s) from `BufferedOutputScheduler`, consuming a2's rolling buffer.
   Use the scheduler's gap/back-pressure to pace a2, and a1's far-ahead schedule
   to pace a1.

The companion diagrams file draws (a) the clean three-layer ideal, (b) what we
actually do today, and (c) a path to (a) using the classes we already have.

## 7. Key files (ground truth)

- `studio/compose/.../AudioScene.java` — `runnerRealTime`, `getCells`,
  `getPatternChannel`, `consolidateRenderBuffers`, `warmNoteCache`
- `studio/music/.../pattern/PatternSystemManager.java` — `sum`, `warmNoteCache`
- `studio/music/.../pattern/PatternLayerManager.java` — `sum`, `sumInternal`,
  `noteAudioCache`, `enableBatched`
- `studio/music/.../pattern/PatternFeatures.java` — `render`, `renderPerNote`,
  per-note `getProducer(-1).evaluate()`
- `studio/music/.../pattern/PatternAudioBuffer.java` — `prepareBatch`,
  `getOutputProducer`
- `studio/music/.../notes/RenderedNoteAudio.java` — `offsetArg`,
  `producerFactory`, `getProducer`
- `studio/arrange/MixdownManager.java`, `EfxManager.java` — DSP/mixdown
- `engine/audio/.../BufferedOutputScheduler.java` — real-time pump
- `compose/.../dsl/audio/*` + `ml/.../resources/pdsl/audio/*.pdsl` — PDSL DSP
- Benchmarks: `PatternRenderingFloorBenchmark`, `AudioSceneBenchmark`

## 8. Related planning docs

- `AUDIO_SCENE_REDESIGN.md` — the phased plan (Phase 1 DSP cutover, Phase 3
  batched compilation, Phase 4 convergence)
- `audio-scene-redesign/PATTERN_RENDERING_FLOOR.md` — the benchmark that proves
  batching is necessary and sufficient
- `audio-scene-redesign/ENVELOPE_INVESTIGATION.md` — batching the a2 envelopes
- `audio-scene-redesign/PATTERN_SYSTEM_PHASE3_DESIGN.md` — the batched-render
  design
- `audio-scene-redesign/METAL_AMBIGUITY_BATCHED_RENDERER.md` — Metal codegen fix
- `PDSL_AUDIO_DSP.md` — the declarative DSP substrate
- `AUDIO_SCENE_BENCHMARK_INVESTIGATION.md` — heap/GC regression work
