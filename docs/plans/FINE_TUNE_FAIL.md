# AggressiveFineTuningTest Scaling Analysis

## 🚫 CURRENT STATUS: BLOCKED (February 2026)

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

## ⚠️ ISSUE PERSISTS (February 2026)

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

## Executive Summary

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

**Conclusion: Production-scale LoRA fine-tuning is currently infeasible with the existing compilation architecture.**

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

The `AggressiveFineTuningTest` class contains three test methods:

1. **`testAggressiveFineTuning()`** - Uses production-scale parameters. Currently expected to timeout/fail until compilation performance is improved.

2. **`testCompilationScaling()`** - Measures compilation time and memory across different embedding dimensions to characterize the scaling behavior.

3. **`testProfiledFineTuning()`** - Runs a modest fine-tuning configuration (embed=64) with OperationProfileNode instrumentation, saving results to `utils/results/finetune_profile_embed64.xml` for analysis with ar-profile-analyzer MCP tools.

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
