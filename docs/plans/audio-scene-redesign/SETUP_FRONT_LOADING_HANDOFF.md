# Handoff: the AudioScene "setup" phase is front-loading real-time rendering

## RESOLVED (2026-07-03) — root cause found and fixed; both numbers now honest AND fast

The remaining ~128.6 s front-load was **not** note preparation and **not** the
redundant first-buffer render (the §"Where the ~314 s went" hypothesis below is
disproven by measurement). `PdslSetupBreakdownTest` (new diagnostic, walks the
setup `OperationList` stage by stage with gather/marshal/eval attribution)
showed the time was 7 stages of 14–29 s each, ~all inside the **first
`evaluate()` of each distinct fused batched kernel** — and `jstack` sampling
pinned it to `AggregatedProducerComputation.prepareScope` →
`uniqueNonZeroOffset`: the gather-collapse (one-hot) loop-replacement probe
that `CollectionSumComputation` enables unconditionally. The batched pattern
chains end in a scatter-add over overlapping notes, so the probe **can never
succeed** — it burned 14–29 s per kernel shape building `ExpressionMatrix`
index substitutions and then returned null (the normal loop scope, which is
what runs in production anyway, was always the fallback). The same probe fired
on every lazily-compiled shape mid-stream, which is where the "honest 0.63×"
went: the 191 s generate loop was ~82 s of real work + ~109 s of doomed probes.

**Fix:** `BatchedPatternRenderer.sumNoteAxis` (engine/audio) — the aggregation
tails of `reduceAligned` and `scatterAddFlat` now call
`setReplaceLoop(false)` on the sum before compilation. Zero behavior change
(the probe always failed on these chains); `PdslSetupBreakdownTest` renders a
bit-for-bit identical peak (0.49411046504974365) before and after.

**Measured after the fix (same pinned scene, `GenerateAudioFileTest`):**

| | before (as-found) | after the cut below | after the probe fix |
|---|---|---|---|
| `setupSeconds` | 314.5 | 128.6 | **2.25** |
| `generateSeconds` | 75.3 | 191.0 | **82.4** |
| `generateRealtimeX` | 1.59 (bogus) | 0.63 (honest) | **1.46 (honest)** |
| rendered peak | 1.00 | 1.00 | **1.00** |

What remains (smaller, follow-up scale):
- Aggregate 1.46× ≠ dropout-free: per-tick p95/max spikes (see
  `PdslHotPathBreakdownTest`) still decide whether a true realtime consumer
  under-runs; the render-ahead ring absorbs some of this.
- `runnerBuildMs` ≈ 16–17 s (PDSL mixdown model build, before setup) is now
  the largest one-time cost; it was outside every measurement here.
- Framework follow-up worth considering: a matrix-size cost gate
  (`globalIndex.limit × localIndex.limit`) in
  `TraversableExpression.uniqueNonZeroOffset` next to the existing
  depth/node gates, so no other deep chain can hit this again.

The original handoff follows, unchanged, for the evidence trail.

---

## TL;DR

The real-time `AudioScene` PDSL runner (`AudioSceneRealtimeRunner`) claims ~1.6×
real-time playback, but that number is a lie: a ~5 minute "setup" phase renders
the entire arrangement **before** playback so the "real-time" loop only has to
mix already-rendered audio. When you stop the front-loading, the honest number
is **0.63× real-time** — the system does not keep up.

This document records the evidence, the cut already made, and the work left. It
is written so a fresh agent can continue without re-deriving any of it.

## The measurements (pinned dense scene: seed 58, 64 measures @ 120 BPM ≈ 120 s of audio, 8192 buffer)

`GenerateAudioFileTest.generateAudioFile` renders 120 s of audio and reports the
split between one-time setup and the steady generate loop.

| | before (as-found) | after the cut in this doc |
|---|---|---|
| `setupSeconds` | **314.5** | **128.6** |
| `generateSeconds` | 75.3 | 191.0 |
| `generateRealtimeX` | 1.59 (bogus) | **0.63 (honest)** |
| rendered peak (still non-silent) | 1.00 | 1.00 |

`KernelCacheReuseAcrossScenesTest` (pre-warm disabled, two identical scenes in
one JVM) proves the setup is **not** kernel compilation:

- scene 0 (cold): `setupMs=128572.9`, `renderLoopMs=66554.5`
- scene 1 (every kernel already compiled + cached): `setupMs=128576.8` — **identical
  within 4 ms** — `renderLoopMs=65581.1`

If setup were compilation, scene 1 would be near-instant. It is bit-for-bit the
same. **Kernel compilation is ~1 s** (the scene0−scene1 render-loop difference).
The rest of "setup" is rendering that repeats every scene.

## Where the ~314 s went (two front-loads)

