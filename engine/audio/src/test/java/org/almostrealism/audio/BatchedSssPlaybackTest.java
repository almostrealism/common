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
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestDepth;
import org.junit.Assert;
import org.junit.Test;

import java.util.Random;

/**
 * End-to-end test of the full melodic-SSS a2 kernel as it is used for real
 * AudioScene playback: per note, three source layers are resampled by their own
 * pitch ratios, shaped by per-layer envelopes generated from ADSR scalars,
 * summed (SSS), passed through the filter-cutoff envelope (the ADSR shape scaled
 * to {@code filterPeak}) and the volume envelope — all generated from per-note
 * scalars — then placed into a wider output window at per-note offsets.
 *
 * <p>The single batched {@code buildBatchedSssChainPlaced} dispatch is compared
 * against a per-note reference that performs the same computation note by note
 * (the path real playback takes today), using the same scalar-generated envelope
 * curves. The envelope generators themselves are verified against production
 * (getVolumeEnv / getLayerEnv) by {@code BatchedEnvelopeTest}.</p>
 */
public class BatchedSssPlaybackTest extends BatchedSssTestBase {

	/** Audio sample rate. */
	private static final int SAMPLE_RATE = OutputLine.sampleRate;


	/**
	 * Verifies that a single batched SSS chain dispatch produces output within 1e-4 RMS
	 * of a per-note reference that applies resampling, layer envelopes, filter-cutoff
	 * envelope, and volume envelope note by note.
	 */
	@Test(timeout = 240000)
	@TestDepth(2)
	public void testFullSssPlaybackChain() {
		BatchedPatternRenderer renderer = new BatchedPatternRenderer(
				N, SOURCE_LENGTH, TARGET_LENGTH, SAMPLE_RATE, FILTER_ORDER);
		Random rng = new Random(11L);

		// ── Per-layer sources, ratios, and per-layer envelope curves. ──
		PackedCollection[] sources = new PackedCollection[LAYERS];
		PackedCollection[] ratios = new PackedCollection[LAYERS];
		PackedCollection[] layerCurves = new PackedCollection[LAYERS];
		PackedCollection[][] sourceByLayerNote = new PackedCollection[LAYERS][N];

		for (int l = 0; l < LAYERS; l++) {
			PackedCollection batch =
					rand(shape(N, SOURCE_LENGTH), rng).multiply(2.0).add(-1.0).evaluate();
			ratios[l] = perNote(1.0 + 0.1 * l, 0.05);

			for (int n = 0; n < N; n++) {
				sourceByLayerNote[l][n] = batch.traverse(1).get(n);
			}

			sources[l] = batch;
			PackedCollection[] env = layerEnvelopeParameters(l);
			layerCurves[l] = renderer.buildLayerEnvelopeCurve(
					env[0], env[1], env[2], env[3], env[4], env[5], env[6], env[7])
					.get().evaluate();
		}

		// ── Filter cutoff curve = ADSR shape scaled to filterPeak (Hz). ──
		PackedCollection[] filterAdsr = filterAdsr();
		PackedCollection filterCutoffs = renderer.buildVolumeEnvelopeCurve(
				filterAdsr[0], filterAdsr[1], filterAdsr[2], filterAdsr[3], filterAdsr[4])
				.multiply(c(MultiOrderFilterEnvelopeProcessor.filterPeak))
				.get().evaluate();
		// ── Volume envelope. ──
		PackedCollection[] volumeAdsr = volumeAdsr();
		PackedCollection volumeEnvelopes = renderer.buildVolumeEnvelopeCurve(
				volumeAdsr[0], volumeAdsr[1], volumeAdsr[2], volumeAdsr[3], volumeAdsr[4])
				.get().evaluate();

		// ── Per-note destination offsets (one starts mid-window). ──
		PackedCollection destOffsets = destinationOffsets();

		// ── Single batched dispatch: 3 layers → placed, summed window. ──
		PackedCollection out = renderer.buildBatchedSssChainPlaced(
				sources, ratios, layerCurves, filterCutoffs, volumeEnvelopes,
				destOffsets, WINDOW_WIDTH)
				.get().evaluate();

		// ── Per-note reference (the path real playback takes today). ──
		PackedCollection expected = new PackedCollection(WINDOW_WIDTH);
		for (int n = 0; n < N; n++) {
			CollectionProducer merged = null;
			for (int l = 0; l < LAYERS; l++) {
				// The reference resamples note by note with a host scalar while the
				// batched path reads the collection, so it takes the ratio from there.
				PackedCollection resampled =
						renderer.buildResampleProducer(sourceByLayerNote[l][n],
										ratios[l].toDouble(n))
								.get().evaluate();
				CollectionProducer shaped =
						cp(resampled).multiply(cp(layerCurves[l].traverse(1).get(n)));
				merged = merged == null ? shaped : merged.add(shaped);
			}

			PackedCollection mergedN = merged.evaluate();
			// The curves are shaped [N, TARGET_LENGTH] with traversal on axis 0, so
			// the row has to be selected on axis 1 to get one note's curve rather
			// than the whole batch
			PackedCollection cutoffN = filterCutoffs.traverse(1).get(n);
			PackedCollection volN = volumeEnvelopes.traverse(1).get(n);

			PackedCollection filtered =
					c(lowPass(traverseEach(cp(mergedN)), cp(cutoffN), SAMPLE_RATE, FILTER_ORDER))
							.reshape(shape(TARGET_LENGTH))
							.get().evaluate();
			PackedCollection voiced = cp(filtered).multiply(cp(volN)).get().evaluate();

			// The note is placed at its own offset and accumulated into the window
			// where it lands, the tail beyond the window being dropped.
			int off = (int) destOffsets.toDouble(n);
			int len = Math.min(TARGET_LENGTH, WINDOW_WIDTH - off);
			if (len > 0) {
				PackedCollection slot = expected.range(shape(len), off);
				a(cp(slot), cp(slot).add(cp(voiced.range(shape(len))))).get().run();
			}
		}

		PackedCollection window = out.range(shape(WINDOW_WIDTH));
		double rms = Math.sqrt(differenceEnergy(window, expected) / WINDOW_WIDTH);
		double refRms = Math.sqrt(energy(expected, 0) / WINDOW_WIDTH);

		log("Full SSS playback chain vs per-note reference:");
		log(String.format("  Reference RMS: %.6f", refRms));
		log(String.format("  Difference RMS: %.6f", rms));
		if (refRms > 1e-10) {
			log(String.format("  Relative difference: %.2e", rms / refRms));
		}

		Assert.assertTrue(
				"Full SSS playback chain RMS difference from per-note reference exceeds 1e-4 (got " + rms + ")",
				rms < 1e-4);
	}
}
