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

import io.almostrealism.expression.Expression;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.ArrayVariable;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.ExpressionComputation;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.hardware.cl.CLOperator;
import org.almostrealism.util.TestFeatures;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

public class EmbeddedCollectionMapTests implements TestFeatures, KernelAssertions {

	protected <T extends PackedCollection<?>> ExpressionComputation<T> first(Producer<T> input) {
		Function<List<ArrayVariable<Double>>, Expression<Double>> expression= np ->
				np.get(1).getValueRelative(0);
		return new ExpressionComputation<>(List.of(expression), (Supplier) input);
	}

	protected <T extends PackedCollection<?>> ExpressionComputation<T> duo(Producer<T> input) {
		List<Function<List<ArrayVariable<Double>>, Expression<Double>>> expression= new ArrayList<>();
		expression.add(np -> np.get(1).getValueRelative(0));
		expression.add(np -> np.get(1).getValueRelative(0));
		return new ExpressionComputation<>(expression, (Supplier) input);
	}

	@Test
	public void multiply() {
		PackedCollection<?> a = tensor(shape(8, 1)).pack();
		PackedCollection<?> b = tensor(shape(8, 1)).pack();

		multiply(p(a.traverseEach()), p(b.traverseEach())).get().evaluate();
	}

	@Test
	public void multiplyMap() {
		int n = 2;

		PackedCollection<?> input = tensor(shape(8, n)).pack();
		PackedCollection<?> filter = tensor(shape(n)).pack();
		filter.fill(pos -> Math.random());

		CLOperator.verboseLog(() -> {
			CollectionProducer<PackedCollection<?>> product = traverse(1, p(input)).map(v -> v.multiply(p(filter)));
			PackedCollection<?> output = product.get().evaluate();
			System.out.println(output.getShape());

			Assert.assertEquals(8, output.getShape().length(0));
			Assert.assertEquals(n, output.getShape().length(1));

			for (int i = 0; i < 8; i++) {
				for (int j = 0; j < n; j++) {
					Assert.assertEquals(input.valueAt(i, j) * filter.valueAt(j),
								output.valueAt(i, j), 0.0001);
				}
			}
		});
	}

	@Test
	public void reduceMax() {
		int c = 16;
		int d = 1;
		int w = 2;

		PackedCollection<?> input = tensor(shape(c / w, w, d)).pack();
		input.fill(pos -> Math.random());

		CLOperator.verboseLog(() -> {
			CollectionProducer<PackedCollection<?>> pool =
					c(p(input)).traverse(1).reduce(v -> max(v));
			System.out.println(pool.getShape());

			PackedCollection<?> output = pool.get().evaluate();
			System.out.println(output.getShape());

			int c2 = c / w;

			for (int copy = 0; copy < d; copy++) {
				for (int j = 0; j < c2; j++) {
					double expected = Math.max(input.valueAt(j, 0, copy), input.valueAt(j, 1, copy));
					double actual = output.toDouble(output.getShape().index(j, copy));

					System.out.println("EmbeddedCollectionMapTests[" + j + "]: Expected " + expected + " vs actual " + actual);
					Assert.assertEquals(expected, actual, 0.0001);
				}
			}
		});
	}

	@Test
	public void singleEnumerateReduceMax() {
		int c = 16;
		int d = 1;
		int w = 2;

		PackedCollection<?> input = tensor(shape(1, c, d)).pack();
		input.fill(pos -> Math.random());

		CLOperator.verboseLog(() -> {
			CollectionProducer<PackedCollection<?>> pool =
					enumerate(shape(1, c, d), c(p(input)))
							.traverse(1).reduce(slice -> max(slice));
			System.out.println(pool.getShape());

			PackedCollection<?> output = pool.get().evaluate();
			System.out.println(output.getShape());

			for (int copy = 0; copy < d; copy++) {
				double expected = -Math.pow(10, 5);

				for (int j = 0; j < c; j++) {
					expected = Math.max(expected, input.valueAt(0, j, copy));
				}

				double actual = output.valueAt(copy, 0);

				System.out.println("EmbeddedCollectionMapTests[" + copy + "]: Expected " + expected + " vs actual " + actual);
				Assert.assertEquals(expected, actual, 0.0001);
			}
		});
	}

