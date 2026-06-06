# AudioScene Redesign — State of Play

> The single big-picture document for the **audio scene redesign / real-time
> playback / PDSL DSP** effort. Read this first; the other docs in this folder are
> evidence and reference for what is asserted here. Last re-baselined 2026-06-03
> against branch `feature/batched-audio-mtl` (verified by direct experiment, not
> inference).

---

## 1. The goal

Render an `AudioScene` at **ratio-of-1**: render time per tick ≤ the audio
duration of that tick. At 44.1 kHz with a 4096-frame tick the budget is
**~92.9 ms/tick**. We are chasing *keep-up*, not sub-millisecond latency.

## 2. Where we are now (the short version)

Two of the three historical concerns are **solved**; the third is now the whole game.

| Concern | Status |
|---|---|
| **a2 — per-note pattern rendering** (was ~89% of the cost, ~99.4% of it JNI dispatch) | **SOLVED.** Graph batching collapses N per-note `evaluate()` calls into one dispatch per pattern-layer per tick. Wired (`PatternLayerManager.enableBatched` / `AR_PATTERN_BATCHED`), correctness-validated, and now cheap/amortized. No longer the bottleneck. |
| **Metal dispatch ceiling** (host wedged forever past ~2300–2560 cumulative dispatches) | **SOLVED.** Fixed and merged; 3000+ sustained Metal dispatches verified. See [METAL_SUSTAINED_DISPATCH.md](METAL_SUSTAINED_DISPATCH.md). |
| **a3 — frame-buffer DSP/mixdown** | **NOW THE BOTTLENECK.** The per-frame mixdown/effects loop is the dominant cost and the only thing between us and ratio-of-1. |

**Verified this session (real curated library, full fx/mixdown, default hybrid routing):**
- All 6 channels render **real, non-silent audio** end-to-end; a 5-minute continuous
  render completes near real-time (**~1.1× the budget at 4096–8192-frame buffers**)
  with bounded memory.
- A profile attributes **99.6% of the tick to a single operation: `f_loop_7651`
  "Loop ×4096 [JNI]"** — the per-frame mixdown loop, running on CPU. Pattern
  batching (a2) is a one-time, amortized cost (~6–11 ms/buffer).

