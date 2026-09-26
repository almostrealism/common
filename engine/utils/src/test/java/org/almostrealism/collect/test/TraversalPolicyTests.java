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

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for TraversalPolicy and its dimension manipulation operations.
 */
public class TraversalPolicyTests extends TestSuiteBase {
	/**
	 * Tests flatten required with single dimension.
	 */
	@Test(timeout = 10000)
	public void flattenRequired1() {
		TraversalPolicy shape = shape(10, 2048, 1024)
				.traverse(0)
				.flatten(true, 1024);
		TraversalPolicy expected = shape(10 * 2048, 1024).traverse(0);
		Assert.assertEquals(expected, shape);
	}

	/**
	 * Tests flatten required with multiple dimensions.
	 */
	@Test(timeout = 10000)
	public void flattenRequired2() {
		TraversalPolicy shape = shape(10, 2048, 1024, 4)
				.traverse(3)
				.flatten(true, 1024, 4);
		TraversalPolicy expected = shape(10 * 2048, 1024, 4).traverse(2);
		Assert.assertEquals(expected, shape);
	}

	/**
	 * Tests permute with 3 dimensions.
	 */
	@Test(timeout = 10000)
	public void permute3() {
		TraversalPolicy shape = new TraversalPolicy(2, 4, 3);
		TraversalPolicy permuted = shape.permute(1, 0, 2);

		assertEquals(4, permuted.length(0));
		assertEquals(2, permuted.length(1));
		assertEquals(3, permuted.length(2));

		assertEquals(3, permuted.inputSizeLong(2));
		assertEquals(12, permuted.inputSizeLong(1));
		assertEquals(24, permuted.inputSizeLong(0));

		for (int i = 0; i < 2; i++) {
			for (int j = 0; j < 4; j++) {
				for (int k = 0; k < 3; k++) {
					assertEquals(shape.index(i, j, k), permuted.index(j, i, k));

					int originalIndex = shape.index(i, j, k);
					int[] originalPosition = shape.position(originalIndex);
					int[] permutedPosition = permuted.position(originalIndex);

					assertEquals(i, originalPosition[0]);
					assertEquals(j, originalPosition[1]);
					assertEquals(k, originalPosition[2]);

					assertEquals(j, permutedPosition[0]);
					assertEquals(i, permutedPosition[1]);
					assertEquals(k, permutedPosition[2]);
				}
			}
		}
	}

	/**
	 * A policy that carries traversal rates — as produced by {@link TraversalPolicy#repeat(int, long)},
	 * {@link TraversalPolicy#withRate(int, int, long)}, and {@link TraversalPolicy#withInput(int...)} —
	 * must still support the dimension-mutating operations. Each of
	 * {@link TraversalPolicy#appendDimension(int)}, {@link TraversalPolicy#prependDimension(int)},
	 * {@link TraversalPolicy#insertDimension(int, long)}, {@link TraversalPolicy#subset(int)}, and
	 * {@link TraversalPolicy#append(TraversalPolicy)} changes the number of dimensions, so it must
	 * resize the rate arrays to match. Before the fix these methods forwarded the original
	 * (now wrong-length) rate arrays to the constructor, which rejects them, so every call threw
	 * {@link IllegalArgumentException} on a rated policy. A newly introduced axis takes a neutral
	 * rate (its input length equals its output length); axes that survive keep their own rate.
	 */
	@Test(timeout = 10000)
	public void ratedPolicySurvivesDimensionMutation() {
		// (2, 3) repeated 4x along axis 0 -> output (8, 3) reading input (2, 3): axis 0 has rate 1/4.
		TraversalPolicy rated = new TraversalPolicy(2, 3).traverse(0).repeat(0, 4);
		assertEquals(8, rated.length(0));
		assertEquals(3, rated.length(1));
		assertEquals(2, rated.inputLengthLong(0));
		assertEquals(3, rated.inputLengthLong(1));

		TraversalPolicy appended = rated.appendDimension(5);
		assertEquals(3, appended.getDimensions());
		assertEquals(120, appended.getTotalSizeLong());
		// The appended axis is neutral (input length == output length); rated axis 0 is preserved.
		assertEquals(2, appended.inputLengthLong(0));
		assertEquals(3, appended.inputLengthLong(1));
		assertEquals(5, appended.inputLengthLong(2));

		TraversalPolicy prepended = rated.prependDimension(5);
		assertEquals(5, prepended.length(0));
		assertEquals(8, prepended.length(1));
		assertEquals(5, prepended.inputLengthLong(0));
		assertEquals(2, prepended.inputLengthLong(1));
		assertEquals(3, prepended.inputLengthLong(2));

		TraversalPolicy inserted = rated.insertDimension(1, 7);
		assertEquals(8, inserted.length(0));
		assertEquals(7, inserted.length(1));
		assertEquals(3, inserted.length(2));
		assertEquals(2, inserted.inputLengthLong(0));
		assertEquals(7, inserted.inputLengthLong(1));
		assertEquals(3, inserted.inputLengthLong(2));

		// Dropping the leading (rated) axis leaves the surviving axis with its own rate.
		TraversalPolicy subset = rated.subset(1);
		assertEquals(1, subset.getDimensions());
		assertEquals(3, subset.length(0));
		assertEquals(3, subset.inputLengthLong(0));

		// Dropping axis 0 keeps a rated inner axis, which must still report input length 3, not 12.
		TraversalPolicy ratedInner = new TraversalPolicy(2, 3, 5).traverse(1).repeat(1, 4);
		assertEquals(12, ratedInner.length(1));
		assertEquals(3, ratedInner.inputLengthLong(1));
		TraversalPolicy innerSubset = ratedInner.subset(1);
		assertEquals(2, innerSubset.getDimensions());
		assertEquals(12, innerSubset.length(0));
		assertEquals(3, innerSubset.inputLengthLong(0));
		assertEquals(5, innerSubset.inputLengthLong(1));

		TraversalPolicy joined = rated.append(new TraversalPolicy(5));
		assertEquals(3, joined.getDimensions());
		assertEquals(2, joined.inputLengthLong(0));
		assertEquals(3, joined.inputLengthLong(1));
		assertEquals(5, joined.inputLengthLong(2));
	}

