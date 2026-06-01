# a2 Batched Rendering — Integration Design

> How the verified melodic-SSS kernel (`BatchedPatternRenderer.buildBatchedSssChainPlaced`)
> gets wired into production pattern rendering. Written after a full investigation
> of the current (master-derived) dispatch, note-construction, and
> envelope/source/pitch surfaces. Evidence cited to source. Companion to
> [`NOTE_GRAPH_SHAPES.md`](NOTE_GRAPH_SHAPES.md) and
> [`VARIABLE_NOTE_SCHEDULING.md`](VARIABLE_NOTE_SCHEDULING.md).

---

## 1. Current dispatch surface (clean stub — nothing to undo)

- `PatternFeatures.render` (`PatternFeatures.java:93-107`) routes to
  `BatchedPatternLayerRenderer.render` when `PatternLayerManager.enableBatched`
  (= `AR_PATTERN_BATCHED`, default false) and a renderer is present; otherwise
  `renderPerNote`.
- `BatchedPatternLayerRenderer.render` (`:198-242`) counts overlapping notes,
  primes the per-bucket renderer cache (`rendererFor(bucketFor(count))`,
  buckets `{16,32,64,128,256,512}`), then **unconditionally** calls
  `features.renderPerNote(...)`. No batched dispatch, no instrumentation.
- `PatternLayerManager` (`:139,146,153,291-306`): `enableBatched` static flag,
  `BATCHED_SOURCE_LENGTH=2048`, `BATCHED_TARGET_LENGTH=1024`, lazily builds one
  `BatchedPatternLayerRenderer` per pattern.
- `RenderedNoteAudio` (`:60-70`) has only `offset`, `expectedFrameCount`,
  `offsetArg`, `producerFactory` — **no batched fields** (the failed branch's
  fields are not here; we start clean).

**Implication:** integration is additive. We replace the unconditional fallback
with: gather → dispatch `buildBatchedSssChainPlaced` once per window → accumulate;
fall back loudly only for notes/shapes we don't yet support.

## 2. The note we must read (and the opacity to fix)

The production melodic note is built by `PatternNoteFactory` (`:105-122`): a
layer-mode `PatternNote` whose `getLayers()` returns **3 `PatternNoteLayer`s**,
each wrapping a `PatternNoteAudioChoice` (the sample selector) with a
`ParameterizedLayerEnvelope.Filter` (the per-layer envelope). Post-merge,
`PatternElementFactory` (`:263-271`) wraps the note (delegate-mode) with
`ParameterizedFilterEnvelope` then `ParameterizedVolumeEnvelope`, per voicing,
gated by `&& melodic`.

**Shape classification (melodic SSS):** `note.getLayers() != null && size()==3 &&
getAggregationChoice()==0.0` and each layer resolves to a file-backed source.
Read the structure — never `innermostAudio()` (the prior branch's bug: it assumed
one source and threw on the 3-layer note).

**Opacity to fix (legitimately):** `PatternNoteLayer.getDelegate()` /
`getFilter()` are `protected` (`PatternNoteAudioAdapter`). The post-merge envelope
`Filter`s are inner classes of the `Parameterized*Envelope` types. To gather
inputs in `studio/music` we must expose, via methods **on the owning types**
(not external pokers):
- the per-layer source choice + its `ParameterizedLayerEnvelope.Filter`,
- the note's post-merge filter/volume `Filter`s,
- each `Filter`'s resolved ADSR scalars (attack/decay/sustain/release, already
  computed as doubles from `ParameterSet`+voicing+mode+automationLevel —
  `ParameterizedVolumeEnvelope.Filter.getAttack()/getDecay()/getSustain()/getRelease()`,
  same for filter/layer envelopes).

## 3. The per-note flat record (what the kernel consumes)

Per active note, gatherable into `[N]` / `[N, targetLength]` tensors:

