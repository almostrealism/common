# Qwen2.5-0.5B Implementation Plan

**Status**: BLOCKED - Model generates wrong tokens, root cause under investigation

See [ASSESSMENT.md](ASSESSMENT.md) for detailed current state analysis.

---

## Problem

Model generates token 27 instead of expected token 271 for input "Hello" (token 9707).

---

## Known Facts

✅ **Working**:
- Embeddings match PyTorch perfectly (RMSE: 0.000000)
- Model compiles and runs end-to-end
- Tokenizer correctly encodes/decodes
- All 291 weight tensors load correctly

❌ **Broken**:
- Transformer layers produce incorrect outputs
- Error amplifies through layers (layer 1: tiny error → layer 2: 56x worse)

❓ **Unknown**:
- Exact location of bug in transformer architecture
- Whether GQA `.repeat()` implementation is correct
- Whether RoPE/QK-Norm/residual connections have issues

---

## Immediate Next Actions

### 1. Test GQA Expansion (**PRIORITY 1**)

**Hypothesis**: The `.repeat()` method may not correctly expand KV heads.

**Test**:
```java
// Verify that traverse(2, keys).repeat(7)
// correctly expands (seqLen, 2, headSize) → (seqLen, 14, headSize)
```

**Why**: Recent commit removed `.expand()` and replaced with `.repeat()`. This is the most recent architectural change.

### 2. Component Isolation Tests (**PRIORITY 2**)

Since full layer tests crash, test components separately:

1. **RoPE rotation**: Verify Q/K get correct rotations for position 0
2. **QK-Norm**: Verify normalization produces expected values
3. **Attention scores**: Verify Q·K^T / sqrt(d) produces expected matrix
4. **Softmax**: Verify attention weights sum to 1
5. **FFN (SwiGLU)**: Verify gate/up/down projections

### 3. Reduce Model Size (**PRIORITY 3**)

Modify `LayerOutputComparisonTest` to use smaller config:
```java
Qwen3Config testConfig = new Qwen3Config(
    896,      // dim - keep same
    4864,     // hiddenDim - keep same
    1,        // layerCount - REDUCE from 24
    14,       // headCount
    2,        // kvHeadCount
    151936,   // vocabSize
    10,       // seqLen - REDUCE from 32768
    true,     // sharedWeights
    1000000.0 // ropeTheta
);
```

This may allow layer tests to compile.

---

## Investigation Strategy

### Phase 1: Verify GQA Expansion (Est: 30 min)

Create unit test for `expandKeysForGQA`:
1. Create mock keys tensor: (seqLen=3, kvHeads=2, headSize=4)
2. Call `expandKeysForGQA` with heads=14
3. Verify output shape: (seqLen=3, heads=14, headSize=4)
4. Verify each KV head repeated 7 times
5. Verify values preserved correctly

**If this fails**: GQA expansion is the root cause.
**If this passes**: Bug is elsewhere in attention mechanism.

### Phase 2: Component-Level Debugging (Est: 2 hours)

Test each transformer component in isolation with known inputs/outputs.

### Phase 3: Numerical Precision Analysis (Est: 1 hour)

Check if float precision differences cause divergence.

---

## Long-term Plan (After Bug Fix)

1. **Phase 7**: Full validation with all 24 layers
2. **Phase 8**: Multi-token generation
3. **Phase 9**: Performance optimization
4. **Phase 10**: Larger Qwen models (4B, 8B)

---

## Files

- **ASSESSMENT.md**: Detailed analysis of current state
- **LayerOutputComparisonTest.java**: Layer-by-layer testing (currently crashes)
- **Qwen3LogitsTest.java**: End-to-end test showing the mismatch
- **AttentionFeatures.java**: Core attention implementation with GQA
- **Qwen3.java**: Main model class

---

## Commands

```bash
# Run embeddings test (passes)
mvn test -Dtest=LayerOutputComparisonTest#compareAfterEmbeddings

# Run end-to-end test (fails - wrong token)
mvn test -Dtest=Qwen3LogitsTest

# Run layer tests (crashes)
mvn test -Dtest=LayerOutputComparisonTest
```

**Note**: Always set environment variables:
```bash
export AR_HARDWARE_LIBS=/home/developer/.libs/
export AR_HARDWARE_DRIVER=native
```
