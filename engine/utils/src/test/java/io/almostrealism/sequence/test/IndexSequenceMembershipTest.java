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

package io.almostrealism.sequence.test;

import io.almostrealism.sequence.ArithmeticIndexSequence;
import io.almostrealism.sequence.ArrayIndexSequence;
import io.almostrealism.sequence.IndexSequence;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

/**
 * Tests the value-membership methods of {@link IndexSequence}
 * ({@link IndexSequence#isEmpty()}, {@link IndexSequence#distinctCount()},
 * {@link IndexSequence#contains(long)}, {@link IndexSequence#containsAll(IndexSequence)} and
 * {@link IndexSequence#union(IndexSequence)}), checking the closed forms of
 * {@link ArithmeticIndexSequence} and the cached search of {@link ArrayIndexSequence}
 * against the values each sequence actually takes.
 */
public class IndexSequenceMembershipTest extends TestSuiteBase {

	/** Arithmetic sequences covering offsets, negative scales, granularity, wrapping and constants. */
	private static final List<ArithmeticIndexSequence> ARITHMETIC = List.of(
			ArithmeticIndexSequence.range(1),
			ArithmeticIndexSequence.range(10),
			new ArithmeticIndexSequence(0, 3, 1, 12, 12),
			new ArithmeticIndexSequence(5, 2, 1, 7, 7),
			new ArithmeticIndexSequence(4, -1, 1, 9, 9),
			new ArithmeticIndexSequence(-6, -4, 1, 5, 5),
			new ArithmeticIndexSequence(0, 1, 3, 12, 12),
			new ArithmeticIndexSequence(1, 2, 2, 6, 20),
			new ArithmeticIndexSequence(7, 0, 1, 4, 4),
			new ArithmeticIndexSequence(3, 5, 8, 8, 8));

	/**
	 * The empty progression has no values, contains nothing, and is contained in everything.
	 */
	@Test(timeout = 30000)
	public void emptyProgression() {
		ArithmeticIndexSequence empty = ArithmeticIndexSequence.range(0);

		Assert.assertTrue(empty.isEmpty());
		Assert.assertEquals(0, empty.lengthLong());
		Assert.assertEquals(0, empty.distinctCount());
		Assert.assertEquals(0, empty.values().count());
		Assert.assertFalse(empty.contains(0));
		Assert.assertTrue(empty.containsAll(empty));
		Assert.assertFalse(empty.containsAll(ArithmeticIndexSequence.range(1)));
		Assert.assertTrue(ArithmeticIndexSequence.range(1).containsAll(empty));
		Assert.assertSame(ARITHMETIC.get(3), empty.union(ARITHMETIC.get(3)));
		Assert.assertSame(ARITHMETIC.get(3), ARITHMETIC.get(3).union(empty));
		assertFails(NoSuchElementException.class, empty::min);
		assertFails(NoSuchElementException.class, empty::max);
		assertFails(IllegalArgumentException.class, () -> ArithmeticIndexSequence.range(-1));
		Assert.assertFalse(ArithmeticIndexSequence.range(1).isEmpty());
	}

	/**
	 * Asserts that running the action throws an exception of the given type.
	 */
	private void assertFails(Class<? extends RuntimeException> type, Runnable action) {
		try {
			action.run();
		} catch (RuntimeException e) {
			Assert.assertTrue(e.toString(), type.isInstance(e));
			return;
		}

		Assert.fail("Expected " + type.getSimpleName());
	}

	/**
	 * The closed-form count and membership of an arithmetic sequence agree with the
	 * values it takes at its positions.
	 */
	@Test(timeout = 30000)
	public void arithmeticMembershipMatchesValues() {
		for (ArithmeticIndexSequence seq : ARITHMETIC) {
			Set<Long> values = positionValues(seq);
			Assert.assertEquals(seq.toString(), values.size(), seq.distinctCount());

			for (long v = -40; v <= 40; v++) {
				Assert.assertEquals(seq + " contains " + v, values.contains(v), seq.contains(v));
			}
		}

		Assert.assertFalse(new ArithmeticIndexSequence(Long.MIN_VALUE + 1, 1, 1, 4, 4).contains(Long.MAX_VALUE));
	}

