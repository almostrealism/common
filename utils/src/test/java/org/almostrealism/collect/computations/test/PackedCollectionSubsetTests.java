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
import io.almostrealism.expression.Sum;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.ArrayVariable;
import org.almostrealism.algebra.Tensor;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CollectionProducerComputation;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.computations.ArrayVariableComputation;
import org.almostrealism.hardware.KernelizedEvaluable;
import org.almostrealism.hardware.cl.HardwareOperator;
import org.almostrealism.util.TestFeatures;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.IntStream;

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
			Producer<PackedCollection<?>> product = _multiply(traverseEach(p(filter)), subset).reshape(filterShape);
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

	@Test
	public void arrayVariableComputation() {
		int filterCount = 4;
		int size = 3;
		int w = 10;
		int h = 10;

		TraversalPolicy filterShape = shape(filterCount, size, size);
		TraversalPolicy inputShape = shape(h, w);
		TraversalPolicy outputShape = shape(h - 2, w - 2, filterCount);

		Tensor<Double> f = tensor(filterShape);
		Tensor<Double> t = tensor(inputShape);

		PackedCollection<?> filter = f.pack();
		PackedCollection<?> input = t.pack();

		// output[i, j] = np.sum(im_region * self.filters, axis=(1, 2))

		Expression index = new Expression(Integer.class, "get_global_id(0)");
		Expression i = outputShape.position(index)[0];
		Expression j = outputShape.position(index)[1];
		Expression k = outputShape.position(index)[2];

		// TODO  The ideal way to describe this computation looks like this
		// kernel(outputShape, (args, i, j, k) -> args[1].get(k).multiply(args[2].get(shape(size, size), i, j)).sum());

		Function<List<ArrayVariable<Double>>, Expression<Double>> expression = (List<ArrayVariable<Double>> args) -> {
			List<Expression> sum = new ArrayList<>();

			// args.get(1).get(k).multiply(args.get(2).get(shape(size, size), i, j)).sum()

			for (int x = 0; x < (size * size); x++) {
				// Pixel x of filter k
				Expression<Double> filterValue = args.get(1).valueAt(filterShape.subset(shape(1, size, size), x, k));
				// -> args.get(1).get(k).valueAt(x)

				// Pixel x of input (i:i+size, j:j+size)
				Expression<Double> inputValue = args.get(2).valueAt(inputShape.subset(shape(size, size), x, i, j));
				// -> args.get(2).get(i, j).subset(size, size).valueAt(x)

				// Multiply and add to sum
				sum.add(filterValue.multiply(inputValue));
			}

			return new Sum(sum.toArray(Expression[]::new));
		};

		CollectionProducerComputation<PackedCollection<?>> producer =
				new ArrayVariableComputation<>(outputShape, List.of(expression), p(filter), p(input));
		KernelizedEvaluable<PackedCollection<?>> ev = producer.get();

		PackedCollection<?> result = new PackedCollection(outputShape);
		ev.kernelEvaluate(result.traverseEach(), filter, input);
		System.out.println(result.getShape());

		for (int p = 0; p < outputShape.length(0); p++) {
			for (int q = 0; q < outputShape.length(1); q++) {
				for (int r = 0; r < outputShape.length(2); r++) {
					double expected = 0;

					for (int x = 0; x < size; x++) {
						for (int y = 0; y < size; y++) {
							expected += filter.toDouble(filterShape.index(r, x, y)) * input.toDouble(inputShape.index(p + x, q + y));
						}
					}

					double actual = result.toDouble(outputShape.index(p, q, r));
					System.out.println("PackedCollectionSubsetTests: [" + p + ", " + q + ", " + r + "] " + expected + " vs " + actual);
					Assert.assertEquals(expected, actual, 0.0001);
				}
			}
		}
	}

	@Test
	public void variableSubsetKernel() {
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

		CollectionProducerComputation<PackedCollection<?>> producer =
				kernel(i -> new Expression(Integer.class, "get_global_id(" + i + ")"),
						subsetShape, (args, pos) -> args[1].get(shape(w, h, d), x0, y0, z0).valueAt(subsetShape.index(pos)), p(input));
		KernelizedEvaluable<PackedCollection<?>> ev = producer.get();

		PackedCollection<?> result = new PackedCollection(subsetShape);
		ev.kernelEvaluate(result.traverseEach());
		System.out.println(result.getShape());

		for (int i = 0; i < w; i++) {
			for (int j = 0; j < h; j++) {
				for (int k = 0; k < d; k++) {
					double expected = (x0 + i + y0 + j + z0 + k);
					double actual = result.toDouble(subsetShape.index(i, j, k));
					System.out.println("PackedCollectionSubsetTests: [" + i + ", " + j + ", " + k + "] " + expected + " vs " + actual);
					Assert.assertEquals(expected, actual, 0.0001);
				}
			}
		}
	}

	@Test
	public void windowSumKernel() {
		int size = 3;
		int w = 10;
		int h = 10;

		TraversalPolicy inputShape = shape(h, w);
		TraversalPolicy outputShape = shape(h - 2, w - 2, 1);

		Tensor<Double> t = tensor(inputShape);
		PackedCollection<?> input = t.pack();

		CollectionProducerComputation<PackedCollection<?>> producer =
				kernel(i -> new Expression(Integer.class, "get_global_id(" + i + ")"),
						outputShape, (args, pos) -> {
							System.out.println("args[1].shape = " + args[1].getShape());
							Expression exp = args[1].get(shape(size, size), pos[0], pos[1]).toList().sum();
							return exp;
						}, p(input));
		KernelizedEvaluable<PackedCollection<?>> ev = producer.get();

		PackedCollection<?> result = new PackedCollection(outputShape);
		ev.kernelEvaluate(result.traverseEach());
		System.out.println(result.getShape());

		for (int p = 0; p < outputShape.length(0); p++) {
			for (int q = 0; q < outputShape.length(1); q++) {
				for (int r = 0; r < outputShape.length(2); r++) {
					double expected = 0;

					for (int x = 0; x < size; x++) {
						for (int y = 0; y < size; y++) {
							expected += input.toDouble(inputShape.index(p + x, q + y));
						}
					}

					double actual = result.toDouble(outputShape.index(p, q, r));
					System.out.println("PackedCollectionSubsetTests: [" + p + ", " + q + ", " + r + "] " + expected + " vs " + actual);
					Assert.assertEquals(expected, actual, 0.0001);
				}
			}
		}
	}

	@Test
	public void convKernel() {
		int filterCount = 4;
		int size = 3;
		int w = 10;
		int h = 10;

		TraversalPolicy filterShape = shape(filterCount, size, size);
		TraversalPolicy inputShape = shape(h, w);
		TraversalPolicy outputShape = shape(h - 2, w - 2, filterCount);

		Tensor<Double> f = tensor(filterShape);
		Tensor<Double> t = tensor(inputShape);

		PackedCollection<?> filter = f.pack();
		PackedCollection<?> input = t.pack();

		CollectionProducerComputation<PackedCollection<?>> producer =
				kernel(outputShape, (args, pos) -> {
							System.out.println("args[1].shape = " + args[1].getShape());
							System.out.println("args[2].shape = " + args[2].getShape());
							return args[1].get(shape(1, size, size), pos[2])
									.multiply(args[2].get(shape(size, size), pos[0], pos[1])).sum();
						}, p(filter), p(input));
		KernelizedEvaluable<PackedCollection<?>> ev = producer.get();

		PackedCollection<?> result = new PackedCollection(outputShape);
		ev.kernelEvaluate(result.traverseEach());
		System.out.println(result.getShape());

		for (int p = 0; p < outputShape.length(0); p++) {
			for (int q = 0; q < outputShape.length(1); q++) {
				for (int r = 0; r < outputShape.length(2); r++) {
					double expected = 0;

					for (int x = 0; x < size; x++) {
						for (int y = 0; y < size; y++) {
							expected += filter.toDouble(filterShape.index(r, x, y)) * input.toDouble(inputShape.index(p + x, q + y));
						}
					}

					double actual = result.toDouble(outputShape.index(p, q, r));
					System.out.println("PackedCollectionSubsetTests: [" + p + ", " + q + ", " + r + "] " + expected + " vs " + actual);
					Assert.assertEquals(expected, actual, 0.0001);
				}
			}
		}
	}
}