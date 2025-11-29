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

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CollectionProducerComputation;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestFeatures;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * Tests for map, reduce, and enumerate operations on PackedCollections.
 * These tests validate traversal, transformation, and aggregation patterns
 * used in neural network layers and tensor operations.
 */
public class PackedCollectionMapTests implements TestFeatures {

	/**
	 * Tests 2D mapping operation that multiplies each element of a collection by all elements of another collection.
	 * Validates that the outer product pattern (c[i] * d[j]) produces correct results.
	 *
	 * <p>Operation: For each element in collection c (size 5), repeat it and multiply by collection d (size 2)
	 * Expected output: 5x2 matrix where output[i,j] = c[i] * d[j]</p>
	 */
	@Test
	public void map2d() {
		int n = 5;
		int m = 2;

		PackedCollection c = empty(shape(n)).fill(Math::random);
		PackedCollection d = empty(shape(m)).fill(Math::random);
		Supplier<CollectionProducerComputation> product =
				() -> cp(c).each().map(shape(m), v ->
						v.repeat(2).mul(cp(d)));

		Consumer<PackedCollection> valid = output -> {
			Assert.assertEquals(5, output.getShape().length(0));
			Assert.assertEquals(2, output.getShape().length(1));

			for (int i = 0; i < n; i++) {
				for (int j = 0; j < m; j++) {
					assertEquals(c.valueAt(i) * d.valueAt(j), output.valueAt(i, j));
				}
			}
		};

		kernelTest(product, valid);
	}

	/**
	 * Tests 3D tensor multiplication with broadcasting.
	 * Validates element-wise multiplication of a 3D tensor (8x3x3) with a 2D filter (3x3).
	 *
	 * <p>Operation: Traverse the first dimension of input, multiply each (3x3) slice by the filter.
	 * Expected: output[i,j,k] = input[i,j,k] * filter[j,k] for all i in [0,8)</p>
	 *
	 * <p>This pattern is common in convolutional layers where filters are applied across feature maps.</p>
	 */
	@Test
	public void map3d() {
		PackedCollection input = tensor(shape(8, 3, 3)).pack();
		PackedCollection filter = tensor(shape(3, 3)).pack();

		Supplier<Producer<PackedCollection>> product =
				() -> traverse(1, p(input)).multiply(repeat(8, p(filter)));

		Consumer<PackedCollection> valid = output -> {
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
		};

		kernelTest(product, valid);
	}

	/**
	 * Tests scaling operations using repeat and traversal.
	 * Validates that timeline values can be scaled by repeating scale factors.
	 *
	 * <p>Currently disabled (@Test commented out) - may need investigation.</p>
	 */
	// @Test
	public void scale() {
		PackedCollection timeline = new PackedCollection(shape(10), 1);
		IntStream.range(0, 10).forEach(i -> timeline.set(i, i + 1));

		PackedCollection scale = new PackedCollection(shape(5), 1);
		IntStream.range(0, 5).forEach(i -> scale.set(i, i + 2));
		log(Arrays.toString(scale.toArray(0, 5)));

		verboseLog(() -> {
			Producer<PackedCollection> repeated = c(p(scale)).traverse(1).repeat(10);

			Producer<PackedCollection> repeatedTimeline = c(p(timeline)).traverse(0).repeat(5);

			Evaluable<PackedCollection> ev = multiply(traverseEach(repeated), traverseEach(repeatedTimeline)).get();
			PackedCollection destination = ev.evaluate();
			log(destination.getShape());
			log(Arrays.toString(destination.toArray(0, 10)));
			log(Arrays.toString(destination.toArray(10, 10)));

			assertEquals(8, destination.toDouble(3));
			assertEquals(12, destination.toDouble(13));
		});
	}

