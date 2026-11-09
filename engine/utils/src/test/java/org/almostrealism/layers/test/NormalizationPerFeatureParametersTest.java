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

package org.almostrealism.layers.test;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.GradientRecorder;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * The per-feature parameters of the normalization layers — the scale and shift of LayerNorm and
 * RMSNorm, and the alpha, scale and shift of DynamicTanh — reach every feature of every normalized
 * run, whatever the number of runs. Each layer normalizes runs of {@code size} consecutive features
 * and then applies its {@code size}-long parameters feature by feature; an input holding exactly as
 * many runs as each run has features (a {@code [4, 4]} input, or a {@code [2, 2, 4]} batch of two
 * sequences of two positions) is the case that tells applying a parameter per feature from applying
 * it per run, so every check below includes it alongside an input whose run count differs.
 *
 * <p>Each layer is compared with the same arithmetic done on the host in double precision.</p>
 */
public class NormalizationPerFeatureParametersTest extends TestSuiteBase implements LayerFeatures {

	/** The number of features in every normalized run. */
	private static final int SIZE = 4;

	/** The epsilon every layer under test adds to its statistic. */
	private static final double EPSILON = 1e-5;

	/** Largest absolute difference permitted from the host reference. */
	private static final double TOLERANCE = 1e-5;

	/**
	 * The input shapes every check runs: three runs (fewer than the features), four runs as a matrix,
	 * and four runs as a batch of two sequences of two positions.
	 */
	private final TraversalPolicy[] shapes = {
			shape(3, SIZE), shape(SIZE, SIZE), shape(2, 2, SIZE)
	};

	/**
	 * LayerNorm centers every run and divides it by its standard deviation, then multiplies feature
	 * {@code c} by {@code weights[c]} and adds {@code biases[c]}, for every run.
	 */
	@Test(timeout = 120000)
	public void layerNormScalesAndShiftsEachFeature() {
		PackedCollection weights = PackedCollection.of(1.0, 2.0, 3.0, 4.0);
		PackedCollection biases = PackedCollection.of(0.5, -0.5, 1.5, -1.5);
		for (TraversalPolicy shape : shapes) {
			PackedCollection input = wave(shape, 0.37);
			double[] expected = hostNormalize(input, true, weights, biases);
			double[] actual = forward(norm(weights, biases, EPSILON).apply(shape), shape, input);
			assertClose("LayerNorm of " + shape, expected, actual);
		}
	}

	/**
	 * RMSNorm divides every run by its root mean square, then multiplies feature {@code c} by
	 * {@code weights[c]} and adds {@code biases[c]}, for every run.
	 */
	@Test(timeout = 120000)
	public void rmsNormScalesAndShiftsEachFeature() {
		PackedCollection weights = PackedCollection.of(1.0, 2.0, 3.0, 4.0);
		PackedCollection biases = PackedCollection.of(0.5, -0.5, 1.5, -1.5);
		for (TraversalPolicy shape : shapes) {
			PackedCollection input = wave(shape, 0.41);
			double[] expected = hostNormalize(input, false, weights, biases);
			double[] actual = forward(rmsnorm(shape, weights, biases, EPSILON), shape, input);
			assertClose("RMSNorm of " + shape, expected, actual);
		}
	}

	/**
	 * DynamicTanh turns feature {@code c} of every run into
	 * {@code weights[c] * tanh(alpha[c] * x) + biases[c]}.
	 */
	@Test(timeout = 120000)
	public void dynamicTanhAppliesEachFeaturesParameters() {
		PackedCollection alpha = PackedCollection.of(0.5, 1.0, 1.5, 2.0);
		PackedCollection weights = PackedCollection.of(1.0, 2.0, 3.0, 4.0);
		PackedCollection biases = PackedCollection.of(0.5, -0.5, 1.5, -1.5);
		double[] a = alpha.doubleStream().toArray();
		double[] w = weights.doubleStream().toArray();
		double[] b = biases.doubleStream().toArray();
		for (TraversalPolicy shape : shapes) {
			PackedCollection input = wave(shape, 0.29);
			double[] x = input.doubleStream().toArray();
			double[] expected = new double[x.length];
			for (int i = 0; i < x.length; i++) {
				int c = i % SIZE;
				expected[i] = w[c] * Math.tanh(a[c] * x[i]) + b[c];
			}

			double[] actual = forward(dynamicTanh(shape, alpha, weights, biases), shape, input);
			assertClose("DynamicTanh of " + shape, expected, actual);
		}
	}

