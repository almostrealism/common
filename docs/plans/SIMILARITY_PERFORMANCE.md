# Similarity Performance Optimization Plan

## Problem Statement

`PrototypeDiscovery` computes pairwise similarity between all audio samples in a library.
With N samples, this requires N*(N-1)/2 comparisons. At N=1000, that is ~500K comparisons;
at N=5000 (a realistic library), it would be ~12.5M comparisons.

Profiling shows that **31.8% of total comparison time is spent creating Computation objects
in Java** — not executing native kernels. Each comparison via `WaveDetailsFactory.productSimilarity`
creates ~12 fresh Computation objects and calls `alignTraversalAxes` ~9 times, all of which
are immediately discarded after a single evaluation.

## Profiling Evidence

### Test: `SimilarityOverheadTest` (utils module)

| Metric | Value |
|--------|-------|
| Scale test (1000 tensors, 499,500 comparisons) | 0.870 ms/comparison, 434.7s total |
| Computation creation time | 0.264 ms/comparison (31.8%) |
| Native evaluation time | 0.567 ms/comparison (68.2%) |
| `differenceSimilarity` (cached evaluable) | 0.249 ms/comparison |
| `cp()` wrapping overhead | 275 ns/call (negligible) |
| Cached computation evaluation (no creation) | 0.530 ms/eval |

### Key Observation

The `differenceSimilarity` code path in `WaveDetails` is **3.5x faster** than `productSimilarity`
because it pre-compiles its computation once using `cv()` placeholders and reuses the resulting
`Evaluable` for every pair:

```java
// WaveDetails.differenceMagnitude — compiled ONCE, reused N times
private static Evaluable<PackedCollection> differenceMagnitude(int bins) {
    return differenceCalc.computeIfAbsent(bins, key -> Ops.op(o ->
        o.cv(new TraversalPolicy(bins, 1), 0)
            .subtract(o.cv(new TraversalPolicy(bins, 1), 1))
            .traverseEach()
            .magnitude()).get());
}
```

The `productSimilarity` path does the opposite — it rebuilds the full expression tree every time:

```java
// WaveDetailsFactory.productSimilarity — rebuilt for EVERY comparison
public double productSimilarity(Producer a, Producer b, int limit) {
    double[] values = multiply(a, b).sum(1)
            .divide(multiply(length(1, a), length(1, b)))
            .evaluate().doubleStream().limit(limit).toArray();
    // ...
}
```

### Per-Comparison Object Creation Breakdown

Each call to `productSimilarity` creates:

| Step | Operation | Classes Created | `alignTraversalAxes` Calls |
|------|-----------|----------------|---------------------------|
| 1 | `multiply(a, b)` | CollectionProductComputation | 1 |
| 2 | `.sum(1)` | ReshapeProducer, CollectionSumComputation | 0 |
| 3 | `length(1, a)` | 2x ReshapeProducer, CollectionProductComputation, CollectionSumComputation, CollectionPowerComputation | 2+ |
| 4 | `length(1, b)` | (same as step 3) | 2+ |
| 5 | `multiply(len_a, len_b)` | CollectionProductComputation | 1 |
| 6 | `.divide(...)` | DefaultTraversableExpressionComputation + wrapper | 1 |
| **Total** | | **~12 objects** | **~9 calls** |

For 500K comparisons: **~6M Computation objects allocated and discarded**, **~4.5M `alignTraversalAxes` calls**.

---

## Optimization Strategies

### Strategy 1: Cached Evaluable for Cosine Similarity (HIGH PRIORITY)

**Approach:** Follow the same pattern as `WaveDetails.differenceMagnitude` — build the
cosine similarity computation once using `cv()` placeholders, compile it to an `Evaluable`,
and reuse it for every comparison.

```java
// Build once
private static Evaluable<PackedCollection> cosineSimilarity(int frames, int bins) {
    return cosineCalc.computeIfAbsent(key, k -> Ops.op(o -> {
        TraversalPolicy shape = new TraversalPolicy(frames, bins, 1);
        CollectionProducer a = o.cv(shape, 0);
        CollectionProducer b = o.cv(shape, 1);
        return o.multiply(a, b).sum(1)
                .divide(o.multiply(o.length(1, a), o.length(1, b)));
    }).get());
}

// Use N times
double[] values = cosineSimilarity(frames, bins)
        .evaluate(tensorA, tensorB)
        .doubleStream().limit(limit).toArray();
```

**Expected improvement:** Eliminates 31.8% creation overhead → ~1.5x speedup on total time.

**Effort:** Low. Only requires changing `WaveDetailsFactory.productSimilarity` to pre-build
and cache the computation. No architectural changes.

**Risk:** Low. The `cv()` pattern is already proven by `differenceMagnitude`.

### Strategy 2: Pre-compute and Cache Norms (MEDIUM PRIORITY)

**Approach:** In `productSimilarity`, `length(1, a)` is recomputed every time tensor `a`
appears in a comparison. With N=1000, tensor `a` participates in 999 comparisons, so its
norm is computed 999 times redundantly.

Pre-compute all norms once:

```java
double[] norms = new double[count];
Evaluable<PackedCollection> normCalc = buildNormEvaluable(frames, bins);
for (int i = 0; i < count; i++) {
    norms[i] = normCalc.evaluate(tensors[i]).toDouble();
}

// Then in comparison loop:
double dotProduct = dotProductCalc.evaluate(a, b).doubleStream().limit(limit)...;
double similarity = dotProduct / (norms[i] * norms[j]);
```

