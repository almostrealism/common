# AR-Time Module

**Temporal/time-series processing framework with hardware-accelerated signal processing.**

## Overview

The `ar-time` module provides abstractions and accelerated computations for:
- **Temporal synchronization** - coordinating sequential operations
- **Time-series data management** - storing, interpolating, querying time-indexed values
- **Signal processing** - FFT, filtering, frequency operations
- **Time-based iteration** - running operations with timing control

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

## Integration with Other Modules

- **ar-geometry** (parent): Uses geometric operations
- **ar-hardware**: All computations compile to GPU kernels
- **ar-ml**: Time-series operations in sequential models
- **Audio processing**: Real-time signal processing pipelines

## Dependencies

```xml
<dependency>
    <groupId>org.almostrealism</groupId>
    <artifactId>ar-time</artifactId>
    <version>0.72</version>
</dependency>
```

## License

Licensed under the Apache License, Version 2.0.
