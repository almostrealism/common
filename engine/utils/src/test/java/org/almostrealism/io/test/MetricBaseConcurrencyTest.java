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

package org.almostrealism.io.test;

import org.almostrealism.io.DistributionMetric;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Tests for {@link org.almostrealism.io.MetricBase} thread safety, verifying
 * that concurrent {@link DistributionMetric#addEntry} calls do not lose data.
 */
public class MetricBaseConcurrencyTest extends TestSuiteBase {

	/**
	 * Verifies that concurrent addEntry calls from multiple threads using
	 * separate keys produce the correct per-key counts without throwing
	 * exceptions.
	 */
	@Test(timeout = 30000) @TestDepth(2)
	public void concurrentAddEntry() throws Exception {
		int threadCount = 8;
		int entriesPerThread = 1000;
		DistributionMetric metric = new DistributionMetric("concurrent-test", 1.0);

		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		CyclicBarrier barrier = new CyclicBarrier(threadCount);

		List<Future<?>> futures = new ArrayList<>();
		for (int t = 0; t < threadCount; t++) {
			int threadId = t;
			futures.add(executor.submit(() -> {
				try {
					barrier.await();
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
				for (int i = 0; i < entriesPerThread; i++) {
					metric.addEntry("key-" + threadId, (long) 1);
				}
			}));
		}

		for (Future<?> f : futures) {
			f.get(20, TimeUnit.SECONDS);
		}
		executor.shutdown();

		Map<String, Integer> counts = metric.getCounts();
		for (int t = 0; t < threadCount; t++) {
			Assert.assertEquals("Count for key-" + t,
					Integer.valueOf(entriesPerThread), counts.get("key-" + t));
		}
		Assert.assertEquals(threadCount, counts.size());
	}

	/**
	 * Verifies that concurrent addEntry calls to the same key
	 * produce the correct per-key count.
	 */
	@Test(timeout = 30000) @TestDepth(2)
	public void concurrentAddEntrySharedKey() throws Exception {
		int threadCount = 8;
		int entriesPerThread = 500;
		DistributionMetric metric = new DistributionMetric("shared-key-test", 1.0);

		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		CyclicBarrier barrier = new CyclicBarrier(threadCount);

		List<Future<?>> futures = new ArrayList<>();
		for (int t = 0; t < threadCount; t++) {
			futures.add(executor.submit(() -> {
				try {
					barrier.await();
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
				for (int i = 0; i < entriesPerThread; i++) {
					metric.addEntry("shared", (long) 1);
				}
			}));
		}

		for (Future<?> f : futures) {
			f.get(20, TimeUnit.SECONDS);
		}
		executor.shutdown();

		Map<String, Integer> counts = metric.getCounts();
		int expectedPerKey = threadCount * entriesPerThread;
		Assert.assertEquals(Integer.valueOf(expectedPerKey), counts.get("shared"));
	}

	/**
	 * Verifies that concurrent addEntry and clear do not throw
	 * {@link java.util.ConcurrentModificationException}.
	 */
	@Test(timeout = 30000) @TestDepth(2)
	public void concurrentAddAndClear() throws Exception {
		int threadCount = 4;
		int iterations = 500;
		DistributionMetric metric = new DistributionMetric("add-clear-test", 1.0);

		ExecutorService executor = Executors.newFixedThreadPool(threadCount + 1);
		CyclicBarrier barrier = new CyclicBarrier(threadCount + 1);

		List<Future<?>> futures = new ArrayList<>();

		// Writer threads
		for (int t = 0; t < threadCount; t++) {
			int threadId = t;
			futures.add(executor.submit(() -> {
				try {
					barrier.await();
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
				for (int i = 0; i < iterations; i++) {
					metric.addEntry("key-" + threadId, (long) 1);
				}
			}));
		}

		// Clear thread
		futures.add(executor.submit(() -> {
			try {
				barrier.await();
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
			for (int i = 0; i < iterations / 10; i++) {
				metric.clear();
			}
		}));

		for (Future<?> f : futures) {
			f.get(20, TimeUnit.SECONDS);
		}
		executor.shutdown();

		// No assertion on count since clear resets it;
		// the test passes if no ConcurrentModificationException was thrown
	}

	/**
	 * Verifies basic single-threaded MetricBase operations: addEntry,
	 * getCount, getTotal, and clear.
	 */
	@Test(timeout = 10000)
	public void singleThreadOperations() {
		DistributionMetric metric = new DistributionMetric("basic-test", 1.0);

		metric.addEntry("a", 10.0);
		metric.addEntry("a", 20.0);
		metric.addEntry("b", 5.0);

		Assert.assertEquals(3, metric.getCount());
		Assert.assertEquals(35.0, metric.getTotal(), 0.001);

		Map<String, Double> entries = metric.getEntries();
		Assert.assertEquals(30.0, entries.get("a"), 0.001);
		Assert.assertEquals(5.0, entries.get("b"), 0.001);

		Map<String, Integer> counts = metric.getCounts();
		Assert.assertEquals(Integer.valueOf(2), counts.get("a"));
		Assert.assertEquals(Integer.valueOf(1), counts.get("b"));

		metric.clear();
		Assert.assertEquals(0, metric.getCount());
		Assert.assertEquals(0.0, metric.getTotal(), 0.001);
		Assert.assertTrue(metric.getEntries().isEmpty());
	}

	/**
	 * Verifies that concurrent reads (getCount, getTotal) while entries
	 * are being added do not throw exceptions.
	 */
	@Test(timeout = 30000) @TestDepth(2)
	public void concurrentReadAndAdd() throws Exception {
		int threadCount = 4;
		int iterations = 500;
		DistributionMetric metric = new DistributionMetric("read-test", 1.0);

		ExecutorService executor = Executors.newFixedThreadPool(threadCount + 1);
		CyclicBarrier barrier = new CyclicBarrier(threadCount + 1);

		List<Future<?>> futures = new ArrayList<>();

		// Writer threads
		for (int t = 0; t < threadCount; t++) {
			int threadId = t;
			futures.add(executor.submit(() -> {
				try {
					barrier.await();
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
				for (int i = 0; i < iterations; i++) {
					metric.addEntry("key-" + threadId, (long) 1);
				}
			}));
		}

		// Reader thread calling getCount and getTotal
		futures.add(executor.submit(() -> {
			try {
				barrier.await();
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
			for (int i = 0; i < iterations; i++) {
				metric.getCount();
				metric.getTotal();
			}
		}));

		for (Future<?> f : futures) {
			f.get(20, TimeUnit.SECONDS);
		}
		executor.shutdown();
	}
}
