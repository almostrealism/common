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

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.midi.HeadGroupConfig;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.Map;
import java.util.Random;

/**
 * Pins the pre-norm transformer layer described by {@code /pdsl/transformer.pdsl}: a residual
 * attention stage followed by a residual SwiGLU feed-forward stage, one token per forward pass.
 *
 * <p>The layer used to be assembled in Java by {@link AttentionFeatures#transformer}, as a
 * {@link SequentialBlock} with {@code accum(attention(...))} and {@code accum(feedForward(...))}.
 * Two independent pins hold the asset to that behavior:</p>
 * <ul>
 *   <li>{@link #javaAssembly} rebuilds that assembly here, statement for statement, and
 *       {@link #assetMatchesJavaAssembly} requires the asset layers to agree with it.</li>
 *   <li>The {@code *_GOLDEN} tables were captured from the Java assembly in
 *       {@link AttentionFeatures#transformer} as it stood on master at commit 74eb83317, by
 *       running the {@link LayerWeights} configurations below through a {@code (1, 16)} model
 *       before that assembly was replaced by the asset (the attention and feed-forward assets it
 *       composed were those of master). {@link #transformerMatchesMasterGoldenValues} requires
 *       every public {@code transformer(...)} overload to reproduce them.</li>
 * </ul>
 *
 * <p>Every configuration runs {@link #STEPS} forward passes through one compiled block, so the
 * key/value caches written by earlier passes are read by later ones, and all but one use
 * grouped-query attention (4 query heads over 2 key/value heads) so the key/value expansion is
 * exercised. The attention and feed-forward stages are pinned on their own, against host
 * references, by {@link AttentionAssetTest} and {@link FeedForwardAssetTest}.</p>
 */
public class TransformerAssetTest extends TestSuiteBase implements AttentionFeatures {

	/** Number of query heads in every configuration. */
	private static final int HEADS = 4;

	/** Elements per head. */
	private static final int HEAD_SIZE = 4;

	/** Model dimension. */
	private static final int DIM = HEADS * HEAD_SIZE;

	/** Hidden dimension of the feed-forward network. */
	private static final int HIDDEN_DIM = 24;

	/** Rows of the key/value caches and of the rotary tables. */
	private static final int SEQ_LEN = 4;

	/** Forward passes per configuration. */
	private static final int STEPS = 3;

	/** RoPE base frequency of the standard rotary table and of the first head group. */
	private static final double THETA = 10000.0;

	/** Largest element-wise difference accepted between two outputs. */
	private static final double TOLERANCE = 1e-5;

	/** Master output of the multi-head configuration without biases (the Llama 2 overload). */
	private static final double[][] MULTI_HEAD_GOLDEN = {
		{ 1.6403058, -1.5219854, -2.1032004, -2.0392067, -1.6491554, 0.7063099, 3.7256150, 1.5891266,
			1.0761018, -2.1432142, -2.4423037, -2.7352586, -1.6459200, 2.8466246, 2.9141352, 0.0397148 },
		{ -0.3544898, -0.3292982, -0.2429720, -0.2156337, 1.1336892, 2.5945230, 1.5653396, -1.7156372,
			-2.8122194, -1.8975599, -0.4895602, 2.3384511, 1.5882075, 0.6865696, -3.3599153, -4.6710253 },
		{ -0.4536927, 0.2105194, 1.8255107, 0.7684571, 0.5122696, -1.3698217, -2.1087339, -1.8365691,
			0.5737191, 3.0356030, 4.3830695, 1.8932123, -1.9108148, -4.2645326, -4.9715767, -0.0916076 }
	};

