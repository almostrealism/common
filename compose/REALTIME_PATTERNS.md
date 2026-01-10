# Real-Time Pattern Rendering Proposal

## Overview

This document details the specific changes required to enable real-time pattern rendering in the Almost Realism audio framework. It is a companion document to [REALTIME_AUDIO_SCENE.md](./REALTIME_AUDIO_SCENE.md) and focuses specifically on modifications to the pattern system classes.

## Current Pattern Flow

### Pattern Hierarchy

```
PatternSystemManager (manages all patterns)
    |
    +-- PatternLayerManager[] (one per pattern slot)
        |
        +-- PatternLayer (root layer)
            |
            +-- PatternElement[] (musical events)
            |   |
            |   +-- PatternNote (audio sample reference)
            |
            +-- PatternLayer (child layer, scale/2)
                |
                +-- PatternElement[]
                    ...
```

### Current Rendering Flow

```java
// 1. AudioScene creates pattern setup
Supplier<Runnable> patternSetup = getPatternSetup(channel);

// 2. PatternSystemManager.sum() is called
// This processes ALL patterns for the ENTIRE duration
OperationList op = patterns.sum(context, channel);

// 3. For each PatternLayerManager:
//    - getAllElementsByChoice(0.0, duration) -> gets ALL elements
//    - For each pattern repetition (measure 0 to totalMeasures):
//        - render() sums audio to destination buffer

// 4. Full destination buffer is now populated
// 5. Effects chain processes the full buffer
```

### Key Methods and Their Issues

#### PatternSystemManager.sum()

**Location**: `music/src/main/java/org/almostrealism/audio/pattern/PatternSystemManager.java:285-329`

**Current Signature**:
```java
public Supplier<Runnable> sum(Supplier<AudioSceneContext> context, ChannelInfo channel)
```

**Issue**: No parameters for frame range. Processes entire pattern duration.

#### PatternLayerManager.sum()

**Location**: `music/src/main/java/org/almostrealism/audio/pattern/PatternLayerManager.java:485-530`

**Current Signature**:
```java
public Supplier<Runnable> sum(Supplier<AudioSceneContext> context,
                              ChannelInfo.Voicing voicing,
                              ChannelInfo.StereoChannel audioChannel)
```

**Issue**: Iterates through all pattern repetitions. No frame boundary awareness.

#### PatternFeatures.render()

**Location**: `music/src/main/java/org/almostrealism/audio/pattern/PatternFeatures.java:19-44`

**Current Signature**:
```java
default void render(AudioSceneContext sceneContext, NoteAudioContext audioContext,
                    List<PatternElement> elements, boolean melodic, double offset)
```

**Issue**: Renders all elements to their full extent. No frame range filtering.

---

## Proposed Changes

### 1. PatternSystemManager Modifications

#### New Method Overload

