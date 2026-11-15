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

import java.util.Arrays;

/**
 * Parity and reference pins for the pre-norm residual feed-forward sub-layer that
 * {@link TransformerBlockFeatures#feedForwardResidualSublayer} now builds from the
 * {@code feed_forward_sublayer.pdsl} asset.
 *
 * <p>The sub-layer was previously assembled inside {@link TransformerBlockFeatures#transformerBlock} as
 * a {@code residual(gatedLinearFeedForward(...))} — the pre-normalization, an optional adaLN modulation
 * of the normalized activation and the gated-linear feed-forward body built as one block, with (in
 * adaLN conditioning) the feed-forward output wrapped in a {@link SequentialBlock} with an
 * {@code adaptiveGate} stage before the residual add. These tests pin the migrated asset three ways:</p>
 * <ul>
 *   <li><b>Parity against the Java composition.</b> Each parity test builds the sub-layer once through
 *       the asset loader and once as the exact Java composition the migration replaced
 *       ({@code residual(gatedLinearFeedForward)}, with an {@code adaptiveGate} stage ahead of the
 *       residual add when modulated), binds the same weights and conditioning, and asserts the two
 *       forward passes agree to a tight tolerance. {@code gatedLinearFeedForward} and
 *       {@code adaptiveGate} are unchanged pre-migration primitives, so this is a comparison against the
 *       reference, not self-agreement.</li>
 *   <li><b>Independent host oracle.</b> {@link #matchesHostReference} recomputes the whole sub-layer in
 *       double precision directly from the weights — normalize, modulate, the fused projection and gated
 *       product, the down projection, the gate and the residual add — independently of both the asset and
 *       the Java assembly, so the asset is pinned to the arithmetic and not only to another graph.</li>
 *   <li><b>Algebraic pin.</b> {@link #closedGateMakesSublayerIdentity} drives the gate to zero and
 *       asserts the sub-layer collapses to the identity, independent of the feed-forward weights — a
 *       property of the residual-with-gate wiring that holds only if the gate multiplies the
 *       feed-forward output before the residual add.</li>
 * </ul>
 */
public class FeedForwardSublayerAssetTest extends TestSuiteBase implements TransformerBlockFeatures {

	/** Batch dimension; two, so each batch entry's per-channel modulation reaches its own rows. */
	private static final int BATCH = 2;
	/** Sequence length; more than one, so the per-channel modulation is broadcast across positions. */
	private static final int SEQ_LEN = 3;
	/** Model dimension. */
	private static final int DIM = 4;
	/** Feed-forward inner dimension (each half of the fused projection). */
	private static final int INNER = 6;
	/** Tolerance for the parity comparison between the asset and the Java composition. */
	private static final double TOLERANCE = 1e-5;
	/** Tolerance for the host-reference comparison, relative to the magnitude of each expected value. */
	private static final double HOST_TOLERANCE = 1e-4;

	/**
	 * The asset's LayerNorm adaLN-modulated sub-layer must match, element for element, the Java
	 * composition {@code residual(gatedLinearFeedForward(layernorm) -> adaptiveGate)} it replaced.
	 */
	@Test(timeout = 240000)
	public void layernormModulatedMatchesJavaComposition() {
		assertParity(NormalizationType.LAYER, true);
	}

	/**
	 * The asset's LayerNorm plain pre-norm sub-layer (no modulation) must match the Java composition
	 * {@code residual(gatedLinearFeedForward(layernorm))} it replaced — the {@code modulation == null}
	 * path used by the prepend conditioning mode.
	 */
	@Test(timeout = 240000)
	public void layernormUnmodulatedMatchesJavaComposition() {
		assertParity(NormalizationType.LAYER, false);
	}

	/**
	 * The asset's RMSNorm adaLN-modulated sub-layer must match the Java composition
	 * {@code residual(gatedLinearFeedForward(rmsnorm) -> adaptiveGate)} it replaced.
	 */
	@Test(timeout = 240000)
	public void rmsnormModulatedMatchesJavaComposition() {
		assertParity(NormalizationType.RMS, true);
	}

