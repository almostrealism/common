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
import io.almostrealism.profile.OperationProfileNode;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.algebra.computations.WeightedSumComputation;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.mem.MemoryDataAdapter;
import org.almostrealism.util.TestSuiteBase;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;

import java.util.ArrayList;
import java.util.List;
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
	 * Collections allocated by a test, destroyed after it. {@link MemoryDataAdapter} disables
	 * finalizer cleanup by default and {@link TestSuiteBase} only clears profiling, so the
	 * native/device buffers these tests own (for example the 3.6-million-element input of
	 * {@link #largeOutputLoopMatchesReference()}) are released here rather than left allocated.
	 */
	private final List<PackedCollection> allocated = new ArrayList<>();

	/**
	 * Destroys the collections the test allocated. {@code destroy()} is idempotent, so a
	 * collection whose memory a computation already released is unaffected.
	 */
	@After
	public void destroyAllocated() {
		for (PackedCollection c : allocated) {
			c.destroy();
		}
		allocated.clear();
	}

	/**
	 * Registers a collection for destruction after the test and returns it, so an allocation
	 * can be tracked inline where it is created.
	 *
	 * @param collection  the collection to destroy after the test
	 * @return the same collection
	 */
	private PackedCollection track(PackedCollection collection) {
		allocated.add(collection);
		return collection;
	}

	/**
	 * A one-dimensional convolution over 64 channels with a kernel of 7 sums a group of 448
	 * members as a loop over the channels, with the 7 kernel taps of a channel in each
	 * iteration, and matches the host evaluation and the single-expression kernel.
	 */
	@Test(timeout = 120000)
	public void convolutionLoopMatchesReference() {
		assertConvolutionLoop("convolution", 64, 8, 7, 20, 7);
	}

	/**
	 * A one-dimensional convolution whose kernel of 16 taps is exactly
	 * {@link WeightedSumComputation#maxUnrolledMembers} sums all 16 taps of a channel in each
	 * iteration of the loop over 32 channels, and matches the host evaluation and the
	 * single-expression kernel.
	 */
	@Test(timeout = 120000)
	public void convolutionKernelAtUnrollLimit() {
		assertConvolutionLoop("convolution (kernel at unroll limit)", 32, 2, 16, 6, 16);
	}

	/**
	 * A one-dimensional convolution whose kernel of 17 taps exceeds
	 * {@link WeightedSumComputation#maxUnrolledMembers} cannot place a whole channel in one
	 * iteration, so its group of 272 members is summed one member per iteration, and matches
	 * the host evaluation and the single-expression kernel.
	 */
	@Test(timeout = 120000)
	public void convolutionKernelBeyondUnrollLimit() {
		assertConvolutionLoop("convolution (kernel beyond unroll limit)", 16, 2, 17, 6, 1);
	}

	/**
	 * A group of exactly {@link WeightedSumComputation#loopThreshold} members is summed by a
	 * loop and matches the host evaluation, while a group one member smaller is summed by a
	 * single expression.
	 */
	@Test(timeout = 120000)
	public void loopThresholdIsInclusive() {
		int n = WeightedSumComputation.loopThreshold;

		PackedCollection a = track(new PackedCollection(shape(2, n)).randFill());
		PackedCollection b = track(new PackedCollection(shape(n, 3)).randFill());
		assertLoopMatches("matmul at threshold",
				matmulReference(a.toArray(), b.toArray(), 2, n, 3),
				() -> matmul(a, b, 2, n, 3));

		PackedCollection c = track(new PackedCollection(shape(2, n - 1)).randFill());
		PackedCollection d = track(new PackedCollection(shape(n - 1, 3)).randFill());
		Assert.assertFalse("a group below the threshold must not be looped",
				((WeightedSumComputation) matmul(c, d, 2, n - 1, 3)).isLooped());
	}

	/**
	 * The looped and single-expression forms of the same sum generate different kernels, so
	 * their signatures must differ; otherwise a kernel cache keyed by signature would reuse
	 * one form's kernel for the other.
	 */
	@Test(timeout = 30000)
	public void loopedSignatureDiffersFromSingleExpression() {
		int n = 300;

		PackedCollection a = track(new PackedCollection(shape(4, n)).randFill());
		PackedCollection b = track(new PackedCollection(shape(n, 3)).randFill());

		WeightedSumComputation looped = (WeightedSumComputation) matmul(a, b, 4, n, 3);
		Assert.assertTrue(looped.isLooped());
		Assert.assertNotNull(looped.signature());
		Assert.assertTrue(looped.signature().contains("{loop:1}"));

		int threshold = WeightedSumComputation.loopThreshold;
		WeightedSumComputation.loopThreshold = Integer.MAX_VALUE;

		try {
			WeightedSumComputation single = (WeightedSumComputation) matmul(a, b, 4, n, 3);
			Assert.assertFalse(single.isLooped());
			Assert.assertNotNull(single.signature());
			Assert.assertFalse(single.signature().contains("{loop:"));
			Assert.assertNotEquals(looped.signature(), single.signature());
		} finally {
			WeightedSumComputation.loopThreshold = threshold;
		}
	}

	/**
	 * Asserts that a one-dimensional convolution of {@code channels} input channels by
	 * {@code filters} filters of {@code kernel} taps, producing {@code length} outputs per
	 * filter, is looped with {@code loopMembers} members per iteration (as recorded in its
	 * signature) and that both kernel forms match a host evaluation.
	 */
	private void assertConvolutionLoop(String label, int channels, int filters, int kernel,
									   int length, int loopMembers) {
		PackedCollection input = track(new PackedCollection(shape(1, 1, channels, length + kernel - 1)).randFill());
		PackedCollection filter = track(new PackedCollection(shape(1, filters, channels, kernel)).randFill());

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

		Supplier<CollectionProducer> conv = () -> {
			TraversalPolicy resultShape = shape(1, filters, 1, length);
			return weightedSum("convolution",
					resultShape.withRate(1, 1, filters).withRate(2, channels, 1),
					resultShape.withRate(2, channels, 1).withRate(3, kernel, length),
					shape(1, 1, channels, kernel), cp(input), cp(filter));
		};

		String signature = ((WeightedSumComputation) conv.get()).signature();
		Assert.assertNotNull(label + " signature", signature);
		Assert.assertTrue(label + " should sum " + loopMembers + " members per iteration",
				signature.endsWith("{loop:" + loopMembers + "}"));

		assertLoopMatches(label, expected, conv);
	}

	/**
	 * Builds the weighted sum for the product of an {@code m} by {@code n} matrix and an
	 * {@code n} by {@code p} matrix, whose group is the inner dimension of {@code n} members.
	 */
	private CollectionProducer matmul(PackedCollection a, PackedCollection b, int m, int n, int p) {
		TraversalPolicy resultShape = shape(1, m, 1, p);
		return weightedSum("matmul", resultShape,
				resultShape.withRate(3, n, p), resultShape.withRate(1, 1, m),
				shape(1, 1, n, 1), shape(1, 1, n, 1),
				cp(a.reshape(1, m, n, 1)), cp(b.reshape(1, 1, n, p)));
	}

	/**
	 * Computes on the host the product of the row-major {@code m} by {@code n} matrix
	 * {@code left} and the row-major {@code n} by {@code p} matrix {@code right}.
	 */
	private static double[] matmulReference(double[] left, double[] right, int m, int n, int p) {
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
		return expected;
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

		PackedCollection a = track(new PackedCollection(shape(m, n)).randFill());
		PackedCollection b = track(new PackedCollection(shape(n, p)).randFill());

		assertLoopMatches("matrix product", matmulReference(a.toArray(), b.toArray(), m, n, p),
				() -> matmul(a, b, m, n, p));
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

		PackedCollection input = track(new PackedCollection(shape(1, 1, channels, height, width)).randFill());
		PackedCollection filter = track(new PackedCollection(shape(1, filters, channels, size, size)).randFill());

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

		PackedCollection a = track(new PackedCollection(shape(4, n)).randFill());
		PackedCollection b = track(new PackedCollection(shape(n, 3)).randFill());

		double[] sums = matmulReference(a.toArray(), b.toArray(), 4, n, 3);
		double[] expected = new double[sums.length];
		for (int i = 0; i < sums.length; i++) {
			expected[i] = 2.0 * sums[i] + 1.0;
		}

		CollectionProducer product = matmul(a, b, 4, n, 3);
		Assert.assertTrue(((WeightedSumComputation) product).isLooped());

		assertMatches("matmul inside expression", expected,
				track(product.multiply(2.0).add(1.0).evaluate()));
	}

	/**
	 * The loop decision made when a sum is constructed is preserved through
	 * {@link WeightedSumComputation#generate(java.util.List)}, so optimization cannot change
	 * the kernel form (and signature) of a looped sum by rereading a since-changed
	 * {@link WeightedSumComputation#loopThreshold}.
	 */
	@Test(timeout = 30000)
	public void generatePreservesLoopDecision() {
		int m = 4;
		int n = 300;
		int p = 3;

		PackedCollection a = track(new PackedCollection(shape(m, n)).randFill());
		PackedCollection b = track(new PackedCollection(shape(n, p)).randFill());

		WeightedSumComputation looped = (WeightedSumComputation) matmul(a, b, m, n, p);
		Assert.assertTrue(looped.isLooped());
		String signature = looped.signature();

		int threshold = WeightedSumComputation.loopThreshold;
		WeightedSumComputation.loopThreshold = Integer.MAX_VALUE;

		try {
			WeightedSumComputation regenerated = (WeightedSumComputation)
					looped.generate(new ArrayList<>(looped.getChildren()));
			Assert.assertTrue("generate must preserve the loop decision", regenerated.isLooped());
			Assert.assertEquals("generate must preserve the signature",
					signature, regenerated.signature());
		} finally {
			WeightedSumComputation.loopThreshold = threshold;
		}
	}

	/**
	 * A weighted sum whose input and weight groups have different total sizes is rejected when
	 * it is constructed, so the loop form cannot silently sum mismatched groups that the
	 * single-expression form rejects.
	 */
	@Test(timeout = 30000)
	public void mismatchedGroupSizesRejected() {
		int m = 4;
		int n = 300;
		int p = 3;

		PackedCollection a = track(new PackedCollection(shape(m, n)).randFill());
		PackedCollection b = track(new PackedCollection(shape(n + 1, p)).randFill());

		TraversalPolicy resultShape = shape(1, m, 1, p);

		try {
			weightedSum("matmul", resultShape,
					resultShape.withRate(3, n, p), resultShape.withRate(1, 1, m),
					shape(1, 1, n, 1), shape(1, 1, n + 1, 1),
					cp(a.reshape(1, m, n, 1)), cp(b.reshape(1, 1, n + 1, p)));
			Assert.fail("mismatched group sizes must be rejected");
		} catch (IllegalArgumentException expected) {
			// Equal-total-size contract enforced by the constructor for both kernel forms
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
				cp(track(new PackedCollection(shape(1, 1, 4, 7)).randFill())),
				cp(track(new PackedCollection(shape(1, 2, 4, 3)).randFill())));
		Assert.assertFalse(((WeightedSumComputation) conv).isLooped());
	}

	/**
	 * A looped weighted sum evaluated into a destination that already holds values overwrites
	 * them with the sum, rather than adding the sum to them. The single-expression form assigns
	 * the output element, so the loop form must too; a reused render buffer would otherwise
	 * accumulate across evaluations.
	 */
	@Test(timeout = 60000)
	public void loopOverwritesReusedDestination() {
		int m = 6;
		int n = 300;
		int p = 5;

		PackedCollection a = track(new PackedCollection(shape(m, n)).randFill());
		PackedCollection b = track(new PackedCollection(shape(n, p)).randFill());
		double[] expected = matmulReference(a.toArray(), b.toArray(), m, n, p);

		CollectionProducer product = matmul(a, b, m, n, p);
		Assert.assertTrue(((WeightedSumComputation) product).isLooped());

		Evaluable<PackedCollection> ev = product.get();
		PackedCollection destination = track(new PackedCollection(shape(1, m, 1, p)));
		destination.fill(100.0);
		ev.into(destination).evaluate();

		assertMatches("matmul (reused destination)", expected, destination);
	}

	/**
	 * A looped matrix product with a large output (many output elements, each a loop over a
	 * group of at least {@link WeightedSumComputation#loopThreshold} members) matches the host
	 * evaluation. This is the shape a dense projection in a render pipeline produces.
	 */
	@Test(timeout = 120000)
	public void largeOutputLoopMatchesReference() {
		int m = 12000;
		int n = 300;
		int p = 1;

		PackedCollection a = track(new PackedCollection(shape(m, n)).randFill());
		PackedCollection b = track(new PackedCollection(shape(n, p)).randFill());

		assertLoopMatches("large output", matmulReference(a.toArray(), b.toArray(), m, n, p),
				() -> matmul(a, b, m, n, p));
	}

	/**
	 * Saves a profile of a looped matrix product so the generated loop kernel source can be
	 * read with the profile analyzer.
	 */
	@Test(timeout = 120000)
	public void loopKernelSourceProfile() throws Exception {
		int m = 6;
		int n = 300;
		int p = 5;

		PackedCollection a = track(new PackedCollection(shape(m, n)).randFill());
		PackedCollection b = track(new PackedCollection(shape(n, p)).randFill());

		OperationProfileNode profile = new OperationProfileNode("weighted_sum_loop");
		Hardware.getLocalHardware().assignProfile(profile);

		try {
			CollectionProducer product = matmul(a, b, m, n, p);
			Assert.assertTrue(((WeightedSumComputation) product).isLooped());
			Evaluable<PackedCollection> ev = product.get();
			assertMatches("profiled matmul", matmulReference(a.toArray(), b.toArray(), m, n, p),
					track(ev.evaluate()));
		} finally {
			Hardware.getLocalHardware().clearProfile();
		}

		new File("results").mkdirs();
		profile.save("results/weighted_sum_loop.xml");
	}

	/**
	 * Asserts that the weighted sum built by {@code sum} is looped, and that both it and the same
	 * sum built with looping disabled evaluate to {@code expected}.
	 */
	private void assertLoopMatches(String label, double[] expected, Supplier<CollectionProducer> sum) {
		CollectionProducer looped = sum.get();
		Assert.assertTrue(label + " should be summed by a loop",
				((WeightedSumComputation) looped).isLooped());
		assertMatches(label + " (loop)", expected, track(looped.evaluate()));

		int threshold = WeightedSumComputation.loopThreshold;
		WeightedSumComputation.loopThreshold = Integer.MAX_VALUE;

		try {
			CollectionProducer single = sum.get();
			Assert.assertFalse(label + " should be summed by a single expression",
					((WeightedSumComputation) single).isLooped());
			assertMatches(label + " (single expression)", expected, track(single.evaluate()));
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
