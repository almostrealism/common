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
import org.almostrealism.util.TestFeatures;
import org.junit.Assert;
import org.junit.Test;

public class TraversalPolicyTests implements TestFeatures {
	@Test
	public void flattenRequired1() {
		TraversalPolicy shape = shape(10, 2048, 1024)
				.traverse(0)
				.flatten(true, 1024);
		TraversalPolicy expected = shape(10 * 2048, 1024).traverse(0);
		Assert.assertEquals(expected, shape);
	}

	@Test
	public void flattenRequired2() {
		TraversalPolicy shape = shape(10, 2048, 1024, 4)
				.traverse(3)
				.flatten(true, 1024, 4);
		TraversalPolicy expected = shape(10 * 2048, 1024, 4).traverse(2);
		Assert.assertEquals(expected, shape);
	}

	@Test
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
					int originalPosition[] = shape.position(originalIndex);
					int permutedPosition[] = permuted.position(originalIndex);

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

	@Test
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
						int originalPosition[] = shape.position(originalIndex);
						int permutedPosition[] = permuted.position(originalIndex);

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
