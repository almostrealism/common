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

package org.almostrealism.layers.test;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Producer;
import org.almostrealism.calculus.ReduceAxis;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.Collections;
import java.util.IdentityHashMap;

/**
 * Verifies the {@link ReduceAxis#OUT output-reduced} delta of the 2-D convolution operator
 * ({@link LayerFeatures#convolution2dOperator}) against the gradient computed on the host
 * from the definition of the convolution, and against the full-Jacobian gradient
 * ({@link #jacobianGradient}), which is formed and evaluated several times.
 *
 * <p>The convolution is a weighted sum whose windows slide over the input, a traversal
 * that cannot be transposed, so its gradient with respect to the input has no reduced form
 * and contracts the full Jacobian. The shapes are chosen so that this contraction, a
 * {@code 7200 × 8192} Jacobian whose entries each sum a window of 72 terms, spans more terms
 * than a signed 32-bit integer can count, as the full-Jacobian weight gradient of a dense
 * layer did when hoisted parts of its flattened position overflowed.</p>
 *
 * @author Michael Murray
 */
public class ConvolutionJacobianGradientTest extends TestSuiteBase implements LayerFeatures {
	/** Number of times each full-Jacobian gradient is formed and evaluated. */
	private static final int JACOBIAN_EVALUATIONS = 3;

	/** Number of input channels. */
	private static final int CHANNELS = 8;

	/** Height and width of the input. */
	private static final int SIZE = 32;

	/** Number of filters. */
	private static final int FILTERS = 8;

	/** Height and width of each filter. */
	private static final int KERNEL = 3;

	/**
	 * The input gradient of a convolution, which is formed by contracting the full
	 * Jacobian, agrees with its definition on every one of several evaluations, as does
	 * the forward pass.
	 */
	@Test(timeout = 20 * 60000)
	public void inputGradient() {
		try (Convolution conv = new Convolution()) {
			Producer<PackedCollection> in = cp(conv.input);
			CollectionProducer operator = conv.operator(in);
			Assert.assertEquals(conv.outputShape.getTotalSize(), operator.getShape().getTotalSize());

			try (PackedCollection forward = evaluate(operator)) {
				assertValuesMatch("forward", conv.forward, forward.toArray(0, conv.forward.length));
			}

			Assert.assertTrue("the input gradient of a sliding window should contract the full Jacobian",
					largestComputation(operator.delta(in, ReduceAxis.OUT, cp(conv.gradient)))
							> conv.outputShape.getTotalSizeLong());

			assertGradientMatches("input", operator, in, cp(conv.gradient), conv.inputGradient);
		}
	}

	/**
	 * The filter gradient of a convolution whose operator views the filters through
	 * {@code cp(filters.reshape(...))} agrees with its definition when it is taken with
	 * respect to {@code cp(filters)}, the producer a convolution layer differentiates its
	 * weight by, so the two views of the same filters are recognized as one input.
	 */
	@Test(timeout = 20 * 60000)
	public void filterGradient() {
		try (Convolution conv = new Convolution()) {
			Producer<PackedCollection> weight = cp(conv.filters);
			CollectionProducer operator = conv.operator(cp(conv.input));

			assertGradientMatches("filter", operator, weight, cp(conv.gradient), conv.filterGradient);
		}
	}

	/**
	 * The convolution operator rejects an input or filters that are not 4-D, and filters
	 * whose channel count differs from the input's, before any computation is built.
	 */
	@Test(timeout = 60000)
	public void operatorRejectsMismatchedShapes() {
		PackedCollection filters = new PackedCollection(shape(FILTERS, CHANNELS, KERNEL, KERNEL));

		assertRejected(shape(CHANNELS, SIZE, SIZE), filters);
		assertRejected(shape(1, CHANNELS + 1, SIZE, SIZE), filters);
		assertRejected(shape(1, CHANNELS, SIZE, SIZE),
				new PackedCollection(shape(FILTERS, CHANNELS, KERNEL * KERNEL)));

		Assert.assertNotNull(convolution2dOperator(shape(2, CHANNELS, SIZE, SIZE), filters, null));
		filters.destroy();
	}

	/**
	 * Asserts that building the convolution operator for the given shapes is rejected.
	 *
	 * @param inputShape the input shape
	 * @param filters    the filters
	 */
	private void assertRejected(TraversalPolicy inputShape, PackedCollection filters) {
		try {
			convolution2dOperator(inputShape, filters, null);
			Assert.fail("input " + inputShape + " with filters " + filters.getShape() + " should be rejected");
		} catch (IllegalArgumentException expected) {
			Assert.assertTrue(expected.getMessage().contains(inputShape.toString()));
		}
	}

