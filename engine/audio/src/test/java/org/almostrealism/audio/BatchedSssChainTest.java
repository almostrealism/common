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

import org.almostrealism.audio.benchmark.PatternRenderingFloorBenchmark;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.time.TemporalFeatures;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.Random;

/**
 * Verifies the batched three-source-sum (SSS) chain — the production melodic
 * note shape (three resampled, per-layer-enveloped source layers, summed, then
 * filter and volume envelopes). Two outputs are checked against a shared
 * per-note reference: {@link BatchedPatternRenderer#buildBatchedSssChain} (the
 * aligned reduction) and {@link BatchedPatternRenderer#buildBatchedSssChainPlaced}
 * (the fused offset-aware scatter placement into a wider window).
 */
public class BatchedSssChainTest extends TestSuiteBase implements TemporalFeatures {

	/** Number of notes in the synthetic workload. */
	private static final int N = 4;

	/** Number of summed source layers (production {@code PatternNoteFactory.LAYER_COUNT}). */
	private static final int LAYERS = 3;

	/** Source samples per note before resampling. */
	private static final int SOURCE_LENGTH = 2048;

	/** Target samples per note after resampling. */
	private static final int TARGET_LENGTH = 1024;

	/** Audio sample rate. */
	private static final int SAMPLE_RATE = OutputLine.sampleRate;

	/** FIR filter order matching production {@code EfxManager.filterOrder}. */
	private static final int FILTER_ORDER = 40;

	/** Batched inputs plus the per-note reference voiced rows for one workload. */
	private static final class Workload {
		/** Per-layer batched source audio, shape {@code [N, SOURCE_LENGTH]} each. */
		private final PackedCollection[] sources = new PackedCollection[LAYERS];
		/** Per-layer per-note resampling ratios, length {@code N} each. */
		private final PackedCollection[] ratios = new PackedCollection[LAYERS];
		/** Per-layer per-note amplitude envelopes, shape {@code [N, TARGET_LENGTH]} each. */
		private final PackedCollection[] layerEnvelopes = new PackedCollection[LAYERS];
		/** Per-note filter cutoff envelopes, shape {@code [N, TARGET_LENGTH]}. */
		private PackedCollection filterCutoffs;
		/** Per-note volume envelopes, shape {@code [N, TARGET_LENGTH]}. */
		private PackedCollection volumeEnvelopes;
		/** Per-note reference voiced output, shape {@code [N][TARGET_LENGTH]}. */
		private final double[][] voiced = new double[N][TARGET_LENGTH];
	}

	/**
	 * Builds a synthetic SSS workload: per-layer random sources, ratios, and
	 * envelopes plus post-merge filter/volume envelopes, and computes the
	 * sequential per-note reference voiced rows (sum of resample × per-layer
	 * envelope over layers → lowPass(cutoff) → × volume).
	 */
	private Workload buildWorkload(BatchedPatternRenderer renderer) {
		Workload w = new Workload();
		Random rng = new Random(7L);

		double[][] ratioValues = new double[LAYERS][N];
		PackedCollection[][] sourceByLayerNote = new PackedCollection[LAYERS][N];
		double[][] layerEnvData = new double[LAYERS][N * TARGET_LENGTH];

		for (int l = 0; l < LAYERS; l++) {
			double[] batchData = new double[N * SOURCE_LENGTH];
			double[] ratioData = new double[N];
			for (int n = 0; n < N; n++) {
				double[] data = new double[SOURCE_LENGTH];
				for (int i = 0; i < SOURCE_LENGTH; i++) {
					data[i] = rng.nextDouble() * 2.0 - 1.0;
					batchData[n * SOURCE_LENGTH + i] = data[i];
				}
				sourceByLayerNote[l][n] = new PackedCollection(SOURCE_LENGTH);
				sourceByLayerNote[l][n].setMem(data);
				// Ratios in [1.0, 1.45] — max source index < SOURCE_LENGTH.
				ratioValues[l][n] = 1.0 + 0.1 * l + 0.05 * n;
				ratioData[n] = ratioValues[l][n];

				double sustain = 0.5 + 0.1 * l + 0.02 * n;
				PatternRenderingFloorBenchmark.fillAdsrShape(layerEnvData[l], n * TARGET_LENGTH, TARGET_LENGTH,
						0.0, 1.0, sustain, 0.0,
						0.04 + 0.01 * l, 0.09 + 0.01 * l, 0.13 + 0.01 * l);
			}
			w.sources[l] = new PackedCollection(shape(N, SOURCE_LENGTH));
			w.sources[l].setMem(batchData);
			w.ratios[l] = new PackedCollection(N);
			w.ratios[l].setMem(ratioData);
			w.layerEnvelopes[l] = new PackedCollection(shape(N, TARGET_LENGTH));
			w.layerEnvelopes[l].setMem(layerEnvData[l]);
		}

		double[] filterCutoffData = new double[N * TARGET_LENGTH];
		double[] volumeEnvData = new double[N * TARGET_LENGTH];
		for (int n = 0; n < N; n++) {
			PatternRenderingFloorBenchmark.fillAdsrShape(filterCutoffData, n * TARGET_LENGTH, TARGET_LENGTH,
					150.0 + n * 50.0, 4000.0 + n * 600.0, 800.0 + n * 200.0, 150.0 + n * 50.0,
					0.05 + n * 0.005, 0.10 + n * 0.005, 0.15 + n * 0.005);
			PatternRenderingFloorBenchmark.fillAdsrShape(volumeEnvData, n * TARGET_LENGTH, TARGET_LENGTH,
					0.0, 1.0, 0.4 + n * 0.05, 0.0,
					0.05 + n * 0.005, 0.10 + n * 0.005, 0.15 + n * 0.005);
		}
		w.filterCutoffs = new PackedCollection(shape(N, TARGET_LENGTH));
		w.filterCutoffs.setMem(filterCutoffData);
		w.volumeEnvelopes = new PackedCollection(shape(N, TARGET_LENGTH));
		w.volumeEnvelopes.setMem(volumeEnvData);

		// Per-note reference: Σ_layer resample × perLayerEnv → lowPass → × volume.
		for (int n = 0; n < N; n++) {
			double[] merged = new double[TARGET_LENGTH];
			for (int l = 0; l < LAYERS; l++) {
				PackedCollection resampled =
						renderer.buildResampleProducer(sourceByLayerNote[l][n], ratioValues[l][n])
								.get().evaluate();
				for (int i = 0; i < TARGET_LENGTH; i++) {
					merged[i] += resampled.toDouble(i) * layerEnvData[l][n * TARGET_LENGTH + i];
				}
			}

			PackedCollection mergedN = new PackedCollection(TARGET_LENGTH);
			mergedN.setMem(merged);
			PackedCollection cutoffN = row(filterCutoffData, n);
			PackedCollection volN = row(volumeEnvData, n);

			PackedCollection filtered =
					c(lowPass(traverseEach(cp(mergedN)), cp(cutoffN), SAMPLE_RATE, FILTER_ORDER))
							.reshape(shape(TARGET_LENGTH))
							.get().evaluate();
			PackedCollection voiced = cp(filtered).multiply(cp(volN)).get().evaluate();
			for (int i = 0; i < TARGET_LENGTH; i++) {
				w.voiced[n][i] = voiced.toDouble(i);
			}
		}

		return w;
	}

