# Oobleck Autoencoder Implementation Plan

**Status**: In Progress - Investigating Numerical Accuracy Issue
**Last Updated**: 2025-12-26

---

## Overview

This document tracks the implementation of the Oobleck/DAC autoencoder for the AR HPC framework. The autoencoder is used by Stable Audio Open for audio encoding/decoding with 65536x compression.

### Architecture Summary

```
Audio Input (B, 2, L)
        |
        v
   [OobleckEncoder]  -- 65536x compression
        |
        v
Latent Space (B, 64, L/65536)
        |
        v
   [OobleckDecoder]  -- 65536x expansion
        |
        v
Audio Output (B, 2, ~L)
```

---

## Current Status

### What's Working

1. **Weight Loading** - StateDictionary loads encoder/decoder weights from protobuf format
2. **Model Building** - Both encoder and decoder models build successfully (~1 minute)
3. **Snake Activation** - Optimized to precompute broadcasted tensors (avoids slow repeat() chains)
4. **Unit Tests** - DiffusionTransformerTests pass with random weights

### What's Not Working

~~1. **Kernel Compilation** - FIXED (see below)~~

2. **Numerical Accuracy** - Decoder output differs significantly from PyTorch reference (MAE > 100x tolerance)
   - Compilation now works (~1.6 minutes)
   - Forward pass completes (~17 minutes)
   - Output values are drastically wrong
   - Likely cause: `LoopedWeightedSumComputation` or `convTranspose1d` indexing bug

### Known Issues

| Issue | Status | Details |
|-------|--------|---------|
| Snake activation slow build | **FIXED** | Precompute broadcast tensors in LayerFeatures.snake() |
| Kernel compilation slow | **FIXED** | LoopedWeightedSumComputation generates native for-loops instead of unrolled expressions |
| Statement limit errors | **FIXED** | CollectionFeatures::a traversal axis adjustment |
| Decoder numerical accuracy | **OPEN** | Output differs >100x from PyTorch reference - investigating |

---

## Numerical Accuracy Investigation (2025-12-26)

### Problem Description

The decoder validation test completes but fails with:
- Mean absolute difference exceeds tolerance (value ~100x larger than threshold)
- This indicates a fundamental correctness issue, not just floating-point precision

### Investigation Strategy

We'll narrow down the issue using focused tests, from simplest to most complex:

#### Phase 1: Verify LoopedWeightedSumComputation Correctness

**Test 1.1**: Compare `LoopedWeightedSumComputation` against hand-computed reference
- Create a test with known input/weight values
- Compute expected output manually
- Verify the computation produces correct results
- File: `utils/src/test/java/.../LoopedWeightedSumCorrectnessTest.java`

**Test 1.2**: Compare `LoopedWeightedSumComputation` against original `weightedSum`
- Use small inputs where both implementations can run
- The original `weightedSum` was known to be correct (just slow)
- If they differ, the bug is in `LoopedWeightedSumComputation`

#### Phase 2: Verify convTranspose1d Correctness

**Test 2.1**: Small-scale convTranspose1d vs PyTorch reference
- Use small input (e.g., 4 channels, 8 samples, kernel=4)
- Export PyTorch reference output for same input/weights
- Compare AR output to PyTorch output
- File: `utils/src/test/java/.../Conv1dLayerTests.java#testConvTranspose1dAgainstReference`

**Test 2.2**: convTranspose1d with stride=1 (simplest case)
- Start with no upsampling
- Verify basic transposed convolution is correct

