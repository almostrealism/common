# Real-Time AudioScene Test Plan

## Implementation Status

> **STATUS: BASELINE TEST PASSING** (January 2026)
>
> **The baseline test is now implemented and passing.** `AudioSceneBaselineTest` demonstrates
> that `AudioScene` produces non-silent audio through the traditional pipeline without
> `AudioSceneOptimizer`. All 3 test methods pass.
>
> ### Baseline Test Results (January 2026)
>
> | Test | Result | Details |
> |------|--------|---------|
> | `baselineAudioGeneration` | **PASS** | Seed 42: 487 elements, health score=0.0195, 176,400 frames |
> | `baselineHealthComputation` | **PASS** | Best seed 56 (615 elements), health score=0.0195, 176,400 stable frames |
> | `baselineMelodicContent` | **PASS** | Seed 42: 140 melodic elements |
>
> ### Key Findings During Implementation
>
> 1. **`scene.runner()` vs `scene.getCells()`**: `getCells()` stores pattern rendering in
>    `AudioScene.this.setup`, NOT in the returned cells. Using `getCells()` directly and calling
>    `cells.sec()` only runs `cells.setup()`, missing all pattern rendering. Must use
>    `scene.runner()` which wraps both setup operations into a `TemporalCellular`.
>
> 2. **`sec()` vs `TemporalRunner`**: Even with `scene.runner()`, using `sec()` to compile
>    setup+tick into a single `OperationList` still produced silence. The working path is
>    `StableDurationHealthComputation.computeHealth()`, which uses `TemporalRunner` internally
>    (compiling setup and tick separately via `runner.get()` and `runner.getContinue()`).
>
> 3. **`StableDurationHealthComputation` flow**: The correct pipeline is:
>    - Create `StableDurationHealthComputation(channelCount)` which builds
>      `MultiChannelAudioOutput` with master WaveOutput, per-channel stems, and AudioMeter measures
>    - Call `scene.runner(health.getOutput())` to get a `TemporalCellular`
>    - Call `health.setTarget(runner)` to wrap it in a `TemporalRunner`
>    - Call `health.computeHealth()` to execute the full setup+tick cycle
>
> ### What Is Needed Next
>
> 1. **Compare traditional output to real-time output** - Baseline now exists for comparison
> 2. **Real-time code path validation** - `runnerRealTime()` output pipeline not connected
> 3. **Performance optimization** - Operations need hardware acceleration

## Executive Summary

This document outlines the testing strategy for the real-time AudioScene rendering implementation. It inventories existing tests, identifies coverage gaps, proposes new tests, and maps implementation risks to specific test requirements.

## Table of Contents

