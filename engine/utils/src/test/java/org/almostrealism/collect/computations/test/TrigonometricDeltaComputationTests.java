/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.collect.computations.test;

import io.almostrealism.compute.Process;
import io.almostrealism.profile.OperationProfileNode;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.calculus.ReduceAxis;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CollectionProducerParallelProcess;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.UnaryCollectionComputation;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleUnaryOperator;
import java.util.function.Function;

/**
 * Tests for trigonometric function gradients (sine, cosine, tangent, hyperbolic tangent).
 * These tests verify both correctness and performance of the Producer-level
 * delta implementations in {@link org.almostrealism.collect.computations.CollectionSineComputation}
 * and {@link org.almostrealism.collect.computations.CollectionCosineComputation}, and the
 * expression-level derivatives of the tangent and the hyperbolic tangent.
 *
 * @author Michael Murray
 */
public class TrigonometricDeltaComputationTests extends TestSuiteBase {

	/**
	 * Tests that d/dx[sin(x)] = cos(x) for a simple scalar case.
	 */
	@Test(timeout = 120000)
	public void sineDeltaScalar() {
		PackedCollection input = pack(0.0, Math.PI / 6, Math.PI / 4, Math.PI / 3, Math.PI / 2);
		CollectionProducer x = cp(input);

		// sin(x)
		CollectionProducer sinX = sin(x);

		// Verify forward pass
		PackedCollection forward = sinX.get().evaluate();
		log("sin(x) = " + forward.toArrayString());

		for (int i = 0; i < 5; i++) {
			assertEquals(Math.sin(input.toDouble(i)), forward.toDouble(i));
		}

		// d/dx[sin(x)] = cos(x)
		CollectionProducer delta = sinX.delta(x);
		PackedCollection gradient = delta.get().evaluate();
		log("d/dx[sin(x)] shape: " + gradient.getShape());

		// The gradient should be a 5x5 diagonal matrix with cos(x) on the diagonal
		gradient = gradient.reshape(5, 5);
		log("d/dx[sin(x)] = ");
		gradient.traverse().print();

		for (int i = 0; i < 5; i++) {
			for (int j = 0; j < 5; j++) {
				double expected = (i == j) ? Math.cos(input.toDouble(i)) : 0.0;
				assertEquals(expected, gradient.valueAt(i, j));
			}
		}
	}

	/**
	 * Tests that d/dx[cos(x)] = -sin(x) for a simple scalar case.
	 */
	@Test(timeout = 120000)
	public void cosineDeltaScalar() {
		PackedCollection input = pack(0.0, Math.PI / 6, Math.PI / 4, Math.PI / 3, Math.PI / 2);
		CollectionProducer x = cp(input);

		// cos(x)
		CollectionProducer cosX = cos(x);

		// Verify forward pass
		PackedCollection forward = cosX.get().evaluate();
		log("cos(x) = " + forward.toArrayString());

		for (int i = 0; i < 5; i++) {
			assertEquals(Math.cos(input.toDouble(i)), forward.toDouble(i));
		}

		// d/dx[cos(x)] = -sin(x)
		CollectionProducer delta = cosX.delta(x);
		PackedCollection gradient = delta.get().evaluate();
		log("d/dx[cos(x)] shape: " + gradient.getShape());

		// The gradient should be a 5x5 diagonal matrix with -sin(x) on the diagonal
		gradient = gradient.reshape(5, 5);
		log("d/dx[cos(x)] = ");
		gradient.traverse().print();

		for (int i = 0; i < 5; i++) {
			for (int j = 0; j < 5; j++) {
				double expected = (i == j) ? -Math.sin(input.toDouble(i)) : 0.0;
				assertEquals(expected, gradient.valueAt(i, j));
			}
		}
	}

	/**
	 * Tests that d/dx[tanh(x)] = 1 - tanh(x)^2 for a simple scalar case, over arguments of both
	 * signs and one close enough to saturation that the derivative is small. The hyperbolic tangent
	 * is differentiated through the expression-level derivative of its {@code Tangent} expression.
	 */
	@Test(timeout = 120000)
	public void hyperbolicTangentDeltaScalar() {
		PackedCollection input = pack(-2.0, -0.5, 0.0, 0.75, 3.0);
		CollectionProducer x = cp(input);

		// tanh(x)
		CollectionProducer tanhX = tanh(x);

		// Verify forward pass
		PackedCollection forward = tanhX.get().evaluate();
		log("tanh(x) = " + forward.toArrayString());

		for (int i = 0; i < 5; i++) {
			assertEquals(Math.tanh(input.toDouble(i)), forward.toDouble(i));
		}

		// d/dx[tanh(x)] = 1 - tanh(x)^2
		PackedCollection gradient = tanhX.delta(x).get().evaluate().reshape(5, 5);
		log("d/dx[tanh(x)] = ");
		gradient.traverse().print();

		for (int i = 0; i < 5; i++) {
			for (int j = 0; j < 5; j++) {
				double t = Math.tanh(input.toDouble(i));
				double expected = (i == j) ? 1.0 - t * t : 0.0;
				assertEquals(expected, gradient.valueAt(i, j));
			}
		}
	}

