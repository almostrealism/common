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

import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.CollectionExponentComputation;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Unit tests demonstrating the functionality and behavior of {@link CollectionExponentComputation}.
 * These tests showcase element-wise power operations, broadcasting, delta computation, and
 * multi-dimensional scenarios to validate the comprehensive javadoc documentation.
 */
public class CollectionExponentComputationTest extends TestSuiteBase {

	/**
	 * Tests basic element-wise exponentiation with matching dimensions.
	 * Demonstrates: [2, 3, 4] ^ [2, 2, 2] = [4, 9, 16]
	 */
	@Test(timeout = 30000)
	public void elementWisePower() {
		PackedCollection base = pack(2.0, 3.0, 4.0);
		PackedCollection exponent = pack(2.0, 2.0, 2.0);

		CollectionExponentComputation power =
				new CollectionExponentComputation(shape(3), p(base), p(exponent));

		PackedCollection result = power.get().evaluate();

		Assert.assertEquals(4.0, result.valueAt(0), 1e-10);
		Assert.assertEquals(9.0, result.valueAt(1), 1e-10);
		Assert.assertEquals(16.0, result.valueAt(2), 1e-10);
	}

	/**
	 * Tests broadcasting a scalar exponent to all elements.
	 * Demonstrates: [2, 3, 4] ^ 3 = [8, 27, 64]
	 */
	@Test(timeout = 30000)
	public void scalarExponentBroadcast() {
		PackedCollection base = pack(2.0, 3.0, 4.0);

		// Using the CollectionFeatures pow method which internally uses CollectionExponentComputation
		CollectionProducer cubed = cp(base).pow(c(3.0));
		PackedCollection result = cubed.get().evaluate();

		Assert.assertEquals(8.0, result.valueAt(0), 1e-10);
		Assert.assertEquals(27.0, result.valueAt(1), 1e-10);
		Assert.assertEquals(64.0, result.valueAt(2), 1e-10);
	}

	/**
	 * Tests multi-dimensional power operations on matrices.
	 * Demonstrates element-wise power on 2x3 matrix.
	 */
	@Test(timeout = 30000)
	public void matrixPower() {
		PackedCollection matrix = new PackedCollection(shape(2, 3));
		// Fill with [[1,2,3], [2,3,4]]
		matrix.setValueAt(1.0, 0, 0);
		matrix.setValueAt(2.0, 0, 1);
		matrix.setValueAt(3.0, 0, 2);
		matrix.setValueAt(2.0, 1, 0);
		matrix.setValueAt(3.0, 1, 1);
		matrix.setValueAt(4.0, 1, 2);

		CollectionExponentComputation matrixPower =
				new CollectionExponentComputation(shape(2, 3), p(matrix), c(2.0));
		PackedCollection squared = matrixPower.get().evaluate();

		// Expected result: [[1,4,9], [4,9,16]]
		Assert.assertEquals(1.0, squared.valueAt(0, 0), 1e-10);
		Assert.assertEquals(4.0, squared.valueAt(0, 1), 1e-10);
		Assert.assertEquals(9.0, squared.valueAt(0, 2), 1e-10);
		Assert.assertEquals(4.0, squared.valueAt(1, 0), 1e-10);
		Assert.assertEquals(9.0, squared.valueAt(1, 1), 1e-10);
		Assert.assertEquals(16.0, squared.valueAt(1, 2), 1e-10);
	}

	/**
	 * Tests various power operations including fractional exponents.
	 * Demonstrates square roots, cubes, and reciprocals.
	 */
	@Test(timeout = 30000)
	public void variousPowerOperations() {
		PackedCollection values = pack(4.0, 8.0, 16.0, 25.0);

		// Square roots (x^0.5)
		CollectionProducer sqrt = cp(values).pow(c(0.5));
		PackedCollection sqrtResult = sqrt.get().evaluate();
		assertEquals(2.0, sqrtResult.valueAt(0));
		assertEquals(2.828427124746, sqrtResult.valueAt(1)); // sqrt(8)
		assertEquals(4.0, sqrtResult.valueAt(2));
		assertEquals(5.0, sqrtResult.valueAt(3));

		// Cubes (x^3)
		CollectionProducer cubes = cp(values).pow(c(3.0));
		PackedCollection cubeResult = cubes.get().evaluate();
		assertEquals(64.0, cubeResult.valueAt(0));    // 4^3
		assertEquals(512.0, cubeResult.valueAt(1));   // 8^3
		assertEquals(4096.0, cubeResult.valueAt(2));  // 16^3
		assertEquals(15625.0, cubeResult.valueAt(3)); // 25^3

		// Reciprocals (x^-1)
		CollectionProducer reciprocals = cp(values).pow(c(-1.0));
		PackedCollection recipResult = reciprocals.get().evaluate();
		assertEquals(0.25, recipResult.valueAt(0));    // 1/4
		assertEquals(0.125, recipResult.valueAt(1));   // 1/8
		assertEquals(0.0625, recipResult.valueAt(2));  // 1/16
		assertEquals(0.04, recipResult.valueAt(3));    // 1/25
	}

