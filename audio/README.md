# ar-audio Module

Tools for audio synthesis and the design and optimization of signal generators and filters.

## Overview

The `ar-audio` module provides comprehensive tools for:
- **Audio Synthesis**: Generate sine waves, noise, and complex waveforms
- **Signal Processing**: Apply filters, delays, and effects
- **Audio I/O**: Read/write WAV files and interface with audio hardware
- **Musical Tuning**: Western chromatic scale, keyboard tuning, and musical scales
- **Sample-Based Playback**: Note-based audio with caching and filtering

The module integrates deeply with the Almost Realism framework's computational graph system, enabling real-time audio processing with hardware acceleration.

## Architecture

```
ar-audio
├── Root Package (org.almostrealism.audio)
│   ├── CellFeatures      - Factory methods for creating audio cells
│   ├── SamplingFeatures  - Sample rate and frame context management
│   ├── CellList          - Hierarchical cell container with lifecycle
│   ├── WavFile           - WAV file I/O (RIFF/PCM format)
│   └── WaveOutput        - Audio output coordination
│
├── sources/              - Signal Generators
│   ├── SineWaveCell      - Sine wave oscillator
│   ├── NoiseGenerator    - White noise generation
│   └── SourceAggregator  - Mixing and combining sources
│
├── synth/                - Synthesis Models
│   ├── AudioSynthesizer  - Main synthesis engine
│   ├── OvertoneSeries    - Harmonic overtone generation
│   └── UniformFrequencySeries - Uniform frequency sets
│
├── filter/               - Effects Processing
│   ├── AudioPassFilter   - High-pass and low-pass filters
│   ├── DelayNetwork      - Delay lines and reverb
│   ├── BasicDelayCell    - Simple delay effect
│   └── EnvelopeProcessor - ADSR envelope processing
│
├── data/                 - Audio Data Management
│   ├── WaveData          - Audio sample container with FFT
│   ├── WaveDataProvider  - Abstract data source
│   ├── FileWaveDataProvider - File-based audio loading
│   └── WaveDetails       - Audio file metadata
│
├── line/                 - Hardware Audio I/O
│   ├── AudioLine         - Full-duplex audio interface
│   ├── InputLine         - Audio capture
│   ├── OutputLine        - Audio playback
│   └── SourceDataOutputLine - Java Sound API output
│
├── notes/                - Sample-Based Playback
│   ├── NoteAudioProvider - Note management with caching
│   ├── NoteAudioFilter   - Note effect processing
│   └── TremoloAudioFilter - Tremolo effect
│
├── tone/                 - Musical Tuning
│   ├── KeyboardTuning    - Tuning system interface
│   ├── DefaultKeyboardTuning - Standard 12-TET tuning
│   ├── WesternChromatic  - 88-key chromatic scale enum
│   ├── Scale             - Musical scale abstraction
│   └── WesternScales     - Major/minor scale factory
│
└── sequence/             - Timed Value Updates
    ├── ValueSequenceCell - Sequenced value playback
    └── TempoAware        - Tempo-aware operations
```

## Dependencies on Other Modules

### Core Framework Dependencies

| Module | Usage |
|--------|-------|
| **graph** | Audio cells extend `CollectionTemporalCellAdapter`, `WaveCell`, and use the `Cell`/`Receptor` pattern |
| **time** | `Temporal` interface, `Frequency` class, `TemporalFeatures` for time-aware operations |
| **collect** | All audio data stored in `PackedCollection` for hardware acceleration |
| **hardware** | `OperationList` for compiled operations, `ComputeRequirement` for GPU hints |
| **heredity** | `Factor<PackedCollection>` for audio processing chains, `Gene` for parameter optimization |
| **io** | `Console` for logging, `OutputFeatures` for file output |

### Integration Patterns

#### Cell-Based Processing

Audio processing uses the graph module's cell system:

```java
// Create a sine wave cell
CellList cells = w(0, "Library/sample.wav")  // Load WAV file
    .d(i -> _250ms())                         // Add 250ms delay
    .f(i -> hp(scalar(500), scalar(0.1)))     // High-pass filter at 500Hz
    .o(i -> new File("output.wav"));          // Output to file

// Execute for 10 seconds
cells.sec(10).get().run();
```

