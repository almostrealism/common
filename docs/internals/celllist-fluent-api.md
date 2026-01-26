# CellList Fluent API

## Overview

The CellList fluent API enables method chaining to build complex audio processing
pipelines in a readable, declarative style. The API is primarily defined in
`CellFeatures.java` with instance methods on `CellList`.

## Core Pattern

Every fluent method creates a **new CellList** with the current list as a parent:

```java
CellList result = source.someMethod(args);
// result.getParents() contains [source]
```

This maintains proper tick ordering automatically.

## Wave Cell Creation

### w(index, file) - Create Wave Cell

```java
CellList drums = w(0, "drums.wav");      // Load from string path
CellList synth = w(0, new File("synth.wav"));  // Load from File

// With multiple sources
CellList multi = cells(
    w(0, "drums.wav"),
    w(1, "bass.wav"),
    w(2, "keys.wav")
);
```

Parameters:
- `index`: Channel/track index for identification
- `file`: Path to WAV file

## Signal Processing Methods

### d(delay) - Add Delay

```java
// Constant delay
CellList delayed = source.d(i -> _250ms());

// Variable delay based on index
CellList varied = source.d(i -> scalar(i * 0.1));

// With scale factor
CellList scaled = source.d(
    i -> _500ms(),           // delay amount
    i -> scalar(0.5)         // feedback/scale
);
```

### f(filter) - Add Filter

```java
// High-pass filter at 500Hz
CellList hp = source.f(i -> hp(c(500), scalar(0.1)));

// Low-pass filter
CellList lp = source.f(i -> lp(c(2000), scalar(0.3)));

// Band-pass
CellList bp = source.f(i -> bp(c(1000), scalar(0.5)));
```

### m(adapter) - Add Mixer/Adapter

```java
// Simple scale
CellList scaled = source.m(i -> scale(0.8));

// Index-based mixing
CellList mixed = source.m(i -> scale(1.0 / (i + 1)));

// With transmission gene
CellList trans = source.m(
    i -> new MixerCell(),
    i -> Gene.of(3, pos -> new ScaleFactor(0.3))
);
```

## Output Methods

### o(file) - Set Output File

```java
CellList output = source
    .f(i -> hp(c(500), scalar(0.1)))
    .o(i -> new File("output_" + i + ".wav"));
```

### om(file) - Set Mono Output

```java
// Force mono output
CellList mono = source.om(i -> new File("mono_output.wav"));
```

### csv(file) - CSV Data Output

```java
// Output sample data as CSV for analysis
CellList csv = source.csv(i -> new File("data_" + i + ".csv"));
```

## Combining Lists

### and(list) - Combine Lists

```java
CellList drums = w(0, "drums.wav");
CellList synth = w(0, "synth.wav");

// Combine into single list
CellList combined = drums.and(synth);
// combined has both drum and synth cells
```

### cells(lists...) - Create from Multiple Lists

```java
CellList all = cells(
    w(0, "drums.wav").f(i -> hp(c(200), scalar(0.1))),
    w(0, "bass.wav").f(i -> lp(c(400), scalar(0.2))),
    w(0, "lead.wav")
);
```

### sum() - Sum All Outputs

```java
CellList mixed = source1.and(source2).and(source3).sum();
// All inputs summed to single output
```

## Grid and Routing

### gr(duration, segments, choices) - Create Grid

```java
// Create grid with 4 segments over 10 seconds
CellList grid = source.gr(10.0, 4, i -> i % 2);
// Routes to different destinations based on choice function
```

### grid(duration, segments, choices) - Weighted Grid

```java
// Grid with weighted routing
CellList weighted = source.grid(10.0, 8, i -> 0.5 + (i * 0.1));
```

### map(dest) - Map to Destinations

```java
CellList mapped = source.map(i -> new ProcessorCell());
// Each source cell maps to a processor
```

### branch(dests...) - Multiple Branches

```java
CellList[] branches = source.branch(
    i -> new DelayCell(),
    i -> new ReverbCell(),
    i -> new ChorusCell()
);
// Creates 3 parallel branches
```

## Execution Methods

### sec(seconds) - Execute for Duration

```java
// Run for 30 seconds
source.sec(30).get().run();

// With reset after completion
source.sec(30, true).get().run();
```