	@Test
	public void enumerateReduceMax() {
		int c = 16;
		int d = 1;
		int w = 2;

		PackedCollection<?> input = tensor(shape(c, d)).pack();
		input.fill(pos -> Math.random());

		CLOperator.verboseLog(() -> {
			CollectionProducer<PackedCollection<?>> pool =
					enumerate(shape(w, d), c(p(input)))
							.traverse(1).reduce(slice -> max(slice));
			System.out.println(pool.getShape());

			PackedCollection<?> output = pool.get().evaluate();
			System.out.println(output.getShape());

			int c2 = c / w;

			for (int copy = 0; copy < d; copy++) {
				for (int j = 0; j < c2; j++) {
					double expected = -Math.pow(10, 5);

					for (int k = 0; k < w; k++) {
						expected = Math.max(expected, input.valueAt(j * w + k, copy));
					}

					double actual = output.valueAt(j, copy);

					System.out.println("EmbeddedCollectionMapTests[" + j + "]: Expected " + expected + " vs actual " + actual);
					Assert.assertEquals(expected, actual, 0.0001);
				}
			}
		});
	}

	@Test
	public void atomicReduceEnumerate() {
		int c = 16;
		int d = 1;
		int w = 1;

		PackedCollection<?> input = tensor(shape(c / w, w, d)).pack();
		input.fill(pos -> Math.random());

		CLOperator.verboseLog(() -> {
			CollectionProducer<PackedCollection<?>> pool =
					c(p(input)).traverse(1)
							.reduce(v ->
									enumerate(shape(1, w, 1), v)
											.traverse(1).reduce(slice -> max(slice)));
			System.out.println(pool.getShape());

			PackedCollection<?> output = pool.get().evaluate();
			System.out.println(output.getShape());

			for (int copy = 0; copy < d; copy++) {
				for (int j = 0; j < c; j++) {
					double expected = input.valueAt(j, 0, copy);
					double actual = output.toDouble(output.getShape().index(j, copy));

					System.out.println("EmbeddedCollectionMapTests[" + j + "]: Expected " + expected + " vs actual " + actual);
					Assert.assertEquals(expected, actual, 0.0001);
				}
			}
		});
	}

	@Test
	public void repeatMap() {
		int c = 8;
		int d = 2;

		PackedCollection<?> input = tensor(shape(c, d)).pack();
		input.fill(pos -> Math.random());

		CLOperator.verboseLog(() -> {
			CollectionProducer<PackedCollection<?>> repeat =
					c(p(input)).traverse(1).expand(2, v -> v.repeat(2));
			System.out.println(repeat.getShape());

			PackedCollection<?> output = repeat.get().evaluate();
			System.out.println(output.getShape());

			for (int i = 0; i < c; i++) {
				for (int j = 0; j < d; j++) {
					double expected = input.valueAt(i, j);

					double actual = output.valueAt(i, 0, j);
					System.out.println("EmbeddedCollectionMapTests[" + j + "]: Expected " + expected + " vs actual " + actual);
					Assert.assertEquals(expected, actual, 0.0001);

					actual = output.valueAt(i, 1, j);
					System.out.println("EmbeddedCollectionMapTests[" + j + "]: Expected " + expected + " vs actual " + actual);
					Assert.assertEquals(expected, actual, 0.0001);
				}
			}
		});
	}

	@Test
	public void repeatMultiply() {
		int d = 4;
		int n = 2;

		PackedCollection<?> filter = tensor(shape(n, d)).pack();
		PackedCollection<?> input = tensor(shape(d)).pack();
		filter.fill(pos -> Math.random());
		input.fill(pos -> Math.random());

		CLOperator.verboseLog(() -> {
			CollectionProducer<PackedCollection<?>> repeat = c(p(input)).repeat(2).each().multiply(p(filter));
			System.out.println(repeat.getShape());

			PackedCollection<?> output = repeat.get().evaluate();
			System.out.println(output.getShape());

			for (int i = 0; i < d; i++) {
				for (int j = 0; j < n; j++) {
					double expected = input.valueAt(i) * filter.valueAt(j, i);
					double actual = output.valueAt(j, i);
					System.out.println("EmbeddedCollectionMapTests[" + j + "]: Expected " + expected + " vs actual " + actual);
					Assert.assertEquals(expected, actual, 0.0001);
				}
			}
		});
	}

