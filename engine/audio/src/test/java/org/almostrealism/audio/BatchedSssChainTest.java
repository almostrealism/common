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
 * Verifies that {@link BatchedPatternRenderer#buildBatchedSssChain} produces
 * output acoustically equivalent to a sequential per-note reference for the
 * production melodic note shape: three independently resampled source layers,
 * each shaped by a per-layer envelope, summed (SSS), then passed through the
 * shared filter-envelope and volume-envelope stages. The back half is the same
 * code exercised by {@link BatchedPatternRendererTest}; this test adds the
 * three-layer summed front half.
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

	/**
	 * Generates {@value #LAYERS} layers of {@value #N} notes with distinct source
	 * buffers, per-layer pitch ratios, and per-layer envelopes; runs both the
	 * sequential per-note SSS reference and
	 * {@link BatchedPatternRenderer#buildBatchedSssChain}; and asserts the RMS
	 * difference is below {@code 1e-4}.
	 */
	@Test(timeout = 120000)
	@TestDepth(2)
	public void testSssAcousticEquivalence() {
		BatchedPatternRenderer renderer = new BatchedPatternRenderer(
				N, SOURCE_LENGTH, TARGET_LENGTH, SAMPLE_RATE, FILTER_ORDER);

		Random rng = new Random(7L);

		// Per-layer source buffers [LAYERS][N] and per-layer ratios [LAYERS][N].
		PackedCollection[][] sources = new PackedCollection[LAYERS][N];
		double[][] ratioValues = new double[LAYERS][N];
		for (int l = 0; l < LAYERS; l++) {
			for (int n = 0; n < N; n++) {
				double[] data = new double[SOURCE_LENGTH];
				for (int i = 0; i < SOURCE_LENGTH; i++) {
					data[i] = rng.nextDouble() * 2.0 - 1.0;
				}
				sources[l][n] = new PackedCollection(SOURCE_LENGTH);
				sources[l][n].setMem(data);
				// Ratios in [1.0, 1.45] — max source index < SOURCE_LENGTH.
				ratioValues[l][n] = 1.0 + 0.1 * l + 0.05 * n;
			}
		}

		// Per-layer envelopes [LAYERS] of shape [N, TARGET_LENGTH].
		double[][] layerEnvData = new double[LAYERS][N * TARGET_LENGTH];
		for (int l = 0; l < LAYERS; l++) {
			for (int n = 0; n < N; n++) {
				double sustain = 0.5 + 0.1 * l + 0.02 * n;
				PatternRenderingFloorBenchmark.fillAdsrShape(layerEnvData[l], n * TARGET_LENGTH, TARGET_LENGTH,
						0.0, 1.0, sustain, 0.0,
						0.04 + 0.01 * l, 0.09 + 0.01 * l, 0.13 + 0.01 * l);
			}
		}

		// Post-merge filter cutoff envelopes [N, TARGET_LENGTH].
		double[] filterCutoffData = new double[N * TARGET_LENGTH];
		for (int n = 0; n < N; n++) {
			PatternRenderingFloorBenchmark.fillAdsrShape(filterCutoffData, n * TARGET_LENGTH, TARGET_LENGTH,
					150.0 + n * 50.0, 4000.0 + n * 600.0, 800.0 + n * 200.0, 150.0 + n * 50.0,
					0.05 + n * 0.005, 0.10 + n * 0.005, 0.15 + n * 0.005);
		}
		PackedCollection filterCutoffs = new PackedCollection(shape(N, TARGET_LENGTH));
		filterCutoffs.setMem(filterCutoffData);

		// Post-merge volume envelopes [N, TARGET_LENGTH].
		double[] volumeEnvData = new double[N * TARGET_LENGTH];
		for (int n = 0; n < N; n++) {
			PatternRenderingFloorBenchmark.fillAdsrShape(volumeEnvData, n * TARGET_LENGTH, TARGET_LENGTH,
					0.0, 1.0, 0.4 + n * 0.05, 0.0,
					0.05 + n * 0.005, 0.10 + n * 0.005, 0.15 + n * 0.005);
		}
		PackedCollection volumeEnvelopes = new PackedCollection(shape(N, TARGET_LENGTH));
		volumeEnvelopes.setMem(volumeEnvData);

		// ── Per-note sequential SSS reference ─────────────────────────────────
		// For each note: sum (resample(layer) × perLayerEnv) over layers →
		// lowPass(cutoff) → × volume → accumulate.
		double[] reference = new double[TARGET_LENGTH];
		for (int n = 0; n < N; n++) {
			double[] merged = new double[TARGET_LENGTH];
			for (int l = 0; l < LAYERS; l++) {
				PackedCollection resampled =
						renderer.buildResampleProducer(sources[l][n], ratioValues[l][n])
								.get().evaluate();
				for (int i = 0; i < TARGET_LENGTH; i++) {
					merged[i] += resampled.toDouble(i) * layerEnvData[l][n * TARGET_LENGTH + i];
				}
			}

			PackedCollection mergedN = new PackedCollection(TARGET_LENGTH);
			mergedN.setMem(merged);

			double[] cutoffNData = new double[TARGET_LENGTH];
			System.arraycopy(filterCutoffData, n * TARGET_LENGTH, cutoffNData, 0, TARGET_LENGTH);
			PackedCollection cutoffN = new PackedCollection(TARGET_LENGTH);
			cutoffN.setMem(cutoffNData);

			PackedCollection filtered =
					c(lowPass(traverseEach(cp(mergedN)), cp(cutoffN), SAMPLE_RATE, FILTER_ORDER))
							.reshape(shape(TARGET_LENGTH))
							.get().evaluate();

			double[] volNData = new double[TARGET_LENGTH];
			System.arraycopy(volumeEnvData, n * TARGET_LENGTH, volNData, 0, TARGET_LENGTH);
			PackedCollection volN = new PackedCollection(TARGET_LENGTH);
			volN.setMem(volNData);

			PackedCollection voiced = cp(filtered).multiply(cp(volN)).get().evaluate();
			for (int i = 0; i < TARGET_LENGTH; i++) {
				reference[i] += voiced.toDouble(i);
			}
		}

		// ── Batched SSS chain path ────────────────────────────────────────────
		PackedCollection[] batchedSources = new PackedCollection[LAYERS];
		PackedCollection[] ratios = new PackedCollection[LAYERS];
		PackedCollection[] layerEnvelopes = new PackedCollection[LAYERS];
		for (int l = 0; l < LAYERS; l++) {
			double[] batchData = new double[N * SOURCE_LENGTH];
			double[] ratioData = new double[N];
			for (int n = 0; n < N; n++) {
				for (int i = 0; i < SOURCE_LENGTH; i++) {
					batchData[n * SOURCE_LENGTH + i] = sources[l][n].toDouble(i);
				}
				ratioData[n] = ratioValues[l][n];
			}
			batchedSources[l] = new PackedCollection(shape(N, SOURCE_LENGTH));
			batchedSources[l].setMem(batchData);
			ratios[l] = new PackedCollection(N);
			ratios[l].setMem(ratioData);
			layerEnvelopes[l] = new PackedCollection(shape(N, TARGET_LENGTH));
			layerEnvelopes[l].setMem(layerEnvData[l]);
		}

		PackedCollection batchedOutput =
				renderer.buildBatchedSssChain(batchedSources, ratios, layerEnvelopes,
						filterCutoffs, volumeEnvelopes)
						.get().evaluate();

		// ── Acoustic equivalence assertion ────────────────────────────────────
		double sumSqDiff = 0.0;
		double sumSqRef = 0.0;
		for (int i = 0; i < TARGET_LENGTH; i++) {
			double diff = reference[i] - batchedOutput.toDouble(i);
			sumSqDiff += diff * diff;
			sumSqRef += reference[i] * reference[i];
		}
		double rms = Math.sqrt(sumSqDiff / TARGET_LENGTH);
		double refRms = Math.sqrt(sumSqRef / TARGET_LENGTH);

		log("BatchedPatternRenderer SSS acoustic equivalence:");
		log(String.format("  Reference RMS: %.6f", refRms));
		log(String.format("  Difference RMS: %.6f", rms));
		if (refRms > 1e-10) {
			log(String.format("  Relative difference: %.2e", rms / refRms));
		}

		Assert.assertTrue(
				"Batched SSS chain RMS difference from per-note reference exceeds 1e-4 (got " + rms + ")",
				rms < 1e-4);
	}
}
