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

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.ExpressionComputation;
import org.almostrealism.collect.computations.PackedCollectionMap;
import org.almostrealism.collect.computations.PackedCollectionRepeat;
import org.almostrealism.collect.computations.TraversableProducerComputationAdapter;
import org.almostrealism.hardware.KernelizedEvaluable;
import org.almostrealism.hardware.cl.HardwareOperator;
import org.almostrealism.util.TestFeatures;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.stream.IntStream;

public class PackedCollectionMapTests implements TestFeatures {
	@Test
	public void map3d() {
		PackedCollection<?> input = tensor(shape(8, 3, 3)).pack();
		PackedCollection<?> filter = tensor(shape(3, 3)).pack();

		HardwareOperator.verboseLog(() -> {
			CollectionProducer<PackedCollection<?>> product = traverse(1, p(input)).map(v -> v.multiply(p(filter)));
			PackedCollection<?> output = product.get().evaluate();
			System.out.println(output.getShape());

			Assert.assertEquals(8, output.getShape().length(0));
			Assert.assertEquals(3, output.getShape().length(1));
			Assert.assertEquals(3, output.getShape().length(2));

			for (int i = 0; i < 8; i++) {
				for (int j = 0; j < 3; j++) {
					for (int k = 0; k < 3; k++) {
						Assert.assertEquals(input.toDouble(input.getShape().index(i, j, k)) *
										filter.toDouble(filter.getShape().index(j, k)),
								output.toDouble(output.getShape().index(i, j, k)), 0.0001);
					}
				}
			}
		});
	}

	// @Test
	public void scale() {
		PackedCollection<?> timeline = new PackedCollection<>(shape(10), 1);
		IntStream.range(0, 10).forEach(i -> timeline.set(i, i + 1));

		PackedCollection<?> scale = new PackedCollection<>(shape(5), 1);
		IntStream.range(0, 5).forEach(i -> scale.set(i, i + 2));
		System.out.println(Arrays.toString(scale.toArray(0, 5)));

		HardwareOperator.verboseLog(() -> {
			Producer<PackedCollection<?>> repeated = c(p(scale)).traverse(1).expand(10, v -> v.repeat(10));

			Producer<PackedCollection<?>> repeatedTimeline = c(p(timeline)).traverse(0).expand(5, v -> v.repeat(5));

			KernelizedEvaluable<PackedCollection<?>> ev = multiply(traverseEach(repeated), traverseEach(repeatedTimeline)).get();
			PackedCollection<?> destination = ev.evaluate();
			System.out.println(destination.getShape());
			System.out.println(Arrays.toString(destination.toArray(0, 10)));
			System.out.println(Arrays.toString(destination.toArray(10, 10)));

			assertEquals(8, destination.toDouble(3));
			assertEquals(12, destination.toDouble(13));
		});
	}

	@Test
	public void simpleReduce() {
		PackedCollection<?> input = tensor(shape(8, 3, 3)).pack();
		PackedCollection<?> source = tensor(shape(3, 1)).pack();
		PackedCollection<?> filter = tensor(shape(3, 1)).pack();

		HardwareOperator.verboseLog(() -> {
			CollectionProducer<PackedCollection<?>> product = traverse(1, p(input)).map(shape(3, 1), v -> add(p(source), p(filter)));
			PackedCollection<?> output = product.get().evaluate();
			System.out.println(output.getShape());

			Assert.assertEquals(8, output.getShape().length(0));
			Assert.assertEquals(3, output.getShape().length(1));
			Assert.assertEquals(1, output.getShape().length(2));

			for (int i = 0; i < 8; i++) {
				for (int j = 0; j < 3; j++) {
					for (int k = 0; k < 1; k++) {
						Assert.assertEquals(source.toDouble(source.getShape().index(j, k)) +
										filter.toDouble(filter.getShape().index(j, k)),
								output.toDouble(output.getShape().index(i, j, k)), 0.0001);
					}
				}
			}
		});
	}