	/**
	 * Tests simple reduce operation that adds two collections for each traversed slice.
	 * Validates that map can produce output with arbitrary transformations independent of input.
	 *
	 * <p>Operation: Traverse input (8x3x3), for each slice produce source + filter (both 3x1).
	 * Expected: output[i,j,k] = source[j,k] + filter[j,k], independent of input values.</p>
	 *
	 * <p>This tests that mapped operations can ignore the traversed input and produce fixed results.</p>
	 */
	@Test
	public void simpleReduce() {
		PackedCollection input = tensor(shape(8, 3, 3)).pack();
		PackedCollection source = tensor(shape(3, 1)).pack();
		PackedCollection filter = tensor(shape(3, 1)).pack();

		Supplier<Producer<PackedCollection>> product =
				() -> traverse(1, p(input)).map(shape(3, 1), v -> add(p(source), p(filter)));

		Consumer<PackedCollection> valid = output -> {
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
		};

		kernelTest(product, valid);
	}

	/**
	 * Tests reduction using sum operation across tensor slices.
	 * Validates that traversal + reduce correctly aggregates values.
	 *
	 * <p>Operation: For each of 8 slices (each 6 elements), compute sum.
	 * Expected: output[i] = sum of input[i, 0:6]</p>
	 */
	@Test
	public void sumByReduce() {
		PackedCollection input = tensor(shape(8, 6)).pack();

		verboseLog(() -> {
			CollectionProducer product = traverse(1, p(input)).reduce(v -> v.sum());
			PackedCollection output = product.get().evaluate();
			log(output.getShape());

			Assert.assertEquals(8, output.getShape().length(0));
			Assert.assertEquals(1, output.getShape().length(1));

			for (int i = 0; i < 8; i++) {
				double expected = 0;

				for (int j = 0; j < 6; j++) {
					expected += input.toDouble(input.getShape().index(i, j));
				}

				if (verboseLogs)
					log(expected + " vs " + output.toDouble(output.getShape().index(i, 0)));
				Assert.assertEquals(expected, output.toDouble(output.getShape().index(i, 0)), 0.0001);
			}
		});
	}

	@Test
	public void sumReduceOneByOne() {
		int size = 1;
		int count = 3;

		PackedCollection input = tensor(shape(size, size, count)).pack();

		verboseLog(() -> {
			CollectionProducer enumerated = enumerate(shape(size, size, 1), p(input));
			CollectionProducer sum = enumerated.traverse(1).reduce(slice -> sum(slice));
			PackedCollection output = sum.get().evaluate();
			log(output.getShape());

			Assert.assertEquals(count, output.getShape().length(0));
			Assert.assertEquals(1, output.getShape().length(1));

			for (int i = 0; i < count; i++) {
				double expected = 0;

				for (int x = 0; x < size; x++) {
					for (int y = 0; y < size; y++) {
						expected = expected + input.valueAt(x, y, i);
					}
				}

				if (verboseLogs)
					log(expected + " vs " + output.valueAt(i, 0));
				Assert.assertEquals(expected, output.valueAt(i, 0), 0.0001);
			}
		});
	}

	@Test
	public void sumReduce2d() {
		int size = 4;
		int count = 3;

		PackedCollection input = tensor(shape(size, size, count)).pack();

		verboseLog(() -> {
			CollectionProducer enumerated = enumerate(shape(size, size, 1), p(input));
			CollectionProducer sum = enumerated.traverse(1).reduce(slice -> sum(slice));
			PackedCollection output = sum.get().evaluate();
			log(output.getShape());

			Assert.assertEquals(count, output.getShape().length(0));
			Assert.assertEquals(1, output.getShape().length(1));

			for (int i = 0; i < count; i++) {
				double expected = 0;

				for (int x = 0; x < size; x++) {
					for (int y = 0; y < size; y++) {
						expected = expected + input.valueAt(x, y, i);
					}
				}

				if (verboseLogs)
					log(expected + " vs " + output.valueAt(i, 0));
				Assert.assertEquals(expected, output.valueAt(i, 0), 0.0001);
			}
		});
	}

