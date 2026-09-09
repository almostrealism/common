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

package io.almostrealism.expression.test;

import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.expression.Expression;
import io.almostrealism.scope.ScopeSettings;
import io.almostrealism.sequence.DefaultIndex;
import io.almostrealism.sequence.Index;
import io.almostrealism.sequence.IndexRange;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

/**
 * Verifies {@link IndexRange}'s own contract directly: construction validation,
 * block partitioning, exact-value conversion, index matching, and memoization.
 */
public class IndexRangeTest extends TestSuiteBase implements ExpressionFeatures {
	/**
	 * A non-positive length must be rejected by the constructor.
	 */
	@Test(timeout = 30000)
	public void constructorRejectsNonPositiveLength() {
		Index index = new DefaultIndex("i", 100);

		try {
			new IndexRange(index, 0, 0);
			Assert.fail("A zero length must be rejected");
		} catch (IllegalArgumentException e) {
			log("zeroLengthMessage=" + e.getMessage());
		}

		try {
			new IndexRange(index, 0, -1);
			Assert.fail("A negative length must be rejected");
		} catch (IllegalArgumentException e) {
			log("negativeLengthMessage=" + e.getMessage());
		}
	}

	/**
	 * Partitioning splits a length into full blocks of {@link ScopeSettings#sequenceBlockSize}
	 * with a final, shorter remainder block, together covering every position exactly once.
	 */
	@Test(timeout = 30000)
	public void partitionCoversEveryPositionInBlocks() {
		int size = ScopeSettings.sequenceBlockSize;
		int len = 2 * size + 100;
		Index index = new DefaultIndex("i", len);

		List<IndexRange> ranges = IndexRange.partition(index, len);
		Assert.assertEquals(3, ranges.size());

		Assert.assertEquals(0, ranges.get(0).getStart());
		Assert.assertEquals(size, ranges.get(0).getLength());
		Assert.assertEquals(size, ranges.get(1).getStart());
		Assert.assertEquals(size, ranges.get(1).getLength());
		Assert.assertEquals(2L * size, ranges.get(2).getStart());
		Assert.assertEquals(100, ranges.get(2).getLength());

		long total = ranges.stream().mapToLong(IndexRange::getLength).sum();
		Assert.assertEquals(len, total);
	}

	/**
	 * Partitioning a non-positive length must be rejected.
	 */
	@Test(timeout = 30000)
	public void partitionRejectsNonPositiveLength() {
		Index index = new DefaultIndex("i", 100);

		try {
			IndexRange.partition(index, 0);
			Assert.fail("A zero length must be rejected");
		} catch (IllegalArgumentException e) {
			log("partitionZeroLengthMessage=" + e.getMessage());
		}
	}

	/**
	 * A range's own {@link IndexRange#positions()} run from {@link IndexRange#getStart()}
	 * for {@link IndexRange#getLength()} consecutive values.
	 */
	@Test(timeout = 30000)
	public void positionsStartAtRangeStart() {
		Index index = new DefaultIndex("i", 1000);
		IndexRange range = new IndexRange(index, 500, 10);

		double[] positions = range.positions();
		Assert.assertEquals(10, positions.length);

		for (int i = 0; i < positions.length; i++) {
			Assert.assertEquals(500 + i, positions[i], 0.0);
		}
	}

	/**
	 * {@link IndexRange#isRangeIndex(Index)} matches an index by name, exactly as
	 * {@link io.almostrealism.sequence.IndexValues} resolves named indices, and rejects
	 * an index with a different name.
	 */
	@Test(timeout = 30000)
	public void isRangeIndexMatchesByName() {
		Index a = new DefaultIndex("a", 10);
		Index b = new DefaultIndex("b", 10);
		Index aAgain = new DefaultIndex("a", 20);

		IndexRange range = new IndexRange(a, 0, 5);
		Assert.assertTrue(range.isRangeIndex(a));
		Assert.assertTrue("Matching is by name, not instance identity", range.isRangeIndex(aAgain));
		Assert.assertFalse(range.isRangeIndex(b));
	}

	/**
	 * {@link IndexRange#constant(double)} fills the range's length with the given value,
	 * for both a zero and a non-zero value.
	 */
	@Test(timeout = 30000)
	public void constantFillsRangeLength() {
		Index index = new DefaultIndex("i", 100);
		IndexRange range = new IndexRange(index, 0, 6);

		double[] zero = range.constant(0.0);
		Assert.assertEquals(6, zero.length);
		for (double v : zero) Assert.assertEquals(0.0, v, 0.0);

		double[] nonZero = range.constant(3.5);
		Assert.assertEquals(6, nonZero.length);
		for (double v : nonZero) Assert.assertEquals(3.5, v, 0.0);
	}

	/**
	 * Values memoized for a node under {@link IndexRange#putCachedValues} are returned
	 * unchanged by {@link IndexRange#getCachedValues}, and an unregistered node returns
	 * {@code null}.
	 */
	@Test(timeout = 30000)
	public void cachedValuesRoundTrip() {
		Index index = new DefaultIndex("i", 100);
		IndexRange range = new IndexRange(index, 0, 4);

		Expression<?> nodeA = e(7);
		Expression<?> nodeB = e(9);

		Assert.assertNull(range.getCachedValues(nodeA));

		double[] values = new double[] { 1.0, 2.0, 3.0, 4.0 };
		range.putCachedValues(nodeA, values);

		Assert.assertSame(values, range.getCachedValues(nodeA));
		Assert.assertNull("A different node must not share the cache entry", range.getCachedValues(nodeB));
	}

	/**
	 * {@link IndexRange#exact(long)} passes through any value within the exact
	 * {@code double} integer range and refuses one that exceeds it in either direction.
	 */
	@Test(timeout = 30000)
	public void exactRefusesValuesBeyondDoublePrecision() {
		Assert.assertEquals((double) IndexRange.MAX_EXACT, IndexRange.exact(IndexRange.MAX_EXACT), 0.0);
		Assert.assertEquals((double) -IndexRange.MAX_EXACT, IndexRange.exact(-IndexRange.MAX_EXACT), 0.0);
		Assert.assertEquals(0.0, IndexRange.exact(0), 0.0);

		try {
			IndexRange.exact(IndexRange.MAX_EXACT + 1);
			Assert.fail("A value beyond the exact range must be refused");
		} catch (IndexRange.InexactValueException e) {
			log("aboveExactMessage=" + e.getMessage());
		}

		try {
			IndexRange.exact(-IndexRange.MAX_EXACT - 1);
			Assert.fail("A value beyond the exact range must be refused");
		} catch (IndexRange.InexactValueException e) {
			log("belowExactMessage=" + e.getMessage());
		}
	}
}
