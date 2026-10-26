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
import org.almostrealism.layers.NormalizationType;
import org.almostrealism.layers.ParameterUpdate;
import org.almostrealism.layers.ProjectionFactory;
import org.almostrealism.model.Model;

import java.util.List;
import java.util.Random;

/**
 * A small decoder-only transformer language model, trainable from scratch.
 *
 * <p>The model maps a window of {@code seqLen} token ids to {@code (seqLen, vocabSize)}
 * log-probabilities of the next token at every position:</p>
 * <ol>
 *   <li>a trainable {@link org.almostrealism.layers.LayerFeatures#embedding token embedding};</li>
 *   <li>{@code depth} pre-norm {@link TransformerBlockFeatures#transformerBlock transformer blocks}
 *       with RMS normalization, causal self-attention with full rotary position embedding, no
 *       query/key normalization, and a SiLU-gated feed-forward of width {@code ffDim};</li>
 *   <li>a final RMS normalization, an output projection to the vocabulary, and a log-softmax over
 *       the vocabulary axis.</li>
 * </ol>
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
public final class CausalLanguageModel extends Model implements TransformerBlockFeatures {
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
	 * {@code (seqLen, vocabSize)} log-probabilities.
	 */
	private void addLayers() {
		TraversalPolicy blockShape = shape(1, config.seqLen, config.dim);
		TraversalPolicy logitShape = shape(1, config.seqLen, config.vocabSize);

		add(embedding(config.getInputShape(), weights.get(EMBEDDING_KEY)));
		add(reshape(shape(config.seqLen, config.dim), blockShape));

		for (int i = 0; i < config.layerCount; i++) {
			add(transformerBlock(1, config.dim, config.seqLen, config.headCount, false, 0, null,
					weights.get(config.layerKey(i, "attention_norm")), null,
					weights.get(config.layerKey(i, "qkv")), weights.get(config.layerKey(i, "wo")),
					null, null, null, null,
					weights.get(INV_FREQ_KEY),
					null, null, null, null, null, null, null, null, null,
					weights.get(config.layerKey(i, "ffn_norm")), null,
					weights.get(config.layerKey(i, "w1")), weights.get(config.layerKey(i, "w2")), null, null,
					null, ProjectionFactory.dense(), AttentionVariant.STANDARD, null, null, null,
					NormalizationType.RMS, null, true));
		}

		add(norm(NormalizationType.RMS, weights.get(FINAL_NORM_KEY), null));
		add(dense(weights.get(OUTPUT_KEY)));
		add(reshape(logitShape, config.getOutputShape()));
		add(logSoftmax(config.getOutputShape()));
	}

	/**
	 * Builds the single-position key/value-cache decode variant of this model over its own weights,
	 * for one token at a time rather than a whole window. The variant takes one token's
	 * {@code (1, dim)} residual-stream embedding at the sequence {@code position} and returns the
	 * {@code (vocabSize)} log-probabilities of the next token, reading and writing a per-layer key and
	 * value cache so that each step scores the new position against the cached past instead of
	 * recomputing the whole window.
	 *
	 * <p>It is the decode counterpart of the training layers assembled in {@link #addLayers()}: the
	 * same embedding weights feed it (the caller supplies the embedding, as the generator does), the
	 * feed-forward of each block is the identical {@link #gatedLinearFeedForward gated feed-forward}
	 * over a single position, and the final normalization, output projection and log-softmax are the
	 * same. Only the attention changes — from the full-window {@link #transformerBlock causal
	 * self-attention} to the cached single-position {@link AttentionFeatures#attention attention}. The
	 * attention weights are the same: the fused {@code qkv} {@code (3 * dim, dim)}
	 * weight is exposed as three contiguous {@code (dim, dim)} query, key and value views without a
	 * copy (section 0 is the query, 1 the key, 2 the value, as the training path's
	 * {@code sequence_attention} slices it), and the position-indexed rotary frequencies are derived
	 * from the stored {@link #INV_FREQ_KEY rotary inverse frequencies} with
	 * {@link #computeRopeFreqs(int, PackedCollection)}, which produces exactly the rotation the
	 * full-sequence path applies at the same row, so greedy decoding agrees with the full forward.</p>
	 *
	 * <p>The returned model is <em>not</em> compiled; the caller compiles and owns it. No weights are
	 * copied: the variant is built over this model's {@link StateDictionary}, which stays the caller's,
	 * exactly as a second training compilation would be. The per-layer caches, one cleared
	 * {@code (seqLen, dim)} key cache and value cache per block, are appended in block order to
	 * {@code keyCaches} and {@code valueCaches} (which must be empty and mutable) so that the caller
	 * can clear them when a new sequence starts and release them when the decode model is destroyed —
	 * they are decode state, not model state.</p>
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

		int dim = config.dim;
		CollectionProducer freqCis = computeRopeFreqs(config.seqLen, weights.get(INV_FREQ_KEY));
		TraversalPolicy tokenShape = shape(1, dim);
		TraversalPolicy ffnShape = shape(1, 1, dim);

		Model model = new Model(tokenShape);

		for (int i = 0; i < config.layerCount; i++) {
			PackedCollection qkv = weights.get(config.layerKey(i, "qkv"));
			PackedCollection wq = qkv.range(shape(dim, dim), 0);
			PackedCollection wk = qkv.range(shape(dim, dim), dim * dim);
			PackedCollection wv = qkv.range(shape(dim, dim), 2 * dim * dim);

			PackedCollection keyCache = new PackedCollection(shape(config.seqLen, dim));
			PackedCollection valueCache = new PackedCollection(shape(config.seqLen, dim));
			keyCache.clear();
			valueCache.clear();
			keyCaches.add(keyCache);
			valueCaches.add(valueCache);

			model.add(residual(attention(config.headCount, config.headCount,
					weights.get(config.layerKey(i, "attention_norm")),
					wk, wv, wq, weights.get(config.layerKey(i, "wo")),
					null, null, null, null, null,
					freqCis, p(position))));

			model.add(reshape(tokenShape, ffnShape));
			model.add(residual(gatedLinearFeedForward(ffnShape, NormalizationType.RMS,
					weights.get(config.layerKey(i, "ffn_norm")), null,
					weights.get(config.layerKey(i, "w1")), null,
					weights.get(config.layerKey(i, "w2")), null,
					null, null, silu(), ProjectionFactory.dense())));
			model.add(reshape(ffnShape, tokenShape));
		}

		model.add(norm(NormalizationType.RMS, weights.get(FINAL_NORM_KEY), null));
		model.add(dense(weights.get(OUTPUT_KEY)));
		model.add(logSoftmax(shape(1, config.vocabSize)));
		return model;
	}
}
