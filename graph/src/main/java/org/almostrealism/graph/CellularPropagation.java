/*
 * Copyright 2024 Michael Murray
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

package org.almostrealism.graph;

import io.almostrealism.relation.Producer;

/**
 * Defines a bidirectional computation component with both forward and backward propagation paths.
 * This interface is the foundation for neural network layers that support backpropagation
 * for gradient-based training.
 *
 * <p>CellularPropagation encapsulates the dual nature of neural network computation:</p>
 * <ul>
 *   <li><b>Forward pass</b> - Transforms input data through the network</li>
 *   <li><b>Backward pass</b> - Propagates gradients back through the network</li>
 * </ul>
 *
 * <h2>Forward and Backward Cells</h2>
 * <p>Each propagation component maintains two cells:</p>
 * <ul>
 *   <li>{@link #getForward()} - The cell that processes data during forward propagation</li>
 *   <li>{@link #getBackward()} - The cell that processes gradients during backpropagation</li>
 * </ul>
 *
 * <h2>Usage in Training</h2>
 * <p>During training:</p>
 * <ol>
 *   <li>Input flows through the forward cell to produce output</li>
 *   <li>Loss gradients flow backward through the backward cell</li>
 *   <li>Backward cell typically updates weights and passes gradients upstream</li>
 * </ol>
 *
 * <h2>Graph Construction</h2>
 * <p>When building neural network graphs, components connect their forward cells
 * in one direction and their backward cells in the reverse direction:</p>
 *
 * <pre>{@code
 * // Forward path: layer1 -> layer2
 * layer1.getForward().setReceptor(layer2.getForward());
 *
 * // Backward path: layer2 -> layer1
 * layer2.getBackward().setReceptor(layer1.getBackward());
 * }</pre>
 *
 * @param <T> the type of data processed, typically {@link org.almostrealism.collect.PackedCollection}
 * @see Cell
 * @see org.almostrealism.model.Block
 * @see org.almostrealism.layers.CellularLayer
 * @author Michael Murray
 */
public interface CellularPropagation<T> {
	/**
	 * Applies the forward transformation to the given input.
	 * This is a convenience method that delegates to the forward cell's apply method.
	 *
	 * @param input the input data producer
	 * @return a producer representing the forward pass output
	 */
	default Producer<T> apply(Producer<T> input) {
		return getForward().apply(input);
	}

	/**
	 * Returns the cell responsible for forward propagation.
	 * During inference, data flows through this cell to produce outputs.
	 *
	 * @return the forward propagation cell
	 */
	Cell<T> getForward();

	/**
	 * Returns the cell responsible for backward propagation (backpropagation).
	 * During training, gradients flow through this cell to update weights
	 * and propagate gradients to upstream layers.
	 *
	 * @return the backward propagation cell
	 */
	Cell<T> getBackward();
}
