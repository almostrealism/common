/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.algebra.test;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.algebra.computations.WeightedSumComputation;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.function.Supplier;

/**
 * Tests weighted sums whose group is large enough to be summed by a native loop rather than by
 * a single expression (see {@link WeightedSumComputation#loopThreshold}). For each shape that
 * {@code weightedSum} is used for - a one-dimensional convolution (several members per
 * iteration), a matrix product (one member per iteration) and a two-dimensional convolution
 * (a two-dimensional block of members per iteration) - the looped kernel must produce the
 * values of a host evaluation of the same sum and of the single-expression kernel.
 */
public class WeightedSumLoopTests extends TestSuiteBase {

	/** Largest deviation, relative to the magnitude of the expected value, that is accepted. */
	private static final double TOLERANCE = 1e-4;

	/**
	 * A one-dimensional convolution over 64 channels with a kernel of 7 sums a group of 448
	 * members as a loop over the channels, with the 7 kernel taps of a channel in each
	 * iteration, and matches the host evaluation and the single-expression kernel.
	 */
	@Test(timeout = 120000)
	public void convolutionLoopMatchesReference() {
		int channels = 64;
		int filters = 8;
		int kernel = 7;
		int length = 20;

		PackedCollection input = new PackedCollection(shape(1, 1, channels, length + kernel - 1)).randFill();
		PackedCollection filter = new PackedCollection(shape(1, filters, channels, kernel)).randFill();

		double[] in = input.toArray();
		double[] w = filter.toArray();
		double[] expected = new double[filters * length];
		for (int f = 0; f < filters; f++) {
			for (int t = 0; t < length; t++) {
				double sum = 0;
				for (int c = 0; c < channels; c++) {
					for (int k = 0; k < kernel; k++) {
						sum += in[c * (length + kernel - 1) + t + k] * w[(f * channels + c) * kernel + k];
					}
				}
				expected[f * length + t] = sum;
			}
		}

		assertLoopMatches("convolution", expected, () -> {
			TraversalPolicy resultShape = shape(1, filters, 1, length);
			return weightedSum("convolution",
					resultShape.withRate(1, 1, filters).withRate(2, channels, 1),
					resultShape.withRate(2, channels, 1).withRate(3, kernel, length),
					shape(1, 1, channels, kernel), cp(input), cp(filter));
		});
	}

	/**
	 * A matrix product with an inner dimension of 300 sums a group of 300 members as a loop
	 * with one member per iteration, and matches the host evaluation and the single-expression
	 * kernel.
	 */
	@Test(timeout = 120000)
	public void matrixProductLoopMatchesReference() {
		int m = 6;
		int n = 300;
		int p = 5;

		PackedCollection a = new PackedCollection(shape(m, n)).randFill();
		PackedCollection b = new PackedCollection(shape(n, p)).randFill();

		double[] left = a.toArray();
		double[] right = b.toArray();
		double[] expected = new double[m * p];
		for (int i = 0; i < m; i++) {
			for (int j = 0; j < p; j++) {
				double sum = 0;
				for (int k = 0; k < n; k++) {
					sum += left[i * n + k] * right[k * p + j];
				}
				expected[i * p + j] = sum;
			}
		}

		assertLoopMatches("matrix product", expected, () -> {
			TraversalPolicy resultShape = shape(1, m, 1, p);
			return weightedSum("matmul", resultShape,
					resultShape.withRate(3, n, p), resultShape.withRate(1, 1, m),
					shape(1, 1, n, 1), shape(1, 1, n, 1),
					cp(a.reshape(1, m, n, 1)), cp(b.reshape(1, 1, n, p)));
		});
	}

