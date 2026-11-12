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
import java.util.List;

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
}
