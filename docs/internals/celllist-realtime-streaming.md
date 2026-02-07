# CellList Real-Time Streaming

## Overview

CellList supports real-time audio streaming through integration with
`BufferedOutputScheduler` and audio line abstractions. This document covers
the architecture and patterns for real-time audio processing.

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                    Real-Time Audio Pipeline                      │
└─────────────────────────────────────────────────────────────────┘

    InputLine                BufferedOutputScheduler              OutputLine
    (microphone)                   │                              (speakers)
         │                         │                                   │
         └──── read() ────>  ┌─────┴─────┐  <──── write() ────────────┘
                             │           │
                             │ CellList  │
                             │ Pipeline  │
                             │           │
                             └───────────┘
                                   │
                                   v
                             TemporalRunner
                             (tick loop)
```

## BufferedOutputScheduler

### Creation

```java
// From CellList directly
BufferedOutputScheduler scheduler = cellList.buffer("/dev/shm/audio");

// With explicit input/output lines
BufferedOutputScheduler scheduler = BufferedOutputScheduler.create(
    inputLine,   // InputLine for reading (can be null)
    outputLine,  // OutputLine for writing
    cellList     // Processing pipeline
);
```

### Lifecycle

```java
// Start processing
scheduler.start();

// Processing runs in background on executor thread
// ...

// Stop processing
scheduler.stop();

// Cleanup
cellList.destroy();
```

### Buffer Safety Model

The scheduler divides the output buffer into groups:

```
Output Buffer (e.g., 4096 frames)
┌─────────┬─────────┬─────────┬─────────┐
│ Group 0 │ Group 1 │ Group 2 │ Group 3 │
│ (1024)  │ (1024)  │ (1024)  │ (1024)  │
└─────────┴─────────┴─────────┴─────────┘
     ^                   ^
     │                   │
   Write              Read
   Position          Position
```

**Safety Rule**: After filling a group, the scheduler pauses until the read
position is in a "safe" group (far enough from write position).

### Timing Control

```java
// Configuration
BufferedOutputScheduler.timingPad = -2;  // Adjust sleep timing
BufferedOutputScheduler.enableVerbose = true;  // Debug logging
BufferedOutputScheduler.logRate = 1024;  // Log every N cycles

// Degraded mode detection
boolean degraded = scheduler.isDegradedMode();
// true when processing can't keep up with real-time
```

## CellList.toLineOperation()

Converts a CellList to an `AudioLineOperation` for use with schedulers:

```java
public AudioLineOperation toLineOperation() {
    return (in, out, frames) -> buffer(out);
}
```

Usage:

```java
CellList pipeline = w(0, "input.wav").f(i -> hp(c(500), scalar(0.1)));

AudioLineOperation operation = pipeline.toLineOperation();

BufferedOutputScheduler scheduler = BufferedOutputScheduler.create(
    inputLine,
    outputLine,
    operation
);
```

## AudioBuffer

`AudioBuffer` provides intermediate storage between input, processing, and output:

```java
AudioBuffer buffer = AudioBuffer.create(sampleRate, frameCount);

// Access buffers
PackedCollection inputBuffer = buffer.getInputBuffer();
PackedCollection outputBuffer = buffer.getOutputBuffer();

// Buffer details
BufferDetails details = buffer.getDetails();
int frames = details.getFrames();
double duration = details.getDuration();
```

## Real-Time Processing Loop

### BufferedOutputScheduler.run()

```java
protected void run() {
    while (!stopped) {
        // Handle user-initiated suspend
        synchronized (suspendLock) {
            while (suspended && !stopped) {
                suspendLock.wait();
            }
        }

        // Check auto-resume from buffer safety pause
        attemptAutoResume();

        if (!paused) {
            // Execute the processing pipeline
            next.run();  // read -> process -> write
            count++;

            // Pause after filling each buffer group
            if (getRenderedFrames() % groupSize == 0) {
                if (!degradedMode) {
                    pause();
                }
            }
        }

        // Adaptive sleep to maintain timing
        Thread.sleep(getTarget());
    }
}
```

### Operations Pipeline

```java
protected Supplier<Runnable> getOperations() {
    OperationList operations = new OperationList("BufferedOutputScheduler");

    // 1. Read from input line
    if (input != null) {
        operations.add(input.read(p(buffer.getInputBuffer())));
    }

    // 2. Process through CellList (tick)
    operations.add(process.tick());

    // 3. Write to output line
    if (output != null) {
        operations.add(output.write(p(buffer.getOutputBuffer())));
    }

    return operations;
}
```

## Shared Memory Audio Lines

### SharedMemoryAudioLine

Uses shared memory for IPC (inter-process communication):

```java
SharedMemoryAudioLine line = new SharedMemoryAudioLine("/dev/shm/audio");

// Use as both input and output
BufferedOutputScheduler scheduler = cellList.buffer("/dev/shm/audio");
```

### Implementation Details

- Uses memory-mapped files for zero-copy data transfer
- Suitable for communication between processes
- Low latency compared to socket-based IPC

## Frame-by-Frame vs Buffer Processing

### Frame-by-Frame (Traditional)

```java
// Each tick processes one frame
TemporalRunner runner = new TemporalRunner(cells, 1);
for (int i = 0; i < totalFrames; i++) {
    runner.getContinue().run();
}
```

### Buffer Processing (Efficient)

```java
// Each tick processes multiple frames
int bufferSize = 512;
TemporalRunner runner = new TemporalRunner(cells, bufferSize);

