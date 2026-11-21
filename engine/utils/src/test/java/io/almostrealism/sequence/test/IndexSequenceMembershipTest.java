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