```java
/**
 * Renders patterns for a specific frame range.
 *
 * <p>This method is designed for real-time audio generation where patterns
 * are rendered incrementally as playback progresses. Only pattern elements
 * that overlap with the specified frame range are processed.</p>
 *
 * <p>The destination buffer in the context should be sized to match
 * {@code frameCount}, not the full arrangement duration.</p>
 *
 * @param context Supplier for the audio scene context
 * @param channel Target channel information
 * @param startFrame Starting frame (0-based, relative to arrangement start)
 * @param frameCount Number of frames to render
 * @return Operation that renders the specified frame range
 */
public Supplier<Runnable> sum(Supplier<AudioSceneContext> context,
                              ChannelInfo channel,
                              int startFrame,
                              int frameCount) {
    OperationList op = new OperationList(
        String.format("PatternSystemManager Sum [%d:%d]", startFrame, startFrame + frameCount));

    // Create frame-aware context
    Supplier<PatternRenderContext> renderContext = () -> {
        AudioSceneContext ctx = context.get();
        return new PatternRenderContext(ctx, startFrame, frameCount);
    };

    // Update destinations for frame range
    if (enableLazyDestination) {
        op.add(() -> () -> updateDestinationsForRange(context.get(), startFrame, frameCount));
    } else {
        updateDestinationsForRange(context.get(), startFrame, frameCount);
    }

    // Sum patterns within frame range
    List<Integer> patternsForChannel = IntStream.range(0, patterns.size())
            .filter(i -> channel.getPatternChannel() == patterns.get(i).getChannel())
            .boxed().toList();

    if (patternsForChannel.isEmpty()) {
        if (enableWarnings) warn("No patterns for channel " + channel);
        return op;
    }

    patternsForChannel.forEach(i -> {
        op.add(patterns.get(i).sum(
            renderContext,
            channel.getVoicing(),
            channel.getAudioChannel(),
            startFrame,
            frameCount));
    });

    // Note: Auto-volume is disabled in real-time mode (see REALTIME_AUDIO_SCENE.md Out of Scope section)

    return op;
}

/**
 * Updates pattern destinations for a specific frame range.
 * The destination buffer is sized to frameCount, not full duration.
 */
private void updateDestinationsForRange(AudioSceneContext ctx, int startFrame, int frameCount) {
    this.destination = ctx.getDestination();
    IntStream.range(0, patterns.size()).forEach(i ->
        patterns.get(i).updateDestinationForRange(ctx, startFrame, frameCount));
}
```

#### New Helper Class: PatternRenderContext

```java
/**
 * Extended context for incremental pattern rendering.
 * Wraps AudioSceneContext with frame range information.
 */
public class PatternRenderContext extends AudioSceneContext {
    private final AudioSceneContext delegate;
    private final int startFrame;
    private final int frameCount;

    public PatternRenderContext(AudioSceneContext delegate, int startFrame, int frameCount) {
        this.delegate = delegate;
        this.startFrame = startFrame;
        this.frameCount = frameCount;
    }

    public int getStartFrame() { return startFrame; }
    public int getFrameCount() { return frameCount; }
    public int getEndFrame() { return startFrame + frameCount; }

    /**
     * Converts a measure position to a frame offset within the current buffer.
     * Returns -1 if the position is outside the current frame range.
     */
    public int measureToBufferOffset(double measure) {
        int absoluteFrame = delegate.getFrameForPosition().apply(measure);
        int relativeFrame = absoluteFrame - startFrame;

        if (relativeFrame < 0 || relativeFrame >= frameCount) {
            return -1;
        }
        return relativeFrame;
    }

    /**
     * Checks if a measure range overlaps with the current frame range.
     */
    public boolean overlapsFrameRange(double startMeasure, double endMeasure) {
        int startAbsolute = delegate.getFrameForPosition().apply(startMeasure);
        int endAbsolute = delegate.getFrameForPosition().apply(endMeasure);
        return startAbsolute < getEndFrame() && endAbsolute > startFrame;
    }

    // Delegate all other methods to wrapped context
    @Override
    public int getMeasures() { return delegate.getMeasures(); }

    @Override
    public PackedCollection getDestination() { return delegate.getDestination(); }

    // ... other delegated methods
}
```

### 2. PatternLayerManager Modifications

#### New Method Overload

