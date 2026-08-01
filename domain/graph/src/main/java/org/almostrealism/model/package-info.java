/*
 * Copyright 2025 Michael Murray
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
 * High-level neural network model construction and execution.
 *
 * <p>This package provides the top-level abstractions for building and running
 * neural network models. Models are constructed by composing {@link org.almostrealism.model.Block}
 * instances into pipelines, then compiled into optimized executables.</p>
 *
 * <p>Key classes:</p>
 * <ul>
 *   <li>{@link org.almostrealism.model.Block} - The primary composable neural network
 *       unit with forward and backward cells</li>
 *   <li>{@link org.almostrealism.model.Model} - Top-level container for building and
 *       training neural networks</li>
 *   <li>{@link org.almostrealism.model.CompiledModel} - Optimized, executable model
 *       produced by Model.compile()</li>
 *   <li>{@link org.almostrealism.model.SequentialBlock} - Container for sequential
 *       block chains with training support</li>
 *   <li>{@link org.almostrealism.model.DefaultBlock} - Basic block implementation
 *       with explicit forward/backward cells</li>
 *   <li>{@link org.almostrealism.model.BranchBlock} - Block that distributes input
 *       to multiple parallel branches</li>
 *   <li>{@link org.almostrealism.model.ForwardOnlyBlock} - Wrapper that disables
 *       backward propagation for inference-only blocks</li>
 *   <li>{@link org.almostrealism.model.ModelFeatures} - Convenience factories for
 *       common model architectures</li>
 * </ul>
 *
 * @see org.almostrealism.model.Block
 * @see org.almostrealism.model.Model
 * @see org.almostrealism.model.CompiledModel
 */
package org.almostrealism.model;