| Field | Source | Cost |
|-------|--------|------|
| 3× source buffer `[sourceLength]` | layer choice → `NoteAudioProvider` raw sample via `WaveDataProvider.getChannelData(ch, 1.0)` / cache | cache hit / file read — **no GPU evaluate** |
| 3× pitch ratio | `tuning.getTone(target).asHertz() / tuning.getTone(root).asHertz()` (`NoteAudioProvider:262`) | arithmetic |
| 3× per-layer ADSR scalars | `ParameterizedLayerEnvelope.Filter` getters | arithmetic |
| filter-env ADSR scalars | `ParameterizedFilterEnvelope.Filter` getters | arithmetic |
| volume-env ADSR scalars | `ParameterizedVolumeEnvelope.Filter` getters | arithmetic |
| `samplingOffset`, `destOffset`, `length` | from frame scheduling (`ScaleTraversalStrategy.createRenderedNote`: `frameForPosition`, `expectedFrameCount`) | arithmetic |

Everything is cheap **except** the envelope *curves* — which the kernel currently
expects pre-materialized. That is the one fork.

## 4. DECISION — generate envelope curves inside the kernel (batched), not per note

The kernel input today (`filterCutoffs`, `volumeEnvelopes`, `layerEnvelopes`) is
`[N, targetLength]` **curves**. Investigation finding: **there is no method to
produce an envelope curve without running an envelope kernel**
(`AudioProcessingUtils.getVolumeEnv/getFilterEnv/getLayerEnv` consume audio and
emit shaped audio). So pre-materializing curves per note = **N GPU evaluates per
window** — the exact per-note-dispatch cost the redesign exists to remove.

**Decision: extend the kernel to generate the envelope curves from `[N]` ADSR
scalar tensors** (attack/decay/sustain/release/duration per note, per envelope),
as the original Phase 3 design anticipated ("envelope generalised from `shape(1)`
to `shape(N)`"). Then the per-note gather is **GPU-evaluate-free** — only scalars,
ratios, offsets, and raw source buffers (cache hits). This keeps the architecture
honest (one dispatch per window, no per-note evaluate) and keeps the next work in
the well-tested kernel domain before the risky orchestration.

Consequence: the per-note flat record carries **ADSR scalars**, not curves. The
kernel's front gains three batched envelope-curve generators (per-layer gain,
filter cutoff-Hz, volume gain) built from `[N]` scalar inputs, reusing the same
shape math as the existing `AudioProcessingUtils` envelopes.

## 5. Sequenced plan

**K. Batched envelope generation (kernel — next).** Add batched curve generators
to `BatchedPatternRenderer`, one per envelope type, taking `[N]` ADSR scalar
tensors → `[N, targetLength]` curves, verified RMS=0 against the existing
per-note `AudioProcessingUtils` envelopes. Then a `buildBatchedSssChainPlaced`
overload that takes ADSR scalar tensors instead of pre-materialized curves.
Order: volume (gain multiply) → per-layer (3-segment) → filter cutoff (Hz curve
feeding the existing FIR stage).

**C. Classifier + structural accessors (orchestration).** Methods on `PatternNote`
/ `PatternNoteLayer` / `Parameterized*Envelope.Filter` exposing the 3 layers'
sources, ratios, and ADSR scalars. A `BatchedNoteShape` classifier that returns
the melodic-SSS record or "unsupported." Unit-tested on a factory-built note:
gathered record → kernel == `renderPerNote` (RMS=0).

**G. Gather + dispatch.** In `BatchedPatternLayerRenderer.render`: gather active
notes' records into `[N,*]` tensors for the window `W = 4 × bufferSize`, dispatch
`buildBatchedSssChainPlaced` once, accumulate into destination. Notes/shapes not
classified → `renderPerNote` with a loud `fallbackCount++`.

**S. Sentinel gate.** `batchedDispatchCount` / `fallbackCount` on
`BatchedPatternLayerRenderer`; an `AudioScenePopulation`-level test asserting,
**at production density**, `batchedDispatchCount>0`, `fallbackCount==0`, and
batched-vs-`renderPerNote` RMS=0. This is the trap-proof gate the prior branch
faked three times.

