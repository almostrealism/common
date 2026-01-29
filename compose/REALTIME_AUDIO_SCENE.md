# Real-Time AudioScene Rendering

## Status

> **IN PROGRESS** (January 2026)
>
> The real-time rendering pipeline compiles and produces audio output. The
> traditional baseline is **validated** (3/3 tests passing). Both the
> `runnerRealTime` (per-sample ticking) and `runnerRealTimeCompiled`
> (per-buffer ticking via `CompiledBatchCell`) paths produce non-silent audio.
>
> **Current focus**: Pattern rendering performance. Each buffer tick takes
> ~20 seconds (at 1024-frame buffer size), which is far too slow for
> real-time use. The priority is making `PatternSystemManager.sum()` with
> small frame ranges performant before continuing with the batch cell
> architecture.

### What Works

- **Traditional pipeline**: Confirmed by `AudioSceneBaselineTest` (3/3 passing)
- **Frame-range sum()**: `PatternSystemManager.sum(ctx, channel, startFrame, frameCount)`
  produces audio for single-buffer renders
- **PatternRenderCell**: Incremental rendering per buffer works correctly, with
  `lastRenderedFrame` guard for efficiency
- **Compiled batch cell**: `CompiledBatchCell` + `runnerRealTimeCompiled()` produces
  valid audio (tested with 8 buffer ticks)
- **Render cell collector**: Pattern render cells are separated from the effects
  CellList via collector parameter to support compiled-loop architecture

### Known Issues

1. **Pattern rendering is too slow** — Each call to `patterns.sum(ctx, channel, startFrame, bufferSize)`
   takes ~20 seconds per buffer tick. This must be solved before the batch cell
   architecture can deliver real-time performance.
2. **Effects loop not compiled** — `cells.tick()` in the compiled runner path is not
   a `Computation`, so `CompiledBatchCell` falls back to `javaLoop()` instead of a
   native compiled `Loop`.
3. **Auto-volume disabled** — Real-time path skips volume normalization (by design)
4. **Section processing skipped** — `getPatternChannelRealTime()` does not apply
   `ChannelSection.process()`

### Next Steps

1. **Solve pattern rendering performance with small batches** — Make
   `sum(ctx, channel, startFrame, bufferSize)` fast enough for real-time use
2. Then revisit compiled Loop architecture for per-frame effects processing

---

## Architecture

### Traditional vs Real-Time Pipeline

**Traditional** (pre-renders everything during setup):
```
AudioScene.runner(output)
  Setup: PatternSystemManager.sum() renders ALL patterns for ENTIRE duration
  Tick:  CellList processes pre-rendered audio through effects
```

**Real-Time** (renders incrementally during tick):
```
AudioScene.runnerRealTime(output, bufferSize)
  Setup: Lightweight initialization (no pattern rendering)
  Tick:  PatternRenderCell renders current buffer via sum(startFrame, bufferSize)
         BatchCell fires rendering every bufferSize ticks
```

### Key Components

| Component | Module | Purpose |
|-----------|--------|---------|
| `PatternRenderCell` | compose | Renders patterns incrementally per buffer |
| `BatchCell` | compose | Wraps cells to execute once per N frames (used by `runnerRealTime`) |
| `CompiledBatchCell` | compose | Render-once + compiled Loop architecture (used by `runnerRealTimeCompiled`) |
| `PatternRenderContext` | music | Extended context with frame range awareness |
| `GlobalTimeManager` | compose | Tracks current frame position across cells |

### Frame Tracking

`PatternRenderCell` accepts an `IntSupplier` for the current frame position, managed
by `BatchCell`/`GlobalTimeManager`. The `lastRenderedFrame` guard ensures rendering
happens only when the frame position advances (once per `bufferSize` ticks).

```
tick() called per sample frame
  -> Check if currentFrame changed since lastRenderedFrame
     -> If changed: clear buffer, call patterns.sum(startFrame, bufferSize), update guard
     -> If same: no-op (reuse previous buffer)
```

