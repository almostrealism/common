# Similarity Performance Optimization Plan

## Completed Work

The following optimizations have been implemented on `feature/similarity-performance`:

- **Cached Evaluable** (Strategy 1): `WaveDetailsFactory.cosineSimilarityEvaluable()` uses `cv()` placeholders to compile the cosine similarity computation once and reuse it. Achieved 2.65x speedup.
- **Norm Caching** (Strategy 2): Rejected — splitting `length()` from the full expression tree caused numerical discrepancies. Norms are computed as part of the cached evaluable instead.
- **Batched Pairwise** (Strategy 3): `WaveDetailsFactory.batchSimilarity()` compares a query against up to 100 targets in a single kernel call. 1.49x additional speedup.
- **Incremental/Lazy** (Strategy 5): `ApproximateSimilarityIndex` and `IncrementalSimilarityComputation` use mean-pooled embeddings with optional random projection to filter candidate pairs, reducing exact comparisons by ~90%.

## Remaining: Matrix Cosine Similarity (Strategy 4)

Compute ALL pairwise similarities as a single matrix operation:

```
S = normalize(X) @ normalize(X)^T
```

Where `X` is the (N × D) matrix of all feature vectors (flattened from frames×bins).

This replaces N*(N-1)/2 individual comparisons with:
1. One norm computation: O(N × D)
2. One matrix multiplication: O(N² × D)
3. The result matrix S[i][j] is the cosine similarity between tensors i and j

**Expected improvement:** Potentially 10-100x speedup for large N, as matrix multiplication is highly optimized on GPU and eliminates all per-comparison Java overhead.

**Challenge:** The current `productSimilarity` computes per-frame cosine similarity and then averages with outlier trimming (skip bottom 10%, top 2). A single matrix multiply would compute a single similarity per pair, losing frame-level granularity. This likely requires a two-phase approach: (1) aggregate features into a fixed-size embedding, (2) matrix similarity on embeddings.

**Files to modify:** `WaveDetailsFactory.java`, potentially `algebra/`

## Validation

All optimizations should be validated against `SimilarityOverheadTest` in the utils module.