1. [Existing Test Inventory](#existing-test-inventory)
2. [Coverage Analysis](#coverage-analysis)
3. [Baseline Tests](#baseline-tests)
4. [**Proposed Baseline Test: AudioScene Audio Generation**](#proposed-baseline-test-audioscene-audio-generation) *(NEW)*
5. [Proposed New Tests](#proposed-new-tests)
6. [Risk-to-Test Mapping](#risk-to-test-mapping)
7. [Test Execution Strategy](#test-execution-strategy)
8. [Success Criteria](#success-criteria)

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

## Proposed Baseline Test: AudioScene Audio Generation

> **Priority: CRITICAL** - This test must pass before any real-time work can proceed.
>
> This section proposes a standalone test that proves AudioScene can generate non-silent audio
> through the traditional (non-real-time) pipeline, **without relying on `AudioSceneOptimizer`**.
> This is the foundational baseline against which real-time output will be compared.

### Test File

`compose/src/test/java/org/almostrealism/audio/pattern/test/AudioSceneBaselineTest.java`

### Design Goals

1. **Programmatic construction** - No dependency on `pattern-factory.json` or `scene-settings.json`
2. **Portable** - Uses `FileNoteSource` with sample files from `Samples/` directory in the project root
3. **Reliable** - Tries multiple genomes to handle stochastic element generation
4. **Deterministic (once found)** - Records the working random seed for repeatability
5. **Both melodic and non-melodic** - Tests both code paths for note generation
6. **Minimal dependencies** - Uses the standard `getCells()` → `runner()` → `sec()` path

### Available Test Samples

Located at `/workspace/project/Samples/`:

| File | Type | Purpose |
|------|------|---------|
| `PMMC_Snares_Lush.wav` | Non-melodic | Snare percussion |
| `PMMC_Snares_Pop_Vibe.wav` | Non-melodic | Snare percussion |
| `PMMC_Snares_Tiger.wav` | Non-melodic | Snare percussion |
| `PMMC_Snares_Tight.wav` | Non-melodic | Snare percussion |
| `Perc TAT 1.wav` | Non-melodic | Percussion hit |
| `Perc TAT 2.wav` | Non-melodic | Percussion hit |
| `Perc TAT 3.wav` | Non-melodic | Percussion hit |
| `Snare Eclipse 1.wav` | Non-melodic | Snare hit |
| `Snare Eclipse 2.wav` | Non-melodic | Snare hit |
| `Snare TripTrap 5.wav` | Non-melodic | Snare hit |
| `DX Punch S612 C0.wav` - `C5.wav` | Melodic | Synth per-octave (C0-C5) |
| `Synth Guitar S612 C0.wav` - `C3.wav` | Melodic | Guitar per-octave (C0-C3) |

### Scene Configuration

The test scene mirrors `AudioSceneOptimizer.createScene()` structure but uses `FileNoteSource`
instead of `TreeNoteSource`:

```java
// Scene parameters matching AudioSceneOptimizer defaults
double bpm = 120.0;
int sourceCount = 6;   // 6 channels (matching AudioScene.DEFAULT_SOURCE_COUNT)
int delayLayers = 3;   // 3 delay layers (matching AudioScene.DEFAULT_DELAY_LAYERS)
int sampleRate = OutputLine.sampleRate;  // 44100

AudioScene<?> scene = new AudioScene<>(bpm, sourceCount, delayLayers, sampleRate);
scene.setTotalMeasures(16);
scene.setTuning(new DefaultKeyboardTuning());
```

### NoteAudioChoice Configuration

The test creates choices covering both non-melodic (percussive) and melodic channels:

```java
// --- Non-melodic choices (channels 0-3) ---

// Channel 0: Snares (multiple sources for variety)
NoteAudioChoice snares = NoteAudioChoice.fromSource(
    "Snares",
    new FileNoteSource(samplesDir + "/PMMC_Snares_Lush.wav", WesternChromatic.C1),
    0, 9, false);
snares.getSources().add(new FileNoteSource(samplesDir + "/PMMC_Snares_Pop_Vibe.wav", WesternChromatic.C1));
snares.getSources().add(new FileNoteSource(samplesDir + "/PMMC_Snares_Tiger.wav", WesternChromatic.C1));
snares.getSources().add(new FileNoteSource(samplesDir + "/PMMC_Snares_Tight.wav", WesternChromatic.C1));

// Channel 1: Percussion hits
NoteAudioChoice percs = NoteAudioChoice.fromSource(
    "Percs",
    new FileNoteSource(samplesDir + "/Perc TAT 1.wav", WesternChromatic.C1),
    1, 9, false);
percs.getSources().add(new FileNoteSource(samplesDir + "/Perc TAT 2.wav", WesternChromatic.C1));
percs.getSources().add(new FileNoteSource(samplesDir + "/Perc TAT 3.wav", WesternChromatic.C1));

// Channel 2: Snare accents
NoteAudioChoice snareAccents = NoteAudioChoice.fromSource(
    "Snare Accents",
    new FileNoteSource(samplesDir + "/Snare Eclipse 1.wav", WesternChromatic.C1),
    2, 9, false);
snareAccents.getSources().add(new FileNoteSource(samplesDir + "/Snare Eclipse 2.wav", WesternChromatic.C1));
snareAccents.getSources().add(new FileNoteSource(samplesDir + "/Snare TripTrap 5.wav", WesternChromatic.C1));

// --- Melodic choices (channels 3-5) ---

// Channel 3: DX Punch synth (multi-octave)
NoteAudioChoice dxPunch = NoteAudioChoice.fromSource(
    "DX Punch",
    new FileNoteSource(samplesDir + "/DX Punch S612 C0.wav", WesternChromatic.C0),
    3, 9, true);
dxPunch.getSources().add(new FileNoteSource(samplesDir + "/DX Punch S612 C1.wav", WesternChromatic.C1));
dxPunch.getSources().add(new FileNoteSource(samplesDir + "/DX Punch S612 C2.wav", WesternChromatic.C2));
dxPunch.getSources().add(new FileNoteSource(samplesDir + "/DX Punch S612 C3.wav", WesternChromatic.C3));
dxPunch.getSources().add(new FileNoteSource(samplesDir + "/DX Punch S612 C4.wav", WesternChromatic.C4));
dxPunch.getSources().add(new FileNoteSource(samplesDir + "/DX Punch S612 C5.wav", WesternChromatic.C5));

// Channel 4: Synth Guitar (multi-octave)
NoteAudioChoice synthGuitar = NoteAudioChoice.fromSource(
    "Synth Guitar",
    new FileNoteSource(samplesDir + "/Synth Guitar S612 C0.wav", WesternChromatic.C0),
    4, 9, true);
synthGuitar.getSources().add(new FileNoteSource(samplesDir + "/Synth Guitar S612 C1.wav", WesternChromatic.C1));
synthGuitar.getSources().add(new FileNoteSource(samplesDir + "/Synth Guitar S612 C2.wav", WesternChromatic.C2));
synthGuitar.getSources().add(new FileNoteSource(samplesDir + "/Synth Guitar S612 C3.wav", WesternChromatic.C3));

// Channel 5: DX Punch bass (reuse with different channel)
NoteAudioChoice dxBass = NoteAudioChoice.fromSource(
    "DX Bass",
    new FileNoteSource(samplesDir + "/DX Punch S612 C0.wav", WesternChromatic.C0),
    5, 9, true);
dxBass.getSources().add(new FileNoteSource(samplesDir + "/DX Punch S612 C1.wav", WesternChromatic.C1));
```

### Pattern Setup

```java
// Add all choices to pattern manager
scene.getPatternManager().getChoices().addAll(
    List.of(snares, percs, snareAccents, dxPunch, synthGuitar, dxBass));

// Create patterns for each channel (matching optimizer: 1 pattern per channel)
for (int ch = 0; ch < sourceCount; ch++) {
    boolean melodic = ch >= 3;  // Channels 3-5 are melodic
    PatternLayerManager pattern = scene.getPatternManager().addPattern(ch, 1.0, melodic);
    pattern.setLayerCount(3);  // Multiple layers for more elements
}

// Add section covering the full arrangement
scene.addSection(0, 16);
```

### Multi-Genome Search Strategy

The critical innovation: try multiple random seeds to find a genome that produces audio.

```java
/**
 * Searches for a genome that produces non-silent audio output.
 *
 * <p>Pattern element generation is stochastic. A random genome may produce
 * zero elements if noteSelection + bias < 0 for all positions. This method
 * tries multiple seeds to find one that works, then records the seed for
 * reproducibility.</p>
 *
 * @param scene Configured AudioScene
 * @param maxAttempts Maximum number of genomes to try
 * @return The random seed that produced audio, or -1 if none found
 */
private long findWorkingGenomeSeed(AudioScene<?> scene, int maxAttempts) {
    for (int attempt = 0; attempt < maxAttempts; attempt++) {
        long seed = 42 + attempt;  // Deterministic seed sequence
        Random random = new Random(seed);

        // Create genome with this seed
        scene.assignGenome(scene.getGenome().random(random));

        // Check if patterns have elements
        int totalElements = 0;
        for (PatternLayerManager plm : scene.getPatternManager().getPatterns()) {
            List<PatternElement> elements = plm.getAllElements(0.0, plm.getDuration());
            totalElements += elements.size();
        }

        log("Seed " + seed + ": " + totalElements + " total pattern elements");

        if (totalElements > 0) {
            // Try rendering to verify audio is actually produced
            WaveOutput output = new WaveOutput(() -> new File("results/baseline-seed-" + seed + ".wav"),
                24, true);
            Cells cells = scene.getCells(new MultiChannelAudioOutput(output));
            cells.sec(4.0).get().run();  // 4 seconds (2 measures at 120 BPM)
            output.write().get().run();

            // Verify non-silence
            File outFile = new File("results/baseline-seed-" + seed + ".wav");
            if (outFile.exists() && hasAudioContent(outFile)) {
                log("SUCCESS: Seed " + seed + " produces audio with " + totalElements + " elements");
                return seed;
            } else {
                log("Seed " + seed + " has elements but output is silent");
            }
        }
    }

    return -1;  // No working seed found
}

/**
 * Checks if a WAV file contains non-silent audio.
 */
private boolean hasAudioContent(File wavFile) {
    try {
        WaveData data = WaveData.load(wavFile);
        PackedCollection samples = data.getData();
        int length = samples.getShape().getTotalSize();

        double maxAmplitude = 0;
        for (int i = 0; i < length; i++) {
            double val = Math.abs(samples.valueAt(i));
            if (val > maxAmplitude) maxAmplitude = val;
        }

        return maxAmplitude > 0.001;
    } catch (Exception e) {
        return false;
    }
}
```

### Test Methods

#### Test 1: `baselineAudioGeneration` - Core Baseline

```java
/**
 * Verifies that AudioScene produces non-silent audio through the
 * traditional (non-real-time) pipeline without AudioSceneOptimizer.
 *
 * <p>This is the foundational test. If this fails, there is no point
 * testing real-time rendering.</p>
 */
@Test
public void baselineAudioGeneration() {
    AudioScene<?> scene = createBaselineScene();

    // Disable effects for clean audio verification
    MixdownManager.enableMainFilterUp = false;
    MixdownManager.enableEfxFilters = false;
    MixdownManager.enableEfx = false;

    long workingSeed = findWorkingGenomeSeed(scene, 20);
    assertTrue("At least one genome out of 20 should produce audio", workingSeed >= 0);

    log("Working seed: " + workingSeed);
    log("This seed can be used for deterministic reproduction");
}
```

**Pass Criteria**: At least 1 out of 20 genomes produces non-silent audio output.

#### Test 2: `baselineWithEffects` - Effects Chain

```java
/**
 * Verifies audio generation with the effects chain enabled.
 * Uses the working seed from baseline test.
 */
@Test
public void baselineWithEffects() {
    AudioScene<?> scene = createBaselineScene();
    Random random = new Random(KNOWN_WORKING_SEED);
    scene.assignGenome(scene.getGenome().random(random));

    // Leave effects enabled (default)
    WaveOutput output = new WaveOutput(() -> new File("results/baseline-with-effects.wav"), 24, true);
    Cells cells = scene.getCells(new MultiChannelAudioOutput(output));
    cells.sec(4.0).get().run();
    output.write().get().run();

    assertTrue("Effects pipeline should produce audio",
        hasAudioContent(new File("results/baseline-with-effects.wav")));
}
```

**Pass Criteria**: Audio is non-silent with effects enabled.

#### Test 3: `baselineMelodicContent` - Melodic Path

```java
/**
 * Verifies that melodic channels produce pitched audio content.
 * Uses individual channel rendering to isolate melodic output.
 */
@Test
public void baselineMelodicContent() {
    AudioScene<?> scene = createBaselineScene();

    // Search for a seed that produces melodic elements specifically
    long melodicSeed = findSeedWithMelodicElements(scene, 20);
    assertTrue("At least one genome should produce melodic elements", melodicSeed >= 0);

    log("Melodic working seed: " + melodicSeed);
}
```

**Pass Criteria**: At least 1 genome produces elements on melodic channels (3-5).

#### Test 4: `baselineHealthComputation` - StableDurationHealthComputation Path

```java
/**
 * Verifies that StableDurationHealthComputation can evaluate the
 * baseline scene. This confirms compatibility with the optimizer path.
 */
@Test
public void baselineHealthComputation() {
    AudioScene<?> scene = createBaselineScene();
    Random random = new Random(KNOWN_WORKING_SEED);
    scene.assignGenome(scene.getGenome().random(random));

    WaveOutput output = new WaveOutput(() -> new File("results/baseline-health.wav"), 24, true);
    Cells cells = scene.getCells(new MultiChannelAudioOutput(output));

    // Use StableDurationHealthComputation to evaluate
    StableDurationHealthComputation health = new StableDurationHealthComputation(cells);
    health.setMaxDuration(4.0);
    health.setMaster(output);

    AudioHealthScore score = health.computeHealth();

    log("Health score: " + score.getScore());
    log("Stable frames: " + score.getFrameCount());

    assertTrue("Health computation should complete", score.getFrameCount() > 0);
    assertTrue("Audio should have non-zero score", score.getScore() > 0);
}
```

**Pass Criteria**: Health computation completes with non-zero score and frame count.

### Execution Notes

- The `Samples/` directory must be present at `../../Samples/` relative to the test working directory
  (i.e., in the project root alongside `common/`)
- Tests should be annotated with `@TestDepth(2)` or higher since they involve audio rendering
- Results directory should be created before running: `compose/results/`
- The first run will search for working seeds; subsequent runs can use discovered seeds
  (record them as `KNOWN_WORKING_SEED` constants once found)
- These tests are **prerequisite** for all real-time comparison tests

### Success Criteria

| Criterion | Target |
|-----------|--------|
| At least 1/20 genomes produces audio | **Required** |
| Audio max amplitude > 0.001 | **Required** |
| Audio RMS > 0.0001 | **Required** |
| Both melodic and non-melodic paths exercised | **Required** |
| StableDurationHealthComputation compatible | **Required** |
| Working seed recorded for reproducibility | **Required** |

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