	/**
	 * The backward pass delivers to LayerNorm's scale the gradient of each feature, summed over the
	 * runs: {@code dL/dweights[c] = sum over runs r of normalized[r, c] * G[r, c]} for an output
	 * gradient {@code G}, so a scale trains feature by feature whatever the number of runs.
	 */
	@Test(timeout = 120000)
	public void layerNormScaleGradientSumsEachFeatureOverTheRuns() {
		PackedCollection weights = PackedCollection.of(1.0, 2.0, 3.0, 4.0);
		PackedCollection unit = PackedCollection.of(1.0, 1.0, 1.0, 1.0);
		for (TraversalPolicy shape : shapes) {
			PackedCollection input = wave(shape, 0.37);
			PackedCollection outputGradient = wave(shape, 0.53);
			double[] normalized = hostNormalize(input, true, unit, null);
			double[] g = outputGradient.doubleStream().toArray();
			double[] expected = new double[SIZE];
			for (int i = 0; i < g.length; i++) {
				expected[i % SIZE] += normalized[i] * g[i];
			}

			GradientRecorder recorder = new GradientRecorder();
			Model model = new Model(shape, recorder);
			model.add(norm(weights, null, EPSILON).apply(shape));
			try (CompiledModel compiled = model.compile(true, true)) {
				compiled.forward(input);
				compiled.backward(outputGradient);
				assertClose("LayerNorm scale gradient for " + shape, expected, recorder.gradientOfSize(SIZE));
			}
		}
	}

	/**
	 * Normalizes every run of {@link #SIZE} features of the input on the host, centering it first for
	 * LayerNorm, then applies the per-feature scale and shift.
	 *
	 * @param input   the input
	 * @param center  true for LayerNorm (divide the centered run by its standard deviation), false for
	 *                RMSNorm (divide the run by its root mean square)
	 * @param weights the per-feature scale
	 * @param biases  the per-feature shift, or {@code null} for none
	 * @return the normalized input, flattened
	 */
	private double[] hostNormalize(PackedCollection input, boolean center,
								   PackedCollection weights, PackedCollection biases) {
		double[] x = input.doubleStream().toArray();
		double[] w = weights.doubleStream().toArray();
		double[] b = biases == null ? new double[SIZE] : biases.doubleStream().toArray();
		double[] out = new double[x.length];
		for (int run = 0; run < x.length / SIZE; run++) {
			double mean = 0.0;
			double meanSquare = 0.0;
			for (int c = 0; c < SIZE; c++) {
				mean += x[run * SIZE + c] / SIZE;
				meanSquare += x[run * SIZE + c] * x[run * SIZE + c] / SIZE;
			}

			double shift = center ? mean : 0.0;
			double divisor = Math.sqrt((center ? meanSquare - mean * mean : meanSquare) + EPSILON);
			for (int c = 0; c < SIZE; c++) {
				out[run * SIZE + c] = (x[run * SIZE + c] - shift) / divisor * w[c] + b[c];
			}
		}
		return out;
	}

	/**
	 * Compiles a layer as a one-layer model and forwards the input once.
	 *
	 * @param layer the layer
	 * @param shape the layer's input shape
	 * @param input the input
	 * @return the output, flattened
	 */
	private double[] forward(CellularLayer layer, TraversalPolicy shape, PackedCollection input) {
		Model model = new Model(shape);
		model.add(layer);
		try (CompiledModel compiled = model.compile(false)) {
			return compiled.forward(input).doubleStream().toArray();
		}
	}

	/**
	 * Asserts that two arrays have the same length and agree element-wise within {@link #TOLERANCE}.
	 *
	 * @param label    the comparison, for failure messages
	 * @param expected the expected values
	 * @param actual   the actual values
	 */
	private void assertClose(String label, double[] expected, double[] actual) {
		Assert.assertEquals(label + ": length", expected.length, actual.length);
		for (int i = 0; i < expected.length; i++) {
			Assert.assertEquals(label + ": element " + i, expected[i], actual[i], TOLERANCE);
		}
	}

	/**
	 * A deterministic input whose runs differ from each other and from their features:
	 * {@code sin(frequency * i + 0.2)} for element {@code i}, computed on the device.
	 *
	 * @param shape     the shape of the input
	 * @param frequency the angular step between consecutive elements
	 * @return the input
	 */
	private PackedCollection wave(TraversalPolicy shape, double frequency) {
		return sin(integers(0, shape.getTotalSize()).multiply(frequency).add(0.2)).reshape(shape).evaluate();
	}
}