	/**
	 * Asserts that the output-reduced delta of a function weighted by the upstream gradient
	 * agrees with the gradient computed from its definition, and that the full-Jacobian
	 * gradient, formed and evaluated anew {@link #JACOBIAN_EVALUATIONS} times, agrees with
	 * it every time.
	 *
	 * @param label     names the gradient in failure messages
	 * @param function  the forward operator
	 * @param target    the producer to differentiate with respect to
	 * @param gradient  the upstream gradient
	 * @param reference the gradient computed directly from its definition
	 */
	private void assertGradientMatches(String label, CollectionProducer function,
									   Producer<PackedCollection> target,
									   Producer<PackedCollection> gradient, double[] reference) {
		CollectionProducer reduced = function.delta(target, ReduceAxis.OUT, gradient);
		log(label + " largestComputation=" + largestComputation(reduced)
				+ " forward=" + function.getShape().getTotalSize());

		PackedCollection actual = evaluate(reduced);
		assertValuesMatch(label + " reduced", reference, actual.toArray(0, reference.length));
		actual.destroy();

		for (int n = 0; n < JACOBIAN_EVALUATIONS; n++) {
			PackedCollection expected = evaluate(jacobianGradient(function, target, gradient));
			assertValuesMatch(label + " jacobian evaluation " + n, reference, expected.toArray(0, reference.length));
			expected.destroy();
		}
	}

	/**
	 * Returns the largest output of any computation in the graph of the given producer,
	 * which exceeds the size of the forward operator only when the full Jacobian is formed.
	 *
	 * @param producer the producer to inspect
	 * @return the largest total size of any collection producer in the graph
	 */
	private static long largestComputation(Object producer) {
		return DenseVectorJacobianProductTest.largestComputation(producer,
				Collections.newSetFromMap(new IdentityHashMap<>()));
	}

	/**
	 * Asserts that two arrays agree to single-precision rounding, as
	 * {@link DenseVectorJacobianProductTest} requires of its gradients.
	 *
	 * @param label    describes the comparison in a failure message
	 * @param expected the expected values
	 * @param actual   the actual values
	 */
	private static void assertValuesMatch(String label, double[] expected, double[] actual) {
		DenseVectorJacobianProductTest.assertValuesMatch(label, expected, actual);
	}

	/**
	 * A random convolution input, filters and upstream gradient, with the forward pass and
	 * the gradients with respect to the input and the filters computed on the host from the
	 * definition of the convolution. Closing it releases the input, filters and gradient.
	 */
	private class Convolution implements AutoCloseable {
		/** Height and width of the output. */
		private final int out = SIZE - KERNEL + 1;

		/** Shape of the input. */
		private final TraversalPolicy inputShape = shape(1, CHANNELS, SIZE, SIZE);

		/** Shape of the output. */
		private final TraversalPolicy outputShape = shape(1, FILTERS, out, out);

		/** The input. */
		private final PackedCollection input = new PackedCollection(inputShape).randnFill();

		/** The filters. */
		private final PackedCollection filters =
				new PackedCollection(shape(FILTERS, CHANNELS, KERNEL, KERNEL)).randnFill();

		/** The upstream gradient. */
		private final PackedCollection gradient = new PackedCollection(outputShape).randnFill();

		/** The forward pass, computed from the definition. */
		private final double[] forward = new double[outputShape.getTotalSize()];

		/** The gradient with respect to the input, computed from the definition. */
		private final double[] inputGradient = new double[inputShape.getTotalSize()];

		/** The gradient with respect to the filters, computed from the definition. */
		private final double[] filterGradient = new double[filters.getShape().getTotalSize()];

		/** Computes the forward pass and both gradients from the definition. */
		Convolution() {
			double[] x = input.toArray(0, inputShape.getTotalSize());
			double[] w = filters.toArray(0, filters.getShape().getTotalSize());
			double[] g = gradient.toArray(0, outputShape.getTotalSize());

			for (int f = 0; f < FILTERS; f++) {
				for (int i = 0; i < out; i++) {
					for (int j = 0; j < out; j++) {
						int o = (f * out + i) * out + j;
						for (int c = 0; c < CHANNELS; c++) {
							for (int ki = 0; ki < KERNEL; ki++) {
								for (int kj = 0; kj < KERNEL; kj++) {
									int xi = (c * SIZE + i + ki) * SIZE + j + kj;
									int wi = ((f * CHANNELS + c) * KERNEL + ki) * KERNEL + kj;
									forward[o] += x[xi] * w[wi];
									inputGradient[xi] += g[o] * w[wi];
									filterGradient[wi] += g[o] * x[xi];
								}
							}
						}
					}
				}
			}
		}

		/**
		 * Applies the convolution operator to the given input.
		 *
		 * @param in the input producer
		 * @return the convolution of the input by {@link #filters}
		 */
		CollectionProducer operator(Producer<PackedCollection> in) {
			return (CollectionProducer) convolution2dOperator(inputShape, filters, null).getResultant(in);
		}

		/** Releases the input, filters and upstream gradient. */
		@Override
		public void close() {
			input.destroy();
			filters.destroy();
			gradient.destroy();
		}
	}

	/**
	 * Evaluates a producer after optimization, as the backward pass would.
	 *
	 * @param producer the producer to evaluate
	 * @return the evaluated result
	 */
	private PackedCollection evaluate(Producer<PackedCollection> producer) {
		return Process.optimized(producer).get().evaluate();
	}
}
