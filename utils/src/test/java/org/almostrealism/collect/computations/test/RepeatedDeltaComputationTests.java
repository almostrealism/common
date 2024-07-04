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

package org.almostrealism.collect.computations.test;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.expression.Expression;
import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Process;
import org.almostrealism.algebra.Tensor;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.AggregatedProducerComputation;
import org.almostrealism.collect.computations.IndexProjectionProducerComputation;
import org.almostrealism.collect.computations.PackedCollectionEnumerate;
import org.almostrealism.collect.computations.TraversableExpressionComputation;
import org.almostrealism.hardware.jni.NativeCompiler;
import org.almostrealism.hardware.metal.MetalProgram;
import org.almostrealism.util.TestFeatures;
import org.almostrealism.util.TestSettings;
import org.junit.Test;

public class RepeatedDeltaComputationTests implements TestFeatures {
//	static {
//		NativeCompiler.enableInstructionSetMonitoring = !TestSettings.skipLongTests;
//		MetalProgram.enableProgramMonitoring = !TestSettings.skipLongTests;
//	}

	@Test
	public void repeatProduct() {
		PackedCollection<?> in = pack(2.0, 1.5);
		PackedCollection<?> multiplier = pack(4.0, 3.0);

		CollectionProducer<PackedCollection<?>> c =
					cp(in).traverse(1).repeat(2)
							.multiply(cp(multiplier).repeat(2))
							.delta(cp(in));
		c.get().evaluate().print();
	}

	@Test
	public void sum() {
		PackedCollection<?> in = pack(2.0, 1.0, 4.0, 3.0).reshape(2, 2).traverse(1);
		PackedCollection<?> out = cp(in).sum().delta(cp(in)).evaluate();
		out.print();

		assertEquals(1.0, out.valueAt(0, 0, 0, 0));
		assertEquals(1.0, out.valueAt(0, 0, 0, 1));
		assertEquals(0.0, out.valueAt(0, 0, 1, 0));
		assertEquals(0.0, out.valueAt(0, 0, 1, 1));
		assertEquals(0.0, out.valueAt(1, 0, 0, 0));
		assertEquals(0.0, out.valueAt(1, 0, 0, 1));
		assertEquals(1.0, out.valueAt(1, 0, 1, 0));
		assertEquals(1.0, out.valueAt(1, 0, 1, 1));
	}

	@Test
	public void productSum() {
		PackedCollection<?> multiplier = pack(4.0, 3.0, 2.0, 1.0).reshape(2, 2).traverse(1);
		PackedCollection<?> in = pack(2.0, 1.0, 4.0, 3.0).reshape(2, 2).traverse(1);
		PackedCollection<?> out = cp(in).multiply(cp(multiplier)).sum().delta(cp(in)).evaluate();
		out.print();

		assertEquals(4.0, out.valueAt(0, 0, 0, 0));
		assertEquals(3.0, out.valueAt(0, 0, 0, 1));
		assertEquals(0.0, out.valueAt(0, 0, 1, 0));
		assertEquals(0.0, out.valueAt(0, 0, 1, 1));
		assertEquals(0.0, out.valueAt(1, 0, 0, 0));
		assertEquals(0.0, out.valueAt(1, 0, 0, 1));
		assertEquals(2.0, out.valueAt(1, 0, 1, 0));
		assertEquals(1.0, out.valueAt(1, 0, 1, 1));
	}

	@Test
	public void productSumIndex() {
		PackedCollection<?> multiplier = pack(4.0, 3.0, 2.0, 1.0).reshape(2, 2).traverse(1);
		PackedCollection<?> in = pack(2.0, 1.0, 4.0, 3.0).reshape(2, 2).traverse(1);

		CollectionProducer<PackedCollection<?>> c = cp(in).multiply(cp(multiplier)).sum().delta(cp(in))
				.reshape(2, 4);
		c = new IndexProjectionProducerComputation<>(c.getShape().traverseEach(), index -> index, c) {
			@Override
			public int getMemLength() { return 1; }
		};

		PackedCollection<?> out = c.evaluate();
		out.print();

		assertEquals(4.0, out.valueAt(0, 0));
		assertEquals(3.0, out.valueAt(0, 1));
		assertEquals(0.0, out.valueAt(0, 2));
		assertEquals(0.0, out.valueAt(0, 3));
		assertEquals(0.0, out.valueAt(1, 0));
		assertEquals(0.0, out.valueAt(1, 1));
		assertEquals(2.0, out.valueAt(1, 2));
		assertEquals(1.0, out.valueAt(1, 3));
	}

