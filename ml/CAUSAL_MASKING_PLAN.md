# Causal Masking Implementation Plan

**Date**: 2025-10-26
**Goal**: Fix the missing causal masking bug in AttentionFeatures.java

---

## Problem Summary

**Current Bug**: Attention reads full KV cache (32,768 positions) at every position.
- At position 0: Attends to 1 valid entry + 32,767 zeros
- Zero-padding causes attention weights to shrink by **192x**
- Error compounds across layers: 56x from layer 1 to layer 2

**Required Fix**: At position `p`, attention should only see cache positions `0..p`.

---

## Implementation Approach

### Option A: Dynamic Cache Slicing (RECOMMENDED)

**Concept**: Slice the KV cache based on current position before computing attention.

**Pros**:
- Clean separation of concerns
- Easy to test incrementally
- Matches PyTorch/HuggingFace behavior exactly
- No need to modify attention computation itself

**Cons**:
- Requires dynamic slicing operation
- May create new Producer objects each forward pass

### Option B: Causal Mask Addition

**Concept**: Add -inf mask to attention scores before softmax.

**Pros**:
- Standard transformer approach
- No dynamic slicing needed

**Cons**:
- More complex to implement in AR framework
- Harder to test incrementally
- Requires modifying attention computation

**Decision**: Go with **Option A** for simplicity and testability.

---

## Implementation Steps

### Step 1: Add Cache Slicing Helper Method

**File**: `AttentionFeatures.java`

**Add method**:
```java
/**
 * Slice cache to include only positions 0..currentPos (inclusive).
 *
 * @param cache Full cache of shape (seqLen, kvHeads, headSize)
 * @param position Current position producer
 * @param kvHeads Number of KV heads
 * @param headSize Size of each head
 * @return Sliced cache producer of shape (currentPos+1, kvHeads, headSize)
 */
default Producer<PackedCollection<?>> sliceCacheToPosition(
        PackedCollection<?> cache,
        Producer<PackedCollection<?>> position,
        int kvHeads,
        int headSize) {
    // TODO: Implement dynamic slicing based on position value
    // For now, return full cache to maintain existing behavior
    return p(cache);
}
```

**Test**: Create `CacheSlicingTest.java`
```java
@Test
public void testCacheSlicing() {
    // Create mock cache with known values
    PackedCollection<?> cache = new PackedCollection<>(10, 2, 64);
    // ... fill with test data

    // Test slicing at different positions
    for (int pos = 0; pos < 5; pos++) {
        Producer<PackedCollection<?>> sliced = sliceCacheToPosition(cache, p(pos), 2, 64);
        // Verify shape is (pos+1, 2, 64)
        // Verify content matches cache[0:pos+1]
    }
}
```

**Success Criteria**: Test passes, helper method compiles and works correctly.

---

### Step 2: Implement Dynamic Slicing Logic

**Update `sliceCacheToPosition` implementation**:
```java
default Producer<PackedCollection<?>> sliceCacheToPosition(
        PackedCollection<?> cache,
        Producer<PackedCollection<?>> position,
        int kvHeads,
        int headSize) {
    // Extract position value
    // Compute slice end: (position + 1) * kvHeads * headSize
    // Return cache.range(0, sliceEnd) reshaped to (position+1, kvHeads, headSize)

    // NOTE: This requires position to be evaluable at graph construction time
    // OR we need a dynamic slice operation that takes position as input
}
```

**Challenge**: The position is a `Producer`, not a concrete value. We may need:
- A dynamic slice operation in the computation graph
- OR evaluate position eagerly (if possible in AR framework)

**Test**: Same as Step 1, but verify actual slicing behavior.

**Success Criteria**:
- At position 0: returns (1, kvHeads, headSize)
- At position 5: returns (6, kvHeads, headSize)
- Content matches original cache

---

### Step 3: Integrate Slicing into Attention

**File**: `AttentionFeatures.java` lines 271-273

**Current code**:
```java
attention.add(attentionKeys(headShape, p(keyCache)));
attention.add(softmax(attentionShape, true));
attention.add(attentionValues(attentionShape, p(valueCache)));
```

**Updated code**:
```java
// Slice caches to current position
Producer<PackedCollection<?>> slicedKeyCache = sliceCacheToPosition(
    keyCache, position, kvHeads, headSize);
Producer<PackedCollection<?>> slicedValueCache = sliceCacheToPosition(
    valueCache, position, kvHeads, headSize);

// Use sliced caches for attention
attention.add(attentionKeys(headShape, slicedKeyCache));
attention.add(softmax(attentionShape, true));  // May need to adjust shape dynamically
attention.add(attentionValues(attentionShape, slicedValueCache));
```