	/**
	 * Tests the delta (derivative) computation functionality.
	 * Demonstrates automatic differentiation using the power rule: d/dx[x^n] = n*x^(n-1)
	 */
	@Test(timeout = 30000)
	public void deltaComputation() {
		// Test f(x) = x^3, df/dx = 3*x^2
		PackedCollection testValues = pack(1.0, 2.0, 3.0);
		CollectionProducer x = cp(testValues);
		CollectionProducer f = x.pow(c(3.0));

		// Compute derivative
		CollectionProducer df_dx = f.delta(x);
		PackedCollection derivative = df_dx.get().evaluate();

		derivative.print();

		// Expected: 3*x^2 = [3*1^2, 3*2^2, 3*3^2] = [3, 12, 27]
		assertEquals(3.0, derivative.valueAt(0, 0));
		assertEquals(12.0, derivative.valueAt(1, 1));
		assertEquals(27.0, derivative.valueAt(2, 2));
	}

	/**
	 * Tests edge cases including powers of zero and one.
	 */
	@Test(timeout = 30000)
	public void edgeCases() {
		// Test x^0 = 1 (for non-zero x)
		PackedCollection nonZeroValues = pack(2.0, 5.0, 10.0);
		CollectionProducer powerZero = cp(nonZeroValues).pow(c(0.0));
		PackedCollection result = powerZero.get().evaluate();
		Assert.assertEquals(1.0, result.valueAt(0), 1e-10);
		Assert.assertEquals(1.0, result.valueAt(1), 1e-10);
		Assert.assertEquals(1.0, result.valueAt(2), 1e-10);

		// Test x^1 = x
		CollectionProducer powerOne = cp(nonZeroValues).pow(c(1.0));
		PackedCollection identityResult = powerOne.get().evaluate();
		Assert.assertEquals(2.0, identityResult.valueAt(0), 1e-10);
		Assert.assertEquals(5.0, identityResult.valueAt(1), 1e-10);
		Assert.assertEquals(10.0, identityResult.valueAt(2), 1e-10);

		// Test 1^x = 1
		PackedCollection exponents = pack(2.0, 10.0, 100.0);
		CollectionProducer oneToX = c(1.0).pow(cp(exponents));
		PackedCollection onesResult = oneToX.get().evaluate();
		Assert.assertEquals(1.0, onesResult.valueAt(0), 1e-10);
		Assert.assertEquals(1.0, onesResult.valueAt(1), 1e-10);
		Assert.assertEquals(1.0, onesResult.valueAt(2), 1e-10);
	}

	/**
	 * Tests configuration flag behavior for custom delta computation.
	 * Temporarily disables custom delta to verify fallback behavior.
	 */
	@Test(timeout = 30000)
	public void customDeltaConfiguration() {
		// Save original state
		boolean originalCustomDelta = CollectionExponentComputation.enableCustomDelta;

		try {
			// Test with custom delta enabled (default)
			CollectionExponentComputation.enableCustomDelta = true;
			PackedCollection testValues = pack(2.0, 3.0);
			CollectionProducer x = cp(testValues);
			CollectionProducer f = x.pow(c(2.0));

			CollectionProducer df_dx_custom = f.delta(x);
			PackedCollection customResult = df_dx_custom.get().evaluate();

			customResult.print();

			// Expected: 2*x = [4, 6]
			assertEquals(4.0, customResult.valueAt(0, 0));
			assertEquals(6.0, customResult.valueAt(1, 1));

			// Test with custom delta disabled
			CollectionExponentComputation.enableCustomDelta = false;
			CollectionProducer df_dx_default = f.delta(x);
			PackedCollection defaultResult = df_dx_default.get().evaluate();

			// Should still compute correctly but potentially less efficiently
			assertEquals(4.0, defaultResult.valueAt(0, 0));
			assertEquals(6.0, defaultResult.valueAt(1, 1));
		} finally {
			// Restore original state
			CollectionExponentComputation.enableCustomDelta = originalCustomDelta;
		}
	}
}