# a2 — Batched Pattern Dispatch

> The pattern-audio rendering layer (the former "Phase 3" / a2). **Why** it
> exists (a benchmark floor that was 99.4% JNI dispatch), **what** it renders
> (a tiny closed set of note shapes, not an arbitrary graph), **how** it is
> wired today (flag-gated, classify-and-dispatch, sentinel-guarded), and **what
> is proven vs. open**. The a2 production source is unchanged since 2026-05-31
> (`39d7f57b9`); the wiring below is current. Bottleneck has since moved
> downstream to the a3 DSP/mixdown loop — see [`STATE_OF_PLAY.md`](STATE_OF_PLAY.md).

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

The whole a2 path is unchanged since 2026-05-31 (`39d7f57b9` "Code cleanup."; the two
supporting files last moved `3edb59355` 2026-05-31 / `08bae4a89` 2026-05-30). The flow
is **flag → per-note-vs-batched dispatch → studio renderer → engine fused kernel**,
guarded by two sentinel counters.

### 3.1 The flag — default OFF

`PatternLayerManager.enableBatched` (`studio/music/.../PatternLayerManager.java:140`):

```java
public static boolean enableBatched = SystemUtils.isEnabled("AR_PATTERN_BATCHED").orElse(false);
```

**Env `AR_PATTERN_BATCHED`, default OFF.** Production runs the per-note path unless
explicitly enabled. Each pattern lazily builds one `BatchedPatternLayerRenderer`
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

## 4. State — proven vs. open

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

### 4.2 OPEN — the real curated-library scene (the #1 blocker)

**`BatchedRealSceneRenderTest` is still `@Ignore`'d**
(`studio/compose/.../pattern/test/BatchedRealSceneRenderTest.java:73-76`). The real-scene
path does **not reliably fire batched dispatch** — some production note shapes render
`peak=0.0` (silence) because an unhandled shape falls through. The test would assert
`peak > 1e-3` and `batchedDispatchCount > 0` (`:176-177`) if enabled. This is the #1
open item: batched dispatch must fire for **every** production note shape on the real
path, not just the synthetic one.

**Correction — the `@Ignore` text names two blockers; one is now stale.** The annotation
(`:73-76`, expanded in the class Javadoc `:62-71`) cites:

- **(a)** real-scene dispatch / `peak=0.0` gap — **STILL REAL.** This is the live blocker.
- **(b)** argument-aggregation / `GeneratedOperation` kernel-pool exhaustion
  ("aggregation-target buffers have no structural signature, so per-instance compilation
  is not reused") — **NOW STALE.** The argument-aggregation subsystem was torn out and
  rebuilt in June 2026: removed `5f0648eab` (2026-06-20, "Removed MemoryData argument
  aggregation"); rebuilt `9732295ff` (2026-06-23, "A new approach to argument
  aggregation", PR #317); aggregate-on-kernel-provider PR #318; enabled by default
  `13ce711c5` (2026-06-23); perf `b4b23f0c5` (2026-06-24). The "null-signature defeats
  kernel reuse" premise is gone, so the pool-exhaustion half of the ignore rationale no
  longer applies. **The test's re-enablability should be re-checked.** (It also requires
  the curated sample library at `/Users/Shared/Music/Samples` + `pattern-factory.json`,
  which is not in CI or the repo, so it could not be verified by source audit.)

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

## 5. Where to start

The single most important open item: **make batched dispatch fire —
`batchedDispatchCount > 0`, `fallbackCount == 0`, non-silent — for EVERY production note
shape on the real curated-library path.** Today some shapes fall through to `peak=0.0`.

The natural first step: **re-check whether `BatchedRealSceneRenderTest` can now be
re-enabled.** Half of its `@Ignore` rationale (the argument-aggregation / kernel-pool
exhaustion) is gone as of the June 2026 aggregation rebuild (§4.2); only the real-scene
dispatch/`peak=0.0` gap remains. Stand up the curated library locally
(`/Users/Shared/Music/Samples` + `pattern-factory.json`), remove the `@Ignore`, run it,
and read which note shape produces `peak=0.0` and falls through `getBatchedInputs() == null`
in `BatchedPatternLayerRenderer.render`. That falling-through shape is the work.

---

## Key files

- `studio/music/.../PatternFeatures.java` — flag check + per-note/batched dispatch (`:97`), single evaluate boundary (`:155`)
- `studio/music/.../PatternLayerManager.java` — `enableBatched` / `AR_PATTERN_BATCHED` default OFF (`:140`)
- `studio/music/.../BatchedPatternLayerRenderer.java` — classify/gather/dispatch, sentinel counters (`:230-280`, `:124,127`)
- `studio/music/.../BatchedNoteInputs.java`, `.../ScaleTraversalStrategy.java` — gather + melodic-SSS classification
- `engine/audio/.../BatchedPatternRenderer.java` — fused melodic-SSS + scatter kernel
- `engine/audio/.../notes/NoteAudioSourceAggregator.java` — merge strategies (`:52-69`)
- `engine/audio/.../benchmark/PatternRenderingFloorBenchmark.java` — the §1 floor evidence
- Tests: `BatchedDispatchSentinelTest`, `BatchedVsPerNoteRmsTest`, `BatchedRealtimeTickTest` (studio/music, active);
  `BatchedRealSceneRenderTest` (studio/compose, `@Ignore`); `AudioScenePdslBenchmarkTest` (per-tick prep measurement)
