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

import io.almostrealism.collect.Algebraic;
import org.almostrealism.algebra.AlgebraFeatures;
import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.DefaultCollectionExpression;
import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.Process;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.MatrixFeatures;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CollectionProducerParallelProcess;
import org.almostrealism.collect.PackedCollection;

import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * A computation that extracts a subset (slice) from a {@link PackedCollection} with
 * Producer-level differentiation support.
 *
 * <p>This class provides an efficient implementation of subset extraction with isolated
 * gradient computation. Unlike the {@link PackedCollectionSubset} implementation that uses
 * index projection, this class extends {@link TransitiveDeltaExpressionComputation} to
 * prevent exponential expression tree growth during backpropagation.</p>
 *
 * <h2>Mathematical Operation</h2>
 * <p>For an input of shape [..., n, ...] and a subset starting at position p:</p>
 * <pre>
 * output[..., i, ...] = input[..., i + p, ...]
 * </pre>
 *
 * <h2>Chain Rule Implementation</h2>
 * <p>The gradient of subset with respect to its input follows transitive delta propagation:</p>
 * <ul>
 *   <li>d(subset)/d(input) = subset applied to d(input)/d(target)</li>
 * </ul>
 *
 * <p>This class implements delta() at the Producer level through the
 * {@link TransitiveDeltaExpressionComputation} base class, creating isolated computation
 * boundaries that prevent the expression tree explosion that occurs with nested index
 * projection implementations.</p>
 *
 * @see PackedCollectionSubset
 * @see TransitiveDeltaExpressionComputation
 * @see CollectionConcatenateComputation
 *
 * @author Michael Murray
 */