	/** Master output of the grouped-query configuration with projection biases. */
	private static final double[][] BIASED_GOLDEN = {
		{ 0.8109682, 2.5791588, -0.8807812, -0.7035209, -1.5195249, -0.8403312, 2.1967154, 3.9349561,
			0.1799672, -2.2546065, -3.8284802, -3.0531561, 1.3171849, 4.2195010, 3.5255251, 2.0955851 },
		{ 0.3065400, 0.4900495, -1.1082282, 1.2940246, 2.1052089, 3.7726848, -0.5330222, -2.1701751,
			-3.1633439, -2.2042270, -1.6587179, 3.7571607, 3.6649990, 1.0412720, -1.2169598, -5.5740290 },
		{ -0.1727054, -0.2245835, 1.4774065, 1.5775830, 2.0726404, -1.0609877, -1.5782030, -2.6117375,
			-0.5342742, 1.9925755, 3.4810905, 2.3132157, -0.8166100, -4.1999874, -4.5816092, -0.4661961 }
	};

	/** Master output of the grouped-query configuration with biases and QK-norm (Qwen3). */
	private static final double[][] QK_NORM_GOLDEN = {
		{ -0.0387875, 1.1033916, -3.0900090, -0.0909261, -1.2148441, 2.1083579, 2.8235862, 3.9660773,
			0.3807235, -1.6052730, -5.0773320, -3.2024918, -1.7927506, 5.5023522, 3.9331119, -0.4335030 },
		{ -0.7657506, -2.0060639, -0.4942234, 1.5034451, 2.2086079, 2.6386621, -0.3196551, -2.3029060,
			-3.1723495, -1.9765792, 0.5692229, 2.8401701, 5.2201405, 3.5924878, -1.9591693, -5.4292827 },
		{ -0.8785619, -0.7461710, 0.5858433, 2.0888200, 0.9501908, -0.5182575, -2.6235020, -1.7210281,
			-0.8609169, 3.2618241, 2.7935965, 2.5843892, -0.4236198, -3.0139396, -4.6871676, 0.4046968 }
	};

	/** Master output of the grouped-query configuration with per-head-group rotation. */
	private static final double[][] HEAD_GROUPS_GOLDEN = {
		{ 1.5266850, 0.7905061, -0.1724797, -1.1328714, -0.9938816, -1.0343029, 2.0891902, 1.9562180,
			0.6393076, -3.2828643, -2.7630179, -3.1446927, -0.0907812, 3.6975145, 3.8307941, 2.4717891 },
		{ -1.1570127, -0.9602233, -1.4213120, 0.7571940, 3.3819668, 1.7422862, 3.8740826, -0.2272561,
			-3.3973467, -1.0400399, 0.2553874, -0.2574699, 4.6451392, 0.4439442, -4.4338365, -4.3437510 },
		{ -0.3167316, -1.0546858, 2.0735457, 1.1893432, 1.7580314, -1.1871701, -1.6522856, -3.0041623,
			-1.6672988, 2.4699895, 3.5759058, 1.2647078, -3.6785483, -7.0066223, -3.8856070, -0.6395237 }
	};

	/**
	 * Each layer of the asset, built through {@link #transformerLayer}, agrees with the Java
	 * assembly it replaced at every position of every configuration.
	 */
	@Test(timeout = 600000)
	public void assetMatchesJavaAssembly() {
		for (LayerWeights w : configurations()) {
			double[][] java = run(javaAssembly(w), w);
			double[][] asset = run(asset(w), w);
			for (int step = 0; step < STEPS; step++) {
				assertClose(w.label + " asset vs Java assembly, position " + step, java[step], asset[step]);
			}
		}
	}

	/**
	 * Every public {@code transformer(...)} overload reproduces the values the Java assembly
	 * produced on master.
	 */
	@Test(timeout = 600000)
	public void transformerMatchesMasterGoldenValues() {
		LayerWeights[] configurations = configurations();
		double[][][] actual = new double[configurations.length][][];
		for (int c = 0; c < configurations.length; c++) {
			actual[c] = run(production(configurations[c]), configurations[c]);
		}

		double[][][] golden = { MULTI_HEAD_GOLDEN, BIASED_GOLDEN, QK_NORM_GOLDEN, HEAD_GROUPS_GOLDEN };
		for (int c = 0; c < configurations.length; c++) {
			for (int step = 0; step < STEPS; step++) {
				assertClose(configurations[c].label + " golden, position " + step,
						golden[c][step], actual[c][step]);
			}
		}
	}

