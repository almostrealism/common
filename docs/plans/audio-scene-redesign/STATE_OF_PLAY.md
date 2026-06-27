# AudioScene Redesign — State of Play

> The single big-picture document for the **audio scene redesign / real-time playback /
> PDSL DSP** effort. Read this first; the other docs in this folder are evidence and
> reference for what is asserted here. Current as of 2026-06-26 against `master` — the
> PDSL mixdown cutover is **merged**. Performance figures are M1 measurements (the dev
> machine moved M4 → M1; numbers from the M4 are labelled *historical*). Benchmark
> figures come from `AudioScenePdslBenchmarkTest`, whose `results/` output is git-ignored,
> so they are reproducible-locally, not committed evidence.

---

## 1. The goal

A **real-time `AudioScene` rendering pipeline** with four properties:

1. **Batched pattern rendering compatible with real-time generation**, including live
   *swapping* of the `Genome` that decides which elements are on the timeline — re-rendered
   on the fly without tearing down the pipeline.
2. **All DSP defined by PDSL files** — the `MixdownManager` / `EfxManager` signal path
   expressed declaratively, not hand-wired in Java.
3. **Acoustic parity with the last released version** — approximately reproduce the
   behavior of the released system this work starts from.
4. **Near-enough to real-time** to start reasoning about getting *under* ratio-of-1.

The headline target is **ratio-of-1**: render time per tick ≤ the audio duration of that
tick. At 44.1 kHz the budget is **~92.9 ms** for a 4096-frame tick, **~185.8 ms** for 8192.

## 2. Where we are now

The a3 DSP/mixdown is **migrated to PDSL, parity-validated by ear, and runs under the
realtime budget** (faster than the legacy CellList tick). The efx-feedback parity has since
closed its three biggest character gaps, and a2 batched pattern dispatch is now validated
on real scenes. What remains for the full real-time vision is **true stereo**, flipping the
batched/PDSL defaults, and the a1/a2/a3 ring decoupling.

| Concern | Status |
|---|---|
| **a2 batching — the mechanism** (per-note rendering was ~89% of cost, ~99.4% JNI dispatch) | **PROVEN** on the synthetic sentinel path: dispatch fires, matches per-note within the enforced RMS bound, 3000 sustained dispatches, well under the per-tick budget for the pattern layer in isolation. → [A2_BATCHED_DISPATCH.md](A2_BATCHED_DISPATCH.md). |
| **a2 batching — real-scene integration** | **RESOLVED (2026-06-26).** On the full curated-library scene batched dispatch fires correctly and renders non-silent audio: single melodic channel `batchedDispatchCount=1388 / fallback=0 / peak=0.51`; all six channels `2220 / 4568(percussive fall back by design) / peak=0.56`. `BatchedRealSceneRenderTest` is re-enabled. Unblocked by the argument-aggregation rebuild; the a2 classification path was unchanged. → [A2_BATCHED_DISPATCH.md](A2_BATCHED_DISPATCH.md). |
| **a3 — frame-buffer DSP/mixdown** | **MIGRATED TO PDSL, parity by ear, under budget.** The full mixdown/efx/reverb DSP runs as one compiled PDSL model per buffer (`mixdown_master_wet`). Default (vectorized) tick 66–139 ms vs the 185.8 ms budget at 8192 (1.34–2.81×), faster than the CellList tick (298–373 ms). Accepted approximations: [PDSL_DIFFERENCES.md](PDSL_DIFFERENCES.md). |
| **efx-feedback parity** | **THREE of four character gaps CLOSED** (gene HP/LP filter, gene-driven feedback delay, gene-driven feedback level — commit `4992598a3`). Only the block-rate re-entry remains. [PDSL_DIFFERENCES.md](PDSL_DIFFERENCES.md) §2/§3.1. |
| **Metal dispatch ceiling** (host wedged past ~2300–2560 cumulative dispatches) | **SOLVED** — 3000+ sustained dispatches, regression-guarded. [KNOWN_ISSUES.md](KNOWN_ISSUES.md) §8.3. |
| **Compile-reuse / argument-aggregation pool exhaustion** | **RESOLVED** — the aggregation subsystem was torn out and rebuilt; the null-signature-defeats-reuse premise is gone, so structurally-identical rebuilds reuse cached kernels. [KNOWN_ISSUES.md](KNOWN_ISSUES.md) §8.1. |