```java
/**
 * Renders pattern elements for a specific frame range.
 *
 * <p>This method filters pattern elements to only those that overlap with
 * the specified frame range, then renders only the overlapping portions
 * of each element's audio.</p>
 *
 * @param context Render context with frame range information
 * @param voicing Target voicing (MAIN or WET)
 * @param audioChannel Target stereo channel
 * @param startFrame Starting frame
 * @param frameCount Number of frames to render
 * @return Operation that renders elements within the frame range
 */
public Supplier<Runnable> sum(Supplier<PatternRenderContext> context,
                              ChannelInfo.Voicing voicing,
                              ChannelInfo.StereoChannel audioChannel,
                              int startFrame,
                              int frameCount) {
    return OperationWithInfo.of(
        new OperationMetadata(
            "PatternLayerManager.sum",
            String.format("PatternLayerManager.sum [%d:%d]", startFrame, startFrame + frameCount)),
        () -> () -> {
            PatternRenderContext ctx = context.get();
            int endFrame = startFrame + frameCount;

            // Convert frame range to measure range for element lookup
            double startMeasure = frameToMeasure(startFrame, ctx);
            double endMeasure = frameToMeasure(endFrame, ctx);

            // Get elements that overlap with frame range
            // Add margin for elements that start before but extend into range
            double lookbackMeasures = maxElementDuration();
            Map<NoteAudioChoice, List<PatternElement>> elements =
                getAllElementsByChoiceForRange(
                    Math.max(0, startMeasure - lookbackMeasures),
                    endMeasure);

            if (elements.isEmpty()) {
                if (!roots.isEmpty() && enableLogging) {
                    log("No elements in frame range [" + startFrame + ":" + endFrame + "]");
                }
                return;
            }

            // Determine which pattern repetitions overlap with frame range
            int firstRepetition = (int) Math.floor(startMeasure / duration);
            int lastRepetition = (int) Math.ceil(endMeasure / duration);

            IntStream.range(firstRepetition, lastRepetition).forEach(rep -> {
                double repStart = rep * duration;
                double repEnd = repStart + duration;

                // Check if this repetition overlaps with frame range
                if (!ctx.overlapsFrameRange(repStart, repEnd)) return;

                // Check section activity
                ChannelSection section = ctx.getSection(repStart);
                if (section != null) {
                    double active = activeSelection.apply(
                        layerParams.get(layerParams.size() - 1),
                        section.getPosition()) + ctx.getActivityBias();
                    if (active < 0) return;
                }

                // Render each choice's elements for this repetition
                elements.keySet().forEach(choice -> {
                    NoteAudioContext audioContext = new NoteAudioContext(
                        voicing, audioChannel,
                        choice.getValidPatternNotes(),
                        this::nextNotePosition);

                    List<PatternElement> repElements = filterElementsForRepetition(
                        elements.get(choice), rep);

                    if (!repElements.isEmpty()) {
                        renderRange(ctx, audioContext, repElements, melodic,
                            repStart, startFrame, frameCount);
                    }
                });
            });
        });
}

/**
 * Converts a frame position to a measure position.
 */
private double frameToMeasure(int frame, AudioSceneContext ctx) {
    // Assuming linear time (no tempo changes mid-arrangement)
    double framesPerMeasure = ctx.getFrames() / (double) ctx.getMeasures();
    return frame / framesPerMeasure;
}

/**
 * Returns the maximum duration of any element in this pattern.
 * Used for lookback when filtering elements by frame range.
 */
private double maxElementDuration() {
    return roots.stream()
        .flatMap(r -> r.getAllElements(0, duration * 100).stream())
        .mapToDouble(PatternElement::getActualDuration)
        .max()
        .orElse(duration);
}
```

### 3. PatternFeatures Modifications

#### New renderRange() Method

