# Almost Realism Compose Module (ar-compose)

## Overview

The **ar-compose** module provides high-level audio scene composition, arrangement, and generation capabilities for the Almost Realism framework. It serves as the primary orchestrator for combining musical patterns, effects processing, mixdown operations, and real-time audio generation.

This module sits at the top of the audio processing hierarchy, integrating:
- Pattern-based music arrangement (via ar-music)
- Cell-based audio processing (via ar-audio)
- Machine learning audio generation (via ar-ml)
- Genetic algorithm optimization (via ar-heredity)

## Architecture

### Module Dependencies

```
ar-compose
    |
    +-- ar-music (patterns, notes, envelopes)
    |
    +-- ar-audio (cells, lines, buffers)
    |
    +-- ar-graph (Cell, Receptor, Layer)
    |
    +-- ar-time (Temporal, TemporalRunner)
    |
    +-- ar-ml (AudioComposer, generators)
    |
    +-- ar-heredity (genetic algorithms)
```

### Core Classes

| Class | Package | Responsibility |
|-------|---------|----------------|
| `AudioScene` | `org.almostrealism.audio` | Central orchestrator for audio scenes |
| `MixdownManager` | `org.almostrealism.audio.arrange` | Effects routing, delays, reverb |
| `AutomationManager` | `org.almostrealism.audio.arrange` | Parameter automation over time |
| `GlobalTimeManager` | `org.almostrealism.audio.arrange` | Time tracking and reset points |
| `EfxManager` | `org.almostrealism.audio.arrange` | Per-channel effects management |
| `RiseManager` | `org.almostrealism.audio.arrange` | Rise/swell effect processing |
| `SceneSectionManager` | `org.almostrealism.audio.arrange` | Musical section organization |
| `AudioComposer` | `org.almostrealism.ml.audio` | ML-based audio generation |
| `AudioLibraryPersistence` | `org.almostrealism.audio.persistence` | Save/load library to Protocol Buffer |
| `LibraryDestination` | `org.almostrealism.audio.persistence` | Batched protobuf file management |
| `PrototypeDiscovery` | `org.almostrealism.audio.discovery` | Find representative samples via graph algorithms |

## AudioScene: The Central Orchestrator

`AudioScene` is the main entry point for constructing and rendering audio compositions. It manages:

1. **Tempo and Timing**: BPM, measure duration, sample rate
2. **Pattern System**: Via `PatternSystemManager` (from ar-music)
3. **Effects Chain**: Via `MixdownManager`, `EfxManager`
4. **Automation**: Via `AutomationManager`
5. **Section Structure**: Via `SceneSectionManager`

### Execution Flow

```
AudioScene.getCells(output)
    |
    +-- [1] setup phase (OperationList)
    |       +-- AutomationManager.setup()
    |       +-- RiseManager.setup()
    |       +-- MixdownManager.setup()
    |       +-- GlobalTimeManager.setup()
    |
    +-- [2] getPatternCells()
    |       +-- getPatternChannel() for each channel
    |           +-- PatternSystemManager.sum()  <-- RENDERS ALL PATTERNS
    |           +-- ChannelSection.process()
    |       +-- MixdownManager.cells()  <-- Creates CellList
    |
    +-- [3] addRequirement(time::tick)
```

### The Setup vs Tick Distinction

A critical architectural concept is the separation between **setup** and **tick** phases:

- **Setup Phase**: Runs once before audio processing begins
  - Pattern rendering (`PatternSystemManager.sum()`)
  - Buffer initialization
  - Effects chain compilation

- **Tick Phase**: Runs repeatedly for each audio frame
  - Cell push operations
  - Effects processing
  - Output writing

**Current Limitation**: The entire pattern arrangement is rendered during setup, before any audio output begins. This prevents true real-time streaming.

## MixdownManager: Effects and Routing

`MixdownManager` creates the final audio processing chain that combines:

1. **Main Channel Processing**
   - Volume adjustment
   - High-pass filters (optional)

2. **Effects Processing**
   - Delay networks (configurable layers)
   - Transmission routing
   - Reverb (via `DelayNetwork`)

3. **Output Routing**
   - Master output
   - Measure outputs for monitoring
   - Stem outputs for multitrack

### Key Methods

```java
// Create the complete audio processing chain
CellList cells(CellList sources, CellList wetSources, CellList riser,
               MultiChannelAudioOutput output,
               ChannelInfo.StereoChannel audioChannel,
               IntUnaryOperator channelIndex)
```

## Arrangement Package

The `org.almostrealism.audio.arrange` package contains:

### GlobalTimeManager
Manages global playback position with support for:
- Reset points (breaks in the timeline)
- Measure-to-frame conversion
- Time tracking via `TimeCell`

### AutomationManager
Provides parameter automation with:
- Periodic modulation (short-term oscillation)
- Overall modulation (long-term evolution)
- Aggregated value computation

### SceneSectionManager
Organizes the musical structure:
- Section definitions (position, length in measures)
- Per-channel section assignment
- Wet/dry channel designation

### EfxManager
Per-channel effects including:
- Filter envelopes
- Compression
- Section-based processing

## Health and Optimization Package

The `org.almostrealism.audio.health` package provides audio quality metrics:

### StableDurationHealthComputation
Evaluates audio quality by measuring how long playback can continue before:
- Clipping occurs (signal exceeds max)
- Silence occurs (signal drops too low)