	/**
	 * Containment between every pair of the arithmetic sequences, and between them and
	 * array sequences, matches the subset relation of their values.
	 */
	@Test(timeout = 30000)
	public void containsAllMatchesSubset() {
		List<IndexSequence> all = new ArrayList<>(ARITHMETIC);
		all.add(ArrayIndexSequence.of(new int[] { 0, 3, 3, 9 }));
		all.add(ArrayIndexSequence.of(new int[] { 2, 4, 6, 8 }));
		all.add(ArrayIndexSequence.of(new int[] { 5, 7, 9, 11, 13, 15, 17 }));
		all.add(ArrayIndexSequence.of(new int[] { 4 }));

		for (IndexSequence a : all) {
			for (IndexSequence b : all) {
				boolean expected = positionValues(a).containsAll(positionValues(b));
				Assert.assertEquals(a + " contains all of " + b, expected, a.containsAll(b));
			}
		}
	}

	/**
	 * Only integral values of a floating-point sequence can be members.
	 */
	@Test(timeout = 30000)
	public void nonIntegralValuesAreNotMembers() {
		IndexSequence halves = ArrayIndexSequence.of(Double.class, new double[] { 0.5, 1.0, 2.5 });

		Assert.assertTrue(halves.contains(1));
		Assert.assertFalse(halves.contains(0));
		Assert.assertFalse(halves.contains(2));
		Assert.assertFalse(ArithmeticIndexSequence.range(5).containsAll(halves));
	}

	/**
	 * A union returns a sequence that already holds the other's values, and otherwise holds
	 * each value of both once, in ascending order.
	 */
	@Test(timeout = 30000)
	public void unionHoldsBothValueSets() {
		for (ArithmeticIndexSequence a : ARITHMETIC) {
			for (ArithmeticIndexSequence b : ARITHMETIC) {
				IndexSequence union = a.union(b);
				Set<Long> expected = positionValues(a);
				expected.addAll(positionValues(b));

				Assert.assertEquals(a + " | " + b, expected, positionValues(union));
				Assert.assertEquals(a + " | " + b, expected.size(), union.distinctCount());

				if (a.containsAll(b)) Assert.assertSame(a, union);
				else if (b.containsAll(a)) Assert.assertSame(b, union);
				else Assert.assertArrayEquals(union.longValues().sorted().toArray(), union.longValues().toArray());
			}
		}
	}

	/**
	 * A progression whose values wrap around the {@code long} range is counted and
	 * searched by the values {@link ArithmeticIndexSequence#valueAt(long)} actually
	 * takes, not by the closed forms that assume exact arithmetic.
	 */
	@Test(timeout = 30000)
	public void wrappingProgressionMatchesValues() {
		ArithmeticIndexSequence repeating = new ArithmeticIndexSequence(0, Long.MIN_VALUE, 1, 3, 3);
		ArithmeticIndexSequence overflowing = new ArithmeticIndexSequence(Long.MAX_VALUE, 2, 1, 2, 2);
		ArithmeticIndexSequence descending = new ArithmeticIndexSequence(Long.MIN_VALUE, -1, 1, 3, 3);
		ArithmeticIndexSequence unitAtEdge = new ArithmeticIndexSequence(Long.MAX_VALUE - 1, 1, 1, 4, 4);

		Assert.assertEquals(Set.of(0L, Long.MIN_VALUE), positionValues(repeating));
		Assert.assertEquals(2, repeating.distinctCount());
		Assert.assertTrue(repeating.contains(0));
		Assert.assertTrue(repeating.contains(Long.MIN_VALUE));
		Assert.assertFalse(repeating.contains(Long.MAX_VALUE));

		Assert.assertEquals(Long.MIN_VALUE + 1, overflowing.valueAt(1).longValue());
		Assert.assertEquals(2, overflowing.distinctCount());
		Assert.assertTrue(overflowing.contains(Long.MAX_VALUE));
		Assert.assertTrue(overflowing.contains(Long.MIN_VALUE + 1));
		Assert.assertFalse(overflowing.contains(Long.MIN_VALUE));

		List<ArithmeticIndexSequence> wrapping = List.of(repeating, overflowing, descending, unitAtEdge);

		for (ArithmeticIndexSequence seq : wrapping) {
			Set<Long> values = positionValues(seq);
			Assert.assertEquals(seq.toString(), values.size(), seq.distinctCount());

			for (long v : values) {
				Assert.assertTrue(seq + " contains " + v, seq.contains(v));
				Assert.assertEquals(seq + " contains " + (v ^ 1),
						values.contains(v ^ 1), seq.contains(v ^ 1));
			}
		}

		Assert.assertTrue(unitAtEdge.containsAll(ArrayIndexSequence.of(Long.class,
				new Number[] { Long.MIN_VALUE, Long.MAX_VALUE })));
		Assert.assertFalse(unitAtEdge.containsAll(ArrayIndexSequence.of(Long.class,
				new Number[] { 0L, Long.MAX_VALUE })));

		IndexSequence union = overflowing.union(ArrayIndexSequence.of(new int[] { 7 }));
		Assert.assertEquals(Set.of(Long.MAX_VALUE, Long.MIN_VALUE + 1, 7L), positionValues(union));
		Assert.assertEquals(3, union.distinctCount());
	}

