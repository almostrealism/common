# Real-Time AudioScene Rendering

## Goal

Enable `AudioScene` to render audio at real-time speeds for live playback,
where each buffer must be computed faster than its playback duration.

**Constraint**: At 44,100 Hz sample rate with a 1024-frame buffer:
- Buffer duration: ~23.2 ms
- Render time must be < 23.2 ms per buffer (with headroom for scheduling jitter)

**Current status**: Architecture refactoring is complete, but **audio quality is
incorrect**. Real-time renderer produces mostly silence (0.6% non-zero samples)
while traditional renderer produces full audio. Root cause investigation needed.

---

## The Core Problem

The per-frame tick loop (processing effects, advancing cursors, writing to
output) **must be a compiled hardware-accelerated Loop** for real-time
performance. This requires that everything inside the loop be a `Computation`.

The previous approach used `BatchedCell` with `Periodic` counting to trigger
pattern rendering once per buffer *inside* the loop. This fundamentally breaks
compilation because:

1. Pattern rendering (`PatternSystemManager.sum()`) involves Java-based note
   evaluation, caching, and sample interpolation that cannot be compiled
2. `Periodic` counting logic pollutes the loop with non-compilable operations
3. Even if `renderBatch()` returned a `Computation`, the pattern evaluation
   underneath it would not be compilable

**The solution**: Separate pattern preparation from per-frame processing.
Pattern rendering happens **outside** the per-frame loop, filling a buffer.
The per-frame loop simply reads from that buffer (a compilable operation).

---

## New Architecture

### Separation of Concerns

| Phase | Operation | Location | Compilable? |
|-------|-----------|----------|-------------|
| **Prepare** | Render patterns to buffer | Outside loop | No (Java) |
| **Tick** | Read from buffer, apply effects, write output | Inside loop | **Yes** |
| **Advance** | Increment frame counter | After loop | Yes |

### `PatternRenderCell` Interface Change

**Before** (BatchedCell-based):
```java
class PatternRenderCell extends BatchedCell {
    // tick() counts to batchSize, then calls renderBatch()
    // renderBatch() calls PatternSystemManager.sum()
    // Problem: tick() contains non-compilable work
}
```

**After** (direct Cell):
```java
class PatternRenderCell extends CellAdapter<PackedCollection> {
    // prepareBatch(frames) - renders patterns into output buffer (outside loop)
    // tick() - no-op or simple cursor logic (compilable, inside loop)
    // push() - forwards output buffer to receptor (compilable)
}
```

### `runnerRealTime` Structure

```java
public TemporalCellular runnerRealTime(output, channels, bufferSize) {
    Cells cells = getCells(output, channels, bufferSize, frameSupplier);

    // Collect all PatternRenderCells that need batch preparation
    List<PatternRenderCell> renderCells = collectRenderCells(cells);

    return new TemporalCellular() {
        Supplier<Runnable> tick() {
            OperationList tick = new OperationList();

            // 1. OUTSIDE LOOP: Prepare pattern data for this buffer
            for (PatternRenderCell cell : renderCells) {
                tick.add(cell.prepareBatch(bufferSize));
            }

            // 2. INSIDE LOOP: Compilable per-frame processing
            tick.add(loop(cells.tick(), bufferSize));  // THIS MUST COMPILE

            // 3. AFTER LOOP: Advance frame position
            tick.add(() -> () -> currentFrame[0] += bufferSize);

            return tick;
        }
    };
}
```

### Data Flow

```
runnerRealTime.tick()
  |
  v
[OUTSIDE LOOP - Java, per buffer]
PatternRenderCell.prepareBatch(1024)
  -> PatternSystemManager.sum(ctx, channel, startFrame, 1024)
  -> PatternFeatures.render() for each overlapping note
  -> Result written to PatternRenderCell.outputBuffer
  |
  v
[INSIDE LOOP - Compiled, per frame]
loop(cells.tick(), 1024)
  |
  +-> PatternRenderCell.tick()     // No-op or index advance
  +-> Effects pipeline cells       // Compilable DSP
  +-> WaveOutput cursor advance    // Compilable
  |
  v
[AFTER LOOP]
currentFrame[0] += 1024
```

---

## Implementation Plan

### Step 1: Refactor `PatternRenderCell` to not extend `BatchedCell` ✅ COMPLETE

**File**: `compose/src/main/java/org/almostrealism/audio/pattern/PatternRenderCell.java`

