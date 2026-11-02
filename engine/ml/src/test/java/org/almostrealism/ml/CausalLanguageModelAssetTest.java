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
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.layers.NormalizationType;
import org.almostrealism.layers.ParameterUpdate;
import org.almostrealism.layers.ProjectionFactory;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.ModelTestFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;

/**
 * Pins the two forward passes of {@link CausalLanguageModel} that {@code /pdsl/causal_lm.pdsl}
 * describes: the full-window pass the model trains with, from {@code (seqLen)} token ids to
 * {@code (seqLen, vocab)} log-probabilities, and the single-position pass the key/value-cache
 * generator decodes with, from one token's {@code (1, dim)} embedding to its {@code (1, vocab)}
 * log-probabilities.
 *
 * <p>Both passes used to be assembled in Java by {@code CausalLanguageModel}: the full window by
 * {@code addLayers()}, which added the token embedding, one
 * {@link TransformerBlockFeatures#transformerBlock} per block and the final normalization, output
 * projection and log-softmax, and the single position by {@code decodeModel()}, which added a
 * residual {@link AttentionFeatures#attention cached attention} and a residual
 * {@link FeedForwardFeatures#gatedLinearFeedForward gated feed-forward} per block, and then the same
 * head. Three independent pins hold the asset to that behavior:</p>
 * <ul>
 *   <li>{@link #javaWindow} and
 *       {@link #javaDecode(CausalLanguageModelConfig, StateDictionary, PackedCollection, List, List)}
 *       rebuild those assemblies here, statement for statement, over the same seeded weights, and the
 *       model built from the asset must agree with them, in its outputs and, for the window, in the
 *       gradient the backward pass delivers to every weight. Before the model was switched to the
 *       asset, the same comparisons were run against the asset's layers bound directly, with the Java
 *       assembly still in place.</li>
 *   <li>{@link #GOLDEN_WINDOW} and {@link #GOLDEN_DECODE} were captured from
 *       {@link CausalLanguageModel} itself on master, before the asset existed, by running
 *       {@link #modelMatchesMasterGoldenWindow} and {@link #decodeModelMatchesMasterGoldenValues}
 *       over the {@link #weights seeded weights}; the model must still reproduce them.</li>
 *   <li>{@link #decodedRowsMatchTheWindow} requires the two passes of the model to agree with each
 *       other: the row a decode produces at each position is the row the full window produces at
 *       that position for the same tokens.</li>
 * </ul>
 *
 * <p>The configuration is small but exercises every stage: two blocks (so the per-block loop and
 * per-block weights are exercised), two heads, a feed-forward width that matches neither the model
 * dimension nor the window, and a window of {@link #SEQ_LEN} positions so the causal mask and the
 * caches written by earlier decode passes and read by later ones are exercised. The weights are drawn
 * larger than the model's own initialization so that the attention distributions are far from
 * uniform.</p>
 */
