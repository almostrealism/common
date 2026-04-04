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
 * Machine learning infrastructure for transformer models, tokenization, and diffusion.
 *
 * <p>This package provides the core building blocks for large language model inference
 * and diffusion-based generative models within the Almost Realism compute pipeline.
 * Computations are expressed as {@code CollectionProducer} compositions that compile
 * to native GPU/CPU kernels via the AR hardware abstraction layer.</p>
 *
 * <h2>Key Components</h2>
 * <ul>
 *   <li>{@link org.almostrealism.ml.AttentionFeatures} - Mixin interface providing
 *       self-attention, cross-attention, and scaled dot-product attention building blocks</li>
 *   <li>{@link org.almostrealism.ml.AutoregressiveModel} - Token-by-token generation
 *       loop for language model inference</li>
 *   <li>{@link org.almostrealism.ml.StateDictionary} - Protobuf-based weight storage
 *       and retrieval for model parameters</li>
 *   <li>{@link org.almostrealism.ml.Tokenizer} - Common interface for encoding text
 *       to token IDs and decoding back</li>
 *   <li>{@link org.almostrealism.ml.DiffusionTrainingDataset} - Dataset adapter that
 *       generates noisy training samples for diffusion model training</li>
 *   <li>{@link org.almostrealism.ml.ModelBundle} - Container combining a
 *       {@link org.almostrealism.ml.StateDictionary} with model metadata</li>
 * </ul>
 *
 * <h2>Subpackages</h2>
 * <ul>
 *   <li>{@code org.almostrealism.ml.audio} - Diffusion transformer and Oobleck autoencoder
 *       for audio generation</li>
 *   <li>{@code org.almostrealism.ml.llama2} - Llama2 language model implementation</li>
 *   <li>{@code org.almostrealism.ml.qwen3} - Qwen3 language model implementation</li>
 *   <li>{@code org.almostrealism.ml.tokenization} - Byte-level BPE tokenization utilities</li>
 * </ul>
 *
 * @see org.almostrealism.ml.AttentionFeatures
 * @see org.almostrealism.ml.StateDictionary
 */
package org.almostrealism.ml;
