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
| M4 laptop | native | yes | yes (profiled) | ~143 ms (after Expression complexity + Exponent fix) |
| M4 laptop | * (MTL+JNI) | yes | yes (profiled) | ~137 ms (after Expression complexity + Exponent fix) |
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

**Status: PROFILED — GPU helps selectively, net 4% improvement on M4**

MCP test runner hardcodes `AR_HARDWARE_DRIVER=native` in env vars, but
this can be overridden via JVM arg `-DAR_HARDWARE_DRIVER=*` (SystemUtils
checks system properties first, then env vars).

**Back-to-back profiled results** (M4 laptop, JaCoCo + monitoring):

| Metric | Native (941a7cf8) | * Driver (3803d277) |
|--------|-------------------|---------------------|
| Avg buffer time | 143.33ms | **137.46ms (-4%)** |
| Kernel count | 143 JNI | 109 JNI + 30 Metal |
| multiOrderFilter runtime | 9.2ms/tick | **1.2ms/tick (-87%)** |
| collectionProduct runtime | 0.7ms/tick | 5.5ms/tick (+686%) |
| collectionZeros runtime | 0.6ms/tick | 4.4ms/tick (+627%) |

**Earlier results** (M1 Ultra Mac Studio, JaCoCo, no monitoring):
- Metal was **slower** in all configs (native 200ms vs Metal 234ms)
- This was before the Expression complexity system optimizations

**Analysis:**
- The `*` driver routes 17 multiOrderFilter convolution kernels to Metal
  GPU (30 MTL instruction sets), achieving 87% speedup on those operations.
- Small collection operations (product, zeros — 1056 invocations each) incur
  GPU dispatch overhead that makes them 7× slower.
- The monolithic kernel stays on CPU at ~83ms/tick in both configurations.
- Net: GPU gains on filters partially offset by dispatch overhead on small ops.
- **Optimization opportunity:** If small collection operations could be kept on
  CPU while filters go to GPU, the full 8ms/tick filter savings would apply
  without the ~9ms/tick penalty from dispatch overhead on small ops.

**Acceptance criteria:**

- [x] GPU performance measured and compared to native baseline
- [x] Kernel count: 109 JNI + 30 Metal (17 convolution kernels on GPU)
- [x] Profiled per-operation breakdown comparing native vs * driver
- [x] Analysis: GPU helps filters (-87%), hurts small ops (+686%), net -4%

---

### Goal 6: Reduce per-sample transcendental function calls — EFFECTIVELY COMPLETE

**Status: RESOLVED by Expression complexity system + Exponent strength-reduction fix**

#### Results summary

Two platform-wide changes resolved the inner-loop transcendental cost:

1. **Expression complexity system** (`docs/plans/EXPRESSION_COMPLEXITY.md`):
   Added `getComputeCost()` / `totalComputeCost()` to Expression, with
   cost-aware CSE ranking, caching eligibility bypass, and LICM extraction.

2. **Cost-aware Exponent strength reduction** (`Exponent.create()`): No longer
   expands `pow(expensive_base, 2)` into `expensive_base * expensive_base`
   when `base.totalComputeCost() >= ScopeSettings.getStrengthReductionCostThreshold()`.
   This prevents patterns like `pow(sin(x), 2)` from duplicating the sin() call.

All results: `AR_HARDWARE_DRIVER=native` (CPU/JNI only, no GPU), M4 laptop,
JaCoCo coverage active.

| Metric | Baseline | After both fixes | Change |
|--------|----------|------------------|--------|
| Inner loop sin() | 148 | **11** | **93% reduction** |
| Inner loop pow() | 76 | **11** | **86% reduction** |
| Pre-loop sin() (LICM-hoisted) | 0 | 234 | Hoisted from loop |
| Pre-loop pow() (LICM-hoisted) | 0 | 345 | Hoisted (incl. pow(sin,2)) |
| Total kernel lines | 4,823 | 5,642 | +819 (LICM declarations) |
| Avg buffer time | — | 163.57ms | — |

#### Runtime budget breakdown (back-to-back, M4 laptop, JaCoCo + monitoring)

| Operation | Native (941a7cf8) | * Driver (3803d277) | Change |
|-----------|-------------------|---------------------|--------|
| **Avg buffer time** | **143.33ms** | **137.46ms** | **-4%** |
| Kernel count | 143 JNI | 109 JNI + 30 Metal | — |
| f_loop (monolithic) | 82.5ms/tick | 84.3ms/tick | ~same |
| multiOrderFilter | 9.2ms/tick (37 inv) | 1.2ms/tick (46 inv) | **-87%** |
| collectionProduct | 0.7ms/tick (1056 inv) | 5.5ms/tick (1056 inv) | **+686%** |
| collectionZeros | 0.6ms/tick (1056 inv) | 4.4ms/tick (1056 inv) | **+627%** |
| collectionAdd | 2.8ms/tick (3263 inv) | 3.5ms/tick (3423 inv) | +25% |