	@Test
	public void repeatMultiplyMap() {
		int c = 8;
		int d = 2;
		int n = 2;

		PackedCollection<?> filter = tensor(shape(n, d)).pack();
		PackedCollection<?> input = tensor(shape(c, d)).pack();
		filter.fill(pos -> Math.random());
		input.fill(pos -> Math.random());

		CLOperator.verboseLog(() -> {
			CollectionProducer<PackedCollection<?>> repeat =
					c(p(input)).traverse(1).expand(n, v -> v.repeat(n).each().multiply(p(filter)));
			System.out.println(repeat.getShape());

			PackedCollection<?> output = repeat.get().evaluate();
			System.out.println(output.getShape());

			for (int i = 0; i < c; i++) {
				for (int j = 0; j < n; j++) {
					for (int k = 0; k < d; k++) {
						double expected = input.valueAt(i, k) * filter.valueAt(j, k);
						double actual = output.valueAt(i, j, k);
						System.out.println("EmbeddedCollectionMapTests[" + i + "]: Expected " + expected + " vs actual " + actual);
						Assert.assertEquals(expected, actual, 0.0001);
					}
				}
			}
		});
	}

	@Test
	public void expandEnumerate() {
		int c = 4;
		int d = 2;

		PackedCollection<?> input = tensor(shape(c, d)).pack();
		input.fill(pos -> Math.random());

		HardwareOperator.verboseLog(() -> {
			CollectionProducer<PackedCollection<?>> pool =
					c(p(input)).traverse(1)
							.expand(1, v ->
									enumerate(shape(1, 2), v));
			System.out.println(pool.getShape());

			PackedCollection<?> output = pool.get().evaluate();
			System.out.println(output.getShape());
			System.out.println(Arrays.toString(output.toArray(0, 8)));

			for (int i = 0; i < c; i++) {
				for (int j = 0; j < d; j++) {
					double expected = input.valueAt(i, j);
					double actual = output.valueAt(i, 0, j);

					System.out.println("EmbeddedCollectionMapTests[" + i + "]: Expected " + expected + " vs actual " + actual);
					Assert.assertEquals(expected, actual, 0.0001);
				}
			}
		});
	}

	@Test
	public void reduceEnumerate() {
		int n = 4;
		int w = 2;

		PackedCollection<?> input = tensor(shape(n, w)).pack();
		input.fill(pos -> Math.random());

		CLOperator.verboseLog(() -> {
			CollectionProducer<PackedCollection<?>> pool =
					c(p(input)).traverse(1)
							.reduce(v ->
									enumerate(shape(1, w), v)
											.traverse(1).reduce(slice -> max(slice)));
			System.out.println(pool.getShape());

			PackedCollection<?> output = pool.get().evaluate();
			System.out.println(output.getShape());

			for (int i = 0; i < n; i++) {
				double expected = Math.max(input.valueAt(i, 0), input.valueAt(i, 1));
				double actual = output.valueAt(i, 0);

				System.out.println("EmbeddedCollectionMapTests[" + i + "]: Expected " + expected + " vs actual " + actual);
				Assert.assertEquals(expected, actual, 0.0001);
			}
		});
	}

	@Test
	public void reduceFirstEnumerate3dSingle() {
		int n = 4;
		int w = 2;
		int d = 1;

		PackedCollection<?> input = tensor(shape(n, w, d)).pack();
		input.fill(pos -> Math.random());

		System.out.println(Arrays.toString(input.toArray(0, 8)));

		CLOperator.verboseLog(() -> {
			CollectionProducer<PackedCollection<?>> pool =
					c(p(input)).traverse(1)
							.reduce(v ->
									enumerate(shape(1, w, 1), v)
											.traverse(1).reduce(slice -> first(slice)));
			System.out.println(pool.getShape());

			PackedCollection<?> output = pool.get().evaluate();
			System.out.println(output.getShape());
			System.out.println(Arrays.toString(output.toArray(0, 4)));

			for (int i = 0; i < n; i++) {
				for (int j = 0; j < d; j++) {
					double expected = input.valueAt(i, 0, j);
					double actual = output.valueAt(i, j);

					System.out.println("EmbeddedCollectionMapTests[" + i + "]: Expected " + expected + " vs actual " + actual);
					Assert.assertEquals(expected, actual, 0.0001);
				}
			}
		});
	}

