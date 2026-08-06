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


	/**
	 * For each of {@value #N} notes, compares the batched volume-envelope curve to
	 * the production {@code getVolumeEnv} applied to a unit (all-ones) signal.
	 */
	@Test(timeout = 120000)
	@TestDepth(2)
	public void testVolumeEnvelopeMatchesProduction() {
		BatchedPatternRenderer renderer = new BatchedPatternRenderer(
				N, 2048, TARGET_LENGTH, SAMPLE_RATE, 2);

		// Short phases so attack+decay+sustain+release all fit in TARGET_LENGTH.
		PackedCollection duration = linear(0.008, 0.016, N).evaluate().reshape(N);
		PackedCollection attack = linear(0.0015, 0.0027, N).evaluate().reshape(N);
		PackedCollection decay = linear(0.0010, 0.0018, N).evaluate().reshape(N);
		PackedCollection sustain = linear(0.4, 0.8, N).evaluate().reshape(N);
		PackedCollection release = linear(0.003, 0.005, N).evaluate().reshape(N);

		// ── Production reference: getVolumeEnv applied to all-ones per note. ──
		PackedCollection reference = new PackedCollection(N, TARGET_LENGTH);
		for (int n = 0; n < N; n++) {
			PackedCollection ones = new PackedCollection(TARGET_LENGTH).fill(1.0);
			PackedCollection ref = AudioProcessingUtils.getVolumeEnv().evaluate(
					ones.traverse(1),
					duration.range(shape(1), n), attack.range(shape(1), n), decay.range(shape(1), n),
					sustain.range(shape(1), n), release.range(shape(1), n));
			cp(ref).get().into(reference.range(shape(TARGET_LENGTH), n * TARGET_LENGTH)).evaluate();
		}

		// ── Batched curve generation. ──
		PackedCollection out = renderer.buildVolumeEnvelopeCurve(
				attack, decay, sustain, release, duration)
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

		// Segment ends d0<d1<d2 all fall within TARGET_LENGTH frames.
		PackedCollection md = linear(0.010, 0.018, N).evaluate().reshape(N);
		PackedCollection f0 = linear(0.3, 0.3, N).evaluate().reshape(N);
		PackedCollection f1 = linear(0.6, 0.6, N).evaluate().reshape(N);
		PackedCollection f2 = linear(1.0, 1.0, N).evaluate().reshape(N);
		PackedCollection v0 = linear(0.0, 0.0, N).evaluate().reshape(N);
		PackedCollection v1 = linear(0.9, 0.94, N).evaluate().reshape(N);
		PackedCollection v2 = linear(0.5, 0.58, N).evaluate().reshape(N);
		PackedCollection v3 = linear(0.0, 0.0, N).evaluate().reshape(N);

		PackedCollection reference = new PackedCollection(N, TARGET_LENGTH);
		for (int n = 0; n < N; n++) {
			PackedCollection ones = new PackedCollection(TARGET_LENGTH).fill(1.0);
			PackedCollection ref = AudioProcessingUtils.getLayerEnv().evaluate(
					ones.traverse(1),
					md.range(shape(1), n), f0.range(shape(1), n),
					f1.range(shape(1), n), f2.range(shape(1), n),
					v0.range(shape(1), n), v1.range(shape(1), n),
					v2.range(shape(1), n), v3.range(shape(1), n));
			cp(ref).get().into(reference.range(shape(TARGET_LENGTH), n * TARGET_LENGTH)).evaluate();
		}

		PackedCollection out = renderer.buildLayerEnvelopeCurve(
				md, f0, f1, f2, v0, v1, v2, v3)
				.get().evaluate();

		assertRmsBelow("Batched layer envelope vs production getLayerEnv", reference, out);
	}

	/** Asserts the RMS difference between the reference and a collection is below {@code 1e-4}. */
	private void assertRmsBelow(String label, PackedCollection reference, PackedCollection actual) {
		int length = reference.getMemLength();
		double rms = Math.sqrt(sum(cp(reference).reshape(length)
				.subtract(cp(actual).reshape(length)).sq()).evaluate().toDouble(0) / length);
		double refRms = Math.sqrt(sum(cp(reference).reshape(length).sq())
				.evaluate().toDouble(0) / length);

		log(label + ":");
		log(String.format("  Reference RMS: %.6f", refRms));
		log(String.format("  Difference RMS: %.6f", rms));
		if (refRms > 1e-10) {
			log(String.format("  Relative difference: %.2e", rms / refRms));
		}
		Assert.assertTrue(label + " RMS difference exceeds 1e-4 (got " + rms + ")", rms < 1e-4);
	}
}
