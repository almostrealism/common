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

package org.almostrealism.color.test;

import org.almostrealism.color.ProbabilityDistribution;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for the non-overlap invariant that
 * {@link ProbabilityDistribution#addRange(double, double, double)} is documented
 * to enforce: adding a range that overlaps an existing one must throw
 * {@link IllegalArgumentException} rather than silently accepting it.
 *
 * <p>The overlap check walks the interval tree with
 * {@code ProbabilityDistribution.Node.contains(start, end)}. A range that fully
 * <em>envelops</em> an existing range overlaps it, but neither of the new range's
 * bounds falls inside the existing interval, so an endpoint-only membership test
 * fails to detect the overlap — the new range is then neither rejected nor
 * inserted (the tree's {@code add} drops a node it cannot place on either side),
 * leaving the overlap silently unreported.</p>
 */
public class ProbabilityDistributionTest extends TestSuiteBase {

	/**
	 * A range that fully envelops an existing range overlaps it and must be
	 * rejected. This is the enveloping case an endpoint-only membership test
	 * misses: neither {@code 0} nor {@code 10} lies inside {@code [2, 3]}, yet
	 * {@code [0, 10]} clearly overlaps {@code [2, 3]}.
	 */
	@Test(timeout = 5000)
	public void envelopingRangeIsRejectedAsOverlap() {
		ProbabilityDistribution d = new ProbabilityDistribution();
		d.addRange(2.0, 3.0, 0.5);

		try {
			d.addRange(0.0, 10.0, 0.5);
			Assert.fail("Adding a range that envelops an existing range must throw "
					+ "IllegalArgumentException, not silently accept the overlap");
		} catch (IllegalArgumentException expected) {
			// expected — the enveloping range overlaps [2, 3]
		}

		Assert.assertEquals("The rejected range must not have been inserted",
				1, d.getNodeCount());
	}

	/**
	 * A new range inside an existing range (the endpoint-inside case the original
	 * check already handled) must still be rejected — a control confirming the
	 * corrected overlap test does not lose previously-detected overlaps.
	 */
	@Test(timeout = 5000)
	public void containedRangeIsRejectedAsOverlap() {
		ProbabilityDistribution d = new ProbabilityDistribution();
		d.addRange(0.0, 10.0, 0.5);

		try {
			d.addRange(2.0, 3.0, 0.5);
			Assert.fail("Adding a range contained within an existing range must throw");
		} catch (IllegalArgumentException expected) {
			// expected
		}

		Assert.assertEquals(1, d.getNodeCount());
	}

	/**
	 * A partially-overlapping range must be rejected — a control for the common
	 * overlap case.
	 */
	@Test(timeout = 5000)
	public void partiallyOverlappingRangeIsRejected() {
		ProbabilityDistribution d = new ProbabilityDistribution();
		d.addRange(0.0, 5.0, 0.5);

		try {
			d.addRange(3.0, 8.0, 0.5);
			Assert.fail("Adding a range that partially overlaps an existing range must throw");
		} catch (IllegalArgumentException expected) {
			// expected
		}

		Assert.assertEquals(1, d.getNodeCount());
	}

	/**
	 * Genuinely disjoint ranges must still be accepted — a control confirming the
	 * corrected overlap test does not over-reject non-overlapping ranges.
	 */
	@Test(timeout = 5000)
	public void disjointRangesAreAccepted() {
		ProbabilityDistribution d = new ProbabilityDistribution();
		d.addRange(2.0, 3.0, 0.5);
		d.addRange(5.0, 6.0, 0.5);
		d.addRange(0.0, 1.0, 0.5);

		Assert.assertEquals("All three disjoint ranges must be inserted",
				3, d.getNodeCount());
	}
}
