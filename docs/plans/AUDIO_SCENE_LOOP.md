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
improvement (verified back-to-back on M1 Ultra Mac Studio).

**Remaining bottlenecks (profile-verified, 2026-03-04):**

1. **Per-sample sin() calls inside the inner loop**: Profile analysis of
   `f_loop_33020` (4823 lines, 2776-line inner loop body) reveals **148 sin()
   calls per sample iteration** × 4096 samples = **606K sin() evaluations per
   buffer tick**. These are NOT in the f_licm pre-loop section — they depend on
   the loop variable. This is the dominant per-tick cost.
2. **Time series interpolation**: 22 `f_acceleratedTimeSeriesValueAt` calls per
   sample, each doing a **linear search** through time series data. This is
   O(samples × entries) per call.
3. **Per-sample arithmetic volume**: 107K additions and 35K multiplications per
   sample, plus 256 fmod(), 79 pow(), 40 floor() calls.
4. **143 JNI instruction set invocations**: ~120 are trivial scalar assignments.
   JNI transition overhead accumulates.
5. **Compile time** (23.3s one-time): 4823 lines → Clang overhead. Not a
   real-time concern but affects developer iteration speed.

### Baseline calibration

**Performance varies significantly by machine and conditions.** All absolute
numbers must record the hardware driver, machine, and whether JaCoCo/monitoring
were active. Only back-to-back comparisons under identical conditions are valid.

| Machine | Driver | JaCoCo | Monitoring | Approx Range |
|---------|--------|--------|------------|--------------|
| M4 laptop | native | yes | yes | 116–254 ms (high variance) |
| M1 Ultra Mac Studio | native | yes | no | ~352 ms |
| M1 Ultra Mac Studio | native | no | no | ~186 ms (with ReplicationMismatchOptimization) |
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
- AutomationManager expression factoring for LICM (Goal 4 Phase 1) — sin()
  arguments restructured so invariant sub-expressions are hoistable

---

## Goals

### Goal 1: Reduce MultiOrderFilter coefficient cost

**Status: PARTIALLY ADDRESSED by `ReplicationMismatchOptimization` (6-16% improvement)**

The `ReplicationMismatchOptimization` strategy selectively isolates
low-parallelism children in the process tree, which includes the filter
coefficient computation. This provides a measurable 6-16% improvement
(verified back-to-back on M1 Ultra Mac Studio). See
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

**Status: PHASE 1+2 COMPLETE (expression factoring + polycyclic), MEASUREMENT COMPLETE — regression on native, neutral on Metal**

The monolithic effects kernel (4835 lines, 3.4MB) contains 62 sin() calls
from LFO/automation modulation. Each executes 4096 times per buffer tick,
producing ~254K transcendental function calls — the single largest compute
cost in the pipeline.

#### Phase 1: Expression factoring for LICM (COMPLETE)

`AutomationManager` expressions were algebraically restructured (commit
`d26249cc5`) so that LICM can hoist loop-invariant sub-expressions. The
old form `sin(time(phase) * freq)` entangled the loop-variant frame counter
with invariant parameters (sampleRate, scale, phase), preventing hoisting.

The new form factors the argument: `sin(frame * angularRate + phaseContrib)`
where `angularRate` and `phaseContrib` are loop-invariant and hoisted as
`f_licm_*` declarations. Generated code confirms:
- `clock / 44100.0` eliminated from inner loop body (was ~126 occurrences)
- Hoisted invariants: `f_licm_6` through `f_licm_10` appear before the loop
- Kernel line count reduced from 4903 → 4823 (80 fewer lines)
- sin() count: 62 (unchanged — arguments are cheaper, not eliminated)

**File modified:** `AutomationManager.java` — new `computePeriodicValue()`
helper, rewrote `getShortPeriodValue`, `getLongPeriodValue`, `getMainValue`.
`*ValueAt` methods left unchanged for backward compatibility.

#### Phase 2: Polycyclic factoring + measurement (COMPLETE)

**Polycyclic factoring** (commit `61cd5805f`): Applied same LICM factoring
to `OptimizeFactorFeatures.polycyclic()`. Changed `toPolycyclicGene()` to
pass `clock.frame(), sampleRate` instead of `clock.time(sampleRate)`.
Factored 48 additional sin() calls (6 unique × 8 duplicates).

**Back-to-back measurement** (M1 Ultra Mac Studio, JaCoCo, no monitoring):

