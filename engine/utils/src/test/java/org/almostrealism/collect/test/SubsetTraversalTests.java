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

package org.almostrealism.collect.test;

import io.almostrealism.collect.SubsetTraversalExpression;
import io.almostrealism.collect.SubsetTraversalIndexMapping;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.collect.WeightedSumDeltaExpression;
import io.almostrealism.expression.Expression;
import org.almostrealism.algebra.computations.WeightedSumComputation;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for SubsetTraversalExpression and subset traversal operations.
 */
public class SubsetTraversalTests extends TestSuiteBase {

	/**
	 * Tests basic subset traversal.
	 */
	@Test(timeout = 10000)
	public void traversal() {
		TraversalPolicy resultShape = shape(4, 1);

//		TraversalPolicy leftShape = shape(2, 4);
//		TraversalPolicy leftGroupShape = shape(2, 1);
//		TraversalPolicy leftPositions = shape(1, 4);

		TraversalPolicy leftShape = shape(4, 2);
		TraversalPolicy leftGroupShape = shape(1, 2);
		TraversalPolicy leftPositions = shape(4, 1);

		TraversalPolicy rightShape = shape(4, 2);
		TraversalPolicy rightGroupShape = shape(1, 2);
		TraversalPolicy rightPositions = shape(4, 1);

		TraversalPolicy deltaShape = shape(resultShape.getTotalSize(), leftShape.getTotalSize());
		int index = deltaShape.index(2, 6);

		SubsetTraversalExpression left = new SubsetTraversalExpression(resultShape, leftShape, leftGroupShape, leftPositions);
		SubsetTraversalExpression right = new SubsetTraversalExpression(resultShape, rightShape, rightGroupShape, rightPositions);

		WeightedSumDeltaExpression mapping =
				new WeightedSumDeltaExpression(resultShape, leftShape, left, right, idx -> idx.toDouble());
		mapping.getValueAt(e(index));
	}

	/**
	 * Tests subset traversal with convolution.
	 */
	@Test(timeout = 10000)
	public void convolution() {
		int batch = 1;
		int channels = 1;
		int height = 4;
		int width = 4;

		int filterCount = 1;
		int size = 2;

		int outHeight = 3;
		int outWidth = 3;

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

		PackedCollection conv = new PackedCollection(shape(batch, 1, channels, height, width));
		PackedCollection filter = new PackedCollection(1, filterCount, channels, size, size);

		WeightedSumComputation sum = (WeightedSumComputation)
				weightedSum("convolutionFilter",
						inputPositions, filterPositions,
						groupShape, cp(conv), cp(filter));

		TraversalPolicy deltaShape = resultShape.append(conv.getShape());
		WeightedSumDeltaExpression exp = new WeightedSumDeltaExpression(resultShape, conv.getShape(),
				sum.getInputTraversal(), sum.getWeightsTraversal(), idx -> (Expression) idx);

		// (1, 2) in the output and (2, 2) in the input
		int index = deltaShape.index(0, 0, 0, 1, 2, 0, 0, 0, 2, 2);

		Expression<?> filterIndex = exp.getValueAt(e(index));
		log(filterIndex.getExpressionSummary());
		Assert.assertEquals("2", filterIndex.getExpressionSummary());
	}

	/**
	 * Pins the operand index expression produced by
	 * {@link SubsetTraversalIndexMapping#getValueAt(Expression)} for every group member and
	 * output index of a simple 2D geometry. With a {@code 4x2} operand traversed by a
	 * {@code 1x2} window, the flat operand index for member {@code m} and output row
	 * {@code o} is {@code o * 2 + m}; each index folds to a constant expression.
	 */
	@Test(timeout = 10000)
	public void indexMappingConstants() {
		TraversalPolicy resultShape = shape(4, 1);
		TraversalPolicy operandShape = shape(4, 2);
		TraversalPolicy groupShape = shape(1, 2);
		TraversalPolicy positions = shape(4, 1);

		for (int member = 0; member < groupShape.getTotalSize(); member++) {
			SubsetTraversalIndexMapping mapping =
					new SubsetTraversalIndexMapping(resultShape, operandShape, groupShape, positions, member);

			for (int output = 0; output < resultShape.getTotalSize(); output++) {
				String child = mapping.getValueAt(e(output)).getExpressionSummary();
				Assert.assertEquals(String.valueOf(output * 2 + member), child);
			}
		}
	}