	@Test
	public void mapEnumerate3d() {
		int n = 4;
		int w = 2;
		int d = 3;

		PackedCollection<?> input = tensor(shape(n, w, d)).pack();
		input.fill(pos -> Math.random());

		System.out.println(Arrays.toString(input.toArray(0, 8)));

		CLOperator.verboseLog(() -> {
			CollectionProducer<PackedCollection<?>> pool =
					c(p(input)).traverse(1)
							.map(shape(d, 1, w, 1),
									v -> enumerate(shape(1, w, 1), v));
			System.out.println(pool.getShape());

			PackedCollection<?> output = pool.get().evaluate();
			System.out.println(output.getShape());
			System.out.println(Arrays.toString(output.toArray(0, 4)));

			for (int i = 0; i < n; i++) {
				for (int j = 0; j < w; j++) {
					for (int k = 0; k < d; k++) {
						double expected = input.valueAt(i, j, k);
						double actual = output.valueAt(i, k, 0, j, 0);

						System.out.println("EmbeddedCollectionMapTests[" + i + "]: Expected " + expected + " vs actual " + actual);
						Assert.assertEquals(expected, actual, 0.0001);
					}
				}
			}
		});
	}

	@Test
	public void embeddedMap() {
		int n = 4;
		int d = 3;

		PackedCollection<?> input = tensor(shape(n, d)).pack();
		input.fill(pos -> Math.random());

		System.out.println(Arrays.toString(input.toArray(0, 12)));

		CLOperator.verboseLog(() -> {
			CollectionProducer<PackedCollection<?>> pool =
					c(p(input)).traverse(1)
							.map(shape(1, 2),
									p -> p.map(shape(1, 2),
											q -> duo(q)));
			System.out.println(pool.getShape());

			PackedCollection<?> output = pool.get().evaluate();
			System.out.println(output.getShape());
			System.out.println(Arrays.toString(output.toArray(0, 4)));

			for (int i = 0; i < n; i++) {
				for (int j = 0; j < 2; j++) {
					double expected = input.valueAt(i, 0);
					double actual = output.valueAt(i, 0, j);

					System.out.println("EmbeddedCollectionMapTests[" + i + "]: Expected " + expected + " vs actual " + actual);
					Assert.assertEquals(expected, actual, 0.0001);
				}
			}
		});
	}

	@Test
	public void enumerate() {
		int n = 4;
		int d = 6;
		int w = 2;

		PackedCollection<?> input = tensor(shape(n, d)).pack();
		input.fill(pos -> Math.random());

		CLOperator.verboseLog(() -> {
			CollectionProducer<PackedCollection<?>> en = enumerate(shape(n, w), c(p(input)));

			System.out.println(en.getShape());

			PackedCollection<?> output = en.get().evaluate();
			System.out.println(output.getShape());

			for (int i = 0; i < n; i++) {
				for (int j = 0; j < d; j++) {
					double expected = input.valueAt(i, j);
					double actual = output.valueAt(j / w, i, j % w);

					System.out.println("EmbeddedCollectionMapTests[" + i + "]: Expected " + expected + " vs actual " + actual);
					Assert.assertEquals(expected, actual, 0.0001);
				}
			}
		});
	}

	@Test
	public void enumerateExpression() {
		int n = 4;
		int d = 6;
		int w = 2;

		PackedCollection<?> a = tensor(shape(n, d)).pack();
		PackedCollection<?> b = tensor(shape(n, d)).pack();
		a.fill(pos -> Math.random());
		b.fill(pos -> Math.random());

		CLOperator.verboseLog(() -> {
			CollectionProducer<PackedCollection<?>> product =
					multiply(c(p(a)).traverse(1), c(p(b)).traverse(1));
			product = enumerate(shape(n, w), product);

			System.out.println(product.getShape());

			PackedCollection<?> output = product.get().evaluate();
			System.out.println(output.getShape());

			for (int i = 0; i < n; i++) {
				for (int j = 0; j < d; j++) {
					double expected = a.valueAt(i, j) * b.valueAt(i, j);
					double actual = output.valueAt(j / w, i, j % w);

					System.out.println("EmbeddedCollectionMapTests[" + i + "]: Expected " + expected + " vs actual " + actual);
					Assert.assertEquals(expected, actual, 0.0001);
				}
			}
		});
	}

