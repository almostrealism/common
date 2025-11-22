/*
 * Copyright 2024 Michael Murray
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
 * Mean Squared Error (MSE) loss function for regression tasks.
 * <p>
 * MSE computes the average of squared differences between predictions and targets:
 * </p>
 * <pre>
 * MSE = (1/n) * sum((output - target)^2)
 * </pre>
 * <p>
 * This loss function is commonly used for regression problems where predictions
 * are continuous values. It heavily penalizes large errors due to the squaring.
 * </p>
 *
 * <h2>Gradient</h2>
 * <p>
 * The gradient of MSE with respect to outputs is:
 * </p>
 * <pre>
 * dL/dOutput = (2/n) * (output - target)
 * </pre>
 *
 * <h2>When to Use</h2>
 * <ul>
 *   <li>Regression problems (predicting continuous values)</li>
 *   <li>When large errors should be penalized more than small errors</li>
 *   <li>When output values follow a Gaussian distribution</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Create MSE loss for output shape [batch, features]
 * MeanSquaredError mse = new MeanSquaredError(shape(10).traverseEach());
 *
 * // Compute loss
 * double loss = mse.loss(predictions, targets);
 *
 * // Get gradient for backpropagation
 * Producer<PackedCollection<?>> grad = mse.gradient(outputProducer, targetProducer);
 * }</pre>
 *
 * @see MeanAbsoluteError
 * @see NegativeLogLikelihood
 * @see LossProvider
 *
 * @author Michael Murray
 */
public class MeanSquaredError implements LossProvider, CodeFeatures {
	private TraversalPolicy outputShape;
	private Evaluable<PackedCollection<?>> loss;

	/**
	 * Creates a Mean Squared Error loss function for the specified output shape.
	 *
	 * @param outputShape the shape of model outputs (typically obtained via {@code shape.traverseEach()})
	 */
	public MeanSquaredError(TraversalPolicy outputShape) {
		this.outputShape = outputShape;
		this.loss = cv(outputShape, 0).subtract(cv(outputShape, 1)).pow(2.0).get();
	}

	/**
	 * Computes the mean squared error between output and target.
	 *
	 * @param output the model's output predictions
	 * @param target the expected target values
	 * @return the mean of squared differences
	 */
	@Override
	public double loss(PackedCollection<?> output, PackedCollection<?> target) {
		return loss.evaluate(output, target).doubleStream().average().orElse(0);
	}

	/**
	 * Computes the gradient of MSE: (2/n) * (output - target).
	 *
	 * @param output producer for the model's output predictions
	 * @param target producer for the expected target values
	 * @return a producer for the loss gradient
	 */
	@Override
	public Producer<PackedCollection<?>> gradient(Producer<PackedCollection<?>> output,
												  Producer<PackedCollection<?>> target) {
		return c(2.0 / outputShape.getTotalSize())
				.multiply(c(output).subtract(c(target)));
	}
}
