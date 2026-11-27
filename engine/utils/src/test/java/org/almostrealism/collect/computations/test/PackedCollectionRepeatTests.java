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
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Tests for PackedCollection repeat operations that replicate collections across dimensions.
 */
public class PackedCollectionRepeatTests extends TestSuiteBase {
	/**
	 * Tests that an isolated repeat operation materialises a dense output whose shape
	 * no longer carries the zero-copy {@link RepeatTraversalOrdering}.
	 */
	@Test(timeout = 30000)
	public void isolatedRepeat() {

		int d = 4;
		int w = 2;
		int h = 3;

		PackedCollection v = new PackedCollection(shape(w, h));
		v.randFill();

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

		// Evaluating a computation materialises a dense output, so the zero-copy repeat
		// ordering is stripped rather than carried through to the evaluated collection.
		Assert.assertFalse(out.getShape().getOrder() instanceof RepeatTraversalOrdering);
	}

	/**
	 * Tests repeat with item traversal.
	 */
	@Test(timeout = 30000)
	public void repeatItem() {
		int w = 2;
		int h = 3;
		int d = 4;

		PackedCollection v = new PackedCollection(shape(w, h));
		v.randFill();

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

	/**
	 * Tests 3D repeat operation.
	 */
	@Test(timeout = 30000)
	public void repeat3d() {
		int w = 1;
		int h = 2;
		int d = 4;

		PackedCollection v = new PackedCollection(shape(w, h));
		v.randFill();

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

	/**
	 * Tests repeat followed by sum.
	 */
	@Test(timeout = 30000)
	public void repeatSum() {
		int size = 30;
		int nodes = 10;

		Tensor<Double> t = tensor(shape(size));
		PackedCollection input = t.pack();

		PackedCollection weights = new PackedCollection(shape(size, nodes));
		weights.randFill();

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

	/**
	 * Tests repeat with enumerate and multiply.
	 */
	@Test(timeout = 30000)
	public void repeatEnumerateMultiply() {
		int size = 30;
		int nodes = 10;

		Tensor<Double> t = tensor(shape(size));
		PackedCollection input = t.pack();

		PackedCollection weights = new PackedCollection(shape(size, nodes));
		weights.randFill();

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

	/**
	 * Tests repeat with enumerate, multiply, and add.
	 */
	@Test(timeout = 30000)
	public void repeatEnumerateMultiplyAdd() {
		int size = 30;
		int nodes = 10;

		Tensor<Double> t = tensor(shape(size));
		PackedCollection input = t.pack();

		PackedCollection weights = new PackedCollection(shape(size, nodes));
		weights.randFill();

		PackedCollection biases = new PackedCollection(shape(nodes));
		biases.randFill();

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

	/**
	 * Tests max operation with repeat.
	 */
	@Test(timeout = 30000)
	public void maxRepeat() {
		PackedCollection in = new PackedCollection(8, 4).randFill();
		in.traverse(1).print();
		log("--");

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

	/**
	 * Tests upsampling with repeat.
	 */
	@Test(timeout = 30000)
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

	/**
	 * A repeat wrapped around a kernel-backed (non-{@code Provider}) producer must evaluate to
	 * the same values as repeating the underlying data directly, exercising the
	 * {@link org.almostrealism.hardware.computations.HardwareEvaluable#setResultProcessor
	 * result processor} path that {@link org.almostrealism.collect.computations.PackedCollectionRepeat#get()}
	 * installs for such an input instead of the {@code Provider} fast path.
	 */
	@Test(timeout = 30000)
	public void repeatOfComputedProducer() {
		int d = 4;
		int w = 2;
		int h = 3;

		PackedCollection v = new PackedCollection(shape(w, h));
		v.randFill();

		PackedCollection out = cp(v).multiply(1.0).repeat(d).get().evaluate();

		for (int x = 0; x < d; x++) {
			for (int y = 0; y < w; y++) {
				for (int z = 0; z < h; z++) {
					double expected = v.valueAt(y, z);
					double actual = out.valueAt(x, y, z);
					assertEquals(expected, actual);
				}
			}
		}
	}

	/** Length of the short vector repeated past the single-precision integer range. */
	private static final int SHORT_LENGTH = 13;

	/** Repetitions that take {@link #SHORT_LENGTH} elements past 2^24 in total. */
	private static final int MANY_REPETITIONS = (1 << 24) / SHORT_LENGTH + 4096;

	/**
	 * A short vector repeated into more than 2^24 elements reads back as {@code i % length}
	 * at every flat index, past the point where a single-precision value can no longer hold
	 * every integer. The vector is repeated whole whether it is a materialized collection, a
	 * computed producer, or {@code integers(0, n)} traversed as one item with
	 * {@code traverse(0)}.
	 */
	@Test(timeout = 300000)
	public void repeatOfAVectorBeyondSinglePrecisionRange() {
		int total = SHORT_LENGTH * MANY_REPETITIONS;
		Assert.assertTrue(total > (1 << 24));

		try (PackedCollection vector = integers(0, SHORT_LENGTH).evaluate()) {
			assertRepeatsWhole(cp(vector).repeat(MANY_REPETITIONS).reshape(shape(total, 1)));
			assertRepeatsWhole(cp(vector).multiply(1.0).repeat(MANY_REPETITIONS)
					.reshape(shape(total, 1)));
			assertRepeatsWhole(integers(0, SHORT_LENGTH).traverse(0).repeat(MANY_REPETITIONS)
					.reshape(shape(total, 1)));
		}
	}

	/**
	 * {@code integers(0, n)} traverses each of its elements, so {@code repeat(q)} repeats each
	 * element {@code q} times in a {@code (n, q)} result whose flat index {@code i} holds
	 * {@code i / q}, not the whole vector. This holds exactly past 2^24 elements as well.
	 */
	@Test(timeout = 300000)
	public void repeatOfAnElementTraversalRepeatsEachElement() {
		int total = SHORT_LENGTH * MANY_REPETITIONS;

		try (PackedCollection out = integers(0, SHORT_LENGTH).repeat(MANY_REPETITIONS).evaluate()) {
			Assert.assertEquals(total, out.getShape().getTotalSize());
			Assert.assertEquals(SHORT_LENGTH, out.getShape().length(0));

			double[] values = out.toArray();
			for (int i = 0; i < total; i++) {
				if (values[i] != i / MANY_REPETITIONS) {
					Assert.fail("Index " + i + " read " + values[i] + ", expected " + (i / MANY_REPETITIONS));
				}
			}
		}
	}

	/**
	 * Asserts that every flat index {@code i} of a repeat of the {@link #SHORT_LENGTH}-element
	 * vector {@code 0, 1, ...} holds {@code i % SHORT_LENGTH}.
	 *
	 * @param repeat the repeat producer to evaluate and release
	 */
	private static void assertRepeatsWhole(CollectionProducer repeat) {
		try (PackedCollection repeated = repeat.evaluate()) {
			double[] values = repeated.toArray();
			Assert.assertEquals((long) SHORT_LENGTH * MANY_REPETITIONS, values.length);
			for (int i = 0; i < values.length; i++) {
				if (values[i] != i % SHORT_LENGTH) {
					Assert.fail("Index " + i + " read " + values[i] + ", expected " + (i % SHORT_LENGTH));
				}
			}
		}
	}
}