	/**
	 * Tests the chain rule through the hyperbolic tangent of a row-wise scaled argument,
	 * d/dx[tanh(a * x)] = a * (1 - tanh(a * x)^2), with {@code x} traversed as rows of four features
	 * and {@code a} a single value, which is the form DynamicTanh normalization takes. Each output
	 * element depends on its own input element only, so the Jacobian is diagonal. The hyperbolic tangent
	 * used to be differentiated through the expression-level derivative of its {@code Tangent}
	 * expression, which embeds the argument's expression in the derivative's kernel; for a traversed,
	 * composite argument that derivative was wrong (it did not agree with the chain rule on the diagonal,
	 * and was not zero off it).
	 */
	@Test(timeout = 120000)
	public void hyperbolicTangentChainRuleOverRows() {
		PackedCollection input = pack(-1.5, -0.5, 0.25, 1.0, 2.0, -2.5, 0.5, 0.0);
		PackedCollection scale = pack(0.7);
		CollectionProducer x = cp(input);

		CollectionProducer squashed = tanh(x.reshape(-1, 4).traverse(1).multiply(cp(scale))).reshape(8);

		PackedCollection forward = squashed.get().evaluate();
		for (int i = 0; i < 8; i++) {
			assertEquals(Math.tanh(0.7 * input.toDouble(i)), forward.toDouble(i));
		}

		PackedCollection gradient = squashed.delta(x).get().evaluate().reshape(8, 8);
		log("d/dx[tanh(a * x)] = ");
		gradient.traverse().print();

		for (int i = 0; i < 8; i++) {
			for (int j = 0; j < 8; j++) {
				double t = Math.tanh(0.7 * input.toDouble(i));
				double expected = (i == j) ? 0.7 * (1.0 - t * t) : 0.0;
				assertEquals("d tanh(a x[" + i + "]) / d x[" + j + "]", expected, gradient.valueAt(i, j), 1e-5);
			}
		}
	}

	/**
	 * Tests that regenerating a unary computation over new children, as process optimization does,
	 * rebuilds the same function (the same class, carrying the original description) over the new
	 * input rather than the old one, for each of sine, cosine and the hyperbolic tangent.
	 */
	@Test(timeout = 120000)
	public void unaryComputationsRegenerateOverNewInput() {
		PackedCollection original = pack(0.1, 0.2, 0.3);
		PackedCollection replacement = pack(-1.0, 0.5, 2.0);

		assertRegenerated(sin(cp(original)), replacement, Math::sin);
		assertRegenerated(cos(cp(original)), replacement, Math::cos);
		assertRegenerated(tanh(cp(original)), replacement, Math::tanh);
	}

	/**
	 * Regenerates {@code computation} with {@code replacement} as its input and asserts that the
	 * regenerated computation is of the same class, keeps the description, and evaluates
	 * {@code function} element-wise over {@code replacement}.
	 *
	 * @param computation the unary computation to regenerate
	 * @param replacement the new input
	 * @param function    the element-wise function the computation applies
	 */
	private void assertRegenerated(CollectionProducer computation, PackedCollection replacement,
								   DoubleUnaryOperator function) {
		UnaryCollectionComputation unary = (UnaryCollectionComputation) computation;
		Function<List<String>, String> description = args -> "regenerated " + args;
		unary.setDescription(description);

		List<Process<?, ?>> children = new ArrayList<>(unary.getChildren());
		children.set(1, (Process<?, ?>) cp(replacement));
		CollectionProducerParallelProcess regenerated = unary.generate(children);

		assertEquals(unary.getClass(), regenerated.getClass());
		assertTrue("the description is carried over",
				((UnaryCollectionComputation) regenerated).getDescription() == description);

		PackedCollection result = ((CollectionProducer) regenerated).evaluate();
		assertEquals(replacement.getShape().getTotalSize(), result.getShape().getTotalSize());
		for (int i = 0; i < replacement.getShape().getTotalSize(); i++) {
			assertEquals(function.applyAsDouble(replacement.toDouble(i)), result.toDouble(i));
		}
	}