#### Producer/Evaluable Pattern

Audio computations follow the framework's lazy evaluation model:

```java
// Create a filter (doesn't execute yet)
AudioPassFilter filter = new AudioPassFilter(sampleRate, c(1000), scalar(0.1), false);

// Get the computation producer
Producer<PackedCollection> result = filter.getResultant(inputSignal);

// Compile and execute
PackedCollection output = result.get().evaluate();
```

#### Temporal Processing

Audio cells implement `Temporal` for sample-by-sample processing:

```java
// Setup phase - initialize state
Runnable setup = cell.setup().get();
setup.run();

// Processing loop
Runnable tick = cell.tick().get();
for (int i = 0; i < sampleCount; i++) {
    tick.run();  // Advance one sample
}
```

## Key Concepts

### Sample Rate Management

The module uses `SamplingFeatures` for consistent sample rate handling:

```java
public class MyAudioProcessor implements SamplingFeatures {
    // Access current sample rate context
    int rate = OutputLine.sampleRate;  // Default: 44100 Hz

    // Create time-based values
    Producer<PackedCollection> quarterSecond = _250ms();
    Producer<PackedCollection> bpm128Beat = bpm(128).l(1);  // One beat at 128 BPM
}
```

### WaveData

The primary container for audio samples:

```java
// Load from file
WaveData data = WaveData.load(new File("audio.wav"));

// Access properties
int sampleRate = data.getSampleRate();
int channels = data.getChannelCount();
double duration = data.getDuration();

// Get sample data
PackedCollection samples = data.getCollection();

// Perform FFT analysis (1024 bins)
PackedCollection spectrum = data.getFrequencyDomain(position);

// Save to file
data.save(new File("output.wav"));
```

### Audio Filters

Filters implement `TemporalFactor<PackedCollection>`:

```java
// High-pass filter: cutoff=500Hz, resonance=0.1
AudioPassFilter highPass = new AudioPassFilter(
    sampleRate,
    c(500),           // Cutoff frequency producer
    scalar(0.1),      // Resonance producer
    true              // true=high-pass, false=low-pass
);

// Delay network for reverb
DelayNetwork reverb = new DelayNetwork(sampleRate, true);
reverb.setDecay(0.5);
reverb.setWet(0.3);
```

### Musical Tuning

The tone package provides music theory primitives:

```java
// Standard 12-TET tuning (A4 = 440Hz)
KeyboardTuning tuning = new DefaultKeyboardTuning();
double freq = tuning.getFrequency(WesternChromatic.A4);  // 440.0

// Create scales
Scale<WesternChromatic> cMajor = WesternScales.major(WesternChromatic.C4, 1);
Scale<WesternChromatic> aMinor = WesternScales.minor(WesternChromatic.A3, 1);

// Iterate scale notes
cMajor.forEach(note -> {
    double noteFreq = tuning.getFrequency(note);
    // Process note...
});
```

### CellList Fluent API

The `CellFeatures` interface provides a fluent API for building audio processing chains:

```java
// Method reference guide:
// w()  - Create wave cell from file
// d()  - Add delay
// f()  - Add filter
// m()  - Apply mixer/scalar
// gr() - Create grid (parallel processing)
// o()  - Set output destination
// sec() - Get runner for N seconds

CellList chain = silence()                           // Start with silence
    .and(w(0, "drums.wav"))                          // Add drum track
    .and(w(0, c(bpm(120).l(0.5)), "hihat.wav"))     // Add hi-hat with timing
    .f(i -> lp(c(2000), scalar(0.2)))               // Low-pass at 2kHz
    .d(i -> _100ms())                                // 100ms delay
    .m(i -> new ScaleFactor(0.8))                   // 80% volume
    .o(i -> new File("mix.wav"));                   // Output file

chain.sec(30).get().run();  // Render 30 seconds
```

## Example Usage

### Basic Sine Wave Generation

```java
public class SineWaveExample implements CellFeatures {
    public void generateTone() {
        // Create a 440Hz sine wave
        SineWaveCell cell = new SineWaveCell();
        cell.setFrequency(c(440.0));
        cell.setAmplitude(c(0.5));

        // Collect output
        PackedCollection output = new PackedCollection(44100);
        cell.setReceptor(protein -> a(1, p(output), protein));

        // Generate 1 second
        OperationList ops = new OperationList();
        ops.add(cell.setup());
        ops.add(sec(cell, 1.0));
        ops.get().run();

        // Save to file
        new WaveData(output, 44100).save(new File("tone.wav"));
    }
}
```

