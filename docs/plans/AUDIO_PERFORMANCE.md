# AudioScene Performance Analysis

**Date:** 2026-03-03
**Author:** Review agent (independent analysis)
**Branch:** `feature/audio-loop-performance`

This document captures the findings of a comprehensive performance
investigation of the AudioScene real-time renderer. It supersedes the
earlier analysis that focused narrowly on `pow()` call counts in the
inner loop, which turned out to be a secondary cost center.

See [AUDIO_SCENE_LOOP.md](AUDIO_SCENE_LOOP.md) for actionable goals
derived from this analysis.

---

## Executive Summary

The AudioScene renderer with full effects enabled runs at **0.63x
real-time** (147ms avg per 93ms buffer). The dominant cost is NOT the
envelope `pow()` computation — it is the **MultiOrderFilter** (FIR
convolution) recomputing sinc/Hamming filter coefficients per sample
rather than pre-computing them once per buffer. This single kernel
accounts for an estimated **80-90% of total compute time**, performing
~336,000 unnecessary transcendental function calls (sin + cos) per
buffer tick.

Secondary costs include JNI transition overhead (143 separate native
calls per buffer, ~120 of which are trivial scalar assignments) and
the 76 remaining variable-exponent `pow()` calls in the envelope
computation.

---

## Architecture: Not a Single Loop

A critical finding: the AudioScene **does not compile into a single
monolithic C function** with a 4096-iteration loop. It decomposes
into **143 separate JNI instruction sets** (`jni_instruction_set_0.c`
through `jni_instruction_set_142.c`) that are invoked sequentially
by the Java-side scheduler per buffer tick.

This changes the optimization landscape significantly — the "inner
loop pow() count" metric we tracked earlier applies only to one of
these 143 kernels. The overall pipeline cost is dominated by a
different kernel entirely.

---

## Timing Baseline

From `compose/results/effects-enabled-performance-summary.txt`:

| Metric | Value |
|--------|-------|
| Buffer count | 43 |
| Target buffer time | 92.88 ms |
| **Avg buffer time** | **147.31 ms** |
| Min buffer time | 133.29 ms |
| Max buffer time | 332.80 ms |
| Real-time ratio | **0.63x** |
| Overruns | 43 / 43 (100%) |

---

## Cost Breakdown by Component

### 1. MultiOrderFilter — ~80-90% of compute time

**File:** `jni_instruction_set_120.c` (33,438 tokens, largest kernel)
**Source:** `time/src/main/java/org/almostrealism/time/computations/MultiOrderFilter.java`

This is a 40th-order FIR convolution filter used by `EfxManager.applyFilter()`
for per-channel filter effects. The inner loop structure:

```c
for (int global_id = 0; global_id < 4096; global_id++) {
    double result = 0.0;
    for (int i = 0; i <= 40; i++) {
        // Compute sinc windowed coefficient per tap, per sample:
        //   sin((tap_offset) * pi * cutoff) / (tap_offset * pi)
        //   * (0.54 - 0.46 * cos(tap_index * 2 * pi / N))
        // Then multiply by input sample and accumulate
        result += coefficient * input[circular_index];
    }
    output[global_id] = result;
}
```

**The problem:** The sinc kernel coefficients depend only on the cutoff
frequency (`f_licm_1`), which is genome-derived and constant for the
entire buffer. Yet the filter recomputes `sin()` and `cos()` for all
41 taps for every one of the 4096 samples.

**Per-buffer cost:**
- 4096 samples x 41 taps = 167,936 iterations
- Each iteration: 2x `sin()`, 2x `cos()`, 2x division, branching
- Total: ~336,000 transcendental function calls per buffer

**The fix:** Pre-compute the 41 filter coefficients once per buffer
(when the cutoff frequency is set), then apply them as a simple
dot-product convolution. This would reduce transcendental calls from
336,000 to 41 — a **~8,000x reduction** in trig computation.

### 2. JNI Transition Overhead — ~5-10%

143 separate JNI native calls per buffer tick. The breakdown:

| Category | Approx. count | What they do |
|----------|---------------|-------------|
| SummationCell zeroing | 50+ | `v = 0.0; v = 0.0;` |
| WaveCell setup | 12+ | `v = 1.0; v = 0.0; v = 4096.0;` |
| ScalarTemporalCellAdapter init | 12+ | Adapter initialization |
| AdjustableDelayCell init | 6+ | Delay line setup |
| Genome normalization | 2 | `pow(pow(dot128, 0.5), -1.0) * value` |
| MultiOrderFilter | 1 | The expensive FIR convolution |
| Enumerate/Repeat | 3 | Array reshape/copy |
| Memory alloc/copy | 4 | JNI array operations |

