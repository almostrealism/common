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
		input.fill(pos -> 0.5 + Math.random());

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
		input.fill(pos -> 0.5 + Math.random());

		PackedCollection result = cumulativeProduct(cp(input), true).get().evaluate();
		Assert.assertEquals(n, result.getShape().getTotalSize());

		double r = 1.0;
		for (int i = 0; i < n; i++) {
			assertEquals(r, result.valueAt(i));
			r *= input.valueAt(i);
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
