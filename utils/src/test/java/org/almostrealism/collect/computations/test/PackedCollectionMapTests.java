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
import org.almostrealism.hardware.cl.HardwareOperator;
import org.almostrealism.util.TestFeatures;
import org.junit.Assert;
import org.junit.Test;

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
	}

	// @Test
	public void enumerateMap2() {
		int r = 10;
		int c = 10;
		int w = 3;
		int s = 1;
		int pad = 2;

		PackedCollection<?> input = tensor(shape(r, c)).pack();
		PackedCollection<?> filter = tensor(shape(2, w, w)).pack();

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
	}

	@Test
	public void enumerateMapReduce() {
		int r = 10;
		int c = 10;
		int w = 3;
		int s = 1;

		PackedCollection<?> input = tensor(shape(r, c)).pack();
		PackedCollection<?> filter = tensor(shape(w, w)).pack();

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
										filter.toDouble(filter.getShape().index(copy, k, l));;
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

		HardwareOperator.verboseLog(() -> {
			CollectionProducer<PackedCollection<?>> conv = c(p(input))
					.enumerate(1, w, s)
					.enumerate(1, w, s)
					.traverse(2)
					.expand(n, v -> v.repeat(n).multiply(p(filter)))
					.traverse()
					.reduce(v -> v.sum());
			System.out.println(conv.getShape());

			PackedCollection<?> output = conv.get().evaluate();
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
