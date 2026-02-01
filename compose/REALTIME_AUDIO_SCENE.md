# Real-Time AudioScene Architecture

## Overview

AudioScene renders audio in two phases: (1) pattern arrangement via
`PatternSystemManager`/`PatternLayerManager`, producing raw audio for each
channel, and (2) effects processing via `MixdownManager`, which constructs a
`CellList` pipeline for mixing, delay, reverb, etc.

The original architecture rendered all patterns during the *setup* phase,
before any audio output. This blocked real-time streaming. The solution was
to move pattern rendering into the *tick* phase via `PatternRenderCell`, a
`BatchedCell` subclass that renders one buffer of pattern audio per batch.

## Unified Method Hierarchy

AudioScene uses a single method hierarchy for building the cell pipeline,
shared by both offline and real-time paths:

```
getCells(output, channels, bufferSize, frameSupplier)
  -> getPatternCells(output, channels, audioChannel, bufferSize, frameSupplier, setup)
       -> getPatternChannel(channel, bufferSize, frameSupplier, setup)
            -> PatternRenderCell + efx.apply(channel, producer, duration, setup)
```

Both offline and real-time paths use `PatternRenderCell`. They differ only in
buffer size and frame supplier:

- **Offline** (`runner`): `bufferSize = totalFrames`, `frameSupplier = () -> 0`.
  Pattern rendering fires once on the first batch during setup via
  `renderNow()`, equivalent to the former setup-phase rendering.

- **Real-time** (`runnerRealTime`): `bufferSize = 1024`, dynamic `frameSupplier`.
  The tick phase wraps the cell pipeline in `loop(frameOp, bufferSize)`.
  `BatchedCell.tick()` uses `Periodic` counting to trigger rendering once
  per buffer within the loop.

## Key Components

- **`BatchedCell`** (`graph` module): Abstract base class that adapts
  per-sample `tick()` to per-buffer `renderBatch()`. Uses `Periodic`
  computation for compilable counter-based triggering when the render body
  is a `Computation`, or a Java fallback otherwise.

- **`Periodic`** (`hardware` module): `OperationComputationAdapter` that
  generates counter-based conditional execution in compiled code. Wraps an
  atom computation and a period N, maintaining a persistent counter in a
  `Bytes(1)`. Generates an increment-and-check pattern using `Scope`,
  `ExpressionAssignment`, and `Cases` — no custom scope subclass needed.
  When nested inside a `Loop`, the counter logic compiles directly into the
  native for-loop body.

- **`PatternRenderCell`** (`compose` module): Extends `BatchedCell`. Calls
  `PatternSystemManager.sum(context, channel, startFrame, bufferSize)` to
  render one buffer of pattern audio.

- **`PatternSystemManager.sum(context, channel, startFrame, frameCount)`**:
  Single unified method for rendering patterns over any frame range, used by
  both the offline and real-time paths.

- **Pre-filtering and caching** in `PatternFeatures.render()` and
  `NoteAudioCache`: Skip non-overlapping notes before `evaluate()`, cache
  results across buffers. See `PARTIAL_PATTERN_RENDERING.md`.

## Runner Methods

### `runner(output)` / `runner(output, channels)`

Offline runner. Uses `bufferSize = totalFrames` and `frameSupplier = () -> 0`.
The tick phase calls `cells.tick()` per sample. Pattern rendering fires once
during setup via `renderNow()`.

### `runnerRealTime(output, bufferSize)` / `runnerRealTime(output, channels, bufferSize)`

Real-time runner. The tick phase wraps `cells.tick()` in
`loop(frameOp, bufferSize)`. The frame counter advances by `bufferSize`
after each loop iteration. `time.tick()` fires once per buffer.

The `loop()` call dispatches via `TemporalFeatures.loop(Supplier<Runnable>, int)`
at runtime — if the cell pipeline produces a `Computation`, it becomes a
compiled `Loop`; otherwise it falls back to Java iteration.

### `runnerRealTimeCompiled(output, bufferSize)` (deprecated)

Delegates to `runnerRealTime`. The separate compiled path is no longer needed
because `Periodic` computation handles the batch counting within the compiled
loop automatically.

## Compiled Code Structure

When `Periodic` is inside a `Loop` (the common audio processing case), the
compiled output is:

```c
for (int i = 1; i < 1024; i += 1) {
    // Periodic counter logic:
    counter[0] = counter[0] + 1;
    if (counter[0] >= 1024) {
        // atom body (renderBatch, etc.)
        counter[0] = 0;
    }
    // other tick operations (effects, WaveOutput cursor, etc.)
}
```

The counter is a `Bytes(1)` — persistent memory that survives across compiled
invocations. It is passed as an argument to the `Periodic` computation, so
the compiled code reads/writes it via pointer. Dependencies are tracked
automatically through `ExpressionAssignment` statements and `Cases` scope.

## Section Processing

Section processing (`ChannelSection.process()`) is currently offline-only.
It operates on the full pre-rendered buffer. The real-time path skips it.
Incremental section processing can be addressed separately.

## Verification

1. `mvn clean install -DskipTests` passes
2. `AudioSceneBaselineTest` passes (offline path still works)
3. `AudioSceneRealTimeCorrectnessTest` depth 0 passes
4. `BatchedCellTest` passes
5. `PeriodicTest` passes (5/5 tests)
