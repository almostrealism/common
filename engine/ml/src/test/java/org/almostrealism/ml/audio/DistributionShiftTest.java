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

package org.almostrealism.ml.audio;

import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Verifies the {@link DistributionShift} schedule warps against values computed from the reference
 * formulas (uniform grid warped point-wise, endpoints pinned), and the wiring of the warps into
 * {@link PingPongSamplingStrategy}.
 */
public class DistributionShiftTest extends TestSuiteBase {

	/** Tolerance for the double-precision reference values. */
	private static final double TOLERANCE = 1e-9;

	/**
	 * A length-invariant {@link LogSNRShift} ({@code rate = 0}) maps the quarter points of the unit
	 * interval to the reference values for {@code anchorLogSnr = -6.2}, {@code logSnrEnd = 2}.
	 */
	@Test(timeout = 60000)
	public void logSnrShiftLengthInvariant() {
		LogSNRShift shift = new LogSNRShift(2000, -6.2, 0.0, 2.0);
		assertFalse(shift.isLengthDependent());

		double[] t = {0.0, 0.25, 0.5, 0.75, 1.0};
		double[] expected = {0.0, 0.5124973964842103, 0.8909031788043871, 0.9844802434215911, 1.0};

		for (int i = 0; i < t.length; i++) {
			assertEquals(expected[i], shift.shift(t[i], 1000), TOLERANCE);
			assertEquals(expected[i], shift.shift(t[i], 0), TOLERANCE);
		}
	}

	/**
	 * With {@code rate = 1} the high-noise bound drops by one per doubling of the sequence length,
	 * moving the schedule toward noise for longer sequences.
	 */
	@Test(timeout = 60000)
	public void logSnrShiftAdaptsToLength() {
		LogSNRShift shift = new LogSNRShift();
		assertTrue(shift.isLengthDependent());
		assertEquals(-7.2, shift.getLogSnrStart(4000), TOLERANCE);
		assertEquals(-4.2, shift.getLogSnrStart(500), TOLERANCE);

		double[] t = {0.25, 0.5, 0.75};
		double[] longSeq = {0.5744425168116589, 0.9308615796566531, 0.9926084586557181};
		double[] shortSeq = {0.389360766050778, 0.7502601055951177, 0.9340109905087812};

		for (int i = 0; i < t.length; i++) {
			assertEquals(longSeq[i], shift.shift(t[i], 4000), TOLERANCE);
			assertEquals(shortSeq[i], shift.shift(t[i], 500), TOLERANCE);
		}

		assertEquals(0.0, shift.shift(0.0, 4000), TOLERANCE);
		assertEquals(1.0, shift.shift(1.0, 4000), TOLERANCE);
	}

	/**
	 * {@link FluxDistributionShift} interpolates {@code alpha} log-linearly in the sequence length
	 * (alpha 2 at 256 to alpha 8 at 4096 gives alpha 4 at 1024) and applies
	 * {@code alpha * t / (1 + (alpha - 1) * t)}.
	 */
	@Test(timeout = 60000)
	public void fluxShiftMatchesReference() {
		FluxDistributionShift shift = new FluxDistributionShift(256, 4096, 2.0, 8.0);
		assertTrue(shift.isLengthDependent());
		assertEquals(4.0, shift.getAlpha(1024), 1e-9);

		double[] t = {0.0, 0.25, 0.5, 0.75, 1.0};
		double[] expected = {0.0, 0.5714285714285714, 0.8, 0.9230769230769231, 1.0};

		for (int i = 0; i < t.length; i++) {
			assertEquals(expected[i], shift.shift(t[i], 1024), TOLERANCE);
		}

		FluxDistributionShift constant = new FluxDistributionShift(3.0);
		assertFalse(constant.isLengthDependent());
		assertEquals(3.0 * 0.5 / (1.0 + 2.0 * 0.5), constant.shift(0.5, 0), TOLERANCE);
	}

