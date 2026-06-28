# a2 — Batched Pattern Dispatch

> The pattern-audio rendering layer (the former "Phase 3" / a2). **Why** it
> exists (a benchmark floor that was 99.4% JNI dispatch), **what** it renders
> (a tiny closed set of note shapes, not an arbitrary graph), **how** it is
> wired today (flag-gated, classify-and-dispatch, sentinel-guarded), and **what
> is proven vs. open**. a2 was reworked substantially on
> `feature/pattern-batched-dispatch` (shared-kernel buckets, batched percussion,
> in-kernel envelopes); the wiring below is current. The system is now
> **a2-bound** (~2.34× realtime on a dense scene) — see
> [`STATE_OF_PLAY.md`](STATE_OF_PLAY.md).

---

## 1. Why a2 exists — the per-note rendering floor

a2 is the answer to a measurement, not a design preference. `PatternRenderingFloorBenchmark`
(`engine/audio/src/test/java/org/almostrealism/audio/benchmark/PatternRenderingFloorBenchmark.java`)
isolated the per-note rendering chain — resample → volume envelope → FIR lowpass
→ accumulate — with no `PatternLayerManager`/`PatternFeatures` orchestration, one
sequential `.evaluate()` of the once-compiled chain per note. Real-time threshold
at 32 measures/tick is **92.9 ms** ("ratio-of-1").

**The floor is flat per-note, and that is the tell.** Sequential 4-kernel cost was
constant at **~0.88 ms/note** regardless of N (468.78 ms at 512 notes/tick →
7,095.85 ms at 8,192) — 19.4× over threshold at 64 notes/measure. Constant per-note
cost with zero cross-note fusion is the signature of pure per-note JNI dispatch. Of
that ~0.88 ms, only ~0.44 ms is arithmetic; kernel fusion across the 4 phases bought
1.73×, confirming ~43% of even the fused chain is dispatch, not math. `Process.optimized()`
*added* ~15% on this flat chain — do not use it on the per-note path.

**Batching collapses N per-note `evaluate()` calls into one dispatch per pattern-layer
per tick.** Re-expressed as a single `CollectionProducer` over all N notes, one
`evaluate()` per tick:

| notes/measure | sequential (ms) | batched (ms) | speedup |
|---|---|---|---|
| 16 | 276.56 | 2.25 | 123× |
| 64 | 1,100.50 | **6.39** | 172× |
| 256 | 4,378.53 | 21.83 | 201× |

At 64 notes/m the batched 3-kernel chain is **14.5× under** the 92.9 ms threshold;
amortized per-note cost falls from 0.5374 ms to 0.0031 ms — i.e. **99.4% of
sequential cost was JNI dispatch overhead**, not arithmetic. A minimal batched
2-kernel form already clears the floor (0.90 ms, ~921×), so dispatch elimination —
not chain length — is the load-bearing mechanic. The padded-FIR 4-kernel chain hits
1.89 ms (~49× under): `MultiOrderFilter`'s boundary check treats `length()` as the
whole array, so on `[N, NOTE_SIZE]` input the `±filterOrder/2` (20 samples at order
40) bleed across rows; padding each row by `filterOrder/2` zeros lands boundary reads
in the pad, ~4% memory overhead, FIR API unchanged. Final accumulate is a reduction
(`permute(reshape,1,0).traverse(1).sum()` → `[NOTE_SIZE]`), folded into the same
kernel, so there is no O(N) intermediate or Java-side post-sum.

The conclusion (per-note JNI dispatch is the cost; graph-batching removes ~99.4% of
it; the win is ~100–1500× depending on chain and density) is what justified building
a2. It is now built and wired.

**Scope:** the 99.4%-dispatch figure is the **synthetic** benchmark floor. On the real
curated scene the dominant a2 cost turned out to be a different one — Metal **pipeline-state
switching** across many distinct compiled kernels, not the arithmetic, the FIR, or the
frame count. Collapsing the dispatch onto one shared kernel (coarse note-count `BUCKETS`
plus a single `SOURCE_BUCKET`) cut batched eval from ~761 ms to ~28 ms. See §4.2 /
[`STATE_OF_PLAY.md`](STATE_OF_PLAY.md).

---

## 2. The production note model — a closed set, not an arbitrary graph

