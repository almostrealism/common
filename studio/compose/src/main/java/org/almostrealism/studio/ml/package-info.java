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
 * Machine learning integration for audio generation and conditioning in the studio compose
 * layer. This package provides components for ML-based audio synthesis, latent space
 * manipulation, auto-encoding, and diffusion-based generation.
 *
 * <p>Key classes include:</p>
 * <ul>
 *   <li>{@link org.almostrealism.studio.ml.AudioComposer} - Composes audio by blending
 *       encoded sources in the latent space of an autoencoder, as a weighted sum or a
 *       normalized weighted mean selected by a position vector</li>
 *   <li>{@link org.almostrealism.studio.ml.ComposableAudioFeatures} - One encoded source of
 *       an {@link org.almostrealism.studio.ml.AudioComposer}, scaled by the dot product of its
 *       merge weights with the position vector and by its blend contribution</li>
 *   <li>{@link org.almostrealism.studio.ml.AudioDiffusionGenerator} - Diffusion model-based
 *       audio generation</li>
 *   <li>{@link org.almostrealism.studio.ml.AudioGenerator} - Base audio generation interface</li>
 *   <li>{@link org.almostrealism.studio.ml.AudioLatentDataset} - Latent space dataset for
 *       training audio generation models</li>
 *   <li>{@link org.almostrealism.studio.ml.AudioModulator} - ML-based audio modulation</li>
 *   <li>{@link org.almostrealism.studio.ml.ConditionalAudioSystem} - Conditional audio
 *       generation system</li>
 *   <li>{@link org.almostrealism.studio.ml.CompiledModelAutoEncoder} - Auto-encoder for
 *       audio feature compression</li>
 * </ul>
 */
package org.almostrealism.studio.ml;