**Two hard constraints learned the hard way:**
1. **Real-time requires HYBRID routing — never force `AR_HARDWARE_DRIVER`.** The
   framework uses JNI (CPU) and Metal *together*. Forcing `mtl` fails to compile the
   mixdown loop (Metal's 31-buffer-argument-per-kernel limit); forcing `native` is
   ~14× slower. The default (unset) router splits work correctly. See
   [KNOWN_ISSUES.md](KNOWN_ISSUES.md).
2. **Continuous rendering requires `-DAR_PATTERN_CACHE_PERSIST=true`.** Without it,
   native memory leaks (~150 MB/buffer) and the per-tick ratio explodes from ~1.1× to
   70×+ before OOM. It is a `static final` read at class load, so it must be a JVM
   `-D` arg.

## 3. The three layers, and the target shape

The redesign target is three layers with very different economics, decoupled by
ring buffers so each runs on its own clock:

| Layer | Job | Cost | Horizon | Home |
|---|---|---|---|---|
| **a1 — pattern generation** | decide *which* notes exist (positions, pitch, ADSR, automation) | cheap | minutes | `PatternLayerManager.sumInternal` (scheduling half) |
| **a2 — per-note rendering** | synthesize each note's samples (resample + envelopes) | costly, **now batched** | seconds | `BatchedPatternLayerRenderer` → `BatchedPatternRenderer` |
| **a3 — frame-buffer rendering** | mix live notes into N frames and play them | **most sensitive — the bottleneck** | just-in-time | `MixdownManager`/`EfxManager` → `BufferedOutputScheduler` |

**Diagram A — the target (three layers, three clocks, two rings):**

```
   horizon: MINUTES            horizon: ~SECONDS            horizon: NOW (just-in-time)
   cadence: rare / on-edit     cadence: every few buffers   cadence: every audio buffer
   grain:   whole arrangement  grain:   a window of notes    grain:   one frame-buffer
   cost:    cheap              cost:    costly (batched)      cost:    must beat the clock

   ┌──────────────────┐    ┌────────────────────────┐    ┌─────────────────────────┐
   │ a1 PATTERN GEN   │    │ a2 PER-NOTE RENDER      │    │ a3 FRAME-BUFFER RENDER  │
   │ genome→note events│   │ for the next few seconds│    │ pull next 1024/4096     │
   │ positions, pitch, │   │ of notes: resample →    │    │ frames from note-audio  │
   │ duration, ADSR    │   │ vol env → filter env →  │    │ ring, run mixdown/fx →  │
   │  ── PURE DATA ──  │   │ ONE batched dispatch    │    │ write to output line    │
   └────────┬─────────┘    └───────────┬────────────┘    └────────────┬────────────┘
            │ note-SCHEDULE            │ note-AUDIO                    │ output
            ▼  ⟿ ring (minutes ahead)  ▼  ⟿ ring (seconds ahead)       ▼ line / DAC
       [ schedule buf ] ─────────▶ [ audio ring buf ] ─────────▶ [ speaker ]
                  back-pressure ◀── pacing ◀── playback clock ◀── BufferedOutputScheduler gap

   INVARIANTS: a3 is the only layer bound to the real-time clock; a1/a2 run AHEAD and
   never block a3. Each seam is a buffer, so each layer has its own grain and cadence.
```

**Where we actually are vs. Diagram A:** the *old* state fused a1+a2 into one
synchronous per-buffer call with N JNI dispatches and no ring before a3. The a2
batching work collapsed the N dispatches into one (the proven 100–1500× win), so the
synthesis cost is no longer the wall. What remains to fully reach Diagram A:
**(1)** decouple a1/a2/a3 onto independent clocks via rolling buffers (today
pattern audio is bulk-rendered once in `setup`, not streamed seconds-ahead), and
**(2)** move a3 (the DSP loop) onto the PDSL substrate so it can be optimized — the
subject of the next phase.

## 4. What has landed

- **a2 batching.** `PatternFeatures.render` routes through `BatchedPatternLayerRenderer`
  when `enableBatched` is set; melodic-SSS fused kernel + accumulate-reduce, buckets
  `{16,32,64,128,256,512}`, `destOffsets[]` scatter placement, resample-as-producer.
  Sentinel discipline (`batchedDispatchCount>0`, `fallbackCount==0` at production
  density). Evidence: [PATTERN_RENDERING_FLOOR.md](PATTERN_RENDERING_FLOOR.md);
  design-as-built: [NOTE_GRAPH_SHAPES.md](NOTE_GRAPH_SHAPES.md).
- **Metal sustained dispatch.** [METAL_SUSTAINED_DISPATCH.md](METAL_SUSTAINED_DISPATCH.md).
- **Real-time streaming substrate (a3 plumbing).** `AudioScene.runnerRealTime`,
  `BufferedOutputScheduler` (gap/back-pressure/degraded-mode), `warmNoteCache`,
  `consolidateRenderBuffers`. The output side is largely solved.
- **The PDSL audio DSP substrate (merged to master).** This is the foundation the
  next phase builds on:
  - **Primitives:** `fir`, `scale`, `identity`, `lowpass`, `highpass`, `biquad`,
    `delay`, `lfo`, and **`delay_network`** (multi-tap feedback reverb — *implemented*,
    `AudioDspPrimitives.java` / `MultiChannelDspFeatures.delayNetworkBlock`).
  - **Multi-channel constructs:** `channels:` header, `for each channel { }`,
    `repeat(N)`, `route(matrix)` (square *or* rectangular), `sum_channels()`,
    `accum_blocks(...)`. Per-channel kernel splitting falls out of how `for each
    channel` compiles — no graph-analysis pass needed.
  - **Producer-valued args:** every primitive the live system drives with a
    `Producer` (volume, cutoffs, delay time, automation) accepts `producer([shape])`;
    bound at dispatch via `PdslPrimitiveContext.toProducer`. Literal calls compile
    identically to before.
  - **PDSL files** (`engine/ml/src/main/resources/pdsl/audio/`): `efx_channel.pdsl`,
    `mixdown_channel.pdsl`, `delay_feedback_bank.pdsl`, `mixdown_manager.pdsl`.
  - **Adapter + tests:** `MixdownManagerPdslAdapter` (`buildArgsMap`,
    `wrapBlockAsCellList`), `MixdownManagerPdslTest` / `MixdownManagerPdslVerificationTest`.
  Full inventory and the row-by-row migration map: [PDSL_AUDIO_DSP.md](PDSL_AUDIO_DSP.md).

## 5. The next phase — migrate the DSP loop to PDSL

**Why:** a3 (the per-frame mixdown/effects loop) is now the bottleneck, it is
frame-recurrent stateful DSP (biquad/delay/reverb state carried frame→frame) that
cannot parallelize across time, and it is hand-wired as a `MixdownManager`/`EfxManager`
`CellList`. Re-expressing it in PDSL puts it in front of the same ML-model optimization
tooling we use elsewhere — so we can attack it (parallelize across channels/voices,
split recurrent from non-recurrent ops onto Metal, block-process where the recurrence
allows) instead of being stuck with one sequential JNI loop.

**What remains (open work):**
- **Production cutover.** `MixdownManager.createCells()` is still the production path.
  Replace/A-B-flag it with `MixdownManagerPdslAdapter`. (The adapter currently applies
  one shared producer across channels — per-channel-distinct automation is a known gap.)
- **EfxManager PDSL rendition.** No PDSL file renders the EFX bus's automation-driven
  wet/dry path yet; `efx_channel.pdsl` is feedforward-only.
- **Variable channel count.** PDSL `channels` is fixed at block-build time; gene-driven
  channel activation is still Java.
- **a1/a2/a3 ring decoupling** (to reach Diagram A): make `PatternAudioBuffer` rolling,
  run a2 on a worker K buffers ahead, point a3 at PDSL `Block`s pumped by
  `BufferedOutputScheduler`.

**The confirmation question this phase must answer** (to be designed next): *can all
signal processing be expressed in PDSL with acoustic parity and at/under the real-time
budget?* — i.e. an A/B equivalence + performance gate across the full
MixdownManager/EfxManager surface, on hybrid routing.

## 6. Options that are moot

The original redesign framed the DSP problem as a choice among hand-built CellList
architectures (A: channel-scoped CellLists; B: flat-buffer pipeline; C:
hybrid + graph-analysis copy-elimination; D: stream-oriented). **PDSL is the answer to
all of them** — `for each channel` gives per-channel kernels, the Block model
eliminates the Cell/CachedStateCell copy chain, and the declarative expression tree
makes a graph-analysis pass unnecessary. Do not revive these; build on PDSL.

## 7. Durable lessons (don't re-derive)

- **Expression-level micro-optimization of GPU kernels is futile** — the native
  compiler already does LICM/strength-reduction/CSE; hand-factoring the expression tree
  produced zero measurable kernel-time change. (From the loop-optimization journal, now
  retired.)
- **Do NOT apply `Process.optimized()` to the per-note / flat-pipeline chain** — it
  *added* ~15% overhead. The optimizer is for graphs with isolation/reuse targets, not
  flat pipelines.
- **The a1→a2 seam is "classify-and-dispatch," not "compile an arbitrary graph"** —
  production notes are a tiny closed set of shapes. The prior attempt failed by assuming
  one source per note (`innermostAudio()`) when every real note is a 3-source merge.
  See [NOTE_GRAPH_SHAPES.md](NOTE_GRAPH_SHAPES.md).

## 8. Key files

| Path | Role |
|---|---|
| `studio/music/.../pattern/PatternLayerManager.java` | a1 scheduling + `enableBatched` / `cachePersist` |
| `studio/music/.../pattern/PatternFeatures.java` | per-note vs batched render dispatch |
| `studio/music/.../pattern/BatchedPatternLayerRenderer.java` | a2 batched dispatch (studio side) |
| `engine/audio/.../BatchedPatternRenderer.java` | a2 fused melodic-SSS kernel |
| `studio/compose/.../arrange/MixdownManager.java`, `EfxManager.java` | a3 DSP (cutover target) |
| `studio/compose/.../arrange/MixdownManagerPdslAdapter.java` | genome → PDSL args bridge |
| `studio/compose/.../dsl/audio/AudioDspPrimitives.java`, `MultiChannelDspFeatures.java` | PDSL audio primitives |
| `engine/ml/src/main/resources/pdsl/audio/*.pdsl` | PDSL DSP layer definitions |
| `engine/audio/.../line/BufferedOutputScheduler.java` | a3 real-time pump |
| `studio/compose/.../optimize/RealtimeContinuousRenderer.java` | continuous-render harness (`AR_RT_*`) |

## 9. Document map (this folder)

- **STATE_OF_PLAY.md** (this doc) — the big picture; start here.
- **[PDSL_AUDIO_DSP.md](PDSL_AUDIO_DSP.md)** — the next-phase centerpiece: PDSL DSP
  capabilities, the MixdownManager migration map, the producer-arg model, the cutover.
- **[NOTE_GRAPH_SHAPES.md](NOTE_GRAPH_SHAPES.md)** — the production note model and how
  a2 batching works (as built).
- **[PATTERN_RENDERING_FLOOR.md](PATTERN_RENDERING_FLOOR.md)** — the benchmark evidence
  that justified a2 batching.
- **[METAL_SUSTAINED_DISPATCH.md](METAL_SUSTAINED_DISPATCH.md)** — the resolved Metal
  dispatch-ceiling fix (done-record).
- **[KNOWN_ISSUES.md](KNOWN_ISSUES.md)** — live platform constraints (Metal 31-buffer
  limit, `floor()` resample ambiguity, cache-persist requirement).

Related but out of scope here: `../AUDIO_SCENE_BENCHMARK_INVESTIGATION.md` (a separate
heap-retention workstream) and the `docs/internals/` references
(`celllist-realtime-streaming.md`, `features-pattern.md`).
