# ML-ONNX Module (ar-ml-onnx)

Provides ONNX Runtime inference integration for running pre-trained models exported in the ONNX format.

## Overview

This module bridges the AR framework with [ONNX Runtime](https://onnxruntime.ai/), enabling inference on models exported from PyTorch, TensorFlow, or other frameworks that support the ONNX standard. It provides adapter classes that expose ONNX model capabilities through AR interfaces.

## Key Types

- **`OnnxFeatures`** — mixin interface providing convenience methods for loading and running ONNX models
- **`OnnxAutoEncoder`** — wraps an ONNX-exported autoencoder model for audio latent space encoding/decoding
- **`OnnxAudioConditioner`** — wraps an ONNX-exported audio conditioning model (e.g., for text-to-audio conditioning)
- **`OnnxDiffusionModel`** — wraps an ONNX-exported diffusion model for audio generation

## Usage

```java
// Load an ONNX autoencoder
OnnxAutoEncoder encoder = new OnnxAutoEncoder(Path.of("model.onnx"));

// Encode audio to latent space
PackedCollection latent = encoder.encode(audioData);

// Decode latent back to audio
PackedCollection audio = encoder.decode(latent);
```

## Dependencies

- **ar-ml** — provides ML infrastructure (DiffusionSampler, model interfaces)
- **com.microsoft.onnxruntime:onnxruntime** — ONNX Runtime Java API

## Design

This module isolates the ONNX Runtime dependency from the rest of the framework. The diffusion and autoencoder interfaces are defined in ar-ml; this module provides ONNX-backed implementations. This allows the same training and generation pipelines to work with either native AR models or pre-trained ONNX models.
