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

import io.almostrealism.code.Precision;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.layers.ParameterUpdate;

import java.util.function.Supplier;

/**
 * Adam (Adaptive Moment Estimation) optimizer for neural network training.
 * <p>
 * Adam combines the benefits of AdaGrad and RMSprop by maintaining both first moment
 * (mean of gradients) and second moment (uncentered variance) estimates. This provides
 * adaptive learning rates for each parameter.
 * </p>
 *
 * <h2>Algorithm</h2>
 * <p>
 * At each timestep t, Adam computes:
 * </p>
 * <pre>
 * m_t = beta1 * m_{t-1} + (1 - beta1) * g_t        (first moment estimate)
 * v_t = beta2 * v_{t-1} + (1 - beta2) * g_t^2     (second moment estimate)
 * m_hat = m_t / (1 - beta1^t)                     (bias correction)
 * v_hat = v_t / (1 - beta2^t)                     (bias correction)
 * theta_t = theta_{t-1} - lr * m_hat / (sqrt(v_hat) + eps)
 * </pre>
 *
 * <h2>Hyperparameters</h2>
 * <ul>
 *   <li><b>Learning rate (alpha)</b>: Step size for updates, typically 0.001</li>
 *   <li><b>Beta1</b>: Exponential decay rate for first moment, typically 0.9</li>
 *   <li><b>Beta2</b>: Exponential decay rate for second moment, typically 0.999</li>
 *   <li><b>Epsilon</b>: Small constant for numerical stability, 1e-7 (hardcoded)</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Create Adam optimizer with default parameters
 * AdamOptimizer adam = new AdamOptimizer(0.001, 0.9, 0.999);
 *
 * // Create update operation for a weight matrix
 * Supplier<Runnable> updateOp = adam.apply(
 *     "layer1.weights",
 *     weightsProducer,
 *     gradientsProducer
 * );
 *
 * // During training, apply the update
 * updateOp.get().run();
 * }</pre>
 *
 * <h2>Implementation Notes</h2>
 * <ul>
 *   <li>Each parameter set maintains its own momentum and velocity accumulators</li>
 *   <li>Timestep counter is incremented automatically on each update</li>
 *   <li>Bias correction ensures stable updates in early training</li>
 * </ul>
 *
 * @see LossProvider
 * @see ModelOptimizer
 * @see ParameterUpdate
 *
 * @author Michael Murray
 */
public class AdamOptimizer implements ParameterUpdate<PackedCollection>, CodeFeatures {
	private Producer<PackedCollection> learningRate;
	private Producer<PackedCollection> beta1;
	private Producer<PackedCollection> beta2;

	/**
	 * Creates an Adam optimizer with the specified hyperparameters.
	 *
	 * @param learningRate the learning rate (alpha), typically 0.001
	 * @param beta1        exponential decay rate for first moment estimates, typically 0.9
	 * @param beta2        exponential decay rate for second moment estimates, typically 0.999
	 */
	public AdamOptimizer(double learningRate, double beta1, double beta2) {
		this(CollectionFeatures.getInstance().c(learningRate),
				CollectionFeatures.getInstance().c(beta1),
				CollectionFeatures.getInstance().c(beta2));
	}

	/**
	 * Creates an Adam optimizer with dynamic hyperparameters provided as producers.
	 * <p>
	 * This constructor allows learning rate scheduling by providing a producer
	 * that computes the learning rate at each step.
	 * </p>
	 *
	 * @param learningRate producer for the learning rate value
	 * @param beta1        producer for the first moment decay rate
	 * @param beta2        producer for the second moment decay rate
	 */
	public AdamOptimizer(Producer<PackedCollection> learningRate,
						 Producer<PackedCollection> beta1,
						 Producer<PackedCollection> beta2) {
		this.learningRate = learningRate;
		this.beta1 = beta1;
		this.beta2 = beta2;
	}

	/**
	 * Creates an update operation for the given parameters.
	 * <p>
	 * This method returns a supplier that, when invoked, produces a runnable
	 * that applies one Adam update step to the weights using the provided gradient.
	 * </p>
	 * <p>
	 * Internal state (momentum m, velocity v, timestep) is maintained across calls,
	 * enabling proper bias correction throughout training.
	 * </p>
	 *
	 * @param name     a descriptive name for the parameter (used in logging)
	 * @param weights  producer for the weight parameters to update
	 * @param gradient producer for the gradients with respect to weights
	 * @return a supplier that produces runnable update operations
	 */
	@Override
	public Supplier<Runnable> apply(String name,
									Producer<PackedCollection> weights,
									Producer<PackedCollection> gradient) {
		TraversalPolicy shape = shape(weights);

		PackedCollection c = new PackedCollection(1);
		PackedCollection m = new PackedCollection(shape.traverseEach());
		PackedCollection v = new PackedCollection(shape.traverseEach());
		double eps = 1e-7; // Hardware.getLocalHardware().epsilon();

		OperationList ops = new OperationList();
		ops.add(a("increment", cp(c), cp(c).add(1)));
		ops.add(a(name + " (\u0394 momentum)", cp(m),
				c(beta1).multiply(cp(m)).add(c(1.0).subtract(c(beta1)).multiply(c(gradient)))));
		ops.add(a(name + " (\u0394 velocity)", cp(v),
				c(beta2).multiply(cp(v)).add(c(1.0).subtract(c(beta2)).multiply(c(gradient).sq()))));

		CollectionProducer<PackedCollection> mt = cp(m).divide(c(1.0).subtract(c(beta1).pow(cp(c))));
		CollectionProducer<PackedCollection> vt = cp(v).divide(c(1.0).subtract(c(beta2).pow(cp(c))));
		ops.add(a(name + " (\u0394 weights)", c(weights).each(),
				c(weights).each().subtract(c(learningRate).multiply(mt).divide(vt.sqrt().add(eps)))));
		return ops;
	}
}
