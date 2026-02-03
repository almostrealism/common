# Real-Time AudioScene Rendering

## Goal

Enable `AudioScene` to render audio at real-time speeds for live playback,
where each buffer must be computed faster than its playback duration.

**Constraint**: At 44,100 Hz sample rate with a 1024-frame buffer:
- Buffer duration: ~23.2 ms
- Render time must be < 23.2 ms per buffer (with headroom for scheduling jitter)

**Current status**: The architecture supports real-time rendering, but
performance has not been validated against the real-time constraint. Tests
produce audio and measure timing but do not assert on timing thresholds.

---

## Architecture Overview

AudioScene renders audio in two phases:

1. **Pattern rendering**: `PatternSystemManager` evaluates note patterns,
   applies sample playback and pitch shifting, sums to per-channel buffers
2. **Effects processing**: `MixdownManager` applies filters, delay, reverb,
   and routes audio through the cell pipeline to `WaveOutput`

### Offline vs Real-Time

Both paths share the same cell construction via `getCells()`:

| Aspect | Offline (`runner`) | Real-Time (`runnerRealTime`) |
|--------|--------------------|-----------------------------|
| Buffer size | `totalFrames` (entire arrangement) | 1024 (configurable) |
| Frame supplier | `() -> 0` (static) | `() -> currentFrame[0]` (advancing) |
| Pattern rendering | Once during setup via `renderNow()` | Per-buffer via `tick()` counting |
| Tick invocation | Per-sample (`cells.tick()`) | Per-buffer (`loop(frameOp, bufferSize)`) |

### Key Components

| Component | Module | Role |
|-----------|--------|------|
| `AudioScene.runnerRealTime()` | compose | Creates runner that wraps tick in `loop()` |
| `PatternRenderCell` | compose | `BatchedCell` subclass that calls `patterns.sum()` |
| `BatchedCell` | graph | Base class with tick counting, output buffer management |
| `Periodic` | hardware | Compilable counter-based conditional execution |
| `PatternSystemManager.sum()` | compose | Renders patterns over a frame range |
| `NoteAudioCache` | music | Caches evaluated note audio across buffers |

### Data Flow (Real-Time Path)

```
runnerRealTime.tick()
  |
  v
loop(cells.tick(), bufferSize)     // Loop wraps the cell pipeline
  |
  v
[per iteration within loop]
  |
  +-> BatchedCell.tick()           // Periodic counting (1/bufferSize fires renderBatch)
  |     |
  |     v (when counter reaches bufferSize)
  |   PatternRenderCell.renderBatch()
  |     |
  |     v
  |   PatternSystemManager.sum(ctx, channel, startFrame, bufferSize)
  |     |
  |     v
  |   PatternFeatures.render() for each overlapping note
  |     - NoteAudioCache.get() / put() for cross-buffer caching
  |     - traverse(1, producer).get().evaluate() for new notes
  |
  +-> Effects pipeline (MixdownManager cells)
  |
  +-> WaveOutput cursor advancement
  |
  v
currentFrame[0] += bufferSize      // Advance frame position after loop
```

---

## Progress Summary

### Completed

1. **Unified cell construction** (`getCells` hierarchy) - both paths share code
2. **`PatternRenderCell`** - incremental pattern rendering in tick phase
3. **`BatchedCell`** - tick counting with `Periodic` support
4. **`Periodic` computation** - compilable counter logic using `Scope.addCase()`
5. **`NoteAudioCache`** - avoids re-evaluating notes spanning multiple buffers
6. **Pre-filtering** - skips notes that don't overlap the current buffer
7. **Frame tracking** - `currentFrame` supplier drives incremental rendering
8. **Test suite** - correctness and timing measurement tests

### Not Yet Done

1. **Performance validation** - no tests assert that render time < buffer duration
2. **Compilable render path** - `PatternRenderCell.renderBatch()` returns a plain
   lambda, not a `Computation`, so the `Periodic` compilable path is not active
3. **Section processing** - real-time path skips `ChannelSection.process()`
4. **Live audio output** - tests write to `WaveOutput` files, not audio hardware

---

## Test Coverage

### Unit Tests (infrastructure validation)

| Test Class | Module | What It Validates |
|------------|--------|-------------------|
| **PeriodicTest** (5 tests) | utils | `Periodic` computation counting, reset, persistence |
| **BatchedCellTest** (7 tests) | compose | Tick counting, push/render separation, state management |

### Correctness Tests (audio output validation)

| Test | Class | Timeout | What It Validates |
|------|-------|---------|-------------------|
| `realTimeProducesAudio` | Correctness | 3 min | `runnerRealTime` produces non-silent WAV |
| `realTimeMatchesTraditional` | Correctness | 10 min | Real-time and offline paths both produce audio |
| `realTimeFrameAdvancement` | Correctness | 1 min | Frame tracking advances across 4 buffer boundaries |
| `frameRangeSumProducesAudio` | Correctness | 1 min | `PatternSystemManager.sum()` renders a single buffer |
| `frameRangeSumMultipleBuffers` | Correctness | 3 min | Consecutive buffer renders stitch correctly |
| `frameRangeWithEffects` | Correctness | 1 min | Pattern channel + effects pipeline ticks without error |
| `multiBufferWithEffects` | Correctness | 3 min | 8 buffer cycles through full runner produce audio |
| `batchCellArchitectureValidation` | Correctness | 10 sec | BatchedCell tick counting and callback mechanics |
| `traditionalRenderBaseline` | RealTime | 2 min | Baseline scene renders via traditional path |
| `compareTraditionalAndRealTime` | RealTime | 10 min | Spectrograms for visual comparison |