	@Test
	public void sumByReduce() {
		PackedCollection<?> input = tensor(shape(8, 6)).pack();

		HardwareOperator.verboseLog(() -> {
			CollectionProducer<PackedCollection<?>> product = traverse(1, p(input)).reduce(v -> v.sum());
			PackedCollection<?> output = product.get().evaluate();
			System.out.println(output.getShape());

			Assert.assertEquals(8, output.getShape().length(0));
			Assert.assertEquals(1, output.getShape().length(1));

			for (int i = 0; i < 8; i++) {
				double expected = 0;

				for (int j = 0; j < 6; j++) {
					expected += input.toDouble(input.getShape().index(i, j));
				}

				System.out.println("PackedCollectionMapTests: " + expected + " vs " + output.toDouble(output.getShape().index(i, 0)));
				Assert.assertEquals(expected, output.toDouble(output.getShape().index(i, 0)), 0.0001);
			}
		});
	}

	@Test
	public void sumReduceOneByOne() {
		int size = 1;
		int count = 3;

		PackedCollection<?> input = tensor(shape(size, size, count)).pack();

		HardwareOperator.verboseLog(() -> {
			CollectionProducer<PackedCollection<?>> enumerated = enumerate(shape(size, size, 1), p(input));
			CollectionProducer<PackedCollection<?>> sum = enumerated.traverse(1).reduce(slice -> sum(slice));
			PackedCollection<?> output = sum.get().evaluate();
			System.out.println(output.getShape());

			Assert.assertEquals(count, output.getShape().length(0));
			Assert.assertEquals(1, output.getShape().length(1));

			for (int i = 0; i < count; i++) {
				double expected = 0;

				for (int x = 0; x < size; x++) {
					for (int y = 0; y < size; y++) {
						expected = expected + input.valueAt(x, y, i);
					}
				}

				System.out.println("PackedCollectionMapTests: " + expected + " vs " + output.valueAt(i, 0));
				Assert.assertEquals(expected, output.valueAt(i, 0), 0.0001);
			}
		});
	}

	@Test
	public void sumReduce2d() {
		int size = 4;
		int count = 3;

		PackedCollection<?> input = tensor(shape(size, size, count)).pack();

		HardwareOperator.verboseLog(() -> {
			CollectionProducer<PackedCollection<?>> enumerated = enumerate(shape(size, size, 1), p(input));
			CollectionProducer<PackedCollection<?>> sum = enumerated.traverse(1).reduce(slice -> sum(slice));
			PackedCollection<?> output = sum.get().evaluate();
			System.out.println(output.getShape());

			Assert.assertEquals(count, output.getShape().length(0));
			Assert.assertEquals(1, output.getShape().length(1));

			for (int i = 0; i < count; i++) {
				double expected = 0;

				for (int x = 0; x < size; x++) {
					for (int y = 0; y < size; y++) {
						expected = expected + input.valueAt(x, y, i);
					}
				}

				System.out.println("PackedCollectionMapTests: " + expected + " vs " + output.valueAt(i, 0));
				Assert.assertEquals(expected, output.valueAt(i, 0), 0.0001);
			}
		});
	}

	@Test
	public void maxReduce() {
		int size = 4;
		int count = 3;

		PackedCollection<?> input = tensor(shape(size, size, count)).pack();

		HardwareOperator.verboseLog(() -> {
			CollectionProducer<PackedCollection<?>> enumerated = enumerate(shape(size, size, 1), p(input));
			CollectionProducer<PackedCollection<?>> max = enumerated.traverse(1).reduce(slice -> max(slice));
			PackedCollection<?> output = max.get().evaluate();
			System.out.println(output.getShape());

			Assert.assertEquals(count, output.getShape().length(0));
			Assert.assertEquals(1, output.getShape().length(1));

			for (int i = 0; i < count; i++) {
				double expected = -1;

				for (int x = 0; x < size; x++) {
					for (int y = 0; y < size; y++) {
						expected = Math.max(input.valueAt(x, y, i), expected);
					}
				}

				System.out.println("PackedCollectionMapTests: " + expected + " vs " + output.valueAt(i, 0));
				Assert.assertEquals(expected, output.valueAt(i, 0), 0.0001);
			}
		});
	}

