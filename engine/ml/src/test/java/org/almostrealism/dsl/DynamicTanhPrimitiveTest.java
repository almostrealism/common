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

package org.almostrealism.dsl;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.GradientRecorder;
import org.almostrealism.util.ModelTestFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.function.DoubleSupplier;

/**
 * Standalone tests of the {@code dynamic_tanh} built-in, DynamicTanh (DyT) normalization: every input
 * element becomes {@code gamma ⊙ tanh(alpha · x) + beta}, the affine parameters applying to every run of
 * as many consecutive features as they hold. Each case builds the {@code squash} layer of
 * {@code /pdsl/test_dynamic_tanh_primitives.pdsl}, compiles it and compares its output against the
 * formula evaluated on the host; the rejection cases check the built-in's argument contract, and the
 * last case checks that alpha and the affine parameters train.
 */
public class DynamicTanhPrimitiveTest extends TestSuiteBase implements ModelTestFeatures {

	/** Classpath location of the primitive fixtures. */
	private static final String FIXTURE = "/pdsl/test_dynamic_tanh_primitives.pdsl";

	/** Agreement required with the double-precision host formula. */
	private static final double TOLERANCE = 1e-6;

	/** Step of the central finite differences. */
	private static final double FINITE_DIFFERENCE_STEP = 1e-2;

	/** Agreement required with the finite differences, relative to the largest gradient compared. */
	private static final double GRADIENT_TOLERANCE = 0.01;

	/**
	 * A scalar alpha and per-feature affine parameters over a {@code [batch, positions, features]}
	 * sequence: every position's features are squashed and then scaled and shifted feature by feature.
	 */
	@Test(timeout = 120000)
	public void dynamicTanhMatchesTheFormula() {
		Random random = new Random(1);
		TraversalPolicy shape = shape(2, 3, 8);
		assertMatchesFormula(shape, seeded(shape(1), random), seeded(shape(8), random),
				seeded(shape(8), random), seeded(shape, random));
	}

	/**
	 * Affine parameters as long as the last axis of a {@code [batch, heads, positions, head_dim]} tensor
	 * normalize every head row with the same parameters, and an alpha with one value per feature scales
	 * each feature inside the tanh by its own value.
	 */
	@Test(timeout = 120000)
	public void dynamicTanhNormalizesEveryHeadRow() {
		Random random = new Random(2);
		TraversalPolicy shape = shape(1, 2, 3, 4);
		assertMatchesFormula(shape, seeded(shape(4), random), seeded(shape(4), random),
				seeded(shape(4), random), seeded(shape, random));
	}

	/** Without affine parameters the stage is the tanh of the scaled input. */
	@Test(timeout = 120000)
	public void dynamicTanhWithoutAffineIsTheTanhOfTheScaledInput() {
		Random random = new Random(3);
		TraversalPolicy shape = shape(2, 5);
		assertMatchesFormula(shape, seeded(shape(1), random), null, null, seeded(shape, random));
	}

	/**
	 * The built-in takes exactly three arguments, and the normalization it builds requires alpha and
	 * rejects affine parameters whose runs do not tile the input. The argument count is the built-in's
	 * own contract, a {@link PdslParseException}; the parameters are checked by the normalization
	 * itself, as they are for a Java caller, with an {@link IllegalArgumentException}.
	 */
	@Test(timeout = 60000)
	public void dynamicTanhRejectsAnInvalidCall() {
		PackedCollection gamma = new PackedCollection(shape(8)).fill(1.0);
		assertRejected("squash_without_biases", shape(2, 8),
				args("alpha", new PackedCollection(shape(1)).fill(1.0), "gamma", gamma),
				PdslParseException.class, "dynamic_tanh() should reject a call without its biases argument");
		assertRejected("squash", shape(2, 8), args("alpha", null, "gamma", gamma, "beta", null),
				IllegalArgumentException.class, "dynamic_tanh() should reject a missing alpha");
		assertRejected("squash", shape(2, 8),
				args("alpha", new PackedCollection(shape(1)).fill(1.0),
						"gamma", new PackedCollection(shape(5)).fill(1.0), "beta", null),
				IllegalArgumentException.class,
				"dynamic_tanh() should reject weights whose runs do not tile the input");
	}

	/**
	 * Alpha and each affine parameter are registered weights of the stage: the backward pass delivers to
	 * each the gradient that central finite differences of {@code L = sum(output * G)} imply, for a fixed
	 * output gradient {@code G}, and to the input likewise. While the hyperbolic tangent was differentiated
	 * through its expression-level derivative, which is wrong for the row-wise scaled argument DynamicTanh
	 * gives it, this failed: the gradient of alpha was off by about a tenth of its magnitude.
	 */
	@Test(timeout = 300000)
	public void dynamicTanhTrainsAlphaAndItsAffine() {
		Random random = new Random(4);
		TraversalPolicy shape = shape(3, 6);
		assertTrains(shape, seeded(shape(1), random), seeded(shape(6), random), null,
				seeded(shape, random), seeded(shape, random));
		assertTrains(shape, seeded(shape(1), random), null, seeded(shape(6), random),
				seeded(shape, random), seeded(shape, random));
	}

