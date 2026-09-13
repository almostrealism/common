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

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.lifecycle.Destroyable;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.layers.NormalizationType;
import org.almostrealism.layers.ProjectionFactory;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;

import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A T5Gemma text encoder built from the framework's attention and feed-forward primitives:
 * token ids become embeddings scaled by the square root of the hidden width, each layer adds
 * {@code postNorm(attention(preNorm(x)))} and {@code postNorm(feedForward(preNorm(x)))} to the
 * residual stream, and a final RMS norm closes the stack. Attention is bidirectional over the
 * whole padded sequence; padded keys are removed from every softmax through the attention mask.
 *
 * <p>The weights follow the released encoder's key layout under an {@code encoder.} prefix, as
 * prepared by the T5Gemma extraction script: the query, key and value projections are fused into
 * {@code self_attn.to_qkv.weight}, the feed-forward's linear and gate projections into
 * {@code mlp.proj.weight} (linear half first), every RMS norm scale carries its unit offset
 * folded in, and {@code rotary.inv_freq} holds the rotary frequencies of the configured base.</p>
 *
 * <p>The forward pass is the pipeline boundary: {@link #forward(long[])} loads the token ids and
 * their validity mask into the graph's leaves and runs the compiled model.</p>
 *
 * @see T5GemmaConfig
 */
public class T5GemmaEncoder implements AttentionFeatures, Destroyable {

	/** Every encoder is compiled for one prompt at a time. */
	private static final int BATCH = 1;

	/** Token id used to fill the positions beyond the prompt. */
	private static final long PAD_TOKEN = 0;

	/** The architecture. */
	private final T5GemmaConfig config;

	/** The weights, keyed as the extraction script writes them. */
	private final StateDictionary weights;

	/** Keys of {@link #weights} not yet consumed by the model build. */
	private final Set<String> unusedWeights;

	/** Token ids of the current prompt, shape {@code [batch, maxLength]}; a leaf of the graph. */
	private final PackedCollection tokenIds;

	/** Validity of each position (one for a prompt token, zero for padding); a leaf of the graph. */
	private final PackedCollection attentionMask;

	/** The encoder block, built on first use. */
	private Block encoder;

	/** The compiled standalone encoder, built on the first {@link #forward(long[])}. */
	private CompiledModel compiled;

	/**
	 * Creates an encoder over the given weights.
	 *
	 * @param config  the architecture
	 * @param weights the weights
	 */
	public T5GemmaEncoder(T5GemmaConfig config, StateDictionary weights) {
		this.config = config;
		this.weights = weights;
		this.unusedWeights = new HashSet<>(weights.keySet());
		this.tokenIds = new PackedCollection(shape(BATCH, config.getMaxLength()));
		this.tokenIds.clear();
		this.attentionMask = new PackedCollection(shape(BATCH, config.getMaxLength()));
		this.attentionMask.clear();
	}

	/**
	 * The architecture of this encoder.
	 *
	 * @return the configuration
	 */
	public T5GemmaConfig getConfig() { return config; }

	/**
	 * The validity mask of the prompt most recently passed to {@link #forward(long[])}, shape
	 * {@code [batch, maxLength]} with one at prompt positions and zero at padding.
	 *
	 * @return the mask
	 */
	public PackedCollection getAttentionMask() { return attentionMask; }

	/**
	 * Encodes a prompt. Ids beyond {@link T5GemmaConfig#getMaxLength()} are dropped and missing
	 * positions are padded; the returned hidden states cover every position, padded ones included.
	 *
	 * @param tokens the prompt's token ids
	 * @return the encoder output, shape {@code [batch, maxLength, hiddenSize]}
	 */
	public PackedCollection forward(long[] tokens) {
		PackedCollection ids = loadPrompt(tokens);

		if (compiled == null) {
			Model m = new Model(shape(BATCH, config.getMaxLength()));
			m.add(block());
			compiled = m.compile(false);
		}

		return compiled.forward(ids);
	}

	/**
	 * Loads a prompt into the encoder's inputs: the token ids (padded or truncated to the compiled
	 * length) and the validity mask read by every attention layer. A model composed from
	 * {@link #block()} feeds the returned ids as its input after calling this.
	 *
	 * @param tokens the prompt's token ids
	 * @return the token id input, shape {@code [batch, maxLength]}
	 */
	public PackedCollection loadPrompt(long[] tokens) {
		int length = config.getMaxLength();
		ByteBuffer ids = ByteBuffer.allocate(Double.BYTES * length);
		ByteBuffer mask = ByteBuffer.allocate(Double.BYTES * length);
		for (int i = 0; i < length; i++) {
			boolean present = i < tokens.length;
			ids.putDouble(present ? (double) tokens[i] : (double) PAD_TOKEN);
			mask.putDouble(present ? 1.0 : 0.0);
		}

		tokenIds.read(ids.flip());
		attentionMask.read(mask.flip());
		return tokenIds;
	}

	/**
	 * The encoder as a block from token ids {@code [batch, maxLength]} to hidden states
	 * {@code [batch, maxLength, hiddenSize]}, built on first use so that a larger model (a
	 * conditioner appending further tokens, say) can compose it. Building consumes the weights and
	 * rejects a dictionary holding keys the architecture does not read. The attention layers read
	 * the validity mask filled by {@link #loadPrompt(long[])}.
	 *
	 * @return the encoder block
	 */
	public Block block() {
		if (encoder == null) {
			encoder = buildEncoder();
			if (!unusedWeights.isEmpty()) {
				throw new IllegalStateException("Weights not consumed by the encoder: " + unusedWeights);
			}
		}

		return encoder;
	}

	/**
	 * Assembles the encoder graph over the token id input.
	 *
	 * @return the encoder block
	 */
	private Block buildEncoder() {
		int hidden = config.getHiddenSize();
		int length = config.getMaxLength();
		TraversalPolicy blockShape = shape(BATCH, length, hidden);

		SequentialBlock main = new SequentialBlock(shape(BATCH, length));
		main.add(tokenEmbedding(weight("encoder.embed_tokens.weight", config.getVocabularySize(), hidden)));

		PackedCollection invFreq = weight("encoder.rotary.inv_freq", config.getHeadDim() / 2);
		for (int i = 0; i < config.getLayers(); i++) {
			String prefix = "encoder.layers." + i;
			main.add(residual(attentionBranch(blockShape, prefix, invFreq)));
			main.add(residual(feedForwardBranch(blockShape, prefix)));
		}

		main.add(rmsnorm(blockShape, weight("encoder.norm.weight", hidden), config.getNormEpsilon()));
		return main;
	}

	/**
	 * The scaled token embedding: each id selects its row of the table, and the rows are scaled
	 * by the square root of the hidden width. The row selection is a gather whose indices are
	 * computed inside the graph from the ids.
	 *
	 * @param table the embedding table, shape {@code [vocabulary, hiddenSize]}
	 * @return the embedding block, {@code [batch, maxLength]} to {@code [batch, maxLength, hiddenSize]}
	 */
	private Block tokenEmbedding(PackedCollection table) {
		int hidden = config.getHiddenSize();
		int length = config.getMaxLength();
		int total = BATCH * length * hidden;
		TraversalPolicy inputShape = shape(BATCH, length);
		TraversalPolicy outputShape = shape(BATCH, length, hidden);

		return layer("tokenEmbedding", inputShape, outputShape, ids -> {
			CollectionProducer element = integers(0, total);
			CollectionProducer feature = element.mod(hidden);
			CollectionProducer position = element.subtract(feature).divide(hidden);
			CollectionProducer id = c(shape(total), c(ids).reshape(shape(BATCH * length)), position);
			CollectionProducer index = id.multiply(hidden).add(feature);
			CollectionProducer table1d = cp(table).reshape(shape(table.getShape().getTotalSize()));
			return c(shape(total), table1d, index).multiply(Math.sqrt(hidden)).reshape(outputShape);
		}, List.of(table));
	}

	/**
	 * The attention residual branch: RMS norm, masked soft-capped self-attention, RMS norm.
	 *
	 * @param blockShape the hidden state shape
	 * @param prefix     the layer's weight key prefix
	 * @param invFreq    the rotary frequencies
	 * @return the branch
	 */
	private Block attentionBranch(TraversalPolicy blockShape, String prefix, PackedCollection invFreq) {
		int hidden = config.getHiddenSize();
		SequentialBlock branch = new SequentialBlock(blockShape);
		branch.add(rmsnorm(blockShape, weight(prefix + ".pre_self_attn_layernorm.weight", hidden), config.getNormEpsilon()));
		branch.add(sequenceAttention(BATCH, config.getMaxLength(), hidden, config.getHeads(),
				weight(prefix + ".self_attn.to_qkv.weight", 3 * hidden, hidden),
				weight(prefix + ".self_attn.o_proj.weight", hidden, hidden),
				null, null, null, null, invFreq, ProjectionFactory.dense(),
				NormalizationType.RMS, null, cp(attentionMask), config.getAttentionSoftcap()));
		branch.add(rmsnorm(blockShape, weight(prefix + ".post_self_attn_layernorm.weight", hidden), config.getNormEpsilon()));
		return branch;
	}

	/**
	 * The feed-forward residual branch: RMS norm, GELU-gated feed-forward, RMS norm.
	 *
	 * @param blockShape the hidden state shape
	 * @param prefix     the layer's weight key prefix
	 * @return the branch
	 */
	private Block feedForwardBranch(TraversalPolicy blockShape, String prefix) {
		int hidden = config.getHiddenSize();
		int inner = config.getIntermediateSize();
		SequentialBlock branch = new SequentialBlock(blockShape);
		branch.add(gatedLinearFeedForward(blockShape, NormalizationType.RMS,
				weight(prefix + ".pre_feedforward_layernorm.weight", hidden), null,
				weight(prefix + ".mlp.proj.weight", 2 * inner, hidden), null,
				weight(prefix + ".mlp.down_proj.weight", hidden, inner), null,
				null, null, gelu(), ProjectionFactory.dense()));
		branch.add(rmsnorm(blockShape, weight(prefix + ".post_feedforward_layernorm.weight", hidden), config.getNormEpsilon()));
		return branch;
	}

	/**
	 * Takes a weight of the given shape from the dictionary, marking it consumed.
	 *
	 * @param key  the weight key
	 * @param dims the expected shape
	 * @return the weight
	 */
	private PackedCollection weight(String key, int... dims) {
		PackedCollection value = weights.get(key);
		if (value == null) {
			throw new IllegalArgumentException("Missing weight " + key);
		}

		TraversalPolicy expected = shape(dims);
		if (value.getShape().getTotalSize() != expected.getTotalSize()) {
			throw new IllegalArgumentException("Weight " + key + " has shape " + value.getShape()
					+ " but " + expected + " was expected");
		}

		unusedWeights.remove(key);
		return value.getShape().getDimensions() == expected.getDimensions() ? value : value.reshape(expected);
	}

	@Override
	public void destroy() {
		if (compiled != null) {
			compiled.destroy();
		}

		tokenIds.destroy();
		attentionMask.destroy();
	}
}