```java
/**
 * Renders pattern elements to a specific frame range in the destination buffer.
 *
 * <p>This method handles the complexity of rendering notes that may:</p>
 * <ul>
 *   <li>Start before the frame range but extend into it</li>
 *   <li>Start within the frame range</li>
 *   <li>End after the frame range</li>
 * </ul>
 *
 * <p>Only the portion of each note that overlaps with the frame range is
 * rendered to the destination buffer.</p>
 *
 * @param context Render context with frame range information
 * @param audioContext Note audio context
 * @param elements Elements to render
 * @param melodic Whether to use melodic or percussive rendering
 * @param measureOffset Measure offset for this pattern repetition
 * @param startFrame Starting frame of buffer
 * @param frameCount Size of destination buffer
 */
default void renderRange(PatternRenderContext context, NoteAudioContext audioContext,
                         List<PatternElement> elements, boolean melodic,
                         double measureOffset, int startFrame, int frameCount) {
    PackedCollection destination = context.getDestination();
    if (destination == null) {
        throw new IllegalArgumentException("Destination buffer is null");
    }

    int endFrame = startFrame + frameCount;

    elements.stream()
        .map(e -> e.getNoteDestinations(melodic, measureOffset, context, audioContext))
        .flatMap(List::stream)
        .forEach(note -> {
            int noteAbsoluteStart = note.getOffset();
            PackedCollection audio = getAudio(note);

            if (audio == null) return;

            int noteLength = audio.getShape().getCount();
            int noteAbsoluteEnd = noteAbsoluteStart + noteLength;

            // Check if note overlaps with frame range
            if (noteAbsoluteEnd <= startFrame || noteAbsoluteStart >= endFrame) {
                return;  // No overlap
            }

            // Calculate overlap region
            int overlapStart = Math.max(noteAbsoluteStart, startFrame);
            int overlapEnd = Math.min(noteAbsoluteEnd, endFrame);
            int overlapLength = overlapEnd - overlapStart;

            if (overlapLength <= 0) return;

            // Calculate offsets
            int sourceOffset = overlapStart - noteAbsoluteStart;  // Offset within note audio
            int destOffset = overlapStart - startFrame;            // Offset within destination buffer

            // Validate ranges
            if (sourceOffset < 0 || sourceOffset + overlapLength > noteLength) {
                warn("Source range out of bounds: " + sourceOffset + "+" + overlapLength + " > " + noteLength);
                return;
            }
            if (destOffset < 0 || destOffset + overlapLength > frameCount) {
                warn("Dest range out of bounds: " + destOffset + "+" + overlapLength + " > " + frameCount);
                return;
            }

            // Sum overlapping portion to destination
            try {
                TraversalPolicy shape = shape(overlapLength);
                AudioProcessingUtils.getSum().sum(
                    destination.range(shape, destOffset),
                    audio.range(shape, sourceOffset)
                );
            } catch (Exception e) {
                warn("Error rendering note: " + e.getMessage());
            }
        });
}

/**
 * Safely retrieves audio from a rendered note.
 *
 * NOTE: Heap.stage() usage is OUT OF SCOPE for initial implementation.
 * The initial implementation will bypass Heap memory management.
 * See REALTIME_AUDIO_SCENE.md Out of Scope section.
 */
private PackedCollection getAudio(RenderedNoteAudio note) {
    try {
        // Initial implementation bypasses Heap.stage()
        return traverse(1, note.getProducer()).get().evaluate();
    } catch (Exception e) {
        warn("Error evaluating note audio: " + e.getMessage());
        return null;
    }
}
```

### 4. PatternElement Modifications

#### Frame-Based Filtering

```java
/**
 * Checks if this element overlaps with a frame range.
 *
 * @param startFrame Start of frame range
 * @param endFrame End of frame range
 * @param ctx Context for position conversion
 * @return true if any part of this element is within the frame range
 */
public boolean overlapsFrameRange(int startFrame, int endFrame, AudioSceneContext ctx) {
    for (double pos : getPositions()) {
        int elemStart = ctx.getFrameForPosition().apply(pos);
        int elemEnd = elemStart + getDurationFrames(ctx);

        if (elemStart < endFrame && elemEnd > startFrame) {
            return true;
        }
    }
    return false;
}

/**
 * Gets the duration of this element in frames.
 */
public int getDurationFrames(AudioSceneContext ctx) {
    return (int) (getActualDuration() * ctx.getTimeForDuration().apply(1.0) *
        OutputLine.sampleRate);
}
```

---

## Caching Strategy

### Element Cache

To avoid repeatedly filtering elements for overlapping frames:

