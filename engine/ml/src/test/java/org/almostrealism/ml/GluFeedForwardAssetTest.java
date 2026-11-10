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
import org.almostrealism.dsl.PdslLoader;
import org.almostrealism.layers.AdapterConfig;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.layers.NormalizationType;
import org.almostrealism.layers.ProjectionFactory;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.util.GradientRecorder;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 *
 * <p>The pre-normalization and the adaLN modulation then moved into the
 * {@code gated_linear_feed_forward_*} layers of the same asset, one per normalization family and
 * modulation. A pinned configuration ({@link PinnedWeights}: deterministic weights, a batch of two
 * and two positions, so every per-batch modulation reaches its own rows and is broadcast across
 * positions) fixes that move from three sides: the golden values the Java assembly computed on master
 * before it, a double-precision host reference of the same arithmetic, and parity of each asset layer
 * built directly with the reference assembly, in the forward pass and in the backward pass. A
 * compiled feed-forward is also run twice with different modulation values, the way a diffusion
 * transformer runs one denoising step after another.</p>
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

	/** Batch of the pinned configuration: two, so each batch entry's modulation reaches its own rows. */
	private static final int PINNED_BATCH = 2;

	/**
	 * Sequence length of the pinned configuration: three, so the modulation is broadcast across
	 * positions and the activation holds six positions of {@link #PINNED_DIM} features each.
	 */
	private static final int PINNED_SEQ_LEN = 3;

	/**
	 * Sequence length of the square configuration: two, so the activation holds exactly as many
	 * positions (two batch entries of two) as each position has features ({@link #PINNED_DIM}).
	 */
	private static final int SQUARE_SEQ_LEN = 2;

	/** Model dimension of the pinned configuration. */
	private static final int PINNED_DIM = 4;

	/** Feed-forward inner dimension of the pinned configuration (each half of the fused projection). */
	private static final int PINNED_INNER = 6;

	/**
	 * Largest difference permitted from the golden values captured on master and from the host
	 * reference, relative to the magnitude of the expected value (and absolute below one): a few
	 * single-precision rounding steps through the normalization, the two projections and the gate.
	 */
	private static final double PINNED_TOLERANCE = 1e-5;

	/**
	 * Output of the pinned LayerNorm feed-forward without modulation, as the Java assembly on master
	 * computed it ({@code FeedForwardFeatures.gatedLinearFeedForward} building {@code norm(LAYER)} and
	 * the {@code glu_feed_forward} body as a {@code SequentialBlock}), captured before the
	 * pre-normalization moved into {@code feed_forward.pdsl}. Flattened {@code [batch, seq_len, dim]}.
	 */
	private static final double[] GOLDEN_LAYERNORM = {
			1.4566901922225952, 0.051587145775556564, -1.32982337474823, -0.4193016290664673,
			1.0659849643707275, 0.4649501442909241, -0.7823371887207031, -0.6250131726264954,
			1.1264138221740723, 0.4719456434249878, -0.8401129841804504, -0.6539220213890076,
			1.2215368747711182, 0.05888943001627922, -1.0919004678726196, -0.33636409044265747,
			1.4617329835891724, 0.04898957535624504, -1.3358514308929443, -0.41899043321609497,
			0.7216991782188416, 0.34899765253067017, -0.48203009366989136, -0.3951597213745117
	};

	/** As {@link #GOLDEN_LAYERNORM}, with the adaLN modulation applied after the LayerNorm. */
	private static final double[] GOLDEN_LAYERNORM_MODULATED = {
			2.8281116485595703, 0.1751774400472641, -2.654369354248047, -1.0452678203582764,
			1.818888783454895, 1.0759060382843018, -1.3035167455673218, -1.433642864227295,
			1.7102850675582886, 0.9332674741744995, -1.2490133047103882, -1.2703320980072021,
			1.35794997215271, 0.17577075958251953, -1.1839826107025146, -0.4881705045700073,
			1.4754645824432373, 0.1551394760608673, -1.3093223571777344, -0.5150783061981201,
			0.8849744200706482, 0.5454016923904419, -0.5708128809928894, -0.6252374053001404
	};

	/** As {@link #GOLDEN_LAYERNORM}, with an RMSNorm (and no norm biases) in place of the LayerNorm. */
	private static final double[] GOLDEN_RMSNORM = {
			10.985669136047363, 4.884790420532227, -9.025656700134277, -8.171392440795898,
			12.256867408752441, 6.908587455749512, -9.529268264770508, -10.386199951171875,
			8.065723419189453, 2.2051103115081787, -7.122063636779785, -4.769715785980225,
			11.231563568115234, 2.3344390392303467, -10.23885440826416, -6.0811848640441895,
			6.064695835113525, 1.9605215787887573, -5.213804721832275, -3.801358699798584,
			13.249197959899902, 7.124917507171631, -10.43954849243164, -10.947782516479492
	};

	/** As {@link #GOLDEN_RMSNORM}, with the adaLN modulation applied after the RMSNorm. */
	private static final double[] GOLDEN_RMSNORM_MODULATED = {
			20.712892532348633, 9.621017456054688, -16.956518173217773, -15.9156494140625,
			23.903799057006836, 13.46195125579834, -18.69062614440918, -20.41429901123047,
			8.465760231018066, 2.3250701427459717, -7.476602077484131, -5.024145126342773,
			4.5696187019348145, 1.0969698429107666, -4.0462565422058105, -2.4949777126312256,
			5.626699924468994, 2.0136375427246094, -4.75566291809082, -3.6807098388671875,
			11.820548057556152, 6.413043975830078, -9.280899047851562, -9.796455383300781
	};

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
		assertParity(batch, normType, activation, modulated, true);
	}

	/**
	 * Builds the gated-linear feed-forward both through the asset path and through the reference
	 * assembly with identical weights and input of the given batch size, with or without norm biases,
	 * runs both, and asserts the outputs agree.
	 *
	 * @param batch      the batch dimension of the input
	 * @param normType   the pre-normalization family
	 * @param activation the gate-half activation
	 * @param modulated  whether to apply adaLN modulation after the norm
	 * @param normBiased whether the norm has biases; an RMSNorm checkpoint carries none
	 */
	private void assertParity(int batch, NormalizationType normType,
							  Function<TraversalPolicy, CellularLayer> activation, boolean modulated,
							  boolean normBiased) {
		TraversalPolicy inputShape = shape(batch, SEQ_LEN, DIM);

		PackedCollection normWeights = new PackedCollection(shape(DIM)).randnFill();
		PackedCollection normBiases = normBiased ? new PackedCollection(shape(DIM)).randnFill() : null;
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
	 * The gated-linear feed-forward computes, for every combination of normalization family and
	 * modulation, the values the Java assembly on master computed for the same deterministic
	 * weights, input and modulation (see {@link PinnedWeights}).
	 */
	@Test(timeout = 120000)
	public void gatedLinearFeedForwardMatchesMasterGoldenValues() {
		PinnedWeights w = new PinnedWeights(PINNED_SEQ_LEN);
		double[] layerNorm = pinnedOutput(w, NormalizationType.LAYER, false);
		double[] layerNormModulated = pinnedOutput(w, NormalizationType.LAYER, true);
		double[] rmsNorm = pinnedOutput(w, NormalizationType.RMS, false);
		double[] rmsNormModulated = pinnedOutput(w, NormalizationType.RMS, true);

		assertClose("LayerNorm vs master", GOLDEN_LAYERNORM, layerNorm, PINNED_TOLERANCE);
		assertClose("modulated LayerNorm vs master", GOLDEN_LAYERNORM_MODULATED, layerNormModulated,
				PINNED_TOLERANCE);
		assertClose("RMSNorm vs master", GOLDEN_RMSNORM, rmsNorm, PINNED_TOLERANCE);
		assertClose("modulated RMSNorm vs master", GOLDEN_RMSNORM_MODULATED, rmsNormModulated,
				PINNED_TOLERANCE);
	}

	/**
	 * RMSNorm + SiLU gated feed-forward with adaLN modulation and no norm biases (the diffusion
	 * transformer's feed-forward for an RMSNorm checkpoint in adaLN conditioning mode, as Stable Audio 3
	 * runs it) computes the same values through the asset as through the reference assembly, for a
	 * batch of two so that each batch entry's modulation must reach its own rows.
	 */
	@Test(timeout = 120000)
	public void rmsNormSiluModulatedMatchesReference() {
		assertParity(MULTI_BATCH, NormalizationType.RMS, silu(), true, false);
	}

	/**
	 * Each of the four layers of {@code feed_forward.pdsl} the gated-linear feed-forward loads, built
	 * directly through {@link PdslLoader}, computes what the Java assembly computes for the same
	 * weights, input and modulation: the reference assembly of this test, the structure that
	 * {@code FeedForwardFeatures.gatedLinearFeedForward} assembled before these layers replaced it.
	 */
	@Test(timeout = 240000)
	public void assetLayersMatchTheJavaAssembly() {
		PinnedWeights w = new PinnedWeights(PINNED_SEQ_LEN);
		TraversalPolicy inputShape = w.inputShape();
		for (NormalizationType normType : NormalizationType.values()) {
			for (boolean modulated : new boolean[] { false, true }) {
				Producer<PackedCollection> scale = modulated ? cp(w.modScale) : null;
				Producer<PackedCollection> shift = modulated ? cp(w.modShift) : null;
				double[] asset = run(inputShape, w.assetLayer(inputShape, normType, scale, shift), w.input);
				double[] java = run(inputShape, reference(inputShape, normType, w.normWeights,
						w.normBiases(normType), w.weightIn, w.biasIn, w.weightOut, w.biasOut,
						scale, shift, silu()), w.input);
				assertClose(normType + (modulated ? " modulated" : "") + " asset vs Java assembly",
						java, asset, PINNED_TOLERANCE);
			}
		}
	}

	/**
	 * The gated-linear feed-forward computes, for every combination of normalization family and
	 * modulation, what the same arithmetic computes on the host in double precision (see
	 * {@link PinnedWeights#hostReference}), independently of both the asset and the Java assembly.
	 */
	@Test(timeout = 120000)
	public void gatedLinearFeedForwardMatchesHostReference() {
		assertMatchesHostReference(new PinnedWeights(PINNED_SEQ_LEN));
	}

	/**
	 * The normalization applies its per-feature weights and biases to every feature even when the
	 * activation holds exactly as many positions as each position has features (two batch entries of
	 * two positions, four features each): the host reference holds for this square activation too.
	 * Before the normalizations broadcast their parameters explicitly, the per-feature vector paired
	 * with the positions of a square activation, so every position was scaled and shifted by the
	 * parameters of its own index instead of each feature by its own.
	 */
	@Test(timeout = 120000)
	public void squareActivationMatchesHostReference() {
		assertMatchesHostReference(new PinnedWeights(SQUARE_SEQ_LEN));
	}

	/**
	 * Asserts that the gated-linear feed-forward of the given weights computes, for every combination
	 * of normalization family and modulation, what the host reference computes.
	 *
	 * @param w the weights, input and modulation
	 */
	private void assertMatchesHostReference(PinnedWeights w) {
		for (NormalizationType normType : NormalizationType.values()) {
			assertClose(normType + " vs host", w.hostReference(normType, null, null),
					pinnedOutput(w, normType, false), PINNED_TOLERANCE);
			assertClose(normType + " modulated vs host", w.hostReference(normType, w.modScale, w.modShift),
					pinnedOutput(w, normType, true), PINNED_TOLERANCE);
		}
	}

	/**
	 * The adaLN modulation is read from the collections behind its producers on every forward pass,
	 * not captured when the block is built: a diffusion transformer refreshes its conditioning before
	 * every denoising step and runs the same compiled model again. One compiled feed-forward runs
	 * twice with different modulation values written between the passes, and each pass matches the
	 * host reference for the values it was given.
	 */
	@Test(timeout = 120000)
	public void modulationIsReadOnEveryForwardPass() {
		PinnedWeights w = new PinnedWeights(PINNED_SEQ_LEN);
		TraversalPolicy inputShape = w.inputShape();
		TraversalPolicy modulationShape = shape(PINNED_BATCH, PINNED_DIM);
		PackedCollection scale = new PackedCollection(modulationShape);
		PackedCollection shift = new PackedCollection(modulationShape);
		PackedCollection laterScale = wave(modulationShape, 0.4, 0.17, 1.3, 0.9);
		PackedCollection laterShift = wave(modulationShape, 0.3, 0.83, 0.1, -0.1);

		Model model = new Model(inputShape);
		model.add(w.feedForward(inputShape, NormalizationType.RMS, cp(scale), cp(shift)));
		try (CompiledModel compiled = model.compile(false)) {
			scale.setFrom(0, w.modScale);
			shift.setFrom(0, w.modShift);
			double[] first = compiled.forward(w.input).doubleStream().toArray();
			assertClose("first pass", w.hostReference(NormalizationType.RMS, w.modScale, w.modShift),
					first, PINNED_TOLERANCE);

			scale.setFrom(0, laterScale);
			shift.setFrom(0, laterShift);
			double[] second = compiled.forward(w.input).doubleStream().toArray();
			assertClose("second pass", w.hostReference(NormalizationType.RMS, laterScale, laterShift),
					second, PINNED_TOLERANCE);
		}
	}

	/**
	 * The backward pass of the modulated feed-forward matches the Java assembly's: the same gradient
	 * reaches the input, the norm scale and both projections, for LayerNorm and for RMSNorm. The norm
	 * and down-projection biases are left out so that every weight has a size of its own, which is how
	 * the recorded gradients are told apart.
	 */
	@Test(timeout = 300000)
	public void modulatedGradientsMatchTheJavaAssembly() {
		int[] weightSizes = { PINNED_DIM, 2 * PINNED_INNER * PINNED_DIM, 2 * PINNED_INNER,
				PINNED_DIM * PINNED_INNER };
		for (NormalizationType normType : NormalizationType.values()) {
			PinnedWeights w = new PinnedWeights(PINNED_SEQ_LEN);
			TraversalPolicy inputShape = w.inputShape();
			PackedCollection outputGradient = wave(inputShape, 0.5, 0.43, 0.25, 0.0);
			Block asset = gatedLinearFeedForward(inputShape, normType, w.normWeights, null,
					w.weightIn, w.biasIn, w.weightOut, null, cp(w.modScale), cp(w.modShift),
					silu(), ProjectionFactory.dense());
			Block java = reference(inputShape, normType, w.normWeights, null,
					w.weightIn, w.biasIn, w.weightOut, null, cp(w.modScale), cp(w.modShift), silu());

			double[][] assetGradients = gradients(inputShape, asset, w.input, outputGradient, weightSizes);
			double[][] javaGradients = gradients(inputShape, java, w.input, outputGradient, weightSizes);
			for (int i = 0; i < javaGradients.length; i++) {
				assertClose(normType + (i == 0 ? " input gradient" : " gradient of weight " + i),
						javaGradients[i], assetGradients[i], PINNED_TOLERANCE);
			}
		}
	}

	/**
	 * Runs a block forward and backward once and returns the gradient that reaches its input followed
	 * by the gradient recorded for each weight of the given sizes.
	 *
	 * @param inputShape     the block's input shape
	 * @param block          the block
	 * @param input          the input activation
	 * @param outputGradient the gradient of the loss with respect to the block's output
	 * @param weightSizes    the sizes of the weights whose gradients to return, each unique to one weight
	 * @return the input gradient, then one gradient per weight size, each flattened
	 */
	private double[][] gradients(TraversalPolicy inputShape, Block block, PackedCollection input,
								 PackedCollection outputGradient, int... weightSizes) {
		GradientRecorder recorder = new GradientRecorder();
		Model model = new Model(inputShape, recorder);
		model.add(block);
		try (CompiledModel compiled = model.compile(true, true)) {
			compiled.forward(input);
			double[][] gradients = new double[weightSizes.length + 1][];
			gradients[0] = compiled.backward(outputGradient).doubleStream().toArray();
			for (int i = 0; i < weightSizes.length; i++) {
				gradients[i + 1] = recorder.gradientOfSize(weightSizes[i]);
			}
			return gradients;
		}
	}

	/**
	 * Runs the feed-forward of the given weights for one normalization family, modulated or not,
	 * through {@link FeedForwardFeatures#gatedLinearFeedForward} and logs its output.
	 *
	 * @param w         the weights, input and modulation
	 * @param normType  the pre-normalization family
	 * @param modulated whether the adaLN modulation follows the norm
	 * @return the output, flattened
	 */
	private double[] pinnedOutput(PinnedWeights w, NormalizationType normType, boolean modulated) {
		double[] output = run(w.inputShape(), w.feedForward(w.inputShape(), normType,
				modulated ? cp(w.modScale) : null, modulated ? cp(w.modShift) : null), w.input);
		log("pinned seq_len=" + w.seqLen + " " + normType + (modulated ? " modulated" : "")
				+ " output=" + Arrays.toString(output));
		return output;
	}

	/**
	 * The weights, input and modulation of a pinned configuration of {@link #PINNED_BATCH} batch
	 * entries of {@link #PINNED_DIM} features. Every tensor is a closed-form sinusoid of its element
	 * index, {@code amplitude * sin(frequency * i + phase) + offset}, computed on the device, so the
	 * values are reproducible without a random source. The LayerNorm feed-forward reads the norm
	 * biases; the RMSNorm one, like an RMSNorm checkpoint, has none.
	 */
	private final class PinnedWeights {
		/** The number of positions of every batch entry. */
		private final int seqLen;

		/** The input activation, {@code [batch, seq_len, dim]}. */
		private final PackedCollection input;

		/** The normalization scale, {@code [dim]}, near one. */
		private final PackedCollection normWeights = wave(shape(PINNED_DIM), 0.2, 0.53, 0.7, 1.0);

		/** The LayerNorm shift, {@code [dim]}. */
		private final PackedCollection normBiases = wave(shape(PINNED_DIM), 0.1, 0.41, 0.2, 0.0);

		/** The fused gate+linear projection, {@code [2 * inner, dim]}. */
		private final PackedCollection weightIn = wave(shape(2 * PINNED_INNER, PINNED_DIM), 0.9, 0.29, 0.5, 0.0);

		/** The fused projection bias, {@code [2 * inner]}. */
		private final PackedCollection biasIn = wave(shape(2 * PINNED_INNER), 0.1, 0.61, 0.3, 0.0);

		/** The down projection, {@code [dim, inner]}. */
		private final PackedCollection weightOut = wave(shape(PINNED_DIM, PINNED_INNER), 0.7, 0.23, 0.9, 0.0);

		/** The down projection bias, {@code [dim]}. */
		private final PackedCollection biasOut = wave(shape(PINNED_DIM), 0.1, 0.47, 0.4, 0.0);

		/** The adaLN multiplier of every batch entry and channel, {@code [batch, dim]}, near one. */
		private final PackedCollection modScale = wave(shape(PINNED_BATCH, PINNED_DIM), 0.3, 0.71, 0.6, 1.0);

		/** The adaLN offset of every batch entry and channel, {@code [batch, dim]}. */
		private final PackedCollection modShift = wave(shape(PINNED_BATCH, PINNED_DIM), 0.2, 0.19, 0.8, 0.0);

		/**
		 * Creates the weights, input and modulation of a configuration whose batch entries hold the
		 * given number of positions.
		 *
		 * @param seqLen the number of positions of every batch entry
		 */
		private PinnedWeights(int seqLen) {
			this.seqLen = seqLen;
			this.input = wave(shape(PINNED_BATCH, seqLen, PINNED_DIM), 0.8, 0.37, 0.11, 0.0);
		}

		/**
		 * Returns the shape of the input activation.
		 *
		 * @return {@code [batch, seq_len, dim]}
		 */
		private TraversalPolicy inputShape() {
			return shape(PINNED_BATCH, seqLen, PINNED_DIM);
		}

		/**
		 * Builds the gated-linear feed-forward of these weights with a SiLU gate and dense projections,
		 * through {@link FeedForwardFeatures#gatedLinearFeedForward}.
		 *
		 * @param inputShape the activation shape
		 * @param normType   the pre-normalization family; only LayerNorm reads the norm biases
		 * @param modScale   the adaLN multiplier, or {@code null} for no modulation
		 * @param modShift   the adaLN offset, or {@code null} for no modulation
		 * @return the feed-forward block
		 */
		private Block feedForward(TraversalPolicy inputShape, NormalizationType normType,
								  Producer<PackedCollection> modScale, Producer<PackedCollection> modShift) {
			return gatedLinearFeedForward(inputShape, normType, normWeights, normBiases(normType),
					weightIn, biasIn, weightOut, biasOut, modScale, modShift, silu(), ProjectionFactory.dense());
		}

		/**
		 * Returns the norm biases a normalization of the given family reads: the LayerNorm shift, and
		 * none for an RMSNorm.
		 *
		 * @param normType the pre-normalization family
		 * @return the norm biases, or {@code null}
		 */
		private PackedCollection normBiases(NormalizationType normType) {
			return normType == NormalizationType.LAYER ? normBiases : null;
		}

		/**
		 * Builds the {@code feed_forward.pdsl} layer for the given normalization family and modulation
		 * directly through {@link PdslLoader}, bound with these weights, a SiLU gate and dense
		 * projections.
		 *
		 * @param inputShape the activation shape
		 * @param normType   the pre-normalization family
		 * @param modScale   the adaLN multiplier, or {@code null} for the unmodulated layer
		 * @param modShift   the adaLN offset, or {@code null} for the unmodulated layer
		 * @return the layer
		 */
		private Block assetLayer(TraversalPolicy inputShape, NormalizationType normType,
								 Producer<PackedCollection> modScale, Producer<PackedCollection> modShift) {
			TraversalPolicy gatedShape = shape(PINNED_BATCH, seqLen, PINNED_INNER);
			ProjectionFactory dense = ProjectionFactory.dense();

			Map<String, Object> args = new HashMap<>();
			args.put("batch", PINNED_BATCH);
			args.put("seq_len", seqLen);
			args.put("dim", PINNED_DIM);
			args.put("inner_dim", PINNED_INNER);
			args.put("norm_weights", normWeights);
			args.put("norm_biases", normBiases(normType));
			args.put("epsilon", normType.defaultEpsilon());
			args.put("mod_scale", modScale);
			args.put("mod_shift", modShift);
			args.put("in_projection", dense.create(inputShape, weightIn, biasIn, AdapterConfig.TargetLayer.FFN_GATE));
			args.put("out_projection", dense.create(gatedShape, weightOut, biasOut, AdapterConfig.TargetLayer.FFN_OUT));
			args.put("gate_activation", silu().apply(gatedShape));

			String layer = "gated_linear_feed_forward_"
					+ (normType == NormalizationType.RMS ? "rmsnorm" : "layernorm")
					+ (modScale == null ? "" : "_modulated");
			PdslLoader loader = new PdslLoader();
			return loader.buildLayer(loader.parseResource(FEED_FORWARD_ASSET), layer, inputShape, args);
		}

		/**
		 * The pinned feed-forward computed on the host in double precision, independently of the graph:
		 * every position is normalized over its {@code dim} features (centered and divided by its
		 * standard deviation for LayerNorm, divided by its root mean square for RMSNorm, each with the
		 * family's default epsilon), scaled by the norm weights and shifted by the LayerNorm biases,
		 * then modulated, projected to the fused {@code [linear | gate]} halves, gated by the SiLU of
		 * the gate half and projected back to {@code dim}.
		 *
		 * @param normType the pre-normalization family
		 * @param modScale the adaLN multiplier, {@code [batch, dim]}, or {@code null} for no modulation
		 * @param modShift the adaLN offset, {@code [batch, dim]}, or {@code null} for no modulation
		 * @return the output, flattened {@code [batch, seq_len, dim]}
		 */
		private double[] hostReference(NormalizationType normType, PackedCollection modScale,
									   PackedCollection modShift) {
			double[] x = input.doubleStream().toArray();
			double[] scale = modScale == null ? null : modScale.doubleStream().toArray();
			double[] offset = modShift == null ? null : modShift.doubleStream().toArray();

			double[] out = new double[x.length];
			for (int b = 0; b < PINNED_BATCH; b++) {
				for (int s = 0; s < seqLen; s++) {
					int row = (b * seqLen + s) * PINNED_DIM;
					double[] normalized = hostNormalize(normType, Arrays.copyOfRange(x, row, row + PINNED_DIM));
					for (int d = 0; scale != null && d < PINNED_DIM; d++) {
						normalized[d] = scale[b * PINNED_DIM + d] * normalized[d] + offset[b * PINNED_DIM + d];
					}

					double[] projected = hostGatedProjection(normalized);
					for (int d = 0; d < PINNED_DIM; d++) {
						out[row + d] = projected[d];
					}
				}
			}
			return out;
		}

		/**
		 * Normalizes one position's {@code dim} features on the host as the given family does, then
		 * applies the norm weights and, for LayerNorm, the norm biases.
		 *
		 * @param normType the pre-normalization family
		 * @param features the position's features
		 * @return the normalized features
		 */
		private double[] hostNormalize(NormalizationType normType, double[] features) {
			boolean layer = normType == NormalizationType.LAYER;
			double[] g = normWeights.doubleStream().toArray();
			double[] beta = layer ? normBiases.doubleStream().toArray() : new double[features.length];
			double mean = Arrays.stream(features).average().orElse(0.0);
			double meanSquare = Arrays.stream(features).map(v -> v * v).average().orElse(0.0);
			double center = layer ? mean : 0.0;
			double statistic = layer ? meanSquare - mean * mean : meanSquare;
			double divisor = Math.sqrt(statistic + normType.defaultEpsilon());

			double[] normalized = new double[features.length];
			for (int d = 0; d < features.length; d++) {
				normalized[d] = (features[d] - center) / divisor * g[d] + beta[d];
			}
			return normalized;
		}

		/**
		 * Applies the gating body on the host to one normalized position: the fused projection, the
		 * linear half times the SiLU of the gate half, and the down projection.
		 *
		 * @param normalized the position's normalized (and possibly modulated) features
		 * @return the position's output features
		 */
		private double[] hostGatedProjection(double[] normalized) {
			double[] wIn = weightIn.doubleStream().toArray();
			double[] bIn = biasIn.doubleStream().toArray();
			double[] wOut = weightOut.doubleStream().toArray();
			double[] bOut = biasOut.doubleStream().toArray();

			double[] gated = new double[PINNED_INNER];
			for (int k = 0; k < PINNED_INNER; k++) {
				double linear = bIn[k];
				double gate = bIn[PINNED_INNER + k];
				for (int d = 0; d < PINNED_DIM; d++) {
					linear += wIn[k * PINNED_DIM + d] * normalized[d];
					gate += wIn[(PINNED_INNER + k) * PINNED_DIM + d] * normalized[d];
				}
				gated[k] = linear * gate / (1.0 + Math.exp(-gate));
			}

			double[] output = new double[PINNED_DIM];
			for (int d = 0; d < PINNED_DIM; d++) {
				output[d] = bOut[d];
				for (int k = 0; k < PINNED_INNER; k++) {
					output[d] += wOut[d * PINNED_INNER + k] * gated[k];
				}
			}
			return output;
		}
	}

	/**
	 * A deterministic collection: {@code amplitude * sin(frequency * i + phase) + offset} for element
	 * {@code i}, computed on the device.
	 *
	 * @param shape     the shape of the collection
	 * @param amplitude the amplitude of the sinusoid
	 * @param frequency the angular step between consecutive elements
	 * @param phase     the angle of element zero
	 * @param offset    the value the sinusoid oscillates around
	 * @return the collection
	 */
	private PackedCollection wave(TraversalPolicy shape, double amplitude, double frequency,
								  double phase, double offset) {
		return sin(integers(0, shape.getTotalSize()).multiply(frequency).add(phase))
				.multiply(amplitude).add(offset).reshape(shape).evaluate();
	}

	/**
	 * Asserts that two activations have the same length and agree element-wise within a tolerance
	 * relative to the magnitude of each expected value (absolute for an expected value below one).
	 *
	 * @param label     the comparison, for failure messages
	 * @param expected  the expected values
	 * @param actual    the actual values
	 * @param tolerance the largest difference permitted per unit of magnitude
	 */
	private void assertClose(String label, double[] expected, double[] actual, double tolerance) {
		assertEquals(label + ": length", expected.length, actual.length);
		for (int i = 0; i < expected.length; i++) {
			assertEquals(label + ": element " + i, expected[i], actual[i],
					tolerance * Math.max(1.0, Math.abs(expected[i])));
		}
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
