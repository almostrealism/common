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

public interface DiffusionTransformerFeatures extends AttentionFeatures, DiffusionFeatures {

	default Block fourierFeatures(int batchSize, int inFeatures, int outFeatures, PackedCollection learnedWeights) {
		// Output dim should be even for sin/cos pairs
		if (outFeatures % 2 != 0) {
			throw new IllegalArgumentException("Output features must be even for Fourier features");
		}

		return layer("fourierFeatures",
				shape(batchSize, inFeatures),
				shape(batchSize, outFeatures),
				in -> {
					CollectionProducer input = c(in);  // Shape: [batchSize, inFeatures]
					CollectionProducer weights = cp(learnedWeights);  // Shape: [outFeatures // 2, inFeatures]

					// Compute f = 2 * pi * input @ weights.T as in Python FourierFeatures
					// input: [batchSize, inFeatures] @ weights.T: [inFeatures, outFeatures // 2]
					// -> [batchSize, outFeatures // 2]
					CollectionProducer f = multiply(
							c(2.0 * Math.PI),
							matmul(input, weights.transpose(1))
					);

					// Calculate cos and sin components as in Python: torch.cat([f.cos(), f.sin()], dim=-1)
					CollectionProducer cosValues = cos(f);
					CollectionProducer sinValues = sin(f);

					// Concatenate cos first, then sin (matching Python order)
					return concat(shape(batchSize, outFeatures),
							cosValues, sinValues);
				},
				List.of(learnedWeights));
	}

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