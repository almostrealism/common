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
 * Parity and reference pins for the pre-norm residual self-attention sub-layer that
 * {@link TransformerBlockFeatures#selfAttentionResidualSublayer} now builds from the
 * {@code self_attention_sublayer.pdsl} asset.
 *
 * <p>The sub-layer was previously assembled inside
 * {@link TransformerBlockFeatures#transformerBlock} as a {@link SequentialBlock} — the
 * pre-normalization, an optional adaLN modulation of the normalized activation, the self-attention,
 * an optional adaLN gate on the attention output — wrapped in a residual connection. These tests
 * pin the migrated asset two ways:</p>
 * <ul>
 *   <li><b>Parity against the Java composition.</b> Each test builds the sub-layer once through the
 *       asset loader and once as the exact Java composition the migration replaced
 *       ({@code residual(norm -> [adaptiveModulate] -> attention -> [adaptiveGate])}), binds the same
 *       weights and conditioning, and asserts the two forward passes agree to a tight tolerance. The
 *       Java composition is the reference implementation (its {@code norm}, {@code adaptiveModulate},
 *       {@code adaptiveGate} and {@code residual} are the pre-migration primitives), so this is a
 *       comparison against the reference, not self-agreement.</li>
 *   <li><b>Independent algebraic pin.</b> {@link #closedGateMakesSublayerIdentity} drives the gate to
 *       zero and asserts the sub-layer collapses to the identity, independent of the attention
 *       weights — a property of the residual-with-gate wiring that holds only if the gate multiplies
 *       the attention output before the residual add.</li>
 * </ul>
 */
public class SelfAttentionSublayerAssetTest extends TestSuiteBase implements TransformerBlockFeatures {

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
	/** Tolerance for the parity comparison between the asset and the Java composition. */
	private static final double TOLERANCE = 1e-5;

	/**
	 * The asset's LayerNorm adaLN-modulated sub-layer must match, element for element, the Java
	 * composition {@code residual(layernorm -> adaptiveModulate -> attention -> adaptiveGate)} it
	 * replaced.
	 */
	@Test(timeout = 240000)
	public void layernormModulatedMatchesJavaComposition() {
		assertParity(NormalizationType.LAYER, true);
	}

	/**
	 * The asset's LayerNorm plain pre-norm sub-layer (no modulation) must match the Java composition
	 * {@code residual(layernorm -> attention)} it replaced — the {@code modulation == null} path used
	 * by the prepend conditioning mode.
	 */
	@Test(timeout = 240000)
	public void layernormUnmodulatedMatchesJavaComposition() {
		assertParity(NormalizationType.LAYER, false);
	}

	/**
	 * The asset's RMSNorm adaLN-modulated sub-layer must match the Java composition
	 * {@code residual(rmsnorm -> adaptiveModulate -> attention -> adaptiveGate)} it replaced.
	 */
	@Test(timeout = 240000)
	public void rmsnormModulatedMatchesJavaComposition() {
		assertParity(NormalizationType.RMS, true);
	}

	/**
	 * The asset's RMSNorm plain pre-norm sub-layer (no modulation) must match the Java composition
	 * {@code residual(rmsnorm -> attention)} it replaced — the {@code modulation == null} path with the
	 * RMSNorm normalization family.
	 */
	@Test(timeout = 240000)
	public void rmsnormUnmodulatedMatchesJavaComposition() {
		assertParity(NormalizationType.RMS, false);
	}

	/**
	 * With the adaLN gate driven fully closed (gate multiplier zero) the sub-layer's residual branch
	 * contributes nothing and the sub-layer must reduce to the identity, regardless of the attention
	 * weights. This pins the residual-with-gate wiring against a direct algebraic reference rather than
	 * against the Java composition.
	 */
	@Test(timeout = 240000)
	public void closedGateMakesSublayerIdentity() {
		TraversalPolicy blockShape = shape(BATCH, SEQ_LEN, DIM);
		Weights w = new Weights();
		PackedCollection input = new PackedCollection(blockShape).randnFill();

		// gate multiplier of zero zeroes the gated attention output, leaving only the residual input.
		PackedCollection modScale = new PackedCollection(shape(BATCH, DIM)).fill(1.0);
		PackedCollection modShift = new PackedCollection(shape(BATCH, DIM));
		PackedCollection gate = new PackedCollection(shape(BATCH, DIM));

		Block sublayer = selfAttentionResidualSublayer(blockShape, NormalizationType.LAYER,
				w.preNormWeight, w.preNormBias, cp(modScale), cp(modShift), cp(gate), w.attention());

		PackedCollection out = run(sublayer, input);
		double diff = compare(input, out);
		log("closed-gate sub-layer vs input difference = " + diff);
		assertTrue("A closed adaLN gate must collapse the self-attention sub-layer to the identity",
				diff < 1e-5);
	}

	/**
	 * The three adaLN conditioning producers must be supplied all together or all absent: a partial
	 * combination is rejected, rather than silently building an unmodulated block (when only
	 * {@code modScale} is null) or deferring the failure to producer binding (when only {@code modScale}
	 * is supplied). This pins the all-or-none validation of {@link #selfAttentionResidualSublayer}.
	 */
	@Test(timeout = 240000)
	public void partialConditioningIsRejected() {
		TraversalPolicy blockShape = shape(BATCH, SEQ_LEN, DIM);
		Weights w = new Weights();
		PackedCollection mod = new PackedCollection(shape(BATCH, DIM)).fill(1.0);

		// modScale supplied, modShift and gate absent — would select a modulated layer and fail at binding.
		assertTrue("partial conditioning (only modScale supplied) must be rejected",
				rejectsConditioning(blockShape, w, mod, null, null));

		// modScale absent, gate supplied — would silently build an unmodulated block, dropping the gate.
		assertTrue("partial conditioning (only gate supplied) must be rejected",
				rejectsConditioning(blockShape, w, null, null, mod));
	}

	/**
	 * Builds the sub-layer with the given (possibly partial) conditioning and reports whether it is
	 * rejected with an {@link IllegalArgumentException}, as the all-or-none validation requires.
	 *
	 * @param blockShape the sub-layer shape
	 * @param w          the shared synthetic weights
	 * @param modScale   the adaLN scale modulation, or {@code null}
	 * @param modShift   the adaLN shift modulation, or {@code null}
	 * @param gate       the adaLN output gate, or {@code null}
	 * @return whether the builder rejected the combination
	 */
	private boolean rejectsConditioning(TraversalPolicy blockShape, Weights w,
										PackedCollection modScale, PackedCollection modShift,
										PackedCollection gate) {
		try {
			selfAttentionResidualSublayer(blockShape, NormalizationType.LAYER,
					w.preNormWeight, w.preNormBias,
					modScale == null ? null : cp(modScale),
					modShift == null ? null : cp(modShift),
					gate == null ? null : cp(gate),
					w.attention());
			return false;
		} catch (IllegalArgumentException expected) {
			return true;
		}
	}

	/**
	 * Builds the self-attention residual sub-layer both through the asset loader and as the Java
	 * composition it replaced, and asserts the two forward passes agree.
	 *
	 * @param normType  the normalization family
	 * @param modulated whether to apply the adaLN modulation and gate
	 */
	private void assertParity(NormalizationType normType, boolean modulated) {
		TraversalPolicy blockShape = shape(BATCH, SEQ_LEN, DIM);
		Weights w = new Weights();
		PackedCollection input = new PackedCollection(blockShape).randnFill();

		PackedCollection modScale = modulated ? new PackedCollection(shape(BATCH, DIM)).randnFill() : null;
		PackedCollection modShift = modulated ? new PackedCollection(shape(BATCH, DIM)).randnFill() : null;
		PackedCollection gate = modulated ? new PackedCollection(shape(BATCH, DIM)).randnFill() : null;

		// Reference: the exact Java composition the migration replaced.
		SequentialBlock sub = new SequentialBlock(blockShape);
		sub.add(norm(normType, w.preNormWeight, w.preNormBias));
		if (modulated) {
			sub.add(adaptiveModulate(blockShape, cp(modScale), cp(modShift)));
		}
		sub.add(w.attention());
		if (modulated) {
			sub.add(adaptiveGate(blockShape, cp(gate)));
		}
		PackedCollection reference = run(residual(sub), input);

		// Migrated asset, with the same weights and conditioning.
		Block asset = selfAttentionResidualSublayer(blockShape, normType,
				w.preNormWeight, w.preNormBias,
				modulated ? cp(modScale) : null,
				modulated ? cp(modShift) : null,
				modulated ? cp(gate) : null,
				w.attention());
		PackedCollection migrated = run(asset, input);

		double diff = compare(reference, migrated);
		log(normType + (modulated ? " modulated" : " unmodulated")
				+ " asset vs Java composition difference = " + diff);
		assertTrue("The migrated self-attention sub-layer must match the Java composition it replaced",
				diff < TOLERANCE);
	}

	/**
	 * Compiles a single block into a model and runs one forward pass over a {@code [batch, seqLen, dim]}
	 * input.
	 *
	 * <p>The forward result is cloned into a host-owned collection before the compiled model is
	 * destroyed, because {@link CompiledModel#forward} returns the collection owned by the model and
	 * that memory is released on {@link CompiledModel#destroy()}. Both the {@link Model} and the
	 * {@link CompiledModel} are destroyed here so the five tests sharing this helper do not accumulate
	 * native activation and kernel resources across the suite.</p>
	 *
	 * @param block the block under test
	 * @param input the model input
	 * @return the forward-pass output
	 */
	private PackedCollection run(Block block, PackedCollection input) {
		Model model = new Model(shape(BATCH, SEQ_LEN, DIM));
		model.sequential().add(block);
		CompiledModel compiled = model.compile(false);
		try {
			return compiled.forward(input).clone();
		} finally {
			compiled.destroy();
			model.destroy();
		}
	}

	/**
	 * Shared synthetic weights for the self-attention sub-layer. {@link #attention()} builds a fresh
	 * standard self-attention block each call over the same weight instances, so the reference and the
	 * asset drive identical attention.
	 */
	private final class Weights {
		/** Self-attention pre-normalization scale. */
		private final PackedCollection preNormWeight = new PackedCollection(shape(DIM)).fill(1.0);
		// TODO(review): no parity test exercises a null preNormBias (the typical RMSNorm/no-bias production path through the asset).
		/** Self-attention pre-normalization bias. */
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

		/**
		 * Builds a standard (non-causal, no padding mask) self-attention block over the shared weights.
		 *
		 * @return the self-attention sub-block
		 */
		private Block attention() {
			return selfAttention(BATCH, SEQ_LEN, DIM, HEADS, AttentionVariant.STANDARD,
					qkv, wo, qNormWeight, qNormBias, kNormWeight, kNormBias,
					invFreq, null, ProjectionFactory.dense(), NormalizationType.LAYER, null, false);
		}
	}
}
