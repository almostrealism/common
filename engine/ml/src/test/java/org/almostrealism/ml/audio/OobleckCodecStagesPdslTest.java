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
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.model.Block;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Pins the Oobleck codec as {@link OobleckCodec} builds it from the {@code oobleck_codec.pdsl}
 * asset — the encoder stage, the decoder stage, and whole encoder and decoder chains of input
 * projection, stages and output projection — against two references that do not depend on the
 * asset:
 * <ul>
 *   <li>the Java assembly it replaced, reproduced in {@link Codec} from {@code OobleckEncoder}
 *       ({@code buildEncoder} and {@code buildEncoderBlock}) and {@code OobleckDecoder}
 *       ({@code buildDecoder} and {@code buildDecoderBlock}) as they stood before the migration,
 *       with their architecture tables made parameters: Snake activations, weight-normalized
 *       {@code Conv1d} and {@code ConvTranspose1d} layers and the shared residual unit, chained in
 *       {@link SequentialBlock}s; and</li>
 *   <li>{@link HostCodec}, a plain double-precision evaluation of the same arithmetic — weight
 *       normalization, the Snake activation, strided cross-correlation, transposed convolution and
 *       the residual unit's skip connection — so the codec is checked against its definition
 *       rather than only against the framework layers agreeing with themselves.</li>
 * </ul>
 *
 * <p>Everything is built through the production path: an in-memory {@link StateDictionary} holds
 * synthetic weights keyed exactly as the Stable Audio Open checkpoint keys them, and a minimal
 * {@link OobleckCodec} subclass calls the stage builders and the {@code addEncoderStages} /
 * {@code addDecoderStages} chains that {@link OobleckEncoder} and {@link OobleckDecoder} call with
 * their full-size tables. The configurations are scaled down but exercise every stage: every
 * stage changes the channel count, the stages run at strides 2, 4 and 8 (kernels padded by 0, 1
 * and 3, and for the transposed convolutions output padding of 1, 3 and 7), each chain hands the
 * output of one stage to a second, and every residual unit runs over a signal longer than its
 * kernel-7 convolution.</p>
 *
 * <p>The codec declares no {@code state}, so no value persists across forward passes and there is
 * no multi-pass behavior to pin.</p>
 */
public class OobleckCodecStagesPdslTest extends TestSuiteBase {

	/**
	 * Absolute tolerance between the asset and the equivalent Java assembly. Both run the same
	 * single-precision layers, so they agree to rounding (exactly, in every run observed).
	 */
	private static final double PARITY_TOLERANCE = 1e-6;

	/**
	 * Absolute tolerance between a single-precision stage and the double-precision host reference.
	 * The largest deviation observed over the single stages is 1.6e-6.
	 */
	private static final double REFERENCE_TOLERANCE = 5e-5;

	/**
	 * Absolute tolerance between a single-precision two-stage chain and the double-precision host
	 * reference. The rounding error of single precision grows with the six residual units and five
	 * convolutions a chain runs through; the largest deviation observed is 3.3e-5.
	 */
	private static final double CHAIN_REFERENCE_TOLERANCE = 3e-4;

	/** Batch size, as the codec is used for inference. */
	private static final int BATCH = 1;

	/** Number of residual units in a stage. */
	private static final int RESIDUAL_UNITS = 3;

	/** Kernel size of the residual unit's first convolution. */
	private static final int UNIT_KERNEL = 7;

	/** Kernel size of the input projections and of the decoder's output projection. */
	private static final int PROJECTION_KERNEL = 7;

	/** Kernel size of the encoder's output projection. */
	private static final int ENCODER_OUTPUT_KERNEL = 3;

	/** Width of the narrow side of the single stages: the encoder stage's input and the decoder stage's output. */
	private static final int NARROW = 4;

	/** Width of the wide side of the single stages: the encoder stage's output and the decoder stage's input. */
	private static final int WIDE = 6;

	/** Length of the single encoder stage's input. */
	private static final int ENCODER_LENGTH = 16;

	/** Downsampling stride of the single encoder stage. */
	private static final int ENCODER_STRIDE = 4;

	/** Length of the single decoder stage's input. */
	private static final int DECODER_LENGTH = 3;

	/** Upsampling stride of the single decoder stage. */
	private static final int DECODER_STRIDE = 8;

	/** Weight-key prefix of the single stages. */
	private static final String STAGE = "codec.layers.1";

	/** The scaled-down encoder chain: 2 audio channels of 32 samples to a 3-channel latent. */
	private static final Half ENCODER = new Half("encoder", 2, 32, 4, new int[] {6, 8}, new int[] {4, 2}, 3);

