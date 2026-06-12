# AudioScene Redesign — State of Play

> The single big-picture document for the **audio scene redesign / real-time
> playback / PDSL DSP** effort. Read this first; the other docs in this folder are
> evidence and reference for what is asserted here. Last re-baselined 2026-06-11
> against `feature/audio-scene-pdsl` (the PDSL mixdown cutover branch, PR #296;
> the earlier `feature/batched-audio-mtl` work is fully merged to master).
> Development has moved from the M4 laptop to an M1 desktop — more memory, older
> chip — so timing figures will not line up exactly: figures carried over from
> the M4 are labelled *historical*, and figures re-measured on the M1 are
> labelled *(M1)*.

---

## 1. The goal

Resume the redesign to get a **real-time `AudioScene` rendering pipeline** with four
properties:

1. **Batched pattern rendering compatible with real-time generation**, including live
   *swapping* of the `Genome` that decides which elements are on the timeline — when the
   genome changes, the arrangement is re-rendered on the fly without tearing down the
   pipeline.
2. **All DSP defined by PDSL files** — the `MixdownManager` / `EfxManager` signal path
   expressed declaratively, not hand-wired in Java.
3. **Acoustic parity with the last released version** — the redesigned pipeline must
   approximately reproduce the behavior of the released system this work starts from.
4. **Near-enough to real-time** that we can start reasoning about the trade-offs needed
   to get *under* ratio-of-1.

The headline performance target is **ratio-of-1**: render time per tick ≤ the audio
duration of that tick. At 44.1 kHz with a 4096-frame tick the budget is **~92.9 ms/tick**.
We are chasing *keep-up*, not sub-millisecond latency.

## 2. Where we are now (the short version)

The picture is more nuanced than "two of three concerns solved." The a2 batching
*mechanism* is proven, but its *integration into the full real-scene render path* is not,
and a cross-cutting compile-reuse gap (kernel-pool exhaustion) now sits in front of any
full-scene work.

| Concern | Status |
|---|---|
| **a2 batching — the mechanism** (per-note rendering was ~89% of the cost, ~99.4% of it JNI dispatch) | **PROVEN.** Graph batching collapses N per-note `evaluate()` calls into one dispatch per pattern-layer per tick. Wired (`PatternLayerManager.enableBatched` / `AR_PATTERN_BATCHED`). Re-verified (M1) on the *synthetic* sentinel path: dispatch fires, output matches per-note within <1% RMS, 3000 sustained dispatches, ~12× under the per-tick budget for the pattern layer in isolation. See §2 verification below. |
| **a2 batching — real-scene integration** | **OPEN.** On the full curated-library scene, batched dispatch does **not** reliably fire for the real pattern path (some methods render `peak=0.0` / silence), and full-scene renders exhaust the native kernel pool (next row). The full-scene test `BatchedRealSceneRenderTest` is `@Ignore`d for this reason. This is integration/scaling work, *not* a defect in the batching kernel. |
| **Compile-reuse / `GeneratedOperation` pool** | **OPEN (cross-cutting blocker).** Structurally-identical computations recompile instead of reusing a cached kernel because argument-aggregation-target buffers have a `null` signature; each rebuild consumes a slot from a fixed pool. The pool was expanded (to `GeneratedOperation5999`) as a **stopgap, not a fix**. See [../SIGNATURE_AGGREGATION_GAP.md](../SIGNATURE_AGGREGATION_GAP.md). |
| **Metal dispatch ceiling** (host wedged forever past ~2300–2560 cumulative dispatches) | **SOLVED.** Fixed and merged; 3000+ sustained Metal dispatches verified, re-confirmed (M1) by the sustained-dispatch sentinel test. See [METAL_SUSTAINED_DISPATCH.md](METAL_SUSTAINED_DISPATCH.md). |
| **a3 — frame-buffer DSP/mixdown** | **MIGRATED TO PDSL (this branch, 2026-06-11), parity validated by ear — but NOT yet realtime.** The full mixdown/efx/reverb DSP runs as one compiled PDSL model per buffer (`mixdown_master_wet`). Benchmarked (M1): the PDSL full tick is ~1.9 s vs the CellList's ~0.3 s at 8192 frames (budget 185.76 ms) — a constant per-tick overhead independent of buffer size and pattern density that must be eliminated (§5). Acoustic gaps are accepted approximations, inventoried in [PDSL_SIGNAL_PATH_DIFFERENCES.md](PDSL_SIGNAL_PATH_DIFFERENCES.md). |

