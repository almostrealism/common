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

package org.almostrealism.audio.filter.test;

import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.audio.filter.BiquadFilterCell;
import org.almostrealism.audio.filter.BiquadFilterData;
import org.almostrealism.audio.filter.DefaultBiquadFilterData;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Verifies that the device-side biquad coefficient producers on {@link BiquadFilterData} agree
 * with the host RBJ Audio EQ Cookbook formulas evaluated by the {@code BiquadFilterCell.calculate*}
 * methods, and that a single compiled coefficient operation handles a cutoff that varies at
 * runtime rather than compiling a distinct kernel per value.
 */
public class BiquadCoefficientParityTest extends TestSuiteBase {

	/** Sample rate used for every case. */
	private static final int SR = 44100;

	/** Cutoff/center frequencies (Hz) exercised across the audible range. */
	private static final double[] FREQS = { 80.0, 500.0, 2000.0, 8000.0, 16000.0 };

	/** Q factors from broad to resonant. */
	private static final double[] QS = { 0.5, 0.707, 2.0, 8.0 };

	/** Gains (dB) for the peaking and shelving responses, including cut, unity and boost. */
	private static final double[] GAINS = { -18.0, -6.0, 0.0, 6.0, 18.0 };

	/** The filter data whose device coefficient producers are under test. */
	private final BiquadFilterData data = new DefaultBiquadFilterData();

	/** A device coefficient producer parameterized by frequency and Q. */
	private interface FreqQCoefficients {
		/**
		 * @param frequency cutoff or center frequency producer
		 * @param q         Q factor producer
		 * @return the {@code (5)} coefficient producer
		 */
		CollectionProducer apply(Producer<PackedCollection> frequency, Producer<PackedCollection> q);
	}

	/** The host reference parameterized by frequency and Q. */
	private interface FreqQReference {
		/**
		 * @param frequency cutoff or center frequency in Hz
		 * @param q         Q factor
		 * @return the {@code [b0, b1, b2, a1, a2]} reference coefficients
		 */
		double[] apply(double frequency, double q);
	}

	/** A device coefficient producer parameterized by frequency and gain. */
	private interface FreqGainCoefficients {
		/**
		 * @param frequency cutoff or center frequency producer
		 * @param gainDb    gain producer in decibels
		 * @return the {@code (5)} coefficient producer
		 */
		CollectionProducer apply(Producer<PackedCollection> frequency, Producer<PackedCollection> gainDb);
	}

	/** The host reference parameterized by frequency and gain. */
	private interface FreqGainReference {
		/**
		 * @param frequency cutoff or center frequency in Hz
		 * @param gainDb    gain in decibels
		 * @return the {@code [b0, b1, b2, a1, a2]} reference coefficients
		 */
		double[] apply(double frequency, double gainDb);
	}

	/** Low-pass coefficients match the host formula. */
	@Test(timeout = 120000)
	public void lowPass() {
		assertFreqQParity("lowPass",
				(f, q) -> data.lowPassCoefficients(f, q, SR),
				(f, q) -> BiquadFilterCell.calculateLowPassCoefficients(f, q, SR));
	}

	/** High-pass coefficients match the host formula. */
	@Test(timeout = 120000)
	public void highPass() {
		assertFreqQParity("highPass",
				(f, q) -> data.highPassCoefficients(f, q, SR),
				(f, q) -> BiquadFilterCell.calculateHighPassCoefficients(f, q, SR));
	}

	/** Band-pass coefficients match the host formula. */
	@Test(timeout = 120000)
	public void bandPass() {
		assertFreqQParity("bandPass",
				(f, q) -> data.bandPassCoefficients(f, q, SR),
				(f, q) -> BiquadFilterCell.calculateBandPassCoefficients(f, q, SR));
	}

	/** Notch coefficients match the host formula. */
	@Test(timeout = 120000)
	public void notch() {
		assertFreqQParity("notch",
				(f, q) -> data.notchCoefficients(f, q, SR),
				(f, q) -> BiquadFilterCell.calculateNotchCoefficients(f, q, SR));
	}

	/** All-pass coefficients match the host formula. */
	@Test(timeout = 120000)
	public void allPass() {
		assertFreqQParity("allPass",
				(f, q) -> data.allPassCoefficients(f, q, SR),
				(f, q) -> BiquadFilterCell.calculateAllPassCoefficients(f, q, SR));
	}