**Key findings:**
- The `*` driver routes multiOrderFilter convolution to Metal GPU, achieving
  an **87% speedup** (9.2ms → 1.2ms/tick) on those 17 convolution kernels.
- Small collection operations (product, zeros) are **dramatically slower** with
  GPU dispatch overhead — collectionProduct goes from 0.7ms to 5.5ms/tick.
- The monolithic kernel stays on CPU (JNI) in both configurations at ~83ms/tick.
- **Net effect is a modest 4% improvement** because GPU gains on filters are
  partially offset by dispatch overhead on small operations.
- The unaccounted overhead (buffer time minus profiled runtime) is ~45ms/tick
  in both cases, suggesting JNI/Java dispatch costs are similar.

**Inner loop sin(): 11 calls, 10 unique.** The sole duplicate is
`sin(time * f_assignment_26455_0)` appearing on two lines writing to
different vector elements (savings: 1 × 4096 = ~82μs/tick — negligible).
The other 9 are genuinely unique LFO patterns with different phase offsets
from genome/parameter arrays — they cannot be deduplicated.

**Cost-aware LICM was the dominant mechanism.** `Repeated.isSubstantialForExtraction()`
hoists expressions with `totalComputeCost() >= 15` regardless of tree depth,
moving ~137 sin() and ~65 pow() out of the 4096-iteration inner loop.

#### Remaining low-priority opportunity

**Pre-loop CSE saturation (LOW IMPACT — 165 extra sin() × 1 = 165/tick)**

The LICM-hoisted pre-loop has 234 sin() calls but only 69 unique patterns.
The 165 duplicates aren't caught because `ScopeSettings.getMaximumReplacements() = 12`
caps total CSE replacements. Since these execute only once per tick (not per
sample), the impact is ~3.3μs at 20ns/sin. Top duplication: 4 sin patterns
appear 14× each (LFO with channel-specific phase offsets, duplicated across
compilation units).

**Recommendation:** Raising `getMaximumReplacements()` from 12 to 64+ would
capture these, but the performance impact is negligible for this workload.
Worth doing as a general improvement to the platform, not as an
audio-loop-specific optimization.

#### Acceptance criteria

- [x] Cost-aware LICM hoists expensive expressions from inner loop
- [x] Inner loop sin() reduced from 148 to 11 (verified in generated C)
- [x] Inner loop pow() reduced from 76 to 11
- [x] Expression complexity system deployed platform-wide
- [x] Exponent strength reduction respects compute cost
- [x] Audio output quality preserved (tests pass, 0 failures)
- [ ] Buffer time improvement measured back-to-back on same machine (pending)

#### Future: Angle-addition optimization (separate task, low priority)

The 9 unique inner-loop sin() patterns share a common rate (`f_licm_2`)
with different phase offsets. Using the angle-addition formula:

    sin(frame*rate + phase) = sin(frame*rate)*cos(phase) + cos(frame*rate)*sin(phase)

we could compute `sin(frame*rate)` and `cos(frame*rate)` once per sample,
then derive all 9 patterns via multiply-add:
- 9 sin() → 2 sin() + 2 cos() + 9 multiply-adds = 4 transcendental
- `cos(phase)` and `sin(phase)` terms are loop-invariant → LICM hoists them

**Priority:** Low. The 11 remaining inner-loop sin() calls add ~0.9ms/tick
(11 × 4096 × 20ns). Angle-addition would save ~0.6ms of that. Not worth
the implementation complexity unless real-time compliance is within reach.

---

### Goal 7: Optimize time series interpolation — DEFERRED

22 `f_acceleratedTimeSeriesValueAt` calls per sample use O(N) linear search
via the deprecated `AcceleratedTimeSeriesValueAt`. The replacement
(`Interpolate` with `enableFunctionalPosition=true`) gives O(1) lookup.
Source: `AdjustableDelayCell.tick()` line 111. Profile evidence (test
39d2d825, `AR_HARDWARE_DRIVER=native`): 20 instances totaling 82ms across
the full test run = ~1.9ms/tick (1.2% of buffer time). Not worth targeting
until higher-impact opportunities are exhausted. See journal entry
2026-03-04 for full investigation details.

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
