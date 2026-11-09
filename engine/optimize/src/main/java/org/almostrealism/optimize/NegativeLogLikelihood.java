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
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

/**
 * Negative Log Likelihood (NLL) loss function for classification tasks.
 * <p>
 * NLL computes the negative log probability of the correct class. When used with
 * log-softmax outputs, this is equivalent to cross-entropy loss. The loss is computed as:
 * </p>
 * <pre>
 * NLL = -log(output[target_class])
 * </pre>
 * <p>
 * For batched inputs, the average NLL across all samples is returned.
 * </p>
 *
 * <h2>Gradient</h2>
 * <p>
 * The gradient is that of the averaged loss: for an output with {@code rows} rows (the
 * leading dimension after padding to two dimensions, the same rows the {@link #loss} terms
 * are computed for), it is {@code -1 / rows} at the target class of each row and 0 elsewhere.
 * </p>
 * <pre>
 * dL/dOutput[r, i] = -1 / rows if i == target_class(r), 0 otherwise
 * </pre>
 * <p>
 * For a single-row output this is -1 at the target class. In general, the loss weighs the
 * output by the target, {@code -sum(output * target) / rows}, and the gradient is
 * {@code -target / rows}, which is exactly its derivative.
 * </p>
 *
 * <h2>When to Use</h2>
 * <ul>
 *   <li>Multi-class classification problems</li>
 *   <li>When outputs are log-probabilities (from log-softmax)</li>
 *   <li>When targets are one-hot encoded class labels</li>
 * </ul>
 *
 * <h2>Input Format</h2>
 * <ul>
 *   <li>Output: Log-probabilities of shape [batch, classes] or [classes]</li>
 *   <li>Target: One-hot encoded labels of the same shape as output</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Create NLL loss
 * NegativeLogLikelihood nll = new NegativeLogLikelihood();
 *
 * // Compute loss (assumes log-softmax outputs and one-hot targets): the mean over rows
 * double loss = nll.loss(cp(predictions), cp(oneHotTargets))
 *     .evaluate().doubleStream().average().orElse(0);
 *
 * // Get gradient for backpropagation
 * Producer<PackedCollection> grad = nll.gradient(outputProducer, targetProducer);
 * }</pre>
 *
 * @see MeanSquaredError
 * @see MeanAbsoluteError
 * @see LossProvider
 *
 * @author Michael Murray
 */
public class NegativeLogLikelihood implements LossProvider, CollectionFeatures {
	/**
	 * Returns the negative log likelihood of each row of the output: for an output with
	 * {@code rows} rows (the leading dimension after padding its shape to two dimensions), the
	 * term of row {@code r} is {@code -sum(output[r] * target[r])}, which for a one-hot target row is
	 * the negated log-probability at the target class. The mean of the terms is the loss.
	 *
	 * @param output producer for the model's log-probability outputs
	 * @param target producer for the one-hot encoded target labels, of the same size as the output
	 * @return a producer of the negative log likelihood of each row
	 */
	@Override
	public CollectionProducer loss(Producer<PackedCollection> output,
								   Producer<PackedCollection> target) {
		TraversalPolicy rows = padDimensions(shape(output), 2);
		return c(output).reshape(rows).multiply(c(target).reshape(rows)).sum(1).minus();
	}

	/**
	 * Computes the gradient of the averaged NLL loss whose terms are given by {@link #loss}.
	 * <p>
	 * For the one-hot encoded targets this loss requires, the gradient of the mean over
	 * {@code rows} rows is {@code -target / rows}, where {@code rows} is the leading dimension of
	 * the output after padding its shape to two dimensions (the rows the {@link #loss} terms are
	 * computed for). It is produced on the device rather than assembled on the host.
	 * </p>
	 *
	 * @param output producer for the model's log-probability outputs, whose shape determines
	 *               the number of rows
	 * @param target producer for the one-hot encoded target labels
	 * @return a producer for the gradient ({@code -1 / rows} at each row's target index)
	 */
	@Override
	public Producer<PackedCollection> gradient(Producer<PackedCollection> output,
												  Producer<PackedCollection> target) {
		int rows = padDimensions(shape(output), 2).length(0);
		return c(target).multiply(-1.0 / rows);
	}
}
