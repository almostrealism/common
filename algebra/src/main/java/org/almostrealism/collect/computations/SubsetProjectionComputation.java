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
import io.almostrealism.expression.Conditional;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;
import org.almostrealism.collect.CollectionProducerParallelProcess;

import java.util.List;
import java.util.stream.Stream;

/**
 * A computation that generates a sparse projection matrix representing the Jacobian
 * of a subset (slice) operation.
 *
 * <p>For a subset operation that extracts elements at positions [pos[0], pos[1], ...]
 * from an input, the Jacobian is a sparse matrix where:</p>
 * <ul>
 *   <li>Entry (i, j) = 1 if output element i comes from input element j</li>
 *   <li>Entry (i, j) = 0 otherwise</li>
 * </ul>
 *
 * <p>This class directly computes these entries using conditional expressions,
 * avoiding the need to construct an identity matrix and apply the subset operation
 * to it (which would be expensive for large Jacobians).</p>
 *
 * <h2>Performance Characteristics</h2>
 * <p>This implementation is O(1) per element - each Jacobian entry is computed
 * independently by comparing the input index with the expected position. This
 * avoids the exponential expression tree growth that occurs with the transitive
 * delta pattern for large Jacobians.</p>
 *
 * @see CollectionSubsetComputation
 * @see TransitiveDeltaExpressionComputation
 *
 * @author Michael Murray
 */
public class SubsetProjectionComputation extends TraversableExpressionComputation {

	private final TraversalPolicy outputShape;
	private final TraversalPolicy inputShape;
	private final Expression<?>[] positionOffsets;

	/**
	 * Constructs a projection matrix computation for a subset operation.
	 *
	 * @param jacobianShape The shape of the Jacobian matrix [outputShape..., inputShape...]
	 * @param outputShape The shape of the subset output
	 * @param inputShape The shape of the subset input
	 * @param positionOffsets The position offsets for each dimension of the subset
	 */
	public SubsetProjectionComputation(TraversalPolicy jacobianShape,
									   TraversalPolicy outputShape,
									   TraversalPolicy inputShape,
									   Expression<?>[] positionOffsets) {
		super("subsetProjection", jacobianShape, MultiTermDeltaStrategy.NONE);
		this.outputShape = outputShape;
		this.inputShape = inputShape;
		this.positionOffsets = positionOffsets;
		init();
	}

	/**
	 * Returns the output shape of the subset operation.
	 *
	 * @return The output shape
	 */
	public TraversalPolicy getOutputShape() {
		return outputShape;
	}

	/**
	 * Returns the input shape of the subset operation.
	 *
	 * @return The input shape
	 */
	public TraversalPolicy getInputShape() {
		return inputShape;
	}

	/**
	 * Returns the position offsets for the subset operation.
	 *
	 * @return Array of position offset expressions
	 */
	public Expression<?>[] getPositionOffsets() {
		return positionOffsets;
	}

	/**
	 * Generates the expression that computes the projection matrix entries.
	 *
	 * <p>For each Jacobian entry at index idx:</p>
	 * <ol>
	 *   <li>Compute the output element index: outputIdx = idx / inputSize</li>
	 *   <li>Compute the input element index: inputIdx = idx % inputSize</li>
	 *   <li>Compute the expected input index for this output position</li>
	 *   <li>Return 1.0 if inputIdx matches expectedInputIdx, 0.0 otherwise</li>
	 * </ol>
	 *
	 * @param args Array of TraversableExpressions (unused - no inputs needed)
	 * @return A CollectionExpression that computes the sparse projection matrix
	 */
	@Override
	protected CollectionExpression getExpression(TraversableExpression... args) {
		TraversalPolicy jacobianShape = getShape();
		long outputSize = outputShape.getTotalSizeLong();
		long inputSizeTotal = inputShape.getTotalSizeLong();

		// Capture fields in local finals for the lambda
		TraversalPolicy localOutputShape = this.outputShape;
		TraversalPolicy localInputShape = this.inputShape;
		Expression<?>[] localPositionOffsets = this.positionOffsets;

		return DefaultCollectionExpression.create(jacobianShape, idx -> {
			// Decompose the jacobian index into output and input parts
			// jacobianIdx = outputIdx * inputSize + inputIdx
			Expression<?> outputIdx = idx.divide(inputSizeTotal);
			Expression<?> inputIdx = idx.imod(inputSizeTotal);

			// Compute the expected input index for this output position
			// The subset maps output position to input position by adding offset
			Expression<?>[] outPos = localOutputShape.position(outputIdx);

			Expression<?>[] expectedInputPos = new Expression[outPos.length];
			for (int d = 0; d < outPos.length; d++) {
				if (d < localPositionOffsets.length) {
					expectedInputPos[d] = outPos[d].add(localPositionOffsets[d]);
				} else {
					expectedInputPos[d] = outPos[d];
				}
			}

			Expression<?> expectedInputIdx = localInputShape.index(expectedInputPos);

			// Return 1.0 if inputIdx matches expectedInputIdx, 0.0 otherwise
			return Conditional.direct(inputIdx.eq(expectedInputIdx),
					new IntegerConstant(1).toDouble(),
					new IntegerConstant(0).toDouble());
		});
	}

	/**
	 * Determines whether this computation always produces zero values.
	 * A projection matrix is never all zeros (it has exactly one 1 per row).
	 *
	 * @return false - projection matrices have non-zero entries
	 */
	@Override
	public boolean isZero() {
		return false;
	}

	/**
	 * Generates a new projection computation with the specified child processes.
	 *
	 * @param children The child processes (unused - this computation has no inputs)
	 * @return A new SubsetProjectionComputation with the same configuration
	 */
	@Override
	public CollectionProducerParallelProcess generate(List<Process<?, ?>> children) {
		return new SubsetProjectionComputation(getShape(), outputShape, inputShape, positionOffsets)
				.setPostprocessor(getPostprocessor())
				.setDescription(getDescription())
				.setShortCircuit(getShortCircuit())
				.addAllDependentLifecycles(getDependentLifecycles());
	}

	@Override
	public String signature() {
		String signature = super.signature();
		if (signature == null || positionOffsets == null) {
			return null;
		}

		return signature + "{outputShape=" + outputShape +
				",inputShape=" + inputShape +
				",pos=" + Stream.of(positionOffsets)
				.map(Expression::signature)
				.reduce((a, b) -> a + "," + b)
				.orElse("") + "}";
	}
}