**Expected improvement:** Eliminates redundant norm computation. Each norm is computed once
(N total) instead of N-1 times (N*(N-1) total). This saves ~40% of the evaluation time
because `length()` accounts for roughly half the expression tree.

**Effort:** Low-medium. Requires splitting `productSimilarity` into norm pre-computation
and dot-product-only comparison.

**Risk:** Low. Pure optimization, no behavioral change.

### Strategy 3: Batched Pairwise Comparison (MEDIUM PRIORITY)

**Approach:** Instead of comparing one pair at a time, compute similarity of tensor `a`
against a batch of tensors `[b1, b2, ..., bK]` in a single kernel launch.

This can be expressed as a matrix-vector operation: stack the comparison targets into a
matrix, multiply by the query vector, and get K similarity scores in one kernel call.

```java
// Compare tensor i against tensors [i+1, i+2, ..., i+batchSize]
PackedCollection batch = stackTensors(tensors, i+1, batchSize);
PackedCollection similarities = batchCosineSimilarity.evaluate(tensors[i], batch);
```

**Expected improvement:** Reduces kernel launch overhead by factor of `batchSize`. With
batch size 100, reduces 500K kernel launches to 5K. Also enables GPU parallelism within
each batch.

**Effort:** Medium. Requires designing a batched computation graph and handling the
stacking/unstacking of tensors.

**Risk:** Medium. Requires careful shape management for variable-length batches at row
boundaries.

### Strategy 4: Matrix Cosine Similarity (HIGH IMPACT, HIGHER EFFORT)

**Approach:** Compute ALL pairwise similarities as a single matrix operation:

```
S = normalize(X) @ normalize(X)^T
```

Where `X` is the (N × D) matrix of all feature vectors (flattened from frames×bins).

This replaces N*(N-1)/2 individual comparisons with:
1. One norm computation: O(N × D)
2. One matrix multiplication: O(N² × D)
3. The result matrix S[i][j] is the cosine similarity between tensors i and j

**Expected improvement:** Potentially 10-100x speedup for large N, as matrix multiplication
is highly optimized on GPU and eliminates all per-comparison Java overhead.

**Effort:** High. Requires:
- Flattening feature data into a single matrix (or handling frame-level similarity differently)
- The existing per-frame similarity with outlier trimming would need adaptation
- Need to decide whether frame-level granularity matters vs a single aggregate embedding

**Risk:** Medium-high. The current `productSimilarity` computes per-frame cosine similarity
and then averages with outlier trimming (skip bottom 10%, top 2). A single matrix multiply
would compute a single similarity per pair, losing frame-level granularity. This may require
a two-phase approach: (1) aggregate features into a fixed-size embedding, (2) matrix similarity
on embeddings.

### Strategy 5: Incremental / Lazy Similarity (LOW PRIORITY)

**Approach:** Not all pairwise similarities are equally important. Many pairs will have
low similarity and won't affect community detection results. Use a multi-resolution approach:

1. Compute fast approximate similarity (e.g., random projection / LSH) for all pairs
2. Only compute exact similarity for pairs above an approximate threshold
3. Feed approximate graph to Louvain; refine communities with exact similarity

**Expected improvement:** Could reduce the number of exact comparisons by 80-90% if most
pairs are dissimilar.

**Effort:** High. Requires implementing approximate nearest neighbor search.

**Risk:** Medium. Community detection results may differ slightly from exact computation.

---

## Recommended Implementation Order

### Phase 1: Quick Wins (Expected: ~2x total speedup)
1. **Strategy 1**: Cached Evaluable — change `productSimilarity` to use `cv()` pattern
2. **Strategy 2**: Pre-compute norms — cache `length(a)` results

### Phase 2: Architectural (Expected: additional 5-10x speedup)
3. **Strategy 3**: Batched comparison — reduce kernel launches
4. **Strategy 4**: Matrix similarity — eliminate per-pair overhead entirely

### Phase 3: Algorithmic (Expected: additional 5-10x for large libraries)
5. **Strategy 5**: Approximate/lazy similarity — reduce comparison count

---

## Validation

All optimizations should be validated against `SimilarityOverheadTest`:
- `pairwiseSimilarityBaseline` — measures creation vs evaluation breakdown
- `pairwiseSimilarityAtScale` — measures end-to-end at 1000 tensors
- `computationCreationOnly` — isolates creation overhead
- `cachedComputationEvaluation` — measures evaluation with reused computation
- `differenceSimilarityBaseline` — reference implementation using cached evaluable
- `cpWrappingOverhead` — confirms cp() wrapping is not a bottleneck

After implementing Strategy 1, the creation overhead percentage should drop to near 0%,
and total time should approach the `cachedComputationEvaluation` benchmark (~0.53 ms/comparison).

After implementing Strategy 2, per-comparison evaluation time should drop significantly
because norms are no longer recomputed.

## Files to Modify

| Strategy | Files |
|----------|-------|
| 1 (Cached Evaluable) | `audio/src/main/java/org/almostrealism/audio/data/WaveDetailsFactory.java` |
| 2 (Cached Norms) | `audio/src/main/java/org/almostrealism/audio/data/WaveDetailsFactory.java`, `audio/src/main/java/org/almostrealism/audio/AudioLibrary.java` |
| 3 (Batched) | `audio/src/main/java/org/almostrealism/audio/data/WaveDetailsFactory.java`, potentially `algebra/` |
| 4 (Matrix) | `audio/src/main/java/org/almostrealism/audio/data/WaveDetailsFactory.java`, `algebra/` |
| 5 (Approximate) | `audio/src/main/java/org/almostrealism/audio/similarity/` (new classes) |
