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

import org.almostrealism.algebra.Tensor;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests that {@code cumulativeProduct} matches a host-computed reference for both the
 * inclusive and the padded (exclusive) forms, and that {@link Tensor#pack()} preserves
 * element values and ordering.
 */
public class CumulativeProductTests extends TestSuiteBase {

	/**
	 * Tests the inclusive cumulative product against a host-computed reference,
	 * where element i is the product of input elements 0 through i.
	 */
	@Test(timeout = 60000)
	public void cumulativeProductInclusive() {
		int n = 25;

		PackedCollection input = new PackedCollection(shape(n));
		rand(input.getShape()).add(0.5).into(input.traverseEach()).evaluate();

		PackedCollection result = cumulativeProduct(cp(input), false).get().evaluate();
		Assert.assertEquals(n, result.getShape().getTotalSize());

		double r = 1.0;
		for (int i = 0; i < n; i++) {
			r *= input.valueAt(i);
			assertEquals(r, result.valueAt(i));
		}
	}

	/**
	 * Tests the padded (exclusive) cumulative product against a host-computed reference,
	 * where element 0 is 1.0 and element i is the product of input elements 0 through i - 1.
	 */
	@Test(timeout = 60000)
	public void cumulativeProductExclusive() {
		int n = 25;

		PackedCollection input = new PackedCollection(shape(n));
		rand(input.getShape()).add(0.5).into(input.traverseEach()).evaluate();

		PackedCollection result = cumulativeProduct(cp(input), true).get().evaluate();
		Assert.assertEquals(n, result.getShape().getTotalSize());

		double r = 1.0;
		for (int i = 0; i < n; i++) {
			assertEquals(r, result.valueAt(i));
			r *= input.valueAt(i);
		}
	}

	/**
	 * Tests the inclusive cumulative product for fixed input values, pinning the exact output of
	 * the scan's accumulation loop to independently computed constants. Each iteration reads the
	 * running product back through the shared accumulator reference on
	 * {@code RepeatedProducerComputation}, so a fixed input isolates that read from the randomness
	 * of {@link #cumulativeProductInclusive()}.
	 */
	@Test(timeout = 60000)
	public void cumulativeProductFixedValues() {
		PackedCollection input = pack(2.0, 3.0, 0.5, 4.0, 1.5);
		PackedCollection result = cumulativeProduct(cp(input), false).get().evaluate();

		double[] expected = { 2.0, 6.0, 3.0, 12.0, 18.0 };
		Assert.assertEquals(expected.length, result.getShape().getTotalSize());
		for (int i = 0; i < expected.length; i++) {
			assertEquals(expected[i], result.valueAt(i));
		}
	}

	/**
	 * Differentiates an inclusive cumulative product with respect to its input and pins the shape
	 * and values of the resulting Jacobian.
	 *
	 * <p>Differentiating a prefix scan routes through {@code ConstantRepeatedDeltaComputation},
	 * which re-evaluates the scan's loop body with a {@code null} global index. That is the only
	 * path that reaches the null branch of {@code RepeatedProducerComputation.kernelIndex} and the
	 * delta-side call of {@code getCurrentOutputValue} (which must read the delta kernel's own
	 * output argument, {@code args[0]}, not {@code getOutputVariable()}); a forward evaluation
	 * never exercises either. This test therefore guards against a regression that made that path
	 * throw a {@link NullPointerException} when the index-normalization helper stopped tolerating a
	 * {@code null} global index.
	 *
	 * <p>The Jacobian itself is a {@code (5, 5)} collection of zeros: the running product is read
	 * back through an output-accumulator memory reference, which differentiation treats as a
	 * constant, so the in-loop term contributes no gradient. That result is a known limitation of
	 * prefix-scan differentiation — not a property this test endorses as the mathematically correct
	 * Jacobian — but it is the established, non-throwing behavior the consolidation must preserve.
	 */
	@Test(timeout = 120000)
	public void cumulativeProductDelta() {
		PackedCollection input = pack(2.0, 3.0, 0.5, 4.0, 1.5);

		PackedCollection jacobian = cumulativeProduct(cp(input), false)
				.delta(cp(input)).evaluate();

		Assert.assertEquals(2, jacobian.getShape().getDimensions());
		Assert.assertEquals(5, jacobian.getShape().length(0));
		Assert.assertEquals(5, jacobian.getShape().length(1));
		Assert.assertEquals(25, jacobian.getMemLength());

		for (int i = 0; i < 25; i++) {
			assertEquals(0.0, jacobian.toDouble(i));
		}
	}

	/**
	 * Tests that packing a numeric {@link Tensor} produces a collection with the
	 * tensor's shape and its element values in position order.
	 */
	@Test(timeout = 60000)
	public void tensorPack() {
		Tensor<Double> t = new Tensor<>();
		for (int i = 0; i < 3; i++) {
			for (int j = 0; j < 4; j++) {
				t.insert(10.0 * i + j, i, j);
			}
		}

		PackedCollection c = t.pack();
		Assert.assertEquals(12, c.getShape().getTotalSize());

		for (int i = 0; i < 3; i++) {
			for (int j = 0; j < 4; j++) {
				assertEquals(10.0 * i + j, c.valueAt(i, j));
			}
		}
	}
}
