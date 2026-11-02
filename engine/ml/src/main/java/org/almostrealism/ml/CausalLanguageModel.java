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

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.dsl.PdslLoader;
import org.almostrealism.layers.ParameterUpdate;
import org.almostrealism.model.Block;
import org.almostrealism.model.Model;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * A small decoder-only transformer language model, trainable from scratch.
 *
 * <p>The model maps a window of {@code seqLen} token ids to {@code (seqLen, vocabSize)}
 * log-probabilities of the next token at every position:</p>
 * <ol>
 *   <li>a trainable {@link org.almostrealism.layers.LayerFeatures#embedding token embedding};</li>
 *   <li>{@code depth} pre-norm transformer blocks with RMS normalization, causal self-attention
 *       with full rotary position embedding, no query/key normalization, and a SiLU-gated
 *       feed-forward of width {@code ffDim};</li>
 *   <li>a final RMS normalization, an output projection to the vocabulary, and a log-softmax over
 *       the vocabulary axis.</li>
 * </ol>
 *
 * <p>The structure is not assembled here. Both forward passes — this full-window one, and the
 * single-position one {@link #decodeModel} builds for decoding through key/value caches — are the
 * layers of {@link #CAUSAL_LM_ASSET}, which define each stage once: the embedding, the full-window and
 * single-position blocks, the gated feed-forward they share and the head. This class binds its
 * configuration as the asset's {@code settings}, in the layout of
 * {@link TransformerConfig#toPdslSettings()}, and each block's weights from the
 * {@link StateDictionary}, and builds those layers in order, one block layer per configured
 * block.</p>
 *
 * <p>The output is two-dimensional, {@code (seqLen, vocabSize)}, so that
 * {@link org.almostrealism.optimize.NegativeLogLikelihood} treats each position as one row and
 * reports the mean next-token loss over the window. All weights live in a {@link StateDictionary}
 * under the names given by the {@code *_KEY} constants and the per-layer keys of the
 * {@link CausalLanguageModelConfig configuration}, so a trained model can be saved and rebuilt
 * from the saved weights. Projection weights use the {@code (out, in)} convention.</p>
 *
 * <p>The layers are assembled when the model is constructed. Its weights are not updated until a
 * {@link #setParameterUpdate parameter update} (for example an
 * {@link org.almostrealism.optimize.AdamOptimizer}) is set, after which a model compiled with
 * {@link #compile(boolean) compile(true)} trains every weight except the rotary frequencies.
 * Like any {@link Model}, an instance is compiled once; another compilation of the same weights,
 * such as an inference model of weights being trained, is a second instance over the same
 * {@link StateDictionary}. A {@link SlidingWindowAutoregressiveModel} generates text from it.</p>
 *
 * <p>As for any {@link Model}, {@link #destroy()} releases the layers, and the layers release the
 * weights they were built over. Instances that share a {@link StateDictionary} therefore share
 * those weights' lifetime: destroy at most one of them, after the others are no longer used, or
 * release the dictionary itself instead.</p>
 *
 * @see CausalLanguageModelConfig
 * @see NextTokenDataset
 * @see SlidingWindowAutoregressiveModel
 */
public final class CausalLanguageModel extends Model implements SequenceAttentionFeatures {
	/**
	 * Classpath location of the asset that defines the structure of both forward passes of the model:
	 * the layers {@link #addLayers()} builds for the full window and {@link #decodeModel} builds for a
	 * single position.
	 */
	public static final String CAUSAL_LM_ASSET = "/pdsl/causal_lm.pdsl";

	/** Weight key of the token embedding table, {@code (vocabSize, dim)}. */
	public static final String EMBEDDING_KEY = "token_embedding";

	/** Weight key of the final normalization scale, {@code (dim)}. */
	public static final String FINAL_NORM_KEY = "final_norm";

	/** Weight key of the output projection, {@code (vocabSize, dim)}. */
	public static final String OUTPUT_KEY = "output";

	/** Key of the rotary inverse frequencies, {@code (dim / heads / 2)}; not trained. */
	public static final String INV_FREQ_KEY = "rope_inv_freq";

	/** Standard deviation of the normal initialization of embedding and projection weights. */
	public static final double INIT_STD = 0.02;

	/** The architecture of this model. */
	private final CausalLanguageModelConfig config;

	/** All weights of the model. */
	private final StateDictionary weights;

	/**
	 * Creates a model with freshly initialized weights, as created by
	 * {@link CausalLanguageModelConfig#createWeights(double, Random)}.
	 *
	 * @param vocabSize number of tokens in the vocabulary
	 * @param seqLen    number of positions per window
	 * @param dim       model dimension
	 * @param heads     number of attention heads
	 * @param depth     number of transformer blocks
	 * @param ffDim     hidden width of the gated feed-forward
	 * @param ropeBase  rotary embedding base frequency
	 * @param random    the source of the initial weights
	 * @throws IllegalArgumentException if {@link CausalLanguageModelConfig} rejects the
	 *                                  configuration, or if {@code ropeBase} is not a finite
	 *                                  positive number
	 */
	public CausalLanguageModel(int vocabSize, int seqLen, int dim, int heads, int depth, int ffDim,
							   double ropeBase, Random random) {
		this(new CausalLanguageModelConfig(vocabSize, seqLen, dim, heads, depth, ffDim), ropeBase, random);
	}

	/**
	 * Creates a model of a configuration with freshly initialized weights, as created by
	 * {@link CausalLanguageModelConfig#createWeights(double, Random)}.
	 *
	 * @param config   the architecture
	 * @param ropeBase rotary embedding base frequency
	 * @param random   the source of the initial weights
	 * @throws IllegalArgumentException if {@code ropeBase} is not a finite positive number
	 */
	public CausalLanguageModel(CausalLanguageModelConfig config, double ropeBase, Random random) {
		this(config, config.createWeights(ropeBase, random));
	}

	/**
	 * Creates a model over existing weights, such as weights loaded from a saved
	 * {@link StateDictionary}.
	 *
	 * @param vocabSize number of tokens in the vocabulary
	 * @param seqLen    number of positions per window
	 * @param dim       model dimension
	 * @param heads     number of attention heads
	 * @param depth     number of transformer blocks
	 * @param ffDim     hidden width of the gated feed-forward
	 * @param weights   the weights, under the configuration's keys
	 * @throws IllegalArgumentException if {@link CausalLanguageModelConfig} rejects the
	 *                                  configuration, or for any weights the
	 *                                  {@link #CausalLanguageModel(CausalLanguageModelConfig, StateDictionary)
	 *                                  configuration and weights constructor} rejects
	 */
	public CausalLanguageModel(int vocabSize, int seqLen, int dim, int heads, int depth, int ffDim,
							   StateDictionary weights) {
		this(new CausalLanguageModelConfig(vocabSize, seqLen, dim, heads, depth, ffDim), weights);
	}

	/**
	 * Creates a model of a configuration over existing weights: weights loaded from a saved
	 * {@link StateDictionary}, or the weights of another model of the same configuration, for a
	 * second compilation of them. Every weight the configuration needs must be present under its
	 * key with the shape given by {@link CausalLanguageModelConfig#getWeightShapes()}, so that an
	 * incompatible checkpoint is rejected here rather than when the model is run.
	 *
	 * @param config  the architecture
	 * @param weights the weights, under the configuration's keys
	 * @throws IllegalArgumentException if {@code weights} is null, or if a weight is missing or
	 *                                  has the wrong shape
	 */
	public CausalLanguageModel(CausalLanguageModelConfig config, StateDictionary weights) {
		super(config.getInputShape(), ParameterUpdate.disabled());
		config.requireWeights(weights);

		this.config = config;
		this.weights = weights;
		addLayers();
	}

	/**
	 * Returns the architecture of this model, which determines the shape and key of every
	 * weight.
	 *
	 * @return the configuration
	 */
	public CausalLanguageModelConfig getConfig() {
		return config;
	}

	/**
	 * Returns the shape of the model's output, {@code (seqLen, vocabSize)}, as declared by its
	 * configuration. The last layer produces these dimensions, possibly with another traversal
	 * axis.
	 *
	 * @return the output shape
	 */
	@Override
	public TraversalPolicy getOutputShape() {
		return config.getOutputShape();
	}

	/**
	 * Returns the weights of this model.
	 *
	 * @return the state dictionary holding every weight
	 */
	public StateDictionary getWeights() {
		return weights;
	}

	/**
	 * Adds the layers of this model over its weights, from {@code (seqLen)} token ids to
	 * {@code (seqLen, vocabSize)} log-probabilities: the {@code causal_lm_embedding} layer of
	 * {@link #CAUSAL_LM_ASSET}, its {@code causal_lm_block} layer once per block, bound to that block's
	 * weights, and its {@code causal_lm_window_head} layer. The model attends to every position of its
	 * window, so each block's padding and key masks are bound as all-ones masks, which leave every value
	 * and every score unchanged.
	 */
	private void addLayers() {
		TraversalPolicy blockShape = shape(1, config.seqLen, config.dim);

		Map<String, Object> embedding = new HashMap<>();
		embedding.put("token_embedding", weights.get(EMBEDDING_KEY));
		add(buildLayer("causal_lm_embedding", config.getInputShape(), embedding));

		for (int i = 0; i < config.layerCount; i++) {
			Map<String, Object> block = blockArguments(i);
			block.put("inv_freq", weights.get(INV_FREQ_KEY));
			block.put("padding_mask", everyPositionValid(1, config.seqLen));
			block.put("key_mask", everyPositionValid(1, config.seqLen));
			add(buildLayer("causal_lm_block", blockShape, block));
		}

		add(buildLayer("causal_lm_window_head", blockShape, headArguments()));
	}

	/**
	 * Builds a layer of {@link #CAUSAL_LM_ASSET} over this model's configuration, which every layer of
	 * the asset reads its sizes from: the configuration is bound as the layer's {@code settings}, in
	 * the layout of {@link TransformerConfig#toPdslSettings()}, alongside the layer's own arguments.
	 *
	 * @param layer      the layer name
	 * @param inputShape the input shape the layer is built for
	 * @param args       the layer's weights and the state of the forward pass it belongs to
	 * @return the layer
	 */
	private Block buildLayer(String layer, TraversalPolicy inputShape, Map<String, Object> args) {
		Map<String, Object> arguments = new HashMap<>(args);
		arguments.put("settings", config.toPdslSettings());
		PdslLoader loader = new PdslLoader();
		return loader.buildLayer(loader.parseResource(CAUSAL_LM_ASSET), layer, inputShape, arguments);
	}

	/**
	 * Binds the weights of one block, which both block layers of {@link #CAUSAL_LM_ASSET} take: the
	 * full-window {@code causal_lm_block} and the single-position {@code causal_lm_decode_block}.
	 *
	 * @param layer the block index
	 * @return the bindings, to be completed with the arguments of one of the two block layers
	 */
	private Map<String, Object> blockArguments(int layer) {
		Map<String, Object> args = new HashMap<>();
		args.put("attention_norm", weights.get(config.layerKey(layer, "attention_norm")));
		args.put("qkv", weights.get(config.layerKey(layer, "qkv")));
		args.put("wo", weights.get(config.layerKey(layer, "wo")));
		args.put("ffn_norm", weights.get(config.layerKey(layer, "ffn_norm")));
		args.put("w1", weights.get(config.layerKey(layer, "w1")));
		args.put("w2", weights.get(config.layerKey(layer, "w2")));
		return args;
	}

	/**
	 * Binds the weights of the head of {@link #CAUSAL_LM_ASSET}, which both forward passes end in: the
	 * final normalization scale and the output projection.
	 *
	 * @return the bindings
	 */
	private Map<String, Object> headArguments() {
		Map<String, Object> args = new HashMap<>();
		args.put("final_norm", weights.get(FINAL_NORM_KEY));
		args.put("output", weights.get(OUTPUT_KEY));
		return args;
	}

	/**
	 * Builds the single-position key/value-cache decode variant of this model over its own weights,
	 * for one token at a time rather than a whole window. The variant takes one token's
	 * {@code (1, dim)} residual-stream embedding at the sequence {@code position} and returns the
	 * {@code (vocabSize)} log-probabilities of the next token, reading and writing a per-layer key and
	 * value cache so that each step scores the new position against the cached past instead of
	 * recomputing the whole window.
	 *
	 * <p>It is the decode counterpart of the training layers {@link #addLayers()} builds: the same
	 * embedding weights feed it (the caller supplies the embedding, as the generator does), and it is
	 * built from the same asset, {@link #CAUSAL_LM_ASSET} — its {@code causal_lm_decode_block} layer
	 * once per block, then its {@code causal_lm_head} layer. The decode block shares the full-window
	 * block's gated feed-forward layer, and the head is the full-window head without its reshape. Only
	 * the attention changes — from the full-window causal self-attention to the cached single-position
	 * attention. The attention weights are the same: the decode block reads the fused {@code qkv}
	 * {@code (3 * dim, dim)} weight as three contiguous {@code (dim, dim)} query, key and value views
	 * without a copy (section 0 is the query, 1 the key, 2 the value, as the full-window attention
	 * slices it), and the position-indexed rotary frequencies are derived from the stored
	 * {@link #INV_FREQ_KEY rotary inverse frequencies} with
	 * {@link #computeRopeFreqs(int, PackedCollection)}, which produces exactly the rotation the
	 * full-sequence path applies at the same row, so greedy decoding agrees with the full forward.</p>
	 *
	 * <p>The returned model is <em>not</em> compiled; the caller compiles and owns it. No weights are
	 * copied: the variant is built over this model's {@link StateDictionary}, which stays the caller's,
	 * exactly as a second training compilation would be. The per-layer caches, one cleared
	 * {@code (seqLen, dim)} key cache and value cache per block, are appended in block order to
	 * {@code keyCaches} and {@code valueCaches} (which must be empty and mutable) and bound as the
	 * {@code key_cache}/{@code value_cache} each block's attention layer reads and writes, so that the
	 * caller can clear them when a new sequence starts and release them when the decode model is
	 * destroyed — they are decode state, not model state.</p>
	 *
	 * @param position   the device-resident single-element sequence position the attention reads;
	 *                   the caller advances and resets it per step and per sequence
	 * @param keyCaches   an empty, mutable list that receives the per-block key caches in block order
	 * @param valueCaches an empty, mutable list that receives the per-block value caches in block order
	 * @return the uncompiled single-position decode model, input {@code (1, dim)} and output
	 *         {@code (1, vocabSize)}, the {@code vocabSize} next-token log-probabilities
	 * @throws IllegalArgumentException if {@code position}, {@code keyCaches} or {@code valueCaches} is
	 *                                  null, or if either cache list is not empty
	 */
	public Model decodeModel(PackedCollection position,
							 List<PackedCollection> keyCaches, List<PackedCollection> valueCaches) {
		if (position == null) {
			throw new IllegalArgumentException("position must not be null");
		} else if (keyCaches == null || valueCaches == null) {
			throw new IllegalArgumentException("cache lists must not be null");
		} else if (!keyCaches.isEmpty() || !valueCaches.isEmpty()) {
			throw new IllegalArgumentException("cache lists must be empty");
		}

		CollectionProducer freqCis = computeRopeFreqs(config.seqLen, weights.get(INV_FREQ_KEY));
		TraversalPolicy tokenShape = shape(1, config.dim);

		Model model = new Model(tokenShape);

		for (int i = 0; i < config.layerCount; i++) {
			PackedCollection keyCache = new PackedCollection(shape(config.seqLen, config.dim));
			PackedCollection valueCache = new PackedCollection(shape(config.seqLen, config.dim));
			keyCache.clear();
			valueCache.clear();
			keyCaches.add(keyCache);
			valueCaches.add(valueCache);

			Map<String, Object> block = blockArguments(i);
			block.put("freq_cis", freqCis);
			block.put("position", p(position));
			block.put("key_cache", keyCache);
			block.put("value_cache", valueCache);
			model.add(buildLayer("causal_lm_decode_block", tokenShape, block));
		}

		model.add(buildLayer("causal_lm_head", tokenShape, headArguments()));
		return model;
	}
}
