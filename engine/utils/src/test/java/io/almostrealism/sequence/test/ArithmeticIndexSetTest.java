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

import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.expression.Expression;
import io.almostrealism.kernel.KernelIndex;
import io.almostrealism.kernel.NoOpKernelStructureContext;
import io.almostrealism.sequence.ArithmeticIndexSet;
import io.almostrealism.sequence.DefaultIndex;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Tests for {@link ArithmeticIndexSet}, the set of index values in which
 * {@link Expression#getIndexOptions(io.almostrealism.sequence.Index)} describes the domain of
 * an expression.
 *
 * <p>The set must hold exactly the values of the loop {@code start + k * step} for
 * {@code k < count} that the index-option analysis enumerated before the set existed, so that
 * every decision taken from the domain is unchanged; it only stops enumerating them. The one
 * exception is a progression whose values are not all {@code int} values, which is refused
 * instead of being wrapped around the {@code int} range: such values are not index values,
 * and an expression whose options would include them has none.</p>
 */
public class ArithmeticIndexSetTest extends TestSuiteBase implements ExpressionFeatures {

	/**
	 * Returns the values {@code start + k * step} for every {@code k} below {@code count},
	 * enumerated one by one.
	 *
	 * @param start the first value
	 * @param step  the difference between consecutive values
	 * @param count the number of values
	 * @return the enumerated values
	 */
	private static Set<Integer> enumerate(int start, int step, int count) {
		Set<Integer> values = new HashSet<>();
		for (int k = 0; k < count; k++) {
			values.add(start + k * step);
		}

		return values;
	}

	/**
	 * A progression holds exactly the values of its loop: it is equal to them in both
	 * directions, reports their number, contains each of them and nothing between or around
	 * them, and iterates them in ascending order.
	 */
	@Test(timeout = 30000)
	public void progressionsHoldTheValuesOfTheirLoop() {
		for (int start : new int[] { -7, 0, 3 }) {
			for (int step : new int[] { 1, 2, 5, 128 }) {
				for (int count : new int[] { 1, 2, 3, 17, 100 }) {
					Set<Integer> expected = enumerate(start, step, count);
					Set<Integer> actual = ArithmeticIndexSet.of(start, step, count);
					String label = "start=" + start + " step=" + step + " count=" + count;

					Assert.assertEquals(label, expected, actual);
					Assert.assertEquals(label, actual, expected);
					Assert.assertEquals(label, expected.size(), actual.size());
					Assert.assertEquals(label, expected.hashCode(), actual.hashCode());

					for (int v = start - 2 * step - 1; v <= start + (count + 1) * step + 1; v++) {
						Assert.assertEquals(label + " value=" + v, expected.contains(v), actual.contains(v));
					}

					List<Integer> ascending = new ArrayList<>(expected);
					Collections.sort(ascending);
					Assert.assertEquals(label, ascending, new ArrayList<>(actual));
				}
			}
		}
	}

	/**
	 * A progression of no values is empty, one of a single value or of step zero holds only
	 * its start, a descending loop holds the same values as the ascending progression ending
	 * at its start, and a progression reaching either end of the {@code int} range holds
	 * exactly the values of its loop.
	 */
	@Test(timeout = 30000)
	public void degenerateProgressionsMatchTheirLoop() {
		Assert.assertTrue(ArithmeticIndexSet.of(4, 3, 0).isEmpty());
		Assert.assertTrue(ArithmeticIndexSet.of(4, 3, -2).isEmpty());
		Assert.assertTrue(ArithmeticIndexSet.range(0).isEmpty());
		Assert.assertFalse(ArithmeticIndexSet.range(0).contains(0));
		Assert.assertEquals(Set.of(9), ArithmeticIndexSet.of(9, 5, 1));
		Assert.assertEquals(Set.of(9), ArithmeticIndexSet.of(9, 0, 6));

		Assert.assertEquals(Set.of(10, 7, 4, 1), ArithmeticIndexSet.of(10, -3, 4));
		Assert.assertEquals(List.of(1, 4, 7, 10), new ArrayList<>(ArithmeticIndexSet.of(10, -3, 4)));
		Assert.assertEquals(enumerate(Integer.MAX_VALUE - 4, 2, 3),
				ArithmeticIndexSet.of(Integer.MAX_VALUE - 4, 2, 3));
		Assert.assertEquals(enumerate(Integer.MIN_VALUE, 1 << 30, 4),
				ArithmeticIndexSet.of(Integer.MIN_VALUE, 1 << 30, 4));
		Assert.assertEquals(enumerate(Integer.MIN_VALUE + 2, -1, 3),
				ArithmeticIndexSet.of(Integer.MIN_VALUE + 2, -1, 3));
	}

	/**
	 * A progression with a value beyond the {@code int} range, or whose consecutive values
	 * differ by more than an {@code int} can hold, is refused rather than wrapped around the
	 * range as {@code int} arithmetic would wrap it.
	 */
	@Test(timeout = 30000)
	public void progressionsBeyondTheIntRangeAreRefused() {
		int[][] refused = {
				{ 0, 1 << 30, 4 },
				{ Integer.MAX_VALUE - 3, 2, 3 },
				{ Integer.MIN_VALUE + 1, -1, 3 },
				{ 0, Integer.MIN_VALUE, 2 }
		};

		for (int[] p : refused) {
			try {
				ArithmeticIndexSet.of(p[0], p[1], p[2]);
				Assert.fail("start=" + p[0] + " step=" + p[1] + " count=" + p[2] + " was not refused");
			} catch (ArithmeticException expected) {
				log("start=" + p[0] + " step=" + p[1] + " count=" + p[2] + " refused: " + expected.getMessage());
			}
		}
	}

	/**
	 * Whether one progression contains another, which is decided from their parameters, agrees
	 * with checking every value of the other one by one, including for empty and descending
	 * progressions, and a progression answers the same for a collection that is not a
	 * progression.
	 */
	@Test(timeout = 30000)
	public void containmentAgreesWithValueByValueChecks() {
		int[] starts = { 0, 1, 2, 3 };
		int[] steps = { -2, 1, 2, 3, 4, 6 };
		int[] counts = { 0, 1, 2, 3, 5 };

		for (int s1 : starts) for (int d1 : steps) for (int n1 : counts) {
			Set<Integer> a = ArithmeticIndexSet.of(s1, d1, n1);
			Set<Integer> enumeratedA = enumerate(s1, d1, n1);

			for (int s2 : starts) for (int d2 : steps) for (int n2 : counts) {
				Set<Integer> b = ArithmeticIndexSet.of(s2, d2, n2);
				Set<Integer> enumeratedB = enumerate(s2, d2, n2);
				String label = "{" + s1 + "+" + d1 + "k, " + n1 + "} contains {" + s2 + "+" + d2 + "k, " + n2 + "}";

				Assert.assertEquals(label, enumeratedA.containsAll(enumeratedB), a.containsAll(b));
				Assert.assertEquals(label, enumeratedA.containsAll(enumeratedB), a.containsAll(enumeratedB));
			}
		}
	}

	/**
	 * An expression shares the set of a child whose options hold the options of the others,
	 * and forms a new set only when none does: the product of the kernel index and its
	 * residues modulo four keeps the progression of every kernel index value, the product of
	 * those residues and the quotients by four holds the values of both, and multiplying that
	 * by the kernel index again yields the progression of every value once more.
	 */
	@Test(timeout = 30000)
	public void childDomainsAreSharedWhenOneHoldsTheOthers() {
		KernelIndex k = kernel().withLimit(12);

		Set<Integer> held = k.multiply(k.imod(4)).getIndexOptions(k).orElseThrow();
		Assert.assertTrue(held instanceof ArithmeticIndexSet);
		Assert.assertEquals(enumerate(0, 1, 12), held);

		Expression<?> residuesByQuotients = k.imod(4).multiply(k.divide(4));
		Assert.assertEquals(Set.of(0, 1, 2, 3, 4, 8), residuesByQuotients.getIndexOptions(k).orElseThrow());

		Set<Integer> regained = residuesByQuotients.multiply(k).getIndexOptions(k).orElseThrow();
		Assert.assertTrue(regained instanceof ArithmeticIndexSet);
		Assert.assertEquals(enumerate(0, 1, 12), regained);
	}

	/**
	 * A quotient whose multiples of the divisor are not all {@code int} values has no options,
	 * as {@link Expression#getIndexOptions(io.almostrealism.sequence.Index)} documents, rather
	 * than options wrapped around the {@code int} range; one whose multiples are all
	 * {@code int} values holds every one of them.
	 */
	@Test(timeout = 30000)
	public void quotientMultiplesBeyondTheIntRangeHaveNoOptions() {
		DefaultIndex wide = new DefaultIndex("wide", 1L << 40);
		Assert.assertEquals(Optional.empty(), wide.divide(1 << 30).getIndexOptions(wide));

		DefaultIndex narrow = new DefaultIndex("narrow", 1L << 30);
		Assert.assertEquals(enumerate(0, 1 << 20, 1 << 10),
				narrow.divide(1 << 20).getIndexOptions(narrow).orElseThrow());
	}

	/**
	 * The domain of the kernel index is described for every expression built from it without
	 * being enumerated: index arithmetic over a sixteen thousand value kernel, the size of a
	 * 128 by 128 image, has the full domain as a progression, and the multiples a quotient
	 * requires are a progression too.
	 */
	@Test(timeout = 30000)
	public void largeDomainsAreDescribedWithoutEnumeration() {
		int limit = 128 * 128;
		KernelIndex k = kernel().withLimit(limit);

		Set<Integer> pixels = k.multiply(3).add(1).getIndexOptions(k).orElseThrow();
		Assert.assertTrue(pixels instanceof ArithmeticIndexSet);
		Assert.assertEquals(limit, pixels.size());
		Assert.assertTrue(pixels.contains(limit - 1));
		Assert.assertFalse(pixels.contains(limit));

		Set<Integer> rows = k.divide(128).getIndexOptions(k).orElseThrow();
		Assert.assertTrue(rows instanceof ArithmeticIndexSet);
		Assert.assertEquals(enumerate(0, 128, 128), rows);

		Set<Integer> columns = k.imod(128).multiply(k.imod(128)).getIndexOptions(k).orElseThrow();
		Assert.assertTrue(columns instanceof ArithmeticIndexSet);
		Assert.assertEquals(enumerate(0, 1, 128), columns);
	}

	/**
	 * A comparison whose value is the same at every value of its enumerable domain still
	 * folds to that constant, and one whose value varies over the domain does not: the
	 * squares of the residues modulo four are zero or one, so they are always below two but
	 * not always below one.
	 */
	@Test(timeout = 30000)
	public void comparisonsFoldOverTheirDomain() {
		KernelIndex k = kernel();
		Expression<?> square = k.imod(4).multiply(k.imod(4)).imod(4);

		Expression<?> belowTwo = square.lessThan(2).simplify(new NoOpKernelStructureContext(64));
		Assert.assertEquals(Optional.of(true), belowTwo.booleanValue());

		Expression<?> belowOne = square.lessThan(1).simplify(new NoOpKernelStructureContext(64));
		Assert.assertEquals(Optional.empty(), belowOne.booleanValue());
	}
}
