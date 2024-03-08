/*
 * Copyright 2024 Michael Murray
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

package org.almostrealism.algebra.computations.test;

import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.ParallelProcess;
import io.almostrealism.relation.Process;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.hardware.jni.NativeCompiler;
import org.almostrealism.hardware.metal.MetalProgram;
import org.almostrealism.util.TestFeatures;
import org.almostrealism.util.TestSettings;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.function.Supplier;
import java.util.stream.IntStream;

public class MatrixDeltaComputationTests implements TestFeatures {
	static {
		NativeCompiler.enableInstructionSetMonitoring = !TestSettings.skipLongTests;
		MetalProgram.enableProgramMonitoring = !TestSettings.skipLongTests;
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

		// dy0/dw = x0, x1, 0,  0
		// dy1/dw = 0,  0,  x0, x1
		Evaluable<PackedCollection<?>> dy = c.delta(p(w)).get();
		PackedCollection<?> dout = dy.evaluate();
		dout.print();
		Assert.assertEquals(dout.getMemLength(), out.getMemLength() * w.getMemLength());
		assertEquals(0.0, dout.toDouble(5));
		assertEquals(3.0, dout.toDouble(7));
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
		int count = 1;
		int dim = 2;

		PackedCollection<?> v = pack(IntStream.range(2, 2 + count * dim).boxed()
				.mapToDouble(Double::valueOf).toArray())
				.reshape(count, dim).traverse();
		PackedCollection<?> w = pack(4.0, -3.0, 2.0, 1.5)
				.reshape(shape(dim, dim));

		HardwareOperator.verboseLog(() -> {
			CollectionProducer<PackedCollection<?>> c = matmul(p(w), x(dim));

			// y = f(x)
			Evaluable<PackedCollection<?>> y = c.get();
			PackedCollection<?> out = y.evaluate(v.traverse());
			System.out.println(Arrays.toString(out.toArray(0, count * dim)));
			assertEquals(8.5, out.toDouble(1));

			// dy/dw = x0, x1, 0, 0, 0, 0, x0, x1
			Evaluable<PackedCollection<?>> dy = c.delta(p(w)).get();
			PackedCollection<?> dout = dy.evaluate(v.traverse());
			System.out.println(Arrays.toString(dout.toArray(0, dout.getMemLength())));
			Assert.assertEquals(dout.getMemLength(), out.getMemLength() * w.getMemLength());
			assertEquals(0.0, dout.toDouble(5));
			assertEquals(3.0, dout.toDouble(7));
		});
	}

	@Test
	public void matmul4() {
		int count = 1;
		int dim = 2;

		PackedCollection<?> v = pack(IntStream.range(2, 2 + count * dim).boxed()
				.mapToDouble(Double::valueOf).toArray())
				.reshape(count, dim).traverse();
		PackedCollection<?> w = pack(4.0, -3.0, 2.0, 1.5)
				.reshape(shape(dim, dim));

		HardwareOperator.verboseLog(() -> {
			CollectionProducer<PackedCollection<?>> c = matmul(p(w), x(dim));

			// dy/dw = x0, x1, 0, 0, 0, 0, x0, x1
			Evaluable<? extends PackedCollection<?>> dy = Process.optimized(c.delta(p(w))).get();
			PackedCollection<?> dout = dy.evaluate(v.traverse());
			System.out.println(Arrays.toString(dout.toArray(0, dout.getMemLength())));
			Assert.assertEquals(dim * dim * dim, dout.getMemLength());
			assertEquals(0.0, dout.toDouble(5));
			assertEquals(3.0, dout.toDouble(7));
		});
	}

	@Test
	public void matmul5() {
		int count = 1;
		int dim = 3;

		PackedCollection<?> v = integers(2, 2 + count * dim)
				.get().evaluate();
		PackedCollection<?> w = pack(
				1000.0, 1000.0, 1000.0,
				1000.0, 1000.0, 1000.0,
				1000.0, 1000.0, 1000.0)
				.reshape(shape(dim, dim));
		CollectionProducer<PackedCollection<?>> c = matmul((Producer) cp(w), cp(v).all());

//		PackedCollection<?> out = Process.optimized(c.delta(cp(w))).get().evaluate();
		PackedCollection<?> out = c.delta(cp(w)).get().evaluate();
		out.print();
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
		dout.print();
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

		HardwareOperator.verboseLog(() -> {
			c.delta(p(w))
					.reshape(outSize, weightSize)
					.traverse(1)
					.multiply(c(g).reshape(outSize).traverse(1).expand(weightSize))
					.enumerate(1, 1)
					.get().into(sparse.each()).evaluate();
		});
		print(outSize, weightSize, sparse);

		Supplier<Runnable> cda;

		if (optimize) {
			cda = a(each(weightFlat), subtract(each(weightFlat), multiply(c(2.0), cdy))).optimize();
		} else {
			cda = a(each(weightFlat), subtract(each(weightFlat), multiply(c(2.0), cdy)));
		}

		HardwareOperator.verboseLog(() -> {
			cda.get().run();
		});

		System.out.println(w.toArrayString());
		assertEquals(999.8, w.toDouble(0));
		assertEquals(999.7, w.toDouble(1));
		assertEquals(999.6, w.toDouble(2));
		assertEquals(999.998, w.toDouble(6));
		assertEquals(999.997, w.toDouble(7));
		assertEquals(999.996, w.toDouble(8));
	}

	@Test
	public void denseWeightsSmall() {
		try {
			ParallelProcess.explicitIsolationTargets.add(operationFilter("f_traversableExpressionComputation_16"));
			ParallelProcess.explicitIsolationTargets.add(operationFilter("f_traversableDeltaComputation_17"));
			ParallelProcess.explicitIsolationTargets.add(operationFilter("f_aggregatedCollectionProducerComputation_22"));

			denseWeights(4, 3);
		} finally {
			ParallelProcess.explicitIsolationTargets.clear();
		}
	}

	@Test
	public void denseWeightsLarge() {
		if (skipLongTests) return;
		denseWeights(1800, 10);
	}

	public void denseWeights(int size, int nodes) {
		PackedCollection<?> v = new PackedCollection<>(shape(size)).fill(Math::random);
		PackedCollection<?> g = new PackedCollection<>(shape(nodes)).fill(Math::random);
		PackedCollection<?> w = new PackedCollection<>(shape(nodes, size)).fill(Math::random);
		CollectionProducer<PackedCollection<?>> c = matmul((Producer) cp(w), cp(v).all());

		int weightSize = size * nodes;
		Producer<PackedCollection<?>> weightFlat = reshape(shape(weightSize), p(w));

		Producer<PackedCollection<?>> cdy = c.delta(p(w))
				.reshape(nodes, weightSize)
				.traverse(1)
				.multiply(c(g).reshape(nodes).traverse(1).repeat(weightSize))
				.enumerate(1, 1)
				.sum(1)
				.reshape(shape(weightSize))
				.each();

		try {
			initKernelMetrics();
			Supplier<Runnable> cda = a(each(weightFlat), subtract(each(weightFlat), multiply(c(2.0), cdy))).optimize();
			cda.get().run();
		} finally {
			logKernelMetrics();
		}
	}
}
