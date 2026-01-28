# Real-Time AudioScene Rendering

## Status

> **IMPLEMENTED, CORRECTNESS TESTING IN PROGRESS** (January 2026)
>
> The real-time rendering pipeline is implemented and compiles. The traditional
> baseline is **validated** (3/3 tests passing). Real-time frame advancement is
> **validated** (4-buffer tick loop passes). Full-duration correctness tests
> require hardware acceleration (~15s per buffer cycle vs 23ms required).

### What Works

- **Traditional pipeline**: Confirmed by `AudioSceneBaselineTest` (3/3 passing)
- **Real-time code compiles and runs**: `PatternRenderCell`, `BatchCell`, frame-range
  `sum()` overloads, `AudioScene.runnerRealTime()` all execute correctly
- **PatternRenderCell.tick() bug fixed**: Frame reading at execution time (not compile
  time), with `lastRenderedFrame` guard for efficiency
- **Frame advancement**: 4-buffer tick loop completes without error (~138s)

### Known Issues

1. **No hardware acceleration** - Operations run in interpreted mode (~650x too slow)
2. **Auto-volume disabled** - Real-time path skips volume normalization (by design)
3. **Section processing skipped** - `getPatternChannelRealTime()` does not apply
   `ChannelSection.process()`

### Next Steps

1. Enable hardware acceleration for real-time operations
2. Complete full-duration correctness tests (currently gated behind `@TestDepth(2)`)

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
| `BatchCell` | compose | Wraps cells to execute once per N frames |
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

### AudioSceneBaselineTest (3/3 PASS)

| Test | What It Validates |
|------|-------------------|
| `baselineAudioGeneration` | `AudioScene` + `StableDurationHealthComputation` produces audio |
| `baselineHealthComputation` | Health computation completes with non-zero score and frames |
| `baselineMelodicContent` | At least one genome produces melodic elements (channels 2-4) |

### AudioSceneRealTimeCorrectnessTest

| Test | Depth | What It Validates | Status |
|------|-------|-------------------|--------|
| `realTimeFrameAdvancement` | 0 | 4 buffer cycles complete without error | **PASS** (~138s) |
| `realTimeProducesAudio` | 2 | Tick loop produces non-silent audio | Pending (needs HW accel) |
| `realTimeMatchesTraditional` | 2 | Both paths produce audio with same seed | Pending (needs HW accel) |

### AudioSceneRealTimeTest (all @TestDepth(2))

| Test | What It Validates |
|------|-------------------|
| `traditionalRenderBaseline` | Traditional single-channel render baseline |
| `realTimeWithTimingMeasurements` | Per-buffer timing statistics |
| `multipleBufferCycles` | Multi-buffer rendering with WAV output |
| `compareTraditionalAndRealTime` | Side-by-side comparison of both pipelines |

### Remaining Test Gaps

| Gap | Priority |
|-----|----------|
| Audio content verification of real-time output | HIGH (needs HW accel) |
| Buffer boundary artifacts | MEDIUM |
| Effects chain with real-time input | MEDIUM |

### Running Tests

```
# Baseline tests (~2.5 minutes)
mcp__ar-test-runner__start_test_run
  module: "compose"
  test_classes: ["AudioSceneBaselineTest"]
  timeout_minutes: 10

# Real-time frame advancement only (~2.5 minutes)
# NOTE: AR_TEST_DEPTH defaults to 9; use depth: 0 to skip expensive tests
mcp__ar-test-runner__start_test_run
  module: "compose"
  test_classes: ["AudioSceneRealTimeCorrectnessTest"]
  depth: 0
  timeout_minutes: 10

# Full real-time correctness (needs HW acceleration or long timeout)
mcp__ar-test-runner__start_test_run
  module: "compose"
  test_classes: ["AudioSceneRealTimeCorrectnessTest"]
  depth: 2
  timeout_minutes: 45
```

**Prerequisites:** `Samples/` directory at `../../Samples/` relative to compose module.

### Success Criteria

| Criterion | Target | Status |
|-----------|--------|--------|
| Baseline produces audio | At least 1/20 genomes | **MET** |
| Baseline health score > 0 | Non-zero score and frames | **MET** |
| Melodic elements generated | At least 1 genome | **MET** |
| Real-time completes without error | No exceptions during 4-buffer tick loop | **MET** |
| Real-time produces non-silent audio | Max amplitude > 0.001, RMS > 0.0001 | Pending (depth 2) |
| Both paths produce audio for same seed | Non-silence in both outputs | Pending (depth 2) |

---

## Files

### Source (Modified)

| File | Changes |
|------|---------|
| `PatternSystemManager.java` | Added frame-range `sum()` overload |
| `PatternLayerManager.java` | Added frame-range `sum()` overload |
| `PatternFeatures.java` | Added `renderRange()` for buffer-aware rendering |
| `AudioScene.java` | Added `runnerRealTime()`, `getCellsRealTime()`, `getPatternChannelRealTime()` |
| `PatternRenderCell.java` | Fixed `tick()` to read frame at execution time |

### Source (Created)

| File | Purpose |
|------|---------|
| `PatternRenderCell.java` | Cell for incremental pattern rendering |
| `BatchCell.java` | Wrapper for N-frame batch execution |
| `PatternRenderContext.java` | Extended context with frame range |
| `GlobalTimeManager.java` | Frame position tracking |

### Tests

| File | Purpose |
|------|---------|
| `AudioSceneTestBase.java` | Shared test infrastructure |
| `AudioSceneBaselineTest.java` | Traditional pipeline validation (3/3 PASS) |
| `AudioSceneRealTimeCorrectnessTest.java` | Real-time correctness validation |
| `AudioSceneRealTimeTest.java` | Timing and performance measurements |
