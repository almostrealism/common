/*
 * Copyright 2025 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.ml.audio;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.ml.dsl.PdslLoader;
import org.almostrealism.model.Block;
import org.almostrealism.model.SequentialBlock;

import java.util.HashMap;
import java.util.Map;

/**
 * Shared base for the two halves of the Oobleck (Stable Audio Open) autoencoder,
 * {@link OobleckEncoder} and {@link OobleckDecoder}.
 *
 * <p>Both halves are chains of the same kinds of stage — an input projection, a run of
 * down- or up-sampling stages built around a residual unit, and an output projection — and
 * the structure of every stage lives in two PDSL assets: {@value #RESIDUAL_ASSET} describes the
 * residual unit (two Snake+Conv1d pairs with a skip connection) and {@value #CODEC_ASSET} the
 * stages that call it. The stage builders here read one stage's weights from the
 * {@link StateDictionary}, keyed in the Stable Audio Open checkpoint format, resolve each
 * weight-normalized convolution's {@code g} and {@code v} parameters into its effective weight,
 * bind them and build the stage's layer. {@link #addEncoderStages} and {@link #addDecoderStages}
 * chain a whole half, and the subclasses only supply their stages' widths and strides, so the
 * encoder and decoder share one definition of every stage and it reads as data flow rather than
 * Java assembly.</p>
 *
 * <p>A half's layers are keyed as the checkpoint keys them: the input projection is
 * {@code <half>.layers.0}, stage {@code i} is {@code <half>.layers.<i + 1>}, and the output
 * projection's Snake activation and convolution take the two indices after the last stage (with
 * five stages, {@code <half>.layers.6} and {@code <half>.layers.7}). Every layer is built for the
 * output shape of the one before it, so the lengths a half's strides produce are those the asset's
 * convolutions compute, with no second formula for them here.</p>
 *
 * @see OobleckEncoder
 * @see OobleckDecoder
 * @see OobleckAutoEncoder
 */
public abstract class OobleckCodec extends SequentialBlock {

	/** Classpath location of the asset describing the residual-unit structure. */
	protected static final String RESIDUAL_ASSET = "/pdsl/audio/oobleck_residual_block.pdsl";

	/** Classpath location of the asset describing the codec stages, which call the residual unit. */
	protected static final String CODEC_ASSET = "/pdsl/audio/oobleck_codec.pdsl";

	/** Kernel size of the first convolution in each residual block (length-preserving with padding 3). */
	private static final int CONV1_KERNEL = 7;

	/** Kernel size of the second, pointwise convolution in each residual block. */
	private static final int CONV3_KERNEL = 1;

	/** Kernel size of the input projections and of the decoder's output projection. */
	private static final int PROJECTION_KERNEL = 7;

	/** Kernel size of the encoder's output projection. */
	private static final int ENCODER_OUTPUT_KERNEL = 3;

	/** Number of residual units in every encoder and decoder stage. */
	private static final int RESIDUAL_UNITS = 3;

	/** Weights loaded from the Stable Audio Open checkpoint format. */
	protected final StateDictionary stateDict;

	/**
	 * Creates a codec half with the given input shape and weights.
	 *
	 * @param inputShape Shape of the input this half consumes
	 * @param stateDict  StateDictionary containing this half's weights, keyed in
	 *                   the Stable Audio Open checkpoint format
	 */
	protected OobleckCodec(TraversalPolicy inputShape, StateDictionary stateDict) {
		super(inputShape);
		this.stateDict = stateDict;
	}