	/**
	 * The asset's RMSNorm plain pre-norm sub-layer (no modulation) must match the Java composition
	 * {@code residual(gatedLinearFeedForward(rmsnorm))} it replaced — the {@code modulation == null} path
	 * with the RMSNorm normalization family.
	 */
	@Test(timeout = 240000)
	public void rmsnormUnmodulatedMatchesJavaComposition() {
		assertParity(NormalizationType.RMS, false);
	}

	/**
	 * The asset's RMSNorm adaLN-modulated sub-layer must match the Java composition when the
	 * pre-normalization bias is {@code null} — the production RMSNorm checkpoint layout, where
	 * {@link org.almostrealism.ml.audio.DiffusionTransformer} supplies a {@code null} shift. This
	 * exercises the asset's optional-bias binding and the bias-free RMSNorm execution path.
	 */
	@Test(timeout = 240000)
	public void rmsnormModulatedNullBiasMatchesJavaComposition() {
		assertParity(NormalizationType.RMS, true, true);
	}

	/**
	 * The asset's RMSNorm plain pre-norm sub-layer (no modulation) must match the Java composition when
	 * the pre-normalization bias is {@code null} — the {@code modulation == null} path with the bias-free
	 * RMSNorm layout production supplies.
	 */
	@Test(timeout = 240000)
	public void rmsnormUnmodulatedNullBiasMatchesJavaComposition() {
		assertParity(NormalizationType.RMS, false, true);
	}

	/**
	 * The migrated sub-layer computes, for every combination of normalization family and modulation,
	 * what the same arithmetic computes on the host in double precision — normalize, modulate, the gated
	 * feed-forward, the gate and the residual add — independently of both the asset and the Java
	 * assembly.
	 */
	@Test(timeout = 240000)
	public void matchesHostReference() {
		for (NormalizationType normType : NormalizationType.values()) {
			for (boolean modulated : new boolean[] { false, true }) {
				Weights w = new Weights();
				Block body = gluFeedForward(blockShape(), w.weightIn, w.biasIn, w.weightOut, w.biasOut,
						silu(), ProjectionFactory.dense());
				Block asset = feedForwardResidualSublayer(blockShape(), normType,
						w.normWeight, w.normBias(normType),
						modulated ? cp(w.modScale) : null,
						modulated ? cp(w.modShift) : null,
						modulated ? cp(w.gate) : null,
						body);

				double[] expected = w.hostReference(normType, modulated);
				double[] actual = flatten(run(asset, w.input));
				assertClose(normType + (modulated ? " modulated" : " unmodulated") + " vs host",
						expected, actual);
			}
		}
	}

	/**
	 * The norm-free gated-linear body that {@link FeedForwardFeatures#gluFeedForward} builds computes,
	 * over an already-normalized input, exactly the fused-projection gated product and down projection
	 * the host computes — pinning the new loader on its own, independently of the pre-normalization and
	 * residual wiring the sub-layer adds around it.
	 */
	@Test(timeout = 240000)
	public void gluFeedForwardBodyMatchesHostReference() {
		Weights w = new Weights();
		Block body = gluFeedForward(blockShape(), w.weightIn, w.biasIn, w.weightOut, w.biasOut,
				silu(), ProjectionFactory.dense());

		// Snapshot the host reference before run() compiles and destroys the model, which releases the
		// device memory of the caller-owned projection weights the host reference reads.
		double[] expected = w.hostBody();
		double[] actual = flatten(run(body, w.input));
		assertClose("glu body vs host", expected, actual);
	}

	/**
	 * With the adaLN gate driven fully closed (gate multiplier zero) the sub-layer's residual branch
	 * contributes nothing and the sub-layer must reduce to the identity, regardless of the feed-forward
	 * weights. This pins the residual-with-gate wiring against a direct algebraic reference rather than
	 * against the Java composition.
	 */
	@Test(timeout = 240000)
	public void closedGateMakesSublayerIdentity() {
		Weights w = new Weights();

		PackedCollection modScale = new PackedCollection(shape(BATCH, DIM)).fill(1.0);
		PackedCollection modShift = new PackedCollection(shape(BATCH, DIM));
		PackedCollection gate = new PackedCollection(shape(BATCH, DIM));

		Block body = gluFeedForward(blockShape(), w.weightIn, w.biasIn, w.weightOut, w.biasOut,
				silu(), ProjectionFactory.dense());
		Block sublayer = feedForwardResidualSublayer(blockShape(), NormalizationType.LAYER,
				w.normWeight, w.normBias, cp(modScale), cp(modShift), cp(gate), body);

		PackedCollection out = run(sublayer, w.input);
		double diff = compare(w.input, out);
		log("closed-gate feed-forward sub-layer vs input difference = " + diff);
		assertTrue("A closed adaLN gate must collapse the feed-forward sub-layer to the identity",
				diff < 1e-5);
	}