	@Test
	public void maxReduce() {
		int size = 4;
		int count = 3;

		PackedCollection input = tensor(shape(size, size, count)).pack();

		verboseLog(() -> {
			CollectionProducer enumerated = enumerate(shape(size, size, 1), p(input));
			CollectionProducer max = enumerated.traverse(1).max();
			PackedCollection output = max.get().evaluate().reshape(shape(count, 1));
			log(output.getShape());

			Assert.assertEquals(count, output.getShape().length(0));
			Assert.assertEquals(1, output.getShape().length(1));

			for (int i = 0; i < count; i++) {
				double expected = -1;

				for (int x = 0; x < size; x++) {
					for (int y = 0; y < size; y++) {
						expected = Math.max(input.valueAt(x, y, i), expected);
					}
				}

				if (verboseLogs)
					log(expected + " vs " + output.valueAt(i, 0));
				Assert.assertEquals(expected, output.valueAt(i, 0), 0.0001);
			}
		});
	}

	@Test
	public void mapReduce() {
		PackedCollection input = tensor(shape(8, 3, 3)).pack();
		PackedCollection filter = tensor(shape(3, 3)).pack();

		Supplier<Producer<PackedCollection>> product =
				() -> traverse(1, p(input))
					.map(v -> v.multiply(p(filter)))
					.reduce(v -> v.sum());

		Consumer<PackedCollection> validate = output -> {
			for (int i = 0; i < 8; i++) {
				double expected = 0;

				for (int j = 0; j < 3; j++) {
					for (int k = 0; k < 3; k++) {
						expected += input.toDouble(input.getShape().index(i, j, k)) * filter.toDouble(filter.getShape().index(j, k));
					}
				}

				if (verboseLogs)
					log(expected + " vs " + output.toDouble(output.getShape().index(i, 0)));
				Assert.assertEquals(expected, output.toDouble(output.getShape().index(i, 0)), 0.0001);
			}
		};

		kernelTest(product, validate);
	}

	/**
	 * Tests map-repeat-reduce pattern used in matrix multiplication and fully connected layers.
	 * Validates that each row of input can be repeated, multiplied by filter rows, and reduced.
	 *
	 * <p>Operation: For input (8x3) and filter (2x3):
	 * 1. Traverse input to get 8 rows of size 3
	 * 2. Repeat each row 2 times
	 * 3. Multiply by filter (broadcasting filter[j] to each repeated copy)
	 * 4. Reduce by summing across last dimension
	 * Expected: output[i,j] = sum(input[i,k] * filter[j,k]) for k in [0,3)</p>
	 *
	 * <p>This is mathematically equivalent to matrix multiplication: output = input @ filter.T</p>
	 */
	@Test
	public void mapRepeatReduce() {
		int r = 8;
		int c = 3;
		int w = 2;

		PackedCollection input = tensor(shape(r, c)).pack();
		PackedCollection filter = tensor(shape(w, c)).pack();

		Supplier<CollectionProducer> product =
				() -> traverse(1, p(input))
						.repeat(w)
						.multiply(cp(filter))
						.sum();

		Consumer<PackedCollection> validate = output -> {
			for (int i = 0; i < r; i++) {
				for (int j = 0; j < w; j++) {
					double expected = 0;

					for (int k = 0; k < c; k++) {
						expected += input.valueAt(i, k) * filter.valueAt(j, k);
					}

					if (verboseLogs)
						log(expected + " vs " + output.valueAt(i, j, 0));
					Assert.assertEquals(expected, output.valueAt(i, j, 0), 0.0001);
				}
			}
		};

		kernelTest(product, validate);
	}