### min(minutes) - Execute for Minutes

```java
// Run for 5 minutes
source.min(5).get().run();
```

### iter(iterations) - Execute N Iterations

```java
// Run 1000 tick iterations
source.iter(1000).get().run();
```

## Buffer Methods

### buffer(location) - Shared Memory Buffer

```java
BufferedOutputScheduler scheduler = source.buffer("/dev/shm/audio");
scheduler.start();

// Later
scheduler.stop();
```

### buffer(destination) - Buffer to Collection

```java
PackedCollection output = new PackedCollection(44100);
TemporalRunner runner = source.buffer(p(output));
runner.get().run();
```

### toLineOperation() - Convert to Line Operation

```java
AudioLineOperation op = source.toLineOperation();
// Use with BufferedOutputScheduler.create()
```

## Advanced Methods

### poly(decision) - Polymorphic Routing

```java
CellList poly = source.poly(i ->
    conditional(gt(input, c(0.5)), c(0), c(1))
);
// Dynamic routing based on signal level
```

### mixdown(seconds) - Create Mixdown

```java
CellList mix = source.mixdown(60.0);
// Prepares 60-second mixdown
```

### addSetup(setup) - Add Setup Operation

```java
CellList withSetup = source.addSetup(() -> () -> {
    System.out.println("Initializing...");
});
```

### addRequirement(temporal) - Add Post-Tick Requirement

```java
CellList withPost = source.addRequirement(postProcessor);
// postProcessor ticks after source
```

### addData(collections) - Track Data for Lifecycle

```java
PackedCollection buffer = new PackedCollection(1024);
CellList tracked = source.addData(buffer);
// buffer.destroy() called when tracked.destroy() called
```

## Method Chaining Example

Complete pipeline construction:

```java
CellList pipeline = w(0, "input.wav")
    // Stage 1: Input processing
    .d(i -> _50ms())                      // Small delay for latency compensation

    // Stage 2: Filtering
    .f(i -> hp(c(80), scalar(0.1)))      // Remove sub-bass rumble
    .f(i -> lp(c(15000), scalar(0.05)))  // Remove high frequency noise

    // Stage 3: Dynamics
    .m(i -> compressor(c(0.5), c(2.0)))  // Compress dynamics

    // Stage 4: Effects
    .d(i -> _250ms(), i -> scalar(0.3))  // Delay with feedback

    // Stage 5: Output
    .m(i -> scale(0.9))                   // Final level
    .o(i -> new File("processed.wav"));   // Output file

// Execute
pipeline.sec(60).get().run();  // Process 60 seconds
```

## Parent Chain Visualization

Each method creates a new CellList with parent:

```
w(0, "input.wav")
    │
    └── CellList[WaveCell]
            │
            └── .d(delay)
                    │
                    └── CellList[DelayCell] ─ parent: [WaveCell list]
                            │
                            └── .f(filter)
                                    │
                                    └── CellList[FilterCell] ─ parent: [DelayCell list]
                                            │
                                            └── .m(mixer)
                                                    │
                                                    └── CellList[MixerCell] ─ parent: [FilterCell list]
```

Tick order: WaveCell → DelayCell → FilterCell → MixerCell

## Helper Methods in CellFeatures

### Delay Helpers

```java
Producer<PackedCollection> _50ms();
Producer<PackedCollection> _100ms();
Producer<PackedCollection> _250ms();
Producer<PackedCollection> _500ms();
Producer<PackedCollection> _1s();
```

### Filter Creation

```java
Factor<PackedCollection> hp(Producer freq, Producer resonance);
Factor<PackedCollection> lp(Producer freq, Producer resonance);
Factor<PackedCollection> bp(Producer freq, Producer resonance);
```

### Constants

```java
Producer<PackedCollection> c(double value);
Producer<PackedCollection> scalar(double value);
Producer<PackedCollection> v(double... values);
```

## Related Files

- `CellFeatures.java` - Static method definitions
- `CellList.java` - Instance method implementations
- `Cells.java` - Interface definition
- `WaveCell.java` - Wave file cell
- `FilteredCell.java` - Filter cell base

## See Also

- [CellList Architecture](celllist-architecture.md) - Full architecture
- [CellList Tick Ordering](celllist-tick-ordering.md) - Execution order
