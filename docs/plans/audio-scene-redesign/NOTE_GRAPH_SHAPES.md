# Note Model & Batched Rendering

> The production note shape, its scheduling axes, and how it is batched. The
> `PatternNoteAudio` delegation system *technically* permits arbitrary graphs, so
> "batch-compile a note" once looked like "compile any graph." In practice we
> build a **tiny, closed set of shapes**, and the batched path recognizes the one
> dominant shape (melodic SSS) and dispatches a precompiled fused kernel for it.
> This is **implemented and wired** (pattern audio batching, the former "Phase 3"
> / a2). The current bottleneck has moved downstream to the a3 DSP/mixdown loop;
> see [`STATE_OF_PLAY.md`](STATE_OF_PLAY.md).

---

## 1. Structural vocabulary (the closed set of nodes)

Verified by reading every `PatternNoteAudio` implementer and every
`new PatternNote(...)` site in `studio/`.

| Node | Arity | Role |
|------|-------|------|
| `NoteAudioProvider` (via `SimplePatternNote`) | leaf | file source -> pitch-shift resample |
| `AutomatedPitchNoteAudio` | leaf | synth source (stateless oscillator) — **not on the production path** |
| `PatternNoteAudioChoice` | deferred | numeric choice -> resolves to a leaf at render time |
| `PatternNote` (layer mode) | **n-ary merge** | combine layers via aggregator (`combineLayers`) |
| `PatternNote` (delegate mode) | unary | wrap a node with one `NoteAudioFilter` |
| `PatternNoteLayer` | unary | source + one filter |
| `NoteAudioFilter` | unary | the transforms below |

**Filters (`NoteAudioFilter` implementations) — also closed:**
`ParameterizedVolumeEnvelope.Filter` (ADSR gain), `ParameterizedFilterEnvelope.Filter`
(ADSR-driven 40th-order time-varying lowpass), `ParameterizedLayerEnvelope.Filter`
(per-layer ADSR), `TremoloAudioFilter` (riser only), `ReversePlaybackAudioFilter`.

**Merge — 4 strategy variants** (`NoteAudioSourceAggregator.java:52-69`), selected
by one scalar choice in [0,1]; weight in parentheses:

1. `SOURCE, SOURCE, SOURCE` (6.0) — **the only one reachable in production**
2. `SOURCE, SOURCE, VOLUME_ENVELOPE` (3.0)
3. `SOURCE, SOURCE, FREQUENCY` (1.0)
4. `SOURCE, FREQUENCY, VOLUME_ENVELOPE` (2.0)

---

## 2. The canonical production note shape

There is essentially **one** production shape with bounded parametric variation,
built by `PatternNoteFactory` (`LAYER_COUNT = 3`, `new PatternNote(layers)`) then
wrapped by `PatternElementFactory`:

```
  layer 0 -- PatternNoteAudioChoice -> leaf source (file->pitch resample) -- [perLayerEnv?] --+
  layer 1 -- PatternNoteAudioChoice -> leaf source (file->pitch resample) -- [perLayerEnv?] --+
  layer 2 -- PatternNoteAudioChoice -> leaf source (file->pitch resample) -- [perLayerEnv?] --+
                                   |
                          MERGE (aggregator strategy 1..4; production = SSS = L0+L1+L2)
                                   |
                          [ filterEnvelope ]   <- optional, gated && melodic
                                   |
                          [ volumeEnvelope ]   <- optional, gated && melodic
                          (built once per voicing: MAIN and WET)
```

**Order, confirmed** (`PatternElementFactory.java:263-271`): per-layer envelopes
apply *before* the merge; the heavy filter + volume envelopes apply *after*,
filter-env inner, volume-env outer. So it is **both**: light per-layer shaping ->
merge -> heavy post-merge envelopes.

**Special case:** the riser (`RiseManager.java:87,90`) is delegate-mode
`source -> TremoloAudioFilter`. Rare and separate; left on the per-note path.

### Shape-audit findings (confirmed against source)

1. **Merge — only SSS reachable.** `aggregationChoice` defaults to `0.0`; the
   production factory uses `new PatternNote(layers)` and never sets it (the only
   non-default `setAggregationChoice` is a test). `getAggregator(c(0.0))` selects
   the highest-weight bucket `{SOURCE,SOURCE,SOURCE}`, and SSS in
   `ModularSourceAggregator` is plain `SummingSourceAggregator` — **the merge is
   just `L0 + L1 + L2`.** Strategies 2-4 are deferred.
2. **Synth leaf — unused in production.** Layers resolve to file sources via
   `PatternNoteAudioChoice` -> `NoteAudioProvider`; `AutomatedPitchNoteAudio` is
   not on the factory path. Deferred.
