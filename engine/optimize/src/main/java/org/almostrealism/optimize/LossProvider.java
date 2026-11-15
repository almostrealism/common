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
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

/**
 * Defines a loss function for neural network training as computations of the loss and of its
 * gradient.
 * <p>
 * A {@code LossProvider} describes the loss between model outputs and target values as a
 * {@link CollectionProducer} of loss terms, whose mean is the loss, and provides the gradient of
 * the loss with respect to the outputs for backpropagation. Both are computations, so the loss
 * is computed on the same device as the model, with the rest of each training sample's work.
 * </p>
 *
 * <h2>Available Implementations</h2>
 * <ul>
 *   <li>{@link MeanSquaredError} - For regression tasks, penalizes squared differences</li>
 *   <li>{@link MeanAbsoluteError} - For robust regression, penalizes absolute differences</li>
 *   <li>{@link NegativeLogLikelihood} - For classification tasks, works with log-softmax outputs</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Create loss function
 * LossProvider mse = new MeanSquaredError(outputShape.traverseEach());
 *
 * // Compute the loss: the mean of its terms
 * double lossValue = mse.loss(cp(modelOutput), cp(target))
 *     .evaluate().doubleStream().average().orElse(0);
 *
 * // Compute gradient for backpropagation
 * Producer<PackedCollection> grad = mse.gradient(
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
 * To implement a custom loss function, provide the terms of the loss and its gradient, both
 * expressed as producers:
 * </p>
 * <pre>{@code
 * public class HuberLoss implements LossProvider, CodeFeatures {
 *     private double delta;
 *
 *     public CollectionProducer loss(Producer<PackedCollection> output,
 *                                    Producer<PackedCollection> target) {
 *         // Huber loss: quadratic for small errors, linear for large errors
 *         // ...
 *     }
 *
 *     public Producer<PackedCollection> gradient(...) {
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
	 * Returns the loss of {@code output} against {@code target} as a computation of its terms:
	 * the values whose mean is the loss. Lower values indicate better predictions.
	 * <p>
	 * Because the loss is a computation, it can be submitted with the work that produces
	 * {@code output}, rather than computed once that work has completed. {@link ModelOptimizer}
	 * submits it after each sample's forward pass and loss gradient, so one wait for the sample
	 * covers all three, and takes the sample's loss as the mean of the terms. On a device that
	 * runs its dispatches asynchronously, that saves a round trip between the host and the device
	 * for every sample.
	 * </p>
	 * <p>
	 * The terms are a {@link CollectionProducer} so that their shape is known before they are
	 * computed: {@link ModelOptimizer} allocates the destination for the terms from it.
	 * </p>
	 *
	 * @param output producer for the model's output predictions
	 * @param target producer for the expected target values
	 * @return a producer of the loss terms, whose mean is the loss
	 */
	CollectionProducer loss(Producer<PackedCollection> output,
							Producer<PackedCollection> target);

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
	Producer<PackedCollection> gradient(
			Producer<PackedCollection> output,
			Producer<PackedCollection> target);
}
