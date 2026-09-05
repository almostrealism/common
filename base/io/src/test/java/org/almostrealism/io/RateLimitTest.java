/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.io;

import org.junit.Assert;
import org.junit.Test;

import java.time.Duration;

/**
 * Locks the behavior of {@link RateLimit}.
 *
 * <p>The window slides rather than accumulating, which is the whole reason
 * this class exists: the SMS limit it replaced was a per-JVM-lifetime counter
 * that, once filled, silenced a long-running controller permanently.</p>
 */
public class RateLimitTest {

	/** A budget is spent one reservation at a time and then refused. */
	@Test(timeout = 10000)
	public void testBudgetIsExhaustedThenRefused() {
		RateLimit limit = new RateLimit(3, Duration.ofHours(1));

		Assert.assertTrue(limit.reserve());
		Assert.assertTrue(limit.reserve());
		Assert.assertTrue(limit.reserve());
		Assert.assertFalse(limit.reserve());
	}

	/** Remaining tracks what has been reserved and never goes negative. */
	@Test(timeout = 10000)
	public void testRemainingTracksReservations() {
		RateLimit limit = new RateLimit(2, Duration.ofHours(1));
		Assert.assertEquals(2, limit.remaining());

		limit.reserve();
		Assert.assertEquals(1, limit.remaining());

		limit.reserve();
		limit.reserve();
		Assert.assertEquals(0, limit.remaining());
	}

	/** Keys hold independent budgets, so one caller cannot spend another's. */
	@Test(timeout = 10000)
	public void testKeysAreIndependent() {
		RateLimit limit = new RateLimit(1, Duration.ofHours(1));

		Assert.assertTrue(limit.reserve("michael"));
		Assert.assertFalse(limit.reserve("michael"));
		Assert.assertTrue(limit.reserve("mmurray"));
	}

	/** A zero-width window lets every reservation through, as it has no past. */
	@Test(timeout = 10000)
	public void testExpiredReservationsAreReclaimed() {
		RateLimit limit = new RateLimit(1, Duration.ZERO);

		Assert.assertTrue(limit.reserve());
		Assert.assertTrue("a reservation older than the window must be reclaimed",
				limit.reserve());
	}

	/** A non-positive maximum permits nothing rather than everything. */
	@Test(timeout = 10000)
	public void testNonPositiveMaximumPermitsNothing() {
		Assert.assertFalse(new RateLimit(0, Duration.ofHours(1)).reserve());
		Assert.assertFalse(new RateLimit(-5, Duration.ofHours(1)).reserve());
	}

	/** Clearing restores the full budget. */
	@Test(timeout = 10000)
	public void testClearRestoresBudget() {
		RateLimit limit = new RateLimit(1, Duration.ofHours(1));
		limit.reserve();
		Assert.assertFalse(limit.reserve());

		limit.clear();
		Assert.assertTrue(limit.reserve());
	}

	/** A null key draws on the same budget as the unkeyed reservation. */
	@Test(timeout = 10000)
	public void testNullKeyIsTheUnkeyedBudget() {
		RateLimit limit = new RateLimit(1, Duration.ofHours(1));

		Assert.assertTrue(limit.reserve());
		Assert.assertFalse(limit.reserve(null));
	}
}
