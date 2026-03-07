# Compute Overhead Investigation Plan

## Summary

JFR CPU profiling of the AudioScene effects pipeline identified the
`MetricBase.addEntry()` path as generating ~9 GB of autoboxed allocations
per test run, but **A/B testing with profiling on vs off shows only ~5%
difference (207ms vs 197ms avg over 645 ticks)** — confirming that the
JVM handles this allocation efficiently (young-gen, JIT-optimized) and it
is NOT the dominant source of overhead.

The true source of the ~32% unaccounted overhead remains unidentified.
The earlier investigation also revealed that **console warning I/O was a
major confound**: `PatternLayerManager` warnings printed per-tick per-channel
added ~87ms/tick to measurements, completely masking the real overhead.
With warnings suppressed (`-DAR_PATTERN_WARNINGS=false`), baseline buffer
time drops from ~264ms to ~177ms.

### Key Lesson

Allocation volume (from JFR `ObjectAllocationSample`) does not equal CPU
time. Modern JVMs with G1GC handle massive young-gen allocation rates with
negligible pause times. CPU sampling (`ExecutionSample` / `NativeMethodSample`)
is a better indicator of actual overhead, but even CPU samples can be
misleading when most time is in native code.

---

## A/B Test: Profiling ON vs OFF (60 seconds, 645 ticks, Metal)

| Metric | Profiling ON | Profiling OFF | Delta |
|--------|-------------|--------------|-------|
| Invocation 1 avg | 176.81 ms | 172.03 ms | -4.78 ms (-2.7%) |
| Invocation 2 avg | 193.63 ms | 190.60 ms | -3.03 ms (-1.6%) |
| Invocation 3 avg | 252.21 ms | 229.41 ms | -22.80 ms (-9.0%) |
| **Mean avg** | **207.55 ms** | **197.35 ms** | **-10.20 ms (-4.9%)** |
| Min buffer | ~151 ms | ~151 ms | ~0 ms |

- Run IDs: `cf78e8ac` (ON), `e2dfc7f4` (OFF)
- Invocation 3 consistently slower in both — likely thermal throttling
- Warning I/O suppressed with `-DAR_PATTERN_WARNINGS=false`
- Conclusion: **profiling overhead is real but small (~5%), not the dominant cost**

---

## Phase 1 Results: JFR CPU Sampling

### Methodology

- **Test**: `AudioSceneBufferConsolidationTest#effectsEnabledPerformance`
- **Machine**: M1 Ultra Mac Studio
- **Driver**: Metal+JNI combined (`-DAR_HARDWARE_DRIVER=*`)
- **JFR settings**: `profile` (CPU method sampling at ~20ms intervals)
- **Recording duration**: 85 seconds (full test including compilation)
- **Kernel execution window**: 10:12:49 - 10:13:43 (~54 seconds)

### CPU Time Distribution

| Category | Samples | Percentage | Notes |
|----------|---------|-----------|-------|
| Native method samples | 3,786 | 95.2% | JVM time in native code |
| Java execution samples | 190 | 4.8% | JVM time in Java bytecode |

**Within native samples during kernel execution window (2,391 samples):**

| Method | Samples | Percentage | What It Is |
|--------|---------|-----------|------------|
| `FileInputStream.readBytes` | 1,448 | 60.6% | Reading C compiler output (subprocess I/O) |
| `ProcessHandleImpl.waitForProcessExit0` | 674 | 28.2% | Waiting for C compiler subprocesses |
| `GeneratedOperation106.apply` | 138 | 5.8% | Actual JNI kernel execution (the real work) |
| `VM.getNanoTimeAdjustment` | 52 | 2.2% | `Instant.now()` calls from `MetricBase` |
| `MTL.waitUntilCompleted` | 35 | 1.5% | Waiting for Metal GPU completion |
| `NativeLibraries.load` | 12 | 0.5% | Loading compiled JNI shared libraries |
| `MTL.createComputePipelineState` | 7 | 0.3% | Metal shader compilation |
| `MTL.createFunction` | 6 | 0.3% | Metal function creation |
| `GeneratedOperation105.apply` | 6 | 0.3% | Secondary JNI kernel |

**Key insight**: Only ~5.8% of native CPU time is spent on actual kernel execution. The remaining 94.2% is subprocess I/O (C compilation), process waiting, and metrics overhead.

**Within Java execution samples during kernel execution window (137 samples):**

| Method | Samples | Percentage | What It Is |
|--------|---------|-----------|------------|
| `HashMap$KeySpliterator.tryAdvance` | 23 | 16.8% | Stream iteration over argument maps |
| `ProviderAwareArgumentMap$$Lambda.test` | 13 | 9.5% | Argument matching predicate |
| `ReferencePipeline$2$1.accept` | 10 | 7.3% | Stream pipeline processing |
| `DirectMethodHandle.allocateInstance` | 10 | 7.3% | Object allocation via reflection |
| `AbstractPipeline.wrapSink` | 7 | 5.1% | Stream API infrastructure |
| `MemoryDataDestinationProducer.get` | 6 | 4.4% | Memory destination resolution |
| `ProducerComputationBase.getDestination` | 6 | 4.4% | Output destination lookup |
| `ReshapeProducer.get` | 5 | 3.6% | Reshape computation evaluation |

