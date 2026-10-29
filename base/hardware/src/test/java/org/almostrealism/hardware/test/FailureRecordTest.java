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

import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.ctx.FailureRecord;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Validates {@link FailureRecord}, the one failure model every submission stream shares: a
 * failure injected at ordinal {@code K} is reported for every ordinal at or after {@code K} and
 * for none before it, the earliest of several failures wins regardless of the order they are
 * recorded in, and the record is never cleared.
 *
 * <p>Plain JUnit: {@code TestSuiteBase} lives in the engine layer, above this module.</p>
 */
public class FailureRecordTest {

	/** Nothing is reported before a failure is recorded. */
	@Test(timeout = 10000)
	public void emptyRecordReportsNothing() {
		FailureRecord record = new FailureRecord();

		Assert.assertFalse(record.isFailed());
		Assert.assertEquals(Long.MAX_VALUE, record.getFailedOrdinal());
		Assert.assertNull(record.failureAt(1));
		Assert.assertNull(record.failureAt(Long.MAX_VALUE - 1));
		record.require(42);
	}

	/** A failure at {@code K} is reported for {@code K} and every later ordinal, and not before. */
	@Test(timeout = 10000)
	public void failureCoversItsOrdinalAndEveryLaterOne() {
		FailureRecord record = new FailureRecord();
		IllegalStateException cause = new IllegalStateException("device fault");
		record.record(5, cause);

		Assert.assertTrue(record.isFailed());
		Assert.assertEquals(5, record.getFailedOrdinal());
		Assert.assertNull("An earlier ordinal ran before the failure", record.failureAt(4));

		for (long ordinal : new long[] { 5, 6, 1000 }) {
			HardwareException reported = record.failureAt(ordinal);
			Assert.assertNotNull("Ordinal " + ordinal + " must report the failure", reported);
			Assert.assertSame(cause, reported.getCause());
			Assert.assertEquals("device fault", reported.getMessage());
		}

		try {
			record.require(7);
			Assert.fail("require must throw for an ordinal after the failure");
		} catch (HardwareException expected) {
			Assert.assertSame(cause, expected.getCause());
		}
	}

	/** Each waiter receives its own exception, so concurrent waiters never share one throwable. */
	@Test(timeout = 10000)
	public void eachReportIsAFreshException() {
		FailureRecord record = new FailureRecord();
		record.record(1, new IllegalStateException("killed"));

		Assert.assertNotSame(record.failureAt(1), record.failureAt(1));
	}

	/** The failure with the lowest ordinal is kept whichever order failures are recorded in. */
	@Test(timeout = 10000)
	public void earliestFailureWins() {
		FailureRecord record = new FailureRecord();
		record.record(10, new IllegalStateException("later"));
		record.record(3, new IllegalStateException("earlier"));
		record.record(7, new IllegalStateException("between"));

		Assert.assertEquals(3, record.getFailedOrdinal());
		Assert.assertEquals("earlier", record.failureAt(10).getCause().getMessage());
		Assert.assertNull(record.failureAt(2));
	}

	/** Concurrent recorders converge on the earliest failure. */
	@Test(timeout = 10000)
	public void concurrentRecordersConvergeOnEarliest() throws InterruptedException {
		FailureRecord record = new FailureRecord();
		CountDownLatch start = new CountDownLatch(1);
		List<Thread> threads = new ArrayList<>();

		for (int i = 1; i <= 16; i++) {
			long ordinal = 100 - i;
			Thread t = new Thread(() -> {
				try {
					start.await();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}

				record.record(ordinal, new IllegalStateException("at " + ordinal));
			});
			t.start();
			threads.add(t);
		}

		start.countDown();
		for (Thread t : threads) t.join();

		Assert.assertEquals(84, record.getFailedOrdinal());
		Assert.assertEquals("at 84", record.failureAt(84).getCause().getMessage());
	}

	/** The record is sticky: later successful work never clears it. */
	@Test(timeout = 10000)
	public void recordIsNeverCleared() {
		FailureRecord record = new FailureRecord();
		record.record(2, new IllegalStateException("poisoned"));

		for (long ordinal = 3; ordinal < 100; ordinal++) {
			Assert.assertNotNull(record.failureAt(ordinal));
		}

		Assert.assertEquals(2, record.getFailedOrdinal());
	}
}
