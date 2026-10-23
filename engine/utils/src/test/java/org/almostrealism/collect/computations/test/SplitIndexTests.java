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

import io.almostrealism.code.Precision;
import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.SplitIndexTraversalOrdering;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.DefaultTraversableExpressionComputation;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for integer-exact index storage: indices held in the split layout of
 * {@link SplitIndexTraversalOrdering} (built with {@link PackedCollection#ofSplitIndices(int...)})
 * and read back by the split-index gather, which must stay exact above {@code 2^24} where an
 * ordinary single-precision index is rounded.
 */
public class SplitIndexTests extends TestSuiteBase {
	/** {@code 2^24}, the largest bound below which every integer is exact in single precision. */
	private static final int SINGLE_PRECISION_LIMIT = 1 << 24;

	/** The modulus of the marker payload, small enough that every marker is exact in FP32. */
	private static final int MARKER_MODULUS = 16;

	/**
	 * Returns whether the active backend stores collections in single precision, where an index
	 * above {@code 2^24} held in an ordinary collection is rounded.
	 *
	 * @return true if collection memory is FP32
	 */
	private boolean singlePrecision() {
		return Hardware.getLocalHardware().getPrecision() == Precision.FP32;
	}

	/**
	 * Each index above {@code 2^24} is stored as two lanes that are individually below
	 * {@code 2^24}, and reconstructing {@code hi * BASE + lo} from the stored lanes recovers the
	 * original value exactly. The control stores the same value as an ordinary index and shows
	 * the rounding the split layout avoids on a single-precision backend.
	 */
	@Test(timeout = 60000)
	public void storageIsExactAboveSinglePrecision() {
		int[] values = { 16777216, 16777217, 16777221, 50000003 };
		PackedCollection lanes = PackedCollection.ofSplitIndices(values);

		Assert.assertEquals(new TraversalPolicy(values.length, SplitIndexTraversalOrdering.LANES),
				lanes.getShape());

		for (int i = 0; i < values.length; i++) {
			double hi = lanes.toDouble(2 * i);
			double lo = lanes.toDouble(2 * i + 1);
			Assert.assertTrue(hi < SINGLE_PRECISION_LIMIT);
			Assert.assertTrue(lo >= 0 && lo < SplitIndexTraversalOrdering.BASE);
			Assert.assertEquals(values[i], (long) hi * SplitIndexTraversalOrdering.BASE + (long) lo);
		}

		PackedCollection plain = PackedCollection.of(16777217);
		Assert.assertEquals(singlePrecision() ? 16777216.0 : 16777217.0, plain.toDouble(0), 0.0);
	}

	/**
	 * The split-index gather reads the element at every stored position, including positions that
	 * straddle the lane boundary ({@code BASE - 1}, {@code BASE}, {@code BASE + 1}), so an error in
	 * the lane order or the radix of the reconstruction selects the wrong elements.
	 */
	@Test(timeout = 60000)
	public void gatherReadsEveryStoredPosition() {
		int count = 20000;
		PackedCollection data = integers(0, count).get().evaluate();
		PackedCollection index = PackedCollection.ofSplitIndices(0, 4095, 4096, 4097, 12345, 19999);

		PackedCollection result = gatherSplitIndex(shape(6), p(data), p(index)).get().evaluate();
		assertEquals(0, result.toDouble(0));
		assertEquals(4095, result.toDouble(1));
		assertEquals(4096, result.toDouble(2));
		assertEquals(4097, result.toDouble(3));
		assertEquals(12345, result.toDouble(4));
		assertEquals(19999, result.toDouble(5));
	}

	/**
	 * An ordinary single-lane index collection passed to the split-index gather is rejected
	 * rather than silently misread as interleaved high and low lanes.
	 */
	@Test(timeout = 60000)
	public void gatherRejectsSingleLaneIndex() {
		PackedCollection data = integers(0, 16).get().evaluate();
		PackedCollection plainIndex = PackedCollection.of(1, 2, 3);

		try {
			gatherSplitIndex(shape(3), p(data), p(plainIndex));
			Assert.fail("Expected a single-lane index to be rejected");
		} catch (IllegalArgumentException e) {
			Assert.assertTrue(e.getMessage().contains(String.valueOf(SplitIndexTraversalOrdering.LANES)));
		}

		PackedCollection wideIndex = PackedCollection.of(1, 2, 3, 4, 5, 6).reshape(new TraversalPolicy(2, 3));
		try {
			gatherSplitIndex(shape(2), p(data), p(wideIndex));
			Assert.fail("Expected an index with three lanes to be rejected");
		} catch (IllegalArgumentException e) {
			Assert.assertTrue(e.getMessage().contains(wideIndex.getShape().toString()));
		}
	}

	/**
	 * Negative positions (used by orderings to mark absent elements) survive the split layout:
	 * the high lane carries the sign and the low lane stays non-negative.
	 */
	@Test(timeout = 60000)
	public void storageRoundTripsNegativeIndex() {
		PackedCollection lanes = PackedCollection.ofSplitIndices(-1, -4097);
		Assert.assertEquals(-1.0, lanes.toDouble(0), 0.0);
		Assert.assertEquals(SplitIndexTraversalOrdering.BASE - 1, lanes.toDouble(1), 0.0);
		Assert.assertEquals(-2.0, lanes.toDouble(2), 0.0);
		Assert.assertEquals(SplitIndexTraversalOrdering.BASE - 1, lanes.toDouble(3), 0.0);
	}

	/**
	 * End-to-end acceptance: gathering at {@code 2^24}, {@code 2^24 + 1} and {@code 2^24 + 5}
	 * from a table whose payload is the exact address modulo 16 returns {@code {0, 1, 5}}. The
	 * payload is computed from the integer kernel index, so it is exact and distinct between
	 * adjacent addresses. The control gathers through an ordinary index collection, which on a
	 * single-precision backend reads the rounded addresses {@code {2^24, 2^24, 2^24 + 4}} and
	 * returns {@code {0, 0, 4}}.
	 */
	@Test(timeout = 300000)
	public void gatherIsExactAboveSinglePrecision() {
		int size = SINGLE_PRECISION_LIMIT + 8;
		TraversalPolicy tableShape = shape(size);
		try (PackedCollection data = new DefaultTraversableExpressionComputation("addressMarker", tableShape,
				CollectionExpression.create(tableShape, idx -> idx.imod(MARKER_MODULUS)))
				.get().evaluate()) {
			assertGatherIsExact(data);
		}
	}

	/**
	 * Gathers at {@code 2^24}, {@code 2^24 + 1} and {@code 2^24 + 5} from the marker table, both
	 * through the split layout and through an ordinary index collection, and checks the payloads.
	 *
	 * @param data the marker table of {@code 2^24 + 8} elements whose payload is address mod 16
	 */
	private void assertGatherIsExact(PackedCollection data) {
		assertEquals(0, data.toDouble(16777216));
		assertEquals(1, data.toDouble(16777217));
		assertEquals(5, data.toDouble(16777221));

		PackedCollection splitIndex = PackedCollection.ofSplitIndices(16777216, 16777217, 16777221);
		PackedCollection exact = gatherSplitIndex(shape(3), p(data), p(splitIndex)).get().evaluate();
		assertEquals(0, exact.toDouble(0));
		assertEquals(1, exact.toDouble(1));
		assertEquals(5, exact.toDouble(2));

		PackedCollection plainIndex = PackedCollection.of(16777216, 16777217, 16777221);
		PackedCollection plain = c(shape(3), p(data), p(plainIndex)).get().evaluate();
		if (singlePrecision()) {
			assertEquals(0, plain.toDouble(0));
			assertEquals(0, plain.toDouble(1));
			assertEquals(4, plain.toDouble(2));
		} else {
			assertEquals(0, plain.toDouble(0));
			assertEquals(1, plain.toDouble(1));
			assertEquals(5, plain.toDouble(2));
		}
	}
}