	/** Length of the encoder chain's output: 32 samples downsampled by 4 to 8, then by 2 to 4. */
	private static final int ENCODER_FRAMES = 4;

	/** The scaled-down decoder chain: a 3-channel latent of 2 frames to 2 audio channels. */
	private static final Half DECODER = new Half("decoder", 3, 2, 8, new int[] {6, 4}, new int[] {2, 4}, 2);

	/** Length of the decoder chain's output: 2 frames upsampled to 2 * 2 + 1 = 5, then to 5 * 4 + 1. */
	private static final int DECODER_SAMPLES = 21;

	/**
	 * The encoder stage the asset builds reproduces, element for element, the Java assembly it
	 * replaced: three residual units, a Snake activation and a strided weight-normalized Conv1d.
	 */
	@Test(timeout = 300000)
	public void encoderBlockMatchesJavaAssembly() {
		Codec codec = new Codec(new WeightSet(7L).encoderStage(STAGE, NARROW, WIDE, ENCODER_STRIDE).build(),
				shape(BATCH, NARROW, ENCODER_LENGTH));
		PackedCollection input = uniform(-1.0, 1.0, new Random(101L), BATCH, NARROW, ENCODER_LENGTH);

		double[] assembly = forward(codec.javaEncoderBlock(NARROW, WIDE, ENCODER_LENGTH, ENCODER_STRIDE, STAGE),
				input).toArray();
		assertMatches("encoder stage: asset vs Java assembly", assembly,
				forward(codec.buildEncoderBlock(shape(BATCH, NARROW, ENCODER_LENGTH), WIDE, ENCODER_STRIDE, STAGE),
						input), PARITY_TOLERANCE);
	}

	/**
	 * The encoder stage the asset builds reproduces a plain double-precision evaluation of its
	 * arithmetic, and divides the length by the stride.
	 */
	@Test(timeout = 300000)
	public void encoderBlockMatchesHostReference() {
		StateDictionary weights = new WeightSet(13L).encoderStage(STAGE, NARROW, WIDE, ENCODER_STRIDE).build();
		PackedCollection input = uniform(-1.0, 1.0, new Random(202L), BATCH, NARROW, ENCODER_LENGTH);
		Block stage = new Codec(weights, shape(BATCH, NARROW, ENCODER_LENGTH))
				.buildEncoderBlock(shape(BATCH, NARROW, ENCODER_LENGTH), WIDE, ENCODER_STRIDE, STAGE);

		Assert.assertArrayEquals("encoder stage output shape",
				new int[] {BATCH, WIDE, ENCODER_LENGTH / ENCODER_STRIDE}, stage.getOutputShape().extent());

		double[] expected = new HostCodec(weights).encoderStage(STAGE, input.toArray(),
				NARROW, ENCODER_LENGTH, WIDE, ENCODER_STRIDE);
		assertMatches("encoder stage: asset vs host reference", expected, forward(stage, input), REFERENCE_TOLERANCE);
	}

	/**
	 * The decoder stage the asset builds reproduces, element for element, the Java assembly it
	 * replaced: a Snake activation, a weight-normalized ConvTranspose1d and three residual units.
	 */
	@Test(timeout = 300000)
	public void decoderBlockMatchesJavaAssembly() {
		Codec codec = new Codec(new WeightSet(17L).decoderStage(STAGE, WIDE, NARROW, DECODER_STRIDE).build(),
				shape(BATCH, WIDE, DECODER_LENGTH));
		PackedCollection input = uniform(-1.0, 1.0, new Random(303L), BATCH, WIDE, DECODER_LENGTH);

		double[] assembly = forward(codec.javaDecoderBlock(WIDE, NARROW, DECODER_LENGTH, DECODER_STRIDE, STAGE),
				input).toArray();
		assertMatches("decoder stage: asset vs Java assembly", assembly,
				forward(codec.buildDecoderBlock(shape(BATCH, WIDE, DECODER_LENGTH), NARROW, DECODER_STRIDE, STAGE),
						input), PARITY_TOLERANCE);
	}

	/**
	 * The decoder stage the asset builds reproduces a plain double-precision evaluation of its
	 * arithmetic, and lengthens the signal to {@code length * stride + 1}.
	 */
	@Test(timeout = 300000)
	public void decoderBlockMatchesHostReference() {
		StateDictionary weights = new WeightSet(19L).decoderStage(STAGE, WIDE, NARROW, DECODER_STRIDE).build();
		PackedCollection input = uniform(-1.0, 1.0, new Random(404L), BATCH, WIDE, DECODER_LENGTH);
		Block stage = new Codec(weights, shape(BATCH, WIDE, DECODER_LENGTH))
				.buildDecoderBlock(shape(BATCH, WIDE, DECODER_LENGTH), NARROW, DECODER_STRIDE, STAGE);

		int outLength = DECODER_LENGTH * DECODER_STRIDE + 1;
		Assert.assertArrayEquals("decoder stage output shape",
				new int[] {BATCH, NARROW, outLength}, stage.getOutputShape().extent());

		double[] expected = new HostCodec(weights).decoderStage(STAGE, input.toArray(),
				WIDE, DECODER_LENGTH, NARROW, DECODER_STRIDE);
		assertMatches("decoder stage: asset vs host reference", expected, forward(stage, input), REFERENCE_TOLERANCE);
	}