	@Test
	public void mapReduce() {
		PackedCollection<?> input = tensor(shape(8, 3, 3)).pack();
		PackedCollection<?> filter = tensor(shape(3, 3)).pack();

		HardwareOperator.verboseLog(() -> {
			CollectionProducer<PackedCollection<?>> product = traverse(1, p(input))
														.map(v -> v.multiply(p(filter)))
														.reduce(v -> v.sum());
			PackedCollection<?> output = product.get().evaluate();
			System.out.println(output.getShape());

			for (int i = 0; i < 8; i++) {
				double expected = 0;

				for (int j = 0; j < 3; j++) {
					for (int k = 0; k < 3; k++) {
						expected += input.toDouble(input.getShape().index(i, j, k)) * filter.toDouble(filter.getShape().index(j, k));
					}
				}

				System.out.println("PackedCollectionMapTests: " + expected + " vs " + output.toDouble(output.getShape().index(i, 0)));
				Assert.assertEquals(expected, output.toDouble(output.getShape().index(i, 0)), 0.0001);
			}
		});
	}

	@Test
	public void mapRepeatReduce() {
		int r = 8;
		int c = 3;
		int w = 2;

		PackedCollection<?> input = tensor(shape(r, c)).pack();
		PackedCollection<?> filter = tensor(shape(w, c)).pack();

		HardwareOperator.verboseLog(() -> {
			CollectionProducer<PackedCollection<?>> product = traverse(1, p(input))
					.expand(w, v -> v.repeat(w).multiply(p(filter)))
					.traverse()
					.reduce(v -> v.sum());
			PackedCollection<?> output = product.get().evaluate();
			System.out.println(output.getShape());

			for (int i = 0; i < r; i++) {
				for (int j = 0; j < w; j++) {
					double expected = 0;

					for (int k = 0; k < c; k++) {
						expected += input.valueAt(i, k) * filter.valueAt(j, k);
					}

					System.out.println("PackedCollectionMapTests: " + expected + " vs " + output.valueAt(i, j, 0));
					Assert.assertEquals(expected, output.valueAt(i, j, 0), 0.0001);
				}
			}
		});
	}

	@Test
	public void enumerateMapSmall() {
		int r = 3;
		int c = 3;
		int w = 2;
		int s = 1;
		int pad = 1;

		PackedCollection<?> input = tensor(shape(r, c)).pack();
		PackedCollection<?> filter = tensor(shape(w, w)).pack();

		input.fill(pos -> pos[0] + pos[1] * 0.1);
		filter.fill(pos -> 1.0);

		HardwareOperator.verboseLog(() -> {
			CollectionProducer<PackedCollection<?>> conv = c(p(input))
					.enumerate(1, w, s)
					.enumerate(1, w, s)
					.traverse(2)
					.map(v -> v.multiply(p(filter)));
			System.out.println(conv.getShape());

			PackedCollection<?> output = conv.get().evaluate();
			System.out.println(output.getShape());

			for (int i = 0; i < r - pad; i++) {
				for (int j = 0; j < c - pad; j++) {
					System.out.println("PackedCollectionMapTests: " + i + ", " + j);

					for (int k = 0; k < w; k++) {
						System.out.print("\t[");
						for (int l = 0; l < w; l++) {
							double expected = input.toDouble(input.getShape().index(i + k, j + l)) * filter.toDouble(filter.getShape().index(k, l));
							double actual = output.toDouble(output.getShape().index(i, j, k, l));

							System.out.print(expected + ", ");
						}

						System.out.print("]\t[");

						for (int l = 0; l < w; l++) {
							double expected = input.toDouble(input.getShape().index(i + k, j + l)) * filter.toDouble(filter.getShape().index(k, l));
							double actual = output.toDouble(output.getShape().index(i, j, k, l));

							System.out.print(actual + ", ");

							Assert.assertEquals(expected, actual, 0.0001);
						}

						System.out.println("]");
					}
				}
			}
		});
	}

