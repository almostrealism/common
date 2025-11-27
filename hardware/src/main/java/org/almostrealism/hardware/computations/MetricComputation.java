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

package org.almostrealism.hardware.computations;

import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Provider;
import io.almostrealism.scope.Metric;
import io.almostrealism.scope.Scope;
import org.almostrealism.hardware.OperationComputationAdapter;
import org.almostrealism.hardware.mem.Bytes;

/**
 * {@link OperationComputationAdapter} that adds runtime profiling metrics to compiled operations.
 *
 * <p>{@link MetricComputation} instruments a {@link Producer} with {@link io.almostrealism.scope.Metric}
 * tracking, allowing runtime monitoring of values during kernel execution. This is useful for:</p>
 * <ul>
 *   <li><strong>Performance profiling:</strong> Track operation timing</li>
 *   <li><strong>Value debugging:</strong> Monitor intermediate computation values</li>
 *   <li><strong>Convergence tracking:</strong> Log gradient norms, loss values, etc.</li>
 *   <li><strong>Memory inspection:</strong> Monitor specific memory locations</li>
 * </ul>
 *
 * <h2>Basic Usage</h2>
 *
 * <pre>{@code
 * // Create producer to monitor
 * Producer<Matrix> gradientNorm = computeGradientNorm();
 *
 * // Add metric tracking
 * MetricComputation<Matrix> metric = new MetricComputation<>(
 *     "Gradient Norm",  // Message to log
 *     100,              // Log every 100 invocations
 *     gradientNorm,     // Producer to measure
 *     0,                // Position in memory (offset)
 *     1                 // Memory length (size)
 * );
 *
 * // Compile to scope
 * Scope<Void> scope = metric.getScope(context);
 * }</pre>
 *
 * <h2>Generated Scope Structure</h2>
 *
 * <p>Adds a {@link io.almostrealism.scope.Metric} to the scope's metrics collection:</p>
 *
 * <pre>{@code
 * Scope<Void> scope = super.getScope(context);
 * Metric metric = new Metric(arg[0], logFrequency);
 * metric.addMonitoredVariable("Gradient Norm", arg[1][pos]);
 * scope.getMetrics().add(metric);
 * }</pre>
 *
 * <h2>Log Frequency</h2>
 *
 * <p>Controls how often values are logged:</p>
 *
 * <pre>{@code
 * // Log every invocation
 * MetricComputation<T> verbose = new MetricComputation<>(
 *     "Value", 1, producer, 0, 1);
 *
 * // Log every 1000 invocations
 * MetricComputation<T> sparse = new MetricComputation<>(
 *     "Loss", 1000, lossProducer, 0, 1);
 * }</pre>
 *
 * <h2>Memory Position and Length</h2>
 *
 * <p>The {@code pos} and {@code memLength} parameters control which memory locations are monitored:</p>
 *
 * <pre>{@code
 * // Monitor single value at position 5
 * MetricComputation<T> single = new MetricComputation<>(
 *     "Weight[5]", 10, weights, 5, 1);
 *
 * // Monitor range [10, 12)
 * MetricComputation<T> range = new MetricComputation<>(
 *     "Activations[10-12]", 100, activations, 10, 2);
 * }</pre>
 *
 * <h2>Dummy Output Argument</h2>
 *
 * <p>The first argument is a dummy {@link org.almostrealism.hardware.mem.Bytes} used for
 * metric control:</p>
 *
 * <pre>{@code
 * // Constructor creates dummy output:
 * super(() -> new Provider(new Bytes(1)), measure);
 *
 * // This is referenced in the Metric as arg[0]
 * Metric metric = new Metric(getArgument(0).reference(e(0)), logFrequency);
 * }</pre>
 *
 * <h2>Use Cases</h2>
 *
 * <ul>
 *   <li><strong>Training monitoring:</strong> Track loss, accuracy during training</li>
 *   <li><strong>Gradient inspection:</strong> Monitor gradient norms for explosion/vanishing</li>
 *   <li><strong>Memory debugging:</strong> Inspect specific tensor values during execution</li>
 *   <li><strong>Performance analysis:</strong> Measure computation timing</li>
 * </ul>
 *
 * <h2>Example: Training Loop Metrics</h2>
 *
 * <pre>{@code
 * // Compute loss
 * Producer<PackedCollection<?>> loss = computeLoss(predictions, labels);
 *
 * // Add metric tracking
 * MetricComputation<PackedCollection<?>> lossMetric = new MetricComputation<>(
 *     "Training Loss", 10, loss, 0, 1);  // Log every 10 steps
 *
 * // Compute gradients
 * Producer<Matrix> gradients = computeGradients(loss);
 *
 * // Track gradient norm
 * Producer<PackedCollection<?>> gradNorm = norm(gradients);
 * MetricComputation<PackedCollection<?>> gradMetric = new MetricComputation<>(
 *     "Gradient Norm", 10, gradNorm, 0, 1);
 *
 * // Combined training step with metrics
 * Computation<Void> trainStep = sequence(
 *     lossMetric,     // Log loss
 *     gradMetric,     // Log gradient norm
 *     updateWeights(gradients)
 * );
 * }</pre>
 *
 * @param <T> The type of value being measured
 * @see OperationComputationAdapter
 * @see io.almostrealism.scope.Metric
 * @see io.almostrealism.relation.Producer
 */
public class MetricComputation<T> extends OperationComputationAdapter<T> implements ExpressionFeatures {
	private String message;
	private int logFrequency;
	private int pos, memLength;

	public MetricComputation(String message, int logFrequency, Producer<T> measure, int pos, int memLength) {
		super(() -> new Provider(new Bytes(1)), measure);
		this.message = message;
		this.logFrequency = logFrequency;
		this.pos = pos;
		this.memLength = memLength;
	}

	@Override
	public Scope<Void> getScope(KernelStructureContext context) {
		Scope<Void> scope = super.getScope(context);
		Metric metric = new Metric(getArgument(0).reference(e(0)), logFrequency);
		metric.addMonitoredVariable(message, getArgument(1).reference(e(pos)));
		scope.getMetrics().add(metric);
		return scope;
	}
}
