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
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.util.ModelTestFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.DoubleSupplier;

/**
 * Verifies that the backward pass of
 * {@link org.almostrealism.ml.SequenceAttentionFeatures#sequenceCrossAttention} delivers a
 * finite-difference-correct, non-zero gradient to the key and value halves of the fused context
 * projection {@code toKvWeight}, to the key normalization weights, and to the query and output
 * projections.
 *
 * <p>The keys and values of cross-attention are computed on a {@link org.almostrealism.model.Block#branch()
 * branch} of an auxiliary context input and fed to the score and context products as graph operands,
 * so a backward pass reaches them. On {@code master} the same projections were buffered through fixed
 * tensors and read back as constants, so their analytic gradient was exactly zero — this test fails
 * against that implementation and passes only when the projections carry gradient.</p>
 *
 * <p>The scalar loss is {@code L = sum(output * G)} for a fixed random tensor {@code G}, so
 * {@code dL/dOutput = G}. The analytic gradient is captured from the backward pass through
 * {@link ModelTestFeatures#gradientRecorder(List) a parameter update that records the gradient}
 * instead of applying it, and is compared against a central finite-difference estimate per slice.</p>
 */
public class SequenceCrossAttentionGradientTest extends TestSuiteBase
		implements AttentionFeatures, ModelTestFeatures {
	/** Query sequence length of the tiny cross-attention block. */
	private static final int QUERY_SEQ = 4;
	/** Context (key/value) sequence length, deliberately different from the query length. */
	private static final int CONTEXT_SEQ = 5;
	/** Model dimension of the tiny cross-attention block. */
	private static final int DIM = 8;
	/** Number of attention heads. */
	private static final int HEADS = 2;
	/** Finite-difference step. */
	private static final double EPS = 1e-2;
	/** Tolerance on the difference relative to the largest gradient magnitude of the slice. */
	private static final double RELATIVE_TOLERANCE = 0.02;
	/** Smallest largest-magnitude a gradient may have: below it the gradient is trivially zero. */
	private static final double MINIMUM_GRADIENT = 1e-3;

	/**
	 * Gradient of cross-attention with respect to the K and V halves of {@code toKvWeight}, the key
	 * normalization weights, and the query and output projections matches finite differences.
	 */
	@Test(timeout = 10 * 60000)
	public void crossAttentionProjectionGradients() {
		Random random = new Random(7);
		int dimHead = DIM / HEADS;
		TraversalPolicy queryShape = shape(1, QUERY_SEQ, DIM);
		TraversalPolicy contextShape = shape(1, CONTEXT_SEQ, DIM);

		PackedCollection toQ = randn(shape(DIM, DIM), 0.0, 0.5, random).evaluate();
		PackedCollection toKv = randn(shape(2 * DIM, DIM), 0.0, 0.5, random).evaluate();
		PackedCollection toOut = randn(shape(DIM, DIM), 0.0, 0.5, random).evaluate();
		PackedCollection qNormWeight = randn(shape(dimHead), 0.0, 0.5, random).evaluate();
		PackedCollection qNormBias = randn(shape(dimHead), 0.0, 0.5, random).evaluate();
		PackedCollection kNormWeight = randn(shape(dimHead), 0.0, 0.5, random).evaluate();
		PackedCollection kNormBias = randn(shape(dimHead), 0.0, 0.5, random).evaluate();
		PackedCollection queryInput = randn(queryShape, 0.0, 1.0, random).evaluate();
		PackedCollection contextInput = randn(contextShape, 0.0, 1.0, random).evaluate();
		PackedCollection outputGradient = randn(queryShape, 0.0, 1.0, random).evaluate();

		List<PackedCollection> captured = new ArrayList<>();
		Model model = new Model(queryShape, gradientRecorder(captured));
		SequentialBlock context = new SequentialBlock(contextShape);
		model.addInput(context);

		SequentialBlock main = model.sequential();
		main.add(sequenceCrossAttention(1, QUERY_SEQ, CONTEXT_SEQ, DIM, HEADS,
				toQ, toKv, toOut, qNormWeight, qNormBias, kNormWeight, kNormBias,
				context, null));
		CompiledModel compiled = model.compile(true, true);

		compiled.forward(queryInput, contextInput);
		compiled.backward(outputGradient);

		Evaluable<PackedCollection> loss = multiply(cv(queryShape, 0), cv(queryShape, 1)).sum().get();
		DoubleSupplier objective = () ->
				loss.evaluate(compiled.forward(queryInput, contextInput), outputGradient).toDouble();

		PackedCollection kvAnalytic = gradientOfSize(captured, 2 * DIM * DIM);
		double[] kvNumeric = centralDifferences(toKv, EPS, objective);
		assertRangeGradient("toKvWeight K half", kvNumeric, kvAnalytic, 0, DIM * DIM);
		assertRangeGradient("toKvWeight V half", kvNumeric, kvAnalytic, DIM * DIM, 2 * DIM * DIM);

		PackedCollection kNormAnalytic = gradientOfSize(captured, dimHead);
		double[] kNormNumeric = centralDifferences(kNormWeight, EPS, objective);
		assertRangeGradient("kNormWeight", kNormNumeric, kNormAnalytic, 0, dimHead);

		// The query and output projections are both [dim, dim]; match each finite difference against
		// whichever recorded gradient of that size it corresponds to.
		assertMatchingGradient("toQWeight", centralDifferences(toQ, EPS, objective), captured, DIM * DIM);
		assertMatchingGradient("toOutWeight", centralDifferences(toOut, EPS, objective), captured, DIM * DIM);

		compiled.destroy();
	}

	/**
	 * Asserts that a contiguous range of the analytic gradient, in memory order, matches the
	 * finite-difference estimate and is not trivially zero.
	 *
	 * @param label    what the gradient is of, for the messages
	 * @param numeric  the finite-difference estimate of the whole weight, in memory order
	 * @param analytic the gradient the backward pass delivered
	 * @param from     the first index of the range
	 * @param to       one past the last index of the range
	 */
	private void assertRangeGradient(String label, double[] numeric, PackedCollection analytic,
									 int from, int to) {
		double maxAbs = 0.0;
		double maxDiff = 0.0;
		for (int index = from; index < to; index++) {
			double actual = analytic.toDouble(index);
			maxAbs = Math.max(maxAbs, Math.abs(numeric[index]));
			maxDiff = Math.max(maxDiff, Math.abs(numeric[index] - actual));
		}

		log(label + ": max |numeric|=" + maxAbs + " max |diff|=" + maxDiff);
		Assert.assertTrue(label + " gradient is trivially zero", maxAbs > MINIMUM_GRADIENT);
		Assert.assertTrue(label + " gradient differs from finite difference by " + maxDiff
				+ " (max magnitude " + maxAbs + ")", maxDiff <= RELATIVE_TOLERANCE * maxAbs);
	}

	/**
	 * Asserts that some recorded gradient of the given size matches the finite-difference estimate and
	 * is not trivially zero. Used for the query and output projections, which share a shape and so
	 * cannot be told apart by size alone.
	 *
	 * @param label    what the gradient is of, for the messages
	 * @param numeric  the finite-difference estimate
	 * @param recorded every gradient the backward pass recorded
	 * @param size     the number of values of the weight
	 */
	private void assertMatchingGradient(String label, double[] numeric,
										List<PackedCollection> recorded, int size) {
		double maxAbs = 0.0;
		for (double value : numeric) maxAbs = Math.max(maxAbs, Math.abs(value));
		Assert.assertTrue(label + " gradient is trivially zero", maxAbs > MINIMUM_GRADIENT);

		double best = Double.POSITIVE_INFINITY;
		for (PackedCollection candidate : recorded) {
			if (candidate.getShape().getTotalSize() != size) continue;

			double maxDiff = 0.0;
			for (int i = 0; i < numeric.length; i++) {
				maxDiff = Math.max(maxDiff, Math.abs(numeric[i] - candidate.toDouble(i)));
			}
			best = Math.min(best, maxDiff);
		}

		log(label + ": max |numeric|=" + maxAbs + " best match |diff|=" + best);
		Assert.assertTrue(label + " has no recorded gradient of size " + size,
				best < Double.POSITIVE_INFINITY);
		Assert.assertTrue(label + " gradient differs from finite difference by " + best
				+ " (max magnitude " + maxAbs + ")", best <= RELATIVE_TOLERANCE * maxAbs);
	}

	/**
	 * Returns the one recorded weight gradient with the given number of values.
	 *
	 * @param recorded the gradients a backward pass recorded, one per weight
	 * @param size     the number of values of the weight
	 * @return the weight's gradient
	 */
	// TODO(review): dimHead is shared by qNorm/qNormBias/kNorm/kNormBias; matching by size alone relies on backward recording order, not identity
	private static PackedCollection gradientOfSize(List<PackedCollection> recorded, int size) {
		return recorded.stream()
				.filter(gradient -> gradient.getShape().getTotalSize() == size)
				.findFirst()
				.orElseThrow(() -> new AssertionError("No gradient recorded for a weight of " + size + " values"));
	}
}
