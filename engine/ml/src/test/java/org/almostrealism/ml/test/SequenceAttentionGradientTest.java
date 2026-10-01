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

package org.almostrealism.ml.test;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.layers.NormalizationType;
import org.almostrealism.layers.ProjectionFactory;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.ModelTestFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Verifies that the backward pass of {@link AttentionFeatures#sequenceAttention} delivers the
 * correct gradient to every slice of the fused QKV projection weight, and that the causal
 * option propagates correct gradients as well.
 *
 * <p>The scalar loss is {@code L = sum(output * G)} for a fixed random tensor {@code G}, so
 * {@code dL/dOutput = G}. The analytic gradient of {@code L} with respect to the fused weight is
 * captured from the backward pass through
 * {@link ModelTestFeatures#gradientRecorder(List) a parameter update that records the gradient}
 * instead of applying it, and is compared against a central finite-difference estimate
 * computed at the test boundary, separately for the query, key and value rows.</p>
 */
public class SequenceAttentionGradientTest extends TestSuiteBase implements AttentionFeatures, ModelTestFeatures {
	/** Sequence length of the tiny attention block. */
	private static final int SEQ_LEN = 4;
	/** Model dimension of the tiny attention block. */
	private static final int DIM = 8;
	/** Number of attention heads. */
	private static final int HEADS = 2;
	/** Finite-difference step. */
	private static final double EPS = 1e-2;
	/** Tolerance on the difference relative to the largest gradient magnitude of the slice. */
	private static final double RELATIVE_TOLERANCE = 0.02;

	/**
	 * Gradient of bidirectional sequence attention with respect to the Q, K and V slices of the
	 * fused projection weight matches finite differences.
	 */
	@Test(timeout = 10 * 60000)
	public void qkvSliceGradients() {
		verifyQkvGradients(false);
	}

	/**
	 * Gradient of causal sequence attention with respect to the Q, K and V slices of the fused
	 * projection weight matches finite differences.
	 */
	@Test(timeout = 10 * 60000)
	public void causalQkvSliceGradients() {
		verifyQkvGradients(true);
	}

	/**
	 * A bidirectional attention model built after a causal one has been compiled, run and
	 * destroyed still compiles its backward pass and delivers correct gradients, so destroying a
	 * model leaves nothing behind that a later model depends on.
	 */
	@Test(timeout = 10 * 60000)
	public void bidirectionalAfterCausalQkvSliceGradients() {
		verifyQkvGradients(true);
		verifyQkvGradients(false);
	}

	/**
	 * Builds a tiny sequence attention model, captures the analytic gradient of the fused QKV
	 * weight and compares each slice against finite differences.
	 *
	 * @param causal whether the attention block applies the causal mask
	 */
	private void verifyQkvGradients(boolean causal) {
		Random random = new Random(7);
		int dimHead = DIM / HEADS;
		TraversalPolicy inputShape = shape(1, SEQ_LEN, DIM);

		PackedCollection qkv = randn(shape(3 * DIM, DIM), 0.0, 0.5, random).evaluate();
		PackedCollection out = randn(shape(DIM, DIM), 0.0, 0.5, random).evaluate();
		PackedCollection invFreq = new PackedCollection(shape(dimHead / 2)).fill(1.0);
		PackedCollection input = randn(inputShape, 0.0, 1.0, random).evaluate();
		PackedCollection outputGradient = randn(inputShape, 0.0, 1.0, random).evaluate();

		List<PackedCollection> captured = new ArrayList<>();
		Model model = new Model(inputShape, gradientRecorder(captured));
		model.add(sequenceAttention(1, SEQ_LEN, DIM, HEADS, qkv, out,
				null, null, null, null, invFreq,
				ProjectionFactory.dense(), NormalizationType.LAYER, null, null, 0.0, causal));
		CompiledModel compiled = model.compile(true);

		compiled.forward(input);
		compiled.backward(outputGradient);

		PackedCollection analytic = captured.stream()
				.filter(c -> c.getShape().getTotalSize() == qkv.getShape().getTotalSize())
				.findFirst()
				.orElseThrow(() -> new AssertionError("No gradient captured for the QKV weight"));

		Evaluable<PackedCollection> loss = multiply(cv(inputShape, 0), cv(inputShape, 1)).sum().get();

		double[] numeric = centralDifferences(qkv, EPS,
				() -> loss.evaluate(compiled.forward(input), outputGradient).toDouble());

		String[] sliceNames = { "Q", "K", "V" };
		for (int slice = 0; slice < 3; slice++) {
			double maxAbs = 0.0;
			double maxDiff = 0.0;

			for (int r = slice * DIM; r < (slice + 1) * DIM; r++) {
				for (int c = 0; c < DIM; c++) {
					int index = r * DIM + c;
					double actual = analytic.toDouble(index);
					maxAbs = Math.max(maxAbs, Math.abs(numeric[index]));
					maxDiff = Math.max(maxDiff, Math.abs(numeric[index] - actual));
				}
			}

			log(sliceNames[slice] + " slice: max |numeric|=" + maxAbs + " max |diff|=" + maxDiff);
			Assert.assertTrue(sliceNames[slice] + " slice gradient is trivially zero", maxAbs > 1e-3);
			Assert.assertTrue(sliceNames[slice] + " slice gradient differs from finite difference by " +
					maxDiff + " (max magnitude " + maxAbs + ")", maxDiff <= RELATIVE_TOLERANCE * maxAbs);
		}

		compiled.destroy();
	}
}
