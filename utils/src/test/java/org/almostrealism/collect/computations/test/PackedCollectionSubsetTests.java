/*
 * Copyright 2024 Michael Murray
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

import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Tensor;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.util.TestFeatures;
import org.almostrealism.util.TestUtils;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;

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

		PackedCollection input = t.pack();
		TraversalPolicy inputShape = input.getShape();
		System.out.println("PackedCollectionSubsetTests: input shape = " + inputShape);

		TraversalPolicy subsetShape = shape(w, h, d);

		int outIndex = 1;
		int pos[] = subsetShape.position(outIndex);
		int index = inputShape.index(x0 + pos[0], y0 + pos[1], z0 + pos[2]);
		System.out.println("Position " + outIndex + " maps to " + index + " " + Arrays.toString(inputShape.position(index)));
		Assert.assertEquals(433, index);

		verboseLog(() -> {
			CollectionProducer<PackedCollection> producer = subset(shape(w, h, d), p(input), x0, y0, z0);
			Evaluable<PackedCollection> ev = producer.get();
			PackedCollection subset = ev.evaluate();

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

		PackedCollection input = t.pack();
		TraversalPolicy inputShape = input.getShape();
		System.out.println("PackedCollectionSubsetTests: input shape = " + inputShape);

		PackedCollection pc = new PackedCollection(1).traverseEach();
		pc.set(0, (double) x0);

		verboseLog(() -> {
			CollectionProducer<PackedCollection> producer = subset(shape(w), p(input), p(pc));
			Evaluable<PackedCollection> ev = producer.get();
			PackedCollection subset = ev.evaluate();

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

		PackedCollection input = t.pack();
		TraversalPolicy inputShape = input.getShape();
		System.out.println("PackedCollectionSubsetTests: input shape = " + inputShape);

		PackedCollection pc = new PackedCollection(2).traverseEach();
		pc.set(0, (double) x0);
		pc.set(1, (double) y0);

		verboseLog(() -> {
			CollectionProducer<PackedCollection> producer = subset(shape(w, h), p(input), p(pc));
			Evaluable<PackedCollection> ev = producer.get();
			PackedCollection subset = ev.evaluate();

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

		PackedCollection input = t.pack();
		TraversalPolicy inputShape = input.getShape();
		System.out.println("PackedCollectionSubsetTests: input shape = " + inputShape);

		PackedCollection pc = new PackedCollection(3).traverseEach();
		pc.set(0, (double) x0);
		pc.set(1, (double) y0);
		pc.set(2, (double) z0);

		verboseLog(() -> {
			CollectionProducer<PackedCollection> producer = subset(shape(w, h, d), p(input), p(pc));
			Evaluable<PackedCollection> ev = producer.get();
			PackedCollection subset = ev.evaluate();

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
		PackedCollection filter = f.pack();

		Tensor<Double> t = tensor(shape(10, 10), (int[] c) -> {
			int x = c[0], y = c[1];
			return x >= x0 && x < x1 && y >= y0 && y < y1;
		});

		PackedCollection input = t.pack();

		verboseLog(() -> {
			CollectionProducer<PackedCollection> subset = subset(shape(size, size), p(input), x0, y0);
			Producer<PackedCollection> product = multiply(traverseEach(cp(filter)), traverseEach(subset));
//			Producer<PackedCollection> product = relativeMultiply(p(filter), subset, null);
			Evaluable<PackedCollection> ev = product.get();
			PackedCollection result = ev.evaluate();

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
		PackedCollection originalInput = new PackedCollection(shape(10, 20));
		originalInput.fill(pos -> Math.random());

		PackedCollection input = new PackedCollection(shape(10, 20));
		input.fill(pos -> originalInput.valueAt(pos));

		PackedCollection filter = new PackedCollection(shape(1, 20));
		filter.fill(pos -> Math.random());

		CollectionProducer<PackedCollection> in = c(p(input));
		CollectionProducer<PackedCollection> subset = subset(shape(1, 20), in, 4, 0).traverseEach();

		verboseLog(() -> {
			a(subset, subset.add(c(p(filter))).traverseEach()).get().run();
		});

		for (int i = 0; i < 20; i++) {
			Assert.assertEquals(
					input.valueAt(4, i),
					originalInput.valueAt(0, i) + filter.valueAt(0, i),
					0.0001);
		}
	}

	@Test
	public void subsetHalves() {
		int batchSize = 1;
		int heads = 2;
		int seqLen = 8;
		int rotaryDim = 16;

		int halfDim = rotaryDim / 2;

		PackedCollection input = new PackedCollection(shape(batchSize, heads, seqLen, rotaryDim)).randFill();

		// Extract first half (x1)
		CollectionProducer<PackedCollection> x1 =
				cp(input).subset(shape(batchSize, heads, seqLen, halfDim),
						0, 0, 0, 0);
		PackedCollection result1 = x1.evaluate();

		// Extract second half (x2)
		CollectionProducer<PackedCollection> x2 =
				cp(input).subset(shape(batchSize, heads, seqLen, halfDim),
						0, 0, 0, halfDim).minus();
		PackedCollection result2 = x2.evaluate();

		for (int h = 0; h < heads; h++) {
			for (int s = 0; s < seqLen; s++) {
				for (int d = 0; d < halfDim; d++) {
					assertEquals(input.valueAt(0, h, s, d), result1.valueAt(0, h, s, d));
					assertEquals(-input.valueAt(0, h, s, d + halfDim), result2.valueAt(0, h, s, d));
				}
			}
		}
	}

	@Test
	public void subsetHalfPad2d() {
		int seqLen = 16;
		int dim = 16;
		int halfDim = dim / 2;

		PackedCollection input = new PackedCollection(shape(seqLen, dim)).randFill();

		CollectionProducer<PackedCollection> x =
				cp(input).subset(shape(seqLen, halfDim), 0, 0);
		CollectionProducer<PackedCollection> padded =
				pad(shape(seqLen, dim), x, 0, halfDim);

		PackedCollection result = padded.evaluate();
		result.traverse(1).print();

		for (int s = 0; s < seqLen; s++) {
			for (int d = 0; d < dim; d++) {
				double actual = result.valueAt(s, d);

				if (d >= halfDim) {
					double expected = input.valueAt(s, d % halfDim);
					assertEquals(expected, actual);
				} else {
					assertEquals(0, actual);
				}
			}
		}
	}

	/**
	 * This test groups together a few of the other tests to ensure
	 * that automatic compiled program reuse does not cause issues.
	 *
	 * @see #subsetHalfPad2dSum1()
	 * @see #subsetHalfPad2dSum2()
	 * @see #subsetHalfPad2dSum3()
	 */
	@Test
	public void subsetHalfPad2dSumAll() {
		if (testDepth < 3) return;
		if (testProfileIs(TestUtils.PIPELINE)) return;

		subsetHalfPad2dSum1();
		subsetHalfPad2dSum2();
		subsetHalfPad2dSum3();
	}

	@Test
	public void subsetHalfPad2dSum1() {
		int seqLen = 16;
		int dim = 16;
		int halfDim = dim / 2;

		PackedCollection input = new PackedCollection(shape(seqLen, dim)).randFill();
		PackedCollection plus = new PackedCollection(shape(seqLen, dim)).randFill();

		CollectionProducer<PackedCollection> x =
				cp(input).subset(shape(seqLen, halfDim), 0, halfDim);
		CollectionProducer<PackedCollection> padded =
				pad(shape(seqLen, dim), x, 0, 0);

		PackedCollection result = padded.add(cp(plus)).evaluate();

		for (int s = 0; s < seqLen; s++) {
			for (int d = 0; d < dim; d++) {
				double actual = result.valueAt(s, d);

				if (d < halfDim) {
					double expected = input.valueAt(s, d + halfDim);
					assertEquals(plus.valueAt(s, d) + expected, actual);
				} else {
					assertEquals(plus.valueAt(s, d), actual);
				}
			}
		}
	}

	@Test
	public void subsetHalfPad2dSum2() {
		int seqLen = 16;
		int dim = 16;
		int halfDim = dim / 2;

		PackedCollection input = new PackedCollection(shape(seqLen, dim)).randFill();

		CollectionProducer<PackedCollection> x =
				cp(input).subset(shape(seqLen, halfDim), 0, halfDim);
		CollectionProducer<PackedCollection> padded =
				pad(shape(seqLen, dim), x, 0, 0);

		PackedCollection result = padded.add(cp(input)).evaluate();

		for (int s = 0; s < seqLen; s++) {
			for (int d = 0; d < dim; d++) {
				double actual = result.valueAt(s, d);
				double plus = input.valueAt(s, d);

				if (d < halfDim) {
					double expected = input.valueAt(s, d + halfDim);
					assertEquals(plus + expected, actual);
				} else {
					assertEquals(plus, actual);
				}
			}
		}
	}

	@Test
	public void subsetHalfPad2dSum3() {
		int seqLen = 16;
		int dim = 16;
		int halfDim = dim / 2;

		PackedCollection input = new PackedCollection(shape(seqLen, dim)).randFill();

		CollectionProducer<PackedCollection> x =
				cp(input).subset(shape(seqLen, halfDim), 0, halfDim);
		CollectionProducer<PackedCollection> padLeft =
				pad(shape(seqLen, dim), x, 0, 0);
		CollectionProducer<PackedCollection> padRight =
				pad(shape(seqLen, dim), x, 0, halfDim);

		PackedCollection result = padLeft.add(padRight).evaluate();

		for (int s = 0; s < seqLen; s++) {
			for (int d = 0; d < dim; d++) {
				double actual = result.valueAt(s, d);
				double expected = input.valueAt(s, halfDim + d % halfDim);
				assertEquals(expected, actual);
			}
		}
	}

	@Test
	public void subsetHalfConcat1d() {
		int dim = 64;
		int halfDim = dim / 2;

		PackedCollection input = new PackedCollection(shape(dim)).randFill();
		CollectionProducer<PackedCollection> x1 = cp(input).subset(shape(halfDim), 0);
		CollectionProducer<PackedCollection> x2 = cp(input).subset(shape(halfDim), halfDim);

		CollectionProducer<PackedCollection> concat = concat(0, x2, x1);
		PackedCollection result = concat.evaluate();

		for (int d = 0; d < dim; d++) {
			double actual = result.valueAt(d);

			if (d < halfDim) {
				double expected = input.valueAt(d + halfDim);
				assertEquals(expected, actual);
			} else {
				double expected = input.valueAt(d - halfDim);
				assertEquals(expected, actual);
			}
		}
	}

	@Test
	public void subsetHalfConcat2d() {
		int seqLen = 16;
		int dim = 16;
		int halfDim = dim / 2;

		PackedCollection input = new PackedCollection(shape(seqLen, dim)).randFill();

		CollectionProducer<PackedCollection> x1 =
				cp(input).subset(shape(seqLen, halfDim), 0, 0);
		CollectionProducer<PackedCollection> x2 =
				cp(input).subset(shape(seqLen, halfDim), 0, halfDim);

		CollectionProducer<PackedCollection> concat = concat(1, x2, x1);
		PackedCollection result = concat.evaluate();

		for (int s = 0; s < seqLen; s++) {
			for (int d = 0; d < dim; d++) {
				double actual = result.valueAt(s, d);

				if (d < halfDim) {
					double expected = input.valueAt(s, d + halfDim);
					assertEquals(expected, actual);
				} else {
					double expected = input.valueAt(s, d - halfDim);
					assertEquals(expected, actual);
				}
			}
		}
	}
}