The `PatternNoteAudio` delegation system *technically* permits arbitrary graphs, so
"batch-compile a note" once looked like "compile any graph." It is not. Production
builds a **tiny, closed set of shapes**, and the a1→a2 seam is **classify-and-dispatch
a precompiled fused kernel for the one dominant shape**, never compile-an-arbitrary-graph.
Everything else falls back, loudly.

### 2.1 The closed vocabulary

Verified by reading every `PatternNoteAudio` implementer and every `new PatternNote(...)`
site in `studio/`:

| Node | Arity | Role |
|------|-------|------|
| `NoteAudioProvider` (via `SimplePatternNote`) | leaf | file source → pitch-shift resample |
| `AutomatedPitchNoteAudio` | leaf | synth oscillator — **not on the production path** |
| `PatternNoteAudioChoice` | deferred | numeric choice → resolves to a leaf at render time |
| `PatternNote` (layer mode) | n-ary merge | combine layers via aggregator |
| `PatternNote` (delegate mode) | unary | wrap a node with one `NoteAudioFilter` |
| `PatternNoteLayer` / `NoteAudioFilter` | unary | source + one transform |

Filters are closed too: `ParameterizedVolumeEnvelope.Filter` (ADSR gain),
`ParameterizedFilterEnvelope.Filter` (ADSR-driven 40th-order time-varying lowpass),
`ParameterizedLayerEnvelope.Filter` (per-layer ADSR), `TremoloAudioFilter` (riser
only), `ReversePlaybackAudioFilter`.

Merge has **4 strategy variants** weighted by one scalar choice in [0,1]
(`engine/audio/.../notes/NoteAudioSourceAggregator.java:52-69`): `SOURCE,SOURCE,SOURCE`
(weight 6.0), `SOURCE,SOURCE,VOLUME_ENVELOPE` (3.0), `SOURCE,SOURCE,FREQUENCY` (1.0),
`SOURCE,FREQUENCY,VOLUME_ENVELOPE` (2.0). The latter three are gated behind
`enableAdvancedAggregation` and are deferred — only SSS is constructed.

### 2.2 The canonical production shape

One shape with bounded parametric variation, built by `PatternNoteFactory`
(`LAYER_COUNT = 3`, `new PatternNote(layers)`), wrapped by `PatternElementFactory`:

```
  layer 0 -- PatternNoteAudioChoice -> leaf (file->pitch resample) -- [perLayerEnv?] --+
  layer 1 -- PatternNoteAudioChoice -> leaf (file->pitch resample) -- [perLayerEnv?] --+
  layer 2 -- PatternNoteAudioChoice -> leaf (file->pitch resample) -- [perLayerEnv?] --+
                                   |
                          MERGE (production = SSS = L0 + L1 + L2)
                                   |
                          [ filterEnvelope ]   <- optional, gated && melodic
                                   |
                          [ volumeEnvelope ]   <- optional, gated && melodic
```

Confirmed order (`PatternElementFactory.java:263-271`): per-layer envelopes apply
*before* the merge; the heavy filter + volume envelopes apply *after* (filter inner,
volume outer). The **3-source merge per note** is the defining feature — and in
production it is just `L0 + L1 + L2` (plain `SummingSourceAggregator`), because
`aggregationChoice` defaults to `0.0` and the factory never sets it (the only
non-default `setAggregationChoice` is a test). Synth leaves are off the factory path.
Per-layer and post-merge envelopes are present iff `melodic` (kick/drum channels are
non-melodic and carry almost none). The riser (`RiseManager.java:87,90`) is a separate
delegate-mode `source -> TremoloAudioFilter`, left on the per-note path.

This is why the a1→a2 seam is tractable: there is **one shape to recognize** (melodic
SSS), three independent per-note axes (pitch resample ratio, length-as-envelope-release
with FIR padding, placement via offset-keyed scatter-add), and a precompiled fused
kernel to dispatch into. Envelope **curves are generated in-kernel from `[N]` ADSR
scalars** — pre-materializing curves would re-introduce N GPU evaluates per window,
defeating the point.

---

## 3. Current wiring — verified against source

The a2 path was reworked on `feature/pattern-batched-dispatch` (shared-kernel buckets,
batched percussion, in-kernel envelope generation); the description below is verified
against current source. The flow is **flag → per-note-vs-batched dispatch → studio
renderer → engine fused kernel**, guarded by two sentinel counters.

