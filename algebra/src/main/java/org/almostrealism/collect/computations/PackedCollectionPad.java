/*
 * Copyright 2025 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.collect.computations;

import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.DefaultCollectionExpression;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.Process;
import io.almostrealism.expression.Conjunction;
import io.almostrealism.expression.Expression;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CollectionProducerComputation;
import org.almostrealism.collect.PackedCollection;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * A computation that pads a {@link PackedCollection} by adding zeros around the original data.
 * This class extends {@link TraversableExpressionComputation} to provide efficient padding operations
 * on multi-dimensional collections.
 * 
 * <p>Padding adds zeros around the input collection according to specified positioning constraints.
 * The padding operation takes an input collection and places it within a larger output collection,
 * filling the remaining space with zeros.</p>
 * 
 * <h3>Usage Examples:</h3>
 * 
 * <p><strong>2D Padding Example:</strong></p>
 * <pre>{@code
 * // Create a 2x3 input collection
 * PackedCollection input = new PackedCollection(2, 3).randFill();
 * 
 * // Pad with 1 zero on the right (axis 1)
 * PackedCollection padded = cp(input).pad(0, 1).traverse(1).evaluate();
 * // Result: 2x5 collection with input data and 1 column of zeros
 * }</pre>
 * 
 * <p><strong>Symmetric Padding Example:</strong></p>
 * <pre>{@code
 * // Create a 2x3 input collection  
 * PackedCollection input = new PackedCollection(2, 3).randFill();
 * 
 * // Pad with 1 zero on all sides
 * PackedCollection padded = cp(input).pad(1, 1).traverse(1).evaluate();
 * // Result: 4x5 collection with input data centered and surrounded by zeros
 * }</pre>
 * 
 * <p><strong>Multi-dimensional Padding:</strong></p>
 * <pre>{@code
 * // Create a 2x4x2x3 input collection
 * PackedCollection input = new PackedCollection(2, 4, 2, 3).randFill();
 * 
 * // Pad only the last two dimensions
 * PackedCollection padded = cp(input).pad(0, 0, 1, 1).traverse(1).evaluate();
 * // Result: 2x4x4x5 collection with padding on dimensions 2 and 3
 * }</pre>
 * 
 * <h3>Key Features:</h3>
 * <ul>
 * <li><strong>Multi-dimensional support:</strong> Works with collections of any number of dimensions</li>
 * <li><strong>Flexible positioning:</strong> Input data can be positioned anywhere within the output shape</li>
 * <li><strong>Zero-padding:</strong> Unused areas are filled with zeros</li>
 * <li><strong>Gradient support:</strong> Implements delta operations for backpropagation</li>
 * <li><strong>Efficient computation:</strong> Uses conditional expressions to avoid unnecessary computations</li>
 * </ul>
 * 
 * <h3>Mathematical Operation:</h3>
 * <p>For an input collection I with shape [d1, d2, ..., dn] and position offset [p1, p2, ..., pn],
 * the padding operation creates an output collection O with shape [s1, s2, ..., sn] where:</p>
 * <ul>
 * <li>si &gt;= pi + di for all dimensions i</li>
 * <li>O[i1, i2, ..., in] = I[i1-p1, i2-p2, ..., in-pn] if all (ij-pj) are within input bounds</li>
 * <li>O[i1, i2, ..., in] = 0 otherwise</li>
 * </ul>
 * 
 * @see TraversableExpressionComputation
 * @see PackedCollection
 * @see org.almostrealism.collect.CollectionFeatures#pad(TraversalPolicy, TraversalPolicy, Producer)
 */
public class PackedCollectionPad extends TraversableExpressionComputation {
	public static boolean enableConditionSimplify = true;

	/** The shape/dimensions of the input collection being padded */
	private final TraversalPolicy inputShape;
	
	/** The position/offset where the input data should be placed within the output */
	private final TraversalPolicy position;

	/**
	 * Constructs a new PackedCollectionPad computation.
	 * 
	 * @param shape The desired output shape after padding. Each dimension must be large enough
	 *              to contain the input data at the specified position: shape[i] &gt;= position[i] + inputShape[i]
	 * @param position The offset position where the input data should be placed within the output.
	 *                 Each value specifies how many zeros to add before the input data in that dimension.
	 *                 For example, position[1] = 2 means add 2 zeros before the input data in dimension 1.
	 * @param input The input collection producer to be padded
	 * 
	 * @throws IllegalArgumentException if the shape and position have different numbers of dimensions,
	 *                                  or if the output shape is too small to contain the input at the specified position
	 * 
	 * @see TraversalPolicy
	 */
	public PackedCollectionPad(TraversalPolicy shape, TraversalPolicy position,
							   Producer<?> input) {
		super("pad", shape, (Producer<PackedCollection>) input);
		this.inputShape = shape(input);
		this.position = position;
		init();

		if (shape.getDimensions() != position.getDimensions()) {
			throw new IllegalArgumentException();
		}

		for (int i = 0; i < shape.getDimensions(); i++) {
			if (shape.length(i) < (position.length(i) + inputShape.length(i))) {
				throw new IllegalArgumentException();
			}
		}
	}

