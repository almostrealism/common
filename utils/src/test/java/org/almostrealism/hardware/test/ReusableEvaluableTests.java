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

package org.almostrealism.hardware.test;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.DelegatedCollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

public class ReusableEvaluableTests implements TestFeatures {

	@Test
	public void add() {
		int n = 6;

		PackedCollection<?> a = new PackedCollection<>(shape(n))
				.randFill().traverseEach();
		PackedCollection<?> b = new PackedCollection<>(shape(n))
				.randFill().traverseEach();
		PackedCollection<?> c = new PackedCollection<>(shape(n))
				.randFill().traverseEach();

		Producer<PackedCollection<?>> sum = createSum(cp(a), cp(b), cp(c));
		PackedCollection<?> out = sum.get().evaluate();

		for (int i = 0; i < n; i++) {
			assertEquals(a.toDouble(i) + b.toDouble(i) + c.toDouble(i), out.toDouble(i));
		}

		PackedCollection<?> alt = new PackedCollection<>(shape(n))
				.randFill().traverseEach();

		sum = createSum(cp(alt), cp(b), cp(c));
		out = sum.get().evaluate();

		for (int i = 0; i < n; i++) {
			assertEquals(alt.toDouble(i) + b.toDouble(i) + c.toDouble(i), out.toDouble(i));
		}
	}

	@Test
	public void multiply1() {
		int n = 6;

		PackedCollection<?> a = new PackedCollection<>(shape(n))
				.randFill().traverseEach();
		PackedCollection<?> b = new PackedCollection<>(shape(n))
				.randFill().traverseEach();
		PackedCollection<?> c = new PackedCollection<>(shape(n))
				.randFill().traverseEach();

		Producer<PackedCollection<?>> product = createProduct(cp(a), cp(b), cp(c));
		PackedCollection<?> out = product.get().evaluate();

		for (int i = 0; i < n; i++) {
			assertEquals((a.toDouble(i) + b.toDouble(i)) * c.toDouble(i), out.toDouble(i));
		}

		PackedCollection<?> alt = new PackedCollection<>(shape(n))
				.randFill().traverseEach();

		product = createProduct(cp(alt), cp(b), cp(c));
		out = product.get().evaluate();

		for (int i = 0; i < n; i++) {
			assertEquals((alt.toDouble(i) + b.toDouble(i)) * c.toDouble(i), out.toDouble(i));
		}
	}

	@Test
	public void multiply2() {
		int n = 6;
		int m = 10;

		PackedCollection<?> a = new PackedCollection<>(shape(n))
				.randFill().traverseEach();
		PackedCollection<?> b = new PackedCollection<>(shape(n))
				.randFill().traverseEach();
		PackedCollection<?> c = new PackedCollection<>(shape(n))
				.randFill().traverseEach();

		Producer<PackedCollection<?>> product = createProduct(cp(a), cp(b), cp(c));
		PackedCollection<?> out = product.get().evaluate();

		for (int i = 0; i < n; i++) {
			assertEquals((a.toDouble(i) + b.toDouble(i)) * c.toDouble(i), out.toDouble(i));
		}

		a = new PackedCollection<>(shape(m))
				.randFill().traverseEach();
		b = new PackedCollection<>(shape(m))
				.randFill().traverseEach();
		c = new PackedCollection<>(shape(m))
				.randFill().traverseEach();

		product = createProduct(cp(a), cp(b), cp(c));
		out = product.get().evaluate();

		for (int i = 0; i < m; i++) {
			assertEquals((a.toDouble(i) + b.toDouble(i)) * c.toDouble(i), out.toDouble(i));
		}
	}

	protected Producer<PackedCollection<?>> createSum(Producer<PackedCollection<?>> a,
																Producer<PackedCollection<?>> b,
																Producer<PackedCollection<?>> c) {
		return instruct("ReusableEvaluableTests.sum",
						args -> add(args[0], args[1]).add(args[2]), a, b, c);
	}

	protected Producer<PackedCollection<?>> createProduct(Producer<PackedCollection<?>> a,
																    Producer<PackedCollection<?>> b,
																    Producer<PackedCollection<?>> c) {
		return instruct("ReusableEvaluableTests.product",
				args -> multiply(add(args[0], args[1]), args[2]), a, b, c);
	}

	protected <T extends PackedCollection<?>> DelegatedCollectionProducer<T> v(Producer<T> inner) {
		return new DelegatedCollectionProducer<>(c(inner), false) {
			@Override
			public boolean isFixedCount() {
				return false;
			}
		};
	}
}