```java
/**
 * Cache for pattern elements organized by frame boundaries.
 */
public class PatternElementCache {
    private final int cacheGranularity;  // Frames per cache bucket
    private final Map<Integer, List<PatternElement>> buckets;

    public PatternElementCache(int granularity) {
        this.cacheGranularity = granularity;
        this.buckets = new ConcurrentHashMap<>();
    }

    /**
     * Gets elements that may overlap with the given frame range.
     * Returns a superset; caller must do final filtering.
     */
    public List<PatternElement> getElementsForRange(int startFrame, int endFrame) {
        int startBucket = startFrame / cacheGranularity;
        int endBucket = (endFrame + cacheGranularity - 1) / cacheGranularity;

        return IntStream.range(startBucket, endBucket + 1)
            .mapToObj(buckets::get)
            .filter(Objects::nonNull)
            .flatMap(List::stream)
            .distinct()
            .collect(Collectors.toList());
    }

    /**
     * Populates the cache from a list of elements.
     */
    public void populate(List<PatternElement> elements, AudioSceneContext ctx) {
        buckets.clear();

        for (PatternElement elem : elements) {
            for (double pos : elem.getPositions()) {
                int startFrame = ctx.getFrameForPosition().apply(pos);
                int endFrame = startFrame + elem.getDurationFrames(ctx);

                int startBucket = startFrame / cacheGranularity;
                int endBucket = (endFrame + cacheGranularity - 1) / cacheGranularity;

                for (int bucket = startBucket; bucket <= endBucket; bucket++) {
                    buckets.computeIfAbsent(bucket, k -> new ArrayList<>()).add(elem);
                }
            }
        }
    }

    public void invalidate() {
        buckets.clear();
    }
}
```

---

## Testing Requirements

### Unit Tests

1. **Frame Range Rendering Accuracy**
   - Render full arrangement in one call
   - Render same arrangement in multiple buffer-sized chunks
   - Compare outputs byte-for-byte

2. **Boundary Conditions**
   - Notes starting exactly at buffer boundary
   - Notes ending exactly at buffer boundary
   - Notes spanning multiple buffers
   - Empty buffers (no notes in range)

3. **Edge Cases**
   - Very short notes (< 1 frame)
   - Very long notes (spanning entire arrangement)
   - Overlapping notes
   - Maximum layer depth

### Integration Tests

1. **Real-Time Playback**
   - BufferedOutputScheduler with real-time rendering
   - Verify no audio glitches or dropouts

2. **Comparison Tests**
   - Non-real-time rendering produces identical output
   - Health computation works with real-time rendering

---

## Migration Path

### Phase 1: Add New Methods (Non-Breaking)

```java
// Add new overloads alongside existing methods
public Supplier<Runnable> sum(context, channel)  // Existing
public Supplier<Runnable> sum(context, channel, startFrame, frameCount)  // New
```

### Phase 2: Add Feature Flag

```java
public class PatternSystemManager {
    public static boolean enableRealtimeRendering = false;

    public Supplier<Runnable> sum(context, channel) {
        if (enableRealtimeRendering) {
            // Called from PatternRenderCell, use full range
            return sum(context, channel, 0, context.get().getFrames());
        }
        // Original implementation
        return originalSum(context, channel);
    }
}
```

### Phase 3: Update AudioScene

```java
public CellList getPatternCells(...) {
    if (PatternSystemManager.enableRealtimeRendering) {
        return getPatternCellsRealTime(...);
    }
    return getPatternCellsLegacy(...);
}
```

### Phase 4: Deprecate Legacy

```java
/**
 * @deprecated Use real-time rendering with
 * {@link PatternSystemManager#enableRealtimeRendering} = true
 */
@Deprecated
public CellList getPatternCellsLegacy(...)
```

---

## Performance Considerations

### Profiling Points

1. `getAllElementsByChoiceForRange()` - Element filtering
2. `frameToMeasure()` / `measureToFrame()` - Position conversion
3. `overlapsFrameRange()` - Range checks
4. `sum.range()` - Audio summation

### Optimization Opportunities

1. **Pre-sorted elements**: Keep elements sorted by start frame
2. **Binary search**: Use binary search for range queries
3. **Lazy audio loading**: Only load note audio when needed
4. **Buffer pooling**: Reuse destination buffers across calls

### Expected Performance

| Operation | Current | Real-Time |
|-----------|---------|-----------|
| Pattern lookup | O(n) all elements | O(log n) + k relevant |
| Audio rendering | Full duration | Buffer size only |
| Memory | Full buffers | Buffer-sized allocation |
| Latency | Setup time | Buffer time |