	/**
	 * A two-stage encoder chained by {@code addEncoderStages} — input projection, two stages that
	 * hand one's output to the next, output projection — reproduces the Java assembly of
	 * {@code OobleckEncoder.buildEncoder} over the same stages, and a host evaluation of the chain.
	 */
	@Test(timeout = 300000)
	public void encoderMatchesJavaAssemblyAndHostReference() {
		StateDictionary weights = new WeightSet(23L).encoder(ENCODER).build();
		PackedCollection input = uniform(-1.0, 1.0, new Random(505L), BATCH, ENCODER.inChannels, ENCODER.length);

		Codec codec = new Codec(weights, ENCODER.inputShape());
		double[] assembly = forward(codec.javaEncoder(ENCODER), input).toArray();
		double[] expected = new HostCodec(weights).encoder(ENCODER, input.toArray());

		codec.addEncoderStages(ENCODER.half, ENCODER.width, ENCODER.stageWidths, ENCODER.strides, ENCODER.outChannels);
		Assert.assertArrayEquals("encoder output shape", new int[] {BATCH, ENCODER.outChannels, ENCODER_FRAMES},
				codec.getOutputShape().extent());

		PackedCollection asset = forward(codec, input);
		assertMatches("encoder: asset vs Java assembly", assembly, asset, PARITY_TOLERANCE);
		assertMatches("encoder: asset vs host reference", expected, asset, CHAIN_REFERENCE_TOLERANCE);
	}

	/**
	 * A two-stage decoder chained by {@code addDecoderStages} — input projection, two stages that
	 * hand one's output to the next, bias-free output projection — reproduces the Java assembly of
	 * {@code OobleckDecoder.buildDecoder} over the same stages, and a host evaluation of the chain.
	 */
	@Test(timeout = 300000)
	public void decoderMatchesJavaAssemblyAndHostReference() {
		StateDictionary weights = new WeightSet(29L).decoder(DECODER).build();
		PackedCollection input = uniform(-1.0, 1.0, new Random(606L), BATCH, DECODER.inChannels, DECODER.length);

		Codec codec = new Codec(weights, DECODER.inputShape());
		double[] assembly = forward(codec.javaDecoder(DECODER), input).toArray();
		double[] expected = new HostCodec(weights).decoder(DECODER, input.toArray());

		codec.addDecoderStages(DECODER.half, DECODER.width, DECODER.stageWidths, DECODER.strides, DECODER.outChannels);
		Assert.assertArrayEquals("decoder output shape", new int[] {BATCH, DECODER.outChannels, DECODER_SAMPLES},
				codec.getOutputShape().extent());

		PackedCollection asset = forward(codec, input);
		assertMatches("decoder: asset vs Java assembly", assembly, asset, PARITY_TOLERANCE);
		assertMatches("decoder: asset vs host reference", expected, asset, CHAIN_REFERENCE_TOLERANCE);
	}

	/** Compiles a block on its own inside a {@link Model} and runs one input through it. */
	private PackedCollection forward(Block block, PackedCollection input) {
		Model model = new Model(block.getInputShape());
		model.add(block);
		return model.compile().forward(input);
	}

	/**
	 * Asserts that {@code actual} holds {@code expected}: the same number of elements, and no
	 * element further than {@code tolerance} from its counterpart.
	 */
	private void assertMatches(String label, double[] expected, PackedCollection actual, double tolerance) {
		TraversalPolicy shape = actual.getShape();
		assertEquals(label + " (element count)", expected.length, shape.getTotalSize());
		double deviation = largestDeviation(shape, position -> expected[shape.index(position)], actual);
		log(label + " largestDeviation=" + deviation);
		assertEquals(label, 0.0, deviation, tolerance);
	}

	/** Draws a tensor uniformly in {@code [min, max)} from the seeded source, produced on the device. */
	private PackedCollection uniform(double min, double max, Random random, int... dims) {
		TraversalPolicy shape = shape(dims);
		return rand(shape, random).multiply(max - min).add(min).reshape(shape).evaluate();
	}

