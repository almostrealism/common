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

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
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
 * under the names given by the {@code *_KEY} constants and the per-layer keys, so a trained model
 * can be saved and rebuilt from the saved weights. Projection weights use the {@code (out, in)}
 * convention.</p>
 *
 * @see NextTokenDataset
 */
public class CausalLanguageModel implements TransformerBlockFeatures {
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

	/** Number of tokens in the vocabulary. */
	private final int vocabSize;

	/** Number of positions per window. */
	private final int seqLen;

	/** Model dimension. */
	private final int dim;

	/** Number of attention heads. */
	private final int heads;

	/** Number of transformer blocks. */
	private final int depth;

	/** Hidden width of the gated feed-forward. */
	private final int ffDim;

	/** All weights of the model. */
	private final StateDictionary weights;

	/**
	 * Creates a model with freshly initialized weights. Embedding and projection weights are
	 * drawn from a normal distribution with mean 0 and standard deviation {@link #INIT_STD} using
	 * {@code random}, normalization scales are ones, and the rotary inverse frequencies are those
	 * of full rotary embedding with base {@code ropeBase}.
	 *
	 * @param vocabSize number of tokens in the vocabulary
	 * @param seqLen    number of positions per window
	 * @param dim       model dimension
	 * @param heads     number of attention heads
	 * @param depth     number of transformer blocks
	 * @param ffDim     hidden width of the gated feed-forward
	 * @param ropeBase  rotary embedding base frequency
	 * @param random    the source of the initial weights
	 * @throws IllegalArgumentException for any configuration the weights constructor rejects, or
	 *                                  if {@code ropeBase} is not a finite positive number
	 */
	public CausalLanguageModel(int vocabSize, int seqLen, int dim, int heads, int depth, int ffDim,
							   double ropeBase, Random random) {
		this(new StateDictionary(new HashMap<>()), vocabSize, seqLen, dim, heads, depth, ffDim);
		initialize(ropeBase, random);
	}

	/**
	 * Creates a model over existing weights, such as weights loaded from a saved
	 * {@link StateDictionary}. Every weight the configuration needs must be present under its key
	 * with the shape given by {@link #getWeightShapes()}, so that an incompatible checkpoint is
	 * rejected here rather than when the model is built or run.
	 *
	 * @param vocabSize number of tokens in the vocabulary
	 * @param seqLen    number of positions per window
	 * @param dim       model dimension
	 * @param heads     number of attention heads
	 * @param depth     number of transformer blocks
	 * @param ffDim     hidden width of the gated feed-forward
	 * @param weights   the weights, under this class's keys
	 * @throws IllegalArgumentException if {@code vocabSize}, {@code seqLen}, {@code dim},
	 *                                  {@code heads} or {@code ffDim} is not positive, if
	 *                                  {@code depth} is negative, if {@code heads} does not divide
	 *                                  {@code dim} or leaves an odd head dimension, if
	 *                                  {@code weights} is null, or if a weight is missing or has
	 *                                  the wrong shape
	 */
	public CausalLanguageModel(int vocabSize, int seqLen, int dim, int heads, int depth, int ffDim,
							   StateDictionary weights) {
		this(weights, vocabSize, seqLen, dim, heads, depth, ffDim);

		getWeightShapes().forEach((key, expected) -> {
			PackedCollection weight = weights.get(key);
			if (weight == null) {
				throw new IllegalArgumentException("Missing weight " + key);
			}

			if (!Arrays.equals(expected.extent(), weight.getShape().extent())) {
				throw new IllegalArgumentException("Weight " + key + " has shape " + weight.getShape() +
						", not " + expected);
			}
		});
	}

	/**
	 * Validates the configuration and stores it with {@code weights}, without checking the
	 * weights themselves, which the fresh-weights constructor has yet to create.
	 *
	 * @param weights   the weights, under this class's keys
	 * @param vocabSize number of tokens in the vocabulary
	 * @param seqLen    number of positions per window
	 * @param dim       model dimension
	 * @param heads     number of attention heads
	 * @param depth     number of transformer blocks
	 * @param ffDim     hidden width of the gated feed-forward
	 */
	private CausalLanguageModel(StateDictionary weights, int vocabSize, int seqLen, int dim, int heads,
								int depth, int ffDim) {
		if (vocabSize <= 0 || seqLen <= 0 || dim <= 0 || heads <= 0 || ffDim <= 0) {
			throw new IllegalArgumentException("vocabSize, seqLen, dim, heads and ffDim must be positive, not " +
					vocabSize + ", " + seqLen + ", " + dim + ", " + heads + ", " + ffDim);
		}

		if (depth < 0) {
			throw new IllegalArgumentException("depth must not be negative, not " + depth);
		}

		if (weights == null) {
			throw new IllegalArgumentException("weights must not be null");
		}

		if (dim % heads != 0) {
			throw new IllegalArgumentException("dim " + dim + " is not divisible by " + heads + " heads");
		}

		if ((dim / heads) % 2 != 0) {
			throw new IllegalArgumentException("Full rotary embedding needs an even head dimension, not " +
					dim / heads);
		}

		this.vocabSize = vocabSize;
		this.seqLen = seqLen;
		this.dim = dim;
		this.heads = heads;
		this.depth = depth;
		this.ffDim = ffDim;
		this.weights = weights;
	}

	/**
	 * Returns the weight key of a per-block weight.
	 *
	 * @param layer the block index
	 * @param name  the weight name within the block ({@code attention_norm}, {@code qkv},
	 *              {@code wo}, {@code ffn_norm}, {@code w1} or {@code w2})
	 * @return the weight key
	 */
	public String layerKey(int layer, String name) {
		return "layers." + layer + "." + name;
	}