	/**
	 * The three adaLN conditioning producers must be supplied all together or all absent: a partial
	 * combination is rejected, rather than silently building an unmodulated block (when only
	 * {@code modScale} is null) or deferring the failure to producer binding (when only {@code modScale}
	 * is supplied). This pins the all-or-none validation of {@link #feedForwardResidualSublayer}.
	 */
	@Test(timeout = 240000)
	public void partialConditioningIsRejected() {
		Weights w = new Weights();
		PackedCollection mod = new PackedCollection(shape(BATCH, DIM)).fill(1.0);

		assertTrue("partial conditioning (only modScale supplied) must be rejected",
				rejectsConditioning(w, mod, null, null));
		assertTrue("partial conditioning (only gate supplied) must be rejected",
				rejectsConditioning(w, null, null, mod));
	}

	/**
	 * Builds the sub-layer with the given (possibly partial) conditioning and reports whether it is
	 * rejected with an {@link IllegalArgumentException}, as the all-or-none validation requires.
	 *
	 * @param w        the shared synthetic weights
	 * @param modScale the adaLN scale modulation, or {@code null}
	 * @param modShift the adaLN shift modulation, or {@code null}
	 * @param gate     the adaLN output gate, or {@code null}
	 * @return whether the builder rejected the combination
	 */
	private boolean rejectsConditioning(Weights w, PackedCollection modScale,
										PackedCollection modShift, PackedCollection gate) {
		Block body = gluFeedForward(blockShape(), w.weightIn, w.biasIn, w.weightOut, w.biasOut,
				silu(), ProjectionFactory.dense());
		try {
			feedForwardResidualSublayer(blockShape(), NormalizationType.LAYER, w.normWeight, w.normBias,
					modScale == null ? null : cp(modScale),
					modShift == null ? null : cp(modShift),
					gate == null ? null : cp(gate),
					body);
			return false;
		} catch (IllegalArgumentException expected) {
			return true;
		}
	}

	/**
	 * Builds the feed-forward residual sub-layer both through the asset loader and as the Java
	 * composition it replaced, and asserts the two forward passes agree.
	 *
	 * @param normType  the normalization family
	 * @param modulated whether to apply the adaLN modulation and gate
	 */
	private void assertParity(NormalizationType normType, boolean modulated) {
		assertParity(normType, modulated, false);
	}

	/**
	 * Builds the feed-forward residual sub-layer both through the asset loader and as the Java
	 * composition it replaced ({@code residual(gatedLinearFeedForward)}, with an {@code adaptiveGate}
	 * stage ahead of the residual add when modulated), and asserts the two forward passes agree.
	 *
	 * @param normType  the normalization family
	 * @param modulated whether to apply the adaLN modulation and gate
	 * @param nullBias  whether the pre-normalization bias is {@code null} (the bias-free layout
	 *                  production supplies for RMSNorm) rather than a deterministic tensor
	 */
	private void assertParity(NormalizationType normType, boolean modulated, boolean nullBias) {
		Weights w = new Weights();
		PackedCollection normBias = nullBias ? null : w.normBias;

		Block feedForward = gatedLinearFeedForward(blockShape(), normType, w.normWeight, normBias,
				w.weightIn, w.biasIn, w.weightOut, w.biasOut,
				modulated ? cp(w.modScale) : null, modulated ? cp(w.modShift) : null,
				silu(), ProjectionFactory.dense());
		Block referenceBlock;
		if (modulated) {
			SequentialBlock gatedFeedForward = new SequentialBlock(blockShape());
			gatedFeedForward.add(feedForward);
			gatedFeedForward.add(adaptiveGate(blockShape(), cp(w.gate)));
			referenceBlock = residual(gatedFeedForward);
		} else {
			referenceBlock = residual(feedForward);
		}
		PackedCollection reference = run(referenceBlock, w.input);

		Block body = gluFeedForward(blockShape(), w.weightIn, w.biasIn, w.weightOut, w.biasOut,
				silu(), ProjectionFactory.dense());
		Block asset = feedForwardResidualSublayer(blockShape(), normType, w.normWeight, normBias,
				modulated ? cp(w.modScale) : null,
				modulated ? cp(w.modShift) : null,
				modulated ? cp(w.gate) : null,
				body);
		PackedCollection migrated = run(asset, w.input);

		double diff = compare(reference, migrated);
		log(normType + (modulated ? " modulated" : " unmodulated")
				+ " asset vs Java composition difference = " + diff);
		assertTrue("The migrated feed-forward sub-layer must match the Java composition it replaced",
				diff < TOLERANCE);
	}

