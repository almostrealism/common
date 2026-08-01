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

package org.almostrealism.ml.audio;

import io.almostrealism.compute.ParallelProcess;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Standalone tests for {@link NumberConditioner}, independent of Stable Audio 3. They verify the
 * scalar-to-embedding numerics against a host-side reference computation of the Fourier features plus
 * the learned projection (on synthetic weights and a known range), that the embedding composes as a
 * {@link CollectionProducer} inside a compiled graph, and that the two consumption forms — the
 * cross-attention token shape {@code [1, 1, outDim]} and the global-conditioning vector shape
 * {@code [1, outDim]} — carry the same embedding. No model weights are involved.
 */
public class NumberConditionerTest extends TestSuiteBase {

	/** Output embedding dimensionality used throughout the tests. */
	private static final int OUT_DIM = 8;

	/** Number of Fourier feature components ({@code FOURIER_DIM / 2} sin/cos pairs). */
	private static final int FOURIER_DIM = 4;

	/** Lower bound of the Stable Audio 3 {@code seconds_total} range, reused here as a known range. */
	private static final double MIN_VAL = 0.0;

	/** Upper bound of the Stable Audio 3 {@code seconds_total} range. */
	private static final double MAX_VAL = 384.0;

	/** Synthetic Fourier projection weights of shape {@code [FOURIER_DIM/2, 1]}. */
	private final PackedCollection fourierWeights =
			new PackedCollection(shape(FOURIER_DIM / 2, 1)).randnFill();

	/** Synthetic projection weight of shape {@code [OUT_DIM, FOURIER_DIM]}. */
	private final PackedCollection projWeight =
			new PackedCollection(shape(OUT_DIM, FOURIER_DIM)).randnFill();

	/** Synthetic projection bias of shape {@code [OUT_DIM]}. */
	private final PackedCollection projBias =
			new PackedCollection(shape(OUT_DIM)).randnFill();

	/**
	 * The scalar-to-embedding output must match a direct host reference of the Fourier features plus
	 * the learned projection, with the bias applied, over the Stable Audio 3 duration range.
	 */
	@Test(timeout = 120000)
	public void embeddingMatchesReferenceWithBias() {
		assertEmbeddingMatchesReference(MIN_VAL, MAX_VAL, 30.0, true);
	}

	/**
	 * The same numeric agreement must hold with no projection bias, exercising the bias-free path.
	 */
	@Test(timeout = 120000)
	public void embeddingMatchesReferenceWithoutBias() {
		assertEmbeddingMatchesReference(MIN_VAL, MAX_VAL, 30.0, false);
	}

	/**
	 * The range is configurable: a unit range with an interior value must still match the reference,
	 * proving the normalization uses {@code minVal}/{@code maxVal} rather than a baked-in range.
	 */
	@Test(timeout = 120000)
	public void embeddingMatchesReferenceForConfiguredRange() {
		assertEmbeddingMatchesReference(0.0, 1.0, 0.25, true);
	}

	/**
	 * Values outside {@code [minVal, maxVal]} must clamp to the endpoints, and the endpoint embeddings
	 * themselves must be distinct (the embedding actually depends on the input).
	 */
	@Test(timeout = 120000)
	public void clampingAndDistinctEndpoints() {
		NumberConditioner conditioner = conditioner(true);

		PackedCollection belowRange = evaluate(conditioner.embed(MIN_VAL - 50.0));
		PackedCollection atMin = evaluate(conditioner.embed(MIN_VAL));
		PackedCollection aboveRange = evaluate(conditioner.embed(MAX_VAL + 100.0));
		PackedCollection atMax = evaluate(conditioner.embed(MAX_VAL));

		assertTrue("Value below the range must clamp to minVal", compare(atMin, belowRange) < 1e-5);
		assertTrue("Value above the range must clamp to maxVal", compare(atMax, aboveRange) < 1e-5);

		double endpointDifference = compare(atMin, atMax);
		log("minVal vs maxVal embedding difference = " + endpointDifference);
		assertTrue("minVal and maxVal must map to distinct embeddings", endpointDifference > 1e-3);
	}

	/**
	 * The embedding must compose as a {@link CollectionProducer} within a larger compiled graph rather
	 * than being a pre-evaluated collection. Scaling the embedding by a downstream producer op and
	 * evaluating the composite must equal the reference embedding scaled by the same factor.
	 */
	@Test(timeout = 120000)
	public void composesAsProducerInGraph() {
		NumberConditioner conditioner = conditioner(true);
		double value = 30.0;

		CollectionProducer embedding = conditioner.embed(value);
		CollectionProducer scaled = embedding.multiply(c(2.0));

		PackedCollection out = evaluate(scaled);
		double[] expected = reference(value, MIN_VAL, MAX_VAL, true);
		double[] actual = out.toArray();

		assertEquals(OUT_DIM, actual.length);
		for (int i = 0; i < OUT_DIM; i++) {
			assertEquals(2.0 * expected[i], actual[i], 1e-4);
		}
	}

