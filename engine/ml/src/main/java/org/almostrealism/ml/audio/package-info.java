/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * Audio diffusion models, autoencoders, and sampling strategies.
 *
 * <p>This package implements the audio generation pipeline including the
 * {@link org.almostrealism.ml.audio.DiffusionTransformer} for denoising,
 * the {@link org.almostrealism.ml.audio.OobleckAutoEncoder} for audio compression,
 * and the {@link org.almostrealism.ml.audio.DiffusionSampler} which owns the
 * diffusion sampling loop.</p>
 *
 * <h2>Architecture</h2>
 * <ol>
 *   <li>Raw audio is encoded to a compact latent representation using
 *       {@link org.almostrealism.ml.audio.OobleckEncoder} (65536x compression)</li>
 *   <li>The {@link org.almostrealism.ml.audio.DiffusionTransformer} iteratively
 *       denoises latents conditioned on text and timestep embeddings</li>
 *   <li>Decoded latents are reconstructed to audio via
 *       {@link org.almostrealism.ml.audio.OobleckDecoder}</li>
 * </ol>
 *
 * <h2>Sampling Loop Ownership</h2>
 * <p>{@link org.almostrealism.ml.audio.DiffusionSampler} is the sole owner of the
 * diffusion sampling loop. Do not write explicit timestep iteration loops; delegate
 * to the sampler instead.</p>
 *
 * @see org.almostrealism.ml.audio.DiffusionSampler
 * @see org.almostrealism.ml.audio.DiffusionTransformer
 * @see org.almostrealism.ml.audio.OobleckAutoEncoder
 */
package org.almostrealism.ml.audio;