**Verified (M1, 2026-06-09) — synthetic sentinel path, `studio/music`:** the batched
pattern mechanism fires and is correct in isolation. `BatchedDispatchSentinelTest`
(`batchedDispatchCount=1, fallback=0`), `BatchedVsPerNoteRmsTest` (relative RMS 0.31% vs
per-note, non-silent), `BatchedRealtimeTickTest` (equivalence relative RMS 0.65%;
sustained run: **3000 dispatches, 0 fallback, non-silent, avg 7.76 ms/tick vs 92.9 ms
budget → ratio ≈ 0.084** at 4096-frame buffers, drift stable). These use *synthetic*
`FileNoteSource` inputs — they validate the kernel/dispatch, not the full scene.

**Historical (M4, 2026-06-03) — real curated library, full fx/mixdown, default hybrid
routing.** *The library is now present on the M1 (real-scene PDSL A/B and benchmark run
against it), but `BatchedRealSceneRenderTest` itself remains `@Ignore`d on the
compile-reuse blocker, so these specific batched-path figures are still M4-historical.*
- All 6 channels rendered **real, non-silent audio** end-to-end; a 5-minute continuous
  render completed near real-time (**~1.1× the budget at 4096–8192-frame buffers**) with
  bounded memory.
- A profile attributed **99.6% of the tick to a single operation: `f_loop_7651`
  "Loop ×4096 [JNI]"** — the per-frame mixdown loop, running on CPU.

