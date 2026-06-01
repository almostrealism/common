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

import org.almostrealism.audio.filter.AudioProcessingUtils;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.time.TemporalFeatures;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;

/**
 * Verifies that {@link BatchedPatternRenderer#buildVolumeEnvelopeCurve} generates
 * per-note ADSR volume-envelope gain curves matching the production
 * {@code AudioProcessingUtils.getVolumeEnv} envelope (applied to a unit signal).
 * Durations are short so every ADSR phase — attack, decay, sustain, release, and
 * the post-release zero — falls within the {@value #TARGET_LENGTH}-frame window.
 */
public class BatchedEnvelopeTest extends TestSuiteBase implements TemporalFeatures {

	/** Number of notes in the synthetic workload. */
	private static final int N = 5;

	/** Envelope curve length per note. */
	private static final int TARGET_LENGTH = 1024;

	/** Audio sample rate. */
	private static final int SAMPLE_RATE = OutputLine.sampleRate;

	/** Returns a single-element {@link PackedCollection} holding the given value. */
	private PackedCollection single(double value) {
		PackedCollection c = new PackedCollection(1);
		c.setMem(new double[] { value });
		return c;
	}

	/** Returns a {@link PackedCollection} populated with the given array of values. */
	private PackedCollection col(double[] values) {
		PackedCollection c = new PackedCollection(values.length);
		c.setMem(values);
		return c;
	}

	/**
	 * For each of {@value #N} notes, compares the batched volume-envelope curve to
	 * the production {@code getVolumeEnv} applied to a unit (all-ones) signal.
	 */
	@Test(timeout = 120000)
	@TestDepth(2)
	public void testVolumeEnvelopeMatchesProduction() {
		BatchedPatternRenderer renderer = new BatchedPatternRenderer(
				N, 2048, TARGET_LENGTH, SAMPLE_RATE, 2);

		double[] attackV = new double[N];
		double[] decayV = new double[N];
		double[] sustainV = new double[N];
		double[] releaseV = new double[N];
		double[] durationV = new double[N];
		for (int n = 0; n < N; n++) {
			// Short phases so attack+decay+sustain+release all fit in TARGET_LENGTH.
			durationV[n] = 0.008 + 0.002 * n;
			attackV[n] = 0.0015 + 0.0003 * n;
			decayV[n] = 0.0010 + 0.0002 * n;
			sustainV[n] = 0.4 + 0.1 * n;
			releaseV[n] = 0.003 + 0.0005 * n;
		}

		// ── Production reference: getVolumeEnv applied to all-ones per note. ──
		double[] onesData = new double[TARGET_LENGTH];
		Arrays.fill(onesData, 1.0);

		double[] reference = new double[N * TARGET_LENGTH];
		for (int n = 0; n < N; n++) {
			PackedCollection ones = new PackedCollection(TARGET_LENGTH);
			ones.setMem(onesData);
			PackedCollection ref = AudioProcessingUtils.getVolumeEnv().evaluate(
					ones.traverse(1),
					single(durationV[n]), single(attackV[n]), single(decayV[n]),
					single(sustainV[n]), single(releaseV[n]));
			for (int i = 0; i < TARGET_LENGTH; i++) {
				reference[n * TARGET_LENGTH + i] = ref.toDouble(i);
			}
		}

		// ── Batched curve generation. ──
		PackedCollection out = renderer.buildVolumeEnvelopeCurve(
				col(attackV), col(decayV), col(sustainV),
				col(releaseV), col(durationV))
				.get().evaluate();

		assertRmsBelow("Batched volume envelope vs production getVolumeEnv", reference, out);
	}

	/**
	 * For each of {@value #N} notes, compares the batched per-layer envelope curve to
	 * the production {@code getLayerEnv} applied to a unit (all-ones) signal.
	 */
	@Test(timeout = 120000)
	@TestDepth(2)
	public void testLayerEnvelopeMatchesProduction() {
		BatchedPatternRenderer renderer = new BatchedPatternRenderer(
				N, 2048, TARGET_LENGTH, SAMPLE_RATE, 2);

		double[] md = new double[N];
		double[] f0 = new double[N];
		double[] f1 = new double[N];
		double[] f2 = new double[N];
		double[] v0 = new double[N];
		double[] v1 = new double[N];
		double[] v2 = new double[N];
		double[] v3 = new double[N];
		for (int n = 0; n < N; n++) {
			// Segment ends d0<d1<d2 all fall within TARGET_LENGTH frames.
			md[n] = 0.010 + 0.002 * n;
			f0[n] = 0.3;
			f1[n] = 0.6;
			f2[n] = 1.0;
			v0[n] = 0.0;
			v1[n] = 0.9 + 0.01 * n;
			v2[n] = 0.5 + 0.02 * n;
			v3[n] = 0.0;
		}

		double[] onesData = new double[TARGET_LENGTH];
		Arrays.fill(onesData, 1.0);

		double[] reference = new double[N * TARGET_LENGTH];
		for (int n = 0; n < N; n++) {
			PackedCollection ones = new PackedCollection(TARGET_LENGTH);
			ones.setMem(onesData);
			PackedCollection ref = AudioProcessingUtils.getLayerEnv().evaluate(
					ones.traverse(1),
					single(md[n]), single(f0[n]), single(f1[n]), single(f2[n]),
					single(v0[n]), single(v1[n]), single(v2[n]), single(v3[n]));
			for (int i = 0; i < TARGET_LENGTH; i++) {
				reference[n * TARGET_LENGTH + i] = ref.toDouble(i);
			}
		}

		PackedCollection out = renderer.buildLayerEnvelopeCurve(
				col(md), col(f0), col(f1), col(f2), col(v0), col(v1), col(v2), col(v3))
				.get().evaluate();

		assertRmsBelow("Batched layer envelope vs production getLayerEnv", reference, out);
	}

	/** Asserts the RMS difference between a flat reference and a collection is below {@code 1e-4}. */
	private void assertRmsBelow(String label, double[] reference, PackedCollection actual) {
		double sumSqDiff = 0.0;
		double sumSqRef = 0.0;
		for (int i = 0; i < reference.length; i++) {
			double diff = reference[i] - actual.toDouble(i);
			sumSqDiff += diff * diff;
			sumSqRef += reference[i] * reference[i];
		}
		double rms = Math.sqrt(sumSqDiff / reference.length);
		double refRms = Math.sqrt(sumSqRef / reference.length);

		log(label + ":");
		log(String.format("  Reference RMS: %.6f", refRms));
		log(String.format("  Difference RMS: %.6f", rms));
		if (refRms > 1e-10) {
			log(String.format("  Relative difference: %.2e", rms / refRms));
		}
		Assert.assertTrue(label + " RMS difference exceeds 1e-4 (got " + rms + ")", rms < 1e-4);
	}
}
