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
import io.almostrealism.collect.WeightedSumDeltaExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.expression.Expression;
import org.almostrealism.algebra.computations.WeightedSumComputation;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestFeatures;
import org.junit.Assert;
import org.junit.Test;

public class SubsetTraversalTests implements TestFeatures {
	@Test
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

	@Test
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
}
