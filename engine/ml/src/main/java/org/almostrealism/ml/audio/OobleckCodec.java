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
 * <p>Both halves load their weights from a {@link StateDictionary} keyed in the
 * Stable Audio Open checkpoint format, and both embed the same residual block —
 * two Snake+Conv1d pairs with a skip connection — within each of their stages.
 * The structure of that residual block lives in the {@value #RESIDUAL_ASSET} PDSL asset;
 * {@link #buildResidualBlock} resolves the block's weights and loads it, so the encoder and
 * decoder share a single definition and it reads as data flow rather than Java assembly.</p>
 *
 * @see OobleckEncoder
 * @see OobleckDecoder
 * @see OobleckAutoEncoder
 */
public abstract class OobleckCodec extends SequentialBlock {

	/** Classpath location of the asset describing the residual-unit structure. */
	protected static final String RESIDUAL_ASSET = "/pdsl/audio/oobleck_residual_block.pdsl";

	/** Kernel size of the first convolution in each residual block (length-preserving with padding 3). */
	private static final int CONV1_KERNEL = 7;

	/** Kernel size of the second, pointwise convolution in each residual block. */
	private static final int CONV3_KERNEL = 1;

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
	 * Builds one residual block from the {@value #RESIDUAL_ASSET} asset: two Snake+Conv1d pairs
	 * whose output is added to the block input (skip connection). Shared by the encoder and
	 * decoder, which embed this block within each of their stages.
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
		args.put("snake0_alpha", stateDict.get(prefix + ".layers.0.alpha"));
		args.put("snake0_beta", stateDict.get(prefix + ".layers.0.beta"));
		args.put("conv1_weight", computeWeightNormWeights(
				stateDict.get(prefix + ".layers.1.weight_g"),
				stateDict.get(prefix + ".layers.1.weight_v"),
				channels, channels, CONV1_KERNEL));
		args.put("conv1_bias", stateDict.get(prefix + ".layers.1.bias"));
		args.put("snake2_alpha", stateDict.get(prefix + ".layers.2.alpha"));
		args.put("snake2_beta", stateDict.get(prefix + ".layers.2.beta"));
		args.put("conv3_weight", computeWeightNormWeights(
				stateDict.get(prefix + ".layers.3.weight_g"),
				stateDict.get(prefix + ".layers.3.weight_v"),
				channels, channels, CONV3_KERNEL));
		args.put("conv3_bias", stateDict.get(prefix + ".layers.3.bias"));
		return args;
	}
}
