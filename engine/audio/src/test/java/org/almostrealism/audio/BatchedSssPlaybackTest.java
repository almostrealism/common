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
import org.almostrealism.time.TemporalFeatures;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestSuiteBase;
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
public class BatchedSssPlaybackTest extends TestSuiteBase implements TemporalFeatures {

	/** Number of notes active in the window. */
	private static final int N = 4;

	/** Source layers summed per note (production {@code PatternNoteFactory.LAYER_COUNT}). */
	private static final int LAYERS = 3;

	/** Source samples per note before resampling. */
	private static final int SOURCE_LENGTH = 2048;

	/** Target samples per note after resampling. */
	private static final int TARGET_LENGTH = 1024;

	/** Output window width in frames (the a2 window; wider than a single note). */
	private static final int WINDOW_WIDTH = 1536;

	/** Audio sample rate. */
	private static final int SAMPLE_RATE = OutputLine.sampleRate;

	/** FIR filter order matching production {@code EfxManager.filterOrder}. */
	private static final int FILTER_ORDER = 40;

	private PackedCollection col(double[] values) {
		PackedCollection c = new PackedCollection(values.length);
		c.setMem(values);
		return c;
	}

	/** Extracts row {@code n} of a flat {@code [N, TARGET_LENGTH]} collection. */
	private PackedCollection row(PackedCollection flat, int n) {
		double[] data = new double[TARGET_LENGTH];
		for (int i = 0; i < TARGET_LENGTH; i++) {
			data[i] = flat.toDouble(n * TARGET_LENGTH + i);
		}
		return col(data);
	}

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
		double[][] ratioValues = new double[LAYERS][N];

		for (int l = 0; l < LAYERS; l++) {
			double[] batchData = new double[N * SOURCE_LENGTH];
			double[] ratioData = new double[N];
			double[] md = new double[N];
			double[] f0 = new double[N];
			double[] f1 = new double[N];
			double[] f2 = new double[N];
			double[] v0 = new double[N];
			double[] v1 = new double[N];
			double[] v2 = new double[N];
			double[] v3 = new double[N];
			for (int n = 0; n < N; n++) {
				double[] data = new double[SOURCE_LENGTH];
				for (int i = 0; i < SOURCE_LENGTH; i++) {
					data[i] = rng.nextDouble() * 2.0 - 1.0;
					batchData[n * SOURCE_LENGTH + i] = data[i];
				}
				sourceByLayerNote[l][n] = col(data);
				ratioValues[l][n] = 1.0 + 0.1 * l + 0.05 * n;
				ratioData[n] = ratioValues[l][n];

				md[n] = 0.012 + 0.002 * n;
				f0[n] = 0.3;
				f1[n] = 0.6;
				f2[n] = 1.0;
				v0[n] = 0.0;
				v1[n] = 0.85 + 0.02 * l;
				v2[n] = 0.5 + 0.03 * n;
				v3[n] = 0.0;
			}
			sources[l] = new PackedCollection(shape(N, SOURCE_LENGTH));
			sources[l].setMem(batchData);
			ratios[l] = col(ratioData);
			layerCurves[l] = renderer.buildLayerEnvelopeCurve(
					col(md), col(f0), col(f1), col(f2), col(v0), col(v1), col(v2), col(v3))
					.get().evaluate();
		}

		// ── Filter cutoff curve = ADSR shape scaled to filterPeak (Hz). ──
		double[] fAtt = new double[N];
		double[] fDec = new double[N];
		double[] fSus = new double[N];
		double[] fRel = new double[N];
		double[] fDur = new double[N];
		// ── Volume envelope. ──
		double[] vAtt = new double[N];
		double[] vDec = new double[N];
		double[] vSus = new double[N];
		double[] vRel = new double[N];
		double[] vDur = new double[N];
		for (int n = 0; n < N; n++) {
			fDur[n] = 0.016 + 0.002 * n;
			fAtt[n] = 0.002 + 0.0003 * n;
			fDec[n] = 0.0015 + 0.0002 * n;
			fSus[n] = 0.5 + 0.05 * n;
			fRel[n] = 0.004 + 0.0005 * n;

			vDur[n] = 0.018 + 0.002 * n;
			vAtt[n] = 0.0015 + 0.0003 * n;
			vDec[n] = 0.0010 + 0.0002 * n;
			vSus[n] = 0.45 + 0.05 * n;
			vRel[n] = 0.003 + 0.0005 * n;
		}
		PackedCollection filterCutoffs = renderer.buildVolumeEnvelopeCurve(
				col(fAtt), col(fDec), col(fSus), col(fRel), col(fDur))
				.multiply(c(MultiOrderFilterEnvelopeProcessor.filterPeak))
				.get().evaluate();
		PackedCollection volumeEnvelopes = renderer.buildVolumeEnvelopeCurve(
				col(vAtt), col(vDec), col(vSus), col(vRel), col(vDur))
				.get().evaluate();

		// ── Per-note destination offsets (one starts mid-window). ──
		double[] destOffsetValues = { 0, 200, 512, 700 };
		PackedCollection destOffsets = col(destOffsetValues);

		// ── Single batched dispatch: 3 layers → placed, summed window. ──
		PackedCollection out = renderer.buildBatchedSssChainPlaced(
				sources, ratios, layerCurves, filterCutoffs, volumeEnvelopes,
				destOffsets, WINDOW_WIDTH)
				.get().evaluate();

		// ── Per-note reference (the path real playback takes today). ──
		double[] expected = new double[WINDOW_WIDTH];
		for (int n = 0; n < N; n++) {
			double[] merged = new double[TARGET_LENGTH];
			for (int l = 0; l < LAYERS; l++) {
				PackedCollection resampled =
						renderer.buildResampleProducer(sourceByLayerNote[l][n], ratioValues[l][n])
								.get().evaluate();
				for (int i = 0; i < TARGET_LENGTH; i++) {
					merged[i] += resampled.toDouble(i) * layerCurves[l].toDouble(n * TARGET_LENGTH + i);
				}
			}

			PackedCollection mergedN = col(merged);
			PackedCollection cutoffN = row(filterCutoffs, n);
			PackedCollection volN = row(volumeEnvelopes, n);

			PackedCollection filtered =
					c(lowPass(traverseEach(cp(mergedN)), cp(cutoffN), SAMPLE_RATE, FILTER_ORDER))
							.reshape(shape(TARGET_LENGTH))
							.get().evaluate();
			PackedCollection voiced = cp(filtered).multiply(cp(volN)).get().evaluate();

			int off = (int) destOffsetValues[n];
			for (int k = 0; k < TARGET_LENGTH; k++) {
				int f = off + k;
				if (f < WINDOW_WIDTH) {
					expected[f] += voiced.toDouble(k);
				}
			}
		}

		double sumSqDiff = 0.0;
		double sumSqRef = 0.0;
		for (int i = 0; i < WINDOW_WIDTH; i++) {
			double diff = expected[i] - out.toDouble(i);
			sumSqDiff += diff * diff;
			sumSqRef += expected[i] * expected[i];
		}
		double rms = Math.sqrt(sumSqDiff / WINDOW_WIDTH);
		double refRms = Math.sqrt(sumSqRef / WINDOW_WIDTH);

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
