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
import org.almostrealism.util.FirFilterTestFeatures;
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
public class BatchedSssChainTest extends TestSuiteBase
		implements TemporalFeatures, FirFilterTestFeatures {

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
		/** Per-note reference voiced output, shape {@code [N, TARGET_LENGTH]}. */
		private PackedCollection voiced;
	}

	/**
	 * Builds a synthetic SSS workload: per-layer random sources, ratios, and
	 * envelopes plus post-merge filter/volume envelopes, and computes the
	 * sequential per-note reference voiced rows (sum of resample × per-layer
	 * envelope over layers → lowPass(cutoff) → × volume).
	 */
	private Workload buildWorkload(BatchedPatternRenderer renderer) {
		Workload w = new Workload();
		w.voiced = new PackedCollection(shape(N, TARGET_LENGTH));
		Random rng = new Random(7L);

		PackedCollection[][] sourceByLayerNote = new PackedCollection[LAYERS][N];

		for (int l = 0; l < LAYERS; l++) {
			w.sources[l] = new PackedCollection(shape(N, SOURCE_LENGTH));
			w.layerEnvelopes[l] = new PackedCollection(shape(N, TARGET_LENGTH));

			for (int n = 0; n < N; n++) {
				sourceByLayerNote[l][n] =
						rand(shape(SOURCE_LENGTH), rng).multiply(2.0).add(-1.0).evaluate();
				w.sources[l].setFrom(n * SOURCE_LENGTH, sourceByLayerNote[l][n]);


				double sustain = 0.5 + 0.1 * l + 0.02 * n;
				w.layerEnvelopes[l].setFrom(n * TARGET_LENGTH,
						PatternRenderingFloorBenchmark.adsrShape(TARGET_LENGTH,
								0.0, 1.0, sustain, 0.0,
								0.04 + 0.01 * l, 0.09 + 0.01 * l, 0.13 + 0.01 * l));
			}

			w.ratios[l] = integers(0, N).multiply(0.05).add(1.0 + 0.1 * l).evaluate();
		}

		w.filterCutoffs = new PackedCollection(shape(N, TARGET_LENGTH));
		w.volumeEnvelopes = new PackedCollection(shape(N, TARGET_LENGTH));

		for (int n = 0; n < N; n++) {
			w.filterCutoffs.setFrom(n * TARGET_LENGTH,
					PatternRenderingFloorBenchmark.adsrShape(TARGET_LENGTH,
							150.0 + n * 50.0, 4000.0 + n * 600.0, 800.0 + n * 200.0, 150.0 + n * 50.0,
							0.05 + n * 0.005, 0.10 + n * 0.005, 0.15 + n * 0.005));
			w.volumeEnvelopes.setFrom(n * TARGET_LENGTH,
					PatternRenderingFloorBenchmark.adsrShape(TARGET_LENGTH,
							0.0, 1.0, 0.4 + n * 0.05, 0.0,
							0.05 + n * 0.005, 0.10 + n * 0.005, 0.15 + n * 0.005));
		}

		// Per-note reference: Σ_layer resample × perLayerEnv → lowPass → × volume.
		for (int n = 0; n < N; n++) {
			CollectionProducer merged = null;
			for (int l = 0; l < LAYERS; l++) {
				PackedCollection resampled =
						renderer.buildResampleProducer(sourceByLayerNote[l][n],
								cp(w.ratios[l].get(n, shape(1))))
								.get().evaluate();
				CollectionProducer layer = cp(resampled)
						.multiply(cp(w.layerEnvelopes[l].get(n, shape(TARGET_LENGTH))));
				merged = merged == null ? layer : merged.add(layer);
			}

			PackedCollection mergedN = merged.evaluate();
			PackedCollection cutoffN = w.filterCutoffs.get(n, shape(TARGET_LENGTH));
			PackedCollection volN = w.volumeEnvelopes.get(n, shape(TARGET_LENGTH));

			PackedCollection filtered =
					c(lowPass(traverseEach(cp(mergedN)), cp(cutoffN), SAMPLE_RATE, FILTER_ORDER))
							.reshape(shape(TARGET_LENGTH))
							.get().evaluate();
			w.voiced.setFrom(n * TARGET_LENGTH,
					cp(filtered).multiply(cp(volN)).get().evaluate());
		}

		return w;
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

		// The aligned reference is the sum over notes, which is one row of ones
		// against the per-note voiced rows.
		PackedCollection expected = matmul(constant(shape(1, N), 1.0), cp(w.voiced))
				.evaluate().reshape(shape(TARGET_LENGTH));

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
		PackedCollection destOffsets = pack(0.0, 256.0, 512.0, 700.0);

		// Each note's voiced row is added into the window at its own offset, clipped
		// where the row would run past the end.
		PackedCollection expected = new PackedCollection(windowWidth);
		for (int n = 0; n < N; n++) {
			int off = (int) destOffsets.toDouble(n);
			int length = Math.min(TARGET_LENGTH, windowWidth - off);
			if (length <= 0) continue;

			PackedCollection slot = expected.range(shape(length), off);
			slot.setFrom(0, add(cp(slot),
					cp(w.voiced.range(shape(length), n * TARGET_LENGTH))).evaluate());
		}

		PackedCollection out = renderer.buildBatchedSssChainPlaced(
				w.sources, w.ratios, w.layerEnvelopes, w.filterCutoffs, w.volumeEnvelopes,
				destOffsets, windowWidth)
				.get().evaluate();

		assertRmsEquivalent("Batched SSS placed chain", expected, out);
	}
}