- ✅ Removed `extends BatchedCell`, replaced with `extends CellAdapter<PackedCollection>`
- ✅ Added `prepareBatch()` method that calls `PatternSystemManager.sum()`
- ✅ Changed `tick()` to return an empty `OperationList` (compilable no-op)
- ✅ Kept `getOutputProducer()` and output buffer management
- ✅ Frame position provided via `IntSupplier` constructor argument

### Step 2: Update `AudioScene.getPatternChannel()` ✅ COMPLETE

**File**: `compose/src/main/java/org/almostrealism/audio/AudioScene.java`

- ✅ `PatternRenderCell` no longer uses BatchedCell parameters
- ✅ Changed `setup.add(renderCell.renderNow())` to `setup.add(renderCell.prepareBatch())`
- ✅ Render cells are tracked via CellList requirements

### Step 3: Update `AudioScene.runnerRealTime()` ✅ COMPLETE

**File**: `compose/src/main/java/org/almostrealism/audio/AudioScene.java`

- ✅ Added `collectRenderCells()` helper method to find all PatternRenderCells
- ✅ In `tick()`, `prepareBatch()` is called for each render cell **before** the loop
- ✅ Updated Javadoc to reflect the new architecture

### Step 4: Verify `cells.tick()` is Compilable ✅ VERIFIED

**Status**: COMPLETE - all 11 tests pass

- ✅ `PatternRenderCell.tick()` returns an empty `OperationList` (compilable no-op)
- ✅ Pattern rendering now happens outside the loop via `prepareBatch()`
- ✅ All `AudioSceneRealTimeCorrectnessTest` tests pass (11/11)

### Step 5: Test Verification ⚠️ TESTS PASS BUT AUDIO QUALITY INCORRECT

**Test Results** (2026-02-03):
- All 11 tests pass (no exceptions thrown)
- However, **spectrogram analysis reveals real-time output is mostly silence**

| Test | Status | Audio Quality |
|------|--------|---------------|
| `realTimeRunnerPerformance` | ✅ PASS | Not assessed (perf only) |
| `realTimeProducesAudio` | ✅ PASS | ❌ 0.6% non-zero (bad) |
| `realTimeMatchesTraditional` | ✅ PASS | ❌ Real-time output nearly silent |
| `realTimeFrameAdvancement` | ✅ PASS | Not assessed |
| `multiBufferWithEffects` | ✅ PASS | Not assessed |
| `batchCellArchitectureValidation` | ✅ PASS | N/A (unit test) |

**Conclusion**: Tests verify the architecture doesn't crash, but the audio data
flow is broken. See "Audio Quality Assessment" section below for details.

---

## Key Files

| File | Changes | Status |
|------|---------|--------|
| `PatternRenderCell.java` | Removed BatchedCell, added `prepareBatch()`, `tick()` is no-op | ✅ Complete |
| `AudioScene.java` | Updated `getPatternChannel()`, `runnerRealTime()`, added `collectRenderCells()` | ✅ Complete |
| `BatchedCell.java` | No changes (still useful for other use cases) |
| `Periodic.java` | No changes (still useful for other use cases) |

---

## What `BatchedCell` and `Periodic` Are Still Good For

`BatchedCell` and `Periodic` remain valuable for cases where:

1. The batched operation **is** compilable (e.g., periodic state snapshots)
2. The tick rate is already per-buffer (not per-sample)
3. The counting logic needs to be part of the compiled code

They are **not** appropriate when the batched operation is fundamentally
non-compilable (like pattern rendering with Java-based note evaluation).

---

## Success Criteria

| # | Criterion | Status |
|---|-----------|--------|
| 1 | `runnerRealTime().tick()` contains a `loop()` call where the body is a compilable `Computation` | ✅ MET |
| 2 | Pattern rendering happens entirely outside the loop | ✅ MET (architecturally) |
| 3 | Per-buffer render time is < 23.2 ms | ❌ NOT MET (319 ms avg) |
| 4 | All existing correctness tests pass | ⚠️ PARTIAL (tests pass, but audio is wrong) |
| 5 | Real-time output matches traditional output | ❌ NOT MET (0.6% vs 100% non-zero) |

---

## Test Coverage

### Test Infrastructure ✅ COMPLETE

The test suite has been refactored to use a shared helper infrastructure:

| Class | Purpose |
|-------|---------|
| `RealTimeTestHelper` | Consolidates common setup, rendering, and verification |
| `AudioStats` | Correctness verification (amplitude, RMS, non-zero ratio) |
| `TimingStats` | Performance analysis (buffer timing, overruns, real-time ratio) |
| `RenderResult` | Bundles output file, audio stats, and timing stats |

### Test Categories

