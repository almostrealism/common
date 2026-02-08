# Real-Time AudioScene Rendering

## Goal

Enable `AudioScene` to render audio at real-time speeds for live playback,
where each buffer must be computed faster than its playback duration.

**Constraint**: At 44,100 Hz sample rate with a 4096-frame buffer:
- Buffer duration: ~92.9 ms
- Render time must be < 92.9 ms per buffer (with headroom for scheduling jitter)

---

## Architecture

### Separation of Concerns

| Phase | Operation | Location | Compilable? |
|-------|-----------|----------|-------------|
| **Prepare** | Render patterns to buffer | Outside loop | No (Java) |
| **Tick** | Read from buffer, apply effects, write output | Inside loop | **Yes** |
| **Advance** | Increment frame counter | After loop | Yes |

### `runnerRealTime` Structure

```java
public TemporalCellular runnerRealTime(output, channels, bufferSize) {
    Cells cells = getCells(output, channels, bufferSize, frameSupplier);

    return new TemporalCellular() {
        Supplier<Runnable> tick() {
            OperationList tick = new OperationList();

            // 1. OUTSIDE LOOP: Prepare pattern data for this buffer
            for (PatternAudioBuffer cell : renderCells) {
                tick.add(cell.prepareBatch(bufferSize));
            }

            // 2. INSIDE LOOP: Compilable per-frame processing
            tick.add(loop(cells.tick(), bufferSize));

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
PatternAudioBuffer.prepareBatch(4096)
  -> PatternSystemManager.sum(ctx, channel, startFrame, 4096)
  -> PatternFeatures.render() for each overlapping note
  -> Result written to PatternAudioBuffer.outputBuffer
  |
  v
[INSIDE LOOP - Compiled, per frame]
loop(cells.tick(), 4096)
  |
  +-> PatternAudioBuffer.tick()     // No-op or index advance
  +-> Effects pipeline cells       // Compilable DSP
  +-> WaveOutput cursor advance    // Compilable
  |
  v
[AFTER LOOP]
currentFrame[0] += 4096
```

### WaveCell Frame Control

WaveCells use external frame control mode for the real-time path. A
`PackedCollection(1)` holds the buffer-local frame index (0 to bufferSize-1),
reset at each buffer start. This is passed via `wWithExternalFrame()` and
plumbed through `EfxManager.apply()` → `createCells()`.

---

## Key Files

| File | Role |
|------|------|
| `PatternAudioBuffer.java` | Buffer holder with `prepareBatch()`, not a Cell |
| `AudioScene.java` | `runnerRealTime()` orchestration |
| `PatternFeatures.java` | `render()` — unified offline/real-time note rendering |
| `PatternLayerManager.java` | `sumInternal()` — repetition-aware render dispatch |
| `RenderedNoteAudio.java` | Per-note audio + offset arg for partial rendering |
| `ScaleTraversalStrategy.java` | Creates `RenderedNoteAudio` with offset PackedCollection |
| `SamplingFeatures.java` | `sampling(rate, offset, frameCount, supplier)` |

---

## Render Path Priority in `PatternFeatures.render()`

```
1. Cache hit → return cached full audio (fastest, O(1) memory read)
2. Cache miss + cache available → full evaluation + store in cache
3. No cache + partial available → partial evaluation (overlap frames only)
4. No cache + no partial → full evaluation (no caching)
```

Partial rendering is only used when no `NoteAudioCache` is available. Even
with cached kernels, partial rendering requires kernel invocation per tick.
Cache hits are zero-computation memory reads. For a note spanning N buffers:

- Full + cache: 1 evaluation + (N-1) cache reads ≈ **280ms + 0ms**
- Partial × N: N kernel invocations ≈ **N × 17ms**

---

## Signature-Independent Partial Rendering

Partial rendering evaluates only the overlap frames of a note instead of
the full note. The computation signature must be independent of the start
frame position so compiled kernels can be reused across different buffer ticks.

### Mechanism

`SamplingFeatures.sampling(rate, PackedCollection offset, int frameCount, Supplier r)`
constructs frame indices as `integers(0, frameCount).add(p(offset))`. The offset
is a caller-owned `PackedCollection` whose value is set before each evaluation.
`CollectionProviderProducer.signature()` returns `"offset:memLength|shapeDetails"`
based on the memory address of the PackedCollection, not the stored value. Same
PackedCollection instance → same signature → compiled kernel reuse.

### Call Chain

