# Almost Realism Music Module (ar-music)

## Overview

The **ar-music** module provides pattern-based music composition capabilities for the Almost Realism framework. It handles the creation, organization, and rendering of musical patterns from audio samples and synthesized notes.

This module is the core music engine that:
- Organizes audio samples into musical patterns
- Manages multi-layer pattern hierarchies
- Handles scale traversal and chord progressions
- Provides automation and envelope control
- Integrates with genetic algorithm optimization

## Architecture

### Module Dependencies

```
ar-music
    |
    +-- ar-audio (CellFeatures, CellList)
    |
    +-- ar-heredity (Chromosome, Gene, genetic algorithms)
    |
    +-- ar-collect (PackedCollection, shapes)
    |
    +-- ar-time (Temporal processing)
```

### Package Organization

```
org.almostrealism.audio
    |
    +-- arrange/          [AudioSceneContext, ChannelSection]
    |
    +-- pattern/          [PatternSystemManager, PatternLayerManager, etc.]
    |
    +-- notes/            [PatternNote, NoteAudioChoice, etc.]
    |
    +-- data/             [ChannelInfo, ParameterSet]
    |
    +-- filter/           [ParameterizedEnvelopes]
    |
    +-- grains/           [Granular synthesis]
    |
    +-- sequence/         [GridSequencer]
```

## Core Classes

### PatternSystemManager

The top-level manager for the entire pattern system. Coordinates multiple `PatternLayerManager` instances across all channels.

**Key Responsibilities:**
- Maintains list of `NoteAudioChoice` options (audio sample configurations)
- Creates and manages `PatternLayerManager` for each pattern
- Provides the `sum()` method for rendering patterns to audio
- Handles auto-volume normalization
- Integrates with genetic algorithm via `ProjectedChromosome`

**Configuration:**
```java
PatternSystemManager.enableAutoVolume = true;   // Normalize output volume
PatternSystemManager.enableLazyDestination = false;  // Immediate buffer update
PatternSystemManager.enableVerbose = false;     // Debug logging
```

**Key Methods:**
- `addPattern(channel, measures, melodic)` - Creates and adds a new pattern layer
- `sum(contextSupplier, channelInfo)` - Aggregates audio from all patterns with auto-volume
- `getPatternElements(start, end)` - Retrieves notes within a time range grouped by audio choice

### PatternLayerManager

Manages a single pattern with hierarchical layers. Each pattern can have up to 32 layers that build upon each other.

**Key Responsibilities:**
- Multi-layer pattern generation
- Melodic vs. percussive mode handling
- Scale traversal strategies (CHORD, SEQUENCE)
- Note selection via genetic algorithm parameters
- Pattern rendering via `sum()`

**Layer Hierarchy:**
```
Layer 0 (Root)
    |-- Seeds: Initial pattern elements from NoteAudioChoice
    |
Layer 1
    |-- Built from Layer 0 with scale/2 granularity
    |
Layer 2
    |-- Built from Layer 1 with scale/4 granularity
    |
... up to 32 layers
```

### PatternElement

Represents a single musical event in a pattern.

**Properties:**
- `position`: Where in the measure(s) the element occurs
- `duration`: How long the element lasts
- `notes`: Map of voicing (MAIN, WET) to PatternNote
- `repeat`: Number of repetitions
- `repeatDuration`: Duration between repetitions
- `automationParameters`: Dynamic control values

```java
// Get rendered audio destinations for a note
List<NoteDestination> destinations = element.getNoteDestinations(
    isMelodic,
    tuningFactor,
    sceneContext,
    noteContext
);

// Compute absolute timing positions (considering repeats)
List<Double> positions = element.getPositions();
```

### PatternNote

Adapter for audio samples that handles:
- Multi-layer note audio
- Keyboard tuning application
- Layer aggregation and filtering

### NoteAudioChoice

Configuration for a set of audio samples that can be used in patterns:
- Sample sources (files, synthesized)
- Scale/granularity constraints
- Seed patterns
- Channel restrictions

### PatternFeatures

Mixin interface for rendering pattern elements to audio buffers.

```java
import org.almostrealism.audio.pattern.PatternFeatures;

public class MyRenderer implements PatternFeatures {
    public void renderPatterns() {
        // Render pattern elements to destination buffer
        render(
            sceneContext,      // Audio scene context
            noteContext,       // Note audio context
            patternElements,   // List of PatternElement
            isMelodic,         // Whether melodic (scale-aware)
            tuningFactor       // Pitch tuning multiplier
        );
    }
}
```

