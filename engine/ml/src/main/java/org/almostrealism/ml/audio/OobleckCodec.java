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
import org.almostrealism.model.Block;
import org.almostrealism.model.SequentialBlock;

/**
 * Shared base for the two halves of the Oobleck (Stable Audio Open) autoencoder,
 * {@link OobleckEncoder} and {@link OobleckDecoder}.
 *
 * <p>Both halves load their weights from a {@link StateDictionary} keyed in the
 * Stable Audio Open checkpoint format, and both embed the same residual block —
 * two Snake+Conv1d pairs with a skip connection — within each of their stages.
 * That residual block is assembled here, in {@link #buildResidualBlock}, so the
 * encoder and decoder share a single implementation.</p>
 *
 * @see OobleckEncoder
 * @see OobleckDecoder
 * @see OobleckAutoEncoder
 */
public abstract class OobleckCodec extends SequentialBlock {

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
	 * Builds one residual block: two Snake+Conv1d pairs whose output is added to
	 * the block input (skip connection). Shared by the encoder and decoder, which
	 * embed this block within each of their stages.
	 *
	 * @param batchSize Batch size
	 * @param channels  Number of channels (constant throughout)
	 * @param seqLength Sequence length (constant throughout)
	 * @param prefix    Weight key prefix (e.g., {@code encoder.layers.1.layers.0}
	 *                  or {@code decoder.layers.1.layers.2})
	 * @return Assembled residual block
	 */
	protected Block buildResidualBlock(int batchSize, int channels, int seqLength, String prefix) {
		TraversalPolicy inputShape = shape(batchSize, channels, seqLength);
		SequentialBlock mainPath = new SequentialBlock(inputShape);

		PackedCollection snake0_alpha = stateDict.get(prefix + ".layers.0.alpha");
		PackedCollection snake0_beta = stateDict.get(prefix + ".layers.0.beta");
		mainPath.add(snake(inputShape, snake0_alpha, snake0_beta));

		PackedCollection conv1_g = stateDict.get(prefix + ".layers.1.weight_g");
		PackedCollection conv1_v = stateDict.get(prefix + ".layers.1.weight_v");
		PackedCollection conv1_b = stateDict.get(prefix + ".layers.1.bias");
		mainPath.add(wnConv1d(batchSize, channels, channels, seqLength, 7, 1, 3,
				conv1_g, conv1_v, conv1_b));

		PackedCollection snake2_alpha = stateDict.get(prefix + ".layers.2.alpha");
		PackedCollection snake2_beta = stateDict.get(prefix + ".layers.2.beta");
		mainPath.add(snake(inputShape, snake2_alpha, snake2_beta));

		PackedCollection conv3_g = stateDict.get(prefix + ".layers.3.weight_g");
		PackedCollection conv3_v = stateDict.get(prefix + ".layers.3.weight_v");
		PackedCollection conv3_b = stateDict.get(prefix + ".layers.3.bias");
		mainPath.add(wnConv1d(batchSize, channels, channels, seqLength, 1, 1, 0,
				conv3_g, conv3_v, conv3_b));

		return residual(mainPath);
	}
}