	@Test
	public void mapEnumerate() {
		int w = 128;
		int h = 12;
		int d = 64;

		TraversalPolicy xShape = shape(h, d);
		TraversalPolicy inShape = shape(w, h, d);
		TraversalPolicy outputShape = shape(h, w);

		PackedCollection x = new PackedCollection(xShape);
		PackedCollection in = new PackedCollection(inShape);

		x.fill(pos -> Math.random());
		in.fill(pos -> Math.random());

		Producer<PackedCollection> o =
				c(p(in)).traverse(1)
						.map(v -> v.multiply(p(x)))
						.traverse(2).sum()
						.reshape(shape(w, h))
						.enumerate(1, 1)
						.reshape(outputShape);
		PackedCollection out = o.get().evaluate();

		for (int n = 0; n < h; n++) {
			for (int t = 0; t < w; t++) {
				double total = 0.0;

				for (int i = 0; i < d; i++) {
					total += x.valueAt(n, i) * in.valueAt(t, n, i);
				}

				if (verboseLogs)
					log("[" + t + "]: " + total + " vs " + out.valueAt(n, t));
				assertEquals(total, out.valueAt(n, t));
			}
		}
	}

	@Test
	public void enumerateMapSmall() {
		int r = 3;
		int c = 3;
		int w = 2;
		int s = 1;
		int pad = 1;

		PackedCollection input = tensor(shape(r, c)).pack();
		PackedCollection filter = tensor(shape(w, w)).pack();

		input.fill(pos -> pos[0] + pos[1] * 0.1);
		filter.fill(pos -> 1.0);

		verboseLog(() -> {
			CollectionProducer conv = c(p(input))
					.enumerate(1, w, s)
					.enumerate(1, w, s)
					.traverse(2)
					.map(v -> v.multiply(p(filter)));
			log(conv.getShape());

			PackedCollection output = conv.get().evaluate();
			log(output.getShape());

			for (int i = 0; i < r - pad; i++) {
				for (int j = 0; j < c - pad; j++) {
					log(i + ", " + j);

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

						log("]");
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

		PackedCollection input = tensor(shape(r, c)).pack();
		PackedCollection filter = tensor(shape(w, w)).pack();

		verboseLog(() -> {
			CollectionProducer conv = c(p(input))
					.enumerate(1, w, s)
					.enumerate(1, w, s)
					.traverse(2)
					.map(v ->
							v.multiply(p(filter)));
			log(conv.getShape());

			PackedCollection output = conv.get().evaluate();
			log(output.getShape());

			for (int i = 0; i < r - pad; i++) {
				for (int j = 0; j < c - pad; j++) {
					log("PackedCollectionMapTests: " + i + ", " + j);

					for (int k = 0; k < w; k++) {
						for (int l = 0; l < w; l++) {
							double expected = input.toDouble(input.getShape().index(i + k, j + l)) * filter.toDouble(filter.getShape().index(k, l));
							double actual = output.toDouble(output.getShape().index(i, j, k, l));

							if (verboseLogs)
								log("\tPackedCollectionMapTests: " + expected + " vs " + actual);
							Assert.assertEquals(expected, actual, 0.0001);
						}
					}
				}
			}
		});
	}

	// @Test
	public void enumerateMapTraverseEach() {
		int r = 10;
		int c = 10;
		int w = 3;
		int s = 1;
		int pad = 2;

		PackedCollection input = tensor(shape(r, c)).pack();
		PackedCollection filter = tensor(shape(w, w)).pack();

		verboseLog(() -> {
			CollectionProducer conv = c(p(input))
					.enumerate(1, w, s)
					.enumerate(1, w, s)
					.traverse(2)
					.map(v ->
							v.traverseEach().multiply(traverseEach(p(filter))));
			log(conv.getShape());

			PackedCollection output = conv.get().evaluate();
			log(output.getShape());

			for (int i = 0; i < r - pad; i++) {
				for (int j = 0; j < c - pad; j++) {
					log("PackedCollectionMapTests: " + i + ", " + j);

					for (int k = 0; k < w; k++) {
						for (int l = 0; l < w; l++) {
							double expected = input.toDouble(input.getShape().index(i + k, j + l)) * filter.toDouble(filter.getShape().index(k, l));
							double actual = output.toDouble(output.getShape().index(i, j, k, l));

							log("\tPackedCollectionMapTests: " + expected + " vs " + actual);
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

		PackedCollection input = tensor(shape(r, c)).pack();
		PackedCollection filter = tensor(shape(w, w)).pack();

		verboseLog(() -> {
			CollectionProducer conv = c(p(input))
					.enumerate(1, w, s)
					.enumerate(1, w, s)
					.traverse(2)
					.multiply(p(filter))
					.sum();

			PackedCollection output = conv.get().evaluate();
			log(output.getShape());

			for (int i = 0; i < 8; i++) {
				for (int j = 0; j < 8; j++) {
					double expected = 0;

					for (int k = 0; k < 3; k++) {
						for (int l = 0; l < 3; l++) {
							expected += input.toDouble(input.getShape().index(i + k, j + l)) * filter.toDouble(filter.getShape().index(k, l));
						}
					}

					double actual = output.toDouble(output.getShape().index(i, j, 0));

					if (verboseLogs)
						log(expected + " vs " + actual);
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

		PackedCollection input = tensor(shape(r, c)).pack();
		PackedCollection filter = tensor(shape(2, w, w)).pack();

		input.fill(pos -> pos[0] + pos[1] * 0.1);

		IntStream.range(0, 5).forEach(n -> {
			verboseLog(() -> {
				CollectionProducer conv = c(p(input))
						.enumerate(1, w, s)
						.enumerate(1, w, s)
						.traverse(2)
						.map(shape(2, w, w), v ->
								v.repeat(2).multiply(traverseEach(p(filter))));  // Map over windows, repeat and multiply

				log("Input shape: " + input.getShape());
				log("Filter shape: " + filter.getShape());
				log("Conv shape: " + conv.getShape());
				PackedCollection output = conv.get().evaluate();
				log("Output shape: " + output.getShape());

				// Print some actual values
				log("Input[0,0] = " + input.valueAt(0, 0));
				log("Filter[0,0,0] = " + filter.valueAt(0, 0, 0));
				log("Output[0,0,0,0,0] = " + output.valueAt(0, 0, 0, 0, 0));

				for (int copy = 0; copy < 2; copy++) {
					for (int i = 0; i < r - pad; i++) {
						for (int j = 0; j < c - pad; j++) {
							log("PackedCollectionMapTests: " + i + ", " + j);

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

								log("]");
							}
						}
					}
				}
			});
		});
	}

	@Test
	public void enumerateRepeatMapReduceSmall() {
//		Known to fail
//		int r = 8;
//		int c = 8;
//		int w = 3, s = 1, pad = 2;
//		int n = 4;

		int r = 3;
		int c = 3;
		int w = 2, s = 1, pad = 1;
		int n = 3;

		PackedCollection input = tensor(shape(r, c)).pack();
		PackedCollection filter = tensor(shape(n, w, w)).pack();

		verboseLog(() -> {
			CollectionProducer conv = c(p(input))
					.enumerate(1, w, s)
					.enumerate(1, w, s)
					.traverse(2)
					.repeat(n)
					.multiply(cp(filter))
					.sum();

			Evaluable<PackedCollection> ev = conv.get();
			PackedCollection output = ev.evaluate();
			output = output.reshape(shape(r - pad, c - pad, n));

			print(4, 3, output);

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

						if (verboseLogs)
							log(expected + " vs " + actual);
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

						if (verboseLogs)
							log(expected + " vs " + actual);
						Assert.assertEquals(expected, actual, 0.0001);
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

		PackedCollection input = tensor(shape(r, c)).pack();
		PackedCollection filter = tensor(shape(n, w, w)).pack();

		verboseLog(() -> {
			CollectionProducer conv = c(p(input))
					.enumerate(1, w, s)
					.enumerate(1, w, s)
					.traverse(2)
					.repeat(n)
					.multiply(cp(filter))
					.sum();

			Evaluable<PackedCollection> ev = conv.get();
			PackedCollection output = ev.evaluate();
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

						if (verboseLogs)
							log(expected + " vs " + actual);
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

						if (verboseLogs)
							log(expected + " vs " + actual);
						Assert.assertEquals(expected, actual, 0.0001);
					}
				}
			}
		});
	}

	@Test
	public void enumerateRepeatReduce() {
		int r = 10;
		int c = 10;
		int w = 3;
		int s = 1;
		int pad = 2;

		int n = 4;

		PackedCollection input = tensor(shape(r, c)).pack();
		PackedCollection filter = tensor(shape(n, w, w)).pack();

		verboseLog(() -> {
			CollectionProducer conv = c(p(input))
					.enumerate(1, w, s)
					.enumerate(1, w, s)
					.traverse(2)
					.repeat(n)
					.traverse(2)
					.multiply(cp(filter).repeat(c - pad).traverse(0).repeat(r - pad).traverse(2))
					.traverse()
					.reduce(v -> v.sum());
			log(conv.getShape());

			Evaluable<PackedCollection> ev = conv.get();
			PackedCollection output = ev.evaluate();
			log(output.getShape());
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

						log("PackedCollectionMapTests: " + expected + " vs " + actual);
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

						if (verboseLogs)
							log(expected + " vs " + actual);
						Assert.assertEquals(expected, actual, 0.0001);
					}
				}
			}
		});
	}

	@Test
	public void enumerateReduceEnumerate() {
		int r = 12;
		int c = 16;
		int d = 1;
		int w = 2;

		PackedCollection input = tensor(shape(r, c, d)).pack();
		input.fill(pos -> (3 + (pos[0] % 3) * 0.75) - (3 + (pos[1] % 5) * 1.25));

		verboseLog(() -> {
			CollectionProducer pool =
					c(p(input))
					.enumerate(2, 1)
					.enumerate(2, w)
					.enumerate(2, w)
					.traverse(3)
					.max();
			log(pool.getShape());

			int r2 = r / w;
			int c2 = c / w;

			PackedCollection output = pool.get().evaluate().reshape(r2, c2, d);
			log(output.getShape());

			for (int copy = 0; copy < d; copy++) {
				for (int i = 0; i < r2; i++) {
					for (int j = 0; j < c2; j++) {
						log(i + ", " + j);

						double expected = -Math.pow(10, 5);

						for (int k = 0; k < w; k++) {
							for (int l = 0; l < w; l++) {
								expected = Math.max(expected, input.valueAt(i * w + k, j * w + l, copy));
							}
						}

						double actual = output.valueAt(i, j, copy);

						if (verboseLogs)
							log("[" + i + ", " + j + "]: Expected " + expected + " vs actual " + actual);
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

		PackedCollection input = tensor(shape(r, c, s)).pack();
		PackedCollection addOn = tensor(shape(s)).pack();

		verboseLog(() -> {
			CollectionProducer conv = traverse(2, p(input))
					.map(v ->
							concat(shape(1, 1, 2 * s), v, p(addOn)));
			log(conv.getShape());

			PackedCollection output = conv.get().evaluate();
			log(output.getShape());

			for (int i = 0; i < 10; i++) {
				for (int j = 0; j < 10; j++) {
					for (int k = 0; k < 3; k++) {
						double expected = input.toDouble(input.getShape().index(i, j, k));
						double actual = output.toDouble(output.getShape().index(i, j, k));

						if (verboseLogs)
							log(expected + " vs " + actual);
						Assert.assertEquals(expected, actual, 0.0001);
					}

					for (int k = 0; k < 3; k++) {
						double expected = addOn.toDouble(addOn.getShape().index(k));
						double actual = output.toDouble(output.getShape().index(i, j, k + 3));

						if (verboseLogs)
							log(expected + " vs " + actual);
						Assert.assertEquals(expected, actual, 0.0001);
					}
				}
			}
		});
	}
}