**The confirmation question this effort had to answer — ANSWERED.** *Can all signal
processing be expressed in PDSL with acoustic parity and at/under the real-time budget?*
**Parity: YES** (validated by ear + windowed RMS on the full real-scene A/B). **Budget:
YES** — the PDSL tick is under budget by default and faster than CellList, after the
`delay_network` ring update was de-fragmented (1382 ms → 3 ms) and channel-uniform bodies
were vectorized. The remaining per-tick cost is per-note pattern prep (the a2 item), not
the DSP. Confirmed end-to-end on the full real scene (2026-06-26): the 6-channel curated
render keeps up warm (ratio ≈ 0.97 at 4096) with batched pattern dispatch + PDSL mixdown.

**Hard constraints learned the hard way** (full detail in [KNOWN_ISSUES.md](KNOWN_ISSUES.md)):
1. **Real-time requires HYBRID routing — never force `AR_HARDWARE_DRIVER`.** `mtl` fails to
   compile the mixdown loop (Metal's 31-buffer-argument limit); `native` is ~14× slower.
2. **Continuous rendering requires `-DAR_PATTERN_CACHE_PERSIST=true`** (a `static final`
   read at class load → a JVM `-D` arg), or native memory leaks ~150 MB/buffer.
3. **Real-scene work needs the curated library** (`/Users/Shared/Music/Samples` +
   `pattern-factory.json`); CI skips those tests gracefully.

## 3. The three layers, and the target shape

Three layers with very different economics, decoupled by ring buffers so each runs on its
own clock:

| Layer | Job | Cost | Horizon | Home |
|---|---|---|---|---|
| **a1 — pattern generation** | decide *which* notes exist | cheap | minutes | `PatternLayerManager.sumInternal` |
| **a2 — per-note rendering** | synthesize each note's samples | costly, **now batched** | seconds | `BatchedPatternLayerRenderer` → `BatchedPatternRenderer` |
| **a3 — frame-buffer rendering** | mix live notes into N frames and play | **most sensitive** | just-in-time | `MixdownManager`/`EfxManager` → PDSL → `BufferedOutputScheduler` |

```
   a1 PATTERN GEN ──note-SCHEDULE──▶ a2 PER-NOTE RENDER ──note-AUDIO──▶ a3 FRAME-BUFFER ──▶ line
   (minutes, cheap)   ⟿ ring         (seconds, batched)   ⟿ ring        (now, beats clock)
   INVARIANT: a3 is the only layer bound to the real-time clock; a1/a2 run AHEAD, never block a3.
```

**Where we are vs. the target:** the a2 batching collapsed the N per-note dispatches into
one (the proven win, now validated on real scenes), and a3 is now a fast compiled PDSL
model. What remains to reach the full streaming shape: **decouple a1/a2/a3 onto independent
clocks via rolling buffers** — run a2 seconds-ahead of a3, rather than bulk-rendering the
pattern audio once in `setup` as today.

## 4. What has landed

- **a3 DSP fully migrated to PDSL** — the complete mixdown/efx/reverb path runs as one
  compiled per-buffer model behind `MixdownManager.enablePdslMixdown` (on by default since 2026-06-26);
  parity-validated by ear + windowed RMS on the 40 s real-scene A/B. Substrate:
  [PDSL_DSP_REFERENCE.md](PDSL_DSP_REFERENCE.md).
- **Gene-driven efx feedback** — HP/LP filter selection (host-side blend; no `choice()`
  primitive needed), per-channel gene-driven feedback delay and level. [PDSL_DIFFERENCES.md](PDSL_DIFFERENCES.md) §2.
- **Vectorized `for each channel` on by default** (`AR_PDSL_VECTOR_FOREACH`) — channel-
  uniform bodies compile once over `[channels, signalSize]`; the cross-context
  instruction-reuse defect that previously forced this off was fixed
  ([KNOWN_ISSUES.md](KNOWN_ISSUES.md) §8.2).
- **Single-channel + lean pattern prep** — `supportsPdsl` accepts any single channel
  (mapping genome reads to the selected scene channel) and the zero-based contiguous
  prefix; `createPdsl` uses `AudioScene.prepareRenderBuffers`, no longer building the
  unused Java mixdown CellList.
- **a2 batching mechanism** — `PatternFeatures.render` routes through
  `BatchedPatternLayerRenderer` when `enableBatched`/`AR_PATTERN_BATCHED` is set;
  validated on the synthetic sentinel path. [A2_BATCHED_DISPATCH.md](A2_BATCHED_DISPATCH.md).
- **Metal sustained dispatch** and the **argument-aggregation / compile-reuse** rebuild —
  [KNOWN_ISSUES.md](KNOWN_ISSUES.md) §8.
- **Real-time streaming substrate** — `AudioSceneRealtimeRunner`, `BufferedOutputScheduler`
  (gap/back-pressure/degraded-mode), `warmNoteCache`. The output side is largely solved.

## 5. What's genuinely open

- **True stereo.** The PDSL path is dual-mono (one master duplicated to both writers).
  True stereo must be one model carrying twice the channels in a *single* forward — never
  the pipeline run twice (that attempt was reverted).
- **Live-swap filter-coeff staleness.** `wet_filter_coeffs` / `efx_filter_coeffs` are
  sampled at build and lag the genome until rebuild ([PDSL_DIFFERENCES.md](PDSL_DIFFERENCES.md) §6).
  Narrow — only matters for live genome-swap workflows.
- **Per-frame automation** (only if ever audible). Per-buffer steps are inaudible at
  production envelope rates.
- **a1/a2/a3 ring decoupling** (to reach the streaming shape) — make `PatternAudioBuffer`
  rolling, run a2 on a worker K buffers ahead, point a3 at the PDSL `Block` pumped by
  `BufferedOutputScheduler`.

> **Closed since earlier drafts of this doc** (do not re-open as "regressions"): gene
> HP/LP `choice()` symptom, gene-driven efx delay/level, the vectorized-for-each default,
> single-channel support, lean pattern prep, the compile-reuse/aggregation pool blocker,
> the PDSL-tick performance gap (the tick is now under budget), the a2 real-scene
> batched-dispatch gap (validated 2026-06-26), and the batched + PDSL-mixdown default flip
> (both on by default since 2026-06-26 — a full CI pass over the audio/pattern tests is the
> gate for the global default change). These were on this list and are done.

## 6. Options that are moot

The original redesign framed the DSP problem as a choice among hand-built CellList
architectures (channel-scoped CellLists; flat-buffer pipeline; hybrid + graph-analysis
copy-elimination; stream-oriented). **PDSL is the answer to all of them** — `for each
channel` gives per-channel kernels, the Block model eliminates the Cell/CachedStateCell
copy chain, and the declarative expression tree makes a graph-analysis pass unnecessary.
Do not revive these; build on PDSL.

## 7. Durable lessons (don't re-derive)

- **Expression-level micro-optimization of GPU kernels is futile** — the native compiler
  already does LICM/strength-reduction/CSE; hand-factoring the expression tree produced
  zero measurable kernel-time change.
- **Do NOT apply `Process.optimized()` to the per-note / flat-pipeline chain** — it *added*
  ~15% overhead. The optimizer is for graphs with isolation/reuse targets, not flat
  pipelines.
- **The a1→a2 seam is "classify-and-dispatch," not "compile an arbitrary graph"** —
  production notes are a tiny closed set of shapes, each a 3-source merge.
  [A2_BATCHED_DISPATCH.md](A2_BATCHED_DISPATCH.md).
- **Per-op dispatch cost was addressed by Metal operation batching, not by `Evaluable.async`.**
  Hot paths encode many dispatches into one command buffer per `ComputeContext` (committed
  at boundaries) and issue ops non-blocking from a single thread with one completion wait
  per group; the DSP forward path does not call `Evaluable.async` at all. The literal
  `async()` default still spawns a thread per issuance, but that is not on the hot path —
  do not mistake it for an untaken realtime lever. [KNOWN_ISSUES.md](KNOWN_ISSUES.md) §8.3.

## 8. Key files

| Path | Role |
|---|---|
| `studio/music/.../pattern/PatternLayerManager.java` | a1 scheduling + `enableBatched` / `cachePersist` |
| `studio/music/.../pattern/PatternFeatures.java` | per-note vs batched render dispatch |
| `studio/music/.../pattern/BatchedPatternLayerRenderer.java` | a2 batched dispatch (studio side) |
| `engine/audio/.../BatchedPatternRenderer.java` | a2 fused melodic-SSS kernel |
| `studio/compose/.../AudioSceneRealtimeRunner.java` | the `TemporalCellular` runner; CellList vs PDSL strategy, `supportsPdsl` |
| `studio/compose/.../arrange/MixdownManager.java`, `EfxManager.java` | a3 DSP (legacy path / cutover source of truth) |
| `studio/compose/.../arrange/MixdownManagerPdslAdapter.java` | genome → PDSL args bridge (gene-driven efx, automation slots) |
| `studio/compose/.../dsl/audio/AudioDspPrimitives.java`, `MultiChannelDspFeatures.java` | PDSL audio primitives |
| `engine/ml/.../dsl/PdslInterpreter.java`, `PdslBuiltins.java` | PDSL interpreter + builtins (`enableVectorizedForEach`) |
| `engine/ml/src/main/resources/pdsl/audio/*.pdsl` | PDSL DSP layer definitions |
| `engine/audio/.../line/BufferedOutputScheduler.java` | a3 real-time pump |

## 9. Document map (this folder)

- **STATE_OF_PLAY.md** (this doc) — the big picture; start here.
- **[A2_BATCHED_DISPATCH.md](A2_BATCHED_DISPATCH.md)** — the a2 per-note batching subsystem:
  the note model, why batching, the wiring, and the open real-scene `peak=0.0` gap. The
  next work happens here.
- **[PDSL_DIFFERENCES.md](PDSL_DIFFERENCES.md)** — the complete inventory of remaining
  PDSL-vs-CellList signal-path differences, with the perceptual symptom→cause→lever map
  and the swap-impact triage. Read before flipping `enablePdslMixdown`.
- **[PDSL_DSP_REFERENCE.md](PDSL_DSP_REFERENCE.md)** — reference for the PDSL audio DSP
  substrate: primitive catalog, multi-channel constructs, the producer-valued-argument
  model.
- **[KNOWN_ISSUES.md](KNOWN_ISSUES.md)** — live platform constraints (hybrid routing /
  Metal 31-buffer limit, cache-persist, `floor()` resample, the a2 real-scene gap) and a
  record of resolved cross-cutting issues (aggregation/compile-reuse, instruction-set
  reuse, Metal sustained dispatch).

Related but out of scope here: `../AUDIO_SCENE_BENCHMARK_INVESTIGATION.md` (a separate
heap-retention workstream) and the `docs/internals/` references
(`celllist-realtime-streaming.md`, `features-pattern.md`,
`backend-compilation-and-dispatch.md`).
