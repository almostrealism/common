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

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;

/**
 * Defines a loss function for neural network training with both loss computation and gradient calculation.
 * <p>
 * A {@code LossProvider} computes the scalar loss value between model outputs and target values,
 * and provides the gradient of the loss with respect to the outputs for backpropagation.
 * </p>
 *
 * <h2>Available Implementations</h2>
 * <ul>
 *   <li>{@link MeanSquaredError} - For regression tasks, penalizes squared differences</li>
 *   <li>{@link MeanAbsoluteError} - For robust regression, penalizes absolute differences</li>
 *   <li>{@link NegativeLogLikelihood} - For classification tasks, works with softmax outputs</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Create loss function
 * LossProvider mse = new MeanSquaredError(outputShape.traverseEach());
 *
 * // Compute scalar loss for logging/metrics
 * double lossValue = mse.loss(modelOutput, target);
 *
 * // Compute gradient for backpropagation
 * Producer<PackedCollection<?>> grad = mse.gradient(
 *     outputProducer,
 *     targetProducer
 * );
 *
 * // Use with ModelOptimizer
 * ModelOptimizer optimizer = new ModelOptimizer(model.compile(), dataset);
 * optimizer.setLossFunction(mse);
 * }</pre>
 *
 * <h2>Custom Loss Functions</h2>
 * <p>
 * To implement a custom loss function, provide both the loss computation and its gradient:
 * </p>
 * <pre>{@code
 * public class HuberLoss implements LossProvider {
 *     private double delta;
 *
 *     public double loss(PackedCollection<?> output, PackedCollection<?> target) {
 *         // Huber loss: quadratic for small errors, linear for large errors
 *         // ...
 *     }
 *
 *     public Producer<PackedCollection<?>> gradient(...) {
 *         // Gradient: linear for small errors, constant for large errors
 *         // ...
 *     }
 * }
 * }</pre>
 *
 * @see MeanSquaredError
 * @see MeanAbsoluteError
 * @see NegativeLogLikelihood
 * @see ModelOptimizer
 *
 * @author Michael Murray
 */
public interface LossProvider {
	/**
	 * Computes the scalar loss value between model outputs and target values.
	 * <p>
	 * The loss value quantifies how well the model's predictions match the targets.
	 * Lower values indicate better predictions. This method is typically used for
	 * logging, monitoring training progress, and early stopping.
	 * </p>
	 *
	 * @param output the model's output predictions
	 * @param target the expected target values
	 * @return the scalar loss value; lower is better
	 */
	double loss(PackedCollection<?> output, PackedCollection<?> target);

	/**
	 * Computes the gradient of the loss with respect to the model outputs.
	 * <p>
	 * The gradient is used during backpropagation to update model parameters.
	 * It indicates how each output element should change to reduce the loss.
	 * </p>
	 *
	 * @param output producer for the model's output predictions
	 * @param target producer for the expected target values
	 * @return a producer for the loss gradient with the same shape as output
	 */
	Producer<PackedCollection<?>> gradient(
			Producer<PackedCollection<?>> output,
			Producer<PackedCollection<?>> target);
}
