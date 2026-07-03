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
import io.almostrealism.relation.Countable;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Producer;
import org.almostrealism.Ops;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.Random;
import org.almostrealism.graph.Cell;
import org.almostrealism.graph.CollectionReceptor;
import org.almostrealism.hardware.HardwareFeatures;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.io.Console;
import org.almostrealism.model.Block;
import org.almostrealism.model.DefaultBlock;

import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A comprehensive factory interface for creating neural network layers.
 * LayerFeatures provides methods for constructing all common layer types used in
 * deep learning, including dense layers, convolutions, normalizations, and more.
 *
 * <p>This interface follows the mix-in pattern and is typically implemented by classes
 * that need access to layer creation functionality. Most layer factory methods return
 * a {@code Function<TraversalPolicy, Block>} or {@code Function<TraversalPolicy, CellularLayer>},
 * allowing layers to be created with flexible input shapes.</p>
 *
 * <p>Activation functions (ReLU, SiLU, GELU, Softmax, Snake, etc.) are provided by the
 * {@link ActivationFeatures} interface, which this interface extends.</p>
 *
 * <h2>How LayerFeatures Is Organised</h2>
 *
 * <p>{@code LayerFeatures} is a thematic umbrella that brings together several focused
 * collaborator interfaces. The split keeps each file readable and lets domain code
 * extend exactly the families it needs:</p>
 * <ul>
 *   <li>{@link ConvolutionLayerFeatures} — 1-D and 2-D convolutions, transposed convolutions,
 *       2-D max pooling, and weight-normalised variants</li>
 *   <li>{@link NormalizationLayerFeatures} — group normalization and RMS normalization</li>
 *   <li>{@link LayerRoutingFeatures} (subtype) — accumulation, concatenation, residual,
 *       similarity, weighted-sum and other routing constructs that combine multiple sub-blocks</li>
 *   <li>{@link ActivationFeatures} (parent) — ReLU, SiLU, GELU, Softmax, Snake, etc.</li>
 * </ul>
 * <p>{@code LayerFeatures} itself retains the foundational layer-construction primitives:
 * the {@code layer(...)} factories that wire forward and backward cells, shape-validation
 * utilities, copy/{@code into} helpers, dense layer factories, scale, lerp, and the
 * shape-manipulation block factories ({@link #passThrough(TraversalPolicy) passThrough},
 * {@link #flattened()}, {@link #reshape(TraversalPolicy, TraversalPolicy) reshape},
 * {@link #subset(TraversalPolicy, TraversalPolicy, int...) subset},
 * {@link #pad(TraversalPolicy, TraversalPolicy, int...) pad}).</p>
 *
 * <h2>Layer Categories</h2>
 *
 * <h3>Linear Layers</h3>
 * <ul>
 *   <li>{@link #dense(int, int)} - Fully connected (dense) layer</li>
 *   <li>{@code dense(PackedCollection, PackedCollection)} - Dense layer with pre-defined weights</li>
 * </ul>
 *
 * <h3>Convolutional Layers</h3>
 * <ul>
 *   <li>{@code convolution2d(int, int, int)} - 2D convolution layer (see {@link ConvolutionLayerFeatures})</li>
 *   <li>{@code convolution1d(...)} - 1D convolution (see {@link ConvolutionLayerFeatures})</li>
 *   <li>{@code pool2d(int)} - 2D max pooling layer (see {@link ConvolutionLayerFeatures})</li>
 * </ul>
 *
 * <h3>Normalization Layers</h3>
 * <ul>
 *   <li>{@code norm(int)} - Group normalization (see {@link NormalizationLayerFeatures})</li>
 *   <li>{@code norm(PackedCollection, PackedCollection)} - Layer normalization with weights</li>
 *   <li>{@code rmsnorm(PackedCollection)} - RMS normalization (see {@link NormalizationLayerFeatures})</li>
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
 *   <li>{@link LayerRoutingFeatures#residual(Block)} - Residual connection</li>
 *   <li>{@code accum(TraversalPolicy, CellularPropagation)} - Element-wise addition</li>
 *   <li>{@code product(CellularPropagation)} - Element-wise multiplication</li>
 *   <li>{@code concat(int, Block)} - Concatenation along an axis</li>
 *   <li>{@link LayerRoutingFeatures#compose(String, TraversalPolicy, Block, TraversalPolicy, io.almostrealism.relation.Composition, io.almostrealism.compute.ComputeRequirement...)} - General composition</li>
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
 * @see ActivationFeatures
 * @see ConvolutionLayerFeatures
 * @see NormalizationLayerFeatures
 * @see LayerRoutingFeatures
 * @see CellularLayer
 * @see Block
 * @see org.almostrealism.model.Model
 * @author Michael Murray
 */
public interface LayerFeatures extends ConvolutionLayerFeatures, NormalizationLayerFeatures {

	/**
	 * When {@code false} (default), composite operations (e.g., accumulation, product) are always
	 * used. When {@code true}, simpler non-composite paths may be taken when available.
	 */
	boolean allowNonComposites = false;

	/** When {@code true} (default), weighted-sum accumulation blocks are generated for residual paths. */
	boolean enableWeightedSum = true;

	/** Child console used for layer-level diagnostic output. */
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

	/**
	 * Creates a cellular layer from raw forward and backward cells.
	 *
	 * @param name         a human-readable label for the layer
	 * @param inputShape   the expected input shape
	 * @param outputShape  the shape produced by the forward cell
	 * @param forward      the forward-pass cell
	 * @param backward     the backward-pass gradient propagation strategy
	 * @param requirements optional compute requirements
	 * @return the constructed {@link CellularLayer}
	 * @deprecated prefer operator-based factory methods
	 */
	@Deprecated
	default CellularLayer layer(String name, TraversalPolicy inputShape, TraversalPolicy outputShape,
								Cell<PackedCollection> forward, BackPropagation backward,
								ComputeRequirement... requirements) {
		return layer(name, inputShape, outputShape, forward, backward,
				Collections.emptyList(), new OperationList(), requirements);
	}

	/**
	 * Creates a pass-through layer factory that invokes {@code consumer} with each forward-pass value.
	 *
	 * @param consumer the consumer that receives the evaluated output for inspection
	 * @return a function that creates a monitoring block for any input shape
	 */
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

	/**
	 * Creates a shape-independent block factory from forward and backward factors.
	 *
	 * @param forward  the forward-pass differentiable factor
	 * @param backward the backward-pass gradient factor
	 * @return a function that creates a {@link DefaultBlock} for any input shape
	 */
	default Function<TraversalPolicy, Block> layer(Factor<PackedCollection> forward,
												   Factor<PackedCollection> backward) {
		return layer(Cell.of(forward), Cell.of(backward));
	}

	/**
	 * Creates a shape-independent block factory from forward and backward cells.
	 *
	 * @param forward  the forward-pass cell
	 * @param backward the backward-pass gradient cell
	 * @return a function that creates a {@link DefaultBlock} for any input shape
	 */
	default Function<TraversalPolicy, Block> layer(Cell<PackedCollection> forward,
												   Cell<PackedCollection> backward) {
		return shape -> new DefaultBlock(shape, shape, forward, backward);
	}

	/**
	 * Creates a cellular layer factory that accepts any input shape and keeps the same output shape.
	 *
	 * @param name         a human-readable label for the layer
	 * @param operator     the differentiable forward operator
	 * @param requirements optional compute requirements
	 * @return a function that creates the layer for any given shape
	 */
	default Function<TraversalPolicy, CellularLayer> layer(String name,
														   Factor<PackedCollection> operator,
														   ComputeRequirement... requirements) {
		return shape -> layer(name, shape, operator, requirements);
	}

	/**
	 * Creates a cellular layer where the input and output shapes are the same.
	 *
	 * @param name         a human-readable label for the layer
	 * @param shape        the input (and output) shape
	 * @param operator     the differentiable forward operator
	 * @param requirements optional compute requirements
	 * @return the constructed {@link CellularLayer}
	 */
	default CellularLayer layer(String name, TraversalPolicy shape,
								Factor<PackedCollection> operator,
								ComputeRequirement... requirements) {
		return layer(name, shape, shape, operator, requirements);
	}

	/**
	 * Creates a cellular layer with distinct input and output shapes and no learnable weights.
	 *
	 * @param name         a human-readable label for the layer
	 * @param inputShape   the expected input shape
	 * @param outputShape  the shape produced by the operator
	 * @param operator     the differentiable forward operator
	 * @param requirements optional compute requirements
	 * @return the constructed {@link CellularLayer}
	 */
	@Override
	default CellularLayer layer(String name, TraversalPolicy inputShape, TraversalPolicy outputShape,
								Factor<PackedCollection> operator,
								ComputeRequirement... requirements) {
		return layer(name, inputShape, outputShape, operator, Collections.emptyList(), requirements);
	}

	/**
	 * Creates a cellular layer with learnable weights and a no-op setup operation.
	 *
	 * @param name         a human-readable label for the layer
	 * @param inputShape   the expected input shape
	 * @param outputShape  the shape produced by the operator
	 * @param operator     the differentiable forward operator
	 * @param weights      the learnable parameter collections
	 * @param requirements optional compute requirements
	 * @return the constructed {@link CellularLayer}
	 */
	@Override
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
	@Override
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

	/**
	 * Core layer factory that wires a forward cell and a backward propagation strategy into a
	 * fully initialised {@link DefaultCellularLayer}.
	 *
	 * <p>The backward cell is wrapped in a {@link BackPropagationCell}. Input and output tracking
	 * is enabled according to {@link Layer#ioTracking}. If monitoring is enabled, a
	 * {@link MonitorReceptor} is installed on the layer's output.</p>
	 *
	 * @param name         a human-readable label for the layer
	 * @param inputShape   the expected input shape
	 * @param outputShape  the shape produced by the forward cell
	 * @param forward      the forward-pass cell
	 * @param backward     the backward-pass gradient propagation strategy
	 * @param weights      the learnable parameter collections
	 * @param setup        the setup operation to run before the first forward pass
	 * @param requirements optional compute requirements
	 * @return the fully initialised {@link CellularLayer}
	 */
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
				warn("Copy requested for " + name + " even though memory copy recording is disabled");
			op.add(Ops.o().copy(name, in, out, shape.getTotalSize()));
		}

		return op;
	}

	/**
	 * Creates a {@link CollectionReceptor} that writes into the given destination collection.
	 *
	 * @param dest the destination {@link PackedCollection}
	 * @return a receptor that copies incoming values into {@code dest}
	 */
	default CollectionReceptor into(PackedCollection dest) {
		return new CollectionReceptor(dest);
	}

	/**
	 * Creates a {@link CollectionReceptor} that writes into a position within the given destination.
	 *
	 * @param dest the destination {@link PackedCollection}
	 * @param pos  a producer for the offset position within {@code dest}
	 * @return a receptor that copies incoming values into {@code dest} at the given position
	 */
	default CollectionReceptor into(PackedCollection dest, Producer<PackedCollection> pos) {
		return new CollectionReceptor(dest, pos);
	}

	/**
	 * Creates a flatten block factory that preserves the leading count dimension.
	 *
	 * @return a function that creates a flattening block for any input shape
	 */
	default Function<TraversalPolicy, Block> flattened() {
		return flattened(true);
	}

	/**
	 * Creates a flatten block factory.
	 *
	 * @param preserveCount when {@code true}, the leading batch/count dimension is preserved;
	 *                      when {@code false}, all dimensions are collapsed to one
	 * @return a function that creates a flattening block for any input shape
	 */
	default Function<TraversalPolicy, Block> flattened(boolean preserveCount) {
		return shape -> {
			TraversalPolicy outputShape = shape.flatten(preserveCount);
			return new DefaultBlock(shape, outputShape,
					Cell.of((in, next) -> next.push(reshape(outputShape, in))),
					Cell.of((in, next) -> next.push(reshape(shape, in))));
		};
	}

	/**
	 * Creates a pass-through block whose forward and backward cells both forward
	 * their input unchanged.
	 *
	 * <p>The backward pass is a true gradient pass-through (gradient of the identity
	 * function is the identity function), not a no-op — gradients flow through
	 * pass-through blocks unchanged.</p>
	 *
	 * <p>The name {@code passThrough} avoids clashing with
	 * {@link org.almostrealism.algebra.MatrixFeatures#identity(TraversalPolicy)
	 * MatrixFeatures.identity(TraversalPolicy)}, which returns an identity matrix
	 * (a {@link CollectionProducer}) rather than a pass-through {@link Block}.</p>
	 *
	 * @param shape the input (and output) shape of the pass-through
	 * @return a {@link Block} whose forward and backward cells both push their input downstream
	 */
	default Block passThrough(TraversalPolicy shape) {
		return new DefaultBlock(shape, shape,
				Cell.of((in, next) -> next.push(in)),
				Cell.of((in, next) -> next.push(in)));
	}

	/**
	 * Creates a reshape block that reinterprets data with a new shape.
	 *
	 * @param inputShape  the original shape
	 * @param outputShape the target shape; must have the same total size as {@code inputShape}
	 * @return a block whose forward cell reshapes to {@code outputShape} and backward reshapes back
	 * @throws IllegalArgumentException if the total sizes differ
	 */
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

	/**
	 * Creates a padding block that embeds the input at a given position within a larger shape.
	 *
	 * @param inputShape   the shape of the data to embed
	 * @param paddedShape  the target padded shape
	 * @param pos          the offset position within the padded output
	 * @return a block whose forward cell pads to {@code paddedShape} and backward subsets back
	 */
	@Override
	default Block pad(TraversalPolicy inputShape, TraversalPolicy paddedShape, int... pos) {
			return new DefaultBlock(inputShape, paddedShape,
				Cell.of((in, next) ->
						next.push(pad(paddedShape, in, pos))),
				Cell.of((in, next) ->
						next.push(subset(inputShape, in, pos))));
	}

	/**
	 * Creates a dense layer factory using the input shape's size as the node count.
	 *
	 * @param nodes the number of output nodes
	 * @return a function that creates a dense layer for any input shape
	 */
	default Function<TraversalPolicy, CellularLayer> dense(int nodes) {
		return shape -> dense(shape.getSize(), nodes).apply(shape);
	}

	/**
	 * Creates a dense layer factory with a learnable bias and weight initialisation.
	 *
	 * @param size  the number of input features
	 * @param nodes the number of output nodes
	 * @return a function that creates a dense layer for any input shape
	 */
	default Function<TraversalPolicy, CellularLayer> dense(int size, int nodes) {
		return dense(size, nodes, true);
	}

	/**
	 * Creates a dense layer factory with weight initialisation and optional bias.
	 *
	 * @param size  the number of input features
	 * @param nodes the number of output nodes
	 * @param bias  when {@code true}, a learnable bias is added
	 * @return a function that creates a dense layer for any input shape
	 */
	default Function<TraversalPolicy, CellularLayer> dense(int size, int nodes, boolean bias) {
		return dense(size, nodes, bias, true);
	}

	/**
	 * Creates a dense layer factory with full control over bias and weight initialisation.
	 *
	 * @param size         the number of input features
	 * @param nodes        the number of output nodes
	 * @param bias         when {@code true}, a learnable bias is added
	 * @param init         when {@code true}, weights are initialised from a scaled random normal distribution
	 * @param requirements optional compute requirements
	 * @return a function that creates a dense layer for any input shape
	 */
	default Function<TraversalPolicy, CellularLayer> dense(int size, int nodes,
														   boolean bias, boolean init,
														   ComputeRequirement... requirements) {
		return inputShape -> dense(inputShape, new PackedCollection(shape(nodes, size)), bias, init, requirements);
	}

	/**
	 * Creates a dense layer factory backed by pre-allocated weights with no bias and no initialisation.
	 *
	 * @param weights      the pre-allocated weight matrix of shape {@code [nodes, size]}
	 * @param requirements optional compute requirements
	 * @return a function that creates a dense layer for any input shape
	 */
	default Function<TraversalPolicy, CellularLayer> dense(PackedCollection weights,
														   ComputeRequirement... requirements) {
		return inputShape -> dense(inputShape, weights, false, false, requirements);
	}

	/**
	 * Creates a dense layer factory backed by pre-allocated weights and biases.
	 *
	 * @param weights      the pre-allocated weight matrix of shape {@code [nodes, size]}
	 * @param biases       the pre-allocated bias vector of shape {@code [nodes]}
	 * @param requirements optional compute requirements
	 * @return a function that creates a dense layer for any input shape
	 */
	default Function<TraversalPolicy, CellularLayer> dense(PackedCollection weights,
														   PackedCollection biases,
														   ComputeRequirement... requirements) {
		return inputShape -> dense(inputShape, weights, biases, false, requirements);
	}

	/**
	 * Creates a dense layer, optionally allocating a bias collection.
	 *
	 * @param inputShape   the expected input shape
	 * @param weights      the weight matrix of shape {@code [nodes, size]}
	 * @param bias         when {@code true}, a zero-initialised bias collection is allocated
	 * @param init         when {@code true}, weights are initialised from a scaled random normal distribution
	 * @param requirements optional compute requirements
	 * @return the constructed dense {@link CellularLayer}
	 */
	default CellularLayer dense(TraversalPolicy inputShape,
								PackedCollection weights,
								boolean bias, boolean init,
								ComputeRequirement... requirements) {
		return dense(inputShape, weights,
				bias ? new PackedCollection(shape(weights.getShape().length(0))) : null,
				init, requirements);
	}

	/**
	 * Core dense layer factory with explicit weight and bias collections.
	 *
	 * <p>Builds a matrix-multiply operator ({@code weights @ input + bias}) and wraps it in
	 * a {@link CellularLayer} with automatic differentiation for backpropagation.</p>
	 *
	 * @param inputShape   the expected input shape
	 * @param weights      the weight matrix of shape {@code [nodes, size]}
	 * @param biases       the bias vector of shape {@code [nodes]}, or {@code null} for no bias
	 * @param init         when {@code true}, weights are initialised from a scaled random normal distribution
	 * @param requirements optional compute requirements
	 * @return the constructed dense {@link CellularLayer}
	 * @throws IllegalArgumentException if the weight matrix is not 2-D or the size does not match the input
	 */
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
	 * Creates a scalar scaling layer factory that multiplies every element of the input
	 * by the given constant.
	 *
	 * @param scale        the scalar multiplier applied to all input elements
	 * @param requirements optional compute requirements
	 * @return a function that creates the scaling {@link CellularLayer} for any input shape
	 */
	default Function<TraversalPolicy, CellularLayer> scale(double scale, ComputeRequirement... requirements) {
		return shape -> scale(shape, scale, requirements);
	}

	/**
	 * Creates a scalar scaling layer that multiplies every element of the given input shape
	 * by the given constant.
	 *
	 * @param shape        the input and output shape
	 * @param scale        the scalar multiplier applied to all input elements
	 * @param requirements optional compute requirements
	 * @return the constructed scaling {@link CellularLayer}
	 */
	default CellularLayer scale(TraversalPolicy shape, double scale, ComputeRequirement... requirements) {
		return layer("scale", shape, shape, input -> multiply(c(input).each(), c(scale)), requirements);
	}

	/**
	 * Returns a setup operation that initializes the given weight collection with random
	 * normal values scaled by the given factor.
	 *
	 * @param weights the weight collection to initialize
	 * @param scale   the scalar multiplier applied to each sampled normal value
	 * @return a {@link Supplier} of the initialization {@link Runnable}
	 */
	@Override
	default Supplier<Runnable> randnInit(PackedCollection weights, double scale) {
		OperationList setup = new OperationList();
		Random randn = randn(shape(weights));
		setup.add(() -> randn::refresh);
		setup.add(a(p(weights.each()), multiply(randn.traverseEach(), c(scale).traverse(0))));
		return setup;
	}

	/**
	 * Builds a linear-interpolation layer for the given input shape.
	 *
	 * @param inputShape must have total size {@code 3 * hiddenSize}
	 * @param hiddenSize size of the output and of each segment in the input
	 * @return a CellularLayer computing {@code from + weight * (to - from)}
	 */
	default CellularLayer lerpLayer(TraversalPolicy inputShape, int hiddenSize) {
		TraversalPolicy outputShape = shape(hiddenSize);
		return layer("lerp", inputShape, outputShape, input -> {
			CollectionProducer inp = c(input);
			CollectionProducer from = subset(outputShape, inp, 0);
			CollectionProducer weight = subset(outputShape, inp, hiddenSize);
			CollectionProducer to = subset(outputShape, inp, 2 * hiddenSize);
			return from.add(weight.multiply(to.subtract(from)));
		});
	}

	/**
	 * A {@link Cell} that also implements {@link Learning}, combining data-flow
	 * propagation with gradient-based weight update support.
	 */
	interface LearningCell extends Cell<PackedCollection>, Learning { }
}
