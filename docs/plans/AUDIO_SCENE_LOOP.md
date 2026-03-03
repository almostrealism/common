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

A comprehensive performance analysis ([AUDIO_PERFORMANCE.md](AUDIO_PERFORMANCE.md))
identified the **MultiOrderFilter** (FIR convolution) as the dominant cost
center (~80-90% of compute time), not the envelope `pow()` calls that
were the focus of earlier optimization work. The filter recomputes
sinc/Hamming coefficients per sample instead of pre-computing them once
per buffer, resulting in ~336,000 unnecessary transcendental function
calls per tick.

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

## PREREQUISITE: Revert Failed Optimization Attempt

**Status: COMPLETE** (reverted in commit `3ec07ab83`)

The previous attempt at Goal 1 (commits `a1a1f3538` and `9afb4203d`)
**introduced a performance regression** and was fully reverted. The
changes had caused:

- **Native-only**: 147 ms → 160 ms (+9% slower, 147 kernels vs 143)
- **Metal**: 147 ms → 247 ms (+68% slower, 171 kernels)
- **No sin/cos removal**: The generated C code still contains sin/cos
  inside the inner loop — the coefficient pre-computation did not work

**What to revert:**

1. **`EfxManager.java`**: Remove `consolidatedCoefficientBuffer`,
   `coefficientBufferIndex`, the `efxGpuPrecompute` OperationList, and
   the coefficient buffer allocation/destruction. Restore `applyFilter()`
   to its original form (pass the coefficient expression tree directly
   to `MultiOrderFilter.create()`).

2. **`OperationList.java`**: Revert `enableAutomaticOptimization` and
   `enableSegmenting` back to `false`. These are **global flags** that
   affect every OperationList in the entire codebase. Changing them is
   completely unrelated to the MultiOrderFilter optimization and caused
   additional overhead. See the javadoc on these flags for details about
   their behavior and risks.

3. **Delete test files** that only test the reverted behavior:
   - `MultiOrderFilterCoefficientPrecomputationTest.java`
   - `OperationListSegmentationTest.java`

**After reverting, verify:**
- `effectsEnabledPerformance` runs with avg buffer time ≤ 147 ms
- 143 JNI instruction sets (not more)
- All existing tests pass

**Why the previous attempt failed — READ THIS CAREFULLY:**

The previous approach tried to pre-compute coefficients in `EfxManager`
by evaluating the coefficient expression into a `PackedCollection` buffer,
then passing `cp(coeffBuffer)` to `MultiOrderFilter.create()`. This does
NOT work because of how the AR expression tree system operates:

- `MultiOrderFilter.create()` builds an **expression tree** at compile
  time. The sin/cos coefficient computations are nodes in that tree.
- Passing `cp(coeffBuffer)` as a `Producer` does not cause the filter
  to "read from the buffer at runtime." Instead, `cp()` wraps the buffer
  in a `CollectionProvider` expression node. When the expression tree is
  compiled, it still generates the same inline coefficient computation
  because the *expression structure* hasn't changed — only the *source*
  of one input was wrapped differently.
- The coefficient buffer was written to (by the separate assignment
  operation), but `MultiOrderFilter` never reads from it — it still
  evaluates its own internal sin/cos expression tree per sample.

**The fix must happen inside `MultiOrderFilter.java` itself.** See Goal 1.

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

### Goal 1: Pre-compute MultiOrderFilter coefficients

**Status: INCOMPLETE — the fix is straightforward, see below**

**Source:** `time/src/main/java/org/almostrealism/time/computations/MultiOrderFilter.java`

The 40th-order FIR filter computes sinc windowed coefficients (involving
`sin()` and `cos()`) for each of 41 taps, for each of 4096 samples. But
the cutoff frequency is genome-derived and constant for the entire buffer.
The coefficients should be computed **once** and reused.

**Current cost:** 4096 x 41 = 167,936 iterations, each with 2x `sin()`,
2x `cos()`, 2x division = **~336,000 transcendental calls per buffer**.

