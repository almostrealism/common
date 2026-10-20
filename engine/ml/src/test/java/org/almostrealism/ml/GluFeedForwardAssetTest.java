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

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.layers.AdapterConfig;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.layers.NormalizationType;
import org.almostrealism.layers.ProjectionFactory;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.List;
import java.util.function.Function;

/**
 * Parity tests pinning the gated-linear feed-forward migration: the {@code glu_feed_forward} layer
 * of {@code /pdsl/feed_forward.pdsl} (loaded by {@link FeedForwardFeatures#gatedLinearFeedForward})
 * must compute exactly what the deleted Java assembly computed — a fused gate+linear projection, a
 * split of its output into two halves, the gate half's activation, the element-wise product of the
 * halves and a down projection, with an optional pre-norm and adaLN modulation composed around it.
 *
 * <p>The reference ({@link #reference}) is assembled directly in the test from the same framework
 * primitives the removed assembly used — {@code norm}, {@link SequentialBlock#split split},
 * {@code product} and the same {@link ProjectionFactory#dense() dense} projections — so it is
 * independent of the asset path it pins and survives the migration. Three configurations cover the
 * production callers: the RMSNorm + GELU gated feed-forward of the T5Gemma encoder, the LayerNorm +
 * SiLU gated feed-forward of the diffusion transformer, and that same feed-forward with adaLN
 * modulation. Dimensions are small but every stage is exercised: a sequence of three positions, a
 * fused projection split into two sixteen-wide halves, and a non-trivial down projection.</p>
 */
public class GluFeedForwardAssetTest extends TestSuiteBase implements AttentionFeatures {

	/** Batch dimension of the test activations. */
	private static final int BATCH = 1;

	/** Batch dimension of the multi-batch test activations (the slices must span real batches). */
	private static final int MULTI_BATCH = 2;

	/** Sequence length of the test activations (more than one, so the split spans real rows). */
	private static final int SEQ_LEN = 3;

	/** Model dimension of the test activations. */
	private static final int DIM = 8;

	/** Feed-forward inner dimension (each half of the fused projection). */
	private static final int INNER = 16;

	/** Largest absolute difference permitted between the asset path and the reference. */
	private static final double TOLERANCE = 1e-5;

	/**
	 * RMSNorm + GELU gated feed-forward (the T5Gemma encoder's feed-forward sub-layer) computes the
	 * same values through the asset as through the reference assembly.
	 */
	@Test(timeout = 120000)
	public void rmsNormGeluMatchesReference() {
		assertParity(NormalizationType.RMS, gelu(), false);
	}

	/**
	 * LayerNorm + SiLU gated feed-forward (the diffusion transformer's unmodulated feed-forward)
	 * computes the same values through the asset as through the reference assembly.
	 */
	@Test(timeout = 120000)
	public void layerNormSiluMatchesReference() {
		assertParity(NormalizationType.LAYER, silu(), false);
	}

	/**
	 * LayerNorm + SiLU gated feed-forward with adaLN modulation (the diffusion transformer's
	 * modulated feed-forward) computes the same values through the asset as through the reference
	 * assembly, so the modulation composed in Java around the asset body is behaviour-preserving.
	 */
	@Test(timeout = 120000)
	public void layerNormSiluModulatedMatchesReference() {
		assertParity(NormalizationType.LAYER, silu(), true);
	}

	/**
	 * With more than one batch the slices of the fused projection must still select the right halves
	 * of every batch row, and the per-batch adaLN modulation must reach the right batch, so the asset
	 * agrees with the reference assembly for a batch of two.
	 */
	@Test(timeout = 120000)
	public void multiBatchModulatedMatchesReference() {
		assertParity(MULTI_BATCH, NormalizationType.LAYER, silu(), true);
	}

