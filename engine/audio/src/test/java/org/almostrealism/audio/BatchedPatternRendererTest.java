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
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.time.TemporalFeatures;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.Random;

/**
 * Verifies that {@link org.almostrealism.audio.BatchedPatternRenderer#buildBatchedChain} produces output
 * acoustically equivalent to sequential per-note resample {@literal ->} lowPass filter {@literal ->}
 * volume envelope {@literal ->} accumulate evaluation on the same workload.
 *
 * <p>Both paths use identical pre-materialized per-row filter cutoff envelopes and
 * volume envelopes, so any difference is due to floating-point addition-order
 * variation in the batched reduction. The RMS tolerance ({@code 1e-4}) is tight
 * enough to catch a real regression but tolerant of such precision differences.</p>
 *
 * @see org.almostrealism.audio.BatchedPatternRenderer
 */
public class BatchedPatternRendererTest extends TestSuiteBase implements TemporalFeatures {

	/** Number of notes in the synthetic workload. */
	private static final int N = 8;

	/** Source samples per note before resampling. */
	private static final int SOURCE_LENGTH = 2048;

	/** Target samples per note after resampling. */
	private static final int TARGET_LENGTH = 1024;

	/** Audio sample rate — uses the configured {@link OutputLine#sampleRate} so tests adapt to any future default change. */
	private static final int SAMPLE_RATE = OutputLine.sampleRate;

	/** FIR filter order matching production {@code EfxManager.filterOrder}. */
	private static final int FILTER_ORDER = 40;