---

## Pattern Rendering API

### Frame-Range sum() Overloads

```java
// PatternSystemManager - full render (traditional)
public Supplier<Runnable> sum(Supplier<AudioSceneContext> context, ChannelInfo channel)

// PatternSystemManager - frame-range render (real-time)
public Supplier<Runnable> sum(Supplier<AudioSceneContext> context,
                              ChannelInfo channel, int startFrame, int frameCount)
```

The frame-range version creates a `PatternRenderContext`, skips auto-volume, and
delegates to per-pattern `sum()` with frame parameters. `PatternLayerManager` converts
frames to measures, filters elements to those overlapping the range, and calls
`renderRange()` instead of `render()`.

### PatternRenderContext

Extends `AudioSceneContext` with frame range awareness:
- `getStartFrame()` / `getFrameCount()` / `getEndFrame()` - Frame range accessors
- `measureToBufferOffset(measure)` - Converts measure position to buffer offset
- `overlapsFrameRange(startMeasure, endMeasure)` - Range overlap check

### Frame Conversion

```java
private double frameToMeasure(int frame, AudioSceneContext ctx) {
    double framesPerMeasure = ctx.getFrames() / (double) ctx.getMeasures();
    return frame / framesPerMeasure;
}
```

Assumes linear time (no tempo changes mid-arrangement).

---

## Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Buffer size | 1024 frames (~23ms at 44.1kHz) | Balance between latency and overhead |
| Auto-volume | Disabled in real-time | Requires full-track knowledge; use compressor-limiter instead |
| Section processing | Skipped in real-time | Operates on full patterns; incremental version deferred |
| Heap.stage() | Bypassed in real-time | Memory management optimization deferred |
| Frame tracking | External via IntSupplier | Allows GlobalTimeManager/BatchCell to control timing |

### Differences from Traditional Path

| Aspect | Traditional | Real-Time |
|--------|-------------|-----------|
| When patterns render | Setup phase (once) | Tick phase (per buffer) |
| Destination buffer size | Full duration | Buffer size (1024 frames) |
| Auto-volume | Enabled | Disabled |
| Section processing | Applied via `ChannelSection.process()` | Skipped |
| Element filtering | All elements | Frame-range filtered |

---

## Risks

| Risk | Status | Mitigation |
|------|--------|------------|
| Performance (operations uncompiled) | **ACTIVE** | Enable hardware acceleration via `CollectionProducer` pattern |
| Buffer boundary artifacts | Untested | `renderRange()` handles note overlap; needs audio verification |
| Section processing differences | By design | Traditional and real-time output will differ structurally |
| Scale traversal frame dependencies | Untested | `PatternRenderContext.measureToBufferOffset()` handles conversion |

---

## Tests

### Test Infrastructure (AudioSceneTestBase)

Abstract base class providing deterministic scene creation. Creates a 6-channel,
120 BPM, 16-measure scene with `NoteAudioChoice` instances backed by `FileNoteSource`.

**Channel mapping:**
| Channel | Type | Choice |
|---------|------|--------|
| 0 | Non-melodic | Percs (TAT 1-3) |
| 1 | Non-melodic | Snares (Lush, Pop Vibe, Tiger, Tight) |
| 2 | Melodic | Bass (DX Punch C0-C1) |
| 3 | Melodic | Harmony (Synth Guitar C0-C3) |
| 4 | Melodic | Lead (DX Punch C2-C5) |
| 5 | Non-melodic | Accents (Eclipse 1-2, TripTrap 5) |

All tests skip when `Samples/` directory is not present. Genome seed search
(`findWorkingGenomeSeed`) tries seeds 42–61 and picks the one producing the
most pattern elements.

### AudioSceneBaselineTest (3/3 PASS)

