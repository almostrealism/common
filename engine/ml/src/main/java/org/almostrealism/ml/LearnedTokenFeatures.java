/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.ml;

import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.model.Block;

import java.util.List;

/**
 * Mixin interface providing learned "memory" / "register" tokens for transformer sequences.
 *
 * <p>Learned register tokens are a fixed set of trainable embeddings prepended to a transformer's
 * token sequence. The transformer attends to them alongside the real tokens, and they are stripped
 * before the model output so the result corresponds only to the real tokens. Registers are a general,
 * model-agnostic capability (they stabilise attention and are common across modern ViT/DiT
 * architectures), so this primitive is reusable by any AR transformer rather than being specific to a
 * single model.</p>
 *
 * <p>The capability is expressed as two composable pieces:</p>
 * <ul>
 *   <li>{@link #learnedTokens(int, int, int, PackedCollection)} broadcasts a learned
 *       {@code [numTokens, dim]} parameter to a {@code [batch, numTokens, dim]} producer; and</li>
 *   <li>{@link #prependLearnedTokens(int, int, int, int, PackedCollection)} concatenates those tokens
 *       to the front of an existing {@code [batch, seqLen, dim]} sequence.</li>
 * </ul>
 *
 * @author  Michael Murray
 */
public interface LearnedTokenFeatures extends LayerFeatures {

	/**
	 * Broadcasts a learned {@code [numTokens, dim]} parameter to a {@code [batch, numTokens, dim]}
	 * producer, replicating the same set of tokens across every batch element.
	 *
	 * @param batchSize    batch dimension
	 * @param numTokens    number of learned tokens
	 * @param dim          embedding dimension of each token
	 * @param tokenWeights the learned token parameter of shape {@code [numTokens, dim]}
	 * @return a producer of the broadcast tokens, shape {@code [batch, numTokens, dim]}
	 */
	default CollectionProducer learnedTokens(int batchSize, int numTokens, int dim,
											 PackedCollection tokenWeights) {
		return cp(tokenWeights).reshape(numTokens, dim).repeat(0, batchSize);
	}

	/**
	 * Builds a block that prepends {@code numTokens} learned tokens to the front of a
	 * {@code [batch, seqLen, dim]} sequence, producing a {@code [batch, numTokens + seqLen, dim]}
	 * sequence. The learned tokens occupy the leading positions; the original sequence follows
	 * unchanged, so stripping the leading {@code numTokens} positions after the transformer recovers
	 * the real tokens.
	 *
	 * @param batchSize    batch dimension
	 * @param seqLen       length of the incoming sequence (before the learned tokens are added)
	 * @param numTokens    number of learned tokens to prepend
	 * @param dim          embedding dimension
	 * @param tokenWeights the learned token parameter of shape {@code [numTokens, dim]}
	 * @return a block mapping {@code [batch, seqLen, dim]} to {@code [batch, numTokens + seqLen, dim]}
	 */
	default Block prependLearnedTokens(int batchSize, int seqLen, int numTokens, int dim,
									   PackedCollection tokenWeights) {
		CollectionProducer tokens = learnedTokens(batchSize, numTokens, dim, tokenWeights);

		return layer("prependLearnedTokens",
				shape(batchSize, seqLen, dim),
				shape(batchSize, numTokens + seqLen, dim),
				in -> concat(1, tokens, c(in)),
				List.of(tokenWeights));
	}
}
