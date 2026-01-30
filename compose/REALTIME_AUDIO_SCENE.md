# Real-Time AudioScene: Consolidation Plan

## Background

AudioScene renders audio in two phases: (1) pattern arrangement via
`PatternSystemManager`/`PatternLayerManager`, producing raw audio for each
channel, and (2) effects processing via `MixdownManager`, which constructs a
`CellList` pipeline for mixing, delay, reverb, etc.

The original architecture rendered all patterns during the *setup* phase,
before any audio output. This blocked real-time streaming. The solution was
to move pattern rendering into the *tick* phase via `PatternRenderCell`, a
`BatchedCell` subclass that renders one buffer of pattern audio per batch.

## What Was Built

- **`BatchedCell`** (`graph` module): Abstract base class that adapts
  per-sample `tick()` to per-buffer `renderBatch()`. Counts N ticks, fires
  once. Caches the compiled render operation.

- **`PatternRenderCell`** (`compose` module): Extends `BatchedCell`. Calls
  `PatternSystemManager.sum(context, channel, startFrame, bufferSize)` to
  render one buffer of pattern audio.

- **`PatternSystemManager.sum(context, channel, startFrame, frameCount)`**:
  Single unified method for rendering patterns over any frame range, used by
  both the offline and real-time paths.

- **Pre-filtering and caching** in `PatternFeatures.render()` and
  `NoteAudioCache`: Skip non-overlapping notes before `evaluate()`, cache
  results across buffers. See `PARTIAL_PATTERN_RENDERING.md`.

## Current Problem: AudioScene Duplication

AudioScene currently has three parallel method hierarchies that do essentially
the same thing (build a CellList of pattern channels + effects):

| Offline | Real-Time | Compiled Real-Time |
|---------|-----------|-------------------|
| `getCells` | `getCellsRealTime` | `getCellsRealTime` (with collector) |
| `getPatternCells` | `getPatternCellsRealTime` | `getPatternCellsRealTime` (with collector) |
| `getPatternChannel` | `getPatternChannelRealTime` | `getPatternChannelRealTime` (with collector) |
| `runner` | `runnerRealTime` | `runnerRealTimeCompiled` |

These exist because the offline path puts pattern rendering in setup and
produces a `Producer<PackedCollection>` from a pre-allocated buffer, while
the real-time path puts it in tick and produces a `Producer` from
`PatternRenderCell.getOutputProducer()`. But both paths ultimately call the
same `efx.apply(channel, producer, duration, setup)`.

### What actually differs

1. **Where the audio `Producer` comes from.** Offline: a `func()` wrapping
   `patternDestinations.get(channel)`. Real-time: `renderCell.getOutputProducer()`.

2. **When rendering happens.** Offline: during setup via `getPatternSetup()`.
   Real-time: during tick via `BatchedCell.tick()`.

3. **Section processing.** Offline applies `ChannelSection.process()` to the
   pre-rendered buffer. Real-time skips it (noted as a known limitation).

4. **`patternDestinations` map.** Only used by the offline path to hold
   pre-rendered full-duration buffers.

### What does NOT differ

- The `getCells` → `getPatternCells` → mixdown structure
- The `efx.apply(channel, producer, duration, setup)` call
- The `runner()` → `TemporalCellular { setup, tick, reset }` skeleton
- Setup operations (tuning, sections, automation, mixdown, time)

## Proposed Consolidation

The key insight: `PatternRenderCell` with `bufferSize = totalFrames` and
`startFrame = () -> 0` renders everything in one batch — functionally
identical to the offline path. Both paths can use `PatternRenderCell`; they
differ only in buffer size and when `tick()` fires.

### Single `getPatternChannel` method

Replace both `getPatternChannel` and `getPatternChannelRealTime` with a
single method:

```java
public CellList getPatternChannel(ChannelInfo channel,
                                  int bufferSize,
                                  IntSupplier frameSupplier,
                                  OperationList setup) {
    Supplier<AudioSceneContext> ctx = () -> getContext(List.of(channel));
    PatternRenderCell renderCell = new PatternRenderCell(
            patterns, ctx, channel, bufferSize, frameSupplier);

    setup.add(renderCell.setup());

    CellList cells = efx.apply(channel, renderCell.getOutputProducer(),
            getTotalDuration(), setup);
    cells.addRequirement(renderCell);
    return cells;
}
```