```
ScaleTraversalStrategy.createRenderedNote()
  creates PackedCollection(1) per note → stored on RenderedNoteAudio.offsetArg
  factory closure captures offsetArg, passes it through:
    → PatternElement.getNoteAudio(..., PackedCollection offset, int frameCount)
    → PatternNoteAudio.getAudio(..., PackedCollection offset, int frameCount)
    → PatternNoteAudioAdapter.computeAudio(..., PackedCollection offset, int frameCount)
    → SamplingFeatures.sampling(rate, offset, frameCount, supplier)
```

In `PatternFeatures.render()`:
```java
note.getOffsetArg().setMem(0, pSourceOffset);  // set runtime value
Producer partial = note.getPartialProducer(pOverlapLength);  // build/reuse graph
PackedCollection audio = traverse(1, partial).get().evaluate();  // execute kernel
```

### Performance

| Note | Full Eval | Partial Eval | Speedup | Kernel |
|------|-----------|-------------|---------|--------|
| 1 (cold) | 273.9ms / 10195 frames | 20.6ms / 4096 frames | 13.30x | Compiled |
| 2 | 19.0ms / 10195 frames | 16.8ms / 4096 frames | 1.13x | Reused |
| 3 | 16.5ms / 10063 frames | 14.5ms / 4096 frames | 1.14x | Reused |

Note 1 incurs compilation cost. Notes 2-3 reuse the compiled kernel, narrowing
the speedup to ~1.13x (pure frame-count reduction). All partial evaluations
produced correct audio.

---

## Current Performance

With 4096-frame buffers (~93ms at 44.1kHz) and ~350 pattern elements:

| Metric | Value |
|--------|-------|
| Avg Buffer Time | ~220 ms |
| Real-Time Ratio | ~0.42x |
| Audio Quality | 99.9% non-zero, RMS ~0.32 |

The bottleneck is **first-time note evaluation** — each unique instrument chain
costs ~270ms to compile. With many pattern elements, the aggregate cost across
all buffers is high. Caching prevents re-evaluation, but the first buffer that
encounters each note pays the full compilation cost.

---

## Configuration

### `enableRedundantCompilation` (Environment Variable)

`AR_REDUNDANT_COMPILATION=disabled` prevents redundant compilation by forcing
the `InstructionSetManager` to reuse cached compiled kernels when the computation
signature matches. Default is enabled (for backward compatibility).

**File**: `hardware/.../AcceleratedComputationEvaluable.java`

### `Heap.stage()` Removal

`Heap.stage()` was removed from note evaluation in `PatternFeatures.render()`.
This eliminates per-note overhead from creating/destroying `OperationAdapter`
wrappers. The instruction cache (`FrequencyCache`) is independent of
`Heap.stage()`. Without staged cleanup, temporary allocations are reclaimed by
GC. Monitor for memory growth during long renders.

---

## Test Coverage

| Class | Purpose |
|-------|---------|
| `RealTimeTestHelper` | Shared setup, rendering, and verification |
| `AudioStats` | Correctness verification (amplitude, RMS, non-zero ratio) |
| `TimingStats` | Performance analysis (buffer timing, overruns, real-time ratio) |

### Tests

| Test | Category | What it verifies |
|------|----------|-----------------|
| `realTimeProducesAudio` | Correctness | Non-silent output |
| `realTimeMatchesTraditional` | Correctness | Comparison with baseline |
| `multiBufferWithEffects` | Correctness | Effects integration |
| `realTimeRunnerPerformance` | Performance | Buffer timing vs real-time constraint |
| `renderTimingVsBufferSize` | Performance | Render time scaling with buffer size |
| `partialNoteRenderingPerformance` | Performance | Partial vs full evaluation speedup |
| `cacheWarmingBenefit` | Performance | Cold vs warm render comparison |
| `batchCellArchitectureValidation` | Diagnostic | BatchedCell counting/callback |
| `realTimeFrameAdvancement` | Diagnostic | Frame tracking across buffers |
| `frameRangeSumProducesAudio` | Diagnostic | PatternSystemManager.sum() isolation |
| `frameRangeSumMultipleBuffers` | Diagnostic | Buffer stitching |
| `frameRangeWithEffects` | Diagnostic | Cell pipeline setup |
| `renderNoteAudioLengthAnalysis` | Diagnostic | Rendering waste quantification |

---

## Next Steps

1. **Warmup strategy**: Pre-evaluate all notes during scene initialization
   (before the real-time loop starts) to populate both the `NoteAudioCache` and
   the `FrequencyCache`. This would eliminate the ~270ms compilation spike on
   first encounter of each instrument chain. The `cacheWarmingBenefit` test
   exists to measure this.

2. **Buffer size tuning**: Larger buffers amortize per-buffer overhead better.
   At 4096 frames the system is at ~0.42x real-time. Larger buffers or fewer
   pattern elements would reach real-time.