	/**
	 * The asset body binds {@code batch} and {@code seq_len} from the first two input axes, so an input
	 * that is not {@code [batch, seq_len, dim]} is rejected rather than silently mis-sliced.
	 */
	@Test(timeout = 60000)
	public void rejectsInputThatIsNotRankThree() {
		PackedCollection normWeights = new PackedCollection(shape(DIM)).randnFill();
		PackedCollection weightIn = new PackedCollection(shape(2 * INNER, DIM)).randnFill();
		PackedCollection weightOut = new PackedCollection(shape(DIM, INNER)).randnFill();

		try {
			gatedLinearFeedForward(shape(SEQ_LEN, DIM), NormalizationType.RMS, normWeights, null,
					weightIn, null, weightOut, null, null, null, gelu(), ProjectionFactory.dense());
			throw new AssertionError("A rank-two input must be rejected");
		} catch (IllegalArgumentException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("[batch, seq_len, dim]"));
		}
	}

	/**
	 * A fused projection with an odd number of output features cannot be split into equal linear and
	 * gate halves, so it is rejected rather than having its last feature silently dropped.
	 */
	@Test(timeout = 60000)
	public void rejectsOddFusedProjectionWidth() {
		PackedCollection normWeights = new PackedCollection(shape(DIM)).randnFill();
		PackedCollection weightIn = new PackedCollection(shape(2 * INNER + 1, DIM)).randnFill();
		PackedCollection weightOut = new PackedCollection(shape(DIM, INNER)).randnFill();

		try {
			gatedLinearFeedForward(shape(BATCH, SEQ_LEN, DIM), NormalizationType.RMS, normWeights, null,
					weightIn, null, weightOut, null, null, null, gelu(), ProjectionFactory.dense());
			throw new AssertionError("An odd fused projection width must be rejected");
		} catch (IllegalArgumentException e) {
			assertTrue(e.getMessage(), e.getMessage().contains(String.valueOf(2 * INNER + 1)));
		}
	}

	/**
	 * Builds the gated-linear feed-forward both through the asset path and through the reference
	 * assembly with identical weights and input, runs both, and asserts the outputs agree.
	 *
	 * @param normType   the pre-normalization family
	 * @param activation the gate-half activation
	 * @param modulated  whether to apply adaLN modulation after the norm
	 */
	private void assertParity(NormalizationType normType,
							  Function<TraversalPolicy, CellularLayer> activation, boolean modulated) {
		assertParity(BATCH, normType, activation, modulated);
	}

	/**
	 * Builds the gated-linear feed-forward both through the asset path and through the reference
	 * assembly with identical weights and input of the given batch size, runs both, and asserts the
	 * outputs agree.
	 *
	 * @param batch      the batch dimension of the input
	 * @param normType   the pre-normalization family
	 * @param activation the gate-half activation
	 * @param modulated  whether to apply adaLN modulation after the norm
	 */
	private void assertParity(int batch, NormalizationType normType,
							  Function<TraversalPolicy, CellularLayer> activation, boolean modulated) {
		TraversalPolicy inputShape = shape(batch, SEQ_LEN, DIM);

		PackedCollection normWeights = new PackedCollection(shape(DIM)).randnFill();
		PackedCollection normBiases = new PackedCollection(shape(DIM)).randnFill();
		PackedCollection weightIn = new PackedCollection(shape(2 * INNER, DIM)).randnFill();
		PackedCollection biasIn = new PackedCollection(shape(2 * INNER)).randnFill();
		PackedCollection weightOut = new PackedCollection(shape(DIM, INNER)).randnFill();
		PackedCollection biasOut = new PackedCollection(shape(DIM)).randnFill();
		PackedCollection modScale = modulated ? new PackedCollection(shape(batch, DIM)).randnFill() : null;
		PackedCollection modShift = modulated ? new PackedCollection(shape(batch, DIM)).randnFill() : null;
		PackedCollection input = new PackedCollection(inputShape).randnFill();

		Producer<PackedCollection> scale = modScale == null ? null : cp(modScale);
		Producer<PackedCollection> shift = modShift == null ? null : cp(modShift);

		Block assetBlock = gatedLinearFeedForward(inputShape, normType, normWeights, normBiases,
				weightIn, biasIn, weightOut, biasOut, scale, shift, activation, ProjectionFactory.dense());
		Block referenceBlock = reference(inputShape, normType, normWeights, normBiases,
				weightIn, biasIn, weightOut, biasOut, scale, shift, activation);

		double[] assetOut = run(inputShape, assetBlock, input);
		double[] referenceOut = run(inputShape, referenceBlock, input);

		double maxDiff = maxAbsDiff(assetOut, referenceOut);
		log("glu_feed_forward parity: batch=" + batch + ", normType=" + normType + ", modulated=" + modulated
				+ ", maxAbsDiff=" + maxDiff);
		assertTrue("glu_feed_forward asset diverges from the reference assembly (maxAbsDiff="
				+ maxDiff + ")", maxDiff < TOLERANCE);
	}

	/**
	 * Assembles the gated-linear feed-forward exactly as the deleted Java assembly did, using the
	 * same framework primitives, so it is an independent baseline for the asset path.
	 *
	 * @param inputShape  the activation shape
	 * @param normType    the pre-normalization family
	 * @param normWeights the normalization scale
	 * @param normBiases  the normalization shift
	 * @param weightIn    the fused gate+linear projection weight
	 * @param biasIn      the fused projection bias
	 * @param weightOut   the down projection weight
	 * @param biasOut     the down projection bias
	 * @param modScale    the adaLN multiplicative modulation, or {@code null}
	 * @param modShift    the adaLN additive modulation, or {@code null}
	 * @param activation  the gate-half activation
	 * @return the reference block
	 */
	private Block reference(TraversalPolicy inputShape, NormalizationType normType,
							PackedCollection normWeights, PackedCollection normBiases,
							PackedCollection weightIn, PackedCollection biasIn,
							PackedCollection weightOut, PackedCollection biasOut,
							Producer<PackedCollection> modScale, Producer<PackedCollection> modShift,
							Function<TraversalPolicy, CellularLayer> activation) {
		ProjectionFactory dense = ProjectionFactory.dense();
		SequentialBlock block = new SequentialBlock(inputShape);
		block.add(norm(normType, normWeights, normBiases));
		if (modScale != null) {
			block.add(adaptiveModulate(block.getOutputShape(), modScale, modShift));
		}
		block.add(dense.create(block.getOutputShape(), weightIn, biasIn,
				AdapterConfig.TargetLayer.FFN_GATE));
		List<Block> split = block.split(2, block.getOutputShape().getDimensions() - 1, 0);
		Block gate = split.get(1).andThen(activation);
		block.add(product(gate));
		block.add(dense.create(block.getOutputShape(), weightOut, biasOut,
				AdapterConfig.TargetLayer.FFN_OUT));
		return block;
	}

	/**
	 * Compiles a block as a one-block model, runs it on the given input and copies the output into a
	 * fresh array (the compiled model reuses one output buffer across calls).
	 *
	 * @param inputShape the model input shape
	 * @param block      the block to run
	 * @param input      the input activation
	 * @return the output activation values
	 */
	private double[] run(TraversalPolicy inputShape, Block block, PackedCollection input) {
		Model model = new Model(inputShape);
		model.add(block);
		CompiledModel compiled = model.compile(false);
		PackedCollection output = compiled.forward(input);
		return output.toArray(0, output.getShape().getTotalSize());
	}

	/**
	 * The largest absolute difference between two equal-length activations.
	 *
	 * @param a the first activation values
	 * @param b the second activation values
	 * @return the largest absolute element-wise difference
	 */
	private double maxAbsDiff(double[] a, double[] b) {
		double max = 0.0;
		for (int i = 0; i < a.length; i++) {
			max = Math.max(max, Math.abs(a[i] - b[i]));
		}
		return max;
	}
}
