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
import io.almostrealism.collect.TraversalOrdering;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.ExplicitIndexTraversalOrdering;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.SplitIndexTraversalOrdering;
import org.almostrealism.collect.computations.DefaultTraversableExpressionComputation;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for integer-exact index storage: positions held in the split layout of
 * {@link SplitIndexTraversalOrdering}, and collections read through that ordering by ordinary
 * computations, which must stay exact above {@code 2^24} where a position stored as an ordinary
 * single-precision value is rounded.
 */
public class SplitIndexTests extends TestSuiteBase {
	/** {@code 2^24}, the largest bound below which every integer is exact in single precision. */
	private static final int SINGLE_PRECISION_LIMIT = 1 << 24;

	/** The modulus of the marker payload, small enough that every marker is exact in FP32. */
	private static final int MARKER_MODULUS = 16;

	/** The offset added by the kernels below, so that the result cannot be the unread input. */
	private static final double OFFSET = 10.0;

	/**
	 * Returns whether the active backend stores collections in single precision, where a
	 * position above {@code 2^24} held in an ordinary collection is rounded.
	 *
	 * @return true if collection memory is FP32
	 */
	private boolean singlePrecision() {
		return Hardware.getLocalHardware().getPrecision() == Precision.FP32;
	}

	/**
	 * Returns a view of the given collection that reads it through the given ordering.
	 *
	 * @param size  the number of logical elements of the view
	 * @param data  the collection to read
	 * @param order the ordering mapping logical indices to positions in {@code data}
	 * @return the ordered view
	 */
	private PackedCollection view(int size, PackedCollection data, TraversalOrdering order) {
		return new PackedCollection(shape(size), 0, data, 0, order);
	}

	/**
	 * Each position above {@code 2^24} is stored as two lanes that are individually below
	 * {@code 2^24}, and the ordering maps each logical index to the original position exactly.
	 * The control stores the same value as an ordinary collection and shows the rounding the
	 * split layout avoids on a single-precision backend.
	 */
	@Test(timeout = 60000)
	public void storageIsExactAboveSinglePrecision() {
		int[] values = { 16777216, 16777217, 16777221, 50000003 };
		SplitIndexTraversalOrdering order = SplitIndexTraversalOrdering.of(values);
		PackedCollection lanes = order.getLanes();

		Assert.assertEquals(new TraversalPolicy(values.length, SplitIndexTraversalOrdering.LANES),
				lanes.getShape());

		for (int i = 0; i < values.length; i++) {
			double hi = lanes.toDouble(2 * i);
			double lo = lanes.toDouble(2 * i + 1);
			Assert.assertTrue(hi < SINGLE_PRECISION_LIMIT);
			Assert.assertTrue(lo >= 0 && lo < SplitIndexTraversalOrdering.BASE);
			Assert.assertEquals(values[i], (long) hi * SplitIndexTraversalOrdering.BASE + (long) lo);
			Assert.assertEquals(values[i], order.indexOf(i));
		}

		PackedCollection plain = PackedCollection.of(16777217);
		Assert.assertEquals(singlePrecision() ? 16777216.0 : 16777217.0, plain.toDouble(0), 0.0);
	}

	/**
	 * The ordering addresses one more position than the largest one it stores, ignoring absent
	 * (negative) positions, including above {@code 2^24}.
	 */
	@Test(timeout = 60000)
	public void lengthCoversLargestPosition() {
		Assert.assertEquals(6, SplitIndexTraversalOrdering.of(5, -1, 3).getLength().getAsInt());
		Assert.assertEquals(0, SplitIndexTraversalOrdering.of(-1, -4097).getLength().getAsInt());
		Assert.assertEquals(16777222, SplitIndexTraversalOrdering.of(16777221, 4096).getLength().getAsInt());
	}

	/**
	 * A kernel reading a collection through the ordering reads the element at every stored
	 * position, including positions that straddle the lane boundary ({@code BASE - 1},
	 * {@code BASE}, {@code BASE + 1}), so an error in the lane order or the radix of the
	 * reconstruction selects the wrong elements.
	 */
	@Test(timeout = 60000)
	public void kernelReadsEveryStoredPosition() {
		int[] positions = { 0, 4095, 4096, 4097, 12345, 19999 };
		PackedCollection data = integers(0, 20000).get().evaluate();
		PackedCollection gathered = view(positions.length, data, SplitIndexTraversalOrdering.of(positions));

		PackedCollection result = cp(gathered).add(c(OFFSET)).get().evaluate();
		for (int i = 0; i < positions.length; i++) {
			assertEquals(positions[i], gathered.toDouble(i));
			assertEquals(positions[i] + OFFSET, result.toDouble(i));
		}
	}

