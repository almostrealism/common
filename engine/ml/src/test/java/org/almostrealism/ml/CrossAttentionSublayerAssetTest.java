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
 * Parity and reference pins for the pre-norm residual cross-attention sub-layer that
 * {@link TransformerBlockFeatures#crossAttentionResidualSublayer} now builds from the
 * {@code cross_attention_sublayer.pdsl} asset.
 *
 * <p>The sub-layer was previously assembled inside
 * {@link TransformerBlockFeatures#transformerBlock} as a {@link SequentialBlock} — the
 * cross-attention pre-normalization, then the cross-attention over the encoder context — wrapped in
 * a residual connection ({@code residual(norm -> sequenceCrossAttention)}). Unlike the self-attention
 * and feed-forward sub-layers, the cross-attention sub-layer is never adaLN-modulated, so there are
 * only the two plain-norm layers and no modulation producers. These tests pin the migrated asset two
 * ways:</p>
 * <ul>
 *   <li><b>Parity against the Java composition.</b> Each test builds the sub-layer once through the
 *       asset loader and once as the exact Java composition the migration replaced
 *       ({@code residual(norm -> sequenceCrossAttention)}), binds the same weights and feeds the same
 *       query and context inputs, and asserts the two forward passes agree to a tight tolerance. The
 *       Java composition is the reference implementation (its {@code norm}, {@code sequenceCrossAttention}
 *       and {@code residual} are the pre-migration primitives), so this is a comparison against the
 *       reference, not self-agreement.</li>
 *   <li><b>Independent algebraic pin.</b> {@link #zeroOutputProjectionMakesSublayerIdentity} drives
 *       the cross-attention output projection to zero and asserts the sub-layer collapses to the
 *       identity, independent of the normalization and projection weights — a property of the
 *       residual wiring that holds only if the cross-attention output is added to the residual input
 *       rather than replacing it.</li>
 * </ul>
 *
 * <p>The cross-attention context is an input of a second {@link Model} fed before the main pass, the
 * same wiring {@link org.almostrealism.ml.audio.DiffusionTransformer} uses and
 * {@link TransformerBlockFeaturesTest} exercises: the context block's keys and values are captured on
 * a {@link Block#branch() branch} when the context model runs, then read by the main model's
 * cross-attention. The query sequence length and the context sequence length differ, so the
 * cross-attention is exercised at a genuinely rectangular query-by-context shape rather than a square
 * special case.</p>
 */
public class CrossAttentionSublayerAssetTest extends TestSuiteBase implements TransformerBlockFeatures {

	/** Batch dimension; scaled-dot-product attention currently asserts a batch size of 1. */
	private static final int BATCH = 1;
	/** Query sequence length (the residual stream the sub-layer operates on). */
	private static final int SEQ_LEN = 4;
	/** Context sequence length; distinct from {@link #SEQ_LEN} so the cross-attention is rectangular. */
	private static final int CONTEXT_SEQ_LEN = 3;
	/** Model dimension. */
	private static final int DIM = 16;
	/** Number of attention heads. */
	private static final int HEADS = 2;
	/** Per-head dimension. */
	private static final int DIM_HEAD = DIM / HEADS;
	/** Tolerance for the parity comparison between the asset and the Java composition. */
	private static final double TOLERANCE = 1e-5;

	/**
	 * The asset's LayerNorm cross-attention sub-layer must match, element for element, the Java
	 * composition {@code residual(layernorm -> sequenceCrossAttention)} it replaced.
	 */
	@Test(timeout = 240000)
	public void layernormMatchesJavaComposition() {
		assertParity(NormalizationType.LAYER, false);
	}

	/**
	 * The asset's RMSNorm cross-attention sub-layer must match the Java composition
	 * {@code residual(rmsnorm -> sequenceCrossAttention)} it replaced.
	 */
	@Test(timeout = 240000)
	public void rmsnormMatchesJavaComposition() {
		assertParity(NormalizationType.RMS, false);
	}

	/**
	 * The asset's RMSNorm cross-attention sub-layer must match the Java composition when the
	 * pre-normalization bias is {@code null} — the production RMSNorm checkpoint layout, where
	 * {@link org.almostrealism.ml.audio.DiffusionTransformer} supplies a {@code null} shift (its
	 * {@code normShift} returns {@code null} for {@link NormalizationType#RMS}). This exercises the
	 * asset's optional-bias binding and the bias-free RMSNorm execution path, which the random-bias
	 * cases never reach.
	 */
	@Test(timeout = 240000)
	public void rmsnormNullBiasMatchesJavaComposition() {
		assertParity(NormalizationType.RMS, true);
	}

	/**
	 * The asset's LayerNorm cross-attention sub-layer must match the Java composition when the
	 * pre-normalization bias is {@code null}, so the asset's optional-bias binding is exercised for the
	 * LayerNorm family too (the {@code layernorm} built-in accepts a {@code null} shift as well).
	 */
	@Test(timeout = 240000)
	public void layernormNullBiasMatchesJavaComposition() {
		assertParity(NormalizationType.LAYER, true);
	}

	/**
	 * With the cross-attention output projection zeroed the attention sub-block emits zeros, so the
	 * residual branch contributes nothing and the sub-layer must reduce to the identity, regardless of
	 * the normalization and projection weights. This pins the residual wiring against a direct algebraic
	 * reference rather than against the Java composition: it holds only if the asset's {@code accum} adds
	 * the cross-attention output to the residual input.
	 */
	@Test(timeout = 240000)
	public void zeroOutputProjectionMakesSublayerIdentity() {
		Weights w = new Weights(true);
		PackedCollection contextInput = new PackedCollection(shape(BATCH, CONTEXT_SEQ_LEN, DIM)).randnFill();
		PackedCollection mainInput = new PackedCollection(shape(BATCH, SEQ_LEN, DIM)).randnFill();

		PackedCollection out = runSublayer(NormalizationType.LAYER, false, true, w, contextInput, mainInput);

		double diff = compare(mainInput, out);
		log("zero-output-projection cross-attention sub-layer vs input difference = " + diff);
		assertTrue("A zeroed cross-attention output projection must collapse the sub-layer to the identity",
				diff < TOLERANCE);
	}

	/**
	 * Builds the cross-attention residual sub-layer both through the asset loader and as the Java
	 * composition it replaced, and asserts the two forward passes agree.
	 *
	 * @param normType the normalization family
	 * @param nullBias whether the pre-normalization bias is {@code null} (the bias-free layout
	 *                 production supplies for RMSNorm) rather than a random tensor
	 */
	private void assertParity(NormalizationType normType, boolean nullBias) {
		Weights w = new Weights();
		PackedCollection contextInput = new PackedCollection(shape(BATCH, CONTEXT_SEQ_LEN, DIM)).randnFill();
		PackedCollection mainInput = new PackedCollection(shape(BATCH, SEQ_LEN, DIM)).randnFill();

		PackedCollection reference = runSublayer(normType, nullBias, false, w, contextInput, mainInput);
		PackedCollection migrated = runSublayer(normType, nullBias, true, w, contextInput, mainInput);

		double diff = compare(reference, migrated);
		log(normType + (nullBias ? " null-bias" : "")
				+ " cross-attention asset vs Java composition difference = " + diff);
		assertTrue("The migrated cross-attention sub-layer must match the Java composition it replaced",
				diff < TOLERANCE);
	}

	/**
	 * Builds the cross-attention residual sub-layer — either from the asset or as the Java composition
	 * it replaced — over a freshly built cross-attention block, compiles it into a model with the
	 * context as a second model input, feeds the context then the query, and returns one forward pass.
	 *
	 * <p>The cross-attention block is rebuilt on every call over the same weight instances, so the
	 * reference and the asset drive identical cross-attention; only the residual wiring (asset vs Java
	 * composition) differs between the two paths. The context is fed through its own compiled model
	 * before the main pass, so the branch that reads the context keys and values is populated when the
	 * main model's cross-attention runs.</p>
	 *
	 * @param normType     the normalization family of the pre-normalization
	 * @param nullBias     whether to supply a {@code null} pre-normalization bias
	 * @param asset        whether to build the sub-layer from the asset ({@code true}) or as the Java
	 *                     composition {@code residual(norm -> crossAttention)} ({@code false})
	 * @param w            the shared synthetic weights
	 * @param contextInput the encoder context, shape {@code [batch, contextSeqLen, dim]}
	 * @param mainInput    the query residual stream, shape {@code [batch, seqLen, dim]}
	 * @return the forward-pass output, cloned to a host-owned collection
	 */
	private PackedCollection runSublayer(NormalizationType normType, boolean nullBias, boolean asset,
										 Weights w, PackedCollection contextInput, PackedCollection mainInput) {
		TraversalPolicy blockShape = shape(BATCH, SEQ_LEN, DIM);
		PackedCollection preNormBias = nullBias ? null : w.crossPreNormBias;

		Model contextModel = new Model(shape(BATCH, CONTEXT_SEQ_LEN, DIM));
		SequentialBlock contextBlock = contextModel.sequential();

		Block crossAttention = sequenceCrossAttention(BATCH, SEQ_LEN, CONTEXT_SEQ_LEN, DIM, HEADS,
				w.crossWq, w.crossKv, w.crossWo,
				w.qNormWeight, w.qNormBias, w.kNormWeight, w.kNormBias,
				contextBlock, null, ProjectionFactory.dense(), normType);

		Block sublayer;
		if (asset) {
			sublayer = crossAttentionResidualSublayer(blockShape, normType,
					w.crossPreNormWeight, preNormBias, crossAttention);
		} else {
			// The exact Java composition the migration replaced.
			SequentialBlock sub = new SequentialBlock(blockShape);
			sub.add(norm(normType, w.crossPreNormWeight, preNormBias));
			sub.add(crossAttention);
			sublayer = residual(sub);
		}

		Model mainModel = new Model(blockShape);
		mainModel.sequential().add(sublayer);

		CompiledModel mainCompiled = mainModel.compile(false);
		CompiledModel contextCompiled = contextModel.compile(false);
		try {
			contextCompiled.forward(contextInput);
			return mainCompiled.forward(mainInput).clone();
		} finally {
			mainCompiled.destroy();
			contextCompiled.destroy();
			mainModel.destroy();
			contextModel.destroy();
		}
	}

	/**
	 * Shared synthetic weights for the cross-attention sub-layer. The cross-attention projections,
	 * query/key normalization and pre-normalization are drawn once and reused by the reference and the
	 * asset, so both drive identical cross-attention.
	 */
	private final class Weights {
		/** Cross-attention pre-normalization scale. */
		private final PackedCollection crossPreNormWeight = new PackedCollection(shape(DIM)).fill(1.0);
		/** Cross-attention pre-normalization bias used by the random-bias parity cases. */
		private final PackedCollection crossPreNormBias = new PackedCollection(shape(DIM)).randnFill();
		/** Query projection, shape {@code [dim, dim]}. */
		private final PackedCollection crossWq = new PackedCollection(shape(DIM, DIM)).randnFill();
		/** Fused key/value projection of the context, shape {@code [2*dim, dim]}. */
		private final PackedCollection crossKv = new PackedCollection(shape(2 * DIM, DIM)).randnFill();
		/** Output projection, shape {@code [dim, dim]}; zeroed in the identity pin. */
		private final PackedCollection crossWo;
		/** Query-normalization scale, over the per-head dimension. */
		private final PackedCollection qNormWeight = new PackedCollection(shape(DIM_HEAD)).fill(1.0);
		/** Query-normalization bias. */
		private final PackedCollection qNormBias = new PackedCollection(shape(DIM_HEAD));
		/** Key-normalization scale, over the per-head dimension. */
		private final PackedCollection kNormWeight = new PackedCollection(shape(DIM_HEAD)).fill(1.0);
		/** Key-normalization bias. */
		private final PackedCollection kNormBias = new PackedCollection(shape(DIM_HEAD));

		/** Draws random cross-attention weights with a non-zero output projection. */
		private Weights() {
			this(false);
		}

		/**
		 * Draws the cross-attention weights, optionally with a zeroed output projection so the
		 * attention sub-block emits zeros (for the identity pin).
		 *
		 * @param zeroOutputProjection whether the output projection {@code crossWo} is zero rather than
		 *                             a random tensor
		 */
		private Weights(boolean zeroOutputProjection) {
			this.crossWo = zeroOutputProjection
					? new PackedCollection(shape(DIM, DIM))
					: new PackedCollection(shape(DIM, DIM)).randnFill();
		}
	}
}
