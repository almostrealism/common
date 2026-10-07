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
import io.almostrealism.lifecycle.Destroyable;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * The architecture of a {@link CausalLanguageModel}: a decoder-only transformer with full
 * multi-head attention (as many key/value heads as query heads), full rotary position embedding
 * and an output projection separate from the token embedding.
 *
 * <p>A configuration is validated when it is created, and determines the shape and key of every
 * weight of a model of it, so that weights can be created for it or checked against it.</p>
 *
 * @see CausalLanguageModel
 */
public class CausalLanguageModelConfig extends TransformerConfig {
	/** The graph operations {@link #createWeights} builds the initial weights with. */
	private static final RotationFeatures OPS = new RotationFeatures() { };

	/**
	 * Creates and validates a configuration.
	 *
	 * @param vocabSize number of tokens in the vocabulary
	 * @param seqLen    number of positions per window
	 * @param dim       model dimension
	 * @param heads     number of attention heads
	 * @param depth     number of transformer blocks
	 * @param ffDim     hidden width of the gated feed-forward
	 * @throws IllegalArgumentException if {@link TransformerConfig} rejects the sizes, or if the
	 *                                  head dimension is odd
	 */
	public CausalLanguageModelConfig(int vocabSize, int seqLen, int dim, int heads, int depth, int ffDim) {
		super(dim, ffDim, depth, heads, heads, vocabSize, seqLen, false);

		if (headSize % 2 != 0) {
			throw new IllegalArgumentException("Full rotary embedding needs an even head dimension, not " +
					headSize);
		}
	}

	/**
	 * Returns the shape of a model input, {@code (seqLen)} token ids.
	 *
	 * @return the input shape
	 */
	public TraversalPolicy getInputShape() {
		return new TraversalPolicy(seqLen);
	}

	/**
	 * Returns the shape of a model output, {@code (seqLen, vocabSize)} log-probabilities.
	 *
	 * @return the output shape
	 */
	public TraversalPolicy getOutputShape() {
		return new TraversalPolicy(seqLen, vocabSize);
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
		Map<String, TraversalPolicy> shapes = new HashMap<>(getNormalWeightShapes());
		shapes.putAll(getScaleWeightShapes());
		shapes.put(CausalLanguageModel.INV_FREQ_KEY, getInvFreqShape());
		return shapes;
	}

	/**
	 * Returns the shape of the rotary inverse frequencies, {@code (headSize / 2)}.
	 *
	 * @return the shape
	 */
	private TraversalPolicy getInvFreqShape() {
		return new TraversalPolicy(headSize / 2);
	}

	/**
	 * Checks that {@code weights} holds every weight of this configuration under its key with
	 * the shape given by {@link #getWeightShapes()}. Entries the configuration does not declare
	 * are ignored.
	 *
	 * @param weights the weights to check
	 * @throws IllegalArgumentException if {@code weights} is null, or if a weight is missing or
	 *                                  has the wrong shape
	 */
	public void requireWeights(StateDictionary weights) {
		if (weights == null) {
			throw new IllegalArgumentException("weights must not be null");
		}

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
	 * Returns the shapes of the embedding and projection weights, which are initialized from a
	 * normal distribution.
	 *
	 * @return the shapes, by key
	 */
	private Map<String, TraversalPolicy> getNormalWeightShapes() {
		Map<String, TraversalPolicy> shapes = new HashMap<>();
		shapes.put(CausalLanguageModel.EMBEDDING_KEY, new TraversalPolicy(vocabSize, dim));
		shapes.put(CausalLanguageModel.OUTPUT_KEY, new TraversalPolicy(vocabSize, dim));

		for (int i = 0; i < layerCount; i++) {
			shapes.put(layerKey(i, "qkv"), new TraversalPolicy(3 * dim, dim));
			shapes.put(layerKey(i, "wo"), new TraversalPolicy(dim, dim));
			shapes.put(layerKey(i, "w1"), new TraversalPolicy(2 * hiddenDim, dim));
			shapes.put(layerKey(i, "w2"), new TraversalPolicy(dim, hiddenDim));
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
		shapes.put(CausalLanguageModel.FINAL_NORM_KEY, new TraversalPolicy(dim));

		for (int i = 0; i < layerCount; i++) {
			shapes.put(layerKey(i, "attention_norm"), new TraversalPolicy(dim));
			shapes.put(layerKey(i, "ffn_norm"), new TraversalPolicy(dim));
		}

		return shapes;
	}

	/**
	 * Creates freshly initialized weights for this configuration. Embedding and projection
	 * weights are drawn, in key order, from a normal distribution with mean 0 and standard
	 * deviation {@link CausalLanguageModel#INIT_STD} using {@code random}, normalization scales
	 * are ones, and the rotary inverse frequencies are those of full rotary embedding with base
	 * {@code ropeBase}.
	 *
	 * @param ropeBase rotary embedding base frequency
	 * @param random   the source of the initial weights
	 * @return the weights, under this configuration's keys
	 * @throws IllegalArgumentException if {@code ropeBase} is not a finite positive number
	 */
	public StateDictionary createWeights(double ropeBase, Random random) {
		CollectionProducer invFreqValues = OPS.computeInvFreq(headSize, ropeBase);
		StateDictionary weights = new StateDictionary(new HashMap<>());

		getNormalWeightShapes().entrySet().stream()
				.sorted(Map.Entry.comparingByKey())
				.forEach(entry -> {
					PackedCollection weight = new PackedCollection(entry.getValue());
					Destroyable.runOnce(OPS.a(OPS.cp(weight.each()),
							OPS.randn(weight.getShape(), 0.0, CausalLanguageModel.INIT_STD, random).each()).get());
					weights.put(entry.getKey(), weight);
				});

		getScaleWeightShapes().forEach((key, shape) -> weights.put(key, new PackedCollection(shape).fill(1.0)));

		PackedCollection invFreq = new PackedCollection(getInvFreqShape());
		Destroyable.runOnce(OPS.a(OPS.cp(invFreq.each()), invFreqValues.each()).get());
		weights.put(CausalLanguageModel.INV_FREQ_KEY, invFreq);
		return weights;
	}

	/**
	 * Returns the number of trainable parameters: every weight of
	 * {@link #getWeightShapes()} except the rotary frequencies.
	 *
	 * @return the parameter count
	 */
	public long getParameterCount() {
		return getWeightShapes().entrySet().stream()
				.filter(entry -> !CausalLanguageModel.INV_FREQ_KEY.equals(entry.getKey()))
				.mapToLong(entry -> entry.getValue().getTotalSize())
				.sum();
	}
}