	/**
	 * Re-traversing a view that reads a larger table through an ordering yields another view of
	 * that table through the same ordering, which reads the same elements, rather than a view
	 * nested inside the first one, which would apply the ordering a second time and address more
	 * positions than the first view has elements.
	 */
	@Test(timeout = 60000)
	public void retraversedViewReadsTheSameElements() {
		PackedCollection data = integers(0, 20).get().evaluate();
		SplitIndexTraversalOrdering order = SplitIndexTraversalOrdering.of(19, 4, 7);
		PackedCollection gathered = view(3, data, order);

		PackedCollection retraversed = gathered.traverse(1);
		Assert.assertEquals(1, retraversed.getShape().getTraversalAxis());
		Assert.assertSame(data, retraversed.getDelegate());
		Assert.assertSame(order, retraversed.getShape().getOrder());
		assertEquals(19.0, retraversed.toDouble(0));
		assertEquals(4.0, retraversed.toDouble(1));
		assertEquals(7.0, retraversed.toDouble(2));
	}

	/**
	 * An ordinary single-lane index table, or one with more than two lanes, is rejected rather
	 * than silently misread as interleaved high and low lanes.
	 */
	@Test(timeout = 60000)
	public void rejectsIndexWithoutTwoLanes() {
		PackedCollection plainIndex = PackedCollection.of(1, 2, 3);
		try {
			new SplitIndexTraversalOrdering(plainIndex);
			Assert.fail("Expected a single-lane index to be rejected");
		} catch (IllegalArgumentException e) {
			Assert.assertTrue(e.getMessage().contains(String.valueOf(SplitIndexTraversalOrdering.LANES)));
		}

		PackedCollection wideIndex = PackedCollection.of(1, 2, 3, 4, 5, 6).reshape(new TraversalPolicy(2, 3));
		try {
			new SplitIndexTraversalOrdering(wideIndex);
			Assert.fail("Expected an index with three lanes to be rejected");
		} catch (IllegalArgumentException e) {
			Assert.assertTrue(e.getMessage().contains(wideIndex.getShape().toString()));
		}
	}

	/**
	 * Negative positions (absent elements) survive the split layout: the high lane carries the
	 * sign and the low lane stays non-negative, and an absent element reads as zero both on the
	 * host and inside a kernel.
	 */
	@Test(timeout = 60000)
	public void negativePositionIsAbsent() {
		SplitIndexTraversalOrdering order = SplitIndexTraversalOrdering.of(-1, -4097, 3);
		PackedCollection lanes = order.getLanes();
		Assert.assertEquals(-1.0, lanes.toDouble(0), 0.0);
		Assert.assertEquals(SplitIndexTraversalOrdering.BASE - 1, lanes.toDouble(1), 0.0);
		Assert.assertEquals(-2.0, lanes.toDouble(2), 0.0);
		Assert.assertEquals(SplitIndexTraversalOrdering.BASE - 1, lanes.toDouble(3), 0.0);

		PackedCollection gathered = view(3, pack(5.0, 6.0, 7.0, 8.0), order);
		assertEquals(0.0, gathered.toDouble(0));
		assertEquals(0.0, gathered.toDouble(1));
		assertEquals(8.0, gathered.toDouble(2));

		PackedCollection result = cp(gathered).multiply(c(2.0)).get().evaluate();
		assertEquals(0.0, result.toDouble(0));
		assertEquals(0.0, result.toDouble(1));
		assertEquals(16.0, result.toDouble(2));
	}

	/**
	 * The extremes of the {@code int} range survive the split layout: {@link Integer#MAX_VALUE}
	 * and {@link Integer#MIN_VALUE} are stored with a high lane of magnitude at most {@code 2^19}
	 * and a low lane in {@code [0, BASE)}, and the ordering maps them back exactly.
	 */
	@Test(timeout = 60000)
	public void storageRoundTripsIntExtremes() {
		SplitIndexTraversalOrdering order = SplitIndexTraversalOrdering.of(Integer.MAX_VALUE, Integer.MIN_VALUE);
		PackedCollection lanes = order.getLanes();
		Assert.assertEquals(524287.0, lanes.toDouble(0), 0.0);
		Assert.assertEquals(SplitIndexTraversalOrdering.BASE - 1, lanes.toDouble(1), 0.0);
		Assert.assertEquals(-524288.0, lanes.toDouble(2), 0.0);
		Assert.assertEquals(0.0, lanes.toDouble(3), 0.0);

		Assert.assertEquals(Integer.MAX_VALUE, order.indexOf(0));
		Assert.assertEquals(Integer.MIN_VALUE, order.indexOf(1));
	}

