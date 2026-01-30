# Partial Pattern Rendering: Performance Analysis and Proposals

## Implementation Status

> **Phase 1 (Pre-filtering) and Phase 2 (Caching) are IMPLEMENTED** (January 2026)
>
> The render path has been unified: a single `PatternFeatures.render()` method
> handles both offline and real-time rendering. Pre-filtering skips notes that
> don't overlap the target frame range before the expensive `evaluate()` call.
> `NoteAudioCache` avoids re-evaluating notes that span multiple buffers.
>
> Rendering a 1024-frame buffer now takes ~52ms (down from ~61ms before
> pre-filtering, and compared to ~342ms of evaluate() time with no filtering).
> Phase 3 (partial note evaluation) is not yet implemented.

## Problem Statement

Rendering a single 1024-frame buffer (~23ms of audio) via
`PatternSystemManager.sum(ctx, channel, startFrame, 1024)` originally took
~61ms per buffer (with ~342ms of evaluate() time across all notes, 98% of
which was wasted on non-overlapping notes). Real-time audio requires this to
complete in under 23ms.

This document analyzes where time is spent and describes the changes made to
reduce unnecessary work.

---

## Code Path Analysis

### Call chain for frame-range rendering

```
PatternSystemManager.sum(ctx, channel, startFrame=0, frameCount=1024)
  |
  +-- For each PatternLayerManager on channel:
      |
      +-- PatternLayerManager.sum(renderCtx, voicing, audioChannel, 0, 1024)
          |
          +-- getAllElementsByChoice(0.0, duration)   // ALL elements, NOT filtered by frame range
          +-- frameToMeasure(0) -> startMeasure       // ~0.0 measures
          +-- frameToMeasure(1024) -> endMeasure      // ~0.012 measures
          +-- firstRepetition = 0, lastRepetition = 1 // Only 1st repetition overlaps
          |
          +-- For repetition 0 (the only one overlapping):
              +-- For each NoteAudioChoice:
                  +-- Create NoteAudioContext (calls nextNotePosition per element)
                  +-- renderRange(ctx, audioCtx, ALL_ELEMENTS, melodic, 0.0, 0, 1024)
```

### Inside render() -- where the waste was

Before Phase 1, the unified `render()` method (formerly `renderRange()`) did:

```java
elements.stream()
    .map(e -> e.getNoteDestinations(...))     // (A) Create RenderedNoteAudio per element
    .flatMap(List::stream)
    .forEach(note -> {
        audio = traverse(1, note.getProducer()).get().evaluate();  // (B) FULL note audio
        // ... overlap check AFTER evaluate ...                    // (C) Check & copy
    });
```

| Step | What it does | Cost |
|------|-------------|------|
| **(A)** getNoteDestinations | Creates `RenderedNoteAudio` with producer + absolute frame offset. Calls `nextNotePosition()` which scans all elements. | Moderate (per element) |
| **(B)** evaluate() | Evaluates the Producer to produce a `PackedCollection` containing the **entire note's audio** (e.g., 44100 frames for a 1-second sample) | **Expensive** |
| **(C)** overlap check + sum | Checks if `[noteStart, noteEnd)` intersects `[startFrame, endFrame)`. Copies overlapping frames. | Cheap |

### The fundamental problem

**Step (B) computes the full note audio before step (C) checks whether it overlaps
with the buffer.** A 1-second note at 44.1kHz produces 44,100 frames of audio, but
the buffer only needs at most 1,024 of those frames. If the note doesn't overlap the
buffer at all, all 44,100 frames are computed and then discarded.

### Secondary waste sources

1. **No element-level pre-filtering.** `getAllElementsByChoice(0.0, duration)` retrieves
   every element in the pattern regardless of position. A pattern with 20 elements where
   only 1 falls within the buffer's 0.012-measure window still processes all 20.

2. **`nextNotePosition()` scans all elements.** Called once per note in
   `NoteAudioContext.createVoicingDetails()`. It calls `getAllElements(position, duration)`
   and streams through all positions. With N elements and M notes per element, this is
   O(N * M * N) total work.

3. **No audio caching.** A note that spans multiple consecutive buffers is re-evaluated
   from scratch for each buffer. A 1-second note spans ~43 buffers (at 1024-frame
   buffer size). Its full audio is computed 43 times.

---

## Measured Results

### `renderTimingVsBufferSize` (seed 60, channel 0)