	/**
	 * Pins the invariant that {@link SubsetTraversalIndexMapping#getValueAt(Expression)} for a
	 * fixed group member produces the same operand index expression as the parent
	 * {@link SubsetTraversalExpression#getInputIndex(Expression, Expression)} invoked with that
	 * member index. The two share the same index geometry; this asserts they stay in agreement
	 * across both a simple 2D traversal and a 5D convolution traversal.
	 */
	@Test(timeout = 10000)
	public void indexMappingMatchesParent() {
		assertMappingMatchesParent(shape(4, 1), shape(4, 2), shape(1, 2), shape(4, 1));

		int batch = 1;
		int channels = 1;
		int height = 4;
		int width = 4;
		int filterCount = 1;
		int size = 2;
		int outHeight = 3;
		int outWidth = 3;

		TraversalPolicy convResult = shape(batch, filterCount, 1, outHeight, outWidth);
		TraversalPolicy convPositions = convResult
				.withRate(1, 1, filterCount)
				.withRate(2, channels, 1);
		TraversalPolicy convOperand = shape(batch, 1, channels, height, width);
		TraversalPolicy convGroup = shape(1, 1, channels, size, size);

		assertMappingMatchesParent(convResult, convOperand, convGroup, convPositions);
	}

	/**
	 * The operand index of a group member located by its position within the window,
	 * coordinate by coordinate, is the operand index of the same member located by its flat
	 * index, for every member and every output. The geometries are the rows of a matrix
	 * multiplying a vector, the columns of a matrix read by the same product (whose members are a
	 * row length apart), and the padded input of a one-dimensional convolution, whose members
	 * span the taps of each channel.
	 */
	@Test(timeout = 30000)
	public void positionedIndexMatchesMemberIndex() {
		int m = 3;
		int n = 40;
		int p = 2;

		TraversalPolicy productResult = shape(1, m, 1, p);
		assertPositionedIndices(productResult, shape(1, m, n, 1), shape(1, 1, n, 1),
				productResult.withRate(3, n, p));
		assertPositionedIndices(productResult, shape(1, 1, n, p), shape(1, 1, n, 1),
				productResult.withRate(1, 1, m));

		int channels = 6;
		int kernel = 3;
		int length = 5;

		TraversalPolicy convResult = shape(1, 2, 1, length);
		assertPositionedIndices(convResult, shape(1, 1, channels, length + kernel - 1),
				shape(1, 1, channels, kernel),
				convResult.withRate(1, 1, 2).withRate(2, channels, 1));
	}

	/**
	 * Asserts, for every group member and every output index, that the operand index computed
	 * from the member's position within the window equals the operand index computed from its
	 * flat index within the group.
	 *
	 * @param resultShape  the output shape of the traversal
	 * @param operandShape the operand collection shape
	 * @param groupShape   the sliding window shape
	 * @param positions    the anchor positions of the window over the operand
	 */
	private void assertPositionedIndices(TraversalPolicy resultShape, TraversalPolicy operandShape,
										 TraversalPolicy groupShape, TraversalPolicy positions) {
		SubsetTraversalExpression traversal =
				new SubsetTraversalExpression(resultShape, operandShape, groupShape, positions);

		for (int member = 0; member < groupShape.getTotalSize(); member++) {
			int[] coordinates = groupShape.position(member);
			Expression[] position = new Expression[coordinates.length];
			for (int d = 0; d < coordinates.length; d++) {
				position[d] = e(coordinates[d]);
			}

			for (int output = 0; output < resultShape.getTotalSize(); output++) {
				int expected = traversal.getInputIndex(e(member), e(output))
						.getSimplified().intValue().getAsInt();
				int actual = traversal.getInputIndex(position, e(output))
						.getSimplified().intValue().getAsInt();
				Assert.assertEquals("member=" + member + " output=" + output, expected, actual);
			}
		}
	}

	/**
	 * Asserts that the per-member index mapping agrees with the parent's {@code getInputIndex}
	 * for every group member and output index of the supplied geometry.
	 *
	 * @param resultShape  the output shape of the traversal
	 * @param operandShape the operand collection shape
	 * @param groupShape   the sliding window shape
	 * @param positions    the anchor positions of the window over the operand
	 */
	private void assertMappingMatchesParent(TraversalPolicy resultShape, TraversalPolicy operandShape,
											TraversalPolicy groupShape, TraversalPolicy positions) {
		SubsetTraversalExpression parent =
				new SubsetTraversalExpression(resultShape, operandShape, groupShape, positions);

		for (int member = 0; member < groupShape.getTotalSize(); member++) {
			SubsetTraversalIndexMapping mapping =
					new SubsetTraversalIndexMapping(resultShape, operandShape, groupShape, positions, member);

			for (int output = 0; output < resultShape.getTotalSize(); output++) {
				String child = mapping.getValueAt(e(output)).getExpressionSummary();
				String viaParent = parent.getInputIndex(e(member), e(output)).getExpressionSummary();
				Assert.assertEquals("member=" + member + " output=" + output, viaParent, child);
			}
		}
	}
}