	/**
	 * {@link LogitDistributionShift} with the reference defaults maps the quarter points at a
	 * sequence length of 2000 to the reference values.
	 */
	@Test(timeout = 60000)
	public void logitShiftMatchesReference() {
		LogitDistributionShift shift = new LogitDistributionShift();
		assertTrue(shift.isLengthDependent());

		double[] t = {0.25, 0.5, 0.75};
		double[] expected = {0.42472556962347374, 0.6889485652425702, 0.8691905420256893};

		for (int i = 0; i < t.length; i++) {
			assertEquals(expected[i], shift.shift(t[i], 2000), TOLERANCE);
		}

		assertEquals(0.0, shift.shift(0.0, 2000), TOLERANCE);
		assertEquals(1.0, shift.shift(1.0, 2000), TOLERANCE);
	}

	/**
	 * {@link DistributionShift#schedule} warps the uniform grid and pins both endpoints; an
	 * eight-step schedule under the length-invariant reference default matches the reference values.
	 */
	@Test(timeout = 60000)
	public void scheduleMatchesReference() {
		DistributionShift shift = new LogSNRShift(2000, -6.2, 0.0, 2.0);
		double[] schedule = shift.schedule(8, 1.0, 1292);
		double[] expected = {1.0, 0.9943755959354477, 0.9844802434215911, 0.9579122720843811,
				0.8909031788043871, 0.7455466141264027, 0.5124973964842103, 0.27388501873922083, 0.0};

		assertEquals(expected.length, schedule.length);
		for (int i = 0; i < expected.length; i++) {
			assertEquals(expected[i], schedule[i], TOLERANCE);
		}

		for (int i = 1; i < schedule.length; i++) {
			assertTrue("schedule must decrease monotonically", schedule[i] < schedule[i - 1]);
		}
	}

	/**
	 * A length-dependent shift refuses to build a schedule without a positive sequence length,
	 * while the identity shift leaves the uniform grid untouched.
	 */
	@Test(timeout = 60000)
	public void scheduleRequiresLengthWhenDependent() {
		try {
			new LogSNRShift().schedule(8, 1.0, 0);
			throw new AssertionError("length-dependent shift must reject a zero sequence length");
		} catch (IllegalArgumentException e) {
			assertTrue(e.getMessage().contains("sequence length"));
		}

		double[] uniform = DistributionShift.identity().schedule(4, 1.0, 0);
		double[] expected = {1.0, 0.75, 0.5, 0.25, 0.0};
		for (int i = 0; i < expected.length; i++) {
			assertEquals(expected[i], uniform[i], TOLERANCE);
		}
	}

	/**
	 * The default {@link PingPongSamplingStrategy} schedule is unchanged by the shift refactor: it
	 * equals the sigmoid of a log-SNR linspace from {@code -6} to {@code 2} with the endpoints pinned.
	 */
	@Test(timeout = 60000)
	public void pingPongDefaultScheduleUnchanged() {
		PingPongSamplingStrategy strategy = new PingPongSamplingStrategy();
		int steps = 8;
		double[] schedule = strategy.getTimesteps(1000, steps);

		double[] legacy = new double[steps + 1];
		double step = (2.0f - (-6.0f)) / steps;
		for (int i = 0; i <= steps; i++) {
			legacy[i] = 1.0 / (1.0 + Math.exp(-6.0f + i * step));
		}
		legacy[0] = 1.0;
		legacy[steps] = 0.0;

		for (int i = 0; i <= steps; i++) {
			assertEquals(legacy[i], schedule[i], 1e-6);
		}
	}

	/**
	 * A {@link PingPongSamplingStrategy} built on a length-dependent shift produces different
	 * schedules for different latent lengths and honors the configured sigma bounds.
	 */
	@Test(timeout = 60000)
	public void pingPongUsesShiftAndSequenceLength() {
		PingPongSamplingStrategy strategy = new PingPongSamplingStrategy(new LogSNRShift(), 1.0, 0.0);
		double[] longSchedule = strategy.getTimesteps(1000, 8, 4000);
		double[] shortSchedule = strategy.getTimesteps(1000, 8, 500);

		assertEquals(1.0, longSchedule[0], TOLERANCE);
		assertEquals(0.0, longSchedule[8], TOLERANCE);
		assertEquals(0.5744425168116589, longSchedule[6], TOLERANCE);
		assertEquals(0.389360766050778, shortSchedule[6], TOLERANCE);
		assertTrue(longSchedule[4] > shortSchedule[4]);
	}
}
