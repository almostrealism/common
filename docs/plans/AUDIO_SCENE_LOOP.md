# AudioScene Loop: Runtime Optimization

## Decision Journal — MANDATORY

**You MUST document your reasoning in
[../journals/AUDIO_SCENE_LOOP.md](../journals/AUDIO_SCENE_LOOP.md).**

Before starting work, add a dated entry explaining your understanding of the
problem and your approach. After making changes, update it with what you
observed in the generated code. See the journal file for format instructions.

---

## Overview

The real-time AudioScene renderer compiles a per-frame DSP pipeline into
143 separate JNI instruction sets invoked sequentially per buffer tick.
With 6 channels and the full effects pipeline enabled, the system must
complete within ~93 ms (44.1 kHz, 4096 samples). Currently it runs at
**0.63x real-time** (147ms avg per buffer).

### Corrected bottleneck analysis (2026-03-03)

An earlier analysis ([AUDIO_PERFORMANCE.md](AUDIO_PERFORMANCE.md)) identified
the **MultiOrderFilter** (FIR convolution) as the dominant cost center,
claiming ~336,000 unnecessary sin/cos calls per tick from coefficient
recomputation. **This was incorrect.** Examination of the baseline generated
kernels shows that the existing `ParallelismTargetOptimization` strategy was
ALREADY correctly separating coefficient computation into 4 small pre-compute
kernels (~82 work items each), with the 16 convolution kernels performing
pure multiply-accumulate (0 cos() calls). The coefficient sin/cos cost in
the baseline is ~656 calls total — negligible.

**The actual baseline bottlenecks are:**

1. **Monolithic effects kernel** (4835 lines, 3.4MB): Contains 62 sin() calls
   from LFO modulation, each executing 4096 times per buffer = ~254K
   transcendental calls per tick. This is the dominant compute cost.
2. **143 JNI instruction set invocations**: ~120 are trivial scalar assignments.
   JNI transition overhead accumulates.
3. **Delay line operations**: 22 `AdjustableDelayCell` loops in the monolithic
   kernel add sequential overhead.

**WARNING:** The `ReplicationMismatchOptimization` strategy (see
[AUDIO_PROCESS_OPTIMIZATION.md](AUDIO_PROCESS_OPTIMIZATION.md)) was intended
to improve coefficient isolation but actually BROKE the existing pre-computation
pipeline, causing a 60% regression (147ms → 235ms). It must be reverted or
fixed before any other optimization work. See the decision journal for details.

---

## Baseline (2026-03-03)

From `compose/results/effects-enabled-performance-summary.txt`:

| Metric | Value |
|--------|-------|
| Avg buffer time | **147.31 ms** |
| Target buffer time | 92.88 ms |
| Real-time ratio | **0.63x** |
| Overruns | 43 / 43 (100%) |
| JNI instruction sets per buffer | 143 |

---

## PREREQUISITE: Revert/Fix ReplicationMismatchOptimization Regression

**Status: INCOMPLETE**

There are two regressions to address:

### Revert A: EfxManager/OperationList changes (COMPLETE)

Reverted in commit `3ec07ab83`. The previous attempt at coefficient
pre-computation via `EfxManager` changes + OperationList global flag
changes was fully reverted. See decision journal for details.

### Revert B: ReplicationMismatchOptimization (INCOMPLETE)

The `ReplicationMismatchOptimization` strategy (commits `ced2ab523`,
`e8d1a334f`) causes a **60% performance regression** (147ms → 235ms)
by interfering with the existing coefficient pre-computation pipeline.

**What happened:** The baseline's `ParallelismTargetOptimization` was
ALREADY correctly separating coefficient computation into pre-compute
kernels. The new `ReplicationMismatchOptimization`, placed first in the
`CascadingOptimizationStrategy` chain, intercepts the optimization
decision and makes DIFFERENT isolation choices that merge coefficients
back into the convolution inner loop. See the decision journal and
[AUDIO_PROCESS_OPTIMIZATION.md](AUDIO_PROCESS_OPTIMIZATION.md) for the
full analysis.

**What to revert/fix:**

Option 1 — **Revert** (safest):
1. `ProcessContextBase.java`: Restore default strategy to just
   `ParallelismTargetOptimization` (remove `CascadingOptimizationStrategy`
   wrapper and `ReplicationMismatchOptimization`)
2. Keep `ReplicationMismatchOptimization.java` and its tests for future
   use — the concept is sound, the cascade interaction is the problem

Option 2 — **Fix cascade interaction** (harder):
- Understand why the two strategies make different isolation decisions
  for the MultiOrderFilter process tree