### Loading and Filtering Audio

```java
public class FilterExample implements CellFeatures {
    public void processAudio() {
        // Load source audio
        WaveData source = WaveData.load(new File("input.wav"));

        // Create processing chain
        CellList cells = w(0, "input.wav")
            .f(i -> hp(c(200), scalar(0.1)))      // Remove frequencies below 200Hz
            .f(i -> lp(c(8000), scalar(0.1)))     // Remove frequencies above 8kHz
            .d(i -> _50ms())                       // Add subtle delay
            .o(i -> new File("filtered.wav"));

        // Process entire file
        cells.sec(source.getDuration()).get().run();
    }
}
```

### Creating a Simple Synthesizer

```java
public class SynthExample implements CellFeatures {
    public void playSynth() {
        KeyboardTuning tuning = new DefaultKeyboardTuning();
        Scale<WesternChromatic> scale = WesternScales.major(WesternChromatic.C4, 1);

        // Play each note in the scale
        scale.forEach(note -> {
            double freq = tuning.getFrequency(note);

            SineWaveCell osc = new SineWaveCell();
            osc.setFrequency(c(freq));

            // Add envelope (attack-decay-sustain-release)
            // ... envelope processing
        });
    }
}
```

## Configuration

### Sample Rate

The default sample rate is 44100 Hz, defined in `OutputLine.sampleRate`. This can be overridden per-context using `SamplingFeatures`.

### FFT Analysis

WaveData uses 1024-bin FFT for frequency analysis, defined in `WaveData.FFT_BINS`.

### Note Caching

`NoteAudioProvider` uses `CacheManager` with a default limit of 200 cached note computations.

## Testing

Run the audio module tests:

```bash
export AR_HARDWARE_LIBS=/tmp/ar_libs/
export AR_HARDWARE_DRIVER=native
mvn test -pl audio
```

Test audio files should be placed in `audio/Library/` for integration tests.

### Test Data Utilities

Use `TestAudioData` for generating synthetic test audio:

```java
// Generate test signals
PackedCollection sine = TestAudioData.sineWave(440.0, 0.5, 44100);
PackedCollection noise = TestAudioData.whiteNoise(1.0, 44100);
PackedCollection impulse = TestAudioData.impulse(1000);
PackedCollection silence = TestAudioData.silence(44100);

// Measure signal properties
double rms = TestAudioData.rms(signal);
double peak = TestAudioData.peak(signal);
boolean isSilent = TestAudioData.isSilent(signal, 0.001);
```

## CellList Tick Ordering (CRITICAL)

Understanding tick ordering is essential for building correct audio pipelines. CellList uses a hierarchical parent-child model that determines execution order.

### Tick Order Determination

When `tick()` is called, temporals are collected and executed in this order:

1. **Parents' temporals** - Collected recursively, depth-first
2. **This list's cells** - Cells that implement `Temporal`
3. **Requirements** - Added via `addRequirement(Temporal)`

```
CellList Hierarchy:

    Parent CellList(s)  --  tick FIRST
          |
    Current CellList    --  tick SECOND
          |
    Requirements        --  tick LAST
```

### Controlling Tick Order

**To make A tick before B**: Make A's CellList a parent of B's CellList

```java
// CORRECT: Use parent hierarchy
CellList renderList = new CellList();
renderList.add(renderCell);  // Ticks first

CellList outputList = new CellList(renderList);  // renderList is parent
outputList.add(outputCell);  // Ticks after renderCell
```

**To make A tick after B**: Add A via `addRequirement()` to B's CellList

```java
CellList main = w(0, "input.wav").m(i -> scale(0.8));
main.addRequirement(postProcessor);  // Ticks after all cells
```

### Fluent API and Parents

Each fluent method creates a new CellList with the current list as parent:

```java
CellList pipeline = w(0, "input.wav")   // Creates base CellList
    .d(i -> _250ms())                    // Creates child with delay
    .f(i -> hp(c(500), scalar(0.1)))    // Creates child with filter
    .m(i -> scale(0.8));                 // Creates child with scale
```