	/**
	 * The gather {@code c(shape, collection, index)} respects the ordering of its collection
	 * argument: indexing a view that reads {@code data} at positions {@code [5, 3, 1]} with
	 * {@code [2, 0]} selects {@code data[1]} and {@code data[5]}.
	 */
	@Test(timeout = 60000)
	public void gatherRespectsOrderedCollection() {
		PackedCollection data = integers(0, 20).get().evaluate();
		PackedCollection ordered = view(3, data, SplitIndexTraversalOrdering.of(5, 3, 1));

		PackedCollection result = c(shape(2), cp(ordered), p(pack(2, 0))).get().evaluate();
		assertEquals(1.0, result.toDouble(0));
		assertEquals(5.0, result.toDouble(1));
	}

	/**
	 * The gather {@code c(shape, collection, index)} respects the ordering of its index
	 * argument: an index table holding {@code [7, 3, 9]} read through an ordering selecting its
	 * positions {@code [2, 0]} supplies the indices {@code [9, 7]}.
	 */
	@Test(timeout = 60000)
	public void gatherRespectsOrderedIndex() {
		PackedCollection data = integers(0, 20).get().evaluate();
		PackedCollection index = view(2, pack(7, 3, 9), SplitIndexTraversalOrdering.of(2, 0));

		PackedCollection result = c(shape(2), p(data), cp(index)).get().evaluate();
		assertEquals(9.0, result.toDouble(0));
		assertEquals(7.0, result.toDouble(1));
	}

	/**
	 * End-to-end acceptance: reading a table at {@code 2^24}, {@code 2^24 + 1} and
	 * {@code 2^24 + 5} through the split ordering selects payloads {@code {0, 1, 5}}, where the
	 * payload is the exact address modulo 16, computed from the integer kernel index so that it
	 * is exact and distinct between adjacent addresses. The control reads the same positions
	 * through an {@link ExplicitIndexTraversalOrdering}, whose positions are stored as ordinary
	 * values, and on a single-precision backend reads the rounded addresses
	 * {@code {2^24, 2^24, 2^24 + 4}}, selecting {@code {0, 0, 4}}.
	 */
	@Test(timeout = 300000)
	public void readIsExactAboveSinglePrecision() {
		int size = SINGLE_PRECISION_LIMIT + 8;
		TraversalPolicy tableShape = shape(size);
		try (PackedCollection data = new DefaultTraversableExpressionComputation("addressMarker", tableShape,
				CollectionExpression.create(tableShape, idx -> idx.imod(MARKER_MODULUS)))
				.get().evaluate()) {
			assertReadIsExact(data);
		}
	}

	/**
	 * Reads the marker table at {@code 2^24}, {@code 2^24 + 1} and {@code 2^24 + 5}, both through
	 * the split ordering and through an explicit index ordering, and checks the payloads.
	 *
	 * @param data the marker table of {@code 2^24 + 8} elements whose payload is address mod 16
	 */
	private void assertReadIsExact(PackedCollection data) {
		assertEquals(0, data.toDouble(16777216));
		assertEquals(1, data.toDouble(16777217));
		assertEquals(5, data.toDouble(16777221));

		PackedCollection exact = view(3, data, SplitIndexTraversalOrdering.of(16777216, 16777217, 16777221));
		PackedCollection result = cp(exact).add(c(OFFSET)).get().evaluate();
		assertEquals(OFFSET, result.toDouble(0));
		assertEquals(1 + OFFSET, result.toDouble(1));
		assertEquals(5 + OFFSET, result.toDouble(2));

		PackedCollection rounded = view(3, data,
				new ExplicitIndexTraversalOrdering(PackedCollection.of(16777216, 16777217, 16777221)));
		PackedCollection control = cp(rounded).add(c(OFFSET)).get().evaluate();
		assertEquals(OFFSET, control.toDouble(0));
		assertEquals((singlePrecision() ? 0 : 1) + OFFSET, control.toDouble(1));
		assertEquals((singlePrecision() ? 4 : 5) + OFFSET, control.toDouble(2));
	}
}