	/**
	 * The four configurations, one per public {@code transformer(...)} overload: multi-head
	 * attention without biases, then grouped-query attention with biases, with biases and
	 * QK-norm, and with per-head-group rotation.
	 */
	private LayerWeights[] configurations() {
		return new LayerWeights[] {
				new LayerWeights("multi-head", HEADS, false, false, false, 1e-5, 11),
				new LayerWeights("biased", 2, true, false, false, 1e-5, 12),
				new LayerWeights("qk-norm", 2, true, true, false, 1e-6, 13),
				new LayerWeights("head-groups", 2, false, false, true, 1e-5, 14)
		};
	}

	/**
	 * The layer as {@link AttentionFeatures#transformer} assembled it in Java before the asset
	 * existed: a {@code (1, dim)} {@link SequentialBlock} holding a residual attention block and
	 * a residual feed-forward block.
	 */
	private Block javaAssembly(LayerWeights w) {
		SequentialBlock transformer = new SequentialBlock(shape(1, DIM));
		if (w.headGroups) {
			transformer.accum(attention(HEADS, w.kvHeads, w.rmsAtt, w.wk, w.wv, w.wq, w.wo,
					w.headGroups(), p(w.position), w.epsilon));
		} else {
			transformer.accum(attention(HEADS, w.kvHeads, w.rmsAtt, w.wk, w.wv, w.wq, w.wo,
					w.bk, w.bv, w.bq, w.qkNormQ, w.qkNormK, cp(w.freqCis), p(w.position), w.epsilon));
		}
		transformer.accum(feedForward(w.rmsFfn, w.w1, w.w2, w.w3, w.epsilon));
		return transformer;
	}

	/** The layer of {@code /pdsl/transformer.pdsl} that holds the configuration's attention stage. */
	private Block asset(LayerWeights w) {
		Map<String, Object> attentionArgs;
		String layer;
		if (w.headGroups) {
			layer = "transformer_mra";
			attentionArgs = attentionArguments(HEADS, w.kvHeads, w.rmsAtt, w.wk, w.wv, w.wq, w.wo,
					w.headGroups(), p(w.position), w.epsilon);
		} else {
			layer = w.qkNormQ == null ? "transformer" : "transformer_qk_norm";
			attentionArgs = attentionArguments(HEADS, w.kvHeads, w.rmsAtt, w.wk, w.wv, w.wq, w.wo,
					w.bk, w.bv, w.bq, w.qkNormQ, w.qkNormK, cp(w.freqCis), p(w.position), w.epsilon);
		}
		return transformerLayer(layer, attentionArgs, w.rmsFfn, w.w1, w.w2, w.w3);
	}

	/** The layer built by the public {@code transformer(...)} overload the configuration exercises. */
	private Block production(LayerWeights w) {
		if (w.headGroups) {
			return transformer(HEADS, w.kvHeads, w.rmsAtt, w.wk, w.wv, w.wq, w.wo, w.headGroups(),
					w.rmsFfn, w.w1, w.w2, w.w3, p(w.position), w.epsilon);
		} else if (w.qkNormQ != null) {
			return transformer(HEADS, w.kvHeads, w.rmsAtt, w.wk, w.wv, w.wq, w.wo, w.bk, w.bv, w.bq,
					w.qkNormQ, w.qkNormK, cp(w.freqCis), w.rmsFfn, w.w1, w.w2, w.w3, p(w.position), w.epsilon);
		} else if (w.bq != null) {
			return transformer(HEADS, w.kvHeads, w.rmsAtt, w.wk, w.wv, w.wq, w.wo, w.bk, w.bv, w.bq,
					null, null, cp(w.freqCis), w.rmsFfn, w.w1, w.w2, w.w3, p(w.position));
		}
		return transformer(HEADS, w.rmsAtt, w.wk, w.wv, w.wq, w.wo, cp(w.freqCis),
				w.rmsFfn, w.w1, w.w2, w.w3, p(w.position));
	}