	@Test
	public void enumerateMap() {
		int r = 10;
		int c = 10;
		int w = 3;
		int s = 1;
		int pad = 2;

		PackedCollection<?> input = tensor(shape(r, c)).pack();
		PackedCollection<?> filter = tensor(shape(w, w)).pack();

		boolean enableDynamic = ExpressionComputation.enableDynamicComputation;

		try {
			ExpressionComputation.enableDynamicComputation = true;

			HardwareOperator.verboseLog(() -> {
				CollectionProducer<PackedCollection<?>> conv = c(p(input))
						.enumerate(1, w, s)
						.enumerate(1, w, s)
						.traverse(2)
						.map(v ->
								v.multiply(p(filter)));
				System.out.println(conv.getShape());

				PackedCollection<?> output = conv.get().evaluate();
				System.out.println(output.getShape());

				for (int i = 0; i < r - pad; i++) {
					for (int j = 0; j < c - pad; j++) {
						System.out.println("PackedCollectionMapTests: " + i + ", " + j);

						for (int k = 0; k < w; k++) {
							for (int l = 0; l < w; l++) {
								double expected = input.toDouble(input.getShape().index(i + k, j + l)) * filter.toDouble(filter.getShape().index(k, l));
								double actual = output.toDouble(output.getShape().index(i, j, k, l));

								System.out.println("\tPackedCollectionMapTests: " + expected + " vs " + actual);
								Assert.assertEquals(expected, actual, 0.0001);
							}
						}
					}
				}
			});
		} finally {
			ExpressionComputation.enableDynamicComputation = enableDynamic;
		}
	}

	// @Test
	public void enumerateMapTraverseEach() {
		int r = 10;
		int c = 10;
		int w = 3;
		int s = 1;
		int pad = 2;

		PackedCollection<?> input = tensor(shape(r, c)).pack();
		PackedCollection<?> filter = tensor(shape(w, w)).pack();

		boolean enableDynamic = ExpressionComputation.enableDynamicComputation;

		try {
			ExpressionComputation.enableDynamicComputation = true;

			HardwareOperator.verboseLog(() -> {
				CollectionProducer<PackedCollection<?>> conv = c(p(input))
						.enumerate(1, w, s)
						.enumerate(1, w, s)
						.traverse(2)
						.map(v ->
								v.traverseEach().multiply(traverseEach(p(filter))));
				System.out.println(conv.getShape());

				PackedCollection<?> output = conv.get().evaluate();
				System.out.println(output.getShape());

				for (int i = 0; i < r - pad; i++) {
					for (int j = 0; j < c - pad; j++) {
						System.out.println("PackedCollectionMapTests: " + i + ", " + j);

						for (int k = 0; k < w; k++) {
							for (int l = 0; l < w; l++) {
								double expected = input.toDouble(input.getShape().index(i + k, j + l)) * filter.toDouble(filter.getShape().index(k, l));
								double actual = output.toDouble(output.getShape().index(i, j, k, l));

								System.out.println("\tPackedCollectionMapTests: " + expected + " vs " + actual);
								Assert.assertEquals(expected, actual, 0.0001);
							}
						}
					}
				}
			});
		} finally {
			ExpressionComputation.enableDynamicComputation = enableDynamic;
		}
	}

	@Test
	public void enumerateMapReduce() {
		int r = 10;
		int c = 10;
		int w = 3;
		int s = 1;

		PackedCollection<?> input = tensor(shape(r, c)).pack();
		PackedCollection<?> filter = tensor(shape(w, w)).pack();

		boolean enableDynamic = ExpressionComputation.enableDynamicComputation;

		try {
			ExpressionComputation.enableDynamicComputation = true;

			HardwareOperator.verboseLog(() -> {
				CollectionProducer<PackedCollection<?>> conv = c(p(input))
						.enumerate(1, w, s)
						.enumerate(1, w, s)
						.traverse(2)
						.map(v -> v.multiply(p(filter)))
						.reduce(v -> v.sum());
				System.out.println(conv.getShape());

				PackedCollection<?> output = conv.get().evaluate();
				System.out.println(output.getShape());

				for (int i = 0; i < 8; i++) {
					for (int j = 0; j < 8; j++) {
						double expected = 0;

						for (int k = 0; k < 3; k++) {
							for (int l = 0; l < 3; l++) {
								expected += input.toDouble(input.getShape().index(i + k, j + l)) * filter.toDouble(filter.getShape().index(k, l));
							}
						}

						double actual = output.toDouble(output.getShape().index(i, j, 0));

						System.out.println("PackedCollectionMapTests: " + expected + " vs " + actual);
						Assert.assertEquals(expected, actual, 0.0001);
					}
				}
			});
		} finally {
			ExpressionComputation.enableDynamicComputation = enableDynamic;
		}
	}


