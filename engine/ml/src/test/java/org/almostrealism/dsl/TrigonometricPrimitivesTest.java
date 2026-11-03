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
import org.almostrealism.util.ModelTestFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.Random;
import java.util.function.DoubleSupplier;
import java.util.function.DoubleUnaryOperator;

/**
 * Standalone tests of the {@code cos} and {@code sin} built-ins, the element-wise trigonometric
 * functions that a Fourier feature projection splits a frequency-scaled projection into. Each case
 * builds one of the {@code cosine} / {@code sine} layers of
 * {@code /pdsl/test_trigonometric_primitives.pdsl}, compiles it and compares its output against the
 * function evaluated on the host; the rejection case checks the no-argument contract, and the last
 * case checks that each function propagates its derivative ({@code -sin} for {@code cos},
 * {@code cos} for {@code sin}) to the input in a backward pass.
 */
public class TrigonometricPrimitivesTest extends TestSuiteBase implements ModelTestFeatures {

	/** Classpath location of the primitive fixtures. */
	private static final String FIXTURE = "/pdsl/test_trigonometric_primitives.pdsl";

	/** Agreement required with the double-precision host function. */
	private static final double TOLERANCE = 1e-6;

	/** Step of the central finite differences. */
	private static final double FINITE_DIFFERENCE_STEP = 1e-2;

	/** Agreement required with the finite differences, relative to the largest gradient compared. */
	private static final double GRADIENT_TOLERANCE = 0.01;

	/** The {@code cosine} layer reproduces {@code Math.cos} of every element of a two-dimensional input. */
	@Test(timeout = 120000)
	public void cosMatchesHostCosine() {
		assertMatchesFunction("cosine", shape(2, 5), new Random(1), Math::cos);
	}

	/** The {@code sine} layer reproduces {@code Math.sin} of every element of a two-dimensional input. */
	@Test(timeout = 120000)
	public void sinMatchesHostSine() {
		assertMatchesFunction("sine", shape(3, 4), new Random(2), Math::sin);
	}

	/**
	 * The built-ins take no arguments: {@code cos(1)} is rejected by the built-in's own contract, a
	 * {@link PdslParseException}.
	 */
	@Test(timeout = 60000)
	public void cosRejectsAnArgument() {
		PdslLoader loader = new PdslLoader();
		try {
			loader.buildLayer(loader.parseResource(FIXTURE), "cosine_with_arg", shape(2, 3), new HashMap<>());
			Assert.fail("cos() should reject a call with an argument");
		} catch (PdslParseException rejection) {
			log("rejected: " + rejection.getMessage());
		}
	}

	/**
	 * Each function propagates its derivative to the input: the backward pass delivers to the input the
	 * gradient that central finite differences of {@code L = sum(output * G)} imply, for a fixed output
	 * gradient {@code G} — {@code -sin(x)} for the cosine layer and {@code cos(x)} for the sine layer.
	 */
	@Test(timeout = 300000)
	public void cosAndSinPropagateTheirDerivatives() {
		assertPropagatesDerivative("cosine", shape(3, 4), new Random(3));
		assertPropagatesDerivative("sine", shape(3, 4), new Random(4));
	}

	/**
	 * Builds the named layer, forwards a seeded input and compares every output element with
	 * {@code function} evaluated on the host.
	 *
	 * @param layer    the fixture layer name
	 * @param shape    the input shape
	 * @param random   the generator of the input
	 * @param function the host function the layer must reproduce
	 */
	private void assertMatchesFunction(String layer, TraversalPolicy shape, Random random,
									   DoubleUnaryOperator function) {
		PackedCollection input = seeded(shape, random);
		Model model = new Model(shape);
		model.add(trig(layer, shape));
		try (CompiledModel compiled = model.compile(false)) {
			double[] actual = compiled.forward(input).doubleStream().toArray();
			double[] x = input.doubleStream().toArray();

			assertEquals("output size", x.length, actual.length);
			for (int i = 0; i < x.length; i++) {
				assertEquals("element " + i, function.applyAsDouble(x[i]), actual[i], TOLERANCE);
			}
		}
	}

	/**
	 * Runs one backward pass of the named layer and checks the gradient delivered to the input against
	 * central finite differences.
	 *
	 * @param layer  the fixture layer name
	 * @param shape  the input shape
	 * @param random the generator of the input and the output gradient
	 */
	private void assertPropagatesDerivative(String layer, TraversalPolicy shape, Random random) {
		PackedCollection input = seeded(shape, random);
		PackedCollection outputGradient = seeded(shape, random);

		Model model = new Model(shape);
		model.add(trig(layer, shape));
		try (CompiledModel compiled = model.compile(true, true)) {
			compiled.forward(input);
			double[] inputGradient = compiled.backward(outputGradient).doubleStream().toArray();

			Evaluable<PackedCollection> loss = multiply(cv(shape, 0), cv(shape, 1)).sum().get();
			DoubleSupplier objective = () -> loss.evaluate(compiled.forward(input), outputGradient).toDouble();

			assertGradientMatches(layer + " input",
					centralDifferences(input, FINITE_DIFFERENCE_STEP, objective),
					inputGradient, GRADIENT_TOLERANCE, 0.0);
		}
	}

	/**
	 * Builds a fixture layer.
	 *
	 * @param layer the fixture layer name
	 * @param shape the input shape
	 * @return the layer
	 */
	private Block trig(String layer, TraversalPolicy shape) {
		PdslLoader loader = new PdslLoader();
		return loader.buildLayer(loader.parseResource(FIXTURE), layer, shape, new HashMap<>());
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
}