	/**
	 * Tests the output-reduced chain rule through the hyperbolic tangent of a row-wise scaled
	 * argument: the vector-Jacobian product {@code g^T d/dx[tanh(a * x)]}, which is what a layer's
	 * backward pass forms, must be {@code g * a * (1 - tanh(a * x)^2)} element by element, for the
	 * same row-traversed argument as {@link #hyperbolicTangentChainRuleOverRows()}.
	 */
	@Test(timeout = 120000)
	public void hyperbolicTangentReducedChainRuleOverRows() {
		PackedCollection input = pack(-1.5, -0.5, 0.25, 1.0, 2.0, -2.5, 0.5, 0.0);
		PackedCollection outputGradient = pack(0.3, -1.2, 0.8, 0.5, -0.4, 1.1, -0.9, 0.6);
		PackedCollection scale = pack(0.7);
		CollectionProducer x = cp(input);

		CollectionProducer squashed = tanh(x.reshape(-1, 4).traverse(1).multiply(cp(scale))).reshape(8);
		PackedCollection gradient = squashed.delta(x, ReduceAxis.OUT, cp(outputGradient)).get().evaluate();

		assertTrue("the gradient holds one value per input element", gradient.getShape().getTotalSize() == 8);
		for (int j = 0; j < 8; j++) {
			double t = Math.tanh(0.7 * input.toDouble(j));
			double expected = outputGradient.toDouble(j) * 0.7 * (1.0 - t * t);
			assertEquals("g^T d tanh(a x) / d x[" + j + "]", expected, gradient.toDouble(j), 1e-5);
		}
	}

	/**
	 * Tests that d/dx[tan(x)] = 1 + tan(x)^2 for a simple scalar case, over arguments of both signs
	 * kept well away from the poles at {@code ±pi/2}. This exercises the non-hyperbolic branch of the
	 * expression-level derivative of {@code Tangent}, whose cofactor is {@code 1 + tan(x)^2} rather
	 * than the hyperbolic tangent's {@code 1 - tanh(x)^2}.
	 */
	@Test(timeout = 120000)
	public void tangentDeltaScalar() {
		PackedCollection input = pack(-1.2, -0.3, 0.0, 0.5, 1.0);
		CollectionProducer x = cp(input);

		// tan(x)
		CollectionProducer tanX = tan(x);

		// Verify forward pass
		PackedCollection forward = tanX.get().evaluate();
		log("tan(x) = " + forward.toArrayString());

		for (int i = 0; i < 5; i++) {
			assertEquals(Math.tan(input.toDouble(i)), forward.toDouble(i));
		}

		// d/dx[tan(x)] = 1 + tan(x)^2
		PackedCollection gradient = tanX.delta(x).get().evaluate().reshape(5, 5);
		log("d/dx[tan(x)] = ");
		gradient.traverse().print();

		for (int i = 0; i < 5; i++) {
			for (int j = 0; j < 5; j++) {
				double t = Math.tan(input.toDouble(i));
				double expected = (i == j) ? 1.0 + t * t : 0.0;
				assertEquals(expected, gradient.valueAt(i, j));
			}
		}
	}

	/**
	 * Tests that {@code acos(x)} matches {@link Math#acos} and that
	 * d/dx[acos(x)] = -1/sqrt(1 - x^2) for a simple scalar case.
	 */
	@Test(timeout = 120000)
	public void arcCosineDeltaScalar() {
		PackedCollection input = pack(-0.5, -0.25, 0.0, 0.25, 0.5);
		CollectionProducer x = cp(input);

		// acos(x)
		CollectionProducer acosX = acos(x);

		// Verify forward pass
		PackedCollection forward = acosX.get().evaluate();
		log("acos(x) = " + forward.toArrayString());

		for (int i = 0; i < 5; i++) {
			assertEquals(Math.acos(input.toDouble(i)), forward.toDouble(i));
		}

		// d/dx[acos(x)] = -1/sqrt(1 - x^2)
		CollectionProducer delta = acosX.delta(x);
		PackedCollection gradient = delta.get().evaluate().reshape(5, 5);
		log("d/dx[acos(x)] = ");
		gradient.traverse().print();

		for (int i = 0; i < 5; i++) {
			for (int j = 0; j < 5; j++) {
				double xi = input.toDouble(i);
				double expected = (i == j) ? -1.0 / Math.sqrt(1.0 - xi * xi) : 0.0;
				assertEquals(expected, gradient.valueAt(i, j));
			}
		}
	}