### Performance Tests (timing measurement, no assertions)

| Test | Class | Timeout | What It Measures |
|------|-------|---------|------------------|
| `realTimeRunnerPerformance` | Correctness | 3 min | Per-buffer timing for 0.25s render |
| `renderTimingVsBufferSize` | Correctness | 3 min | Whether render time scales with buffer size |
| `renderNoteAudioLengthAnalysis` | Correctness | 3 min | Note-level evaluate() waste and timing |
| `realTimeWithTimingMeasurements` | RealTime | 5 min | Per-buffer timing with overrun counting |
| `multipleBufferCycles` | RealTime | 3 min | 2 seconds of buffers with timing stats |

### Baseline Tests (non-real-time pipeline validation)

| Test | Class | Timeout | What It Validates |
|------|-------|---------|-------------------|
| `baselineAudioGeneration` | Baseline | 5 min | Per-sample pipeline produces WAV |
| `baselineHealthComputation` | Baseline | 10 min | `StableDurationHealthComputation` works |
| `cellPipelineDiagnostic` | Baseline | 1 min | Cell roots, cursor advancement |
| `baselineMelodicContent` | Baseline | 30 sec | Pattern elements are generated |

---

## Remaining Work

### Priority 1: Performance Validation

Add assertions to verify render time < buffer duration:

```java
// In realTimeRunnerPerformance or a new test
double bufferDurationMs = BUFFER_SIZE / (double) SAMPLE_RATE * 1000;
assertTrue("Average buffer render time must be < buffer duration",
    avgBufferTimeMs < bufferDurationMs);
```

This will reveal whether the current implementation actually meets real-time
constraints.

### Priority 2: Profile Bottlenecks

If tests fail the real-time constraint, profile to identify bottlenecks:

- `PatternFeatures.render()` evaluate() calls
- `traverse(1, producer).get().evaluate()` compilation overhead
- `NoteAudioCache` miss rates
- Effects pipeline per-sample processing

### Priority 3: Compilable Render Path

Make `PatternRenderCell.renderBatch()` return a `Computation` so that
`BatchedCell.tick()` uses the `Periodic` compilable path. This requires:

1. `PatternSystemManager.sum()` to return a `Computation`, not `Supplier<Runnable>`
2. Evaluate whether `NoteAudioCache` can work with compiled code

### Priority 4: Section Processing

`ChannelSection.process()` currently operates on full pre-rendered buffers.
For real-time, this needs incremental processing or removal.

### Priority 5: Live Audio Output

Replace `WaveOutput` file writing with a real-time audio output (e.g., PortAudio,
JACK, or platform-specific API).

---

## Running Tests

```bash
# All real-time tests (depth 0 only)
mcp__ar-test-runner__start_test_run module="compose" \
    test_classes=["AudioSceneRealTimeCorrectnessTest", "AudioSceneRealTimeTest"] \
    depth=0 timeout_minutes=15

# Full test suite including depth 2 tests
mcp__ar-test-runner__start_test_run module="compose" \
    test_classes=["AudioSceneRealTimeCorrectnessTest", "AudioSceneRealTimeTest", \
                  "AudioSceneBaselineTest"] \
    depth=2 timeout_minutes=30

# Infrastructure tests
mcp__ar-test-runner__start_test_run module="utils" \
    test_classes=["PeriodicTest"] timeout_minutes=5
mcp__ar-test-runner__start_test_run module="compose" \
    test_classes=["BatchedCellTest"] timeout_minutes=5
```

---

## File Locations

| File | Module | Purpose |
|------|--------|---------|
| `AudioScene.java` | compose | `runnerRealTime()`, `getCells()` hierarchy |
| `PatternRenderCell.java` | compose | Incremental pattern rendering |
| `PatternSystemManager.java` | compose | `sum()` for frame-range rendering |
| `PatternFeatures.java` | music | `render()` with pre-filtering and caching |
| `NoteAudioCache.java` | music | Cross-buffer note audio cache |
| `BatchedCell.java` | graph | Tick counting, output buffer management |
| `Periodic.java` | hardware | Compilable counter-based execution |
| `AudioSceneRealTimeCorrectnessTest.java` | compose/test | Correctness and timing tests |
| `AudioSceneRealTimeTest.java` | compose/test | Integration tests with Library samples |
| `AudioSceneBaselineTest.java` | compose/test | Baseline non-real-time tests |
| `PeriodicTest.java` | utils/test | Periodic computation unit tests |
| `BatchedCellTest.java` | compose/test | BatchedCell unit tests |