	@Test
	public void productSumIndex2() {
		PackedCollection<?> multiplier = pack(4.0, 3.0, 2.0, 1.0).reshape(2, 2)
			.traverse(1);
		PackedCollection<?> in = pack(2.0, 1.0, 4.0, 3.0).reshape(2, 2)
			.traverse(1);

		CollectionProducer<PackedCollection<?>> c = cp(in).multiply(cp(multiplier)).sum().delta(cp(in))
				.reshape(2, 4);
		c = new PackedCollectionEnumerate<>(shape(2, 1).traverse(), new TraversalPolicy(true, 0, 1).traverse(), c) {
			@Override
			public Expression getValueAt(Expression index) {
				return getTraversableArguments(index)[1].getValueAt(index.imod(2).multiply(4).add(index.divide(2)));
			}
		};

		PackedCollection<?> out = c.evaluate();
		out.traverse(1).print();

		assertEquals(4.0, out.valueAt(0, 0));
		assertEquals(3.0, out.valueAt(1, 0));
		assertEquals(0.0, out.valueAt(2, 0));
		assertEquals(0.0, out.valueAt(3, 0));
		assertEquals(0.0, out.valueAt(0, 1));
		assertEquals(0.0, out.valueAt(1, 1));
		assertEquals(2.0, out.valueAt(2, 1));
		assertEquals(1.0, out.valueAt(3, 1));
	}

	@Test
	public void productRepeatSum1() {
		PackedCollection<?> multiplier = pack(4.0, 3.0, 2.0, 1.0).reshape(2, 2);
		PackedCollection<?> in = pack(2.0, 1.0, 4.0, 3.0).reshape(2, 2);

		CollectionProducer<PackedCollection<?>> c = cp(in)
				.multiply(cp(multiplier)).delta(cp(in))
				.reshape(4, 4)
				.traverse()
				.repeat(3)
				.sum(2);

		PackedCollection<?> out = c.evaluate().reshape(4, 3);
		out.traverse(1).print();

		for (int i = 0; i < 4; i++) {
			for (int j = 0; j < 3; j++) {
				assertEquals(multiplier.toDouble(i), out.valueAt(i, j));
			}
		}
	}

	@Test
	public void productEnumerate() {
		PackedCollection<?> multiplier = pack(4.0, 3.0, 2.0, 1.0).reshape(2, 2);
		PackedCollection<?> in = pack(2.0, 1.0, 4.0, 3.0).reshape(2, 2);

		CollectionProducer<PackedCollection<?>> c = cp(in)
				.multiply(cp(multiplier)).delta(cp(in))
				.reshape(4, 4)
				.enumerate(1, 1);

		PackedCollection<?> out = c.evaluate().reshape(4, 4);
		out.traverse(1).print();

		for (int i = 0; i < 4; i++) {
			for (int j = 0; j < 4; j++) {
				assertEquals(i == j ? multiplier.toDouble(i) : 0.0, out.valueAt(i, j));
			}
		}
	}

	@Test
	public void productEnumerateLarge() {
		PackedCollection<?> multiplier = new PackedCollection<>(10).fill(pos -> pos[0] + 1.0);
		PackedCollection<?> in = new PackedCollection<>(10);

		CollectionProducer<PackedCollection<?>> id = cp(new PackedCollection<>(10, 10));
		id = new PackedCollectionEnumerate<>(shape(10, 1).traverse(), new TraversalPolicy(true, 0, 1).traverse(), id) {
			@Override
			public Expression getValueAt(Expression index) {
				// return super.getValueAt(index);
				return projectIndex(index);
			}
		};

		id.evaluate().reshape(10, 10).traverse(1).print();

		CollectionProducer<PackedCollection<?>> c = cp(in)
				.multiply(cp(multiplier)).delta(cp(in))
				.reshape(10, 10)
				.enumerate(1, 1);

		PackedCollection<?> out = c.evaluate().reshape(10, 10);
		out.traverse(1).print();

		for (int i = 0; i < 10; i++) {
			for (int j = 0; j < 10; j++) {
				assertEquals(i == j ? multiplier.toDouble(i) : 0.0, out.valueAt(i, j));
			}
		}
	}

	@Test
	public void productSumEnumerate() {
		productSumEnumerate(false);
	}

	@Test
	public void productSumEnumerateOptimized() {
		boolean chainRule = AggregatedProducerComputation.enableTransitiveDelta;

		try {
			AggregatedProducerComputation.enableTransitiveDelta = false;
			productSumEnumerate(true);
		} finally {
			AggregatedProducerComputation.enableTransitiveDelta = chainRule;
		}
	}

