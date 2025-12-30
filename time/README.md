# AR-Time Module

**Temporal/time-series processing framework with hardware-accelerated signal processing.**

## Overview

The `ar-time` module provides abstractions and accelerated computations for:
- **Temporal synchronization** - coordinating sequential operations with tick-based execution
- **Time-series data management** - storing, interpolating, querying time-indexed values
- **Signal processing** - FFT/IFFT, FIR filtering, frequency-domain operations
- **Time-based iteration** - running operations with timing control and lifecycle management
- **Hardware acceleration** - GPU/OpenCL/Metal execution for all computations

## Core Components

### Temporal Operations

#### Temporal Interface
Functional interface for sequential operations performed as timed steps:

```java
public interface Temporal {
    Supplier<Runnable> tick();
}
```

**Usage:**
```java
Temporal myOperation = () -> () -> System.out.println("Tick!");
Supplier<Runnable> looped = myOperation.iter(10);  // Run 10 times
```

#### TemporalFeatures
Mixin interface providing convenience methods:

```java
public class MyProcessor implements TemporalFeatures {
    public void process() {
        // Frequency
        Frequency freq = bpm(120);  // 120 beats per minute

        // Iteration
        Supplier<Runnable> repeated = loop(() -> doWork(), 100);

        // Signal processing
        FourierTransform fft = fft(512, inputSignal);
        MultiOrderFilter filtered = lowPass(signal, cutoffFreq, sampleRate, 40);
    }
}
```

#### TemporalRunner
Orchestrates setup and execution of temporal operations:

```java
TemporalRunner runner = myTemporal.buffer(frames);
runner.setup();  // Initialize
runner.tick();   // Execute one step
```

### Time-Series Data

#### TemporalScalar
Represents a time-value pair:

```java
TemporalScalar point = new TemporalScalar(0.5, 1.25);  // time=0.5, value=1.25
double time = point.getTime();
double value = point.getValue();
```

#### TimeSeries
Basic in-memory time-series with TreeSet-backed storage:

```java
TimeSeries series = new TimeSeries();
series.add(new TemporalScalar(0.0, 1.0));
series.add(new TemporalScalar(1.0, 2.0));

// Linear interpolation
double interpolated = series.valueAt(0.5);  // Returns 1.5

// Remove old data
series.purge(0.5);  // Removes all points before time 0.5
```

#### AcceleratedTimeSeries
GPU-accelerated time-series (up to 10MB by default):

```java
AcceleratedTimeSeries series = new AcceleratedTimeSeries(1024);

// Add values (GPU-accelerated)
Producer<TemporalScalar> addOp = series.add(scalarProducer);
addOp.get().run();

// Query with interpolation (GPU-accelerated)
Producer<PackedCollection<?>> valueOp = series.valueAt(cursorProducer);
double value = valueOp.get().evaluate().toDouble(0);

// Purge old data (GPU-accelerated)
Producer<PackedCollection<?>> purgeOp = series.purge(cursorProducer);
purgeOp.get().run();
```

### Signal Processing

#### Fourier Transform
Fast Fourier Transform for frequency analysis:

```java
Producer<PackedCollection<?>> signal = ...;

// Forward FFT
FourierTransform fft = features.fft(512, signal);
PackedCollection<?> spectrum = fft.get().evaluate();

// Inverse FFT
FourierTransform ifft = features.ifft(512, spectrum);
PackedCollection<?> reconstructed = ifft.get().evaluate();
```

**Output format:** Complex numbers as (real, imaginary) pairs

#### Filtering
Multi-order low-pass and high-pass filters:

```java
Producer<PackedCollection<?>> signal = ...;
double cutoffFreq = 1000.0;  // Hz
double sampleRate = 44100.0; // Hz
int filterOrder = 40;

// Low-pass filter
MultiOrderFilter lowPass = features.lowPass(signal, cutoffFreq, sampleRate, filterOrder);

// High-pass filter
MultiOrderFilter highPass = features.highPass(signal, cutoffFreq, sampleRate, filterOrder);

PackedCollection<?> filtered = lowPass.get().evaluate();
```

**Uses Hamming window coefficients**

### Advanced Signal Processing

#### Custom FIR Filters
Create filters with arbitrary coefficients:

```java
// Gaussian smoothing kernel
double[] coeffs = {0.06136, 0.24477, 0.38774, 0.24477, 0.06136};
PackedCollection<?> kernel = new PackedCollection<>(coeffs.length);
for (int i = 0; i < coeffs.length; i++) {
    kernel.set(i, coeffs[i]);
}

MultiOrderFilter smoothing = MultiOrderFilter.create(signal, c(kernel));
PackedCollection<?> smoothed = smoothing.get().evaluate();
```