3. **Per-layer envelopes — present iff melodic.** `PatternElementFactory` passes
   `blend = melodic` to the note factory, so melodic notes carry per-layer +
   post-merge envelopes (filter/volume both gated `&& melodic`); kick/drum
   channels are non-melodic and carry (almost) none.

### Two architectural distinctions

- **Per-note filter envelope** (`ParameterizedFilterEnvelope`, inside the batched
  chain) is **distinct** from the **per-channel arrangement filter**, which stays
  downstream in `MixdownManager`. The batched kernel owns only the per-note one.

---

## 3. Scheduling axes — three independent dimensions of per-note variability

A batched kernel wants a fixed shape `[N, W]`. Across the N notes in a window,
three things vary independently — separating them is what made batching tractable:

| Axis | What varies | Resolution |
|------|-------------|------------|
| **Pitch** | resample ratio (note key vs sample root) | per-note `[N]` ratio tensor; kernel arithmetic identical regardless of ratios. No pitch-class tiling. |
| **Length** | how many frames the note sounds | uniform `W`-wide rows + validity mask; **the envelope's release *is* the duration** — no separate duration input. FIR padding (`±filterOrder/2 = 20` zeros/row) prevents cross-row bleed. |
| **Placement** | where in the window the note starts | offset-aware **scatter-add** keyed by a per-note `[N]` `destOffset`. The aligned reduce is the `destOffset ≡ 0` special case, so the proven chain upstream is untouched. |

**Streaming framing shrinks placement.** The real-time path
(`AudioScene.runnerRealTime`) renders one `bufferSize` window per tick. The batch
is the notes *active in that window* — **polyphony-bounded (tens, not thousands**;
the 2048-note figure was the offline whole-arrangement render). **Most active
notes started in an earlier buffer and continue**, so their `destOffset = 0` and
only their *within-note sampling offset* advances; only newly-triggered notes have
`destOffset > 0`. So scatter matters for a minority of notes per buffer.

---

## 4. How it is batched (IMPLEMENTED)

### 4.1 Dispatch routing — `PatternFeatures.render` (`PatternFeatures.java:97`)

When `PatternLayerManager.enableBatched` (= env `AR_PATTERN_BATCHED`) is set and a
batched renderer is present, `render` routes to
`BatchedPatternLayerRenderer.render`; otherwise `renderPerNote`. Each pattern
lazily builds one `BatchedPatternLayerRenderer`; constants
`BATCHED_SOURCE_LENGTH = 2048`, `BATCHED_TARGET_LENGTH = 1024`.

### 4.2 Classify, gather, dispatch — `BatchedPatternLayerRenderer` (studio/music)

`render` (`:230-280`) collects the notes overlapping `[startFrame, endFrame)`. A
note is batchable iff it carries a `BatchedNoteInputs` record
(`note.getBatchedInputs() != null`). If **every** overlapping note is batchable it
calls `dispatchBatched` and increments `batchedDispatchCount`; otherwise it
increments `fallbackCount` and routes the whole tick to `features.renderPerNote`.
Dispatch splits the range into `MAX_WINDOW`-bounded sub-windows so kernel size
stays bounded; a note spanning a boundary continues in the next sub-window from
its advancing sampling offset. The renderer is selected per overlap count from
buckets `{16, 32, 64, 128, 256, 512}` (`bucketFor` / `rendererFor`); `destOffsets[]`
scatter placement is `Math.max(0, noteStart - startFrame)` per row.

### 4.3 The gather recipe — `ScaleTraversalStrategy` + `BatchedNoteInputs`

`ScaleTraversalStrategy.createRenderedNote` calls `gatherBatchedInputs`, which
classifies via `BatchedNoteInputs.isMelodicSssShape(note)` and builds the flat
record via `BatchedNoteInputs.from(...)`. Classification peels the note structure
(never `innermostAudio()`): outer delegate-mode `PatternNote`
(`ParameterizedVolumeEnvelope.Filter`) -> delegate-mode `PatternNote`
(`ParameterizedFilterEnvelope.Filter`) -> layer-mode `PatternNote` whose
`getLayers()` is 3 wrappers, each a `PatternNoteAudioChoice` +
`ParameterizedLayerEnvelope.Filter`. Melodic SSS iff `getLayers().size()==3 &&
getAggregationChoice()==0.0` and each layer resolves to a file source.

The flat per-note record (gatherable into `[N]` / `[N, targetLength]` tensors),
all **GPU-evaluate-free** (cache hits + arithmetic):