| Test | What It Does |
|------|--------------|
| `baselineAudioGeneration` | Renders audio via `StableDurationHealthComputation`, verifies WAV file produced |
| `baselineHealthComputation` | Checks health computation returns non-zero score and frames |
| `baselineMelodicContent` | Verifies at least one genome produces melodic elements (channels 2–4) |

### AudioSceneRealTimeCorrectnessTest

This test class contains 10 test methods organized into four groups. All tests
disable `MixdownManager` filters and `PatternSystemManager` warnings.

#### Original real-time runner tests

These tests use `AudioScene.runnerRealTime()`, which returns a `TemporalCellular`
that is ticked per sample (44100 ticks per second of audio). Internally, a
`BatchCell` counts ticks and fires `PatternRenderCell` rendering every
`bufferSize` ticks.

| Test | Depth | Timeout | What It Does |
|------|-------|---------|--------------|
| `realTimeFrameAdvancement` | — | 15 min | Runs 4 buffer cycles (4096 per-sample ticks) through `runnerRealTime`. Checks the output WAV file exists. Does not verify audio content. |
| `realTimeProducesAudio` | 2 | 30 min | Runs `RENDER_SECONDS` (4.0s) of per-sample ticks through `runnerRealTime`. Verifies non-silent output (max amplitude > 0.001, RMS > 0.0001). |
| `realTimeMatchesTraditional` | 2 | 45 min | Renders the same scene through both the traditional path (`StableDurationHealthComputation`) and `runnerRealTime`. Verifies both produce non-silent output. Logs RMS comparison but does not assert similarity. |

#### Frame-range sum in isolation

These tests call `PatternSystemManager.sum(ctx, channel, startFrame, frameCount)`
directly. No cells, no effects pipeline, no `BatchCell`.

| Test | Depth | Timeout | What It Does |
|------|-------|---------|--------------|
| `frameRangeSumProducesAudio` | — | 1 min | Renders a single 1024-frame buffer via `patterns.sum(ctx, channel, 0, 1024)`. Logs amplitude/RMS. Does not hard-fail if amplitude is zero (pattern elements may not start at frame 0). |
| `frameRangeSumMultipleBuffers` | 2 | 2 min | Renders 4 seconds of audio buffer-by-buffer via consecutive `patterns.sum()` calls. Copies each buffer into a concatenated result. Asserts at least some buffers have audio. |

#### Frame-range rendering with effects pipeline

These tests use `AudioScene.getPatternChannelRealTime()` or `runnerRealTime()` to
combine `PatternRenderCell` with the effects/mixdown pipeline, then tick per sample.

| Test | Depth | Timeout | What It Does |
|------|-------|---------|--------------|
| `frameRangeWithEffects` | — | 1 min | Calls `getPatternChannelRealTime()` to get a CellList with `PatternRenderCell` + effects. Ticks 1024 times (per-sample). Verifies no exceptions. Does not check audio content. |
| `multiBufferWithEffects` | 2 | 30 min | Runs 8 buffer cycles (8192 per-sample ticks) through `runnerRealTime`. Verifies non-silent output in the WAV file. |

#### Compiled batch cell architecture

These tests use `AudioScene.runnerRealTimeCompiled()`, which returns a
`TemporalCellular` backed by `CompiledBatchCell`. Each tick renders one full
buffer (not per-sample). Pattern rendering is separated from effects processing
via the render cell collector pattern.

| Test | Depth | Timeout | What It Does |
|------|-------|---------|--------------|
| `batchCellArchitectureValidation` | — | 1 min | Creates a `CompiledBatchCell` with lambda operations (not real scene). Verifies batch size, batch counter advancement, and that `isFrameOpCompilable()` returns false for lambdas. |
| `compiledBatchCellProducesAudio` | 2 | 10 min | Runs 8 buffer ticks through `runnerRealTimeCompiled`. Verifies non-silent output. Currently falls back to Java loop (~20s per tick). |
| `compiledBatchCellPerformance` | 3 | 45 min | Renders 0.25s of audio through both `runnerRealTime` (per-sample) and `runnerRealTimeCompiled` (per-buffer). Logs timing comparison. Informational only — no performance assertions. |