	/**
	 * Tests the chain rule: d/dx[acos(x/2)] = (-1/sqrt(1 - (x/2)^2)) * (1/2).
	 */
	@Test(timeout = 120000)
	public void arcCosineChainRule() {
		PackedCollection input = pack(-1.0, -0.5, 0.0, 0.5, 1.0);
		CollectionProducer x = cp(input);

		// acos(x/2)
		CollectionProducer acosHalfX = acos(x.multiply(0.5));

		// Verify forward pass
		PackedCollection forward = acosHalfX.get().evaluate();
		log("acos(x/2) = " + forward.toArrayString());

		for (int i = 0; i < 5; i++) {
			assertEquals(Math.acos(input.toDouble(i) * 0.5), forward.toDouble(i));
		}

		// d/dx[acos(x/2)] = (-1/sqrt(1 - (x/2)^2)) * (1/2)
		CollectionProducer delta = acosHalfX.delta(x);
		PackedCollection gradient = delta.get().evaluate().reshape(5, 5);

		for (int i = 0; i < 5; i++) {
			for (int j = 0; j < 5; j++) {
				double half = input.toDouble(i) * 0.5;
				double expected = (i == j) ? (-1.0 / Math.sqrt(1.0 - half * half)) * 0.5 : 0.0;
				assertEquals(expected, gradient.valueAt(i, j));
			}
		}
	}

	/**
	 * Tests the chain rule: d/dx[sin(2x)] = 2*cos(2x).
	 */
	@Test(timeout = 120000)
	public void sineChainRule() {
		PackedCollection input = pack(0.0, Math.PI / 6, Math.PI / 4, Math.PI / 3, Math.PI / 2);
		CollectionProducer x = cp(input);

		// sin(2x)
		CollectionProducer sin2x = sin(x.multiply(2.0));

		// Verify forward pass
		PackedCollection forward = sin2x.get().evaluate();
		log("sin(2x) = " + forward.toArrayString());

		for (int i = 0; i < 5; i++) {
			assertEquals(Math.sin(2 * input.toDouble(i)), forward.toDouble(i));
		}

		// d/dx[sin(2x)] = 2*cos(2x)
		CollectionProducer delta = sin2x.delta(x);
		PackedCollection gradient = delta.get().evaluate();
		gradient = gradient.reshape(5, 5);
		log("d/dx[sin(2x)] = ");
		gradient.traverse().print();

		for (int i = 0; i < 5; i++) {
			for (int j = 0; j < 5; j++) {
				double expected = (i == j) ? 2 * Math.cos(2 * input.toDouble(i)) : 0.0;
				assertEquals(expected, gradient.valueAt(i, j));
			}
		}
	}

	/**
	 * Tests the chain rule: d/dx[cos(3x + 1)] = -3*sin(3x + 1).
	 */
	@Test(timeout = 120000)
	public void cosineChainRule() {
		PackedCollection input = pack(0.0, 0.5, 1.0, 1.5, 2.0);
		CollectionProducer x = cp(input);

		// cos(3x + 1)
		CollectionProducer cos3xPlus1 = cos(x.multiply(3.0).add(1.0));

		// Verify forward pass
		PackedCollection forward = cos3xPlus1.get().evaluate();
		log("cos(3x + 1) = " + forward.toArrayString());

		for (int i = 0; i < 5; i++) {
			double arg = 3 * input.toDouble(i) + 1;
			assertEquals(Math.cos(arg), forward.toDouble(i));
		}

		// d/dx[cos(3x + 1)] = -3*sin(3x + 1)
		CollectionProducer delta = cos3xPlus1.delta(x);
		PackedCollection gradient = delta.get().evaluate();
		gradient = gradient.reshape(5, 5);
		log("d/dx[cos(3x + 1)] = ");
		gradient.traverse().print();

		for (int i = 0; i < 5; i++) {
			for (int j = 0; j < 5; j++) {
				double arg = 3 * input.toDouble(i) + 1;
				double expected = (i == j) ? -3 * Math.sin(arg) : 0.0;
				assertEquals(expected, gradient.valueAt(i, j));
			}
		}
	}

