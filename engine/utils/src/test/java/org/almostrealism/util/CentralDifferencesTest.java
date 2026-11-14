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

package org.almostrealism.util;

import io.almostrealism.relation.Evaluable;
import org.almostrealism.collect.PackedCollection;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for {@link TestFeatures#centralDifferences(PackedCollection, double,
 * java.util.function.DoubleSupplier)}: the derivative it reports for every element, and the
 * restoration of the perturbed collection both when the function returns normally and when it
 * throws part way through.
 */
public class CentralDifferencesTest extends TestSuiteBase {
	/**
	 * The central differences of the sum of squares are {@code 2 x} at every element, and the
	 * collection holds its original values afterwards.
	 */
	@Test(timeout = 60000)
	public void sumOfSquaresDerivative() {
		PackedCollection values = PackedCollection.of(1.0, -2.0, 3.0);
		Evaluable<PackedCollection> sumOfSquares = cp(values).multiply(cp(values)).sum().get();

		double[] numeric = centralDifferences(values, 0.5,
				() -> sumOfSquares.evaluate().toDouble(0));

		Assert.assertEquals(3, numeric.length);
		Assert.assertEquals(2.0, numeric[0], 1e-6);
		Assert.assertEquals(-4.0, numeric[1], 1e-6);
		Assert.assertEquals(6.0, numeric[2], 1e-6);
		Assert.assertArrayEquals(new double[] {1.0, -2.0, 3.0}, values.toArray(), 0.0);
		values.destroy();
	}

	/**
	 * For the sum of fourth powers, whose derivative is {@code 4 x^3}, a central difference with
	 * step {@code h} is off by {@code h^2 f'''/6 = h^2 (24 x)/6}, which is {@code x} for
	 * {@code h = 0.5}; the extrapolated difference cancels that term and, the fifth derivative being
	 * zero, is exact up to rounding. The collection holds its original values afterwards.
	 */
	@Test(timeout = 60000)
	public void extrapolatedDifferencesCancelTheSecondOrderError() {
		PackedCollection values = PackedCollection.of(1.0, -2.0, 3.0);
		Evaluable<PackedCollection> sumOfFourthPowers = cp(values).pow(c(4.0)).sum().get();
		double[] x = {1.0, -2.0, 3.0};

		double[] central = centralDifferences(values, 0.5,
				() -> sumOfFourthPowers.evaluate().toDouble(0));
		double[] extrapolated = extrapolatedDifferences(values, 0.5,
				() -> sumOfFourthPowers.evaluate().toDouble(0));

		Assert.assertEquals(3, extrapolated.length);
		for (int i = 0; i < x.length; i++) {
			double derivative = 4 * x[i] * x[i] * x[i];
			Assert.assertEquals(derivative + x[i], central[i], 1e-3);
			Assert.assertEquals(derivative, extrapolated[i], 1e-3);
		}
		Assert.assertArrayEquals(x, values.toArray(), 0.0);
		values.destroy();
	}

	/**
	 * When the function throws while an element is perturbed, the failure reaches the caller
	 * unchanged and the collection still holds its original values rather than the perturbed
	 * copy that was being evaluated.
	 */
	@Test(timeout = 60000)
	public void restoresValuesWhenFunctionThrows() {
		PackedCollection values = PackedCollection.of(1.0, -2.0, 3.0);
		int[] calls = {0};
		double[] seen = new double[3];

		try {
			centralDifferences(values, 0.5, () -> {
				seen[calls[0]] = values.toDouble(0);
				if (++calls[0] == 3) throw new IllegalStateException("broken graph");
				return 0.0;
			});
			Assert.fail("the failure of the function was not propagated");
		} catch (IllegalStateException expected) {
			Assert.assertEquals("broken graph", expected.getMessage());
		}

		Assert.assertEquals(3, calls[0]);
		Assert.assertEquals(1.5, seen[0], 1e-9);
		Assert.assertEquals(0.5, seen[1], 1e-9);
		Assert.assertEquals(1.0, seen[2], 1e-9);
		Assert.assertArrayEquals(new double[] {1.0, -2.0, 3.0}, values.toArray(), 0.0);
		values.destroy();
	}
}