This automatically creates the parent chain, ensuring tick order:
wave cell -> delay cell -> filter cell -> scale cell

## Real-Time Audio Streaming

CellList integrates with `BufferedOutputScheduler` for real-time audio output.

### Basic Real-Time Playback

```java
// Create processing pipeline
CellList pipeline = w(0, "music.wav")
    .f(i -> hp(c(80), scalar(0.1)))
    .m(i -> scale(0.8));

// Create scheduler for real-time output
BufferedOutputScheduler scheduler = pipeline.buffer("/dev/shm/audio");
scheduler.start();

// Play for 60 seconds
Thread.sleep(60000);

// Cleanup
scheduler.stop();
pipeline.destroy();
```

### BufferedOutputScheduler Features

- **Adaptive timing**: Automatically adjusts sleep durations to maintain real-time
- **Buffer safety**: Divides buffer into groups and pauses to prevent overruns
- **Degraded mode**: Detects when processing can't keep up and continues without pause
- **Suspend/Resume**: User-initiated pause/resume for playback control

### Monitoring Performance

```java
BufferedOutputScheduler.enableVerbose = true;  // Enable logging

scheduler.start();

// Monitor in separate thread
while (!stopped) {
    System.out.println("Gap: " + scheduler.getRenderingGap() + "ms");
    System.out.println("Buffer: " + scheduler.getBufferGapPercent() + "%");
    System.out.println("Degraded: " + scheduler.isDegradedMode());
    Thread.sleep(1000);
}
```

## AudioLibrary System

The `AudioLibrary` class provides centralized management for audio sample collections with asynchronous loading, analysis, and similarity computation.

### Key-Identifier Architecture

**CRITICAL**: The library uses a two-level identification scheme:

| Concept | Method | Returns | Purpose |
|---------|--------|---------|---------|
| **Key** | `WaveDataProvider.getKey()` | File path | Display, file access |
| **Identifier** | `WaveDataProvider.getIdentifier()` | MD5 hash | Content deduplication, storage |

**Why two identifiers?**
- Same audio content = same identifier, even at different file paths
- Protobuf persistence stores only identifiers (content-based)
- File paths are resolved at runtime via `library.find(identifier)`

### Resolving Identifiers to File Paths

When loading from protobuf, you need BOTH the data AND a file tree:

```java
// 1. Create library with file tree (directory of audio files)
AudioLibrary library = new AudioLibrary(new File("/path/to/samples"), 44100);

// 2. Load pre-computed data from protobuf
AudioLibraryPersistence.loadLibrary(library, "/path/to/library");

// 3. Resolve identifier to file path
for (WaveDetails details : library.getAllDetails()) {
    String identifier = details.getIdentifier();  // MD5 hash

    WaveDataProvider provider = library.find(identifier);
    if (provider != null) {
        String filePath = provider.getKey();  // Actual file path!
        System.out.println("File: " + filePath);
    }
}
```

### Internal Data Structures

- `identifiers` map: key (file path) → identifier (MD5 hash)
- `info` map: identifier → WaveDetails

For detailed documentation, see [Audio Library Documentation](docs/AUDIO_LIBRARY.md).


## See Also

- [Audio Library Documentation](docs/AUDIO_LIBRARY.md) - Detailed AudioLibrary system docs
- [Compose Module](../compose/README.md) - Protobuf persistence, PrototypeDiscovery
- [Graph Module](../graph/README.md) - Cell and temporal abstractions
- [Time Module](../time/README.md) - Temporal processing
- [Collect Module](../collect/README.md) - PackedCollection operations
- [Hardware Module](../hardware/README.md) - GPU acceleration
- [Internal Docs: CellList Architecture](../docs/internals/celllist-architecture.md) - Detailed architecture
- [Internal Docs: CellList Tick Ordering](../docs/internals/celllist-tick-ordering.md) - Tick order details
- [Internal Docs: CellList Fluent API](../docs/internals/celllist-fluent-api.md) - Fluent API reference
- [Internal Docs: CellList Real-Time Streaming](../docs/internals/celllist-realtime-streaming.md) - Streaming details