	@Test
	public void enumerateRepeatMapSmall() {
		int r = 3;
		int c = 3;
		int w = 2;
		int s = 1;
		int pad = 1;

		PackedCollection<?> input = tensor(shape(r, c)).pack();
		PackedCollection<?> filter = tensor(shape(2, w, w)).pack();

		input.fill(pos -> pos[0] + pos[1] * 0.1);

		boolean enableDynamic = ExpressionComputation.enableDynamicComputation;

		try {
			ExpressionComputation.enableDynamicComputation = true;

			IntStream.range(0, 20).forEach(n -> {
				HardwareOperator.verboseLog(() -> {
					CollectionProducer<PackedCollection<?>> conv = c(p(input))
							.enumerate(1, w, s)
							.enumerate(1, w, s)
							.traverse(2)
							.expand(2, v ->
									v.repeat(2).multiply(p(filter)));
					System.out.println(conv.getShape());

					PackedCollection<?> output = conv.get().evaluate();
					System.out.println(output.getShape());

					for (int copy = 0; copy < 2; copy++) {
						for (int i = 0; i < r - pad; i++) {
							for (int j = 0; j < c - pad; j++) {
								System.out.println("PackedCollectionMapTests: " + i + ", " + j);

								for (int k = 0; k < w; k++) {
									System.out.print("\t[");
									for (int l = 0; l < w; l++) {
										double expected = input.toDouble(input.getShape().index(i + k, j + l)) *
												filter.toDouble(filter.getShape().index(copy, k, l));
										double actual = output.toDouble(output.getShape().index(i, j, copy, k, l));

										System.out.print(expected + ", ");
									}

									System.out.print("]\t[");

									for (int l = 0; l < w; l++) {
										double expected = input.toDouble(input.getShape().index(i + k, j + l)) *
												filter.toDouble(filter.getShape().index(copy, k, l));

										double actual = output.toDouble(output.getShape().index(i, j, copy, k, l));

										System.out.print(actual + ", ");

										Assert.assertEquals(expected, actual, 0.0001);
									}

									System.out.println("]");
								}
							}
						}
					}
				});
			});
		} finally {
			ExpressionComputation.enableDynamicComputation = enableDynamic;
		}
	}


	@Test
	public void enumerateRepeatMapReduce() {
		int r = 10;
		int c = 10;
		int w = 3;
		int s = 1;
		int pad = 2;

		int n = 4;

		PackedCollection<?> input = tensor(shape(r, c)).pack();
		PackedCollection<?> filter = tensor(shape(n, w, w)).pack();

		boolean enableDynamic = ExpressionComputation.enableDynamicComputation;

		try {
			ExpressionComputation.enableDynamicComputation = true;

			HardwareOperator.verboseLog(() -> {
				CollectionProducer<PackedCollection<?>> conv = c(p(input))
						.enumerate(1, w, s)
						.enumerate(1, w, s)
						.traverse(2)
						.expand(n, v -> v.repeat(n).multiply(p(filter)))
						.traverse()
						.reduce(v -> v.sum());
				System.out.println(conv.getShape());

				KernelizedEvaluable<PackedCollection<?>> ev = (KernelizedEvaluable<PackedCollection<?>>) conv.get();
				PackedCollection<?> output = ev.evaluate();
				System.out.println(output.getShape());
				output = output.reshape(shape(8, 8, 4));

				for (int filterIndex = 0; filterIndex < n; filterIndex++) {
					for (int i = 0; i < r - pad; i++) {
						for (int j = 0; j < c - pad; j++) {
							double expected = 0;

							for (int k = 0; k < w; k++) {
								for (int l = 0; l < w; l++) {
									expected += input.toDouble(input.getShape().index(i + k, j + l)) * filter.toDouble(filter.getShape().index(filterIndex, k, l));
								}
							}

							double actual = output.toDouble(output.getShape().index(i, j, filterIndex));

							System.out.println("PackedCollectionMapTests: " + expected + " vs " + actual);
							Assert.assertEquals(expected, actual, 0.0001);
						}
					}
				}

				output.clear();
				conv.get().into(output.traverseEach()).evaluate();

				for (int filterIndex = 0; filterIndex < n; filterIndex++) {
					for (int i = 0; i < r - pad; i++) {
						for (int j = 0; j < c - pad; j++) {
							double expected = 0;

							for (int k = 0; k < w; k++) {
								for (int l = 0; l < w; l++) {
									expected += input.toDouble(input.getShape().index(i + k, j + l)) * filter.toDouble(filter.getShape().index(filterIndex, k, l));
								}
							}

							double actual = output.toDouble(output.getShape().index(i, j, filterIndex));

							System.out.println("PackedCollectionMapTests: " + expected + " vs " + actual);
							Assert.assertEquals(expected, actual, 0.0001);
						}
					}
				}
			});
		} finally {
			ExpressionComputation.enableDynamicComputation = enableDynamic;
		}
	}