	/**
	 * The sub-layer input and output shape {@code [batch, seq_len, dim]}.
	 *
	 * @return the block shape
	 */
	private TraversalPolicy blockShape() {
		return shape(BATCH, SEQ_LEN, DIM);
	}

	/**
	 * Compiles a single block into a model and runs one forward pass over a {@code [batch, seqLen, dim]}
	 * input, cloning the result into host-owned memory before the compiled model is destroyed.
	 *
	 * @param block the block under test
	 * @param input the model input
	 * @return the forward-pass output
	 */
	private PackedCollection run(Block block, PackedCollection input) {
		Model model = new Model(blockShape());
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
	 * Flattens a collection to a host double array.
	 *
	 * @param collection the collection
	 * @return its values, flattened
	 */
	private double[] flatten(PackedCollection collection) {
		return collection.doubleStream().toArray();
	}

	/**
	 * Asserts two activations agree element-wise within {@link #HOST_TOLERANCE} relative to the
	 * magnitude of each expected value (absolute for an expected value below one).
	 *
	 * @param label    the comparison, for failure messages
	 * @param expected the expected values
	 * @param actual   the actual values
	 */
	private void assertClose(String label, double[] expected, double[] actual) {
		assertEquals(label + ": length", expected.length, actual.length);
		for (int i = 0; i < expected.length; i++) {
			assertEquals(label + ": element " + i, expected[i], actual[i],
					HOST_TOLERANCE * Math.max(1.0, Math.abs(expected[i])));
		}
	}

	/**
	 * Deterministic synthetic weights, input and modulation for the feed-forward sub-layer. Every tensor
	 * is a closed-form sinusoid of its element index, so the values are reproducible and the host
	 * reference can recompute the exact arithmetic.
	 */
	private final class Weights {
		/** The input activation, {@code [batch, seq_len, dim]}. */
		private final PackedCollection input = wave(shape(BATCH, SEQ_LEN, DIM), 0.8, 0.37, 0.11, 0.0);
		/** The pre-normalization scale, {@code [dim]}, near one. */
		private final PackedCollection normWeight = wave(shape(DIM), 0.2, 0.53, 0.7, 1.0);
		/** The LayerNorm shift, {@code [dim]}. */
		private final PackedCollection normBias = wave(shape(DIM), 0.1, 0.41, 0.2, 0.0);
		/** The fused gate+linear projection, {@code [2 * inner, dim]}. */
		private final PackedCollection weightIn = wave(shape(2 * INNER, DIM), 0.9, 0.29, 0.5, 0.0);
		/** The fused projection bias, {@code [2 * inner]}. */
		private final PackedCollection biasIn = wave(shape(2 * INNER), 0.1, 0.61, 0.3, 0.0);
		/** The down projection, {@code [dim, inner]}. */
		private final PackedCollection weightOut = wave(shape(DIM, INNER), 0.7, 0.23, 0.9, 0.0);
		/** The down projection bias, {@code [dim]}. */
		private final PackedCollection biasOut = wave(shape(DIM), 0.1, 0.47, 0.4, 0.0);
		/** The adaLN multiplier of every batch entry and channel, {@code [batch, dim]}, near one. */
		private final PackedCollection modScale = wave(shape(BATCH, DIM), 0.3, 0.71, 0.6, 1.0);
		/** The adaLN offset of every batch entry and channel, {@code [batch, dim]}. */
		private final PackedCollection modShift = wave(shape(BATCH, DIM), 0.2, 0.19, 0.8, 0.0);
		/** The adaLN gate multiplier of every batch entry and channel, {@code [batch, dim]}. */
		private final PackedCollection gate = wave(shape(BATCH, DIM), 0.4, 0.31, 0.2, 0.5);

		/**
		 * Returns the norm bias a normalization of the given family reads: the LayerNorm shift, and none
		 * for an RMSNorm.
		 *
		 * @param normType the pre-normalization family
		 * @return the norm bias, or {@code null}
		 */
		private PackedCollection normBias(NormalizationType normType) {
			return normType == NormalizationType.LAYER ? normBias : null;
		}

		/**
		 * The feed-forward sub-layer computed on the host in double precision, independently of the
		 * graph: every position is normalized over its {@code dim} features, optionally modulated, run
		 * through the gated feed-forward body, optionally gated, and added back to the residual stream.
		 *
		 * @param normType  the pre-normalization family
		 * @param modulated whether the adaLN modulation and gate are applied
		 * @return the output, flattened {@code [batch, seq_len, dim]}
		 */
		private double[] hostReference(NormalizationType normType, boolean modulated) {
			double[] x = input.doubleStream().toArray();
			double[] scale = modScale.doubleStream().toArray();
			double[] shift = modShift.doubleStream().toArray();
			double[] gateValues = gate.doubleStream().toArray();

			double[] out = new double[x.length];
			for (int b = 0; b < BATCH; b++) {
				for (int s = 0; s < SEQ_LEN; s++) {
					int row = (b * SEQ_LEN + s) * DIM;
					double[] activation = Arrays.copyOfRange(x, row, row + DIM);
					double[] normalized = hostNormalize(normType, activation);
					for (int d = 0; modulated && d < DIM; d++) {
						normalized[d] = scale[b * DIM + d] * normalized[d] + shift[b * DIM + d];
					}

					double[] projected = hostGatedProjection(normalized);
					for (int d = 0; d < DIM; d++) {
						double gated = modulated ? gateValues[b * DIM + d] * projected[d] : projected[d];
						out[row + d] = activation[d] + gated;
					}
				}
			}
			return out;
		}

		/**
		 * The norm-free gated feed-forward body computed on the host: every position of the input is run
		 * directly through the fused projection, gated product and down projection, with no
		 * pre-normalization and no residual add. This is what {@link FeedForwardFeatures#gluFeedForward}
		 * builds on its own.
		 *
		 * @return the output, flattened {@code [batch, seq_len, dim]}
		 */
		private double[] hostBody() {
			double[] x = input.doubleStream().toArray();
			double[] out = new double[x.length];
			for (int b = 0; b < BATCH; b++) {
				for (int s = 0; s < SEQ_LEN; s++) {
					int row = (b * SEQ_LEN + s) * DIM;
					double[] projected = hostGatedProjection(Arrays.copyOfRange(x, row, row + DIM));
					for (int d = 0; d < DIM; d++) {
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
			double[] g = normWeight.doubleStream().toArray();
			double[] beta = layer ? normBias.doubleStream().toArray() : new double[features.length];
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
		 * Applies the gated feed-forward body on the host to one normalized position: the fused
		 * projection, the linear half times the SiLU of the gate half, and the down projection.
		 *
		 * @param normalized the position's normalized (and possibly modulated) features
		 * @return the position's output features
		 */
		private double[] hostGatedProjection(double[] normalized) {
			double[] wIn = weightIn.doubleStream().toArray();
			double[] bIn = biasIn.doubleStream().toArray();
			double[] wOut = weightOut.doubleStream().toArray();
			double[] bOut = biasOut.doubleStream().toArray();

			double[] gated = new double[INNER];
			for (int k = 0; k < INNER; k++) {
				double linear = bIn[k];
				double gateHalf = bIn[INNER + k];
				for (int d = 0; d < DIM; d++) {
					linear += wIn[k * DIM + d] * normalized[d];
					gateHalf += wIn[(INNER + k) * DIM + d] * normalized[d];
				}
				gated[k] = linear * gateHalf / (1.0 + Math.exp(-gateHalf));
			}

			double[] output = new double[DIM];
			for (int d = 0; d < DIM; d++) {
				output[d] = bOut[d];
				for (int k = 0; k < INNER; k++) {
					output[d] += wOut[d * INNER + k] * gated[k];
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
}
