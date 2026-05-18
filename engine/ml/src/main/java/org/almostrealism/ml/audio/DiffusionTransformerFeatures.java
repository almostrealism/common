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

import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.ml.DiffusionFeatures;
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
 * @see DiffusionTransformer
 * @see org.almostrealism.ml.AttentionFeatures
 */
public interface DiffusionTransformerFeatures extends AttentionFeatures, DiffusionFeatures {

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
				in -> {
					CollectionProducer input = c(in);
					CollectionProducer weights = cp(learnedWeights);

					CollectionProducer f = multiply(
							c(2.0 * Math.PI),
							matmul(input, weights.transpose(1))
					);

					CollectionProducer cosValues = cos(f);
					CollectionProducer sinValues = sin(f);

					return concat(shape(batchSize, outFeatures),
							cosValues, sinValues);
				},
				List.of(learnedWeights));
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