	/**
	 * A union involving non-integral values keeps every value as it is, rather than
	 * truncating it to an integer.
	 */
	@Test(timeout = 30000)
	public void unionPreservesNonIntegralValues() {
		IndexSequence halves = ArrayIndexSequence.of(Double.class, new double[] { 0.5, 1.0, 2.5 });
		IndexSequence union = ArithmeticIndexSequence.range(2).union(halves);

		Assert.assertArrayEquals(new double[] { 0.0, 0.5, 1.0, 2.5 },
				union.doubleValues().distinct().sorted().toArray(), 0.0);
		Assert.assertEquals(4, union.distinctCount());
		Assert.assertFalse(union.contains(2));
		Assert.assertTrue(union.contains(0));

		IndexSequence reversed = halves.union(ArithmeticIndexSequence.range(2));
		Assert.assertArrayEquals(union.doubleValues().toArray(), reversed.doubleValues().toArray(), 0.0);
	}

	/**
	 * Integral values near the ends of the {@code long} range, which a {@code double}
	 * cannot represent exactly, are still members, and a union keeps neighbouring
	 * large values distinct rather than collapsing them through {@code double}.
	 */
	@Test(timeout = 30000)
	public void largeIntegralValuesAreMembers() {
		IndexSequence large = ArrayIndexSequence.of(Long.class,
				new Number[] { Long.MAX_VALUE, Long.MIN_VALUE, 1L });

		Assert.assertTrue(large.contains(Long.MAX_VALUE));
		Assert.assertTrue(large.contains(Long.MIN_VALUE));
		Assert.assertTrue(large.contains(1));
		Assert.assertFalse(large.contains(Long.MAX_VALUE - 1));

		IndexSequence neighbour = ArrayIndexSequence.of(Long.class,
				new Number[] { Long.MAX_VALUE - 1 });
		IndexSequence union = large.union(neighbour);

		Assert.assertEquals(4, union.distinctCount());
		Assert.assertTrue(union.contains(Long.MAX_VALUE));
		Assert.assertTrue(union.contains(Long.MAX_VALUE - 1));
		Assert.assertTrue(union.contains(Long.MIN_VALUE));
		Assert.assertTrue(union.containsAll(large));
		Assert.assertTrue(union.containsAll(neighbour));
	}

	/**
	 * A union that has to enumerate a non-integral value keeps neighbouring large
	 * integers distinct, rather than rounding both to the same {@code double}, and keeps
	 * an integral floating-point value equal to the integer it represents.
	 */
	@Test(timeout = 30000)
	public void mixedUnionKeepsLargeIntegralValuesDistinct() {
		IndexSequence large = ArrayIndexSequence.of(Long.class,
				new Number[] { Long.MAX_VALUE, Long.MAX_VALUE - 1, 3L });
		IndexSequence fractional = ArrayIndexSequence.of(Double.class,
				new double[] { 0.5, 3.0 });

		for (IndexSequence union : new IndexSequence[] { large.union(fractional), fractional.union(large) }) {
			Assert.assertEquals(4, union.distinctCount());
			Assert.assertTrue(union.contains(Long.MAX_VALUE));
			Assert.assertTrue(union.contains(Long.MAX_VALUE - 1));
			Assert.assertTrue(union.contains(3));
			Assert.assertFalse(union.contains(0));
			Assert.assertTrue(union.containsAll(large));
			Assert.assertEquals(0.5, union.valueAt(0).doubleValue(), 0.0);
			Assert.assertEquals(3L, union.valueAt(1).longValue());
			Assert.assertEquals(Long.MAX_VALUE - 1, union.valueAt(2).longValue());
			Assert.assertEquals(Long.MAX_VALUE, union.valueAt(3).longValue());
		}
	}

