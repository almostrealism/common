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

import io.almostrealism.collect.RepeatTraversalOrdering;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Tensor;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestFeatures;
import org.junit.Assert;
import org.junit.Test;

import java.util.function.Consumer;
import java.util.function.Supplier;

public class PackedCollectionRepeatTests implements TestFeatures {
	@Test
	public void isolatedRepeat() {
		if (skipKnownIssues) return;

		int d = 4;
		int w = 2;
		int h = 3;

		PackedCollection v = new PackedCollection(shape(w, h));
		v.fill(pos -> Math.random());

		PackedCollection out = cp(v).repeat(d).get().evaluate();

		out.print();

		for (int x = 0; x < d; x++) {
			for (int y = 0; y < w; y++) {
				for (int z = 0; z < h; z++) {
					double expected = v.valueAt(y, z);
					double actual = out.valueAt(x, y, z);
					assertEquals(expected, actual);
				}
			}
		}

		Assert.assertTrue(out.getShape().getOrder() instanceof RepeatTraversalOrdering);
	}

	@Test
	public void repeatItem() {
		int w = 2;
		int h = 3;
		int d = 4;

		PackedCollection v = new PackedCollection(shape(w, h));
		v.fill(pos -> Math.random());

		PackedCollection out = cp(v).traverse().repeat(d).get().evaluate();

		print(2, 12, out);

		for (int x = 0; x < w; x++) {
			for (int y = 0; y < d; y++) {
				for (int z = 0; z < h; z++) {
					double expected = v.valueAt(x, z);
					double actual = out.valueAt(x, y, z);
					assertEquals(expected, actual);
				}
			}
		}
	}

	@Test
	public void repeat3d() {
		int w = 1;
		int h = 2;
		int d = 4;

		PackedCollection v = new PackedCollection(shape(w, h));
		v.fill(pos -> Math.random());

		verboseLog(() -> {
			PackedCollection out = c(p(v)).traverseEach().repeat(d).get().evaluate();

			for (int x = 0; x < w; x++) {
				for (int y = 0; y < h; y++) {
					for (int z = 0; z < d; z++) {
						double expected = v.valueAt(x, y);
						double actual = out.valueAt(x, y, z);
						assertEquals(expected, actual);
					}
				}
			}
		});
	}

	@Test
	public void repeatSum() {
		int size = 30;
		int nodes = 10;

		Tensor<Double> t = tensor(shape(size));
		PackedCollection input = t.pack();

		PackedCollection weights = new PackedCollection(shape(size, nodes));
		weights.fill(pos -> Math.random());

		Supplier<Producer<PackedCollection>> dense =
				() -> cp(input).repeat(nodes).each().traverse(1).sum();

		Consumer<PackedCollection> valid = output -> {
			for (int i = 0; i < nodes; i++) {
				double expected = 0;

				for (int x = 0; x < size; x++) {
					expected += input.valueAt(x);
				}

				double actual = output.valueAt(i);
				Assert.assertEquals(expected, actual, 0.0001);
			}
		};

		kernelTest(dense, valid);
	}

	@Test
	public void repeatEnumerateMultiply() {
		int size = 30;
		int nodes = 10;

		Tensor<Double> t = tensor(shape(size));
		PackedCollection input = t.pack();

		PackedCollection weights = new PackedCollection(shape(size, nodes));
		weights.fill(pos -> Math.random());

		Supplier<Producer<PackedCollection>> dense =
				() -> c(p(input)).repeat(nodes).traverseEach()
						.multiply(c(p(weights))
								.enumerate(1, 1))
						.traverse(1).sum();

		Consumer<PackedCollection> valid = output -> {
			for (int i = 0; i < nodes; i++) {
				double expected = 0;

				for (int x = 0; x < size; x++) {
					expected += weights.valueAt(x, i) * input.valueAt(x);
				}

				double actual = output.valueAt(i);

				Assert.assertEquals(expected, actual, 0.0001);
			}
		};

		kernelTest(dense, valid);
	}

	@Test
	public void repeatEnumerateMultiplyAdd() {
		int size = 30;
		int nodes = 10;

		Tensor<Double> t = tensor(shape(size));
		PackedCollection input = t.pack();

		PackedCollection weights = new PackedCollection(shape(size, nodes));
		weights.fill(pos -> Math.random());

		PackedCollection biases = new PackedCollection(shape(nodes));
		biases.fill(pos -> Math.random());

		Supplier<Producer<PackedCollection>> dense =
				() -> c(p(input)).repeat(nodes).traverseEach()
						.multiply(c(p(weights))
								.enumerate(1, 1))
						.traverse(1).sum()
						.add(p(biases));

		Consumer<PackedCollection> valid = output -> {
			for (int i = 0; i < nodes; i++) {
				double expected = 0;

				for (int x = 0; x < size; x++) {
					expected += weights.valueAt(x, i) * input.valueAt(x);
				}

				double actual = output.valueAt(i);
				Assert.assertNotEquals(expected, actual, 0.0001);

				expected += biases.valueAt(i);
				Assert.assertEquals(expected, actual, 0.0001);
			}
		};

		kernelTest(dense, valid);
	}

	@Test
	public void maxRepeat() {
		PackedCollection in = new PackedCollection(8, 4).randFill();
		in.traverse(1).print();
		System.out.println("--");

		PackedCollection o = cp(in).traverse(1).max().repeat(3).get().evaluate();
		o.traverse(1).print();

		for (int h = 0; h < 8; h++) {
			double max = in.valueAt(h, 0);
			for (int i = 1; i < 4; i++) {
				if (in.valueAt(h, i) > max) {
					max = in.valueAt(h, i);
				}
			}

			for (int i = 0; i < 3; i++) {
				double actual = o.valueAt(h, i, 0);
				assertEquals(max, actual);
			}
		}
	}

	@Test
	public void upsample() {
		PackedCollection input = pack(1.0, 2.0, 3.0, 4.0).reshape(1, 1, 2, 2);

		PackedCollection out = cp(input)
				.repeat(4, 2)
				.repeat(3, 2)
				.evaluate()
				.reshape(1, 1, 4, 4);
		out.traverse(3).print();

		for (int i = 0; i < 4; i++) {
			for (int j = 0; j < 4; j++) {
				double expected = input.valueAt(0, 0, i / 2, j / 2);
				double actual = out.valueAt(0, 0, i, j);
				assertEquals(expected, actual);
			}
		}
	}
}
