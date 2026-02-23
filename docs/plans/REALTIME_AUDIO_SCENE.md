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
    CellList cells = getCells(output, channels, bufferSize, frameSupplier);
    PackedCollection bufferFrameIndex = new PackedCollection(1);

    // Loop body is built ONCE at construction time, not per tick
    OperationList loopBody = new OperationList("RealTime Per-Frame Body");
    loopBody.add(cells.tick());                                    // per-frame DSP
    loopBody.add(a(1, cp(bufferFrameIndex), c(1.0).add(cp(bufferFrameIndex))));  // increment frame index

    return new TemporalCellular() {
        Supplier<Runnable> tick() {
            OperationList tick = new OperationList("AudioScene RealTime Runner Tick");

            // 1. OUTSIDE LOOP: Reset buffer frame index and prepare pattern data
            tick.add(() -> () -> bufferFrameIndex.setMem(0, 0));
            for (PatternAudioBuffer cell : renderCells) {
                tick.add(cell.prepareBatch());
            }

            // 2. INSIDE LOOP: Compilable per-frame processing
            tick.add(loop(loopBody, bufferSize));

            // 3. AFTER LOOP: Advance global frame position
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
bufferFrameIndex.setMem(0, 0)       // Reset buffer-local frame index
PatternAudioBuffer.prepareBatch()
  -> PatternSystemManager.sum(ctx, channel, startFrame, bufferSize)
  -> PatternFeatures.render() for each overlapping note
  -> Result written to PatternAudioBuffer.outputBuffer
  |
  v
[INSIDE LOOP - Compiled, per frame]
loop(loopBody, bufferSize)
  loopBody contains:
  +-> cells.tick()                   // CellList tick (root pushes + temporals)
  |     +-> PatternAudioBuffer read  // Read from prepared buffer
  |     +-> Effects pipeline cells   // Compilable DSP
  |     +-> WaveOutput cursor advance
  +-> bufferFrameIndex++             // Compiled increment
  |
  v
[AFTER LOOP]
currentFrame[0] += bufferSize
```

### Loop Compilation Path

The `loop(loopBody, bufferSize)` call dispatches through:

1. `TemporalFeatures.loop(Supplier<Runnable>, int)` — checks if `loopBody`
   is a `Computation`
2. `HardwareFeatures.loop(Computation<Void>, int)` — checks
   `loopBody.isComputation()`:
   - **If true**: Creates a `new Loop(loopBody, iterations)` — a compiled
     for-loop that generates native kernel code with
     `for (int i = 1; i < iterations; i++) { ... }`
   - **If false**: Falls back to a Java loop that calls `loopBody.get()`
     once and runs it `iterations` times via `IntStream.range(...).forEach(...)`

`Loop.getScope()` creates a `Repeated` scope wrapping the atom's scope. The
atom is the `loopBody` OperationList, whose scope aggregates **all
`PackedCollection` arguments** needed by every operation in the cell graph.
This is where the argument count can become very large (see
[Tester Application Experiment](#tester-application-experiment) below).

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

## Tester Application Experiment

A standalone JavaFX application (`RealtimeSceneTesterJavaFX`) was built in
`ringsdesktop/audio-desktop` to exercise the real-time pipeline interactively.
See `ringsdesktop/REALTIME_SCENE_TESTER.md` for the full design.

### What the Tester Does

- 16 sliders control a `ProjectedGenome` embedding, which drives pattern
  generation via `AudioScene.assignGenome()`
- Pressing Play builds a runner via `scene.runnerRealTime()`, wraps it in a
  `BufferedOutputScheduler`, and streams audio to the system speaker via
  Java Sound API (`SourceDataLine`)
- Slider changes while playing trigger a debounced stop/recompile/restart
  cycle
- An `OperationProfileNode` is assigned to `Hardware` at startup and captures
  compilation timing, runtime timing, and scope timing for the entire session.
  The profile is saved to `results/realtime_scene_profile.xml` on demand or
  on window close.

### Key Findings

1. **Compilation dominates wall-clock time.** The first Play press triggers
   compilation of the `Loop` scope (the compiled per-frame kernel). This takes
   far longer than any runtime phase. Subsequent ticks reuse the compiled
   kernel, but every slider change triggers recompilation because
   `assignGenome()` produces a structurally different computation graph.

2. **The Loop scope has 500+ arguments.** When `Loop.getScope()` builds the
   `Repeated` scope, the atom's scope (the `loopBody` OperationList)
   aggregates every `PackedCollection` argument from the entire cell graph.
   With 6 channels, each with effects pipelines, the argument count exceeds
   500. This is the primary driver of slow compilation — scope generation,
   argument resolution, and native code generation all scale with argument
   count.

3. **Runtime is not quite real-time.** Even after compilation, tick execution
   with a 4096-frame buffer (~93ms at 44.1kHz) takes longer than the buffer
   duration. The system achieves roughly 0.4x real-time. Increasing the buffer
   size improves the ratio by amortizing per-tick overhead.

4. **Every genome change requires full recompilation.** Because the
   computation graph changes structurally with each genome (different pattern
   elements → different cell configurations), the compiled kernel cannot be
   reused. This makes interactive slider adjustment impractical without a
   strategy to avoid recompilation.

---

## Next Steps

### 1. Increase Buffer Size to 16384 Frames

Increase `BUFFER_SIZE` from 4096 to 16384 (~372ms at 44.1kHz). This 4x
increase accepts higher latency in exchange for better amortization of
per-tick overhead. At the current ~0.42x real-time ratio with 4096 frames,
a 4x buffer should bring runtime closer to real-time by reducing the
relative weight of fixed per-tick costs.

This is a simple constant change in both the tester app
(`RealtimeSceneTesterController.BUFFER_SIZE`) and in test configurations.

### 2. Reduce Loop Scope Argument Count

The compiled `Loop` scope currently has 500+ arguments because every
`PackedCollection` referenced by the cell graph becomes a kernel argument.
This is the primary compilation bottleneck. Approaches to reduce the count:

- **Heap consolidation**: Use `Heap.stage()` or similar to combine many
  small `PackedCollection` objects into a single contiguous allocation. The
  kernel would then receive one large buffer plus offset calculations instead
  of hundreds of individual arguments.
- **Identify unnecessary arguments**: Some arguments may be constants or
  duplicates that could be inlined or deduplicated during scope generation.
  Profile the argument list to categorize what's being passed in (pattern
  data, effect parameters, frame indices, output buffers) and identify
  candidates for elimination.
- **Shared buffer pools**: Multiple cells using similar-sized buffers could
  share a single allocation, reducing the total argument count.

#### Gene Value Consolidation (Implemented)

`ProjectedChromosome.consolidateGeneValues()` packs all gene value
collections within a chromosome into a single contiguous `PackedCollection`.
Each gene's `values` field is replaced with a view (delegate) into the
consolidated buffer. Because `Scope.getArguments()` resolves variables to
their root delegate before deduplication, all gene values from a chromosome
collapse into a single kernel argument instead of one per gene.

`AudioScene` calls `genome.consolidateGeneValues()` at the end of its
constructor, after all chromosomes and genes have been added. This reduces
gene-derived arguments from O(genes) to O(chromosomes). The consolidated
buffers are automatically kept up to date by `refreshValues()`, which writes
through the views.

**Remaining opportunities** (not yet implemented):
- Consolidate `PatternAudioBuffer.outputBuffer` collections (one per
  channel) into a single buffer with offset-based access
- Consolidate `EfxManager.applyFilter()` destination buffers similarly
- Profile the full argument list to identify other small collections
  that could be consolidated

### 3. Parameterize Computation via `Evaluable` to Avoid Recompilation

The fundamental problem is that each genome change produces a structurally
different computation graph, forcing full recompilation. The solution is to
make the genome parameters **runtime arguments** to a single compiled kernel
rather than baked-in constants.

`Evaluable` already supports runtime arguments — that is its primary purpose.
The approach:

- Identify which parts of the computation graph change with the genome
  (pattern element positions, durations, amplitudes, channel assignments)
  vs. which parts are structurally fixed (the DSP pipeline, effects chain,
  output routing)
- Factor the genome-dependent values into `PackedCollection` arguments that
  are passed at evaluation time rather than embedded in the computation graph
- Compile the kernel **once** with argument slots for the genome-dependent
  values, then update those `PackedCollection` values on each genome change
  without recompilation

This is the most impactful change: it would make slider adjustments
instantaneous (just update argument values) instead of requiring a full
recompile cycle. It also enables the warmup strategy (pre-compile during
scene initialization) because the compiled kernel would be genome-independent.

#### Analysis: Graph Is Already Genome-Independent (Verified)

Investigation of the computation graph construction reveals that the cell
graph built by `getCells()` is **already structurally independent** of the
genome parameters:

1. **Gene values use `cp()` references**, not inlined constants.
   `ProjectedGene.valueAt(pos)` returns
   `cp(values.range(shape(1), pos))` — a `PackedCollection` reference
   that is read at runtime. When `assignGenome()` calls
   `refreshValues()`, the underlying `PackedCollection` values are
   updated in place, and the compiled kernel sees the new values on its
   next execution.

2. **EfxManager filter selection is runtime-controlled.** The
   `applyFilter()` method uses `choice()` to select between high-pass
   and low-pass coefficients at runtime via a genome-derived decision
   value. Both coefficient sets are always computed; the decision
   parameter picks one. No structural branching occurs.

3. **All other genome-derived values** (delay times, wet levels,
   feedback, automation, mix levels) flow through `Factor` instances
   that produce `cp()`-based `Producer`s.

**Consequence:** The runner built by `runnerRealTime()` can be reused
across genome changes. Callers should call `assignGenome()` to update
the `PackedCollection` values, then continue ticking the existing runner.
Rebuilding the runner (which triggers recompilation) is unnecessary.

The `assignGenome()` javadoc now documents this capability. The tester
application (`RealtimeSceneTesterJavaFX`) should be updated to reuse
the runner on slider changes instead of doing a stop/recompile/restart
cycle.

### Previously Identified (Still Relevant)

4. **Warmup strategy**: Pre-evaluate all notes during scene initialization
   (before the real-time loop starts) to populate both the `NoteAudioCache`
   and the `FrequencyCache`. This would eliminate the ~270ms compilation spike
   on first encounter of each instrument chain. The `cacheWarmingBenefit` test
   exists to measure this. (Note: this becomes much more viable once step 3
   is implemented, since the kernel would be genome-independent.)

5. **Buffer size tuning**: Further experimentation with buffer sizes beyond
   16384 may be warranted depending on the results of steps 2 and 3.