	/**
	 * The rate resizing must not disturb the common, rate-free case: adding or dropping a
	 * dimension on a policy with no traversal rates must leave the rate arrays absent (all-ones)
	 * so that the index/position round trip is unchanged.
	 */
	@Test(timeout = 10000)
	public void rateFreePolicyDimensionMutationRoundTrips() {
		TraversalPolicy plain = new TraversalPolicy(2, 3);

		TraversalPolicy appended = plain.appendDimension(4);
		assertEquals(24, appended.getTotalSizeLong());
		assertEquals(24, appended.getTotalInputSizeLong());

		int idx = appended.index(1, 2, 3);
		int[] pos = appended.position(idx);
		assertEquals(1, pos[0]);
		assertEquals(2, pos[1]);
		assertEquals(3, pos[2]);
	}

	/**
	 * Tests permute with 4 dimensions.
	 */
	@Test(timeout = 10000)
	public void permute4() {
		TraversalPolicy shape = new TraversalPolicy(2, 4, 3, 8);
		TraversalPolicy permuted = shape.permute(0, 2, 1, 3);

		assertEquals(2, permuted.length(0));
		assertEquals(3, permuted.length(1));
		assertEquals(4, permuted.length(2));
		assertEquals(8, permuted.length(3));

		assertEquals(8, permuted.inputSizeLong(3));
		assertEquals(24, permuted.inputSizeLong(2));
		assertEquals(96, permuted.inputSizeLong(1));
		assertEquals(192, permuted.inputSizeLong(0));

		for (int i = 0; i < 2; i++) {
			for (int j = 0; j < 4; j++) {
				for (int k = 0; k < 3; k++) {
					for (int l = 0; l < 8; l++) {
						assertEquals(shape.index(i, j, k, l), permuted.index(i, k, j, l));

						int originalIndex = shape.index(i, j, k, l);
						int[] originalPosition = shape.position(originalIndex);
						int[] permutedPosition = permuted.position(originalIndex);

						assertEquals(i, originalPosition[0]);
						assertEquals(j, originalPosition[1]);
						assertEquals(k, originalPosition[2]);
						assertEquals(l, originalPosition[3]);

						assertEquals(i, permutedPosition[0]);
						assertEquals(k, permutedPosition[1]);
						assertEquals(j, permutedPosition[2]);
						assertEquals(l, permutedPosition[3]);
					}
				}
			}
		}
	}
}
