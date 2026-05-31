# Note-Audio Graph Shapes — The Scope Reduction

> **The key realization.** The `PatternNoteAudio` delegation system *technically*
> permits arbitrary graphs, so "batch-compile a note" looks like "compile any
> graph" — a hard problem. But in practice we only ever construct a **tiny,
> closed set of shapes.** Recognizing the handful of shapes we actually build,
> and precompiling a batched kernel for each, turns the a2 problem from
> "general graph compilation" into "classify-and-dispatch." This document
> enumerates the real shapes (with code evidence) and the strategy that follows.
>
> Companion to [`PRIOR_ATTEMPT_POSTMORTEM.md`](PRIOR_ATTEMPT_POSTMORTEM.md) and
> [`REALTIME_RENDERING_DIAGRAMS.md`](REALTIME_RENDERING_DIAGRAMS.md).

---

## 1. The structural vocabulary (what nodes exist)

Verified by reading every `PatternNoteAudio` implementer and every
`new PatternNote(...)` site in `studio/`.

| Node | Arity | Role | Where |
|------|-------|------|-------|
| `NoteAudioProvider` (via `SimplePatternNote`) | leaf | file source → pitch-shift resample | `engine/audio/.../notes/NoteAudioProvider.java` |
| `AutomatedPitchNoteAudio` | leaf | synth source (stateless oscillator) | `studio/music/.../notes/AutomatedPitchNoteAudio.java` |
| `PatternNoteAudioChoice` | deferred | numeric choice → resolves to a leaf at render time | `…/notes/PatternNoteAudioChoice.java` |
| `PatternNote` (layer mode) | **n-ary merge** | combine layers via aggregator | `…/notes/PatternNote.java` (`combineLayers`) |
| `PatternNote` (delegate mode) | unary | wrap a node with one `NoteAudioFilter` | `…/notes/PatternNote.java` |
| `PatternNoteLayer` | unary | source + one filter | `…/notes/PatternNoteLayer.java` |
| `NoteAudioFilter` | unary | the transforms (below) | `engine/audio/.../notes/NoteAudioFilter.java` |

**The filters (`NoteAudioFilter` implementations) — also a closed set:**
`ParameterizedVolumeEnvelope.Filter` (ADSR gain), `ParameterizedFilterEnvelope.Filter`
(ADSR-driven 40th-order time-varying lowpass), `ParameterizedLayerEnvelope.Filter`
(per-layer ADSR), `TremoloAudioFilter` (riser only), `ReversePlaybackAudioFilter`.

**The merge — exactly 4 strategy variants** (`NoteAudioSourceAggregator.java:52–69`),
selected by one scalar choice in [0,1]:

| # | Layer roles | Weight |
|---|-------------|--------|
| 1 | `SOURCE, SOURCE, SOURCE` | 6.0 |
| 2 | `SOURCE, SOURCE, VOLUME_ENVELOPE` | 3.0 |
| 3 | `SOURCE, SOURCE, FREQUENCY` | 1.0 |
| 4 | `SOURCE, FREQUENCY, VOLUME_ENVELOPE` | 2.0 |

---

## 2. The canonical production shape (what we actually build)

There is essentially **one** production note shape, with bounded parametric
variation. Built by `PatternNoteFactory` (`LAYER_COUNT = 3`, line 42;
`new PatternNote(layers)`, line 121), then wrapped by `PatternElementFactory`
(lines 263–271):

```
  ┌─ layer 0 ─ PatternNoteAudioChoice → leaf source (file→pitch resample) ─ [perLayerEnv?] ─┐
  ├─ layer 1 ─ PatternNoteAudioChoice → leaf source (file→pitch resample) ─ [perLayerEnv?] ─┤
  ├─ layer 2 ─ PatternNoteAudioChoice → leaf source (file→pitch resample) ─ [perLayerEnv?] ─┤
  └──────────────────────────────────────────────────────────────────────────────────────┘
                                  │
                         MERGE (aggregator strategy ∈ {1,2,3,4}, chosen by aggregationChoice)
                                  │
                         [ filterEnvelope ]   ← optional, gated by enableFilterEnvelope (&& melodic)
                                  │
                         [ volumeEnvelope ]   ← optional, gated by enableVolumeEnvelope
                                  │
                         (built once per voicing: MAIN and WET)
```