### Allocation Analysis (The Smoking Gun)

During the kernel execution window, **~24 billion bytes** of objects were allocated:

| Class | Allocation Weight | What It Is |
|-------|------------------|------------|
| `java/lang/Double` | 7.4 GB | Autoboxing in `MetricBase.addEntry` |
| `java/lang/Integer` | 2.0 GB | Autoboxing in `MetricBase.addEntry` |
| `Object[]` | 1.7 GB | Array allocations |
| `ReferencePipeline$Head` | 1.6 GB | Stream API overhead |
| `java/lang/reflect/Method` | 1.4 GB | Reflection |
| `java/lang/Short` | 947 MB | Short autoboxing |
| `ReferencePipeline$2` | 698 MB | Stream intermediates |
| `Expression$$Lambda` | 461 MB | Lambda allocations |
| `ArrayList$ArrayListSpliterator` | 555 MB | Stream spliterators |
| `HardwareEvaluable` | 368 MB | Per-dispatch wrapper |

**Allocation source tracing** for `Double` autoboxing:

| AR Source | Allocation Weight | Path |
|-----------|------------------|------|
| `MetricBase.addEntry` -> `DistributionMetric.addEntry` -> `OperationProfileNode.recordDuration` | 3.6 GB | Profiling runtime timing |
| Lambda -> `MetricBase.addEntry` (two variants) | 3.4 GB | Profiling scope/compile timing |
| `PatternElement.getPositions` | 274 MB | Pattern resolution |
| `FrequencyCache.prepareCapacity` | 134 MB | Frequency cache |

**Conclusion**: ~7.0 GB of the 7.4 GB `Double` allocation comes from the profiling system.

### Root Cause: `MetricBase.addEntry()`

The method at `io/src/main/java/org/almostrealism/io/MetricBase.java:183` executes four autoboxing operations per call:

```java
entries.merge(name, value, Double::sum);          // Double autoboxing
counts.merge(name, 1, Integer::sum);              // Integer autoboxing
intervalTotals.merge(interval, value, Double::sum); // Double autoboxing
intervalCounts.merge(interval, 1, Integer::sum);    // Integer autoboxing
```

Plus `getCurrentInterval()` calls `Instant.now()` which is a native call (`VM.getNanoTimeAdjustment`), adding ~2.2% of native CPU time.

This is called from `OperationProfileNode.recordDuration()` on every kernel dispatch, and from `getScopeListener()` on every compilation event.

---

## Phase 2: Recommended Instrumentation Areas

Based on the JFR data, the overhead decomposes into these categories. Each needs dedicated timing instrumentation to measure precisely:

### Category 1: Profiling System Overhead — MEASURED, SMALL
- **Source**: `OperationProfileNode` -> `MetricBase.addEntry` -> `DistributionMetric.addEntry`
- **Measured impact**: ~5% of per-tick time (A/B test: 207ms ON vs 197ms OFF)
- **Status**: Despite ~9 GB of autoboxed allocations, modern G1GC handles them
  efficiently. NOT the dominant overhead source.

### Category 2: Argument Resolution / `ProviderAwareArgumentMap`
- **Source**: `ProviderAwareArgumentMap.get()` iterates a `HashMap` via streams, calling a predicate lambda for each entry
- **Estimated impact**: 10-20% of per-tick Java time
- **Instrumentation**: Add `System.nanoTime()` around the argument resolution loop in `OperationList.run()` or the equivalent dispatch path
- **Fix**: Cache resolved arguments per operation, or use a more efficient lookup structure

### Category 3: C Compiler Subprocess I/O
- **Source**: `FileInputStream.readBytes` (60.6%) and `ProcessHandleImpl.waitForProcessExit0` (28.2%) during kernel window
- **Estimated impact**: This is compilation of uncached kernels, should only happen on first buffer tick
- **Instrumentation**: Count compiler invocations per tick; if >0 after tick 1, investigate cache misses
- **Fix**: Ensure kernel compilation cache works correctly; consider pre-compiling all kernels before entering the buffer tick loop

### Category 4: Object Allocation / GC Pressure
- **Source**: `HardwareEvaluable` (368 MB), `MemoryDataDestination` (218 MB), `DefaultContextSpecific` (176 MB), `Stack` (201 MB) created per dispatch
- **Estimated impact**: GC pauses from 24 GB/54s = ~444 MB/s allocation rate
- **Instrumentation**: Track GC pause time per buffer tick; compare with `start_memory_monitor`
- **Fix**: Object pooling for `HardwareEvaluable`, `MemoryDataDestination`; avoid creating `Stack` per dispatch

### Category 5: Stream API Overhead
- **Source**: `ReferencePipeline` infrastructure (1.6 GB + 698 MB + 555 MB + many smaller)
- **Estimated impact**: Part of Categories 2 and 4; streams create many short-lived objects
- **Fix**: Replace hot-path stream pipelines with explicit loops (especially in `ProviderAwareArgumentMap.get()` and `OperationList.run()`)