- Modify `ReplicationMismatchOptimization` so it only adds isolation
  on top of what `ParallelismTargetOptimization` would produce

**After reverting/fixing, verify:**
- `effectsEnabledPerformance` runs with avg buffer time ≤ 147 ms
- 143 JNI instruction sets (not more)
- Convolution kernels have 0 cos() (coefficient pre-computation intact)
- All existing tests pass

---

## Prior Work (completed, do NOT break)

- LICM Phases 1-4: f_assignment hoisting + f_licm sub-expression extraction
- Exponent strength reduction: `pow(x,2)` → `x*x`, `pow(x,3)` → `x*x*x`, etc.
- TimeCellReset compact loop generation
- CSE limit at 12 (`ScopeSettings.maxReplacements`)
- All optimizations unconditionally enabled (no feature flags)
- Inner-loop pow() reduced from 150 → 76 (remaining 76 have variable exponents)
- Argument count regression investigated — 389 args is expected with effects enabled

---

## Goals

### Goal 1: ~~Pre-compute MultiOrderFilter coefficients~~ ALREADY DONE IN BASELINE

**Status: NOT NEEDED — baseline already pre-computes coefficients correctly**

**IMPORTANT UPDATE (2026-03-03):** Examination of the baseline generated
kernels reveals that `ParallelismTargetOptimization` was ALREADY correctly
isolating coefficient computation into separate pre-compute kernels. The
convolution kernels in the baseline are pure multiply-accumulate with 0 cos()
calls. The 336,000 unnecessary trig calls described in AUDIO_PERFORMANCE.md
were never present in the generated code.

**Baseline kernel structure (verified from run `f13e3f6b`):**
- 4 coefficient kernels (e.g., `jni_instruction_set_109.c`): ~82 work items,
  compute sinc-windowed coefficients with sin/cos, write to buffer
- 16 clean convolution kernels (e.g., `jni_instruction_set_110.c`): 4096 work
  items, 41-tap loop, pure MAC: `result = (samples[index] * coefficients[i]) + result`
- Total coefficient sin/cos calls: ~656 per buffer (negligible)

**WARNING:** The `ReplicationMismatchOptimization` strategy attempted to
improve this but BROKE it — see [AUDIO_PROCESS_OPTIMIZATION.md](AUDIO_PROCESS_OPTIMIZATION.md).
It must be reverted or fixed to restore baseline performance before proceeding
to Goals 2 and 3.

#### What remains useful from Goal 1 investigation

The `p()` vs `cp()` analysis and the `MultiOrderFilterConvolutionTest` test
infrastructure are valuable reference material for understanding how the
Process optimization system handles coefficient isolation. The two-kernel
pattern demonstrated in `convolutionWithChosenCoefficients()` is the same
pattern that `ParallelismTargetOptimization` produces automatically.

#### Revised acceptance criteria

- [x] Coefficient computation is OUTSIDE the sample loop (already true in baseline)
- [x] Convolution kernels are pure multiply-accumulate (already true in baseline)
- [ ] `ReplicationMismatchOptimization` regression reverted/fixed
- [ ] Baseline performance restored (≤ 147ms avg buffer time)

---

### Goal 2: Split pipeline into GPU pre-computation + CPU sequential loop

**Status: INCOMPLETE — do not attempt until ReplicationMismatchOptimization regression is resolved**

The framework already supports multi-kernel pipelines via `OperationList`
with per-step `ComputeRequirement`, `PackedCollection` for inter-kernel
data, and automatic memory migration (zero-copy on Apple Silicon).

Many components in the effects chain are pure functions of time and genome
parameters (envelopes, automation, volume scaling, sample playback) and
could run as a parallel GPU kernel over all 4096 frames. Only IIR filters
and delay lines require sequential per-frame processing.

**Required work:**

1. **Identify which operations in the cell graph are parallelizable.**
   Use the classification in [AUDIO_PERFORMANCE.md](AUDIO_PERFORMANCE.md).

2. **Restructure the computation graph** so that parallelizable operations
   are expressed as separate `Computation` objects with
   `ComputeRequirement.GPU`, writing intermediate results to
   `PackedCollection` buffers.

3. **Create a minimal CPU sequential loop** that reads pre-computed values
   and only runs the truly sequential parts (IIR filter accumulation,
   delay line read/write, frame counter updates).

4. **Compose the passes** in an `OperationList` pipeline:
   GPU pre-compute → CPU sequential loop.

**Acceptance criteria:**

- [ ] Pipeline split into at least 2 phases (parallel + sequential)
- [ ] Parallel phase runs on GPU (or demonstrates parallelism)
- [ ] Audio output is correct (compare against single-kernel baseline)
- [ ] Performance improvement measured and documented
- [ ] All existing tests pass

