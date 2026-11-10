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

package org.almostrealism.collect.computations.test;

import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Pins the Jacobian produced by the single-factor chain-rule delta shared across the
 * element-wise unary computations (sine, cosine, hyperbolic tangent, exponential and
 * logarithm). Every one of these routes its {@code deltaDependent} through the same
 * {@code g'(f(x)) * f'(x)} construction on
 * {@link org.almostrealism.collect.computations.TraversableExpressionComputation}, so
 * these tests assert the diagonal Jacobian each call site emits for a scalar input and
 * guard that the shared construction preserves the behavior of each copy it replaced.
 *
 * <p>The exponential and logarithm delta paths had no dedicated coverage before this
 * class; they are pinned here alongside the sine path so the consolidation is exercised
 * at every call site.</p>
 *
 * @author Michael Murray
 */
public class ElementwiseChainRuleDeltaTests extends TestSuiteBase {

	/**
	 * Verifies that {@code d/dx[e^x]} is a diagonal Jacobian carrying {@code e^x} on the
	 * diagonal, pinning the {@link org.almostrealism.collect.computations.CollectionExponentialComputation}
	 * chain-rule delta.
	 */
	@Test(timeout = 120000)
	public void exponentialDeltaScalar() {
		PackedCollection input = pack(-1.5, -0.25, 0.0, 0.5, 2.0);
		CollectionProducer x = cp(input);

		CollectionProducer expX = exp(x);

		PackedCollection forward = expX.get().evaluate();
		for (int i = 0; i < 5; i++) {
			assertEquals(Math.exp(input.toDouble(i)), forward.toDouble(i));
		}

		PackedCollection gradient = expX.delta(x).get().evaluate().reshape(5, 5);

		for (int i = 0; i < 5; i++) {
			for (int j = 0; j < 5; j++) {
				double expected = (i == j) ? Math.exp(input.toDouble(i)) : 0.0;
				assertEquals("d exp(x[" + i + "]) / d x[" + j + "]", expected, gradient.valueAt(i, j), 1e-5);
			}
		}
	}

	/**
	 * Verifies that {@code d/dx[ln(x)]} is a diagonal Jacobian carrying {@code 1/x} on the
	 * diagonal, pinning the {@link org.almostrealism.collect.computations.CollectionLogarithmComputation}
	 * chain-rule delta (which is enabled by default).
	 */
	@Test(timeout = 120000)
	public void logarithmDeltaScalar() {
		PackedCollection input = pack(0.25, 0.5, 1.0, 2.0, 4.0);
		CollectionProducer x = cp(input);

		CollectionProducer logX = log(x);

		PackedCollection forward = logX.get().evaluate();
		for (int i = 0; i < 5; i++) {
			assertEquals(Math.log(input.toDouble(i)), forward.toDouble(i));
		}

		PackedCollection gradient = logX.delta(x).get().evaluate().reshape(5, 5);

		for (int i = 0; i < 5; i++) {
			for (int j = 0; j < 5; j++) {
				double expected = (i == j) ? 1.0 / input.toDouble(i) : 0.0;
				assertEquals("d ln(x[" + i + "]) / d x[" + j + "]", expected, gradient.valueAt(i, j), 1e-5);
			}
		}
	}

	/**
	 * Verifies the zero-ignoring exponential delta, whose cofactor is itself
	 * {@code expIgnoreZero(x)}: the diagonal carries {@code e^x} for non-zero inputs and
	 * {@code 0} where the input is zero, because the forward pass passes zero through
	 * unchanged.
	 */
	@Test(timeout = 120000)
	public void exponentialIgnoreZeroDeltaScalar() {
		PackedCollection input = pack(-1.5, 0.0, 0.5, 0.0, 2.0);
		CollectionProducer x = cp(input);

		CollectionProducer expX = expIgnoreZero(x);

		PackedCollection forward = expX.get().evaluate();
		for (int i = 0; i < 5; i++) {
			double v = input.toDouble(i);
			assertEquals(v == 0.0 ? 0.0 : Math.exp(v), forward.toDouble(i));
		}

		PackedCollection gradient = expX.delta(x).get().evaluate().reshape(5, 5);

		for (int i = 0; i < 5; i++) {
			double v = input.toDouble(i);
			for (int j = 0; j < 5; j++) {
				double expected = (i == j && v != 0.0) ? Math.exp(v) : 0.0;
				assertEquals("d expIgnoreZero(x[" + i + "]) / d x[" + j + "]", expected, gradient.valueAt(i, j), 1e-5);
			}
		}
	}

	/**
	 * Verifies that the inner factor {@code f'(x)} is applied when the input is not the
	 * target itself: {@code d/dx[e^(3x)] = 3 e^(3x)} and {@code d/dx[ln(3x)] = 1/x}, each on
	 * the diagonal of the Jacobian.
	 */
	@Test(timeout = 120000)
	public void scaledInputDelta() {
		PackedCollection input = pack(0.25, 0.5, 1.0, 1.5, 2.0);
		CollectionProducer x = cp(input);

		PackedCollection expGradient = exp(x.multiply(3.0)).delta(x).get().evaluate().reshape(5, 5);
		PackedCollection logGradient = log(x.multiply(3.0)).delta(x).get().evaluate().reshape(5, 5);

		for (int i = 0; i < 5; i++) {
			double v = input.toDouble(i);
			for (int j = 0; j < 5; j++) {
				double expectedExp = (i == j) ? 3.0 * Math.exp(3.0 * v) : 0.0;
				double expectedLog = (i == j) ? 1.0 / v : 0.0;
				assertEquals("d exp(3x[" + i + "]) / d x[" + j + "]",
						expectedExp, expGradient.valueAt(i, j), 1e-4 * Math.max(1.0, expectedExp));
				assertEquals("d ln(3x[" + i + "]) / d x[" + j + "]",
						expectedLog, logGradient.valueAt(i, j), 1e-5);
			}
		}
	}

	/**
	 * Verifies that {@code d/dx[sin(x)]} is a diagonal Jacobian carrying {@code cos(x)} on the
	 * diagonal, pinning the {@link org.almostrealism.collect.computations.UnaryCollectionComputation}
	 * chain-rule delta (via the sine cofactor) through the same shared construction.
	 */
	@Test(timeout = 120000)
	public void sineDeltaDiagonal() {
		PackedCollection input = pack(0.0, Math.PI / 6, Math.PI / 4, Math.PI / 3, Math.PI / 2);
		CollectionProducer x = cp(input);

		CollectionProducer sinX = sin(x);

		PackedCollection gradient = sinX.delta(x).get().evaluate().reshape(5, 5);

		for (int i = 0; i < 5; i++) {
			for (int j = 0; j < 5; j++) {
				double expected = (i == j) ? Math.cos(input.toDouble(i)) : 0.0;
				assertEquals("d sin(x[" + i + "]) / d x[" + j + "]", expected, gradient.valueAt(i, j), 1e-5);
			}
		}
	}
}
