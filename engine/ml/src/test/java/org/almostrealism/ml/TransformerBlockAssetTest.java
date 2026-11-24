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
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.layers.NormalizationType;
import org.almostrealism.layers.ProjectionFactory;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Parity and reference pins for the block-level transformer-block composition that
 * {@link TransformerBlockFeatures#transformerBlockStack} now builds from the
 * {@code transformer_block.pdsl} asset, and that {@link TransformerBlockFeatures#transformerBlock}
 * delegates its sub-layer sequencing to.
 *
 * <p>The composition was previously assembled inside {@link TransformerBlockFeatures#transformerBlock}
 * as a {@link SequentialBlock}: the self-attention residual sub-layer, an optional cross-attention
 * residual sub-layer, an optional per-position local conditioning add, and the feed-forward residual
 * sub-layer, added in that order. The sub-layers themselves are already assets (and independently
 * pinned by {@code SelfAttentionSublayerAssetTest} and {@code FeedForwardSublayerAssetTest}); the only
 * structure this migration moves into PDSL is the ordering of those sub-layers. These tests pin it two
 * ways:</p>
 * <ul>
 *   <li><b>Independent algebraic pin of the ordering.</b> The four
 *       {@code stacksSublayersInOrder*} tests sequence order-sensitive affine blocks
 *       ({@code x -> scale*x + shift}) through {@link TransformerBlockFeatures#transformerBlockStack}
 *       and assert the output equals the sequence applied in self, cross, local, feed-forward order
 *       computed directly in Java — a reference independent of the framework — for every combination of
 *       the two optional sub-layers. A reordered or dropped stage fails.</li>
 *   <li><b>Parity against the Java assembly.</b> {@link #fullBlockMatchesSequentialAssembly} builds a
 *       real self-attention + feed-forward transformer block once through
 *       {@link TransformerBlockFeatures#transformerBlock} (the asset) and once as the exact
 *       {@link SequentialBlock} composition the migration replaced, binds the same weights, and asserts
 *       the two agree to a tight tolerance over repeated forward passes.</li>
 * </ul>
 */
public class TransformerBlockAssetTest extends TestSuiteBase implements TransformerBlockFeatures {

	/** Batch dimension; scaled-dot-product attention currently asserts a batch size of 1. */
	private static final int BATCH = 1;
	/** Sequence length. */
	private static final int SEQ_LEN = 4;
	/** Model dimension. */
	private static final int DIM = 16;
	/** Number of attention heads. */
	private static final int HEADS = 2;
	/** Per-head dimension. */
	private static final int DIM_HEAD = DIM / HEADS;
	/** Hidden (per-half) dimension of the gated feed-forward. */
	private static final int HIDDEN_DIM = 2 * DIM;
	/** Tolerance for the parity and reference comparisons. */
	private static final double TOLERANCE = 1e-5;

	/** The plain block sequences the self-attention and feed-forward sub-layers, in that order. */
	@Test(timeout = 240000)
	public void stacksSublayersInOrder() {
		assertStacksInOrder(false, false);
	}

	/** The cross block sequences self-attention, cross-attention, then feed-forward, in that order. */
	@Test(timeout = 240000)
	public void stacksSublayersInOrderWithCross() {
		assertStacksInOrder(true, false);
	}

	/** The local block sequences self-attention, the local conditioning add, then feed-forward. */
	@Test(timeout = 240000)
	public void stacksSublayersInOrderWithLocal() {
		assertStacksInOrder(false, true);
	}

	/** The cross-and-local block sequences self-attention, cross-attention, local, then feed-forward. */
	@Test(timeout = 240000)
	public void stacksSublayersInOrderWithCrossAndLocal() {
		assertStacksInOrder(true, true);
	}

	/**
	 * Sequences order-sensitive affine blocks through {@link #transformerBlockStack} for the given
	 * combination of optional sub-layers and asserts the result equals both the same affine sequence
	 * applied directly in Java (an independent reference) and the equivalent {@link SequentialBlock}
	 * assembly the migration replaced.
	 *
	 * <p>The blocks are {@code x -> scale*x + shift} with distinct, non-commuting coefficients, so the
	 * output pins the exact self, cross, local, feed-forward ordering: any reordered, dropped or
	 * duplicated stage changes the result.</p>
	 *
	 * @param cross whether the cross-attention sub-layer position is present
	 * @param local whether the local conditioning position is present
	 */
	private void assertStacksInOrder(boolean cross, boolean local) {
		TraversalPolicy blockShape = shape(BATCH, SEQ_LEN, DIM);
		PackedCollection input = new PackedCollection(blockShape).randnFill();

		// The affine coefficients of each position, in the sequencing order self, [cross], [local], ff.
		double[][] stages = stageCoefficients(cross, local);

		Block asset = transformerBlockStack(blockShape,
				affine("self", 2.0, 0.0),
				cross ? affine("cross", 1.0, 1.0) : null,
				local ? affine("local", 1.0, 2.0) : null,
				affine("ff", 3.0, 0.0));
		double[] assetOut = run(asset, input, 1).toArray();

		SequentialBlock sequential = new SequentialBlock(blockShape);
		sequential.add(affine("self", 2.0, 0.0));
		if (cross) sequential.add(affine("cross", 1.0, 1.0));
		if (local) sequential.add(affine("local", 1.0, 2.0));
		sequential.add(affine("ff", 3.0, 0.0));
		double[] sequentialOut = run(sequential, input, 1).toArray();

		double[] in = input.toArray();
		double maxExpectedDiff = 0.0;
		double maxSequentialDiff = 0.0;
		for (int i = 0; i < in.length; i++) {
			double expected = in[i];
			for (double[] stage : stages) {
				expected = expected * stage[0] + stage[1];
			}
			maxExpectedDiff = Math.max(maxExpectedDiff, Math.abs(expected - assetOut[i]));
			maxSequentialDiff = Math.max(maxSequentialDiff, Math.abs(sequentialOut[i] - assetOut[i]));
		}

		log("stack cross=" + cross + " local=" + local
				+ ": asset vs algebraic reference = " + maxExpectedDiff
				+ ", asset vs SequentialBlock = " + maxSequentialDiff);
		assertTrue("The asset must sequence the sub-layers in self, cross, local, feed-forward order",
				maxExpectedDiff < TOLERANCE);
		assertTrue("The asset sequencing must match the SequentialBlock assembly it replaced",
				maxSequentialDiff < TOLERANCE);
	}

	/**
	 * The affine coefficients {@code {scale, shift}} of the present positions, in sequencing order.
	 *
	 * @param cross whether the cross-attention position is present
	 * @param local whether the local conditioning position is present
	 * @return one {@code {scale, shift}} pair per present position, in order
	 */
	private double[][] stageCoefficients(boolean cross, boolean local) {
		int count = 2 + (cross ? 1 : 0) + (local ? 1 : 0);
		double[][] stages = new double[count][];
		int index = 0;
		stages[index++] = new double[]{2.0, 0.0};
		if (cross) stages[index++] = new double[]{1.0, 1.0};
		if (local) stages[index++] = new double[]{1.0, 2.0};
		stages[index] = new double[]{3.0, 0.0};
		return stages;
	}

	/**
	 * A shape-preserving affine block {@code x -> scale*x + shift} over {@code [batch, seq_len, dim]}.
	 *
	 * @param name  layer name
	 * @param scale multiplicative coefficient
	 * @param shift additive coefficient
	 * @return the affine block
	 */
	private Block affine(String name, double scale, double shift) {
		TraversalPolicy blockShape = shape(BATCH, SEQ_LEN, DIM);
		return layer(name, blockShape, blockShape, in -> c(in).multiply(scale).add(shift));
	}

	/**
	 * A real self-attention + feed-forward transformer block sequenced through the asset (as
	 * {@link TransformerBlockFeatures#transformerBlock} now does) must match, element for element, the
	 * {@link SequentialBlock} composition it replaced, over two forward passes. The self-attention and
	 * feed-forward residual sub-layers are built from the same weights on both sides, so the only
	 * difference under test is the ordering mechanism.
	 */
	@Test(timeout = 300000)
	public void fullBlockMatchesSequentialAssembly() {
		TraversalPolicy blockShape = shape(BATCH, SEQ_LEN, DIM);
		Weights w = new Weights();
		PackedCollection input = new PackedCollection(blockShape).randnFill();

		// Migrated path: the transformer_block asset sequences the residual sub-layers.
		Block asset = transformerBlockStack(blockShape,
				selfAttentionResidualSublayer(blockShape, NormalizationType.RMS,
						w.preNormWeight, w.preNormBias, null, null, null, w.attention()),
				null, null,
				feedForwardResidualSublayer(blockShape, NormalizationType.RMS,
						w.ffnNormWeight, w.ffnNormBias, null, null, null, w.feedForward()));
		double[] assetOut = run(asset, input, 2).toArray();

		// Reference: the exact SequentialBlock the migration replaced, over the same weights.
		SequentialBlock reference = new SequentialBlock(blockShape);
		reference.add(selfAttentionResidualSublayer(blockShape, NormalizationType.RMS,
				w.preNormWeight, w.preNormBias, null, null, null, w.attention()));
		reference.add(feedForwardResidualSublayer(blockShape, NormalizationType.RMS,
				w.ffnNormWeight, w.ffnNormBias, null, null, null, w.feedForward()));
		double[] referenceOut = run(reference, input, 2).toArray();

		double maxDiff = 0.0;
		for (int i = 0; i < assetOut.length; i++) {
			maxDiff = Math.max(maxDiff, Math.abs(assetOut[i] - referenceOut[i]));
		}
		log("full block asset vs SequentialBlock assembly difference = " + maxDiff);
		assertTrue("The migrated transformer block must match the SequentialBlock assembly it replaced",
				maxDiff < TOLERANCE);
	}

	/**
	 * Compiles a single block into a model and runs {@code passes} forward passes over a
	 * {@code [batch, seq_len, dim]} input, returning the last pass's output cloned into host-owned
	 * memory before the compiled model is destroyed.
	 *
	 * @param block  the block under test
	 * @param input  the model input
	 * @param passes the number of forward passes to run (at least one)
	 * @return the final forward-pass output
	 */
	private PackedCollection run(Block block, PackedCollection input, int passes) {
		Model model = new Model(shape(BATCH, SEQ_LEN, DIM));
		model.sequential().add(block);
		CompiledModel compiled = model.compile(false);
		try {
			PackedCollection out = null;
			for (int pass = 0; pass < passes; pass++) {
				out = compiled.forward(input).clone();
			}
			return out;
		} finally {
			compiled.destroy();
			model.destroy();
		}
	}

	/**
	 * Shared synthetic weights for the full self-attention + feed-forward block. {@link #attention()}
	 * and {@link #feedForward()} build fresh sub-blocks over the same weight instances, so the asset and
	 * the reference drive identical sub-layers.
	 */
	private final class Weights {
		/** Self-attention pre-normalization scale. */
		private final PackedCollection preNormWeight = new PackedCollection(shape(DIM)).fill(1.0);
		/** Self-attention pre-normalization bias ({@code null}-friendly RMS layout uses a zero bias here). */
		private final PackedCollection preNormBias = new PackedCollection(shape(DIM)).randnFill();
		/** Fused {@code [3*dim, dim]} self-attention QKV projection. */
		private final PackedCollection qkv = new PackedCollection(shape(3 * DIM, DIM)).randnFill();
		/** Self-attention output projection. */
		private final PackedCollection wo = new PackedCollection(shape(DIM, DIM)).randnFill();
		/** Query-normalization scale. */
		private final PackedCollection qNormWeight = new PackedCollection(shape(DIM_HEAD)).fill(1.0);
		/** Query-normalization bias. */
		private final PackedCollection qNormBias = new PackedCollection(shape(DIM_HEAD));
		/** Key-normalization scale. */
		private final PackedCollection kNormWeight = new PackedCollection(shape(DIM_HEAD)).fill(1.0);
		/** Key-normalization bias. */
		private final PackedCollection kNormBias = new PackedCollection(shape(DIM_HEAD));
		/** RoPE inverse frequencies. */
		private final PackedCollection invFreq = new PackedCollection(shape(DIM_HEAD / 4)).fill(0.01);
		/** Feed-forward pre-normalization scale. */
		private final PackedCollection ffnNormWeight = new PackedCollection(shape(DIM)).fill(1.0);
		/** Feed-forward pre-normalization bias. */
		private final PackedCollection ffnNormBias = new PackedCollection(shape(DIM)).randnFill();
		/** Fused {@code [2*hidden, dim]} gate+linear feed-forward projection. */
		private final PackedCollection w1 = new PackedCollection(shape(2 * HIDDEN_DIM, DIM)).randnFill();
		/** Fused projection bias. */
		private final PackedCollection w1Bias = new PackedCollection(shape(2 * HIDDEN_DIM)).randnFill();
		/** Feed-forward down projection {@code [dim, hidden]}. */
		private final PackedCollection w2 = new PackedCollection(shape(DIM, HIDDEN_DIM)).randnFill();
		/** Down projection bias. */
		private final PackedCollection w2Bias = new PackedCollection(shape(DIM)).randnFill();

		/**
		 * Builds a standard (non-causal, no padding mask) self-attention block over the shared weights.
		 *
		 * @return the self-attention sub-block
		 */
		private Block attention() {
			return selfAttention(BATCH, SEQ_LEN, DIM, HEADS, AttentionVariant.STANDARD,
					qkv, wo, qNormWeight, qNormBias, kNormWeight, kNormBias,
					invFreq, null, ProjectionFactory.dense(), NormalizationType.RMS, null, false);
		}

		/**
		 * Builds the norm-free gated-linear feed-forward body over the shared weights.
		 *
		 * @return the feed-forward sub-block
		 */
		private Block feedForward() {
			return gluFeedForward(shape(BATCH, SEQ_LEN, DIM), w1, w1Bias, w2, w2Bias,
					silu(), ProjectionFactory.dense());
		}
	}
}
