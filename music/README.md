# AR-Music Module

**Musical pattern composition, note management, and audio rendering for the Almost Realism framework.**

## Overview

The `ar-music` module provides tools for:
- **Pattern composition** - Creating and managing hierarchical musical patterns
- **Note management** - Individual note events with timing, duration, and scale traversal
- **Audio rendering** - Converting patterns to audio via hardware-accelerated operations
- **Melodic generation** - Scale-aware traversal strategies for melodic content

## Core Components

### PatternSystemManager

Manages a hierarchical system of musical patterns across multiple channels and layers.

```java
import org.almostrealism.audio.pattern.PatternSystemManager;

// Create pattern system
PatternSystemManager manager = new PatternSystemManager();

// Add patterns to channels
manager.addPattern(0, 4.0, false);  // Channel 0, 4 measures, non-melodic (drums)
manager.addPattern(1, 4.0, true);   // Channel 1, 4 measures, melodic (bass)

// Render all patterns in a channel to audio
Producer<PackedCollection<?>> audio = manager.sum(
    () -> sceneContext,
    channelInfo
);

// Get pattern elements within a time range
Map<NoteAudioChoice, List<PatternElement>> elements =
    manager.getPatternElements(startBeat, endBeat);
```

**Key Methods:**
- `addPattern(channel, measures, melodic)` - Creates and adds a new pattern layer
- `sum(contextSupplier, channelInfo)` - Aggregates audio from all patterns with auto-volume
- `getPatternElements(start, end)` - Retrieves notes within a time range grouped by audio choice

### PatternElement

Represents a single musical note event with timing, duration, scale traversal, and repetition.

```java
import org.almostrealism.audio.pattern.PatternElement;

// PatternElement contains:
// - Position in beats
// - Duration
// - Scale position and traversal strategy
// - Repeat count and spacing
// - Automation parameters

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

**Key Properties:**
- `position` - Beat position in the pattern
- `duration` - Note length (can use `DurationStrategy` for dynamic calculation)
- `scalePosition` - Position in musical scale (for melodic patterns)
- `repeatCount` / `repeatDuration` - Note repetition settings
- `automation` - `PackedCollection` of automation parameters

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

### PatternLayerManager

Manages a single layer within the pattern system, handling note placement and generation.

```java
import org.almostrealism.audio.pattern.PatternLayerManager;

// PatternLayerManager coordinates:
// - Note placement within a layer
// - Pattern generation from genomes
// - Layer-level audio rendering
```

### Note Audio Pipeline

#### NoteAudioChoice

Represents an audio source selection for a note (e.g., a drum sample or synth patch).

```java
import org.almostrealism.audio.notes.NoteAudioChoice;

// NoteAudioChoice links patterns to audio sources
// Used as key in PatternSystemManager.getPatternElements() grouping
```

#### NoteAudioProvider

Provides audio data for notes, typically from sample files.

```java
import org.almostrealism.audio.notes.NoteAudioProvider;

// Supplies audio data to the pattern rendering pipeline
// Implementations include FileNoteSource for sample playback
```

#### FileNoteSource

Loads audio samples from files for use as note sources.

```java
import org.almostrealism.audio.notes.FileNoteSource;

// Load a drum sample
FileNoteSource kickSource = new FileNoteSource("/samples/kick.wav");

// The audio is available for pattern rendering
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

## Scale Traversal

For melodic patterns, `ScaleTraversalStrategy` controls how notes move through the musical scale:

```java
// PatternElement uses scale traversal for melodic content
element.setScalePosition(0);  // Root note
element.setScaleTraversalStrategy(strategy);  // How to move through scale
```

## Dependencies

The music module depends on:
- `ar-audio` - Core audio processing
- `ar-time` - Temporal operations and `TemporalRunner`
- `ar-collect` - `PackedCollection` for audio buffers
- `ar-heredity` - Genetic representation via `ProjectedChromosome`

## See Also

- [AR-Audio Module](../audio/README.md) - Core audio processing
- [AR-Compose Module](../compose/README.md) - Audio scene optimization
- [AR-Time Module](../time/README.md) - Temporal operations