| Run ID | Code | Driver | Avg Buffer | Min Buffer |
|--------|------|--------|-----------|-----------|
| 5786d96e | Factored (Phase 1+2) | native (FP64) | 220.90 ms | 189 ms |
| 0163ddfd | Factored (Phase 1+2) | * (MTL+JNI, FP32) | 230.90 ms | 202 ms |
| ddb6255e | Unfactored | native (FP64) | 200.06 ms | 181 ms |
| 3152eed8 | Unfactored | * (MTL+JNI, FP32) | 233.59 ms | 211 ms |

**Findings:**
- **Native driver**: Factoring is a ~10% regression (200→221ms). Clang/LLVM
  already performs its own LICM; manual factoring may increase register
  pressure from 128 `f_licm_*` variables.
- **Metal driver**: Factoring is neutral-to-marginal-improvement (234→231ms).
- **Metal vs Native**: Metal is slower in all cases — 143 sequential kernel
  launches incur GPU dispatch overhead that outweighs parallelism gains.

**Decision:** Expression factoring does not help on native driver (primary
production path). Additional runs recommended to confirm. Further
sin() reduction approaches still available:

- **Polynomial approximation**: `sinApprox()` at expression tree level
- **LFO value sharing**: CSE limited to 12 replacements, 36 unique patterns
  competing. Teaching CSE to prioritize by duplication count could eliminate
  42 redundant sin() calls.
- **Per-buffer pre-computation**: Some sin() args may be fully invariant

**Acceptance criteria:**

- [x] Expression factoring enables LICM hoisting of sin() arguments
- [x] Phase 1+2 improvement measured back-to-back (2x2 matrix: native/Metal)
- [x] Decision: factoring is regression on native, neutral on Metal
- [ ] Confirm regression with additional runs before final revert decision
- [x] Audio output quality preserved (tests pass)
- [x] All existing tests pass

---

### Goal 5: Test with GPU acceleration (AR_HARDWARE_DRIVER=*)

**Status: INITIAL TESTING COMPLETE (M1 Ultra Mac Studio)**

MCP test runner hardcodes `AR_HARDWARE_DRIVER=native` in env vars, but
this can be overridden via JVM arg `-DAR_HARDWARE_DRIVER=*` (SystemUtils
checks system properties first, then env vars).

**Results** (M1 Ultra Mac Studio, JaCoCo, no monitoring, see Goal 4 Phase 2 table):
- Metal is **slower** than native in all configurations tested
- Factored code: native 221ms vs Metal 231ms (+5%)
- Unfactored code: native 200ms vs Metal 234ms (+17%)
- Metal uses FP32 precision, kernel count unchanged at 143

**Required work:**

1. ~~**Run `effectsEnabledPerformance` with `-DAR_HARDWARE_DRIVER=*`**~~ DONE
   (M1 Ultra Mac Studio). Metal is slower than native with 143 kernels.

2. **Compare kernel count**: Metal may generate additional kernels (171 vs
   143 was observed). Understand why and whether this is expected.

3. **Profile**: If GPU doesn't help, determine whether the bottleneck is
   kernel launch overhead (143+ JNI calls) or data transfer.

**NOTE:** GPU acceleration is most likely to help AFTER Goals 3 and 4
reduce the kernel count and per-kernel compute cost. With 143 sequential
kernel launches, GPU dispatch overhead may negate parallelism gains.

**Acceptance criteria:**

- [x] GPU performance measured and compared to native baseline
- [x] Kernel count: unchanged at 143 (no additional Metal kernels)
- [x] Analysis: Metal hurts — 143 sequential dispatches incur overhead > parallelism gain

---

### Goal 6: Reduce per-sample sin() calls — HIGHEST PRIORITY (profile-defended)

**Status: PLANNING**

**Profile evidence:** `f_loop_33020` inner loop (lines 2041-4817 in generated
C code) contains 148 sin() calls that execute on EVERY sample iteration.
At 4096 samples per buffer, this produces **606,208 sin() evaluations per tick**.
The kernel runs at 82ms/tick on M4 laptop — the 93ms real-time target requires
reducing this to <93ms.

The 128 `f_licm_*` pre-loop declarations already hoist angular rates and phase
contributions, but the sin() calls themselves CANNOT be hoisted because they
depend on the loop variable `_33020_i` (the sample frame index):
`sin(_33020_i * angularRate + phaseContrib)`.

#### Approach A: LFO sub-buffer approximation

LFO frequencies are typically 0.1Hz–20Hz. At 44.1kHz, even a 20Hz LFO
completes only ~1.86 cycles per 4096-sample buffer. This means the sin()
value changes slowly across the buffer. Instead of computing sin() per sample:

