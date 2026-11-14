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
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tests for the interval listeners of {@link org.almostrealism.io.MetricBase}.
 *
 * <p>An interval listener is documented to be notified when an interval completes,
 * that is, once an entry arrives in a later interval. The interval index is derived
 * from wall-clock time since the class was loaded, so these tests drive it directly
 * through an overridden {@code getCurrentInterval()}.</p>
 */
public class MetricBaseIntervalTest extends TestSuiteBase {

	/**
	 * A metric whose current interval is set explicitly rather than read from the clock.
	 */
	private static class SteppedMetric extends DistributionMetric {
		/** The interval reported to {@link #getCurrentInterval()}. */
		private long interval;

		/** Creates a metric that starts in interval {@code 0}. */
		SteppedMetric() {
			super("stepped", 1.0);
		}

		/**
		 * Sets the interval that subsequent entries are recorded in.
		 *
		 * @param interval the interval index
		 */
		void setInterval(long interval) { this.interval = interval; }

		@Override
		protected long getCurrentInterval() { return interval; }

		/**
		 * Registers a listener that records each notification as {@code {total, count}}.
		 *
		 * @return the list the notifications are appended to
		 */
		List<double[]> listen() {
			List<double[]> reports = new ArrayList<>();
			addIntervalListener((total, count) -> reports.add(new double[] { total, count }));
			return reports;
		}
	}

	/**
	 * Consecutive intervals starting at zero are each reported once the next one begins.
	 */
	@Test(timeout = 10000)
	public void consecutiveIntervalsAreReported() {
		SteppedMetric metric = new SteppedMetric();
		List<double[]> reports = metric.listen();

		metric.addEntry("a", 2.0);
		metric.addEntry("a", 3.0);
		metric.setInterval(1);
		metric.addEntry("a", 7.0);
		metric.setInterval(2);
		metric.addEntry("a", 1.0);

		Assert.assertEquals(2, reports.size());
		Assert.assertArrayEquals(new double[] { 5.0, 2 }, reports.get(0), 0.0);
		Assert.assertArrayEquals(new double[] { 7.0, 1 }, reports.get(1), 0.0);
	}

	/**
	 * A metric first used after interval {@code 1} (more than two interval lengths after
	 * the process started) must still report its first interval once the next begins.
	 * Reporting used to fire only when the new interval was exactly two past the last
	 * reported one, which starts at {@code -1}, so such a metric never reported at all.
	 */
	@Test(timeout = 10000)
	public void intervalIsReportedWhenMetricIsFirstUsedLate() {
		SteppedMetric metric = new SteppedMetric();
		List<double[]> reports = metric.listen();

		metric.setInterval(5);
		metric.addEntry("a", 4.0);
		metric.setInterval(6);
		metric.addEntry("a", 1.0);

		Assert.assertEquals("interval 5 completed when interval 6 began", 1, reports.size());
		Assert.assertArrayEquals(new double[] { 4.0, 1 }, reports.get(0), 0.0);
	}

	/**
	 * An interval with no entries must not stop later intervals from being reported.
	 * Reporting used to require the new interval to be exactly two past the last
	 * reported one, so a single idle interval silenced the listeners permanently.
	 */
	@Test(timeout = 10000)
	public void intervalsAreReportedAfterAnIdleInterval() {
		SteppedMetric metric = new SteppedMetric();
		List<double[]> reports = metric.listen();

		metric.addEntry("a", 1.0);
		metric.setInterval(1);
		metric.addEntry("a", 2.0);
		metric.setInterval(3);
		metric.addEntry("a", 3.0);
		metric.setInterval(4);
		metric.addEntry("a", 4.0);

		Assert.assertEquals("intervals 0, 1 and 3 each completed", 3, reports.size());
		Assert.assertArrayEquals(new double[] { 1.0, 1 }, reports.get(0), 0.0);
		Assert.assertArrayEquals(new double[] { 2.0, 1 }, reports.get(1), 0.0);
		Assert.assertArrayEquals(new double[] { 3.0, 1 }, reports.get(2), 0.0);
	}