### 3.1 The flag — on by default

`PatternLayerManager.enableBatched` (`studio/music/.../PatternLayerManager.java:140`):

```java
public static boolean enableBatched = SystemUtils.isEnabled("AR_PATTERN_BATCHED").orElse(true);
```

**Env `AR_PATTERN_BATCHED`, on by default** (since 2026-06-26; set `=disabled` to force the
per-note path). Batchable melodic notes dispatch through the batched path; non-batchable
note shapes fall back to per-note automatically. Each pattern lazily builds one `BatchedPatternLayerRenderer`
(`getBatchedLayerRenderer()`, `:327`) so compiled kernels are shared across ticks of
the same shape.

### 3.2 Per-note vs. batched dispatch — `PatternFeatures.render`

`studio/music/.../PatternFeatures.java:94-107` is the single render path for offline
and real-time:

```java
if (PatternLayerManager.enableBatched) {           // :97
    BatchedPatternLayerRenderer batchedRenderer = getBatchedLayerRenderer();
    if (batchedRenderer != null) { batchedRenderer.render(...); return; }   // :100
}
renderPerNote(sceneContext, audioContext, elements, melodic, offset, ...);  // :106
```

`accumulateBatchedOutput` (`:155-157`, `output.get().evaluate()`) is the **single
evaluate boundary**, shared with the per-note path; a large window is split into
bounded sub-windows that each accumulate into a destination slice.

### 3.3 Classify, gather, dispatch — `BatchedPatternLayerRenderer` (studio/music)

`studio/music/.../BatchedPatternLayerRenderer.java`. `render` (`:230-280`) collects the
notes overlapping `[startFrame, endFrame)`; a note is batchable iff it carries a
`BatchedNoteInputs` record (`note.getBatchedInputs() != null`, `:263`). If **every**
overlapping note is batchable it dispatches and counts; otherwise it counts a fallback
and routes the whole tick to `features.renderPerNote`:

```java
if (allBatchable && !overlapping.isEmpty()) {
    dispatchBatched(features, overlapping, startFrame, frameCount, destination);  // :269
    batchedDispatchCount.incrementAndGet();                                       // :270
    return;
}
if (!overlapping.isEmpty()) fallbackCount.incrementAndGet();                       // :276
features.renderPerNote(...);                                                       // :278
```

`dispatchBatched` (`:297`) splits the range into `MAX_WINDOW = 8192`-bounded
sub-windows (`:111`) so compiled kernel size stays bounded — a note spanning a boundary
continues from its advancing sampling offset. Renderer is selected per overlap count
from buckets `{16,32,64,128,256,512}` (`bucketFor` `:177`, `rendererFor` `:193`);
`destOffsets[]` scatter placement is `max(0, noteStart - startFrame)` per row. The
gather recipe (`ScaleTraversalStrategy.createRenderedNote` → `gatherBatchedInputs` →
`BatchedNoteInputs.isMelodicSssShape` / `.from(...)`) peels the note structure — outer
volume-env delegate → filter-env delegate → 3-layer note — **without** collapsing it
via `innermostAudio()`, and reads all per-note fields from cache hits + arithmetic
(GPU-evaluate-free). Melodic SSS iff `getLayers().size()==3 && getAggregationChoice()==0.0`
and each layer resolves to a file source.

### 3.4 The fused kernel — `BatchedPatternRenderer` (engine/audio)

`engine/audio/.../BatchedPatternRenderer.java`.
`buildBatchedSssChainPlacedFromScalars(sources[], ratios[], layerEnvParams[][],
filterAdsr[], volumeAdsr[], destOffsets, samplingOffsets, windowWidth)` is the fully
fused production form, dispatched once per window:

```
per active note n:
  layer i in {0,1,2}: resample(srcᵢ, ratioᵢ) at samplingOffset, masked to length
                      Lᵢ = resampleᵢ · perLayerEnv(adsrᵢ)            [N, W]
  merged   = L0 + L1 + L2                                            (SSS plain sum)
  filtered = paddedRowFIR(merged, filterCutoffEnv(adsr))            (reused back-half)
  voiced   = filtered · volumeEnv(adsr)
out[f]     = Σ_n scatterAddFlat(voiced[n], destOffset[n])           (aligned reduce = destOffset≡0)
```