| Field | Source |
|-------|--------|
| 3× source buffer | layer `PatternNoteAudioChoice.getDelegate(sel)` -> `NoteAudioProvider`; raw = `getProvider().getChannelData(ch, 1.0)` (cache) |
| 3× pitch ratio | `tuning.getTone(target).asHertz() / tuning.getTone(root).asHertz()` |
| 3× per-layer ADSR (8 args) | `ParameterizedLayerEnvelope.Filter` attack/sustain/release + volume0..3 |
| filter-env ADSR | `ParameterizedFilterEnvelope.Filter` attack/decay/sustain/release; cutoff = ADSR × filterPeak |
| volume-env ADSR | `ParameterizedVolumeEnvelope.Filter` attack/decay/sustain/release |
| `samplingOffset`, `destOffset`, `length` | frame scheduling (`frameForPosition`, `expectedFrameCount`) |

**Decision: envelope curves are generated in-kernel from `[N]` ADSR scalars.**
There is no way to produce an envelope curve without running an envelope kernel,
so pre-materializing curves per note would re-introduce N GPU evaluates per
window. Instead the kernel takes scalar ADSR tensors and generates the per-layer
gain, filter cutoff-Hz, and volume gain curves internally — one dispatch, no
per-note evaluate.

### 4.4 The fused kernel — `BatchedPatternRenderer` (engine/audio)

`buildBatchedSssChainPlacedFromScalars(sources[], ratios[], layerEnvParams[][],
filterAdsr[], volumeAdsr[], destOffsets, samplingOffsets, windowWidth)` is the
fully fused production form, dispatched once per window:

```
per active note n in window W:
  layer i in {0,1,2}: resample(srcᵢ, ratioᵢ) at samplingOffset, masked to length
                      Lᵢ = resampleᵢ · perLayerEnv(adsrᵢ)         [N, W]
  merged   = L0 + L1 + L2                                          (SSS = plain sum)
  filtered = paddedRowFIR(merged, filterCutoffEnv(adsr))          (reused back-half)
  voiced   = filtered · volumeEnv(adsr)                           (reused back-half)
out[f]     = Σ_n scatterAddFlat(voiced[n], destOffset[n])         (aligned reduce = destOffset≡0)
```

`buildResampleProducer` does pitch resample as a producer (not host math). The
melodic-SSS front half (3 sources -> per-layer env -> sum) is the new work; the
back half (filter env -> volume env) is the verified-RMS=0 chain from PR #175.
`accumulateBatchedOutput` is the single evaluate boundary, shared with the
per-note path.

---

## 5. Validation & sentinel discipline

The gate is `batchedDispatchCount > 0 && fallbackCount == 0` measured **at
production density**, plus batched-vs-`renderPerNote` RMS = 0 acoustic
equivalence. This is verified: at production density the batched path fires for
every tick and never falls back. Deferred shapes (drums/non-melodic, merge
strategies 2-4, synth leaves, riser/tremolo) classify "unsupported" and route to
`renderPerNote` with a loud `fallbackCount++` — never silently.

---

## 6. Lessons from the prior attempt

The earlier `feature/audio-scene-redesign` wiring rendered **zero** notes through
the batched path (`batchedDispatchCount = 0`, `fallbackCount = 476`). Root cause:
it assumed a note was **one** leaf source — `populateBatchedInputs` called
`innermostAudio()` and hardcoded `pitchRatio = 1.0`. But every production note is
a **3-layer merge**; a layer-mode `PatternNote` has no single innermost audio
(`combineLayers` throws). Secondary failures: the gather called `.evaluate()`
per note (relocating, not removing, the JNI cost), and measurements were
tautological or run at the wrong (low) density three times running.

The discipline that prevents recurrence: **read the note structure, never collapse
it** via `innermostAudio()`; and **gate on `batchedDispatchCount > 0 /
fallbackCount == 0` at production density** with RMS = 0 equivalence — never trust
"verified locally" at reduced density.

---

## 7. Deferred (each falls back loudly)

Non-melodic/drum notes (bare 3-source sum), merge strategies 2-4,
FREQUENCY/VOLUME_ENVELOPE aggregator roles, synth leaves, the riser/tremolo note,
pitch-class tiling (resample caching), multi-buffer windows, and the
optimizer-scale 32-measure batch.

---

## Key files

`studio/music/.../PatternFeatures.java`,
`studio/music/.../BatchedPatternLayerRenderer.java`,
`studio/music/.../PatternLayerManager.java`,
`studio/music/.../BatchedNoteInputs.java`,
`studio/music/.../ScaleTraversalStrategy.java`,
`engine/audio/.../BatchedPatternRenderer.java`,
`engine/audio/.../notes/NoteAudioSourceAggregator.java`,
`engine/audio/.../notes/RenderedNoteAudio.java`,
`engine/audio/.../notes/NoteAudioProvider.java`,
`.../Parameterized{Volume,Filter,Layer}Envelope.java`.