	/**
	 * Clearing a metric discards its interval data, so the interval in progress when it
	 * was cleared has nothing left to report and the next entry must not report it.
	 */
	@Test(timeout = 10000)
	public void clearedIntervalIsNotReported() {
		SteppedMetric metric = new SteppedMetric();
		List<double[]> reports = metric.listen();

		metric.addEntry("a", 1.0);
		metric.clear();
		metric.setInterval(1);
		metric.addEntry("a", 2.0);
		metric.setInterval(2);
		metric.addEntry("a", 3.0);

		Assert.assertEquals("only interval 1 completed after the clear", 1, reports.size());
		Assert.assertArrayEquals(new double[] { 2.0, 1 }, reports.get(0), 0.0);
	}

	/**
	 * When an entry is recorded for an interval below the highest one already seen — as can
	 * happen when concurrent entries straddling a boundary acquire the aggregate lock out of
	 * order — the lower interval is already complete and must be reported immediately rather
	 * than lost. Tracking only the maximum interval and requiring a strictly greater one to
	 * report used to drop it.
	 */
	@Test(timeout = 10000)
	public void outOfOrderLowerIntervalIsReported() {
		SteppedMetric metric = new SteppedMetric();
		List<double[]> reports = metric.listen();

		metric.setInterval(7);
		metric.addEntry("a", 1.0);
		metric.setInterval(5);
		metric.addEntry("a", 2.0);

		Assert.assertEquals("interval 5 completed since interval 7 was already seen", 1, reports.size());
		Assert.assertArrayEquals(new double[] { 2.0, 1 }, reports.get(0), 0.0);
	}

	/**
	 * A completed interval is reported exactly once. A later entry that lands in an interval
	 * whose report has already fired must not report it a second time, even though its value
	 * still accumulates into that interval's totals.
	 */
	@Test(timeout = 10000)
	public void completedIntervalIsNotReportedTwice() {
		SteppedMetric metric = new SteppedMetric();
		List<double[]> reports = metric.listen();

		metric.addEntry("a", 1.0);
		metric.setInterval(1);
		metric.addEntry("a", 2.0);
		metric.setInterval(0);
		metric.addEntry("a", 5.0);

		Assert.assertEquals("interval 0 reported once despite the late entry", 1, reports.size());
		Assert.assertArrayEquals(new double[] { 1.0, 1 }, reports.get(0), 0.0);
	}

	/**
	 * Entries crossing interval boundaries concurrently with {@code clear()} must never deliver
	 * a {@code null} total or count to an interval listener. Reporting used to read the interval
	 * maps when the listeners ran, so a {@code clear()} emptying those maps between an entry
	 * recording a boundary and the report reading it passed {@code null} to the listeners, which
	 * fails when a listener unboxes the payload. Capturing the total and count under the aggregate
	 * lock at the moment the interval completes removes that window entirely, so the listener here
	 * — which unboxes both values — is never handed a {@code null}.
	 */
	@Test(timeout = 30000)
	public void concurrentClearNeverDeliversNullToListeners() throws InterruptedException {
		SteppedMetric metric = new SteppedMetric();
		List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

		metric.addIntervalListener((total, count) -> {
			double unboxedTotal = total;
			int unboxedCount = count;
			if (unboxedCount < 0 || unboxedTotal < 0.0) {
				failures.add(new AssertionError("unexpected negative interval payload"));
			}
		});

		AtomicBoolean stop = new AtomicBoolean(false);

		Thread writer = new Thread(() -> {
			try {
				for (long i = 0; i < 200000 && !stop.get(); i++) {
					metric.setInterval(i);
					metric.addEntry("a", 1.0);
				}
			} catch (Throwable t) {
				failures.add(t);
			} finally {
				stop.set(true);
			}
		});

		Thread clearer = new Thread(() -> {
			while (!stop.get()) {
				metric.clear();
			}
		});

		writer.start();
		clearer.start();
		writer.join();
		clearer.join();

		Assert.assertTrue("no listener received a null interval payload: " + failures,
				failures.isEmpty());
	}
}
