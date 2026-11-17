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

package org.almostrealism.stats.test;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.stats.DistributionFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Verifies that {@link DistributionFeatures#softmax(org.almostrealism.collect.CollectionProducer, boolean)}
 * returns the textbook distribution {@code exp(x_i) / sum(exp(x_j))} for every finite input,
 * including logits equal to {@code 0.0}, in both the max-subtracting and the plain form, and that
 * {@link DistributionFeatures#softmaxIgnoreZero(org.almostrealism.collect.CollectionProducer, boolean)}
 * treats zero-valued elements as padding.
 */
public class DistributionSoftmaxTests extends TestSuiteBase implements DistributionFeatures {

	/** Absolute tolerance for comparing a probability against the textbook value. */
	private static final double TOLERANCE = 1e-5;

	/**
	 * Computes the softmax of the given logits on the host with the textbook formula,
	 * as the reference the compiled computation is compared against.
	 *
	 * @param logits the logits
	 * @return {@code exp(x_i) / sum(exp(x_j))} for each logit
	 */
	private static double[] expectedSoftmax(double... logits) {
		double[] result = new double[logits.length];
		double sum = 0.0;

		for (int i = 0; i < logits.length; i++) {
			result[i] = Math.exp(logits[i]);
			sum += result[i];
		}

		for (int i = 0; i < logits.length; i++) {
			result[i] /= sum;
		}

		return result;
	}

	/**
	 * Evaluates the softmax of the given logits in both the max-subtracting and the plain
	 * form and compares every probability with the textbook value.
	 *
	 * @param logits the logits
	 */
	private void assertSoftmax(PackedCollection logits) {
		double[] expected = expectedSoftmax(logits.toArray());

		for (boolean subtractMax : new boolean[] { true, false }) {
			PackedCollection actual = softmax(cp(logits), subtractMax).evaluate();
			assertDistribution("softmax(subtractMax=" + subtractMax + ")", expected, actual, 0);
		}
	}

	/**
	 * Compares a region of the actual output with the expected probabilities.
	 *
	 * @param label a description of the computation, used in assertion messages
	 * @param expected the expected probabilities
	 * @param actual the evaluated output
	 * @param offset the index of the first element of the region in {@code actual}
	 */
	private static void assertDistribution(String label, double[] expected,
										   PackedCollection actual, int offset) {
		for (int i = 0; i < expected.length; i++) {
			Assert.assertEquals(label + " [" + (offset + i) + "]",
					expected[i], actual.toDouble(offset + i), TOLERANCE);
		}
	}

	/** A zero logit that is the largest logit receives the largest probability. */
	@Test(timeout = 60000)
	public void zeroLogitIsMaximum() {
		assertSoftmax(pack(-1.0, 0.0, -2.0));
	}

	/** A zero logit that is not the largest logit still takes part in the distribution. */
	@Test(timeout = 60000)
	public void zeroLogitIsNotMaximum() {
		assertSoftmax(pack(1.0, 0.0, 2.0));
		assertSoftmax(pack(0.0, -3.0, 0.5, 1.5));
	}

	/** An input of only zeros yields the uniform distribution. */
	@Test(timeout = 60000)
	public void allZeroLogits() {
		assertSoftmax(pack(0.0, 0.0, 0.0, 0.0));
	}

	/** An input with no zeros keeps the textbook result. */
	@Test(timeout = 60000)
	public void noZeroLogits() {
		assertSoftmax(pack(1.0, 2.0, 3.0));
		assertSoftmax(pack(-0.5, 4.0, -2.25, 0.75));
	}

	/** Each row of a traversed matrix is normalized on its own, zero logits included. */
	@Test(timeout = 60000)
	public void rowsWithZeroLogits() {
		double[] first = { -1.0, 0.0, -2.0 };
		double[] second = { 0.0, 0.0, 3.0 };

		for (boolean subtractMax : new boolean[] { true, false }) {
			PackedCollection input = pack(-1.0, 0.0, -2.0, 0.0, 0.0, 3.0).reshape(shape(2, 3));
			PackedCollection actual = softmax(cp(input).traverse(1), subtractMax).evaluate();

			String label = "rows softmax(subtractMax=" + subtractMax + ")";
			assertDistribution(label, expectedSoftmax(first), actual, 0);
			assertDistribution(label, expectedSoftmax(second), actual, 3);
		}
	}

	/**
	 * The masked variant treats an element equal to exactly zero as padding: it receives
	 * probability zero and the other elements are normalized without it.
	 */
	@Test(timeout = 60000)
	public void ignoreZeroMasksPadding() {
		double[] expected = expectedSoftmax(-1.0, -2.0);

		for (boolean subtractMax : new boolean[] { true, false }) {
			PackedCollection actual = softmaxIgnoreZero(cp(pack(-1.0, 0.0, -2.0)), subtractMax).evaluate();
			String label = "softmaxIgnoreZero(subtractMax=" + subtractMax + ")";
			Assert.assertEquals(label + " [0]", expected[0], actual.toDouble(0), TOLERANCE);
			Assert.assertEquals(label + " [1]", 0.0, actual.toDouble(1), TOLERANCE);
			Assert.assertEquals(label + " [2]", expected[1], actual.toDouble(2), TOLERANCE);
		}
	}
}