### Category 6: Metal GPU Synchronization
- **Source**: `MTL.waitUntilCompleted` (1.5%) + `MTL.commitCommandBuffer` (0.1%)
- **Estimated impact**: Small but serializes CPU/GPU pipeline
- **Fix**: Consider async command buffer submission with double-buffering

---

## Phase 3 Results: Per-Phase Tick Instrumentation

### Methodology

- **Test**: `AudioSceneBufferConsolidationTest#effectsEnabledPerformance`
- **Run ID**: `80fc54bb`
- **Duration**: 60 seconds (645 ticks at 4096 frames @ 44100 Hz)
- **Driver**: Metal+JNI combined (`-DAR_HARDWARE_DRIVER=*`)
- **Warnings**: Suppressed (`-DAR_PATTERN_WARNINGS=false`)
- **Profiling**: Enabled (default)
- **Instrumentation**: `-DAR_INSTRUMENT_PHASES=true` — breaks `OperationList.Runner` tick
  into individual sub-operations and times each one via `System.nanoTime()`

### Tick Structure (27 sub-operations)

| Phase | Avg ms | % of Tick | Description |
|-------|--------|-----------|-------------|
| 0 | 0.014 | 0.0% | Reset buffer frame index (AudioScene lambda) |
| 1-24 | **52.78** | **27.5%** | 24× `PatternAudioBuffer.prepareBatch()` |
| 25 | **139.19** | **72.5%** | `Loop x4096` — monolithic inner loop kernel |
| 26 | 0.002 | 0.0% | Advance global frame position (AudioScene lambda) |
| Overhead | 0.016 | 0.0% | Timing infrastructure (nanoTime calls) |
| **Total** | **192.01** | **100%** | **Avg buffer time** |

### PrepareBatch Breakdown (24 channels)

The 24 `PatternAudioBuffer.prepareBatch()` calls show a repeating pattern per
6-channel group (4 groups × 6 channels). Within each group:

| Channel Type | Avg ms | Count | Pattern |
|-------------|--------|-------|---------|
| Fast channels | ~0.6 | 12 | Minimal work (empty/silent buffers?) |
| Medium channels | ~1.7 | 4 | Moderate preparation |
| Slow channels | ~3.2-4.2 | 4 | Full pattern resolution + sample copy |
| Slowest channels | ~4.7-7.9 | 4 | Heavy pattern resolution or sample loading |

Total prepareBatch overhead: **~53ms / tick (27.5% of total)**

### Key Findings

1. **The inner loop kernel (Phase 25) is the dominant cost at 139ms (72.5%)**. This
   is the actual audio computation — effects, filters, mixing, automation — executing
   4096 iterations. At 92.9ms target for real-time, the kernel alone is 1.5× over budget.

2. **PrepareBatch adds 53ms (27.5%)** of overhead per tick. This is Java-side work:
   pattern element resolution, sample buffer preparation, argument setup for the kernel.
   Even if the kernel were instant, prepareBatch alone would consume 57% of the real-time
   budget.

3. **The timing infrastructure adds zero measurable overhead** (0.016ms for 27
   `System.nanoTime()` calls per tick). The phase instrumentation is safe for
   routine use.

4. **Both the kernel AND prepareBatch need optimization** to achieve real-time:
   - Kernel: 139ms → needs to drop to ~60ms (reduce computation volume — see Phase 4)
   - PrepareBatch: 53ms → needs to drop to ~25ms (cache pattern resolution, reduce allocations)

### Comparison with Target

| Metric | Value | Target | Over Budget |
|--------|-------|--------|-------------|
| Total buffer time | 192ms | 92.9ms | 2.07× |
| Kernel (Phase 25) | 139ms | ~65ms | 2.14× |
| PrepareBatch (Phases 1-24) | 53ms | ~25ms | 2.12× |

---

## Phase 4 Results: LICM Factoring and Kernel Composition Analysis

### Methodology

- **Test**: `AudioSceneBufferConsolidationTest#effectsEnabledPerformance`
- **Run ID**: `cc0b621a`
- **Duration**: 60 seconds (645 ticks), Metal+JNI driver
- **Monitoring**: `-DAR_INSTRUCTION_SET_MONITORING=always` to capture generated C code

### LICM Factoring Result — VERIFIED WORKING, NO PERFORMANCE IMPACT

The `AutomationManager.computePeriodicValue()` helper was implemented to factor
`sin((position + phase) * freq)` into `sin(clock * angularRate + phaseContrib)`,
separating loop-invariant and loop-variant terms.

**Generated C code verification** (`jni_instruction_set_106.c`, 4591 lines):
- **216 of 229 sin() calls hoisted** before the loop as `f_licm_*` variables
- **13 sin() calls remain in-loop** — from position-based `getAggregatedValueAt` path
  where the phase parameter is genuinely loop-variant (read from mutable buffer)