	/**
	 * Compiles {@code block} and runs {@link #STEPS} forward passes, advancing the positions
	 * before each, returning the output of every pass.
	 */
	private double[][] run(Block block, LayerWeights w) {
		Model model = new Model(shape(1, DIM));
		model.add(block);
		CompiledModel compiled = model.compile();

		double[][] outputs = new double[STEPS][];
		for (int step = 0; step < STEPS; step++) {
			w.advance(step);
			outputs[step] = compiled.forward(w.input(step)).toArray();
			log(w.label + " position " + step + " = " + arrayLiteral(outputs[step]));
		}
		return outputs;
	}

	/** Requires {@code actual} to hold {@code expected} element for element, within {@link #TOLERANCE}. */
	private static void assertClose(String label, double[] expected, double[] actual) {
		Assert.assertEquals(label + ": length", expected.length, actual.length);
		for (int i = 0; i < expected.length; i++) {
			Assert.assertEquals(label + ": element " + i, expected[i], actual[i], TOLERANCE);
		}
	}

	/** Renders {@code values} as the Java array initializer the golden tables are written in. */
	private static String arrayLiteral(double[] values) {
		StringBuilder literal = new StringBuilder("{");
		for (int i = 0; i < values.length; i++) {
			literal.append(i == 0 ? " " : ", ").append(String.format("%.7f", values[i]));
		}
		return literal.append(" },").toString();
	}

	/**
	 * The seeded weights, positions and inputs of one configuration. Tensors are drawn on the
	 * device from a {@link Random} with a fixed seed, in declaration order, so every build of a
	 * configuration binds the same values.
	 */
	private final class LayerWeights {
		/** Name of the configuration in failure messages and logs. */
		final String label;
		/** Number of key/value heads. */
		final int kvHeads;
		/** Whether the attention stage rotates per head group rather than with one table. */
		final boolean headGroups;
		/** RMSNorm epsilon of both stages. */
		final double epsilon;
		/** The cache row written and the causal limit of the current pass. */
		final PackedCollection position = new PackedCollection(shape(1));
		/** The rotary position of each head group in the head-group configuration. */
		final PackedCollection[] groupPositions = {
				new PackedCollection(shape(1)), new PackedCollection(shape(1)) };
		/** Rotary table shared by every head, {@code [SEQ_LEN, HEAD_SIZE / 2, 2]}. */
		final PackedCollection freqCis;
		/** Draws every random tensor, in declaration order. */
		private final Random random;
		/** Pre-attention RMSNorm scale {@code [DIM]}. */
		final PackedCollection rmsAtt;
		/** Query projection {@code [DIM, DIM]}. */
		final PackedCollection wq;
		/** Key projection {@code [kvHeads * HEAD_SIZE, DIM]}. */
		final PackedCollection wk;
		/** Value projection {@code [kvHeads * HEAD_SIZE, DIM]}. */
		final PackedCollection wv;
		/** Attention output projection {@code [DIM, DIM]}. */
		final PackedCollection wo;
		/** Query bias, or {@code null}. */
		final PackedCollection bq;
		/** Key bias, or {@code null}. */
		final PackedCollection bk;
		/** Value bias, or {@code null}. */
		final PackedCollection bv;
		/** Per-head query QK-norm scale {@code [HEADS, HEAD_SIZE]}, or {@code null}. */
		final PackedCollection qkNormQ;
		/** Per-head key QK-norm scale {@code [kvHeads, HEAD_SIZE]}, or {@code null}. */
		final PackedCollection qkNormK;
		/** Pre-FFN RMSNorm scale {@code [DIM]}. */
		final PackedCollection rmsFfn;
		/** FFN gate projection {@code [HIDDEN_DIM, DIM]}. */
		final PackedCollection w1;
		/** FFN down projection {@code [DIM, HIDDEN_DIM]}. */
		final PackedCollection w2;
		/** FFN up projection {@code [HIDDEN_DIM, DIM]}. */
		final PackedCollection w3;

