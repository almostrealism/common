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
import io.almostrealism.compute.ParallelProcess;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.layers.ProjectionFactory;
import org.almostrealism.ml.audio.ConditioningMode;
import org.almostrealism.ml.audio.DiffusionTransformer;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Standalone tests for adaptive layer-normalization (adaLN-Zero) conditioning — Block B1 of the
 * Stable Audio 3 component plan — proven independently of any real Stable Audio 3 weights.
 *
 * <p>adaLN modulation derives, per transformer block, six {@code [batch, dim]} components
 * (scale/shift/gate for self-attention followed by scale/shift/gate for the feed-forward) from a
 * global conditioning vector combined with a learned {@code to_scale_shift_gate} parameter, and
 * applies them as {@code x = x + gate * sublayer(scale * norm(x) + shift)}. The tests verify:</p>
 * <ul>
 *   <li>the scale/shift ({@link AdaptiveLayerNormFeatures#adaptiveModulate}) and gate
 *       ({@link AdaptiveLayerNormFeatures#adaptiveGate}) algebra against a direct host-side reference,
 *       and the modulation-parameter combination + component split against the same;</li>
 *   <li>that an adaLN block with identity modulation ({@code scale=1, shift=0, gate=1}) is numerically
 *       equal to the unmodulated block — i.e. the default (prepend) path is behaviour-preserving — and
 *       that {@code gate=0} collapses the block to the identity function;</li>
 *   <li>that the modulation threads through the compiled computation graph as a {@link Producer}, both
 *       at the block level and end-to-end through a {@link DiffusionTransformer} in
 *       {@link ConditioningMode#ADALN} mode, with the default {@link ConditioningMode#PREPEND} path
 *       unchanged.</li>
 * </ul>
 */
public class AdaLNConditioningTest extends TestSuiteBase implements AttentionFeatures {

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

	/**
	 * {@link AdaptiveLayerNormFeatures#adaptiveModulate} must compute {@code scale * x + shift} with the
	 * per-channel {@code [batch, dim]} scale and shift broadcast across the sequence.
	 */
	@Test(timeout = 120000)
	public void adaptiveModulateMatchesReference() {
		TraversalPolicy shape = shape(BATCH, SEQ_LEN, DIM);
		PackedCollection input = new PackedCollection(shape).randnFill();
		PackedCollection scale = new PackedCollection(shape(BATCH, DIM)).randnFill();
		PackedCollection shift = new PackedCollection(shape(BATCH, DIM)).randnFill();

		PackedCollection out = run(adaptiveModulate(shape, cp(scale), cp(shift)), input);

		double maxError = 0.0;
		for (int b = 0; b < BATCH; b++) {
			for (int s = 0; s < SEQ_LEN; s++) {
				for (int d = 0; d < DIM; d++) {
					double expected = input.valueAt(b, s, d) * scale.valueAt(b, d) + shift.valueAt(b, d);
					maxError = Math.max(maxError, Math.abs(expected - out.valueAt(b, s, d)));
					assertEquals(expected, out.valueAt(b, s, d), 1e-4);
				}
			}
		}
		log("adaptiveModulate max error vs reference = " + maxError);
	}

	/**
	 * {@link AdaptiveLayerNormFeatures#adaptiveGate} must compute {@code gate * x} with the per-channel
	 * {@code [batch, dim]} gate broadcast across the sequence.
	 */
	@Test(timeout = 120000)
	public void adaptiveGateMatchesReference() {
		TraversalPolicy shape = shape(BATCH, SEQ_LEN, DIM);
		PackedCollection input = new PackedCollection(shape).randnFill();
		PackedCollection gate = new PackedCollection(shape(BATCH, DIM)).randnFill();

		PackedCollection out = run(adaptiveGate(shape, cp(gate)), input);

		double maxError = 0.0;
		for (int b = 0; b < BATCH; b++) {
			for (int s = 0; s < SEQ_LEN; s++) {
				for (int d = 0; d < DIM; d++) {
					double expected = input.valueAt(b, s, d) * gate.valueAt(b, d);
					maxError = Math.max(maxError, Math.abs(expected - out.valueAt(b, s, d)));
					assertEquals(expected, out.valueAt(b, s, d), 1e-4);
				}
			}
		}
		log("adaptiveGate max error vs reference = " + maxError);
	}

	/**
	 * The packed modulation produced by {@link AdaptiveLayerNormFeatures#adaptiveModulationParameters}
	 * must equal {@code conditioning + to_scale_shift_gate} (broadcast over the six components and the
	 * batch respectively), and {@link AdaptiveLayerNormFeatures#modulationComponent} must select each
	 * {@code [batch, dim]} slice exactly.
	 */
	@Test(timeout = 120000)
	public void modulationParametersAndComponentsMatchReference() {
		PackedCollection conditioning = new PackedCollection(shape(BATCH, DIM)).randnFill();
		PackedCollection scaleShiftGate =
				new PackedCollection(shape(AdaptiveLayerNormFeatures.MODULATION_COMPONENTS, DIM)).randnFill();

		Producer<PackedCollection> modulation =
				adaptiveModulationParameters(cp(conditioning), scaleShiftGate, BATCH, DIM);
		PackedCollection packed = evaluate(modulation);

		assertEquals(BATCH * AdaptiveLayerNormFeatures.MODULATION_COMPONENTS * DIM,
				packed.getShape().getTotalSize());

		for (int b = 0; b < BATCH; b++) {
			for (int c = 0; c < AdaptiveLayerNormFeatures.MODULATION_COMPONENTS; c++) {
				for (int d = 0; d < DIM; d++) {
					double expected = conditioning.valueAt(b, d) + scaleShiftGate.valueAt(c, d);
					assertEquals(expected, packed.valueAt(b, c, d), 1e-4);
				}
			}
		}

		// modulationComponent must select each [batch, dim] slice from the packed [batch, 6, dim] tensor.
		for (int c = 0; c < AdaptiveLayerNormFeatures.MODULATION_COMPONENTS; c++) {
			PackedCollection component = evaluate(modulationComponent(modulation, BATCH, DIM, c));
			assertEquals(BATCH * DIM, component.getShape().getTotalSize());
			for (int b = 0; b < BATCH; b++) {
				for (int d = 0; d < DIM; d++) {
					double expected = conditioning.valueAt(b, d) + scaleShiftGate.valueAt(c, d);
					assertEquals(expected, component.valueAt(b, d), 1e-4);
				}
			}
		}
	}

	/**
	 * An adaLN block whose modulation is the identity ({@code scale=1, shift=0, gate=1} for both
	 * sub-layers) must be numerically equal to the unmodulated block. Because the unmodulated block is
	 * exactly the pre-change pre-norm residual block (the {@code modulation == null} path used by
	 * {@link ConditioningMode#PREPEND}), this proves the default conditioning path is behaviour-preserving
	 * while the modulation algebra reduces to identity as documented.
	 */
	@Test(timeout = 240000)
	public void identityModulationEqualsUnmodulatedBlock() {
		BlockWeights w = new BlockWeights();
		PackedCollection input = new PackedCollection(shape(BATCH, SEQ_LEN, DIM)).randnFill();

		PackedCollection unmodulated = run(w.block(null), input);

		// Identity modulation: scales = 1, shifts = 0, gates = 1, with a zero conditioning vector.
		PackedCollection identity = scaleShiftGate(1.0, 0.0, 1.0, 1.0, 0.0, 1.0);
		Producer<PackedCollection> modulation =
				adaptiveModulationParameters(zeroConditioning(), identity, BATCH, DIM);
		PackedCollection modulated = run(w.block(modulation), input);

		double diff = compare(unmodulated, modulated);
		log("identity adaLN vs unmodulated block difference = " + diff);
		assertTrue("adaLN with identity modulation must equal the unmodulated (prepend) block path",
				diff < 1e-4);
	}

	/**
	 * With {@code gate=0} for both sub-layers, every residual branch contributes zero and the block must
	 * reduce to the identity function (output equal to input), regardless of the scale/shift values or
	 * the sub-layer weights.
	 */
	@Test(timeout = 240000)
	public void gateZeroMakesBlockIdentity() {
		BlockWeights w = new BlockWeights();
		PackedCollection input = new PackedCollection(shape(BATCH, SEQ_LEN, DIM)).randnFill();

		// Gates = 0 (scales = 1, shifts = 0 are immaterial since the gated branch is zeroed out).
		PackedCollection gated = scaleShiftGate(1.0, 0.0, 0.0, 1.0, 0.0, 0.0);
		Producer<PackedCollection> modulation =
				adaptiveModulationParameters(zeroConditioning(), gated, BATCH, DIM);
		PackedCollection out = run(w.block(modulation), input);

		double diff = compare(input, out);
		log("gate=0 block vs input difference = " + diff);
		assertTrue("adaLN with gate=0 must collapse the block to the identity function", diff < 1e-4);
	}

	/**
	 * The modulation must compose as a {@link Producer} inside a compiled computation graph: a block
	 * built with a producer-derived modulation compiles and runs a forward pass producing a finite
	 * tensor of the expected {@code [batch, seqLen, dim]} shape.
	 */
	@Test(timeout = 240000)
	public void adaLNBlockComposesAsProducerInGraph() {
		BlockWeights w = new BlockWeights();
		PackedCollection input = new PackedCollection(shape(BATCH, SEQ_LEN, DIM)).randnFill();

		PackedCollection conditioning = new PackedCollection(shape(BATCH, DIM)).randnFill();
		PackedCollection scaleShiftGate =
				new PackedCollection(shape(AdaptiveLayerNormFeatures.MODULATION_COMPONENTS, DIM)).randnFill();
		Producer<PackedCollection> modulation =
				adaptiveModulationParameters(cp(conditioning), scaleShiftGate, BATCH, DIM);

		PackedCollection out = run(w.block(modulation), input);

		assertEquals(BATCH * SEQ_LEN * DIM, out.getShape().getTotalSize());
		double[] values = out.toArray();
		for (int i = 0; i < values.length; i++) {
			assertTrue("adaLN block output must be finite", Double.isFinite(values[i]));
		}
		log("adaLN block composed in graph, output size = " + values.length);
	}

	/**
	 * End-to-end integration: a {@link DiffusionTransformer} constructed in {@link ConditioningMode#ADALN}
	 * mode builds, compiles and runs a forward pass, consuming the per-block {@code to_scale_shift_gate}
	 * modulation through the full model graph. The output shape matches the input (the sequence is not
	 * lengthened by a prepended conditioning token).
	 */
	@Test(timeout = 240000)
	public void diffusionTransformerAdaLNModeForward() {
		int ioChannels = 2;
		int embedDim = 32;
		int depth = 1;
		int numHeads = 2;
		int patchSize = 1;
		int globalCondDim = 16;
		int audioSeqLen = 8;
		int condSeqLen = 4;

		DiffusionTransformer transformer = new DiffusionTransformer(
				ioChannels, embedDim, depth, numHeads, patchSize,
				0, globalCondDim, "rf_denoiser",
				audioSeqLen, condSeqLen, ConditioningMode.ADALN,
				null, false);

		int batchSize = DiffusionTransformer.batchSize;
		PackedCollection input = new PackedCollection(shape(batchSize, ioChannels, audioSeqLen)).randnFill();
		PackedCollection timestep = new PackedCollection(shape(batchSize, 1)).randnFill();
		PackedCollection globalCond = new PackedCollection(shape(batchSize, globalCondDim)).randnFill();

		PackedCollection output = transformer.forward(input, timestep, null, globalCond);

		assertEquals("ADALN output should have the same shape as the input (no prepended token)",
				input.getShape().getTotalSize(), output.getShape().getTotalSize());
		transformer.destroy();
		log("DiffusionTransformer ADALN forward output shape = " + output.getShape());
	}

	/**
	 * The default constructor (no explicit {@link ConditioningMode}) must select
	 * {@link ConditioningMode#PREPEND} and produce output identical to an explicitly
	 * {@code PREPEND}-configured transformer with the same weights — confirming the added conditioning
	 * mode does not disturb the default prepend wiring.
	 */
	@Test(timeout = 240000)
	public void prependModeMatchesDefaultConstructor() {
		int ioChannels = 2;
		int embedDim = 32;
		int depth = 1;
		int numHeads = 2;
		int patchSize = 1;
		int globalCondDim = 16;
		int audioSeqLen = 8;
		int condSeqLen = 4;

		DiffusionTransformer defaultMode = new DiffusionTransformer(
				ioChannels, embedDim, depth, numHeads, patchSize,
				0, globalCondDim, "rf_denoiser",
				audioSeqLen, condSeqLen, null, false);
		DiffusionTransformer prependMode = new DiffusionTransformer(
				ioChannels, embedDim, depth, numHeads, patchSize,
				0, globalCondDim, "rf_denoiser",
				audioSeqLen, condSeqLen, ConditioningMode.PREPEND, null, false);

		int batchSize = DiffusionTransformer.batchSize;
		PackedCollection input = new PackedCollection(shape(batchSize, ioChannels, audioSeqLen)).randnFill();
		PackedCollection timestep = new PackedCollection(shape(batchSize, 1)).randnFill();
		PackedCollection globalCond = new PackedCollection(shape(batchSize, globalCondDim)).randnFill();

		PackedCollection defaultOut = defaultMode.forward(input, timestep, null, globalCond);
		PackedCollection prependOut = prependMode.forward(input, timestep, null, globalCond);

		double diff = compare(defaultOut, prependOut);
		log("default-constructor vs explicit PREPEND difference = " + diff);
		assertTrue("Default constructor must select the PREPEND mode (identical output)", diff < 1e-6);

		defaultMode.destroy();
		prependMode.destroy();
	}

	/**
	 * Builds a {@code [batch, dim]} zero conditioning vector producer, so that the resulting modulation
	 * is exactly the {@code to_scale_shift_gate} parameter.
	 *
	 * @return a producer of a zero {@code [batch, dim]} conditioning vector
	 */
	private Producer<PackedCollection> zeroConditioning() {
		return cp(new PackedCollection(shape(BATCH, DIM)));
	}

	/**
	 * Builds a {@code [6, dim]} {@code to_scale_shift_gate} parameter with the six per-component values
	 * broadcast across the {@code dim} channels.
	 *
	 * @param scaleSelf scale for the self-attention sub-layer
	 * @param shiftSelf shift for the self-attention sub-layer
	 * @param gateSelf  gate for the self-attention sub-layer
	 * @param scaleFf   scale for the feed-forward sub-layer
	 * @param shiftFf   shift for the feed-forward sub-layer
	 * @param gateFf    gate for the feed-forward sub-layer
	 * @return the {@code [6, dim]} parameter collection
	 */
	private PackedCollection scaleShiftGate(double scaleSelf, double shiftSelf, double gateSelf,
											double scaleFf, double shiftFf, double gateFf) {
		double[] components = { scaleSelf, shiftSelf, gateSelf, scaleFf, shiftFf, gateFf };
		PackedCollection ssg =
				new PackedCollection(shape(AdaptiveLayerNormFeatures.MODULATION_COMPONENTS, DIM));
		ssg.fill(pos -> components[pos[0]]);
		return ssg;
	}

	/**
	 * Compiles a single block into a model and runs one forward pass over a {@code [batch, seqLen, dim]}
	 * input.
	 *
	 * @param block the block under test
	 * @param input the model input
	 * @return the forward-pass output
	 */
	private PackedCollection run(Block block, PackedCollection input) {
		Model model = new Model(shape(BATCH, SEQ_LEN, DIM));
		model.sequential().add(block);
		CompiledModel compiled = model.compile(false);
		return compiled.forward(input);
	}

	/**
	 * Evaluates a collection producer at the top of the call stack (test boundary), using the
	 * optimization pass the framework requires for standalone producer evaluation.
	 *
	 * @param producer the producer to evaluate
	 * @return the evaluated collection
	 */
	private PackedCollection evaluate(Producer<PackedCollection> producer) {
		return ((Evaluable<PackedCollection>) ((ParallelProcess) producer).optimize().get()).evaluate();
	}

	/**
	 * Shared synthetic weights for a single self-attention + feed-forward transformer block (no
	 * cross-attention). The same weight instances are reused across the modulated and unmodulated
	 * builds so that any output difference is attributable solely to the modulation.
	 */
	private final class BlockWeights {
		/** Feed-forward hidden dimension (GLU expansion of the model dimension). */
		private final int hiddenDim = DIM * 4;

		/** Self-attention pre-normalization scale. */
		private final PackedCollection preNormWeight = new PackedCollection(shape(DIM)).fill(1.0);
		/** Self-attention pre-normalization bias. */
		private final PackedCollection preNormBias = new PackedCollection(shape(DIM));
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
		private final PackedCollection invFreq = new PackedCollection(shape(DIM_HEAD / 4)).fill(pos -> 0.01);

		/** Feed-forward pre-normalization scale. */
		private final PackedCollection ffnNormWeight = new PackedCollection(shape(DIM)).fill(1.0);
		/** Feed-forward pre-normalization bias. */
		private final PackedCollection ffnNormBias = new PackedCollection(shape(DIM));
		/** Feed-forward GLU gate projection. */
		private final PackedCollection w1 = new PackedCollection(shape(2 * hiddenDim, DIM)).randnFill();
		/** Feed-forward GLU gate projection bias. */
		private final PackedCollection w1Bias = new PackedCollection(shape(2 * hiddenDim));
		/** Feed-forward output projection. */
		private final PackedCollection w2 = new PackedCollection(shape(DIM, hiddenDim)).randnFill();
		/** Feed-forward output projection bias. */
		private final PackedCollection w2Bias = new PackedCollection(shape(DIM));

		/**
		 * Builds the transformer block with an optional adaLN modulation. A {@code null} modulation
		 * exercises the unmodulated (prepend) path.
		 *
		 * @param modulation the packed {@code [batch, 6, dim]} modulation, or {@code null}
		 * @return the transformer block
		 */
		private Block block(Producer<PackedCollection> modulation) {
			return transformerBlock(
					BATCH, DIM, SEQ_LEN, HEADS,
					false, 0, null,
					preNormWeight, preNormBias,
					qkv, wo,
					qNormWeight, qNormBias,
					kNormWeight, kNormBias,
					invFreq,
					null, null,
					null, null, null,
					null, null,
					null, null,
					ffnNormWeight, ffnNormBias,
					w1, w2, w1Bias, w2Bias,
					null, ProjectionFactory.dense(),
					AttentionVariant.STANDARD, null, modulation);
		}
	}
}