	/**
	 * A two-dimensional convolution over 32 channels with a 3 by 3 kernel sums a group of 288
	 * members as a loop over the channels, with the 9 taps of a channel in each iteration, and
	 * matches the host evaluation and the single-expression kernel.
	 */
	@Test(timeout = 120000)
	public void convolution2dLoopMatchesReference() {
		int channels = 32;
		int filters = 4;
		int size = 3;
		int height = 6;
		int width = 5;
		int outHeight = height - size + 1;
		int outWidth = width - size + 1;

		PackedCollection input = new PackedCollection(shape(1, 1, channels, height, width)).randFill();
		PackedCollection filter = new PackedCollection(shape(1, filters, channels, size, size)).randFill();

		double[] in = input.toArray();
		double[] w = filter.toArray();
		double[] expected = new double[filters * outHeight * outWidth];
		for (int f = 0; f < filters; f++) {
			for (int y = 0; y < outHeight; y++) {
				for (int x = 0; x < outWidth; x++) {
					double sum = 0;
					for (int c = 0; c < channels; c++) {
						for (int i = 0; i < size; i++) {
							for (int j = 0; j < size; j++) {
								sum += in[(c * height + y + i) * width + x + j] *
										w[((f * channels + c) * size + i) * size + j];
							}
						}
					}
					expected[(f * outHeight + y) * outWidth + x] = sum;
				}
			}
		}

		assertLoopMatches("two-dimensional convolution", expected, () -> {
			TraversalPolicy resultShape = shape(1, filters, 1, outHeight, outWidth);
			return weightedSum("convolution2d",
					resultShape.withRate(1, 1, filters).withRate(2, channels, 1),
					resultShape.withRate(2, channels, 1)
							.withRate(3, size, outHeight).withRate(4, size, outWidth),
					shape(1, 1, channels, size, size), cp(input), cp(filter));
		});
	}

	/**
	 * A looped weighted sum that is an operand of a larger expression still contributes its
	 * value to that expression.
	 */
	@Test(timeout = 120000)
	public void loopInsideLargerExpression() {
		int n = 512;

		PackedCollection a = new PackedCollection(shape(4, n)).randFill();
		PackedCollection b = new PackedCollection(shape(n, 3)).randFill();

		double[] left = a.toArray();
		double[] right = b.toArray();

		TraversalPolicy resultShape = shape(1, 4, 1, 3);
		CollectionProducer product = weightedSum("matmul", resultShape,
				resultShape.withRate(3, n, 3), resultShape.withRate(1, 1, 4),
				shape(1, 1, n, 1), shape(1, 1, n, 1),
				cp(a.reshape(1, 4, n, 1)), cp(b.reshape(1, 1, n, 3)));
		Assert.assertTrue(((WeightedSumComputation) product).isLooped());

		PackedCollection result = product.multiply(2.0).add(1.0).evaluate();

		for (int i = 0; i < 4; i++) {
			for (int j = 0; j < 3; j++) {
				double sum = 0;
				for (int k = 0; k < n; k++) {
					sum += left[i * n + k] * right[k * 3 + j];
				}
				Assert.assertEquals(2.0 * sum + 1.0, result.toDouble(i * 3 + j),
						TOLERANCE * Math.max(1.0, 2.0 * sum + 1.0));
			}
		}
	}

	/** A group smaller than the loop threshold is still summed by a single expression. */
	@Test(timeout = 30000)
	public void smallGroupIsNotLooped() {
		TraversalPolicy resultShape = shape(1, 2, 1, 5);
		CollectionProducer conv = weightedSum("convolution",
				resultShape.withRate(1, 1, 2).withRate(2, 4, 1),
				resultShape.withRate(2, 4, 1).withRate(3, 3, 5),
				shape(1, 1, 4, 3),
				cp(new PackedCollection(shape(1, 1, 4, 7)).randFill()),
				cp(new PackedCollection(shape(1, 2, 4, 3)).randFill()));
		Assert.assertFalse(((WeightedSumComputation) conv).isLooped());
	}

	/**
	 * Asserts that the weighted sum built by {@code sum} is looped, and that both it and the same
	 * sum built with looping disabled evaluate to {@code expected}.
	 */
	private void assertLoopMatches(String label, double[] expected, Supplier<CollectionProducer> sum) {
		CollectionProducer looped = sum.get();
		Assert.assertTrue(label + " should be summed by a loop",
				((WeightedSumComputation) looped).isLooped());
		assertMatches(label + " (loop)", expected, looped.evaluate());

		int threshold = WeightedSumComputation.loopThreshold;
		WeightedSumComputation.loopThreshold = Integer.MAX_VALUE;

		try {
			CollectionProducer single = sum.get();
			Assert.assertFalse(label + " should be summed by a single expression",
					((WeightedSumComputation) single).isLooped());
			assertMatches(label + " (single expression)", expected, single.evaluate());
		} finally {
			WeightedSumComputation.loopThreshold = threshold;
		}
	}

	/** Asserts that every element of {@code actual} is within tolerance of its counterpart. */
	private void assertMatches(String label, double[] expected, PackedCollection actual) {
		double[] values = actual.toArray();
		Assert.assertEquals(label + " (element count)", expected.length, values.length);

		for (int i = 0; i < expected.length; i++) {
			Assert.assertEquals(label + " element " + i, expected[i], values[i],
					TOLERANCE * Math.max(1.0, Math.abs(expected[i])));
		}
	}
}
