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

package org.almostrealism.ml.dsl;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.layers.LayerRoutingFeatures;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pins the {@code conv1d} and {@code snake} PDSL primitives on their own, through the
 * single-primitive layers of {@code test_conv_snake_primitives.pdsl}, independently of the
 * Oobleck residual unit that composes them.
 *
 * <p>Each primitive is checked two ways: against the {@code ConvolutionLayerFeatures}/
 * {@code ActivationFeatures} method it dispatches to (so the argument mapping — weight, bias,
 * stride and padding for the convolution; per-channel alpha and beta for Snake — is exercised),
 * and against a value computed on the host straight from the primitive's definition (so the
 * primitive is checked against its own arithmetic rather than only against the framework layer).</p>
 */
public class Conv1dSnakePrimitivesTest extends TestSuiteBase implements LayerRoutingFeatures {

	/** Absolute tolerance when comparing two single-precision kernels of the same operation. */
	private static final double KERNEL_TOLERANCE = 1e-5;

	/** Absolute tolerance when comparing a single-precision kernel to a double-precision expectation. */
	private static final double REFERENCE_TOLERANCE = 2e-3;

	/** Classpath location of the fixture program. */
	private static final String FIXTURE = "/pdsl/test_conv_snake_primitives.pdsl";

	/**
	 * The {@code conv1d} primitive produces the same result as {@code convolution1d} for a
	 * strided, padded convolution whose output-channel count differs from its input-channel
	 * count, so the primitive is reading the batch, channel counts, kernel, stride and padding
	 * from the shapes and arguments correctly.
	 */
	@Test(timeout = 120000)
	public void conv1dMatchesConvolutionLayer() {
		int batch = 1;
		int inChannels = 2;
		int outChannels = 3;
		int length = 6;
		int kernel = 3;
		int stride = 2;
		int padding = 1;

		PackedCollection weight = wave(shape(outChannels, inChannels, kernel), 0.37);
		PackedCollection bias = wave(shape(outChannels), 0.19);
		PackedCollection input = wave(shape(batch, inChannels, length), 0.53);

		double[] actual = run("conv1d_biased", shape(batch, inChannels, length),
				args("w", weight, "bias", bias, "stride", stride, "padding", padding), input);

		Model reference = new Model(shape(batch, inChannels, length));
		reference.add(convolution1d(batch, inChannels, outChannels, length, kernel, stride, padding,
				weight, bias));
		double[] expected = reference.compile().forward(input).toArray();

		assertClose("conv1d vs convolution1d", expected, actual, KERNEL_TOLERANCE);
	}

	/**
	 * The three-argument {@code conv1d} form (no bias) produces the same result as
	 * {@code convolution1d} with a {@code null} bias.
	 */
	@Test(timeout = 120000)
	public void conv1dWithoutBiasMatchesConvolutionLayer() {
		int batch = 1;
		int inChannels = 2;
		int outChannels = 2;
		int length = 5;
		int kernel = 3;

		PackedCollection weight = wave(shape(outChannels, inChannels, kernel), 0.41);
		PackedCollection input = wave(shape(batch, inChannels, length), 0.61);

		double[] actual = run("conv1d_unbiased", shape(batch, inChannels, length),
				args("w", weight, "stride", 1, "padding", 1), input);

		Model reference = new Model(shape(batch, inChannels, length));
		reference.add(convolution1d(batch, inChannels, outChannels, length, kernel, 1, 1,
				weight, null));
		double[] expected = reference.compile().forward(input).toArray();

		assertClose("conv1d (no bias) vs convolution1d", expected, actual, KERNEL_TOLERANCE);
	}