	/**
	 * Returns the shape of every weight of this configuration, by key: the trainable weights
	 * and the rotary inverse frequencies.
	 *
	 * @return the weight shapes, by key
	 */
	public Map<String, TraversalPolicy> getWeightShapes() {
		Map<String, TraversalPolicy> shapes = new HashMap<>(getRandomWeightShapes());
		shapes.putAll(getScaleWeightShapes());
		shapes.put(INV_FREQ_KEY, shape(dim / heads / 2));
		return shapes;
	}

	/**
	 * Returns the shapes of the embedding and projection weights, which are initialized from a
	 * normal distribution.
	 *
	 * @return the shapes, by key
	 */
	private Map<String, TraversalPolicy> getRandomWeightShapes() {
		Map<String, TraversalPolicy> shapes = new HashMap<>();
		shapes.put(EMBEDDING_KEY, shape(vocabSize, dim));
		shapes.put(OUTPUT_KEY, shape(vocabSize, dim));

		for (int i = 0; i < depth; i++) {
			shapes.put(layerKey(i, "qkv"), shape(3 * dim, dim));
			shapes.put(layerKey(i, "wo"), shape(dim, dim));
			shapes.put(layerKey(i, "w1"), shape(2 * ffDim, dim));
			shapes.put(layerKey(i, "w2"), shape(dim, ffDim));
		}

		return shapes;
	}

	/**
	 * Returns the shapes of the normalization scales, which are initialized to ones.
	 *
	 * @return the shapes, by key
	 */
	private Map<String, TraversalPolicy> getScaleWeightShapes() {
		Map<String, TraversalPolicy> shapes = new HashMap<>();
		shapes.put(FINAL_NORM_KEY, shape(dim));

		for (int i = 0; i < depth; i++) {
			shapes.put(layerKey(i, "attention_norm"), shape(dim));
			shapes.put(layerKey(i, "ffn_norm"), shape(dim));
		}

		return shapes;
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
	 * Returns the shape of the model's output, {@code (seqLen, vocabSize)}.
	 *
	 * @return the output shape
	 */
	public TraversalPolicy getOutputShape() {
		return shape(seqLen, vocabSize);
	}

	/**
	 * Returns the number of trainable parameters (every weight this configuration declares in
	 * {@link #getWeightShapes()}, except the rotary frequencies). Any additional entries a loaded
	 * checkpoint carries are not part of the model and are not counted.
	 *
	 * @return the parameter count
	 */
	public long getParameterCount() {
		return getWeightShapes().entrySet().stream()
				.filter(entry -> !INV_FREQ_KEY.equals(entry.getKey()))
				.mapToLong(entry -> entry.getValue().getTotalSize())
				.sum();
	}

	/**
	 * Builds the computation graph of this model over its weights. Every weight except the rotary
	 * frequencies is trainable, and is updated by {@code update} during the backward pass of a
	 * model compiled for training.
	 *
	 * @param update the parameter update applied to every trainable weight (for example an
	 *               {@link org.almostrealism.optimize.AdamOptimizer})
	 * @return a model from {@code (seqLen)} token ids to {@code (seqLen, vocabSize)} log-probabilities
	 */
	public Model buildModel(ParameterUpdate<PackedCollection> update) {
		TraversalPolicy blockShape = shape(1, seqLen, dim);
		TraversalPolicy logitShape = shape(1, seqLen, vocabSize);

		Model model = new Model(shape(seqLen), update);
		model.add(embedding(shape(seqLen), weights.get(EMBEDDING_KEY)));
		model.add(reshape(shape(seqLen, dim), blockShape));

		for (int i = 0; i < depth; i++) {
			model.add(transformerBlock(1, dim, seqLen, heads, false, 0, null,
					weights.get(layerKey(i, "attention_norm")), null,
					weights.get(layerKey(i, "qkv")), weights.get(layerKey(i, "wo")),
					null, null, null, null,
					weights.get(INV_FREQ_KEY),
					null, null, null, null, null, null, null, null, null,
					weights.get(layerKey(i, "ffn_norm")), null,
					weights.get(layerKey(i, "w1")), weights.get(layerKey(i, "w2")), null, null,
					null, ProjectionFactory.dense(), AttentionVariant.STANDARD, null, null, null,
					NormalizationType.RMS, null, true));
		}

		model.add(norm(NormalizationType.RMS, weights.get(FINAL_NORM_KEY), null));
		model.add(dense(weights.get(OUTPUT_KEY)));
		model.add(reshape(logitShape, getOutputShape()));
		model.add(logSoftmax(getOutputShape()));
		return model;
	}

	/**
	 * Creates and initializes every weight of the model.
	 *
	 * @param ropeBase rotary embedding base frequency
	 * @param random   the source of the initial weights
	 */
	private void initialize(double ropeBase, Random random) {
		int dimHead = dim / heads;
		CollectionProducer invFreqValues = computeInvFreq(dimHead, ropeBase);

		Map<String, TraversalPolicy> normal = getRandomWeightShapes();
		normal.keySet().stream().sorted().forEach(key -> {
			PackedCollection weight = new PackedCollection(normal.get(key));
			a(cp(weight.each()), randn(weight.getShape(), 0.0, INIT_STD, random).each()).get().run();
			weights.put(key, weight);
		});

		getScaleWeightShapes().forEach((key, shape) -> weights.put(key, new PackedCollection(shape).fill(1.0)));

		PackedCollection invFreq = new PackedCollection(getWeightShapes().get(INV_FREQ_KEY));
		a(cp(invFreq.each()), invFreqValues.each()).get().run();
		weights.put(INV_FREQ_KEY, invFreq);
	}
}
