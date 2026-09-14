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

package org.almostrealism.ml;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.layers.NormalizationType;
import org.almostrealism.ml.audio.ConditioningMode;
import org.almostrealism.ml.audio.DiffusionTransformer;
import org.almostrealism.ml.audio.DiffusionTransformerConfig;
import org.almostrealism.ml.audio.DiffusionTransformerFeatures;
import org.almostrealism.ml.audio.TimestepEncoding;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.Map;

/**
 * Verifies the {@link NormalizationType} family selection: the RMS branch of the typed
 * normalization builder against a host reference, and a {@link DiffusionTransformer} configured
 * for RMS normalization consuming a checkpoint that carries only the {@code gamma} parameters.
 */
public class RmsNormalizationTest extends TestSuiteBase implements DiffusionTransformerFeatures {

	/** Batch size of every case. */
	private static final int BATCH = 1;

	/** Feature count of the normalization case. */
	private static final int FEATURES = 8;

	/**
	 * With {@link NormalizationType#RMS} the typed builder divides each feature vector by its root
	 * mean square (with the given epsilon) and scales by the weight, without subtracting the mean.
	 */
	@Test(timeout = 120000)
	public void rmsFamilyMatchesHostReference() {
		int rows = 4;
		double eps = 1e-6;
		TraversalPolicy shape = shape(BATCH, rows, FEATURES);
		PackedCollection weight = new PackedCollection(shape(FEATURES)).randnFill();
		PackedCollection input = new PackedCollection(shape).randnFill();

		Model model = new Model(shape);
		model.sequential().add(norm(NormalizationType.RMS, weight, null, eps));
		PackedCollection output = model.compile(false).forward(input).reshape(shape);

		double[] rowRms = new double[rows];
		for (int r = 0; r < rows; r++) {
			double sumSquares = 0.0;
			for (int f = 0; f < FEATURES; f++) {
				sumSquares += input.valueAt(0, r, f) * input.valueAt(0, r, f);
			}
			rowRms[r] = Math.sqrt(sumSquares / FEATURES + eps);
		}
		assertTrue("rows must differ in scale for the per-row statistic to be observable",
				Math.abs(rowRms[0] - rowRms[1]) > 1e-3 || Math.abs(rowRms[2] - rowRms[3]) > 1e-3);
		for (int r = 0; r < rows; r++) {
			for (int f = 0; f < FEATURES; f++) {
				double expected = input.valueAt(0, r, f) / rowRms[r] * weight.valueAt(f);
				assertEquals(expected, output.valueAt(0, r, f), 1e-4);
			}
		}
	}

	/**
	 * The RMS layer built directly over a multi-row shape, with a bias, normalizes each row by its
	 * own statistic; before this was fixed the per-row scale was applied cyclically by element
	 * index, so only single-vector inputs were normalized correctly.
	 */
	@Test(timeout = 120000)
	public void rmsLayerNormalizesEachRowIndependently() {
		int rows = 5;
		TraversalPolicy shape = shape(rows, FEATURES);
		PackedCollection weight = new PackedCollection(shape(FEATURES)).randnFill();
		PackedCollection bias = new PackedCollection(shape(FEATURES)).randnFill();
		PackedCollection input = new PackedCollection(shape).randnFill();

		Model model = new Model(shape);
		model.sequential().add(rmsnorm(shape, weight, bias, 1e-5));
		PackedCollection output = model.compile(false).forward(input).reshape(shape);

		for (int r = 0; r < rows; r++) {
			double sumSquares = 0.0;
			for (int f = 0; f < FEATURES; f++) {
				sumSquares += input.valueAt(r, f) * input.valueAt(r, f);
			}
			double rms = Math.sqrt(sumSquares / FEATURES + 1e-5);
			for (int f = 0; f < FEATURES; f++) {
				double expected = input.valueAt(r, f) / rms * weight.valueAt(f) + bias.valueAt(f);
				assertEquals(expected, output.valueAt(r, f), 1e-4);
			}
		}
	}

	/**
	 * A transformer configured for RMS normalization reads {@code pre_norm.gamma},
	 * {@code ff_norm.gamma} and {@code q_norm.gamma}/{@code k_norm.gamma} only: a checkpoint in that
	 * layout is consumed completely (so weight validation passes) and the prediction is finite.
	 */
	@Test(timeout = 240000)
	public void transformerConsumesRmsCheckpointLayout() {
		int ioChannels = 2;
		int dim = 16;
		int audioSeqLen = 6;
		int globalCondDim = 8;
		DiffusionTransformerConfig config = new DiffusionTransformerConfig(
				ioChannels, dim, 1, 2, 1, 0, globalCondDim, "rf_denoiser", audioSeqLen, 0)
				.withConditioningMode(ConditioningMode.ADALN)
				.withMemoryTokens(2)
				.withTimestepEncoding(TimestepEncoding.EXPO)
				.withNormalization(NormalizationType.RMS);

		Map<String, PackedCollection> weights = new DiffusionTransformerWeightFixture().weights(config);
		assertTrue(weights.containsKey("model.model.transformer.layers.0.pre_norm.gamma"));
		assertFalse(weights.containsKey("model.model.transformer.layers.0.pre_norm.beta"));
		assertTrue(weights.containsKey("model.model.transformer.layers.0.self_attn.q_norm.gamma"));
		assertFalse(weights.containsKey("model.model.transformer.layers.0.self_attn.q_norm.weight"));

		DiffusionTransformer transformer = new DiffusionTransformer(config, new StateDictionary(weights));
		PackedCollection input = new PackedCollection(shape(BATCH, ioChannels, audioSeqLen)).randnFill();
		PackedCollection timestep = new PackedCollection(shape(BATCH, 1)).fill(0.5);
		PackedCollection globalCond = new PackedCollection(shape(BATCH, globalCondDim)).randnFill();

		PackedCollection output = transformer.forward(input, timestep, null, globalCond);
		assertEquals(input.getShape().getTotalSize(), output.getShape().getTotalSize());

		double magnitude = 0.0;
		for (int i = 0; i < output.getShape().getTotalSize(); i++) {
			assertTrue(Double.isFinite(output.toDouble(i)));
			magnitude = Math.max(magnitude, Math.abs(output.toDouble(i)));
		}

		transformer.destroy();
		assertTrue("the prediction must not be identically zero", magnitude > 1e-6);
	}
}
