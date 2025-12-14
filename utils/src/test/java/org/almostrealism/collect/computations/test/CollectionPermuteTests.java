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

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

/**
 * Test suite for {@link CollectionPermute} demonstrating various dimension reordering scenarios.
 * These tests validate that permutation operations correctly transform collection shapes and
 * preserve element values while reordering dimensional access patterns.
 *
 * @see CollectionPermute
 * @see org.almostrealism.collect.CollectionFeatures#permute(io.almostrealism.relation.Producer, int...)
 */
public class CollectionPermuteTests implements TestFeatures {
	/**
	 * Tests basic 2D transpose operation (matrix transpose).
	 * Demonstrates swapping the two dimensions of a 2D collection using permute(1, 0).
	 *
	 * <p>Original shape: (2, 4) -> Permuted shape: (4, 2)</p>
	 * <p>Element at [i,j] in original -> Element at [j,i] in result</p>
	 */
	@Test(timeout = 30000)
	public void permute2() {
		PackedCollection input = new PackedCollection(shape(2, 4)).randFill();
		PackedCollection out = cp(input).permute(1, 0).evaluate();

		assertEquals(4, out.getShape().length(0));
		assertEquals(2, out.getShape().length(1));

		for (int i = 0; i < 2; i++) {
			for (int j = 0; j < 4; j++) {
				int originalIndex = input.getShape().index(i, j);
				int permutedIndex = input.getShape().permute(1, 0).index(i, j);

				assertEquals(input.valueAt(i, j), out.valueAt(j, i));
			}
		}
	}

	/**
	 * Tests 4D dimension reordering with partial permutation.
	 * Demonstrates swapping middle dimensions while keeping first and last in place using permute(0, 2, 1, 3).
	 *
	 * <p>Original shape: (2, 4, 3, 8) -> Permuted shape: (2, 3, 4, 8)</p>
	 * <p>Element at [i,j,k,l] in original -> Element at [i,k,j,l] in result</p>
	 * <p>This pattern is common in tensor operations where you need to reorder height/width or channel dimensions.</p>
	 */
	@Test(timeout = 30000)
	public void permute4() {
		PackedCollection input = new PackedCollection(shape(2, 4, 3, 8)).randFill();
		PackedCollection out = cp(input).permute(0, 2, 1, 3).evaluate();

		assertEquals(2, out.getShape().length(0));
		assertEquals(3, out.getShape().length(1));
		assertEquals(4, out.getShape().length(2));
		assertEquals(8, out.getShape().length(3));

		for (int i = 0; i < 2; i++) {
			for (int j = 0; j < 4; j++) {
				for (int k = 0; k < 3; k++) {
					for (int l = 0; l < 8; l++) {
						assertEquals(input.valueAt(i, j, k, l), out.valueAt(i, k, j, l));
					}
				}
			}
		}
	}
}
