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
		int[] targets = { 1, 4, 0, 4 };
		TraversalPolicy outputShape = shape(rows, classes).traverseEach();

		Random random = new Random(9);
		PackedCollection output = randn(shape(rows, classes), -1.5, 0.5, random).evaluate();
		PackedCollection target = new PackedCollection(shape(rows, classes));
		for (int r = 0; r < rows; r++) {
			target.setMem(r * classes + targets[r], 1.0);
		}

		NegativeLogLikelihood nll = new NegativeLogLikelihood();
		PackedCollection gradient = nll.gradient(cv(outputShape, 0), cv(outputShape, 1)).get()
				.evaluate(output.each(), target.each());

		double eps = 1e-2;
		for (int i = 0; i < rows * classes; i++) {
			double original = output.toDouble(i);
			output.setMem(i, original + eps);
			double plus = nll.loss(output, target);
			output.setMem(i, original - eps);
			double minus = nll.loss(output, target);
			output.setMem(i, original);

			double numeric = (plus - minus) / (2 * eps);
			Assert.assertEquals("element " + i, numeric, gradient.toDouble(i), 1e-4);
		}

		Assert.assertEquals(-1.0 / rows, gradient.toDouble(targets[0]), 1e-9);
	}
}
