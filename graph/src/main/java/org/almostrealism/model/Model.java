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

package org.almostrealism.model;

import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.profile.OperationProfile;
import io.almostrealism.cycle.Setup;
import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.graph.Cell;
import org.almostrealism.graph.CellularPropagation;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.layers.Learning;
import org.almostrealism.layers.ParameterUpdate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The top-level container for neural network models in the Almost Realism framework.
 * Model provides a high-level API for building, training, and executing neural networks
 * by composing blocks and managing their interconnections.
 *
 * <p>A Model consists of:</p>
 * <ul>
 *   <li>A main sequential block chain for the primary data flow</li>
 *   <li>Optional auxiliary input blocks for multi-input architectures</li>
 *   <li>A parameter update strategy for training</li>
 * </ul>
 *
 * <h2>Building Models</h2>
 * <p>Models are built by adding blocks sequentially:</p>
 * <pre>{@code
 * Model model = new Model(shape(784));
 * model.add(dense(256));
 * model.add(relu(model.getOutputShape()));
 * model.add(dense(10));
 * model.add(softmax());
 * }</pre>
 *
 * <h2>Training</h2>
 * <p>Models support gradient-based training through parameter updates:</p>
 * <pre>{@code
 * Model model = new Model(inputShape, learningRate);
 * CompiledModel compiled = model.compile(true);  // Enable backprop
 *
 * // Training loop
 * for (batch : data) {
 *     PackedCollection<?> output = compiled.forward(input);
 *     PackedCollection<?> gradient = computeGradient(output, target);
 *     compiled.backward(gradient);
 * }
 * }</pre>
 *
 * <h2>Multi-Input Models</h2>
 * <p>For models with multiple inputs (e.g., attention with queries, keys, values):</p>
 * <pre>{@code
 * Model model = new Model(queryShape);
 * model.addInput(keyBlock);
 * model.addInput(valueBlock);
 * model.add(attentionLayer);
 * }</pre>
 *
 * <h2>Compilation</h2>
 * <p>Before execution, models should be compiled for optimal performance:</p>
 * <ul>
 *   <li>{@link #compile()} - Compile with default settings (backprop enabled)</li>
 *   <li>{@link #compile(boolean)} - Control backpropagation support</li>
 *   <li>{@link #compile(OperationProfile)} - Compile with profiling</li>
 * </ul>
 *
 * @see Block
 * @see CompiledModel
 * @see SequentialBlock
 * @author Michael Murray
 */
public class Model implements Setup, Destroyable, CodeFeatures {
	private SequentialBlock blocks;
	private List<Block> inputs;

	private ParameterUpdate<PackedCollection<?>> parameterUpdate;

	/**
	 * Creates a new model with no initial shape.
	 */
	public Model() {
		this(null);
	}

	/**
	 * Creates a new model with the specified input shape and default learning rate.
	 *
	 * @param shape the expected input shape for the model
	 */
	public Model(TraversalPolicy shape) {
		this(shape, 1e-5);
	}

	/**
	 * Creates a new model with the specified input shape and learning rate.
	 *
	 * @param shape the expected input shape for the model
	 * @param learningRate the learning rate for gradient-based optimization
	 */
	public Model(TraversalPolicy shape, double learningRate) {
		this(shape, ParameterUpdate.scaled(CollectionFeatures.getInstance().c(learningRate)));
	}

	/**
	 * Creates a new model with the specified input shape and parameter update strategy.
	 *
	 * @param shape the expected input shape for the model
	 * @param parameterUpdate the strategy for updating model parameters during training
	 */
	public Model(TraversalPolicy shape, ParameterUpdate<PackedCollection<?>> parameterUpdate) {
		this.blocks = new SequentialBlock(shape);
		this.inputs = new ArrayList<>();
		setParameterUpdate(parameterUpdate);
	}

	/**
	 * Sets the parameter update strategy for all blocks in this model.
	 *
	 * @param update the parameter update strategy to use during training
	 */
	public void setParameterUpdate(ParameterUpdate<PackedCollection<?>> update) {
		this.parameterUpdate = update;
		blocks.setParameterUpdate(update);
	}

	/**
	 * Returns all blocks in this model's main sequential chain.
	 *
	 * @return an unmodifiable list of blocks
	 */
	public List<Block> getBlocks() {
		return blocks.getBlocks();
	}

	/**
	 * Adds a block to this model's main sequential chain.
	 *
	 * @param b the block to add
	 * @return this model for method chaining
	 */
	public Model add(Block b) {
		blocks.add(b);
		return this;
	}

	/**
	 * Adds a block to this model using a factory function.
	 * The factory receives the current output shape and produces a block.
	 *
	 * @param block a function that creates a block from the current output shape
	 * @return this model for method chaining
	 */
	public Model add(Function<TraversalPolicy, ? extends Block> block) {
		add(block.apply(blocks.getOutputShape()));
		return this;
	}

	/**
	 * Adds an auxiliary input block for multi-input architectures.
	 * Auxiliary inputs are used for architectures like attention mechanisms
	 * that require multiple separate input streams.
	 *
	 * @param b the auxiliary input block
	 * @return this model for method chaining
	 */
	public Model addInput(Block b) {
		if (b instanceof Learning) {
			((Learning) b).setParameterUpdate(parameterUpdate);
		}

		inputs.add(b);
		return this;
	}

	/**
	 * Creates and adds a new sequential block to this model.
	 *
	 * @return the new sequential block for adding layers
	 */
	public SequentialBlock sequential() {
		SequentialBlock seq = new SequentialBlock(blocks.getOutputShape());
		add(seq);
		return seq;
	}

	/**
	 * Returns all auxiliary input blocks.
	 *
	 * @return an unmodifiable list of input blocks
	 */
	public List<Block> getInputs() {
		return Collections.unmodifiableList(inputs);
	}

	/**
	 * Returns the first block in the main chain.
	 *
	 * @return the first block
	 */
	public Block firstBlock() { return blocks.firstBlock(); }

	/**
	 * Returns the last block in the main chain.
	 *
	 * @return the last block
	 */
	public Block lastBlock() { return blocks.lastBlock(); }

	/**
	 * Returns the input shape expected by this model.
	 *
	 * @return the input shape
	 */
	public TraversalPolicy getInputShape() { return firstBlock().getInputShape(); }

	/**
	 * Returns the output shape produced by this model.
	 *
	 * @return the output shape
	 */
	public TraversalPolicy getOutputShape() { return lastBlock().getOutputShape(); }

	/**
	 * Returns setup operations for initializing all blocks in this model.
	 *
	 * @return a supplier of runnable setup operations
	 */
	@Override
	public Supplier<Runnable> setup() {
		OperationList setup = new OperationList("Model Setup");
		inputs.forEach(b -> setup.add(b.setup()));
		setup.add(blocks.setup());
		return setup;
	}

	/**
	 * Returns all forward cells for the main chain and auxiliary inputs.
	 *
	 * @return a list of forward cells
	 */
	public List<Cell<PackedCollection<?>>> forward() {
		return Stream.concat(Stream.of(
							blocks.getForward()),
							inputs.stream().map(CellularPropagation::getForward))
				.collect(Collectors.toUnmodifiableList());
	}

	/**
	 * Returns the backward cell for gradient propagation.
	 *
	 * @return the backward cell
	 */
	public Cell<PackedCollection<?>> backward() { return blocks.getBackward(); }

	/**
	 * Compiles this model with backpropagation enabled.
	 *
	 * @return a compiled model ready for execution
	 */
	public CompiledModel compile() {
		return CompiledModel.compile(this);
	}

	/**
	 * Compiles this model with backpropagation and profiling.
	 *
	 * @param profile the operation profile for performance monitoring
	 * @return a compiled model ready for execution
	 */
	public CompiledModel compile(OperationProfile profile) {
		return CompiledModel.compile(this, profile);
	}

	/**
	 * Compiles this model with optional backpropagation support.
	 *
	 * @param backprop whether to enable backpropagation
	 * @return a compiled model ready for execution
	 */
	public CompiledModel compile(boolean backprop) {
		return CompiledModel.compile(this, backprop, false, null);
	}

	/**
	 * Compiles this model with optional backpropagation and profiling.
	 *
	 * @param backprop whether to enable backpropagation
	 * @param profile the operation profile for performance monitoring
	 * @return a compiled model ready for execution
	 */
	public CompiledModel compile(boolean backprop, OperationProfile profile) {
		return CompiledModel.compile(this, backprop, false, profile);
	}

	/**
	 * Compiles this model with backpropagation and optional gradient return.
	 *
	 * @param backprop whether to enable backpropagation
	 * @param returnGradient whether to return input gradients from backward pass
	 * @return a compiled model ready for execution
	 */
	public CompiledModel compile(boolean backprop, boolean returnGradient) {
		return CompiledModel.compile(this, backprop, returnGradient, null);
	}

	/**
	 * Compiles this model with full configuration options.
	 *
	 * @param backprop whether to enable backpropagation
	 * @param returnGradient whether to return input gradients from backward pass
	 * @param profile the operation profile for performance monitoring
	 * @return a compiled model ready for execution
	 */
	public CompiledModel compile(boolean backprop, boolean returnGradient, OperationProfile profile) {
		return CompiledModel.compile(this, backprop, returnGradient, profile);
	}

	/**
	 * Destroys this model and releases all resources.
	 * After calling this method, the model should not be used.
	 */
	@Override
	public void destroy() {
		if (blocks != null) {
			blocks.destroy();
			blocks = null;
		}

		if (inputs != null) {
			inputs.forEach(Block::destroy);
			inputs = null;
		}
	}
}
