# Qwen3 Inference Performance Analysis

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

### 1. Eliminate Layer I/O Tracking Copy Operations

**Cost:** 17.8% of total time (copy_4864 at 11.0% + copy_896 at 6.8%)

`DefaultCellularLayer` generates entry and exit memory copy operations for every
layer to support input/output tracking and backpropagation. During inference,
these copies are pure overhead — no gradient flows backward.

The copies originate in `DefaultCellularLayer.init()`:

```
graph/src/main/java/org/almostrealism/layers/DefaultCellularLayer.java:119-154
```

The entry cell calls `into()` which creates a `MemoryDataCopy` when
`enableMemoryDataCopy == true` and `shape.getCountLong() == 1`:

```
graph/src/main/java/org/almostrealism/layers/LayerFeatures.java:452-504
```

**Proposed approach:**

- Add an inference-only compilation path in `Model.compile()` that sets
  `enableMemoryDataCopy = false` or disables `ioTracking` entirely when
  backpropagation is not needed.
- Alternatively, use assignment-based pass-through instead of memory copies
  for inference mode, since assignment can be fused into the downstream
  operation's expression tree while MemoryDataCopy cannot.

**Expected savings:** ~15-17% of forward pass time.

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

---

### 5. Virtual GQA Head Expansion

**Cost:** 1.2% for repeat operations + downstream memory bandwidth impact

The GQA mechanism (14:2 = 7:1 query-to-KV ratio) physically duplicates each
KV head 7 times via `packedCollectionRepeat`. This creates 7x redundant memory
that the downstream attention dot-product must read through.

**Proposed approach:**

- Replace physical KV head repetition with stride-based indexing in the
  attention kernel, so that multiple query heads read from the same KV head
  memory without duplication.
- Modify the attention computation to accept a `kvHeadRatio` parameter and
  adjust its memory access pattern: `kv_head_index = query_head_index / ratio`.
- This avoids the copy entirely and reduces attention memory bandwidth by a
  factor equal to the GQA ratio (7x for this config).

**Expected savings:** ~1-3% direct + improved cache utilization for attention.

---

## Summary

| Priority | Improvement | Current Cost | Expected Savings |
|----------|-------------|--------------|------------------|
| 1 | Eliminate inference-mode copy ops | 17.8% | ~15-17% |
| 2 | Batch assignments / reduce op count | 7.8% + overhead | ~5-8% |
| 3 | Optimize dense matmul kernel | 12.6% | ~3-6% |
| 4 | Fuse SwiGLU gate operations | 7.3% + 2.2% | ~3-5% |
| 5 | Virtual GQA head expansion | 1.2% + bandwidth | ~1-3% |

Combined potential improvement: ~27-39% of forward pass time, which could bring
the per-token latency from ~735ms down to ~450-535ms on this config.

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
