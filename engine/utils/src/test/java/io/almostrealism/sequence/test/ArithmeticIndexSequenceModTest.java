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
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for {@link ArithmeticIndexSequence#mod(long)}.
 *
 * <p>{@link ArithmeticIndexSequence#mod(long)} is documented as an optimization
 * that returns an equivalent {@link ArithmeticIndexSequence} when it can, and
 * "otherwise falls back to the default element-wise modulo operation." Whichever
 * path it takes, the contract of {@link IndexSequence#mod(long)} is element-wise:
 * the result at every position must equal {@code valueAt(pos) % m}. These tests
 * pin that equivalence for sequences with granularity greater than one, where the
 * modulus is a multiple of {@code m} but not of {@code granularity * m}.</p>
 */
public class ArithmeticIndexSequenceModTest extends TestSuiteBase {

	/**
	 * Reducing a granularity-2 sequence whose modulus (6) is a multiple of the
	 * reduction modulus (2) but not of {@code granularity * m} (4) must still
	 * produce the element-wise remainder at every position.
	 *
	 * <p>The base sequence {@code offset=0, scale=1, granularity=2, mod=6, len=8}
	 * is {@code [0, 0, 1, 1, 2, 2, 0, 0]}. Reduced modulo 2 it must be
	 * {@code [0, 0, 1, 1, 0, 0, 0, 0]}. The fast path may only be taken when it
	 * reproduces exactly this.</p>
	 */
	@Test(timeout = 10000)
	public void granularTwoModTwoMatchesElementwise() {
		ArithmeticIndexSequence seq = new ArithmeticIndexSequence(0, 1, 2, 6, 8);
		IndexSequence reduced = seq.mod(2);

		for (long pos = 0; pos < 8; pos++) {
			long expected = seq.valueAt(pos).longValue() % 2;
			Assert.assertEquals("mismatch at position " + pos,
					expected, reduced.valueAt(pos).longValue());
		}
	}

	/**
	 * A second, independent case: granularity 3, modulus 12 (a multiple of the
	 * reduction modulus 2 but not of {@code granularity * m = 6}). The base
	 * sequence is {@code [0,0,0,1,1,1,2,2,2,3,3,3]} over its first 12 positions;
	 * reduced modulo 2 it must be {@code [0,0,0,1,1,1,0,0,0,1,1,1]}.
	 */
	@Test(timeout = 10000)
	public void granularThreeModTwoMatchesElementwise() {
		ArithmeticIndexSequence seq = new ArithmeticIndexSequence(0, 1, 3, 12, 12);
		IndexSequence reduced = seq.mod(2);

		for (long pos = 0; pos < 12; pos++) {
			long expected = seq.valueAt(pos).longValue() % 2;
			Assert.assertEquals("mismatch at position " + pos,
					expected, reduced.valueAt(pos).longValue());
		}
	}

	/**
	 * The fast path is still exercised and correct when the modulus <em>is</em> a
	 * multiple of {@code granularity * m}: {@code mod=8, granularity=2, m=2}. The
	 * base sequence {@code [0,0,1,1,2,2,3,3]} reduced modulo 2 is
	 * {@code [0,0,1,1,0,0,1,1]}. This guards against a fix that simply disables
	 * the optimization entirely.
	 */
	@Test(timeout = 10000)
	public void alignedModulusMatchesElementwise() {
		ArithmeticIndexSequence seq = new ArithmeticIndexSequence(0, 1, 2, 8, 8);
		IndexSequence reduced = seq.mod(2);

		for (long pos = 0; pos < 8; pos++) {
			long expected = seq.valueAt(pos).longValue() % 2;
			Assert.assertEquals("mismatch at position " + pos,
					expected, reduced.valueAt(pos).longValue());
		}
	}
}
