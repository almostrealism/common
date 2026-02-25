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

package org.almostrealism.model;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.cycle.Setup;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Cell;
import org.almostrealism.graph.CellularPropagation;
import org.almostrealism.graph.CollectionReceptor;
import org.almostrealism.graph.Receptor;
import org.almostrealism.hardware.Input;
import org.almostrealism.layers.Component;
import org.almostrealism.layers.Layer;
import org.almostrealism.layers.LayerFeatures;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A composable neural network unit that can be chained with other blocks to build complex models.
 * Block is the primary abstraction for neural network components, providing both forward and
 * backward propagation capabilities along with shape information and composition methods.
 *
 * <p>Blocks combine several important interfaces:</p>
 * <ul>
 *   <li>{@link Component} - Provides output shape information</li>
 *   <li>{@link CellularPropagation} - Forward and backward cells for data flow</li>
 *   <li>{@link Setup} - Initialization logic</li>
 *   <li>{@link LayerFeatures} - Access to layer creation utilities</li>
 * </ul>
 *
 * <h2>Block Composition</h2>
 * <p>Blocks support fluent composition through methods like:</p>
 * <ul>
 *   <li>{@link #andThen(Block)} - Chain with another block</li>
 *   <li>{@link #andThen(Function)} - Chain with a block factory</li>
 *   <li>{@link #reshape(int...)} - Add a reshape operation</li>
 *   <li>{@link #scale(double)} - Add a scaling operation</li>
 *   <li>{@link #branch()} - Create parallel branches</li>
 * </ul>
 *
 * <h2>Shape Management</h2>
 * <p>Every block has defined input and output shapes:</p>
 * <ul>
 *   <li>{@link #getInputShape()} - The expected input shape</li>
 *   <li>{@link #getOutputShape()} - The resulting output shape (from Component)</li>
 * </ul>
 *
 * <h2>Forward Pass</h2>
 * <p>Data flows through blocks via the forward cell:</p>
 * <pre>{@code
 * Block block = dense(256).apply(inputShape);
 * Runnable forwardOp = block.forward(inputData);
 * forwardOp.run();
 * }</pre>
 *
 * <h2>Block Types</h2>
 * <ul>
 *   <li>{@link DefaultBlock} - Basic block with explicit forward/backward cells</li>
 *   <li>{@link SequentialBlock} - Container for sequential block chains</li>
 *   <li>{@link BranchBlock} - Block that splits flow to multiple branches</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Build a neural network block chain
 * Block network = new SequentialBlock(shape(784))
 *     .andThen(dense(256))
 *     .andThen(relu())
 *     .andThen(dense(10))
 *     .andThen(softmax());
 *
 * // Execute forward pass
 * network.forward(input).run();
 * }</pre>
 *
 * @see SequentialBlock
 * @see DefaultBlock
 * @see org.almostrealism.layers.CellularLayer
 * @see Model
 * @author Michael Murray
 */
public interface Block extends Component, CellularPropagation<PackedCollection>, Setup, LayerFeatures {
	/**
	 * Flag to enable automatic count alignment when reshaping.
	 */
	boolean enableAlignCountReshape = false;

	/**
	 * Returns the expected input shape for this block.
	 *
	 * @return the input shape as a TraversalPolicy
	 */
	TraversalPolicy getInputShape();

	/**
	 * Executes the forward pass with the given input data.
	 *
	 * @param input the input data collection
	 * @return a runnable that executes the forward pass when run
	 */
	default Runnable forward(PackedCollection input) {
		return getForward().push(CollectionFeatures.getInstance().cp(input)).get();
	}

	/**
	 * Creates operations for the forward pass with the given input producer.
	 *
	 * @param input the input data producer
	 * @return a supplier of the forward pass operations
	 */
	default Supplier<Runnable> forward(Producer<PackedCollection> input) {
		return getForward().push(input);
	}

	/**
	 * Adds a reshape operation to this block, returning a new block
	 * that reshapes output to the specified dimensions.
	 *
	 * @param dims the target dimensions
	 * @return a new block with the reshape operation appended
	 * @throws IllegalArgumentException if total size does not match
	 */
	default Block reshape(int... dims) {
		return reshape(new TraversalPolicy(dims));
	}

	/**
	 * Adds a reshape operation to this block with a TraversalPolicy shape.
	 *
	 * @param shape the target shape
	 * @return a new block with the reshape operation appended
	 * @throws IllegalArgumentException if total size does not match
	 */
	default Block reshape(TraversalPolicy shape) {
		if (getOutputShape().getTotalSize() != shape.getTotalSize()) {
			throw new IllegalArgumentException("Cannot reshape " + getOutputShape() + " to " + shape);
		}

		if (enableAlignCountReshape) {
			shape = shape.alignCount(getOutputShape());
		}

		return andThen(reshape(getOutputShape(), shape));
	}

	/**
	 * Adds a scaling operation to this block.
	 *
	 * @param factor the scaling factor to apply
	 * @return a new block with the scale operation appended
	 */
	default Block scale(double factor) {
		return andThen(scale(getOutputShape(), factor));
	}

	/**
	 * Adds an enumerate operation to reshape the output by enumerating
	 * along a specific axis.
	 *
	 * @param depth the traversal depth
	 * @param axis the axis to enumerate
	 * @param len the enumeration length
	 * @param requirements optional compute requirements
	 * @return a new block with the enumerate operation appended
	 */
	default Block enumerate(int depth, int axis, int len, ComputeRequirement... requirements) {
		// TODO  There should be a much more direct way to determine the resulting shape than this
		TraversalPolicy resultShape =
				traverse(depth, c(Input.value(getOutputShape(), 0)))
					.enumerate(axis, len)
						.getShape();
		return andThen(layer("enumerate", getOutputShape(), resultShape,
				in -> traverse(depth, in).enumerate(axis, len),
				requirements));
	}

	default Block enumerate(TraversalPolicy shape, ComputeRequirement... requirements) {
		if (getOutputShape().getTotalSize() % shape.getTotalSize() != 0) {
			throw new IllegalArgumentException();
		}

		TraversalPolicy resultShape = shape
				.prependDimension(getOutputShape().getTotalSize() / shape.getTotalSize());
		return andThen(layer("enumerate", getOutputShape(), resultShape,
				in -> CollectionFeatures.getInstance().enumerate(shape, in),
				requirements));
	}

	/**
	 * Extracts a subset of this block's output at the specified position.
	 *
	 * @param shape the shape of the subset to extract
	 * @param dims the position coordinates for extraction
	 * @return a new block with the subset operation appended
	 * @throws IllegalArgumentException if dimensions don't match
	 */
	default Block subset(TraversalPolicy shape, int... dims) {
		if (getOutputShape().getDimensions() != shape.getDimensions()) {
			throw new IllegalArgumentException("Cannot take a " + shape + " subset of " +
					getOutputShape() + " with different number of dimensions");
		}

		return andThen(subset(shape, position(dims)));
	}

	/**
	 * Transposes the output along the specified axis.
	 *
	 * @param axis the axis to transpose
	 * @param requirements optional compute requirements
	 * @return a new block with the transpose operation appended
	 */
	default Block transpose(int axis, ComputeRequirement... requirements) {
		return enumerate(axis - 1, axis, 1, requirements);
	}

	/**
	 * Permutes the dimensions of this block's output according to the specified order.
	 *
	 * @param order the new dimension ordering (e.g., {1, 0, 2} swaps first two dimensions)
	 * @return a new block with the permute operation appended
	 */
	default Block permute(int... order) {
		TraversalPolicy resultShape = getOutputShape().permute(order).extentShape();
		return andThen(layer("permute", getOutputShape(), resultShape,
				in -> CollectionFeatures.getInstance().permute(in, order)));
	}

	/**
	 * Appends a dense (fully connected) layer with the given weights.
	 *
	 * @param weights the weight matrix for the dense layer
	 * @param requirements optional compute requirements
	 * @return a new block with the dense layer appended
	 */
	default Block andThenDense(PackedCollection weights, ComputeRequirement... requirements) {
		return andThen(dense(weights, requirements));
	}

	/**
	 * Appends a dense (fully connected) layer with the given weights and biases.
	 *
	 * @param weights the weight matrix for the dense layer
	 * @param biases the bias vector for the dense layer
	 * @param requirements optional compute requirements
	 * @return a new block with the dense layer appended
	 */
	default Block andThenDense(PackedCollection weights,
							   PackedCollection biases,
							   ComputeRequirement... requirements) {
		return andThen(dense(weights, biases, requirements));
	}

	/**
	 * Creates a new branch from this block's output.
	 * The returned SequentialBlock can be used to add operations that run
	 * in parallel with the main flow.
	 *
	 * @return a new SequentialBlock representing the branch
	 */
	default SequentialBlock branch() {
		BranchBlock split = new BranchBlock(getOutputShape());
		SequentialBlock branch = split.append(new SequentialBlock(getOutputShape()));
		andThen(split);
		return branch;
	}

	/**
	 * Chains this block with another block, creating a sequential pipeline.
	 * The forward cells are connected in sequence, and the backward cells
	 * are connected in reverse order for backpropagation.
	 *
	 * @param <T> the type of the next block
	 * @param next the block to append to this block
	 * @return the next block (for method chaining)
	 */
	default <T extends Block> Block andThen(T next) {
		// Chain with existing receptor instead of replacing it
		Receptor<PackedCollection> existing = getForward().getReceptor();
		if (existing != null) {
			getForward().setReceptor(Receptor.to(existing, next.getForward()));
		} else {
			getForward().setReceptor(next.getForward());
		}
		next.getBackward().setReceptor(getBackward());
		return next;
	}

	default <T extends Block> Block andThen(Function<TraversalPolicy, T> next) {
		return andThen(next.apply(getOutputShape()));
	}

	default <T extends Receptor<PackedCollection>> T andThen(T next) {
		if (Layer.propagationWarnings)
			warn("andThen(" + next + ") may not support backpropagation");
		// Chain with existing receptor instead of replacing it
		Cell<PackedCollection> forward = getForward();
		Receptor<PackedCollection> existing = forward.getReceptor();
		if (existing != null) {
			forward.setReceptor(Receptor.to(existing, next));
		} else {
			forward.setReceptor(next);
		}
		return next;
	}

	default CollectionReceptor andThen(PackedCollection destination) {
		if (Layer.propagationWarnings)
			warn("andThen(" + destination + ") may not support backpropagation");
		CollectionReceptor r = new CollectionReceptor(destination);
		// Chain with existing receptor instead of replacing it
		Receptor<PackedCollection> existing = getForward().getReceptor();
		if (existing != null) {
			getForward().setReceptor(Receptor.to(existing, r));
		} else {
			getForward().setReceptor(r);
		}
		return r;
	}

	@Override
	default String describe() {
		return getInputShape().toStringDetail() + " -> " + getOutputShape().toStringDetail();
	}
}