1. Compute sin() at N evenly-spaced points (e.g., N=16 or N=64)
2. Linearly interpolate between them for intermediate samples

This reduces 148 × 4096 = 606K sin() calls to 148 × 64 = 9,472 sin() calls
(~64× reduction). The interpolation cost is a multiply + add per sample.

**Implementation:** This requires changes at the expression tree level
(probably in `AutomationManager` or the `Repeated` loop generation) to
detect sin() expressions that vary linearly with the loop counter and replace
them with a pre-computed lookup + interpolation pattern.

**Risk:** Requires framework-level changes to the expression compilation
pipeline. The interpolation introduces a small approximation error.

#### Approach B: Polynomial sin() approximation

Replace `sin(x)` with a fast polynomial approximation (e.g., Bhaskara I
formula or minimax polynomial) at the code generation level. This avoids
per-sample function call overhead while maintaining reasonable accuracy
for audio LFO modulation (which doesn't need IEEE 754 precision).

**Implementation:** Add a `sinApprox()` expression node that generates
inline polynomial code instead of calling `sin()`. Apply it selectively
to LFO/automation expressions.

**Risk:** Audio quality impact — needs A/B testing. Lower risk than
Approach A because it's a drop-in replacement, not a structural change.

#### Approach C: Identify and eliminate redundant sin() calls

The 148 sin() calls per sample may include duplicates that CSE failed to
catch (CSE limit is 12 replacements, 36 unique patterns competing). Profile
data shows 128 f_licm variables — if some share the same angular rate and
phase, their sin() values are identical.

**Investigation needed:** Extract the sin() arguments from the loop body
to identify duplicates that could be merged at the expression tree level.

**Priority:** C first (investigation only), then B or A based on findings.

**Acceptance criteria:**

- [ ] Per-sample sin() call count reduced (target: < 30 per sample)
- [ ] Buffer time improvement measured back-to-back
- [ ] Audio output quality preserved (listen test + spectral comparison)
- [ ] All existing tests pass

---

### Goal 7: Optimize time series interpolation — SECONDARY PRIORITY (profile-defended)

**Status: PLANNING**

**Profile evidence:** 22 `f_acceleratedTimeSeriesValueAt` calls per sample
iteration, each performing a **linear search** from the beginning of the time
series data. The search loop (`for (int i = start; i < end; i++)`) scans
for the correct interpolation interval on every sample.

Since the time series is accessed with monotonically increasing time values
(the frame counter), the previous position is almost always correct or one
step forward.

#### Approach: Last-position caching

Add a local variable per time series call that remembers the last found
index. On the next sample, start the search from that position instead of
from the beginning.

**Implementation:** Modify the generated `f_acceleratedTimeSeriesValueAt`
functions to accept and return a position hint variable. Or, modify the
expression tree representation of `AcceleratedTimeSeries.valueAt()` to
include a cached position.

**Expected impact:** Reduces O(samples × timeseries_length) to O(samples),
since each lookup only needs to check 1-2 positions.

**Acceptance criteria:**

- [ ] Time series lookup converted from linear to incremental search
- [ ] Buffer time improvement measured
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
5. **LICM hoisting (Goal 4):** In the monolithic kernel (largest `.c`
   file), verify that `clock / 44100.0` does NOT appear in the inner
   loop body. Hoisted invariants should appear as `f_licm_*` declarations
   before the loop. Check with:
   ```bash
   BIGGEST=$(ls -lS compose/results/<run_id>/*.c | head -1 | awk '{print $NF}')
   echo "clock/44100 in loop: $(grep -c '44100' $BIGGEST)"
   echo "f_licm declarations: $(grep -c 'f_licm_' $BIGGEST)"
   ```

6. **Profile analysis (Goal 6/7):** Run with profiling enabled and use
   the ar-profile-analyzer MCP tools to compare per-sample operation counts
   and timing breakdown. The test now saves
   `results/effects-enabled-performance-profile.xml` when profiling is
   enabled via `Hardware.getLocalHardware().assignProfile(profile)`.
   Key commands:
   ```
   mcp__ar-profile-analyzer__get_source_summary  node_key:"<loop_key>"
   mcp__ar-profile-analyzer__get_timing_breakdown node_key:"<loop_key>"
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
| `AutomationManager.java` | LFO/automation modulation — **Goal 4 target** (Phase 1 complete) |
| `MultiOrderFilter.java` | FIR filter — coefficient optimization target |
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