	/**
	 * One half of a scaled-down codec: the plan {@code addEncoderStages} or {@code addDecoderStages}
	 * is given, and the checkpoint keys its layers read.
	 */
	private static final class Half {
		/** Weight key prefix of the half. */
		private final String half;

		/** Channel count of the half's input. */
		private final int inChannels;

		/** Length of the half's input. */
		private final int length;

		/** Width of the input projection's output. */
		private final int width;

		/** Width of each stage's output. */
		private final int[] stageWidths;

		/** Stride of each stage. */
		private final int[] strides;

		/** Width of the output projection's output. */
		private final int outChannels;

		/**
		 * Describes a half.
		 *
		 * @param half        weight key prefix of the half
		 * @param inChannels  channel count of the half's input
		 * @param length      length of the half's input
		 * @param width       width of the input projection's output
		 * @param stageWidths width of each stage's output
		 * @param strides     stride of each stage
		 * @param outChannels width of the output projection's output
		 */
		private Half(String half, int inChannels, int length, int width, int[] stageWidths, int[] strides,
					 int outChannels) {
			this.half = half;
			this.inChannels = inChannels;
			this.length = length;
			this.width = width;
			this.stageWidths = stageWidths;
			this.strides = strides;
			this.outChannels = outChannels;
		}

		/** The half's {@code [batch, inChannels, length]} input shape. */
		private TraversalPolicy inputShape() {
			return new TraversalPolicy(BATCH, inChannels, length);
		}

		/** Weight key prefix of the half's layer {@code index}. */
		private String layer(int index) {
			return half + ".layers." + index;
		}

		/** Width of the input of stage {@code stage}: the input projection's output, or the previous stage's. */
		private int stageInput(int stage) {
			return stage == 0 ? width : stageWidths[stage - 1];
		}

		/** Width of the last stage's output, the input of the output projection. */
		private int lastWidth() {
			return stageWidths[stageWidths.length - 1];
		}
	}

	/**
	 * Synthetic weights keyed as the Stable Audio Open checkpoint keys them, drawn in order from one
	 * seeded source. Snake parameters and weight-normalization magnitudes are kept positive so the
	 * activation and the normalization are well defined.
	 */
	private final class WeightSet {
		/** The seeded source every tensor is drawn from, in the order the tensors are added. */
		private final Random random;

		/** The tensors added so far, by checkpoint key. */
		private final Map<String, PackedCollection> weights = new HashMap<>();

		/**
		 * Creates an empty weight set.
		 *
		 * @param seed the seed of the source the tensors are drawn from
		 */
		private WeightSet(long seed) {
			this.random = new Random(seed);
		}

		/** Adds a Snake activation's per-channel {@code alpha} and {@code beta}. */
		private WeightSet snake(String prefix, int channels) {
			weights.put(prefix + ".alpha", uniform(0.5, 1.5, random, channels));
			weights.put(prefix + ".beta", uniform(0.5, 1.5, random, channels));
			return this;
		}

		/**
		 * Adds a weight-normalized convolution from {@code inChannels} to {@code outChannels}: its
		 * {@code [out, 1, 1]} magnitude, its {@code [out, in, kernel]} direction and, when
		 * {@code biased}, its {@code [out]} bias.
		 */
		private WeightSet convolution(String prefix, int outChannels, int inChannels, int kernel, boolean biased) {
			weights.put(prefix + ".weight_g", uniform(0.5, 1.5, random, outChannels, 1, 1));
			weights.put(prefix + ".weight_v", uniform(-0.5, 0.5, random, outChannels, inChannels, kernel));
			if (biased) {
				weights.put(prefix + ".bias", uniform(-0.2, 0.2, random, outChannels));
			}
			return this;
		}

		/**
		 * Adds a weight-normalized transposed convolution from {@code inChannels} to
		 * {@code outChannels}: its {@code [in, 1, 1]} magnitude, its {@code [in, out, kernel]}
		 * direction (the {@code ConvTranspose1d} layout) and its {@code [out]} bias.
		 */
		private WeightSet transposedConvolution(String prefix, int inChannels, int outChannels, int kernel) {
			weights.put(prefix + ".weight_g", uniform(0.5, 1.5, random, inChannels, 1, 1));
			weights.put(prefix + ".weight_v", uniform(-0.5, 0.5, random, inChannels, outChannels, kernel));
			weights.put(prefix + ".bias", uniform(-0.2, 0.2, random, outChannels));
			return this;
		}

		/** Adds a residual unit: Snake, kernel-7 convolution, Snake, pointwise convolution. */
		private WeightSet residualUnit(String prefix, int channels) {
			return snake(prefix + ".layers.0", channels)
					.convolution(prefix + ".layers.1", channels, channels, UNIT_KERNEL, true)
					.snake(prefix + ".layers.2", channels)
					.convolution(prefix + ".layers.3", channels, channels, 1, true);
		}