	/** Peaking-EQ coefficients match the host formula across gains. */
	@Test(timeout = 120000)
	public void peakingEQ() {
		assertFreqGainParity("peakingEQ",
				(f, g) -> data.peakingEQCoefficients(f, c(0.707), g, SR),
				(f, g) -> BiquadFilterCell.calculatePeakingEQCoefficients(f, 0.707, g, SR));
	}

	/** Low-shelf coefficients match the host formula across gains. */
	@Test(timeout = 120000)
	public void lowShelf() {
		assertFreqGainParity("lowShelf",
				(f, g) -> data.lowShelfCoefficients(f, g, SR),
				(f, g) -> BiquadFilterCell.calculateLowShelfCoefficients(f, g, SR));
	}

	/** High-shelf coefficients match the host formula across gains. */
	@Test(timeout = 120000)
	public void highShelf() {
		assertFreqGainParity("highShelf",
				(f, g) -> data.highShelfCoefficients(f, g, SR),
				(f, g) -> BiquadFilterCell.calculateHighShelfCoefficients(f, g, SR));
	}

	/**
	 * Compiles the update operation once against a cutoff buffer, then drives every {@link #FREQS}
	 * value through it, confirming both that the written coefficients stay correct and that a
	 * varying cutoff does not require a new compiled operation.
	 */
	@Test(timeout = 120000)
	public void updateReusesOneOperation() {
		PackedCollection cutoff = new PackedCollection(1);
		Runnable update = data.updateCoefficients(
				data.lowPassCoefficients(cp(cutoff), c(0.707), SR)).get();

		for (double f : FREQS) {
			cutoff.fill(f);
			update.run();

			double[] host = BiquadFilterCell.calculateLowPassCoefficients(f, 0.707, SR);
			assertClose("updateLowPass cutoff=" + f, host, data.coefficients().toArray(0, 5));
		}
	}

	/**
	 * Compiles the device producer once against frequency/Q buffers and asserts parity with the
	 * host reference across every {@link #FREQS} and {@link #QS} combination.
	 *
	 * @param name   label for assertion messages
	 * @param device the device coefficient producer under test
	 * @param host   the host reference formula
	 */
	private void assertFreqQParity(String name, FreqQCoefficients device, FreqQReference host) {
		PackedCollection frequency = new PackedCollection(1);
		PackedCollection q = new PackedCollection(1);
		Evaluable<PackedCollection> coefficients = device.apply(cp(frequency), cp(q)).get();

		for (double f : FREQS) {
			for (double qv : QS) {
				frequency.fill(f);
				q.fill(qv);
				assertClose(name + " f=" + f + " q=" + qv, host.apply(f, qv),
						coefficients.evaluate().toArray(0, 5));
			}
		}
	}

	/**
	 * Compiles the device producer once against frequency/gain buffers and asserts parity with the
	 * host reference across every {@link #FREQS} and {@link #GAINS} combination.
	 *
	 * @param name   label for assertion messages
	 * @param device the device coefficient producer under test
	 * @param host   the host reference formula
	 */
	private void assertFreqGainParity(String name, FreqGainCoefficients device, FreqGainReference host) {
		PackedCollection frequency = new PackedCollection(1);
		PackedCollection gain = new PackedCollection(1);
		Evaluable<PackedCollection> coefficients = device.apply(cp(frequency), cp(gain)).get();

		for (double f : FREQS) {
			for (double g : GAINS) {
				frequency.fill(f);
				gain.fill(g);
				assertClose(name + " f=" + f + " gain=" + g, host.apply(f, g),
						coefficients.evaluate().toArray(0, 5));
			}
		}
	}

	/**
	 * Asserts element-wise agreement with a combined absolute and relative tolerance, wide enough
	 * to absorb single-precision device arithmetic yet far tighter than any formula error.
	 */
	private void assertClose(String label, double[] expected, double[] actual) {
		for (int i = 0; i < expected.length; i++) {
			double tolerance = 1e-4 + 1e-4 * Math.abs(expected[i]);
			Assert.assertEquals(label + " [" + i + "]", expected[i], actual[i], tolerance);
		}
	}
}
