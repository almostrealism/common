# AR-Compose Module

Audio persistence, discovery, and composition tools for the Almost Realism framework.

## Key Components

| Class | Package | Purpose |
|-------|---------|---------|
| `AudioLibraryPersistence` | `o.a.audio.persistence` | Save/load library to Protocol Buffer |
| `LibraryDestination` | `o.a.audio.persistence` | Batched protobuf file management |
| `PrototypeDiscovery` | `o.a.audio.discovery` | Find representative samples via graph algorithms |

## Key-Identifier Architecture

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

## PrototypeDiscovery

Console app for finding representative samples using graph algorithms.

```bash
java -cp ... org.almostrealism.audio.discovery.PrototypeDiscovery \
  --data ~/.almostrealism/library --clusters 5
```

**Note**: To display file paths (not just identifiers), PrototypeDiscovery needs access to the original audio files directory. Use `AudioLibrary.find(identifier).getKey()` to resolve identifiers to paths.

## AudioScene and Runner Lifecycle

The `AudioScene` class is the central orchestration class for audio generation, managing patterns, effects, timing, and the audio cell pipeline.

### AudioScene.runner() Method

The `runner()` method creates a `TemporalCellular` that wraps the audio generation pipeline for iterative execution.

```java
import org.almostrealism.audio.AudioScene;
import org.almostrealism.heredity.TemporalCellular;

// Create AudioScene
AudioScene scene = new AudioScene(animation, bpm, sampleRate);
scene.loadSettings(settingsFile);

// Create runner from the scene
TemporalCellular runner = scene.runner(audioOutput);

// The runner provides setup() and tick() methods
// Setup: initializes patterns, effects, timing - runs ONCE
runner.setup().get().run();

// Tick: advances the audio pipeline by one frame
// Call repeatedly for continuous audio generation
for (int frame = 0; frame < totalFrames; frame++) {
    runner.tick().get().run();
}

// Reset: resets the temporal state for another run
runner.reset();
```

### Runner with Specific Channels

```java
// Run only specific channels (e.g., drums and bass)
List<Integer> channels = List.of(0, 1);
TemporalCellular runner = scene.runner(audioOutput, channels);
```

### Runner Lifecycle Flow

```
AudioScene.runner()
    │
    ├── getCells(output) → Cells
    │       │
    │       ├── AudioScene.setup (OperationList)
    │       │       ├── AutomationManager.setup()
    │       │       ├── RiseManager.setup() (if enabled)
    │       │       ├── MixdownManager.setup()
    │       │       └── GlobalTimeManager.setup()
    │       │
    │       └── PatternCells (CellList)
    │               └── MixdownManager.cells()
    │
    └── Returns TemporalCellular
            │
            ├── setup() → OperationList (flattened)
            │       ├── AudioScene setup ops
            │       └── Cells setup ops
            │
            ├── tick() → Cells.tick()
            │
            └── reset() → Cells.reset()
```

### Memory Considerations

The runner allocates pattern destination buffers on first use:
- One `PackedCollection` per channel per voicing (MAIN/WET) per stereo channel
- Size: `min(standardDurationFrames, totalSamples)` frames
- Destroyed when `AudioScene.destroy()` is called or duration changes

## Audio Scene Optimization

The compose module provides evolutionary optimization for audio scenes, breeding and evaluating audio configurations to maximize fitness metrics.

### AudioSceneOptimizer

Evolutionary algorithm optimizer that breeds and evaluates audio scenes to maximize health fitness.

```java
import org.almostrealism.audio.optimize.AudioSceneOptimizer;

// Build optimizer from an AudioScene
AudioSceneOptimizer optimizer = AudioSceneOptimizer.build(scene, cycles);

// Configure feature level (what effects/filters are enabled)
optimizer.setFeatureLevel(2);  // 0=minimal, higher=more features

// Run optimization iterations
for (int gen = 0; gen < generations; gen++) {
    optimizer.iterate();
    System.out.println("Gen " + gen +
        " avg=" + optimizer.getAverageScore() +
        " max=" + optimizer.getMaxScore());
}
```

**Key Methods:**
- `build(scene, cycles)` - Factory to create optimizer with automatic genome breeding
- `setFeatureLevel(level)` - Configure which audio features are enabled
- `iterate()` - Run one generation of evolution

### AudioScenePopulation

Manages a population of audio scene genomes, coordinating evaluation and execution.

```java
import org.almostrealism.audio.optimize.AudioScenePopulation;

// AudioScenePopulation wraps an AudioScene and maintains genome list
AudioScenePopulation population = new AudioScenePopulation(scene);

// Initialize with template genome
population.init(templateGenome, audioOutput, enabledChannels);

// Enable a specific genome for evaluation
population.enableGenome(genomeIndex);

// Generate audio for all genomes
population.generate(
    durationFrames,
    sampleRate,
    () -> outputFilename,
    result -> handleResult(result)
);
```

**Key Methods:**
- `init(genome, output, channels)` - Initialize population with template genome
- `enableGenome(index)` - Activate a genome and reset temporal state
- `generate(frames, rate, nameSupplier, callback)` - Generate audio for all genomes

### StableDurationHealthComputation

Fitness evaluator that measures how long an audio scene plays before clipping or silence.

```java
import org.almostrealism.audio.health.StableDurationHealthComputation;

// Create health computation
StableDurationHealthComputation health = new StableDurationHealthComputation();

// Configure
health.setMaxDuration(30);  // Max 30 seconds before timeout

// Set the target to evaluate
health.setTarget(temporalCellular);

// Compute fitness score
AudioHealthScore score = health.computeHealth();
System.out.println("Stable duration: " + score.getScore());
```

The health computation:
1. Runs the temporal cellular in incremental batches via `TemporalRunner`
2. Monitors for audio clipping (values > 1.0)
3. Monitors for silence (sustained zero output)
4. Returns a score based on stable playback duration

**Key Methods:**
- `setTarget(temporal)` - Set the temporal entity to evaluate
- `setMaxDuration(seconds)` - Set timeout threshold
- `computeHealth()` - Run evaluation and return `AudioHealthScore`

### Optimization Flow

```
AudioSceneOptimizer
    │
    ├── AudioScenePopulation (genome management)
    │       │
    │       ├── Genome<PackedCollection> (genetic parameters)
    │       │
    │       └── TemporalCellular (audio execution)
    │
    └── StableDurationHealthComputation (fitness evaluation)
            │
            └── TemporalRunner (incremental execution)
                    │
                    └── AudioHealthScore (fitness result)
```

### Integration with PopulationOptimizer

`AudioSceneOptimizer` extends the general `PopulationOptimizer` framework:

```java
// The optimizer integrates with the genetic algorithm framework
optimizer.setHealthListener((signature, score) ->
    log("Genome " + signature + " scored " + score.getScore())
);

// Access best genome
Genome<PackedCollection> best = optimizer.getBestGenome();
```

## See Also

- [Detailed Compose Documentation](docs/COMPOSE_MODULE.md)
- [Audio Library Documentation](../audio/docs/AUDIO_LIBRARY.md)
- [AR-Music Module](../music/README.md) - Pattern composition
- [AR-Optimize Module](../optimize/README.md) - Generic optimization framework
- [AR-Time Module](../time/README.md) - TemporalRunner and temporal operations
