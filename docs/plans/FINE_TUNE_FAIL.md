# AggressiveFineTuningTest Scaling Analysis

## Current status (September 2026 re-baseline)

**The February 2026 verdict — "production-scale LoRA fine-tuning is currently infeasible with the
existing compilation architecture" — is withdrawn as unsupported by current measurements.** No
acceptance threshold (target configuration plus maximum cold first-step latency or compile budget)
was fixed before these measurements were taken, so no new feasibility label is assigned. This
document reports the measured timings and growth rates only.

Headline, on the configurations actually measured (single-block and 2–3-block LoRA
`DiffusionTransformer`s, `latentLen=2`):

- **Cold first training step is 13.6–20.8 s (medians) across embed 8 → 256**, and it does not grow
  with embed: the lockstep series *decreases* slightly (20.8 s at embed=8, 15.1 s at embed=256), and
  the controlled embed-only series decreases too (18.3 s → 13.6 s for embed 16 → 64). The warm
  second step is 0.35–1.3 s.
- **Each additional transformer block adds about 1 s** of cold first-step latency (embed=16:
  18.3 s → 19.1 s → 20.4 s for depth 1 → 2 → 3).
- February 2026 recorded 38–52 s at embed=8, 7.9 min at embed=16, 26 min at embed=64, 31 min at
  embed=128 and a timeout beyond 44 min at embed=256 for the same configurations (see the
  historical tables below). The same configurations now take 14–21 s.
- The `IndexProjectionProducerComputation.delta()` scope error that blocked the profiled run in
  February **does not reproduce**: every configuration below compiled, trained and completed its
  backward pass.

Getting there required three fixes in this re-baseline, the first two of which were outright
failures, not slowness (see "Defects found and fixed"): fine-tuning through `ModelOptimizer` with
`DiffusionTrainingDataset` was failing immediately on a shape check, every embed ≥ 64 configuration
failed in `compileForTraining()` with an instruction cache collision, and a single analysis
(`ExpressionMatrix.uniqueNonZeroOffset`) accounted for about 80% of the cold backward pass.

### Measurement conditions

- **Base `master` commit:** `ac76c0e24` (the merge-base of the branch).
- **Tested worktree:** branch `project/plan-20260929-043714` at `207fa7c8d`, the commit that
  added this document together with the harness change in `AggressiveFineTuningTest` and the
  three fixes below (parent `9b4fc5026`). The "final tree" numbers were produced by the tree of
  that commit; the "before"
  columns note which fixes were absent.
- **Host:** Apple M1 Ultra, 128 GB, macOS 15.7.1, JDK 24 test JVM. **Backend:** automatic selection
  (`AR_HARDWARE_DRIVER` unset), which initialised OpenCL, Metal and the native (JNI) backend;
  kernels ran on Metal and JNI.