public class CausalLanguageModelAssetTest extends TestSuiteBase
		implements TransformerBlockFeatures, ModelTestFeatures {

	/** Vocabulary size. */
	private static final int VOCAB = 11;

	/** Positions per window. */
	private static final int SEQ_LEN = 4;

	/** Model dimension. */
	private static final int DIM = 8;

	/** Attention heads. */
	private static final int HEADS = 2;

	/** Transformer blocks. */
	private static final int DEPTH = 2;

	/** Hidden width of the gated feed-forward. */
	private static final int FF_DIM = 12;

	/** Rotary embedding base. */
	private static final double ROPE_BASE = 10000.0;

	/** Seed of the weights. */
	private static final long SEED = 2026L;

	/**
	 * The token ids of the window, and the tokens decoded one per position; {@link #ids()} writes the
	 * same ids out for the full window.
	 */
	private static final int[] TOKENS = { 3, 7, 1, 9 };

	/** Largest element-wise difference accepted between two computations of the same values. */
	private static final double TOLERANCE = 1e-5;

	/**
	 * Log-probabilities of the full window over {@link #TOKENS}, one row per position, captured from
	 * {@code CausalLanguageModel} on master ({@code addLayers()} as it stood, before the asset existed)
	 * over the {@link #weights} drawn with seed {@link #SEED}.
	 */
	private static final double[][] GOLDEN_WINDOW = {
		{ -3.4545438, -3.1183672, -3.4384234, -0.9565625, -3.0067163, -2.7259333, -4.5715489, -3.4686399,
			-2.1470010, -2.0764830, -2.2145948 },
		{ -1.6713692, -3.2789810, -1.6109595, -1.8502042, -2.5377595, -1.9483769, -2.9444091, -3.2808905,
			-3.1498227, -4.4169207, -2.9811323 },
		{ -1.8034432, -3.7556634, -1.8062303, -2.6848638, -2.3982279, -1.2800779, -2.3344259, -3.1149337,
			-4.7498965, -3.9874051, -3.1691508 },
		{ -1.6405594, -3.5941927, -1.7948890, -2.9787285, -2.6434300, -1.5040205, -2.4692922, -2.3279450,
			-4.2971830, -3.7077699, -3.0363288 }
	};

	/**
	 * Log-probabilities of each single-position decode over {@link #TOKENS}, one row per position,
	 * captured from {@code CausalLanguageModel.decodeModel} on master (as it stood, before the asset
	 * existed) over the {@link #weights} drawn with seed {@link #SEED}.
	 */
	private static final double[][] GOLDEN_DECODE = {
		{ -3.4545438, -3.1183672, -3.4384234, -0.9565626, -3.0067160, -2.7259333, -4.5715485, -3.4686399,
			-2.1470008, -2.0764828, -2.2145948 },
		{ -1.6713687, -3.2789812, -1.6109593, -1.8502047, -2.5377593, -1.9483767, -2.9444089, -3.2808902,
			-3.1498232, -4.4169211, -2.9811325 },
		{ -1.8034432, -3.7556634, -1.8062303, -2.6848638, -2.3982279, -1.2800779, -2.3344259, -3.1149337,
			-4.7498965, -3.9874048, -3.1691511 },
		{ -1.6405593, -3.5941930, -1.7948890, -2.9787285, -2.6434300, -1.5040203, -2.4692919, -2.3279450,
			-4.2971835, -3.7077696, -3.0363283 }
	};

	/**
	 * The model's full window, built from the asset, reproduces the Java assembly it replaced, with
	 * blocks and without (a model of depth zero is the embedding followed directly by the head).
	 */
	@Test(timeout = 600000)
	public void windowMatchesJavaAssembly() {
		for (int depth : new int[] { 0, DEPTH }) {
			CausalLanguageModelConfig config = config(depth);
			StateDictionary weights = weights(config);
			try {
				double[][] java = window(javaWindow(config, weights, ParameterUpdate.disabled()));
				double[][] asset = window(new CausalLanguageModel(config, weights));
				for (int p = 0; p < SEQ_LEN; p++) {
					assertClose("depth " + depth + " window position " + p, java[p], asset[p]);
				}
			} finally {
				weights.destroy();
			}
		}
	}

	/**
	 * The backward pass of the model's full window delivers to every weight the gradient the Java
	 * assembly delivered, for the same output gradient: the asset trains exactly as the assembly did.
	 */
	@Test(timeout = 900000)
	public void windowGradientsMatchJavaAssembly() {
		CausalLanguageModelConfig config = config(DEPTH);
		StateDictionary weights = weights(config);
		PackedCollection outputGradient = randn(shape(SEQ_LEN, VOCAB), 0.0, 1.0, new Random(7)).evaluate();
		try {
			Map<String, double[]> java = gradients(config, weights,
					update -> javaWindow(config, weights, update), outputGradient);
			Map<String, double[]> asset = gradients(config, weights, update -> {
				CausalLanguageModel lm = new CausalLanguageModel(config, weights);
				lm.setParameterUpdate(update);
				return lm;
			}, outputGradient);
			for (String key : java.keySet()) {
				assertClose("gradient of " + key, java.get(key), asset.get(key));
			}
		} finally {
			outputGradient.destroy();
			weights.destroy();
		}
	}

	/** The model's full window reproduces the values it produced on master. */
	@Test(timeout = 600000)
	public void modelMatchesMasterGoldenWindow() {
		CausalLanguageModelConfig config = config(DEPTH);
		StateDictionary weights = weights(config);
		try {
			double[][] rows = window(new CausalLanguageModel(config, weights));
			for (int p = 0; p < SEQ_LEN; p++) {
				log("window position " + p + " = " + Arrays.toString(rows[p]));
			}
			Assert.assertEquals("golden window positions", SEQ_LEN, GOLDEN_WINDOW.length);
			for (int p = 0; p < SEQ_LEN; p++) {
				assertClose("window position " + p, GOLDEN_WINDOW[p], rows[p]);
			}
		} finally {
			weights.destroy();
		}
	}

	/**
	 * The model's single-position decode, built from the asset, reproduces the Java assembly it
	 * replaced at every position, where each pass reads the keys and values the earlier passes cached.
	 */
	@Test(timeout = 600000)
	public void decodeMatchesJavaAssembly() {
		CausalLanguageModelConfig config = config(DEPTH);
		StateDictionary weights = weights(config);
		try {
			double[][] java = javaDecode(config, weights);
			double[][] asset = decodeModel(new CausalLanguageModel(config, weights), weights);
			for (int p = 0; p < SEQ_LEN; p++) {
				assertClose("decode position " + p, java[p], asset[p]);
			}
		} finally {
			weights.destroy();
		}
	}

	/** The model's single-position decode reproduces the values it produced on master. */
	@Test(timeout = 600000)
	public void decodeModelMatchesMasterGoldenValues() {
		CausalLanguageModelConfig config = config(DEPTH);
		StateDictionary weights = weights(config);
		try {
			double[][] rows = decodeModel(new CausalLanguageModel(config, weights), weights);
			for (int p = 0; p < SEQ_LEN; p++) {
				log("decode position " + p + " = " + Arrays.toString(rows[p]));
			}
			Assert.assertEquals("golden decode positions", SEQ_LEN, GOLDEN_DECODE.length);
			for (int p = 0; p < SEQ_LEN; p++) {
				assertClose("decode position " + p, GOLDEN_DECODE[p], rows[p]);
			}
		} finally {
			weights.destroy();
		}
	}

	/**
	 * The two passes of the model agree: decoding {@link #TOKENS} one position at a time through the
	 * key/value caches gives, at every position, the row the full window gives at that position.
	 */
	@Test(timeout = 600000)
	public void decodedRowsMatchTheWindow() {
		CausalLanguageModelConfig config = config(DEPTH);
		StateDictionary weights = weights(config);
		try {
			CausalLanguageModel lm = new CausalLanguageModel(config, weights);
			double[][] window = window(lm);
			double[][] decoded = decodeModel(lm, weights);
			for (int p = 0; p < SEQ_LEN; p++) {
				assertClose("position " + p, window[p], decoded[p]);
			}
		} finally {
			weights.destroy();
		}
	}

	/**
	 * The full window as {@code CausalLanguageModel.addLayers()} assembled it on master, statement for
	 * statement.
	 *
	 * @param config  the architecture
	 * @param weights the weights
	 * @param update  the parameter update of the model
	 * @return the uncompiled model
	 */
	private Model javaWindow(CausalLanguageModelConfig config, StateDictionary weights,
							 ParameterUpdate<PackedCollection> update) {
		Model model = new Model(config.getInputShape(), update);
		TraversalPolicy blockShape = shape(1, config.seqLen, config.dim);
		TraversalPolicy logitShape = shape(1, config.seqLen, config.vocabSize);

		model.add(embedding(config.getInputShape(), weights.get(CausalLanguageModel.EMBEDDING_KEY)));
		model.add(reshape(shape(config.seqLen, config.dim), blockShape));

		for (int i = 0; i < config.layerCount; i++) {
			model.add(transformerBlock(1, config.dim, config.seqLen, config.headCount, false, 0, null,
					weights.get(config.layerKey(i, "attention_norm")), null,
					weights.get(config.layerKey(i, "qkv")), weights.get(config.layerKey(i, "wo")),
					null, null, null, null,
					weights.get(CausalLanguageModel.INV_FREQ_KEY),
					null, null, null, null, null, null, null, null, null,
					weights.get(config.layerKey(i, "ffn_norm")), null,
					weights.get(config.layerKey(i, "w1")), weights.get(config.layerKey(i, "w2")), null, null,
					null, ProjectionFactory.dense(), AttentionVariant.STANDARD, null, null, null,
					NormalizationType.RMS, null, true));
		}

		model.add(norm(NormalizationType.RMS, weights.get(CausalLanguageModel.FINAL_NORM_KEY), null));
		model.add(dense(weights.get(CausalLanguageModel.OUTPUT_KEY)));
		model.add(reshape(logitShape, config.getOutputShape()));
		model.add(logSoftmax(config.getOutputShape()));
		return model;
	}

	/**
	 * The single-position pass as {@code CausalLanguageModel.decodeModel()} assembled it on master,
	 * statement for statement, over freshly cleared caches it appends to the given lists.
	 *
	 * @param config      the architecture
	 * @param weights     the weights
	 * @param position    the sequence position the attention reads
	 * @param keyCaches   receives the per-block key caches
	 * @param valueCaches receives the per-block value caches
	 * @return the uncompiled model
	 */
	private Model javaDecode(CausalLanguageModelConfig config, StateDictionary weights,
							 PackedCollection position,
							 List<PackedCollection> keyCaches, List<PackedCollection> valueCaches) {
		int dim = config.dim;
		CollectionProducer freqCis = computeRopeFreqs(config.seqLen, weights.get(CausalLanguageModel.INV_FREQ_KEY));
		TraversalPolicy tokenShape = shape(1, dim);
		TraversalPolicy ffnShape = shape(1, 1, dim);

		Model model = new Model(tokenShape);

		for (int i = 0; i < config.layerCount; i++) {
			PackedCollection qkv = weights.get(config.layerKey(i, "qkv"));
			PackedCollection wq = qkv.range(shape(dim, dim), 0);
			PackedCollection wk = qkv.range(shape(dim, dim), dim * dim);
			PackedCollection wv = qkv.range(shape(dim, dim), 2 * dim * dim);

			PackedCollection keyCache = cache(config, keyCaches);
			PackedCollection valueCache = cache(config, valueCaches);

			model.add(residual(attention(config.headCount, config.headCount,
					weights.get(config.layerKey(i, "attention_norm")),
					wk, wv, wq, weights.get(config.layerKey(i, "wo")),
					null, null, null, null, null,
					freqCis, p(position), 1e-5,
					keyCache, valueCache)));

			model.add(reshape(tokenShape, ffnShape));
			model.add(residual(gatedLinearFeedForward(ffnShape, NormalizationType.RMS,
					weights.get(config.layerKey(i, "ffn_norm")), null,
					weights.get(config.layerKey(i, "w1")), null,
					weights.get(config.layerKey(i, "w2")), null,
					null, null, silu(), ProjectionFactory.dense())));
			model.add(reshape(ffnShape, tokenShape));
		}

		model.add(norm(NormalizationType.RMS, weights.get(CausalLanguageModel.FINAL_NORM_KEY), null));
		model.add(dense(weights.get(CausalLanguageModel.OUTPUT_KEY)));
		model.add(logSoftmax(shape(1, config.vocabSize)));
		return model;
	}

	/**
	 * Allocates one cleared {@code (seqLen, dim)} cache and appends it to {@code caches}.
	 *
	 * @param config the architecture
	 * @param caches receives the cache
	 * @return the cache
	 */
	private PackedCollection cache(CausalLanguageModelConfig config, List<PackedCollection> caches) {
		PackedCollection cache = new PackedCollection(shape(config.seqLen, config.dim));
		cache.clear();
		caches.add(cache);
		return cache;
	}

	/**
	 * Decodes {@link #TOKENS} one position at a time through the Java assembly
	 * {@link #javaDecode(CausalLanguageModelConfig, StateDictionary, PackedCollection, List, List)}.
	 *
	 * @param config  the architecture
	 * @param weights the weights
	 * @return the log-probabilities of every position
	 */
	private double[][] javaDecode(CausalLanguageModelConfig config, StateDictionary weights) {
		PackedCollection position = new PackedCollection(1);
		List<PackedCollection> keyCaches = new ArrayList<>();
		List<PackedCollection> valueCaches = new ArrayList<>();
		try {
			return decode(javaDecode(config, weights, position, keyCaches, valueCaches), weights, position);
		} finally {
			position.destroy();
			keyCaches.forEach(PackedCollection::destroy);
			valueCaches.forEach(PackedCollection::destroy);
		}
	}

	/**
	 * Decodes {@link #TOKENS} one position at a time through the model's own
	 * {@link CausalLanguageModel#decodeModel decode variant}.
	 *
	 * @param lm      the model
	 * @param weights its weights
	 * @return the log-probabilities of every position
	 */
	private double[][] decodeModel(CausalLanguageModel lm, StateDictionary weights) {
		PackedCollection position = new PackedCollection(1);
		List<PackedCollection> keyCaches = new ArrayList<>();
		List<PackedCollection> valueCaches = new ArrayList<>();
		try {
			return decode(lm.decodeModel(position, keyCaches, valueCaches), weights, position);
		} finally {
			position.destroy();
			keyCaches.forEach(PackedCollection::destroy);
			valueCaches.forEach(PackedCollection::destroy);
		}
	}

	/**
	 * Compiles a single-position model and decodes {@link #TOKENS} through it, one forward pass per
	 * position, each fed the embedding row of its token.
	 *
	 * @param model    the uncompiled single-position model
	 * @param weights  the weights holding the token table
	 * @param position the position the model reads, advanced before each pass
	 * @return the log-probabilities of every position
	 */
	private double[][] decode(Model model, StateDictionary weights, PackedCollection position) {
		PackedCollection table = weights.get(CausalLanguageModel.EMBEDDING_KEY);
		PackedCollection input = new PackedCollection(shape(1, DIM));
		CompiledModel compiled = model.compile(false);
		try {
			double[][] rows = new double[SEQ_LEN][];
			for (int p = 0; p < SEQ_LEN; p++) {
				position.fill((double) p);
				input.setFrom(0, table.range(shape(DIM), TOKENS[p] * DIM), 0, DIM);
				rows[p] = compiled.forward(input).toArray(0, VOCAB);
			}
			return rows;
		} finally {
			Destroyable.destroy(compiled);
			input.destroy();
		}
	}

	/**
	 * Compiles a full-window model for inference and returns its log-probabilities for
	 * {@link #TOKENS}.
	 *
	 * @param model the uncompiled model
	 * @return the {@code VOCAB} log-probabilities of every position
	 */
	private double[][] window(Model model) {
		PackedCollection ids = ids();
		CompiledModel compiled = model.compile(false);
		try {
			PackedCollection output = compiled.forward(ids);
			double[][] rows = new double[SEQ_LEN][];
			for (int p = 0; p < SEQ_LEN; p++) {
				rows[p] = output.toArray(p * VOCAB, VOCAB);
			}
			return rows;
		} finally {
			Destroyable.destroy(compiled);
			ids.destroy();
		}
	}

	/**
	 * Runs one forward and one backward pass of a full-window model through a parameter update that
	 * records the gradient of every weight instead of applying it, and returns the gradients by weight
	 * key.
	 *
	 * @param config         the architecture
	 * @param weights        the weights
	 * @param build          builds the model with the recording update
	 * @param outputGradient the gradient of the loss with respect to the output
	 * @return the gradient of every trained weight, by key
	 */
	private Map<String, double[]> gradients(CausalLanguageModelConfig config, StateDictionary weights,
											Function<ParameterUpdate<PackedCollection>, Model> build,
											PackedCollection outputGradient) {
		List<Producer<PackedCollection>> wired = new ArrayList<>();
		List<PackedCollection> recorded = new ArrayList<>();
		ParameterUpdate<PackedCollection> recorder = gradientRecorder(recorded);
		ParameterUpdate<PackedCollection> update = (name, weight, gradient) -> {
			wired.add(weight);
			return recorder.apply(name, weight, gradient);
		};

		PackedCollection ids = ids();
		CompiledModel compiled = build.apply(update).compile(true);
		try {
			compiled.forward(ids);
			compiled.backward(outputGradient);

			Map<String, double[]> gradients = new HashMap<>();
			for (String key : config.getWeightShapes().keySet()) {
				if (CausalLanguageModel.INV_FREQ_KEY.equals(key)) continue;

				PackedCollection weight = weights.get(key);
				for (int i = 0; i < wired.size(); i++) {
					if (wired.get(i).get().evaluate().getMem() == weight.getMem()) {
						gradients.put(key, recorded.get(i).doubleStream().toArray());
					}
				}
				Assert.assertTrue("no gradient was delivered to " + key, gradients.containsKey(key));
			}
			return gradients;
		} finally {
			Destroyable.destroy(compiled);
			ids.destroy();
		}
	}

	/**
	 * The configuration of the given depth.
	 *
	 * @param depth the number of blocks
	 * @return the configuration
	 */
	private CausalLanguageModelConfig config(int depth) {
		return new CausalLanguageModelConfig(VOCAB, SEQ_LEN, DIM, HEADS, depth, FF_DIM);
	}

	/**
	 * Every weight of the configuration, drawn on the device from a {@link Random} seeded with
	 * {@link #SEED}, in key order: normalization scales uniformly from {@code [0.6, 1.4)}, the embedding
	 * table and projections uniformly from {@code [-0.5, 0.5)}, and the rotary inverse frequencies those
	 * of base {@link #ROPE_BASE}.
	 *
	 * @param config the architecture
	 * @return the weights, under the configuration's keys
	 */
	private StateDictionary weights(CausalLanguageModelConfig config) {
		Random random = new Random(SEED);
		Map<String, PackedCollection> weights = new HashMap<>();
		config.getWeightShapes().entrySet().stream()
				.sorted(Map.Entry.comparingByKey())
				.forEach(entry -> {
					TraversalPolicy weightShape = entry.getValue();
					if (CausalLanguageModel.INV_FREQ_KEY.equals(entry.getKey())) {
						weights.put(entry.getKey(),
								config.computeInvFreq(config.headSize, ROPE_BASE).evaluate().reshape(weightShape));
					} else if (entry.getKey().contains("norm")) {
						weights.put(entry.getKey(), draw(weightShape, 0.6, 1.4, random));
					} else {
						weights.put(entry.getKey(), draw(weightShape, -0.5, 0.5, random));
					}
				});
		return new StateDictionary(weights);
	}

	/**
	 * A tensor drawn uniformly from {@code [min, max)}.
	 *
	 * @param weightShape the shape
	 * @param min         the lower bound
	 * @param max         the upper bound
	 * @param random      the generator
	 * @return the tensor
	 */
	private PackedCollection draw(TraversalPolicy weightShape, double min, double max, Random random) {
		return rand(weightShape, random).multiply(max - min).add(min).reshape(weightShape).evaluate();
	}

	/**
	 * The window of token ids, {@link #TOKENS} in order, written out as literals.
	 *
	 * @return the {@code (SEQ_LEN)} ids
	 */
	private PackedCollection ids() {
		return PackedCollection.of(3, 7, 1, 9);
	}

	/**
	 * Requires {@code actual} to hold {@code expected} element for element, within {@link #TOLERANCE}
	 * of the larger of one and the expected magnitude.
	 *
	 * @param label    names the compared values in a failure
	 * @param expected the expected values
	 * @param actual   the actual values
	 */
	private static void assertClose(String label, double[] expected, double[] actual) {
		Assert.assertEquals(label + ": length", expected.length, actual.length);
		for (int i = 0; i < expected.length; i++) {
			Assert.assertEquals(label + ": element " + i, expected[i], actual[i],
					TOLERANCE * Math.max(1.0, Math.abs(expected[i])));
		}
	}
}