	/**
	 * Both consumption forms must carry the same embedding: the global-conditioning vector is
	 * {@code [1, outDim]} and the cross-attention token is {@code [1, 1, outDim]}, and their values
	 * match {@link NumberConditioner#embed(double)} element-wise.
	 */
	@Test(timeout = 120000)
	public void dualRouteShapesAndValues() {
		NumberConditioner conditioner = conditioner(true);
		double value = 30.0;

		PackedCollection base = evaluate(conditioner.embed(value));
		PackedCollection global = evaluate(conditioner.globalCond(value));
		PackedCollection token = evaluate(conditioner.crossAttentionToken(value));

		// Global-conditioning vector shape [1, outDim].
		assertEquals(2, global.getShape().getDimensions());
		assertEquals(1, global.getShape().length(0));
		assertEquals(OUT_DIM, global.getShape().length(1));

		// Cross-attention token shape [1, 1, outDim] — a single conditioning token.
		assertEquals(3, token.getShape().getDimensions());
		assertEquals(1, token.getShape().length(0));
		assertEquals(1, token.getShape().length(1));
		assertEquals(OUT_DIM, token.getShape().length(2));

		double[] baseValues = base.toArray();
		double[] globalValues = global.toArray();
		double[] tokenValues = token.toArray();

		for (int i = 0; i < OUT_DIM; i++) {
			assertEquals(baseValues[i], globalValues[i], 1e-6);
			assertEquals(baseValues[i], tokenValues[i], 1e-6);
		}
	}

	/**
	 * Builds a conditioner over the given range from the shared synthetic weights.
	 *
	 * @param withBias whether to include the projection bias
	 * @return the conditioner
	 */
	private NumberConditioner conditioner(boolean withBias) {
		return new NumberConditioner(MIN_VAL, MAX_VAL, OUT_DIM, fourierWeights, projWeight,
				withBias ? projBias : null);
	}

	/**
	 * Asserts the compiled embedding matches the host reference for the given range, value and bias
	 * configuration.
	 *
	 * @param minV     lower bound of the range
	 * @param maxV     upper bound of the range
	 * @param value    the input scalar
	 * @param withBias whether the projection bias is applied
	 */
	private void assertEmbeddingMatchesReference(double minV, double maxV, double value, boolean withBias) {
		NumberConditioner conditioner = new NumberConditioner(minV, maxV, OUT_DIM, fourierWeights,
				projWeight, withBias ? projBias : null);

		PackedCollection out = evaluate(conditioner.embed(value));
		double[] expected = reference(value, minV, maxV, withBias);
		double[] actual = out.toArray();

		assertEquals(OUT_DIM, actual.length);
		for (int i = 0; i < OUT_DIM; i++) {
			assertEquals(expected[i], actual[i], 1e-4);
		}
	}

	/**
	 * Host-side reference of the conditioner computation: clamp and normalize the scalar, expand into
	 * Fourier features ({@code cos} components followed by {@code sin} components), then apply the
	 * learned projection and optional bias.
	 *
	 * @param value    the input scalar
	 * @param minV     lower bound of the range
	 * @param maxV     upper bound of the range
	 * @param withBias whether to add the projection bias
	 * @return the reference embedding of length {@link #OUT_DIM}
	 */
	private double[] reference(double value, double minV, double maxV, boolean withBias) {
		double clamped = Math.max(minV, Math.min(maxV, value));
		double normalized = (clamped - minV) / (maxV - minV);

		int half = FOURIER_DIM / 2;
		double[] fourier = new double[FOURIER_DIM];
		for (int j = 0; j < half; j++) {
			double projected = 2.0 * Math.PI * normalized * fourierWeights.valueAt(j, 0);
			fourier[j] = Math.cos(projected);
			fourier[half + j] = Math.sin(projected);
		}

		double[] embedding = new double[OUT_DIM];
		for (int i = 0; i < OUT_DIM; i++) {
			double sum = 0.0;
			for (int j = 0; j < FOURIER_DIM; j++) {
				sum += fourier[j] * projWeight.valueAt(i, j);
			}
			if (withBias) {
				sum += projBias.valueAt(i);
			}
			embedding[i] = sum;
		}
		return embedding;
	}

	/**
	 * Evaluates a producer at the test boundary (top of the call stack), using the optimization pass
	 * the framework requires for standalone producer evaluation.
	 *
	 * @param producer the producer to evaluate
	 * @return the evaluated collection
	 */
	private PackedCollection evaluate(Producer<PackedCollection> producer) {
		return ((Evaluable<PackedCollection>) ((ParallelProcess) producer).optimize().get()).evaluate();
	}
}
