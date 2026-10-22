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
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.ModelTestFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleSupplier;

/**
 * Diagnostic (temporary): establishes by central finite differences whether the four lambda
 * re-parameterization vectors receive a gradient when they are registered as the weights of a
 * scale-along-axis layer whose factors are {@code differentialLambda}, and confirms they receive
 * none when the same factors are applied by a weightless {@code scale}.
 */
// TODO(review): throwaway diagnostic, not the committed deliverable — the plan's implementation
// TODO(review): (scale-weights overload, PdslBuiltins/pdsl wiring, real per-vector test in
// TODO(review): DifferentialAttentionAssetTest) is still outstanding; delete or replace this file.
public class LambdaScaleGradientDiagnostic extends TestSuiteBase
		implements DifferentialAttentionFeatures, ModelTestFeatures {

	/** Batch size. */
	private static final int BATCH = 1;
	/** Head count. */
	private static final int HEADS = 2;
	/** Sequence length. */
	private static final int SEQ = 3;
	/** Dimension per head. */
	private static final int HEAD_DIM = 2;
	/** Length of each lambda vector. */
	private static final int LAMBDA_DIM = 3;
	/** Lambda initialization constant. */
	private static final double LAMBDA_INIT = 0.2;
	/** Finite-difference step. */
	private static final double STEP = 1e-2;

	/** Registered lambda weights receive a finite-difference-correct gradient. */
	@Test(timeout = 120000)
	public void registeredWeightsGetGradient() {
		TraversalPolicy shape = shape(BATCH, HEADS, SEQ, HEAD_DIM);
		PackedCollection[] p = new PackedCollection[4];
		for (int i = 0; i < 4; i++) p[i] = new PackedCollection(shape(HEADS, LAMBDA_DIM)).randnFill();

		Producer<PackedCollection> factors = differentialLambda(p[0], p[1], p[2], p[3], LAMBDA_INIT);

		PackedCollection input = new PackedCollection(shape).randnFill();
		PackedCollection outputGradient = new PackedCollection(shape).randnFill();

		List<PackedCollection> recorded = new ArrayList<>();
		Model model = new Model(shape, gradientRecorder(recorded));
		model.add(layer("scaleAlongAxis", shape, shape,
				in -> c(in).multiply(broadcast(shape, 1, factors)),
				List.of(p[0], p[1], p[2], p[3])));

		try (CompiledModel compiled = model.compile(true, true)) {
			compiled.forward(input);
			compiled.backward(outputGradient);

			Evaluable<PackedCollection> loss = multiply(cv(shape, 0), cv(shape, 1)).sum().get();
			DoubleSupplier objective = () -> loss.evaluate(compiled.forward(input), outputGradient).toDouble();

			String[] names = { "q1", "k1", "q2", "k2" };
			for (int i = 0; i < 4; i++) {
				double[] numeric = centralDifferences(p[i], STEP, objective);
				double[] analytic = recorded.get(i).doubleStream().toArray();
				double mag = max(numeric);
				double diff = maxDiff(numeric, analytic);
				log(names[i] + ": |numeric|=" + mag + " maxDiff=" + diff + " |analytic|=" + max(analytic));
				assertTrue(names[i] + " gradient trivially zero", mag > 1e-3);
				assertTrue(names[i] + " differs from finite differences by " + diff, diff <= 0.02 * mag);
			}
		}
	}

	/** A weightless scale records no weight gradient at all (the master defect). */
	@Test(timeout = 120000)
	public void weightlessScaleGivesNoGradient() {
		TraversalPolicy shape = shape(BATCH, HEADS, SEQ, HEAD_DIM);
		PackedCollection[] p = new PackedCollection[4];
		for (int i = 0; i < 4; i++) p[i] = new PackedCollection(shape(HEADS, LAMBDA_DIM)).randnFill();

		Producer<PackedCollection> factors = differentialLambda(p[0], p[1], p[2], p[3], LAMBDA_INIT);
		PackedCollection input = new PackedCollection(shape).randnFill();
		PackedCollection outputGradient = new PackedCollection(shape).randnFill();

		List<PackedCollection> recorded = new ArrayList<>();
		Model model = new Model(shape, gradientRecorder(recorded));
		model.add(scale(shape, 1, factors));

		try (CompiledModel compiled = model.compile(true, true)) {
			compiled.forward(input);
			compiled.backward(outputGradient);
			log("weightless scale recorded " + recorded.size() + " weight gradients");
			assertEquals(0, recorded.size());
		}
	}

	/**
	 * The largest magnitude in an array.
	 *
	 * @param a the values
	 * @return the largest absolute value
	 */
	private static double max(double[] a) {
		double m = 0.0;
		for (double v : a) m = Math.max(m, Math.abs(v));
		return m;
	}

	/**
	 * The largest element-wise difference between two arrays.
	 *
	 * @param a the first array
	 * @param b the second array
	 * @return the largest absolute difference
	 */
	private static double maxDiff(double[] a, double[] b) {
		double m = 0.0;
		for (int i = 0; i < a.length; i++) m = Math.max(m, Math.abs(a[i] - b[i]));
		return m;
	}
}
