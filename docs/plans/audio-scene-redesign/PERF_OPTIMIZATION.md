# PDSL Realtime Render — Performance Optimization

> Goal: render the all-channel AudioScene (PDSL mixdown path) at **≥5× realtime**
> (per-tick ratio ≤ 0.2 vs the playback budget), sustained for ≥2 min of audio on
> real samples across all channels, reproducibly. Started 2026-06-26 on
> `feature/pattern-batched-dispatch`.

## Where the time actually goes (measured, not assumed)

Profiled with `AudioScenePdslBenchmarkTest.pdslTickStageTiming` / `pdslTickProfile`
against the deterministic curated scene (persisted `scene-settings.json`, seed 52 =
1154 elements, 6 channels, buffer 8192). Per-channel element counts:
`c0=18, c1=96, c2=184, c3=420, c4=432, c5=4`.

| Tick component | Cost | Verdict |
|---|---|---|
| **PDSL mixdown forward** (`mixdown_master_wet`) | **~34 ms** @ 8192 | **Already ≈5× realtime.** Not the bottleneck. |
| **a2 pattern prep — one dense percussion channel** | ~3.8 s (94% of the tick) | **Was the wall — now resolved** (percussion batches, lever 1 below); this profile predates it. |
| gather (build note destinations) | ~156 ms/tick | Secondary, a2 |
| automation refresh / streaming / clock | < 5 ms | Negligible |

**The 3.8 s was Java-side per-note graph building, not GPU compute.** The captured
profile had `compiled_operations=0`, ~67 k nodes, and recorded kernel time a rounding
error: a dense window rendered `getProducer().evaluate()` **per percussion hit**
(hundreds of notes → ~67 k-node graph).

**Why (the original diagnosis):** at the time, the a2 batched-dispatch path only handled
the **melodic-SSS** note shape, so percussion notes never classified and fell to the
per-note path. The curated `pattern-factory.json` is **11/14 percussion** (Kicks, Hats,
Toms, Claps, Percs, Rise, …) vs 3 melodic (Chord/Lead/Bass), and **each channel is
exclusively one type**, so a dense percussion channel was 100% per-note. **This is now
resolved — percussion batches** (`BatchedNoteInputs.isPercussionSssShape` +
`BatchedPatternLayerRenderer.dispatchWindowPercussion`); the 3.8 s wall is closed and the
system is a2-bound at ~2.34× dense.

## Levers (ranked by impact × reusability)

### 1. Batch percussion — PRIMARY, moves the ratio
A percussive note is a **strict subset** of a melodic note's preparation: a single
source placed at an offset with an optional volume envelope — **no filter envelope, no
pitch/merge of 3 layers** (confirm exact transforms against the per-note producer). So a
batched percussion kernel (all hits in a window → one dispatch, place-at-offset + sum,
optional volume ADSR) is simpler than the melodic-SSS kernel and reuses the existing
`BatchedPatternLayerRenderer` dispatch + bucket cache + sentinel counters. Because
channels are homogeneous, dispatch is all-or-nothing per channel (no partial/mixed
handling needed). This is the direct path to collapsing the 3.8 s percussion stage to
tens of ms.

### 2. a3 mixdown forward — inference-mode stateless-stage fusion (REUSABLE, capture-only for now)
**Not pursued yet** (the forward is already ~34 ms, so this won't move the full-render
ratio by itself), but recorded because it is a broad, reusable PDSL-platform/HPC win.

**Problem.** `LayerFeatures.layer(...)` hardcodes `layer.init(inputShape,
Layer.ioTracking, true)`. `DefaultCellularLayer.init` then allocates an `output`
`PackedCollection` **and** an `exit` cell that copies every stage's result via `into()`.
So **each stateless stage** of the forward (clip, fir, scale, sum_channels, the master
clip→fir→scale→clip tail, and each `accum_blocks` sub-stage — ~25–30 of ~38 stages)
writes a full `[channels, signalSize]` (~96 KB) intermediate and is **its own kernel**.
That is ~6 MB of throwaway intermediate traffic and ~30 GPU launches per tick, and the
**native compiler cannot fuse across an explicit `into` copy.**

**Fix.** The forward is inference-only (no backprop), so output tracking is pure
overhead. Either:
- (a) build the PDSL inference layers with output tracking **off** so contiguous
  stateless stages compose into nested `Producer`s the native compiler fuses on its own
  (`DefaultCellularLayer.getForward` already has a no-tracking path that is currently
  unused), **gated on an inference flag** so backprop/training paths are untouched; or
- (b) add a contiguous-stateless-segment fusion pass in the interpreter.

Stateful boundaries (delay / feedback / biquad ring writes) must stay materialized. This
is the same principle that took the `delay_network` ring update 1382 ms → 3 ms. Touch
points: `domain/graph` `LayerFeatures.layer` (the hardcoded `true`),
`DefaultCellularLayer`, `engine/ml` `PdslInterpreter`. **Reusable across every PDSL model
(transformers included); expected ~2–4× on any forward.**

### 3. Parallel / fused `accum_blocks` arms (reusable)
`mixdown_master_wet` = `accum_blocks(MAIN, WET, REVERB)`; the three arms read disjoint
input slices and only combine at the final element-wise sum, but run serially today.
Dispatch the independent arms on concurrent command streams, or fuse their stateless
portions into one wide kernel. A "parallel arms" execution mode for `accum_blocks` is
reusable.

### 4. Whole-bank `automationRefresh` slot assigns (cheap dispatch reduction)
`MixdownManagerPdslAdapter.automationRefresh` loops per channel issuing ~5C+2 (~32 at
6ch) tiny slot-assign dispatches/tick. Assign all channels of each slot in one
whole-bank op (one `[C]` assign per scalar slot; one `[C,taps]` gather for coeffs).

## Measurement harness & reproducibility

- **`AudioScenePdslBenchmarkTest`** (studio/compose, `@TestDepth(2)`): `pdslRealtimeBenchmark`
  (warm tick median/p95 vs budget, `realtimeX = budget/median`), `pdslTickStageTiming`
  (per-stage tick breakdown — instrumented with per-channel `countElements` and
  batched/fallback/gather counters), `pdslTickProfile` (OperationProfileNode XML).
- Deterministic scene via `AudioSceneTestBase.loadCuratedScene` (persisted
  `results/pdsl-cutover/scene-settings.json`) + `fixedGenome(seed)`. **Do not** use
  random/unseeded genomes (they yield silent channels and non-reproducible numbers).
- The library path defaults to `../../Samples`; point it at the real library with
  `-DAR_RINGS_LIBRARY=/Users/Shared/Music/Samples`, or symlink `common/Samples` so the
  default resolves (frees the single surefire `argLine` `-D` slot — only one `-D` survives
  the runner's unquoted argLine).
- `AR_PATTERN_CACHE_PERSIST` reads via `Boolean.getBoolean` (pass `true`); it did **not**
  change the percussion stage cost (so the bottleneck is not a cache miss).

## Status
- Profiling complete; the original bottleneck (a2 percussion per-note rendering) is
  **resolved** — percussion now batches (lever 1, done).
- Partial batching (mixed-window split) was tried and reverted — overkill, since channels
  are homogeneous.
- Current state: the system is **a2-bound at ~2.34× dense**; reaching the ≥5× goal needs
  the a2 batched eval kernel redesigned (shared-`sourceLength` and host marshal are the
  open structural costs). See [`STATE_OF_PLAY.md`](STATE_OF_PLAY.md) §5 for the current
  source of truth.
