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

import org.almostrealism.audio.filter.MultiOrderFilterEnvelopeProcessor;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestDepth;
import org.junit.Assert;
import org.junit.Test;

import java.util.Random;

/**
 * Verifies that the fully fused production entry point
 * {@link BatchedPatternRenderer#buildBatchedSssChainPlacedFromScalars} — which
 * generates all envelope curves inside the kernel from per-note ADSR scalars —
 * produces the same output as the already-verified
 * {@link BatchedPatternRenderer#buildBatchedSssChainPlaced} fed the same curves
 * materialized from those scalars. This is the gather's target API: a single
 * dispatch consuming only cheap per-note scalars, ratios, sources, and offsets.
 */
public class BatchedSssFromScalarsTest extends BatchedSssTestBase {

	/** Audio sample rate used for envelope curve construction. */
	private static final int SAMPLE_RATE = OutputLine.sampleRate;


	/**
	 * Verifies that the fused scalar-driven path produces output identical
	 * to the pre-materialized envelope-curve path within a tight RMS threshold.
	 */
	@Test(timeout = 240000)
	@TestDepth(2)
	public void testFromScalarsMatchesMaterialized() {
		BatchedPatternRenderer renderer = new BatchedPatternRenderer(
				N, SOURCE_LENGTH, TARGET_LENGTH, SAMPLE_RATE, FILTER_ORDER);
		Random rng = new Random(23L);

		PackedCollection[] sources = new PackedCollection[LAYERS];
		PackedCollection[] ratios = new PackedCollection[LAYERS];
		PackedCollection[][] layerEnvParams = new PackedCollection[LAYERS][8];
		PackedCollection[] layerCurves = new PackedCollection[LAYERS];

		for (int l = 0; l < LAYERS; l++) {
			PackedCollection batch =
					rand(shape(N, SOURCE_LENGTH), rng).multiply(2.0).add(-1.0).evaluate();
			sources[l] = batch;
			ratios[l] = perNote(1.0 + 0.1 * l, 0.05);
			layerEnvParams[l] = layerEnvelopeParameters(l);
			layerCurves[l] = renderer.buildLayerEnvelopeCurve(
					layerEnvParams[l][0], layerEnvParams[l][1], layerEnvParams[l][2], layerEnvParams[l][3],
					layerEnvParams[l][4], layerEnvParams[l][5], layerEnvParams[l][6], layerEnvParams[l][7])
					.get().evaluate();
		}

		PackedCollection[] filterAdsr = filterAdsr();
		PackedCollection[] volumeAdsr = volumeAdsr();

		PackedCollection filterCutoffs = renderer.buildVolumeEnvelopeCurve(
				filterAdsr[0], filterAdsr[1], filterAdsr[2], filterAdsr[3], filterAdsr[4])
				.multiply(c(MultiOrderFilterEnvelopeProcessor.filterPeak))
				.get().evaluate();
		PackedCollection volumeEnvelopes = renderer.buildVolumeEnvelopeCurve(
				volumeAdsr[0], volumeAdsr[1], volumeAdsr[2], volumeAdsr[3], volumeAdsr[4])
				.get().evaluate();

		PackedCollection destOffsets = destinationOffsets();

		// Materialized-curve path (already verified against the per-note reference).
		PackedCollection materialized = renderer.buildBatchedSssChainPlaced(
				sources, ratios, layerCurves, filterCutoffs, volumeEnvelopes, destOffsets, WINDOW_WIDTH)
				.get().evaluate();

		// Fully fused path: curves generated inside the kernel from the same scalars.
		PackedCollection fused = renderer.buildBatchedSssChainPlacedFromScalars(
				sources, ratios, layerEnvParams, filterAdsr, volumeAdsr, destOffsets, WINDOW_WIDTH)
				.get().evaluate();

		double sumSqDiff = 0.0;
		for (int i = 0; i < WINDOW_WIDTH; i++) {
			double diff = materialized.toDouble(i) - fused.toDouble(i);
			sumSqDiff += diff * diff;
		}
		double rms = Math.sqrt(sumSqDiff / WINDOW_WIDTH);
		log(String.format("Fused-from-scalars vs materialized RMS: %.2e", rms));

		Assert.assertTrue(
				"Fused-from-scalars output differs from materialized-curve output (RMS " + rms + ")",
				rms < 1e-6);
	}
}
