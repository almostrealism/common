# Partial Pattern Rendering: Performance Analysis and Proposals

## Problem Statement

Rendering a single 1024-frame buffer (~23ms of audio) via
`PatternSystemManager.sum(ctx, channel, startFrame, 1024)` takes ~20 seconds.
Real-time audio requires this to complete in under 23ms. The rendering is
roughly 1000x too slow.

This document analyzes where time is spent and proposes changes to eliminate
unnecessary work.

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

### Inside renderRange() -- where the waste happens

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

### Proposal 1: Pre-filter notes by offset before evaluate()

**Goal:** Never call `evaluate()` on a note that doesn't overlap the buffer.

**Approach:** The note's absolute frame offset is known from `RenderedNoteAudio.getOffset()`
before evaluation. The note's duration can be estimated from `PatternNote.getDuration() *
sampleRate` without evaluating the producer. Add an `expectedFrameCount` field to
`RenderedNoteAudio` so the overlap check can run before `evaluate()`.

**Changes:**

1. `RenderedNoteAudio`: Add `int expectedFrameCount` field, set during construction
2. `ScaleTraversalStrategy.getNoteDestinations()`: Compute expected frame count from
   `PatternNote.getDuration(target, audioSelection) * sampleRate` and store it
3. `PatternFeatures.renderRange()`: Check overlap using `[offset, offset + expectedFrameCount)`
   BEFORE calling `evaluate()`. Skip notes that don't overlap.

```java
// In renderRange(), BEFORE evaluate:
int noteStart = note.getOffset();
int noteEstimatedEnd = noteStart + note.getExpectedFrameCount();
if (noteEstimatedEnd <= startFrame || noteStart >= endFrame) {
    return;  // Skip -- no overlap, don't evaluate
}
```

**Impact:** Eliminates evaluate() calls for all non-overlapping notes. In the scenario
above, this reduces from 30 evaluate() calls to 1-3. This is the highest-value change.

**Risk:** Low. The expected frame count is an estimate; the actual audio might be
slightly different. But the overlap check after evaluate() still runs as a safety net.

---

### Proposal 2: Pre-filter elements by position

**Goal:** Don't even create `RenderedNoteAudio` for elements that clearly fall outside
the buffer's time range.

**Approach:** Each `PatternElement` has a `getPosition()` in measures. Convert the
buffer's frame range to a measure range and skip elements whose position (plus maximum
possible note duration) falls outside that range.

**Changes:**

1. `PatternFeatures.renderRange()` or `PatternLayerManager.sum()`: Before calling
   `getNoteDestinations()`, check if the element's position (converted to frames)
   could possibly overlap the buffer. Account for note duration by adding a generous
   maximum note length.

```java
// In renderRange(), filter elements before getNoteDestinations:
int maxNoteLengthFrames = (int) (MAX_NOTE_DURATION_SECONDS * sampleRate);
elements.stream()
    .filter(e -> {
        double absPosition = measureOffset + e.getPosition();
        int elemFrame = context.frameForPosition(absPosition);
        // Could any note from this element overlap the buffer?
        return elemFrame < endFrame + maxNoteLengthFrames
            && elemFrame + maxNoteLengthFrames > startFrame;
    })
    .map(e -> e.getNoteDestinations(...))
    ...
```

**Impact:** Reduces the number of `getNoteDestinations()` calls and the associated
`nextNotePosition()` overhead. For elements with repeats, this also avoids creating
`RenderedNoteAudio` instances for notes at positions far from the buffer.

**Risk:** Low. Uses a conservative maximum note length to avoid false negatives.

---

### Proposal 3: Cache evaluated note audio across buffer ticks

**Goal:** A note that spans multiple buffers should be evaluated once, not once per buffer.

**Approach:** Maintain a cache keyed by `(elementIdentity, repetitionIndex, noteIndex)` that
stores the evaluated `PackedCollection` audio. On subsequent buffer ticks, look up the
cached audio instead of re-evaluating the producer.

**Changes:**

1. Add a `NoteAudioCache` to `PatternRenderCell` or `PatternLayerManager`
2. Before `evaluate()`, check the cache. If hit, use cached audio.
3. After `evaluate()`, store in cache.
4. Evict entries when the note's frame range no longer overlaps with any future buffer.

```java
// Cache key: note identity + repetition offset
NoteAudioCacheKey key = new NoteAudioCacheKey(element, repIndex, noteIndex);

PackedCollection audio = cache.get(key);
if (audio == null) {
    audio = traverse(1, note.getProducer()).get().evaluate();
    cache.put(key, audio);
}

// On buffer advancement, evict notes that ended before current buffer
cache.evictBefore(currentStartFrame);
```

**Impact:** Eliminates redundant evaluation for long notes. A 1-second note evaluated
once instead of 43 times. Combined with Proposal 1, this means only overlapping notes
are evaluated, and each is evaluated only once.

**Risk:** Medium. Requires identity semantics for cache keys. Cache invalidation on
genome changes or pattern edits. Memory usage for cached audio.

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

## Recommended Approach

### Phase 1: Pre-filtering (Proposals 1 + 2)

These changes are low-risk and can be implemented and tested incrementally.

1. Add `expectedFrameCount` to `RenderedNoteAudio`
2. Pre-filter by offset in `renderRange()` before `evaluate()`
3. Pre-filter elements by position before `getNoteDestinations()`

**Expected outcome:** The number of `evaluate()` calls drops from all-notes-in-pattern
to only-overlapping-notes (typically 1-3 per buffer instead of 20-60). Rendering time
for a single buffer should drop proportionally.

### Phase 2: Caching (Proposal 3)

Once pre-filtering is working, add caching to avoid re-evaluating the same note
across consecutive buffers.

**Expected outcome:** Each note is evaluated once regardless of how many buffers it
spans. Combined with Phase 1, total evaluate() calls for a full arrangement render
equals the number of unique notes, not notes-per-buffer * buffers.

### Phase 3: Partial evaluation (Proposal 4)

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

## Files to Modify

| File | Phase | Changes |
|------|-------|---------|
| `RenderedNoteAudio.java` | 1 | Add `expectedFrameCount` field |
| `ScaleTraversalStrategy.java` | 1 | Compute and store expected frame count |
| `PatternFeatures.java` | 1 | Pre-filter by offset before evaluate() |
| `PatternLayerManager.java` | 1 | Pre-filter elements by position (optional) |
| `PatternRenderCell.java` | 2 | Add NoteAudioCache |
| `NoteAudio.java` | 3 | Add range-based getAudio() (if needed) |

## Tests

| Test | What it verifies |
|------|-----------------|
| `renderTimingVsBufferSize` | Time scales with buffer size (not constant) |
| `renderNoteAudioLengthAnalysis` | Waste ratio drops after each phase |
| `frameRangeSumProducesAudio` | Audio output still correct after changes |
| `frameRangeSumMultipleBuffers` | Consecutive buffers still stitch correctly |