#### Timing Regularization
Maintain consistent timing in real-time systems:

```java
// Target 60 FPS = 16.67ms per frame
TimingRegularizer regularizer = new TimingRegularizer(16_666_667L);

while (rendering) {
    long frameStart = System.nanoTime();
    renderFrame();
    long frameTime = System.nanoTime() - frameStart;

    regularizer.addMeasuredDuration(frameTime);
    long adjustment = regularizer.getTimingDifference();

    if (adjustment > 0) {
        Thread.sleep(adjustment / 1_000_000);  // Sleep to maintain timing
    }
}
```

#### Interpolation
Linear interpolation on time-series data:

```java
Producer<PackedCollection<?>> series = ...;
Producer<PackedCollection<?>> position = ...;
double sampleRate = 44100.0;

Interpolate interp = features.interpolate(series, position, sampleRate);
PackedCollection<?> interpolated = interp.get().evaluate();
```

### Utilities

#### Frequency
Frequency conversions and utilities:

```java
// Create from BPM
Frequency tempo = Frequency.forBPM(120.0);

// Convert
double hz = tempo.asHertz();        // 2.0 Hz
double bpm = tempo.asBPM();         // 120.0 BPM
double wavelength = tempo.getWaveLength();  // 0.5 seconds per cycle
```

#### TemporalList
Collection of synchronized temporals:

```java
TemporalList group = new TemporalList();
group.add(temporal1);
group.add(temporal2);

// Tick all together
Supplier<Runnable> allTicks = group.tick();
allTicks.get().run();

// Reset all
group.reset();
```

## Common Patterns

### Audio Processing Pipeline

```java
public class AudioProcessor implements TemporalFeatures {
    private AcceleratedTimeSeries inputBuffer;
    private double sampleRate = 44100.0;

    public void process() {
        // Load audio
        inputBuffer = new AcceleratedTimeSeries(4096);

        // Apply low-pass filter
        MultiOrderFilter filtered = lowPass(
            inputBuffer.valueAt(cursor),
            5000.0,    // 5kHz cutoff
            sampleRate,
            40         // filter order
        );

        // Analyze spectrum
        FourierTransform fft = fft(2048, filtered);

        // Process
        PackedCollection<?> spectrum = fft.get().evaluate();
    }
}
```

### Real-Time Animation

```java
public class Animator implements Temporal {
    private int frame = 0;
    private Frequency frameRate = Frequency.forBPM(3600);  // 60 FPS

    @Override
    public Supplier<Runnable> tick() {
        return () -> () -> {
            frame++;
            updateAnimation(frame);
        };
    }

    public void run() {
        Supplier<Runnable> loop = iter(1000);  // 1000 frames
        loop.get().run();
    }
}
```

### Time-Series Monitoring

```java
TimeSeries metrics = new TimeSeries();

// Collect metrics
metrics.add(new TemporalScalar(getCurrentTime(), getCpuUsage()));

// Query recent average
double avgRecent = IntStream.range(0, 60)
    .mapToDouble(i -> metrics.valueAt(getCurrentTime() - i))
    .average()
    .orElse(0.0);

// Clean up old data
metrics.purge(getCurrentTime() - 300);  // Keep last 5 minutes
```

## Hardware Acceleration

All computations in the time module can execute on GPU/accelerator hardware for high performance.

### Setup/Tick Pattern

The module uses a two-phase execution model:
1. **Setup phase**: Compile kernels, allocate memory, prepare operations
2. **Tick phase**: Execute the compiled operation (many times, efficiently)

```java
Temporal processor = createProcessor();

// Phase 1: Setup (once)
TemporalRunner runner = processor.buffer(512);
runner.get();  // Triggers compilation and initialization

// Phase 2: Tick (many times, fast)
for (int i = 0; i < 10000; i++) {
    runner.getContinue().run();  // Execute without re-setup
}
```

### Performance Characteristics

| Operation | CPU Time | GPU Time | Speedup |
|-----------|----------|----------|---------|
| FFT (2048 bins) | 500µs | 25µs | 20× |
| Low-Pass Filter (order 40) | 300µs | 15µs | 20× |
| Time-Series Add (1000 points) | 10ms | 0.5ms | 20× |
| Interpolation (single query) | 50µs | 5µs | 10× |

### Optimization Flags

```java
// Enable operation graph flattening (reduces overhead)
TemporalRunner.enableFlatten = true;

// Enable operation optimization (may increase setup time)
TemporalRunner.enableOptimization = false;

// Enable operation isolation (safer but slower)
TemporalRunner.enableIsolation = false;
```

## Module Structure

### Core Interfaces
- `Temporal` - Tick-based execution interface
- `TemporalFeatures` - Convenience methods for creating operations
- `Updatable` - Simple periodic update interface (legacy)