The melodic-SSS front half (3 sources → per-layer env → sum) is the new work; the back
half (filter env → volume env) is the verified-RMS=0 chain from PR #175. `buildResampleProducer`
does pitch resample as a producer, not host math.

### 3.5 Sentinel discipline

The gate is **`batchedDispatchCount > 0 && fallbackCount == 0`** measured at production
density (counters at `BatchedPatternLayerRenderer.java:124,127`; `resetCounters` `:140`).
Every deferred shape — non-melodic/drum notes, merge strategies 2–4, synth leaves, the
riser/tremolo note — classifies "unsupported" and routes to `renderPerNote` with a loud
`fallbackCount++`, **never silently**. The discipline exists because the prior attempt
(`feature/audio-scene-redesign`) rendered *zero* notes through the batched path
(`batchedDispatchCount = 0`, `fallbackCount = 476`): it assumed a note was one leaf
source, called `innermostAudio()` (which throws on a 3-layer merge), and ran
measurements at the wrong density. **Read the note structure, never collapse it; gate on
the counters at production density.**

---

## 4. State — proven and validated on real scenes

### 4.1 PROVEN — the synthetic sentinel path

Three tests in `studio/music/.../pattern/` exercise the dispatch + kernel and are **not
`@Ignore`'d**. They use synthetic `FileNoteSource` inputs, so they validate the
kernel/dispatch mechanism — **not** a full real scene.

- **`BatchedDispatchSentinelTest`** — asserts `batchedDispatchCount > 0` (`:126`):
  enabling the flag actually fires the batched path.
- **`BatchedVsPerNoteRmsTest`** — asserts the batched output is non-silent
  (`refRms > 1e-4`, `:170`) and matches the per-note render within an **enforced 5%
  relative RMS bound** (`relative < 0.05`, `:172`).
- **`BatchedRealtimeTickTest`** — sustains `SUSTAINED_TICKS = 3000` dispatches (`:64`)
  and asserts: non-silent (`maxAbs > 1e-4`, `:308`); **no tick falls back**
  (`fallback == 0`, `:309`); dispatch count passes the **old ~2300–2560 Metal wedge
  ceiling** (`batched > 2560`, `:310`); and mean per-tick render is **under the
  real-time budget** (`avgMs < budgetMs`, `:312`). It also re-asserts the 5% RMS bound
  (`:198`).

**Measurements vs. guarantees.** The often-quoted figures — **< 1% RMS**, **~12× under
budget**, **7.76 ms per tick** — are *reported M1 measurements*, not assertion
thresholds. The enforced bounds are looser (5% RMS; merely *under* budget; > 2560
dispatches). Cite the tight numbers as observed M1 behavior, not as contracts.

### 4.2 RESOLVED — the real curated-library scene

`BatchedRealSceneRenderTest` is **re-enabled** (the `@Ignore` is removed). On the real
curated scene batched dispatch fires correctly and produces non-silent audio. Validated
2026-06-26:

- **Single melodic channel** (`singleMelodicChannelFullPipeline`, CellList mixdown):
  `fallbackCount=0`. Every note on the channel classifies as the melodic-SSS shape
  (`getBatchedInputs()` non-null) and dispatches; no fallback. (The test uses a random
  `realGenome()`, so the peak amplitude is seed-dependent and flaky — it can be `0.0` on
  some seeds; do not treat a specific peak as a fixture.)
- **All six channels** (`allChannelsFullPipeline`, PDSL mixdown via
  `-DAR_PDSL_MIXDOWN=enabled`): both melodic **and** percussion channels batch — percussion
  now classifies (`BatchedNoteInputs.isPercussionSssShape`) and dispatches through
  `BatchedPatternLayerRenderer.dispatchWindowPercussion` →
  `BatchedPatternRenderer.buildBatchedPercussionChainPlaced`; only continuing notes and
  non-batchable shapes fall to per-note. The mix is non-silent. (Peak is seed-dependent —
  see the single-channel note above.)
- **Warm steady-state** (`*WarmSteadyState`, PDSL mixdown) — clean batched dispatch and
  non-silent output once warm. (The earlier warm-tick ratios here predate the a2
  shared-kernel rework; for current perf the system is a2-bound at ~2.34× dense — see
  [`STATE_OF_PLAY.md`](STATE_OF_PLAY.md).)