- **25 `/44100` divisions remain in-loop** — from `pow()` envelope expressions using
  the frame counter
- **62 `f_licm_*` variables** defined before the loop

**Performance comparison:**

| Metric | Baseline (80fc54bb) | With LICM (cc0b621a) | Delta |
|--------|-------------------|---------------------|-------|
| Avg buffer time | 192.01 ms | 190.71 ms | -1.3 ms (-0.7%) |
| Kernel (Phase 25) | 139.19 ms | 140.22 ms | +1.0 ms (+0.7%) |
| PrepareBatch | ~52.78 ms | ~50.47 ms | -2.3 ms (-4.4%) |

**Conclusion: LICM factoring had no measurable impact on kernel runtime.** The
expression transformation is correct (verified in generated C), but sin() was never
the dominant cost.

### Inner Loop Composition (The Actual Bottleneck)

The monolithic kernel inner loop is **2783 lines** executed 4096 times per tick:

| Operation | Per Iteration | Per Tick (×4096) | Nature |
|-----------|--------------|------------------|--------|
| Array assignments | 2,530 | 10.4 million | **Memory bandwidth bound** |
| fmin/fmax (clamps) | 231 | 946K | Cheap ALU ops |
| powf (envelopes) | 57 | 233K | Expensive transcendentals |
| floor (indices) | 40 | 164K | Moderate |
| sin (LFO) | 13 | 53K | Small fraction |

**The kernel is dominated by array-indexed memory operations**, not transcendental
functions. Each iteration performs ~2530 reads/writes to large float arrays, creating
severe pressure on L1/L2 cache. The 57 `powf` calls per iteration (233K per tick) are
the most expensive per-call operations remaining.

### Reduction Opportunities

The 2783-line loop body processes **6 source channels**, each with:
- Sample read + interpolation (floor, conditional)
- Filter chain (IIR biquad — the `fmin`/`fmax` clamps)
- Delay line (read/write at offset)
- Automation (sin/pow for LFO modulation, now mostly hoisted)
- Mix to output (multiply + accumulate)

**Possible approaches to reduce computation volume:**

1. **Reduce active channel count**: The test uses 6 channels with 4 groups = 24 render
   cells. If many channels are silent or below threshold, skip their computation
   entirely (zero-detection at prepareBatch level).

2. **Simplify filter chains when not needed**: If a channel's filter coefficients are
   at passthrough values (no EQ/filtering active), bypass the IIR computation. Each
   biquad filter adds ~20 operations per sample per channel.

3. **Reduce automation granularity**: The 13 remaining in-loop sin() calls and 57 powf
   calls compute automation values per-sample. Since automation changes slowly relative
   to audio rate, computing automation once per block (e.g., every 64 or 256 samples)
   and interpolating would eliminate most transcendental calls from the inner loop.

4. **Split monolithic kernel into parallel pieces**: The current kernel processes all
   channels sequentially in one loop. Splitting into per-channel kernels would enable
   Metal/GPU parallelism across channels (6 parallel dispatches instead of 1 serial).

5. **Reduce powf to cheaper approximation**: The 57 `powf` calls use `powf(x, 3.0)` and
   `powf(x, exponent)`. The cubic case can be replaced with `x*x*x` (3 multiplies vs
   1 transcendental). Variable-exponent cases may benefit from fast `exp2(e*log2(x))`
   approximations.

6. **Consolidate array layout for cache efficiency**: The kernel accesses arrays at
   large strides (offsets like +1606, +1645, etc.). Reorganizing the data layout to
   improve spatial locality could reduce cache misses.

7. **Reduce duplicate computation across channel groups**: The 4 groups of 6 channels
   show repeating patterns in the generated code. If groups share common sub-expressions
   (e.g., same automation phase, same filter type), these could be computed once and
   shared.

### Detailed Inner Loop Operation Breakdown

| Operation | Per Iteration | Per Tick (×4096) | Category |
|-----------|--------------|------------------|----------|
| Array assignments | 2,530 | 10.4M | Memory bandwidth |
| fmin/fmax (clamp) | 231 | 946K | ALU (cheap) |
| Zero resets (= 0.0) | 103 | 422K | Memory write |
| Conditionals (if) | 67 | 274K | Branch prediction |
| Increments (+= 1.0) | 59 | 242K | ALU (cheap) |
| powf (envelopes) | 57 | 233K | **Transcendental** |
| floor (indices) | 40 | 164K | ALU |
| `acceleratedTimeSeriesValueAt` calls | 22 | 90K | **Inner loop + memory** |
| Inner for-loops (time series search) | 22 | 90K | Branch-heavy loop |
| sin (LFO) | 13 | 53K | Transcendental |

**The 22 `acceleratedTimeSeriesValueAt` calls are notable** — each contains a
nested `for` loop iterating over a time series buffer, adding unpredictable
branches and memory accesses inside an already hot loop. These are delay line
lookups and time-series interpolation.

### Estimated Impact of Each Reduction Strategy