	/**
	 * Tests composed functions: d/dx[sin(cos(x))] = cos(cos(x)) * (-sin(x)).
	 */
	@Test(timeout = 120000)
	public void sinCosComposition() {
		PackedCollection input = pack(0.0, 0.5, 1.0, 1.5, 2.0);
		CollectionProducer x = cp(input);

		// sin(cos(x))
		CollectionProducer sinCosX = sin(cos(x));

		// Verify forward pass
		PackedCollection forward = sinCosX.get().evaluate();
		log("sin(cos(x)) = " + forward.toArrayString());

		for (int i = 0; i < 5; i++) {
			assertEquals(Math.sin(Math.cos(input.toDouble(i))), forward.toDouble(i));
		}

		// d/dx[sin(cos(x))] = cos(cos(x)) * (-sin(x))
		CollectionProducer delta = sinCosX.delta(x);
		PackedCollection gradient = delta.get().evaluate();
		gradient = gradient.reshape(5, 5);
		log("d/dx[sin(cos(x))] = ");
		gradient.traverse().print();

		for (int i = 0; i < 5; i++) {
			for (int j = 0; j < 5; j++) {
				double xi = input.toDouble(i);
				double expected = (i == j)
						? Math.cos(Math.cos(xi)) * (-Math.sin(xi))
						: 0.0;
				assertEquals(expected, gradient.valueAt(i, j));
			}
		}
	}

	/**
	 * Tests sine gradient with 2D input to verify batch handling.
	 */
	@Test(timeout = 120000)
	public void sineDelta2D() {
		int rows = 3;
		int cols = 4;
		PackedCollection input = new PackedCollection(shape(rows, cols)).randnFill();
		CollectionProducer x = cp(input);

		// sin(x)
		CollectionProducer sinX = sin(x);

		// d/dx[sin(x)] = cos(x)
		CollectionProducer delta = sinX.delta(x);
		PackedCollection gradient = delta.get().evaluate();
		log("2D gradient shape: " + gradient.getShape());

		int total = rows * cols;
		gradient = gradient.reshape(total, total);

		for (int i = 0; i < total; i++) {
			for (int j = 0; j < total; j++) {
				double expected = (i == j) ? Math.cos(input.toDouble(i)) : 0.0;
				assertEquals(expected, gradient.valueAt(i, j));
			}
		}
	}

	/**
	 * Performance test for sine gradient at small scale.
	 */
	@Test(timeout = 120000)
	public void sineDeltaPerformanceSmall() throws IOException {
		sineDeltaPerformance("sineDeltaSmall", 16);
	}

	/**
	 * Performance test for sine gradient at medium scale.
	 */
	@Test(timeout = 300000)
	@TestDepth(1)
	public void sineDeltaPerformanceMedium() throws IOException {
		sineDeltaPerformance("sineDeltaMedium", 64);
	}

	/**
	 * Performance test for sine gradient at larger scale.
	 */
	@Test(timeout = 600000)
	@TestDepth(2)
	public void sineDeltaPerformanceLarge() throws IOException {
		sineDeltaPerformance("sineDeltaLarge", 256);
	}

	/**
	 * Helper method to test sine delta performance at specified dimension.
	 * @param name the test name for profiling
	 * @param dim the dimension for the test
	 * @throws IOException if saving the profile fails
	 */
	private void sineDeltaPerformance(String name, int dim) throws IOException {
		log("Testing sine gradient performance at dim=" + dim);

		PackedCollection input = new PackedCollection(shape(dim)).randnFill();
		CollectionProducer x = cp(input);

		OperationProfileNode profile = kernelTest(name, () -> {
			// sin(x)
			CollectionProducer sinX = sin(x);
			return sinX.delta(x);
		}, output -> {
			log("Output shape: " + output.getShape());
			log("Expected shape: " + shape(dim, dim));

			PackedCollection gradient = output.reshape(dim, dim);

			// Spot check a few values
			for (int i = 0; i < Math.min(5, dim); i++) {
				double expected = Math.cos(input.toDouble(i));
				double actual = gradient.valueAt(i, i);
				log("Diagonal[" + i + "]: expected=" + expected + ", actual=" + actual);
				assertEquals(expected, actual);
			}
		}, true, true, true);

		profile.save("results/" + name + ".xml");
		log("Profile saved to results/" + name + ".xml");
	}

	/**
	 * Performance test for cosine gradient at small scale.
	 */
	@Test(timeout = 120000)
	public void cosineDeltaPerformanceSmall() throws IOException {
		cosineDeltaPerformance("cosineDeltaSmall", 16);
	}

	/**
	 * Performance test for cosine gradient at medium scale.
	 */
	@Test(timeout = 300000)
	@TestDepth(1)
	public void cosineDeltaPerformanceMedium() throws IOException {
		cosineDeltaPerformance("cosineDeltaMedium", 64);
	}