	/**
	 * The bounds of a union holding integral values as {@link Long}s beside non-integral
	 * ones are found over all of its values, whichever representation its first value
	 * has, so a fractional maximum is rounded up rather than truncated.
	 */
	@Test(timeout = 30000)
	public void mixedUnionBoundsUseEveryValue() {
		IndexSequence halves = ArrayIndexSequence.of(Double.class, new double[] { 0.5, 1.0, 2.5 });

		IndexSequence union = ArithmeticIndexSequence.range(2).union(halves);
		Assert.assertTrue(union.valueAt(0) instanceof Long);
		Assert.assertEquals(3, union.max());
		Assert.assertEquals(0, union.min());

		IndexSequence negative = ArrayIndexSequence.of(Double.class, new double[] { -2.5, 0.5 });
		IndexSequence below = negative.union(ArrayIndexSequence.of(Long.class, new Number[] { 4L }));
		Assert.assertEquals(-2, below.min());
		Assert.assertEquals(4, below.max());

		IndexSequence large = ArrayIndexSequence.of(Long.class,
				new Number[] { Long.MAX_VALUE - 1, -7L });
		IndexSequence mixed = large.union(halves);
		Assert.assertEquals(Long.MAX_VALUE - 1, mixed.max());
		Assert.assertEquals(-7, mixed.min());
	}

	/**
	 * A progression whose values wrap around the {@code long} range over a very large
	 * number of steps is counted and searched from its parameters, without enumerating
	 * its values, and the results agree with wrapping arithmetic.
	 */
	@Test(timeout = 30000)
	public void largeWrappingProgressionIsNotEnumerated() {
		ArithmeticIndexSequence huge = new ArithmeticIndexSequence(
				Long.MAX_VALUE, 1, 1, Long.MAX_VALUE, Long.MAX_VALUE);
		// Values Long.MAX_VALUE, then Long.MIN_VALUE up to -3 after wrapping
		Assert.assertEquals(Long.MAX_VALUE, huge.distinctCount());
		Assert.assertTrue(huge.contains(Long.MAX_VALUE));
		Assert.assertTrue(huge.contains(Long.MIN_VALUE));
		Assert.assertTrue(huge.contains(-3));
		Assert.assertFalse(huge.contains(-2));
		Assert.assertFalse(huge.contains(Long.MAX_VALUE - 1));

		// Scale 2^62 repeats every four steps: offset, offset + 2^62, offset - 2^63, offset - 2^62
		ArithmeticIndexSequence periodic = new ArithmeticIndexSequence(
				5, 1L << 62, 1, Long.MAX_VALUE, Long.MAX_VALUE);
		Assert.assertEquals(4, periodic.distinctCount());
		Assert.assertTrue(periodic.contains(5));
		Assert.assertTrue(periodic.contains(5 + (1L << 62)));
		Assert.assertTrue(periodic.contains(5 + Long.MIN_VALUE));
		Assert.assertTrue(periodic.contains(5 - (1L << 62)));
		Assert.assertFalse(periodic.contains(6));
		Assert.assertFalse(periodic.contains(5 + (1L << 61)));

		Assert.assertTrue(periodic.containsAll(ArrayIndexSequence.of(Long.class,
				new Number[] { 5L, 5 + Long.MIN_VALUE })));
		Assert.assertFalse(periodic.containsAll(ArrayIndexSequence.of(Long.class,
				new Number[] { 5L, 7L })));
	}