The `render()` method:
1. Extracts `NoteDestination` objects from each `PatternElement`
2. Sums the audio into the destination buffer using staged heap operations
3. Uses `AudioProcessingUtils` for efficient audio mixing

## The Pattern Rendering Process

### Current (Non-Real-Time) Flow

```
AudioScene.getPatternSetup(channel)
    |
    +-- PatternSystemManager.sum(context, channel)
        |
        +-- updateDestinations()  // Set output buffer
        |
        +-- For each PatternLayerManager:
            |
            +-- PatternLayerManager.sum(context, voicing, audioChannel)
                |
                +-- getAllElementsByChoice(0.0, duration)
                |
                +-- For each pattern repetition:
                    |
                    +-- render(context, audioContext, elements, melodic, offset)
                        |
                        +-- For each element:
                            +-- getNoteDestinations()
                            +-- Sum audio to destination buffer
        |
        +-- Auto-volume normalization (if enabled)
```

### The `sum()` Method

The critical method that renders patterns to audio:

```java
// PatternSystemManager.sum()
public Supplier<Runnable> sum(Supplier<AudioSceneContext> context, ChannelInfo channel) {
    OperationList op = new OperationList("PatternSystemManager Sum");

    // Update destination buffers
    op.add(updateDestinations);

    // Sum each pattern for this channel
    patternsForChannel.forEach(i -> {
        op.add(patterns.get(i).sum(context, channel.getVoicing(), channel.getAudioChannel()));
    });

    // Auto-volume normalization
    if (enableAutoVolume) {
        Producer<PackedCollection> max = cp(destination).traverse(0).max().isolate();
        op.add(volumeAdjustment);
    }

    return op;
}
```

```java
// PatternLayerManager.sum()
public Supplier<Runnable> sum(Supplier<AudioSceneContext> context,
                              ChannelInfo.Voicing voicing,
                              ChannelInfo.StereoChannel audioChannel) {
    return OperationWithInfo.of(metadata, () -> () -> {
        Map<NoteAudioChoice, List<PatternElement>> elements = getAllElementsByChoice(0.0, duration);

        int count = ctx.getMeasures() / duration;  // How many times pattern repeats

        IntStream.range(0, count).forEach(i -> {
            // Check if section is active
            ChannelSection section = ctx.getSection(i * duration);
            double active = activeSelection.apply(params, section.getPosition());
            if (active < 0) return;

            // Render each choice's elements
            elements.keySet().forEach(choice -> {
                render(ctx, audioContext, elements.get(choice), melodic, offset);
            });
        });
    });
}
```

### Pattern Rendering (`PatternFeatures.render()`)

The actual audio summation happens in `PatternFeatures.render()`:

```java
default void render(AudioSceneContext sceneContext, NoteAudioContext audioContext,
                    List<PatternElement> elements, boolean melodic, double offset) {
    PackedCollection destination = sceneContext.getDestination();

    elements.stream()
        .map(e -> e.getNoteDestinations(melodic, offset, sceneContext, audioContext))
        .flatMap(List::stream)
        .forEach(note -> {
            // Calculate frame range
            int frames = Math.min(audio.getShape().getCount(),
                    destination.getShape().length(0) - note.getOffset());

            // Sum audio to destination
            AudioProcessingUtils.getSum().sum(
                destination.range(shape(frames), note.getOffset()),
                audio.range(shape(frames)));
        });
}
```

## Note Audio Pipeline

### NoteAudioProvider

Provides audio data for notes, typically from sample files.

```java
import org.almostrealism.audio.notes.NoteAudioProvider;

// Supplies audio data to the pattern rendering pipeline
// Implementations include FileNoteSource for sample playback
```

### FileNoteSource

Loads audio samples from files for use as note sources.

```java
import org.almostrealism.audio.notes.FileNoteSource;

// Load a drum sample
FileNoteSource kickSource = new FileNoteSource("/samples/kick.wav");

// The audio is available for pattern rendering
```

## Limitations for Real-Time Audio

### Current Issues

1. **Full Duration Rendering**: `sum()` renders the entire pattern duration at once
   - Cannot specify a frame range to render
   - Must complete before any audio output begins

2. **Destination Buffer Pre-allocation**:
   - Buffers sized for `standardDurationFrames` or `getTotalSamples()`
   - Cannot be incrementally filled

3. **No Frame Range Support**:
   - `getAllElementsByChoice(start, end)` uses measure-based time, not frames
   - No mechanism to render "frames N to N+buffer_size"

### Required Changes for Real-Time

See `REALTIME_PATTERNS.md` for the detailed proposal. Key changes needed:

1. Add frame range parameters to `sum()` methods
2. Modify `render()` to respect frame boundaries
3. Add incremental element filtering
4. Integrate with tick-based execution