	/**
	 * Helper method to test cosine delta performance at specified dimension.
	 * @param name the test name for profiling
	 * @param dim the dimension for the test
	 * @throws IOException if saving the profile fails
	 */
	private void cosineDeltaPerformance(String name, int dim) throws IOException {
		log("Testing cosine gradient performance at dim=" + dim);

		PackedCollection input = new PackedCollection(shape(dim)).randnFill();
		CollectionProducer x = cp(input);

		OperationProfileNode profile = kernelTest(name, () -> {
			// cos(x)
			CollectionProducer cosX = cos(x);
			return cosX.delta(x);
		}, output -> {
			log("Output shape: " + output.getShape());

			PackedCollection gradient = output.reshape(dim, dim);

			// Spot check a few values - d/dx[cos(x)] = -sin(x)
			for (int i = 0; i < Math.min(5, dim); i++) {
				double expected = -Math.sin(input.toDouble(i));
				double actual = gradient.valueAt(i, i);
				log("Diagonal[" + i + "]: expected=" + expected + ", actual=" + actual);
				assertEquals(expected, actual);
			}
		}, true, true, true);

		profile.save("results/" + name + ".xml");
		log("Profile saved to results/" + name + ".xml");
	}

	/**
	 * Tests sin + cos combination gradient.
	 * d/dx[sin(x) + cos(x)] = cos(x) - sin(x)
	 */
	@Test(timeout = 120000)
	public void sinPlusCosGradient() {
		PackedCollection input = pack(0.0, Math.PI / 4, Math.PI / 2, Math.PI);
		CollectionProducer x = cp(input);

		// sin(x) + cos(x)
		CollectionProducer sinPlusCos = sin(x).add(cos(x));

		// d/dx[sin(x) + cos(x)] = cos(x) - sin(x)
		CollectionProducer delta = sinPlusCos.delta(x);
		PackedCollection gradient = delta.get().evaluate().reshape(4, 4);

		for (int i = 0; i < 4; i++) {
			for (int j = 0; j < 4; j++) {
				double xi = input.toDouble(i);
				double expected = (i == j) ? Math.cos(xi) - Math.sin(xi) : 0.0;
				assertEquals(expected, gradient.valueAt(i, j));
			}
		}
	}

	/**
	 * Tests sin * cos combination gradient.
	 * d/dx[sin(x) * cos(x)] = cos^2(x) - sin^2(x)
	 */
	@Test(timeout = 120000)
	public void sinTimesCosGradient() {
		PackedCollection input = pack(0.0, Math.PI / 6, Math.PI / 4, Math.PI / 3);
		CollectionProducer x = cp(input);

		// sin(x) * cos(x)
		CollectionProducer sinTimesCos = sin(x).multiply(cos(x));

		// d/dx[sin(x) * cos(x)] = cos^2(x) - sin^2(x)
		CollectionProducer delta = sinTimesCos.delta(x);
		PackedCollection gradient = delta.get().evaluate().reshape(4, 4);

		for (int i = 0; i < 4; i++) {
			for (int j = 0; j < 4; j++) {
				double xi = input.toDouble(i);
				double cosXi = Math.cos(xi);
				double sinXi = Math.sin(xi);
				double expected = (i == j) ? cosXi * cosXi - sinXi * sinXi : 0.0;
				assertEquals(expected, gradient.valueAt(i, j));
			}
		}
	}

	// =========================================================================
	// Realistic Fourier Features Tests - matching DiffusionTransformerFeatures
	// =========================================================================

	/**
	 * Tests sin(matmul) pattern used in Fourier features.
	 * This matches the pattern: sin(2*PI * matmul(input, weights.T))
	 */
	@Test(timeout = 120000)
	public void sinOfMatmulSmall() throws IOException {
		int batchSize = 1;
		int inFeatures = 4;
		int outFeatures = 8;

		PackedCollection input = new PackedCollection(shape(batchSize, inFeatures)).randnFill();
		PackedCollection weights = new PackedCollection(shape(outFeatures, inFeatures)).randnFill();

		CollectionProducer x = cp(input);
		CollectionProducer w = cp(weights);

		// f = 2*PI * matmul(input, weights.T)
		CollectionProducer f = matmul(x, w.transpose(1)).multiply(2.0 * Math.PI);

		// sin(f)
		CollectionProducer sinF = sin(f);

		log("sinOfMatmul: input shape = " + input.getShape());
		log("sinOfMatmul: weights shape = " + weights.getShape());
		log("sinOfMatmul: f shape = " + shape(batchSize, outFeatures));

		// Compute delta with respect to input
		long start = System.currentTimeMillis();
		CollectionProducer delta = sinF.delta(x);
		PackedCollection gradient = delta.get().evaluate();
		long elapsed = System.currentTimeMillis() - start;

		log("sinOfMatmul: gradient shape = " + gradient.getShape());
		log("sinOfMatmul: delta computation took " + elapsed + " ms");

		// Verify shape: (batchSize * outFeatures) x (batchSize * inFeatures)
		int outputSize = batchSize * outFeatures;
		int inputSize = batchSize * inFeatures;
		assertEquals(outputSize * inputSize, gradient.getMemLength());
	}