| Buffer size | Time | ms/frame |
|---|---|---|
| 256 frames | 475.9 ms | 1.8592 |
| 1024 frames | 61.1 ms | 0.0596 |
| 4096 frames | 33.9 ms | 0.0083 |
| 44100 frames | 59.4 ms | 0.0013 |

**Interpretation:** Time does NOT scale linearly with buffer size. The 256-frame
buffer takes 14x longer than the 4096-frame buffer despite requesting 16x fewer
frames. This confirms that the rendering pipeline is doing work proportional to
note count and note duration, not to buffer size.

The 256-frame buffer being slowest suggests per-invocation overhead dominates at
very small sizes. The 4096 and 44100 sizes are similar (~34-59ms), indicating
that the actual audio evaluation cost is similar regardless of how many output
frames are requested. (These times are for a seed with only 5 notes on channel 0;
seeds with more active patterns would show higher times.)

### `renderNoteAudioLengthAnalysis` (seed 48, channel 0, buffer [0, 1024))

| Metric | Value |
|--------|-------|
| Pattern elements | 5 |
| Notes created | 5 |
| Notes overlapping buffer | 1 |
| Notes NOT overlapping | 4 |
| Min note length | 9,917 frames |
| Max note length | 10,195 frames |
| Max note / buffer ratio | 10.0x |
| **Total frames evaluated** | **50,433** |
| **Frames actually useful** | **1,024** |
| **Frames wasted** | **49,409** |
| **Waste ratio** | **98.0%** |
| getNoteDestinations time | 8.3 ms |
| evaluate() total | 342.0 ms |
| evaluate() overlapping | 264.0 ms |
| evaluate() non-overlapping | 78.0 ms |
| Avg evaluate per note | 68.4 ms |

**Key findings:**

1. **4 out of 5 notes are evaluated unnecessarily.** They don't overlap the buffer
   at all, but their full audio (9,917-10,195 frames each) is computed and discarded.
   This wastes 78ms of evaluate() time.

2. **Even the 1 overlapping note wastes 90% of its frames.** It produces ~10,000 frames
   but only 1,024 are copied to the buffer. This wastes 264ms of evaluate() time on
   frames that are discarded.

3. **getNoteDestinations is cheap (8.3ms) compared to evaluate (342ms).** The bottleneck
   is clearly in the audio evaluation, not in computing note positions or offsets.

4. **These numbers are for a low-activity seed (5 notes on channel 0).** More active
   patterns would have more elements, more notes, and proportionally more waste.

### Where the 20s per buffer tick comes from

The `patterns.sum()` call itself takes ~61ms (at 1024 buffer size). The ~20 seconds
observed in the compiled batch cell tests comes from the effects pipeline: `cells.tick()`
falls back to `javaLoop()` which ticks the CellList 1024 times per buffer. The pattern
rendering waste is a separate problem from the effects processing overhead, but both
must be solved for real-time operation.

---

## Diagnostic Tests

Two tests in `AudioSceneRealTimeCorrectnessTest` measure the actual waste:

### `renderTimingVsBufferSize`

Times `patterns.sum()` with buffer sizes of 256, 1024, 4096, and 44100 frames.
Shows whether rendering time scales with buffer size or is constant.

### `renderNoteAudioLengthAnalysis`

Replicates the inner loop of `renderRange()` with instrumentation. For each
note that would be processed when rendering buffer [0, 1024):

- Records the note's absolute frame offset
- Times the `evaluate()` call
- Records the note's audio length (in frames)
- Checks overlap with the target buffer
- Computes aggregate statistics: notes overlapping vs total, frames useful vs
  evaluated, time in evaluate() for overlapping vs non-overlapping notes

---

## Proposals

### Proposal 1: Pre-filter notes by offset before evaluate() -- IMPLEMENTED

**Goal:** Never call `evaluate()` on a note that doesn't overlap the buffer.

