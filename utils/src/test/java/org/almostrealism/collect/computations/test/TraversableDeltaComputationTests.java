/*
 * Copyright 2023 Michael Murray
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

import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.AcceleratedOperation;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.hardware.ProcessDetailsFactory;
import org.almostrealism.util.TestFeatures;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.function.Supplier;
import java.util.stream.IntStream;

public class TraversableDeltaComputationTests implements TestFeatures {
	@Test
	public void polynomial0() {
		// x + 1
		CollectionProducer<PackedCollection<?>> c = x().add(1);

		// dy = f'(x)
		Evaluable<PackedCollection<?>> dy = c.delta(x()).get();
		PackedCollection<?> out = dy.evaluate(pack(1, 2, 3, 4, 5).traverseEach());
	}

	@Test
	public void polynomial1() {
		// x^2 + 3x + 1
		CollectionProducer<PackedCollection<?>> c = x().sq().add(x().mul(3)).add(1);

		// y = f(x)
		Evaluable<PackedCollection<?>> y = c.get();
		PackedCollection<?> out = y.evaluate(pack(1, 2, 3, 4, 5).traverseEach());
		System.out.println(Arrays.toString(out.toArray(0, 5)));

		// dy = f'(x)
		Evaluable<PackedCollection<?>> dy = c.delta(x()).get();
		out = dy.evaluate(pack(1, 2, 3, 4, 5).traverseEach());
		System.out.println(Arrays.toString(out.toArray(0, 5)));
	}

	@Test
	public void polynomial2() {
		int dim = 3;
		int count = 2;

		PackedCollection<?> v = pack(IntStream.range(0, count * dim).boxed()
				.mapToDouble(Double::valueOf).toArray())
				.reshape(count, dim).traverse();
		PackedCollection<?> w = pack(4, -3, 2);
		CollectionProducer<PackedCollection<?>> x = x(dim);

		// w * x
		CollectionProducer<PackedCollection<?>> c = x.mul(p(w));

		// y = f(x)
		Evaluable<PackedCollection<?>> y = c.get();
		PackedCollection<?> out = y.evaluate(v);
		System.out.println(Arrays.toString(out.toArray(0, count * dim)));

		// dy = f'(x)
		//    = w
		Evaluable<PackedCollection<?>> dy = c.delta(x).get();
		PackedCollection<?> dout = dy.evaluate(v);
		double d[] = dout.toArray(0, count * dim * dim);
		System.out.println(Arrays.toString(d));

		for (int i = 0; i < count; i++) {
			for (int j = 0 ; j < dim; j++) {
				for (int k = 0; k < dim; k++) {
					if (j == k) {
						assertEquals(w.toDouble(j), d[i * dim * dim + j * dim + k]);
					} else {
						assertEquals(0.0, d[i * dim * dim + j * dim + k]);
					}
				}
			}
		}
	}

	@Test
	public void polynomial3() {
		int dim = 3;
		int count = 2;

		PackedCollection<?> v = pack(IntStream.range(0, count * dim).boxed()
				.mapToDouble(Double::valueOf).toArray())
				.reshape(count, dim).traverse();
		PackedCollection<?> w = pack(4, -3, 2);
		CollectionProducer<PackedCollection<?>> x = x(dim);

		// w * x + 1
		CollectionProducer<PackedCollection<?>> c = x.mul(p(w)).add(c(1).repeat(3).consolidate());

		// y = f(x)
		Evaluable<PackedCollection<?>> y = c.get();
		PackedCollection<?> out = y.evaluate(v);
		double l[] = out.toArray(0, count * dim);
		System.out.println(Arrays.toString(l));
		assertEquals(1.0, l[0]);
		assertEquals(-2.0, l[1]);

		// dy = f'(x)
		//    = w
		Evaluable<PackedCollection<?>> dy = c.delta(x).get();
		PackedCollection<?> dout = dy.evaluate(v);
		double d[] = dout.toArray(0, count * dim * dim);
		System.out.println(Arrays.toString(d));

		for (int i = 0; i < count; i++) {
			for (int j = 0 ; j < dim; j++) {
				for (int k = 0; k < dim; k++) {
					if (j == k) {
						assertEquals(w.toDouble(j), d[i * dim * dim + j * dim + k]);
					} else {
						assertEquals(0.0, d[i * dim * dim + j * dim + k]);
					}
				}
			}
		}
	}

	@Test
	public void polynomial4() {
		int dim = 3;

		PackedCollection<?> v = pack(IntStream.range(0, 4 * dim).boxed()
										.mapToDouble(Double::valueOf).toArray())
										.reshape(4, dim).traverse();
		PackedCollection<?> w = pack(4, -3, 2);
		CollectionProducer<PackedCollection<?>> x = x(dim);

		// x^2 + w * x + 1
		CollectionProducer<PackedCollection<?>> c = x.sq().add(x.mul(p(w))).add(c(1).repeat(3).consolidate());

		// y = f(x)
		Evaluable<PackedCollection<?>> y = c.get();
		PackedCollection<?> out = y.evaluate(v);
		System.out.println(Arrays.toString(out.toArray(0, 4 * dim)));


		HardwareOperator.verboseLog(() -> {
			// dy = f'(x)
			//    = 2x + w
			Evaluable<PackedCollection<?>> dy = c.delta(x).get();
			PackedCollection<?> dout = dy.evaluate(v);
			System.out.println(Arrays.toString(dout.toArray(0, 4 * dim)));
		});
	}

	@Test
	public void matmul1() {
		int dim = 2;

		PackedCollection<?> v = pack(IntStream.range(2, 2 + dim).boxed()
										.mapToDouble(Double::valueOf).toArray())
										.reshape(dim);
		PackedCollection<?> w = pack(4.0, -3.0, 2.0, 1.5)
										.reshape(shape(dim, dim));

		// x0 * w0 + x1 * w1,  x0 * w2 + x1 * w3
		// x0 * 4 + x1 * -3,  x0 * 2 + x1 * 1.5
		// 2 * 4 + 3 * -3, 2 * 2 + 3 * 1.5
		CollectionProducer<PackedCollection<?>> c = matmul(p(w), p(v));
		System.out.println("c: " + shape(c).toStringDetail());
		System.out.println("v: " + shape(v).toStringDetail());

		// y = f(x)
		Evaluable<PackedCollection<?>> y = c.get();
		PackedCollection<?> out = y.evaluate();
		System.out.println(Arrays.toString(out.toArray(0, dim)));
		assertEquals(8.5, out.toDouble(1));

		HardwareOperator.verboseLog(() -> {
			// dy0/dw = x0, x1, 0,  0
			// dy1/dw = 0,  0,  x0, x1
			Evaluable<PackedCollection<?>> dy = c.delta(p(w)).get();
			PackedCollection<?> dout = dy.evaluate();
			System.out.println(Arrays.toString(dout.toArray(0, dout.getMemLength())));
			Assert.assertEquals(dout.getMemLength(), out.getMemLength() * w.getMemLength());
			assertEquals(0.0, dout.toDouble(5));
			assertEquals(3.0, dout.toDouble(7));
		});
	}

	@Test
	public void matmul2() {
		int dim = 10;

		PackedCollection<?> v = pack(IntStream.range(2, 2 + dim).boxed()
				.mapToDouble(Double::valueOf).toArray())
				.reshape(dim);
		PackedCollection<?> w = empty(shape(dim, dim))
				.fill(1, 2, 3, 4)
				.reshape(shape(dim, dim));

		// x0 * w0 + x1 * w1,  x0 * w2 + x1 * w3
		// x0 * 4 + x1 * -3,  x0 * 2 + x1 * 1.5
		// 2 * 4 + 3 * -3, 2 * 2 + 3 * 1.5
		CollectionProducer<PackedCollection<?>> c = matmul(p(w), p(v));
		System.out.println("c: " + shape(c).toStringDetail());
		System.out.println("v: " + shape(v).toStringDetail());

		// y = f(x)
		Evaluable<PackedCollection<?>> y = c.get();
		PackedCollection<?> out = y.evaluate();
		System.out.println(Arrays.toString(out.toArray(0, dim)));
		// assertEquals(8.5, out.toDouble(1));

		HardwareOperator.verboseLog(() -> {
			// dy0/dw = x0, x1, 0,  0
			// dy1/dw = 0,  0,  x0, x1
			Evaluable<PackedCollection<?>> dy = c.delta(p(w)).get();
			PackedCollection<?> dout = dy.evaluate();
			System.out.println(Arrays.toString(dout.toArray(0, dout.getMemLength())));
			Assert.assertEquals(dout.getMemLength(), out.getMemLength() * w.getMemLength());
			// assertEquals(0.0, dout.toDouble(5));
			// assertEquals(3.0, dout.toDouble(7));
		});
	}

	@Test
	public void matmul3() {
		boolean enableArgumentKernelSize = ProcessDetailsFactory.enableArgumentKernelSize;

		try {
			ProcessDetailsFactory.enableArgumentKernelSize = false;

			int count = 1;
			int dim = 2;

			PackedCollection<?> v = pack(IntStream.range(2, 2 + count * dim).boxed()
					.mapToDouble(Double::valueOf).toArray())
					.reshape(count, dim).traverse();
			PackedCollection<?> w = pack(4.0, -3.0, 2.0, 1.5)
					.reshape(shape(dim, dim));

			// x0 * w0 + x1 * w1,  x0 * w2 + x1 * w3
			// x0 * 4 + x1 * -3,  x0 * 2 + x1 * 1.5
			CollectionProducer<PackedCollection<?>> c = matmul(p(w), x(dim));

			// y = f(x)
			Evaluable<PackedCollection<?>> y = c.get();
			PackedCollection<?> out = y.evaluate(v);
			System.out.println(Arrays.toString(out.toArray(0, count * dim)));
			assertEquals(8.5, out.toDouble(1));

			HardwareOperator.verboseLog(() -> {
				// dy/dw = x0, x1, 0, 0, 0, 0, x0, x1
				Evaluable<PackedCollection<?>> dy = c.delta(p(w)).get();
				PackedCollection<?> dout = dy.evaluate(v);
				System.out.println(Arrays.toString(dout.toArray(0, dout.getMemLength())));
				Assert.assertEquals(dout.getMemLength(), out.getMemLength() * w.getMemLength());
				assertEquals(0.0, dout.toDouble(5));
				assertEquals(3.0, dout.toDouble(7));
			});
		} finally {
			ProcessDetailsFactory.enableArgumentKernelSize = enableArgumentKernelSize;
		}
	}

	@Test
	public void enumerate() {
		int count = 2;
		int dim = 3;

		PackedCollection<?> v = pack(2.0, 3.0, 4.0,
											2.0, 3.0, 4.0,
											2.0, 3.0, 4.0,
											5.0, 6.0, 7.0,
											5.0, 6.0, 7.0,
											5.0, 6.0, 7.0)
				.reshape(count, 1, dim, dim).traverse();

		CollectionProducer cdy = cp(v)
				.reshape(count, dim * dim)
											.enumerate(1, 1)
											.sum(1)
											.reshape(3, 3);
		Evaluable<PackedCollection<?>> dy = cdy.get();
		PackedCollection<?> dout = dy.evaluate();
		System.out.println(Arrays.toString(dout.toArray(0, dout.getMemLength())));
		assertEquals(7.0, dout.toDouble(0));
		assertEquals(9.0, dout.toDouble(1));
		assertEquals(11.0, dout.toDouble(2));
		assertEquals(7.0, dout.toDouble(3));
		assertEquals(9.0, dout.toDouble(4));
		assertEquals(11.0, dout.toDouble(5));
	}

	@Test
	public void matmulEnumerate() {
		int count = 2;
		int dim = 3;

		PackedCollection<?> v = pack(IntStream.range(2, 2 + count * dim).boxed()
				.mapToDouble(Double::valueOf).toArray())
				.reshape(count, dim).traverse();
		PackedCollection<?> w = pack(4.0, -3.0, 2.5, 2.0, 1.5, 1.0, 7.0, 4.0, -2.0)
				.reshape(shape(dim, dim));

		// x0 * w0 + x1 * w1,  x0 * w2 + x1 * w3
		// x0 * 4 + x1 * -3,  x0 * 2 + x1 * 1.5
		CollectionProducer<PackedCollection<?>> c = matmul(p(w), p(v));

		Producer<PackedCollection<?>> cdy = c.delta(p(w))
											.reshape(count, dim * dim)
											.enumerate(1, 1)
											.sum(1)
											.reshape(3, 3);
		Evaluable<PackedCollection<?>> dy = cdy.get();
		PackedCollection<?> dout = dy.evaluate();
		System.out.println(Arrays.toString(dout.toArray(0, dout.getMemLength())));
		assertEquals(7.0, dout.toDouble(0));
		assertEquals(9.0, dout.toDouble(1));
		assertEquals(11.0, dout.toDouble(2));
		assertEquals(7.0, dout.toDouble(3));
		assertEquals(9.0, dout.toDouble(4));
		assertEquals(11.0, dout.toDouble(5));
	}

	@Test
	public void matmulEnumerateProduct() {
		matmulEnumerateProduct(false);
	}

	@Test
	public void matmulEnumerateProductOptimized() {
		matmulEnumerateProduct(true);
	}

	public void matmulEnumerateProduct(boolean optimize) {
		int count = 1;
		int dim = 3;

		PackedCollection<?> v = integers(2, 2 + count * dim)
									.get().evaluate();
		PackedCollection<?> g = pack(
							0.05,
									0.005,
									0.0005);
		PackedCollection<?> w = pack(
							1000.0, 1000.0, 1000.0,
									1000.0, 1000.0, 1000.0,
									1000.0, 1000.0, 1000.0)
				.reshape(shape(dim, dim));
		CollectionProducer<PackedCollection<?>> c = matmul((Producer) cp(w), cp(v).all());

		int outSize = dim;
		int weightSize = dim * dim;
		Producer<PackedCollection<?>> weightFlat = reshape(shape(weightSize), p(w));

		Producer<PackedCollection<?>> cdy = c.delta(p(w))
				.reshape(outSize, weightSize)
				.traverse(1)
				.multiply(c(g).reshape(outSize).traverse(1).expand(weightSize))
				.enumerate(1, 1)
				.sum(1)
				.reshape(shape(weightSize))
				.each();

		PackedCollection<?> sparse = new PackedCollection<>(shape(outSize, weightSize));
		System.out.println("c: " + shape(c).toStringDetail());
		System.out.println("v: " + shape(v).toStringDetail());

		c.delta(p(w)).get().into(sparse.traverse()).evaluate();
		print(outSize, weightSize, sparse);

		c.delta(p(w))
				.reshape(outSize, weightSize)
				.traverse(1)
				.multiply(c(g).reshape(outSize).traverse(1).expand(weightSize))
				.enumerate(1, 1)
				.get().into(sparse.each()).evaluate();
		print(outSize, weightSize, sparse);

		Supplier<Runnable> cda;

		if (optimize) {
			cda = a(each(weightFlat), subtract(each(weightFlat), multiply(c(2.0), cdy))).optimize();
		} else {
			cda = a(each(weightFlat), subtract(each(weightFlat), multiply(c(2.0), cdy)));
		}

		cda.get().run();
		System.out.println(w.toArrayString());
		assertEquals(999.8, w.toDouble(0));
		assertEquals(999.7, w.toDouble(1));
		assertEquals(999.6, w.toDouble(2));
		assertEquals(999.998, w.toDouble(6));
		assertEquals(999.997, w.toDouble(7));
		assertEquals(999.996, w.toDouble(8));
	}

	@Test
	public void map() {
		PackedCollection<?> g = pack(3, 5);
		PackedCollection<?> w = pack(10, 100, 1000);
		CollectionProducer<PackedCollection<?>> c = cp(g).each()
				.map(shape(3), v -> v.repeat(3).mul(cp(w)));

		print(2 * 3, 1, c.get().evaluate());

		PackedCollection<?> result = new PackedCollection<>(shape(2, 3, 3));
		c.delta(p(w)).into(result.traverse(1)).evaluate();
		print(2 * 3, 3, result);

		for (int i = 0; i < 3; i++) {
			for (int j = 0; j < 3; j++) {
				assertEquals(i == j ? 3 : 0, result.valueAt(0, i, j));
				assertEquals(i == j ? 5 : 0, result.valueAt(1, i, j));
			}
		}
	}

	@Test
	public void conv2d() {
		int size = 3;
		int filterCount = 8;

		PackedCollection<?> input = integers(1, 101).evaluate().reshape(10, 10);
		PackedCollection<?> filters = pack(1, 2, 3, 4, 5, 6, 7, 8);

		CollectionProducer<PackedCollection<?>> c = cp(input)
						.enumerate(1, size, 1)
						.enumerate(1, size, 1)
						.traverse(2)
						.expand(filterCount, v -> v.repeat(filterCount).each().multiply(p(filters)))
						.traverse()
						.reduce(v -> v.sum());

		PackedCollection<?> result = c.delta(p(filters)).evaluate();
		print(50, 8, result);
		// TODO  assertions
	}

	@Test
	public void conv2dEnumerateProduct() {
		int h = 3; // 10;
		int w = 4; // 10;
		int size = 3;
		int filterCount = 2; // 8;

		PackedCollection<?> input = integers(1, (h * w) + 1).evaluate().reshape(h, w);
		PackedCollection<?> filters = integers(1, filterCount + 1).evaluate();

		CollectionProducer<PackedCollection<?>> c = cp(input)
				.enumerate(1, size, 1)
				.enumerate(1, size, 1)
				.traverse(2)
				.expand(filterCount, v -> v.repeat(filterCount).each().multiply(p(filters)))
				.traverse()
				.reduce(v -> v.sum());

		int outSize = shape(c).getTotalSize();
		PackedCollection<?> g = integers(1, outSize + 1).evaluate().reshape(shape(c));
		Producer<PackedCollection<?>> weightFlat = reshape(shape(filterCount), p(filters));

		Producer<PackedCollection<?>> cdy = c.delta(p(filters))
				.reshape(outSize, filterCount)
				.traverse(1)
				.multiply(c(g).reshape(outSize).traverse(1).expand(filterCount))
				.enumerate(1, 1)
				.sum(1)
				.reshape(shape(filterCount))
				.each();

		PackedCollection<?> sparse = new PackedCollection<>(shape(outSize, filterCount));

		c.delta(p(filters)).into(sparse.traverse()).evaluate();
		print(h, filterCount, sparse);

		c.delta(p(filters))
				.reshape(outSize, filterCount)
				.traverse(1)
				.multiply(c(g).reshape(outSize).traverse(1).expand(filterCount))
				.enumerate(1, 1)
				.into(sparse.each()).evaluate();
		print(h, filterCount, sparse);

		// HardwareOperator.verboseLog(() -> {
			Supplier<Runnable> cda = a(each(weightFlat), subtract(each(weightFlat), multiply(c(2.0), cdy)));
			cda.get().run();
		// });
	}
}