	/**
	 * Creates the expression that defines how values are computed for the padded collection.
	 * This method implements the core padding logic by creating conditional expressions that:
	 * <ol>
	 * <li>Map output indices to corresponding input indices by subtracting the position offset</li>
	 * <li>Check if the computed input indices are within the valid input bounds</li>
	 * <li>Return the input value if indices are valid, or zero if they're outside bounds</li>
	 * </ol>
	 * 
	 * <p>The expression uses conditional logic to avoid index-out-of-bounds errors and efficiently
	 * implement the padding behavior without explicit branching in the generated computation.</p>
	 * 
	 * @param args The traversable expressions, where args[1] is the input collection expression
	 * @return A CollectionExpression that computes padded values based on output indices
	 */
	@Override
	protected CollectionExpression getExpression(TraversableExpression... args) {
		return DefaultCollectionExpression.create(getShape(), idx -> {
			// Separate the index into batch and local components to support batch processing
			// This allows the pad operation to "repeat" the pattern for each batch element
			// following the same approach as PackedCollectionEnumerate
			long blockSize = getShape().getTotalSizeLong();
			Expression<?> batchIdx = idx.divide(blockSize);
			Expression<?> localIdx = idx.imod(blockSize);

			Expression<?>[] superPos = getShape().position(localIdx);
			Expression<?>[] innerPos = new Expression[superPos.length];
			List<Expression<?>> conditions = new ArrayList<>();

			// For each dimension, compute the input index by subtracting the position offset
			for (int i = 0; i < superPos.length; i++) {
				int offset = position.length(i);

				if (offset == 0) {
					// No offset in this dimension - use output index directly
					innerPos[i] = superPos[i];
				} else {
					// Subtract offset to get input index
					innerPos[i] = superPos[i].subtract(offset);
					// Add condition: input index must be >= 0
					conditions.add(innerPos[i].greaterThanOrEqual(0));
				}

				// Add condition: input index must be < input dimension size
				if (offset + inputShape.length(i) < getShape().length(i)) {
					conditions.add(innerPos[i].lessThan(inputShape.length(i)));
				}
			}

			Expression<Boolean> cond = Conjunction.of(conditions);
			if (enableConditionSimplify) {
				cond = (Expression) cond.simplify();
			}

			if (!cond.booleanValue().orElse(Boolean.TRUE)) {
				// If conditions are definitely false,
				// there is no need to obtain the value
				return e(0.0);
			}

			// Compute the input index accounting for both the local position and batch offset
			// For batch inputs, each batch element repeats the pad pattern
			Expression<?> inputIdx = inputShape.index(innerPos).add(
					batchIdx.multiply(inputShape.getTotalSizeLong()));

			// Get the value from the input collection at the computed indices
			Expression<?> out = args[1].getValueAt(inputIdx);

			// Return input value if all conditions are met, otherwise return 0
			return conditional(cond, out, e(0.0));
		});
	}

	/**
	 * Generates a new PackedCollectionPad computation with the specified child processes.
	 * This method is part of the computation graph generation system.
	 *
	 * @param children The child processes, where children.get(1) should be the input collection producer
	 * @return A new CollectionProducerComputation that performs the padding operation
	 */
	@Override
	public CollectionProducerComputation generate(List<Process<?, ?>> children) {
		return pad(getShape(), position, (Producer<?>) children.get(1));
	}

	/**
	 * Computes the gradient (delta) of the padding operation with respect to a target.
	 * This method enables backpropagation through padding operations by creating a new
	 * padding computation that operates on the gradient of the input.
	 *
	 * <p>The delta computation extends the dimensionality to include both the output gradient
	 * shape and the target shape, allowing gradients to flow backward through the padding.</p>
	 *
	 * <p><strong>Example:</strong> If the forward padding creates a 4x5 output from a 2x3 input,
	 * the delta operation will create a 4x5x2x3 result where each output position contains
	 * the gradient with respect to the corresponding input position (or zero if no correspondence exists).</p>
	 *
	 * @param target The target with respect to which the gradient is computed
	 * @return A CollectionProducer that computes the gradient of the padding operation
	 *
	 * @see TraversableExpressionComputation#delta(Producer)
	 */
	@Override
	public CollectionProducer delta(Producer<?> target) {
		Supplier in = getInputs().get(1);

		TraversalPolicy shape = getShape();
		TraversalPolicy targetShape = shape(target);
		TraversalPolicy deltaShape = shape.append(targetShape);

		// Extend position to match the dimensionality of the delta computation
		TraversalPolicy position = this.position;
		while (position.getDimensions() < deltaShape.getDimensions()) {
			position = position.appendDimension(0);
		}

		return pad(deltaShape, position, delta((Producer<PackedCollection>) in, target));
	}

	@Override
	public String signature() {
		String signature = super.signature();
		if (signature == null || inputShape == null || position == null)
			return null;

		return signature + "{" + inputShape.toStringDetail() +
						"|" + position.toStringDetail() + "}";
	}
}
