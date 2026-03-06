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
   - Kernel: 139ms → needs to drop to ~60ms (reduce sin/pow calls, LICM factoring)
   - PrepareBatch: 53ms → needs to drop to ~25ms (cache pattern resolution, reduce allocations)

### Comparison with Target

| Metric | Value | Target | Over Budget |
|--------|-------|--------|-------------|
| Total buffer time | 192ms | 92.9ms | 2.07× |
| Kernel (Phase 25) | 139ms | ~65ms | 2.14× |
| PrepareBatch (Phases 1-24) | 53ms | ~25ms | 2.12× |

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

### Short-Term (Kernel Optimization)

3. ~~**Rewrite MetricBase.addEntry()**~~ **DEPRIORITIZED**: The autoboxing allocations
   are handled efficiently by G1GC (~5% impact). Not worth rewriting unless the
   profiling system becomes a bottleneck in other contexts.

4. ~~**Add per-tick timing instrumentation**~~ **DONE**: Phase instrumentation via
   `-DAR_INSTRUMENT_PHASES=true` implemented in `RealTimeTestHelper`. Results in Phase 3 above.

5. **Reduce inner loop kernel cost (139ms → ~65ms)**:
   - Factor LFO sin() expressions for LICM (see plan: LFO sin() cost via expression factoring)
   - 62 sin() calls × 4096 iterations = ~254K transcendental calls per tick
   - Algebraic redistribution of frequency multiplier enables hoisting of invariant
     sub-expressions, eliminating ~500K divisions per buffer

6. **Reduce prepareBatch cost (53ms → ~25ms)**:
   - Profile `PatternAudioBuffer.prepareBatch()` to identify what the 4-8ms channels
     are doing (pattern resolution? sample copying? argument allocation?)
   - Cache pattern element resolution across ticks when patterns haven't changed
   - Replace stream API in hot paths with explicit loops

7. **Replace stream API in hot paths**: Convert `ProviderAwareArgumentMap` argument
   resolution from stream-based HashMap iteration to explicit loop with early exit.

### Long-Term (Architecture)

8. **Object pooling**: Pool `HardwareEvaluable`, `MemoryDataDestination`, and other per-dispatch objects to reduce allocation rate.

9. **Pre-compilation**: Ensure all kernels are compiled before entering the buffer tick loop so no compilation happens during real-time execution.

10. **Async Metal dispatch**: Overlap CPU argument preparation with GPU kernel execution using command buffer double-buffering.

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

## Raw Data Location

- JFR recording: `/tmp/jfr_overhead_analysis/cpu_profile.jfr`
- Test output: `/tmp/jfr_overhead_analysis/test_output.txt`
- Test run b44bed42 (3 repetitions with default JFR): `tools/mcp/test-runner/runs/b44bed42/`
