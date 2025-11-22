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

import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.PackedCollection;

import java.util.stream.IntStream;

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
 * The gradient is sparse: -1 at the target class index, 0 elsewhere.
 * </p>
 * <pre>
 * dL/dOutput[i] = -1 if i == target_class, 0 otherwise
 * </pre>
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
 * // Compute loss (assumes softmax outputs and one-hot targets)
 * double loss = nll.loss(predictions, oneHotTargets);
 *
 * // Get gradient for backpropagation
 * Producer<PackedCollection<?>> grad = nll.gradient(outputProducer, targetProducer);
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
	 * Computes the negative log likelihood loss.
	 * <p>
	 * For each sample in the batch, extracts the log-probability at the target class
	 * index (determined by argmax of the target) and negates it. Returns the average
	 * loss across all samples.
	 * </p>
	 *
	 * @param output the model's log-probability outputs
	 * @param target the one-hot encoded target labels
	 * @return the average negative log likelihood
	 * @throws IllegalArgumentException if batch sizes don't match
	 */
	@Override
	public double loss(PackedCollection<?> output, PackedCollection<?> target) {
		PackedCollection<?> o = output.reshape(padDimensions(output.getShape(), 2)).traverse(1);
		PackedCollection<?> t = target.reshape(padDimensions(target.getShape(), 2)).traverse(1);

		int bs = o.getShape().length(0);
		if (bs != t.getShape().length(0)) {
			throw new IllegalArgumentException("Batch size mismatch");
		}

		return IntStream.range(0, bs).mapToDouble(i -> {
			PackedCollection<?> v = (PackedCollection<?>) t.get(i);
			return -o.get(i).toDouble(v.argmax());
		}).average().orElse(0.0);
	}

	/**
	 * Computes the gradient of NLL loss.
	 * <p>
	 * The gradient is -1 at the target class position and 0 elsewhere.
	 * </p>
	 *
	 * @param output producer for the model's log-probability outputs
	 * @param target producer for the one-hot encoded target labels
	 * @return a producer for the sparse gradient (-1 at target index)
	 */
	@Override
	public Producer<PackedCollection<?>> gradient(Producer<PackedCollection<?>> output,
												  Producer<PackedCollection<?>> target) {
		return () -> {
			Evaluable<PackedCollection<?>> out = output.get();
			Evaluable<PackedCollection<?>> valid = target.get();

			return args -> {
				PackedCollection<?> o = out.evaluate(args).traverse(1);
				PackedCollection<?> v = valid.evaluate(args).traverse(1);

				int bs = o.getShape().length(0);
				double grad[] = new double[o.getShape().getTotalSize()];

				for (int n = 0; n < o.getShape().length(0); n++) {
					double od[] = o.get(n).toArray();
					int idx = ((PackedCollection<?>) v.get(n)).argmax();

					for (int i = 0; i < od.length; i++) {
						if (i == idx) {
							grad[n * bs + i] = -1.0;
						} else {
							grad[n * bs +i] = 0.0;
						}
					}
				}

				return PackedCollection.of(grad).reshape(o.getShape());
			};
		};
	}
}
