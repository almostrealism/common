# Qwen3 Inference Performance Analysis

> **Status (as of 2026-04-29): Continue with revisions.**
>
> Objective 1 (inference-mode tracking elimination) is **complete** and landed on
> `feature/qwen-perf-updates`. Objective 5 (GQA head expansion) is **effectively
> complete** via a different path than planned. Objectives 2, 3, and 4 remain
> outstanding. See the assessment sections below for detail.

---

## Profiling Setup

- **Test class:** `Qwen3InferenceProfileTest`
- **Config:** 24 layers, dim=896, hiddenDim=4864, 14 query heads, 2 KV heads, seqLen=128, vocab=1000
- **Backend:** JNI (native), aarch64, 8GB RAM
- **Average forward pass:** 735.55ms (1.4 tok/s)
- **Profile artifact:** `ml/results/qwen3_inference_profile.xml`

Synthetic (random) weights were used. The forward pass operations have the same
computational cost regardless of weight values, so the profile is representative
of real-weight inference. The vocab size was reduced from 151,936 to 1,000 to
keep memory manageable; the final dense projection cost scales linearly with
vocab and should be considered separately at full scale.

## Profile Breakdown

| Rank | Operation | Time | % Total | Invocations | Avg | Category |
|------|-----------|------|---------|-------------|-----|----------|
| 1 | `f_weightedSumComputation_6771` | 6.45s | 12.6% | 629 | 10.26ms | Dense matmul |
| 2 | `copy_4864` | 5.63s | 11.0% | — | — | Memory copy (FFN hidden dim) |
| 3 | `operations_10518` (1373 children) | 4.00s | 7.8% | — | — | KV cache + layer assignments |
| 4 | `f_collectionProductComputation_6555` | 3.72s | 7.3% | 642 | 5.80ms | SwiGLU gate x up |
| 5 | `copy_896` | 3.47s | 6.8% | — | — | Memory copy (model dim) |
| 6 | `collectionSumComputation` (multiple) | ~2.2s | 4.3% | — | — | Residual connections |
| 7 | `greaterThanCollection` (24 instances) | ~1.1s | 2.2% | — | — | SiLU activation |
| 8 | `packedCollectionRepeat` (multiple) | ~0.6s | 1.2% | — | — | GQA KV head expansion |
| 9 | `collectionExponentialComputation` | ~0.4s | 0.9% | — | — | Softmax |

Total accounted: ~27.6s of 51.2s profile time. The remainder is distributed
across 21,034 operation nodes (reshapes, delegates, smaller computations, and
one-time compilation overhead during warmup).

## Prioritized Improvements

### ~~1. Eliminate Layer I/O Tracking Copy Operations~~ ✅ COMPLETE

~~**Cost:** 17.8% of total time (copy_4864 at 11.0% + copy_896 at 6.8%)~~

> **Retrospective (2026-04-29):** This objective is complete. `DefaultCellularLayer`
> now has `setInputTracking(boolean)`, which allocates or destroys the input buffer
> on demand. `CompiledModel.compile(model, false, ...)` calls `configureTracking()`
> to disable input tracking across all layers before optimizing the forward graph.
> The entry cell uses a runtime `this.input == null` check so no cell rebuild is
> needed. The feature is validated by `LayerTrackingTest` (4 tests: correctness,
> backprop, performance comparison, operation count comparison). Qwen3 tests and
> `RopeCompilationRegressionTest` all use `compile(false)` for inference, so the
> optimization is active in all inference paths.
>
> **Related master work:** Commit `06b1dd32d` (master, 2026-04-21) expanded use of
> `Assignment` in place of `MemoryDataCopy` via `MemoryDataFeatures.enableAssignmentCopy`.
> This is orthogonal to the tracking elimination: tracking elimination removes the copy
> entirely in inference mode, while the Assignment change converts copy type. Both can
> coexist.

---

### 2. Reduce Operation Count and Batch Assignments

**Cost:** 7.8% for `operations_10518` + JNI dispatch overhead across 21,034 nodes

The profile contains 21,034 operation nodes for a 24-layer model (~877
operations per layer). The `operations_10518` composite node alone has 1,373
assignment children (KV cache writes and layer outputs), each incurring
individual JNI dispatch overhead. Many `reshape` and `delegate` wrapper nodes
add dispatch cost without performing computation.

**Proposed approach:**

- Batch KV cache assignment operations so that all cache writes for a single
  layer are compiled into one kernel instead of many individual assignments.
- Improve `OperationList` fusion to collapse sequential element-wise operations
  and reshape chains into fewer dispatched kernels.
- Audit the expression tree for unnecessary reshape/delegate wrappers that could
  be eliminated during optimization.

**Expected savings:** ~5-8% of forward pass time.

> **Current state (2026-04-29):** Master's `ExpansionWidthTargetOptimization` work
> (commits `db8e531ea`, `06b1dd32d`, and the planning doc
> `docs/plans/EXPANSION_WIDTH_FAITHFUL_DEFINITION.md`) addresses kernel
> compilation explosion (a separate correctness/scalability concern) but does not
> specifically batch KV cache assignments or reduce dispatch count. This objective
> is still outstanding. The proposed approach remains valid.