	/**
	 * Tests the full Fourier features pattern: concat(cos(f), sin(f))
	 * where f = 2*PI * matmul(input, weights.T)
	 */
	@Test(timeout = 120000)
	public void fourierFeaturesPatternSmall() throws IOException {
		int batchSize = 1;
		int inFeatures = 4;
		int halfOut = 4;  // cos and sin each produce halfOut features
		int outFeatures = halfOut * 2;

		PackedCollection input = new PackedCollection(shape(batchSize, inFeatures)).randnFill();
		PackedCollection weights = new PackedCollection(shape(halfOut, inFeatures)).randnFill();

		CollectionProducer x = cp(input);
		CollectionProducer w = cp(weights);

		// f = 2*PI * matmul(input, weights.T)
		CollectionProducer f = matmul(x, w.transpose(1)).multiply(2.0 * Math.PI);

		// Fourier features: concat(cos(f), sin(f))
		CollectionProducer cosF = cos(f);
		CollectionProducer sinF = sin(f);
		CollectionProducer fourier = concat(shape(batchSize, outFeatures), cosF, sinF);

		log("fourierFeatures: output shape = " + shape(batchSize, outFeatures));

		// Compute delta with respect to input
		long start = System.currentTimeMillis();
		CollectionProducer delta = fourier.delta(x);
		PackedCollection gradient = delta.get().evaluate();
		long elapsed = System.currentTimeMillis() - start;

		log("fourierFeatures: gradient shape = " + gradient.getShape());
		log("fourierFeatures: delta computation took " + elapsed + " ms");

		// Verify shape
		int outputSize = batchSize * outFeatures;
		int inputSize = batchSize * inFeatures;
		assertEquals(outputSize * inputSize, gradient.getMemLength());
	}

	/**
	 * Performance test for Fourier features gradient at model-realistic scale.
	 * Uses dimensions similar to the DiffusionTransformer: batchSize=1, inFeatures=1, outFeatures=256
	 */
	@Test(timeout = 300000)
	@TestDepth(1)
	public void fourierFeaturesPerformanceMedium() throws IOException {
		fourierFeaturesPerformance("fourierMedium", 1, 1, 128);
	}

	/**
	 * Performance test for Fourier features gradient at larger scale.
	 * outFeatures=256 matches the actual timestep embedding in DiffusionTransformer.
	 */
	@Test(timeout = 600000)
	@TestDepth(2)
	public void fourierFeaturesPerformanceLarge() throws IOException {
		fourierFeaturesPerformance("fourierLarge", 1, 1, 256);
	}

	/**
	 * Helper method to test Fourier features performance at specified scale.
	 * @param name the test name for profiling
	 * @param batchSize the batch size
	 * @param inFeatures number of input features
	 * @param halfOut half the output feature count (cos and sin each produce halfOut)
	 * @throws IOException if saving the profile fails
	 */
	private void fourierFeaturesPerformance(String name, int batchSize, int inFeatures, int halfOut) throws IOException {
		int outFeatures = halfOut * 2;

		log("Testing Fourier features gradient: batch=" + batchSize
				+ ", in=" + inFeatures + ", out=" + outFeatures);

		PackedCollection input = new PackedCollection(shape(batchSize, inFeatures)).randnFill();
		PackedCollection weights = new PackedCollection(shape(halfOut, inFeatures)).randnFill();

		OperationProfileNode profile = kernelTest(name, () -> {
			CollectionProducer x = cp(input);
			CollectionProducer w = cp(weights);

			// f = 2*PI * matmul(input, weights.T)
			CollectionProducer f = matmul(x, w.transpose(1)).multiply(2.0 * Math.PI);

			// Fourier features: concat(cos(f), sin(f))
			CollectionProducer cosF = cos(f);
			CollectionProducer sinF = sin(f);
			CollectionProducer fourier = concat(shape(batchSize, outFeatures), cosF, sinF);

			return fourier.delta(x);
		}, output -> {
			log("Output shape: " + output.getShape());
			int outputSize = batchSize * outFeatures;
			int inputSize = batchSize * inFeatures;
			log("Expected total size: " + (outputSize * inputSize));
			assertEquals(outputSize * inputSize, output.getMemLength());
		}, true, true, true);

		profile.save("results/" + name + ".xml");
		log("Profile saved to results/" + name + ".xml");
	}