public class CollectionSubsetComputation extends TransitiveDeltaExpressionComputation
		implements CollectionFeatures {

	private final Expression<?>[] pos;
	private final TraversalPolicy inputShape;

	/**
	 * Constructs a subset computation with static integer positions.
	 *
	 * @param shape The output shape after subsetting
	 * @param input The input producer to extract from
	 * @param pos The starting position coordinates as integers, one per dimension
	 */
	public CollectionSubsetComputation(TraversalPolicy shape, Producer<PackedCollection> input, int... pos) {
		this(shape, input,
				IntStream.of(pos).mapToObj(i -> (Expression<?>) new IntegerConstant(i)).toArray(Expression[]::new));
	}

	/**
	 * Constructs a subset computation with expression-based positions.
	 *
	 * @param shape The output shape after subsetting
	 * @param input The input producer to extract from
	 * @param pos The starting position coordinates as expressions
	 */
	@SafeVarargs
	public CollectionSubsetComputation(TraversalPolicy shape, Producer<PackedCollection> input, Expression<?>... pos) {
		super("subset", shape, input);

		if (!(input instanceof Shape)) {
			throw new IllegalArgumentException("Subset cannot be performed without a TraversalPolicy");
		}

		this.inputShape = ((Shape) input).getShape();
		this.pos = pos;
		init();
	}

	/**
	 * Returns the starting position for subsetting.
	 *
	 * @return Array of position expressions
	 */
	public Expression<?>[] getPosition() {
		return pos;
	}

	/**
	 * Returns the shape of the input collection.
	 *
	 * @return The input shape
	 */
	public TraversalPolicy getInputShape() {
		return inputShape;
	}

	/**
	 * Generates the expression that computes the subset extraction.
	 *
	 * <p>For each output position, this computes the corresponding input position
	 * by adding the offset to each dimension, then reads from that input position.</p>
	 *
	 * @param args Array of TraversableExpressions where args[1] is the input
	 * @return A CollectionExpression that computes the subset output
	 */
	@Override
	protected CollectionExpression getExpression(TraversableExpression... args) {
		TraversalPolicy outputShape = getShape();

		return DefaultCollectionExpression.create(outputShape, idx -> {
			// Separate batch and local index
			long blockSize = outputShape.getTotalSizeLong();
			Expression<?> batchIdx = idx.divide(blockSize);
			Expression<?> localIdx = idx.imod(blockSize);

			// Get position along each dimension in output
			Expression<?>[] outPos = outputShape.position(localIdx);

			// Compute position in input by adding offset
			Expression<?>[] inputPos = new Expression[outPos.length];
			for (int d = 0; d < outPos.length; d++) {
				if (d < pos.length) {
					inputPos[d] = outPos[d].add(pos[d]);
				} else {
					inputPos[d] = outPos[d];
				}
			}

			// Compute input index
			Expression<?> inputIdx = inputShape.index(inputPos)
					.add(batchIdx.multiply(inputShape.getTotalSizeLong()));

			// Get value from input (args[1] because args[0] is destination)
			return args[1].getValueAt(inputIdx);
		});
	}

	/**
	 * Generates a new subset computation with the specified child processes.
	 *
	 * <p>This method computes the output shape dynamically from the input shapes,
	 * which is essential for correct delta propagation where input shapes change.</p>
	 *
	 * @param children The child processes to use as inputs
	 * @return A new CollectionSubsetComputation with the updated children
	 */
	@Override
	public CollectionProducerParallelProcess generate(List<Process<?, ?>> children) {
		Producer<PackedCollection> input = (Producer<PackedCollection>) children.get(1);

		// For delta propagation, we need to compute the new output shape
		// based on the input shape with target dimensions appended
		TraversalPolicy newInputShape = shape(input);
		TraversalPolicy originalOutputShape = getShape();

		// If input shape has grown (delta case), adjust output shape proportionally
		TraversalPolicy newOutputShape;
		if (newInputShape.getDimensions() > inputShape.getDimensions()) {
			// Input has additional dimensions from delta - preserve them in output
			int extraDims = newInputShape.getDimensions() - inputShape.getDimensions();
			long[] dims = new long[originalOutputShape.getDimensions() + extraDims];

			// Copy original output dimensions
			for (int i = 0; i < originalOutputShape.getDimensions(); i++) {
				dims[i] = originalOutputShape.length(i);
			}

			// Copy extra dimensions from input
			for (int i = 0; i < extraDims; i++) {
				dims[originalOutputShape.getDimensions() + i] =
						newInputShape.length(inputShape.getDimensions() + i);
			}

			newOutputShape = new TraversalPolicy(dims);
		} else {
			newOutputShape = originalOutputShape;
		}

		return new CollectionSubsetComputation(newOutputShape, input, pos)
				.setPostprocessor(getPostprocessor())
				.setDescription(getDescription())
				.setShortCircuit(getShortCircuit())
				.addAllDependentLifecycles(getDependentLifecycles());
	}

	/**
	 * Computes the derivative (delta) of this subset computation using a direct
	 * projection matrix representation.
	 *
	 * <p>For a subset operation, the Jacobian is a sparse projection matrix where:
	 * <ul>
	 *   <li>For each output position i, there's exactly one non-zero entry at input position j</li>
	 *   <li>j = computeInputIndex(i) based on the position offset</li>
	 *   <li>All other entries are zero</li>
	 * </ul>
	 *
	 * <p>This method creates a direct projection expression rather than relying on the
	 * transitive delta pattern (which would compute subset(identity)), providing better
	 * performance for large Jacobians.</p>
	 *
	 * @param target The {@link Producer} with respect to which the derivative is computed
	 * @return A {@link CollectionProducer} that computes the projection matrix Jacobian
	 */
	@Override
	public CollectionProducer delta(Producer<?> target) {
		// First try the standard optimizations (same producer, no match, etc.)
		CollectionProducer delta = MatrixFeatures.getInstance().attemptDelta(this, target);
		if (delta != null) {
			return delta;
		}

		// Get the input producer
		CollectionProducer input = (CollectionProducer) getInputs().get(1);

		// Check if the input matches the target (the common case: d(subset(x))/dx)
		if (AlgebraFeatures.match(input, target)) {
			// Create a direct projection matrix
			return createProjectionMatrix();
		}

		// For the chain rule case: d(subset(f(x)))/dx = d(subset)/df * df/dx
		// Use the transitive delta pattern from the superclass
		return super.delta(target);
	}

	/**
	 * Creates a projection matrix representing the subset's Jacobian.
	 *
	 * <p>The projection matrix has shape [outputShape..., inputShape...] where:
	 * <ul>
	 *   <li>Entry (..., ...) = 1 if output position corresponds to input position</li>
	 *   <li>Entry (..., ...) = 0 otherwise</li>
	 * </ul>
	 *
	 * @return A CollectionProducer representing the projection matrix
	 */
	private CollectionProducer createProjectionMatrix() {
		TraversalPolicy outputShape = getShape();
		long outputSize = outputShape.getTotalSizeLong();
		long inputSizeTotal = inputShape.getTotalSizeLong();

		// Jacobian shape is [outputShape..., inputShape...]
		TraversalPolicy jacobianShape = outputShape.append(inputShape);

		// Capture the position and input shape in local finals for the lambda
		Expression<?>[] positionOffsets = this.pos;
		TraversalPolicy thisInputShape = this.inputShape;

		return new SubsetProjectionComputation(jacobianShape, outputShape, thisInputShape, positionOffsets);
	}

	@Override
	public String signature() {
		String signature = super.signature();
		if (signature == null || pos == null) {
			return null;
		}

		return signature + "{pos=" + Stream.of(pos)
				.map(Expression::signature)
				.reduce((a, b) -> a + "," + b)
				.orElse("") + "}";
	}
}
