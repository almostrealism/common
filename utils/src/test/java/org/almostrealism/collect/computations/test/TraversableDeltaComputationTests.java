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
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.AcceleratedOperation;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.util.TestFeatures;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.stream.IntStream;

public class TraversableDeltaComputationTests implements TestFeatures {
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
		boolean enableArgumentKernelSize = AcceleratedOperation.enableArgumentKernelSize;

		try {
			AcceleratedOperation.enableArgumentKernelSize = false;

			int count = 1;
			int dim = 2;

			PackedCollection<?> v = pack(IntStream.range(2, 2 + count * dim).boxed()
					.mapToDouble(Double::valueOf).toArray())
					.reshape(count, dim);
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
			AcceleratedOperation.enableArgumentKernelSize = enableArgumentKernelSize;
		}
	}
}