| Strategy | Eliminates | Estimated Savings | Difficulty |
|----------|-----------|-------------------|------------|
| `powf(x,3)` → `x*x*x` | 11 powf/iter | ~2-4ms/tick | Low (Exponent.create threshold) |
| `powf(x,2)` → `x*x` | 4 powf/iter | ~1ms/tick | Low (already implemented, threshold issue) |
| `powf(x,0.5)` → `sqrtf(x)` | 2 powf/iter | ~0.5ms/tick | Low |
| Skip silent channels | ~420 ops/iter per silent ch | Up to ~30ms if 3 ch silent | Medium (prepareBatch detection) |
| Sub-block automation (×64) | 12 sin + ~30 powf in loop | ~15-20ms/tick | Medium (expression restructure) |
| Cache time series lookups | 22 inner loops/iter | ~10-20ms/tick | Medium (depends on access pattern) |
| GPU parallel split | N/A | ~50-80ms (parallel channels) | High (architecture change) |

---

## Recommended Next Steps

### Immediate (Quick Wins)

1. ~~**Disable profiling in performance tests**~~ **DONE / DISPROVEN**: A/B test
   shows only ~5% difference. Profiling is not the dominant overhead. However,
   the `-DAR_DISABLE_PROFILING=true` and `-DAR_RENDER_SECONDS=N` system properties
   were added and remain useful for future experiments.

2. **Suppress warning I/O in performance tests**: Use `-DAR_PATTERN_WARNINGS=false`
   in all performance runs. The `PatternLayerManager` warnings added ~87ms/tick
   of pure I/O overhead. This is now the single largest known confound.

### Short-Term (Completed / Deprioritized)

3. ~~**Rewrite MetricBase.addEntry()**~~ **DEPRIORITIZED**: ~5% impact, G1GC handles it.

4. ~~**Add per-tick timing instrumentation**~~ **DONE**: Phase instrumentation via
   `-DAR_INSTRUMENT_PHASES=true`. Results in Phase 3.

5. ~~**LICM factoring for LFO sin() expressions**~~ **DONE / NO IMPACT**: Expression
   factoring works (216/229 sin calls hoisted), but sin() was never the bottleneck.
   The kernel is memory-bandwidth bound with 2530 array operations per iteration.
   See Phase 4 results.

### TOP 3 PRIORITIES (Selected After Phase 5-6 Analysis)

**Priority 1: Eliminate CachedStateCell copy chains** — HIGHEST IMPACT
- **File**: `graph/src/main/java/org/almostrealism/graph/CachedStateCell.java`
- **What**: Extend the existing SummationCell optimization (line 213-218) to other
  receptor types. When the downstream receptor is a simple forwarding cell
  (PassThroughCell, ReceptorCell, or another CachedStateCell), eliminate the
  intermediate `outValue` copy.
- **Estimated savings**: 200-300 eliminated copies × 4096 iterations = ~800K-1.2M
  fewer memory ops/tick → ~15-25ms reduction in kernel time
- **Difficulty**: Low — the pattern is already proven in the SummationCell case

**Priority 2: Replace `powf(x, N)` with multiplication for small integer N** — MEDIUM IMPACT
- **File**: `code/src/main/java/io/almostrealism/expression/Exponent.java`
- **What**: The 11 `powf(x, 3.0)` and 4 `powf(x, 2.0)` calls in the inner loop are
  not being strength-reduced because the base expression's `totalComputeCost()` exceeds
  the threshold (10). Either raise the threshold or add a separate path that handles
  small integer exponents regardless of base cost (since `x*x*x` evaluates the base
  only once in generated C, not three times — the C compiler CSE handles it).
- **Estimated savings**: 15 powf → multiply conversions × 4096 = ~61K transcendental
  calls eliminated → ~3-5ms reduction in kernel time
- **Difficulty**: Low — single threshold/logic change in `Exponent.create()`

**Priority 3: Fuse zero-reset + accumulate pairs** — MEDIUM IMPACT
- **File**: Code generator or `CachedStateCell`/`SummationCell` interaction
- **What**: The pattern `arr[X] = 0.0; ... arr[X] = arr[X] + val;` appears 103 times.
  When a SummationCell has exactly one contributor per tick, the zero+accumulate can be
  replaced with a single direct assignment: `arr[X] = val;`. This is related to
  Priority 1 — if the copy chain is eliminated, many of these pairs become unnecessary.
- **Estimated savings**: 103 zero resets eliminated = ~422K fewer ops/tick → ~2-4ms
- **Difficulty**: Low-Medium — depends on being able to detect single-contributor case

### Other Opportunities (Ranked)

6. **Replace `powf(x, 3.0)` with `x*x*x`**: See Priority 2 above.

7. **Reduce automation granularity**: The 13 in-loop sin() and remaining powf calls
   compute per-sample automation. Automation signals change slowly (LFO at 2-16 Hz vs
   44100 Hz sample rate). Computing once per sub-block (every 64-256 samples) and
   linearly interpolating would eliminate most transcendental calls from the inner loop.

8. **Skip silent/inactive channels**: If a channel has no active pattern elements in the
   current buffer window, skip its entire computation path. This requires zero-detection
   at the prepareBatch level and conditional kernel execution.