- **Harness:** the individually selectable methods of `AggressiveFineTuningTest` (see "Test
  Structure"). Each figure is one test-runner invocation, i.e. a fresh JVM, so no repetition reuses
  kernels compiled by another. Three repetitions per configuration; the tables give the median and
  the min–max range.
- **Columns:** *Cold* is the wall-clock of the first `optimizer.optimize(1)` (lazy backward compile
  plus forward, loss, backward and update); *Warm* is the second `optimize(1)`; *Compile est.* is the
  derived `cold − warm` per run (median shown). *Fwd* is `compileForTraining()`. Cold first-step
  latency is the like-for-like comparison with February's `Backward (ms)` column.

### Lockstep series (the configurations of `testCompilationScaling`, depth=1)

`ioChannels`, `numHeads` and `globalCondDim` grow together with `embedDim`, so this series measures a
combined model-size axis.

| Embed | IO | Heads | GC | Fwd (s) | Cold (s), median [range] | Warm (s) | Compile est. (s) | Feb-2026 table A | Feb-2026 table B | Ratio vs B |
|------:|---:|------:|---:|--------:|-------------------------:|---------:|-----------------:|-----------------:|-----------------:|-----------:|
| 8 | 4 | 1 | 8 | 14.2 | 20.8 [20.2–21.7] | 0.35 | 20.5 | 52.0 s | 38.0 s | 1.8× |
| 16 | 8 | 1 | 16 | 11.8 | 18.3 [18.0–27.8] | 0.40 | 17.9 | > 300 s | 472.6 s | 26× |
| 32 | 16 | 1 | 32 | 10.1 | 17.3 [17.3–17.4] | 0.42 | 16.9 | — | 417.7 s | 24× |
| 64 | 32 | 2 | 64 | 6.9 | 13.7 [13.6–14.1] | 0.49 | 13.2 | — | 1,555.5 s | 114× |
| 128 | 64 | 2 | 128 | 6.3 | 15.6 [14.7–16.8] | 0.62 | 15.0 | — | 1,886.5 s | 121× |
| 256 | 64 | 4 | 256 | 4.7 | 15.1 [14.7–15.1] | 1.29 | 13.8 | — | > 2,640 s (timeout) | > 175× |

Table A is §"Current Scaling Data (February 2026)", table B is §"Scaling Test Results"; they disagree
for the same configurations, and the ratio column uses table B. The February figures were taken in a
single JVM looping over all configurations (a later configuration could reuse kernels compiled for
an earlier one) under a harness whose settings no longer match `master`, and they record no host or
backend. Treat the ratios as indicative of the order of magnitude, not as a precise speed-up.

### Controlled series (final tree)

**Embed only** — `ioChannels=8`, `numHeads=1`, `globalCondDim=16`, depth=1 (embed=16 is the same
configuration as the lockstep embed=16 point):

| Embed | Cold (s), median [range] | Warm (s) | Compile est. (s) |
|------:|-------------------------:|---------:|-----------------:|
| 16 | 18.3 [18.0–27.8] | 0.40 | 17.9 |
| 32 | 16.9 [16.9–18.8] | 0.40 | 16.5 |
| 64 | 13.6 [12.6–15.2] | 0.47 | 13.2 |

**Depth only** — the embed=16 lockstep configuration:

| Depth | Cold (s), median [range] | Warm (s) | Compile est. (s) |
|------:|-------------------------:|---------:|-----------------:|
| 1 | 18.3 [18.0–27.8] | 0.40 | 17.9 |
| 2 | 19.1 [18.7–27.2] | 0.48 | 18.6 |
| 3 | 20.4 [20.1–22.7] | 0.55 | 19.9 |

**Growth rates.** Over the measured range, cold first-step latency has no positive dependence on
embed: the log-log slope is about −0.1 for the lockstep series (embed 8 → 256) and about −0.2 for
the embed-only series (embed 16 → 64). The decrease is consistent with more intermediates exceeding
the argument-aggregation size limit at larger sizes, but that attribution has not been measured.
Depth adds about 1.0 s per block (least-squares slope over the three depth medians). The first run
of a configuration in a batch was occasionally slower (the 27–28 s outliers); the medians are not
affected.

**What is not measured.** Nothing here was run at embed=1024 or depth=16, cross-attention
conditioning (`condTokenDim=0` throughout) was not exercised, and all runs use `latentLen=2`. Run
time, not compile time, may dominate at production sizes. The warm step already grows from 0.35 s
to 1.3 s between embed 8 and 256. Any statement about the production configuration is an
extrapolation beyond these data and is left as an open question.

### Backward-pass profile (embed=64, depth=1)

`testProfiledBackwardEmbed64` builds and compiles the model and runs a forward pass *outside* the
profile, then profiles only the first `CompiledModel.backward(...)` call, so the saved profile
(`studio/compose/results/finetune_backward_profile_embed64.xml`) is backward-scoped.

| Tree | Cold first backward (s) | Warm backward (s) |
|------|------------------------:|------------------:|
| Before the `ExpressionMatrix` fix | 25.4, 24.1 | 0.18, 0.18 |
| Final tree | 7.9 [7.7–8.7] | 0.15 [0.13–0.18] |

The two timing kinds, from the final-tree profile:

- **Backend compile** (`" compile"` metric entries; whole-profile total **3.18 s** over 144 compiled
  kernels). Top three, from `ar-profile-analyzer find_slowest_by_category(category="compile")`,
  with shares of the 3.18 s total: `f_collectionSumComputation_3948` `sum (64, 1)` 269.4 ms (8.5%),
  `f_collectionSumComputation_4445` `sum (32, 1)` 262.1 ms (8.2%), `f_collectionSumComputation_4305`
  `sum (18, 1)` 258.6 ms (8.1%). **Thirteen kernels — twelve small `sum` reductions and one
  `indexOfMax` — account for 3.11 s (97.8%) of the backend compile total**, at 215–269 ms each;
  the other 131 kernels compile in about 0.07 s together. `get_source` shows the thirteen are native
  (JNI) C kernels, while, for example, the `multiply (576, 1536)` kernel is a Metal kernel that
  compiles in milliseconds.
- **Stage-detail timings** (non-exclusive, accumulated; not comparable with the shares above):
  `kernelSeries` 0.94 s over 786 entries, `expressionCacheMatch` **0.03 s** over 2,779 entries
  (February: ~1,375.6 s). A keyed point lookup (`get_timing_breakdown` on node `5693`) shows the
  stage details attached to an individual operation are negligible (`kernelSeries [1/3, false]`
  ≈ 1 µs); the totals above were summed over every node of the profile XML, which the analyzer
  cannot do.

The profile's nodes account for about 5.1 s of the 7.9 s cold backward pass; the remaining JVM-side
preparation (expression construction and scope preparation that is not recorded against a node) is
not attributed by the profile.

**February hot spots, re-checked.** `collectionProductComputation` and `collectionAddComputation`
kernels are no longer compile hot spots (each compiles in milliseconds on Metal).
`expressionCacheMatch` has fallen from the largest accumulated stage-detail entry to 0.03 s. The
72× `projectDelta` intermediate is a run-side concern and out of scope here.

### Defects found and fixed

1. **`DiffusionTrainingDataset` timestep shape.** Since the batched-input validation added to
   `CompiledModel.InputManager` (June 2026), `DiffusionTransformer` declares its timestep input as
   `(batchSize, 1)`, but the dataset still produced a bare `(1)` tensor, so every `ModelOptimizer` run
   over a `DiffusionTrainingDataset` failed at once with *"Model input shape mismatch: expected
   (1, 1) but received (1)"*. `createTimestepTensor` now produces `(batchSize, 1)`. Callers must
   likewise supply global conditioning as `(batchSize, globalCondDim)`. Test:
   `DiffusionTrainingDatasetTest` (engine/ml).
2. **Instruction cache collision in `Assignment`.** `Assignment.signature()` omits the destination
   so that one kernel serves every size, but whether the destination is folded into the aggregate
   argument depends on its root size (`MemoryDataArgumentMap.maxAggregateLength`, 1024). The
   `clearBranchGradient` assignments of a `BranchBlock` backward pass clear a 1536-element and a
   576-element gradient; both matched one signature, and every embed ≥ 64 configuration failed in
   `compileForTraining()` with *"Instruction cache collision reusing f_assignment_…"*. The signature
   now includes whether the destination is an aggregation target
   (`MemoryDataArgumentMap.isAggregationTarget(Supplier)`). Test:
   `InstructionCacheCollisionEnforcementTest#assignmentAcrossAggregationLimitDoesNotCollide`
   (engine/utils), which reproduced the exact exception before the fix.
3. **Per-entry constant construction in the loop-replacement analysis (the step-6 lever).** A JFR
   sample of the cold embed=64 backward (242 samples) placed 81% of samples under
   `AggregatedProducerComputation.prepareScope` → `…uniqueNonZeroOffset` →
   `ExpressionMatrix.uniqueMatchingOffset`, and 75% in `MaskMatrix.valueAt` / `SequenceMatrix.valueAt`
   → `Constant.of` / `IntegerConstant.<init>` — a constant expression built for every entry of
   matrices with up to hundreds of thousands of entries, only to test it for zero (the cost the
   September update below had already named as the largest remaining one).
   `ExpressionMatrix.uniqueNonZeroOffset` now tests each entry through `isNonZero`, which a
   `SequenceMatrix` answers from its stored number and a `MaskMatrix` from its mask's stored number,
   with results identical to the expression test. Tests: `ExpressionMatrixNonZeroTests` (engine/utils);
   `SoftmaxTests#logSoftmaxBackwards1`, `ProductDeltaIsolationTest#testSingleAttentionBackward` and
   `ConvolutionModelTests#convBackwardsMediumBatch` pass unchanged.

**Before / after for fix 3** (same harness, three fresh JVMs each side; "before" includes fixes 1 and
2):

| Configuration | Cold before, median [range] | Cold after, median [range] |
|---------------|----------------------------:|---------------------------:|
| Lockstep embed=64 | 31.5 s [30.0–32.7] | 13.7 s [13.6–14.1] |
| Lockstep embed=128 | 22.6 s [22.0–23.7] | 15.6 s [14.7–16.8] |
| Lockstep embed=256 | 34.5 s [33.1–37.1] | 15.1 s [14.7–15.1] |
| Profiled first backward, embed=64 | 25.4 s, 24.1 s (two runs) | 7.9 s [7.7–8.7] |

For the smaller configurations the "before" runs also predate fix 2 (which only changes signatures
for small destinations): embed=8 21.4 s → 20.8 s, embed=16 24.8 s → 18.3 s, embed=32 22.1 s → 17.3 s.
embed=8 is essentially unchanged — its cold step is dominated by fixed costs the fix does not touch.

### Next lever

**Per-kernel native (JNI) compilation of small reductions** (profile-evidenced). In the backward
pass at embed=64, 13 small `sum`/`indexOfMax` kernels are compiled as native C kernels at about
250 ms each — 3.11 s, 97.8% of the backend compile total and about 40% of the 7.9 s cold backward.
Compiling them as one native unit, or letting small reductions run on the GPU backend that already
compiles the rest of the graph in milliseconds, would remove most of that. This is kernel-routing /
native-compiler work and does not touch the sparse-Jacobian projections, the `Sum` reordering budget
or the memoization gating owned by `feature/lora-gradients`. The second target is the roughly 2.8 s of
the cold backward (and the larger fixed cost of the forward compile, 4.7–14.2 s) that the profile does
not attribute to any operation.

### Open questions

- **Feasibility threshold.** A target configuration and a maximum cold first-step latency (or compile
  budget) still need to be fixed before a feasibility label can be assigned.
- **Production configuration.** embed=1024, depth=16 and cross-attention conditioning were not
  measured; neither was a latent length beyond 2.
- **Known-issue markers.** `testCompilationScaling`, `testProfiledFineTuning` and
  `testAggressiveFineTuning` remain `@TestProperties(knownIssue = true)` with five-minute timeouts.
  Run with `AR_LONG_TESTS=enabled` (the flag takes `enabled`/`disabled`, not `true`),
  `testProfiledFineTuning` now passes on the measurement host in about 35 s (8.3 s compile, 23.1 s for
  three epochs), so its February blocker is gone. The markers were left in place: removing one also
  admits the test to the CI pipeline, whose Linux runners were not measured, and
  `testCompilationScaling` runs six configurations in one method. Whether to lift them is a separate
  decision.

---

# Historical record (February 2026, pre-optimization)

Everything below was written before the optimizations described above. Its numbers are kept for
comparison; its verdicts ("BLOCKED", "infeasible") are superseded by the September 2026 re-baseline.

## 🚫 HISTORICAL STATUS: BLOCKED (February 2026, superseded)

**The `testProfiledFineTuning` test cannot run due to a native compiler scope error.**

- **Blocker:** `IndexProjectionProducerComputation.delta()` produces expressions with out-of-scope Index variables
- **Error:** `'_3882_i' undeclared (first use in this function)`
- **Location:** Enumerate operation's backward pass gradient computation
- **Fix Required:** See "BLOCKING Issue: IndexProjectionProducerComputation Scope Error" section below

**Until this is fixed, we cannot:**
1. Generate new profile artifacts (`finetune_profile_embed64.xml`)
2. Measure whether previous optimizations improved performance
3. Identify remaining bottleneck locations

---

## ⚠️ ISSUE PERSISTS (February 2026, superseded)

**The backward pass compilation bottleneck is NOT fully resolved.** While recent commits (gradient support in Cosine/Sine, ProjectionFactory fixes) improved certain configurations, **the underlying exponential scaling persists**.

### Current Scaling Data (February 2026)

Measured with `testCompilationScaling()` - single transformer block (depth=1):

| Embed | IO | Depth | Heads | Forward (ms) | Backward (ms) | Scaling |
|------:|---:|------:|------:|-------------:|--------------:|---------|
| 8 | 4 | 1 | 1 | 3,235 | 51,988 | baseline |
| 16 | 8 | 1 | 1 | ~4,000 | >300,000+ | **>6x** |

**Key observation:** Doubling embed dimension from 8 to 16 causes >6x increase in backward compilation time. The embed=16 backward pass was cancelled after 5+ minutes without completion (while embed=8 took only 52 seconds).

This confirms **super-linear (likely exponential) scaling** that makes production-scale fine-tuning infeasible.

### Critical Finding: Lazy Compilation During Backward Execution

Detailed timing for embed=64 (from `testLoRADiffusionTransformerGradient`):

| Phase | Time | Description |
|-------|------|-------------|
| `compileForTraining()` | 3,193 ms | Explicit model compilation |
| `compiled.forward()` | 7,475 ms | Forward pass execution |
| `compiled.backward()` | >180,000 ms | Backward pass execution (cancelled after 3+ min) |

**The bottleneck is NOT in explicit `compile()` but in lazy compilation during `backward.run()`.**

Something is being lazily compiled during the first backward pass execution. This explains why:
- The explicit compilation appears fast (~3 seconds)
- The first training step (`optimizer.optimize(1)`) takes 50+ seconds for embed=8
- The `expressionCacheMatch` profiling entries occur during backward execution, not during explicit compilation

---

## Executive Summary (February 2026, superseded)

**The fundamental bottleneck for LoRA fine-tuning of diffusion transformers IS backward pass expression-tree compilation time, which scales dramatically worse than O(n^2) with embedding dimension.**

Production-scale fine-tuning (EMBED_DIM=1024, DEPTH=16) remains infeasible. Even a minimal single-layer transformer with EMBED_DIM=256 would require hours of compilation time.

---

## Scaling Test Results

The following measurements were taken using `testCompilationScaling()` with a single transformer block (DEPTH=1) to isolate the relationship between embedding dimension and compilation time.

| Embed | IO | Depth | Heads | Forward (ms) | Backward (ms) | Train (ms) | Heap (MB) |
|------:|---:|------:|------:|-------------:|--------------:|-----------:|---------:|
| 8 | 4 | 1 | 1 | 2,964 | 37,957 | 272 | 23 |
| 16 | 8 | 1 | 1 | 2,108 | 472,645 | 233 | 29 |
| 32 | 16 | 1 | 1 | 1,813 | 417,705 | 291 | 32 |
| 64 | 32 | 1 | 2 | 1,887 | 1,555,530 | 1,165 | 38 |
| 128 | 64 | 1 | 2 | 1,783 | 1,886,469 | 3,075 | 52 |
| 256 | 64 | 1 | 4 | ~800 | >2,640,000 | timeout | - |

**Key observations:**

1. **Forward pass compilation is constant** - approximately 2 seconds regardless of embedding dimension
2. **Backward pass compilation dominates** - from 38 seconds at embed=8 to 31+ minutes at embed=128
3. **Training step is fast** - once compiled, a single training iteration takes only 1-3 seconds
4. **Memory usage is modest** - heap stays under 60MB; this is not a memory problem
5. **Scaling is worse than O(n^2)** - doubling embed from 64 to 128 does NOT double compile time proportionally

---

## Root Cause Analysis

### The Expression Tree Problem

AR's compilation pipeline works as follows:

1. **Model definition** creates a `Model` composed of `Cell` layers
2. **Forward pass compilation** (`model.compile(false)`) builds an `OperationList` representing the forward computation graph
3. **Backward pass compilation** (`model.compile(true)`) derives the gradient computation graph via automatic differentiation
4. **Code generation** converts the expression tree to native C code
5. **Native compilation** compiles the C code to a shared library

The backward pass derivation (step 3) is where the bottleneck occurs. The AR framework implements automatic differentiation by:

1. Walking the forward expression tree
2. For each node, calling its `delta()` method to compute the derivative
3. Composing these derivatives via the chain rule

For a transformer layer with attention and feedforward components, the expression tree is already complex. The backward pass must differentiate every operation, including:

- Matrix multiplications (O(n^3) symbolic derivatives)
- Softmax (complex Jacobian)
- RoPE embeddings (sine/cosine composition)
- LayerNorm/RMSNorm
- Residual connections

### Why It Scales So Poorly

The backward pass expression tree size grows combinatorially because:

1. **Chain rule composition** - each derivative multiplies through all downstream operations
2. **Attention complexity** - attention has O(seq^2) intermediate values, each needing gradients
3. **No expression simplification** - the tree captures all algebraic structure without reduction
4. **Repeated subexpressions** - common subexpressions are not deduplicated during derivation

When embedding dimension doubles, the number of weight parameters roughly quadruples (for Q, K, V, O projections), and the attention computation complexity grows with head_dim. The derivative expressions for each parameter must reference all upstream gradients, causing tree size to explode.

---

## Production Scale Assessment

**Target configuration:**
- EMBED_DIM = 1024
- DEPTH = 16 transformer blocks
- NUM_HEADS = 8
- IO_CHANNELS = 64
- COND_TOKEN_DIM = 768
- GLOBAL_COND_DIM = 768

**Projection from scaling data:**

Given that embed=128 with DEPTH=1 takes 31 minutes for backward compilation:
- A single block at embed=1024 would take many hours (extrapolating the super-quadratic curve)
- 16 blocks would multiply this by approximately 16x (assuming linear scaling with depth)
- Production-scale backward compilation would require days, not minutes

**Conclusion (February 2026, withdrawn in September 2026 — see "Current status"): Production-scale LoRA fine-tuning is currently infeasible with the existing compilation architecture.**

---

## Comparison: Forward vs Backward

The stark contrast between forward and backward compilation times reveals the nature of the problem:

| Phase | embed=8 | embed=128 | Ratio |
|-------|--------:|----------:|------:|
| Forward | 2.9s | 1.8s | 0.6x |
| Backward | 38s | 1886s | 50x |

Forward compilation time is essentially constant (and even decreases slightly with larger dimensions, likely due to amortized overhead). This means the model architecture itself is not the problem - the derivative computation is.

---

## Potential Solutions

### 1. Gradient Checkpointing / Selective Differentiation

Instead of computing the full backward graph, only differentiate with respect to LoRA adapter weights. The base model weights are frozen, so their gradients are not needed.

**Implementation approach:**
- Mark non-trainable weights as constants in the expression tree
- Skip derivative computation for constant branches
- Only compute gradients flowing to LoRA adapters

**Expected benefit:** Dramatic reduction in backward tree size, proportional to the ratio of LoRA parameters to total parameters.

### 2. Expression Tree Simplification

Add algebraic simplification during derivative computation:
- Common subexpression elimination
- Constant folding for frozen weights
- Dead code elimination for unused gradients

**Implementation approach:**
- Modify `Expression.delta()` methods to return simplified forms
- Add a simplification pass after tree construction
- Cache derivative subexpressions

### 3. Lazy/Incremental Compilation

Instead of compiling the entire backward pass upfront, compile gradients on-demand:
- Compile forward pass normally
- During backward pass, compile gradient kernels one layer at a time
- Discard intermediate gradient kernels after use

**Tradeoff:** Slower training iterations, but avoids the O(huge) upfront compilation.

### 4. Alternative Differentiation Approach

Consider implementing reverse-mode AD at the operation level rather than expression level:
- Define gradient operations for each layer type (attention, FFN, etc.)
- Compose gradients at layer granularity, not expression granularity
- Similar to how PyTorch/JAX handle autograd

**Tradeoff:** Significant architectural change, but fundamentally solves the scaling problem.

### 5. External Gradient Computation

For very large models, compute gradients externally:
- Use Python/JAX to compute gradient shapes and symbolic expressions
- Import pre-computed gradient operations as AR Operations
- AR handles compilation of simpler, pre-derived operations

---

## Recommendations for Next Steps

1. **Profile at embed=64** - Use OperationProfileNode to identify which specific operations dominate backward compilation time

2. **Implement solution #1** - Selective differentiation for LoRA weights only is the lowest-effort, highest-impact optimization

3. **Benchmark alternative approaches** - If selective differentiation is insufficient, prototype expression simplification

4. **Consider architectural bounds** - Document the maximum practical model size given current constraints, and design LoRA tests accordingly

---

## Profile Analysis Results (embed=64)

A profiled fine-tuning run was performed using `testProfiledFineTuning()` with embed=64, depth=1, and OperationProfileNode instrumentation. The profile captured 512.8 seconds of computation across 4967 operation nodes.

### Top Time Consumers

| Rank | Operation Type | Duration | % of Total | Invocations | Avg/Call |
|-----:|----------------|----------|------------|-------------|----------|
| 1 | `collectionProductComputation` | 146.3s | 28.5% | 14 | 10.4s |
| 2 | `collectionAddComputation` | 112.7s | 22.0% | 14 | 8.0s |
| 3 | `aggregatedProducerComputation` | 41.4s | 8.1% | 15 | 2.8s |
| 4 | `aggregatedProducerComputation` | 40.8s | 8.0% | 15 | 2.7s |
| 5 | `collectionProductComputation` | 29.3s | 5.7% | 14 | 2.1s |

**Key insight:** The top 2 operations consume **50.5%** of profiled time. These are:
- Matrix multiplication derivatives (`collectionProductComputation`)
- Gradient accumulation (`collectionAddComputation`)

### Reshape Operation Overhead

The profile reveals extensive reshape wrapping around core computations:

```
reshape(reshape(reshape(delegate(f_collectionProductComputation_4654))))
reshape(reshape(delegate(f_collectionAddComputation_4419)))
reshape(delegate(f_collectionSumComputation_4659))
```

This pattern indicates:
1. **Redundant reshape operations** - multiple nested reshapes that could be collapsed
2. **Delegate wrapper overhead** - additional indirection layer around computations
3. **Potential for fusion** - consecutive reshape/delegate chains could be eliminated

### Operation Index Analysis

The high operation indices (3900-4662) in the slowest operations confirm these are **backward pass derivatives**, generated late in the compilation process. The forward pass uses lower indices (< 1000).

---

## Performance Improvement Ideas

Based on the profile analysis, here are concrete performance improvements to pursue:

### Idea 1: Reshape Fusion (Medium effort, High impact)

**Problem:** Multiple nested reshape operations wrap core computations, adding overhead without changing the underlying data.

**Solution:** Implement a reshape fusion pass that:
1. Detects consecutive `reshape(reshape(...))` patterns
2. Computes the composed reshape transformation
3. Replaces the chain with a single reshape (or eliminates it if identity)

**Implementation location:** `io.almostrealism.expression` or `io.almostrealism.collect` package

**Expected benefit:** 10-20% reduction in operation count and tree traversal time

### Idea 2: Product/Sum Derivative Caching (Medium effort, High impact)

**Problem:** `collectionProductComputation` and `collectionAddComputation` dominate at 50%+ of time, with 14 invocations each.

**Solution:**
1. Identify structurally identical derivative subexpressions
2. Cache the first computation and reuse for subsequent invocations
3. Implement at the `Expression.delta()` level

**Implementation approach:**
- Add a `DeltaCache` that keys on expression structure
- Before computing delta, check cache for equivalent structure
- Store computed deltas for reuse

**Expected benefit:** Could reduce product/add computation by 50%+ if redundancy is high

### Idea 3: Lazy Kernel Compilation per Layer (High effort, Fundamental)

**Problem:** The 60-minute compilation time is dominated by generating a single monolithic backward kernel.

**Solution:**
1. Compile backward pass layer-by-layer instead of all-at-once
2. Each layer gets its own native kernel
3. Execute kernels sequentially during training

**Implementation approach:**
- Modify `Model.compile(true)` to return a `LayeredCompiledModel`
- Each layer compiles its forward + backward independently
- Memory for intermediate activations is managed between layer calls

**Tradeoff:** Slightly slower training (kernel launch overhead per layer) but dramatically faster compilation

**Expected benefit:** Compile time scales linearly with depth instead of super-quadratically

### Idea 4: Constant Propagation for Frozen Weights (Low effort, Medium impact)

**Problem:** LoRA fine-tuning freezes most weights, but the backward pass still computes gradients for them.

**Solution:**
1. Mark frozen weights as `ConstantExpression` during model setup
2. When `delta()` is called on a constant, return zero immediately
3. Zero gradients propagate and eliminate dead branches

**Implementation approach:**
- Add `Expression.markConstant()` method
- Modify `Model.compile(true)` to accept a `Set<String>` of trainable parameter names
- Only compute gradients for trainable parameters

**Expected benefit:** For LoRA (1-5% trainable), could eliminate 95%+ of gradient computation

### Idea 5: ExpressionMatrix Optimization (Low effort, Low-Medium impact)

**Problem:** Many `WARN: Unable to create ExpressionMatrix` messages indicate fallback to slower scalar operations.

**Solution:**
1. Profile which expression patterns fail ExpressionMatrix creation
2. Implement specialized handlers for common failing patterns
3. The variable indices 3995-4094 suggest specific backward-pass patterns

**Implementation location:** `io.almostrealism.collect.IdentityCollectionExpression`, `DiagonalCollectionExpression`

**Expected benefit:** Faster code generation for backward pass expressions

---

## Detailed Profile Analysis (February 2026)

Using the enhanced `ar-profile-analyzer` MCP tools with compile/run breakdown, we discovered that the bottleneck is **not** where we expected.

### Timing Category Breakdown

| Category | Top Operation | Time | % of Profile |
|----------|---------------|------|--------------|
| **Compile** | `collectionAddComputation_681` | 739ms | 0.1% |
| **Run** | `aggregatedProducerComputation_4094` | 5.2s | 1.0% |
| **`expressionCacheMatch`** | `_7_27_Sum` | **1375.6s** | 268% (!) |
| **`kernelSeries`** | various | ~150s | 29% |

The compile and run times are negligible. The real time is in `stageDetailTime` entries.

### Stage Detail Analysis

**`expressionCacheMatch_7_27_Sum` = 1375 seconds (23 minutes)**

Format: `expressionCacheMatch_<depth>_<nodes>_<type>`

This is time spent in `ExpressionCache.get()` comparing expressions. For a Sum expression with 27 nodes at depth 7, the cache lookup is taking 23 minutes.

**`kernelSeries [23/2780, true]` = 15.7 seconds**

Format: `kernelSeries [<depth>/<nodes>, <success>]`

This is time in `KernelSeriesProvider.getSeries()` converting expressions to series form. Note: backward pass expressions reach **2780 nodes at depth 23**.

### Working Theory: Cache Lookup Performance

**Initial Hypothesis (DISPROVEN):** The `FrequencyCache` is doing O(n×m) linear equality comparisons.

**Investigation Results:**
1. `FrequencyCache` uses `HashMap` internally (line 68) - lookups ARE hash-based O(1)
2. `Expression.hashCode()` uses cached values (`hash`, `nodeCount`, `depth`) - O(1)
3. `Expression.compare()` has early rejection via cached hash/depth/nodeCount - O(1) for non-matching
4. `SpectrumCaching.isExpressionCacheTarget()` is O(1)

**Revised Understanding:**

The 1375 seconds is **accumulated time** from millions of cache lookups, not slow individual lookups:
- 1375 seconds ÷ 0.1ms per lookup = **13.75 million lookups**
- 1375 seconds ÷ 1ms per lookup = **1.375 million lookups**

The bottleneck is the **sheer volume** of expression creation during backward pass derivation. Each `delta()` call creates new expressions via `Sum.of()`, `Product.of()`, etc., and each creation goes through `Expression.process()` → `ExpressionCache.match()`.

**Key observation:** The timing is specifically for `expressionCacheMatch_7_27_Sum` - Sum expressions with exactly depth 7 and 27 nodes. This suggests a pattern of repeated similar expression creation that could potentially be deduplicated earlier in the derivation process.

**Open Questions:**
1. Why are so many Sum expressions with identical structure (d=7, n=27) being created?
2. Is the cache actually helping (finding duplicates) or just adding overhead?
3. Could derivative expressions be memoized at a higher level?

### Update (September 2026): The Cache Lookups Are Not O(1)

A JFR recording (60 s, 194 execution samples) of `ProductDeltaIsolationTest#testSingleAttentionBackward`,
whose single backward pass took about 184 s on both Metal and the native backend, contradicts the
"DISPROVEN" hypothesis above:

- About 65% of the samples are in `ExpressionCache.get` → `FrequencyCache.get`/`put` →
  `HashMap$TreeNode.find` → `Expression.equals`/`NAryExpression.compare`. `TreeNode.find` recurses only
  when many keys in one bin share the *same* `hashCode()`, so the bins have been treeified and every
  lookup scans them with deep structural comparisons.
- The shared hash codes come from the structural hash computed in `Expression.init()`: for a node with
  children it is the product of the children's hashes (`(a % 2713) * (b % 2713)`), and any leaf that is
  not a constant hashes to 1. The product is commutative, a 1 contributes nothing, and a zero leaf zeroes
  every ancestor, so families of expressions that differ only in their constants collide.
- At least 81% of the samples are inside `ExplicitExpressionMatrix.populate`, the `uniqueNonZeroOffset`
  analysis run by `AggregatedProducerComputation.prepareScope`. It substitutes every (row, column) pair
  into the target index expression, creating exactly such a family, and every node went through the
  compilation's `ExpressionCache`. This answers open question 2 for these expressions: pure overhead.

`ExplicitExpressionMatrix.populate` now substitutes the row index once per row and builds its entries
under `ExpressionCache.bypass(...)`. `testSingleAttentionBackward` went from a mean of 186 s to 25 s on
Metal and from 198 s to 41 s on the native backend; `ConvolutionModelTests#convBackwardsMediumBatch`
went from 12.3 s to 6.0 s on Metal and from 15.4 s to 8.7 s on the native backend.

The weak structural hash still degrades every other `ExpressionCache` and `HashMap<Expression, ...>`
use. Replacing it with a well-mixed, non-annihilating combination is the general fix, but
`Expression.getSimplified()` uses `hashCode()` equality as its fixed-point test and
`Scope.processReplacements` iterates a `HashSet` of common sub-expression targets, so that change
alters simplification termination and sub-expression extraction everywhere and needs its own validation.

### Update (September 2026): The Explicit Matrix Entries Were Never Read

After the cache bypass, `ProductDeltaIsolationTest#testSingleAttentionBackward` still spent about
24 s of its 26 s (Metal, M1 Ultra) in `ExplicitExpressionMatrix` population. Six index matrices were
built for the loop-replacement analysis in `AggregatedProducerComputation.prepareScope`, from
128 x 2048 to 131072 x 16 entries, each entry a symbolic substitution. Every one of those analyses
returned no offset.

For an explicit input, the analysis that follows never reads the entries.
`MatrixFunctionEvaluator` evaluates the function at `Index.child(row, col)` itself rather than at
the matrix entries, and its full-expansion path (`ExpressionMatrix.enableUnsequencedMatrices`) is off,
so only `allColumnsMatch()` looks at entries, and it stops at the first column that differs.
`ExplicitExpressionMatrix` now substitutes entries on demand and populates in full only when
`getRowDuplicates()` is requested. Analysis results are unchanged: an in-run comparison of every
loop-replacement decision, eager against on-demand, matched across the softmax, log-softmax, norm,
convolution and attention-style backward tests checked. Measured over five runs each:

| Test | Metal before | Metal after | Native before | Native after |
|------|--------------|-------------|---------------|--------------|
| `ProductDeltaIsolationTest#testSingleAttentionBackward` | 26.0 s | 3.6 s | 40.4 s | 17.8 s |
| `ConvolutionModelTests#convBackwardsMediumBatch` | 5.9 s | 1.4 s | 8.7 s | 4.4 s |

Letting the analysis read the real entry values is not a safe shortcut. Evaluating the same targets
into a `SequenceMatrix` instead, which routes them through `SequenceFunctionEvaluator` and the
column-sequence branch of `TraversableExpression.uniqueNonZeroOffset`, changed one loop-replacement
decision in `SoftmaxTests#logSoftmaxBackwards1`, and that gradient came out wrong. The leaf results
became more accurate, and an operand's unique offset was then adopted for a sum whose other operand
is dense. The evaluation at child positions and the way operand offsets are combined need their own
investigation before the analysis consumes real entry values.

The largest remaining cost of the analysis is `ExpressionMatrix.uniqueMatchingOffset`, which creates
a constant expression for every entry of the value matrix (`MaskMatrix.valueAt`,
`SequenceMatrix.valueAt`) only to test it for zero.

---

## Isolated Component Testing (February 2026)

To identify which specific component triggers the problematic `expressionCacheMatch_7_27_Sum` pattern, we created isolated tests in `AttentionGradientScalingTest`.

### Test Results

| Test | Nodes | Total Time | Backward Time | expressionCacheMatch? |
|------|-------|------------|---------------|----------------------|
| Isolated Attention (seqLen=4, heads=2, dim=32) | 799 | 1.1s | 350ms | No |
| Attention + embed=64 equiv (seqLen=2, heads=2, dim=64) | ~800 | 1.2s | 369ms | No |
| Transformer Block (attention + FFN + residuals) | 1596 | 1.7s | 626ms | No |
| LoRA Transformer Block (attention w/ LoRA + FFN) | ~2000 | ~1.4s | 690ms | No |
| **Full DiffusionTransformer with LoRA (BROKEN)** | **4967** | **512.8s** | **~500s** | **Yes (1375s)** |

### Key Observations

The isolated tests revealed that:
1. Individual components (attention, FFN, normalization, LoRA) all compile quickly in isolation
2. The problematic pattern emerges when components are combined in the full DiffusionTransformer architecture
3. The `expressionCacheMatch_7_27_Sum` pattern (1375+ seconds) appears specific to the full model backward pass
4. **The scaling issue persists** - doubling embedding dimension causes >6x increase in backward compilation time

While commits `42f3a03d2` and `be19dac16` fixed the `ProjectionFactory` handling for LoRA layer registration, the fundamental expression tree scaling problem remains unsolved.

---

## Test Structure

### AggressiveFineTuningTest (compose module)

(Updated September 2026.) The `AggressiveFineTuningTest` class contains:

1. **`testAggressiveFineTuning()`** - Uses production-scale parameters (embed=1024, depth=16). Not re-measured in the September re-baseline.

2. **`testCompilationScaling()`** - Loops over the six lockstep configurations in one JVM and logs a table with `Fwd`, `Cold1`, `Warm2` and the derived `CompileEst` (`cold − warm`) columns.

3. **`testProfiledFineTuning()`** - Profiles model creation, forward execution and three epochs at embed=64, saving `results/finetune_profile_embed64.xml` under the module directory, whether the run succeeds or fails.

4. **Per-configuration measurements** - `testScalingLockstepEmbed{8,16,32,64,128,256}`, `testScalingFixedDimsEmbed{32,64}` and `testScalingDepth{2,3}Embed16`, one configuration each, individually selectable, with a 30-minute JUnit timeout (below the 40-minute test-runner budget) and excluded from the CI pipeline profile (`@TestProperties(excludeProfiles = TestUtils.PIPELINE)`). Each logs a greppable `scalingResult embed=... coldStepMs=... warmStepMs=... compileEstimateMs=...` line.

5. **`testProfiledBackwardEmbed64()`** - Profiles only the first `CompiledModel.backward(...)` call of the embed=64 configuration and saves `results/finetune_backward_profile_embed64.xml` whether the pass succeeds or fails, logging `profiledBackward outcome=... coldBackwardMs=...` and the warm backward latency. Same timeout and CI exclusion as above.

### AttentionGradientScalingTest (ml module)

The `AttentionGradientScalingTest` class isolates attention and transformer block components:

1. **`testMinimalAttention()`** - Smallest attention config (seqLen=4, heads=2, dim=32) to establish baseline.

2. **`testAttentionGradientEmbed64()`** - Matches the embed=64 configuration from the failing case.

3. **`testTransformerBlockGradient()`** - Attention + FFN + residuals to test if the combination triggers the pattern.

4. **`testAttentionGradientScaling()`** - Sweeps across configurations to measure scaling behavior.

---

## SubsetProjectionComputation Fix (February 2026)

### Native Compiler Error Resolved

During investigation of the gradient computation path, a native compiler error was encountered:
```
error: '_3882_i' undeclared (first use in this function)
```

**Root Cause:** The `CollectionSubsetComputation.createProjectionMatrix()` method was passing the original `pos` expression array to `SubsetProjectionComputation`. These expressions contained references to `Index` variables from the parent computation's scope. When the generated C code tried to use these variables, they were not declared in the new scope.

**Fix Applied:**
1. Added `arePositionOffsetsConstant()` guard method - only use the optimized projection matrix path when position offsets are constant expressions
2. Modified `createProjectionMatrix()` to extract the constant values and create fresh `IntegerConstant` expressions without scope references

**Files Changed:**
- `algebra/src/main/java/org/almostrealism/collect/computations/CollectionSubsetComputation.java`

**Verification:** All 14 `PackedCollectionSubsetTests` pass.

**Note:** This fix enables the `SubsetProjectionComputation` optimization to work correctly, but does **not** address the main performance bottleneck (the `expressionCacheMatch_7_27_Sum` taking 1375+ seconds). That remains the primary unsolved issue.

### BLOCKING Issue: IndexProjectionProducerComputation Scope Error

**STATUS: BLOCKS testProfiledFineTuning**

During testing, the same scope error pattern was found in `IndexProjectionProducerComputation.delta()`:
```
error: '_3882_i' undeclared
```

**Root Cause:** The `IndexProjectionProducerComputation.delta()` method (lines 436-442) creates a new projection lambda:
```java
UnaryOperator<Expression<?>> project = idx -> {
    Expression[] pos = overallShape.position(idx);
    return deltaShape.index(projectIndex(pos[0]), pos[1]);
};
```

This lambda calls `projectIndex(pos[0])`, which for `PackedCollectionEnumerate` creates complex expressions with intermediate variables (`block`, `slice`, `offset`) containing Index references from the original computation scope. These references become invalid in the new `IndexProjectionProducerComputation` context.

**Why This Is Harder to Fix Than SubsetProjectionComputation:**
1. `SubsetProjectionComputation` had simple constant position offsets that could be extracted as `IntegerConstant` values
2. `IndexProjectionProducerComputation` uses a `UnaryOperator<Expression<?>>` function that can contain arbitrary logic
3. The `PackedCollectionEnumerate.projectIndex()` creates dynamic expressions based on the index structure, not just constant values

**Potential Fix Approaches:**
1. **Specialized delta for enumerate** - Create `PackedCollectionEnumerate.delta()` that produces a projection matrix directly (like `SubsetProjectionComputation`), avoiding the generic `IndexProjectionProducerComputation` delta path
2. **Expression scope sanitization** - Add a pass that replaces Index variable references with their simplified constant values when possible
3. **Lazy evaluation pattern** - Change `projectIndex` to return a function that defers Index resolution

**Impact:** This blocks the fine-tuning test from running. The test fails during the backward pass when the enumerate operation's gradient is being computed.

**Verification:** This is a **pre-existing issue on the develop branch** - not introduced by the SubsetProjectionComputation fix.

---

## Historical Context

Previous versions of this document focused on OOM issues during audio encoding and model weight loading. Those issues were resolved through:
- Native memory leak fixes in AudioLatentDataset
- Proper model destruction after encoding
- Memory management for compiled models

The current bottleneck is purely computational (compilation time), not memory-related.