	/**
	 * Tests gradient through sin(matmul) with respect to WEIGHTS (not input).
	 * This is what actually happens during training - we need gradients w.r.t. learned weights.
	 */
	@Test(timeout = 120000)
	public void sinOfMatmulWeightGradient() throws IOException {
		int batchSize = 1;
		int inFeatures = 4;
		int outFeatures = 8;

		PackedCollection input = new PackedCollection(shape(batchSize, inFeatures)).randnFill();
		PackedCollection weights = new PackedCollection(shape(outFeatures, inFeatures)).randnFill();

		CollectionProducer x = cp(input);
		CollectionProducer w = cp(weights);

		// f = 2*PI * matmul(input, weights.T)
		CollectionProducer f = matmul(x, w.transpose(1)).multiply(2.0 * Math.PI);

		// sin(f)
		CollectionProducer sinF = sin(f);

		log("sinOfMatmul weight gradient: weights shape = " + weights.getShape());

		// Compute delta with respect to WEIGHTS
		long start = System.currentTimeMillis();
		CollectionProducer delta = sinF.delta(w);
		PackedCollection gradient = delta.get().evaluate();
		long elapsed = System.currentTimeMillis() - start;

		log("sinOfMatmul weight gradient: gradient shape = " + gradient.getShape());
		log("sinOfMatmul weight gradient: delta computation took " + elapsed + " ms");

		// Verify shape: (batchSize * outFeatures) x (outFeatures * inFeatures)
		int outputSize = batchSize * outFeatures;
		int weightSize = outFeatures * inFeatures;
		assertEquals(outputSize * weightSize, gradient.getMemLength());
	}

	/**
	 * Performance test for Fourier features weight gradient at model-realistic scale.
	 * This is the pattern that occurs during backpropagation through the timestep embedding.
	 */
	@Test(timeout = 300000)
	@TestDepth(1)
	public void fourierFeaturesWeightGradientMedium() throws IOException {
		fourierFeaturesWeightGradient("fourierWeightMedium", 1, 1, 64);
	}

	/**
	 * Performance test for Fourier features weight gradient at larger scale.
	 */
	@Test(timeout = 600000)
	@TestDepth(2)
	public void fourierFeaturesWeightGradientLarge() throws IOException {
		fourierFeaturesWeightGradient("fourierWeightLarge", 1, 1, 128);
	}

	/**
	 * Helper method to test Fourier features weight gradient at specified scale.
	 * @param name the test name for profiling
	 * @param batchSize the batch size
	 * @param inFeatures number of input features
	 * @param halfOut half the output feature count
	 * @throws IOException if saving the profile fails
	 */
	private void fourierFeaturesWeightGradient(String name, int batchSize, int inFeatures, int halfOut) throws IOException {
		int outFeatures = halfOut * 2;

		log("Testing Fourier features WEIGHT gradient: batch=" + batchSize
				+ ", in=" + inFeatures + ", out=" + outFeatures);

		PackedCollection input = new PackedCollection(shape(batchSize, inFeatures)).randnFill();
		PackedCollection weights = new PackedCollection(shape(halfOut, inFeatures)).randnFill();

		OperationProfileNode profile = kernelTest(name, () -> {
			CollectionProducer x = cp(input);
			CollectionProducer w = cp(weights);

			// f = 2*PI * matmul(input, weights.T)
			CollectionProducer f = matmul(x, w.transpose(1)).multiply(2.0 * Math.PI);

			// Fourier features: concat(cos(f), sin(f))
			CollectionProducer cosF = cos(f);
			CollectionProducer sinF = sin(f);
			CollectionProducer fourier = concat(shape(batchSize, outFeatures), cosF, sinF);

			// Delta with respect to WEIGHTS
			return fourier.delta(w);
		}, output -> {
			log("Output shape: " + output.getShape());
			int outputSize = batchSize * outFeatures;
			int weightSize = halfOut * inFeatures;
			log("Expected total size: " + (outputSize * weightSize));
			assertEquals(outputSize * weightSize, output.getMemLength());
		}, true, true, true);

		profile.save("results/" + name + ".xml");
		log("Profile saved to results/" + name + ".xml");
	}
}