	/** Extracts row {@code n} of a flat {@code [N, TARGET_LENGTH]} array into a collection. */
	private PackedCollection row(double[] flat, int n) {
		double[] data = new double[TARGET_LENGTH];
		System.arraycopy(flat, n * TARGET_LENGTH, data, 0, TARGET_LENGTH);
		PackedCollection c = new PackedCollection(TARGET_LENGTH);
		c.setMem(data);
		return c;
	}

	/** Asserts the RMS difference between {@code expected} and {@code actual} is below {@code 1e-4}. */
	private void assertRmsEquivalent(String label, double[] expected, PackedCollection actual) {
		double sumSqDiff = 0.0;
		double sumSqRef = 0.0;
		for (int i = 0; i < expected.length; i++) {
			double diff = expected[i] - actual.toDouble(i);
			sumSqDiff += diff * diff;
			sumSqRef += expected[i] * expected[i];
		}
		double rms = Math.sqrt(sumSqDiff / expected.length);
		double refRms = Math.sqrt(sumSqRef / expected.length);

		log(label + " acoustic equivalence:");
		log(String.format("  Reference RMS: %.6f", refRms));
		log(String.format("  Difference RMS: %.6f", rms));
		if (refRms > 1e-10) {
			log(String.format("  Relative difference: %.2e", rms / refRms));
		}
		Assert.assertTrue(label + " RMS difference exceeds 1e-4 (got " + rms + ")", rms < 1e-4);
	}

	/**
	 * Aligned reduction: the summed SSS chain must match Σ_n voiced[n].
	 */
	@Test(timeout = 120000)
	@TestDepth(2)
	public void testSssAcousticEquivalence() {
		BatchedPatternRenderer renderer = new BatchedPatternRenderer(
				N, SOURCE_LENGTH, TARGET_LENGTH, SAMPLE_RATE, FILTER_ORDER);
		Workload w = buildWorkload(renderer);

		double[] expected = new double[TARGET_LENGTH];
		for (int n = 0; n < N; n++) {
			for (int i = 0; i < TARGET_LENGTH; i++) {
				expected[i] += w.voiced[n][i];
			}
		}

		PackedCollection out = renderer.buildBatchedSssChain(
				w.sources, w.ratios, w.layerEnvelopes, w.filterCutoffs, w.volumeEnvelopes)
				.get().evaluate();

		assertRmsEquivalent("Batched SSS chain", expected, out);
	}

	/**
	 * Fused placement: the SSS chain plus offset-aware scatter must match the
	 * per-note voiced rows placed at their destination offsets in a wider window,
	 * with one note truncated at the window edge. Offsets {0, 256, 512, 700},
	 * window 1536: note 3 spans [700, 1724) and is truncated at 1536.
	 */
	@Test(timeout = 120000)
	@TestDepth(2)
	public void testSssPlacedAcousticEquivalence() {
		BatchedPatternRenderer renderer = new BatchedPatternRenderer(
				N, SOURCE_LENGTH, TARGET_LENGTH, SAMPLE_RATE, FILTER_ORDER);
		Workload w = buildWorkload(renderer);

		int windowWidth = 1536;
		double[] destOffsetValues = { 0, 256, 512, 700 };
		PackedCollection destOffsets = new PackedCollection(N);
		destOffsets.setMem(destOffsetValues);

		double[] expected = new double[windowWidth];
		for (int n = 0; n < N; n++) {
			int off = (int) destOffsetValues[n];
			for (int k = 0; k < TARGET_LENGTH; k++) {
				int f = off + k;
				if (f < windowWidth) {
					expected[f] += w.voiced[n][k];
				}
			}
		}

		PackedCollection out = renderer.buildBatchedSssChainPlaced(
				w.sources, w.ratios, w.layerEnvelopes, w.filterCutoffs, w.volumeEnvelopes,
				destOffsets, windowWidth)
				.get().evaluate();

		assertRmsEquivalent("Batched SSS placed chain", expected, out);
	}
}