	/**
	 * Acoustic equivalence test: generates a synthetic workload of {@value #N} notes
	 * with distinct source buffers, pitch ratios (1.0 – 1.7), and per-row ADSR envelopes;
	 * runs both the sequential per-note pipeline and
	 * {@link BatchedPatternRenderer#buildBatchedChain} on the same inputs; and asserts the
	 * RMS difference is below {@code 1e-4}.
	 *
	 * <p>Per-note pipeline order: resample → lowPass(per-sample cutoff) →
	 * elementwise volume multiply → accumulate into output buffer.</p>
	 *
	 * <p>Batched pipeline: all four kernels in one compiled
	 * {@link CollectionProducer} via {@link BatchedPatternRenderer#buildBatchedChain}.</p>
	 */
	@Test(timeout = 120000)
	@TestDepth(2)
	public void testAcousticEquivalence() {
		BatchedPatternRenderer renderer = new BatchedPatternRenderer(
				N, SOURCE_LENGTH, TARGET_LENGTH, SAMPLE_RATE, FILTER_ORDER);

		Random rng = new Random(42L);

		// Build N distinct random source buffers of shape [SOURCE_LENGTH].
		PackedCollection[] sources = new PackedCollection[N];
		for (int n = 0; n < N; n++) {
			double[] data = new double[SOURCE_LENGTH];
			for (int i = 0; i < SOURCE_LENGTH; i++) {
				data[i] = rng.nextDouble() * 2.0 - 1.0;
			}
			sources[n] = new PackedCollection(SOURCE_LENGTH);
			sources[n].setMem(data);
		}

		// Per-note pitch ratios: 1.0, 1.1, ..., 1.7.
		// Max source index = floor(1023 × 1.7) + 1 = 1740 < SOURCE_LENGTH — in bounds.
		double[] ratioValues = new double[N];
		double[] ratioData = new double[N];
		for (int n = 0; n < N; n++) {
			ratioValues[n] = 1.0 + n * 0.1;
			ratioData[n] = ratioValues[n];
		}
		PackedCollection ratios = new PackedCollection(N);
		ratios.setMem(ratioData);

		// Per-row filter cutoff envelopes: shape [N, TARGET_LENGTH].
		// Each row is an ADSR-shaped Hz curve with distinct peak, sustain, and base values.
		double[] filterCutoffData = new double[N * TARGET_LENGTH];
		for (int n = 0; n < N; n++) {
			double peak = 4000.0 + n * 600.0;
			double sustain = 800.0 + n * 200.0;
			double base = 150.0 + n * 50.0;
			PatternRenderingFloorBenchmark.fillAdsrShape(filterCutoffData, n * TARGET_LENGTH, TARGET_LENGTH,
					base, peak, sustain, base,
					0.05 + n * 0.005, 0.10 + n * 0.005, 0.15 + n * 0.005);
		}
		PackedCollection filterCutoffs = new PackedCollection(shape(N, TARGET_LENGTH));
		filterCutoffs.setMem(filterCutoffData);

		// Per-row volume envelopes: shape [N, TARGET_LENGTH].
		double[] volumeEnvData = new double[N * TARGET_LENGTH];
		for (int n = 0; n < N; n++) {
			double sustain = 0.4 + n * 0.05;
			PatternRenderingFloorBenchmark.fillAdsrShape(volumeEnvData, n * TARGET_LENGTH, TARGET_LENGTH,
					0.0, 1.0, sustain, 0.0,
					0.05 + n * 0.005, 0.10 + n * 0.005, 0.15 + n * 0.005);
		}
		PackedCollection volumeEnvelopes = new PackedCollection(shape(N, TARGET_LENGTH));
		volumeEnvelopes.setMem(volumeEnvData);

		// ── Per-note sequential reference path ────────────────────────────────
		// For each note: resample → lowPass(cutoff) → × volume → accumulate.
		double[] reference = new double[TARGET_LENGTH];
		for (int n = 0; n < N; n++) {
			// Resample source[n] to [TARGET_LENGTH] using note n's ratio.
			PackedCollection resampled =
					renderer.buildResampleProducer(sources[n], ratioValues[n])
							.get().evaluate();

			// Extract per-note filter cutoff from row n of filterCutoffs.
			double[] cutoffNData = new double[TARGET_LENGTH];
			System.arraycopy(filterCutoffData, n * TARGET_LENGTH, cutoffNData, 0, TARGET_LENGTH);
			PackedCollection cutoffN = new PackedCollection(TARGET_LENGTH);
			cutoffN.setMem(cutoffNData);

			// Apply filter envelope: lowPass with per-sample Hz cutoff from cutoffN.
			PackedCollection filtered =
					c(lowPass(traverseEach(cp(resampled)), cp(cutoffN), SAMPLE_RATE, FILTER_ORDER))
							.reshape(shape(TARGET_LENGTH))
							.get().evaluate();

			// Extract per-note volume envelope from row n of volumeEnvelopes.
			double[] volNData = new double[TARGET_LENGTH];
			System.arraycopy(volumeEnvData, n * TARGET_LENGTH, volNData, 0, TARGET_LENGTH);
			PackedCollection volN = new PackedCollection(TARGET_LENGTH);
			volN.setMem(volNData);

			// Apply volume envelope: elementwise multiply.
			PackedCollection voiced = cp(filtered).multiply(cp(volN)).get().evaluate();

			// Accumulate into the reference output buffer.
			for (int i = 0; i < TARGET_LENGTH; i++) {
				reference[i] += voiced.toDouble(i);
			}
		}

		// ── Batched 4-kernel chain path ───────────────────────────────────────
		// Pack raw source buffers into batchedSource [N, SOURCE_LENGTH], then
		// evaluate the full resample → filter → volume → reduce chain in one shot.
		double[] batchData = new double[N * SOURCE_LENGTH];
		for (int n = 0; n < N; n++) {
			for (int i = 0; i < SOURCE_LENGTH; i++) {
				batchData[n * SOURCE_LENGTH + i] = sources[n].toDouble(i);
			}
		}
		PackedCollection batchedSource = new PackedCollection(shape(N, SOURCE_LENGTH));
		batchedSource.setMem(batchData);

		PackedCollection batchedOutput =
				renderer.buildBatchedChain(batchedSource, ratios, filterCutoffs, volumeEnvelopes)
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

		log("BatchedPatternRenderer acoustic equivalence:");
		log(String.format("  Reference RMS: %.6f", refRms));
		log(String.format("  Difference RMS: %.6f", rms));
		if (refRms > 1e-10) {
			log(String.format("  Relative difference: %.2e", rms / refRms));
		}

		Assert.assertTrue(
				"Batched chain RMS difference from per-note reference exceeds 1e-4 (got " + rms + ")",
				rms < 1e-4);
	}

}