	/**
	 * Builds one residual unit on its own from the {@value #RESIDUAL_ASSET} asset: two
	 * Snake+Conv1d pairs whose output is added to the unit's input (skip connection). The encoder
	 * and decoder stages do not come through here: the stage layers of {@value #CODEC_ASSET} call
	 * the same {@code oobleck_residual_block} layer directly, so a standalone unit and the units
	 * inside every stage share one definition.
	 *
	 * <p>The structure is not assembled here; it is in the asset. This method resolves the block's
	 * weights — including each convolution's weight-normalized {@code g} and {@code v} parameters
	 * into its effective weight — binds them, and loads the asset's {@code oobleck_residual_block}
	 * layer for the {@code [batch, channels, length]} input shape.</p>
	 *
	 * @param batchSize Batch size
	 * @param channels  Number of channels (constant throughout)
	 * @param seqLength Sequence length (constant throughout)
	 * @param prefix    Weight key prefix (e.g., {@code encoder.layers.1.layers.0}
	 *                  or {@code decoder.layers.1.layers.2})
	 * @return Assembled residual block
	 */
	protected Block buildResidualBlock(int batchSize, int channels, int seqLength, String prefix) {
		PdslLoader loader = new PdslLoader();
		return loader.buildLayer(loader.parseResource(RESIDUAL_ASSET), "oobleck_residual_block",
				shape(batchSize, channels, seqLength), residualBlockArguments(channels, prefix));
	}

	/**
	 * Binds the arguments the {@code oobleck_residual_block} layer of {@value #RESIDUAL_ASSET}
	 * takes: the per-channel Snake parameters of each activation and the effective (weight-
	 * normalized) weight and bias of each convolution. Weight normalization is resolved here, at
	 * bind time, rather than in the asset: each convolution's stored {@code weight_g} and
	 * {@code weight_v} parameters combine into the one weight {@code conv1d} uses, exactly as any
	 * other stored weight is reshaped or sliced before it is bound.
	 *
	 * @param channels Number of channels (the convolutions map channels to channels)
	 * @param prefix   Weight key prefix within {@link #stateDict}
	 * @return the argument bindings for the {@code oobleck_residual_block} layer
	 */
	protected Map<String, Object> residualBlockArguments(int channels, String prefix) {
		Map<String, Object> args = new HashMap<>();
		putSnake(args, "snake0", prefix + ".layers.0");
		putConvolution(args, "conv1", prefix + ".layers.1", channels, channels, CONV1_KERNEL);
		putSnake(args, "snake2", prefix + ".layers.2");
		putConvolution(args, "conv3", prefix + ".layers.3", channels, channels, CONV3_KERNEL);
		return args;
	}

	/**
	 * Chains an encoder onto this block: the input projection to {@code width} channels, one
	 * encoder stage per stride, each downsampling by its stride and widening to its entry of
	 * {@code stageWidths}, and the encoder's output projection to {@code latentWidth} channels,
	 * each layer keyed and shaped as the class documentation describes.
	 *
	 * @param half        weight key prefix of the encoder (e.g. {@code encoder})
	 * @param width       the input projection's width
	 * @param stageWidths the width of each stage's output
	 * @param strides     the downsampling stride of each stage
	 * @param latentWidth the output projection's width
	 */
	protected void addEncoderStages(String half, int width, int[] stageWidths, int[] strides, int latentWidth) {
		addHalf(half, width, stageWidths, strides, this::buildEncoderBlock, latentWidth, this::buildEncoderOutput);
	}

	/**
	 * Chains a decoder onto this block: the input projection to {@code width} channels, one
	 * decoder stage per stride, each upsampling by its stride and narrowing to its entry of
	 * {@code stageWidths}, and the decoder's output projection to {@code audioChannels} channels,
	 * each layer keyed and shaped as the class documentation describes.
	 *
	 * @param half          weight key prefix of the decoder (e.g. {@code decoder})
	 * @param width         the input projection's width
	 * @param stageWidths   the width of each stage's output
	 * @param strides       the upsampling stride of each stage
	 * @param audioChannels the output projection's width
	 */
	protected void addDecoderStages(String half, int width, int[] stageWidths, int[] strides, int audioChannels) {
		addHalf(half, width, stageWidths, strides, this::buildDecoderBlock, audioChannels, this::buildDecoderOutput);
	}

