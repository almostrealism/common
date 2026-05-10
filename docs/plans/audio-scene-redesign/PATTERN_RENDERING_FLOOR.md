# Pattern Rendering Floor — Benchmark Results and Interpretation

**Date:** May 2026  
**Branch:** feature/audio-scene-redesign  
**Benchmark class:** `engine/audio/src/test/java/org/almostrealism/audio/benchmark/PatternRenderingFloorBenchmark.java`

---

## What This Measures

The benchmark isolates the cost of the four-kernel pattern rendering chain with no
`PatternLayerManager` / `PatternFeatures` orchestration overhead. It answers:

> "If we kept all four kernels but removed every line of the existing pattern
> orchestration, what is the best per-tick time achievable?"

That number is the lower bound on what is achievable without rethinking the rendering
model. If the floor itself exceeds the 92.9ms ratio-of-1 threshold, Phase 3 structural
restructuring is mandatory, not optional.

### Kernel chain

1. **Pitch interpolation** — linear resample from `[SOURCE_SIZE=2048]` to `[NOTE_SIZE=1024]`
   using fractional-position gather. A new kernel; no existing PDSL primitive.
2. **Volume envelope** — element-wise multiply by a precomputed ADSR curve.
   Mirrors the `scale` PDSL primitive with a producer-valued gain.
3. **FIR lowpass filter** — `MultiOrderFilter` convolution (order 40, cutoff 8 kHz).
   Mirrors the `lowpass` PDSL primitive.
4. **Accumulate** — element-wise add into an output buffer.

Each note is one sequential `.evaluate()` of the compiled chain. The chain is compiled
once; all timed iterations use the same compiled `Evaluable`.

---

## Linux / CPU Baseline Results — Dimension 1

**Platform:** Linux aarch64 Docker container, JNI CPU backend  
**GPU status:** OpenCL library (`JOCL_2_0_4-linux-arm64`) absent — fell back to JNI.  
**Kernel size:** SOURCE_SIZE=2048, NOTE_SIZE=1024, filter order 40  
**Measures per tick:** 32  
**Warmup:** 3 runs × notes; **Timed:** 20 runs

| notes/measure | Total notes/tick | Mean (ms) | Median (ms) | P95 (ms) | StdDev (ms) | Per-note avg (ms) | vs 92.9ms threshold |
|---|---|---|---|---|---|---|---|
| 16 | 512 | 468.78 | 461.58 | 496.60 | 25.10 | 0.9156 | **ABOVE — 5.0× overhead** |
| 32 | 1,024 | 909.35 | 911.40 | 946.13 | 34.21 | 0.8880 | **ABOVE — 9.8× overhead** |
| 64 | 2,048 | 1,804.14 | 1,783.21 | 1,972.72 | 81.49 | 0.8809 | **ABOVE — 19.4× overhead** |
| 128 | 4,096 | 3,563.95 | 3,543.08 | 3,770.78 | 96.07 | 0.8701 | **ABOVE — 38.4× overhead** |
| 256 | 8,192 | 7,095.85 | 7,058.37 | 7,281.34 | 139.02 | 0.8662 | **ABOVE — 76.4× overhead** |

---

## Interpretation

### 1. The per-note cost is essentially constant

At ~0.87–0.92 ms/note across all densities, the scaling is perfectly linear: total time ≈
note_count × 0.87 ms. This is the signature of **purely sequential per-note JNI execution**
with no inter-note parallelism. No GPU kernel fusion occurs across notes, which is exactly
what the benchmark was designed to confirm: in the current per-note-evaluate pattern,
each note is one separate kernel dispatch.

### 2. The Linux/CPU floor does NOT settle the Phase 2 vs Phase 3 question

These numbers are from a JNI (CPU-only) backend on a Linux ARM64 Docker container.
The production environment is Metal on M1 Ultra. Metal is not just "faster" — for
parallelizable workloads it operates in a fundamentally different performance regime.

