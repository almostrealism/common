# Partial Pattern Rendering: Performance Analysis

## Status

Phases 1 (pre-filtering) and 2 (caching) are implemented. Phase 3 (partial
note evaluation) is not started and may not be necessary.

## What Was Done

### Phase 1: Pre-filter notes before evaluate()

`PatternFeatures.render()` checks each note's frame range (via
`RenderedNoteAudio.getExpectedFrameCount()` and absolute offset) against the
target buffer range *before* calling `evaluate()`. Non-overlapping notes are
skipped entirely.

**Files:** `RenderedNoteAudio`, `PatternElement.getEffectiveDuration()`,
`ScaleTraversalStrategy.createRenderedNote()`, `PatternFeatures.render()`

### Phase 2: Cache evaluated note audio across buffers

`NoteAudioCache` stores evaluated `PackedCollection` results keyed by absolute
frame offset. Notes spanning multiple consecutive buffers are evaluated once.
`evictBefore(startFrame)` removes entries for notes that ended before the
current buffer.

**Files:** `NoteAudioCache`, `PatternLayerManager` (holds cache instance),
`PatternFeatures.render()` (checks/stores cache)

### Results

| Metric | Before | After Phase 1+2 |
|--------|--------|-----------------|
| evaluate() calls per buffer | 5 (4 wasted) | 1 (cached after first eval) |
| Total frames evaluated | 50,433 | ~10,000 (once, then cached) |
| Time per 1024-frame buffer | ~342ms | ~52ms |

### Phase 3: Partial note evaluation (not started)

Only compute the frames of a note that fall within the buffer. Requires
changes deep in the audio pipeline (random-access audio sources, offset-aware
envelopes). Only pursue if phases 1+2 prove insufficient.
