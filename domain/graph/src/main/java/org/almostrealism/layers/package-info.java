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
 * Neural network layer abstractions and factory interfaces.
 *
 * <p>This package provides the foundation for building neural network layers
 * in the Almost Realism framework. Layers are composed of forward and backward
 * {@link org.almostrealism.graph.Cell} instances that implement the computational
 * graph pattern.</p>
 *
 * <p>Key interfaces and classes:</p>
 * <ul>
 *   <li>{@link org.almostrealism.layers.LayerFeatures} - Comprehensive factory interface
 *       for creating neural network layers (dense, conv, norm, etc.)</li>
 *   <li>{@link org.almostrealism.layers.ActivationFeatures} - Factory interface for
 *       activation functions (ReLU, SiLU, GELU, Softmax, Snake)</li>
 *   <li>{@link org.almostrealism.layers.CellularLayer} - Interface for layers that
 *       expose forward and backward cells</li>
 *   <li>{@link org.almostrealism.layers.DefaultCellularLayer} - Standard implementation
 *       of CellularLayer with input/output tracking</li>
 *   <li>{@link org.almostrealism.layers.BackPropagation} - Interface for gradient
 *       propagation through layers</li>
 *   <li>{@link org.almostrealism.layers.DefaultGradientPropagation} - Automatic
 *       differentiation for gradient computation</li>
 *   <li>{@link org.almostrealism.layers.Learning} - Marker interface for trainable components</li>
 *   <li>{@link org.almostrealism.layers.ParameterUpdate} - Strategy for updating weights</li>
 *   <li>{@link org.almostrealism.layers.LoRALinear} - Low-Rank Adaptation for efficient fine-tuning</li>
 *   <li>{@link org.almostrealism.layers.MonitorReceptor} - Debugging receptor for NaN/zero detection</li>
 * </ul>
 *
 * @see org.almostrealism.layers.LayerFeatures
 * @see org.almostrealism.layers.CellularLayer
 * @see org.almostrealism.model.Model
 */
package org.almostrealism.layers;