#### Why this is hard (expression tree vs. runtime data)

The AR framework compiles computation graphs into native code via
expression trees. When `MultiOrderFilter` builds its expression tree, the
sinc/Hamming coefficient computation is embedded as expression nodes
(containing `Sin`, `Cos`, `Quotient`, etc.) that become inline C code in
the generated kernel. The compiler sees a single expression tree for the
entire "compute coefficients + convolve" operation, and it compiles into
a single kernel with the coefficient math inside the per-sample loop.

The key distinction is between `cp(buffer)` and `p(buffer)`:

- `cp(buffer)` wraps the buffer in a `CollectionProducer` computation
  with an absorbable `Scope`. The expression tree can be inlined.
- `p(buffer)` creates a `CollectionProviderProducer` — a plain buffer
  reference with NO computation scope. There is nothing for
  `Scope.tryAbsorb()` to merge, so the kernel is forced to read from
  the buffer at runtime rather than inlining the coefficient expressions.

#### The fix: two-kernel approach in EfxManager

A working test already demonstrates the pattern — see
`MultiOrderFilterConvolutionTest.convolutionWithChosenCoefficients()`.
The fix applies this same pattern to `EfxManager.applyFilter()`:

1. **Pre-compute coefficients into a physical buffer** as a separate
   kernel (runs once per buffer, 41 iterations with sin/cos):
   ```java
   PackedCollection coeffBuffer = new PackedCollection(filterOrder + 1);
   setup.add(a("efxCoeffs", cp(coeffBuffer.each()), coefficients));
   ```

2. **Pass the buffer to MultiOrderFilter via `p(coeffBuffer)`** (plain
   buffer reference, NOT `cp(coeffBuffer)`):
   ```java
   setup.add(a("efxFilter", cp(destination.each()),
       MultiOrderFilter.create(audio, p(coeffBuffer))));
   ```

When `MultiOrderFilter.getValueAt(i)` reads from a `p(buffer)` input,
it reads pre-computed data — no sin/cos expression tree to inline. The
convolution kernel contains only multiply-accumulate operations.

**No changes to `MultiOrderFilter.java` are required.** The fix is
entirely in `EfxManager.applyFilter()`.

**NOTE on `reference(i)` vs `getValueAt(i)` in MultiOrderFilter:** A
previous attempt tried changing `getValueAt(i)` to `reference(i)` in
`MultiOrderFilter.getScope()`. This is NOT needed and was reverted.
When the coefficient input is already a physical buffer (via `p()`),
`getValueAt(i)` correctly reads from it without inlining. The
`reference(i)` approach was insufficient on its own because of scope
absorption via `Scope.tryAbsorb()`. The two-kernel separation at the
call site is what actually prevents inlining.

#### Acceptance criteria

- [ ] Generated C code shows coefficient computation OUTSIDE the sample loop
- [ ] Inner convolution loop contains only multiply-accumulate (no sin/cos)
- [ ] `effectsEnabledPerformance` produces correct audio output (non-silent,
  similar spectral characteristics)
- [ ] Buffer time drops significantly (target: < 93 ms avg)
- [ ] All existing tests pass

#### How to verify the generated code

After running `effectsEnabledPerformance` with
`-DAR_INSTRUCTION_SET_MONITORING=always`, find the largest `.c` file in
the results directory. That is the MultiOrderFilter kernel. Search for
`sin(` and `cos(` — if they appear between the `for (... global_id ...)`
loop start and the loop's closing brace, the optimization is NOT working.
The convolution inner loop should contain only multiplication and
addition (dot product of coefficients × samples).

---

### Goal 2: Split pipeline into GPU pre-computation + CPU sequential loop

**Status: INCOMPLETE — do not attempt until Goal 1 is complete**

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

**Status: INCOMPLETE — do not attempt until Goal 1 is complete**

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
4. **MultiOrderFilter kernel:** The largest `.c` file should NOT contain
   `sin()` or `cos()` inside the sample loop.

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
