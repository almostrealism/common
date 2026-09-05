/*
 * Copyright 2026 Michael Murray
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

import java.util.Arrays;

/**
 * Bounds tests for {@link TraversalPolicy#position(int)}.
 *
 * <p>{@code position(int)} converts a flat input index into a multidimensional
 * position. The valid input index range is {@code [0, getTotalInputSize() - 1]}.
 * The method guards against out-of-range indices by throwing
 * {@link IllegalArgumentException}, but an off-by-one in that guard let an index
 * equal to {@code getTotalInputSize()} — the first out-of-range value, and the
 * one most likely to arise from an inclusive/exclusive bound confusion — slip
 * through and produce an out-of-range position instead of failing fast. These
 * tests pin the boundary contract so that regression is caught.</p>
 */
public class TraversalPolicyPositionBoundsTest extends TestSuiteBase {

	/**
	 * An index equal to the total input size is one past the last valid index and
	 * must be rejected. Before the guard fix, {@code position(6)} on a {@code (2, 3)}
	 * shape silently returned the out-of-range position {@code [2, 0]} (axis 0 has
	 * length 2, so 2 is not a valid coordinate) rather than throwing.
	 */
	@Test(timeout = 10000)
	public void positionRejectsIndexEqualToTotalInputSize() {
		TraversalPolicy shape = new TraversalPolicy(2, 3);
		int size = shape.getTotalInputSize();
		Assert.assertEquals(6, size);

		try {
			int[] pos = shape.position(size);
			Assert.fail("position(" + size + ") must throw for an index equal to the "
					+ "total input size, but returned " + Arrays.toString(pos));
		} catch (IllegalArgumentException expected) {
			// expected
		}
	}

	/**
	 * A negative index is out of range and must be rejected.
	 */
	@Test(timeout = 10000)
	public void positionRejectsNegativeIndex() {
		TraversalPolicy shape = new TraversalPolicy(2, 3);

		try {
			int[] pos = shape.position(-1);
			Assert.fail("position(-1) must throw, but returned "
					+ Arrays.toString(pos));
		} catch (IllegalArgumentException expected) {
			// expected
		}
	}

	/**
	 * Every valid index in {@code [0, size)} must yield an in-range position whose
	 * round trip through {@link TraversalPolicy#index(int...)} recovers the index.
	 * This confirms the tightened guard does not reject legitimate indices.
	 */
	@Test(timeout = 10000)
	public void positionAcceptsEveryValidIndex() {
		TraversalPolicy shape = new TraversalPolicy(2, 3);
		int size = shape.getTotalInputSize();

		for (int i = 0; i < size; i++) {
			int[] pos = shape.position(i);
			Assert.assertTrue("axis 0 coordinate out of range for index " + i,
					pos[0] >= 0 && pos[0] < shape.length(0));
			Assert.assertTrue("axis 1 coordinate out of range for index " + i,
					pos[1] >= 0 && pos[1] < shape.length(1));
			Assert.assertEquals("round trip failed for index " + i, i, shape.index(pos));
		}
	}
}