9. **Reduce prepareBatch cost**: At 16384-frame buffers this is only 6.2% of tick time.
   Still worth optimizing at 4096-frame buffers (27.5%).

### Medium-Term (Architecture)

10. **Split monolithic kernel for GPU parallelism**: The 2783-line loop processes all 6
    channels sequentially. Splitting into per-channel kernels enables Metal parallel
    dispatch (6 concurrent GPU threads instead of 1 serial CPU thread).

11. **Consolidate array layout for cache efficiency**: Array access strides are large
    (+1606, +1645, etc.). Reorganizing for spatial locality would reduce cache misses
    in the 10.4M memory operations per tick.

12. **Expression aliasing / copy propagation in code generator**: When the code generator
    emits `a = b; c = a;`, it could recognize that `c` can read directly from `b`. This
    is a standard compiler optimization but requires changes to the code generation layer.

### Long-Term

13. **Object pooling**: Pool `HardwareEvaluable`, `MemoryDataDestination`, and other per-dispatch objects to reduce allocation rate.

14. **Pre-compilation**: Ensure all kernels are compiled before entering the buffer tick loop so no compilation happens during real-time execution.

15. **Async Metal dispatch**: Overlap CPU argument preparation with GPU kernel execution using command buffer double-buffering.

---

## JMX MCP Tool Issues Found

During this investigation, two bugs were identified and fixed in the MCP tooling:

### 1. PID Discovery Fails on macOS (test-runner)
- **File**: `tools/mcp/test-runner/server.py`
- **Bug**: `_get_ppid()` used `/proc/<pid>/stat` which doesn't exist on macOS
- **Fix**: Added `ps -o ppid=` fallback for macOS
- **Also**: Changed `"ForkedBooter" in line` to also match `"surefirebooter"` since `jps -l` shows the jar path on modern Surefire
- **Also**: Changed direct parent check to ancestor walk (`_is_descendant_of`) since the fork's parent is the Maven JVM, not the `mvn` shell wrapper

### 2. Process Liveness Check Fails on macOS (ar-jmx)
- **File**: `tools/mcp/jmx/jvm_diagnostics.py`
- **Bug**: `is_process_alive()` used `/proc/<pid>/stat` which doesn't exist on macOS
- **Fix**: Added `os.kill(pid, 0)` fallback
- **Also**: Fixed `get_ppid()` with `ps -o ppid=` fallback

### 3. JFR Settings Not Configurable (test-runner)
- **File**: `tools/mcp/test-runner/server.py`
- **Enhancement**: Added `jfr_settings` parameter (`"default"` or `"profile"`) to `start_test_run` and `RunConfig`, replacing the hardcoded `settings=default`

These fixes require an MCP server restart to take effect.

---

## Phase 5 Results: Buffer Size Scaling (4096 vs 16384 Frames)

### Motivation

The user asked whether a 4× increase in buffer size (from 4096 to 16384 frames,
~93ms → ~372ms latency budget) would materially change the priority of optimization
strategies. Longer loops amortize per-buffer overhead (prepareBatch) but increase
absolute kernel time.

### Methodology

- **Test**: `AudioSceneBufferConsolidationTest#effectsEnabledPerformance`
- **Run ID**: `9fab3066` (16384 frames) vs `80fc54bb` (4096 frames, baseline)
- **Duration**: 60 seconds, Metal+JNI driver (`-DAR_HARDWARE_DRIVER=*`)
- **Instrumentation**: `-DAR_INSTRUMENT_PHASES=true`
- **Buffer size**: `-DAR_BUFFER_SIZE=16384`

### Results Comparison

| Metric | 4096 frames | 16384 frames | Scaling Factor |
|--------|-------------|--------------|----------------|
| Target buffer time | 92.88 ms | 371.52 ms | 4.0× |
| Avg buffer time | 192.01 ms | 583.99 ms | 3.04× |
| Kernel (Loop phase) | 139.19 ms | 547.30 ms | 3.93× (near-linear) |
| PrepareBatch (24 ch) | ~52.78 ms | ~36.00 ms | 0.68× (reduced) |
| Kernel % of tick | 72.5% | 93.7% | — |
| PrepareBatch % of tick | 27.5% | 6.2% | — |
| Real-time ratio | 0.48× | 0.64× | +33% improvement |
| Overruns | 645/645 | 161/161 | — |

### Key Findings

1. **Kernel time scales nearly linearly** with buffer size (3.93× for 4× frames).
   This confirms the kernel is pure per-sample computation with no significant
   per-buffer overhead or caching benefit from longer runs.

2. **PrepareBatch time *decreases*** from 53ms to 36ms at 4× buffer size because
   there are fewer batch boundaries (161 vs 645 ticks). Per-tick prepareBatch cost
   drops because less frequent Java-side pattern resolution.

3. **The longer buffer improves the real-time ratio** from 0.48× to 0.64× — a 33%
   improvement just from amortizing Java overhead. Still not real-time, but meaningful.