	/**
	 * Chains one half of the codec onto this block, each layer built for the output of the one
	 * before it: the input projection ({@code <half>.layers.0}), one stage per stride
	 * ({@code <half>.layers.1} onwards) and the output projection, whose Snake activation and
	 * convolution are keyed by the two layer indices after the last stage.
	 *
	 * @param half        weight key prefix of the half
	 * @param width       the input projection's width
	 * @param stageWidths the width of each stage's output
	 * @param strides     the stride of each stage
	 * @param stage       builds one stage
	 * @param outputWidth the output projection's width
	 * @param output      builds the output projection
	 */
	private void addHalf(String half, int width, int[] stageWidths, int[] strides, StageBuilder stage,
						 int outputWidth, ProjectionBuilder output) {
		add(buildInputProjection(getOutputShape(), width, half + ".layers.0"));
		for (int i = 0; i < strides.length; i++) {
			add(stage.build(getOutputShape(), stageWidths[i], strides[i], half + ".layers." + (i + 1)));
		}

		int next = strides.length + 1;
		add(output.build(getOutputShape(), outputWidth, half + ".layers." + next, half + ".layers." + (next + 1)));
	}

	/**
	 * Builds the input projection of either half from the {@code oobleck_input_projection} layer
	 * of {@value #CODEC_ASSET}: a length-preserving kernel-7 convolution from the input's channels
	 * to {@code outChannels}.
	 *
	 * @param inputShape  the {@code [batch, channels, length]} input shape
	 * @param outChannels the width of the first stage
	 * @param prefix      weight key prefix of the convolution (e.g. {@code encoder.layers.0})
	 * @return the input projection
	 */
	protected Block buildInputProjection(TraversalPolicy inputShape, int outChannels, String prefix) {
		Map<String, Object> args = new HashMap<>();
		putConvolution(args, "conv", prefix, outChannels, inputShape.length(1), PROJECTION_KERNEL);
		return buildCodecLayer("oobleck_input_projection", inputShape, args);
	}

	/**
	 * Builds one encoder stage from the {@code oobleck_encoder_block} layer of {@value #CODEC_ASSET}:
	 * three residual units at the input width, then a Snake activation and a strided convolution
	 * whose kernel spans one stride, which downsample the signal by {@code stride} and widen it to
	 * {@code outChannels}. The checkpoint keys the three residual units {@code layers.0} to
	 * {@code layers.2} of the stage, the Snake activation {@code layers.3} and the convolution
	 * {@code layers.4}.
	 *
	 * @param inputShape  the stage's {@code [batch, channels, length]} input shape
	 * @param outChannels the width the stage widens the signal to
	 * @param stride      the downsampling factor, which is also the convolution's kernel size
	 * @param prefix      weight key prefix of the stage (e.g. {@code encoder.layers.1})
	 * @return the encoder stage
	 */
	protected Block buildEncoderBlock(TraversalPolicy inputShape, int outChannels, int stride, String prefix) {
		int channels = inputShape.length(1);
		Map<String, Object> args = residualUnitArguments(channels, prefix, 0);
		putSnake(args, "snake", prefix + ".layers.3");
		putConvolution(args, "down", prefix + ".layers.4", outChannels, channels, stride);
		args.put("stride", stride);
		return buildCodecLayer("oobleck_encoder_block", inputShape, args);
	}

	/**
	 * Builds the encoder's output from the {@code oobleck_encoder_output} layer of
	 * {@value #CODEC_ASSET}: a Snake activation, then a length-preserving kernel-3 convolution down
	 * to the latent width.
	 *
	 * @param inputShape  the {@code [batch, channels, length]} input shape
	 * @param outChannels the latent width
	 * @param snakePrefix weight key prefix of the Snake activation (e.g. {@code encoder.layers.6})
	 * @param convPrefix  weight key prefix of the convolution (e.g. {@code encoder.layers.7})
	 * @return the encoder's output projection
	 */
	protected Block buildEncoderOutput(TraversalPolicy inputShape, int outChannels,
									   String snakePrefix, String convPrefix) {
		Map<String, Object> args = new HashMap<>();
		putSnake(args, "snake", snakePrefix);
		putConvolution(args, "conv", convPrefix, outChannels, inputShape.length(1), ENCODER_OUTPUT_KERNEL);
		return buildCodecLayer("oobleck_encoder_output", inputShape, args);
	}