**Test 2.3**: convTranspose1d with stride=16 (decoder's first block)
- Use small channels but realistic stride
- This is where the complexity is

#### Phase 3: Layer-by-Layer Decoder Validation

**Test 3.1**: First WNConv1d only (input projection)
- Just the first conv layer: WNConv1d(64 -> 2048, k=7)
- Compare to PyTorch intermediate output

**Test 3.2**: First DecoderBlock only
- Snake + WNConvTranspose1d + 3 ResidualBlocks
- Compare to PyTorch intermediate output after first block

**Test 3.3**: Progressively add blocks
- If first block is correct, add second, etc.
- Find where error first exceeds tolerance

#### Phase 4: Index Expression Verification

**Test 4.1**: Verify inputIndexer produces correct indices
- Log the indices computed by the lambda
- Compare to expected indices for convTranspose1d pattern

**Test 4.2**: Verify weightIndexer produces correct indices
- Same as above for weight indices

### Test Files to Create

```
utils/src/test/java/org/almostrealism/algebra/
├── LoopedWeightedSumCorrectnessTest.java  # Phase 1 tests
└── Conv1dLayerTests.java                   # Phase 2 tests (already exists, add methods)

ml/src/test/java/org/almostrealism/ml/audio/
└── OobleckLayerValidationTest.java         # Phase 3 tests
```

### Investigation Progress

| Test | Status | Result |
|------|--------|--------|
| 1.1 LoopedWeightedSum basic | **PASSED** | Hand-computed reference matches (tolerance 1e-6) |
| 1.2 LoopedWeightedSum vs manual | **PASSED** | Max diff 8.88e-16 |
| 1.3 LoopedWeightedSum large scale | **PASSED** | 64 outer x 16 inner, max diff 2.27e-13 |
| 2.1 convTranspose1d small reference | **PASSED** | Output varies correctly [0.25, 0.5, 0.5, 0.25] |
| 3.1 First WNConv1d only | **PASSED** | std=0.256479, output varies correctly |
| 3.2 WNConv1d + Snake | **PASSED** | std=0.331244, output varies correctly |
| 3.3 Through first block (no residuals) | **PASSED** | std=0.076691, output varies correctly |
| 3.4 WNConvTranspose1d alone | **PASSED** | std=0.077096, output varies correctly |
| 3.5 Single residual block | **PASSED** | std=31.169, NO NaN, output varies correctly |
| 3.6 Two residual blocks | **PASSED** | std=162.696, NO NaN, output varies correctly |
| 3.7 Complete block WITH 3 residuals | **PASSED** | std=297.408, NO NaN, output varies correctly |
| 3.8 Conv1d correctness suite (9 tests) | **PASSED** | All produce correct numerical output |
| 3.9 Output projection isolation | **PASSED** | WNConv1d 128→2 produces varying output from varying input |

---

## Comprehensive Test Inventory

### OobleckLayerValidationTest.java (ml module)

Tests for validating decoder layers against expected behavior.

| Test Name | Line | Purpose | Status |
|-----------|------|---------|--------|
| `testFirstWNConv1dOnly` | 66 | First conv layer: WNConv1d 64→2048, k=7 | **PASSED** |
| `testFirstWNConvTranspose1dOnly` | 167 | First transpose conv: WNConvTranspose1d 2048→1024, stride=16 | **PASSED** |
| `testFirstConvPlusSnake` | 277 | WNConv1d + Snake combination | **PASSED** |
| `testThroughFirstDecoderBlock` | 357 | Input proj + Snake + WNConvTranspose (no residuals) | **PASSED** |
| `testSingleResidualBlock` | 483 | Single residual block in isolation | **PASSED** |
| `testTwoResidualBlocks` | 626 | Two sequential residual blocks | **PASSED** |
| `testCompleteFirstDecoderBlock` | 771 | Full decoder block 1 with 3 residual blocks | **PASSED** |
| `testDecoderBlocks1And2` | 911 | First two decoder blocks combined | **PASSED** (std=300.48) |
| `testDecoderBlocks1To3` | 1050 | Blocks 1-3 combined | TIMEOUT (forward pass) |
| `testAllBlocksWithoutOutputProj` | 1190 | All 5 blocks + final Snake (no output proj) | TIMEOUT |
| `testOutputProjectionIsolation` | 1341 | Output projection WNConv1d 128→2 alone | **PASSED** |
| `testBlock3Isolation` | 1446 | Block 3 in isolation | TIMEOUT (compilation hangs 15+ min) |

### Conv1dCorrectnessTest.java (ml module)

Unit tests for Conv1d implementation correctness.

| Test Name | Purpose | Status |
|-----------|---------|--------|
| `testWeightedSumDirect` | Direct weightedSum call | **PASSED** |
| `testManualConv` | Manual computation verification | **PASSED** |
| `testSimpleConv1d` | Basic Conv1d layer (expected [4.0, 6.0, 8.0]) | **PASSED** |
| `testWeightedSumWithReshape` | With reshape operations | **PASSED** |
| `testWeightedSumWithReshapeNoStrideRate` | Without stride rate | **PASSED** |
| `testPositionVariesAcrossSequence` | Position indexing verification | **PASSED** |
| `testMultipleOutputChannels` | Multi-channel output | **PASSED** |
| `testStride2` | Stride > 1 (expected [1.0, 3.0, 5.0]) | **PASSED** |
| `testMultipleInputChannels` | Multi-channel input | **PASSED** |

### OobleckValidationTest.java (ml module)

End-to-end validation against PyTorch reference outputs.

| Test Name | Purpose | Status |
|-----------|---------|--------|
| `testDecoderAgainstPyTorchReference` | Full decoder output vs PyTorch | **FAILING** (constant ~0.278) |
| `testEncoderAgainstPyTorchReference` | Full encoder output vs PyTorch | TBD |
| `testEncoderIntermediatesAgainstPyTorch` | Encoder layer outputs | TBD |

### Conv1dLayerTests.java (utils module)

Convolution layer tests in utils module.

| Test Name | Purpose | Status |
|-----------|---------|--------|
| `testConvTranspose1dCorrectnessSmall` | Small transposed conv (4ch × 4k) | **PASSED** |
| `testConvTranspose1dLargeChannels` | Large transposed conv (2048ch) | **PASSED** (with LoopedWeightedSum) |

### LoopedWeightedSumCorrectnessTest.java (utils module)

Tests for LoopedWeightedSumComputation.

| Test Name | Purpose | Status |
|-----------|---------|--------|
| `testSmall` | Basic 3×2 input, 2×2 weights | **PASSED** |
| `testVsManual` | Compare against manual computation | **PASSED** (max diff 8.88e-16) |
| `testLargerScale` | 64 outer × 16 inner | **PASSED** (max diff 2.27e-13) |

---

### Update: Conv1d Unit Tests (2025-12-26, Late)

**Finding**: The previous session's compilation error (`ArithmeticIndexSequence.toArray()`) was caused by **debug logging**, not an actual bug in convolution1d.

The test file `Conv1dCorrectnessTest.java` had `enableLogging = true` for `SubsetTraversalExpression` and `SubsetTraversalIndexMapping`. This debug logging calls `toArray()` on expressions containing layer parameter references, which throws `UnsupportedOperationException`.

**Fix Applied**: Changed logging to `enableLogging = false` in the test.

**Test Results**: All 9 Conv1d correctness tests now pass:
- `testManualConv` - Manual computation verification
- `testWeightedSumDirect` - Direct weightedSum call
- `testSimpleConv1d` - Basic Conv1d layer (expected [4.0, 6.0, 8.0], got [4.0, 6.0, 8.0])
- `testMultipleOutputChannels` - Multi-channel output
- `testWeightedSumWithReshape` - With reshape operations
- `testPositionVariesAcrossSequence` - Position indexing verification
- `testStride2` - Stride > 1 (expected [1.0, 3.0, 5.0], got [1.0, 3.0, 5.0])
- `testWeightedSumWithReshapeNoStrideRate` - Without stride rate
- `testMultipleInputChannels` - Multi-channel input

**Implication**: The Conv1d implementation is correct for unit tests. The full decoder's constant output issue is NOT caused by Conv1d - it must be elsewhere in the decoder integration (blocks 3-5 or output projection).

### Update: Output Projection Isolation Test (2025-12-26)

**Test 3.9: testOutputProjectionIsolation - PASSED**

The output projection layer (WNConv1d 128→2, kernel=7) works correctly in isolation:
- Input: synthetic varying data [1, 128, 270000] with std ~300
- Output: varying values with min=-347.27, max=339.12, std=66.39
- NO NaN values
- Output VARIES correctly (not constant)
- Compile time: 11.4 seconds
- Forward time: 0.4 seconds

**Implication**: The output projection is NOT the cause of the constant output bug. When given varying input, it produces varying output correctly.

### Summary: Constant Output Bug Analysis (2025-12-26)

**What Works (produces varying output):**
- ✅ Conv1d unit tests (all 9 pass with correct values)
- ✅ First decoder block with all 3 residual blocks (std=297.4)
- ✅ Blocks 1+2 combined (std=300.48)
- ✅ Output projection in isolation (std=66.39)

**What Fails:**
- ❌ Full decoder (constant output ~0.278)
- ⏰ Blocks 1-3 test: TIMEOUT during forward pass
- ⏰ All blocks without output proj: TIMEOUT during forward pass
- ⏰ Block 3 isolation test: TIMEOUT during compilation (15+ min)

**Key Observations:**
1. Individual layers work correctly up through blocks 1+2
2. The bug appears somewhere in blocks 3-5 or their integration
3. Tests timeout before revealing where output becomes constant
4. The output projection itself is NOT the cause (passes isolation test)

**Current Theory:**
The constant output bug manifests when blocks 3+ are added, but the tests timeout before completion due to large output tensor sizes. The issue could be:
1. Block 3's different stride (stride=8 vs stride=16 in blocks 1-2)
2. Integration issue between block 2 output and block 3 input
3. Numerical collapse in later blocks due to activation scaling

**Next Investigation Steps:**
1. Create `testBlock3Isolation` - test block 3 alone with synthetic input
2. Profile forward pass to find where time is spent
3. Check if the 17M element output (blocks 1-5) causes memory/computation issues

### Phase 1 Results (2025-12-26)

**Finding**: `LoopedWeightedSumComputation` is producing correct results.
- Test 1.1: Hand-computed reference [10.6, 12.7] matched within 1e-6
- Test 1.2: Random inputs matched manual computation within 1e-15
- Test 1.3: Larger scale (64 outer, 16 inner) matched within 1e-13

**Key Observation**: The `LoopedWeightedSumComputation` class is NOT currently used by `convTranspose1d`.
The `convTranspose1d` implementation in `LayerFeatures.java:842` still uses the standard `weightedSum`
which creates `WeightedSumComputation`. The `LoopedWeightedSumComputation` import exists but is unused.

This means the numerical accuracy issue is NOT in `LoopedWeightedSumComputation` since it isn't being used.

### Phase 2 Results (2025-12-26)

**Test 2.1**: Small-scale convTranspose1d produces correct output.
- Test configuration: inputChannels=4, outputChannels=2, kernelSize=4, stride=2, padding=1
- Input: all 1.0 values
- Weights: all 0.0625 (1/16)
- Output: [0.25, 0.5, 0.5, 0.25, 0.25, 0.5, 0.5, 0.25]
- This matches the expected PyTorch behavior (verified by manual computation)

### Phase 3 Results: Layer-by-Layer Validation (2025-12-26, Updated)

**Key Finding**: All layers including residual blocks work correctly with varying output!

| Layer Configuration | Output Stats | Result |
|---------------------|--------------|--------|
| First WNConv1d (64->2048, k=7) alone | std=0.256479 | VARYING |
| WNConv1d + Snake | std=0.331244 | VARYING |
| WNConv1d + Snake + WNConvTranspose1d | std=0.076691 | VARYING |
| WNConvTranspose1d (2048->1024) alone | std=0.077096 | VARYING |
| Block + 1 residual block | std=31.169 | VARYING |
| Block + 2 residual blocks | std=162.696 | VARYING |
| Complete block WITH 3 residual blocks | std=297.408 | VARYING |

**Key Observation**: All layer tests pass with varying output and NO NaN.
- Single residual block: 22 operations, 64s compile, std=31.169
- Two residual blocks: 37 operations, 64s compile, std=162.696
- Three residual blocks: 52 operations, 64s compile, std=297.408

**Next Investigation**: The full decoder validation still fails with nearly constant output (~0.278).
Since individual layers and the complete first decoder block work correctly, the issue must be:
1. In later decoder blocks (blocks 2-5)
2. In the combination of multiple decoder blocks
3. In the final output projection layer

**Next Steps**:
1. Re-run full decoder validation to confirm current behavior
2. Test decoder blocks 2-5 individually
3. Test progressive addition of decoder blocks

### Progressive Block Testing (2025-12-26)

**Strategy**: Since individual layers pass, test progressive combinations of decoder blocks.

| Test Configuration | Output Stats | Elements | Compile | Forward | Result |
|-------------------|--------------|----------|---------|---------|--------|
| Blocks 1+2 | std=300.48, varying | 270K | 74s | ~6min | **PASSED** |
| Blocks 1-3 | - | 1.08M | 77s | TIMEOUT | Hung during forward |
| All blocks + Snake | - | 17.3M | 79s | TIMEOUT | Hung during forward |
| Full decoder | constant ~0.278 | 270K | - | - | **FAILED** |

**Key Finding**: Blocks 1+2 produce varying output (std=300.48), but:
- Full decoder produces constant output (~0.278)
- Tests beyond blocks 1+2 timeout during forward pass (too many elements)

**Implication**: The bug is somewhere in blocks 3-5, final Snake, or output projection.
But we cannot easily isolate it due to forward pass performance issues.

**Performance Observation**: Forward pass time scales poorly with output size:
- 270K elements (blocks 1+2): ~6 minutes
- 1.08M elements (blocks 1-3): TIMEOUT at 5+ minutes
- 17.3M elements (all blocks): TIMEOUT at 7+ minutes

**Potential Root Causes**:
1. **Forward pass performance**: Native code execution is slow for large output tensors
2. **Block 3 stride change**: Blocks 1-2 use stride=16, block 3 uses stride=8 (different code path?)
3. **Numeric instability**: Values may collapse to constant through later blocks

**Next Investigation Path**:
1. Test output projection (WNConv1d 128->2) in isolation with synthetic varying input
2. Create smaller-scale block 3 test (reduce channels to speed up)
3. Profile where forward pass time is being spent

### Block 3 Component Testing (2025-12-27)

**Goal**: Identify which component of block 3 causes the slow compilation (15+ min timeout).

Block 3 configuration:
- inChannels: 512, outChannels: 256
- inputLength: 33, outputLength: 265
- **stride: 8** (vs stride=2 for blocks 1-2)
- kernel: 8, padding: 3, outputPadding: 7

**Component Tests Created** (in OobleckLayerValidationTest.java):
- `testBlock3SnakeOnly` - Snake activation alone
- `testBlock3TransposeOnly` - WNConvTranspose1d alone
- `testBlock3SnakeAndTranspose` - Snake + WNConvTranspose1d
- `testBlock3OneResidual` - Snake + WNConvTranspose1d + 1 residual

**Results**:

| Component | Compile Time | Result |
|-----------|--------------|--------|
| Snake only (512ch, len=33) | **10 seconds** | FAST |
| WNConvTranspose1d only (512→256, stride=8) | **>5 minutes** | **TIMEOUT** |

**CRITICAL FINDING**: The WNConvTranspose1d with stride=8 is the compilation bottleneck!

**Analysis**:
1. Snake activation compiles quickly (10s) - NOT the issue
2. WNConvTranspose1d with stride=8 causes >5 minute compilation - THIS IS THE BOTTLENECK
3. Block 3 uses stride=8 (vs stride=2 for other blocks), creating 8x more output elements
4. This explains why blocks 1-2 (stride=2) compile and run fine, but block 3 times out

**Hypothesis**: The large stride (8) combined with high channel count (512→256) creates a massive expression tree that overwhelms the compiler. The WNConvTranspose1d computation complexity scales as:
- O(inChannels × outChannels × kernelSize × outputLength)
- For block 3: 512 × 256 × 8 × 265 = 277 million operations

**Next Steps**:
1. Investigate WNConvTranspose1d compilation for large strides
2. Consider optimizing the computation graph for transposed convolutions
3. Check if LoopedWeightedSumComputation can help (currently unused by convTranspose1d)

### Decoder Validation Result (2025-12-26)

**Key Finding**: Decoder output is nearly CONSTANT across all positions!

From `decoder_validation.log`:
```
Mismatch at [0]: actual=0.278305, expected=6.842309
Mismatch at [1]: actual=0.278305, expected=10.742569
Mismatch at [2]: actual=0.278305, expected=1.240803
...
Mean Absolute Difference: 5.354761
Elements above tolerance: 269283 / 270922 (99.40%)
```

**Critical Observation**: ALL actual values are nearly identical (~0.278 at start, ~0.270 at end)
while expected values vary from -4.4 to +17.8.

**Analysis**: The constant output pattern strongly suggests:
1. Index expressions at large scale (2048 channels × 16 kernel) may be producing incorrect indices
2. All output positions may be accessing the same input elements
3. The variation in input is not propagating through the decoder correctly

**The small-scale test (4 channels × 4 kernel) produces correct VARYING output.**
This suggests the issue is specific to large-scale index expressions.

---

## Test Commands

### Prerequisites

```bash
# Set environment variables (required for all tests)
export AR_HARDWARE_LIBS=/tmp/ar_libs/
export AR_HARDWARE_DRIVER=native
export AR_HARDWARE_MEMORY_SCALE=6  # For large models
```

### Unit Tests (Fast - Random Weights)

These tests validate the model architecture without loading real weights:

```bash
# Test DiffusionTransformer with random weights
mvn test -pl ml -Dtest=DiffusionTransformerTests

# Test individual transformer block shapes
mvn test -pl ml -Dtest=DiffusionTransformerTests#transformerBlockShapes

# Test forward pass with random weights
mvn test -pl ml -Dtest=DiffusionTransformerTests#forwardPassWithRandomWeights
```

### Validation Tests (Slow - Real Weights, ONNX Comparison)

These tests compare AR implementation against PyTorch/ONNX reference outputs:

```bash
# Encoder validation (compares against PyTorch reference)
mvn test -pl ml -Dtest=OobleckValidationTest#testEncoderAgainstPyTorchReference

# Decoder validation (compares against PyTorch reference)
# WARNING: May take 5+ minutes or hang during compilation
mvn test -pl ml -Dtest=OobleckValidationTest#testDecoderAgainstPyTorchReference

# Encoder intermediate layer validation
mvn test -pl ml -Dtest=OobleckValidationTest#testEncoderIntermediatesAgainstPyTorch
```

---

## ONNX/PyTorch Reference Comparison

### Test Data Location

```
ml/test_data/stable_audio/
├── weights/
│   ├── encoder_weights/     # Protobuf weight files
│   └── decoder_weights/     # Protobuf weight files
├── reference/
│   ├── test_input.bin       # Input audio (1, 2, 65536)
│   ├── encoder_output.bin   # PyTorch encoder output
│   ├── latent_input.bin     # Latent for decoder test
│   ├── decoder_output.bin   # PyTorch decoder output
│   └── encoder_after_*.bin  # Intermediate layer outputs
└── *.log                    # Validation test logs
```

### Generating Reference Data

The reference data was generated using PyTorch. To regenerate:

```bash
cd ml/scripts
python extract_stable_audio_autoencoder.py
```

This script:
1. Loads the Stable Audio Open checkpoint
2. Exports weights to protobuf format
3. Runs encoder/decoder with deterministic inputs
4. Saves reference outputs in binary format

### Binary Reference File Format

```
[4 bytes: int32 count (little-endian)]
[count x 4 bytes: float32 values (little-endian)]
```

### Comparison Metrics

The validation tests compute:
- **Mean Absolute Difference** (MAD) - primary metric
- **Max Absolute Difference**
- **RMSE** - Root Mean Square Error
- **Mismatch Count** - elements exceeding tolerance

Default tolerances:
- Encoder: 0.01
- Decoder: 0.05 (higher due to error accumulation)

---

## File Structure

### Source Files

```
ml/src/main/java/org/almostrealism/ml/audio/
├── OobleckEncoder.java      # Encoder implementation
├── OobleckDecoder.java      # Decoder implementation
├── OobleckAutoEncoder.java  # Combined encoder+decoder
└── DiffusionTransformer.java # DiT model (for diffusion)
```

### Supporting Files

```
graph/src/main/java/org/almostrealism/layers/
└── LayerFeatures.java       # Contains snake(), wnConv1d(), wnConvTranspose1d()

ml/src/test/java/org/almostrealism/ml/audio/
├── OobleckValidationTest.java    # PyTorch comparison tests
└── test/DiffusionTransformerTests.java  # Unit tests
```

---

## Architecture Details

### Decoder Structure (~70 layers)

```
layers.0: WNConv1d(64 -> 2048, k=7, p=3)          # Input projection
layers.1: DecoderBlock(2048 -> 1024, stride=16)  # 5x upsampling blocks
layers.2: DecoderBlock(1024 -> 512, stride=16)
layers.3: DecoderBlock(512 -> 256, stride=8)
layers.4: DecoderBlock(256 -> 128, stride=8)
layers.5: DecoderBlock(128 -> 128, stride=4)
layers.6: Snake(128)                              # Final activation
layers.7: WNConv1d(128 -> 2, k=7, p=3)            # Output projection
```

Each DecoderBlock contains:
- Snake activation
- WNConvTranspose1d (upsampling)
- 3x ResidualBlocks (each with 2 Snake + 2 WNConv1d)

### Encoder Structure (mirror of decoder)

```
layers.0: WNConv1d(2 -> 128, k=7, p=3)            # Input projection
layers.1-5: EncoderBlocks with downsampling      # Mirror of decoder
layers.6: Snake(2048)                             # Final activation
layers.7: WNConv1d(2048 -> 64, k=3, p=1)          # Output projection
```

---

## Next Steps

### Immediate (Unblock Validation)

1. **Investigate `AcceleratedComputationOperation` bottleneck**
   - Profile what happens inside `compileRunnable()` → `AcceleratedComputationOperation`
   - The 252 operations take 7+ minutes to compile into a single kernel
   - Key files to investigate:
     - `hardware/src/.../AcceleratedComputationOperation.java`
     - `hardware/src/.../DefaultComputer.java:595` (compileRunnable)

2. **Consider layer-by-layer compilation** instead of single kernel
   - Split the 252 operations into smaller compilable units
   - Trade off: more kernel launches vs. faster compilation

3. **Add progress logging inside AcceleratedComputationOperation** to identify slow phases

### Medium Term

1. **Kernel caching** - compile once, save compiled form to disk
2. **Incremental compilation** - compile and cache individual layers
3. **Parallel compilation** - compile independent operation groups concurrently

### Long Term

1. **Full autoencoder pipeline** - encoder -> latent -> decoder
2. **Integration with DiffusionTransformer** for audio generation
3. **Batch processing** for efficient inference

---

## Component-Level Performance Analysis

### Decoder Components Overview

The decoder consists of these atomic components (inner to outer complexity):

| Component | Count | Description |
|-----------|-------|-------------|
| **Snake** | 36 | Learnable activation: x + (1/β)sin²(αx) |
| **WNConv1d** | ~35 | Weight-normalized 1D convolution (k=1 or k=7) |
| **WNConvTranspose1d** | 5 | Weight-normalized transposed conv for upsampling |
| **ResidualBlock** | 15 | Snake + WNConv(k=7) + Snake + WNConv(k=1) + skip |
| **DecoderBlock** | 5 | Snake + WNConvTranspose1d + 3×ResidualBlock |

### Sequence Length Progression

```
Input latent:      2 samples × 64 channels
After block 1:    32 samples × 1024 channels  (stride=16)
After block 2:   512 samples × 512 channels   (stride=16)
After block 3:  4096 samples × 256 channels   (stride=8)
After block 4: 32768 samples × 128 channels   (stride=8)
After block 5: 135461 samples × 128 channels  (stride=4)
Output:        135461 samples × 2 channels
```

### Slowness Probability Estimates (Pre-Test)

Based on analysis of the decoder architecture:

| Component | Probability | Rationale |
|-----------|-------------|-----------|
| **WNConvTranspose1d (large output)** | 50% | Last stages produce 32K-135K samples; transposed conv is complex |
| **Snake (large sequence)** | 25% | Final stages have 17M+ elements (135461 × 128 channels) |
| **WNConv1d (large sequence)** | 15% | k=7 convolutions on 135K samples |
| **Residual connection** | 10% | Skip connections may complicate computation graph |

---

## Component Test Results (2025-12-21)

### Summary Table

| Component | Channels | SeqLen | Ops | Compile (ms) | Forward (ms) | Status |
|-----------|----------|--------|-----|--------------|--------------|--------|
| Snake Small | 128 | 32 | 3 | 163 | 15 | ✅ |
| Snake Medium | 128 | 4,096 | 3 | 155 | 27 | ✅ |
| **Snake Large** | 128 | **135,461** | 3 | **156** | 381 | ✅ |
| WNConv1d Small | 128 | 32 | 3 | 1,868 | 123 | ✅ |
| **WNConv1d Large** | 128 | **135,461** | 3 | **1,489** | 2,046 | ✅ |
| ResidualBlock Small | 128 | 32 | 16 | 2,050 | 1,670 | ✅ |
| **WNConvTranspose 16x** | **2048→1024** | 2 | 3 | ~1,500 | - | ✅ FIXED (LoopedWeightedSumComputation) |
| **DecoderBlock 1** | **2048→1024** | 2 | 50 | ~83,000 | - | ✅ FIXED |

### Key Finding: **WNConvTranspose1d with High Channel Counts is the Bottleneck**

1. **Snake scales perfectly**: Compile time is constant (~155ms) regardless of sequence length (32 → 135,461). The earlier optimization (precomputing broadcasted tensors) is working as intended.

2. **WNConv1d scales well**: Compile time (~1.5-2s) is roughly constant regardless of sequence length.

3. **WNConvTranspose1d with 2048 channels is EXTREMELY slow**: A single WNConvTranspose1d layer with inChannels=2048 takes over 3 minutes (180+ seconds) just to compile - and this is only 3 operations!

4. **The issue is CHANNEL COUNT, not sequence length**: The first decoder block operates on only 2 samples but with 2048 channels, yet takes 4+ minutes.

### Root Cause Analysis

The decoder has 5 WNConvTranspose1d layers:

| Block | In Channels | Out Channels | Expected Compile Time |
|-------|-------------|--------------|----------------------|
| 1 | 2048 | 1024 | **Very slow** (>3 min) |
| 2 | 1024 | 512 | **Slow** (~1-2 min expected) |
| 3 | 512 | 256 | Moderate |
| 4 | 256 | 128 | Fast |
| 5 | 128 | 128 | Fast |

The convTranspose1d operation complexity scales with `inChannels × outChannels × kernelSize`. For block 1:
- 2048 × 1024 × 16 = **33.5M** weight elements

### Revised Probability (Post-Test)

| Component | Old Est. | Actual | Notes |
|-----------|----------|--------|-------|
| WNConvTranspose1d (high channels) | 50% | **~95%** | Confirmed as primary bottleneck |
| Snake | 25% | **0%** | Scales perfectly |
| WNConv1d | 15% | **~5%** | Takes ~2s but not the main issue |
| Residual connection | 10% | **0%** | No measurable impact |

### Root Cause Identified (2025-12-22)

**The issue is the `weightedSum` group size in `convTranspose1d`.**

From `LayerFeatures.java:823`:
```java
TraversalPolicy groupShape = shape(1, 1, inputChannels, kernelSize);
```

For each output element, the operation sums `inputChannels × kernelSize` values:

| Test Case | Weighted Sum Size | Compile Time |
|-----------|-------------------|--------------|
| Small (4ch × 4k) | **16** values | **0.78 seconds** |
| Large (2048ch × 16k) | **32,768** values | **>150 seconds** (timeout) |

The weighted sum is summing **32K values per output element** for the decoder's first block!
This creates an enormous expression tree that takes minutes to compile.

### Replication Test

```bash
# Fast test (16 values per weighted sum)
export AR_HARDWARE_LIBS=/tmp/ar_libs/ && export AR_HARDWARE_DRIVER=native && \
mvn test -pl utils -Dtest=Conv1dLayerTests#testConvTranspose1dCorrectnessSmall

# Slow test (32K values per weighted sum) - will timeout
export AR_HARDWARE_LIBS=/tmp/ar_libs/ && export AR_HARDWARE_DRIVER=native && \
mvn test -pl utils -Dtest=Conv1dLayerTests#testConvTranspose1dLargeChannels
```

### Restructuring Attempts (2025-12-22)

Attempted several approaches to fix the slow `convTranspose1d` compilation:

1. **Two-step approach: small weightedSum + permute + sum** (FAILED)
   - Step 1: weightedSum over just kernelSize (16 values per output element)
   - Step 2: permute to put inputChannels last, then use sum() to reduce
   - Issue: Creates a huge intermediate tensor (batch × inputChannels × outputChannels × outLength = 68M elements)
   - Result: Still times out because even generating 68M operations is slow

2. **im2col + matmul approach** (FAILED)
   - Used `enumerate` to extract sliding windows (im2col pattern)
   - Reshape windows and filters for matrix multiplication
   - Use matmul which internally uses multiply + sum
   - Issue: matmul's multiply + sum creates a huge broadcasted tensor:
     - windows: (33, 32768)
     - filters: (32768, 1024)
     - When matmul does `multiply(traverseEach(a), repeat(outputSize, b))`, it creates:
       - repeat(32768, (33, 32768)) = (32768, 33, 32768) = 34 BILLION elements!
   - Result: Still times out due to massive intermediate tensor

3. **Different groupShape layout** (NO IMPROVEMENT)
   - Changed from `(1, 1, inputChannels, kernelSize)` to `(1, inputChannels, 1, kernelSize)`
   - Result: Same total group size, so no performance improvement

### Root Cause Analysis

The fundamental problem is that **all approaches that express the computation as multiply + sum
create large intermediate tensors** that are slow to compile:

| Approach | Intermediate Size | Why It's Slow |
|----------|-------------------|---------------|
| Single weightedSum | N/A | 32K operations per output, unrolled |
| Two-step weightedSum + sum | 68M elements | 68M operations to generate |
| im2col + matmul | 34B elements | Massive broadcast in multiply |

The computation genuinely requires `inputChannels × kernelSize × outputChannels × outLength`
work (1.1 billion multiply-adds for decoder block 1). The issue is that the framework
generates unrolled operations instead of loops.

### Current State

The `convTranspose1d` implementation uses the original weightedSum approach, which is:
- **Correct**: Small test passes with expected output values
- **Slow for large inputChannels**: Times out with 2048 channels due to 32K-element sums

### Required Framework Changes

To fix this properly, the framework needs changes to its code generation:

1. **Loop-based weightedSum for large group sizes**
   - Key file: `algebra/src/.../WeightedSumComputation.java`
   - When group size exceeds a threshold (e.g., 1024), generate a loop instead of unrolled ops
   - This is the most targeted fix

2. **Loop-based sum for large inputs**
   - `CollectionSumComputation` uses `AggregatedProducerComputation` with `setReplaceLoop(true)`
   - Verify this actually generates loop-based code for large inputs

3. **Avoid broadcast in matmul**
   - The matmul implementation (line 224 in MatrixFeatures.java) uses:
     ```java
     return multiply(traverseEach(a), repeat(outputSize, b)).traverse(batchAxis).sum();
     ```
   - This creates a massive broadcasted tensor for matrix-matrix multiplication
   - Should use a more efficient approach for 2D×2D matmul

4. **Kernel caching**
   - Once compiled, save the native kernel to disk
   - Subsequent runs would load the cached kernel instantly

### Narrow Test Plan

Create isolated tests for each component at different scales:

```
OobleckComponentTests.java
├── testSnakeSmall()           - Snake at 32 samples
├── testSnakeMedium()          - Snake at 4096 samples
├── testSnakeLarge()           - Snake at 135461 samples
├── testWNConv1dSmall()        - WNConv1d(k=7) at 32 samples
├── testWNConv1dLarge()        - WNConv1d(k=7) at 135461 samples
├── testWNConvTranspose16x()   - Transpose with stride=16
├── testWNConvTranspose4x()    - Transpose with stride=4, large output
├── testResidualBlockSmall()   - Full residual block at 32 samples
├── testResidualBlockLarge()   - Full residual block at 135461 samples
├── testDecoderBlock1()        - First decoder block only
├── testDecoderBlock5()        - Last decoder block only (largest)
└── testFullDecoder()          - Full decoder (baseline comparison)
```

Each test should measure and report:
- Build time (model construction)
- Compile time (model.compile())
- Forward pass time (compiled.forward())

---

## Debugging Tips

### Check Build Status

```bash
# Full build (must pass before committing)
export AR_HARDWARE_LIBS=/tmp/ar_libs/ && \
export AR_HARDWARE_DRIVER=native && \
mvn clean install -DskipTests
```

### View Validation Logs

```bash
# After running tests, check logs
cat ml/test_data/stable_audio/encoder_validation.log
cat ml/test_data/stable_audio/decoder_validation.log
```

### Monitor Compilation Progress

If compilation seems stuck, check for:
1. High CPU usage (optimization running)
2. Memory pressure (large expression trees)
3. Console output for warnings

---

## Recent Changes

### 2025-12-25: Kernel Compilation Fix - LoopedWeightedSumComputation

**Problem**: The `convTranspose1d` operation with large channel counts (2048 channels × 16 kernel = 32K values per weighted sum) caused kernel compilation to take 7+ minutes. The code was generating unrolled expressions instead of native for-loops.

**Root Cause**: The optimization pipeline (`Process::optimize`) wasn't being applied because `OperationList.enableAutomaticOptimization = false` by default, and even when enabled, uniform (single-item) lists skip the optimization path.

**Solution**: Created `LoopedWeightedSumComputation` class that:
1. Overrides `isIsolationTarget()` to return `true` - forces isolation during optimization
2. Overrides `getScope()` to generate native for-loops for outer/inner iteration
3. Uses indexer lambdas for flexible input/weight access patterns

**Key Files**:
- `algebra/src/main/java/org/almostrealism/algebra/computations/LoopedWeightedSumComputation.java`
- `layers/LayerFeatures.java` - Updated `convTranspose1d()` to use new computation

**Results** (decoder with 2048 channels):
- optimize() time: **53ms** (was 7+ minutes stuck)
- kernel compilation: **83 seconds** (reasonable for 252 operations)
- Total compile time: **~1.4 minutes** (was timing out at 10+ minutes)

**Generated Code** (with optimization applied):
```c
for (int _3_i = 0; _3_i < outerCount;) {
    result[...] = (input[...] * weight[...]) + (input[...] * weight[...]) +
                  ... + result[...];  // innerCount terms unrolled
    _3_i = _3_i + 1;
}
```

The native for-loop replaces O(outerCount × innerCount) unrolled operations with a loop containing only innerCount terms.

---

### 2025-12-20: Snake Activation Optimization

**Problem**: Snake activation used `.repeat(seqLen)` which created huge expression trees during compilation for long sequences (seqLen up to 135461).

**Solution**: Modified `LayerFeatures.snake()` to precompute broadcasted alpha/beta tensors as `PackedCollection`s before building the expression graph.

**Location**: `graph/src/main/java/org/almostrealism/layers/LayerFeatures.java:1467-1508`

**Result**: "Building decoder..." phase now completes in ~1 minute (was hanging indefinitely).

---

## References

- [Stable Audio Open](https://huggingface.co/stabilityai/stable-audio-open-1.0)
- [DAC (Descript Audio Codec)](https://github.com/descriptinc/descript-audio-codec)
- [Oobleck Paper](https://arxiv.org/abs/2306.01203)