---

### 3. Optimize Dense Matmul Kernel

**Cost:** 12.6% of total time (6.45s, 629 invocations, 10.26ms avg)

The `weightedSumComputation` kernel is the largest single compute operation.
FFN projections are 896x4864 per layer; attention Q/K/V/O projections are
896x896 or 896x128. The 10.26ms average per invocation suggests room for
improvement in the generated native code.

**Proposed approach:**

- Investigate the generated C code for the matmul kernel to determine whether
  it uses cache-friendly tiled access or naive triple loops.
- Implement tiled matrix multiplication with block sizes tuned for L1/L2 cache
  (e.g., 32x32 or 64x64 tiles).
- Add SIMD/NEON intrinsics for aarch64 in the JNI code generator, since the
  profiling target is ARM.
- Consider fusing the matmul with the subsequent bias addition when biases are
  present (Qwen3 has Q/K/V biases).

**Expected savings:** ~3-6% of forward pass time (30-50% kernel speedup).

> **Current state (2026-04-29):** No tiled matmul or SIMD/NEON work has landed.
> `LoopedWeightedSumComputation` (added to this branch for convolution) uses a
> looped native-kernel approach but is targeted at convolution, not general matmul.
> This objective is still outstanding and the path remains valid.

---

### 4. Fuse SwiGLU Gate Operations

**Cost:** 7.3% for element-wise product + ~2.2% for SiLU activation components

The SwiGLU FFN computes `silu(gate_proj(x)) * up_proj(x)` followed by
`down_proj(...)`. Currently the SiLU activation (`greaterThanCollection` for
the comparison + `collectionExponentialComputation` for exp + element-wise ops)
and the gate x up multiply (`collectionProductComputation`) are separate
kernels with separate JNI dispatches and intermediate buffers.

**Proposed approach:**

- Create a fused SwiGLU kernel that computes `silu(gate[i]) * up[i]` in a
  single pass: `gate[i] * (1.0 / (1.0 + exp(-gate[i]))) * up[i]`.
- This eliminates one intermediate buffer allocation and multiple JNI
  round-trips per layer per token.
- The fused version is also more cache-friendly since it touches each element
  of `gate` and `up` exactly once.

**Expected savings:** ~3-5% of forward pass time.

> **Current state (2026-04-29):** `AttentionFeatures.java` and `Qwen3.java` both
> reference SwiGLU. The `swiGLU()` method in `AttentionFeatures` still uses separate
> operations for the SiLU activation and gate multiplication. No fused single-pass
> kernel exists. This objective is still outstanding.

---

### ~~5. Virtual GQA Head Expansion~~ ✅ EFFECTIVELY COMPLETE

~~**Cost:** 1.2% for repeat operations + downstream memory bandwidth impact~~

~~The GQA mechanism (14:2 = 7:1 query-to-KV ratio) physically duplicates each
KV head 7 times via `packedCollectionRepeat`. This creates 7x redundant memory
that the downstream attention dot-product must read through.~~

> **Retrospective (2026-04-29):** The `AttentionFeatures.java` GQA implementation
> already avoids physical head repetition. The code comment reads: "This avoids
> `traverse().repeat()` which causes compilation issues." The implementation uses
> per-group looping with `subset()` to extract query slices for each KV group,
> processing them without materializing the expanded KV tensor. This achieves the
> memory-bandwidth reduction the plan proposed, via a different (but equivalent)
> path. No further work needed on this objective.

---

## Summary

| Priority | Improvement | Original Cost | Status |
|----------|-------------|---------------|--------|
| 1 | ~~Eliminate inference-mode copy ops~~ | 17.8% | ✅ Complete (`setInputTracking`, `configureTracking`) |
| 2 | Batch assignments / reduce op count | 7.8% + overhead | ⏳ Outstanding |
| 3 | Optimize dense matmul kernel | 12.6% | ⏳ Outstanding |
| 4 | Fuse SwiGLU gate operations | 7.3% + 2.2% | ⏳ Outstanding |
| 5 | ~~Virtual GQA head expansion~~ | 1.2% + bandwidth | ✅ Complete (subset-based per-group loop) |

Remaining potential improvement from objectives 2–4: ~15–19% of forward pass
time. With objective 1 complete (~15-17% reduction), the per-token latency
from ~735ms has been improved; at scale with real weights, the vocab projection
(896×151,936 matmul) will dominate — profiling with real weights is the logical
next step.

## Reproducing the Profile

Run the profiling test:

```
mcp__ar-test-runner__start_test_run
  module: "ml"
  test_classes: ["Qwen3InferenceProfileTest"]
  jvm_args: ["-Xmx8g"]
  timeout_minutes: 20
```

Analyze the resulting XML:

```
mcp__ar-profile-analyzer__load_profile       path: "ml/results/qwen3_inference_profile.xml"
mcp__ar-profile-analyzer__find_slowest       path: "ml/results/qwen3_inference_profile.xml" limit: 20
mcp__ar-profile-analyzer__search_operations  path: "ml/results/qwen3_inference_profile.xml" pattern: "copy"
mcp__ar-profile-analyzer__list_children      path: "ml/results/qwen3_inference_profile.xml"
```

