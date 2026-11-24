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
import io.almostrealism.sequence.IndexSequence;
import io.almostrealism.sequence.Sequence;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests that {@link ArithmeticIndexSequence} keeps the contracts of {@link IndexSequence#subset(long)}
 * and {@link Sequence#toArray()}, computing both from its parameters like every other member.
 */
public class ArithmeticIndexSequenceTest extends TestSuiteBase {

	/**
	 * A subset of a wrapping sequence takes exactly its first values. It keeps the period
	 * while that still wraps within the shorter length, stores a period that no longer
	 * wraps as that length, and is the sequence itself at the full length.
	 */
	@Test(timeout = 10000)
	public void subsetKeepsTheFirstValues() {
		ArithmeticIndexSequence seq = new ArithmeticIndexSequence(3, 2, 2, 10, 40);

		for (long len : new long[] { 1, 7, 10, 25 }) {
			IndexSequence subset = seq.subset(len);
			Assert.assertEquals(len, subset.lengthLong());

			for (long pos = 0; pos < len; pos++) {
				Assert.assertEquals("mismatch at position " + pos + " of " + len,
						seq.valueAt(pos).longValue(), subset.valueAt(pos).longValue());
			}
		}

		Assert.assertEquals(7, seq.subset(7).getMod());
		Assert.assertEquals(10, seq.subset(25).getMod());
		Assert.assertSame(seq, seq.subset(40));
	}

	/**
	 * A subset can be neither empty nor longer than the sequence it is taken from.
	 */
	@Test(timeout = 10000)
	public void subsetRejectsLengthsOutsideTheSequence() {
		ArithmeticIndexSequence seq = new ArithmeticIndexSequence(1, 1, 8);

		for (long len : new long[] { 0, 9 }) {
			try {
				seq.subset(len);
				Assert.fail("A subset of length " + len + " must be rejected");
			} catch (IllegalArgumentException expected) {
				Assert.assertTrue(expected.getMessage().contains(String.valueOf(len)));
			}
		}
	}

	/**
	 * The array form holds the value at every position, wrapping and repeating exactly as
	 * {@link ArithmeticIndexSequence#valueAt(long)} does, and a length beyond the
	 * {@code int} range is reported rather than materialized.
	 */
	@Test(timeout = 10000)
	public void toArrayHoldsEveryValue() {
		ArithmeticIndexSequence seq = new ArithmeticIndexSequence(-5, 3, 2, 6, 20);
		Number[] values = seq.toArray();

		Assert.assertEquals(20, values.length);

		for (int pos = 0; pos < values.length; pos++) {
			Assert.assertEquals("mismatch at position " + pos,
					seq.valueAt(pos).longValue(), values[pos].longValue());
		}

		try {
			new ArithmeticIndexSequence(1, 1, 1L << 32).toArray();
			Assert.fail("A length beyond the int range cannot be materialized");
		} catch (ArithmeticException expected) {
			Assert.assertNotNull(expected.getMessage());
		}
	}
}