		/** Adds an encoder stage: three residual units, a Snake and the strided convolution. */
		private WeightSet encoderStage(String prefix, int inChannels, int outChannels, int stride) {
			for (int unit = 0; unit < RESIDUAL_UNITS; unit++) {
				residualUnit(prefix + ".layers." + unit, inChannels);
			}
			return snake(prefix + ".layers.3", inChannels)
					.convolution(prefix + ".layers.4", outChannels, inChannels, stride, true);
		}

		/** Adds a decoder stage: a Snake, the transposed convolution and three residual units. */
		private WeightSet decoderStage(String prefix, int inChannels, int outChannels, int stride) {
			snake(prefix + ".layers.0", inChannels)
					.transposedConvolution(prefix + ".layers.1", inChannels, outChannels, stride);
			for (int unit = 0; unit < RESIDUAL_UNITS; unit++) {
				residualUnit(prefix + ".layers." + (unit + 2), outChannels);
			}
			return this;
		}

		/** Adds an encoder: input projection, the stages and the kernel-3 output projection. */
		private WeightSet encoder(Half plan) {
			convolution(plan.layer(0), plan.width, plan.inChannels, PROJECTION_KERNEL, true);
			for (int stage = 0; stage < plan.strides.length; stage++) {
				encoderStage(plan.layer(stage + 1), plan.stageInput(stage), plan.stageWidths[stage], plan.strides[stage]);
			}
			int output = plan.strides.length + 1;
			return snake(plan.layer(output), plan.lastWidth())
					.convolution(plan.layer(output + 1), plan.outChannels, plan.lastWidth(), ENCODER_OUTPUT_KERNEL, true);
		}

		/** Adds a decoder: input projection, the stages and the bias-free kernel-7 output projection. */
		private WeightSet decoder(Half plan) {
			convolution(plan.layer(0), plan.width, plan.inChannels, PROJECTION_KERNEL, true);
			for (int stage = 0; stage < plan.strides.length; stage++) {
				decoderStage(plan.layer(stage + 1), plan.stageInput(stage), plan.stageWidths[stage], plan.strides[stage]);
			}
			int output = plan.strides.length + 1;
			return snake(plan.layer(output), plan.lastWidth())
					.convolution(plan.layer(output + 1), plan.outChannels, plan.lastWidth(), PROJECTION_KERNEL, false);
		}

		/** Returns the weights added so far. */
		private StateDictionary build() {
			return new StateDictionary(weights);
		}
	}

	/**
	 * The Oobleck codec's arithmetic in plain double precision over a set of checkpoint-keyed
	 * weights, each signal a flattened {@code [channels, length]} array (batch 1). This is the
	 * oracle the asset is pinned against.
	 */
	private static final class HostCodec {
		/** Epsilon added to a weight-normalization norm before division, matching the framework. */
		private static final double WEIGHT_NORM_EPSILON = 1e-12;

		/** The weights, keyed as the checkpoint keys them. */
		private final StateDictionary weights;

		/**
		 * Creates the reference over a weight set.
		 *
		 * @param weights the weights, keyed as the checkpoint keys them
		 */
		private HostCodec(StateDictionary weights) {
			this.weights = weights;
		}

		/** The encoder of {@code plan}: input projection, the stages and the kernel-3 output projection. */
		private double[] encoder(Half plan, double[] x) {
			int length = plan.length;
			double[] y = convolve(plan.layer(0), x, plan.inChannels, length, plan.width, PROJECTION_KERNEL, 1, 3);
			for (int stage = 0; stage < plan.strides.length; stage++) {
				int stride = plan.strides[stage];
				y = encoderStage(plan.layer(stage + 1), y, plan.stageInput(stage), length, plan.stageWidths[stage], stride);
				length = (length + 2 * ((stride - 1) / 2) - stride) / stride + 1;
			}
			int output = plan.strides.length + 1;
			y = snake(plan.layer(output), y, length);
			return convolve(plan.layer(output + 1), y, plan.lastWidth(), length, plan.outChannels,
					ENCODER_OUTPUT_KERNEL, 1, 1);
		}

		/** The decoder of {@code plan}: input projection, the stages and the kernel-7 output projection. */
		private double[] decoder(Half plan, double[] x) {
			int length = plan.length;
			double[] y = convolve(plan.layer(0), x, plan.inChannels, length, plan.width, PROJECTION_KERNEL, 1, 3);
			for (int stage = 0; stage < plan.strides.length; stage++) {
				int stride = plan.strides[stage];
				y = decoderStage(plan.layer(stage + 1), y, plan.stageInput(stage), length, plan.stageWidths[stage], stride);
				length = (length - 1) * stride - 2 * ((stride - 1) / 2) + stride + (stride - 1);
			}
			int output = plan.strides.length + 1;
			y = snake(plan.layer(output), y, length);
			return convolve(plan.layer(output + 1), y, plan.lastWidth(), length, plan.outChannels,
					PROJECTION_KERNEL, 1, 3);
		}