## Supporting Classes

### AudioSceneContext

Provides context for pattern rendering:
- `measures`: Total measures in the scene
- `frames`: Total frames (samples)
- `destination`: Output buffer
- `sections`: Channel sections for activity
- `automationLevel`: Parameter automation function

### ChannelSection

Defines a section of the arrangement:
- `position`: Start measure
- `length`: Duration in measures
- `process()`: Section-specific processing

### ParameterizedEnvelopes

Envelope generators for dynamic control:
- `ParameterizedVolumeEnvelope`
- `ParameterizedFilterEnvelope`
- `ParameterizedLayerEnvelope`

### ChordProgressionManager

Manages chord changes throughout the scene:
- Scale selection per position
- Genetic algorithm integration
- Configurable duration and size

## Scale Traversal

For melodic patterns, `ScaleTraversalStrategy` controls how notes move through the musical scale:

```java
// PatternElement uses scale traversal for melodic content
element.setScalePosition(0);  // Root note
element.setScaleTraversalStrategy(strategy);  // How to move through scale
```

## Usage Examples

### Creating Pattern Layers

```java
// Create system manager with choices and chromosomes
PatternSystemManager patterns = new PatternSystemManager(choices, chromosomes);
patterns.init();

// Add patterns for each channel
PatternLayerManager kickPattern = patterns.addPattern(0, 1.0, false);  // 1 measure, percussive
PatternLayerManager bassPattern = patterns.addPattern(2, 4.0, true);   // 4 measures, melodic

// Configure layers
kickPattern.setLayerCount(4);
bassPattern.setLayerCount(6);
bassPattern.setScaleTraversalStrategy(ScaleTraversalStrategy.SEQUENCE);
```

### Rendering Patterns

```java
// In AudioScene
Supplier<Runnable> patternSetup = patterns.sum(
    () -> getContext(List.of(channel)),
    channel
);

// Execute (renders entire arrangement)
patternSetup.get().run();
```

### Accessing Pattern Elements

```java
// Get elements in a time range (in measures)
Map<NoteAudioChoice, List<PatternElement>> elements =
    patterns.getPatternElements(0.0, 4.0);

// Get all elements from a specific pattern
List<PatternElement> bassElements =
    bassPattern.getAllElements(0.0, 4.0);
```

## Configuration

### PatternLayerManager Settings

```java
PatternLayerManager.Settings settings = new PatternLayerManager.Settings();
settings.setChannel(2);                    // Bass channel
settings.setDuration(4.0);                 // 4 measures
settings.setMelodic(true);                 // Melodic mode
settings.setScaleTraversalStrategy(ScaleTraversalStrategy.SEQUENCE);
settings.setScaleTraversalDepth(5);        // How deep in scale
settings.setMinLayerScale(0.0625);         // Minimum granularity (1/16)
settings.setLayerCount(6);                 // Number of layers
```

### PatternElementFactory Configuration

```java
PatternElementFactory factory = new PatternElementFactory();
factory.setVolumeEnvelope(...);
factory.setFilterEnvelope(...);
factory.setChordPositionSelection(...);
factory.setNoteDurationStrategy(...);
```

## Integration with AudioScene

The music module integrates with `AudioSceneContext` and the broader audio pipeline:

```java
// In AudioScene setup:
PatternSystemManager patterns = new PatternSystemManager();
// ... configure patterns ...

// During rendering:
Producer<PackedCollection<?>> channelAudio = patterns.sum(
    () -> scene.getContext(),
    channel
);
```

## Pattern Rendering Flow

```
PatternSystemManager
    │
    ├── PatternLayerManager (per layer)
    │       │
    │       └── PatternElement (individual notes)
    │               │
    │               └── NoteAudioChoice → NoteAudioProvider
    │
    └── sum() → PatternFeatures.render()
            │
            └── PackedCollection (rendered audio buffer)
```

## Dependencies

The music module depends on:
- `ar-audio` - Core audio processing
- `ar-time` - Temporal operations and `TemporalRunner`
- `ar-collect` - `PackedCollection` for audio buffers
- `ar-heredity` - Genetic representation via `ProjectedChromosome`

## Testing

Run tests using the MCP test runner:

```
mcp__ar-test-runner__start_test_run
  module: "music"
  profile: "pipeline"
```

## Related Documentation

- [ar-compose README](../compose/README.md) - Audio scene orchestration
- [ar-audio README](../../engine/audio/README.md) - Cell infrastructure
- [REALTIME_PATTERNS.md](../compose/REALTIME_PATTERNS.md) - Real-time pattern proposal
