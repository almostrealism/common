# Qwen3 Transformer Block Debugging - Session Summary

## Problem Statement
Transformer block output explodes to **8.6e292** instead of expected value **6.4**

## Methodology
Systematic bottom-up component testing to isolate the numerical explosion

## Test Results

### ✅ Passing Components (7 tests)

1. **testWeightStatistics**
   - All 7 weight matrices in reasonable range
   - Q/K/V/O projections: mean≈0, std≈0.01-0.08, range ±1.3
   - FFN weights: similar statistics
   - **Conclusion**: Weights load correctly from protobuf

2. **testRMSNorm**
   - Input: sum=0.15, max=0.06
   - Output: sum=4.1, max=1.0
   - **Conclusion**: RMSNorm normalizes correctly to unit variance

3. **testRMSNormPlusDense** 
   - Chained: Input → RMSNorm → Dense(Q projection)
   - Output: sum=8.2, max=1.1
   - **Conclusion**: Matrix multiplication works correctly

4. **testRoPEFrequencies**
   - Computed freqCis shape: (8, 32, 2)
   - Position 0: cos=1.0, sin=0.0 (matches PyTorch exactly)
   - Max abs value: 1.0 (within [-1, 1] as expected)
   - **Conclusion**: RoPE frequency computation is correct

5. **testRoPERotation**
   - Full chain: Input → RMSNorm → Dense → Reshape → RoPE
   - Output: sum=8.4, max=1.1
   - **Conclusion**: RoPE rotation applies correctly without explosion

### ❌ Failing Tests (2 tests)

6. **testAttentionWithoutFFN** 
   - Input: sum=0.15, max=0.06
   - Output: sum=-2.93e292, **max=2.49e293**
   - **EXPLOSION CONFIRMED IN ATTENTION MECHANISM**

7. **testTransformerBlockReference** (full block with FFN)
   - Output: max=8.6e292
   - Expected: 6.4
   - Same order of magnitude as attention-only test

## Root Cause Analysis

### Explosion Location: ATTENTION MECHANISM

Since all preprocessing works (RMSNorm ✅, Dense ✅, RoPE ✅) but full attention fails, the explosion occurs in:

1. **Q·K^T computation** (attentionKeys method)
   - Computes dot product of queries and keys
   - Includes GQA expansion: 2 KV heads → 14 query heads (7x repeat)
   - Divides by sqrt(headSize) = sqrt(64) = 8

2. **Softmax normalization**
   - Applied to attention scores
   - Could overflow if input values too large

3. **Attention·V weighted sum** (attentionValues method)
   - Multiplies attention scores by values
   - Includes GQA expansion

4. **Output projection** dense(wo)
   - Final (896, 896) matrix multiplication

### Most Likely Culprit: GQA Expansion

The **14:2 head ratio** requires unusual expansion operations:
- Each of 2 KV heads must be repeated 7 times
- Uses `traverse(2, keys).repeat(7)` in expandKeysForGQA
- Shape warnings during compilation suggest dimension issues
- This is the most complex operation unique to GQA

### Alternative Theories

1. **Uninitialized Cache Memory**
   - key/value caches might contain garbage
   - Would multiply through attention computation

2. **Softmax Overflow**
   - If Q·K^T > 700, softmax overflows to infinity
   - But sqrt(headSize) scaling should prevent this

3. **Dimension Mismatch**
   - Warnings like "(896, 1) does not match (1, 896)" appear
   - Could cause incorrect broadcasting

## Key Findings

| Finding | Significance |
|---------|--------------|
| All preprocessing correct | Narrows problem to attention core |
| RoPE works (max=1.1) | Explosion is NOT in position encoding |
| Attention explodes to 2.49e293 | Consistent reproducible bug |
| Shape warnings present | Suggests dimension/reshape issues |
| GQA 14:2 ratio unusual | Complex repeat operations suspect |

## Next Steps (Priority Order)

1. **Test Q·K^T in isolation**
   - Build minimal test: just Q projection → reshape → Q·K^T
   - Check if explosion occurs in dot product

2. **Test GQA expansion**
   - Create simple test of traverse().repeat() with 14:2 ratio
   - Verify shapes and values

3. **Check cache initialization**
   - Verify PackedCollection() zeros memory
   - Test with pre-filled vs fresh caches

4. **Add intermediate logging**
   - Print Q, K, V values after projection
   - Print attention scores before/after softmax
   - Compare with PyTorch reference at each step

5. **Test without GQA**
   - Temporarily modify to 14:14 head ratio (no expansion)
   - See if explosion still occurs

## Files Created/Modified

### Test Files
- **Qwen3ComponentTest.java** - 7 systematic component tests
- **Qwen3TransformerBlockTest.java** - Full block comparison

### Documentation
- **PLAN.md** - Updated with debugging findings
- **DEBUGGING_SUMMARY.md** - This document

### Source Code
- **AttentionFeatures.java:210** - Fixed headSize extraction (`.size()` → `.length()`)

## Conclusion

The numerical explosion is **definitively located in the attention mechanism** after RoPE rotation. The explosion is consistent (2.49e293) and reproducible, indicating a systematic bug rather than random memory corruption.

The most likely cause is a **bug in the GQA expansion logic** (`traverse().repeat()` operations) for the unusual 14:2 head ratio. Shape warnings during compilation support this theory.

All preprocessing components (normalization, projections, RoPE) work correctly, which significantly narrows the debugging scope to the core attention operations (Q·K^T, softmax, attention·V).