	@Test
	public void enumerateReduceEnumerate() {
		int r = 12;
		int c = 16;
		int d = 1;
		int w = 2;

		PackedCollection<?> input = tensor(shape(r, c, d)).pack();
		input.fill(pos -> (3 + (pos[0] % 3) * 0.75) - (3 + (pos[1] % 5) * 1.25));

		HardwareOperator.verboseLog(() -> {
			CollectionProducer<PackedCollection<?>> pool =
					c(p(input)).enumerate(1, w)
							.enumerate(1, w)
							.traverse(2)
							.reduce(v ->
									enumerate(shape(1, 1, w, w, 1), v)
											.traverse(1).reduce(slice -> max(slice)));
			System.out.println(pool.getShape());

			PackedCollection<?> output = pool.get().evaluate();
			System.out.println(output.getShape());

			int r2 = r / w;
			int c2 = c / w;

			for (int copy = 0; copy < d; copy++) {
				for (int i = 0; i < r2; i++) {
					for (int j = 0; j < c2; j++) {
						System.out.println("PackedCollectionMapTests: " + i + ", " + j);

						double expected = -Math.pow(10, 5);

						for (int k = 0; k < w; k++) {
							for (int l = 0; l < w; l++) {
								expected = Math.max(expected, input.valueAt(i * w + k, j * w + l, copy));
							}
						}

						double actual = output.toDouble(output.getShape().index(i, j, copy));

						System.out.println("PackedCollectionMapTests[" + i + ", " + j + "]: Expected " + expected + " vs actual " + actual);
						Assert.assertEquals(expected, actual, 0.0001);
					}
				}
			}
		});
	}

	// @Test
	public void mapConcat() {
		int r = 10;
		int c = 10;
		int s = 3;

		PackedCollection<?> input = tensor(shape(r, c, s)).pack();
		PackedCollection<?> addOn = tensor(shape(s)).pack();

		HardwareOperator.verboseLog(() -> {
			CollectionProducer<PackedCollection<?>> conv = traverse(2, p(input))
					.map(v -> concat((Producer) v, p(addOn)));
			System.out.println(conv.getShape());

			PackedCollection<?> output = conv.get().evaluate();
			System.out.println(output.getShape());

			for (int i = 0; i < 10; i++) {
				for (int j = 0; j < 10; j++) {
					for (int k = 0; k < 3; k++) {
						double expected = input.toDouble(input.getShape().index(i, j, k));
						double actual = output.toDouble(output.getShape().index(i, j, k));

						System.out.println("PackedCollectionMapTests: " + expected + " vs " + actual);
						Assert.assertEquals(expected, actual, 0.0001);
					}

					for (int k = 0; k < 3; k++) {
						double expected = addOn.toDouble(addOn.getShape().index(k));
						double actual = output.toDouble(output.getShape().index(i, j, k + 3));

						System.out.println("PackedCollectionMapTests: " + expected + " vs " + actual);
						Assert.assertEquals(expected, actual, 0.0001);
					}
				}
			}
		});
	}
}
