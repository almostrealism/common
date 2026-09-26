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

package org.almostrealism.ml.audio;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.layers.LayerRoutingFeatures;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Pins the Oobleck residual unit built from the {@code oobleck_residual_block.pdsl} asset by
 * {@link OobleckCodec#buildResidualBlock} against two references that do not depend on the asset:
 * <ul>
 *   <li>the Java assembly the asset replaced — {@code residual(} Snake, weight-normalized Conv1d,
 *       Snake, weight-normalized Conv1d {@code )} built directly from {@code ActivationFeatures},
 *       {@code ConvolutionLayerFeatures} and {@code LayerRoutingFeatures} — so the migration is
 *       shown to preserve the exact composition; and</li>
 *   <li>a plain double-precision evaluation of the same arithmetic (weight normalization
 *       {@code w = v * g / ||v||}, the two cross-correlations, the periodic Snake activation and
 *       the residual add), so every stage is checked against its definition rather than only
 *       against the framework layers.</li>
 * </ul>
 *
 * <p>The block is exercised through the production path: an in-memory {@link StateDictionary}
 * holds synthetic weights keyed exactly as the Stable Audio Open checkpoint keys the residual
 * block, and a minimal {@link OobleckCodec} subclass calls {@code buildResidualBlock}. A scaled-
 * down but honest configuration is used — several channels so the per-channel Snake parameters
 * and the weight-normalization norms are not trivially satisfied, a kernel-7 first convolution
 * and a kernel-1 second convolution as in the real block, and a sequence long enough that the
 * kernel-7 padding does not span the whole signal.</p>
 *
 * <p>This block holds no state that persists across forward passes (it declares no {@code state}
 * cache, unlike the attention block), so {@link #residualBlockIsAPureFunctionOfItsInput} pins
 * that property directly rather than exercising a cache over several positions.</p>
 */
public class OobleckResidualBlockPdslTest extends TestSuiteBase implements LayerRoutingFeatures {

	/** Absolute tolerance between the asset block and the equivalent Java assembly (both single precision). */
	private static final double PARITY_TOLERANCE = 1e-4;

	/** Absolute tolerance between the single-precision asset block and the double-precision host reference. */
	private static final double REFERENCE_TOLERANCE = 3e-3;

	/** Batch size. */
	private static final int BATCH = 1;

	/** Channel count, held constant through the residual block. */
	private static final int CHANNELS = 4;

	/** Sequence length, held constant through the residual block. */
	private static final int SEQ_LENGTH = 12;

	/** Kernel size of the first convolution. */
	private static final int CONV1_KERNEL = 7;

	/** Padding of the first (length-preserving) convolution. */
	private static final int CONV1_PADDING = 3;

	/** Kernel size of the second, pointwise convolution. */
	private static final int CONV3_KERNEL = 1;

	/** Padding of the second convolution. */
	private static final int CONV3_PADDING = 0;

	/** Weight-key prefix under which the synthetic residual-block weights are stored. */
	private static final String PREFIX = "encoder.layers.1.layers.0";

	/** Epsilon added to a weight-normalization norm before division, matching the framework. */
	private static final double WEIGHT_NORM_EPSILON = 1e-12;

	/**
	 * The block the asset builds reproduces, element for element, the Java assembly it replaced:
	 * a residual around two Snake+Conv1d pairs, with the convolutions weight-normalized.
	 */
	@Test(timeout = 300000)
	public void residualBlockMatchesJavaAssembly() {
		StateDictionary weights = syntheticWeights(7L);
		PackedCollection input = uniform(-1.0, 1.0, new Random(101L), BATCH, CHANNELS, SEQ_LENGTH);

		double[] asset = forward(assetBlock(weights), input);
		double[] assembly = forward(javaAssemblyBlock(weights), input);

		assertClose("asset vs Java assembly", assembly, asset, PARITY_TOLERANCE);
	}

	/**
	 * The block the asset builds reproduces a plain double-precision evaluation of the residual
	 * unit's arithmetic, so its correctness does not rest on the framework's own Conv1d and Snake
	 * layers agreeing with themselves.
	 */
	@Test(timeout = 300000)
	public void residualBlockMatchesHostReference() {
		StateDictionary weights = syntheticWeights(13L);
		PackedCollection input = uniform(-1.0, 1.0, new Random(202L), BATCH, CHANNELS, SEQ_LENGTH);

		double[] asset = forward(assetBlock(weights), input);
		double[] expected = hostReference(weights, input);

		assertClose("asset vs host reference", expected, asset, REFERENCE_TOLERANCE);
	}

	/**
	 * The residual block is a pure function of its input: it declares no persistent state, so the
	 * same input compiled once yields the same output regardless of what was pushed through the
	 * block in between.
	 */
	@Test(timeout = 300000)
	public void residualBlockIsAPureFunctionOfItsInput() {
		StateDictionary weights = syntheticWeights(23L);
		PackedCollection inputA = uniform(-1.0, 1.0, new Random(303L), BATCH, CHANNELS, SEQ_LENGTH);
		PackedCollection inputB = uniform(-1.0, 1.0, new Random(404L), BATCH, CHANNELS, SEQ_LENGTH);

		Model model = new Model(shape(BATCH, CHANNELS, SEQ_LENGTH));
		model.add(assetBlock(weights));
		CompiledModel compiled = model.compile();

		double[] firstA = compiled.forward(inputA).toArray();
		compiled.forward(inputB);
		double[] secondA = compiled.forward(inputA).toArray();

		assertClose("repeated forward of the same input", firstA, secondA, 0.0);
	}

	/** Builds the residual block from the asset through the production {@link OobleckCodec} path. */
	private Block assetBlock(StateDictionary weights) {
		LoaderCodec codec = new LoaderCodec(weights, shape(BATCH, CHANNELS, SEQ_LENGTH));
		return codec.residualBlock(BATCH, CHANNELS, SEQ_LENGTH, PREFIX);
	}

	/**
	 * Builds the residual block as the deleted Java assembly did: a residual around a sequence of
	 * Snake, weight-normalized Conv1d, Snake and weight-normalized Conv1d.
	 */
	private Block javaAssemblyBlock(StateDictionary weights) {
		TraversalPolicy inputShape = shape(BATCH, CHANNELS, SEQ_LENGTH);
		SequentialBlock mainPath = new SequentialBlock(inputShape);
		mainPath.add(snake(inputShape,
				weights.get(PREFIX + ".layers.0.alpha"), weights.get(PREFIX + ".layers.0.beta")));
		mainPath.add(wnConv1d(BATCH, CHANNELS, CHANNELS, SEQ_LENGTH, CONV1_KERNEL, 1, CONV1_PADDING,
				weights.get(PREFIX + ".layers.1.weight_g"), weights.get(PREFIX + ".layers.1.weight_v"),
				weights.get(PREFIX + ".layers.1.bias")));
		mainPath.add(snake(inputShape,
				weights.get(PREFIX + ".layers.2.alpha"), weights.get(PREFIX + ".layers.2.beta")));
		mainPath.add(wnConv1d(BATCH, CHANNELS, CHANNELS, SEQ_LENGTH, CONV3_KERNEL, 1, CONV3_PADDING,
				weights.get(PREFIX + ".layers.3.weight_g"), weights.get(PREFIX + ".layers.3.weight_v"),
				weights.get(PREFIX + ".layers.3.bias")));
		return residual(mainPath);
	}

	/** Compiles a block on its own inside a {@link Model} and runs one input through it. */
	private double[] forward(Block block, PackedCollection input) {
		Model model = new Model(shape(BATCH, CHANNELS, SEQ_LENGTH));
		model.add(block);
		return model.compile().forward(input).toArray();
	}

	/**
	 * The residual unit's output in plain double precision: {@code input + conv3(snake2(conv1(
	 * snake0(input))))}, with each convolution weight-normalized. This is the oracle the asset is
	 * pinned against.
	 *
	 * @param weights the residual block's weights
	 * @param input   the {@code [batch, channels, length]} input
	 * @return the flattened residual-block output
	 */
	private double[] hostReference(StateDictionary weights, PackedCollection input) {
		double[] x = input.toArray();

		double[] afterSnake0 = snakeReference(x,
				weights.get(PREFIX + ".layers.0.alpha").toArray(),
				weights.get(PREFIX + ".layers.0.beta").toArray());
		double[] afterConv1 = convolveReference(afterSnake0,
				weightNormReference(weights.get(PREFIX + ".layers.1.weight_g").toArray(),
						weights.get(PREFIX + ".layers.1.weight_v").toArray(), CONV1_KERNEL),
				weights.get(PREFIX + ".layers.1.bias").toArray(), CONV1_KERNEL, CONV1_PADDING);
		double[] afterSnake2 = snakeReference(afterConv1,
				weights.get(PREFIX + ".layers.2.alpha").toArray(),
				weights.get(PREFIX + ".layers.2.beta").toArray());
		double[] afterConv3 = convolveReference(afterSnake2,
				weightNormReference(weights.get(PREFIX + ".layers.3.weight_g").toArray(),
						weights.get(PREFIX + ".layers.3.weight_v").toArray(), CONV3_KERNEL),
				weights.get(PREFIX + ".layers.3.bias").toArray(), CONV3_KERNEL, CONV3_PADDING);

		double[] out = new double[x.length];
		for (int i = 0; i < out.length; i++) {
			out[i] = x[i] + afterConv3[i];
		}
		return out;
	}

	/** Per-channel Snake activation {@code f(x) = x + (1 / beta) * sin^2(alpha * x)} on the host. */
	private static double[] snakeReference(double[] x, double[] alpha, double[] beta) {
		double[] out = new double[x.length];
		for (int c = 0; c < CHANNELS; c++) {
			for (int t = 0; t < SEQ_LENGTH; t++) {
				int i = c * SEQ_LENGTH + t;
				double s = Math.sin(alpha[c] * x[i]);
				out[i] = x[i] + s * s / beta[c];
			}
		}
		return out;
	}

	/**
	 * Weight normalization {@code w[o] = g[o] * v[o] / (||v[o]|| + eps)} on the host, with the norm
	 * taken over each output channel's whole {@code [channels, kernel]} slice.
	 */
	private static double[] weightNormReference(double[] weightG, double[] weightV, int kernel) {
		int sliceSize = CHANNELS * kernel;
		double[] w = new double[CHANNELS * sliceSize];
		for (int o = 0; o < CHANNELS; o++) {
			double sumSq = 0;
			for (int j = 0; j < sliceSize; j++) {
				double value = weightV[o * sliceSize + j];
				sumSq += value * value;
			}
			double scale = weightG[o] / (Math.sqrt(sumSq) + WEIGHT_NORM_EPSILON);
			for (int j = 0; j < sliceSize; j++) {
				w[o * sliceSize + j] = weightV[o * sliceSize + j] * scale;
			}
		}
		return w;
	}

	/**
	 * Channels-to-channels 1-D cross-correlation on the host at stride 1:
	 * {@code out[o, t] = bias[o] + sum over c, k of weight[o, c, k] * in[c, t + k - padding]},
	 * with out-of-range positions treated as zero. Padding is chosen so the length is preserved.
	 */
	private static double[] convolveReference(double[] in, double[] weight, double[] bias,
											  int kernel, int padding) {
		double[] out = new double[CHANNELS * SEQ_LENGTH];
		for (int o = 0; o < CHANNELS; o++) {
			for (int t = 0; t < SEQ_LENGTH; t++) {
				double acc = bias[o];
				for (int c = 0; c < CHANNELS; c++) {
					for (int k = 0; k < kernel; k++) {
						int pos = t + k - padding;
						if (pos >= 0 && pos < SEQ_LENGTH) {
							acc += weight[(o * CHANNELS + c) * kernel + k] * in[c * SEQ_LENGTH + pos];
						}
					}
				}
				out[o * SEQ_LENGTH + t] = acc;
			}
		}
		return out;
	}

	/**
	 * An in-memory {@link StateDictionary} with synthetic weights keyed exactly as the residual
	 * block reads them, drawn from the given seed. Snake beta and the weight magnitudes are kept
	 * positive so the activation and the normalization are well defined.
	 */
	private StateDictionary syntheticWeights(long seed) {
		Random random = new Random(seed);
		Map<String, PackedCollection> weights = new HashMap<>();
		weights.put(PREFIX + ".layers.0.alpha", uniform(0.5, 1.5, random, CHANNELS));
		weights.put(PREFIX + ".layers.0.beta", uniform(0.5, 1.5, random, CHANNELS));
		weights.put(PREFIX + ".layers.1.weight_g", uniform(0.5, 1.5, random, CHANNELS, 1, 1));
		weights.put(PREFIX + ".layers.1.weight_v", uniform(-0.5, 0.5, random, CHANNELS, CHANNELS, CONV1_KERNEL));
		weights.put(PREFIX + ".layers.1.bias", uniform(-0.2, 0.2, random, CHANNELS));
		weights.put(PREFIX + ".layers.2.alpha", uniform(0.5, 1.5, random, CHANNELS));
		weights.put(PREFIX + ".layers.2.beta", uniform(0.5, 1.5, random, CHANNELS));
		weights.put(PREFIX + ".layers.3.weight_g", uniform(0.5, 1.5, random, CHANNELS, 1, 1));
		weights.put(PREFIX + ".layers.3.weight_v", uniform(-0.5, 0.5, random, CHANNELS, CHANNELS, CONV3_KERNEL));
		weights.put(PREFIX + ".layers.3.bias", uniform(-0.2, 0.2, random, CHANNELS));
		return new StateDictionary(weights);
	}

	/** Draws a tensor uniformly in {@code [min, max)} from the seeded source, produced on the device. */
	private PackedCollection uniform(double min, double max, Random random, int... dims) {
		TraversalPolicy shape = shape(dims);
		return rand(shape, random).multiply(max - min).add(min).reshape(shape).evaluate();
	}

	/** Compares two vectors element-wise within {@code tolerance}. */
	private static void assertClose(String label, double[] expected, double[] actual, double tolerance) {
		Assert.assertEquals(label + " length", expected.length, actual.length);
		for (int i = 0; i < expected.length; i++) {
			Assert.assertEquals(label + " element " + i, expected[i], actual[i], tolerance);
		}
	}

	/** Minimal concrete {@link OobleckCodec} exposing {@code buildResidualBlock} for the test. */
	private static final class LoaderCodec extends OobleckCodec {
		/**
		 * @param stateDict  the synthetic weights
		 * @param inputShape the residual block's input shape
		 */
		private LoaderCodec(StateDictionary stateDict, TraversalPolicy inputShape) {
			super(inputShape, stateDict);
		}

		/**
		 * Builds the residual block through the production loader.
		 *
		 * @param batchSize batch size
		 * @param channels  channel count
		 * @param seqLength sequence length
		 * @param prefix    weight-key prefix
		 * @return the residual block
		 */
		private Block residualBlock(int batchSize, int channels, int seqLength, String prefix) {
			return buildResidualBlock(batchSize, channels, seqLength, prefix);
		}
	}
}
