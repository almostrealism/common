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
 * of a concatenation of subset operations.
 *
 * <p>For a {@code concat(subset(x, offA), subset(x, offB), ...)} operation along a given axis,
 * the Jacobian is a sparse matrix where each row has at most one non-zero entry.
 * The non-zero value may be +1 or -1 depending on whether the input was negated.</p>
 *
 * <p>This class directly computes these entries using conditional expressions,
 * avoiding the need to construct separate {@link SubsetProjectionComputation}s for each
 * input and then concatenate them (which creates large intermediate computations
 * that are expensive to compile).</p>
 *
 * <h2>Performance Characteristics</h2>
 * <p>This implementation is O(1) per element - each Jacobian entry is computed
 * independently using a single segment determination plus index comparison. This avoids
 * creating and materializing intermediate projection matrices for each concat input.</p>
 *
 * @see SubsetProjectionComputation
 * @see CollectionConcatenateComputation
 *
 * @author Michael Murray
 */
public class ConcatProjectionComputation extends TraversableExpressionComputation {

	private final TraversalPolicy outputShape;
	private final TraversalPolicy targetShape;
	private final int axis;
	private final int[] segmentBoundaries;
	private final Expression<?>[][] segmentOffsets;
	private final double[] segmentScales;

	/**
	 * Constructs a projection matrix computation for a concat-of-subsets operation.
	 *
	 * @param jacobianShape The shape of the Jacobian matrix [outputShape..., targetShape...]
	 * @param outputShape The shape of the concat output
	 * @param targetShape The shape of the differentiation target
	 * @param axis The concatenation axis
	 * @param segmentBoundaries Cumulative axis sizes marking input boundaries
	 * @param segmentOffsets Position offsets for each input's subset operation
	 * @param segmentScales Scale factor for each input (1.0 for subset, -1.0 for minus(subset))
	 */
	public ConcatProjectionComputation(TraversalPolicy jacobianShape,
									   TraversalPolicy outputShape,
									   TraversalPolicy targetShape,
									   int axis,
									   int[] segmentBoundaries,
									   Expression<?>[][] segmentOffsets,
									   double[] segmentScales) {
		super("concatProjection", jacobianShape, MultiTermDeltaStrategy.NONE);
		this.outputShape = outputShape;
		this.targetShape = targetShape;
		this.axis = axis;
		this.segmentBoundaries = segmentBoundaries;
		this.segmentOffsets = segmentOffsets;
		this.segmentScales = segmentScales;
		init();
	}

	/**
	 * Generates the expression that computes the projection matrix entries.
	 *
	 * <p>For each Jacobian entry at index idx:</p>
	 * <ol>
	 *   <li>Compute the output element index and target element index</li>
	 *   <li>Determine which concat segment the output element belongs to</li>
	 *   <li>Compute the expected target index for that segment's subset mapping</li>
	 *   <li>Return the segment's scale if indices match, 0.0 otherwise</li>
	 * </ol>
	 *
	 * @param args Array of TraversableExpressions (unused - no inputs needed)
	 * @return A CollectionExpression that computes the sparse projection matrix
	 */
	@Override
	protected CollectionExpression getExpression(TraversableExpression... args) {
		TraversalPolicy jacobianShape = getShape();
		long targetSize = targetShape.getTotalSizeLong();
		int numSegments = segmentBoundaries.length;

		TraversalPolicy localOutputShape = this.outputShape;
		TraversalPolicy localTargetShape = this.targetShape;
		int localAxis = this.axis;
		int[] localBoundaries = this.segmentBoundaries;
		Expression<?>[][] localOffsets = this.segmentOffsets;
		double[] localScales = this.segmentScales;

		return DefaultCollectionExpression.create(jacobianShape, idx -> {
			Expression<?> outputIdx = idx.divide(targetSize);
			Expression<?> targetIdx = idx.imod(targetSize);

			Expression<?>[] outPos = localOutputShape.position(outputIdx);

			Expression<?> result = new IntegerConstant(0).toDouble();

			for (int seg = numSegments - 1; seg >= 0; seg--) {
				int segStart = seg > 0 ? localBoundaries[seg - 1] : 0;

				Expression<?>[] expectedTargetPos = new Expression[outPos.length];
				for (int d = 0; d < outPos.length; d++) {
					Expression<?> localPos;
					if (d == localAxis) {
						localPos = outPos[d].subtract(segStart);
					} else {
						localPos = outPos[d];
					}

					if (d < localOffsets[seg].length) {
						expectedTargetPos[d] = localPos.add(localOffsets[seg][d]);
					} else {
						expectedTargetPos[d] = localPos;
					}
				}

				Expression<?> expectedTargetIdx = localTargetShape.index(expectedTargetPos);

				Expression<?> scaleExpr;
				if (localScales[seg] >= 0) {
					scaleExpr = new IntegerConstant((int) localScales[seg]).toDouble();
				} else {
					scaleExpr = new IntegerConstant((int) localScales[seg]).toDouble();
				}

				Expression<?> value = Conditional.of(
						targetIdx.eq(expectedTargetIdx),
						scaleExpr,
						new IntegerConstant(0).toDouble());

				if (seg == numSegments - 1) {
					result = value;
				} else {
					result = Conditional.of(
							outPos[localAxis].lessThan(localBoundaries[seg]),
							value,
							result);
				}
			}

			return result;
		});
	}

	/**
	 * A projection matrix is never all zeros (it has exactly one non-zero entry per row).
	 *
	 * @return false
	 */
	@Override
	public boolean isZero() {
		return false;
	}

	/**
	 * Generates a new projection computation with the specified child processes.
	 *
	 * @param children The child processes (unused - this computation has no inputs)
	 * @return A new ConcatProjectionComputation with the same configuration
	 */
	@Override
	public CollectionProducerParallelProcess generate(List<Process<?, ?>> children) {
		return new ConcatProjectionComputation(getShape(), outputShape, targetShape,
				axis, segmentBoundaries, segmentOffsets, segmentScales)
				.setPostprocessor(getPostprocessor())
				.setDescription(getDescription())
				.setShortCircuit(getShortCircuit())
				.addAllDependentLifecycles(getDependentLifecycles());
	}

	@Override
	public String signature() {
		String signature = super.signature();
		if (signature == null || segmentBoundaries == null) {
			return null;
		}

		StringBuilder sb = new StringBuilder(signature);
		sb.append("{axis=").append(axis);
		sb.append(",out=").append(outputShape);
		sb.append(",target=").append(targetShape);
		for (int i = 0; i < segmentBoundaries.length; i++) {
			sb.append(",seg").append(i).append("=");
			sb.append(segmentBoundaries[i]).append("/");
			sb.append(segmentScales[i]).append("/");
			if (segmentOffsets[i] != null) {
				sb.append(Stream.of(segmentOffsets[i])
						.map(Expression::signature)
						.reduce((a, b) -> a + "," + b)
						.orElse(""));
			}
		}
		sb.append("}");
		return sb.toString();
	}
}
