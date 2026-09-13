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

package org.almostrealism.ml.t5gemma;

import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.PackedCollection;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Small random weights in the layout {@link T5GemmaEncoder} reads: unit norm scales (the
 * extraction script's folded offset for a zero reference scale), the rotary frequencies of the
 * configured base, and small random projections and embeddings.
 */
public class T5GemmaWeightFixture implements CodeFeatures {

	/** Random projections are scaled by this factor so activations stay well-conditioned. */
	private static final double WEIGHT_SCALE = 0.2;

	/**
	 * A two-layer configuration with a 16-wide hidden state, two heads, a 32-wide feed-forward and a
	 * 40-token vocabulary, compiled for the given length.
	 *
	 * @param maxLength number of token positions
	 * @return the configuration
	 */
	public T5GemmaConfig smallConfig(int maxLength) {
		return new T5GemmaConfig(16, 2, 2, 8, 32, 1e-6, 50.0, 10000.0, 40, maxLength);
	}

	/**
	 * Builds the weight map for the given configuration.
	 *
	 * @param config the architecture
	 * @param seed   seed of the random weights
	 * @return weights keyed as the encoder reads them
	 */
	public Map<String, PackedCollection> weights(T5GemmaConfig config, long seed) {
		int hidden = config.getHiddenSize();
		int inner = config.getIntermediateSize();
		int half = config.getHeadDim() / 2;
		Random random = new Random(seed);
		Map<String, PackedCollection> w = new HashMap<>();

		w.put("encoder.embed_tokens.weight", random(random, config.getVocabularySize(), hidden));
		w.put("encoder.rotary.inv_freq", integers(0, half)
				.multiply(-2.0 * Math.log(config.getRopeTheta()) / config.getHeadDim()).exp().evaluate());
		w.put("encoder.norm.weight", new PackedCollection(shape(hidden)).fill(1.0));

		for (int i = 0; i < config.getLayers(); i++) {
			String p = "encoder.layers." + i;
			w.put(p + ".pre_self_attn_layernorm.weight", new PackedCollection(shape(hidden)).fill(1.0));
			w.put(p + ".post_self_attn_layernorm.weight", new PackedCollection(shape(hidden)).fill(1.0));
			w.put(p + ".pre_feedforward_layernorm.weight", new PackedCollection(shape(hidden)).fill(1.0));
			w.put(p + ".post_feedforward_layernorm.weight", new PackedCollection(shape(hidden)).fill(1.0));
			w.put(p + ".self_attn.to_qkv.weight", random(random, 3 * hidden, hidden));
			w.put(p + ".self_attn.o_proj.weight", random(random, hidden, hidden));
			w.put(p + ".mlp.proj.weight", random(random, 2 * inner, hidden));
			w.put(p + ".mlp.down_proj.weight", random(random, hidden, inner));
		}

		return w;
	}

	/**
	 * A small random weight.
	 *
	 * @param random the source of values
	 * @param dims   the weight dimensions
	 * @return the weight
	 */
	public PackedCollection random(Random random, int... dims) {
		PackedCollection value = new PackedCollection(shape(dims)).randnFill(random);
		return cp(value).multiply(WEIGHT_SCALE).into(value.traverseEach()).evaluate();
	}
}