**Spikes (from `VARIABLE_NOTE_SCHEDULING.md`):** scatter cost at `W=4×bufferSize`;
active-note count per window in real scenes; continuing-note envelope slice
(ADSR position advanced by `samplingOffset`).

## 6. What stays deferred (loud fallback)

Drums/non-melodic notes, merge strategies 2–4, synth leaves, the riser/tremolo
note, and runtime-variable channel count — each classified "unsupported" and
routed to `renderPerNote` with `fallbackCount++`, never silently.

## 6b. The gather recipe (verified extraction map)

Investigated against source — the exact callable API to build a note's flat
record. Almost everything is already public; only one accessor pair must be
added.

**Kernel entry point (done, verified):**
`BatchedPatternRenderer.buildBatchedSssChainPlacedFromScalars(sources[], ratios[],
layerEnvParams[layer][8], filterAdsr[5], volumeAdsr[5], destOffsets, windowWidth)`
— generates all envelope curves internally from `[N]` scalars; one dispatch. The
gather only has to fill these scalar/source/offset tensors.

**Note structure to peel** (from `element.getNote(voicing)`): outer
delegate-mode `PatternNote` (filter = `ParameterizedVolumeEnvelope.Filter`) →
its delegate is a delegate-mode `PatternNote` (filter =
`ParameterizedFilterEnvelope.Filter`) → its delegate is the layer-mode
`PatternNote` (`getLayers()` = 3 layer wrappers, each holding a
`PatternNoteAudioChoice` + `ParameterizedLayerEnvelope.Filter`). Classify melodic
SSS: `getLayers().size()==3 && getAggregationChoice()==0.0`.

**ADSR scalars (all getters public):**
- Volume: `ParameterizedVolumeEnvelope.Filter.getAttack(dur)/getDecay()/getSustain()/getRelease(dur)`,
  with `sustain *= adj`, `release *= adj`, `adj = adjustmentBase +
  adjustmentAutomation * automationLevel`.
- Filter: `ParameterizedFilterEnvelope.Filter.getAttack()/getDecay()/getSustain()/getRelease()`,
  same `adj` on sustain/release; cutoff = ADSR × `filterPeak`.
- Per-layer (8 args): `ParameterizedLayerEnvelope.Filter.getAttack()/getSustain()/getRelease()`
  + `getVolume0()..getVolume3()` (mainDuration = note duration).

**Source + pitch (all public):** layer `PatternNoteAudioChoice.getDelegate(audioSelection)`
→ `SimplePatternNote.getNoteAudio()` → `NoteAudioProvider`; raw sample =
`getProvider().getChannelData(channel, 1.0)`; ratio =
`getTuning().getTone(target).asHertz() / getTuning().getTone(getRoot()).asHertz()`.
`audioSelection` from `NoteAudioContext.getAudioSelection()`.

**Offsets:** `samplingOffset`/`destOffset`/duration from
`ScaleTraversalStrategy.createRenderedNote` (`frameForPosition`,
`expectedFrameCount`).

**The one opacity to fix:** `PatternNote.getDelegate()/getFilter()` and
`PatternNoteLayer.getDelegate()/getFilter()` are `protected`. Add public
accessors (or read from a same-package gatherer) — this is the legitimate
"make it not opaque" work, and the only structural change the gather requires.

## 7. Key files

`PatternFeatures.java`, `BatchedPatternLayerRenderer.java`,
`PatternLayerManager.java`, `RenderedNoteAudio.java`,
`ScaleTraversalStrategy.java`, `PatternNote.java`, `PatternNoteLayer.java`,
`PatternNoteAudioChoice.java`, `Parameterized{Volume,Filter,Layer}Envelope.java`,
`NoteAudioProvider.java`, `AudioProcessingUtils.java`,
`engine/audio/.../BatchedPatternRenderer.java`.
