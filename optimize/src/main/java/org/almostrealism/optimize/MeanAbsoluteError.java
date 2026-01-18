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

package org.almostrealism.optimize;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.PackedCollection;

/**
 * Mean Absolute Error (MAE) loss function for robust regression tasks.
 * <p>
 * MAE computes the average of absolute differences between predictions and targets:
 * </p>
 * <pre>
 * MAE = (1/n) * sum(|output - target|)
 * </pre>
 * <p>
 * This loss function is more robust to outliers than MSE because it does not
 * square the errors. Large errors are penalized linearly rather than quadratically.
 * </p>
 *
 * <h2>Gradient</h2>
 * <p>
 * The gradient of MAE with respect to outputs is:
 * </p>
 * <pre>
 * dL/dOutput = (1/n) * sign(output - target)
 * </pre>
 * <p>
 * Where sign(x) = 1 if x > 0, -1 if x < 0.
 * </p>
 *
 * <h2>When to Use</h2>
 * <ul>
 *   <li>Regression problems with outliers in the data</li>
 *   <li>When all errors should be weighted equally regardless of magnitude</li>
 *   <li>When robustness to outliers is more important than convergence speed</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Create MAE loss for output shape [batch, features]
 * MeanAbsoluteError mae = new MeanAbsoluteError(shape(10).traverseEach());
 *
 * // Compute loss
 * double loss = mae.loss(predictions, targets);
 *
 * // Get gradient for backpropagation
 * Producer<PackedCollection> grad = mae.gradient(outputProducer, targetProducer);
 * }</pre>
 *
 * @see MeanSquaredError
 * @see NegativeLogLikelihood
 * @see LossProvider
 *
 * @author Michael Murray
 */
public class MeanAbsoluteError implements LossProvider, CodeFeatures {
	private final TraversalPolicy outputShape;
	private final Evaluable<PackedCollection> loss;

	/**
	 * Creates a Mean Absolute Error loss function for the specified output shape.
	 *
	 * @param outputShape the shape of model outputs (typically obtained via {@code shape.traverseEach()})
	 */
	public MeanAbsoluteError(TraversalPolicy outputShape) {
		this.outputShape = outputShape;
		this.loss = cv(outputShape, 0).subtract(cv(outputShape, 1)).abs().get();
	}

	/**
	 * Computes the mean absolute error between output and target.
	 *
	 * @param output the model's output predictions
	 * @param target the expected target values
	 * @return the mean of absolute differences
	 */
	@Override
	public double loss(PackedCollection output, PackedCollection target) {
		return loss.evaluate(output, target).doubleStream().average().orElse(0);
	}

	/**
	 * Computes the gradient of MAE: (1/n) * sign(output - target).
	 *
	 * @param output producer for the model's output predictions
	 * @param target producer for the expected target values
	 * @return a producer for the loss gradient with values +1/n or -1/n
	 */
	@Override
	public Producer<PackedCollection> gradient(Producer<PackedCollection> output,
												  Producer<PackedCollection> target) {
		double f = 1.0 / outputShape.getTotalSize();
		return c(output).greaterThan(c(target), c(f), c(-f));
	}
}
