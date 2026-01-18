/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism.layers;

import org.almostrealism.model.Block;

/**
 * A neural network layer that combines the capabilities of both {@link Layer} and {@link Block}.
 * CellularLayer is the primary interface for trainable neural network layers that can be
 * composed into models using the cell-based architecture.
 *
 * <p>By combining Layer and Block, CellularLayer provides:</p>
 * <ul>
 *   <li><b>From Layer</b>: Access to learnable weights and configuration flags</li>
 *   <li><b>From Block</b>: Input/output shapes, forward/backward propagation cells, and composition methods</li>
 * </ul>
 *
 * <h2>Common Implementations</h2>
 * <p>CellularLayer is typically implemented by layer types such as:</p>
 * <ul>
 *   <li>Dense (fully-connected) layers</li>
 *   <li>Convolutional layers</li>
 *   <li>Normalization layers (LayerNorm, BatchNorm, RMSNorm)</li>
 *   <li>Activation layers (ReLU, GELU, SiLU)</li>
 *   <li>Pooling layers</li>
 *   <li>Attention layers</li>
 * </ul>
 *
 * <h2>Creating Layers</h2>
 * <p>Layers are typically created through factory methods in {@link LayerFeatures}:</p>
 * <pre>{@code
 * // Create a dense layer with 512 input, 256 output neurons
 * CellularLayer dense = layerFeatures.dense(512, 256).apply(inputShape);
 *
 * // Create a normalization layer
 * CellularLayer norm = layerFeatures.norm(weights, biases).apply(shape);
 *
 * // Create a SiLU activation layer
 * CellularLayer silu = layerFeatures.silu().apply(shape);
 * }</pre>
 *
 * <h2>Usage in Models</h2>
 * <p>CellularLayer instances can be composed into models:</p>
 * <pre>{@code
 * Model model = new Model(inputShape);
 * model.add(dense(512, 256));
 * model.add(norm(256));
 * model.add(silu());
 * model.add(dense(256, 10));
 * model.add(softmax());
 * }</pre>
 *
 * @see Layer
 * @see Block
 * @see LayerFeatures
 * @see org.almostrealism.model.Model
 * @author Michael Murray
 */
public interface CellularLayer extends Layer, Block {

}
