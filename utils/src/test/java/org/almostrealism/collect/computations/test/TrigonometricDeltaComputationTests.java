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

import io.almostrealism.profile.OperationProfileNode;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.IOException;

/**
 * Tests for trigonometric function gradients (sine, cosine).
 * These tests verify both correctness and performance of the Producer-level
 * delta implementations in {@link org.almostrealism.collect.computations.CollectionSineComputation}
 * and {@link org.almostrealism.collect.computations.CollectionCosineComputation}.
 *
 * @author Michael Murray
 */
public class TrigonometricDeltaComputationTests extends TestSuiteBase {

	/**
	 * Tests that d/dx[sin(x)] = cos(x) for a simple scalar case.
	 */
	@Test
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
	@Test
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
	 * Tests the chain rule: d/dx[sin(2x)] = 2*cos(2x).
	 */
	@Test
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
	@Test
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
	@Test
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
	@Test
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
	@Test
	public void sineDeltaPerformanceSmall() throws IOException {
		sineDeltaPerformance("sineDeltaSmall", 16);
	}

	/**
	 * Performance test for sine gradient at medium scale.
	 */
	@Test
	@TestDepth(1)
	public void sineDeltaPerformanceMedium() throws IOException {
		sineDeltaPerformance("sineDeltaMedium", 64);
	}

	/**
	 * Performance test for sine gradient at larger scale.
	 */
	@Test
	@TestDepth(2)
	public void sineDeltaPerformanceLarge() throws IOException {
		sineDeltaPerformance("sineDeltaLarge", 256);
	}

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
	@Test
	public void cosineDeltaPerformanceSmall() throws IOException {
		cosineDeltaPerformance("cosineDeltaSmall", 16);
	}

	/**
	 * Performance test for cosine gradient at medium scale.
	 */
	@Test
	@TestDepth(1)
	public void cosineDeltaPerformanceMedium() throws IOException {
		cosineDeltaPerformance("cosineDeltaMedium", 64);
	}

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
	@Test
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
	@Test
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
}
