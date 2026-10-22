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

package org.almostrealism.ml.dsl;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.ml.RotationFeatures;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Pins the decomposed {@code transformer_block} fixture of {@code /pdsl/test_layers.pdsl} against
 * the Java assembly it stands in for. The fixture calls the decomposed {@code attention} layer of
 * {@code /pdsl/attention.pdsl} (imported by the fixture), which replaced the {@code attention}
 * built-in the fixture used to call; the built-in delegated to {@link AttentionFeatures#attention}.
 *
 * <p>The reference assembles the same block in Java statement for statement: a {@code (1, DIM)}
 * {@link SequentialBlock} holding a residual multi-head attention block (the eight-argument
 * {@link AttentionFeatures#attention} overload, whose RMSNorm epsilon is the {@code 1e-5} the
 * fixture writes as a literal) and a residual SwiGLU feed-forward block. Both run {@link #STEPS}
 * forward passes through one compiled block so the key/value caches written by earlier passes are
 * read by later ones, and the fixture must agree with the reference at every position.</p>
 */
public class TransformerBlockFixtureParityTest extends TestSuiteBase implements AttentionFeatures {

	/** Number of query heads, as the fixture hardcodes. */
	private static final int HEADS = 4;

	/** Elements per head, as the fixture hardcodes ({@code DIM / HEADS}). */
	private static final int HEAD_SIZE = 16;

	/** Model dimension, as the fixture hardcodes. */
	private static final int DIM = HEADS * HEAD_SIZE;

	/** Hidden dimension of the feed-forward network. */
	private static final int HIDDEN_DIM = 128;

	/** Rows of the key/value caches and of the rotary table, as the fixture hardcodes. */
	private static final int SEQ_LEN = 32;

	/** Forward passes run through one compiled block. */
	private static final int STEPS = 3;

	/** RoPE base frequency of the rotary table. */
	private static final double THETA = 10000.0;

	/** RMSNorm epsilon of the feed-forward stage (the fixture's {@code epsilon} parameter). */
	private static final double EPSILON = 1e-6;

	/** Largest element-wise difference accepted between the fixture and the reference. */
	private static final double TOLERANCE = 1e-5;

	/**
	 * The decomposed fixture reproduces the Java assembly at every position of every forward pass.
	 */
	@Test(timeout = 600000)
	public void decomposedFixtureMatchesBuiltinAssembly() {
		Weights w = new Weights(21);

		double[][] reference = run(javaAssembly(w), w);
		double[][] fixture = run(fixture(w), w);

		for (int step = 0; step < STEPS; step++) {
			Assert.assertEquals("output length at position " + step, reference[step].length, fixture[step].length);
			for (int i = 0; i < reference[step].length; i++) {
				Assert.assertEquals("position " + step + ", element " + i,
						reference[step][i], fixture[step][i], TOLERANCE);
			}
		}
	}

	/**
	 * The layer as the {@code attention} built-in and {@link AttentionFeatures} assembled it: a
	 * residual multi-head attention block followed by a residual SwiGLU feed-forward block.
	 */
	private Block javaAssembly(Weights w) {
		SequentialBlock transformer = new SequentialBlock(shape(1, DIM));
		transformer.accum(attention(HEADS, w.rmsAtt, w.wk, w.wv, w.wq, w.wo, cp(w.freqCis), p(w.position)));
		transformer.accum(feedForward(w.rmsFfn, w.w1, w.w2, w.w3, EPSILON));
		return transformer;
	}

	/** The {@code transformer_block} layer of {@code /pdsl/test_layers.pdsl}, built from its arguments. */
	private Block fixture(Weights w) {
		Map<String, Object> args = new HashMap<>();
		args.put("heads", HEADS);
		args.put("kv_heads", HEADS);
		args.put("rms_att_weight", w.rmsAtt);
		args.put("wq", w.wq);
		args.put("wk", w.wk);
		args.put("wv", w.wv);
		args.put("wo", w.wo);
		args.put("freq_cis", w.freqCis);
		args.put("position", w.position);
		args.put("rms_ffn_weight", w.rmsFfn);
		args.put("w1", w.w1);
		args.put("w2", w.w2);
		args.put("w3", w.w3);
		args.put("epsilon", EPSILON);

		PdslLoader loader = new PdslLoader();
		return loader.buildLayer(loader.parseResource("/pdsl/test_layers.pdsl"),
				"transformer_block", shape(1, DIM), args);
	}

	/**
	 * Compiles {@code block} and runs {@link #STEPS} forward passes, writing each pass's position
	 * before it, and returns the output of every pass.
	 */
	private double[][] run(Block block, Weights w) {
		Model model = new Model(shape(1, DIM));
		model.add(block);
		CompiledModel compiled = model.compile();

		double[][] outputs = new double[STEPS][];
		for (int step = 0; step < STEPS; step++) {
			w.position.fill(step);
			outputs[step] = compiled.forward(w.input(step)).toArray();
		}
		return outputs;
	}

	/**
	 * The seeded weights, position and inputs shared by the fixture and the reference. Tensors are
	 * drawn on the device from a {@link Random} with a fixed seed, in declaration order, so both
	 * builds bind the same values.
	 */
	private final class Weights {
		/** The cache row written and the causal limit of the current pass. */
		final PackedCollection position = new PackedCollection(shape(1));
		/** Rotary table shared by every head, {@code [SEQ_LEN, HEAD_SIZE / 2, 2]}. */
		final PackedCollection freqCis;
		/** Draws every random tensor, in declaration order. */
		private final Random random;
		/** Pre-attention RMSNorm scale {@code [DIM]}. */
		final PackedCollection rmsAtt;
		/** Query projection {@code [DIM, DIM]}. */
		final PackedCollection wq;
		/** Key projection {@code [DIM, DIM]}. */
		final PackedCollection wk;
		/** Value projection {@code [DIM, DIM]}. */
		final PackedCollection wv;
		/** Attention output projection {@code [DIM, DIM]}. */
		final PackedCollection wo;
		/** Pre-FFN RMSNorm scale {@code [DIM]}. */
		final PackedCollection rmsFfn;
		/** FFN gate projection {@code [HIDDEN_DIM, DIM]}. */
		final PackedCollection w1;
		/** FFN down projection {@code [DIM, HIDDEN_DIM]}. */
		final PackedCollection w2;
		/** FFN up projection {@code [HIDDEN_DIM, DIM]}. */
		final PackedCollection w3;

		/**
		 * Draws the tensors of the configuration.
		 *
		 * @param seed seed of the random tensors
		 */
		Weights(long seed) {
			this.freqCis = RotationFeatures.computeRopeFreqs(THETA, HEAD_SIZE, SEQ_LEN).evaluate();
			this.random = new Random(seed);
			this.rmsAtt = draw(0.6, 1.4, DIM);
			this.wq = draw(-0.4, 0.4, DIM, DIM);
			this.wk = draw(-0.4, 0.4, DIM, DIM);
			this.wv = draw(-0.4, 0.4, DIM, DIM);
			this.wo = draw(-0.4, 0.4, DIM, DIM);
			this.rmsFfn = draw(0.6, 1.4, DIM);
			this.w1 = draw(-0.4, 0.4, HIDDEN_DIM, DIM);
			this.w2 = draw(-0.4, 0.4, DIM, HIDDEN_DIM);
			this.w3 = draw(-0.4, 0.4, HIDDEN_DIM, DIM);
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
			TraversalPolicy shape = shape(dims);
			return rand(shape, random).multiply(max - min).add(min).reshape(shape).evaluate();
		}
	}
}
