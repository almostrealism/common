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

package org.almostrealism.ml.clap;

import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.StateDictionary;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Random CLAP2 weights in the layout the extraction script writes, for a model small enough to
 * compile and run quickly: both towers 32 wide with two 16-wide heads and two blocks, a 24-wide
 * embedding space, and the full 50,260-token vocabulary (so the real special token ids, padding
 * included, address rows of the table).
 */
public class Clap2WeightFixture implements CodeFeatures {

	/** Width of both trunks. */
	public static final int WIDTH = 32;

	/** Number of heads of both towers. */
	public static final int HEADS = 2;

	/** Number of blocks of both towers. */
	public static final int LAYERS = 2;

	/** Inner width of the audio feed-forwards. */
	public static final int AUDIO_INNER = 48;

	/** Inner width of the audio pool's feed-forward. */
	public static final int POOL_INNER = 64;

	/** Inner width of the text feed-forwards. */
	public static final int TEXT_INNER = 40;

	/** Width of the shared embedding space. */
	public static final int EMBED = 24;

	/** Number of tokens in the vocabulary. */
	public static final int VOCABULARY = 50260;

	/** Random projections are scaled by this factor so activations stay well-conditioned. */
	private static final double WEIGHT_SCALE = 0.2;

	/**
	 * Creates the weights.
	 *
	 * @param seed the seed of every random tensor
	 * @return the weights
	 */
	public StateDictionary weights(long seed) {
		Random random = new Random(seed);
		Map<String, PackedCollection> w = new HashMap<>();
		int headDim = WIDTH / HEADS;

		w.put("audio.patch_embed.weight", random(random, WIDTH, Clap2Config.PATCH_SIZE + 1));
		w.put("audio.norm_pre.weight", ones(WIDTH));
		for (int i = 0; i < LAYERS; i++) {
			String p = "audio.blocks." + i;
			w.put(p + ".norm1.weight", ones(WIDTH));
			w.put(p + ".attn.qkv.weight", random(random, 3 * WIDTH, WIDTH));
			w.put(p + ".attn.qkv.bias", random(random, 3 * WIDTH));
			w.put(p + ".attn.proj.weight", random(random, WIDTH, WIDTH));
			w.put(p + ".attn.proj.bias", random(random, WIDTH));
			w.put(p + ".gamma_1", ones(1, WIDTH));
			w.put(p + ".norm2.weight", ones(WIDTH));
			w.put(p + ".mlp.fc1.weight", random(random, 2 * AUDIO_INNER, WIDTH));
			w.put(p + ".mlp.fc1.bias", random(random, 2 * AUDIO_INNER));
			w.put(p + ".mlp.fc2.weight", random(random, WIDTH, AUDIO_INNER));
			w.put(p + ".mlp.fc2.bias", random(random, WIDTH));
			w.put(p + ".gamma_2", ones(1, WIDTH));
		}
		w.put("audio.norm.weight", ones(WIDTH));
		w.put("audio.pool.latent", random(random, 1, WIDTH));
		w.put("audio.pool.q.weight", random(random, WIDTH, WIDTH));
		w.put("audio.pool.q.bias", random(random, WIDTH));
		w.put("audio.pool.kv.weight", random(random, 2 * WIDTH, WIDTH));
		w.put("audio.pool.kv.bias", random(random, 2 * WIDTH));
		w.put("audio.pool.proj.weight", random(random, WIDTH, WIDTH));
		w.put("audio.pool.proj.bias", random(random, WIDTH));
		w.put("audio.pool.norm.weight", ones(WIDTH));
		w.put("audio.pool.mlp.fc1.weight", random(random, POOL_INNER, WIDTH));
		w.put("audio.pool.mlp.fc1.bias", random(random, POOL_INNER));
		w.put("audio.pool.mlp.fc2.weight", random(random, WIDTH, POOL_INNER));
		w.put("audio.pool.mlp.fc2.bias", random(random, WIDTH));
		w.put("audio.head.hidden.weight", random(random, EMBED, WIDTH));
		w.put("audio.head.hidden.bias", random(random, EMBED));
		w.put("audio.head.output.weight", random(random, EMBED, EMBED));
		w.put("audio.head.output.bias", random(random, EMBED));
		w.put(Clap2Config.AUDIO_HEADS_KEY, ones(HEADS));

		w.put("text.token_embedding.weight", random(random, VOCABULARY, WIDTH));
		for (int i = 0; i < LAYERS; i++) {
			String p = "text.blocks." + i;
			w.put(p + ".norm1.weight", ones(WIDTH));
			w.put(p + ".attn.qkv.weight", random(random, 3 * WIDTH, WIDTH));
			w.put(p + ".attn.q_norm.weight", ones(headDim));
			w.put(p + ".attn.k_norm.weight", ones(headDim));
			w.put(p + ".attn.proj.weight", random(random, WIDTH, WIDTH));
			w.put(p + ".norm2.weight", ones(WIDTH));
			w.put(p + ".mlp.w12.weight", random(random, 2 * TEXT_INNER, WIDTH));
			w.put(p + ".mlp.w3.weight", random(random, WIDTH, TEXT_INNER));
		}
		w.put("text.ln_final.weight", ones(WIDTH));
		w.put("text.pool.latent", random(random, 1, WIDTH));
		w.put("text.pool.q.weight", random(random, WIDTH, WIDTH));
		w.put("text.pool.kv.weight", random(random, 2 * WIDTH, WIDTH));
		w.put("text.pool.q_norm.weight", ones(headDim));
		w.put("text.pool.k_norm.weight", ones(headDim));
		w.put("text.projection.weight", random(random, EMBED, WIDTH));
		w.put(Clap2.LOGIT_SCALE_KEY, pack(4.605170185988092));

		return new StateDictionary(w);
	}

	/**
	 * The cosine similarity of two embeddings, computed on the host; the CLAP2 tests compare
	 * native embeddings with each other and with reference embeddings through it.
	 *
	 * @param a the first embedding
	 * @param b the second embedding, of the same size
	 * @return their cosine similarity
	 */
	public static double cosine(PackedCollection a, PackedCollection b) {
		double dot = 0;
		double na = 0;
		double nb = 0;
		for (int i = 0; i < a.getShape().getTotalSize(); i++) {
			dot += a.toDouble(i) * b.toDouble(i);
			na += a.toDouble(i) * a.toDouble(i);
			nb += b.toDouble(i) * b.toDouble(i);
		}
		return dot / Math.sqrt(na * nb);
	}

	/**
	 * A normally distributed tensor scaled by {@link #WEIGHT_SCALE}.
	 *
	 * @param random the random source
	 * @param dims   the shape
	 * @return the tensor
	 */
	private PackedCollection random(Random random, int... dims) {
		PackedCollection value = new PackedCollection(shape(dims)).randnFill(random);
		return cp(value).multiply(WEIGHT_SCALE).into(value.traverseEach()).evaluate();
	}

	/**
	 * A tensor of ones.
	 *
	 * @param dims the shape
	 * @return the tensor
	 */
	private PackedCollection ones(int... dims) {
		return new PackedCollection(shape(dims)).fill(1.0);
	}
}