	@Test
	public void mapFirstEnumerate2d() {
		int n = 4;
		int d = 6;
		int w = 2;

		PackedCollection<?> input = tensor(shape(n, d)).pack();
		input.fill(pos -> Math.random());

		System.out.println(Arrays.toString(input.toArray(0, 12)));

		CLOperator.verboseLog(() -> {
			CollectionProducer<PackedCollection<?>> pool =
					c(p(input)).traverse(1)
							.map(shape(3, 1),
									p ->
											enumerate(shape(1, w), p).traverse(1)
													.map(shape(1), q -> first(q)));
			System.out.println(pool.getShape());

			PackedCollection<?> output = pool.get().evaluate();
			System.out.println(output.getShape());
			System.out.println(Arrays.toString(output.toArray(0, 12)));

			int c = d / w;

			for (int i = 0; i < n; i++) {
				for (int j = 0; j < c; j++) {
					double expected = input.valueAt(i, 2 * j);
					double actual = output.valueAt(i, j, 0);

					System.out.println("EmbeddedCollectionMapTests[" + i + "]: Expected " + expected + " vs actual " + actual);
					Assert.assertEquals(expected, actual, 0.0001);
				}
			}
		});
	}

	@Test
	public void mapFirstEnumerate3d() {
		int n = 4;
		int w = 2;
		int d = 3;

		PackedCollection<?> input = tensor(shape(n, w, d)).pack();
		input.fill(pos -> Math.random());

		System.out.println(Arrays.toString(input.toArray(0, 8)));

		CLOperator.verboseLog(() -> {
			CollectionProducer<PackedCollection<?>> pool =
					c(p(input)).traverse(1)
							.map(shape(3, 1), v ->
									enumerate(shape(1, w, 1), v)
											.traverse(1).reduce(slice -> first(slice)));
			System.out.println(pool.getShape());

			PackedCollection<?> output = pool.get().evaluate();
			System.out.println(output.getShape());
			System.out.println(Arrays.toString(output.toArray(0, 4)));

			for (int i = 0; i < n; i++) {
				for (int j = 0; j < d; j++) {
					double expected = input.valueAt(i, 0, j);
					double actual = output.valueAt(i, j, 0);

					System.out.println("EmbeddedCollectionMapTests[" + i + "]: Expected " + expected + " vs actual " + actual);
					Assert.assertEquals(expected, actual, 0.0001);
				}
			}
		});
	}

	@Test
	public void reduceMaxEnumerate3d() {
		int n = 8;
		int w = 2;
		int d = 1;

		PackedCollection<?> input = tensor(shape(n, w, d)).pack();
		input.fill(pos -> Math.random());

		CLOperator.verboseLog(() -> {
			CollectionProducer<PackedCollection<?>> pool =
					c(p(input)).traverse(1)
							.reduce(v ->
									enumerate(shape(1, w, 1), v)
											.traverse(1).reduce(slice -> max(slice)));
			System.out.println(pool.getShape());

			PackedCollection<?> output = pool.get().evaluate();
			System.out.println(output.getShape());

			for (int i = 0; i < n; i++) {
				for (int j = 0; j < d; j++) {
					double expected = Math.max(input.valueAt(i, 0, j), input.valueAt(i, 1, j));
					double actual = output.valueAt(i, j);

					System.out.println("EmbeddedCollectionMapTests[" + i + "]: Expected " + expected + " vs actual " + actual);
					Assert.assertEquals(expected, actual, 0.0001);
				}
			}
		});
	}

	@Test
	public void enumerateReduceEnumerateMax() {
		int c = 8;
		int d = 3;
		int w = 2;

		PackedCollection<?> input = tensor(shape(c, d)).pack();
		input.fill(pos -> Math.random());

		CLOperator.verboseLog(() -> {
			CollectionProducer<PackedCollection<?>> pool =
					c(p(input)).enumerate(w)
							.traverse(1)
							.map(shape(d, 1), v ->
									enumerate(shape(1, w, 1), v)
											.traverse(1).reduce(slice -> max(slice)));
			System.out.println(pool.getShape());

			PackedCollection<?> output = pool.get().evaluate();
			System.out.println(output.getShape());

			int c2 = c / w;

			for (int copy = 0; copy < d; copy++) {
				for (int j = 0; j < c2; j++) {
					double expected = -Math.pow(10, 5);

					for (int k = 0; k < w; k++) {
						expected = Math.max(expected, input.valueAt(j * w + k, copy));
					}

					double actual = output.valueAt(j, copy);

					System.out.println("EmbeddedCollectionMapTests[" + j + "]: Expected " + expected + " vs actual " + actual);
					Assert.assertEquals(expected, actual, 0.0001);
				}
			}
		});
	}
}