		/**
		 * Draws the tensors of one configuration.
		 *
		 * @param label      name of the configuration
		 * @param kvHeads    number of key/value heads
		 * @param bias       whether the query, key and value projections carry biases
		 * @param qkNorm     whether the attention stage normalizes queries and keys per head
		 * @param headGroups whether the attention stage rotates per head group
		 * @param epsilon    RMSNorm epsilon
		 * @param seed       seed of the random tensors
		 */
		LayerWeights(String label, int kvHeads, boolean bias, boolean qkNorm, boolean headGroups,
					 double epsilon, long seed) {
			this.label = label;
			this.kvHeads = kvHeads;
			this.headGroups = headGroups;
			this.epsilon = epsilon;
			this.freqCis = RotationFeatures.computeRopeFreqs(THETA, HEAD_SIZE, SEQ_LEN).evaluate();
			this.random = new Random(seed);

			int kvDim = kvHeads * HEAD_SIZE;
			this.rmsAtt = draw(0.6, 1.4, DIM);
			this.wq = draw(-0.4, 0.4, DIM, DIM);
			this.wk = draw(-0.4, 0.4, kvDim, DIM);
			this.wv = draw(-0.4, 0.4, kvDim, DIM);
			this.wo = draw(-0.4, 0.4, DIM, DIM);
			this.bq = bias ? draw(-0.2, 0.2, DIM) : null;
			this.bk = bias ? draw(-0.2, 0.2, kvDim) : null;
			this.bv = bias ? draw(-0.2, 0.2, kvDim) : null;
			this.qkNormQ = qkNorm ? draw(0.6, 1.4, HEADS, HEAD_SIZE) : null;
			this.qkNormK = qkNorm ? draw(0.6, 1.4, kvHeads, HEAD_SIZE) : null;
			this.rmsFfn = draw(0.6, 1.4, DIM);
			this.w1 = draw(-0.4, 0.4, HIDDEN_DIM, DIM);
			this.w2 = draw(-0.4, 0.4, DIM, HIDDEN_DIM);
			this.w3 = draw(-0.4, 0.4, HIDDEN_DIM, DIM);
		}

		/**
		 * Two head groups of two query heads each, rotated with base frequencies {@link #THETA}
		 * and 500 at their own positions (see {@link #advance}).
		 */
		HeadGroupConfig[] headGroups() {
			Producer<PackedCollection>[] positions = new Producer[] { p(groupPositions[0]), p(groupPositions[1]) };
			return HeadGroupConfig.fromParams(new double[] { THETA, 500.0 }, HEAD_SIZE, SEQ_LEN,
					new int[] { 2, 2 }, positions);
		}

		/**
		 * Sets the positions of pass {@code step}: the cache position is the step, the first head
		 * group trails it at half the rate and the second leads it by one row.
		 */
		void advance(int step) {
			int leading = Math.min(SEQ_LEN - 1, step + 1);
			position.fill(step);
			groupPositions[0].fill(step / 2);
			groupPositions[1].fill(leading);
		}

		/**
		 * The token vector of pass {@code step}, produced on the device: element {@code i} is
		 * {@code (1 + i / 4) cos(0.9 i + 0.3 + 1.7 step)}, so later elements carry larger values.
		 */
		PackedCollection input(int step) {
			CollectionProducer index = integers(0, DIM);
			return cos(index.multiply(0.9).add(0.3 + 1.7 * step))
					.multiply(index.multiply(0.25).add(1.0))
					.reshape(shape(1, DIM)).evaluate();
		}

		/** A tensor of the given dimensions drawn uniformly from {@code [min, max)}. */
		private PackedCollection draw(double min, double max, int... dims) {
			return rand(shape(dims), random).multiply(max - min).add(min).reshape(shape(dims)).evaluate();
		}
	}
}
