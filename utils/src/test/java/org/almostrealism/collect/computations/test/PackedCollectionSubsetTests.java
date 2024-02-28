/*
 * Copyright 2023 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.collect.computations.test;

import io.almostrealism.expression.Expression;
import io.almostrealism.expression.KernelIndex;
import io.almostrealism.expression.Sum;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.ParallelProcess;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.ArrayVariable;
import org.almostrealism.algebra.Tensor;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CollectionProducerComputation;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.computations.ArrayVariableComputation;
import org.almostrealism.collect.computations.PackedCollectionRepeat;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.hardware.cl.CLOperator;
import org.almostrealism.hardware.metal.MetalOperator;
import org.almostrealism.util.TestFeatures;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public class PackedCollectionSubsetTests implements TestFeatures {

	@Test
	public void subset3d() {
		int w = 2;
		int h = 4;
		int d = 3;

		int x0 = 4, x1 = x0 + w;
		int y0 = 3, y1 = y0 + h;
		int z0 = 2, z1 = z0 + d;

		Tensor<Double> t = tensor(shape(10, 10, 10), (int[] c) -> {
			int x = c[0], y = c[1], z = c[2];
			return x >= x0 && x < x1 && y >= y0 && y < y1 && z >= z0 && z < z1;
		});

		PackedCollection<?> input = t.pack();
		TraversalPolicy inputShape = input.getShape();
		System.out.println("PackedCollectionSubsetTests: input shape = " + inputShape);

		TraversalPolicy subsetShape = shape(w, h, d);

		int outIndex = 1;
		int pos[] = subsetShape.position(outIndex);
		int index = inputShape.index(x0 + pos[0], y0 + pos[1], z0 + pos[2]);
		System.out.println("Position " + outIndex + " maps to " + index + " " + Arrays.toString(inputShape.position(index)));
		Assert.assertEquals(433, index);

		HardwareOperator.verboseLog(() -> {
			CollectionProducer<PackedCollection<?>> producer = subset(shape(w, h, d), p(input), x0, y0, z0);
			Evaluable<PackedCollection<?>> ev = producer.get();
			PackedCollection<?> subset = ev.evaluate();

			Assert.assertEquals(w, subset.getShape().length(0));
			Assert.assertEquals(h, subset.getShape().length(1));
			Assert.assertEquals(d, subset.getShape().length(2));

			for (int i = 0; i < w; i++) {
				for (int j = 0; j < h; j++) {
					for (int k = 0; k < d; k++) {
						double expected = (x0 + i + y0 + j + z0 + k);
						double actual = subset.toDouble(subsetShape.index(i, j, k));
						System.out.println("PackedCollectionSubsetTests: [" + i + ", " + j + ", " + k + "] " + expected + " vs " + actual);
						Assert.assertEquals(expected, actual, 0.0001);
					}
				}
			}
		});
	}

	@Test
	public void dynamicSubset1d() {
		int w = 2;

		int x0 = 4, x1 = x0 + w;

		Tensor<Double> t = tensor(shape(10), (int[] c) -> {
			int x = c[0];
			return x >= x0 && x < x1;
		});

		PackedCollection<?> input = t.pack();
		TraversalPolicy inputShape = input.getShape();
		System.out.println("PackedCollectionSubsetTests: input shape = " + inputShape);

		PackedCollection<?> pc = new PackedCollection<>(1).traverseEach();
		pc.set(0, (double) x0);

		HardwareOperator.verboseLog(() -> {
			CollectionProducer<PackedCollection<?>> producer = subset(shape(w), p(input), p(pc));
			Evaluable<PackedCollection<?>> ev = producer.get();
			PackedCollection<?> subset = ev.evaluate();

			Assert.assertEquals(w, subset.getShape().length(0));

			for (int i = 0; i < w; i++) {
				double expected = x0 + i;
				double actual = subset.valueAt(i);
				System.out.println("PackedCollectionSubsetTests: [" + i + "] " + expected + " vs " + actual);
				assertEquals(expected, actual);
			}
		});
	}

	@Test
	public void dynamicSubset2d() {
		int w = 2;
		int h = 4;

		int x0 = 4, x1 = x0 + w;
		int y0 = 3, y1 = y0 + h;

		Tensor<Double> t = tensor(shape(10, 10), (int[] c) -> {
			int x = c[0], y = c[1];
			return x >= x0 && x < x1 && y >= y0 && y < y1;
		});

		PackedCollection<?> input = t.pack();
		TraversalPolicy inputShape = input.getShape();
		System.out.println("PackedCollectionSubsetTests: input shape = " + inputShape);

		PackedCollection<?> pc = new PackedCollection<>(2).traverseEach();
		pc.set(0, (double) x0);
		pc.set(1, (double) y0);

		HardwareOperator.verboseLog(() -> {
			CollectionProducer<PackedCollection<?>> producer = subset(shape(w, h), p(input), p(pc));
			Evaluable<PackedCollection<?>> ev = producer.get();
			PackedCollection<?> subset = ev.evaluate();

			Assert.assertEquals(w, subset.getShape().length(0));
			Assert.assertEquals(h, subset.getShape().length(1));

			for (int i = 0; i < w; i++) {
				for (int j = 0; j < h; j++) {
					double expected = (x0 + i + y0 + j);
					double actual = subset.valueAt(i, j);
					System.out.println("PackedCollectionSubsetTests: [" + i + ", " + j + "] " + expected + " vs " + actual);
					assertEquals(expected, actual);
				}
			}
		});
	}

	@Test
	public void dynamicSubset3d() {
		int n = 10;
		int w = 2, h = 4, d = 3;
		int x0 = 4, y0 = 3, z0 = 2;

		int x1 = x0 + w;
		int y1 = y0 + h;
		int z1 = z0 + d;

		Tensor<Double> t = tensor(shape(n, n, n), (int[] c) -> {
			int x = c[0], y = c[1], z = c[2];
			return x >= x0 && x < x1 && y >= y0 && y < y1 && z >= z0 && z < z1;
		});

		PackedCollection<?> input = t.pack();
		TraversalPolicy inputShape = input.getShape();
		System.out.println("PackedCollectionSubsetTests: input shape = " + inputShape);

		PackedCollection<?> pc = new PackedCollection<>(3).traverseEach();
		pc.set(0, (double) x0);
		pc.set(1, (double) y0);
		pc.set(2, (double) z0);

		HardwareOperator.verboseLog(() -> {
			CollectionProducer<PackedCollection<?>> producer = subset(shape(w, h, d), p(input), p(pc));
			Evaluable<PackedCollection<?>> ev = producer.get();
			PackedCollection<?> subset = ev.evaluate();

			Assert.assertEquals(w, subset.getShape().length(0));
			Assert.assertEquals(h, subset.getShape().length(1));
			Assert.assertEquals(d, subset.getShape().length(2));

			for (int i = 0; i < w; i++) {
				for (int j = 0; j < h; j++) {
					for (int k = 0; k < d; k++) {
						double expected = input.valueAt(x0 + i, y0 + j, z0 + k);
						double actual = subset.valueAt(i, j, k);
						System.out.println("PackedCollectionSubsetTests: [" + i + ", " + j + ", " + k + "] " + expected + " vs " + actual);
						assertEquals(expected, actual);
					}
				}
			}
		});
	}

	@Test
	public void enumerate2d() {
		Tensor<Double> t = tensor(shape(10, 10), (int[] c) -> c[1] < 2);
		PackedCollection<?> input = t.pack();

		CollectionProducer<PackedCollection<?>> producer = enumerate(shape(10, 2), traverseEach(p(input)));
		Evaluable<PackedCollection<?>> ev = producer.get();
		PackedCollection<?> enumerated = ev.evaluate().reshape(shape(5, 10, 2));

		Assert.assertEquals(5, enumerated.getShape().length(0));
		Assert.assertEquals(10, enumerated.getShape().length(1));
		Assert.assertEquals(2, enumerated.getShape().length(2));

		for (int i = 0; i < 5; i++) {
			for (int j = 0; j < 10; j++) {
				for (int k = 0; k < 2; k++) {
					if (i == 0) {
						Assert.assertTrue(enumerated.toDouble(enumerated.getShape().index(i, j, k)) >= 0);
					} else {
						Assert.assertTrue(enumerated.toDouble(enumerated.getShape().index(i, j, k)) <= 0);
					}
				}
			}
		}
	}

	@Test
	public void slices() {
		int size = 4;
		int count = 3;

		PackedCollection<?> input = tensor(shape(size, size, count)).pack();

		CLOperator.verboseLog(() -> {
			CollectionProducer<PackedCollection<?>> enumerated = enumerate(shape(size, size, 1), p(input));
			PackedCollection<?> output = enumerated.get().evaluate();
			System.out.println(output.getShape());

			Assert.assertEquals(count, output.getShape().length(0));
			Assert.assertEquals(size, output.getShape().length(1));
			Assert.assertEquals(size, output.getShape().length(2));
			Assert.assertEquals(1, output.getShape().length(3));

			for (int i = 0; i < count; i++) {
				for (int x = 0; x < size; x++) {
					for (int y = 0; y < size; y++) {
						double expected = input.valueAt(x, y, i);
						System.out.println("PackedCollectionMapTests: " + expected + " vs " + output.valueAt(i, x, y, 0));
						Assert.assertEquals(expected, output.valueAt(i, x, y, 0), 0.0001);
					}
				}
			}
		});
	}

	@Test
	public void enumerateProduct() {
		PackedCollection<?> input = tensor(shape(6, 4)).pack();
		PackedCollection<?> operand = tensor(shape(4, 6, 1)).pack();

		CollectionProducer<PackedCollection<?>> product = enumerate(shape(6, 1), p(input)).traverse(0).multiply(p(operand));
		System.out.println(product.getShape());

		Evaluable<PackedCollection<?>> ev = product.get();
		PackedCollection<?> enumerated = ev.evaluate().reshape(shape(4, 6));

		Assert.assertEquals(4, enumerated.getShape().length(0));
		Assert.assertEquals(6, enumerated.getShape().length(1));

		for (int i = 0; i < 4; i++) {
			for (int j = 0; j < 6; j++) {
				Assert.assertEquals(input.toDouble(input.getShape().index(j, i)) * operand.toDouble(operand.getShape().index(i, j)),
						enumerated.toDouble(enumerated.getShape().index(i, j)), 0.0001);
			}
		}
	}

	@Test
	public void enumerateDivide() {
		int w = 4; // 1024;
		int h = 9;
		int d = 145;

		TraversalPolicy inShape = shape(w, h, d);
		TraversalPolicy outputShape = shape(h, w);

		PackedCollection<?> in = new PackedCollection<>(inShape);

		in.fill(pos -> Math.random());

		CollectionProducer<PackedCollection<?>> o =
				c(p(in))
						.traverse(2).sum()
						.divide(c(Math.sqrt(d)))
						.reshape(shape(w, h))
						.enumerate(1, 1)
						.reshape(outputShape);

		HardwareOperator.verboseLog(() -> {
//			PackedCollection<?> out = o.get().evaluate();
			// TODO This should not require optimization to pass, but currently it does
			PackedCollection<?> out = ((Evaluable<PackedCollection<?>>) ((ParallelProcess) o).optimize().get()).evaluate();

			for (int n = 0; n < h; n++) {
				for (int t = 0; t < w; t++) {
					double total = 0.0;

					for (int i = 0; i < d; i++) {
						total += in.valueAt(t, n, i);
					}

					total /= Math.sqrt(d);

					System.out.println("PackedCollectionSubsetTests[" + n + ", " + t + "]: " + total + " vs " + out.valueAt(n, t));
					assertEquals(total, out.valueAt(n, t));
				}
			}
		});
	}

	@Test
	public void enumerate2dProduct() {
		Tensor<Double> t = tensor(shape(4, 6));
		PackedCollection<?> input = t.pack().traverseEach();

		PackedCollection<?> operand = new PackedCollection<>(shape(6, 4));
		operand.fill(pos -> Math.random());
		operand = operand.traverseEach();

		Producer<PackedCollection<?>> product = enumerate(shape(4, 1), p(input)).traverse(0)
										.multiply(enumerate(shape(1, 4), p(operand)).traverse(0));

		Evaluable<PackedCollection<?>> ev = product.get();
		PackedCollection<?> enumerated = ev.evaluate().reshape(shape(6, 4));

		Assert.assertEquals(6, enumerated.getShape().length(0));
		Assert.assertEquals(4, enumerated.getShape().length(1));

		for (int i = 0; i < 6; i++) {
			for (int j = 0; j < 4; j++) {
				Assert.assertEquals(input.toDouble(input.getShape().index(j, i)) * operand.toDouble(operand.getShape().index(i, j)),
									enumerated.toDouble(enumerated.getShape().index(i, j)), 0.0001);
			}
		}
	}

	@Test
	public void stride2dProduct() {
		Tensor<Double> t = tensor(shape(8, 10));
		PackedCollection<?> input = t.pack();

		PackedCollection<?> filter = tensor(shape(9, 8, 2), (int[] c) -> {
			int i = c[0], j = c[1], k = c[2];
			return i == 0 || i == 8 || j == 0 || j == 7 || k == 0 || k == 1;
		}).pack();

		Producer<PackedCollection<?>> stride =
				enumerate(1, 2, 1, p(input))
				.traverse(0)
				.multiply(p(filter));
		Evaluable<PackedCollection<?>> ev = stride.get();
		PackedCollection<?> enumerated = ev.evaluate().reshape(shape(9, 8, 2));

		System.out.println(enumerated.getShape());

		for (int i = 0; i < 9; i++) {
			for (int j = 0; j < 8; j++) {
				for (int k = 0; k < 2; k++) {
					Assert.assertEquals(input.toDouble(input.getShape().index(j, i + k)) *
											filter.toDouble(filter.getShape().index(i, j, k)),
										enumerated.toDouble(enumerated.getShape().index(i, j, k)), 0.0001);
				}
			}
		}
	}

	@Test
	public void enumerateTwiceSmall() {
		PackedCollection<?> input = tensor(shape(4, 4)).pack();

//		HardwareOperator.verboseLog(() -> {
			CollectionProducer<PackedCollection<?>> convY = c(p(input))
					.enumerate(1, 2, 2);
			PackedCollection<?> output = convY.get().evaluate();
			System.out.println(output.getShape());

			CollectionProducer<PackedCollection<?>> convX = c(traverse(0, p(output)))
					.enumerate(1, 2, 2);
			output = convX.get().evaluate();
			System.out.println(output.getShape());

			for (int i = 0; i < 2; i++) {
				for (int j = 0; j < 2; j++) {
					for (int k = 0; k < 2; k++) {
						for (int l = 0; l < 2; l++) {
							double expected = input.toDouble(input.getShape().index(i * 2 + k, j * 2 + l));
							double actual = output.toDouble(output.getShape().index(i, j, k, l));
							System.out.println("PackedCollectionMapTests: " + expected + " vs " + actual);
							Assert.assertEquals(expected, actual, 0.0001);
						}
					}
				}
			}
//		});
	}

	@Test
	public void doubleEnumerateSmall() {
		int r = 4;
		int c = 4;
		int w = 2;
		int s = 2;

		PackedCollection<?> input = tensor(shape(r, c)).pack();

		CLOperator.verboseLog(() -> {
			CollectionProducer<PackedCollection<?>> conv = c(p(input))
					.enumerate(1, w, s)
					.enumerate(1, w, s);
			PackedCollection<?> output = conv.get().evaluate();
			System.out.println(output.getShape());

			for (int i = 0; i < r; i += s) {
				for (int j = 0; j < c; j += s) {
					System.out.println("i: " + i + " j: " + j);
					for (int k = 0; k < w; k++) {
						for (int l = 0; l < w; l++) {
							double expected = input.toDouble(input.getShape().index(i + k, j + l));
							double actual = output.toDouble(output.getShape().index(i / s, j / s, k, l));
							System.out.println("PackedCollectionSubsetTests: " + expected + " vs " + actual);
							Assert.assertEquals(expected, actual, 0.0001);
						}
					}
				}
			}
		});
	}

	@Test
	public void enumerateTwice() {
		PackedCollection<?> input = tensor(shape(10, 10)).pack();

		CLOperator.verboseLog(() -> {
			CollectionProducer<PackedCollection<?>> convY = c(p(input))
					.enumerate(1, 3, 1);
			PackedCollection<?> output = convY.get().evaluate();
			System.out.println(output.getShape());

			CollectionProducer<PackedCollection<?>> convX = c(traverse(0, p(output)))
					.enumerate(1, 3, 1);
			output = convX.get().evaluate();
			System.out.println(output.getShape());

			for (int i = 0; i < 8; i++) {
				for (int j = 0; j < 8; j++) {
					for (int k = 0; k < 3; k++) {
						for (int l = 0; l < 3; l++) {
							double expected = input.toDouble(input.getShape().index(i + k, j + l));
							double actual = output.toDouble(output.getShape().index(i, j, k, l));
							System.out.println("PackedCollectionMapTests: " + expected + " vs " + actual);
							Assert.assertEquals(expected, actual, 0.0001);
						}
					}
				}
			}
		});
	}

	// @Test
	public void doubleStride2dProduct() {
		Tensor<Double> t = tensor(shape(8, 10));
		PackedCollection<?> input = t.pack();

		PackedCollection<?> filter = tensor(shape(9, 8, 2), (int[] c) -> {
			int i = c[0], j = c[1], k = c[2];
			return i == 0 || i == 8 || j == 0 || j == 7 || k == 0 || k == 1;
		}).pack();

		Producer<PackedCollection<?>> stride = c(p(input))
						.enumerate(0, 3, 1)
						.enumerate(1, 3, 1)
						.multiply(c(p(filter)));
	}

	@Test
	public void subsetTranspose() {
		// TODO Transpose a matrix and then take a subset
		// enumerate(shape(10, 1), p(input)).subset(shape(3, 3, 1), 2, 2, 0)
	}

	@Test
	public void subsetProduct() {
		int size = 3;
		int x0 = 4, x1 = x0 + size;
		int y0 = 3, y1 = y0 + size;

		TraversalPolicy filterShape = shape(size, size);

		Tensor<Double> f = tensor(filterShape, (int[] c) -> true);
		PackedCollection<?> filter = f.pack();

		Tensor<Double> t = tensor(shape(10, 10), (int[] c) -> {
			int x = c[0], y = c[1];
			return x >= x0 && x < x1 && y >= y0 && y < y1;
		});

		PackedCollection<?> input = t.pack();

		HardwareOperator.verboseLog(() -> {
			CollectionProducer<PackedCollection<?>> subset = subset(shape(size, size), p(input), x0, y0);
//			Producer<PackedCollection<?>> product = multiply(traverseEach(p(filter)), traverseEach(subset)).reshape(filterShape);
			Producer<PackedCollection<?>> product = relativeMultiply(p(filter), subset, null);
			Evaluable<PackedCollection<?>> ev = product.get();
			PackedCollection<?> result = ev.evaluate();

			Assert.assertEquals(size, result.getShape().length(0));
			Assert.assertEquals(size, result.getShape().length(1));

			for (int i = 0; i < size; i++) {
				for (int j = 0; j < size; j++) {
					double expected = filter.toDouble(filterShape.index(i, j)) * (x0 + i + y0 + j);
					double actual = result.toDouble(subset.getShape().index(i, j));
					System.out.println("PackedCollectionSubsetTests: [" + i + ", " + j + "] " + expected + " vs " + actual);
					Assert.assertEquals(expected, actual, 0.0001);
				}
			}
		});
	}

	// @Test
	public void subsetAssignment() {
		PackedCollection<?> originalInput = new PackedCollection<>(shape(10, 20));
		originalInput.fill(pos -> Math.random());

		PackedCollection<?> input = new PackedCollection<>(shape(10, 20));
		input.fill(pos -> originalInput.valueAt(pos));

		PackedCollection<?> filter = new PackedCollection<>(shape(1, 20));
		filter.fill(pos -> Math.random());

		CollectionProducer<PackedCollection<?>> in = c(p(input));
		CollectionProducer<PackedCollection<?>> subset = subset(shape(1, 20), in, 4, 0).traverseEach();

		CLOperator.verboseLog(() -> {
			a(subset, subset.add(c(p(filter))).traverseEach()).get().run();
		});

		for (int i = 0; i < 20; i++) {
			Assert.assertEquals(
					input.valueAt(4, i),
					originalInput.valueAt(0, i) + filter.valueAt(0, i),
					0.0001);
		}
	}
}