		/**
		 * An encoder stage: three residual units, a Snake activation and a strided convolution
		 * whose kernel is the stride, padded by {@code (stride - 1) / 2}.
		 */
		private double[] encoderStage(String prefix, double[] x, int channels, int length, int outChannels,
									  int stride) {
			double[] y = x;
			for (int unit = 0; unit < RESIDUAL_UNITS; unit++) {
				y = residualUnit(prefix + ".layers." + unit, y, channels, length);
			}
			y = snake(prefix + ".layers.3", y, length);
			return convolve(prefix + ".layers.4", y, channels, length, outChannels, stride, stride, (stride - 1) / 2);
		}

		/**
		 * A decoder stage: a Snake activation, a transposed convolution whose kernel is the stride,
		 * trimmed by {@code (stride - 1) / 2} with {@code stride - 1} samples of output padding, and
		 * three residual units.
		 */
		private double[] decoderStage(String prefix, double[] x, int channels, int length, int outChannels,
									  int stride) {
			int padding = (stride - 1) / 2;
			int outLength = (length - 1) * stride - 2 * padding + stride + (stride - 1);
			double[] y = snake(prefix + ".layers.0", x, length);
			y = convolveTransposed(prefix + ".layers.1", y, channels, length, outChannels, stride, stride, padding,
					stride - 1);
			for (int unit = 0; unit < RESIDUAL_UNITS; unit++) {
				y = residualUnit(prefix + ".layers." + (unit + 2), y, outChannels, outLength);
			}
			return y;
		}

		/**
		 * The residual unit stored under {@code prefix}: {@code x + conv3(snake2(conv1(snake0(x))))},
		 * with the kernel-7 convolution padded by 3 and the pointwise convolution unpadded.
		 */
		private double[] residualUnit(String prefix, double[] x, int channels, int length) {
			double[] y = snake(prefix + ".layers.0", x, length);
			y = convolve(prefix + ".layers.1", y, channels, length, channels, UNIT_KERNEL, 1, 3);
			y = snake(prefix + ".layers.2", y, length);
			y = convolve(prefix + ".layers.3", y, channels, length, channels, 1, 1, 0);
			for (int i = 0; i < y.length; i++) {
				y[i] += x[i];
			}
			return y;
		}

		/** The Snake activation stored under {@code prefix}: {@code x + sin^2(alpha x) / beta} per channel. */
		private double[] snake(String prefix, double[] x, int length) {
			double[] alpha = weights.get(prefix + ".alpha").toArray();
			double[] beta = weights.get(prefix + ".beta").toArray();
			double[] out = new double[x.length];
			for (int i = 0; i < x.length; i++) {
				int c = i / length;
				double s = Math.sin(alpha[c] * x[i]);
				out[i] = x[i] + s * s / beta[c];
			}
			return out;
		}

		/**
		 * The weight-normalized convolution stored under {@code prefix}, as a strided
		 * cross-correlation: {@code out[o, t] = bias[o] + sum over c, k of w[o, c, k] x[c, t * stride
		 * + k - padding]}, with positions outside the signal read as zero and no bias when the
		 * checkpoint stores none.
		 */
		private double[] convolve(String prefix, double[] x, int inChannels, int length, int outChannels,
								  int kernel, int stride, int padding) {
			double[] w = weightNormalized(prefix);
			double[] bias = bias(prefix, outChannels);
			int outLength = (length + 2 * padding - kernel) / stride + 1;
			double[] out = new double[outChannels * outLength];
			for (int o = 0; o < outChannels; o++) {
				for (int t = 0; t < outLength; t++) {
					double sum = bias[o];
					for (int c = 0; c < inChannels; c++) {
						for (int k = 0; k < kernel; k++) {
							int position = t * stride + k - padding;
							if (position >= 0 && position < length) {
								sum += w[(o * inChannels + c) * kernel + k] * x[c * length + position];
							}
						}
					}
					out[o * outLength + t] = sum;
				}
			}
			return out;
		}