	/**
	 * Builds one decoder stage from the {@code oobleck_decoder_block} layer of {@value #CODEC_ASSET}:
	 * a Snake activation and a transposed convolution whose kernel spans one stride, which upsample
	 * the signal by {@code stride} and narrow it to {@code outChannels}, then three residual units at
	 * the output width. The checkpoint keys the Snake activation {@code layers.0} of the stage, the
	 * transposed convolution {@code layers.1} and the three residual units {@code layers.2} to
	 * {@code layers.4}.
	 *
	 * @param inputShape  the stage's {@code [batch, channels, length]} input shape
	 * @param outChannels the width the stage narrows the signal to
	 * @param stride      the upsampling factor, which is also the transposed convolution's kernel size
	 * @param prefix      weight key prefix of the stage (e.g. {@code decoder.layers.1})
	 * @return the decoder stage
	 */
	protected Block buildDecoderBlock(TraversalPolicy inputShape, int outChannels, int stride, String prefix) {
		int channels = inputShape.length(1);
		Map<String, Object> args = residualUnitArguments(outChannels, prefix, 2);
		putSnake(args, "snake", prefix + ".layers.0");
		String upPrefix = prefix + ".layers.1";
		args.put("up_weight", computeWeightNormWeightsTransposed(stateDict.get(upPrefix + ".weight_g"),
				stateDict.get(upPrefix + ".weight_v"), channels, outChannels, stride));
		args.put("up_bias", stateDict.get(upPrefix + ".bias"));
		args.put("stride", stride);
		return buildCodecLayer("oobleck_decoder_block", inputShape, args);
	}

	/**
	 * Builds the decoder's output from the {@code oobleck_decoder_output} layer of
	 * {@value #CODEC_ASSET}: a Snake activation, then a length-preserving kernel-7 convolution, which
	 * has no bias, down to the audio channels.
	 *
	 * @param inputShape  the {@code [batch, channels, length]} input shape
	 * @param outChannels the number of audio channels
	 * @param snakePrefix weight key prefix of the Snake activation (e.g. {@code decoder.layers.6})
	 * @param convPrefix  weight key prefix of the convolution (e.g. {@code decoder.layers.7})
	 * @return the decoder's output projection
	 */
	protected Block buildDecoderOutput(TraversalPolicy inputShape, int outChannels,
									   String snakePrefix, String convPrefix) {
		Map<String, Object> args = new HashMap<>();
		putSnake(args, "snake", snakePrefix);
		args.put("conv_weight", weightNormalized(convPrefix, outChannels, inputShape.length(1), PROJECTION_KERNEL));
		return buildCodecLayer("oobleck_decoder_output", inputShape, args);
	}

	/**
	 * Builds a layer of {@value #CODEC_ASSET}, parsed together with {@value #RESIDUAL_ASSET} into one
	 * program so that the stages can call the residual unit. {@link PdslLoader#parseResource} caches
	 * each parsed asset, so building every stage this way parses each asset only once.
	 *
	 * @param layer      the layer name
	 * @param inputShape the layer's input shape
	 * @param args       the layer's argument bindings
	 * @return the layer
	 */
	private Block buildCodecLayer(String layer, TraversalPolicy inputShape, Map<String, Object> args) {
		PdslLoader loader = new PdslLoader();
		return loader.buildLayer(loader.parseResources(RESIDUAL_ASSET, CODEC_ASSET), layer, inputShape, args);
	}