1. **~186 s — the "kernel pre-warm" render-sweeps the whole arrangement.**
   `AudioSceneRealtimeRunner.setup()` had a block gated on `preWarmMaxSeconds`
   (default 300) that ran `renderOp.run()` for **every buffer of the whole
   arrangement** (~646 buffers) and threw the result away. Its stated purpose is
   to compile every `(bucket, sourceLength, targetLength)` kernel shape off the
   real-time clock — but there are only ~30 distinct kernels, so ~640 of those
   646 buffers are pure redundant rendering. It also doubled as a way to leave
   the render-ahead ring warm so the consumer never starves.

2. **~128.6 s — note preparation / first-buffer render inside `prepareRenderBuffers`.**
   This is **prefill-invariant** (see the cut below: changing the ring prefill
   from 24 to 1 did not move it), so it is *not* the render-ahead ring fill. It
   is `AudioScene.prepareRenderBuffers()` → `prepareRenderCells()` →
   `createRenderCell(...)` and the "renders the first buffer" step whose own
   comment admits "the producer harmlessly re-renders [it] below" — i.e. it is
   very likely redundant, front-loaded pattern rendering / note-audio prep for
   the arrangement.

## What was cut in this handoff (staged, not committed)

File: `studio/compose/src/main/java/org/almostrealism/studio/AudioSceneRealtimeRunner.java`

1. `preWarmMaxSeconds` default `300.0` → `0.0`. Disables the whole-arrangement
   render-sweep. Kernels now compile lazily on the render-ahead producer thread
   during playback. (The block is left gated, not deleted, so a *bounded
   compile-only* warm can be added later — but see the guardrail below.)
2. `renderStream.start(renderAheadSlots)` → `renderStream.start(1)`.
   `PatternRenderStream.start(prefill)` spin-blocks until `prefill` buffers are
   rendered; passing `24` blocked setup rendering 24 buffers up front. Passing 1
   lets the producer fill the ring lazily during playback (the consumer may
   under-run until the producer gets ahead — that is the honest cost).

Result: setup 314.5 → 128.6 s, and the honest 0.63× real-time is now visible.
Audio still generates (non-silent, exit 0).

## What is left for you

1. **Eliminate the remaining ~128.6 s front-load in `prepareRenderBuffers`.** It
   is prefill-invariant, so it is the render-cell setup + the "first buffer
   render," not the ring. Start by instrumenting the sub-phases of
   `setup.run()` (createRenderCell vs the first-buffer render vs first lazy
   compile) to pinpoint it, then make note preparation *incremental* — prepared
   as the producer first needs each note during playback — instead of eagerly in
   setup. The comment claiming the producer re-renders the first buffer suggests
   the setup-side render may be safe to drop outright; verify with peak parity.

2. **The real problem is 0.63× real-time.** With the cheating removed, the a2
   pattern render (note prep → gather → sum) cannot keep up with playback. The
   whole point of the a1/a2/a3 layer split was to make this streamable in real
   time; it is not. That is the actual performance work, and the setup phase was
   hiding it. (Commit-cause work on this branch already cut the a2 per-tick Metal
   commits substantially — see the two most recent commits — but the a2 render is
   still the bottleneck.)

## How to measure honestly (do not trust `realtimeX` without checking setup)

- `GenerateAudioFileTest.generateAudioFile` — the headline: `setupSeconds` +
  `generateRealtimeX`. **`generateRealtimeX` is the honest number; `setupSeconds`
  is where cheating hides.** Any "real-time" claim must be paired with a setup
  cost that is small and bounded (seconds, not minutes).
- `KernelCacheReuseAcrossScenesTest.reuseAcrossScenes` — scene 0 vs scene 1
  isolates compile (cross-scene reuse) from per-scene rendering. Compile is ~1 s;
  if a "setup" cost does not shrink on scene 1, it is rendering, not compile.
- `PdslHotPathBreakdownTest.hotPathBreakdown` — per-tick a2/a3 split and Metal
  commit-cause attribution (`hostCompleteRequesters`), for the steady loop.

## Guardrail

Do **not** re-enable `preWarmMaxSeconds` as a whole-arrangement render, and do
**not** move rendering into setup to make the loop look fast. A bounded
compile-only warm (render just enough buffers to reach each distinct kernel
shape — a handful, never the whole arrangement) is acceptable; front-loading the
render is the exact anti-pattern this document exists to stop. A real-time system
cannot claim real-time performance while doing the important work up front.

## Branch state at handoff (feature/pattern-batched-dispatch)

- Committed: `3c4981ed6` batched bound-source clear; `80f93b10e` consolidated
  render-buffer clear (both cut a2 per-tick Metal commits; parity-verified).
- Uncommitted: this cut (`AudioSceneRealtimeRunner.java`); and
  `PdslHotPathBreakdownTest.java` carries the commit-cause diagnostic
  instrumentation (per-tick requester histogram) — keep it, it is how the a2/a3
  commit attribution above was measured.
