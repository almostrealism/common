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

package org.almostrealism.collect.computations.test;

import io.almostrealism.collect.DefaultCollectionExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.expression.Sum;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducerComputation;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.DefaultTraversableExpressionComputation;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Test class demonstrating usage patterns for {@link org.almostrealism.collect.computations.TraversableExpressionComputation}
 * and its concrete implementation {@link DefaultTraversableExpressionComputation}.
 *
 * <p>This class provides practical examples of how to create and use traversable
 * expression computations for common mathematical operations on multi-dimensional collections.
 *
 * @author Michael Murray
 */
public class TraversableExpressionComputationTest extends TestSuiteBase {

	/**
	 * Creates a computation that sums pairs of adjacent elements in a collection.
	 * This is a practical example of how to implement reduction operations using
	 * {@link DefaultTraversableExpressionComputation}.
	 *
	 * <p>The computation takes a 2D input collection and produces a 1D output where
	 * each element is the sum of two adjacent elements from the input row.
	 *
	 * @param <T> The type of {@link PackedCollection} to process
	 * @param a   The input producer providing the collection to process
	 * @return A computation that sums pairs of elements
	 */
	protected DefaultTraversableExpressionComputation pairSum(Producer a) {
		TraversalPolicy shape = shape(a).replace(shape(1));

		return new DefaultTraversableExpressionComputation("pairSum", shape,
				(args) ->
						DefaultCollectionExpression.create(shape,
								idx -> {
									log(shape);
									return Sum.of(
											args[1].getValueAt(idx.multiply(2)),
											args[1].getValueAt(idx.multiply(2).add(1)));
								}
						), a);
	}

	/**
	 * Tests the basic pairSum operation on a 3x2 matrix.
	 * Demonstrates how to create and execute a TraversableExpressionComputation
	 * that performs element-wise reduction (summing pairs).
	 *
	 * <p>Expected behavior:
	 * - Input: 3x2 matrix with random values
	 * - Output: 3x1 matrix where each element is the sum of the corresponding row pair
	 */
	@Test(timeout = 30000)
	public void pair() {
		int r = 3;
		int c = 2;

		PackedCollection input = new PackedCollection(shape(r, c));
		input.fill(pos -> Math.random());

		DefaultTraversableExpressionComputation sum = pairSum(p(input.traverse(1)));
		PackedCollection out = sum.get().evaluate();
		out.print();

		for (int i = 0; i < r; i++) {
			double expected = input.valueAt(i, 0) + input.valueAt(i, 1);
			double actual = out.valueAt(i, 0);
			assertEquals(expected, actual);
		}
	}

	/**
	 * Tests the pairSum operation used within a map operation.
	 * Demonstrates how TraversableExpressionComputation can be used as part of
	 * higher-order operations like mapping across collection elements.
	 *
	 * <p>This test shows a more complex usage pattern where the pairSum computation
	 * is applied through a map operation, demonstrating composition of computations.
	 *
	 * <p>Expected behavior:
	 * - Input: 3x2 matrix with random values
	 * - Process: Map pairSum across the input using traversal
	 * - Output: 3x1 matrix with summed pairs (same as pair() test but through mapping)
	 */
	@Test(timeout = 30000)
	public void map() {
		int r = 3;
		int c = 2;

		PackedCollection input = new PackedCollection(shape(r, c));
		input.fill(pos -> Math.random());

		CollectionProducerComputation sum = c(p(input.traverse(1))).map(shape(1), v -> pairSum(v));
		PackedCollection out = sum.get().evaluate();

		for (int i = 0; i < r; i++) {
			double expected = input.valueAt(i, 0) + input.valueAt(i, 1);
			double actual = out.valueAt(i, 0);
			assertEquals(expected, actual);
		}
	}
}
