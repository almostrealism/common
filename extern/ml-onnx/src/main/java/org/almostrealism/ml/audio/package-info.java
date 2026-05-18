/*
 * Copyright 2026 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

/**
 * ONNX Runtime implementations of the audio ML interfaces defined in
 * {@code engine/ml}.
 *
 * <p>This package provides thin wrappers that delegate to ONNX Runtime sessions
 * to perform audio encoding, decoding, text conditioning, and diffusion model
 * forward passes:
 *
 * <ul>
 *   <li>{@link org.almostrealism.ml.audio.OnnxAutoEncoder} — encodes stereo audio
 *       into a compact latent and decodes latents back to audio using paired
 *       ONNX encoder/decoder models.</li>
 *   <li>{@link org.almostrealism.ml.audio.OnnxAudioConditioner} — runs a T5-based
 *       ONNX conditioner model to produce cross-attention and global conditioning
 *       tensors from token ids.</li>
 *   <li>{@link org.almostrealism.ml.audio.OnnxDiffusionModel} — wraps an ONNX
 *       diffusion transformer (DiT) model and exposes its forward pass through the
 *       {@link org.almostrealism.ml.audio.DiffusionModel} interface.</li>
 * </ul>
 *
 * <p>All ONNX Runtime types ({@code OnnxTensor}, {@code OrtSession},
 * {@code OrtEnvironment}) are fully encapsulated within this package and must
 * not leak into higher-level modules.
 */
package org.almostrealism.ml.audio;