The ~120 trivial kernels could be fused into a single batch
initialization kernel, eliminating most of the JNI transition cost.

### 3. Envelope/Automation pow() — ~2-5%

76 remaining `pow()` calls with variable (genome-derived) exponents.
After strength reduction eliminated constant-exponent cases:

- 50 with exponent 3.0 → now `x*x*x` (strength-reduced)
- 24 with exponent 2.0 → now `x*x` (strength-reduced)
- **76 with variable exponents** → genuine runtime `pow()` calls

The variable exponents come from genome parameters used as nonlinear
transfer function shaping. These cannot be eliminated without changing
the DSP algorithm.

### 4. Setup Operations — ~2-5%

Trivial per-buffer initialization (dominated by JNI overhead, not
computation). Would be addressed by the JNI fusion in item 2.

---

## Effect-by-Effect Analysis

### Effects That Require Frame-at-a-Time Processing

| Effect | Class | Why Sequential |
|--------|-------|---------------|
| AudioPassFilter (IIR hp/lp) | `AudioPassFilter.java` | Output y[n] depends on y[n-1] and y[n-2] (feedback). Second-order biquad IIR filter. Also recomputes coefficients from frequency/resonance each tick. |
| BiquadFilterCell | `BiquadFilterCell.java` | Same IIR feedback limitation. Direct Form I implementation. Coefficients computed at configuration time (not per-frame). |
| AdjustableDelayCell | `AdjustableDelayCell.java` | Cursor-pair-based circular buffer read/write. Could be vectorized if delay is constant. |
| DelayNetwork (reverb FDN) | `DelayNetwork.java` | 128 delay lines with Householder matrix feedback. Cross-frame feedback is sequential, but the within-frame matmul is parallel (already hardware-accelerated). |
| ADSREnvelope | `ADSREnvelope.java` | Phase transitions (attack→decay→sustain→release) depend on previous sample's phase. Pure Java implementation (comment: "envelope computation done in Java rather than GPU because branching logic is complex"). |

### Effects That Could Be Batch-Computed (GPU-Parallelizable)

| Effect | Class | Why Parallelizable |
|--------|-------|--------------------|
| MultiOrderFilter (FIR) | `MultiOrderFilter.java` | Each output sample computed independently. Already structured for GPU. Documented: "Parallelism: Each output sample computed independently." |
| EnvelopeSection (position-based) | `EnvelopeSection.java` | Pure function of time — no frame-to-frame state. Already used in batch mode by `MultiOrderFilterEnvelopeProcessor`. |
| Automation (pow, sin) | `AutomationManager.java` | All expressions are functions of time + genome parameters. `getMainValueAt()` uses `time.pow(c(3.0))` and `sin()` for LFO — no feedback. |
| oneToInfinity (genome mapping) | `HeredityFeatures.java` | Maps genome [0,1) → [0,∞) via `1/(1-x^exp)-1`. Loop-invariant, already hoisted by LICM. |
| Sample playback (WaveCell) | `CollectionTemporalCellAdapter` | Reading from buffer at known positions is embarrassingly parallel (gather). |
| Volume scaling | `ScaleFactor` | Pointwise multiply — trivially parallel. |
| VolumeEnvelopeExtraction | `VolumeEnvelopeExtraction.java` | Moving average of absolute values. Implements `StatelessFilter`. |

### Key Observation

The `oneToInfinity` expression (`pow((- pow(genome[...], 3.0)) + 1.0, -1.0) + -1.0`)
originates from `HeredityFeatures.oneToInfinity()` (line 213). It is used by:

- `MixdownManager` (line 188): delay time parameters — `oneToInfinity(p, 3.0).multiply(c(60.0))`
- `OptimizeFactorFeatures` (lines 45-63): adjustment durations and exponents
- `DefaultChannelSectionFactory` (lines 101, 116, 130): section durations

These are all genome-parameter-derived and loop-invariant. LICM already
hoists them. The remaining 76 variable-exponent pow() calls have
exponents that are themselves genome-derived (`f_licm_*` values), so
they're computed once per kernel invocation, not per sample.

---

## Cell Graph Topology (Effects Enabled)

For each of 6 channels (stereo = 12 paths):

