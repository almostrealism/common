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

package org.almostrealism.collect.computations.test;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.PassThroughProducer;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for forming a multi-hot vector from {@code oneHotRows(n, positions).transpose().sum(1)}.
 * Each stage ({@code oneHotRows}, then {@code transpose()}, then {@code sum(1)}) is evaluated
 * separately so that a wrong result can be attributed to the faulty stage, and the whole
 * composition is checked under both constant and runtime-argument positions and under both
 * plain and optimised evaluation.
 */
public class MultiHotRowsTests extends TestSuiteBase {

	/**
	 * The full composition over {@code n = 40} for positions {@code {3, 17, 39}} must yield a
	 * multi-hot vector that is {@code 1} at 3, 17 and 39 and {@code 0} elsewhere.
	 */
	@Test(timeout = 60000)
	public void multiHotThreePositions() {
		assertMultiHot(40, pack(3, 17, 39));
	}

	/**
	 * A single position must yield an ordinary one-hot vector.
	 */
	@Test(timeout = 60000)
	public void multiHotSinglePosition() {
		assertMultiHot(40, pack(17));
	}

	/**
	 * Positions supplied out of order must still each set their index.
	 */
	@Test(timeout = 60000)
	public void multiHotOutOfOrder() {
		assertMultiHot(40, pack(39, 3, 17));
	}

	/**
	 * A repeated position must yield {@code 2} at that index (the sum of both rows).
	 */
	@Test(timeout = 60000)
	public void multiHotRepeatedPosition() {
		assertMultiHot(40, pack(3, 17, 17, 39));
	}

	/**
	 * Checks the composition both when the positions are an embedded constant and when they are a
	 * runtime kernel argument (a {@link PassThroughProducer}), because the two drive different code.
	 *
	 * @param n            the length of each row
	 * @param positionData the indices to set, one per row
	 */
	protected void assertMultiHot(int n, PackedCollection positionData) {
		int k = positionData.getShape().getTotalSize();
		int[] positions = new int[k];
		for (int i = 0; i < k; i++) {
			positions[i] = (int) positionData.toDouble(i);
		}

		// Positions as an embedded constant
		assertMultiHot(n, positions, cp(positionData));

		// Positions as a runtime kernel argument
		assertMultiHot(n, positions, new PassThroughProducer(shape(k), 0), positionData);
	}

	/**
	 * Evaluates each stage of the composition and checks it against values computed here,
	 * under both plain and optimised evaluation.
	 *
	 * @param n          the length of each row
	 * @param positions  the indices to set, one per row
	 * @param pos        the producer of the positions
	 * @param args       the arguments to evaluate {@code pos} with (empty when it is a constant)
	 */
	protected void assertMultiHot(int n, int[] positions, Producer<PackedCollection> pos, Object... args) {
		int k = positions.length;

		// Stage 1: oneHotRows -> (k, n) one-hot rows
		PackedCollection rows = oneHotRows(n, pos).evaluate(args);
		Assert.assertEquals(k, rows.getShape().length(0));
		Assert.assertEquals(n, rows.getShape().length(1));
		for (int r = 0; r < k; r++) {
			for (int c = 0; c < n; c++) {
				double expected = (c == positions[r]) ? 1.0 : 0.0;
				assertEquals(expected, rows.valueAt(r, c));
			}
		}

		// Stage 2: transpose -> (n, k), read linearly so k == 1 (a trimmed shape) is handled
		PackedCollection transposed = oneHotRows(n, pos).transpose().evaluate(args);
		Assert.assertEquals((long) n * k, transposed.getShape().getTotalSize());
		PackedCollection transposedFlat = transposed.reshape(n, k);
		for (int c = 0; c < n; c++) {
			for (int r = 0; r < k; r++) {
				double expected = (c == positions[r]) ? 1.0 : 0.0;
				assertEquals(expected, transposedFlat.valueAt(c, r));
			}
		}

		// Stage 3: sum over axis 1 -> (n) multi-hot, under plain and optimised evaluation
		PackedCollection multiHot = oneHotRows(n, pos).transpose().sum(1).evaluate(args);
		PackedCollection multiHotOptimized =
				oneHotRows(n, pos).transpose().sum(1).evaluateOptimized(args);
		Assert.assertEquals(n, multiHot.getShape().getTotalSize());
		Assert.assertEquals(n, multiHotOptimized.getShape().getTotalSize());
		PackedCollection multiHotFlat = multiHot.reshape(n);
		PackedCollection multiHotOptimizedFlat = multiHotOptimized.reshape(n);
		for (int c = 0; c < n; c++) {
			double expected = 0.0;
			for (int r = 0; r < k; r++) {
				if (positions[r] == c) expected += 1.0;
			}
			assertEquals(expected, multiHotFlat.valueAt(c));
			assertEquals(expected, multiHotOptimizedFlat.valueAt(c));
		}
	}
}
