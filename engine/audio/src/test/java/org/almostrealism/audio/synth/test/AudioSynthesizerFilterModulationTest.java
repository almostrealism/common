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

package org.almostrealism.audio.synth.test;

import org.almostrealism.audio.filter.ADSREnvelope;
import org.almostrealism.audio.filter.BiquadFilterCell;
import org.almostrealism.audio.synth.AudioSynthesizer;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Verifies that the device operation which recomputes the modulated filter coefficients each tick in
 * {@link AudioSynthesizer#tick()} agrees with the host RBJ low-pass formula, tick for tick, as the
 * filter envelope sweeps the cutoff.
 *
 * <p>After each synth tick the filter envelope has advanced and the device operation has written the
 * corresponding coefficients into the filter's coefficient block. The test reads the envelope level
 * that drove that tick, forms the clamped modulated cutoff the same way the device operation does, and
 * asserts the written coefficients equal {@link BiquadFilterCell#calculateLowPassCoefficients} for that
 * cutoff. This equivalence held for the previous host callback as well, so the test passes both before
 * and after the migration. The tick operation is built once and run repeatedly, confirming a
 * runtime-varying cutoff does not compile a kernel per sample.</p>
 */
public class AudioSynthesizerFilterModulationTest extends TestSuiteBase {

	/** Base cutoff in Hz configured on the synth. */
	private static final double BASE_CUTOFF = 5000.0;

	/** Filter envelope modulation depth in Hz. */
	private static final double AMOUNT = 3000.0;

	/** Q the modulation path uses for the low-pass response, matching the pre-migration quirk. */
	private static final double Q = 0.707;

	/** Envelope sample rate, small so the cutoff sweeps a meaningful range across a few ticks. */
	private static final int ENV_SR = 100;

	/** The modulated coefficients written on the device match the host formula across the sweep. */
	@Test(timeout = 120000)
	public void filterCoefficientsTrackEnvelope() {
		AudioSynthesizer synth = new AudioSynthesizer();
		synth.setLowPassFilter(BASE_CUTOFF, Q);
		ADSREnvelope filterEnvelope = new ADSREnvelope(0.052, 0.034, 0.6, 0.041, ENV_SR);
		synth.setFilterEnvelope(filterEnvelope);
		synth.setFilterEnvelopeAmount(AMOUNT);

		BiquadFilterCell filter = synth.getFilter();
		int sampleRate = filter.getSampleRate();

		synth.setup().get().run();
		Runnable tick = synth.tick().get();
		synth.noteOn();

		for (int i = 0; i < 20; i++) {
			tick.run();

			double level = filterEnvelope.getCurrentLevel();
			double modulated = BASE_CUTOFF + level * AMOUNT;
			double clamped = Math.max(20.0, Math.min(20000.0, modulated));
			double[] expected = BiquadFilterCell.calculateLowPassCoefficients(clamped, Q, sampleRate);
			double[] actual = filter.getData().coefficients().toArray(0, 5);

			assertClose("tick " + i + " level=" + level, expected, actual);
		}
	}

	/**
	 * Asserts element-wise agreement with a combined absolute and relative tolerance, wide enough to
	 * absorb single-precision device arithmetic yet far tighter than any wiring or formula error.
	 *
	 * @param label    the assertion label
	 * @param expected the host reference coefficients
	 * @param actual   the device coefficients
	 */
	private void assertClose(String label, double[] expected, double[] actual) {
		for (int i = 0; i < expected.length; i++) {
			double tolerance = 1e-4 + 1e-4 * Math.abs(expected[i]);
			Assert.assertEquals(label + " [" + i + "]", expected[i], actual[i], tolerance);
		}
	}
}