	/**
	 * The {@code conv1d} primitive computes the standard 1-D cross-correlation with same-length
	 * padding, checked against a plain-double convolution of a hand-chosen kernel.
	 */
	@Test(timeout = 120000)
	public void conv1dMatchesHandComputedReference() {
		int batch = 1;
		int inChannels = 1;
		int outChannels = 1;
		int length = 5;
		int kernel = 3;
		int stride = 1;
		int padding = 1;

		// A single [1, 1, 3] kernel [1, 2, 3] with bias 0.5 over the ramp input [1, 2, 3, 4, 5].
		TraversalPolicy weightShape = shape(outChannels, inChannels, kernel);
		TraversalPolicy biasShape = shape(outChannels);
		TraversalPolicy inputShape = shape(batch, inChannels, length);
		PackedCollection weight = pack(weightShape, 1.0, 2.0, 3.0);
		PackedCollection bias = pack(biasShape, 0.5);
		PackedCollection input = pack(inputShape, 1.0, 2.0, 3.0, 4.0, 5.0);

		double[] actual = run("conv1d_biased", inputShape,
				args("w", weight, "bias", bias, "stride", stride, "padding", padding), input);

		double[] expected = convolveReference(input.toArray(), weight.toArray(), bias.toArray(),
				inChannels, outChannels, length, kernel, stride, padding);
		assertClose("conv1d hand reference", expected, actual, REFERENCE_TOLERANCE);
	}

	/**
	 * The {@code snake} primitive produces the same result as the {@code snake} activation layer
	 * for per-channel parameters, so the alpha and beta arguments are passed through unchanged.
	 */
	@Test(timeout = 120000)
	public void snakeMatchesActivationLayer() {
		int batch = 1;
		int channels = 3;
		int length = 4;

		PackedCollection alpha = shiftedWave(shape(channels), 0.7, 2.0);
		PackedCollection beta = shiftedWave(shape(channels), 0.3, 2.0);
		PackedCollection input = wave(shape(batch, channels, length), 0.9);

		double[] actual = run("snake_channels", shape(batch, channels, length),
				args("alpha", alpha, "beta", beta), input);

		Model reference = new Model(shape(batch, channels, length));
		reference.add(snake(shape(batch, channels, length), alpha, beta));
		double[] expected = reference.compile().forward(input).toArray();

		assertClose("snake vs snake layer", expected, actual, KERNEL_TOLERANCE);
	}

	/**
	 * The {@code snake} primitive computes {@code x + (1 / beta) * sin^2(alpha * x)} per channel,
	 * checked against a plain-double evaluation of that expression.
	 */
	@Test(timeout = 120000)
	public void snakeMatchesHandComputedReference() {
		int batch = 1;
		int channels = 2;
		int length = 3;

		TraversalPolicy paramShape = shape(channels);
		TraversalPolicy inputShape = shape(batch, channels, length);
		PackedCollection alpha = pack(paramShape, 0.5, 2.0);
		PackedCollection beta = pack(paramShape, 1.0, 4.0);
		PackedCollection input = pack(inputShape, 1.0, -2.0, 3.0, 0.5, -1.5, 2.5);

		double[] actual = run("snake_channels", inputShape,
				args("alpha", alpha, "beta", beta), input);

		double[] a = alpha.toArray();
		double[] b = beta.toArray();
		double[] x = input.toArray();
		double[] expected = new double[x.length];
		for (int c = 0; c < channels; c++) {
			for (int t = 0; t < length; t++) {
				int i = c * length + t;
				double s = Math.sin(a[c] * x[i]);
				expected[i] = x[i] + s * s / b[c];
			}
		}
		assertClose("snake hand reference", expected, actual, REFERENCE_TOLERANCE);
	}

	/** {@code conv1d} rejects an argument count that is neither three nor four. */
	@Test(timeout = 60000)
	public void conv1dRejectsWrongArgumentCount() {
		PackedCollection weight = new PackedCollection(shape(2, 2, 3));
		try {
			PdslBuiltins.call("conv1d", List.of(weight, 1));
			Assert.fail("conv1d should reject a two-argument call");
		} catch (PdslParseException expected) {
			// expected
		}
	}

