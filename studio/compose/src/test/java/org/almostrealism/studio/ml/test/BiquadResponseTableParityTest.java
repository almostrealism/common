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

package org.almostrealism.studio.ml.test;

import org.almostrealism.audio.filter.AudioPassFilter;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.studio.arrange.MixdownManagerPdslAdapter;
import org.almostrealism.studio.optimize.FixedFilterChromosome;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Verifies that the {@link MixdownManagerPdslAdapter}'s closed-form, producer-built biquad
 * impulse-response table reproduces the original Java IIR recurrence element-wise. The
 * recurrence is the authoritative reference (it is the FIR realisation of
 * {@link AudioPassFilter}'s exact biquad); the producer table must match it across every
 * bin and tap so the swept-cutoff render is unchanged.
 */
public class BiquadResponseTableParityTest extends TestSuiteBase {
	/** Audio sample rate in Hz. */
	private static final int SAMPLE_RATE = 44100;
	/** FIR filter order; taps = order + 1. */
	private static final int FILTER_ORDER = 40;
	/** Number of log-spaced cutoff bins in the response table. */
	private static final int BINS = 1024;

	/** The closed-form high-pass table must match the recurrence. */
	@Test(timeout = 120000)
	public void highPassTableMatchesRecurrence() throws Exception {
		assertTableMatches(true);
	}

	/** The closed-form low-pass table must match the recurrence. */
	@Test(timeout = 120000)
	public void lowPassTableMatchesRecurrence() throws Exception {
		assertTableMatches(false);
	}

	/**
	 * Invokes the producer-built table and asserts element-wise agreement with the recurrence.
	 *
	 * @param high true for the high-pass design; false for low-pass
	 */
	private void assertTableMatches(boolean high) {
		int taps = FILTER_ORDER + 1;

		PackedCollection table = new MixdownManagerPdslAdapter(
				new MixdownManagerPdslAdapter.Config(1, 1024, SAMPLE_RATE, FILTER_ORDER, 0.5, 100))
				.biquadResponseTable(high);

		double[] reference = referenceTable(high, taps);
		double maxDiff = 0.0;
		int worst = -1;
		for (int i = 0; i < BINS * taps; i++) {
			double diff = Math.abs(table.toDouble(i) - reference[i]);
			if (diff > maxDiff) {
				maxDiff = diff;
				worst = i;
			}
		}

		log("biquadResponseTable parity high=" + high + " maxDiff=" + maxDiff
				+ " worstBin=" + (worst / taps) + " worstTap=" + (worst % taps)
				+ " producer=" + table.toDouble(worst) + " reference=" + reference[worst]);
		Assert.assertTrue("max abs diff " + maxDiff + " exceeds tolerance", maxDiff < 1e-3);
	}

	/**
	 * Builds the reference {@code [BINS, taps]} table from the authoritative Java recurrence.
	 *
	 * @param high true for the high-pass design; false for low-pass
	 * @param taps FIR taps per response
	 * @return the flattened reference table
	 */
	private static double[] referenceTable(boolean high, int taps) {
		double[] data = new double[BINS * taps];
		double span = Math.log(20000.0 / AudioPassFilter.MIN_FREQUENCY);
		for (int b = 0; b < BINS; b++) {
			double cutoff = AudioPassFilter.MIN_FREQUENCY * Math.exp(span * b / (BINS - 1.0));
			biquadImpulseResponse(high, cutoff, SAMPLE_RATE, data, b * taps, taps);
		}
		return data;
	}

	/**
	 * The original truncated biquad impulse-response recurrence, retained here as the
	 * authoritative reference the producer table must reproduce.
	 *
	 * @param high       true for the high-pass design; false for low-pass
	 * @param cutoff     cutoff frequency in Hz
	 * @param sampleRate audio sample rate in Hz
	 * @param out        destination array
	 * @param offset     index of the first coefficient within {@code out}
	 * @param count      number of taps to write
	 */
	private static void biquadImpulseResponse(boolean high, double cutoff, int sampleRate,
											  double[] out, int offset, int count) {
		double r = FixedFilterChromosome.defaultResonance;
		double t = Math.tan(Math.PI * cutoff / sampleRate);
		double c = high ? t : 1.0 / t;
		double a1 = 1.0 / (1.0 + r * c + c * c);
		double a2 = high ? -2.0 * a1 : 2.0 * a1;
		double a3 = a1;
		double b1 = high ? 2.0 * (c * c - 1.0) * a1 : 2.0 * (1.0 - c * c) * a1;
		double b2 = (1.0 - r * c + c * c) * a1;

		out[offset] = a1;
		if (count > 1) out[offset + 1] = a2 - b1 * out[offset];
		if (count > 2) out[offset + 2] = a3 - b1 * out[offset + 1] - b2 * out[offset];
		for (int n = 3; n < count; n++) {
			out[offset + n] = -b1 * out[offset + n - 1] - b2 * out[offset + n - 2];
		}
	}
}