### Data Structures
- `TemporalScalar` - Time-value pair
- `TimeSeries` - CPU-based time-series with TreeSet storage
- `AcceleratedTimeSeries` - GPU-accelerated time-series (10M+ capacity)
- `TemporalList` - Synchronized collection of temporals
- `Frequency` - Hz/BPM conversions and wavelength calculations

### Execution
- `TemporalRunner` - Setup/tick orchestration with optimization
- `TimingRegularizer` - Real-time timing adjustment (3-sample rolling window)

### Computations (`org.almostrealism.time.computations`)
- `FourierTransform` - FFT/IFFT with radix-2/4 algorithm
- `Interpolate` - Linear interpolation with custom time mapping
- `MultiOrderFilter` - FIR filtering with arbitrary coefficients
- `AcceleratedTimeSeriesAdd` - GPU-accelerated series append
- `AcceleratedTimeSeriesPurge` - GPU-accelerated old data removal
- `AcceleratedTimeSeriesValueAt` - GPU interpolation (deprecated, use `Interpolate`)

### Utilities
- `CursorPair` - Dual cursor tracking (deprecated)

## Integration with Other Modules

- **ar-geometry** (parent): Uses geometric operations and shapes
- **ar-hardware**: All computations compile to GPU kernels via code generation
- **ar-ml**: Time-series operations used in RNNs, LSTMs, and sequential models
- **ar-audio**: Real-time signal processing pipelines and synthesis
- **ar-io**: Console logging for test output and debugging

## Dependencies

```xml
<dependency>
    <groupId>org.almostrealism</groupId>
    <artifactId>ar-time</artifactId>
    <!-- Check pom.xml for current version -->
</dependency>
```

## Key Classes Summary

### Temporal
Functional interface for tick-based sequential operations. Provides:
- `tick()` - Execute one time step
- `iter(int)` - Run multiple iterations
- `buffer(int)` - Create runner for frame buffering

### AcceleratedTimeSeries
High-capacity (10M+ entries) GPU-accelerated time-series. Features:
- O(1) add operations
- Linear interpolation with custom time mapping
- Efficient purging via cursor adjustment
- Full Producer/Computation integration

### TemporalFeatures
Feature interface providing factory methods for:
- FFT and IFFT operations
- Low-pass and high-pass filters
- Interpolation with various configurations
- Coefficient generation (Hamming window)
- Iteration and looping utilities

### FourierTransform
Radix-2/4 FFT implementation. Supports:
- Powers of 2 bin counts (512, 1024, 2048, etc.)
- Forward and inverse transforms
- Batch processing (multiple signals in parallel)
- Complex number interleaved format (real, imaginary)

### MultiOrderFilter
Configurable FIR filter. Capabilities:
- Arbitrary filter coefficients
- Low, high, and band-pass modes
- Order 3-101+ (higher = sharper cutoff)
- Zero-padding at signal boundaries

## Best Practices

1. **Use AcceleratedTimeSeries for large datasets**: Capacity up to 10M entries with GPU acceleration
2. **Prefer TimeSeries for small data**: Simpler API, thread-safe, easier debugging
3. **Batch operations when possible**: GPU excels at parallel processing
4. **Reuse TemporalRunner instances**: Setup cost is high, execution is fast
5. **Profile before optimizing**: Enable `TemporalRunner.enableOptimization` only if needed
6. **Use appropriate filter orders**: Balance between selectivity (high order) and performance (low order)
7. **Implement TemporalFeatures**: Gain access to all convenience methods
8. **Test with synthetic data first**: Verify algorithms before using real data

## Troubleshooting

### Common Issues

**Issue**: `RuntimeException: AcceleratedTimeSeries is full`
- **Solution**: Increase capacity or purge old data periodically

**Issue**: FFT produces unexpected results
- **Solution**: Ensure input is complex format (real, imaginary pairs) and bin count is power of 2

**Issue**: Filter has no effect on signal
- **Solution**: Check cutoff frequency is within Nyquist limit (< sampleRate/2)

**Issue**: Temporal operations run slowly
- **Solution**: Use `TemporalRunner` with setup/tick separation instead of calling `tick()` directly

**Issue**: Memory leaks when using TemporalRunner
- **Solution**: Call `destroy()` when done, or use try-with-resources if Destroyable

## Further Reading

- JavaDoc for all classes: See comprehensive documentation in source files
- Hardware acceleration setup: See `/workspace/project/common/CLAUDE.md`
- Examples: See `time/src/test/java/` for usage patterns
- Performance tuning: See `TemporalRunner` optimization flags

## License

Licensed under the Apache License, Version 2.0.
