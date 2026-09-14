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

package org.almostrealism.ml;

import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.layers.NormalizationType;
import org.almostrealism.ml.audio.DiffusionTransformerConfig;

import java.util.HashMap;
import java.util.Map;

/**
 * Small random weights for a {@link org.almostrealism.ml.audio.DiffusionTransformer} built from a
 * {@link DiffusionTransformerConfig}, covering every key the transformer consumes for that
 * configuration (including the adaLN, memory-token and local-add paths, and the normalization
 * family's parameter layout) so that {@code validateWeights()} accepts the dictionary. The
 * timestep features are assumed to be the deterministic {@code EXPO} encoding.
 */
public class DiffusionTransformerWeightFixture implements CodeFeatures {

	/** Random weights are scaled by this factor so activations stay well-conditioned. */
	private static final double WEIGHT_SCALE = 0.1;

	/** Width of the expo Fourier timestep features consumed by the timestep embedding. */
	private static final int TIMESTEP_FEATURES = 256;

	/**
	 * Builds the weight map for the given configuration.
	 *
	 * @param config the transformer configuration
	 * @return weights keyed as the transformer reads them
	 */
	public Map<String, PackedCollection> weights(DiffusionTransformerConfig config) {
		int dim = config.getEmbedDim();
		int io = config.getIoChannels();
		int dimHead = dim / config.getNumHeads();
		int hiddenDim = dim * 4;
		int packed = AdaptiveLayerNormFeatures.MODULATION_COMPONENTS * dim;
		boolean rms = config.getNormalization() == NormalizationType.RMS;
		boolean crossAttention = config.getCondTokenDim() > 0 && config.getCondSeqLen() > 0;
		Map<String, PackedCollection> w = new HashMap<>();

		put(w, "model.model.to_timestep_embed.0.weight", dim, TIMESTEP_FEATURES);
		put(w, "model.model.to_timestep_embed.0.bias", dim);
		put(w, "model.model.to_timestep_embed.2.weight", dim, dim);
		put(w, "model.model.to_timestep_embed.2.bias", dim);
		if (config.getGlobalCondDim() > 0) {
			put(w, "model.model.to_global_embed.0.weight", dim, config.getGlobalCondDim());
			put(w, "model.model.to_global_embed.2.weight", dim, dim);
		}
		if (crossAttention) {
			put(w, "model.model.to_cond_embed.0.weight", dim, config.getCondTokenDim());
			put(w, "model.model.to_cond_embed.2.weight", dim, dim);
		}
		put(w, "model.model.preprocess_conv.weight", io, io);
		put(w, "model.model.postprocess_conv.weight", io, io);
		put(w, "model.model.transformer.project_in.weight", dim, io);
		put(w, "model.model.transformer.project_out.weight", io, dim);
		put(w, "model.model.transformer.rotary_pos_emb.inv_freq", dimHead / 4);
		if (config.getNumMemoryTokens() > 0) {
			put(w, "model.model.transformer.memory_tokens", config.getNumMemoryTokens(), dim);
		}
		put(w, "model.model.transformer.global_cond_embedder.0.weight", dim, dim);
		put(w, "model.model.transformer.global_cond_embedder.0.bias", dim);
		put(w, "model.model.transformer.global_cond_embedder.2.weight", packed, dim);
		put(w, "model.model.transformer.global_cond_embedder.2.bias", packed);

		for (int i = 0; i < config.getDepth(); i++) {
			String p = "model.model.transformer.layers." + i;
			blockNorm(w, p + ".pre_norm", dim, rms);
			put(w, p + ".self_attn.to_qkv.weight", dim * 3, dim);
			put(w, p + ".self_attn.to_out.weight", dim, dim);
			queryKeyNorm(w, p + ".self_attn.q_norm", dimHead, rms);
			queryKeyNorm(w, p + ".self_attn.k_norm", dimHead, rms);
			if (crossAttention) {
				blockNorm(w, p + ".cross_attend_norm", dim, rms);
				put(w, p + ".cross_attn.to_q.weight", dim, dim);
				put(w, p + ".cross_attn.to_kv.weight", 2 * dim, dim);
				put(w, p + ".cross_attn.to_out.weight", dim, dim);
				queryKeyNorm(w, p + ".cross_attn.q_norm", dimHead, rms);
				queryKeyNorm(w, p + ".cross_attn.k_norm", dimHead, rms);
			}
			blockNorm(w, p + ".ff_norm", dim, rms);
			put(w, p + ".ff.ff.0.proj.weight", 2 * hiddenDim, dim);
			put(w, p + ".ff.ff.0.proj.bias", 2 * hiddenDim);
			put(w, p + ".ff.ff.2.weight", dim, hiddenDim);
			put(w, p + ".ff.ff.2.bias", dim);
			put(w, p + ".to_scale_shift_gate", packed);
			if (config.getLocalAddCondDim() > 0) {
				put(w, p + ".to_local_embed.0.weight", dim, config.getLocalAddCondDim());
				put(w, p + ".to_local_embed.0.bias", dim);
				put(w, p + ".to_local_embed.2.weight", dim, dim);
				put(w, p + ".to_local_embed.2.bias", dim);
			}
		}

		return w;
	}

	/**
	 * Adds a block normalization's parameters: {@code gamma} for both families, {@code beta} only
	 * for layer normalization.
	 *
	 * @param weights the weight map
	 * @param prefix  key prefix of the normalization module
	 * @param dim     feature count
	 * @param rms     whether the family is RMS normalization
	 */
	private void blockNorm(Map<String, PackedCollection> weights, String prefix, int dim, boolean rms) {
		put(weights, prefix + ".gamma", dim);
		if (!rms) {
			put(weights, prefix + ".beta", dim);
		}
	}

	/**
	 * Adds a query/key normalization's parameters: the layer normalization module stores a scale
	 * and a bias, the RMS normalization module a scale ({@code gamma}) alone.
	 *
	 * @param weights the weight map
	 * @param prefix  key prefix of the normalization module
	 * @param dimHead feature count
	 * @param rms     whether the family is RMS normalization
	 */
	private void queryKeyNorm(Map<String, PackedCollection> weights, String prefix, int dimHead, boolean rms) {
		if (rms) {
			put(weights, prefix + ".gamma", dimHead);
		} else {
			put(weights, prefix + ".weight", dimHead);
			put(weights, prefix + ".bias", dimHead);
		}
	}

	/**
	 * Adds a small random weight of the given shape to the weight map.
	 *
	 * @param weights the weight map
	 * @param key     the weight key
	 * @param dims    the weight dimensions
	 */
	private void put(Map<String, PackedCollection> weights, String key, int... dims) {
		PackedCollection value = new PackedCollection(shape(dims)).randnFill();
		weights.put(key, cp(value).multiply(WEIGHT_SCALE).into(value.traverseEach()).evaluate());
	}
}