---

### Goal 3: Reduce JNI call overhead by fusing trivial kernels

**Status: INCOMPLETE — do not attempt until ReplicationMismatchOptimization regression is resolved**

143 JNI native calls per buffer tick, ~120 of which are trivial scalar
assignments (`v = 0.0`, `v = 1.0`, `v = 4096.0`). Each JNI transition
has overhead that adds up.

**Required work:**

1. **Identify which of the 143 instruction sets are trivial** (constant
   assignments, single-line operations, SummationCell zeroing).

2. **Fuse them into a single batch initialization kernel** — either by
   combining their scopes at compilation time or by replacing them with
   a single memset/initialization operation.

3. **Measure the JNI call count reduction** and its impact on buffer time.

**NOTE:** Do NOT attempt to achieve kernel fusion by flipping
`OperationList.enableAutomaticOptimization` or
`OperationList.enableSegmenting` to `true`. These are global flags with
complex interactions across the entire codebase. They are not required
for this project and must not be changed here. If kernel fusion is
needed, implement it specifically for the AudioScene pipeline at the
composition level (e.g., in the cell graph construction), not by changing
global compilation behavior.

**Acceptance criteria:**

- [ ] JNI instruction set count reduced (target: < 30 per buffer)
- [ ] No change in audio output or test results
- [ ] Buffer time improvement measured and documented
- [ ] All existing tests pass

---

## Verification Protocol

### How to run the verification test

```
mcp__ar-test-runner__start_test_run
  module: "compose"
  test_methods: [{"class": "AudioSceneBufferConsolidationTest",
                  "method": "effectsEnabledPerformance"}]
  jvm_args: ["-DAR_INSTRUCTION_SET_MONITORING=always"]
  timeout_minutes: 15
```

Generated `.c` files appear in `compose/results/<run_id>/`.

### What to check

1. **Timing:** Check `effects-enabled-performance-summary.txt` for avg
   buffer time. Target: < 93 ms.
2. **Kernel count:** Count files in the results directory. Target: < 30.
3. **Audio quality:** Output WAV should be non-silent with reasonable
   spectral content.
4. **Convolution kernels (CRITICAL):** Search ALL `.c` files for the
   41-tap convolution loop (`i <= 40`). These kernels should be pure
   multiply-accumulate with **0 cos() calls**. Check with:
   ```bash
   # Find convolution kernels
   grep -rl 'i <= 40' compose/results/<run_id>/
   # Verify no cos() in them
   for f in $(grep -rl 'i <= 40' compose/results/<run_id>/); do
     echo "$(basename $f): $(grep -c 'cos(' $f) cos()"; done
   ```
   If ANY convolution kernel has cos(), the coefficient pre-computation
   pipeline is broken. The baseline has 0 cos() in all 16 convolution
   kernels.
5. **Do NOT only check the largest `.c` file.** The largest file is the
   monolithic effects kernel, which handles LFO modulation (not
   convolution). It has 62 sin() and 0 cos() in both baseline and
   regression runs. Checking only the largest file will give a false
   sense that coefficient isolation is working.

**If buffer time is unchanged after your modifications, the optimization
is not working.** Do not claim completion based on unit tests alone.

---

## Constraints

- All optimizations MUST be unconditionally enabled — no feature flags
- Do NOT increase `ScopeSettings.maxReplacements` beyond 12
- Do NOT modify pom.xml files
- Do NOT weaken or disable existing tests
- Do NOT change global flags in `OperationList.java` (see Goal 3 note)
- Document all reasoning in the decision journal

## Key Source Files

| File | Purpose |
|------|---------|
| `MultiOrderFilter.java` | FIR filter — **primary optimization target** |
| `OperationList.java` | Multi-kernel pipeline composition (**do not change global flags**) |
| `ComputeRequirement.java` | CPU/GPU selection per operation |
| `Repeated.java` | Loop scope generation and LICM |
| `AcceleratedOperation.java` | Kernel dispatch and execution |
| `DefaultComputer.java` | CPU/GPU context selection logic |
| `Exponent.java` | Strength reduction (already implemented) |

## Related Documents

- [AUDIO_PERFORMANCE.md](AUDIO_PERFORMANCE.md) — **Full performance analysis**
- [Decision Journal](../journals/AUDIO_SCENE_LOOP.md) — **MANDATORY**
- [REALTIME_AUDIO_SCENE.md](REALTIME_AUDIO_SCENE.md) — Architecture and
  buffer consolidation history