	/** {@code snake} rejects any argument count other than two. */
	@Test(timeout = 60000)
	public void snakeRejectsWrongArgumentCount() {
		PackedCollection alpha = new PackedCollection(shape(3));
		try {
			PdslBuiltins.call("snake", List.of(alpha));
			Assert.fail("snake should reject a one-argument call");
		} catch (PdslParseException expected) {
			// expected
		}
	}

	/**
	 * Standard 1-D cross-correlation of {@code input} against {@code weight} on the host:
	 * {@code out[o, t] = bias[o] + sum over c, k of weight[o, c, k] * input[c, t * stride + k - padding]},
	 * with out-of-range input positions treated as zero.
	 *
	 * @param input       flattened {@code [in_channels, length]} input
	 * @param weight      flattened {@code [out_channels, in_channels, kernel]} weight
	 * @param bias        {@code [out_channels]} bias
	 * @param inChannels  number of input channels
	 * @param outChannels number of output channels
	 * @param length      input length
	 * @param kernel      kernel size
	 * @param stride      convolution stride
	 * @param padding     zero-padding added to both ends of the input
	 * @return the flattened {@code [out_channels, out_length]} convolution
	 */
	private static double[] convolveReference(double[] input, double[] weight, double[] bias,
											  int inChannels, int outChannels, int length,
											  int kernel, int stride, int padding) {
		int outLength = (length + 2 * padding - kernel) / stride + 1;
		double[] out = new double[outChannels * outLength];
		for (int o = 0; o < outChannels; o++) {
			for (int t = 0; t < outLength; t++) {
				double acc = bias[o];
				for (int c = 0; c < inChannels; c++) {
					for (int k = 0; k < kernel; k++) {
						int pos = t * stride + k - padding;
						if (pos >= 0 && pos < length) {
							acc += weight[(o * inChannels + c) * kernel + k] * input[c * length + pos];
						}
					}
				}
				out[o * outLength + t] = acc;
			}
		}
		return out;
	}

	/** Runs one fixture layer over a single input and returns the output values. */
	private double[] run(String layer, TraversalPolicy inputShape, Map<String, Object> args,
						 PackedCollection input) {
		PdslLoader loader = new PdslLoader();
		Block block = loader.buildLayer(loader.parse(fixture()), layer, inputShape, args);
		Model model = new Model(inputShape);
		model.add(block);
		CompiledModel compiled = model.compile();
		return compiled.forward(input).toArray();
	}

	/** Reads the fixture program source from the test classpath. */
	private String fixture() {
		try (InputStream in = getClass().getResourceAsStream(FIXTURE)) {
			Assert.assertNotNull("Fixture " + FIXTURE + " missing from the test classpath", in);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new IllegalStateException("Failed to read " + FIXTURE, e);
		}
	}

	/** Builds an argument map from alternating names and values. */
	private static Map<String, Object> args(Object... namesAndValues) {
		Map<String, Object> args = new HashMap<>();
		for (int i = 0; i < namesAndValues.length; i += 2) {
			args.put((String) namesAndValues[i], namesAndValues[i + 1]);
		}
		return args;
	}

	/** A deterministic, non-degenerate collection: the sine of a scaled index ramp, on the device. */
	private PackedCollection wave(TraversalPolicy shape, double frequency) {
		return sin(integers(0, shape.getTotalSize()).multiply(frequency)).reshape(shape).evaluate();
	}

	/**
	 * A {@link #wave} shifted by {@code offset} so the values stay positive, for the Snake
	 * activation's per-channel alpha and beta parameters.
	 */
	private PackedCollection shiftedWave(TraversalPolicy shape, double frequency, double offset) {
		return sin(integers(0, shape.getTotalSize()).multiply(frequency)).add(offset)
				.reshape(shape).evaluate();
	}

	/** Compares two vectors element-wise within {@code tolerance}. */
	private static void assertClose(String label, double[] expected, double[] actual, double tolerance) {
		Assert.assertEquals(label + " length", expected.length, actual.length);
		for (int i = 0; i < expected.length; i++) {
			Assert.assertEquals(label + " element " + i, expected[i], actual[i], tolerance);
		}
	}
}