Both halves of the old `@Ignore` rationale are gone: (a) the `GeneratedOperation`
pool-exhaustion / compile-reuse blocker was fixed by the June-2026 argument-aggregation
rebuild (removed `5f0648eab`; rebuilt `9732295ff` PR #317; `aggregate-on-kernel-provider`
PR #318; enabled `13ce711c5`; perf `b4b23f0c5`); and (b) "batched dispatch does not fire /
`peak=0.0`" is simply false now. The aggregation rebuild is what unblocked it — the a2
note-classification path itself was unchanged since 2026-05-31.

> **Caveat (a3, not a2):** `allChannelsFullPipeline` *times out* (>18 min cold) on the
> legacy **CellList** mixdown — a3 mixdown cold-render speed, unrelated to a2 batching — so
> the all-channels methods are run with the faster PDSL mixdown
> (`-DAR_PDSL_MIXDOWN=enabled`). The single-channel methods pass on either path. (Note:
> `AR_PDSL_MIXDOWN` takes the literal value `enabled`/`disabled`, not `true`/`false`.)

### 4.3 Adjacent state

- **Metal sustained-dispatch ceiling — SOLVED** (merged, regression-guarded by
  `BatchedRealtimeTickTest`'s `batched > 2560` sustain). Do not re-explain here; see
  [`KNOWN_ISSUES.md`](KNOWN_ISSUES.md) §8.3.
- **Per-note pattern preparation** (~64 ms/tick on the M1, one busy channel ≈ half) is
  the dominant remaining per-tick cost once the DSP is fast. Flagged as an **M1 runtime
  measurement** (from `AudioScenePdslBenchmarkTest`, `BENCH_MEASURES=64`), **not** a
  CI-gated or source-verifiable number.
- The downstream a3 DSP/mixdown migration to PDSL is its own workstream — see
  [`STATE_OF_PLAY.md`](STATE_OF_PLAY.md) and
  [`PDSL_DIFFERENCES.md`](PDSL_DIFFERENCES.md).

---

## 5. Status and next steps

The real-scene dispatch gap is **resolved** (§4.2): batched dispatch fires correctly on the
real curated scene for melodic channels, percussive channels fall back as designed, and
`BatchedRealSceneRenderTest` is re-enabled. `AR_PATTERN_BATCHED` is **on by default**
(`PatternLayerManager.enableBatched`, since 2026-06-26); the test still sets it explicitly
per-method.

Reasonable next steps, in rough order:
- **5× — the a2 kernel redesign.** The system is now a2-bound (~2.34× dense); reaching 5×
  needs the batched eval kernel reworked (the shared-`sourceLength` and host marshal cost
  are the open structural items). This is the headline remaining work — see
  [`STATE_OF_PLAY.md`](STATE_OF_PLAY.md) §5.
- **Validate a broader genome sweep** of `BatchedRealSceneRenderTest` (its single-channel
  method is flaky on random genomes).
- **a3 mixdown speed** for the all-channels **CellList** path is a separate workstream; the
  PDSL mixdown already addresses it (see [PDSL_DIFFERENCES.md](PDSL_DIFFERENCES.md) §7).

---

## Key files

- `studio/music/.../PatternFeatures.java` — flag check + per-note/batched dispatch (`:97`), single evaluate boundary (`:155`)
- `studio/music/.../PatternLayerManager.java` — `enableBatched` / `AR_PATTERN_BATCHED` default ON (`:140`)
- `studio/music/.../BatchedPatternLayerRenderer.java` — classify/gather/dispatch, sentinel counters (`:230-280`, `:124,127`)
- `studio/music/.../BatchedNoteInputs.java`, `.../ScaleTraversalStrategy.java` — gather + melodic-SSS classification
- `engine/audio/.../BatchedPatternRenderer.java` — fused melodic-SSS + scatter kernel
- `engine/audio/.../notes/NoteAudioSourceAggregator.java` — merge strategies (`:52-69`)
- `engine/audio/.../benchmark/PatternRenderingFloorBenchmark.java` — the §1 floor evidence
- Tests: `BatchedDispatchSentinelTest`, `BatchedVsPerNoteRmsTest`, `BatchedRealtimeTickTest` (studio/music, active);
  `BatchedRealSceneRenderTest` (studio/compose, re-enabled, curated-library-gated); `AudioScenePdslBenchmarkTest` (per-tick prep measurement)