This is used for genetic algorithm fitness evaluation.

### Key Usage Pattern

```java
StableDurationHealthComputation health = new StableDurationHealthComputation(6);
health.setTarget(audioScene.runner(output));

// Run evaluation
Runnable start = runner.get();  // Setup + initial ticks
Runnable iterate = runner.getContinue();  // Tick only

for (long frame = 0; frame < maxFrames; frame += batchSize) {
    (frame == 0 ? start : iterate).run();
    checkForClipping();
    checkForSilence();
}
```

## Generation Package

The `org.almostrealism.audio.generative` package provides:

### GenerationManager
Coordinates audio generation from various sources:
- Pattern-based generation
- ML model generation
- Hybrid approaches

### GenerationResourceManager
Manages generation resources including:
- Model weights
- Audio samples
- Cached computations

## ML Audio Package

The `org.almostrealism.ml.audio` package provides:

### AudioComposer
Generates audio using autoencoder-based latent space interpolation:
- Encodes audio features
- Interpolates in latent space
- Decodes to audio output

### ComposableAudioFeatures
Enables weighted feature composition for audio generation.

## Persistence Package

The `org.almostrealism.audio.persistence` package handles audio library storage.

### Key-Identifier Architecture

**CRITICAL**: Protobuf stores only the **identifier** (MD5 hash), NOT file paths.

To resolve an identifier to a file path, you need BOTH:
1. The protobuf data (contains identifiers and features)
2. A file tree (directory of audio files)

```java
// Create library with file tree
AudioLibrary library = new AudioLibrary(new File("/path/to/samples"), 44100);

// Load protobuf data
AudioLibraryPersistence.loadLibrary(library, "/path/to/library");

// Resolve identifier to file path
WaveDataProvider provider = library.find(identifier);
String filePath = provider.getKey();  // Actual file path!
```

### Why This Matters

| What You Have | Method | Returns |
|---------------|--------|---------|
| WaveDetails from protobuf | `details.getIdentifier()` | MD5 hash (e.g., `a1b2c3d4...`) |
| WaveDataProvider from library | `provider.getKey()` | File path (e.g., `/samples/kick.wav`) |

**To convert identifier → file path**: `library.find(identifier).getKey()`

## Discovery Package

The `org.almostrealism.audio.discovery` package provides sample discovery tools.

### PrototypeDiscovery

Console app for finding representative samples using graph algorithms.

```bash
java -cp ... org.almostrealism.audio.discovery.PrototypeDiscovery \
  --data ~/.almostrealism/library --clusters 5
```

**Note**: To display file paths (not just identifiers), PrototypeDiscovery needs access to the original audio files directory. Use `AudioLibrary.find(identifier).getKey()` to resolve identifiers to paths.

## Usage Examples

### Basic AudioScene Setup

```java
// Create an audio scene at 120 BPM, 44100 Hz
AudioScene<?> scene = new AudioScene<>(120.0, 6, 3, 44100);

// Load settings and patterns
scene.loadSettings(new File("scene.json"));
scene.loadPatterns("patterns.json");

// Set the audio library
scene.setLibraryRoot(new FileWaveDataProviderNode(new File("samples/")));
```

### Rendering Audio

```java
// Get cells for output
Cells cells = scene.getCells(output);

// Create temporal runner
TemporalCellular runner = scene.runner(output);

// Execute setup + initial processing
runner.setup().get().run();
runner.tick().get().run();
```

### Buffered Playback

```java
// Buffer to output line
CellList cells = scene.getCells(output);
BufferedOutputScheduler scheduler = cells.buffer(outputLine);

// Start real-time playback
scheduler.start();
```

## Configuration

### Key Static Flags

```java
// MixdownManager
MixdownManager.enableMixdown = false;      // Enable mixdown processing
MixdownManager.enableReverb = true;        // Enable reverb effect
MixdownManager.enableTransmission = true;  // Enable delay transmission

// TemporalRunner
TemporalRunner.enableOptimization = false; // Apply hardware optimization
TemporalRunner.enableFlatten = true;       // Flatten operation lists
```

## Real-Time Audio Considerations

### Current Architecture Limitations

The current architecture has a significant limitation for real-time audio:

1. **Pattern Pre-rendering**: `PatternSystemManager.sum()` renders the entire arrangement during the setup phase
2. **Fixed Destination Buffers**: Pattern destinations are pre-allocated for the full duration
3. **No Incremental Rendering**: Patterns cannot be rendered incrementally

### Path to Real-Time

See `REALTIME_AUDIO_SCENE.md` for the proposed solution involving:

1. Buffer-aware pattern rendering
2. Moving pattern sum to tick phase
3. N-frame batch processing in cells

## Testing

Run tests using the MCP test runner:

```
mcp__ar-test-runner__start_test_run
  module: "compose"
  profile: "pipeline"
```

## Related Documentation

- [Detailed Compose Documentation](docs/COMPOSE_MODULE.md)
- [ar-music README](../music/README.md) - Pattern and note management
- [ar-audio README](../audio/README.md) - Cell infrastructure
- [Audio Library Documentation](../audio/docs/AUDIO_LIBRARY.md)
- [REALTIME_AUDIO_SCENE.md](./REALTIME_AUDIO_SCENE.md) - Real-time proposal