For offline mode, the caller passes `bufferSize = totalFrames` and
`frameSupplier = () -> 0`. The cell renders everything on the first tick.

For real-time mode, the caller passes `bufferSize = 1024` and a dynamic
`frameSupplier`. The cell renders incrementally.

### Single `getPatternCells` method

```java
public CellList getPatternCells(MultiChannelAudioOutput output,
                                List<Integer> channels,
                                ChannelInfo.StereoChannel audioChannel,
                                int bufferSize,
                                IntSupplier frameSupplier,
                                OperationList setup) {
    int[] idx = channels.stream().mapToInt(i -> i).toArray();
    CellList main = all(idx.length, i ->
            getPatternChannel(new ChannelInfo(idx[i], Voicing.MAIN, audioChannel),
                    bufferSize, frameSupplier, setup));
    CellList wet = all(idx.length, i ->
            getPatternChannel(new ChannelInfo(idx[i], Voicing.WET, audioChannel),
                    bufferSize, frameSupplier, setup));
    return mixdown.cells(main, wet, riser.getRise(bufferSize),
            output, audioChannel, i -> idx[i]);
}
```

### Single `getCells` method

```java
public Cells getCells(MultiChannelAudioOutput output,
                      List<Integer> channels,
                      int bufferSize,
                      IntSupplier frameSupplier) {
    setup = new OperationList("AudioScene Setup");
    addCommonSetup(setup);
    setup.add(() -> () -> patterns.setTuning(tuning));
    setup.add(sections.setup());

    CellList cells = cells(
            getPatternCells(output, channels, LEFT, bufferSize, frameSupplier, setup),
            getPatternCells(output, channels, RIGHT, bufferSize, frameSupplier, setup));
    return cells.addRequirement(time::tick);
}
```

### Two `runner` methods (offline and real-time)

The `runner` methods are thin wrappers that choose buffer size and frame
tracking strategy. These genuinely differ in their tick model:

- **Offline** (`runner`): `bufferSize = totalFrames`, `frameSupplier = () -> 0`.
  Tick calls `cells.tick()` per sample. Pattern rendering fires once on the
  first batch (equivalent to current setup-phase rendering).

- **Real-time** (`runner` with bufferSize parameter): `bufferSize = 1024`,
  `frameSupplier` tracks position. Tick calls `cells.tick()` per sample;
  `BatchedCell` handles the batching internally. The compiled variant uses
  `loop(frameOp, bufferSize)` instead of per-sample ticking.

The compiled variant (`runnerRealTimeCompiled`) may warrant a separate method
because it restructures the tick phase (render cells once per buffer + compiled
loop for effects). But the cell *construction* should go through the same
`getCells`/`getPatternChannel` path.

### What gets deleted

- `getPatternChannelRealTime` (both overloads)
- `getPatternCellsRealTime` (all overloads)
- `getCellsRealTime` (both overloads)
- `getPatternSetup`
- `refreshPatternDestination`
- `patternDestinations` field
- The `renderCellCollector` pattern (render cells are always added as
  requirements; the compiled path can retrieve them from the CellList)

### Section processing

Section processing (`ChannelSection.process()`) is currently offline-only.
It operates on the full pre-rendered buffer. Two options:

1. **Skip for now** — the real-time path already skips it. Merge without it
   and add incremental section processing later.
2. **Apply after the fact** — sections operate on the `PatternRenderCell`
   output buffer. This requires sections to work on buffer-sized chunks,
   which they may not support today.

Recommendation: option 1. Section processing can be addressed separately.

## Files to Modify

| File | Change |
|------|--------|
| `AudioScene.java` | Consolidate to single method hierarchy |
| `AudioSceneRealTimeCorrectnessTest.java` | Update to use unified API |
| `AudioSceneRealTimeTest.java` | Update to use unified API |

## Verification

1. `mvn clean install -DskipTests` passes
2. `AudioSceneBaselineTest` passes (offline path still works)
3. `AudioSceneRealTimeCorrectnessTest` depth 0 passes
4. `RealTimeRenderingTest` passes
5. `BatchedCellTest` passes
