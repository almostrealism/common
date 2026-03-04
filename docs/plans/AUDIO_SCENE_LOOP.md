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

### Bottleneck analysis

The performance analysis ([AUDIO_PERFORMANCE.md](AUDIO_PERFORMANCE.md))
identified the MultiOrderFilter coefficient computation as a significant
cost center. The `ReplicationMismatchOptimization` strategy (see
[AUDIO_PROCESS_OPTIMIZATION.md](AUDIO_PROCESS_OPTIMIZATION.md)) addresses
this by selectively isolating low-parallelism children, providing a 6-16%
improvement (verified back-to-back on M2 desktop).

**Remaining bottlenecks (in priority order):**

1. **Monolithic effects kernel** (4835 lines, 3.4MB): Contains 62 sin() calls
   from LFO modulation, each executing 4096 times per buffer = ~254K
   transcendental calls per tick. This is the dominant compute cost.
2. **143 JNI instruction set invocations**: ~120 are trivial scalar assignments.
   JNI transition overhead accumulates.
3. **Delay line operations**: 22 `AdjustableDelayCell` loops in the monolithic
   kernel add sequential overhead.

### Baseline calibration

**Performance varies significantly by machine and conditions.** All absolute
numbers must record the hardware driver, machine, and whether JaCoCo/monitoring
were active. Only back-to-back comparisons under identical conditions are valid.

| Machine | Driver | JaCoCo | Monitoring | Approx Range |
|---------|--------|--------|------------|--------------|
| M4 laptop | native | yes | yes | 116–254 ms (high variance) |
| M2 desktop | native | yes | no | ~352 ms |
| M2 desktop | native | no | no | ~186 ms (with ReplicationMismatchOptimization) |
| M4 laptop | * (Metal) | yes | yes | ~247 ms (failed agent code, 171 kernels) |

The 147ms figure in the original baseline table below was from a favorable
thermal state on the M4 laptop. It is not reliably reproducible.

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

**Status: RESOLVED — No regression exists**

### Revert A: EfxManager/OperationList changes (COMPLETE)

Reverted in commit `3ec07ab83`. The previous attempt at coefficient
pre-computation via `EfxManager` changes + OperationList global flag
changes was fully reverted. See decision journal for details.

### Revert B: ReplicationMismatchOptimization (RESOLVED — no action needed)

**The claimed regression was based on incorrect baseline measurements.**

Verification against master in an isolated worktree (run `5d8c818d`)
showed that master itself produces 17 convolution kernels with 1 cos()
each and ~224ms avg buffer time with monitoring. The review's claim of
"0 cos() in convolution kernels" and "147ms baseline" does not match
the actual master branch behavior.

**Back-to-back comparison** (same machine, same conditions):

| Configuration | Avg Buffer Time | Delta |
|---------------|----------------|-------|
| ParallelismTargetOptimization only | 375.72 ms | baseline |
| CascadingOptimizationStrategy (ReplicationMismatch + ParallelismTarget) | 352.04 ms | **-6%** |

Earlier runs without JaCoCo overhead showed a ~16% improvement
(220ms → 186ms). The strategy is **retained** in `ProcessContextBase`.

See decision journal entry "2026-03-03 — Verification: Review's baseline
claims are incorrect" for full analysis.

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

### Goal 1: Reduce MultiOrderFilter coefficient cost

**Status: PARTIALLY ADDRESSED by `ReplicationMismatchOptimization` (6-16% improvement)**

The `ReplicationMismatchOptimization` strategy selectively isolates
low-parallelism children in the process tree, which includes the filter
coefficient computation. This provides a measurable 6-16% improvement
(verified back-to-back on M2 desktop). See
[AUDIO_PROCESS_OPTIMIZATION.md](AUDIO_PROCESS_OPTIMIZATION.md) for details.

**Baseline kernel structure** (consistent across all verified runs):
- 17 convolution kernels with 1 cos() each (Hamming window computation)
- Convolution kernels contain a 41-tap inner loop with inline coefficient
  computation (sin/cos per tap per sample)

**Further optimization opportunity:** The convolution kernels still compute
sinc/Hamming coefficients inline. Full coefficient pre-computation into a
separate buffer (demonstrated in `MultiOrderFilterConvolutionTest
.convolutionWithChosenCoefficients()` using the `p()` pattern) could
eliminate the remaining per-sample trig calls. However, this is a lower
priority than addressing the monolithic kernel's LFO sin() cost (Goal 4).

#### Reference material

