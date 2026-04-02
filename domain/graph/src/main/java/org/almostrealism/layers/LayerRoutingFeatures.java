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
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Cell;
import org.almostrealism.graph.CellularPropagation;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.model.Block;
import org.almostrealism.model.SequentialBlock;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Factory interface for structural routing layers that compose blocks.
 *
 * <p>{@link LayerFeatures} is a large interface covering both simple transformation
 * layers (dense, norm, activation) and structural routing layers (accum, concat,
 * product, residual, similarity). This interface captures the latter category —
 * methods that combine or route the outputs of multiple blocks rather than
 * applying a pointwise transformation to a single input.</p>
 *
 * <h2>Routing vs Simple Layers</h2>
 * <ul>
 *   <li><strong>Simple layers</strong> ({@link LayerFeatures}): dense, rmsnorm, softmax, activation —
 *       apply a transformation to one input and produce one output</li>
 *   <li><strong>Routing layers</strong> ({@link LayerRoutingFeatures}): accum, concat, product,
 *       residual, similarity, weightedSum — combine or route outputs of multiple sub-blocks</li>
 * </ul>
 *
 * <h2>Typical Usage</h2>
 * <p>Transformer and attention implementations implement or extend this interface so
 * they can use skip-connections ({@link #residual}), gated activations ({@link #product}),
 * multi-head similarity ({@link #similarity}), and parallel embedding fans ({@link #concatBlocks}).</p>
 *
 * @see LayerFeatures
 * @see org.almostrealism.ml.AttentionFeatures
 */
public interface LayerRoutingFeatures extends LayerFeatures {

	/**
	 * Creates an accumulation layer that adds the output of the given cell to the layer's input.
	 *
	 * @deprecated Does not support backpropagation. Use {@link #accum(TraversalPolicy, CellularPropagation, ComputeRequirement...)} instead.
	 * @param shape        the input and output shape
	 * @param value        the cell whose output is accumulated with the input
	 * @param requirements optional compute requirements
	 * @return the constructed accumulation {@link CellularLayer}
	 */
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

	/**
	 * Creates an accumulation layer that element-wise adds the output of an auxiliary
	 * propagation path to the main input.
	 *
	 * @param shape        the input and output shape
	 * @param aux          the auxiliary propagation whose output is added to the input
	 * @param requirements optional compute requirements
	 * @return the constructed accumulation {@link CellularLayer}
	 */
	default CellularLayer accum(TraversalPolicy shape,
								CellularPropagation<PackedCollection> aux,
								ComputeRequirement... requirements) {
		return compose("accum", shape, aux,
				(input, auxValue) -> add(traverseEach(input), traverseEach(auxValue)),
				requirements);
	}

	/**
	 * Creates a concatenation layer factory that concatenates the main input and the auxiliary block's
	 * output along the given axis.
	 *
	 * @param axis         the axis along which to concatenate
	 * @param aux          the auxiliary block whose output is concatenated with the main input
	 * @param requirements optional compute requirements
	 * @return a function that creates the concatenation layer for any main input shape
	 */
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

	/**
	 * Creates a concatenation layer with explicit input and output shapes.
	 *
	 * @param inputShape   the main input shape
	 * @param outputShape  the shape after concatenation
	 * @param aux          the auxiliary block whose output is concatenated
	 * @param requirements optional compute requirements
	 * @return the constructed concatenation {@link CellularLayer}
	 */
	default CellularLayer concat(TraversalPolicy inputShape,
								 TraversalPolicy outputShape,
								 Block aux,
								 ComputeRequirement... requirements) {
		return compose("concat", inputShape, aux, outputShape,
				(input, auxValue) -> concat(outputShape, input, auxValue),
				requirements);
	}

	/**
	 * Creates a product layer that element-wise multiplies the input by a fixed producer value.
	 *
	 * @deprecated Does not support backpropagation.
	 * @param value        the producer supplying the fixed multiplier values
	 * @param requirements optional compute requirements
	 * @return the constructed product {@link CellularLayer}
	 */
	@Deprecated
	default CellularLayer product(Producer<PackedCollection> value, ComputeRequirement... requirements) {
		warn("product will not support backpropagation");
		TraversalPolicy shape = shape(value);
		return layer("product", shape, shape,
				input -> multiply(traverseEach(input), traverseEach(value)),
				requirements);
	}

	/**
	 * Creates a product layer that computes the element-wise product of the outputs of two cells.
	 *
	 * @deprecated Does not support backpropagation.
	 * @param inputShape   the input shape fed to both cells
	 * @param outputShape  the shape of the resulting product
	 * @param a            the first cell
	 * @param b            the second cell
	 * @param requirements optional compute requirements
	 * @return the constructed product {@link CellularLayer}
	 */
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

	/**
	 * Apply each of the given blocks to the same input and concatenate their outputs.
	 * The output shape is automatically computed as the sum of each block's output size.
	 *
	 * <p>Each block receives the layer's input independently, its output is captured via
	 * {@link Cell.CaptureReceptor}, and all captured outputs are concatenated in order
	 * to form the final output.</p>
	 *
	 * @param inputShape the input tensor shape
	 * @param blocks     the sub-blocks to apply in parallel and concatenate
	 * @param requirements optional compute requirements
	 * @return a CellularLayer whose output is the concatenation of all block outputs
	 */
	default CellularLayer concatBlocks(TraversalPolicy inputShape,
									   List<Block> blocks,
									   ComputeRequirement... requirements) {
		int totalSize = 0;
		for (Block b : blocks) totalSize += b.getOutputShape().getTotalSize();
		TraversalPolicy outputShape = shape(totalSize);

		return layer("concat_blocks", inputShape, outputShape, Cell.of((input, next) -> {
			List<Cell.CaptureReceptor<PackedCollection>> receptors = new ArrayList<>();
			for (Block b : blocks) {
				Cell.CaptureReceptor<PackedCollection> receptor = new Cell.CaptureReceptor<>();
				b.getForward().setReceptor(receptor);
				receptors.add(receptor);
			}

			OperationList ops = new OperationList("concat_blocks");
			for (Block b : blocks) {
				ops.add(b.getForward().push(input));
			}

			if (next != null) {
				CollectionProducer[] parts = new CollectionProducer[blocks.size()];
				for (int i = 0; i < blocks.size(); i++) {
					parts[i] = c(receptors.get(i).getReceipt())
							.reshape(blocks.get(i).getOutputShape());
				}
				ops.add(next.push(concat(parts).reshape(outputShape)));
			}

			return ops;
		}), null, requirements);
	}

	/**
	 * Creates an element-wise product layer factory using the given auxiliary branch.
	 *
	 * @param aux          the auxiliary propagation whose output is multiplied element-wise with the main input
	 * @param requirements optional compute requirements
	 * @return a function that creates the product layer for any input shape
	 */
	default Function<TraversalPolicy, CellularLayer> product(CellularPropagation<PackedCollection> aux,
															 ComputeRequirement... requirements) {
		return shape -> product(shape, aux, requirements);
	}

	/**
	 * Creates an element-wise product layer for the given shape using the given auxiliary branch.
	 *
	 * @param shape        the input (and output) shape
	 * @param aux          the auxiliary propagation whose output is multiplied element-wise
	 * @param requirements optional compute requirements
	 * @return the constructed product {@link CellularLayer}
	 */
	default CellularLayer product(TraversalPolicy shape,
								  CellularPropagation<PackedCollection> aux,
								  ComputeRequirement... requirements) {
		return compose("product", shape, aux,
				(input, auxValue) -> multiply(traverseEach(input), traverseEach(auxValue)),
				requirements);
	}

	/**
	 * Creates a linear-interpolation (lerp) block.
	 * Expects a concatenated input of shape ({@code 3 * hiddenSize}) laid out as
	 * {@code [from | weight | to]}, and computes:
	 * <pre>output = from + weight * (to - from)</pre>
	 * which equals {@code (1 - weight) * from + weight * to}.
	 *
	 * @param hiddenSize the size of each of the three input segments and the output
	 * @return a factory that builds the lerp layer for any (3 * hiddenSize) input shape
	 */
	default Function<TraversalPolicy, Block> lerp(int hiddenSize) {
		return inputShape -> lerpLayer(inputShape, hiddenSize);
	}

	/**
	 * Creates a residual block factory that wraps the given block factory in a skip-connection.
	 *
	 * @param block a factory that produces the inner block for a given shape
	 * @return a function that creates a residual block wrapping the inner block
	 */
	default Function<TraversalPolicy, Block> residual(Function<TraversalPolicy, Block> block) {
		return shape -> residual(block.apply(shape));
	}

	/**
	 * Wraps a block in a residual skip-connection by accumulating the block's output with the input.
	 *
	 * @param block the inner block; its input and output shapes must have the same total size
	 * @return a {@link SequentialBlock} that adds the inner block's output to its input
	 * @throws IllegalArgumentException if the block's input and output shapes have different total sizes
	 */
	default Block residual(Block block) {
		if (block.getInputShape().getTotalSize() != block.getOutputShape().getTotalSize())
			throw new IllegalArgumentException();

		SequentialBlock residual = new SequentialBlock(block.getInputShape());
		residual.accum(block);
		return residual;
	}

	/**
	 * Creates a pairwise similarity layer factory that computes a weighted inner product
	 * between the main input and the key block output across spatial positions.
	 *
	 * <p>The key block must produce a 4-D output of shape {@code (batch, c, dim, s2)}.
	 * The resulting layer maps inputs of some query shape to an output of shape
	 * {@code (batch, c, s1, s2)}.</p>
	 *
	 * @param k   the key block whose output forms one side of the similarity computation
	 * @param c   the number of channels (heads)
	 * @param s1  the number of query positions
	 * @param s2  the number of key positions
	 * @return a function that creates the similarity {@link CellularLayer} for any query input shape
	 */
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

	/**
	 * Creates a weighted-sum (attention value aggregation) layer factory that multiplies
	 * attention weights against a value block and sums over the head dimension.
	 *
	 * <p>The value block must produce a 4-D output of shape {@code (batch, heads, dimHead, size)}.
	 * The resulting layer output has shape {@code (batch, heads, size, dimHead)}.</p>
	 *
	 * @param v       the value block whose output is aggregated by the attention weights
	 * @param heads   the number of attention heads
	 * @param dimHead the per-head feature dimension
	 * @param size    the sequence length (number of key/value positions)
	 * @return a function that creates the weighted-sum {@link CellularLayer} for any attention-weight input shape
	 */
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
}
