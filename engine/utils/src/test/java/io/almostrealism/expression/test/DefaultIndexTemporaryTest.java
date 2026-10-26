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

package io.almostrealism.expression.test;

import io.almostrealism.sequence.DefaultIndex;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Tests for {@link DefaultIndex#temporary()} and {@link DefaultIndex#temporary(int)}.
 */
public class DefaultIndexTemporaryTest extends TestSuiteBase {

	/**
	 * A temporary index is named {@code ci_N}, is unbounded unless a limit is given, and
	 * takes the given limit as its exclusive upper bound when one is.
	 */
	@Test(timeout = 10000)
	public void namesAndLimits() {
		DefaultIndex unbounded = DefaultIndex.temporary();
		DefaultIndex bounded = DefaultIndex.temporary(7);

		Assert.assertTrue(unbounded.getName().matches("ci_\\d+"));
		Assert.assertTrue(bounded.getName().matches("ci_\\d+"));
		Assert.assertNotEquals(unbounded.getName(), bounded.getName());
		Assert.assertFalse(unbounded.getLimit().isPresent());
		Assert.assertEquals(7, bounded.getLimit().getAsLong());
		Assert.assertEquals(6, bounded.upperBound(null).getAsLong());
	}

	/**
	 * Temporary indices created concurrently from many threads, through both overloads,
	 * never share a name.
	 */
	@Test(timeout = 30000)
	public void concurrentNamesAreUnique() throws InterruptedException {
		int threads = 8;
		int perThread = 2000;
		Set<String> names = ConcurrentHashMap.newKeySet();
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(threads);

		try {
			for (int t = 0; t < threads; t++) {
				boolean bounded = t % 2 == 0;
				executor.submit(() -> {
					start.await();
					for (int i = 0; i < perThread; i++) {
						names.add((bounded ? DefaultIndex.temporary(10) : DefaultIndex.temporary()).getName());
					}
					return null;
				});
			}

			start.countDown();
			executor.shutdown();
			Assert.assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS));
		} finally {
			executor.shutdownNow();
		}

		Assert.assertEquals(threads * perThread, names.size());
	}
}