The `p()` vs `cp()` analysis and the `MultiOrderFilterConvolutionTest` test
infrastructure are valuable for understanding how the Process optimization
system handles coefficient isolation.

#### Acceptance criteria

- [x] `ReplicationMismatchOptimization` provides measurable improvement
- [ ] Further coefficient pre-computation (lower priority than Goal 4)

---

### Goal 2: Split pipeline into GPU pre-computation + CPU sequential loop

**Status: INCOMPLETE**

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

**Status: INCOMPLETE**

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

### Goal 4: Reduce LFO modulation sin() cost in monolithic kernel — NEXT PRIORITY

**Status: INCOMPLETE — this is the highest-impact remaining optimization**

The monolithic effects kernel (4835 lines, 3.4MB) contains 62 sin() calls
from LFO/automation modulation. Each executes 4096 times per buffer tick,
producing ~254K transcendental function calls — the single largest compute
cost in the pipeline.

**Investigation needed:**

1. **Identify what generates the 62 sin() calls.** These are from automation
   curves, LFO modulation, or envelope computation in the effects chain.
   Use `-DAR_INSTRUCTION_SET_MONITORING=always` and examine the monolithic
   kernel to trace which source computations produce the sin() expressions.

2. **Determine if LFO parameters are per-buffer constants.** If the LFO
   frequency/phase parameters come from genome values and the frame counter
   (both constant within a buffer tick), the sin() values could be
   pre-computed once per buffer instead of per sample.

3. **Evaluate optimization approaches:**
   - **LICM hoisting**: If the sin() arguments are loop-invariant (same
     across all 4096 samples), LICM should already hoist them. If they're
     NOT being hoisted, investigate why — this may be a LICM gap.
   - **Pre-computation via process isolation**: If the LFO computation has
     lower parallelism than the monolithic kernel, the
     `ReplicationMismatchOptimization` should already detect it. If not,
     the threshold or parallelism detection may need adjustment.
   - **Cheaper approximation**: If sin() varies per-sample (e.g., driven
     by a per-sample counter), consider polynomial or lookup table
     approximations for LFO where precision is not critical.

**Acceptance criteria:**

- [ ] sin() calls in the monolithic kernel reduced (identify target count)
- [ ] Buffer time improvement measured (back-to-back, same conditions)
- [ ] Audio output quality preserved
- [ ] All existing tests pass

---

### Goal 5: Test with GPU acceleration (AR_HARDWARE_DRIVER=*)

**Status: NOT TESTED with clean code**

All MCP test runner runs use `AR_HARDWARE_DRIVER=native` (hardcoded in
`tools/mcp/test-runner/server.py` line 193). GPU acceleration via Metal
has never been properly tested with the current clean codebase.

One test with `-DAR_HARDWARE_DRIVER=*` on the M4 laptop (run `cfcb5834`)
produced 247ms with 171 kernels, but this was with the failed agent changes
and is not representative.

**Required work:**

1. **Run `effectsEnabledPerformance` with `-DAR_HARDWARE_DRIVER=*`** on the
   M2 desktop to get a proper GPU baseline.

2. **Compare kernel count**: Metal may generate additional kernels (171 vs
   143 was observed). Understand why and whether this is expected.

3. **Profile**: If GPU doesn't help, determine whether the bottleneck is
   kernel launch overhead (143+ JNI calls) or data transfer.

**NOTE:** GPU acceleration is most likely to help AFTER Goals 3 and 4
reduce the kernel count and per-kernel compute cost. With 143 sequential
kernel launches, GPU dispatch overhead may negate parallelism gains.

**Acceptance criteria:**

- [ ] GPU performance measured and compared to native baseline
- [ ] Kernel count difference documented
- [ ] Analysis of whether GPU helps or hurts, and why

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
   buffer time. **Record the machine, AR_HARDWARE_DRIVER, and whether
   JaCoCo/monitoring were active.** Only compare against runs from the
   same machine under the same conditions.
2. **Kernel count:** Count `.c` files in the results directory.
   Baseline: 143. Target for Goal 3: < 30.
3. **Audio quality:** Output WAV should be non-silent with reasonable
   spectral content.
4. **Convolution kernels:** Search ALL `.c` files for the 41-tap
   convolution loop (`i <= 40`). Baseline has 17 such kernels, each
   with 1 cos() call (Hamming window). Check with:
   ```bash
   for f in $(grep -rl 'i <= 40' compose/results/<run_id>/); do
     echo "$(basename $f): $(grep -c 'cos(' $f) cos()"; done
   ```

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