	/**
	 * Builds the {@code squash} layer, forwards {@code input} and compares every output element with
	 * {@code gamma ⊙ tanh(alpha · x) + beta} evaluated on the host.
	 *
	 * @param shape the input shape
	 * @param alpha the scale inside the tanh
	 * @param gamma the per-feature output scale, or {@code null} for none
	 * @param beta  the per-feature output shift, or {@code null} for none
	 * @param input the input
	 */
	private void assertMatchesFormula(TraversalPolicy shape, PackedCollection alpha,
									  PackedCollection gamma, PackedCollection beta, PackedCollection input) {
		Model model = new Model(shape);
		model.add(squash(shape, alpha, gamma, beta));
		try (CompiledModel compiled = model.compile(false)) {
			double[] actual = compiled.forward(input).doubleStream().toArray();
			double[] x = input.doubleStream().toArray();
			double[] a = alpha.doubleStream().toArray();
			double[] g = gamma == null ? null : gamma.doubleStream().toArray();
			double[] b = beta == null ? null : beta.doubleStream().toArray();
			int features = g != null ? g.length : b != null ? b.length : shape.length(shape.getDimensions() - 1);

			assertEquals("output size", x.length, actual.length);
			for (int i = 0; i < x.length; i++) {
				int f = i % features;
				double expected = Math.tanh((a.length == 1 ? a[0] : a[f]) * x[i]);
				if (g != null) expected *= g[f];
				if (b != null) expected += b[f];
				assertEquals("element " + i, expected, actual[i], TOLERANCE);
			}
		}
	}

	/**
	 * Runs one backward pass of the {@code squash} layer and checks the gradient of each bound parameter
	 * and of the input against central finite differences.
	 *
	 * @param shape          the input shape
	 * @param alpha          the scale inside the tanh, one value
	 * @param gamma          the per-feature output scale, or {@code null} for none
	 * @param beta           the per-feature output shift, or {@code null} for none
	 * @param input          the input
	 * @param outputGradient the gradient of the loss with respect to the output
	 */
	private void assertTrains(TraversalPolicy shape, PackedCollection alpha, PackedCollection gamma,
							  PackedCollection beta, PackedCollection input, PackedCollection outputGradient) {
		GradientRecorder recorder = new GradientRecorder();
		Model model = new Model(shape, recorder);
		model.add(squash(shape, alpha, gamma, beta));
		try (CompiledModel compiled = model.compile(true, true)) {
			compiled.forward(input);
			double[] inputGradient = compiled.backward(outputGradient).doubleStream().toArray();

			Evaluable<PackedCollection> loss = multiply(cv(shape, 0), cv(shape, 1)).sum().get();
			DoubleSupplier objective = () -> loss.evaluate(compiled.forward(input), outputGradient).toDouble();

			PackedCollection affine = gamma != null ? gamma : beta;
			assertGradientMatches(gamma != null ? "gamma" : "beta",
					centralDifferences(affine, FINITE_DIFFERENCE_STEP, objective),
					recorder.gradientOfSize(affine.getShape().getTotalSize()), GRADIENT_TOLERANCE, 0.0);
			assertGradientMatches("alpha", centralDifferences(alpha, FINITE_DIFFERENCE_STEP, objective),
					recorder.gradientOfSize(1), GRADIENT_TOLERANCE, 0.0);
			assertGradientMatches("input", centralDifferences(input, FINITE_DIFFERENCE_STEP, objective),
					inputGradient, GRADIENT_TOLERANCE, 0.0);
		}
	}

	/**
	 * Builds the {@code squash} fixture layer.
	 *
	 * @param shape the input shape
	 * @param alpha the scale inside the tanh
	 * @param gamma the per-feature output scale, or {@code null} for none
	 * @param beta  the per-feature output shift, or {@code null} for none
	 * @return the layer
	 */
	private Block squash(TraversalPolicy shape, PackedCollection alpha,
						 PackedCollection gamma, PackedCollection beta) {
		PdslLoader loader = new PdslLoader();
		return loader.buildLayer(loader.parseResource(FIXTURE), "squash", shape,
				args("alpha", alpha, "gamma", gamma, "beta", beta));
	}

	/**
	 * Asserts that building a fixture layer is rejected with an exception of the given type.
	 *
	 * @param layer    the fixture layer name
	 * @param shape    the input shape
	 * @param args     the layer's arguments
	 * @param expected the type of the exception the rejection must raise
	 * @param message  the failure message when the layer is not rejected
	 */
	private void assertRejected(String layer, TraversalPolicy shape, Map<String, Object> args,
								Class<? extends RuntimeException> expected, String message) {
		PdslLoader loader = new PdslLoader();
		try {
			loader.buildLayer(loader.parseResource(FIXTURE), layer, shape, args);
			Assert.fail(message);
		} catch (RuntimeException rejection) {
			if (!expected.isInstance(rejection)) {
				throw rejection;
			}
			log("rejected: " + rejection.getMessage());
		}
	}

	/**
	 * A collection of standard Gaussian values drawn from {@code random}.
	 *
	 * @param shape  the shape
	 * @param random the generator
	 * @return the collection
	 */
	private PackedCollection seeded(TraversalPolicy shape, Random random) {
		return new PackedCollection(shape).randnFill(random);
	}

	/**
	 * Builds an argument map from alternating names and values; a value may be {@code null}.
	 *
	 * @param entries name, value, name, value, ...
	 * @return the argument map
	 */
	private Map<String, Object> args(Object... entries) {
		Map<String, Object> args = new HashMap<>();
		for (int i = 0; i < entries.length; i += 2) {
			args.put((String) entries[i], entries[i + 1]);
		}
		return args;
	}
}