	/**
	 * A wrapping progression of enormous length with only four distinct values streams
	 * those four values once each, so its bounds, distinct values and unions with other
	 * sequences are found without enumerating its positions.
	 */
	@Test(timeout = 30000)
	public void periodicWrappingProgressionValuesAreBounded() {
		ArithmeticIndexSequence periodic = new ArithmeticIndexSequence(
				5, 1L << 62, 1, Long.MAX_VALUE, Long.MAX_VALUE);
		long[] expected = { 5 + Long.MIN_VALUE, 5 - (1L << 62), 5, 5 + (1L << 62) };

		Assert.assertArrayEquals(expected, periodic.longValues().sorted().toArray());
		Assert.assertEquals(4, periodic.distinct().length);
		Assert.assertEquals(5 + Long.MIN_VALUE, periodic.min());
		Assert.assertEquals(5 + (1L << 62), periodic.max());

		IndexSequence union = periodic.union(ArrayIndexSequence.of(Long.class, new Number[] { 7L, 5L }));
		Assert.assertArrayEquals(new long[] { 5 + Long.MIN_VALUE, 5 - (1L << 62), 5, 7, 5 + (1L << 62) },
				union.longValues().toArray());

		IndexSequence reversed = ArrayIndexSequence.of(Long.class, new Number[] { 7L, 5L }).union(periodic);
		Assert.assertArrayEquals(union.longValues().toArray(), reversed.longValues().toArray());

		Assert.assertTrue(ArrayIndexSequence.of(Long.class, new Number[] { 5L, 5 + Long.MIN_VALUE,
				5 - (1L << 62), 5 + (1L << 62), 9L }).containsAll(periodic));
		Assert.assertFalse(ArithmeticIndexSequence.range(10).containsAll(periodic));
	}

	/**
	 * Wrapping progressions of several scales and granularities stream each distinct
	 * value once, and those values are exactly the ones taken at their positions.
	 */
	@Test(timeout = 30000)
	public void wrappingValuesMatchPositions() {
		long[] scales = { 1L << 62, 3L << 61, Long.MAX_VALUE, -(1L << 62) };
		int checked = 0;

		for (long scale : scales) {
			for (long granularity = 1; granularity <= 3; granularity++) {
				for (long len = 2; len <= 12; len++) {
					ArithmeticIndexSequence seq = new ArithmeticIndexSequence(
							Long.MAX_VALUE - 1, scale, granularity, len, len);
					if (!wrapsAt(Long.MAX_VALUE - 1, scale, (len - 1) / granularity)) continue;
					checked++;

					Set<Long> positions = positionValues(seq);
					long[] streamed = seq.longValues().toArray();
					Assert.assertEquals(seq.toString(), positions.size(), streamed.length);
					Assert.assertEquals(seq.toString(), positions,
							Arrays.stream(streamed).boxed().collect(Collectors.toSet()));
				}
			}
		}

		Assert.assertTrue(checked > 40);
	}

	/**
	 * For short wrapping progressions with a range of scales, offsets and step counts,
	 * the distinct count and membership derived from the parameters match the values
	 * {@link ArithmeticIndexSequence#valueAt(long)} takes at every position.
	 */
	@Test(timeout = 30000)
	public void wrappingClosedFormsMatchEnumeration() {
		long[] scales = { 3, -5, 1L << 62, -(1L << 62), 3L << 61, Long.MIN_VALUE,
				Long.MAX_VALUE, (Long.MAX_VALUE / 3) * 2 };
		long[] offsets = { 0, 1, Long.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE - 4, -9 };
		int checked = 0;

		for (long scale : scales) {
			for (long offset : offsets) {
				for (long len = 1; len <= 9; len++) {
					ArithmeticIndexSequence seq = new ArithmeticIndexSequence(offset, scale, 1, len, len);
					if (!wrapsAt(offset, scale, len - 1)) continue;
					checked++;

					Set<Long> values = positionValues(seq);
					Assert.assertEquals(seq.toString(), values.size(), seq.distinctCount());

					for (long v : values) {
						Assert.assertTrue(seq + " contains " + v, seq.contains(v));
						for (long probe : new long[] { v + 1, v - 1, v ^ (1L << 62), v + scale }) {
							Assert.assertEquals(seq + " contains " + probe,
									values.contains(probe), seq.contains(probe));
						}
					}
				}
			}
		}

		Assert.assertTrue(checked > 50);
	}

	/**
	 * Returns whether {@code offset + scale * steps} leaves the {@code long} range in
	 * exact arithmetic.
	 */
	private static boolean wrapsAt(long offset, long scale, long steps) {
		try {
			Math.addExact(offset, Math.multiplyExact(scale, steps));
			return false;
		} catch (ArithmeticException e) {
			return true;
		}
	}