**Correctness Tests** (generate visual artifacts):
- `realTimeProducesAudio` - verifies non-silent output
- `realTimeMatchesTraditional` - compares with baseline
- `multiBufferWithEffects` - verifies effects integration

**Performance Tests** (informational, no assertions):
- `realTimeRunnerPerformance` - measures buffer timing vs real-time constraint
- `renderTimingVsBufferSize` - checks if render time scales with buffer size

**Diagnostic Tests** (verify mechanics):
- `batchCellArchitectureValidation` - verifies BatchedCell counting/callback
- `realTimeFrameAdvancement` - verifies frame tracking across buffers
- `frameRangeSumProducesAudio` - tests PatternSystemManager.sum() in isolation
- `frameRangeSumMultipleBuffers` - tests buffer stitching
- `frameRangeWithEffects` - tests cell pipeline setup
- `renderNoteAudioLengthAnalysis` - quantifies rendering waste

### Visual Artifacts

Correctness tests generate artifacts in `results/` for manual review:
- `<test>-spectrogram.png` - frequency content visualization
- `<test>-summary.txt` - human-readable statistics

---

## Audio Quality Assessment (2026-02-03)

### Spectrogram Review Summary

| Spectrogram | Audio Quality | Assessment |
|-------------|---------------|------------|
| `realtime-sine-baseline` | ✅ **GOOD** | Clear horizontal line at low frequency. Sine wave rendered correctly. |
| `realtime-freq-sweep` | ✅ **GOOD** | Diagonal line from low to high frequency. Classic sweep pattern. |
| `realtime-multi-freq` | ✅ **GOOD** | Multiple horizontal bands. Multi-frequency content present. |
| `realtime-delayed-sine` | ✅ **GOOD** | Sine starting after delay. Timing works correctly. |
| `realtime-pipeline-a` | ✅ **GOOD** | Frequency content with vertical stripes. Pipeline produces audio. |
| `realtime-pipeline-b` | ✅ **GOOD** | Similar to pipeline-a. Effects chain works. |
| `audioscene-traditional-baseline` | ✅ **GOOD** | Dense vertical stripes. Traditional renderer produces full audio. |
| `realtime-correctness` | ❌ **BAD** | Nearly black. Only 0.6% non-zero samples. Mostly silence. |
| `comparison-realtime` | ❌ **BAD** | Nearly black. Real-time AudioScene produces silence. |
| `audioscene-realtime-simple` | ❌ **BAD** | Mostly black with possible brief content. |
| `audioscene-realtime-buffers` | ⚠️ **UNCLEAR** | White/grey - may have content but unclear. |
| `audioscene-realtime-timed` | ⚠️ **UNCLEAR** | White/grey - similar to buffers test. |

### Key Finding

**Simple waveform generation works correctly**, but **AudioScene pattern rendering
in real-time mode produces mostly silence**.

The evidence:
- All sine wave tests (baseline, sweep, multi-freq, delayed) show clear frequency content
- Pipeline tests show audio passing through effects chains
- Traditional AudioScene baseline shows dense audio content
- Real-time AudioScene tests show nearly black spectrograms (silence)

### Quantitative Evidence

From `realtime-correctness-summary.txt`:
```
Audio Statistics:
  Non-Zero Ratio: 0.6%      <-- PROBLEM: 99.4% of samples are zeros
  Max Amplitude: 0.999310   <-- Brief spike exists
  RMS Level: 0.037707       <-- Very low energy overall

Timing Statistics:
  Avg Buffer Time: 319.055 ms
  Target Buffer Time: 23.220 ms
  Real-Time Ratio: 0.07x    <-- ~14x slower than real-time
```

### Root Cause Hypothesis

The architecture separation (prepareBatch outside loop, tick inside loop) is
implemented, but **the data flow from `prepareBatch()` output to `tick()`
consumption may not be connected correctly**.

Possibilities:
1. `prepareBatch()` writes to a buffer, but `tick()` reads from a different buffer
2. The frame position passed to `PatternSystemManager.sum()` may be incorrect
3. The output buffer may not be properly forwarded through the cell receptor chain
4. `CellList` requirements collection may not find all `PatternRenderCell` instances

### Next Investigation Steps

1. **Trace data flow**: Add logging to verify `prepareBatch()` output buffer
   contains audio, and `tick()` reads from the same buffer
2. **Verify frame positions**: Log frame ranges passed to `PatternSystemManager.sum()`
3. **Check CellList.getRequirements()**: Verify all `PatternRenderCell` instances
   are discovered by `collectRenderCells()`
4. **Compare traditional vs real-time cell graphs**: The traditional renderer works,
   so compare the cell pipeline structure