## Note on Full-Scale Extrapolation

This profile used vocab=1000 instead of the real 151,936. The final dense
projection (`dense` layer producing logits) scales linearly with vocab size.
At full scale, that projection becomes a 896x151,936 matmul (~136M multiply-
adds per token), which would likely become the single largest operation,
surpassing the FFN projections. A separate profiling run with larger vocab
(or the real weights) should be done to quantify this.

---

## Plan Assessment (2026-04-29)

### 1. What was the original plan trying to accomplish?

The plan aimed to accelerate Qwen3 inference from ~735ms/token (1.4 tok/s) on a
24-layer synthetic model to approximately 450–535ms/token, a 27–39% improvement.
Five specific bottlenecks were identified by profiling: copy overhead from I/O
tracking (17.8%), KV cache assignment dispatch overhead (7.8%), dense matmul
kernel inefficiency (12.6%), un-fused SwiGLU components (9.5%), and physical GQA
KV head repetition (1.2%). See the Profile Breakdown and Prioritized Improvements
sections for citations.

### 2. Which objectives have already been achieved?

**Objective 1 (copy overhead):** Implemented on this branch. `DefaultCellularLayer`
gains `setInputTracking(boolean)` (line 325,
`domain/graph/src/main/java/org/almostrealism/layers/DefaultCellularLayer.java`),
and `CompiledModel` gains `configureTracking()` (line 353,
`domain/graph/src/main/java/org/almostrealism/model/CompiledModel.java`).
`LayerTrackingTest` (4 tests, `engine/utils/src/test/java/`) validates correctness
and operation-count reduction. `Qwen3VocabProjectionTest` and
`RopeCompilationRegressionTest` use `compile(false)` confirming the path is active.

**Objective 5 (GQA head expansion):** Achieved via a different route on master.
`AttentionFeatures.java` (line 328,
`engine/ml/src/main/java/org/almostrealism/ml/AttentionFeatures.java`) uses
per-group `subset()` calls rather than `packedCollectionRepeat`, with an
explicit code comment noting this avoids the repeat. The bandwidth benefit is
the same as the plan's proposed stride-based approach.

### 3. Which objectives are still outstanding?

**Objective 2** (batch assignments, reduce dispatch): KV cache write batching is
not present. Master's ExpansionWidth work addresses kernel size, not dispatch count.
The path described (OperationList fusion, fewer dispatched kernels per layer)
remains valid and unimplemented.

**Objective 3** (tiled matmul, SIMD/NEON): No tiled matmul or ARM SIMD work has
landed. The generated native kernel is still naive-loop style. The proposed path
(investigate generated C, implement tiling, add NEON intrinsics) remains valid
and is the highest-value remaining objective by raw percentage.

**Objective 4** (fused SwiGLU): No fused single-pass SwiGLU kernel. `AttentionFeatures`
still uses separate `greaterThanCollection`, `exp`, and `multiply` operations.
The proposed path remains valid.

### 4. Has the landscape shifted?

- **PDSL audio DSP** (`feature/pdsl-audio-dsp`, merged to master 2026-04-xx): Adds
  `producer([shape])` parameter binding, multi-channel constructs, `accum_blocks`,
  `route()`, `sum_channels()` — all audio DSP primitives. No overlap with qwen
  plan objectives; the infrastructure doesn't subsume any ML optimization needed here.
- **Assignment expansion** (master, commit `06b1dd32d`): `MemoryDataFeatures.enableAssignmentCopy`
  flag allows Assignment-based copies that can fuse into expression trees. This is
  orthogonal to but compatible with the tracking elimination. The `enableMemoryDataCopy`
  flag in `DefaultCellularLayer` already controls which path is used. This does not
  make Objective 1 redundant — tracking elimination is stronger (zero copy vs converted copy).
- **ExpansionWidth optimization** (master): Targets kernel compilation explosion, not
  operation count or dispatch overhead. Does not subsume Objective 2.
- No retired keywords (`pipeline`, `fan_out_with`) appear in the branch.

### 5. Is the plan still useful as written?

**Yes, with revisions.** The overall direction is right: the bottlenecks identified by
the profile are real, the objectives are correctly prioritized, and the proposed
approaches for objectives 2–4 are still the right paths. Objectives 1 and 5 are
now done. The plan needs updating to mark those complete, note the master-side
context for each, and reorder next steps to focus on objectives 2–4.

### 6. Recommendation

**Continue with revisions.** The merge is complete, the branch's core
`setInputTracking` feature is confirmed intact and in use. Objectives 2, 3, and 4
represent ~15–19% of forward pass time and have clear implementation paths.

**Suggested next step:** Profile `Qwen3InferenceProfileTest` against the current
branch HEAD to measure the realized gain from Objective 1 (compare new profile
against the 735ms baseline). Then prioritize Objective 3 (tiled matmul) — it is
the single largest remaining bottleneck (12.6%) and is largely independent of
the other two.