The question these numbers do not answer: **can Metal parallelize across notes within a
single compiled chain?**

- **Per-note sequential on Metal:** If each note is still a separate Metal kernel
  dispatch (same structure as Linux JNI, just on GPU), speedup vs JNI for this workload
  is typically 2–5× for small kernels (dispatch overhead dominates small payloads).
  At 5× speedup: 64 notes/measure → 1804/5 = 361 ms. Still ~4× above threshold.
- **Batched single-graph on Metal:** If all notes for one tick are composed into one
  `CollectionProducer` and compiled/dispatched as one kernel, Metal can parallelize
  across all notes. With 2048 parallel operations (64 notes × 32 measures), GPU
  throughput could cut the per-note cost by 20–100× vs JNI. At 50× speedup:
  64 notes/measure → 1804/50 = 36 ms. Below threshold.

### 3. What the floor tells us about the execution model

The ~0.87 ms/note on JNI is the cost of four operations on 1024 samples:
- Resample (gather + lerp): read 2048 floats, write 1024
- Volume (multiply): 1024 multiply-accumulate
- Filter (FIR order 40): 1024 × 41 multiply-accumulate = 42,024 MACs
- Accumulate (add): 1024 adds

This is very low arithmetic intensity on a per-note basis. The JNI overhead
(crossing the Java/native boundary, framework bookkeeping) likely dominates over
actual compute for small note sizes. On Metal, the same operations would be
memory-bandwidth-bound, not compute-bound.

### 4. Implications for Phase 2 vs Phase 3

**Phase 2 targeted fixes alone cannot close the gap on Linux/CPU.**

The Linux/CPU floor is 19× above the threshold at the typical density (64 notes/measure).
No amount of orchestration cleanup can close a 19× gap when the kernels themselves cost
that much in sequential execution.

**However, Linux/CPU baseline is not the production environment.** Phase 2 targeted fixes
(allocation reduction, cache hit improvements, batch evaluation) may be sufficient IF
Metal numbers show the kernel floor on Mac well below the threshold. The critical question
is what Metal latency each per-note evaluate() call carries vs what a batched single-graph
dispatch would cost.

**Phase 3 is mandatory IF:** Metal numbers from the Mac run show the optimistic floor
(single-chain per-note evaluation) is above the 92.9ms threshold at typical density.

**Phase 3 may be avoidable IF:** Metal numbers show the floor is well below the threshold
— meaning the gap between the floor and the current 278ms is explained by orchestration
overhead (allocation churn, cache misses, Java loop overhead) that Phase 2 can fix.

### 5. What Mac/Metal numbers are needed

To make the Phase 2 vs Phase 3 decision, run the same benchmark on the M1 Ultra Mac:

```bash
mvn test -pl engine/audio -Dtest=PatternRenderingFloorBenchmark -DAR_TEST_DEPTH=2
```

The critical comparison:
- **Kernel floor on Metal at 64 notes/measure** vs **92.9ms threshold**
- If floor < 92.9ms: Phase 2 targeted fixes have room. The question becomes what fraction
  of the current 278ms is orchestration vs kernel cost.
- If floor ≥ 92.9ms: Phase 3 structural restructuring (batch compilation, single Producer
  per tick) is necessary. The current execution model is structurally insufficient.

---

## Summary Verdict

**Linux/CPU baseline: the floor at 64 notes/measure is 1,804ms — 19× above the 92.9ms
threshold. This is expected for sequential JNI execution and does not prove that Phase 3
is necessary — it only confirms that sequential per-note JNI dispatching is the bottleneck.
Mac/Metal numbers are required before the Phase 2 vs Phase 3 decision can be made.**

The benchmark code is in place and runnable on both Linux and Mac:

```bash
mvn test -pl engine/audio -Dtest=PatternRenderingFloorBenchmark
```

Results are appended to `engine/audio/results/pattern-rendering-floor.txt`.
