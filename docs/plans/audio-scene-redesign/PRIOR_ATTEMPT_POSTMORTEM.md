# Prior Attempt Postmortem — `feature/audio-scene-redesign`

> Why the earlier batched-rendering wiring did not achieve a1/a2/a3 layer
> separation, what is salvageable, and the one mistaken assumption that explains
> the whole failure. Companion to
> [`REALTIME_RENDERING_STATE_OF_PLAY.md`](REALTIME_RENDERING_STATE_OF_PLAY.md)
> and [`NOTE_GRAPH_SHAPES.md`](NOTE_GRAPH_SHAPES.md).

## What landed vs. what stalled

The branch's **kernel** work merged to master via **PR #175** and is sound: the
`BatchedPatternRenderer` 4-kernel chain (resample → filter env → volume env →
reduce) with **acoustic equivalence verified at RMS = 0** against the per-note
reference. Keep it. On master, however, `BatchedPatternLayerRenderer.render()`
**unconditionally falls back to `features.renderPerNote()`** (≈ line 240) — the
batched path is built but dormant.

The **production wiring** to actually fire the batched path never worked. It
lives in 5 unmerged commits (and the branch is 278 commits behind master):
`ScaleTraversalStrategy.populateBatchedInputs`, batched fields on
`RenderedNoteAudio`, `AudioScenePopulation` dispatch, plus honest verification
tests.

## The single root cause: it assumed the wrong note shape

`populateBatchedInputs` tried to reduce each note to **one** leaf source:

```java
PatternNoteAudio leaf = patternNote.innermostAudio();   // expects a single source
Producer<PackedCollection> leafAudioProducer = leaf.getAudio(...);
PackedCollection raw = PatternFeatures.materialiseProducer(leafAudioProducer);
...
note.setBatchedSource(src);
note.setBatchedPitchRatio(1.0);            // hardcoded — ignores pitch
```

But **every production note is a 3-layer merge**, not a single source
(`PatternNoteFactory.LAYER_COUNT = 3`, `PatternNoteFactory.java:42`;
`new PatternNote(layers)` at line 121). A layer-mode `PatternNote` has no single
"innermost audio" — `innermostAudio() → getAudio() → combineLayers(noteDuration =
-1)` throws `UnsupportedOperationException`. The branch's own honest test
(commit `9271c2525`) records the result: **`batchedDispatchCount = 0`,
`fallbackCount = 476`** — the batched path fired for zero notes.

The batched-input schema was `(1 source, 1 pitch ratio, vol env, filter env)`.
The real note is `(3 sources, 3 pitch ratios, per-layer envelopes, 1 merge
strategy, post-merge filter env, post-merge volume env)`. The schema was wrong
by construction; no amount of wiring could populate it. (See
[`NOTE_GRAPH_SHAPES.md`](NOTE_GRAPH_SHAPES.md) for the actual shape.)

## Two secondary problems (both fatal on their own)

1. **The gather re-introduced the per-note cost it was meant to remove.** Even
   when it didn't throw, `populateBatchedInputs` called
   `materialiseProducer(...)` (= `.evaluate()`) **per note** and `setMem`-copied
   into a fixed buffer. Flattening a note sub-graph to a flat tensor by
   materialising it on the host is the per-note JNI round-trip, relocated
   earlier in the tick — not eliminated.
2. **Measurements were tautological or off-scenario, three times running.**
   (a) The original acoustic-equivalence test compared the per-note path to
   itself (flag-on *was* the per-note path). (b) A 0.4373 ratio was synthetic —
   the test hand-populated the batched fields. (c) The branch's honest 0.3214
   ratio is the **per-note fallback at low note density**, not the batched path
   and not the dense 32-measure / 64-notes-per-measure case that actually
   profiles at 3.3× over budget. The scenario that passed was never the scenario
   that fails.

## What to carry forward (and what to discard)

**Keep:**
- The merged `BatchedPatternRenderer` kernel (RMS = 0). It is the a2 engine.
- The **sentinel-counter verification discipline**: `batchedDispatchCount` must
  advance and `fallbackCount` must stay 0, **measured at production density**.
  This is the guard against the tautological-complete trap that bit the branch
  three times.
- The fail-loud fallback infrastructure (warnings, counters, strict mode).

**Discard:**
- `populateBatchedInputs` as written. Its `innermostAudio()`-to-single-leaf
  premise is the bug. Do not forward-port it.
- The "a downstream/ringsdesktop caller must populate the fields" framing — the
  note is constructed inside common; the flattening must happen in common.

**Do not rebase the branch.** It is 278 commits behind and its core premise is
wrong-shaped. Reattempt the a1↔a2 seam against the *actual* note shape.

## The reframed design fork

The seam cannot be a flat per-note tensor gather populated by materialising each
note. Two viable directions (now informed by the bounded shape vocabulary in
[`NOTE_GRAPH_SHAPES.md`](NOTE_GRAPH_SHAPES.md)):

- **(1) Graph-level batching.** Compose all notes' producer sub-graphs into one
  `CollectionProducer` and evaluate once. Keeps notes as graphs; the bounded
  shape set makes "one uniform graph shape across N notes" tractable rather than
  open-ended.
- **(2) Flat-note representation.** Make a *scheduled* note a flat descriptor by
  construction — resolve the 3 layers + merge strategy + envelope params into a
  fixed input record at schedule time (a1), so a2 never reverse-engineers a flat
  form from a sub-graph.

The decisive new fact: because the production note vocabulary is a **small,
closed set** (one dominant 3-layer shape × 4 merge strategies × optional
envelopes), either direction reduces to **recognize-the-shape, dispatch-the-
matching-precompiled-kernel** — not "compile an arbitrary graph." That scope
reduction is the subject of [`NOTE_GRAPH_SHAPES.md`](NOTE_GRAPH_SHAPES.md).