	/**
	 * The empty progression takes no value, so it is not constant, while a progression
	 * with a single position is.
	 */
	@Test(timeout = 30000)
	public void emptyProgressionIsNotConstant() {
		Assert.assertFalse(ArithmeticIndexSequence.range(0).isConstant());
		Assert.assertFalse(new ArithmeticIndexSequence(9, 0, 1, 1, 0).isConstant());
		Assert.assertTrue(ArithmeticIndexSequence.range(1).isConstant());
		Assert.assertTrue(new ArithmeticIndexSequence(9, 0, 1, 4, 4).isConstant());
	}

	/**
	 * A progression whose values wrap around the {@code long} range reports the bounds of
	 * the values it actually takes, so that a contiguous progression spanning its wrapped
	 * endpoints does not claim to contain it and a union keeps every one of its values.
	 */
	@Test(timeout = 30000)
	public void wrappingProgressionBounds() {
		// Values 0, Long.MIN_VALUE, 0 (Long.MIN_VALUE * 2 wraps to 0)
		ArithmeticIndexSequence wrapping = new ArithmeticIndexSequence(0, Long.MIN_VALUE, 1, 3, 3);
		Assert.assertEquals(Set.of(0L, Long.MIN_VALUE), positionValues(wrapping));
		Assert.assertEquals(Long.MIN_VALUE, wrapping.min());
		Assert.assertEquals(0, wrapping.max());

		ArithmeticIndexSequence pair = new ArithmeticIndexSequence(0, 1, 1, 2, 2);
		Assert.assertFalse(pair.containsAll(wrapping));
		Assert.assertTrue(wrapping.containsAll(ArithmeticIndexSequence.range(1)));

		IndexSequence union = pair.union(wrapping);
		Assert.assertEquals(Set.of(0L, 1L, Long.MIN_VALUE), positionValues(union));
		Assert.assertTrue(union.contains(Long.MIN_VALUE));
		Assert.assertEquals(Long.MIN_VALUE, union.min());
		Assert.assertEquals(1, union.max());
	}

	/**
	 * Only values a {@code long} represents exactly count as integral: a floating-point
	 * value at or beyond {@code 2^63} is neither a member under {@code Long.MAX_VALUE} nor
	 * saturated into the long range by a union.
	 */
	@Test(timeout = 30000)
	public void outOfRangeFloatingValuesAreNotLongs() {
		double twoTo63 = Math.scalb(1.0, 63);

		Assert.assertTrue(IndexSequence.isLongValue(Long.MAX_VALUE));
		Assert.assertTrue(IndexSequence.isLongValue(Long.MIN_VALUE));
		Assert.assertTrue(IndexSequence.isLongValue(7));
		Assert.assertTrue(IndexSequence.isLongValue(-twoTo63));
		Assert.assertTrue(IndexSequence.isLongValue(3.0));
		Assert.assertFalse(IndexSequence.isLongValue(twoTo63));
		Assert.assertFalse(IndexSequence.isLongValue(3.5));
		Assert.assertFalse(IndexSequence.isLongValue(Double.NaN));
		Assert.assertFalse(IndexSequence.isLongValue(Double.POSITIVE_INFINITY));
		Assert.assertFalse(IndexSequence.isLongValue(Double.NEGATIVE_INFINITY));

		IndexSequence huge = ArrayIndexSequence.of(Double.class, new Number[] { twoTo63 });
		Assert.assertFalse(huge.contains(Long.MAX_VALUE));

		IndexSequence one = ArrayIndexSequence.of(Long.class, new Number[] { 1L });
		Assert.assertFalse(one.containsAll(huge));

		IndexSequence union = one.union(huge);
		Assert.assertEquals(2, union.distinctCount());
		Assert.assertTrue(union.contains(1));
		Assert.assertFalse(union.contains(Long.MAX_VALUE));
		Assert.assertEquals(twoTo63, union.doubleValues().max().orElseThrow(), 0.0);
	}

	/**
	 * Returns the set of values a sequence takes over all of its positions.
	 */
	private Set<Long> positionValues(IndexSequence seq) {
		return LongStream.range(0, seq.lengthLong())
				.mapToObj(seq::valueAt)
				.filter(v -> v.doubleValue() == v.longValue())
				.map(Number::longValue)
				.collect(Collectors.toSet());
	}
}