**Approach:** The note's absolute frame offset is known from `RenderedNoteAudio.getOffset()`
before evaluation. The note's duration is computed from `PatternElement.getEffectiveDuration()
* OutputLine.sampleRate`. An `expectedFrameCount` field on `RenderedNoteAudio` enables the
overlap check to run before `evaluate()`.

**Changes (implemented):**

1. `RenderedNoteAudio`: Added `int expectedFrameCount` field with 3-parameter constructor
2. `PatternElement`: Added `getEffectiveDuration()` extracting duration logic from `getNoteAudio()`
3. `ScaleTraversalStrategy`: Added `createRenderedNote()` helper that computes `expectedFrameCount`
   from `element.getEffectiveDuration() * OutputLine.sampleRate`
4. `PatternFeatures.render()`: Checks overlap using `[offset, offset + expectedFrameCount)`
   BEFORE calling `evaluate()`. Skips notes that don't overlap.

```java
// In render(), BEFORE evaluate:
if (note.getExpectedFrameCount() > 0) {
    int noteEstimatedEnd = noteStart + note.getExpectedFrameCount();
    if (noteEstimatedEnd <= startFrame || noteStart >= endFrame) {
        return;  // Skip -- no overlap, don't evaluate
    }
} else if (noteStart >= endFrame) {
    return;
}
```

**Impact:** Eliminates evaluate() calls for all non-overlapping notes.

**Risk:** Low. The expected frame count is an estimate; the actual audio might be
slightly different. A post-evaluate overlap check still runs as a safety net.

---

### Proposal 2: Pre-filter elements by position -- PARTIALLY ADDRESSED

**Goal:** Don't even create `RenderedNoteAudio` for elements that clearly fall outside
the buffer's time range.

**Status:** This is partially addressed by the repetition-level filtering in
`PatternLayerManager.sumInternal()`, which converts the frame range to a measure range
and only processes overlapping repetitions. Element-level pre-filtering within a
repetition is not yet implemented, but the per-note pre-filtering from Proposal 1
provides similar benefit at a slightly later stage in the pipeline.

**Remaining opportunity:** For patterns with many elements, adding element-level
filtering before `getNoteDestinations()` could avoid creating `RenderedNoteAudio`
instances for elements far from the buffer. This is a minor optimization given that
Proposal 1 already skips the expensive `evaluate()` call.

---

### Proposal 3: Cache evaluated note audio across buffer ticks -- IMPLEMENTED

**Goal:** A note that spans multiple buffers should be evaluated once, not once per buffer.

**Approach:** `NoteAudioCache` maintains a `Map<Integer, PackedCollection>` keyed by
absolute frame offset. Before `evaluate()`, the cache is checked. After `evaluate()`,
the result is stored. Before each buffer tick, `evictBefore(startFrame)` removes entries
for notes that have ended before the current buffer.

**Changes (implemented):**

1. `NoteAudioCache`: New class in `org.almostrealism.audio.pattern` with `get()`, `put()`,
   `evictBefore()`, `clear()`, `size()` methods
2. `PatternLayerManager`: Holds a `NoteAudioCache` instance. The frame-range `sum()` calls
   `noteAudioCache.evictBefore(startFrame)` then passes the cache to `sumInternal()`.
   The full-render `sum()` passes `null` cache.
3. `PatternFeatures.render()`: Accepts optional `NoteAudioCache` parameter. Checks cache
   before `evaluate()`, stores result after `evaluate()`.

```java
// In render():
PackedCollection audio = (cache != null) ? cache.get(noteStart) : null;
if (audio == null) {
    PackedCollection[] evaluated = {null};
    Heap.stage(() -> evaluated[0] = traverse(1, note.getProducer()).get().evaluate());
    audio = evaluated[0];
    if (cache != null) cache.put(noteStart, audio);
}
```

**Impact:** Each note is evaluated once regardless of how many buffers it spans.
Combined with Proposal 1, total evaluate() calls for a full arrangement render equals
the number of unique overlapping notes, not notes-per-buffer * buffers.

**Risk:** Low. The simple integer key (frame offset) avoids identity semantics issues.
Cache is evicted automatically as playback advances. Full-render path passes null cache,
so baseline behavior is unchanged.

---

### Proposal 4: Partial note evaluation

**Goal:** Only compute the frames of a note that actually fall within the buffer.

**Approach:** Modify the `Producer<PackedCollection>` returned by `getNoteAudio()` to
accept a frame range, so it only generates the requested portion of the note's audio.

This is the most invasive change. It requires:

1. `NoteAudio.getAudio()` to accept `startOffset` and `frameCount` parameters
2. The underlying audio source (WAV file, synthesizer) to support random access
3. Any envelope/automation applied to the note to be offset-aware

**Impact:** Eliminates all frame waste. Each evaluate() call produces exactly the
frames needed for the buffer. Combined with Proposal 1, this is the theoretical
optimum.

**Risk:** High. Requires changes deep in the audio pipeline. Some audio sources
(synthesizers) may not support random-access generation. Envelope and automation
calculations must be position-aware.

---

## Implementation Status

### Phase 1: Pre-filtering (Proposal 1) -- DONE

1. Added `expectedFrameCount` to `RenderedNoteAudio`
2. Added `getEffectiveDuration()` to `PatternElement`
3. Added `createRenderedNote()` helper to `ScaleTraversalStrategy`
4. Pre-filter by offset in unified `render()` before `evaluate()`

**Outcome:** Non-overlapping notes are skipped before the expensive `evaluate()` call.

### Phase 2: Caching (Proposal 3) -- DONE

1. Created `NoteAudioCache` class
2. Integrated cache into `PatternLayerManager` (frame-range path)
3. Cache eviction on each buffer tick via `evictBefore(startFrame)`

**Outcome:** Notes spanning multiple buffers are evaluated once and reused.

### Code Unification -- DONE

The render path was unified as part of Phase 1/2 implementation:

1. `PatternFeatures`: Single `render()` method replaces former `render()` + `renderRange()`.
   Both offline and real-time use the same method with `startFrame`/`frameCount`/`cache` params.
2. `PatternLayerManager`: Single `sumInternal()` replaces two separate implementations.
   Full-render `sum()` passes `startFrame=0, frameCount=totalFrames, cache=null`.
   Frame-range `sum()` passes actual frame range and `noteAudioCache`.
3. `PatternSystemManager`: Frame-range `sum()` passes context directly to
   `PatternLayerManager.sum()` (no longer wraps in `PatternRenderContext`).

### Phase 3: Partial evaluation (Proposal 4) -- NOT STARTED

Only pursue this if Phase 1 + 2 don't achieve sufficient performance. It requires
deeper changes to the audio pipeline.

---

## Verification

After each phase, the diagnostic tests should show improvement in `renderNoteAudioLengthAnalysis`:

| Metric | Current (measured) | After Phase 1 | After Phase 2 |
|--------|-------------------|---------------|---------------|
| evaluate() calls per buffer | 5 (4 wasted) | 1 | 1 (cached) |
| Frames evaluated per buffer | 50,433 | ~10,000 | ~10,000 (once) |
| evaluate() time per buffer | 342 ms | ~68 ms | ~0 ms (cache hit) |
| Waste ratio | 98.0% | ~90% | ~0% (cached) |

After Phase 1, only overlapping notes are evaluated (1 instead of 5). The single
overlapping note still evaluates its full audio (~10,000 frames for 1,024 useful),
but the total time drops from 342ms to ~68ms.

After Phase 2, the overlapping note's audio is cached from its first evaluation
and reused across subsequent buffer ticks, eliminating the evaluate() cost entirely
for notes that span multiple buffers.

Note: These numbers are for a low-activity seed (5 notes on channel 0). More active
patterns with more elements will show proportionally greater improvement from Phase 1.

---

## Files Modified

| File | Phase | Changes |
|------|-------|---------|
| `RenderedNoteAudio.java` | 1 | Added `expectedFrameCount` field, 3-parameter constructor |
| `PatternElement.java` | 1 | Added `getEffectiveDuration()`, refactored `getNoteAudio()` to use it |
| `ScaleTraversalStrategy.java` | 1 | Added `createRenderedNote()` helper computing expected frame count |
| `PatternFeatures.java` | 1+2 | Unified `render()` + `renderRange()` into single method with pre-filtering and cache support |
| `PatternLayerManager.java` | 1+2 | Unified two `sum()` overloads via shared `sumInternal()`. Added `NoteAudioCache` field |
| `PatternSystemManager.java` | 1 | Simplified frame-range `sum()` (removed `PatternRenderContext` wrapping) |
| `NoteAudioCache.java` | 2 | New class for caching evaluated note audio |
| `RiseManager.java` | — | Updated `render()` call signature |

## Tests

| Test | What it verifies |
|------|-----------------|
| `renderTimingVsBufferSize` | Time scales with buffer size (not constant) |
| `renderNoteAudioLengthAnalysis` | Waste ratio drops after each phase |
| `frameRangeSumProducesAudio` | Audio output still correct after changes |
| `frameRangeSumMultipleBuffers` | Consecutive buffers still stitch correctly |