	public void productSumEnumerate(boolean optimize) {
		PackedCollection<?> multiplier = pack(4.0, 3.0, 2.0, 1.0).reshape(2, 2).traverse(1);
		PackedCollection<?> in = pack(1.0, 1.0, 1.0, 1.0).reshape(2, 2).traverse(1);

		CollectionProducer<PackedCollection<?>> c = cp(in).multiply(cp(multiplier)).sum().delta(cp(in)).reshape(2, 4).enumerate(1, 1);
		PackedCollection<?> out = optimize ? Process.optimized(c).get().evaluate() : c.evaluate();
		out.traverse(1).print();

		assertEquals(4.0, out.valueAt(0, 0));
		assertEquals(3.0, out.valueAt(1, 0));
		assertEquals(0.0, out.valueAt(2, 0));
		assertEquals(0.0, out.valueAt(3, 0));
		assertEquals(0.0, out.valueAt(0, 1));
		assertEquals(0.0, out.valueAt(1, 1));
		assertEquals(2.0, out.valueAt(2, 1));
		assertEquals(1.0, out.valueAt(3, 1));
	}

	@Test
	public void max() {
		PackedCollection<?> in = pack(1.0, 2.0, 4.0, 3.0).reshape(2, 2).traverse(1);
		PackedCollection<?> out = cp(in).max().delta(cp(in)).evaluate();
		out.print();

		assertEquals(0.0, out.valueAt(0, 0, 0, 0));
		assertEquals(1.0, out.valueAt(0, 0, 0, 1));
		assertEquals(0.0, out.valueAt(0, 0, 1, 0));
		assertEquals(0.0, out.valueAt(0, 0, 1, 1));
		assertEquals(0.0, out.valueAt(1, 0, 0, 0));
		assertEquals(0.0, out.valueAt(1, 0, 0, 1));
		assertEquals(1.0, out.valueAt(1, 0, 1, 0));
		assertEquals(0.0, out.valueAt(1, 0, 1, 1));
	}

	@Test
	public void pool2d() {
		int r = 4;
		int c = 4;
		int s = 2;

		int ro = r / s;
		int co = c / s;

		PackedCollection<?> in = new PackedCollection<>(r * c).fill(pos -> (double) pos[0])
									.reshape(r, c, 1).traverse(1);
		PackedCollection<?> out = Process.optimized(cp(in)
				.enumerate(2, 1)
				.enumerate(2, s)
				.enumerate(2, s)
				.traverse(3)
				.max().delta(cp(in))).get().evaluate();
		out = out.reshape(ro, co, r, c);
		out.traverse(3).print();

		for (int i = 0; i < ro; i++) {
			for (int j = 0; j < co; j++) {
				double max = Double.MIN_VALUE;
				int x = -1, y = -1;

				log("[" + i + ", " + j + "] in ->");
				in.range(shape(s, s), in.getShape().index(i * s, j * s)).traverse().print();
				log("[" + i + ", " + j + "] out ->");
				out.range(shape(r, c), out.getShape().index(i, j, 0, 0)).traverse().print();

				for (int k = 0; k < s; k++) {
					for (int l = 0; l < s; l++) {
						if (in.valueAt(i * s + k, j * s + l) > max) {
							max = in.valueAt(i * s + k, j * s + l);
							x = i * s + k;
							y = j * s + l;
						}
					}
				}

				for (int ix = 0; ix < r; ix++) {
					for (int jy = 0; jy < c; jy++) {
						assertEquals(x == ix && y == jy ? 1.0 : 0.0,
								out.valueAt(i, j, ix, jy));
					}
				}
			}
		}
	}

	@Test
	public void convSmallest() {
		int dim = 10;
		int size = 3;
		int filters = 8;

		convolution2d(shape(dim, dim), size, filters);
	}

	@Test
	public void convSmall() {
		if (skipLongTests) return;

		int dim = 16;
		int size = 3;
		int filters = 8;

		convolution2d(shape(dim, dim), size, filters);
	}

	@Test
	public void convLarge() {
		if (skipLongTests || skipKnownIssues) return;

		int dim = 64;
		int size = 3;
		int filters = 8;

		convolution2d(shape(dim, dim), size, filters);
	}

	public void convolution2d(TraversalPolicy inputShape, int size, int filterCount) {
		try {
			initKernelMetrics();

			int pad = size - 1;
			TraversalPolicy outputShape = shape(inputShape.length(0) - pad, inputShape.length(1) - pad, filterCount);
			TraversalPolicy filterShape = shape(filterCount, size, size);
			PackedCollection<?> filters = new PackedCollection<>(filterShape).randnFill();

			PackedCollection<?> input = new PackedCollection<>(inputShape).randnFill();
			Process.optimized(cp(input).enumerate(1, size, 1)
					.enumerate(1, size, 1)
					.traverse(2)
					.repeat(filterCount)
					.traverse(2)
					.multiply(cp(filters)
							.repeat(outputShape.length(1)).traverse(0)
							.repeat(outputShape.length(0)).traverse(2))
					.traverse()
					.sum()
					.delta(cp(input)))
					.get().evaluate();
		} finally {
			logKernelMetrics();
		}
	}
}
