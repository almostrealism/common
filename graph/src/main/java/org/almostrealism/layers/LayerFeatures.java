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

package org.almostrealism.layers;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.expression.Expression;
import io.almostrealism.cycle.Setup;
import io.almostrealism.relation.Composition;
import io.almostrealism.relation.Countable;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Producer;
import org.almostrealism.Ops;
import org.almostrealism.algebra.MatrixFeatures;
import org.almostrealism.algebra.computations.LoopedWeightedSumComputation;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.Random;
import org.almostrealism.geometry.GeometryFeatures;
import org.almostrealism.graph.Cell;
import org.almostrealism.graph.CellularPropagation;
import org.almostrealism.graph.CollectionReceptor;
import org.almostrealism.graph.Receptor;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.HardwareFeatures;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.model.Block;
import org.almostrealism.model.DefaultBlock;
import org.almostrealism.model.SequentialBlock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A comprehensive factory interface for creating neural network layers.
 * LayerFeatures provides methods for constructing all common layer types used in
 * deep learning, including dense layers, convolutions, normalizations, activations,
 * and more.
 *
 * <p>This interface follows the mix-in pattern and is typically implemented by classes
 * that need access to layer creation functionality. Most layer factory methods return
 * a {@code Function<TraversalPolicy, Block>} or {@code Function<TraversalPolicy, CellularLayer>},
 * allowing layers to be created with flexible input shapes.</p>
 *
 * <h2>Layer Categories</h2>
 *
 * <h3>Linear Layers</h3>
 * <ul>
 *   <li>{@link #dense(int, int)} - Fully connected (dense) layer</li>
 *   <li>{@link #dense(PackedCollection, PackedCollection)} - Dense layer with pre-defined weights</li>
 * </ul>
 *
 * <h3>Convolutional Layers</h3>
 * <ul>
 *   <li>{@link #convolution2d(int, int, int)} - 2D convolution layer</li>
 *   <li>{@link #convolution1d(int, int, int, int, int, int, PackedCollection, PackedCollection)} - 1D convolution</li>
 *   <li>{@link #pool2d(int)} - 2D max pooling layer</li>
 * </ul>
 *
 * <h3>Normalization Layers</h3>
 * <ul>
 *   <li>{@link #norm(int)} - Group normalization</li>
 *   <li>{@link #norm(PackedCollection, PackedCollection)} - Layer normalization with weights</li>
 *   <li>{@link #rmsnorm(PackedCollection)} - RMS normalization</li>
 * </ul>
 *
 * <h3>Activation Functions</h3>
 * <ul>
 *   <li>{@link #relu(TraversalPolicy)} - ReLU activation</li>
 *   <li>{@link #silu()} - SiLU (Swish) activation</li>
 *   <li>{@link #gelu()} - GELU activation</li>
 *   <li>{@link #softmax()} - Softmax activation</li>
 *   <li>{@link #logSoftmax()} - Log-softmax activation</li>
 * </ul>
 *
 * <h3>Shape Manipulation</h3>
 * <ul>
 *   <li>{@link #reshape(TraversalPolicy, TraversalPolicy)} - Reshape layer</li>
 *   <li>{@link #flattened()} - Flatten layer</li>
 *   <li>{@link #subset(TraversalPolicy, TraversalPolicy)} - Subset extraction</li>
 *   <li>{@link #pad(TraversalPolicy, TraversalPolicy, int...)} - Padding layer</li>
 * </ul>
 *
 * <h3>Composition Operations</h3>
 * <ul>
 *   <li>{@link #residual(Block)} - Residual connection</li>
 *   <li>{@link #accum(TraversalPolicy, CellularPropagation)} - Element-wise addition</li>
 *   <li>{@link #product(CellularPropagation)} - Element-wise multiplication</li>
 *   <li>{@link #concat(int, Block)} - Concatenation along an axis</li>
 *   <li>{@link #compose(String, TraversalPolicy, Block, TraversalPolicy, io.almostrealism.relation.Composition)} - General composition</li>
 * </ul>
 *
 * <h2>Usage Patterns</h2>
 *
 * <h3>Function-Based Layer Creation</h3>
 * <p>Most methods return functions that take input shape and produce layers:</p>
 * <pre>{@code
 * Function<TraversalPolicy, CellularLayer> denseFactory = dense(256);
 * CellularLayer layer = denseFactory.apply(inputShape);
 * }</pre>
 *
 * <h3>Using with Model</h3>
 * <p>Layers integrate seamlessly with the Model class:</p>
 * <pre>{@code
 * Model model = new Model(shape(784));
 * model.add(dense(256));
 * model.add(relu(model.getOutputShape()));
 * model.add(dense(10));
 * model.add(softmax());
 * }</pre>
 *
 * <h3>Pre-trained Weights</h3>
 * <p>Layers can be created with pre-defined weights:</p>
 * <pre>{@code
 * PackedCollection weights = stateDict.get("layer.weight");
 * PackedCollection biases = stateDict.get("layer.bias");
 * CellularLayer layer = dense(weights, biases).apply(inputShape);
 * }</pre>
 *
 * @see CellularLayer
 * @see Block
 * @see org.almostrealism.model.Model
 * @author Michael Murray
 */
public interface LayerFeatures extends MatrixFeatures, GeometryFeatures, ConsoleFeatures {

	boolean allowNonComposites = false;
	boolean enableWeightedSum = true;

	boolean enableIgnoreZero = true;
	boolean enableLogStability = true;

	Console console = CollectionFeatures.console.child();

	/**
	 * Checks if two shapes are compatible for layer output.
	 * Compatible means: same total size and same effective dimensions
	 * (ignoring traversal axis differences).
	 *
	 * <p>This method is used during shape validation to determine if
	 * a layer's computed output shape matches its declared output shape.</p>
	 *
	 * @param actual The actual shape produced by the layer's operator
	 * @param expected The declared output shape of the layer
	 * @return true if shapes are compatible, false otherwise
	 * @see #validateFactorShape(String, TraversalPolicy, TraversalPolicy, Factor)
	 */
	default boolean isShapeCompatible(TraversalPolicy actual, TraversalPolicy expected) {
		// Must have same total size
		if (actual.getTotalSize() != expected.getTotalSize()) {
			return false;
		}

		// dimensions must match exactly (ignoring traversal axis)
		return actual.equalsIgnoreAxis(expected);
	}

	/**
	 * Validates that a Factor produces output with the expected shape.
	 * This method applies the operator to a placeholder producer with the input shape
	 * and verifies the result has the expected output shape.
	 *
	 * <p>This validation is used to catch shape mismatches at layer creation time rather than at runtime.</p>
	 *
	 * @param name Layer name for error messages
	 * @param inputShape Expected input shape
	 * @param outputShape Expected output shape
	 * @param operator The Factor to validate
	 * @throws IllegalArgumentException if the operator produces an incompatible shape
	 * @see #isShapeCompatible(TraversalPolicy, TraversalPolicy)
	 */
	default void validateFactorShape(String name,
									 TraversalPolicy inputShape,
									 TraversalPolicy outputShape,
									 Factor<PackedCollection> operator) {
		// Create a dynamic producer with the input shape that does nothing
		// This avoids allocating actual memory for shape validation
		Producer<PackedCollection> testInput = func(inputShape, args -> null);

		// Apply the operator using getResultant
		Producer<PackedCollection> result = operator.getResultant(testInput);

		// Extract the result shape
		TraversalPolicy actualShape = shape(result);

		// Validate
		if (!isShapeCompatible(actualShape, outputShape)) {
			throw new IllegalArgumentException(
					"Layer '" + name + "' operator produces shape " + actualShape +
							" but declared output shape is " + outputShape);
		}
	}

	@Deprecated
	default CellularLayer layer(String name, TraversalPolicy inputShape, TraversalPolicy outputShape,
								Cell<PackedCollection> forward, BackPropagation backward,
								ComputeRequirement... requirements) {
		return layer(name, inputShape, outputShape, forward, backward,
				Collections.emptyList(), new OperationList(), requirements);
	}

	default Function<TraversalPolicy, Block> monitor(Consumer<PackedCollection> consumer) {
		return layer(Cell.of((in, next) -> {
				OperationList op = new OperationList("Monitor Layer");
				op.add(() -> {
					Evaluable<PackedCollection> eval = in.get();

					return () -> consumer.accept(eval.evaluate());
				});
				op.add(next.push(in));
				return op;
			}), Cell.of((in, next) -> next.push(in)));
	}

	default Function<TraversalPolicy, Block> layer(Factor<PackedCollection> forward,
												   Factor<PackedCollection> backward) {
		return layer(Cell.of(forward), Cell.of(backward));
	}

	default Function<TraversalPolicy, Block> layer(Cell<PackedCollection> forward,
												   Cell<PackedCollection> backward) {
		return shape -> new DefaultBlock(shape, shape, forward, backward);
	}

	default Function<TraversalPolicy, CellularLayer> layer(String name,
														   Factor<PackedCollection> operator,
														   ComputeRequirement... requirements) {
		return shape -> layer(name, shape, operator, requirements);
	}

	default CellularLayer layer(String name, TraversalPolicy shape,
								Factor<PackedCollection> operator,
								ComputeRequirement... requirements) {
		return layer(name, shape, shape, operator, requirements);
	}

	default CellularLayer layer(String name, TraversalPolicy inputShape, TraversalPolicy outputShape,
								Factor<PackedCollection> operator,
								ComputeRequirement... requirements) {
		return layer(name, inputShape, outputShape, operator, Collections.emptyList(), requirements);
	}

	default CellularLayer layer(String name, TraversalPolicy inputShape, TraversalPolicy outputShape,
								Factor<PackedCollection> operator,
								List<PackedCollection> weights,
								ComputeRequirement... requirements) {
		return layer(name, inputShape, outputShape, operator, weights, new OperationList(), requirements);
	}

	/**
	 * Creates a cellular layer with shape validation.
	 *
	 * <p>This method validates that the operator produces the expected output shape before creating
	 * the layer. If the operator produces an incompatible shape, an {@link IllegalArgumentException}
	 * is thrown at layer creation time rather than at runtime.</p>
	 *
	 * <p><b>Important:</b> Each layer's operator is responsible for producing output that matches
	 * the declared output shape. If internal computations produce different shapes (e.g., matmul
	 * conventions), the operator must include an explicit reshape to the declared output shape.</p>
	 *
	 * @param name Layer name for identification and error messages
	 * @param inputShape Expected input shape for the layer
	 * @param outputShape Expected output shape for the layer
	 * @param operator The transformation to apply (must produce outputShape from inputShape)
	 * @param weights Learnable parameters for this layer
	 * @param setup Initialization operation for the layer
	 * @param requirements Optional compute requirements
	 * @return A new CellularLayer with the validated operator
	 * @throws IllegalArgumentException if the operator produces an incompatible shape
	 * @see #validateFactorShape(String, TraversalPolicy, TraversalPolicy, Factor)
	 */
	default CellularLayer layer(String name, TraversalPolicy inputShape, TraversalPolicy outputShape,
								Factor<PackedCollection> operator,
								List<PackedCollection> weights,
								Supplier<Runnable> setup,
								ComputeRequirement... requirements) {
		validateFactorShape(name, inputShape, outputShape, operator);

		return layer(name, inputShape, outputShape, Cell.of(operator),
				DefaultGradientPropagation.create(name, operator, weights.stream().map(this::cp)),
				weights, setup, requirements);
	}

	default CellularLayer layer(String name, TraversalPolicy inputShape, TraversalPolicy outputShape,
								Cell<PackedCollection> forward, BackPropagation backward,
								List<PackedCollection> weights, Supplier<Runnable> setup,
								ComputeRequirement... requirements) {
		BackPropagationCell backwardCell = new BackPropagationCell(name, backward);
		DefaultCellularLayer layer = new DefaultCellularLayer(name, outputShape, forward, backwardCell, weights, setup);
		if (requirements.length > 0) layer.setComputeRequirements(List.of(requirements));

		layer.init(inputShape, Layer.ioTracking, true);
		if (HardwareFeatures.outputMonitoring)
			layer.setMonitor(new MonitorReceptor(
					name, inputShape, outputShape,
					weights.toArray(PackedCollection[]::new)));

		backwardCell.setForwardInput(layer.getInput());
		return layer;
	}

	default Function<TraversalPolicy, CellularLayer> compose(String name,
															 Block aux,
															 Composition<PackedCollection> operator,
															 ComputeRequirement... requirements) {
		return shape -> compose(name, shape, aux.getOutputShape(), aux, operator, requirements);
	}

	default Function<TraversalPolicy, CellularLayer> compose(String name,
															 Block aux,
															 TraversalPolicy outputShape,
															 Composition<PackedCollection> operator,
															 ComputeRequirement... requirements) {
		return shape -> compose(name, shape, aux, outputShape, operator, requirements);
	}

	default CellularLayer compose(String name,
								  TraversalPolicy inputShape,
								  Block aux, TraversalPolicy outputShape,
								  Composition<PackedCollection> operator,
								  ComputeRequirement... requirements) {
		return compose(name, inputShape, aux.getOutputShape(), outputShape, aux, operator, requirements);
	}

	default CellularLayer compose(String name,
								  TraversalPolicy shape,
								  CellularPropagation<PackedCollection> aux,
								  Composition<PackedCollection> operator,
								  ComputeRequirement... requirements) {
		return compose(name, shape, shape, aux, operator, requirements);
	}

	default CellularLayer compose(String name,
								  TraversalPolicy shape,
								  TraversalPolicy auxShape,
								  CellularPropagation<PackedCollection> aux,
								  Composition<PackedCollection> operator,
								  ComputeRequirement... requirements) {
		return compose(name, shape, auxShape, shape, aux, operator, requirements);
	}

	default CellularLayer compose(String name,
								  TraversalPolicy inputShape,
								  TraversalPolicy auxShape,
								  TraversalPolicy outputShape,
								  CellularPropagation<PackedCollection> aux,
								  Composition<PackedCollection> operator,
								  ComputeRequirement... requirements) {
		PackedCollection auxInput = Layer.ioTracking ? new PackedCollection(auxShape) : null;

		// Capture the input coming in via aux, storing
		// the actual value (if it is necessary)
		Cell<PackedCollection> auxExit = Cell.of((in, next) -> {
			if (auxInput == null) {
				return next.push(in);
			} else {
				OperationList op = new OperationList(name + " composed layer (Entry)");
				op.add(into(name + " composed layer (Input Record)", in,
						p(auxInput), DefaultCellularLayer.enableMemoryDataCopy));
				op.add(next.push(p(auxInput)));
				return op;
			}
		});
		aux.getForward().setReceptor(auxExit);

		// Capture the result intended as input for the composition
		Cell.CaptureReceptor<PackedCollection> auxReceptor = new Cell.CaptureReceptor<>();
		auxExit.setReceptor(auxReceptor);

		Supplier<Runnable> setup = new OperationList();
		if (aux instanceof Setup) {
			setup = ((Setup) aux).setup();
		}

		// Create a layer that composes its input with whatever was received for aux
		DefaultCellularLayer layer = new DefaultCellularLayer(name, outputShape,
				Cell.of((input, next) -> next == null ? new OperationList() :
						next.push(operator.compose(input, auxReceptor.getReceipt()))),
				null, Collections.emptyList(), setup);
		if (requirements.length > 0) layer.setComputeRequirements(List.of(requirements));

		layer.init(inputShape, Layer.ioTracking, true);

		// Create gradient propagation for the main input
		String mainName = name + " main";
		BackPropagationCell mainBackward = new BackPropagationCell(mainName,
				DefaultGradientPropagation.create(mainName, in -> operator.compose(in, p(auxInput))));
		mainBackward.setForwardInput(layer.getInput());

		// Create gradient propagation for the aux input
		// and direct its output to the aux backward Cell
		String auxName = name + " aux";
		BackPropagationCell auxBackward = new BackPropagationCell(auxName,
				DefaultGradientPropagation.create(auxName, in -> operator.compose(p(layer.getInput()), in)));
		auxBackward.setForwardInput(auxInput);
		auxBackward.setReceptor(aux.getBackward());

		// Combine both backpropagation steps and attach the result to the layer
		layer.setBackward(new LearningCell() {
			@Override
			public void setParameterUpdate(ParameterUpdate<PackedCollection> update) {
				if (aux instanceof Learning) {
					((Learning) aux).setParameterUpdate(update);
				}
			}

			@Override
			public Supplier<Runnable> push(Producer<PackedCollection> input) {
				OperationList op = new OperationList(name + " Composed Backward");
				op.add(auxBackward.push(input));
				op.add(mainBackward.push(input));
				return op;
			}

			@Override
			public void setReceptor(Receptor<PackedCollection> r) {
				mainBackward.setReceptor(r);
			}
		});
		return layer;
	}

	/**
	 * Creates an operation that copies data from one producer to another with strict shape validation.
	 *
	 * <p>This method enforces that the input and output shapes match exactly (ignoring traversal axis).
	 * If shapes do not match, an {@link IllegalArgumentException} is thrown immediately rather than
	 * allowing silent reshaping. This strict enforcement ensures shape bugs are caught at layer
	 * creation time rather than causing subtle numerical errors at runtime.</p>
	 *
	 * @param name Operation name for error messages
	 * @param in The source producer
	 * @param out The destination producer
	 * @param copy If true and count is 1, uses memory copy; otherwise uses assignment
	 * @param requirements Optional compute requirements for the operation
	 * @return A supplier that produces the copy operation
	 * @throws IllegalArgumentException if shapes do not match
	 * @see #isShapeCompatible(TraversalPolicy, TraversalPolicy)
	 */
	default Supplier<Runnable> into(String name,
								 Producer<PackedCollection> in, Producer<PackedCollection> out,
								 boolean copy,
								 ComputeRequirement... requirements) {
		return into(name, in, out, copy, requirements.length > 0 ? List.of(requirements) : null);
	}

	/**
	 * Creates an operation that copies data from one producer to another with strict shape validation.
	 *
	 * <p>This method enforces that the input and output shapes match exactly (ignoring traversal axis).
	 * If shapes do not match, an {@link IllegalArgumentException} is thrown immediately rather than
	 * allowing silent reshaping. This strict enforcement ensures shape bugs are caught at layer
	 * creation time rather than causing subtle numerical errors at runtime.</p>
	 *
	 * @param name Operation name for error messages
	 * @param in The source producer
	 * @param out The destination producer
	 * @param copy If true and count is 1, uses memory copy; otherwise uses assignment
	 * @param requirements Optional list of compute requirements for the operation
	 * @return A supplier that produces the copy operation
	 * @throws IllegalArgumentException if shapes do not match
	 * @see #isShapeCompatible(TraversalPolicy, TraversalPolicy)
	 */
	default Supplier<Runnable> into(String name,
								 Producer<PackedCollection> in, Producer<PackedCollection> out,
								 boolean copy,
								 List<ComputeRequirement> requirements) {
		TraversalPolicy shape = shape(in);
		TraversalPolicy outShape = shape(out);

		OperationList op = new OperationList(name);
		op.setComputeRequirements(requirements);

		if (!copy || shape.getCountLong() > 1) {
			int axis = shape.alignCount(Countable.countLong(in)).getTraversalAxis();

			if (shape.equalsIgnoreAxis(outShape)) {
				op.add(a(name, traverse(axis, out), traverse(axis, in)));
			} else {
				// Fail fast on shape mismatch
				throw new IllegalArgumentException(
						"Shape mismatch in '" + name + "': " +
								shape + " does not match " + outShape);
			}
		} else {
			if (!DefaultCellularLayer.enableMemoryDataCopy)
				warn("Using MemoryDataCopy instead of Assignment for " + name);
			op.add(Ops.o().copy(name, in, out, shape.getTotalSize()));
		}

		return op;
	}

	default CollectionReceptor into(PackedCollection dest) {
		return new CollectionReceptor(dest);
	}

	default CollectionReceptor into(PackedCollection dest, Producer<PackedCollection> pos) {
		return new CollectionReceptor(dest, pos);
	}

	default Function<TraversalPolicy, Block> flattened() {
		return flattened(true);
	}

	default Function<TraversalPolicy, Block> flattened(boolean preserveCount) {
		return shape -> {
			TraversalPolicy outputShape = shape.flatten(preserveCount);
			return new DefaultBlock(shape, outputShape,
					Cell.of((in, next) -> next.push(reshape(outputShape, in))),
					Cell.of((in, next) -> next.push(reshape(shape, in))));
		};
	}

	default Block reshape(TraversalPolicy inputShape, TraversalPolicy outputShape) {
		if (inputShape.getTotalSize() != outputShape.getTotalSize()) {
			throw new IllegalArgumentException("Cannot reshape " + inputShape + " to " + outputShape);
		}

		return new DefaultBlock(inputShape, outputShape,
				Cell.of((in, next) -> next.push(reshape(outputShape, in))),
				Cell.of((in, next) -> next.push(reshape(inputShape, in))));
	}

	/**
	 * Creates a function that produces a Block for subset extraction operations using position from TraversalPolicy.
	 * This method provides a functional interface for creating subset blocks where the position coordinates
	 * are derived from a TraversalPolicy's extent.
	 *
	 * @param subsetShape The shape of the extracted subset
	 * @param pos The TraversalPolicy whose extent provides position coordinates
	 * @return A function that takes input shape and returns a Block for subset operations
	 * 
	 * @see #subset(TraversalPolicy, TraversalPolicy, int...)
	 */
	default Function<TraversalPolicy, Block> subset(TraversalPolicy subsetShape, TraversalPolicy pos) {
		return inputShape -> subset(inputShape, subsetShape, pos.extent());
	}

	/**
	 * Creates a Block that performs subset extraction in the forward pass and padding in the backward pass.
	 * This is commonly used in neural network architectures where you need to crop data in one direction
	 * and pad it back in the reverse direction during backpropagation.
	 *
	 * <p>The forward operation extracts a subset from the input at the specified position,
	 * while the backward operation pads the gradient back to the original input size.</p>
	 *
	 * @param inputShape The shape of the input data
	 * @param subsetShape The shape of the extracted subset
	 * @param pos The position coordinates where to extract the subset from
	 * @return A Block that performs subset extraction forward and padding backward
	 * 
	 * @see #subset(TraversalPolicy, Producer, int...)
	 * @see CollectionFeatures#subset
	 */
	default Block subset(TraversalPolicy inputShape, TraversalPolicy subsetShape, int... pos) {
		if (inputShape.getDimensions() != subsetShape.getDimensions()) {
			throw new IllegalArgumentException("Cannot take a " + subsetShape + " subset of " +
					inputShape + " with different number of dimensions");
		} else if (subsetShape.getDimensions() != pos.length) {
			throw new IllegalArgumentException("Subset shape " + subsetShape +
					" does not match position (" + pos.length + " dimensions)");
		}

		return new DefaultBlock(inputShape, subsetShape,
				Cell.of((in, next) ->
						next.push(subset(subsetShape, in, pos))),
				Cell.of((in, next) ->
						next.push(pad(inputShape, new TraversalPolicy(true, pos), in))));
	}

	default Block pad(TraversalPolicy inputShape, TraversalPolicy paddedShape, int... pos) {
			return new DefaultBlock(inputShape, paddedShape,
				Cell.of((in, next) ->
						next.push(pad(paddedShape, in, pos))),
				Cell.of((in, next) ->
						next.push(subset(inputShape, in, pos))));
	}

	/**
	 * Creates a 1D convolution block with kernel size 1 (pointwise convolution).
	 * This is a convenience method that delegates to the full convolution1d with stride=1.
	 *
	 * @param batchSize Batch size
	 * @param inputChannels Number of input channels
	 * @param outputChannels Number of output channels (filters)
	 * @param seqLength Sequence length
	 * @param kernelSize Must be 1 for this overload
	 * @param padding Must be 0 for this overload
	 * @param weights Weight tensor with shape [outputChannels, inputChannels, kernelSize]
	 * @param bias Optional bias tensor with shape [outputChannels], may be null
	 * @return Block performing the 1D convolution
	 */
	default Block convolution1d(int batchSize, int inputChannels, int outputChannels,
								int seqLength, int kernelSize, int padding,
								PackedCollection weights, PackedCollection bias) {
		return convolution1d(batchSize, inputChannels, outputChannels, seqLength,
							kernelSize, 1, padding, weights, bias);
	}

	/**
	 * Creates a 1D convolution block with arbitrary kernel size, stride, and padding.
	 *
	 * <p>Implements the standard 1D convolution operation commonly used in audio processing
	 * and sequence modeling. The output length is computed as:
	 * out_length = (seq_length + 2*padding - kernel_size) / stride + 1</p>
	 *
	 * @param batchSize Batch size
	 * @param inputChannels Number of input channels
	 * @param outputChannels Number of output channels (filters)
	 * @param seqLength Input sequence length
	 * @param kernelSize Size of the convolution kernel
	 * @param stride Stride of the convolution (for downsampling, use stride &gt; 1)
	 * @param padding Zero-padding added to both sides of the input
	 * @param weights Weight tensor with shape [outputChannels, inputChannels, kernelSize]
	 * @param bias Optional bias tensor with shape [outputChannels], may be null
	 * @param requirements Optional compute requirements
	 * @return Block performing the 1D convolution
	 */
	default Block convolution1d(int batchSize, int inputChannels, int outputChannels,
								int seqLength, int kernelSize, int stride, int padding,
								PackedCollection weights, PackedCollection bias,
								ComputeRequirement... requirements) {
		// For kernel size 1 and stride 1, use optimized pointwise implementation
		if (kernelSize == 1 && stride == 1 && padding == 0) {
			weights = weights.reshape(weights.getShape().trim());
			return new SequentialBlock(shape(batchSize, inputChannels, seqLength))
					.enumerate(1, 2, 1)
					.reshape(batchSize * seqLength, inputChannels)
					.andThenDense(weights, bias)
					.reshape(batchSize, seqLength, outputChannels)
					.enumerate(1, 2, 1)
					.reshape(batchSize, outputChannels, seqLength);
		}

		// Calculate output length
		int paddedLength = seqLength + 2 * padding;
		int outLength = (paddedLength - kernelSize) / stride + 1;

		TraversalPolicy inputShape = shape(batchSize, inputChannels, seqLength);
		TraversalPolicy outputShape = shape(batchSize, outputChannels, outLength);
		TraversalPolicy filterShape = shape(outputChannels, inputChannels, kernelSize);

		// Ensure weights have correct shape
		if (weights.getShape().getTotalSize() != filterShape.getTotalSize()) {
			throw new IllegalArgumentException("Weight shape mismatch: expected " +
					filterShape + " but got " + weights.getShape());
		}
		PackedCollection filters = weights.reshape(filterShape);

		Factor<PackedCollection> operator = input -> {
			CollectionProducer in = c(input);

			// Apply padding if needed
			if (padding > 0) {
				in = pad(shape(batchSize, inputChannels, paddedLength), in, 0, 0, padding);
			}

			// Reshape for convolution: (batch, 1, channels, paddedLength) - 4D
			CollectionProducer conv = in.reshape(-1, 1, inputChannels, paddedLength);
			CollectionProducer filter = cp(filters.reshape(1, outputChannels, inputChannels, kernelSize));

			// Define positions for weighted sum
			// Use batch from reshaped producer to match conv2d's pattern
			int bs = conv.getShape().length(0);
			TraversalPolicy resultShape = shape(bs, outputChannels, 1, outLength);
			TraversalPolicy inputPositions = resultShape
					.withRate(1, 1, outputChannels)
					.withRate(2, inputChannels, 1)
					.withRate(3, stride, 1);  // Add stride rate for position variation
			TraversalPolicy filterPositions = resultShape
					.withRate(0, 1, bs)
					.withRate(2, inputChannels, 1)
					.withRate(3, kernelSize, outLength);
			TraversalPolicy groupShape = shape(1, 1, inputChannels, kernelSize);

			CollectionProducer result = weightedSum("conv1dFilter",
					inputPositions, filterPositions,
					groupShape, conv, filter);

			// Add bias if provided
			if (bias != null) {
				int t = outLength;
				result = result.reshape(bs, outputChannels, t)
						.add(cp(bias).repeat(bs).traverse(2).repeat(t));
			}

			return result.reshape(-1, outputChannels, outLength).traverseEach();
		};

		return layer("conv1d", inputShape.traverse(1), outputShape.traverse(1),
					operator, bias != null ? List.of(filters, bias) : List.of(filters),
					new OperationList(), requirements);
	}

	/**
	 * Creates a 1D transposed convolution (deconvolution) block for upsampling.
	 *
	 * <p>Transposed convolution is the gradient operation of a normal convolution,
	 * commonly used in decoder networks for upsampling. The output length is computed as:
	 * {@code out_length = (seq_length - 1) * stride - 2 * padding + kernel_size}</p>
	 *
	 * <p>The implementation works by upsampling the input (inserting zeros between elements),
	 * padding for convolution boundaries, then performing standard convolution with the
	 * transposed weight layout. Specifically:</p>
	 * <ol>
	 *   <li>Upsample input by placing each element in a cell of size {@code stride},
	 *       with zeros filling the remaining positions</li>
	 *   <li>Trim to the correct expanded length: {@code (seqLength - 1) * stride + 1}</li>
	 *   <li>Pad with {@code kernelSize - 1 - padding} zeros on each side</li>
	 *   <li>Perform standard convolution using {@code weightedSum}</li>
	 * </ol>
	 *
	 * @param batchSize Batch size
	 * @param inputChannels Number of input channels
	 * @param outputChannels Number of output channels
	 * @param seqLength Input sequence length
	 * @param kernelSize Size of the convolution kernel
	 * @param stride Stride (upsampling factor)
	 * @param padding Padding to remove from output
	 * @param weights Weight tensor with shape [inputChannels, outputChannels, kernelSize]
	 * @param bias Optional bias tensor with shape [outputChannels], may be null
	 * @param requirements Optional compute requirements
	 * @return Block performing the transposed 1D convolution
	 */
	default Block convTranspose1d(int batchSize, int inputChannels, int outputChannels,
								  int seqLength, int kernelSize, int stride, int padding,
								  PackedCollection weights, PackedCollection bias,
								  ComputeRequirement... requirements) {
		return convTranspose1d(batchSize, inputChannels, outputChannels, seqLength,
				kernelSize, stride, padding, 0, weights, bias, requirements);
	}

	/**
	 * Creates a 1D transposed convolution (deconvolution) layer with output padding.
	 *
	 * @param batchSize Batch size
	 * @param inputChannels Number of input channels
	 * @param outputChannels Number of output channels
	 * @param seqLength Input sequence length
	 * @param kernelSize Size of the convolution kernel
	 * @param stride Stride (upsampling factor)
	 * @param padding Padding to remove from output
	 * @param outputPadding Additional size added to output
	 * @param weights Weight tensor with shape [inputChannels, outputChannels, kernelSize]
	 * @param bias Optional bias tensor with shape [outputChannels], may be null
	 * @param requirements Optional compute requirements
	 * @return Block performing the transposed 1D convolution
	 */
	default Block convTranspose1d(int batchSize, int inputChannels, int outputChannels,
								  int seqLength, int kernelSize, int stride, int padding,
								  int outputPadding, PackedCollection weights, PackedCollection bias,
								  ComputeRequirement... requirements) {
		int outLength = (seqLength - 1) * stride - 2 * padding + kernelSize + outputPadding;

		TraversalPolicy inputShape = shape(batchSize, inputChannels, seqLength);
		TraversalPolicy outputShape = shape(batchSize, outputChannels, outLength);
		TraversalPolicy filterShape = shape(inputChannels, outputChannels, kernelSize);

		if (weights.getShape().getTotalSize() != filterShape.getTotalSize()) {
			throw new IllegalArgumentException("Weight shape mismatch: expected " +
					filterShape + " but got " + weights.getShape());
		}
		PackedCollection filters = weights.reshape(filterShape);

		Factor<PackedCollection> operator = input -> {
			CollectionProducer in = c(input);

			int expandedLength = (seqLength - 1) * stride + 1;
			// For transposed convolution, PyTorch uses: output[o] = sum_k input[t] * weight[k]
			// where (o + padding - k) = t * stride. Combined with kernel flip below, this means
			// leftPadding = kernelSize - 1 - padding places input[0] so that after flipping,
			// output[0] correctly uses weight[padding].
			int leftPadding = kernelSize - 1 - padding;
			// Total length must be at least outLength + kernelSize - 1 to avoid out-of-bounds access
			// when computing output[outLength-1] with kernel position kernelSize-1
			int paddedExpandedLength = outLength + kernelSize - 1;

			// Upsample: place each input element at start of a stride-sized cell, zeros fill the rest
			TraversalPolicy upsampleCellShape = shape(batchSize * inputChannels, seqLength, stride);
			CollectionProducer upsampled = pad(upsampleCellShape,
					in.reshape(batchSize * inputChannels, seqLength, 1),
					0, 0, 0);

			CollectionProducer upsampledFlat = upsampled.reshape(batchSize * inputChannels, seqLength * stride);

			// Trim trailing zeros from upsampling
			if (seqLength * stride > expandedLength) {
				upsampledFlat = upsampledFlat.subset(shape(batchSize * inputChannels, expandedLength), 0, 0);
			}

			// Pad for convolution boundaries - asymmetric: left padding aligns output, right padding prevents overflow
			if (paddedExpandedLength > expandedLength) {
				upsampledFlat = pad(shape(batchSize * inputChannels, paddedExpandedLength),
						upsampledFlat, 0, leftPadding);
			}

			// Reshape for convolution
			// Input is (batch, inputChannels, paddedExpandedLength)
			// Filters are (inputChannels, outputChannels, kernelSize)
			CollectionProducer conv = upsampledFlat.reshape(batchSize, inputChannels, 1, paddedExpandedLength);
			CollectionProducer filter = cp(filters).reshape(1, inputChannels, outputChannels, kernelSize);

			CollectionProducer result;

			// Use LoopedWeightedSumComputation to avoid massive expression trees
			// for large inputChannels. The outer loop (inputChannels) becomes a native
			// for-loop, while the inner sum (kernelSize) is unrolled.
			{
				TraversalPolicy loopedOutputShape = shape(batchSize, outputChannels, outLength).traverseEach();
				TraversalPolicy loopedInputShape = shape(batchSize * inputChannels, paddedExpandedLength);

				final int ocLen = outLength;
				final int icChannels = inputChannels;
				final int ocChannels = outputChannels;
				final int kSize = kernelSize;
				final int paddedLen = paddedExpandedLength;

				LoopedWeightedSumComputation.InputIndexer inputIndexer = (outputIdx, outerIdx, innerIdx) -> {
					// outputIdx is flat index into (batch, outputChannels, outLength)
					// outerIdx is inputChannel (ic)
					// innerIdx is kernel position (k)
					// Input shape: (batch * inputChannels, paddedExpandedLength)
					// We want input[b, ic, o+k] = (b * inputChannels + ic) * paddedLen + (o + k)
					Expression<?> b = outputIdx.divide(ocChannels * ocLen);
					Expression<?> o = outputIdx.imod(ocLen);
					return b.multiply(icChannels).add(outerIdx).multiply(paddedLen).add(o).add(innerIdx);
				};

				LoopedWeightedSumComputation.WeightIndexer weightIndexer = (outputIdx, outerIdx, innerIdx) -> {
					// outerIdx is inputChannel (ic)
					// innerIdx is kernel position (k)
					// Filter shape: (inputChannels, outputChannels, kernelSize)
					// We want filter[ic, oc, flipped_k] where flipped_k = kernelSize - 1 - k
					// This kernel flip is required because PyTorch's transposed convolution
					// uses the kernel in reverse order compared to standard convolution.
					Expression<?> oc = outputIdx.divide(ocLen).imod(ocChannels);
					// Compute (kSize - 1 - innerIdx) by negating innerIdx and adding (kSize - 1)
					Expression<?> flippedK = innerIdx.multiply(-1).add(kSize - 1);
					return outerIdx.multiply(ocChannels * kSize).add(oc.multiply(kSize)).add(flippedK);
				};

				LoopedWeightedSumComputation computation = new LoopedWeightedSumComputation(
						"convTranspose1dLooped",
						loopedOutputShape,
						inputChannels,
						kernelSize,
						loopedInputShape,
						filterShape,
						inputIndexer,
						weightIndexer,
						upsampledFlat,
						cp(filters));

				result = c(computation).reshape(batchSize, outputChannels, outLength);
			}

			if (bias != null) {
				result = result.reshape(batchSize, outputChannels, outLength)
						.add(cp(bias).reshape(1, outputChannels, 1)
								.repeat(batchSize).traverse(2).repeat(outLength));
			}

			return result.traverseEach();
		};

		return layer("convTranspose1d", inputShape.traverseEach(), outputShape.traverseEach(),
					operator, bias != null ? List.of(filters, bias) : List.of(filters),
					new OperationList(), requirements);
	}

	default Function<TraversalPolicy, Block> convolution2d(int inputChannels, int filterCount, int size, int padding,
																   ComputeRequirement... requirements) {
		return convolution2d(inputChannels, filterCount, size, padding, true, requirements);
	}

	default Function<TraversalPolicy, Block> convolution2d(int inputChannels, int filterCount, int size, int padding,
														   boolean bias, ComputeRequirement... requirements) {
		if (inputChannels != 1) {
			return shape -> {
				shape = padDimensions(shape, 2, 4);
				int c = shape.getDimensions() > 2 ? shape.length(1) : 1;
				if (c != inputChannels) {
					throw new IllegalArgumentException();
				}

				return convolution2d(shape, filterCount, size, padding, bias, requirements);
			};
		}

		return shape -> convolution2d(shape, filterCount, size, padding, bias, requirements);
	}

	default Function<TraversalPolicy, Block> convolution2d(int filterCount, int size, ComputeRequirement... requirements) {
		return convolution2d(filterCount, size, 0, requirements);
	}

	default Function<TraversalPolicy, Block> convolution2d(int filterCount, int size, int padding, ComputeRequirement... requirements) {
		return shape -> convolution2d(shape, filterCount, size, padding, true, requirements);
	}

	default Block convolution2d(TraversalPolicy inputShape, int filterCount,
										int size, ComputeRequirement... requirements) {
		return convolution2d(inputShape, filterCount, size, 0, true, requirements);
	}

	default Block convolution2d(TraversalPolicy inputShape, int filterCount,
										int size, boolean bias, ComputeRequirement... requirements) {
		return convolution2d(inputShape, filterCount, size, 0, bias, requirements);
	}

	default Block convolution2d(TraversalPolicy inputShape, int filterCount,
								int size, int padding,
								boolean bias, ComputeRequirement... requirements) {
		inputShape = padDimensions(inputShape, 2, 4);

		if (inputShape.getDimensions() != 4) {
			throw new IllegalArgumentException();
		}

		int h = inputShape.length(2);
		int w = inputShape.length(3);

		int batch = inputShape.length(0);
		int channels = inputShape.length(1);
		int height = h + 2 * padding;
		int width = w + 2 * padding;

		int diff = size - 1;
		int outHeight = height - diff;
		int outWidth = width - diff;
		TraversalPolicy outputShape = shape(batch, filterCount, outHeight, outWidth);

		TraversalPolicy filterShape = shape(filterCount, channels, size, size);
		PackedCollection filters = new PackedCollection(filterShape);

		TraversalPolicy biasShape = shape(filterCount);
		PackedCollection biases = bias ? new PackedCollection(biasShape) : null;

		Factor<PackedCollection> operator = input -> {
			CollectionProducer in = c(input);
			CollectionProducer conv =
					in.reshape(-1, 1, channels, height, width);
			CollectionProducer filter =
					cp(filters.reshape(1, filterCount, channels, size, size));

			int bs = conv.getShape().length(0);

			TraversalPolicy resultShape = shape(batch, filterCount, 1, outHeight, outWidth);
			TraversalPolicy inputPositions = resultShape
					.withRate(1, 1, filterCount)
					.withRate(2, channels, 1);
			TraversalPolicy filterPositions = resultShape
					.withRate(0, 1, batch)
					.withRate(2, channels, 1)
					.withRate(3, size, outHeight)
					.withRate(4, size, outWidth);
			TraversalPolicy groupShape =
					shape(1, 1, channels, size, size);
			CollectionProducer result =
					weightedSum("convolutionFilter",
							inputPositions, filterPositions,
							groupShape, conv, filter);

			if (biases != null) {
				int t = outHeight * outWidth;
				result = result.reshape(bs, filterCount, t)
						.add(cp(biases).repeat(bs).traverse(2).repeat(t));
			}

			return result
					.reshape(-1, filterCount, outHeight, outWidth)
					.traverseEach();
		};

		OperationList setup = new OperationList();
		setup.add(randnInit(filters, 1.0 / (channels * size * size)));
		if (biases != null) {
			setup.add(randnInit(biases, 1.0 / (channels * size * size)));
		}

		TraversalPolicy convInputShape = shape(batch, channels, height, width);
		CellularLayer layer = layer("convolution2d",
								convInputShape.traverse(1), outputShape.traverse(1),
								operator,
								biases == null ? List.of(filters) : List.of(filters, biases),
								setup, requirements);

		if (padding > 0) {
			SequentialBlock block = new SequentialBlock(inputShape);
			block.add(pad(inputShape, convInputShape, 0, 0, padding, padding));
			block.add(layer);
			return block;
		} else {
			return layer;
		}
	}

	default Function<TraversalPolicy, CellularLayer> pool2d(int size) {
		return shape -> pool2d(shape, size);
	}

	default CellularLayer pool2d(TraversalPolicy inputShape, int size, ComputeRequirement... requirements) {
		inputShape = padDimensions(inputShape, 2, 4);

		if (inputShape.getDimensions() != 4) {
			throw new IllegalArgumentException();
		}

		int n = inputShape.length(0);
		int c = inputShape.length(1);
		int h = inputShape.length(2);
		int w = inputShape.length(3);

		TraversalPolicy outputShape =
					shape(n, c, h / size, w / size).alignCount(inputShape);

		Factor<PackedCollection> operator = input ->
				c(input)
						.reshape(-1, c, h, w)
						.traverse(2)
						.enumerate(3, size)
						.enumerate(3, size)
						.max(4)
						.reshape(outputShape.traverseEach());
		return layer("pool2d", inputShape, outputShape, operator, requirements);
	}

	default Function<TraversalPolicy, CellularLayer> dense(int nodes) {
		return shape -> dense(shape.getSize(), nodes).apply(shape);
	}

	default Function<TraversalPolicy, CellularLayer> dense(int size, int nodes) {
		return dense(size, nodes, true);
	}

	default Function<TraversalPolicy, CellularLayer> dense(int size, int nodes, boolean bias) {
		return dense(size, nodes, bias, true);
	}

	default Function<TraversalPolicy, CellularLayer> dense(int size, int nodes,
														   boolean bias, boolean init,
														   ComputeRequirement... requirements) {
		return inputShape -> dense(inputShape, new PackedCollection(shape(nodes, size)), bias, init, requirements);
	}

	default Function<TraversalPolicy, CellularLayer> dense(PackedCollection weights,
														   ComputeRequirement... requirements) {
		return inputShape -> dense(inputShape, weights, false, false, requirements);
	}

	default Function<TraversalPolicy, CellularLayer> dense(PackedCollection weights,
														   PackedCollection biases,
														   ComputeRequirement... requirements) {
		return inputShape -> dense(inputShape, weights, biases, false, requirements);
	}

	default CellularLayer dense(TraversalPolicy inputShape,
								PackedCollection weights,
								boolean bias, boolean init,
								ComputeRequirement... requirements) {
		return dense(inputShape, weights,
				bias ? new PackedCollection(shape(weights.getShape().length(0))) : null,
				init, requirements);
	}

	default CellularLayer dense(TraversalPolicy inputShape,
								PackedCollection weights,
								PackedCollection biases,
								boolean init,
								ComputeRequirement... requirements) {
		TraversalPolicy weightShape = weights.getShape();
		if (weightShape.getDimensions() != 2) {
			throw new IllegalArgumentException();
		}

		int nodes = weightShape.length(0);
		int size = weightShape.length(1);

		// Use a flat shape for matmul
		TraversalPolicy flat = padDimensions(inputShape, 2)
						.flatten(true, size)
						.traverse(1);
		if (flat.length(1) != size) {
			throw new IllegalArgumentException();
		}

		TraversalPolicy outputShape = inputShape
				.replaceDimension(inputShape.getDimensions() - 1, nodes);
		Factor<PackedCollection> operator = input -> {
			input = reshape(flat, input);
			CollectionProducer result = biases != null ?
					matmul(p(weights), input).add(traverse(1, p(biases))) :
					matmul(p(weights), input);
			return result.reshape(outputShape);
		};

		OperationList setup = new OperationList("dense " + size + " init");
		if (init) {
			Random randn = randn(weightShape);
			setup.add(() -> randn::refresh);
			setup.add(a(p(weights.each()), divide(randn.traverseEach(), c(size).traverseAll())));
			if (biases != null) {
				setup.add(a(p(biases.each()), c(0.0)));
			}
		}

		return layer("dense " + size, inputShape.traverseEach(), outputShape.traverseEach(),
				operator, biases != null ? List.of(weights, biases) : List.of(weights),
				setup,
				requirements);
	}

	/**
	 * Creates a softmax layer factory that accepts any input shape.
	 * The output shape will match the input shape.
	 */
	default Function<TraversalPolicy, CellularLayer> softmax() {
		return shape -> softmax(shape);
	}

	default CellularLayer softmax(int size) {
		return softmax(shape(size));
	}

	default CellularLayer softmax(TraversalPolicy shape) {
		return layer("softmax", shape, shape,
				input -> c(input).traverse(1).exp().divide(c(input).traverse(1).exp().traverse(0).sum()));
	}

	default Function<TraversalPolicy, CellularLayer> softmax(boolean subtractMax, ComputeRequirement... requirements) {
		return shape -> softmax(shape, subtractMax, requirements);
	}

	default CellularLayer softmax(TraversalPolicy shape, boolean subtractMax, ComputeRequirement... requirements) {
		if (shape.getDimensions() < 2) {
			throw new IllegalArgumentException();
		}

		int axis = shape.getDimensions() - 1;
		int seqLen = shape.length(axis);
		double eps = 1e-5;

		if (enableLogStability) {
			return layer("softmax2d", shape, shape, input -> {
				CollectionProducer max = traverse(axis, input).max();
				CollectionProducer stable =
						traverse(axis + 1, input).subtract(max.expand(seqLen));
				CollectionProducer logSum =
						stable.exp().traverse(axis).sum().log().expand(seqLen);
				return stable.subtract(logSum).exp();
			}, requirements);
		} else {
			return layer("softmax2d", shape, shape, input -> {
				CollectionProducer o = traverse(axis, input);

				if (subtractMax) {
					if (enableIgnoreZero) {
						o = o.max();
						o = o.expand(seqLen);
						o = traverse(axis + 1, input).subtractIgnoreZero(o);
					} else {
						o = o.max().add(eps);
						o = o.expand(seqLen);
						o = traverse(axis + 1, input).subtract(o);
					}
				}

				o = o.expIgnoreZero().traverse(axis);

				if (subtractMax && enableIgnoreZero) {
					o = o.divide(o.sum().expand(seqLen));
				} else {
					o = o.divide(o.sum().add(eps).expand(seqLen));
				}

				return o;
			}, requirements);
		}
	}

	default Function<TraversalPolicy, CellularLayer> logSoftmax(ComputeRequirement... requirements) {
		return shape -> logSoftmax(shape, requirements);
	}

	default Function<TraversalPolicy, CellularLayer> logSoftmax(int size, ComputeRequirement... requirements) {
		return shape -> {
			shape = padDimensions(shape, 2);
			if (shape.length(1) != size) {
				throw new IllegalArgumentException();
			}

			return logSoftmax(shape, requirements);
		};
	}

	default CellularLayer logSoftmax(TraversalPolicy shape, ComputeRequirement... requirements) {
		shape = padDimensions(shape, 2).traverse(1);

		return layer("logSoftmax", shape, shape, input ->
				c(input).traverse(2).subtract(
							c(input).traverse(2).exp().traverse(1).sum().log()),
				requirements);
	}

	@Deprecated
	default CellularLayer accum(TraversalPolicy shape, Cell<PackedCollection> value, ComputeRequirement... requirements) {
		if (!allowNonComposites) {
			throw new UnsupportedOperationException("accum will not support backpropagation");
		}

		warn("accum will not support backpropagation");
		return layer("accum", shape, shape, Cell.of((input, next) -> {
			Cell.CaptureReceptor<PackedCollection> r = new Cell.CaptureReceptor<>();
			value.setReceptor(r);

			OperationList ops = new OperationList();
			ops.add(value.push(input));
			if (next != null) ops.add(next.push(add(traverseEach(input), traverseEach(r.getReceipt()))));
			return ops;
		}), null, requirements);
	}

	default CellularLayer accum(TraversalPolicy shape,
								  CellularPropagation<PackedCollection> aux,
								  ComputeRequirement... requirements) {
		return compose("accum", shape, aux,
				(input, auxValue) -> add(traverseEach(input), traverseEach(auxValue)),
				requirements);
	}

	default Function<TraversalPolicy, CellularLayer> concat(int axis, Block aux, ComputeRequirement... requirements) {
		return shape -> {
			TraversalPolicy auxShape = aux.getOutputShape();
			if (auxShape.getDimensions() != shape.getDimensions()) {
				throw new IllegalArgumentException();
			}

			return concat(shape,
					shape.replaceDimension(axis, shape.length(axis) + auxShape.length(axis)),
					aux, requirements);
		};
	}

	default CellularLayer concat(TraversalPolicy inputShape,
								 TraversalPolicy outputShape,
								 Block aux,
								 ComputeRequirement... requirements) {
		return compose("concat", inputShape, aux, outputShape,
				(input, auxValue) -> concat(outputShape, input, auxValue),
				requirements);
	}

	@Deprecated
	default CellularLayer product(Producer<PackedCollection> value, ComputeRequirement... requirements) {
		warn("product will not support backpropagation");
		TraversalPolicy shape = shape(value);
		return layer("product", shape, shape,
				input -> multiply(traverseEach(input), traverseEach(value)),
				requirements);
	}

	@Deprecated
	default CellularLayer product(TraversalPolicy inputShape, TraversalPolicy outputShape,
								  Cell<PackedCollection> a, Cell<PackedCollection> b,
								  ComputeRequirement... requirements) {
		warn("product will not support backpropagation");
		return layer("product", inputShape, outputShape, Cell.of((input, next) -> {
			Cell.CaptureReceptor<PackedCollection> ar = new Cell.CaptureReceptor<>();
			a.setReceptor(ar);

			Cell.CaptureReceptor<PackedCollection> br = new Cell.CaptureReceptor<>();
			b.setReceptor(br);

			OperationList ops = new OperationList();
			ops.add(a.push(input));
			ops.add(b.push(input));
			if (next != null)
				ops.add(next.push(multiply(traverseEach(ar.getReceipt()), traverseEach(br.getReceipt()))));
			return ops;
		}), null, requirements);
	}

	default Function<TraversalPolicy, CellularLayer> product(CellularPropagation<PackedCollection> aux,
								  							 ComputeRequirement... requirements) {
		return shape -> product(shape, aux, requirements);
	}

	default CellularLayer product(TraversalPolicy shape,
								  CellularPropagation<PackedCollection> aux,
								  ComputeRequirement... requirements) {
		return compose("product", shape, aux,
					(input, auxValue) -> multiply(traverseEach(input), traverseEach(auxValue)),
				requirements);
	}

	default Function<TraversalPolicy, Block> residual(Function<TraversalPolicy, Block> block) {
		return shape -> residual(block.apply(shape));
	}

	default Block residual(Block block) {
		if (block.getInputShape().getTotalSize() != block.getOutputShape().getTotalSize())
			throw new IllegalArgumentException();

		SequentialBlock residual = new SequentialBlock(block.getInputShape());
		residual.accum(block);
		return residual;
	}

	default Function<TraversalPolicy, CellularLayer> similarity(
			Block k, int c, int s1, int s2) {
		if (k.getOutputShape().getDimensions() != 4 ||
				k.getOutputShape().length(1) != c ||
				k.getOutputShape().length(3) != s2) {
			throw new IllegalArgumentException();
		}

		int batchSize = k.getOutputShape().length(0);
		int dim = k.getOutputShape().length(2);

		TraversalPolicy outputShape = shape(batchSize, c, s1, s2).traverseEach();

		return compose("similarity", k, outputShape, (a, b) -> {
			TraversalPolicy leftShape = shape(batchSize, c, dim, s1, 1);
			TraversalPolicy rightShape = shape(batchSize, c, dim, 1, s2);

			TraversalPolicy resultShape = shape(batchSize, c, 1, s1, s2);
			TraversalPolicy leftPosition = leftShape.repeat(4, s2);
			TraversalPolicy rightPosition = rightShape.repeat(3, s1);
			TraversalPolicy groupShape = shape(1, 1, dim, 1, 1);

			return weightedSum("similarity",
					resultShape,
					leftPosition, rightPosition,
					groupShape, groupShape,
					reshape(leftShape, c(a)),
					reshape(rightShape, c(b)))
					.reshape(outputShape);
		});
	}

	default Function<TraversalPolicy, CellularLayer> weightedSum(
			Block v, int heads, int dimHead, int size) {
		if (v.getOutputShape().getDimensions() != 4 ||
				v.getOutputShape().length(1) != heads ||
				v.getOutputShape().length(2) != dimHead ||
				v.getOutputShape().length(3) != size) {
			throw new IllegalArgumentException();
		}

		int batchSize = v.getOutputShape().length(0);

		if (enableWeightedSum) {
			return null;
		} else {
			return compose("weightedSum", v, shape(batchSize, heads, size, dimHead),
					(a, b) -> {
						CollectionProducer pa = c(a)
								.traverse(4)
								.repeat(dimHead);
						CollectionProducer pb = c(b)
								.traverse(2)
								.enumerate(3, 1)
								.traverse(2)
								.repeat(size);
						return multiply(pa, pb)
								.reshape(batchSize, heads, size, size, dimHead)
								.traverse(3)
								.enumerate(4, 1)
								.sum(4);
					});
		}
	}

	default Function<TraversalPolicy, CellularLayer> scale(double scale, ComputeRequirement... requirements) {
		return shape -> scale(shape, scale, requirements);
	}

	default CellularLayer scale(TraversalPolicy shape, double scale, ComputeRequirement... requirements) {
		return layer("scale", shape, shape, input -> multiply(c(input).each(), c(scale)), requirements);
	}

	default Function<TraversalPolicy, CellularLayer> relu(ComputeRequirement... requirements) {
		return shape -> relu(shape, requirements);
	}

	default CellularLayer relu(TraversalPolicy shape, ComputeRequirement... requirements) {
		return layer("relu", shape, shape, input -> rectify(input), requirements);
	}

	default Function<TraversalPolicy, CellularLayer> silu(ComputeRequirement... requirements) {
		return shape -> silu(shape, requirements);
	}

	default CellularLayer silu(TraversalPolicy shape, ComputeRequirement... requirements) {
		return layer("silu", shape, shape, input -> multiply(traverseEach(input), sigmoid(traverseEach(input))), requirements);
	}

	default Function<TraversalPolicy, CellularLayer> gelu(ComputeRequirement... requirements) {
		return shape -> gelu(shape, requirements);
	}

	default CellularLayer gelu(TraversalPolicy shape, ComputeRequirement... requirements) {
		// 0.5 * x * (1 + math.tanh(sqrt(2 / pi) * (x + 0.044715 * x^3)))
		return layer("gelu", shape, shape, input -> {
			CollectionProducer x = c(input).traverseEach();
			CollectionProducer x3 = pow(x, c(3));
			CollectionProducer tanh =
					tanh(x.add(x3.multiply(c(0.044715)))
						.multiply(c(ROOT_2_BY_PI)));
			return c(0.5).multiply(x).multiply(tanh.add(c(1)));
		}, requirements);
	}

	/**
	 * Calculate the effective width for a normalization layer. This will always be
	 * the total size of weights or biases (if they are present), otherwise it will
	 * simply be the total size of the {@link TraversalPolicy} divided by the desired
	 * number of groups. This size is critical to the distinction between so called
	 * "batch" normalization versus "layer" normalization.
	 *
	 * @param shape
	 * @param groups
	 * @param weights
	 * @param biases
	 * @return
	 */
	default int normSize(TraversalPolicy shape, int groups, PackedCollection weights, PackedCollection biases) {
		int size;

		if (weights != null) {
			return weights.getShape().getTotalSize();
		} else if (biases != null) {
			return biases.getShape().getTotalSize();
		} else {
			return shape.getTotalSize();
		}
	}

	/**
	 * Creates a Snake activation layer factory with default alpha=1.0.
	 * Snake activation is defined as: f(x) = x + (1/alpha) * sin^2(alpha * x)
	 *
	 * <p>Snake is a learnable periodic activation function that provides smoother
	 * gradients than ReLU and is particularly effective for audio synthesis tasks.</p>
	 *
	 * @param requirements Optional compute requirements
	 * @return Function that creates a Snake activation layer for any input shape
	 * @see #snake(double, ComputeRequirement...)
	 */
	default Function<TraversalPolicy, CellularLayer> snake(ComputeRequirement... requirements) {
		return snake(1.0, requirements);
	}

	/**
	 * Creates a Snake activation layer factory with specified alpha parameter.
	 * Snake activation is defined as: f(x) = x + (1/alpha) * sin^2(alpha * x)
	 *
	 * @param alpha The frequency parameter for the sinusoidal component (default 1.0)
	 * @param requirements Optional compute requirements
	 * @return Function that creates a Snake activation layer for any input shape
	 */
	default Function<TraversalPolicy, CellularLayer> snake(double alpha, ComputeRequirement... requirements) {
		return shape -> snake(shape, alpha, requirements);
	}

	/**
	 * Creates a Snake activation layer with specified shape and alpha parameter.
	 * Snake activation is defined as: f(x) = x + (1/alpha) * sin^2(alpha * x)
	 *
	 * @param shape Input and output shape for the layer
	 * @param alpha The frequency parameter for the sinusoidal component
	 * @param requirements Optional compute requirements
	 * @return CellularLayer implementing Snake activation
	 */
	default CellularLayer snake(TraversalPolicy shape, double alpha, ComputeRequirement... requirements) {
		return layer("snake", shape, shape, input -> {
			CollectionProducer x = c(input).traverseEach();
			// f(x) = x + (1/alpha) * sin^2(alpha * x)
			CollectionProducer sinPart = sin(x.multiply(c(alpha)));
			CollectionProducer sinSquared = pow(sinPart, c(2.0));
			return x.add(sinSquared.multiply(c(1.0 / alpha)));
		}, requirements);
	}

	/**
	 * Creates a learnable Snake activation layer factory with per-channel alpha and beta parameters.
	 * Snake activation is defined as: f(x) = x + (1/beta) * sin^2(alpha * x)
	 *
	 * <p>This variant is used by Stable Audio Open / DAC autoencoders where alpha and beta
	 * are learned parameters stored per-channel.</p>
	 *
	 * @param alpha Per-channel frequency parameter, shape (channels,)
	 * @param beta Per-channel scaling parameter, shape (channels,)
	 * @param requirements Optional compute requirements
	 * @return Function that creates a learnable Snake activation layer for any input shape
	 */
	default Function<TraversalPolicy, CellularLayer> snake(PackedCollection alpha,
														   PackedCollection beta,
														   ComputeRequirement... requirements) {
		return shape -> snake(shape, alpha, beta, requirements);
	}

	/**
	 * Creates a learnable Snake activation layer with per-channel alpha and beta parameters.
	 * Snake activation is defined as: f(x) = x + (1/beta) * sin^2(alpha * x)
	 *
	 * <p>For input shape (batch, channels, length), alpha and beta should have shape (channels,).
	 * The activation is applied element-wise with channel-specific parameters.</p>
	 *
	 * <p>This implementation precomputes the broadcasted alpha/beta tensors to avoid
	 * creating large expression trees during compilation, which significantly improves
	 * compilation performance for long sequences.</p>
	 *
	 * @param shape Input shape, typically (batch, channels, length)
	 * @param alpha Per-channel frequency parameter, shape (channels,)
	 * @param beta Per-channel scaling parameter, shape (channels,)
	 * @param requirements Optional compute requirements
	 * @return CellularLayer implementing learnable Snake activation
	 */
	default CellularLayer snake(TraversalPolicy shape, PackedCollection alpha,
								PackedCollection beta, ComputeRequirement... requirements) {
		int channels = shape.length(1);
		int seqLen = shape.length(2);
		int batch = shape.length(0);

		// Precompute broadcasted alpha and beta tensors to avoid creating large
		// expression trees during compilation. This is much faster than using
		// repeat() chains in the expression graph for long sequences.
		PackedCollection alphaExpanded = new PackedCollection(shape(batch, channels, seqLen));
		PackedCollection betaExpanded = new PackedCollection(shape(batch, channels, seqLen));

		// Use direct memory access for efficient broadcasting
		double[] alphaData = alphaExpanded.toArray(0, (int) alphaExpanded.getMemLength());
		double[] betaData = betaExpanded.toArray(0, (int) betaExpanded.getMemLength());

		for (int b = 0; b < batch; b++) {
			for (int c = 0; c < channels; c++) {
				double alphaVal = alpha.valueAt(c);
				double betaVal = beta.valueAt(c);
				int baseIdx = (b * channels + c) * seqLen;
				java.util.Arrays.fill(alphaData, baseIdx, baseIdx + seqLen, alphaVal);
				java.util.Arrays.fill(betaData, baseIdx, baseIdx + seqLen, betaVal);
			}
		}

		// Copy back to PackedCollections
		alphaExpanded.setMem(0, alphaData, 0, alphaData.length);
		betaExpanded.setMem(0, betaData, 0, betaData.length);

		return layer("snakeLearnable", shape, shape, input -> {
			CollectionProducer x = c(input);

			// Use precomputed broadcasted tensors - no repeat() needed in expression graph
			CollectionProducer alphaBC = cp(alphaExpanded);
			CollectionProducer betaBC = cp(betaExpanded);

			// f(x) = x + (1/beta) * sin^2(alpha * x)
			CollectionProducer sinPart = sin(x.multiply(alphaBC));
			CollectionProducer sinSquared = pow(sinPart, c(2.0));
			return x.add(sinSquared.divide(betaBC)).traverseEach();
		}, requirements);
	}

	/**
	 * Creates a Conv1d layer with weight normalization.
	 *
	 * <p>Weight normalization decomposes the weight matrix W into a direction component v
	 * and a magnitude component g: W = g * v / ||v||</p>
	 *
	 * <p>This is used by Stable Audio Open / DAC autoencoders.</p>
	 *
	 * @param batchSize Batch size
	 * @param inChannels Input channels
	 * @param outChannels Output channels
	 * @param seqLength Input sequence length
	 * @param kernelSize Convolution kernel size
	 * @param stride Convolution stride
	 * @param padding Padding amount
	 * @param weightG Magnitude parameter, shape (outChannels, 1, 1)
	 * @param weightV Direction parameter, shape (outChannels, inChannels, kernelSize)
	 * @param bias Bias, shape (outChannels,) or null
	 * @param requirements Optional compute requirements
	 * @return Block implementing weight-normalized Conv1d
	 */
	default Block wnConv1d(int batchSize, int inChannels, int outChannels, int seqLength,
						   int kernelSize, int stride, int padding,
						   PackedCollection weightG, PackedCollection weightV,
						   PackedCollection bias, ComputeRequirement... requirements) {
		// Compute normalized weights: W = g * v / ||v||
		// ||v|| is computed per output channel (norm over inChannels * kernelSize)
		PackedCollection normalizedWeights = computeWeightNormWeights(weightG, weightV,
				outChannels, inChannels, kernelSize);

		// Use standard conv1d with the normalized weights
		return convolution1d(batchSize, inChannels, outChannels, seqLength,
				kernelSize, stride, padding, normalizedWeights, bias, requirements);
	}

	/**
	 * Computes normalized weights from weight normalization parameters.
	 * W = g * v / ||v|| where ||v|| is computed per output channel.
	 */
	default PackedCollection computeWeightNormWeights(PackedCollection weightG,
													  PackedCollection weightV,
													  int outChannels, int inChannels, int kernelSize) {
		// weightG shape: (outChannels, 1, 1)
		// weightV shape: (outChannels, inChannels, kernelSize)
		// Output shape: (outChannels, inChannels, kernelSize)

		int vSize = inChannels * kernelSize;
		PackedCollection result = new PackedCollection(outChannels, inChannels, kernelSize);

		for (int oc = 0; oc < outChannels; oc++) {
			// Compute L2 norm of v for this output channel
			double normSq = 0.0;
			for (int ic = 0; ic < inChannels; ic++) {
				for (int k = 0; k < kernelSize; k++) {
					double v = weightV.toDouble(oc * vSize + ic * kernelSize + k);
					normSq += v * v;
				}
			}
			double norm = Math.sqrt(normSq);

			// Get magnitude g for this output channel
			double g = weightG.toDouble(oc);

			// Compute W = g * v / ||v||
			double scale = g / (norm + 1e-12);
			for (int ic = 0; ic < inChannels; ic++) {
				for (int k = 0; k < kernelSize; k++) {
					int idx = oc * vSize + ic * kernelSize + k;
					double v = weightV.toDouble(idx);
					result.setMem(idx, v * scale);
				}
			}
		}

		return result;
	}

	/**
	 * Creates a ConvTranspose1d layer with weight normalization.
	 *
	 * @param batchSize Batch size
	 * @param inChannels Input channels
	 * @param outChannels Output channels
	 * @param seqLength Input sequence length
	 * @param kernelSize Convolution kernel size
	 * @param stride Convolution stride
	 * @param padding Padding amount
	 * @param weightG Magnitude parameter, shape (inChannels, 1, 1) for transposed
	 * @param weightV Direction parameter, shape (inChannels, outChannels, kernelSize)
	 * @param bias Bias, shape (outChannels,) or null
	 * @param requirements Optional compute requirements
	 * @return Block implementing weight-normalized ConvTranspose1d
	 */
	default Block wnConvTranspose1d(int batchSize, int inChannels, int outChannels, int seqLength,
									int kernelSize, int stride, int padding,
									PackedCollection weightG, PackedCollection weightV,
									PackedCollection bias, ComputeRequirement... requirements) {
		return wnConvTranspose1d(batchSize, inChannels, outChannels, seqLength,
				kernelSize, stride, padding, 0, weightG, weightV, bias, requirements);
	}

	/**
	 * Creates a ConvTranspose1d layer with weight normalization and output padding.
	 *
	 * @param batchSize Batch size
	 * @param inChannels Input channels
	 * @param outChannels Output channels
	 * @param seqLength Input sequence length
	 * @param kernelSize Convolution kernel size
	 * @param stride Convolution stride
	 * @param padding Padding amount
	 * @param outputPadding Additional size added to output
	 * @param weightG Magnitude parameter, shape (inChannels, 1, 1) for transposed
	 * @param weightV Direction parameter, shape (inChannels, outChannels, kernelSize)
	 * @param bias Bias, shape (outChannels,) or null
	 * @param requirements Optional compute requirements
	 * @return Block implementing weight-normalized ConvTranspose1d
	 */
	default Block wnConvTranspose1d(int batchSize, int inChannels, int outChannels, int seqLength,
									int kernelSize, int stride, int padding, int outputPadding,
									PackedCollection weightG, PackedCollection weightV,
									PackedCollection bias, ComputeRequirement... requirements) {
		// For transposed conv, weight shape is (inChannels, outChannels, kernelSize)
		// Normalize over outChannels * kernelSize per input channel
		PackedCollection normalizedWeights = computeWeightNormWeightsTransposed(weightG, weightV,
				inChannels, outChannels, kernelSize);

		return convTranspose1d(batchSize, inChannels, outChannels, seqLength,
				kernelSize, stride, padding, outputPadding, normalizedWeights, bias, requirements);
	}

	/**
	 * Computes normalized weights for transposed convolution.
	 * W = g * v / ||v|| where ||v|| is computed per input channel (first dimension).
	 */
	default PackedCollection computeWeightNormWeightsTransposed(PackedCollection weightG,
																PackedCollection weightV,
																int inChannels, int outChannels, int kernelSize) {
		int vSize = outChannels * kernelSize;
		PackedCollection result = new PackedCollection(inChannels, outChannels, kernelSize);

		for (int ic = 0; ic < inChannels; ic++) {
			// Compute L2 norm of v for this input channel
			double normSq = 0.0;
			for (int oc = 0; oc < outChannels; oc++) {
				for (int k = 0; k < kernelSize; k++) {
					double v = weightV.toDouble(ic * vSize + oc * kernelSize + k);
					normSq += v * v;
				}
			}
			double norm = Math.sqrt(normSq);

			// Get magnitude g for this input channel
			double g = weightG.toDouble(ic);

			// Compute W = g * v / ||v||
			double scale = g / (norm + 1e-12);
			for (int oc = 0; oc < outChannels; oc++) {
				for (int k = 0; k < kernelSize; k++) {
					int idx = ic * vSize + oc * kernelSize + k;
					double v = weightV.toDouble(idx);
					result.setMem(idx, v * scale);
				}
			}
		}

		return result;
	}

	/**
	 * Calculate the effective width for a normalization layer. This will always be
	 * the total size of weights or biases (if they are present), otherwise it will
	 * simply be the total size of the {@link TraversalPolicy} divided by the desired
	 * number of groups. This size is critical to the distinction between so called
	 * "batch" normalization versus "layer" normalization.
	 *
	 * @param shape
	 * @param groups
	 * @param weights
	 * @param biases
	 * @return
	 */
	default int normSize(TraversalPolicy shape, int groups, PackedCollection weights, PackedCollection biases) {
		int size;

		if (weights != null) {
			return weights.getShape().getTotalSize();
		} else if (biases != null) {
			return biases.getShape().getTotalSize();
		} else {
			return shape.getTotalSize();
		}
	}

	default Function<TraversalPolicy, CellularLayer> norm(ComputeRequirement... requirements) {
		return norm(1, requirements);
	}

	default Function<TraversalPolicy, CellularLayer> norm(int groups, ComputeRequirement... requirements) {
		return shape -> norm(shape, groups, requirements);
	}

	default Function<TraversalPolicy, CellularLayer> norm(PackedCollection weights,
														  PackedCollection biases,
														  ComputeRequirement... requirements) {
		return shape -> norm(shape, 1, weights, biases, false, requirements);
	}

	default Function<TraversalPolicy, CellularLayer> norm(PackedCollection weights,
														  PackedCollection biases,
														  double eps,
														  ComputeRequirement... requirements) {
		return shape -> norm(shape, 1, weights, biases, eps, false, requirements);
	}


	default CellularLayer norm(TraversalPolicy shape, int groups, ComputeRequirement... requirements) {
		return norm(shape, groups, true, requirements);
	}

	default CellularLayer norm(TraversalPolicy shape, int groups,
							   boolean trainable, ComputeRequirement... requirements) {
		return norm(shape, normSize(shape, groups, null, null), groups, trainable, requirements);
	}

	default CellularLayer norm(TraversalPolicy shape, int size, int groups,
							   boolean trainable, ComputeRequirement... requirements) {
		return norm(shape, size, groups,
				trainable ? new PackedCollection(size) : null,
				trainable ? new PackedCollection(size) : null,
				true, requirements);
	}

	default CellularLayer norm(int groups,
							   PackedCollection weights,
							   PackedCollection biases,
							   ComputeRequirement... requirements) {
		return norm(groups, weights, biases, false, requirements);
	}

	default CellularLayer norm(int groups, PackedCollection weights, PackedCollection biases,
							   boolean init, ComputeRequirement... requirements) {
		TraversalPolicy shape;

		if (weights != null) {
			shape = shape(weights);
		} else if (biases != null) {
			shape = shape(biases);
		} else {
			throw new IllegalArgumentException();
		}

		return norm(shape, groups, weights, biases, init, requirements);
	}

	default CellularLayer norm(TraversalPolicy shape,
							   PackedCollection weights,
							   PackedCollection biases,
							   ComputeRequirement... requirements) {
		return norm(shape, 1, weights, biases, requirements);
	}

	default CellularLayer norm(TraversalPolicy shape, int groups,
							   PackedCollection weights,
							   PackedCollection biases,
							   ComputeRequirement... requirements) {
		return norm(shape, groups, weights, biases, true, requirements);
	}

	default CellularLayer norm(TraversalPolicy shape, int groups,
							   PackedCollection weights,
							   PackedCollection biases,
							   boolean init,
							   ComputeRequirement... requirements) {
		return norm(shape, normSize(shape, groups, weights, biases),
				groups, weights, biases, init, requirements);
	}

	default CellularLayer norm(TraversalPolicy shape, int size, int groups,
							   PackedCollection weights,
							   PackedCollection biases,
							   boolean init,
							   ComputeRequirement... requirements) {
		return norm(shape, size, groups, weights, biases,
				Hardware.getLocalHardware().epsilon(), init, requirements);
	}

	default CellularLayer norm(TraversalPolicy shape, int groups,
							   PackedCollection weights,
							   PackedCollection biases,
							   double eps, boolean init,
							   ComputeRequirement... requirements) {
		return norm(shape, normSize(shape, groups, weights, biases),
				groups, weights, biases, eps, init, requirements);
	}

	default CellularLayer norm(TraversalPolicy shape,
							   int size, int groups,
							   PackedCollection weights,
							   PackedCollection biases,
							   double eps, boolean init,
							   ComputeRequirement... requirements) {
		if ((weights != null && shape(weights).getTotalSize() != size) ||
				(biases != null && shape(biases).getTotalSize() != size)) {
			throw new IllegalArgumentException();
		}

		if (size % groups != 0) {
			if (shape.getTotalSizeLong() % groups == 0) {
				warn("Group normalization may span across batches");
			} else {
				throw new IllegalArgumentException();
			}
		}

		List<PackedCollection> prop = new ArrayList<>();
		if (weights != null) prop.add(weights);
		if (biases != null) prop.add(biases);

		PackedCollection w = weights == null ? null : weights.flatten();
		PackedCollection b = biases == null ? null : biases.flatten();

		OperationList setup = new OperationList();
		if (init) {
			if (w != null) setup.add(a(p(w.each()), c(1)));
			if (b != null) setup.add(a(p(b.each()), c(0.0)));
		}

		TraversalPolicy outputShape = shape.traverse(1);
		return layer("norm", outputShape, outputShape, input -> {
			CollectionProducer in = c(input).reshape(-1, groups, Math.toIntExact(size / groups));
			CollectionProducer out = in.subtractMean(2).divide(in.variance(2).add(c(eps)).sqrt());
			out = out.reshape(-1, Math.toIntExact(size)).traverse(1);

			if (w != null) out = out.multiply(cp(w));
			if (b != null) out = out.add(cp(b));
			return out.reshape(outputShape.traverseEach());
		}, prop, setup, requirements);
	}

	default Function<TraversalPolicy, CellularLayer> rmsnorm(int size, ComputeRequirement... requirements) {
		return rmsnorm(size, true, requirements);
	}

	default Function<TraversalPolicy, CellularLayer> rmsnorm(int size, boolean bias, ComputeRequirement... requirements) {
		return shape -> rmsnorm(shape,
				new PackedCollection(shape(size)).fill(1.0),
				bias ? new PackedCollection(shape(size)) : null,
				requirements);
	}


	default CellularLayer rmsnorm(PackedCollection weights,
								  ComputeRequirement... requirements) {
		return rmsnorm(weights.getShape(), weights, null, requirements);
	}

	default CellularLayer rmsnorm(PackedCollection weights,
								  PackedCollection biases,
								  ComputeRequirement... requirements) {
		return rmsnorm(weights.getShape(), weights, biases, requirements);
	}

	default CellularLayer rmsnorm(TraversalPolicy shape,
								  PackedCollection weights,
								  ComputeRequirement... requirements) {
		return rmsnorm(shape, weights, null, requirements);
	}

	default CellularLayer rmsnorm(TraversalPolicy shape,
								  PackedCollection weights,
								  PackedCollection biases,
								  ComputeRequirement... requirements) {
		if (weights.getShape().getDimensions() != 1 ||
				(biases != null && biases.getShape().getDimensions() != 1)) {
			throw new IllegalArgumentException();
		}

		int size = weights.getShape().getTotalSize();
		int axis = shape.getDimensions() - 1;

		return layer("rmsnorm", shape, shape, input -> {
			CollectionProducer ss = pow(traverseEach(input), c(2.0)).traverse(axis).sum();
			ss = ss.divide(c(size)).add(c(1e-5));
			ss = c(1.0).divide(ss.pow(c(0.5)));

			if (weights == null) {
				ss = ss.multiply(traverseEach(input));
			} else {
				ss = multiply(traverseEach(cp(weights)), traverseEach(input)).multiply(ss);
			}

			if (biases != null) {
				ss = ss.add(traverseEach(cp(biases)));
			}

			return ss.reshape(shape);
		}, biases != null ? List.of(weights, biases) : List.of(weights), requirements);
	}

	default Supplier<Runnable> randnInit(PackedCollection weights, double scale) {
		OperationList setup = new OperationList();
		Random randn = randn(shape(weights));
		setup.add(() -> randn::refresh);
		setup.add(a(p(weights.each()), multiply(randn.traverseEach(), c(scale).traverse(0))));
		return setup;
	}

	interface LearningCell extends Cell<PackedCollection>, Learning { }
}
