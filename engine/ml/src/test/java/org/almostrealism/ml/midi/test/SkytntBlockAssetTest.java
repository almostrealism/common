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

package org.almostrealism.ml.midi.test;

import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.ml.RotationFeatures;
import org.almostrealism.ml.dsl.PdslLoader;
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
 * Pins the SkyTNT block described by {@code /pdsl/midi/skytnt_block.pdsl} after its migration from
 * the {@code attention} built-in to the decomposed {@code attention} layer of
 * {@code /pdsl/attention.pdsl}.
 *
 * <p>The block is a residual self-attention stage followed by a residual SwiGLU feed-forward
 * stage. Before the migration the attention stage was the {@code attention} built-in, which
 * allocated the key/value caches in Java and hid the attention structure; now
 * {@code skytnt_block.pdsl} imports {@code attention.pdsl} and calls its decomposed {@code attention}
 * layer, and the caller allocates the caches. The reference here is the pre-migration Java path
 * itself — a residual {@link AttentionFeatures#attention} (the public 8-argument overload the
 * built-in dispatched to) plus a residual
 * {@link org.almostrealism.ml.FeedForwardFeatures#feedForward}, assembled with {@code accum} — which
 * the attention and feed-forward assets are separately pinned against by
 * {@link org.almostrealism.ml.AttentionAssetTest} and {@link org.almostrealism.ml.FeedForwardAssetTest}:</p>
 * <ul>
 *   <li>{@link #skytntBlockMatchesMonolithReference} requires the migrated {@code skytnt_block} to
 *       reproduce that Java path element for element, at the shipped RMSNorm epsilon, across
 *       {@link #STEPS} forward passes so the key/value caches written by earlier passes are read by
 *       later ones.</li>
 *   <li>{@link #skytntBlockAppliesEpsilonToAttention} demonstrates the defect the migration fixes:
 *       the built-in path hardcodes the attention RMSNorm epsilon to {@code 1e-5} and ignores the
 *       block's {@code epsilon} argument for attention, while the migrated block forwards it. At the
 *       shipped epsilon the two agree; at any other epsilon they diverge, and the divergence is the
 *       attention stage because both paths use the same epsilon for the feed-forward stage.</li>
 * </ul>
 *
 * <p>SkyTNT attention is plain multi-head attention (key/value heads equal to query heads) with no
 * projection biases, so both configurations below exercise that shape at two head geometries.</p>
 */
public class SkytntBlockAssetTest extends TestSuiteBase implements AttentionFeatures {

	/** Model dimension shared by both configurations. */
	private static final int DIM = 16;

	/** Rows of the key/value caches and of the rotary table. */
	private static final int SEQ_LEN = 4;

	/** Forward passes per configuration, so a cache written by one pass is read by the next. */
	private static final int STEPS = 3;

	/** RoPE base frequency of the rotary table. */
	private static final double THETA = 10000.0;

	/** The RMSNorm epsilon SkyTNT ships with; both paths agree here. */
	private static final double SHIPPED_EPSILON = 1e-5;

	/** Largest element-wise difference accepted between two outputs that should agree. */
	private static final double TOLERANCE = 1e-5;

	/**
	 * The migrated {@code skytnt_block} reproduces the pre-migration Java path (residual
	 * {@code attention()} plus {@code feedForward()}) at the shipped epsilon, at every position of
	 * every configuration.
	 */
	@Test(timeout = 300000)
	public void skytntBlockMatchesMonolithReference() {
		for (BlockWeights w : configurations()) {
			double[][] reference = run(reference(w, SHIPPED_EPSILON), w);
			double[][] migrated = run(migrated(w, SHIPPED_EPSILON), w);
			for (int step = 0; step < STEPS; step++) {
				assertClose(w.label + " skytnt_block vs pre-migration Java path, position " + step,
						reference[step], migrated[step]);
			}
		}
	}

	/**
	 * The migrated block applies its {@code epsilon} argument to the attention RMSNorm, which the
	 * built-in path ignored (it hardcoded {@code 1e-5}). At the shipped epsilon the two paths agree;
	 * at an unusual epsilon they diverge, and only the attention stage can be responsible because
	 * both use the same epsilon for the feed-forward stage.
	 */
	@Test(timeout = 300000)
	public void skytntBlockAppliesEpsilonToAttention() {
		BlockWeights w = configurations()[0];
		double unusual = 3.0;

		double[][] referenceShipped = run(reference(w, SHIPPED_EPSILON), w);
		double[][] migratedShipped = run(migrated(w, SHIPPED_EPSILON), w);
		for (int step = 0; step < STEPS; step++) {
			assertClose("shipped-epsilon parity, position " + step,
					referenceShipped[step], migratedShipped[step]);
		}

		double[][] referenceUnusual = run(reference(w, unusual), w);
		double[][] migratedUnusual = run(migrated(w, unusual), w);
		double diff = maxAbsDiff(referenceUnusual[0], migratedUnusual[0]);
		log("attention-epsilon divergence at position 0 = " + diff);
		Assert.assertTrue("the migrated block must apply epsilon to the attention RMSNorm; the "
				+ "monolith path ignores it, so the two must diverge at epsilon=" + unusual
				+ " (max abs diff was " + diff + ")", diff > 1e-3);
	}

	/** The two head geometries: four heads of four elements, then two heads of eight. */
	private BlockWeights[] configurations() {
		return new BlockWeights[] {
				new BlockWeights("four-heads", 4, 24, 21),
				new BlockWeights("two-heads", 2, 16, 22)
		};
	}

	/**
	 * Builds the migrated {@code skytnt_block} of {@code /pdsl/midi/skytnt_block.pdsl}. The
	 * attention arguments (including the freshly allocated key/value caches and the head size) come
	 * from {@link AttentionFeatures#attentionArguments}, exactly as the SkyTNT loader builds them.
	 *
	 * @param w       the seeded weights
	 * @param epsilon the RMSNorm epsilon of both stages
	 * @return the constructed block
	 */
	private Block migrated(BlockWeights w, double epsilon) {
		Map<String, Object> args = attentionArguments(w.heads, w.heads, w.rmsAtt,
				w.wk, w.wv, w.wq, w.wo, SEQ_LEN, p(w.position), epsilon);
		args.put("freq_cis", cp(w.freqCis));
		args.put("rms_ffn_weight", w.rmsFfn);
		args.put("gate_proj", w.w1);
		args.put("up_proj", w.w3);
		args.put("down_proj", w.w2);

		PdslLoader loader = new PdslLoader();
		return loader.buildLayer(loader.parseResource("/pdsl/midi/skytnt_block.pdsl"),
				"skytnt_block", shape(1, DIM), args);
	}

	/**
	 * Builds the reference block the way the SkyTNT block behaved before the migration: a residual
	 * {@link AttentionFeatures#attention} stage (the public 8-argument overload — the one the
	 * {@code attention} built-in dispatched to, which hardcodes the attention RMSNorm epsilon to
	 * {@code 1e-5}) and a residual {@link org.almostrealism.ml.FeedForwardFeatures#feedForward}
	 * stage (the {@code swiglu_ffn} asset the SkyTNT feed-forward is structurally identical to).
	 * {@code w1}/{@code w2}/{@code w3} are the SkyTNT gate/down/up projections.
	 *
	 * @param w       the seeded weights
	 * @param epsilon the RMSNorm epsilon of the feed-forward stage (the attention stage uses
	 *                {@code 1e-5} regardless, as this test demonstrates)
	 * @return the constructed block
	 */
	private Block reference(BlockWeights w, double epsilon) {
		SequentialBlock block = new SequentialBlock(shape(1, DIM));
		block.accum(attention(w.heads, w.rmsAtt, w.wk, w.wv, w.wq, w.wo, cp(w.freqCis), p(w.position)));
		block.accum(feedForward(w.rmsFfn, w.w1, w.w2, w.w3, epsilon));
		return block;
	}

	/**
	 * Compiles {@code block} and runs {@link #STEPS} forward passes, advancing the cache position
	 * before each, returning the output of every pass.
	 *
	 * @param block the block to run
	 * @param w     the configuration, supplying the position reference and the per-pass inputs
	 * @return the output of each pass
	 */
	private double[][] run(Block block, BlockWeights w) {
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

	/** Requires {@code actual} to hold {@code expected} element for element, within {@link #TOLERANCE}. */
	private static void assertClose(String label, double[] expected, double[] actual) {
		Assert.assertEquals(label + ": length", expected.length, actual.length);
		for (int i = 0; i < expected.length; i++) {
			Assert.assertEquals(label + ": element " + i, expected[i], actual[i], TOLERANCE);
		}
	}

	/** The largest absolute element-wise difference between two equally sized vectors. */
	private static double maxAbsDiff(double[] a, double[] b) {
		double max = 0.0;
		for (int i = 0; i < a.length; i++) {
			max = Math.max(max, Math.abs(a[i] - b[i]));
		}
		return max;
	}

	/**
	 * The seeded weights, position and inputs of one configuration. Tensors are drawn on the device
	 * from a {@link Random} with a fixed seed, in declaration order, so both the reference build and
	 * the migrated build bind the same values.
	 */
	private final class BlockWeights {
		/** Name of the configuration in failure messages and logs. */
		private final String label;
		/** Number of attention heads (SkyTNT uses the same count for key/value heads). */
		private final int heads;
		/** The cache row written and the causal limit of the current pass. */
		private final PackedCollection position = new PackedCollection(shape(1));
		/** Rotary table shared by every head, {@code [SEQ_LEN, headSize / 2, 2]}. */
		private final PackedCollection freqCis;
		/** Pre-attention RMSNorm scale {@code [DIM]}. */
		private final PackedCollection rmsAtt;
		/** Query projection {@code [DIM, DIM]}. */
		private final PackedCollection wq;
		/** Key projection {@code [DIM, DIM]}. */
		private final PackedCollection wk;
		/** Value projection {@code [DIM, DIM]}. */
		private final PackedCollection wv;
		/** Attention output projection {@code [DIM, DIM]}. */
		private final PackedCollection wo;
		/** Pre-feed-forward RMSNorm scale {@code [DIM]}. */
		private final PackedCollection rmsFfn;
		/** Gate projection {@code [hiddenDim, DIM]} (SkyTNT gate_proj, transformer_block w1). */
		private final PackedCollection w1;
		/** Down projection {@code [DIM, hiddenDim]} (SkyTNT down_proj, transformer_block w2). */
		private final PackedCollection w2;
		/** Up projection {@code [hiddenDim, DIM]} (SkyTNT up_proj, transformer_block w3). */
		private final PackedCollection w3;
		/** Draws every random tensor, in declaration order. */
		private final Random random;

		/**
		 * Draws the tensors of one configuration.
		 *
		 * @param label     name of the configuration
		 * @param heads     number of attention heads (DIM must be a multiple of it)
		 * @param hiddenDim feed-forward intermediate dimension
		 * @param seed      seed of the random tensors
		 */
		private BlockWeights(String label, int heads, int hiddenDim, long seed) {
			this.label = label;
			this.heads = heads;
			this.random = new Random(seed);
			int headSize = DIM / heads;
			this.freqCis = RotationFeatures.computeRopeFreqs(THETA, headSize, SEQ_LEN).evaluate();
			this.rmsAtt = draw(0.6, 1.4, DIM);
			this.wq = draw(-0.4, 0.4, DIM, DIM);
			this.wk = draw(-0.4, 0.4, DIM, DIM);
			this.wv = draw(-0.4, 0.4, DIM, DIM);
			this.wo = draw(-0.4, 0.4, DIM, DIM);
			this.rmsFfn = draw(0.6, 1.4, DIM);
			this.w1 = draw(-0.4, 0.4, hiddenDim, DIM);
			this.w2 = draw(-0.4, 0.4, DIM, hiddenDim);
			this.w3 = draw(-0.4, 0.4, hiddenDim, DIM);
		}

		/**
		 * The token vector of pass {@code step}, produced on the device: element {@code i} is
		 * {@code (1 + i / 4) cos(0.9 i + 0.3 + 1.7 step)}, so later elements carry larger values and
		 * the mean square stays near unity, which makes the RMSNorm epsilon observable.
		 *
		 * @param step the pass index
		 * @return the input token vector, shape {@code [1, DIM]}
		 */
		private PackedCollection input(int step) {
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
