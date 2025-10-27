# Qwen2.5-0.5B Implementation Plan - UPDATED

**Status**: Critical bugs identified at layers 2→3, 22→23, and 23→24

See [ASSESSMENT.md](ASSESSMENT.md) for initial analysis.

---

## Key Findings from Error Accumulation Analysis

### ✅ **Good News**: Core implementation works correctly
- **Layers 3-22**: Show excellent error growth (0.88x-1.17x per layer)
- This proves the fundamental attention/FFN/normalization logic is correct
- Most of the model maintains numerical stability

### ❌ **Critical Issues**: Specific layer transitions fail catastrophically

| Layer Transition | Error Growth | Status | Notes |
|-----------------|--------------|--------|--------|
| 1→2 | 1.675x | ✅ Normal | Expected numerical precision |
| **2→3** | **15.010x** | ❌ BUG | Massive jump from 0.001 to 0.020 |
| 3→22 | ~1.05x avg | ✅ Excellent | 19 layers with stable growth |
| **22→23** | **3.813x** | ⚠️ Issue | Jump from 0.044 to 0.169 |
| **23→24** | **20.187x** | ❌ CRITICAL | Catastrophic: 0.169 to 3.406 |

### PyTorch Reference Also Shows Anomalies
- Layer 2: Std deviation jumps 100x (0.33 → 26.6)
- Layer 21→22: Std drops 30x (55.7 → 1.9)
- Suggests layers 2, 21-24 may have special operations

---

## Investigation Plan: What's Special About These Layers?

### Phase 1: Layer Configuration Analysis

Create **LayerConfigurationTest.java** to detect differences:

```java
@Test
public void analyzeLayerDifferences() {
    // Check each layer's weights for:
    // 1. Different weight shapes
    // 2. Missing/extra weights
    // 3. Weight magnitude differences
    // 4. Bias presence/absence patterns

    for (int layer = 0; layer < 24; layer++) {
        analyzeLayer(layer);
    }
}

private void analyzeLayer(int layer) {
    String prefix = "model.layers." + layer;

    // Check for special weights
    boolean hasQKNorm = stateDict.has(prefix + ".self_attn.q_norm.weight");
    boolean hasBiasQ = stateDict.has(prefix + ".self_attn.q_proj.bias");

    // Check weight magnitudes
    double qWeightStd = computeStd(stateDict.get(prefix + ".self_attn.q_proj.weight"));

    // Flag anomalies
    if (layer == 2 || layer == 22 || layer == 23) {
        log("Layer " + layer + " analysis:");
        log("  Has QK-Norm: " + hasQKNorm);
        log("  Has Q bias: " + hasBiasQ);
        log("  Q weight std: " + qWeightStd);
    }
}
```

### Phase 2: Operation-Level Debugging

Create **LayerOperationDebugTest.java** to trace operations:

```java
@Test
public void debugProblematicLayers() {
    // Test layers 2, 22, 23 in isolation
    testLayerInIsolation(2);   // Where first jump occurs
    testLayerInIsolation(22);  // Before final jump
    testLayerInIsolation(23);  // Catastrophic layer
}

private void testLayerInIsolation(int layerIdx) {
    // Load input from previous layer
    PackedCollection<?> input = loadReference("after_layer_" + (layerIdx-1) + ".bin");

    // Run ONLY this layer
    Model singleLayer = buildSingleLayer(layerIdx);
    PackedCollection<?> output = singleLayer.forward(input);

    // Compare with reference
    PackedCollection<?> expected = loadReference("after_layer_" + layerIdx + ".bin");

    // Detailed comparison
    compareWithBreakdown(output, expected);
}
```

### Phase 3: Component-Level Testing

Create **ComponentIsolationTest.java** to test each part:

```java
@Test
public void testLayer2Components() {
    // Break down layer 2 into components:
    // 1. Input layernorm
    // 2. Attention (Q/K/V proj, RoPE, softmax, output)
    // 3. Residual connection
    // 4. Post-attention layernorm
    // 5. FFN (gate, up, down)
    // 6. Final residual

    PackedCollection<?> input = loadReference("after_layer_1.bin");

    // Test each component separately
    testInputNorm(2, input);
    testAttention(2, input);
    testFFN(2, input);
}
```

### Phase 4: Differential Testing

Create **DifferentialAnalysisTest.java** to find exact divergence:

```java
@Test
public void findExactDivergencePoint() {
    // For problematic layers, save intermediate outputs:
    // - After input norm
    // - After Q/K/V projection
    // - After RoPE
    // - After attention scores
    // - After softmax
    // - After attention output
    // - After residual
    // - After FFN norm
    // - After FFN
    // - After final residual

    instrumentLayer(2);
    instrumentLayer(22);
    instrumentLayer(23);
}
```

---

## Diagnostic Test Suite

### 1. **WeightAnalysisTest** - Check for weight anomalies
- Compare weight statistics across all layers
- Flag layers with unusual patterns
- Check for missing/extra weights

### 2. **LayerIsolationTest** - Test problematic layers alone
- Test layers 2, 22, 23 in complete isolation
- Compare with PyTorch references
- Identify which layer is actually broken

### 3. **ComponentBreakdownTest** - Test sub-components
- Break each problematic layer into parts
- Test normalization, attention, FFN separately
- Find exact operation that fails

### 4. **InstrumentedModelTest** - Full model with logging
- Add detailed logging at every step
- Save intermediate outputs
- Compare with PyTorch at each checkpoint

### 5. **NumericalStabilityTest** - Check for overflow/underflow
- Monitor value ranges through layers
- Check for NaN/Inf
- Verify numerical bounds

---

## Immediate Actions

1. **Create diagnostic test suite** (30 min)
2. **Run weight analysis** to check for configuration differences (10 min)
3. **Test layer 2 in isolation** since it's the first failure point (20 min)
4. **Instrument the model** to capture intermediate outputs (20 min)
5. **Compare with PyTorch** at each step to find divergence (30 min)

---

## Success Criteria

- [ ] Identify what makes layers 2, 22, 23 special
- [ ] Fix the specific bugs causing 15x, 3.8x, and 20x error jumps
- [ ] Achieve < 1.2x error growth per layer across all 24 layers
- [ ] Generate correct token (271 instead of 27)

---

## Commands

```bash
export AR_HARDWARE_LIBS=/home/developer/.libs/
export AR_HARDWARE_DRIVER=native

# Run diagnostic tests
mvn test -pl ml -Dtest=LayerConfigurationTest
mvn test -pl ml -Dtest=LayerIsolationTest
mvn test -pl ml -Dtest=ComponentBreakdownTest

# Run full error accumulation analysis
mvn test -pl ml -Dtest=ErrorAccumulationAnalysisTest
```