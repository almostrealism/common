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

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.ml.DiffusionFeatures;
import org.almostrealism.ml.LearnedTokenFeatures;
import org.almostrealism.model.Block;
import org.almostrealism.model.SequentialBlock;

import java.util.List;

/**
 * Mixin interface providing Fourier feature and timestep embedding building blocks
 * for diffusion transformer models.
 *
 * <p>Implementations of this interface can construct the conditioning inputs used
 * by {@link DiffusionTransformer}: Fourier projections for continuous timesteps
 * and a two-layer MLP that maps Fourier features to the transformer embedding dimension.</p>
 *
 * <p>Like all {@code Features} interfaces, this is a mixin: a type that needs these
 * operations should <em>implement</em> this interface (the methods are stateless
 * {@code default} methods) rather than accept or hold a {@code Features} instance —
 * passing one around as an object defeats the purpose of the pattern.</p>
 *
 * @see DiffusionTransformer
 * @see org.almostrealism.ml.AttentionFeatures
 */
public interface DiffusionTransformerFeatures extends AttentionFeatures, DiffusionFeatures, LearnedTokenFeatures {

	/**
	 * Builds a Fourier feature projection layer.
	 * <p>
	 * Projects input values through learned weights and applies cosine and sine transforms,
	 * producing {@code outFeatures / 2} cosine components followed by {@code outFeatures / 2}
	 * sine components concatenated into a single output tensor.
	 * </p>
	 *
	 * @param batchSize      batch dimension
	 * @param inFeatures     number of input features (typically 1 for scalar timestep)
	 * @param outFeatures    number of output features; must be even for sin/cos pairs
	 * @param learnedWeights the projection weight matrix of shape {@code [outFeatures/2, inFeatures]}
	 * @return a block that performs the Fourier feature projection
	 */
	default Block fourierFeatures(int batchSize, int inFeatures, int outFeatures, PackedCollection learnedWeights) {
		// Output dim should be even for sin/cos pairs
		if (outFeatures % 2 != 0) {
			throw new IllegalArgumentException("Output features must be even for Fourier features");
		}

		return layer("fourierFeatures",
				shape(batchSize, inFeatures),
				shape(batchSize, outFeatures),
				in -> fourierFeatures(batchSize, outFeatures, in, learnedWeights),
				List.of(learnedWeights));
	}

	/**
	 * Builds the Fourier feature projection as a {@link CollectionProducer}, the producer-level
	 * counterpart of {@link #fourierFeatures(int, int, int, PackedCollection)}.
	 * <p>
	 * Projects {@code input} through {@code learnedWeights}, scales by {@code 2*pi}, and concatenates
	 * the cosine components ({@code outFeatures / 2}) followed by the sine components
	 * ({@code outFeatures / 2}) into a single {@code [batchSize, outFeatures]} producer. Returning a
	 * producer (rather than a {@link Block}) lets callers compose the Fourier features directly into a
	 * larger computation graph without an intervening host evaluation; this is the form reused by
	 * {@link NumberConditioner}.
	 * </p>
	 *
	 * @param batchSize      batch dimension
	 * @param outFeatures    number of output features; must be even for sin/cos pairs
	 * @param input          the scalar input producer of shape {@code [batchSize, inFeatures]}
	 * @param learnedWeights the projection weight matrix of shape {@code [outFeatures/2, inFeatures]}
	 * @return a producer of the Fourier feature projection
	 */
	default CollectionProducer fourierFeatures(int batchSize, int outFeatures,
											   Producer<PackedCollection> input, PackedCollection learnedWeights) {
		// Output dim should be even for sin/cos pairs
		if (outFeatures % 2 != 0) {
			throw new IllegalArgumentException("Output features must be even for Fourier features");
		}

		CollectionProducer values = c(input);
		CollectionProducer weights = cp(learnedWeights);

		CollectionProducer f = multiply(
				c(2.0 * Math.PI),
				matmul(values, weights.transpose(1))
		);

		return concat(shape(batchSize, outFeatures), cos(f), sin(f));
	}

	/**
	 * Builds a two-layer MLP timestep embedding that maps a scalar timestep
	 * to a dense conditioning vector.
	 * <p>
	 * The pipeline is: Fourier features → linear → SiLU → linear.
	 * Output shape is {@code [batchSize, embedDim]}.
	 * </p>
	 *
	 * @param batchSize              batch dimension
	 * @param embedDim               output embedding dimension
	 * @param timestepFeaturesWeight Fourier projection weights of shape {@code [128, 1]}
	 * @param weight0                first linear layer weights of shape {@code [embedDim, 256]}
	 * @param bias0                  first linear layer bias of shape {@code [embedDim]}
	 * @param weight2                second linear layer weights of shape {@code [embedDim, embedDim]}
	 * @param bias2                  second linear layer bias of shape {@code [embedDim]}
	 * @return a sequential block that produces the timestep embedding
	 */
	default Block timestepEmbedding(int batchSize, int embedDim,
									PackedCollection timestepFeaturesWeight,
									PackedCollection weight0, PackedCollection bias0,
									PackedCollection weight2, PackedCollection bias2) {
		SequentialBlock embedding = new SequentialBlock(shape(batchSize, 1));
		embedding.add(fourierFeatures(batchSize, 1, 256, timestepFeaturesWeight));
		embedding.add(dense(weight0, bias0));
		embedding.add(silu(shape(batchSize, embedDim)));
		embedding.add(dense(weight2, bias2));
		return embedding;
	}
}