**Order, confirmed** (`PatternElementFactory.java:263–271`): per-layer envelopes
are applied *before* the merge; the heavy filter + volume envelopes are applied
*after* the merge, filter-env inner, volume-env outer. So the answer to "merge
first or envelopes first" is **both**: light per-layer shaping → merge → heavy
post-merge envelopes.

**One special case:** the riser (`RiseManager.java:87,90`) is delegate-mode
`source → TremoloAudioFilter`. It is rare, separate, and can be handled by its
own kernel or left on the per-note path without affecting the main budget.

---

## 3. The batched-input schema this implies

Because the shape is fixed, a note's *entire* identity is a flat record of
scalars/buffers — gatherable into `[N]`-shaped tensors across N notes that share
a shape class:

```
per note:
  layer[0..2]:  { sourceBuffer, pitchRatio, perLayerEnvParams? }
  aggregationChoice           (selects merge strategy 1..4)
  filterEnvParams  (ADSR)     (present iff filterEnvelope enabled)
  volumeEnvParams  (ADSR)     (present iff volumeEnvelope enabled)
  startOffset, duration
```

This is the schema the prior attempt got wrong: it modeled `(1 source, 1 pitch,
2 envelopes)` and called `innermostAudio()` to find the single source. The real
schema is `(3 sources, 3 pitches, per-layer + merge + 2 post-merge envelopes)`.
See [`PRIOR_ATTEMPT_POSTMORTEM.md`](PRIOR_ATTEMPT_POSTMORTEM.md).

---

## 4. The strategy: classify-and-dispatch precompiled kernels

The general "compile an arbitrary note graph" problem collapses to:

1. **Enumerate the shape classes.** The structural permutation count is small:
   `merge-strategy (4) × filterEnv (on/off) × volumeEnv (on/off)` ≈ **16 worst
   case**, and far fewer in practice because `enableFilterEnvelope` /
   `enableVolumeEnvelope` are effectively static per build, and the
   `FREQUENCY`/`VOLUME_ENVELOPE` layer roles are a bounded handful. Add 1 riser
   shape. Call it a **single-digit family of kernels**, not an open set.
2. **Precompile one batched kernel per shape class** (or per merge strategy, with
   envelope stages toggled), reusing the already-merged `BatchedPatternRenderer`
   chain as the post-merge half and adding the 3-layer merge as the front half.
3. **At schedule time (a1), classify each note** into its shape class by
   inspecting the constructed `PatternNote` (mode, layer count, aggregation
   choice, attached filters) and emit a flat input record.
4. **Group notes by shape class and dispatch the matching kernel once per
   group per tick.** Notes of the same class batch together; different classes
   are different (still O(1)-per-tick) dispatches.
5. **Fall back loudly, never silently.** Any note that doesn't match a known
   shape class routes to `renderPerNote` *and increments a counter the
   production test asserts is zero* at production density.

This is the concrete realization of the a1↔a2 seam: a1 produces flat per-note
records tagged with a shape class; a2 is a small set of precompiled batched
kernels keyed by shape class; the seam between them is plain data, not a graph.

---

## 5. Why this is the unlock

- **It removes the open-ended-graph fear.** We are not compiling arbitrary
  DAGs; we are recognizing ~a handful of known templates. The "variable note
  scheduling as a static graph" question (Diagram C) becomes "which of the N
  templates does this note match, and gather its inputs."
- **It reuses the proven kernel.** The merged `BatchedPatternRenderer` (RMS = 0)
  is the post-merge half; only the 3-layer merge front-half is new.
- **It makes correctness checkable.** Per shape class, batched vs per-note
  acoustic equivalence is a finite test matrix, not an open-ended claim.
- **It is the right `PatternNote` boundary.** The classifier *is* the missing
  piece the prior attempt faked with `innermostAudio()`. Built correctly, it
  knows a note is a 3-layer merge and gathers 3 sources, not 1.

---

## 6. Shape-audit findings (CONFIRMED, May 2026)

The shape audit is done; the four questions are answered against source. The
live set is far smaller than the worst-case bound.

1. **Merge strategy — only SSS is reachable in production.** `aggregationChoice`
   defaults to `0.0` and the production factory uses `new PatternNote(layers)`
   which never sets it (`PatternNoteFactory.java:121`; the only non-default
   `setAggregationChoice` is in `RescalingAggregationTests`, a test).
   `getAggregator(c(0.0))` selects the first/highest-weight bucket =
   `{SOURCE,SOURCE,SOURCE}`, and SSS in `ModularSourceAggregator` is plain
   `SummingSourceAggregator` — **the merge is just `L0 + L1 + L2`.** The other 3
   strategies (and thus the `FREQUENCY`/`VOLUME_ENVELOPE` aggregator roles) are
   **deferred** until/unless `aggregationChoice` is varied.
