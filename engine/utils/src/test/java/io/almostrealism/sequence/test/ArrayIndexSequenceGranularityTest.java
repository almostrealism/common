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
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for {@link ArrayIndexSequence#getGranularity()}.
 *
 * <p>Granularity is documented as "the number of consecutive index positions
 * that share the same value," detected by finding the first position where the
 * value changes and then verifying "that this pattern holds throughout the
 * sequence," returning {@code 1} when no consistent pattern is found. The
 * defining consequence is that if {@code getGranularity()} returns {@code g},
 * then any two positions {@code p} and {@code q} with {@code p / g == q / g}
 * lie in the same block and must hold the same value.</p>
 *
 * <p>These tests pin that invariant for sequences whose length is not an exact
 * multiple of the detected granularity, so that the detection's verification
 * must examine the trailing partial block rather than skipping it.</p>
 */
public class ArrayIndexSequenceGranularityTest extends TestSuiteBase {

	/**
	 * Asserts the defining invariant of {@link ArrayIndexSequence#getGranularity()}:
	 * for the reported granularity {@code g}, every pair of positions in the same
	 * block of {@code g} consecutive positions holds the same value.
	 *
	 * @param seq the sequence to check
	 */
	private void assertGranularityInvariant(IndexSequence seq) {
		int g = seq.getGranularity();
		long len = seq.lengthLong();

		for (long p = 0; p < len; p++) {
			for (long q = p + 1; q < len && q / g == p / g; q++) {
				Assert.assertEquals(
						"positions " + p + " and " + q + " share block " + (p / g)
								+ " under reported granularity " + g
								+ " but hold different values",
						seq.valueAt(p).doubleValue(), seq.valueAt(q).doubleValue(), 0.0);
			}
		}
	}

	/**
	 * The sequence {@code [0, 0, 0, 2, 3]} has consecutive-run lengths 3, 1, 1, so
	 * no single granularity greater than one holds throughout it: its correct
	 * granularity is {@code 1}. The first value change is at position 3, which
	 * makes the detector consider a granularity of 3; the trailing partial block
	 * (positions 3 and 4, values 2 and 3) must then be rejected rather than
	 * ignored. Reporting 3 would place positions 3 and 4 in the same block while
	 * they hold different values.
	 */
	@Test(timeout = 10000)
	public void trailingPartialBlockBreaksGranularity() {
		IndexSequence seq = ArrayIndexSequence.of(new int[] { 0, 0, 0, 2, 3 });

		assertGranularityInvariant(seq);
		Assert.assertEquals(1, seq.getGranularity());
	}

	/**
	 * A second case where the first change is further in: {@code [0, 0, 0, 0, 1, 2]}
	 * (run lengths 4, 1, 1). The detector considers a granularity of 4, whose
	 * trailing partial block spans positions 4 and 5 (values 1 and 2); because that
	 * block is not internally constant the correct granularity is {@code 1}.
	 */
	@Test(timeout = 10000)
	public void trailingPartialBlockLongerPrefix() {
		IndexSequence seq = ArrayIndexSequence.of(new int[] { 0, 0, 0, 0, 1, 2 });

		assertGranularityInvariant(seq);
		Assert.assertEquals(1, seq.getGranularity());
	}

	/**
	 * A trailing partial block that <em>is</em> internally constant must still be
	 * accepted: {@code [0, 0, 0, 1, 1, 1, 2]} has full blocks {@code [0,0,0]} and
	 * {@code [1,1,1]} followed by a partial block of the single value {@code 2}, so
	 * the granularity is 3. This guards against a fix that rejects every partial
	 * block instead of only the ones that are not constant.
	 */
	@Test(timeout = 10000)
	public void constantTrailingPartialBlockIsAccepted() {
		IndexSequence seq = ArrayIndexSequence.of(new int[] { 0, 0, 0, 1, 1, 1, 2 });

		assertGranularityInvariant(seq);
		Assert.assertEquals(3, seq.getGranularity());
	}

	/**
	 * A sequence whose length is an exact multiple of its granularity is unaffected:
	 * {@code [0, 0, 1, 1, 2, 2]} has granularity 2.
	 */
	@Test(timeout = 10000)
	public void exactMultipleGranularityIsUnchanged() {
		IndexSequence seq = ArrayIndexSequence.of(new int[] { 0, 0, 1, 1, 2, 2 });

		assertGranularityInvariant(seq);
		Assert.assertEquals(2, seq.getGranularity());
	}
}
