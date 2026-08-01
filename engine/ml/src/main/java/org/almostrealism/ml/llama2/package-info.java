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
 * Llama2 language model implementation using the Almost Realism compute pipeline.
 *
 * <p>This package provides a complete Llama2 inference implementation that loads
 * weights from the binary checkpoint format used by
 * <a href="https://github.com/karpathy/llama2.c">llama2.c</a> and constructs
 * an {@link org.almostrealism.ml.AutoregressiveModel} backed by
 * {@link org.almostrealism.ml.AttentionFeatures} building blocks.</p>
 *
 * <h2>Key Classes</h2>
 * <ul>
 *   <li>{@link org.almostrealism.ml.llama2.Llama2} - Top-level model entry point</li>
 *   <li>{@link org.almostrealism.ml.llama2.Llama2Config} - Model hyperparameters</li>
 *   <li>{@link org.almostrealism.ml.llama2.Llama2Weights} - Weight tensors loaded from
 *       the binary checkpoint</li>
 * </ul>
 *
 * @see org.almostrealism.ml.llama2.Llama2
 * @see org.almostrealism.ml.AttentionFeatures
 */
package org.almostrealism.ml.llama2;