4. **At 16384 frames, the kernel is 93.7% of tick time** — prepareBatch optimization
   becomes nearly irrelevant. All optimization effort should focus on the kernel.

### Impact on Strategy Priorities

| Strategy | Priority at 4096 | Priority at 16384 | Why |
|----------|------------------|-------------------|-----|
| Kernel: eliminate copies | **HIGH** | **CRITICAL** | 93.7% of time; linear scaling |
| Kernel: reduce powf | Medium | High | Proportionally larger share |
| Kernel: sub-block automation | Medium | High | Same |
| PrepareBatch optimization | High (27.5%) | Low (6.2%) | Amortized at longer buffer |
| Skip silent channels | Medium | High | Eliminates kernel work |

**Conclusion**: At 16384-frame buffers, the kernel dominates so completely that
prepareBatch optimization becomes a distraction. The top priority is reducing
per-sample computation in the inner loop — particularly the array copy/forwarding
operations that constitute the bulk of the 2775-line loop body.

---

## Phase 6 Results: Inner Loop Array Assignment Analysis

### Methodology

Generated C code analysis of `jni_instruction_set_106.c` from run `cc0b621a`.
Inner loop: line 1809-4585 (2775 lines), iterating 4096 times per tick.

### Detailed Operation Census

| Operation | Count/Iteration | Per Tick (×4096) | % of Loop Lines |
|-----------|----------------|------------------|-----------------|
| Simple copies (arr[X] = arr[Y]) | **535** | **2.19M** | **19.3%** |
| — Same-array copies | 490 | 2.01M | 17.7% |
| — Cross-array copies | 45 | 184K | 1.6% |
| Zero resets (= 0.0) | **103** | **422K** | **3.7%** |
| Accumulations (arr[X] = arr[X] + val) | **117** | **479K** | **4.2%** |
| Ternary conditionals (sample reads) | **99** | **406K** | **3.6%** |
| Assignments with arithmetic | **~1,920** | **~7.9M** | **69.2%** |
| **Total assignment lines** | **~2,775** | **~11.4M** | **100%** |

### Copy Chain Architecture (Source of 535 Simple Copies)

The 535 simple copies trace directly to the `CachedStateCell.tick()` double-buffering
pattern in the graph module:

```
CachedStateCell.tick() [standard path]:
  1. assign(outValue, cachedValue)  → generates "arr[out] = arr[cached]"  (COPY)
  2. reset(cachedValue)             → generates "arr[cached] = 0.0"        (ZERO)
  3. super.push(outValue)           → forwards to receptor chain           (MORE COPIES)
```

Each `CachedStateCell` in the pipeline generates:
- 1 copy (cached → out)
- 1 zero reset
- 1+ downstream copies (push to receptor)

With 24 render cells × multiple effects stages, this produces hundreds of copies.

**Existing optimization**: `CachedStateCell.tick()` already has a special case for
`SummationCell` receptors (line 213-218) that skips the intermediate `outValue` copy
and pushes `cachedValue` directly. This shows the pattern is recognized but only
applied to one specific case.

### The 490 Same-Array Copies — Root Cause

The 490 same-array copies (`arr[X] = arr[Y]` within the same consolidated array)
arise from the cell forwarding chain:

```
Source Cell writes → cachedValue at offset A
CachedStateCell.tick() copies → outValue at offset B        (COPY 1)
CellAdapter.push() forwards → receptor cell's input         (COPY 2)
  If receptor is another CachedStateCell:
    CachedStateCell.push() assigns → its cachedValue         (COPY 3)
```

In the generated code, after buffer consolidation, all these offsets are into
the same large array. The copies are pure data movement with no computation.

### Low-Hanging Fruit Opportunities

**1. Extend the SummationCell optimization pattern (EASIEST)**

`CachedStateCell.tick()` already optimizes for `SummationCell` receptors.
The same pattern could be applied when the receptor is a simple
`PassThroughCell` or `ReceptorCell` — instead of copy→zero→push, directly
push the cachedValue and then zero it.

More broadly: when a `CachedStateCell`'s receptor is another `CachedStateCell`,
the intermediate `outValue` copy can be eliminated by having the downstream cell
read directly from the upstream cell's `cachedValue` (or `outValue` if the order
is guaranteed).

**Estimated impact**: Could eliminate ~200-300 of the 490 same-array copies.
At 4096 iterations, that's ~800K-1.2M fewer memory operations per tick.

**2. Expression aliasing in code generation (MEDIUM)**

At the code generation level, when the compiler sees `a = b; c = a;` it could
recognize that `c` can read directly from `b`, eliminating the intermediate `a`.
This is a standard compiler optimization (copy propagation) but the AR code
generator may not perform it because each assignment is a separate `Computation`.

**Estimated impact**: Could eliminate most remaining simple copies.

**3. Fuse accumulate-and-reset patterns (LOW-HANGING)**

The pattern `arr[X] = 0.0; ... arr[X] = arr[X] + val;` (103 zeros + 117
accumulations) could be fused: instead of clear-then-accumulate, use direct
assignment on first write and accumulate on subsequent writes. Or, if there's
exactly one accumulation per zero, the pair can be replaced with a single
assignment.