### AudioSceneRealTimeTest (all @TestDepth(2))

| Test | What It Does |
|------|--------------|
| `traditionalRenderBaseline` | Traditional single-channel render baseline |
| `realTimeWithTimingMeasurements` | Per-buffer timing statistics |
| `multipleBufferCycles` | Multi-buffer rendering with WAV output |
| `compareTraditionalAndRealTime` | Side-by-side comparison of both pipelines |

### Key Observations

1. **Pattern rendering dominates execution time.** Each `patterns.sum()` call for
   a 1024-frame buffer takes ~20 seconds. This makes all tests that tick per-sample
   extremely slow (44100 ticks × overhead per tick), and makes the compiled batch
   cell tests slow despite ticking only once per buffer (because the render operation
   itself is the bottleneck).

2. **The compiled Loop falls back to Java.** `cells.tick()` is not a `Computation`,
   so `CompiledBatchCell` uses `javaLoop()` instead of a native compiled `Loop`.
   This is a secondary concern — fixing pattern rendering performance is prerequisite.

3. **Most audio content assertions are at depth 2+.** Only `frameRangeSumProducesAudio`,
   `frameRangeWithEffects`, `batchCellArchitectureValidation`, and
   `realTimeFrameAdvancement` run at depth 0. Of these, only
   `frameRangeSumProducesAudio` checks audio content (and it soft-fails on silence).

### Running Tests

```
# Baseline tests
mcp__ar-test-runner__start_test_run
  module: "compose"
  test_classes: ["AudioSceneBaselineTest"]
  timeout_minutes: 10

# Depth 0 only (frame-range sum, effects integration, architecture validation, frame advancement)
mcp__ar-test-runner__start_test_run
  module: "compose"
  test_classes: ["AudioSceneRealTimeCorrectnessTest"]
  depth: 0
  timeout_minutes: 10

# Depth 2 (includes multi-buffer, compiled batch cell, full-duration tests)
mcp__ar-test-runner__start_test_run
  module: "compose"
  test_classes: ["AudioSceneRealTimeCorrectnessTest"]
  depth: 2
  timeout_minutes: 30
```

**Prerequisites:** `Samples/` directory at `../../Samples/` relative to compose module.

---

## Files

### Source (Modified)

| File | Changes |
|------|---------|
| `PatternSystemManager.java` | Added frame-range `sum()` overload |
| `PatternLayerManager.java` | Added frame-range `sum()` overload |
| `PatternFeatures.java` | Added `renderRange()` for buffer-aware rendering |
| `AudioScene.java` | Added `runnerRealTime()`, `runnerRealTimeCompiled()`, `getCellsRealTime()`, `getPatternChannelRealTime()` with render cell collector overloads |
| `PatternRenderCell.java` | Fixed `tick()` to read frame at execution time |

### Source (Created)

| File | Purpose |
|------|---------|
| `PatternRenderCell.java` | Cell for incremental pattern rendering |
| `BatchCell.java` | Wrapper for N-frame batch execution (per-sample tick counting) |
| `CompiledBatchCell.java` | Render-once + compiled Loop for per-buffer ticking |
| `PatternRenderContext.java` | Extended context with frame range |
| `GlobalTimeManager.java` | Frame position tracking |

### Tests

| File | Purpose |
|------|---------|
| `AudioSceneTestBase.java` | Shared test infrastructure (scene creation, genome, channel mapping) |
| `AudioSceneBaselineTest.java` | Traditional pipeline validation (3/3 PASS) |
| `AudioSceneRealTimeCorrectnessTest.java` | Real-time correctness: 10 tests across 4 groups |
| `AudioSceneRealTimeTest.java` | Timing and performance measurements |
