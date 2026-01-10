# Real-Time AudioScene Test Plan

## Executive Summary

This document outlines the testing strategy for the real-time AudioScene rendering implementation. It inventories existing tests, identifies coverage gaps, proposes new tests, and maps implementation risks to specific test requirements.

## Table of Contents

1. [Existing Test Inventory](#existing-test-inventory)
2. [Coverage Analysis](#coverage-analysis)
3. [Baseline Tests](#baseline-tests)
4. [Proposed New Tests](#proposed-new-tests)
5. [Risk-to-Test Mapping](#risk-to-test-mapping)
6. [Test Execution Strategy](#test-execution-strategy)
7. [Success Criteria](#success-criteria)

---

## Existing Test Inventory

### ar-audio Module (26 test files)

| Test File | Location | Coverage Area | Relevance |
|-----------|----------|---------------|-----------|
| `RealtimePlaybackTest` | `audio/src/test/java/.../line/test/` | BufferedOutputScheduler, SourceDataOutputLine | **HIGH** - Tests real-time infrastructure |
| `CellListTests` | `audio/src/test/java/.../test/` | CellList export, delay chains | **HIGH** - Tests CellList operations |
| `WaveCellTest` | `audio/src/test/java/.../sources/test/` | WaveCell audio source | MEDIUM |
| `SineWaveCellTest` | `audio/src/test/java/.../test/` | Sine wave generation | LOW |
| `DelayCellTest` | `audio/src/test/java/.../filter/test/` | Delay processing | MEDIUM |
| `ReverbCellTests` | `audio/src/test/java/.../filter/test/` | Reverb effects | LOW |
| `AudioPassFilterTest` | `audio/src/test/java/.../filter/test/` | Filter processing | LOW |
| `MixdownTest` | `audio/src/test/java/.../sources/test/` | Audio mixdown | MEDIUM |
| `ScaleTest` | `audio/src/test/java/.../tone/test/` | Musical scales | LOW |
| `DefaultKeyboardTuningTest` | `audio/src/test/java/.../tone/test/` | Keyboard tuning | LOW |

**Key Observations:**
- `RealtimePlaybackTest` provides the foundation for real-time testing with BufferedOutputScheduler
- Tests cover CellList infrastructure but not with pattern rendering
- No tests combine real-time scheduling with pattern-based audio

### ar-music Module (6 test files)

| Test File | Location | Coverage Area | Relevance |
|-----------|----------|---------------|-----------|
| `PatternAudioTest` | `music/src/test/java/.../pattern/test/` | Note audio generation, envelopes | **HIGH** - Tests pattern note rendering |
| `ChordProgressionManagerTest` | `music/src/test/java/.../pattern/test/` | Chord progressions | MEDIUM |
| `EnvelopeTests` | `music/src/test/java/.../filter/test/` | Volume/filter envelopes | MEDIUM |
| `ParameterFunctionTest` | `music/src/test/java/.../data/test/` | Parameter functions | LOW |
| `GrainTest` | `music/src/test/java/.../grains/test/` | Granular synthesis | LOW |
| `SequenceTest` | `music/src/test/java/.../sources/test/` | Sequenced audio | LOW |

**Key Observations:**
- `PatternAudioTest` tests individual note audio but not full pattern rendering
- No tests for `PatternLayerManager.sum()` or `PatternSystemManager.sum()`
- Envelope tests are standalone, not integrated with pattern flow

### ar-compose Module (18 test files)

| Test File | Location | Coverage Area | Relevance |
|-----------|----------|---------------|-----------|
| `AudioSceneTest` | `compose/src/test/java/.../test/` | AudioScene.runner() | **CRITICAL** - Tests scene execution |
| `PatternElementTests` | `compose/src/test/java/.../notes/` | PatternFeatures.render() | **CRITICAL** - Tests pattern rendering |
| `PatternFactoryTest` | `compose/src/test/java/.../pattern/test/` | PatternLayerManager.sum() | **CRITICAL** - Tests layer rendering |
| `StableDurationHealthComputationTest` | `compose/src/test/java/.../optimize/test/` | TemporalRunner execution | **HIGH** - Tests non-real-time flow |
| `MixdownManagerTests` | `compose/src/test/java/.../arrange/test/` | MixdownManager.cells() | **HIGH** - Tests effects chain |
| `MixerTests` | `compose/src/test/java/.../test/` | BufferedOutputScheduler with CellList | **HIGH** - Tests real-time mixing |
| `DefaultChannelSectionTest` | `compose/src/test/java/.../arrange/test/` | ChannelSection processing | MEDIUM |
| `AudioSceneOptimizationTest` | `compose/src/test/java/.../optimize/test/` | Scene optimization | MEDIUM |
| `AudioScenePopulationTest` | `compose/src/test/java/.../optimize/test/` | Population-based optimization | LOW |

**Key Observations:**
- `PatternElementTests` directly tests `PatternFeatures.render()` - critical baseline
- `PatternFactoryTest` tests `PatternLayerManager.sum()` - critical baseline
- `AudioSceneTest` tests full scene but with non-real-time runner
- `MixerTests` tests real-time with BufferedOutputScheduler but not with patterns

---

## Coverage Analysis

### Impacted Components

The real-time implementation will modify or depend on these components:

| Component | Current Test Coverage | Gap |
|-----------|----------------------|-----|
| `PatternSystemManager.sum()` | **NONE** | No direct tests |
| `PatternLayerManager.sum()` | PatternFactoryTest | Frame-range not tested |
| `PatternFeatures.render()` | PatternElementTests | Frame-range not tested |
| `AudioSceneContext` | Indirect via pattern tests | Frame conversion not tested |
| `BufferedOutputScheduler` | RealtimePlaybackTest, MixerTests | Not tested with patterns |
| `TemporalRunner` | StableDurationHealthComputationTest | Real-time mode not tested |
| `AudioScene.runner()` | AudioSceneTest | Real-time mode not tested |
| `ChannelSection.process()` | DefaultChannelSectionTest | Incremental not tested |

### Coverage Gaps Summary

1. **No integration tests for patterns + real-time output**
   - RealtimePlaybackTest uses CellList without patterns
   - MixerTests uses BufferedOutputScheduler without patterns

2. **No frame-range aware rendering tests**
   - All pattern tests render full duration
   - No tests verify correct buffer boundary handling

3. **No PatternSystemManager.sum() tests**
   - Only PatternLayerManager is tested
   - Top-level orchestration untested

4. **No tests for section processing with buffer boundaries**
   - ChannelSection.process() only tested with full buffers

---

## Baseline Tests

These existing tests establish the baseline behavior that must not regress.

### Critical Baseline Tests

| Test | Class | Purpose | Pass Criteria |
|------|-------|---------|---------------|
| `pattern()` | PatternElementTests | Verify PatternFeatures.render() | Audio file matches expected |
| `runLayers()` | PatternFactoryTest | Verify PatternLayerManager.sum() | Audio file matches expected |
| `runScene()` | AudioSceneTest | Verify AudioScene.runner() | Scene executes without error |
| `cells()` | StableDurationHealthComputationTest | Verify TemporalRunner execution | Health computation completes |
| `bufferedRealtimePlayback()` | RealtimePlaybackTest | Verify BufferedOutputScheduler | Frames rendered > 0 |
| `sampleMixer()` | MixerTests | Verify SharedMemoryAudioLine | Output buffer non-zero |
| `mixdown1()` | MixdownManagerTests | Verify MixdownManager.cells() | Audio file generated |

### Baseline Validation Process

Before implementing real-time changes:

1. **Run all baseline tests** to establish current behavior
2. **Capture output audio files** for regression comparison
3. **Record execution times** for performance baseline
4. **Document any flaky tests** that may need special handling

```bash
# Run baseline tests (using MCP test runner)
mcp__ar-test-runner__start_test_run
  module: "compose"
  test_classes: ["PatternElementTests", "PatternFactoryTest", "AudioSceneTest",
                 "StableDurationHealthComputationTest", "MixdownManagerTests"]
  profile: "baseline"
```

---

## Proposed New Tests

### Phase 1 Tests: Buffer-Aware Pattern Rendering

#### Test 1.1: PatternSystemManager Frame-Range Sum

**File**: `compose/src/test/java/org/almostrealism/audio/pattern/test/PatternSystemManagerTest.java`

```java
/**
 * Tests PatternSystemManager.sum() with frame range parameters.
 * Verifies that incremental rendering produces identical output to full rendering.
 */
@Test
public void frameRangeSum() {
    // Setup
    PatternSystemManager manager = createTestManager();
    AudioSceneContext fullContext = createContext(totalFrames);
    AudioSceneContext rangeContext = createContext(bufferSize);

    // Full render (baseline)
    PackedCollection fullOutput = new PackedCollection(totalFrames);
    fullContext.setDestination(fullOutput);
    manager.sum(() -> fullContext, channel).get().run();

    // Incremental render
    PackedCollection incrementalOutput = new PackedCollection(totalFrames);
    for (int start = 0; start < totalFrames; start += bufferSize) {
        rangeContext.setDestination(incrementalOutput.range(shape(bufferSize), start));
        manager.sum(() -> rangeContext, channel, start, bufferSize).get().run();
    }

    // Verify outputs match
    assertArrayEquals(fullOutput.toArray(), incrementalOutput.toArray(), 0.0001);
}
```

**Pass Criteria**: Incremental output matches full render within tolerance.

#### Test 1.2: PatternLayerManager Frame-Range Sum

**File**: `compose/src/test/java/org/almostrealism/audio/pattern/test/PatternLayerManagerFrameRangeTest.java`

```java
/**
 * Tests PatternLayerManager.sum() with frame range parameters.
 * Verifies buffer boundary handling for patterns.
 */
@Test
public void frameRangeSumWithBufferBoundary() {
    // Test with pattern element spanning buffer boundary
    PatternLayerManager manager = createManagerWithBoundarySpanningElement();

    // Render first buffer
    manager.sum(context, voicing, channel, 0, bufferSize).get().run();

    // Render second buffer (should include remainder of spanning note)
    manager.sum(context, voicing, channel, bufferSize, bufferSize).get().run();

    // Verify no audio artifacts at boundary
    verifyNoBoundaryArtifacts(destination, bufferSize);
}
```

**Pass Criteria**: No clicks, gaps, or volume discontinuities at buffer boundaries.

#### Test 1.3: PatternFeatures.renderRange()

**File**: `compose/src/test/java/org/almostrealism/audio/pattern/test/PatternFeaturesRangeTest.java`

```java
/**
 * Tests PatternFeatures.renderRange() for correct partial rendering.
 */
@Test
public void renderRangePartialNote() {
    // Create note that extends beyond buffer
    PatternElement element = createElementWithLongNote();
    int noteLength = 2000; // frames
    int bufferSize = 1024;

    // Render first buffer (partial note)
    renderRange(context, audioContext, List.of(element), false, 0, bufferSize);

    // Verify first bufferSize frames are rendered
    assertNotAllZero(destination.range(shape(bufferSize), 0));

    // Render second buffer (remainder)
    renderRange(context, audioContext, List.of(element), false, bufferSize, bufferSize);

    // Verify remainder is rendered
    assertNotAllZero(destination.range(shape(noteLength - bufferSize), bufferSize));
}
```

**Pass Criteria**: Partial notes render correctly across buffer boundaries.

### Phase 2 Tests: Pattern Render Cell

#### Test 2.1: PatternRenderCell Tick Execution

**File**: `compose/src/test/java/org/almostrealism/audio/pattern/test/PatternRenderCellTest.java`

```java
/**
 * Tests PatternRenderCell tick-based rendering.
 */
@Test
public void tickBasedRendering() {
    PatternRenderCell cell = new PatternRenderCell(manager, contextSupplier, channel, bufferSize);

    // Setup
    cell.setup().get().run();

    // Execute ticks
    for (int i = 0; i < totalFrames / bufferSize; i++) {
        cell.tick().get().run();
    }

    // Verify complete audio generated
    assertNotAllZero(destination);
}
```

**Pass Criteria**: Tick-based rendering produces complete audio.

#### Test 2.2: BatchCell Execution Frequency

**File**: `compose/src/test/java/org/almostrealism/audio/pattern/test/BatchCellTest.java`

```java
/**
 * Tests BatchCell executes wrapped cell at correct frequency.
 */
@Test
public void batchExecutionFrequency() {
    AtomicInteger tickCount = new AtomicInteger(0);
    Cell<PackedCollection> countingCell = createCountingCell(tickCount);

    BatchCell<PackedCollection> batchCell = new BatchCell<>(countingCell, 1024);

    // Execute 4096 ticks
    for (int i = 0; i < 4096; i++) {
        batchCell.tick().get().run();
    }

    // Should have executed 4 times (4096 / 1024)
    assertEquals(4, tickCount.get());
}
```

**Pass Criteria**: Wrapped cell executes exactly N/batchSize times.

### Phase 3 Tests: AudioScene Integration

#### Test 3.1: Real-Time AudioScene Execution

**File**: `compose/src/test/java/org/almostrealism/audio/test/AudioSceneRealtimeTest.java`

```java
/**
 * Tests AudioScene with real-time rendering enabled.
 */
@Test
public void realtimeSceneExecution() {
    AudioScene scene = createTestScene();
    scene.setRealTimeRendering(true);

    // Create buffered output
    PackedCollection output = new PackedCollection(totalFrames);
    BufferedOutputScheduler scheduler = scene.runner(multiChannelOutput)
        .buffer(createOutputLine());

    scheduler.start();
    Thread.sleep(playbackDuration);
    scheduler.stop();

    // Verify audio generated
    assertTrue(scheduler.getRenderedFrames() > 0);
    assertNotAllZero(output);
}
```

**Pass Criteria**: Real-time scene produces audio without errors.

#### Test 3.2: Real-Time vs Non-Real-Time Comparison

**File**: `compose/src/test/java/org/almostrealism/audio/test/AudioSceneComparisonTest.java`

```java
/**
 * Compares real-time and non-real-time rendering output.
 * Critical regression test.
 */
@Test
public void realtimeMatchesNonRealtime() {
    AudioScene scene = createDeterministicTestScene();

    // Non-real-time render
    PackedCollection nonRealtime = renderNonRealtime(scene);

    // Real-time render
    PackedCollection realtime = renderRealtime(scene);

    // Compare outputs (allow small tolerance for timing differences)
    assertArrayEquals(nonRealtime.toArray(), realtime.toArray(), 0.01);
}
```

**Pass Criteria**: Real-time output matches non-real-time within tolerance.

### Phase 4 Tests: Performance and Edge Cases

#### Test 4.1: Performance Benchmark

**File**: `compose/src/test/java/org/almostrealism/audio/test/RealtimePerformanceTest.java`

```java
/**
 * Benchmarks real-time rendering performance.
 */
@Test
public void realtimePerformanceBenchmark() {
    AudioScene scene = createComplexScene();
    int bufferSize = 1024;
    int iterations = 1000;

    long[] renderTimes = new long[iterations];

    for (int i = 0; i < iterations; i++) {
        long start = System.nanoTime();
        renderBuffer(scene, i * bufferSize, bufferSize);
        renderTimes[i] = System.nanoTime() - start;
    }

    // Calculate statistics
    double avgMs = Arrays.stream(renderTimes).average().getAsDouble() / 1_000_000;
    double maxMs = Arrays.stream(renderTimes).max().getAsLong() / 1_000_000.0;

    // Buffer time at 44.1kHz: 1024/44100 = 23.2ms
    double bufferTimeMs = (bufferSize / 44100.0) * 1000;

    log("Average render time: " + avgMs + "ms");
    log("Max render time: " + maxMs + "ms");
    log("Buffer time: " + bufferTimeMs + "ms");

    // Must complete faster than real-time
    assertTrue("Average too slow: " + avgMs, avgMs < bufferTimeMs * 0.8);
    assertTrue("Max too slow: " + maxMs, maxMs < bufferTimeMs);
}
```

**Pass Criteria**: Render time consistently below real-time threshold.

#### Test 4.2: Pattern Element Caching

**File**: `compose/src/test/java/org/almostrealism/audio/pattern/test/PatternCachingTest.java`

```java
/**
 * Verifies pattern element caching doesn't affect correctness.
 */
@Test
public void cachingCorrectness() {
    PatternLayerManager manager = createManagerWithRepeatingPattern();

    // Render without caching
    PackedCollection uncached = renderWithCaching(manager, false);

    // Render with caching
    PackedCollection cached = renderWithCaching(manager, true);

    // Outputs must match
    assertArrayEquals(uncached.toArray(), cached.toArray(), 0.0);
}
```

**Pass Criteria**: Cached rendering produces identical output.

---

## Risk-to-Test Mapping

This section maps each implementation risk from REALTIME_AUDIO_SCENE.md to specific tests.

### Risk 1: Audio Artifacts at Buffer Boundaries

| Aspect | Test Coverage |
|--------|---------------|
| Click prevention | Test 1.2: frameRangeSumWithBufferBoundary |
| Gap prevention | Test 1.3: renderRangePartialNote |
| Note spanning | Test 1.3: renderRangePartialNote |
| Overlap-add | *New test needed*: overlapAddTransitions |

**Additional Test Needed:**
```java
@Test
public void overlapAddTransitions() {
    // Create adjacent buffers
    // Verify smooth transition using overlap-add
    // Measure discontinuity at boundary
}
```

### Risk 2: Volume Instability

**OUT OF SCOPE**: Volume normalization is disabled in real-time mode. A compressor-style
limiter would be the appropriate solution for real-time volume management, but this is
deferred to future work.

### Risk 3: Performance Regression

| Aspect | Test Coverage |
|--------|---------------|
| Render speed | Test 4.1: realtimePerformanceBenchmark |
| Caching efficiency | Test 4.2: cachingCorrectness |
| Worst case | Test 4.1: max render time check |

### Risk 4: Section Processing Complexity

| Aspect | Test Coverage |
|--------|---------------|
| Basic processing | DefaultChannelSectionTest (existing) |
| Incremental | *New test needed*: incrementalSectionProcessing |

**Additional Test Needed:**
```java
@Test
public void incrementalSectionProcessing() {
    // Create section spanning multiple buffers
    // Process incrementally
    // Verify section effects apply correctly
}
```

### Risk 5: Backward Compatibility

| Aspect | Test Coverage |
|--------|---------------|
| API compatibility | All existing tests must pass |
| Output compatibility | Test 3.2: realtimeMatchesNonRealtime |
| Configuration flag | *New test needed*: configurationFlagBehavior |

### Risk 6: Scale Traversal Frame Dependencies

| Aspect | Test Coverage |
|--------|---------------|
| Frame conversion | *New test needed*: scaleTraversalFrameConversion |
| Buffer-relative | *New test needed*: bufferRelativeScalePosition |

**Additional Test Needed:**
```java
@Test
public void scaleTraversalFrameConversion() {
    // Create melodic pattern with scale positions
    // Render incrementally
    // Verify correct notes at each position
}
```

### Risk 7: Note Audio Evaluation Timing

**Heap.stage() OUT OF SCOPE**: The current `PatternFeatures.render()` uses `Heap.stage()` for
memory management during note audio evaluation. This will be bypassed for initial implementation.
If heap memory management proves beneficial for performance, it can be introduced later.

| Aspect | Test Coverage |
|--------|---------------|
| Pre-evaluation | *New test needed*: noteAudioPreEvaluation |

### Risk 8: Section Activity Bias

| Aspect | Test Coverage |
|--------|---------------|
| Bias propagation | *New test needed*: activityBiasPropagation |
| Mid-playback changes | *New test needed*: dynamicActivityBias |

---

## Test Execution Strategy

### Pre-Implementation Phase

1. **Run all baseline tests** and record results
2. **Capture reference audio files** for comparison
3. **Document any existing failures** or flaky tests

### During Implementation

| Phase | Tests to Run | Frequency |
|-------|--------------|-----------|
| Phase 1 | Test 1.1, 1.2, 1.3 + all baseline | After each commit |
| Phase 2 | Test 2.1, 2.2 + Phase 1 tests | After each commit |
| Phase 3 | Test 3.1, 3.2 + Phase 2 tests | After each commit |
| Phase 4 | Test 4.1, 4.2 + all tests | Final validation |

### Post-Implementation Phase

1. **Full regression suite** including all existing and new tests
2. **Performance comparison** with baseline
3. **Audio quality validation** using reference comparisons
4. **Manual listening tests** for subjective quality

### Continuous Integration

```yaml
# Suggested CI configuration
test_realtime:
  runs-on: ubuntu-latest
  steps:
    - name: Run baseline tests
      run: |
        mcp__ar-test-runner__start_test_run
          module: "compose"
          profile: "baseline"

    - name: Run real-time tests
      run: |
        mcp__ar-test-runner__start_test_run
          module: "compose"
          profile: "realtime"
          depth: 2
```

---

## Success Criteria

### Functional Criteria

| Criterion | Measurement | Target |
|-----------|-------------|--------|
| All baseline tests pass | Test count | 100% |
| Real-time output matches non-real-time | Audio diff | < 1% difference |
| No audio artifacts at boundaries | Manual review | Zero artifacts |

### Performance Criteria

| Criterion | Measurement | Target |
|-----------|-------------|--------|
| Average render time | Benchmark test | < 80% of buffer time |
| Maximum render time | Benchmark test | < 100% of buffer time |
| Memory usage | Profiling | < 2x baseline |

### Compatibility Criteria

| Criterion | Measurement | Target |
|-----------|-------------|--------|
| API backward compatible | Compile test | No breaking changes |
| Existing workflows work | Integration tests | 100% pass |
| Configuration opt-in | Manual test | Default behavior unchanged |

---

## Appendix: Test File Locations

### Existing Tests (Baseline)

| Module | Path |
|--------|------|
| ar-audio | `audio/src/test/java/org/almostrealism/audio/` |
| ar-music | `music/src/test/java/org/almostrealism/audio/` |
| ar-compose | `compose/src/test/java/org/almostrealism/audio/` |

### New Tests (To Be Created)

| Test Class | Module | Path |
|------------|--------|------|
| PatternSystemManagerTest | ar-compose | `compose/src/test/java/.../pattern/test/` |
| PatternLayerManagerFrameRangeTest | ar-compose | `compose/src/test/java/.../pattern/test/` |
| PatternFeaturesRangeTest | ar-compose | `compose/src/test/java/.../pattern/test/` |
| PatternRenderCellTest | ar-compose | `compose/src/test/java/.../pattern/test/` |
| BatchCellTest | ar-compose | `compose/src/test/java/.../pattern/test/` |
| AudioSceneRealtimeTest | ar-compose | `compose/src/test/java/.../test/` |
| AudioSceneComparisonTest | ar-compose | `compose/src/test/java/.../test/` |
| RealtimePerformanceTest | ar-compose | `compose/src/test/java/.../test/` |
| PatternCachingTest | ar-compose | `compose/src/test/java/.../pattern/test/` |

---

## Related Documents

- [REALTIME_AUDIO_SCENE.md](./REALTIME_AUDIO_SCENE.md) - Implementation proposal
- [REALTIME_PATTERNS.md](./REALTIME_PATTERNS.md) - Pattern system changes
- [ar-compose README](./README.md) - Module documentation
- [ar-music README](../music/README.md) - Music module documentation