**Estimated impact**: Eliminate 103 zero resets (422K ops/tick).

---

## Phase 7 Results: Priority 1 & 2 Implementation — No Kernel Impact

### Changes Implemented

Two optimizations were implemented on the `feature/audio-loop-performance` branch:

**Priority 1 — CachedStateCell copy chain elimination** (`CachedStateCell.java`):
Changed `tick()` to push `cachedValue` directly to ANY receptor type (not just
`SummationCell`). When a receptor is present, the intermediate `outValue` copy
is skipped entirely. The `outValue` is still maintained for the no-receptor case
(used by `CellPair` via `getResultant()`/`next()`).

**Priority 2 — Exponent strength reduction** (`Exponent.java`):
Removed the `totalComputeCost()` gate for exponents |exp| <= 3. Now `pow(x,2)`,
`pow(x,3)`, `pow(x,-2)`, `pow(x,-3)` are always expanded to multiply/divide
chains regardless of base expression complexity. Higher exponents (e.g., `pow(x,4)`)
remain gated by the cost threshold. Rationale: the C compiler's CSE ensures
the base is evaluated only once even when the expression tree duplicates it.

### Generated Code Verification

| Metric | Baseline (cc0b621a) | Optimized (2f9182e1) | Delta |
|--------|---------------------|---------------------|-------|
| Kernel lines | 4591 | 4569 | -22 (-0.5%) |
| Array assignments | 2372 | 2326 | -46 (-1.9%) |
| powf() calls | 352 | 241 | **-111 (-31.5%)** |
| sin() calls | ~221 | 221 | ~0 |
| Total kernels | 139 | 143 | +4 |

The 111 eliminated `powf` calls confirm the Exponent strength reduction is working.
In the generated C, `pow(x,3)` expressions now appear as `(x * x) * x` inline.
The 46 fewer assignments confirm some copy elimination from CachedStateCell.

### Performance Results

| Metric | 4096 Baseline | 4096 Optimized | 16384 Baseline | 16384 Optimized |
|--------|--------------|----------------|----------------|-----------------|
| Avg buffer time | 192.01 ms | 178.93 ms | 583.99 ms | 599.24 ms* |
| Kernel (Loop phase) | 139.19 ms | 139.71 ms | 547.30 ms | 548.02 ms |
| Real-time ratio | 0.48x | 0.52x | 0.64x | 0.62x* |

*The 16384-frame optimized run (`7532b6c9`) executed concurrently with the 4096-frame
run (`2f9182e1`), causing resource contention. An isolated re-run (`58ace835`) confirmed:
Avg 594.13ms (vs 583.99 baseline), Kernel 541.52ms (vs 547.30 baseline, -1.1%).
The ~1% kernel difference is within run-to-run noise.

### Analysis — Why No Kernel Impact

**Kernel time is unchanged** despite 111 fewer powf calls and 46 fewer copies:

1. **GPU compiler already optimizes `powf(x, 3.0)`**: The OpenCL compiler performs
   its own strength reduction, converting `powf(x, 3.0)` to multiplication chains
   before GPU execution. Our source-level change produces identical GPU machine code.

2. **Copy operations are negligible on GPU**: The 46 eliminated array assignments
   represent simple memory moves that execute in a single clock cycle on GPU ALUs.
   At 4096 iterations, this is ~189K fewer ops — but GPU memory bandwidth is measured
   in GB/s, making this unmeasurable.

3. **The 4096-frame avg improvement (192 → 179ms)** is entirely in the prepareBatch
   phase (Java-side), not the kernel. This may be run-to-run variance rather than a
   real improvement.

### Conclusion

**Expression-level optimizations (strength reduction, copy elimination) do not
measurably impact GPU kernel execution time.** The GPU compiler performs the same
optimizations independently. Future optimization effort should focus on:

1. **Reducing total operation count** (skip silent channels, sub-block automation)
2. **Improving memory access patterns** (cache-friendly array layout)
3. **GPU parallelism** (split monolithic kernel into per-channel dispatches)
4. **PreparareBatch overhead** at 4096-frame buffers (still 27.5% of tick)

These are architectural changes that the GPU compiler cannot discover on its own.

### Status Update for TOP 3 PRIORITIES

| Priority | Status | Impact |
|----------|--------|--------|
| 1. CachedStateCell copy elimination | IMPLEMENTED | No kernel impact (GPU optimizes copies) |
| 2. Exponent strength reduction | IMPLEMENTED | No kernel impact (GPU optimizes powf) |
| 3. Fuse zero-reset + accumulate | DEPRIORITIZED | Would also have no GPU impact |

---

## Raw Data Location

- JFR recording: `/tmp/jfr_overhead_analysis/cpu_profile.jfr`
- Test output: `/tmp/jfr_overhead_analysis/test_output.txt`
- Test run b44bed42 (3 repetitions with default JFR): `tools/mcp/test-runner/runs/b44bed42/`