**Challenge**: `attentionShape = shape(heads, seqLen)` is static, but now seqLen should be `currentPos + 1`.

**Solution**: Either:
1. Make attentionShape dynamic based on sliced cache size
2. Keep full shape but only fill first `currentPos+1` positions

**Test**: Run existing `Qwen3TransformerBlockTest` - should still pass.

**Success Criteria**: Single transformer block test still passes with ~1e-6 error.

---

### Step 4: Validate with Layer Comparison Tests

**Run**: `LayerOutputComparisonTest#compareAfter1Layer`

**Expected Before Fix**:
- RMSE: 0.001091 (currently good)

**Expected After Fix**:
- RMSE: Similar or better (causal masking might slightly improve)

**Run**: `LayerOutputComparisonTest#compareAfter2Layers`

**Expected Before Fix**:
- RMSE: 0.061109 (56x worse than 1 layer)

**Expected After Fix**:
- RMSE: ~0.001-0.002 (should NOT compound 56x!)

**Success Criteria**:
- Error does NOT compound exponentially
- 2-layer RMSE is similar to 1-layer RMSE (within 2-3x)

---

### Step 5: Validate with Full Model

**Run**: `Qwen3LogitsTest`

**Expected Before Fix**:
- Generates token 27 ("<|im_start|>")

**Expected After Fix**:
- Generates token 271 ("\\n\\n")

**Success Criteria**: Model generates correct token!

---

## Testing Strategy

### Phase 1: Unit Tests (Incremental)
1. ✅ Test cache slicing helper in isolation
2. ✅ Test slicing at different positions (0, 1, 5, 100)
3. ✅ Test that sliced cache has correct shape and content

### Phase 2: Integration Tests
1. ✅ Run `Qwen3TransformerBlockTest` - should still pass
2. ✅ Run `LayerOutputComparisonTest#compareAfter1Layer` - should be similar
3. ✅ Run `LayerOutputComparisonTest#compareAfter2Layers` - should NOT compound

### Phase 3: End-to-End Validation
1. ✅ Run `Qwen3LogitsTest` - should generate token 271
2. ✅ Run `CausalMaskingTest#compareZeroPaddingEffect` - effect should be gone
3. ✅ Run full generation - should produce coherent text

---

## Potential Challenges

### Challenge 1: Dynamic Slicing in AR Framework

**Issue**: Position is a `Producer`, not a concrete value.

**Solutions**:
1. **Check if AR has dynamic slice operation**: Search for `slice`, `range`, `subset` operations that take producers as inputs
2. **Use conditional evaluation**: If AR supports conditional operations, use `if (pos == 0) slice(0,1) else if (pos == 1) slice(0,2) ...`
3. **Pre-allocate multiple caches**: Have separate caches for each position (memory intensive)
4. **Eager evaluation**: If position can be evaluated before graph execution, compute slice eagerly

**Next Step**: Search AR codebase for dynamic slicing capabilities.

### Challenge 2: Dynamic Attention Shape

**Issue**: `attentionShape = shape(heads, seqLen)` is static but seqLen should vary.

**Solutions**:
1. **Use maximum shape**: Keep `shape(heads, seqLen)` but only fill first `currentPos+1` positions
2. **Dynamic reshape**: If AR supports it, reshape based on producer value
3. **Mask unused positions**: Instead of slicing, mask attention scores with -inf for positions > currentPos

**Next Step**: Determine if AR framework supports dynamic shapes or if masking is needed.

---

## Rollback Plan

If dynamic slicing proves too difficult:
1. **Fallback to Option B (Causal Mask)**: Add -inf mask before softmax
2. **Simplified slicing**: Only support specific positions (0-10) for initial testing
3. **Static analysis**: Pre-compute all possible slices and select based on position

---

## Next Actions

1. **Search AR codebase** for dynamic slice/range operations
2. **Prototype `sliceCacheToPosition`** with simplest working approach
3. **Run unit tests** to validate slicing logic
4. **Integrate into attention** and run transformer block test
5. **Validate with layer comparison tests**

---

## Success Metrics

- [ ] Single transformer block test still passes (~1e-6 error)
- [ ] 1-layer model matches PyTorch (RMSE ~0.001)
- [ ] 2-layer model does NOT compound error 56x (RMSE ~0.001-0.003)
- [ ] Full model generates correct token (271 instead of 27)
- [ ] Zero-padding effect eliminated (from CausalMaskingTest)
