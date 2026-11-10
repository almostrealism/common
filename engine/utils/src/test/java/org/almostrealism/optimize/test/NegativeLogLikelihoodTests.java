/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.optimize.test;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.optimize.NegativeLogLikelihood;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for the loss terms of {@link NegativeLogLikelihood} over rows as wide as a language model's
 * vocabulary.
 */
public class NegativeLogLikelihoodTests extends TestSuiteBase {
	/**
	 * {@link NegativeLogLikelihood} scores a row as wide as a large vocabulary quickly: the term
	 * of the row is computed as a reduction over its columns rather than as one expression with a
	 * term for every column, which took tens of seconds to compile for a row of this width. For an
	 * output whose log-probabilities are all equal and a one-hot target, the term is the negated
	 * log-probability.
	 */
	@Test(timeout = 10000)
	public void negativeLogLikelihoodOfWideRow() {
		int classes = 8487;
		PackedCollection output = new PackedCollection(shape(classes));
		output.fill(-9.0);
		PackedCollection target = new PackedCollection(shape(classes));
		target.setMem(5, 1.0);

		PackedCollection terms = new NegativeLogLikelihood()
				.loss(cp(output.each()), cp(target.each())).evaluate();
		Assert.assertEquals(1, terms.getMemLength());
		Assert.assertEquals(9.0, terms.toDouble(0), 1e-4);
	}

	/**
	 * {@link NegativeLogLikelihood} rejects an output with no shape, rather than treating it as
	 * a single element and computing a term for one row of one column.
	 */
	@Test(timeout = 10000)
	public void negativeLogLikelihoodRejectsShapelessOutput() {
		Producer<PackedCollection> target = cp(new PackedCollection(shape(2, 3)));
		assertRejected(() -> new NegativeLogLikelihood().loss(shapeless(), target));
	}

	/**
	 * {@link NegativeLogLikelihood} rejects a target with no shape, rather than reporting a row
	 * count mismatch derived from a shape it does not have.
	 */
	@Test(timeout = 10000)
	public void negativeLogLikelihoodRejectsShapelessTarget() {
		Producer<PackedCollection> output = cp(new PackedCollection(shape(2, 3)));
		assertRejected(() -> new NegativeLogLikelihood().loss(output, shapeless()));
	}

	/**
	 * The gradient of {@link NegativeLogLikelihood} rejects an output with no shape, as the loss
	 * terms do, rather than treating it as a single row and scaling the target by -1.
	 */
	@Test(timeout = 10000)
	public void negativeLogLikelihoodGradientRejectsShapelessOutput() {
		Producer<PackedCollection> target = cp(new PackedCollection(shape(2, 3)));
		assertRejected(() -> new NegativeLogLikelihood().gradient(shapeless(), target));
	}

	/**
	 * The gradient of {@link NegativeLogLikelihood} for an output of two rows is the target
	 * scaled by -1/2, the derivative of the mean of the two row terms.
	 */
	@Test(timeout = 10000)
	public void negativeLogLikelihoodGradientOfTwoRows() {
		PackedCollection target = new PackedCollection(shape(2, 3));
		target.setMem(1, 1.0);
		target.setMem(5, 1.0);

		PackedCollection gradient = new NegativeLogLikelihood()
				.gradient(cp(new PackedCollection(shape(2, 3))), cp(target)).evaluate();
		double[] expected = {0.0, -0.5, 0.0, 0.0, 0.0, -0.5};
		Assert.assertArrayEquals(expected, gradient.toArray(), 1e-6);
	}

	/** Returns a producer that does not implement {@link io.almostrealism.collect.Shape}. */
	private static Producer<PackedCollection> shapeless() {
		return () -> null;
	}

	/**
	 * Asserts that {@code loss} throws an {@link IllegalArgumentException} naming the
	 * negative log likelihood as the operation that requires a shape.
	 */
	private static void assertRejected(Runnable loss) {
		try {
			loss.run();
		} catch (IllegalArgumentException e) {
			Assert.assertEquals("Negative Log Likelihood cannot be performed without a TraversalPolicy",
					e.getMessage());
			return;
		}

		Assert.fail("Expected IllegalArgumentException for a shapeless producer");
	}
}