		/**
		 * The weight-normalized transposed convolution stored under {@code prefix}, in its scatter
		 * form: every input sample {@code x[c, i]} adds {@code w[c, o, k] x[c, i]} to output position
		 * {@code i * stride + k - padding} of an output
		 * {@code (length - 1) * stride - 2 * padding + kernel + outputPadding} long, positions outside
		 * it being dropped.
		 */
		private double[] convolveTransposed(String prefix, double[] x, int inChannels, int length,
											int outChannels, int kernel, int stride, int padding,
											int outputPadding) {
			double[] w = weightNormalized(prefix);
			double[] bias = bias(prefix, outChannels);
			int outLength = (length - 1) * stride - 2 * padding + kernel + outputPadding;
			double[] out = new double[outChannels * outLength];
			for (int o = 0; o < outChannels; o++) {
				for (int t = 0; t < outLength; t++) {
					out[o * outLength + t] = bias[o];
				}
			}
			for (int c = 0; c < inChannels; c++) {
				for (int i = 0; i < length; i++) {
					for (int o = 0; o < outChannels; o++) {
						for (int k = 0; k < kernel; k++) {
							int t = i * stride + k - padding;
							if (t >= 0 && t < outLength) {
								out[o * outLength + t] += w[(c * outChannels + o) * kernel + k] * x[c * length + i];
							}
						}
					}
				}
			}
			return out;
		}

		/**
		 * The effective weight of the weight-normalized convolution stored under {@code prefix}:
		 * {@code g[c] v[c] / (||v[c]|| + eps)} for every slice {@code c} of the direction's leading
		 * axis, which is the output axis of a convolution and the input axis of a transposed one.
		 */
		private double[] weightNormalized(String prefix) {
			double[] g = weights.get(prefix + ".weight_g").toArray();
			double[] v = weights.get(prefix + ".weight_v").toArray();
			int slice = v.length / g.length;
			double[] w = new double[v.length];
			for (int c = 0; c < g.length; c++) {
				double sumSq = 0.0;
				for (int j = 0; j < slice; j++) {
					sumSq += v[c * slice + j] * v[c * slice + j];
				}
				double scale = g[c] / (Math.sqrt(sumSq) + WEIGHT_NORM_EPSILON);
				for (int j = 0; j < slice; j++) {
					w[c * slice + j] = v[c * slice + j] * scale;
				}
			}
			return w;
		}

		/** The bias stored under {@code prefix}, or zeros when the checkpoint stores none. */
		private double[] bias(String prefix, int channels) {
			PackedCollection bias = weights.get(prefix + ".bias");
			return bias == null ? new double[channels] : bias.toArray();
		}
	}

	/**
	 * Minimal concrete {@link OobleckCodec}: the production stage builders and chains are reached
	 * through it, and it reproduces the Java assembly of the encoder and decoder they replaced.
	 */
	private static final class Codec extends OobleckCodec {
		/**
		 * Creates an empty codec half over the synthetic weights.
		 *
		 * @param weights    the synthetic weights
		 * @param inputShape the shape of the half's input
		 */
		private Codec(StateDictionary weights, TraversalPolicy inputShape) {
			super(inputShape, weights);
		}

		/**
		 * The encoder as {@code OobleckEncoder.buildEncoder} assembled it before the migration, over
		 * the stages of {@code plan}: a kernel-7 input projection, the encoder stages, a Snake
		 * activation and a kernel-3 output projection, with each stage's length worked out from the
		 * last.
		 */
		private Block javaEncoder(Half plan) {
			SequentialBlock encoder = new SequentialBlock(plan.inputShape());
			encoder.add(wnConv1d(BATCH, plan.inChannels, plan.width, plan.length, 7, 1, 3,
					stateDict.get(plan.layer(0) + ".weight_g"), stateDict.get(plan.layer(0) + ".weight_v"),
					stateDict.get(plan.layer(0) + ".bias")));

			int inChannels = plan.width;
			int currentLength = plan.length;
			for (int blockIdx = 0; blockIdx < plan.strides.length; blockIdx++) {
				int outChannels = plan.stageWidths[blockIdx];
				int stride = plan.strides[blockIdx];
				encoder.add(javaEncoderBlock(inChannels, outChannels, currentLength, stride, plan.layer(blockIdx + 1)));

				int kernel = stride;
				int padding = (kernel - 1) / 2;
				currentLength = (currentLength + 2 * padding - kernel) / stride + 1;
				inChannels = outChannels;
			}

			String snakeKey = plan.layer(plan.strides.length + 1);
			String convKey = plan.layer(plan.strides.length + 2);
			encoder.add(snake(shape(BATCH, inChannels, currentLength),
					stateDict.get(snakeKey + ".alpha"), stateDict.get(snakeKey + ".beta")));
			encoder.add(wnConv1d(BATCH, inChannels, plan.outChannels, currentLength, 3, 1, 1,
					stateDict.get(convKey + ".weight_g"), stateDict.get(convKey + ".weight_v"),
					stateDict.get(convKey + ".bias")));
			return encoder;
		}

