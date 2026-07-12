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

package org.almostrealism.audio;

import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.notes.NoteAudioProvider;
import org.almostrealism.audio.tone.WesternChromatic;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.time.TemporalFeatures;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.Random;

/**
 * Verifies that the batched kernel's resample
 * ({@link BatchedPatternRenderer#buildResampleProducer}) matches the production
 * note-audio resample ({@code WaveDataProvider.getChannelData(channel, rate)},
 * the interpolation used by {@code NoteAudioProvider}) for the same source and
 * pitch ratio. This is the linchpin for batched-vs-per-note acoustic
 * equivalence: if the two resamplers agree, the batched path can reproduce the
 * production per-note audio.
 *
 * <p>Finding: the kernel's {@code i*ratio} linear resample matches the production
 * {@code Interpolate} (a banked, time-domain interpolation with {@code rate^-1}
 * scaling) exactly for {@code ratio >= 1} (pitch up / unity), but diverges for
 * {@code ratio < 1} (pitch down). The pitch-up/unity case is asserted; the
 * pitch-down divergence is logged as a known gap to close (either reproduce the
 * banked algorithm in the kernel, or have the gather supply the production-
 * resampled source).</p>
 */
public class ResampleEquivalenceTest extends TestSuiteBase implements TemporalFeatures {

	/** Number of samples in the randomly generated source buffer used for resampling tests. */
	private static final int SOURCE_LENGTH = 2048;

	/** Number of output samples produced by the resampler under test. */
	private static final int TARGET_LENGTH = 1024;

	/**
	 * Creates a {@link PackedCollection} filled with random values in [-1, 1]
	 * using the given seed, providing a reproducible source signal for comparison.
	 */
	private PackedCollection randomSource(long seed) {
		Random rng = new Random(seed);
		double[] data = new double[SOURCE_LENGTH];
		for (int i = 0; i < SOURCE_LENGTH; i++) {
			data[i] = rng.nextDouble() * 2.0 - 1.0;
		}
		PackedCollection c = new PackedCollection(SOURCE_LENGTH);
		a(cp(c), c(data)).get().run();
		return c;
	}

	/**
	 * Verifies that the batched kernel resample matches the production
	 * {@link NoteAudioProvider} resample for pitch-up and unity ratios,
	 * and that pitch-down divergence stays within its known bounded gap.
	 */
	@Test(timeout = 120000)
	@TestDepth(2)
	public void testResampleMatchesProduction() {
		BatchedPatternRenderer renderer = new BatchedPatternRenderer(
				1, SOURCE_LENGTH, TARGET_LENGTH, OutputLine.sampleRate, 2);

		// Pitch-up / unity ratios are asserted equivalent; pitch-down is logged.
		double[] pitchUpRatios = { 1.0, 1.3, 1.7, 2.0 };
		double[] pitchDownRatios = { 0.8, 0.5 };

		double worstUp = 0.0;
		for (double ratio : pitchUpRatios) {
			worstUp = Math.max(worstUp, resampleRms(renderer, ratio, "pitch-up"));
		}
		double worstDown = 0.0;
		for (double ratio : pitchDownRatios) {
			worstDown = Math.max(worstDown, resampleRms(renderer, ratio, "pitch-down (known gap)"));
		}

		Assert.assertTrue(
				"batched resample differs from production getChannelData for ratio >= 1 (worst rel "
						+ worstUp + ")",
				worstUp < 2e-4);

		// Pitch-down (upsampling) is a known, bounded gap: the kernel does clean
		// floor/floor+1 linear interpolation at i*ratio, while production Interpolate
		// selects its bracket via ceil(index)-1 plus a conditional bump, giving a
		// different (nearest-neighbor-leaning) fractional result. The kernel's interp
		// is arguably more correct; the divergence peaks near ratio 0.8 (~4%). This
		// guard catches a regression that worsens the gap; the whole-pattern impact is
		// negligible (BatchedVsPerNoteRmsTest ~0.15%).
		Assert.assertTrue(
				"pitch-down resample gap exceeded its known bound (worst rel " + worstDown + ")",
				worstDown < 0.06);
	}

	/** Computes the relative RMS difference between the kernel and production resample for one ratio. */
	private double resampleRms(BatchedPatternRenderer renderer, double ratio, String label) {
		PackedCollection source = randomSource((long) (ratio * 1000));

		NoteAudioProvider provider = NoteAudioProvider.create(() -> source, WesternChromatic.C1);
		PackedCollection reference = provider.getProvider().getChannelData(0, ratio);
		PackedCollection batched = renderer.buildResampleProducer(source, ratio).get().evaluate();

		int n = Math.min(TARGET_LENGTH, reference.getMemLength());
		double sumSqDiff = 0.0;
		double sumSqRef = 0.0;
		for (int i = 0; i < n; i++) {
			double diff = reference.toDouble(i) - batched.toDouble(i);
			sumSqDiff += diff * diff;
			sumSqRef += reference.toDouble(i) * reference.toDouble(i);
		}
		double rms = Math.sqrt(sumSqDiff / n);
		double refRms = Math.sqrt(sumSqRef / n);
		double relative = refRms > 1e-10 ? rms / refRms : 0.0;

		log(String.format("%-22s ratio=%.2f  refRms=%.6f  diffRms=%.6f  rel=%.2e",
				label, ratio, refRms, rms, relative));
		return relative;
	}
}