for (int i = 0; i < totalFrames / bufferSize; i++) {
    runner.getContinue().run();  // Process 512 frames at once
}
```

Buffer processing is more efficient due to:
- Reduced kernel launch overhead
- Better cache utilization
- Fewer setup/teardown operations

## Timing and Synchronization

### Key Metrics

```java
// Rendered count (buffer cycles completed)
long count = scheduler.getRenderedCount();

// Rendered frames
long frames = scheduler.getRenderedFrames();

// Real time elapsed (adjusted for pauses)
long realTime = scheduler.getRealTime();

// Rendered audio duration
long renderedTime = scheduler.getRenderedTime();

// Gap between rendered and real time
long gap = scheduler.getRenderingGap();
// Positive = ahead of playback (good)
// Negative = behind playback (bad)
```

### Buffer Gap Monitoring

```java
// Gap in frames
int bufferGap = scheduler.getBufferGap();

// Gap as percentage
double gapPercent = scheduler.getBufferGapPercent();
// 25-75% = normal
// <25% = risk of underrun
// >75% = risk of overrun
```

## Suspend/Resume

### User-Initiated

```java
// Pause playback (user pressed pause button)
scheduler.suspend();

// Check if suspended
boolean isPaused = scheduler.isSuspended();

// Resume playback
scheduler.unsuspend();
```

### Internal (Buffer Safety)

Internal pause/resume is handled automatically by the scheduler to prevent
buffer overwrites.

## Common Patterns

### Basic Real-Time Playback

```java
// Create pipeline
CellList pipeline = w(0, "music.wav")
    .f(i -> hp(c(80), scalar(0.1)))
    .m(i -> scale(0.8));

// Create shared memory output
SharedMemoryAudioLine output = new SharedMemoryAudioLine("/dev/shm/playback");

// Start scheduler
BufferedOutputScheduler scheduler = BufferedOutputScheduler.create(
    null,  // No input
    output,
    pipeline
);
scheduler.start();

// Play for 60 seconds
Thread.sleep(60000);

// Stop
scheduler.stop();
pipeline.destroy();
```

### Real-Time Effects Processing

```java
// Create effects chain
CellList effects = new CellList();
effects.add(inputCell);  // From microphone
effects.add(reverbCell);
effects.add(outputCell);

// Create with input and output lines
BufferedOutputScheduler scheduler = BufferedOutputScheduler.create(
    microphoneLine,   // InputLine
    speakersLine,     // OutputLine
    effects
);
scheduler.start();
```

### Monitoring Performance

```java
// Enable verbose logging
BufferedOutputScheduler.enableVerbose = true;
BufferedOutputScheduler.logRate = 100;  // Log every 100 cycles

scheduler.start();

// Monitor in separate thread
while (scheduler.isRunning()) {
    System.out.println("Gap: " + scheduler.getRenderingGap() + "ms");
    System.out.println("Buffer: " + scheduler.getBufferGapPercent() + "%");
    System.out.println("Degraded: " + scheduler.isDegradedMode());
    Thread.sleep(1000);
}
```

## Performance Considerations

### Buffer Size Selection

```java
// Smaller buffers = lower latency, more CPU usage
int smallBuffer = 128;  // ~3ms at 44.1kHz

// Larger buffers = higher latency, less CPU usage
int largeBuffer = 2048;  // ~46ms at 44.1kHz

// Common default
int defaultBuffer = 512;  // ~12ms at 44.1kHz
```

### Batch Count Configuration

```java
// BufferDefaults.batchCount determines frames per tick
BufferDefaults.batchCount = 8;  // Default
// Higher = more efficient, higher latency
// Lower = more responsive, more CPU
```

### Group Configuration

```java
// BufferDefaults.groups determines pause frequency
BufferDefaults.groups = 4;  // Default
// More groups = more frequent pauses, smoother timing
// Fewer groups = less overhead, potentially choppier
```

## Troubleshooting

### Underruns (Glitches/Clicks)

**Symptoms**: Audio clicks, gaps, or glitches

**Causes**:
- Processing too slow to keep up
- Buffer too small
- System load too high

**Solutions**:
1. Increase buffer size
2. Reduce pipeline complexity
3. Check for non-compiled operations
4. Monitor `getRenderingGap()` - should be positive

### Overruns

**Symptoms**: Repeated audio segments

**Causes**:
- Write position catching up to read position
- Buffer groups not pausing properly

**Solutions**:
1. Verify `isSafeGroup()` logic
2. Increase group count
3. Check timing pad value

### High Latency

**Symptoms**: Noticeable delay between input and output

**Causes**:
- Buffer size too large
- Too many buffer groups
- Long processing chain

**Solutions**:
1. Reduce buffer size (at cost of stability)
2. Optimize processing pipeline
3. Use compiled operations where possible

## Related Files

- `BufferedOutputScheduler.java` - Main scheduler
- `OutputLine.java` - Output interface
- `InputLine.java` - Input interface
- `SharedMemoryAudioLine.java` - Shared memory implementation
- `AudioBuffer.java` - Intermediate buffer
- `BufferDefaults.java` - Configuration defaults
- `TimingRegularizer.java` - Adaptive timing

## See Also

- [CellList Architecture](celllist-architecture.md) - Core architecture
- [CellList Tick Ordering](celllist-tick-ordering.md) - Execution order
