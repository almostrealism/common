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

package org.almostrealism.studio.persistence.test;

import org.almostrealism.audio.data.WaveData;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.studio.persistence.test.support.HarmonicEnergyFeatureProvider;
import org.almostrealism.util.TestFeatures;
import org.junit.Assert;
import org.junit.Test;

/**
 * Verifies that the correlation computed by {@link HarmonicEnergyFeatureProvider}
 * agrees with the host-side evaluation of the same definition.
 *
 * <p>The host form is the oracle: it is a direct transcription of the summation, so
 * agreement with it is what establishes that the computation is equivalent.</p>
 */
public class HarmonicEnergyFeatureProviderTest implements TestFeatures {
	/** Sample rate for the synthetic signals. */
	private static final int SAMPLE_RATE = 44100;

	/** Windows the signal is divided into. */
	private static final int FRAMES = 8;

	/** Cosine bins per window. */
	private static final int BINS = 16;

	/** Length of the synthetic signals, in samples. */
	private static final int LENGTH = 4096;

	/**
	 * A deterministic signal with several partials, so the bins see differing energy.
	 *
	 * @return the audio to analyse
	 */
	private WaveData signal() {
		PackedCollection samples =
				sin(integers(0, LENGTH).multiply(2.0 * Math.PI * 440.0 / SAMPLE_RATE))
					.add(sin(integers(0, LENGTH).multiply(2.0 * Math.PI * 1320.0 / SAMPLE_RATE)).multiply(0.5))
					.add(sin(integers(0, LENGTH).multiply(2.0 * Math.PI * 5000.0 / SAMPLE_RATE)).multiply(0.25))
					.evaluate();
		return new WaveData(samples, SAMPLE_RATE);
	}

	/**
	 * The computation and the host evaluation agree element for element.
	 *
	 * <p>Every bin is a sum over all {@code LENGTH / FRAMES} samples of its window.
	 * The reference accumulates that sum in double precision while the computation
	 * accumulates it in whatever precision the device provides, so the two are
	 * compared to a tolerance that reflects the difference over a window this long
	 * rather than to exact equality. A mistake in the conversion — a transposed axis,
	 * a phase taken from the window rather than the signal, an off-by-one harmonic —
	 * moves values by far more than that.</p>
	 */
	@Test(timeout = 180000)
	public void energyMatchesHostEvaluation() {
		HarmonicEnergyFeatureProvider provider = new HarmonicEnergyFeatureProvider(
				FRAMES, BINS, SAMPLE_RATE, LENGTH / (double) SAMPLE_RATE);
		WaveData audio = signal();

		double[] expected = provider.referenceFeatures(audio);
		PackedCollection actual = provider.computeFeatures(audio);

		Assert.assertEquals(expected.length, actual.getMemLength());
		Assert.assertEquals(FRAMES * BINS, actual.getMemLength());

		double peak = 0.0;
		for (int i = 0; i < expected.length; i++) {
			peak = Math.max(peak, Math.abs(expected[i]));
		}

		assertTrue("Reference features are empty", peak > 1e-6);

		double tolerance = 1e-4 * peak;
		double worst = 0.0;

		for (int i = 0; i < expected.length; i++) {
			worst = Math.max(worst, Math.abs(expected[i] - actual.toDouble(i)));
			Assert.assertEquals("bin " + i, expected[i], actual.toDouble(i), tolerance);
		}

		log(String.format("energy agrees to %.2e of a peak of %.4f", worst, peak));
	}

	/**
	 * The phase depends on where a window begins, so shifting the signal changes the
	 * result. This is what distinguishes this provider from one whose phase restarts
	 * at every window, and it holds in the computed form as it does on the host.
	 */
	@Test(timeout = 180000)
	public void windowPositionAffectsResult() {
		HarmonicEnergyFeatureProvider provider = new HarmonicEnergyFeatureProvider(
				FRAMES, BINS, SAMPLE_RATE, LENGTH / (double) SAMPLE_RATE);

		PackedCollection features = provider.computeFeatures(signal());

		double first = features.toDouble(0);
		double later = features.toDouble((FRAMES - 1) * BINS);

		assertTrue("Every window produced the same value", Math.abs(first - later) > 1e-9);
	}

	/**
	 * Silence produces zero energy rather than anything undefined.
	 */
	@Test(timeout = 180000)
	public void silenceProducesZeroEnergy() {
		HarmonicEnergyFeatureProvider provider = new HarmonicEnergyFeatureProvider(
				FRAMES, BINS, SAMPLE_RATE, LENGTH / (double) SAMPLE_RATE);
		WaveData audio = new WaveData(new PackedCollection(LENGTH), SAMPLE_RATE);

		PackedCollection actual = provider.computeFeatures(audio);

		for (int i = 0; i < actual.getMemLength(); i++) {
			Assert.assertEquals(0.0, actual.toDouble(i), 1e-12);
		}
	}
}