	/**
	 * Binds the three residual units of a stage, each under the parameter names of
	 * {@link #residualBlockArguments} prefixed with {@code unit0_}, {@code unit1_} or {@code unit2_}.
	 *
	 * @param channels    the residual units' width
	 * @param stagePrefix weight key prefix of the stage
	 * @param firstLayer  index, within the stage, of the first residual unit's checkpoint layer
	 * @return the argument bindings of the three residual units
	 */
	private Map<String, Object> residualUnitArguments(int channels, String stagePrefix, int firstLayer) {
		Map<String, Object> args = new HashMap<>();
		for (int unit = 0; unit < RESIDUAL_UNITS; unit++) {
			String unitName = "unit" + unit + "_";
			residualBlockArguments(channels, stagePrefix + ".layers." + (firstLayer + unit))
					.forEach((name, value) -> args.put(unitName + name, value));
		}
		return args;
	}

	/**
	 * Binds a Snake activation's per-channel parameters as {@code <name>_alpha} and
	 * {@code <name>_beta}.
	 *
	 * @param args   the bindings to add to
	 * @param name   the parameter name prefix
	 * @param prefix weight key prefix of the activation
	 */
	private void putSnake(Map<String, Object> args, String name, String prefix) {
		args.put(name + "_alpha", stateDict.get(prefix + ".alpha"));
		args.put(name + "_beta", stateDict.get(prefix + ".beta"));
	}

	/**
	 * Binds a weight-normalized convolution's effective weight and its bias as
	 * {@code <name>_weight} and {@code <name>_bias}.
	 *
	 * @param args        the bindings to add to
	 * @param name        the parameter name prefix
	 * @param prefix      weight key prefix of the convolution
	 * @param outChannels the convolution's output channels
	 * @param inChannels  the convolution's input channels
	 * @param kernel      the convolution's kernel size
	 */
	private void putConvolution(Map<String, Object> args, String name, String prefix,
								int outChannels, int inChannels, int kernel) {
		args.put(name + "_weight", weightNormalized(prefix, outChannels, inChannels, kernel));
		args.put(name + "_bias", stateDict.get(prefix + ".bias"));
	}

	/**
	 * Resolves a weight-normalized convolution's stored {@code weight_g} and {@code weight_v}
	 * parameters into its effective {@code [outChannels, inChannels, kernel]} weight.
	 *
	 * @param prefix      weight key prefix of the convolution
	 * @param outChannels the convolution's output channels
	 * @param inChannels  the convolution's input channels
	 * @param kernel      the convolution's kernel size
	 * @return the effective weight
	 */
	private PackedCollection weightNormalized(String prefix, int outChannels, int inChannels, int kernel) {
		return computeWeightNormWeights(stateDict.get(prefix + ".weight_g"), stateDict.get(prefix + ".weight_v"),
				outChannels, inChannels, kernel);
	}

	/** Builds one down- or up-sampling stage: {@link #buildEncoderBlock} or {@link #buildDecoderBlock}. */
	@FunctionalInterface
	private interface StageBuilder {
		/**
		 * Builds the stage.
		 *
		 * @param inputShape the stage's {@code [batch, channels, length]} input shape
		 * @param width      the width of the stage's output
		 * @param stride     the stage's stride
		 * @param prefix     weight key prefix of the stage
		 * @return the stage
		 */
		Block build(TraversalPolicy inputShape, int width, int stride, String prefix);
	}

	/** Builds an output projection: {@link #buildEncoderOutput} or {@link #buildDecoderOutput}. */
	@FunctionalInterface
	private interface ProjectionBuilder {
		/**
		 * Builds the output projection.
		 *
		 * @param inputShape  the projection's {@code [batch, channels, length]} input shape
		 * @param width       the width of the projection's output
		 * @param snakePrefix weight key prefix of the Snake activation
		 * @param convPrefix  weight key prefix of the convolution
		 * @return the output projection
		 */
		Block build(TraversalPolicy inputShape, int width, String snakePrefix, String convPrefix);
	}
}