2. **Synth leaf — not used in production.** `AutomatedPitchNoteAudio` /
   `StatelessSourceNoteAudioAdapter` are not on the factory path (layers are
   `PatternNoteAudioChoice` → file sources via `NoteAudioProvider`). **Deferred.**
3. **Per-layer envelopes — present iff melodic.** `PatternElementFactory.java:263`
   calls `getNoteFactory().apply(params, null, melodic, noteLayers)` → `blend =
   melodic` → per-layer envelope applied (`PatternNoteFactory.java:111-112`).
   Kick/drum channels are non-melodic → bare layers, and their post-merge
   filter/volume envelopes are also gated `&& melodic` (lines 266-271). So
   **melodic notes carry per-layer + post-merge envelopes; drums carry (almost)
   none.**
4. **Merge + FREQUENCY/VolEnv roles are new kernel work.**
   `BatchedPatternRenderer.buildBatchedChain` is single-source per note
   (`[N, sourceLength]`): resample → filterEnv → volumeEnv → reduce. No merge,
   no FREQUENCY/EQ, no VolEnv-aggregator role. **The back half (filterEnv →
   volumeEnv → reduce) is reusable verbatim; the 3-layer front half is new.**

## 7. First-iteration scope (the thing to actually build)

Target exactly ONE shape class — the **melodic SSS note** — and reuse the
existing kernel's back half:

```
  NEW front half                          REUSED from BatchedPatternRenderer
  ┌────────────────────────────────┐      ┌──────────────────────────────────────┐
  per note n (in a batch of N):
    layer i∈{0,1,2}: resample(srcᵢ, ratioᵢ) → perLayerEnvᵢ      (per-layer env multiply)
    merged = L0 + L1 + L2            ← SSS = plain sum
                                  merged → filterEnv → volumeEnv → Σ over N → [targetLength]
```

**Per-note input record (flat, gatherable into `[N]`/`[N,W]` tensors):**
`3×{ sourceBuffer, pitchRatio, perLayerEnvParams }`, `filterEnvParams`,
`volumeEnvParams`, `samplingOffset` (frames into the note), `destOffset` (frames
into the window), `length` (mask). (No `aggregationChoice` — fixed to SSS. No
synth. No `FREQUENCY`/`VolEnv` aggregator inputs.) See
[`VARIABLE_NOTE_SCHEDULING.md`](VARIABLE_NOTE_SCHEDULING.md) for how pitch,
length, and placement map onto a fixed-shape kernel.

**Out of first iteration (explicit, each falls back loudly):**
- Non-melodic (kick/drum) notes — bare 3-source sum, no envelopes; keep on
  `renderPerNote` initially (they are cheap and few) or add as shape class #2.
- Merge strategies 2–4, synth leaves, the riser/tremolo note.

**Build order:** (1) extend `BatchedPatternRenderer` front half from 1 source to
3-sources-each-resampled-+-layerEnv-then-summed, holding the back half fixed;
(2) write the classifier at the `RenderedNoteAudio` construction site that reads
the `PatternNote` structure (3 layers, SSS, melodic) and emits the flat record —
**reading the structure, not collapsing it via `innermostAudio()`**; (3) gather
N melodic notes per tick, dispatch once; (4) gate on the
`batchedDispatchCount > 0 / fallbackCount == 0` sentinel **at production
density**, with batched-vs-per-note RMS=0 acoustic equivalence.

## 8. Residual design question — RESOLVED

Within the melodic SSS class, notes differ on three independent axes, resolved
in [`VARIABLE_NOTE_SCHEDULING.md`](VARIABLE_NOTE_SCHEDULING.md):
- **Pitch** → per-note `[N]` ratio tensor (already supported); no tiling.
- **Length** → uniform `W`-wide rows; the envelope encodes duration; a validity
  mask zeros the tail.
- **Placement** → an offset-aware **scatter-add** keyed by a per-note `[N]`
  `destOffset`, which *generalizes* the kernel's existing aligned reduce
  (`destOffset ≡ 0` is current behavior). In the streaming model `W = bufferSize`
  and the batch is the polyphony-bounded set of notes active in the window, so
  this is small. Pitch-class tiling, multi-buffer windows, and the optimizer-
  scale 32-measure reduction are deferred optimizations.
