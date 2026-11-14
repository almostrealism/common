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

import io.almostrealism.sequence.ArrayIndexSequence;
import io.almostrealism.sequence.IndexSequence;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests the bounding contract of {@link IndexSequence#min()} and
 * {@link IndexSequence#max()} for floating-point sequences.
 *
 * <p>{@link IndexSequence#min()} is documented as returning "the minimum value in
 * this sequence" and {@link IndexSequence#max()} "the maximum value". Because the
 * result is returned as a {@code long}, a fractional value must be rounded, and the
 * only rounding that preserves the meaning of the words is outward: a minimum rounds
 * <em>down</em> so it never exceeds any element (a lower bound), and a maximum rounds
 * <em>up</em> so it never falls below any element (an upper bound). {@code max()}
 * rounds up with {@code Math.ceil}; the symmetric choice for {@code min()} is
 * {@code Math.floor}.</p>
 */
public class IndexSequenceMinTest {

	/**
	 * A floating-point sequence whose minimum is {@code -2.5}. {@code min()} must not
	 * report a value greater than an element that is actually present: {@code -2.5}
	 * is in the sequence, so {@code min() <= -2.5} must hold. Rounding the minimum up
	 * to {@code -2} violates this, because {@code -2 > -2.5} — the reported "minimum"
	 * is larger than a real element.
	 */
	@Test(timeout = 10000)
	public void minimumIsALowerBoundForFractionalValues() {
		IndexSequence seq = ArrayIndexSequence.of(Double.class, new double[] { -2.5, 10.0 });

		long min = seq.min();

		Assert.assertTrue(
				"min() == " + min + " but the sequence contains -2.5, so the reported "
						+ "minimum is greater than an actual element",
				min <= -2.5);
	}

	/**
	 * The minimum must be a lower bound for <em>every</em> element of the sequence,
	 * mirroring the upper-bound guarantee that {@link IndexSequence#max()} already
	 * provides. This pins the symmetry between the two methods.
	 */
	@Test(timeout = 10000)
	public void minAndMaxBoundEveryElement() {
		double[] values = { -2.5, 1.25, 7.75 };
		IndexSequence seq = ArrayIndexSequence.of(Double.class, values);

		long min = seq.min();
		long max = seq.max();

		for (double v : values) {
			Assert.assertTrue("min() == " + min + " is not <= element " + v, min <= v);
			Assert.assertTrue("max() == " + max + " is not >= element " + v, max >= v);
		}
	}
}
