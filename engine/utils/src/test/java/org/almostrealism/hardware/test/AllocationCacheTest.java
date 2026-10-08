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

package org.almostrealism.hardware.test;

import org.almostrealism.hardware.mem.AllocationCache;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests for {@link AllocationCache}, using strings as stand-ins for native handles so that
 * no device is needed.
 */
public class AllocationCacheTest extends TestSuiteBase {

	/** A handle that is offered is handed back for an allocation of exactly its size only. */
	@Test(timeout = 10_000)
	public void reusesOnlyExactSize() {
		AllocationCache<String> cache = new AllocationCache<>(1024, 512);

		Assert.assertNull(cache.take(64));
		Assert.assertTrue(cache.offer(64, "a"));
		Assert.assertEquals(64, cache.getHeldBytes());

		Assert.assertNull(cache.take(128));
		Assert.assertEquals("a", cache.take(64));
		Assert.assertNull(cache.take(64));
		Assert.assertEquals(0, cache.getHeldBytes());
	}

	/** A handle larger than the entry limit, or one that would exceed capacity, is declined. */
	@Test(timeout = 10_000)
	public void declinesBeyondLimits() {
		AllocationCache<String> cache = new AllocationCache<>(256, 128);

		Assert.assertFalse(cache.offer(129, "large"));
		Assert.assertTrue(cache.offer(128, "a"));
		Assert.assertTrue(cache.offer(128, "b"));
		Assert.assertFalse(cache.offer(1, "c"));
		Assert.assertEquals(256, cache.getHeldBytes());
	}

	/** Flushing releases every held handle, and the cache keeps accepting afterwards. */
	@Test(timeout = 10_000)
	public void flushReleasesEverything() {
		AllocationCache<String> cache = new AllocationCache<>(1024, 512);
		cache.offer(64, "a");
		cache.offer(64, "b");
		cache.offer(32, "c");

		List<String> released = new ArrayList<>();
		cache.flush(released::add);

		Assert.assertEquals(3, released.size());
		Assert.assertTrue(released.containsAll(List.of("a", "b", "c")));
		Assert.assertEquals(0, cache.getHeldBytes());
		Assert.assertNull(cache.take(64));

		Assert.assertTrue(cache.offer(64, "d"));
	}

	/** Every handle is released even if releasing one fails, and the failure is reported. */
	@Test(timeout = 10_000)
	public void flushReleasesPastFailure() {
		AllocationCache<String> cache = new AllocationCache<>(1024, 512);
		cache.offer(16, "a");
		cache.offer(16, "b");
		cache.offer(16, "c");

		List<String> released = new ArrayList<>();
		try {
			cache.flush(h -> {
				released.add(h);
				if (released.size() == 1) throw new IllegalStateException("failed");
			});
			Assert.fail("The failure should be rethrown");
		} catch (IllegalStateException e) {
			Assert.assertEquals("failed", e.getMessage());
		}

		Assert.assertEquals(3, released.size());
		Assert.assertEquals(0, cache.getHeldBytes());
	}

	/** A closed cache releases what it holds and declines everything offered afterwards. */
	@Test(timeout = 10_000)
	public void closeDeclinesLaterOffers() {
		AllocationCache<String> cache = new AllocationCache<>(1024, 512);
		cache.offer(64, "a");

		List<String> released = new ArrayList<>();
		cache.close(released::add);

		Assert.assertEquals(List.of("a"), released);
		Assert.assertFalse(cache.offer(64, "b"));
		Assert.assertNull(cache.take(64));
	}
}
