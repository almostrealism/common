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
 * Qwen3 language model implementation using the Almost Realism compute pipeline.
 *
 * <p>This package implements the Qwen3 transformer architecture with full support for
 * autoregressive text generation. Weights are loaded from protobuf format (exported via
 * the provided Python extraction script) using {@link org.almostrealism.ml.StateDictionary}.</p>
 *
 * <h2>Key Features</h2>
 * <ul>
 *   <li>Grouped Query Attention (GQA) with 4:1 query-to-KV head ratio</li>
 *   <li>QK-Normalization per head for training stability</li>
 *   <li>SwiGLU feed-forward networks</li>
 *   <li>Rotary position embeddings (RoPE) with 1M base frequency</li>
 *   <li>Tied input and output embeddings</li>
 * </ul>
 *
 * <h2>Key Classes</h2>
 * <ul>
 *   <li>{@link org.almostrealism.ml.qwen3.Qwen3} - Top-level model entry point</li>
 *   <li>{@link org.almostrealism.ml.qwen3.Qwen3Config} - Model hyperparameters</li>
 *   <li>{@link org.almostrealism.ml.qwen3.Qwen3Tokenizer} - Byte-level BPE tokenizer
 *       with Qwen3-specific vocabulary and merge rules</li>
 * </ul>
 *
 * @see org.almostrealism.ml.qwen3.Qwen3
 * @see org.almostrealism.ml.AttentionFeatures
 */
package org.almostrealism.ml.qwen3;
