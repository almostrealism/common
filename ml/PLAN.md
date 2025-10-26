# Qwen2.5-0.5B Implementation Plan - REVISED

**Status**: Bug narrowed to final processing or layer stacking

See [ASSESSMENT.md](ASSESSMENT.md) for detailed analysis.

---

## Problem Statement

✅ **What Works**:
- Embeddings: Perfect match with PyTorch
- Single transformer block: Nearly perfect (1e-6 error)
- All individual components (GQA, RoPE, attention, FFN, residuals)

❌ **What Fails**:
- Full 24-layer model generates token 27 instead of expected token 271

❓ **Root Cause**:
- Either final processing (model.norm + lm_head) OR layer stacking

---

## Immediate Next Actions

### 1. Test Final RMSNorm in Isolation (**PRIORITY 1**)

**Test**: Apply model.norm to PyTorch's hidden_states[24] and compare:
1. Load `after_layer_22.bin` (closest we have to final layer)
2. Load model.norm weights
3. Apply RMSNorm in AR
4. Compare with reference

**If this fails**: RMSNorm bug
**If this passes**: Bug is in lm_head or layer stacking

### 2. Test lm_head Projection (**PRIORITY 2**)

**Test**: Apply lm_head to a known vector:
1. Use PyTorch's normalized output
2. Apply AR's dense(lm_head)
3. Compare logits

**If this fails**: lm_head dense layer bug
**If this passes**: Bug must be in layer stacking

### 3. Trace Layer-by-Layer Error Growth (**PRIORITY 3**)

Since we can't compile layer tests, use the actual Qwen3 model with instrumentation:
1. Modify Qwen3.java to save intermediate outputs
2. Run inference
3. Compare with PyTorch references at layers 1, 5, 10, 22

This will show if/where error compounds.

---

## Test Strategy

### Phase 1: Isolate Final Components (Est: 1 hour)

```java
// Test model.norm in isolation
@Test
public void testFinalNorm() {
    // Load after_layer_22.bin as input
    // Load model.norm.weight
    // Apply rmsnorm
    // Compare with after_final_norm.bin (need to generate)
}

// Test lm_head in isolation
@Test
public void testLmHead() {
    // Load normalized output
    // Load lm_head.weight
    // Apply dense projection
    // Compare with final_logits.bin
}
```

### Phase 2: Instrument Full Model (Est: 30 min)

Add hooks to Qwen3.java to capture outputs:
```java
// After transformer stack (before model.norm)
PackedCollection<?> beforeNorm = ...;
saveForInspection("before_norm.bin", beforeNorm);

// After model.norm
PackedCollection<?> afterNorm = ...;
saveForInspection("after_norm.bin", afterNorm);

// Compare with PyTorch at each step
```

### Phase 3: Generate Missing References (Est: 15 min)

Update `generate_layer_outputs.py`:
```python
# Save after last transformer layer
hidden_last = hidden_states[24]  # After layer 23
save_tensor(hidden_last, "after_layer_23.bin")

# Save after model.norm
normalized = model.model.norm(hidden_last)
save_tensor(normalized, "after_final_norm.bin")

# Already have final_logits.bin
```

---

## Success Criteria

**Phase 1 Success**: Identify which component (model.norm or lm_head) has the bug
**Phase 2 Success**: See where error starts/compounds in layer stack
**Phase 3 Success**: Have complete reference data for all pipeline stages

---

## Debugging Checklist

- [ ] Test model.norm with known input/output
- [ ] Test lm_head with known input/output
- [ ] Generate missing PyTorch references
- [ ] Instrument Qwen3.java for intermediate outputs
- [ ] Compare layer-by-layer progression
- [ ] Identify exact location of divergence

---

## Files

- **ASSESSMENT.md**: Detailed analysis with test results
- **Qwen3TransformerBlockTest.java**: Single block test (✅ passes)
- **Qwen3.java**: Full model (needs instrumentation)
- **generate_layer_outputs.py**: Reference data generator (needs updates)

---

## Commands

```bash
export AR_HARDWARE_LIBS=/home/developer/.libs/
export AR_HARDWARE_DRIVER=native

# Verify transformer block still passes
mvn test -Dtest=Qwen3TransformerBlockTest

# Run end-to-end test
mvn test -Dtest=Qwen3LogitsTest

# Generate updated PyTorch references
python generate_layer_outputs.py
```