		/**
		 * The decoder as {@code OobleckDecoder.buildDecoder} assembled it before the migration, over
		 * the stages of {@code plan}: a kernel-7 input projection, the decoder stages, a Snake
		 * activation and a bias-free kernel-7 output projection, with each stage's length worked out
		 * from the last.
		 */
		private Block javaDecoder(Half plan) {
			SequentialBlock decoder = new SequentialBlock(plan.inputShape());
			decoder.add(wnConv1d(BATCH, plan.inChannels, plan.width, plan.length, 7, 1, 3,
					stateDict.get(plan.layer(0) + ".weight_g"), stateDict.get(plan.layer(0) + ".weight_v"),
					stateDict.get(plan.layer(0) + ".bias")));

			int currentLength = plan.length;
			for (int blockIdx = 0; blockIdx < plan.strides.length; blockIdx++) {
				int inChannels = plan.stageInput(blockIdx);
				int outChannels = plan.stageWidths[blockIdx];
				int stride = plan.strides[blockIdx];

				decoder.add(javaDecoderBlock(inChannels, outChannels, currentLength, stride, plan.layer(blockIdx + 1)));

				int kernel = stride;
				int padding = (kernel - 1) / 2;
				int outputPadding = stride - 1;
				currentLength = (currentLength - 1) * stride - 2 * padding + kernel + outputPadding;
			}

			String snakeKey = plan.layer(plan.strides.length + 1);
			String convKey = plan.layer(plan.strides.length + 2);
			decoder.add(snake(shape(BATCH, plan.lastWidth(), currentLength),
					stateDict.get(snakeKey + ".alpha"), stateDict.get(snakeKey + ".beta")));
			decoder.add(wnConv1d(BATCH, plan.lastWidth(), plan.outChannels, currentLength, 7, 1, 3,
					stateDict.get(convKey + ".weight_g"), stateDict.get(convKey + ".weight_v"), null));
			return decoder;
		}

		/**
		 * The encoder stage as {@code OobleckEncoder.buildEncoderBlock} assembled it before the
		 * migration: the three residual units, a Snake activation and a weight-normalized Conv1d
		 * whose kernel is the stride, padded by {@code (kernel - 1) / 2}.
		 */
		private Block javaEncoderBlock(int inChannels, int outChannels, int seqLength, int stride, String prefix) {
			SequentialBlock block = new SequentialBlock(shape(BATCH, inChannels, seqLength));
			for (int resIdx = 0; resIdx < RESIDUAL_UNITS; resIdx++) {
				block.add(buildResidualBlock(BATCH, inChannels, seqLength, prefix + ".layers." + resIdx));
			}

			block.add(snake(shape(BATCH, inChannels, seqLength),
					stateDict.get(prefix + ".layers.3.alpha"), stateDict.get(prefix + ".layers.3.beta")));

			int kernel = stride;
			int padding = (kernel - 1) / 2;
			block.add(wnConv1d(BATCH, inChannels, outChannels, seqLength, kernel, stride, padding,
					stateDict.get(prefix + ".layers.4.weight_g"), stateDict.get(prefix + ".layers.4.weight_v"),
					stateDict.get(prefix + ".layers.4.bias")));
			return block;
		}

		/**
		 * The decoder stage as {@code OobleckDecoder.buildDecoderBlock} assembled it before the
		 * migration: a Snake activation, a weight-normalized ConvTranspose1d whose kernel is the
		 * stride, padded by {@code (kernel - 1) / 2} with {@code stride - 1} samples of output
		 * padding, and the three residual units.
		 */
		private Block javaDecoderBlock(int inChannels, int outChannels, int seqLength, int stride, String prefix) {
			int kernel = stride;
			int padding = (kernel - 1) / 2;
			int outputPadding = stride - 1;
			int outLength = (seqLength - 1) * stride - 2 * padding + kernel + outputPadding;

			SequentialBlock block = new SequentialBlock(shape(BATCH, inChannels, seqLength));
			block.add(snake(shape(BATCH, inChannels, seqLength),
					stateDict.get(prefix + ".layers.0.alpha"), stateDict.get(prefix + ".layers.0.beta")));
			block.add(wnConvTranspose1d(BATCH, inChannels, outChannels, seqLength, kernel, stride, padding,
					outputPadding, stateDict.get(prefix + ".layers.1.weight_g"),
					stateDict.get(prefix + ".layers.1.weight_v"), stateDict.get(prefix + ".layers.1.bias")));

			for (int resIdx = 0; resIdx < RESIDUAL_UNITS; resIdx++) {
				block.add(buildResidualBlock(BATCH, outChannels, outLength, prefix + ".layers." + (resIdx + 2)));
			}
			return block;
		}
	}
}
