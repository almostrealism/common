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
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.dsl.PdslLoader;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A T5Gemma text encoder: token ids become embeddings scaled by the square root of the hidden
 * width, each layer adds {@code postNorm(attention(preNorm(x)))} and
 * {@code postNorm(feedForward(preNorm(x)))} to the residual stream, and a final RMS norm closes the
 * stack. Attention is bidirectional over the whole padded sequence; padded keys are removed from
 * every softmax through the attention mask.
 *
 * <p>The structure of a layer is not assembled here: it is the {@code t5gemma_encoder_layer} of
 * {@link #ENCODER_ASSET}, where the two norm-wrapped sub-layers and their residual connections are
 * written out. This class reads each layer's weights from the dictionary, binds them with the masks
 * and builds that layer once per layer; the token embedding before the layers and the final norm
 * after them are built here.</p>
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

	/** The asset whose layers define one encoder layer and its two sub-layers. */
	private static final String ENCODER_ASSET = "/pdsl/t5gemma_encoder.pdsl";

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

	/**
	 * The number of prompt positions, held on the device as a single value so that the validity
	 * mask is produced by a kernel reading it rather than written by host code.
	 */
	private final PackedCollection promptLength;

	/**
	 * The compiled assignment that writes {@link #attentionMask} from {@link #promptLength},
	 * built on first use and reused by every subsequent {@link #loadPrompt(long[])}.
	 */
	private Runnable attentionMaskUpdate;

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
		this.promptLength = new PackedCollection(shape(1));
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
		int count = tokens.length < length ? tokens.length : length;
		long[] ids = new long[length];
		for (int i = 0; i < length; i++) {
			boolean present = i < tokens.length;
			if (present && (tokens[i] < 0 || tokens[i] >= config.getVocabularySize())) {
				throw new IllegalArgumentException("Token id " + tokens[i] + " at position " + i
						+ " is outside the vocabulary [0, " + config.getVocabularySize() + ")");
			}

			ids[i] = present ? tokens[i] : PAD_TOKEN;
		}

		tokenIds.fill(ids);
		promptLength.fill(count);

		if (attentionMaskUpdate == null) {
			TraversalPolicy maskShape = shape(BATCH, length);
			CollectionProducer position = integers(0, length).repeat(BATCH).reshape(maskShape);
			CollectionProducer limit = cp(promptLength).repeat(BATCH * length).reshape(maskShape);
			attentionMaskUpdate = a("attentionMask", p(attentionMask), lessThan(position, limit)).get();
		}

		attentionMaskUpdate.run();
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
	 * Assembles the encoder graph over the token id input: the scaled token embedding, one
	 * {@link #encoderLayer encoder layer} of {@link #ENCODER_ASSET} per configured layer, and the
	 * final RMS norm.
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
			main.add(encoderLayer(blockShape, "encoder.layers." + i, invFreq));
		}

		main.add(rmsnorm(blockShape, weight("encoder.norm.weight", hidden), config.getNormEpsilon()));
		return main;
	}

	/**
	 * The scaled token embedding: each id selects its row of the table, and the rows are scaled
	 * by the square root of the hidden width. The row selection is a row gather, whose element
	 * addresses are computed in integer arithmetic inside the graph; a flat element index would
	 * be rounded once it exceeds what single precision can represent, and the table is far larger
	 * than that.
	 *
	 * @param table the embedding table, shape {@code [vocabulary, hiddenSize]}
	 * @return the embedding block, {@code [batch, maxLength]} to {@code [batch, maxLength, hiddenSize]}
	 */
	Block tokenEmbedding(PackedCollection table) {
		int hidden = config.getHiddenSize();
		int length = config.getMaxLength();
		TraversalPolicy inputShape = shape(BATCH, length);
		TraversalPolicy outputShape = shape(BATCH, length, hidden);

		return layer("tokenEmbedding", inputShape, outputShape, ids ->
				rows(shape(BATCH * length, hidden), cp(table), c(ids).reshape(shape(BATCH * length)))
						.multiply(Math.sqrt(hidden)).reshape(outputShape), List.of(table));
	}

	/**
	 * One encoder layer, {@code x + norm(attention(norm(x)))} then {@code x + norm(ffn(norm(x)))}: the
	 * {@code t5gemma_encoder_layer} of {@link #ENCODER_ASSET}, in its soft-capped form when the
	 * configuration soft-caps the attention logits, bound with the weights of the layer at
	 * {@code prefix}.
	 *
	 * @param blockShape the hidden state shape
	 * @param prefix     the layer's weight key prefix
	 * @param invFreq    the rotary frequencies
	 * @return the layer
	 */
	Block encoderLayer(TraversalPolicy blockShape, String prefix, PackedCollection invFreq) {
		Map<String, Object> arguments = selfAttentionArguments(blockShape, prefix, invFreq);
		arguments.putAll(feedForwardArguments(blockShape, prefix));
		return buildLayer("t5gemma_encoder_layer" + attentionLogits(), blockShape, arguments);
	}

	/**
	 * The attention residual branch — RMS norm, masked self-attention, RMS norm — on its own: the
	 * {@code t5gemma_self_attention} sub-layer of {@link #ENCODER_ASSET}, in its soft-capped form when
	 * the configuration soft-caps the attention logits, without the residual connection
	 * {@link #encoderLayer} adds around it.
	 *
	 * @param blockShape the hidden state shape
	 * @param prefix     the layer's weight key prefix
	 * @param invFreq    the rotary frequencies
	 * @return the branch
	 */
	Block attentionBranch(TraversalPolicy blockShape, String prefix, PackedCollection invFreq) {
		return buildLayer("t5gemma_self_attention" + attentionLogits(), blockShape,
				selfAttentionArguments(blockShape, prefix, invFreq));
	}

	/**
	 * The feed-forward residual branch — RMS norm, GELU-gated feed-forward, RMS norm — on its own: the
	 * {@code t5gemma_feed_forward} sub-layer of {@link #ENCODER_ASSET}, without the residual connection
	 * {@link #encoderLayer} adds around it.
	 *
	 * @param blockShape the hidden state shape
	 * @param prefix     the layer's weight key prefix
	 * @return the branch
	 */
	Block feedForwardBranch(TraversalPolicy blockShape, String prefix) {
		return buildLayer("t5gemma_feed_forward", blockShape, feedForwardArguments(blockShape, prefix));
	}

	/**
	 * The arguments of the self-attention sub-layer of the layer at {@code prefix}: its two norms and
	 * two projections, the head geometry, the rotary frequencies, the soft-cap, and the masks — the
	 * prompt mask filled by {@link #loadPrompt(long[])} as the key mask, and an all-ones value mask,
	 * because the encoder leaves the values of padded positions as they are.
	 *
	 * @param blockShape the hidden state shape
	 * @param prefix     the layer's weight key prefix
	 * @param invFreq    the rotary frequencies
	 * @return the arguments
	 */
	private Map<String, Object> selfAttentionArguments(TraversalPolicy blockShape, String prefix,
													   PackedCollection invFreq) {
		int hidden = config.getHiddenSize();
		Map<String, Object> arguments = layerArguments(blockShape);
		arguments.put("heads", config.getHeads());
		arguments.put("head_dim", config.getHeadDim());
		arguments.put("pre_self_attn_layernorm", weight(prefix + ".pre_self_attn_layernorm.weight", hidden));
		arguments.put("to_qkv", weight(prefix + ".self_attn.to_qkv.weight", 3 * hidden, hidden));
		arguments.put("o_proj", weight(prefix + ".self_attn.o_proj.weight", hidden, hidden));
		arguments.put("post_self_attn_layernorm", weight(prefix + ".post_self_attn_layernorm.weight", hidden));
		arguments.put("inv_freq", invFreq);
		arguments.put("padding_mask", everyPositionValid(BATCH, config.getMaxLength()));
		arguments.put("key_mask", cp(attentionMask));
		arguments.put("softcap", config.getAttentionSoftcap());
		return arguments;
	}

	/**
	 * The arguments of the feed-forward sub-layer of the layer at {@code prefix}: its two norms, the
	 * fused linear and gate projection, the down projection and the inner width.
	 *
	 * @param blockShape the hidden state shape
	 * @param prefix     the layer's weight key prefix
	 * @return the arguments
	 */
	private Map<String, Object> feedForwardArguments(TraversalPolicy blockShape, String prefix) {
		int hidden = config.getHiddenSize();
		int inner = config.getIntermediateSize();
		Map<String, Object> arguments = layerArguments(blockShape);
		arguments.put("inner_dim", inner);
		arguments.put("pre_feedforward_layernorm", weight(prefix + ".pre_feedforward_layernorm.weight", hidden));
		arguments.put("mlp_proj", weight(prefix + ".mlp.proj.weight", 2 * inner, hidden));
		arguments.put("down_proj", weight(prefix + ".mlp.down_proj.weight", hidden, inner));
		arguments.put("post_feedforward_layernorm", weight(prefix + ".post_feedforward_layernorm.weight", hidden));
		return arguments;
	}

	/**
	 * The arguments every layer of {@link #ENCODER_ASSET} takes: the batch and sequence extents of the
	 * hidden states, and the epsilon of every RMS norm.
	 *
	 * @param blockShape the hidden state shape, {@code [batch, maxLength, hiddenSize]}
	 * @return the arguments, to be completed with the layer's own
	 */
	private Map<String, Object> layerArguments(TraversalPolicy blockShape) {
		Map<String, Object> arguments = new HashMap<>();
		arguments.put("batch", blockShape.length(0));
		arguments.put("seq_len", blockShape.length(1));
		arguments.put("epsilon", config.getNormEpsilon());
		return arguments;
	}

	/**
	 * Names the attention logits of this configuration in the layer names of {@link #ENCODER_ASSET}:
	 * {@code _softcapped} when the configuration soft-caps them, nothing when it does not.
	 *
	 * @return the layer name suffix
	 */
	private String attentionLogits() {
		return config.hasAttentionSoftcap() ? "_softcapped" : "";
	}

	/**
	 * Builds a layer of {@link #ENCODER_ASSET} for the hidden state shape.
	 *
	 * @param layer      the layer name
	 * @param blockShape the hidden state shape
	 * @param arguments  the layer's arguments
	 * @return the layer
	 */
	private Block buildLayer(String layer, TraversalPolicy blockShape, Map<String, Object> arguments) {
		PdslLoader loader = new PdslLoader();
		return loader.buildLayer(loader.parseResource(ENCODER_ASSET), layer, blockShape, arguments);
	}

	/**
	 * Takes a weight of the given shape from the dictionary, marking it consumed.
	 *
	 * @param key  the weight key
	 * @param dims the expected shape
	 * @return the weight
	 */
	PackedCollection weight(String key, int... dims) {
		PackedCollection value = weights.get(key);
		if (value == null) {
			throw new IllegalArgumentException("Missing weight " + key);
		}

		TraversalPolicy actual = value.getShape();
		TraversalPolicy expected = shape(dims);
		boolean sameRank = actual.getDimensions() == expected.getDimensions();
		if (actual.getTotalSize() != expected.getTotalSize()
				|| (sameRank && !Arrays.equals(actual.extent(), expected.extent()))) {
			throw new IllegalArgumentException("Weight " + key + " has shape " + actual
					+ " but " + expected + " was expected");
		}

		unusedWeights.remove(key);
		return sameRank ? value : value.reshape(expected);
	}

	@Override
	public void destroy() {
		weights.destroy();

		if (compiled != null) {
			compiled.destroy();
		}

		tokenIds.destroy();
		attentionMask.destroy();
		promptLength.destroy();
	}
}
