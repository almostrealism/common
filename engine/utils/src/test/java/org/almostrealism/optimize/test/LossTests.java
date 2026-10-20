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

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.optimize.MeanSquaredError;
import org.almostrealism.optimize.NegativeLogLikelihood;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.Random;

/**
 * Tests for loss functions.
 */
public class LossTests extends TestSuiteBase {
	/**
	 * Tests mean squared error loss computation.
	 */
	@Test(timeout = 10000)
	public void meanSquaredError() {
		TraversalPolicy outputShape = new TraversalPolicy(1, 1, 28, 28).traverseEach();
		PackedCollection input = new PackedCollection(shape(1, 1, 28, 28));
		PackedCollection target = new PackedCollection(shape(1, 1, 28, 28));

		MeanSquaredError mse = new MeanSquaredError(outputShape);
		PackedCollection grad = mse.gradient(cv(outputShape, 0), cv(outputShape, 1)).get()
				.evaluate(input.each(), target.each());
		Assert.assertEquals(outputShape, grad.getShape());
	}

	/**
	 * The gradient of {@link NegativeLogLikelihood} over a multi-row output is the gradient of the
	 * averaged loss it reports: a central finite difference of {@code loss} with respect to every
	 * output element matches {@code gradient}, which is {@code -1 / rows} at each row's target
	 * class and zero elsewhere. The gradient producer is built from argument producers of the output
	 * shape, as {@code ModelOptimizer} builds it.
	 */
	@Test(timeout = 60000)
	public void negativeLogLikelihoodMultiRowGradient() {
		int rows = 4;
		int classes = 5;
		TraversalPolicy outputShape = shape(rows, classes).traverseEach();

		Random random = new Random(9);
		PackedCollection output = randn(shape(rows, classes), -1.5, 0.5, random).evaluate();
		PackedCollection target = PackedCollection.of(
				0, 1, 0, 0, 0,
				0, 0, 0, 0, 1,
				1, 0, 0, 0, 0,
				0, 0, 0, 0, 1).reshape(shape(rows, classes));

		NegativeLogLikelihood nll = new NegativeLogLikelihood();
		PackedCollection gradient = nll.gradient(cv(outputShape, 0), cv(outputShape, 1)).get()
				.evaluate(output.each(), target.each());

		double[] numeric = centralDifferences(output, 1e-2, () -> nll.loss(output, target));
		for (int i = 0; i < rows * classes; i++) {
			Assert.assertEquals("element " + i, numeric[i], gradient.toDouble(i), 1e-4);
		}

		Assert.assertEquals(-1.0 / rows, gradient.toDouble(1), 1e-9);
	}
}