**Hard constraints learned the hard way:**
1. **Real-time requires HYBRID routing — never force `AR_HARDWARE_DRIVER`.** The
   framework uses JNI (CPU) and Metal *together*. Forcing `mtl` fails to compile the
   mixdown loop (Metal's 31-buffer-argument-per-kernel limit); forcing `native` is
   ~14× slower. The default (unset) router splits work correctly. See
   [KNOWN_ISSUES.md](KNOWN_ISSUES.md).
2. **Continuous rendering requires `-DAR_PATTERN_CACHE_PERSIST=true`.** Without it,
   native memory leaks (~150 MB/buffer) and the per-tick ratio explodes from ~1.1× to
   70×+ before OOM. It is a `static final` read at class load, so it must be a JVM
   `-D` arg.
3. **Full-scene renders need the compile-reuse gap closed (or worked around).** Until
   structurally-identical kernels are cached rather than recompiled, every fresh scene
   build burns kernel-pool slots and eventually exhausts them, cascading into unrelated
   `AudioScene` test failures. See [../SIGNATURE_AGGREGATION_GAP.md](../SIGNATURE_AGGREGATION_GAP.md).

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

- **a2 batching (the mechanism).** `PatternFeatures.render` routes through
  `BatchedPatternLayerRenderer` when `enableBatched` is set; melodic-SSS fused kernel +
  accumulate-reduce, buckets `{16,32,64,128,256,512}`, `destOffsets[]` scatter placement,
  resample-as-producer. Sentinel discipline (`batchedDispatchCount>0`,
  `fallbackCount==0`). Evidence: [PATTERN_RENDERING_FLOOR.md](PATTERN_RENDERING_FLOOR.md);
  design-as-built: [NOTE_GRAPH_SHAPES.md](NOTE_GRAPH_SHAPES.md). **Caveat:** validated on
  the synthetic sentinel path only — real-scene integration is open work (see §2 and §5).
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

**Blockers in front of full-scene work (do these first):**
- **Compile-reuse / kernel-pool exhaustion.** Structurally-identical computations
  recompile rather than reuse a cached kernel (null signature on argument-aggregation
  targets), so each scene rebuild consumes `GeneratedOperation` slots until the pool is
  exhausted and unrelated `AudioScene` tests start failing. The pool expansion shipped so
  far is a stopgap. A real fix (valid signatures for aggregation targets, or gating
  aggregation) is prerequisite to re-enabling `BatchedRealSceneRenderTest` and to any
  sustained full-scene render. See [../SIGNATURE_AGGREGATION_GAP.md](../SIGNATURE_AGGREGATION_GAP.md).
- **a2 real-scene dispatch.** Batched dispatch fires on synthetic notes but not reliably
  on the real curated-library pattern path (some methods render `peak=0.0`). Make batched
  dispatch fire for every production note shape — the a1→a2 seam is "classify-and-dispatch"
  over a closed set of shapes (see §7 and [NOTE_GRAPH_SHAPES.md](NOTE_GRAPH_SHAPES.md)),
  so an unhandled real shape silently falls through to silence.
- ~~Curated library on this machine~~ — **resolved:** `/Users/Shared/Music/Samples`
  (~6k WAVs) and a valid `pattern-factory.json` are present on the M1; all real-scene
  tests run (they skip gracefully where the library is absent, e.g. CI).

**The cutover itself — DONE (2026-06-11, this branch): parity reached.**
`AudioSceneRealtimeRunner` houses both strategies behind
`MixdownManager.enablePdslMixdown` (`AR_PDSL_MIXDOWN`, default off): the established
CellList path and a Block-forward PDSL path (pattern prep unchanged; one compiled
`mixdown_master_wet` forward per buffer; output streamed to the master line; never
`wrapBlockAsCellList`). Level/sweep parity on the full 40s real-scene A/B with a
faithful reverb-ON control was validated by ear by the owner: windowed RMS ratios
0.88–1.03, overall 0.94–0.99, sweeps tracking, deterministic re-render via persisted
`scene-settings.json` + fixed genome seed (`AudioScenePdslCutoverTest.realSceneAbReview`).
The defect history (six masking defects) and durable lessons:
[EFX_PDSL_PARITY_PLAN.md](EFX_PDSL_PARITY_PLAN.md). The complete inventory of remaining
(accepted) path differences and their swap impact:
[PDSL_SIGNAL_PATH_DIFFERENCES.md](PDSL_SIGNAL_PATH_DIFFERENCES.md).

**Outstanding work (the actual to-do list):**
- **Fix `ringWrite`'s multi-frame ring rebuild (top performance item — ROOT CAUSE
  LOCALIZED 2026-06-11).** The PDSL full tick is ≈1.9 s vs the CellList's ≈0.3 s at
  8192 frames (M1, `AudioScenePdslBenchmarkTest`,
  `studio/compose/results/pdsl-cutover/benchmark.txt`; constant per tick, genome- and
  buffer-size-independent). Stage timing
  (`AudioScenePdslBenchmarkTest.pdslTickStageTiming`) attributed 2067 ms of the
  2134 ms tick to `compiled.forward`; layer bisection
  (`MixdownManagerPdslVerificationTest.mixdownLayerForwardTiming`, synthetic args at
  6×8192) attributed THAT to the reverb `delay_network`: main bus 22 ms, efx bus
  66 ms, reverb 1382 ms at the production 2-frame ring (128 ms at 1 frame, 2921 ms at
  4 frames — ≈1.4 s per additional ring frame). The cause is
  `MultiChannelDspFeatures.ringWrite`: for rings longer than one frame it rebuilds the
  ENTIRE ring as a per-slot masked `concat` chain (concat compiles to pad+add, so the
  graph becomes hundreds of tiny kernels whose per-dispatch overhead dwarfs their
  ~0.01 ms kernel time — the OperationProfile shows ops invoked ~500× per forward).
  Fix: express the ring update as ONE kernel over `[channels * bufSize]` using
  index-derived masks (compare `floor(offset/signalSize)` to the frame-aligned head)
  and gathers, with no concat; then re-validate parity (the construct is in the
  validated signal path) and re-run the benchmark. An earlier "~20 ms steady state"
  note measured the DSP portion alone and must not be cited as the tick rate.
