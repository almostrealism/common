# Real-Time AudioScene Rendering Proposal

## Implementation Status

> **STATUS: BASELINE VALIDATED, REAL-TIME PATH UNVALIDATED** (January 2026)
>
> Code was written for all four phases, but **no proof exists that real-time rendering works**.
>
> **What works:**
> - The traditional (non-real-time) AudioScene rendering pipeline **does produce audio**.
>   This is confirmed by both `AudioSceneOptimizer` / `StableDurationHealthComputation` AND
>   the standalone `AudioSceneBaselineTest` (3/3 tests passing).
> - **`AudioSceneBaselineTest`** proves AudioScene generates audio without `AudioSceneOptimizer`:
>   - Seed 42: 487 pattern elements (140 melodic), health score=0.0195, 176,400 frames
>   - Uses `StableDurationHealthComputation` with `scene.runner(health.getOutput())`
>
> **Critical architectural finding:**
> - `scene.getCells()` stores ALL pattern rendering in `AudioScene.this.setup` (not in cells)
> - `scene.runner()` wraps both `AudioScene.this.setup()` and `cells.setup()` together
> - The `TemporalRunner` path (compile setup and tick separately) is required; `sec()` produces silence
>
> **What doesn't work:**
> 1. **Real-time rendering is unvalidated** - No test has demonstrated that the real-time code
>    path (`runnerRealTime()`, `PatternRenderCell`, `BatchCell`) produces correct audio output.
> 2. **Performance is 4-10x too slow** - 100-213ms per buffer vs 23ms required (0.1-0.2x real-time)
> 3. **No hardware acceleration** - Operations run in slow interpreted mode ("OperationList was not compiled")
> 4. **No valid comparison test exists** - Cannot verify real-time matches offline output
>
> **What is needed next:**
> - Compare traditional output (baseline now exists) to real-time output
> - Fix real-time output pipeline (Gap 2 in Implementation Gaps)
> - Enable hardware acceleration (Gap 3)
>
> See the [Implementation Gaps](#implementation-gaps) section for detailed analysis.
> The original implementation summary is preserved below for reference.

## Executive Summary

This document proposes a comprehensive redesign of the `AudioScene` rendering pipeline to enable true real-time audio generation. Currently, the entire musical arrangement is pre-rendered during a "setup" phase before any audio output begins. This proposal outlines how to move pattern rendering into the incremental "tick" phase, enabling streaming audio generation suitable for live playback.

## Table of Contents

1. [Current Architecture Analysis](#current-architecture-analysis)
2. [Problem Statement](#problem-statement)
3. [Proposed Solution Overview](#proposed-solution-overview)
4. [Detailed Design](#detailed-design)
5. [Implementation Phases](#implementation-phases)
6. [Risks and Mitigations](#risks-and-mitigations)
7. [Open Questions](#open-questions)
8. [Appendices](#appendices)

---

## Current Architecture Analysis

### The Setup/Tick Model

The Almost Realism audio framework follows a two-phase execution model:

| Phase | When | Purpose | Typical Operations |
|-------|------|---------|-------------------|
| **Setup** | Once, before playback | Initialize state, compile operations | Pattern rendering, buffer allocation |
| **Tick** | Every N frames | Process audio incrementally | Effects, mixing, output |

This model is implemented through the `Temporal` interface:

```java
public interface Temporal {
    Supplier<Runnable> tick();
}

public interface Setup {
    Supplier<Runnable> setup();
}
```

### Current AudioScene Flow

```
AudioScene.getCells(output)
    |
    +-- [SETUP PHASE - runs once] --------------------------------+
    |   |                                                          |
    |   +-- automation.setup()                                     |
    |   +-- riser.setup()                                          |
    |   +-- mixdown.setup()                                        |
    |   +-- time.setup()                                           |
    |   |                                                          |
    |   +-- getPatternChannel(channel, frames, setup)              |
    |       |                                                      |
    |       +-- getPatternSetup(channel)                           |
    |           |                                                  |
    |           +-- PatternSystemManager.sum()  <-- ENTIRE SCENE   |
    |           |   |                                              |
    |           |   +-- PatternLayerManager.sum()  (per pattern)   |
    |           |       |                                          |
    |           |       +-- render() all elements to destination   |
    |           |                                                  |
    |       +-- ChannelSection.process()                           |
    |       +-- EfxManager.apply()                                 |
    |                                                              |
    +--------------------------------------------------------------+
    |
    +-- [TICK PHASE - runs per buffer] ---------------------------+
    |   |                                                          |
    |   +-- CellList.tick()                                        |
    |       |                                                      |
    |       +-- Push through cells (effects, mixing)               |
    |       +-- Write to output                                    |
    |                                                              |
    +--------------------------------------------------------------+
```

### Key Components

#### AudioScene.getPatternChannel()
```java
public CellList getPatternChannel(ChannelInfo channel, int frames, OperationList setup) {
    OperationList patternSetup = new OperationList("PatternChannel Setup");

    // This renders ALL patterns for the ENTIRE duration
    patternSetup.add(getPatternSetup(channel));

    // Section processing
    sections.getChannelSections(channel).stream()
        .map(section -> section.process(sectionAudio, sectionAudio))
        .forEach(patternSetup::add);

    // Add to main setup
    setup.add(patternSetup);

    // Return cell for effects processing
    return efx.apply(channel, result, getTotalDuration(), setup);
}
```

#### PatternSystemManager.sum()
```java
public Supplier<Runnable> sum(Supplier<AudioSceneContext> context, ChannelInfo channel) {
    OperationList op = new OperationList("PatternSystemManager Sum");

    // Update destination (full buffer)
    op.add(updateDestinations);

    // Sum ALL patterns for this channel
    patternsForChannel.forEach(i -> {
        op.add(patterns.get(i).sum(context, channel.getVoicing(), channel.getAudioChannel()));
    });

    // Volume normalization
    if (enableAutoVolume) {
        // Requires full buffer to compute max
        Producer<PackedCollection> max = cp(destination).traverse(0).max().isolate();
        op.add(volumeAdjustment);
    }

    return op;
}
```

#### PatternLayerManager.sum()
```java
public Supplier<Runnable> sum(Supplier<AudioSceneContext> context,
                              ChannelInfo.Voicing voicing,
                              ChannelInfo.StereoChannel audioChannel) {
    return () -> () -> {
        Map<NoteAudioChoice, List<PatternElement>> elements = getAllElementsByChoice(0.0, duration);

        // For each pattern repetition
        int count = ctx.getMeasures() / duration;
        IntStream.range(0, count).forEach(i -> {
            double offset = i * duration;

            // Render ALL elements for this pattern repetition
            elements.keySet().forEach(choice -> {
                render(ctx, audioContext, elements.get(choice), melodic, offset);
            });
        });
    };
}
```

### Where Buffering Works (MixdownManager)

The `MixdownManager.cells()` method creates a `CellList` that supports buffering:

```java
CellList cells = createCells(sources, wetSources, riser, output, audioChannel, channelIndex);
```

This `CellList` can be buffered via:

```java
// Real-time buffered output
BufferedOutputScheduler scheduler = cells.buffer(outputLine);

// Non-real-time buffered processing
TemporalRunner runner = cells.buffer(destination);
```

The issue is that by the time we reach this point, `sources` already contains fully-rendered pattern data in pre-allocated buffers.

---

## Problem Statement

### The Core Issue

The pattern rendering process in `PatternSystemManager.sum()` and `PatternLayerManager.sum()` operates on the **entire arrangement** at once. There is no mechanism to:

1. Specify a frame range (e.g., "render frames 0 to 1024")
2. Incrementally render patterns as playback progresses
3. Defer pattern rendering to the tick phase

### Consequences

1. **No True Real-Time**: Playback cannot begin until the entire scene is rendered
2. **High Memory Usage**: Full-duration buffers must be allocated upfront
3. **No Live Updates**: Cannot modify patterns during playback
4. **Latency**: Long setup time before audio begins

### Example: Current Non-Real-Time Flow

From `StableDurationHealthComputation`:

```java
// In TemporalRunner.get()
Runnable start = runner.get();  // Runs setup (including full pattern rendering)
Runnable iterate = runner.getContinue();  // Runs tick only

// Setup runs here - blocks until complete
start.run();  // <- May take minutes for complex scenes

// Now ticks can proceed
iterate.run();
```

---

## Proposed Solution Overview

### High-Level Approach

Transform the pattern rendering from a batch "setup" operation to an incremental "tick" operation that respects buffer boundaries.

### Key Changes

1. **Buffer-Aware Pattern Sum**: Add `startFrame` and `frameCount` parameters to `sum()` methods
2. **Move Pattern Sum to Tick**: Execute pattern rendering during tick phase instead of setup
3. **N-Frame Batch Processing**: New Cell tooling for operations that run once per N frames

### Out of Scope (This Phase)

The following are explicitly deferred to future work:

1. **Volume Normalization**: The existing auto-volume feature relies on computing the max level
   across the entire audio track. In a real-time context, the max level is unknown in advance.
   For now, auto-volume will be **disabled in real-time mode**. A proper solution would be a
   compressor-style limiter, which is a separate feature.

2. **Heap Memory Management**: The current `PatternFeatures.render()` uses `Heap.stage()` for
   memory management during note audio evaluation. This will be bypassed for initial implementation.
   If heap memory management proves beneficial for performance, it can be introduced later.

### Architecture After Changes

```
AudioScene.getCells(output)
    |
    +-- [SETUP PHASE - lightweight] ------------------------------+
    |   |                                                          |
    |   +-- automation.setup()                                     |
    |   +-- Initialize pattern managers (no rendering)             |
    |   +-- Allocate buffer-sized destinations                     |
    |                                                              |
    +--------------------------------------------------------------+
    |
    +-- [TICK PHASE - per buffer] --------------------------------+
    |   |                                                          |
    |   +-- PatternRenderCell.tick()  <-- NEW                      |
    |       |                                                      |
    |       +-- PatternSystemManager.sum(startFrame, bufferSize)   |
    |           |                                                  |
    |           +-- PatternLayerManager.sum(startFrame, bufferSize)|
    |               |                                              |
    |               +-- render() only elements in frame range      |
    |   |                                                          |
    |   +-- MixdownManager cells (effects, mixing)                 |
    |   +-- Write to output                                        |
    |                                                              |
    +--------------------------------------------------------------+
```

---

## Detailed Design

### Component 1: Buffer-Aware Pattern Rendering

#### PatternSystemManager Changes

```java
/**
 * Renders patterns for a specific frame range into the destination buffer.
 *
 * @param context Scene context supplier
 * @param channel Target channel
 * @param startFrame Starting frame (0-based)
 * @param frameCount Number of frames to render
 * @return Operation that renders the specified frame range
 */
public Supplier<Runnable> sum(Supplier<AudioSceneContext> context,
                              ChannelInfo channel,
                              int startFrame,
                              int frameCount) {
    OperationList op = new OperationList("PatternSystemManager Sum [" +
        startFrame + ":" + (startFrame + frameCount) + "]");

    // Update destinations with frame-range aware context
    op.add(() -> () -> updateDestinationsForRange(context.get(), startFrame, frameCount));

    // Sum patterns for this channel within the frame range
    patternsForChannel.forEach(i -> {
        op.add(patterns.get(i).sum(context, channel.getVoicing(),
            channel.getAudioChannel(), startFrame, frameCount));
    });

    // Note: Auto-volume is disabled in real-time mode (see Out of Scope section)

    return op;
}
```

#### PatternLayerManager Changes

```java
/**
 * Renders pattern elements for a specific frame range.
 *
 * @param context Scene context supplier
 * @param voicing Target voicing (MAIN/WET)
 * @param audioChannel Target stereo channel
 * @param startFrame Starting frame
 * @param frameCount Number of frames to render
 * @return Operation that renders the specified frame range
 */
public Supplier<Runnable> sum(Supplier<AudioSceneContext> context,
                              ChannelInfo.Voicing voicing,
                              ChannelInfo.StereoChannel audioChannel,
                              int startFrame,
                              int frameCount) {
    return () -> () -> {
        AudioSceneContext ctx = context.get();
        int endFrame = startFrame + frameCount;

        // Convert frame range to measure range
        double startMeasure = frameToMeasure(startFrame, ctx);
        double endMeasure = frameToMeasure(endFrame, ctx);

        // Get elements that overlap with frame range
        Map<NoteAudioChoice, List<PatternElement>> elements =
            getAllElementsByChoiceForFrameRange(startMeasure, endMeasure);

        if (elements.isEmpty()) return;

        // Render elements within frame range
        elements.keySet().forEach(choice -> {
            NoteAudioContext audioContext = new NoteAudioContext(
                voicing, audioChannel, choice.getValidPatternNotes(), this::nextNotePosition);

            List<PatternElement> rangeElements = filterElementsForFrameRange(
                elements.get(choice), startFrame, endFrame, ctx);

            renderRange(ctx, audioContext, rangeElements, melodic,
                startFrame, frameCount);
        });
    };
}
```

#### PatternFeatures.renderRange()

```java
/**
 * Renders pattern elements to a specific frame range in the destination.
 * Only processes audio that falls within [startFrame, startFrame + frameCount].
 */
default void renderRange(AudioSceneContext sceneContext, NoteAudioContext audioContext,
                         List<PatternElement> elements, boolean melodic,
                         int startFrame, int frameCount) {
    PackedCollection destination = sceneContext.getDestination();
    int endFrame = startFrame + frameCount;

    elements.stream()
        .map(e -> e.getNoteDestinations(melodic, 0, sceneContext, audioContext))
        .flatMap(List::stream)
        .filter(note -> noteOverlapsRange(note, startFrame, endFrame))
        .forEach(note -> {
            // Calculate overlap with buffer
            int noteStart = note.getOffset();
            int noteEnd = noteStart + note.getAudio().getShape().getCount();

            int overlapStart = Math.max(noteStart, startFrame);
            int overlapEnd = Math.min(noteEnd, endFrame);
            int overlapFrames = overlapEnd - overlapStart;

            if (overlapFrames <= 0) return;

            // Source offset within note audio
            int srcOffset = overlapStart - noteStart;
            // Destination offset within buffer
            int dstOffset = overlapStart - startFrame;

            // Sum overlapping portion
            AudioProcessingUtils.getSum().sum(
                destination.range(shape(overlapFrames), dstOffset),
                note.getAudio().range(shape(overlapFrames), srcOffset)
            );
        });
}
```

### Component 2: Pattern Render Cell

A new `Cell` implementation that handles per-buffer pattern rendering.

```java
/**
 * Cell that renders patterns for each buffer period.
 * Runs once per N frames where N is the buffer size.
 */
public class PatternRenderCell implements Cell<PackedCollection>, Temporal, Setup {
    private final PatternSystemManager patterns;
    private final Supplier<AudioSceneContext> contextSupplier;
    private final ChannelInfo channel;
    private final int bufferSize;

    private int currentFrame;
    private Receptor<PackedCollection> receptor;

    public PatternRenderCell(PatternSystemManager patterns,
                             Supplier<AudioSceneContext> contextSupplier,
                             ChannelInfo channel,
                             int bufferSize) {
        this.patterns = patterns;
        this.contextSupplier = contextSupplier;
        this.channel = channel;
        this.bufferSize = bufferSize;
        this.currentFrame = 0;
    }

    @Override
    public Supplier<Runnable> setup() {
        OperationList setup = new OperationList("PatternRenderCell Setup");
        // Initialize patterns (no rendering)
        setup.add(() -> () -> {
            patterns.updateDestination(contextSupplier.get());
            currentFrame = 0;
        });
        return setup;
    }

    @Override
    public Supplier<Runnable> tick() {
        // Render patterns for current buffer
        OperationList tick = new OperationList("PatternRenderCell Tick");

        tick.add(patterns.sum(contextSupplier, channel, currentFrame, bufferSize));

        // Advance frame counter
        tick.add(() -> () -> currentFrame += bufferSize);

        return tick;
    }

    @Override
    public Supplier<Runnable> push(Producer<PackedCollection> protein) {
        // Forward rendered audio to receptor
        Producer<PackedCollection> buffer = contextSupplier.get()::getDestination;
        return receptor != null ? receptor.push(buffer) : new OperationList();
    }

    @Override
    public void setReceptor(Receptor<PackedCollection> r) {
        this.receptor = r;
    }

    @Override
    public void reset() {
        currentFrame = 0;
    }
}
```

### Component 3: Batch Processing Cell Wrapper

A cell wrapper that executes an operation once per N frames.

```java
/**
 * Wraps a Cell to execute its tick only once per N frames.
 * All other frames skip the wrapped tick and use cached output.
 */
public class BatchCell<T> implements Cell<T>, Temporal, Setup {
    private final Cell<T> wrapped;
    private final int batchSize;
    private int frameInBatch;

    private Producer<T> cachedOutput;

    public BatchCell(Cell<T> wrapped, int batchSize) {
        this.wrapped = wrapped;
        this.batchSize = batchSize;
        this.frameInBatch = 0;
    }

    @Override
    public Supplier<Runnable> setup() {
        OperationList setup = new OperationList("BatchCell Setup");
        if (wrapped instanceof Setup) {
            setup.add(((Setup) wrapped).setup());
        }
        setup.add(() -> () -> frameInBatch = 0);
        return setup;
    }

    @Override
    public Supplier<Runnable> tick() {
        OperationList tick = new OperationList("BatchCell Tick");

        // Only execute wrapped tick at batch boundaries
        tick.add(() -> () -> {
            if (frameInBatch == 0) {
                if (wrapped instanceof Temporal) {
                    ((Temporal) wrapped).tick().get().run();
                }
            }
            frameInBatch = (frameInBatch + 1) % batchSize;
        });

        return tick;
    }

    @Override
    public Supplier<Runnable> push(Producer<T> protein) {
        return wrapped.push(protein);
    }

    @Override
    public void setReceptor(Receptor<T> r) {
        wrapped.setReceptor(r);
    }
}
```

### Component 4: Incremental Volume Normalization

> **OUT OF SCOPE**: Volume normalization is deferred to future work. Auto-volume will be
> disabled in real-time mode. A compressor-style limiter would be more appropriate for
> real-time audio, but is a separate feature.

### Component 5: Modified AudioScene Integration

```java
public CellList getPatternCellsRealTime(MultiChannelAudioOutput output,
                                        List<Integer> channels,
                                        ChannelInfo.StereoChannel audioChannel,
                                        int bufferSize) {
    // No pattern rendering in setup
    OperationList setup = new OperationList("AudioScene Realtime Setup");
    setup.add(automation.setup());
    setup.add(mixdown.setup());
    setup.add(time.setup());
    setup.add(initializePatternDestinations(bufferSize));

    // Create pattern render cells
    int[] channelIndex = channels.stream().mapToInt(i -> i).toArray();
    CellList patternCells = all(channelIndex.length, i -> {
        ChannelInfo channel = new ChannelInfo(channelIndex[i],
            ChannelInfo.Voicing.MAIN, audioChannel);

        return new PatternRenderCell(patterns,
            () -> getContext(List.of(channel)), channel, bufferSize);
    });

    // Apply effects chain
    return mixdown.cells(patternCells, output, audioChannel);
}
```

---

## Implementation Phases

### Phase 1: Buffer-Aware Pattern Rendering
**Focus**: Modify `sum()` methods to accept frame range parameters

**Changes**:
- Add `sum(context, channel, startFrame, frameCount)` overload to `PatternSystemManager`
- Add `sum(..., startFrame, frameCount)` overload to `PatternLayerManager`
- Implement `renderRange()` in `PatternFeatures`
- Add frame-to-measure conversion utilities

**Validation**:
- Unit tests comparing full render vs incremental render
- Verify audio output matches between approaches

### Phase 2: Pattern Render Cell
**Focus**: Create new Cell infrastructure for incremental rendering

**Changes**:
- Implement `PatternRenderCell`
- Implement `BatchCell` wrapper
- Add frame tracking to `AudioSceneContext`

**Validation**:
- Test pattern rendering in tick phase
- Verify correct frame progression

### Phase 3: AudioScene Integration
**Focus**: Integrate real-time rendering into AudioScene

**Changes**:
- Add `getPatternCellsRealTime()` method
- Modify buffer allocation strategy
- Update `runner()` method for real-time mode

**Validation**:
- End-to-end real-time playback test
- Compare output with non-real-time rendering

### Phase 4: Optimization and Polish
**Focus**: Performance tuning and edge cases

**Changes**:
- Optimize element filtering for frame ranges
- Add caching for repeated patterns
- Handle pattern duration mismatches

**Validation**:
- Performance benchmarks
- Stress tests with complex scenes

---

## Risks and Mitigations

### Risk 1: Audio Artifacts at Buffer Boundaries
**Concern**: Notes spanning buffer boundaries may produce clicks or gaps

**Mitigation**:
- Implement overlap-add for smooth transitions
- Pre-fetch notes that start within N frames of buffer end
- Test extensively with various buffer sizes

### Risk 2: Volume Instability

> **OUT OF SCOPE**: Volume normalization is deferred. Auto-volume will be disabled in
> real-time mode. This risk is not applicable to the current implementation scope.

### Risk 3: Performance Regression
**Concern**: Per-buffer pattern rendering may be slower than batch

**Mitigation**:
- Cache element lookups across frames
- Pre-compute frame ranges at buffer boundaries
- Profile and optimize hot paths

### Risk 4: Section Processing Complexity
**Concern**: `ChannelSection.process()` currently operates on full patterns

**Mitigation**:
- Defer section processing to effects chain
- Implement incremental section processing
- Document breaking changes

### Risk 5: Backward Compatibility
**Concern**: Existing code may depend on current behavior

**Mitigation**:
- Keep original `sum()` signatures
- Add `enableRealTimeRendering` flag
- Document migration path

---

## Design Decisions

This section documents key design decisions made during planning.

### D1: Buffer Size Selection

**Decision**: Use 1024 frames as the default, but adopt whatever the AudioLine reports.

The implementation should query `BufferedAudioPlayer::deliver` to determine the actual
buffer size used by the audio hardware. The default of 1024 frames (~23ms at 44.1kHz)
provides a reasonable balance between latency and overhead.

### D2: Pattern Element Caching

**Decision**: Caching is optional and at developer discretion.

Pattern element caching provides limited benefit because the envelope depends on
automation state, which changes on every render. The `PatternElement` class itself
could be cached, but the audio associated with notes cannot be safely cached. If
caching proves useful during implementation, it can be added; otherwise, skip it.

### D3: Auto-Volume Mode

**Decision**: Disabled in real-time mode.

Auto-volume is explicitly out of scope for this implementation. The feature requires
knowledge of the full audio track's max level, which is unavailable in real-time.
A proper solution would be a compressor-style limiter, which is a separate feature.

### D4: Section Processing

**Decision**: `DefaultChannelSectionFactory.Section` should accept input/output buffers.

The `AudioProcessor` implementation needs to work with per-buffer processing. This is
expected to be a minor change to the existing design. The section processing should
integrate with the effects chain and operate on each buffer incrementally.

### D5: TemporalRunner Compatibility

**Decision**: No changes to TemporalRunner required.

The runner's execution model remains unchanged. The difference is that:
- **Setup phase** (`TemporalRunner::get`): Will have much less work (no pattern rendering)
- **Tick phase** (`TemporalRunner::getContinue`): Will have more work (pattern rendering per buffer)

This aligns with the existing two-phase execution model.

---

## Appendices

### Appendix A: Current Code References

| File | Key Lines | Purpose |
|------|-----------|---------|
| `AudioScene.java` | 649-683 | `getPatternChannel()` |
| `AudioScene.java` | 709-719 | `getPatternSetup()` |
| `PatternSystemManager.java` | 285-329 | `sum()` |
| `PatternLayerManager.java` | 485-530 | `sum()` |
| `PatternFeatures.java` | 19-44 | `render()` |
| `CellList.java` | 236-253 | `buffer()` methods |
| `TemporalRunner.java` | 414-449 | `get()` and `getContinue()` |

### Appendix B: Test Files

| Test | Purpose |
|------|---------|
| `RealtimePlaybackTest.java` | Existing real-time playback tests |
| `StableDurationHealthComputation.java` | Non-real-time pattern execution |

### Appendix C: Related Documentation

- [ar-compose README](./README.md)
- [ar-music README](../music/README.md)
- [REALTIME_PATTERNS.md](./REALTIME_PATTERNS.md)

---

## Review Notes

*Added during documentation review phase*

### Verified Consistency Points

1. **Setup/Tick Architecture**: The proposal correctly describes the two-phase model as implemented in `TemporalRunner` and `AudioScene.runner()`.

2. **Pattern Rendering Flow**: The call chain `AudioScene.getPatternSetup()` -> `PatternSystemManager.sum()` -> `PatternLayerManager.sum()` -> `PatternFeatures.render()` is accurately documented.

3. **Frame Conversion**: The `AudioSceneContext.frameForPosition()` and `getFrameForPosition()` methods exist and provide the measure-to-frame conversion needed for the proposed changes.

4. **Section Processing**: `ChannelSection` extends `AudioProcessor` with a `process(destination, source)` method. The proposal correctly identifies this as a risk area.

### Additional Risks Identified

#### Risk 6: Scale Traversal Frame Dependencies

The `ScaleTraversalStrategy` enum uses `context.frameForPosition()` to compute note offsets during `getNoteDestinations()`. For real-time rendering:
- The frame offsets must be computed relative to the current buffer
- The `PatternRenderContext` wrapper must correctly translate positions

**Mitigation**: The proposed `PatternRenderContext.measureToBufferOffset()` should handle this, but integration testing is critical.

#### Risk 7: Note Audio Evaluation Timing

`PatternFeatures.render()` currently evaluates note audio inside `Heap.stage()`:
```java
Heap.stage(() ->
    process.apply(traverse(1, note.getProducer()).get().evaluate()));
```

For real-time rendering:
- Audio evaluation must complete within the buffer time window

> **OUT OF SCOPE**: `Heap.stage()` usage is deferred. The initial implementation will
> bypass Heap memory management. If performance issues arise, this can be revisited.

#### Risk 8: Section Activity Bias

`PatternLayerManager.sum()` checks section activity via:
```java
double active = activeSelection.apply(...) + ctx.getActivityBias();
if (active < 0) return;
```

For incremental rendering, activity bias must be available at render time, not just setup time.

**Mitigation**: Ensure `PatternRenderContext` propagates activity bias from the parent context.

### Implementation Priority Adjustment

Based on the review and design decisions, the implementation phases are:

1. **Phase 1**: Buffer-aware pattern rendering
2. **Phase 2**: Pattern render cell
3. **Phase 3**: AudioScene integration
4. **Phase 4**: Optimization and polish

Note: Volume normalization and Heap memory management are explicitly **out of scope**
for this implementation. Pattern element caching is optional and at developer discretion.

### Testing Requirements Update

Add the following test cases:

1. **Frame Boundary Precision**: Verify `PatternRenderContext.measureToBufferOffset()` returns correct values at exact buffer boundaries
2. **Scale Traversal Correctness**: Test melodic patterns spanning buffer boundaries
3. **Activity Bias Propagation**: Test section activity changes mid-playback

---

## Implementation Gaps

> **Investigation Date**: January 2025
>
> The following issues were discovered through integration testing with `AudioSceneRealTimeTest`.
> **Important context**: The traditional AudioScene pipeline (via `AudioSceneOptimizer` /
> `StableDurationHealthComputation`) **does produce audio**. The failures below reflect
> inadequate test construction and unvalidated real-time code paths, not a broken AudioScene.

### Gap 1: Test Scene Construction Does Not Produce Audio

**Evidence from `traditionalRenderBaseline` test:**
```
Total frames: 88199 (correct count)
Duration: 2.00 seconds (correct)
Max amplitude: 0.000000  <-- ALL ZEROS
```

**Root Cause**: The test's `createTestScene()` method constructs a minimal scene with only
2 channels and 2 patterns, assigns a single random genome, and hopes that the stochastic
pattern element generation produces notes. This is unreliable because:
- `PatternElementFactory.apply()` returns `Optional.empty()` when `noteSelection + bias < 0`
- Default `bias` is -0.2 for most choices; combined with `seedBiasAdjustment`, many genomes
  produce zero elements
- The test tries only ONE random genome instead of searching for a genome that produces audio
- The `AudioSceneOptimizer` succeeds because it evaluates many genomes over many iterations

**What is needed**: A baseline test that tries multiple genomes (10+) with a properly
configured scene (more channels, more pattern layers, appropriate choices) to reliably
find a configuration that produces audio. See [REALTIME_TEST_PLAN.md](./REALTIME_TEST_PLAN.md)
for the proposed baseline test.

### Gap 2: Real-Time Output Pipeline Not Connected

**Evidence from `multipleBufferCycles` test:**
```
Expected frames: 88200 (2 seconds at 44.1kHz)
Actual frames: 85
```

**Root Cause**: The `WaveOutput` is not properly receiving frames from the real-time
rendering pipeline. The tick/push cycle doesn't propagate audio to the output. This is
a real implementation gap in the real-time code path.

### Gap 3: Performance is 4-10x Too Slow

**Evidence:**
```
Required buffer time: 23.22 ms
Avg render time: 100.78 - 213.40 ms
Real-time ratio: 0.11x - 0.23x
Buffer overruns: 100%
```

**Root Cause**: Operations are not being hardware-accelerated. Log shows:
```
WARN: OperationList: OperationList was not compiled (uniform = false)
```

**Architectural Issues**:
1. **No use of `CollectionProducer` pattern** - The implementation uses Java loops and
   `PackedCollection.setMem()` instead of hardware-accelerated `Producer` operations
2. **Operations created per-tick instead of compiled once** - Each tick creates new
   operations rather than compiling a reusable operation during setup
3. **No integration with CellList compilation** - The pattern rendering bypasses the
   normal Cell compilation path that enables hardware acceleration

### Gap 4: Code Duplication

The implementation duplicates functionality rather than extending existing patterns:

1. **`PatternRenderCell`** duplicates `Cell` patterns instead of using `CellList.map()`
2. **`BatchCell`** duplicates timing logic instead of using `TemporalRunner` batch support
3. **`sum(startFrame, frameCount)` overloads** duplicate the entire rendering logic instead
   of parameterizing the existing `sum()` method

### Gap 5: No Valid Comparison Test

A comparison test (`compareTraditionalAndRealTime`) exists but cannot produce meaningful
results because:
1. The test scene does not reliably produce audio (Gap 1)
2. The real-time output pipeline is not connected (Gap 2)
3. No baseline exists to compare against

### Path Forward

1. **Create a baseline test** that proves AudioScene produces audio without `AudioSceneOptimizer`
   (see [REALTIME_TEST_PLAN.md](./REALTIME_TEST_PLAN.md) for the proposed test)
2. **Fix the real-time output pipeline** (Gap 2) so frames reach `WaveOutput`
3. **Enable hardware acceleration** (Gap 3) by using `CollectionProducer` operations
4. **Eliminate code duplication** (Gap 4) by integrating with `CellList` infrastructure
5. **Build comparison test** once baseline and real-time both produce audio

---

## Implementation Summary (Original - For Reference)

> **Code Written**: January 2025
> **Status**: Code exists but does not function correctly

### Files Created

| File | Location | Purpose |
|------|----------|---------|
| `PatternRenderContext.java` | `music/src/main/java/org/almostrealism/audio/arrange/` | Extended context with frame range awareness |
| `PatternRenderCell.java` | `compose/src/main/java/org/almostrealism/audio/pattern/` | Cell that renders patterns incrementally during tick phase |
| `BatchCell.java` | `compose/src/main/java/org/almostrealism/audio/pattern/` | Wrapper that executes operations once per N frames |
| `RealTimeRenderingTest.java` | `compose/src/test/java/org/almostrealism/audio/pattern/test/` | Unit tests for real-time components (6 tests) |

### Files Modified

| File | Changes |
|------|---------|
| `PatternSystemManager.java` | Added `sum(context, channel, startFrame, frameCount)` overload |
| `PatternLayerManager.java` | Added `sum(context, voicing, audioChannel, startFrame, frameCount)` overload |
| `PatternFeatures.java` | Added `renderRange()` method for buffer-aware rendering |
| `AudioScene.java` | Added `runnerRealTime()`, `getCellsRealTime()`, `getPatternChannelRealTime()` methods |

### Key Implementation Details

1. **Buffer-aware rendering**: Frame range parameters added to all `sum()` methods
2. **Frame tracking**: External frame position management via `IntSupplier` callback
3. **Note boundary handling**: `renderRange()` properly splits notes spanning buffer boundaries
4. **Volume normalization**: Disabled in real-time mode (out of scope as planned)
5. **Heap.stage**: Bypassed in real-time mode (out of scope as planned)

### Usage Example

```java
// Create real-time runner with 1024-frame buffer
TemporalCellular runner = scene.runnerRealTime(output, 1024);

// Setup phase is lightweight (no pattern rendering)
runner.setup().get().run();

// Tick phase renders patterns incrementally
for (int i = 0; i < totalFrames; i++) {
    runner.tick().get().run();
}
```

### Test Results

All 6 new tests in `RealTimeRenderingTest` pass:
- `testPatternRenderContextBasics`
- `testBatchCellBasics`
- `testBatchCellFrameTracking`
- `testPatternRenderCellSetup`
- `testPatternRenderContextDelegation`
- `testOverlapCalculations`