```
Source WaveCell (sample playback)
  |
  +--- Volume Adjustment
  |     |
  |     +---> Main Filter Up (AudioPassFilter hp) [IIR, sequential]
  |     |
  |     +---> SummationCell (accumulate)
  |     |
  |     +---> Wet Filter (FixedFilterChromosome hp/lp) [IIR, sequential]
  |     |
  |     +---> EfxManager.applyFilter (MultiOrderFilter FIR) [DOMINANT COST]
  |     |
  |     +---> Delay level scaling
  |     |
  |     +---> AdjustableDelayCell (delay line) [sequential]
  |     |
  |     +---> AutomationManager modulation [parallelizable]
  |     |
  |     +---> Self-feedback (mself with transmission grid)
  |     |
  |     +---> SummationCell (accumulate)
  |
  +--- Reverb routing
        |
        +---> SummationCell (sum across channels)
        |
        +---> DelayNetwork (FDN reverb) [sequential cross-frame]

Main + Efx ---> Master Filter Down (AudioPassFilter lp) [IIR, sequential]
           |
           +---> Master Output
```

---

## GPU/Kernel Architecture: Feasibility of Split Execution

The framework already has all the building blocks for splitting
computation between GPU pre-computation and CPU sequential loops:

### Existing Mechanisms

| Mechanism | What It Does |
|-----------|-------------|
| `OperationList` | Multi-step pipeline with per-step `ComputeRequirement` |
| `ComputeRequirement.GPU / .CPU` | Per-operation backend selection |
| `PackedCollection` | Inter-kernel data buffer (CPU or GPU resident) |
| `DefaultComputer.getContext()` | Automatic CPU/GPU selection based on parallelism (count > 128 → GPU) |
| `Process.optimize()` / `IsolatedProcess` | Creates kernel boundaries by isolating sub-processes |
| Unified memory (Apple Silicon) | Metal + `MetalJNIMemoryAccessor` = zero-copy between CPU and GPU |

### Split Execution Pattern

```java
OperationList pipeline = new OperationList("Audio Pipeline");

// Pass 1: GPU pre-computation (4096 work items in parallel)
OperationList precompute = new OperationList("Envelope Precompute");
precompute.setComputeRequirements(List.of(ComputeRequirement.GPU));
precompute.add(envelopeComputation);  // Writes to PackedCollection[4096]
pipeline.add(precompute);

// Pass 2: CPU sequential loop (frame-dependent state only)
OperationList sequential = new OperationList("Sequential Accumulate");
sequential.setComputeRequirements(List.of(ComputeRequirement.CPU));
sequential.add(loop(minimalAccumulate, 4096));
pipeline.add(sequential);
```

### Constraints

- **Kernel compilation overhead:** Each additional kernel requires
  separate compilation. `InstructionSetManager` cache mitigates this
  for repeated executions.
- **Memory transfer:** On Apple Silicon with unified memory, CPU↔GPU
  is zero-copy. On discrete GPUs, explicit transfers would be needed.
- **Scope restructuring:** The current `Repeated` scope generates
  a single-kernel loop. Splitting requires restructuring the computation
  graph before scope generation.

---

## Options Ranked by Impact

| # | Option | Est. Impact | Effort | Notes |
|---|--------|------------|--------|-------|
| 1 | **Fix MultiOrderFilter coefficient recomputation** | Very High (~80-90% of cost) | Low-Medium | Pre-compute 41 coefficients once per buffer instead of per sample. Eliminates ~336K trig calls. |
| 2 | **Split pipeline: GPU pre-computation + CPU sequential** | High | High | Compute envelopes, automation, filter coefficients in parallel GPU kernel. Run only IIR/delay in CPU loop. |
| 3 | **Replace IIR filters with FIR** | Medium | Medium | AudioPassFilter (biquad IIR) → MultiOrderFilter (FIR). FIR is parallel but needs ~40 taps for equivalent selectivity. |
| 4 | **Reduce JNI call overhead** | Low-Medium (~5-10%) | Medium | Fuse 120+ trivial setup kernels into one batch initialization. |
| 5 | **Algebraic simplification of remaining pow()** | Low (~2-5%) | Done | Strength reduction already implemented. Remaining 76 have variable exponents. |

---

## Why Effects-Disabled Performance Is Fine

Without effects, there is no `MultiOrderFilter`, no `AudioPassFilter`,
no `DelayNetwork`, no `AdjustableDelayCell`. The pipeline is just sample
playback (WaveCell buffer reads) + volume scaling + output accumulation.
This is trivially fast — all operations are simple memory reads and
multiplies with no transcendental function calls.

The entire performance gap is caused by the effects chain, and within
that chain, the FIR filter's per-sample coefficient recomputation is
the single largest cost.

---

## Related Documents

- [AUDIO_SCENE_LOOP.md](AUDIO_SCENE_LOOP.md) — Actionable optimization goals
- [REALTIME_AUDIO_SCENE.md](REALTIME_AUDIO_SCENE.md) — Architecture and
  buffer consolidation
- [Decision Journal](../journals/AUDIO_SCENE_LOOP.md) — Implementation history