- **Lean pattern prep.** `createPdsl` still builds the unused Java mixdown CellList as
  a side effect of reusing `getCells` for pattern preparation. Build-time-only cost
  (PDSL build ≈52 s vs CellList ≈70 s — the PDSL build already skips compiling the
  CellList's tick loop); worth removing, but it is not the tick-rate problem.
- **True stereo.** The PDSL path is dual-mono (one master duplicated to both writers).
  Stereo is a real, expected feature (stereo samples with distinct L/R must carry
  through independently) and must be one model carrying twice the channels in a
  *single* forward — never the pipeline run twice (that attempt was reverted).
- **Flip the default.** `enablePdslMixdown` is off by default; turning it on for
  production (ringsdesktop) awaits the swap-impact review
  ([PDSL_SIGNAL_PATH_DIFFERENCES.md](PDSL_SIGNAL_PATH_DIFFERENCES.md) §7) and CI.
- **Gene HP/LP `choice()` in compiled models.** The efx gene filter renders LP-only
  until PDSL supports `choice(...)` inside a compiled graph.
- **Per-frame automation (only if ever audible).** Automation steps per buffer
  (~186 ms at 8192); production envelopes are slow enough that this is inaudible.
- **Live genome swapping (goal #1) — mechanism exists, correctness partially open.**
  `AudioSceneMultiGenomeTest.multiGenomeFullRunner` swaps genomes on a live runner
  (memory-stability validated); `AudioScenePdslBenchmarkTest` exercises the same
  protocol on the PDSL path. Open: validating the swap's *audio* correctness under
  continuous live output, and the build-time-sampled argument staleness
  ([PDSL_SIGNAL_PATH_DIFFERENCES.md](PDSL_SIGNAL_PATH_DIFFERENCES.md) §5 — wet/efx
  filter coefficient banks lag the genome until rebuild).
- **Variable channel count.** PDSL `channels` is fixed at block-build time; gene-driven
  channel activation is still Java. The runner guards: selections that are not a
  zero-based contiguous prefix of size ≥ 2 fall back to the CellList path.
- **a1/a2/a3 ring decoupling** (to reach Diagram A): make `PatternAudioBuffer` rolling,
  run a2 on a worker K buffers ahead, point a3 at PDSL `Block`s pumped by
  `BufferedOutputScheduler`.

**The confirmation question this phase had to answer — HALF ANSWERED (2026-06-11):**
*can all signal processing be expressed in PDSL with acoustic parity and at/under the
real-time budget?* **Parity: YES** — validated by ear + windowed RMS on the full
real-scene A/B. **Budget: NOT YET** — the full PDSL tick runs ~10× over budget on the
M1 (vs ~1.6–2× for the CellList tick), dominated by a constant per-tick overhead that
is independent of buffer size and pattern density (see the benchmark item above). The
expressiveness question is settled; the remaining work is engineering the constant out
of the tick, not re-architecting the DSP.

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
  limit, `floor()` resample ambiguity, cache-persist requirement, compile-reuse /
  kernel-pool exhaustion, real-scene dispatch gap).
- **[PDSL_SIGNAL_PATH_DIFFERENCES.md](PDSL_SIGNAL_PATH_DIFFERENCES.md)** — the
  authoritative inventory of every remaining difference between the PDSL and CellList
  paths (DSP approximations, determinism, staleness, swap impact). Read before flipping
  `enablePdslMixdown` anywhere.
- **[EFX_PDSL_PARITY_PLAN.md](EFX_PDSL_PARITY_PLAN.md)** — done-record of the DSP parity
  work: the parity standard, the six masking defects and their lessons, and pointers to
  the remaining follow-ups.
- **[../SIGNATURE_AGGREGATION_GAP.md](../SIGNATURE_AGGREGATION_GAP.md)** — the cross-cutting
  compile-reuse blocker (null signature on argument-aggregation targets →
  `GeneratedOperation` pool exhaustion). Lives one level up because it is not
  audio-specific, but it currently gates full-scene rendering.

Related but out of scope here: `../AUDIO_SCENE_BENCHMARK_INVESTIGATION.md` (a separate
heap-retention workstream) and the `docs/internals/` references
(`celllist-realtime-streaming.md`, `features-pattern.md`).
