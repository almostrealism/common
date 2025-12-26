# Stable Audio Open Autoencoder Implementation Status

## Overview

Native AR framework implementation of the Stable Audio Open autoencoder (also known as Oobleck/DAC),
which achieves 65536x compression of stereo audio through a VAE architecture.

Strides: 4 x 8 x 8 x 16 x 16 = 65536x compression.

## Implementation Complete

**Date**: 2025-12-17

The implementation has been rewritten to match the actual `model.ckpt` checkpoint from Stable Audio Open.

### Actual Architecture (from checkpoint)

| Component | Implemented | Notes |
|-----------|-------------|-------|
| Compression ratio | **65536x** | 4 x 8 x 8 x 16 x 16 |
| Encoder strides | 4, 8, 8, 16, 16 | Matches checkpoint |
| Decoder strides | 16, 16, 8, 8, 4 | Reverse of encoder |
| Weight format | **Weight Normalization** | wnConv1d, wnConvTranspose1d |
| Snake activation | **Learnable** (alpha, beta) | Per-channel parameters |
| Residual blocks | **3 nested per stage** | Matches checkpoint |
| Conv kernels | 7 (residual), stride (downsample) | k=7 for main path |

### Encoder Layer Structure (actual)
```
layers.0: WNConv1d(2 → 128, k=7)           # Input projection
layers.1: EncoderBlock(128, stride=4)      # 3 nested ResBlocks + downsample
layers.2: EncoderBlock(128→256, stride=8)
layers.3: EncoderBlock(256→512, stride=8)
layers.4: EncoderBlock(512→1024, stride=16)
layers.5: EncoderBlock(1024→2048, stride=16)
layers.6: Snake(2048)                      # Final activation
layers.7: WNConv1d(2048 → 128, k=3)        # Output projection
```

### Decoder Layer Structure (actual)
```
layers.0: WNConv1d(64 → 2048, k=7)         # Input projection
layers.1: DecoderBlock(2048→1024, stride=16)
layers.2: DecoderBlock(1024→512, stride=16)
layers.3: DecoderBlock(512→256, stride=8)
layers.4: DecoderBlock(256→128, stride=8)
layers.5: DecoderBlock(128, stride=4)
layers.6: Snake(128)                       # Final activation
layers.7: WNConv1d(128 → 2, k=7)           # Output projection
```

## Implementation Status

### Phase 1: Foundation Layers - COMPLETE
- [x] Learnable Snake activation with alpha/beta parameters (`LayerFeatures.snake()`)
- [x] Conv1d with arbitrary kernel size, stride, and padding
- [x] ConvTranspose1d for upsampling
- [x] Weight Normalization support (`wnConv1d`, `wnConvTranspose1d`)

### Phase 2: Architecture - COMPLETE
- [x] `OobleckEncoder.java` - Matches Stable Audio Open checkpoint
- [x] `OobleckDecoder.java` - Matches Stable Audio Open checkpoint
- [x] `VAEBottleneck.java` - Channel split for inference
- [x] `OobleckAutoEncoder.java` - Full autoencoder assembly

### Phase 3: Weight Extraction - COMPLETE
- [x] Python script: `scripts/extract_stable_audio_autoencoder.py`
- [x] Extracts encoder and decoder weights in StateDictionary format
- [x] Generates PyTorch reference outputs for validation

### Phase 4: Validation - COMPLETE (Architecture Validated)
- [x] PyTorch reference outputs generated for encoder
- [x] PyTorch reference outputs generated for decoder
- [x] Java validation test framework (`OobleckValidationTest.java`)
- [x] Weight extraction in protobuf format (365 tensors load correctly)
- [x] Architecture constructs and compiles
- [ ] Full numerical validation (requires >8GB RAM for 131K sample input)

## Files

```
ml/src/main/java/org/almostrealism/ml/audio/
├── OobleckAutoEncoder.java    # Full autoencoder assembly
├── OobleckEncoder.java        # Audio → Latent encoder (65536x compression)
├── OobleckDecoder.java        # Latent → Audio decoder
├── VAEBottleneck.java         # VAE mean extraction (128 → 64 channels)
└── OOBLECK_STATUS.md          # This status document

ml/src/test/java/org/almostrealism/ml/audio/
├── OobleckAutoEncoderTest.java  # Architecture validation tests (synthetic)
└── OobleckValidationTest.java   # PyTorch reference comparison tests

ml/scripts/
└── extract_stable_audio_autoencoder.py  # Weight extraction + reference generation

graph/src/main/java/org/almostrealism/layers/LayerFeatures.java
├── snake(shape, alpha, beta)    # Learnable Snake activation
├── wnConv1d(...)                # Weight-normalized Conv1d
└── wnConvTranspose1d(...)       # Weight-normalized ConvTranspose1d
```

## What's Working

1. **Architecture compiles** - All components construct without errors
2. **Shape propagation** - Encoder downsamples by 65536x, decoder upsamples accordingly
3. **Residual connections** - Using `residual()` method for identity skip connections
4. **Build passes** - `mvn clean install -DskipTests` succeeds
5. **Weight extraction** - Python script successfully extracts weights in protobuf format
6. **Reference generation** - PyTorch reference outputs generated for validation
7. **Weights load correctly** - StateDictionary loads 365 tensors from protobuf files
8. **ConvTranspose1d output_padding** - Added support for proper upsampling in decoder

## Validation Status

The implementation architecture matches the Stable Audio Open checkpoint structure:
- Weight key names match (`encoder.layers.X.layers.Y...`)
- Channel progressions match (128→256→512→1024→2048)
- Stride and kernel sizes match
- All 365 weight tensors extracted successfully

## Next Steps

### To Complete Validation
1. **Run validation tests** - Execute `OobleckValidationTest` with real weights
2. **Compare numerically** - Target: mean absolute error < 0.01

### Future Enhancements
1. **Training support** - Add reparameterization trick to VAEBottleneck
2. **Streaming inference** - Support processing audio in chunks
3. **Performance optimization** - Profile and optimize critical paths
4. **Integration** - Connect with audio I/O utilities

## Technical Notes

### Weight Normalization
Weight normalization decomposes weights W = g * v / ||v|| where:
- `weight_g`: magnitude parameter (outChannels, 1, 1)
- `weight_v`: direction parameter (outChannels, inChannels, kernel)

The normalization is pre-computed at model construction time.

### Snake Activation
Learnable Snake activation: f(x) = x + (1/beta) * sin²(alpha * x)
- `alpha`: per-channel frequency parameter
- `beta`: per-channel scaling parameter

### Residual Block Pattern
Uses the built-in `residual()` method from LayerFeatures for identity skip connections:
```java
return residual(mainPath);
```

### ConvTranspose1d Output Padding
Decoder upsampling requires `output_padding` to achieve correct output lengths:
```java
// output_length = (in - 1) * stride - 2*padding + kernel + output_padding
int outputPadding = stride - 1;  // Required for proper upsampling
block.add(wnConvTranspose1d(..., outputPadding, ...));
```

### Memory Requirements
Full validation with real weights requires significant memory due to large intermediate tensors:
- Input: 131072 samples -> 16MB for (1, 128, 131072) after input conv
- Recommended: >16GB RAM for full model validation
- The 8GB limit in the test environment causes `Memory max reached` errors

## References

- Original Oobleck paper/implementation (Stability AI)
- HuggingFace model: `stabilityai/stable-audio-open-small` (or similar)
- AR Framework documentation in `common/CLAUDE.md`
