/*
 * Copyright 2024 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.stats;

import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

import java.util.Random;

/**
 * Provides methods for sampling from probability distributions and applying statistical transformations.
 *
 * <p>This interface extends {@link CollectionFeatures} to provide utilities for:</p>
 * <ul>
 *   <li>Sampling from discrete probability distributions</li>
 *   <li>Computing softmax transformations</li>
 *   <li>Statistical operations on packed collections</li>
 * </ul>
 *
 * <h2>Discrete Sampling</h2>
 * <pre>{@code
 * DistributionFeatures features = new DistributionFeatures() {};
 *
 * PackedCollection<?> probs = new PackedCollection<>(3);
 * probs.set(0, 0.2);  // 20%
 * probs.set(1, 0.5);  // 50%
 * probs.set(2, 0.3);  // 30%
 *
 * int index = features.sample(probs);  // Returns 0, 1, or 2 based on probabilities
 * }</pre>
 *
 * <h2>Softmax</h2>
 * <pre>{@code
 * // Convert logits to probabilities
 * CollectionProducer<?> logits = ...;
 * CollectionProducer<?> probs = features.softmax(logits);
 *
 * // Without max subtraction (less numerically stable)
 * CollectionProducer<?> probs = features.softmax(logits, false);
 * }</pre>
 *
 * @see CollectionFeatures
 */
public interface DistributionFeatures extends CollectionFeatures {
	/** Random number generator for sampling */
	Random rand = new Random();

	/**
	 * Samples an index from a discrete probability distribution.
	 * The distribution should sum to approximately 1.0.
	 *
	 * @param distribution the probability distribution
	 * @return a sampled index from 0 to distribution.getMemLength()-1
	 */
	default int sample(PackedCollection<?> distribution) {
		return sample(distribution, distribution.getMemLength());
	}

	/**
	 * Samples an index from the first n elements of a discrete probability distribution.
	 * Uses inverse transform sampling: generates a uniform random value and finds
	 * the first index where the cumulative probability exceeds that value.
	 *
	 * @param distribution the probability distribution
	 * @param n the number of elements to sample from
	 * @return a sampled index from 0 to n-1
	 */
	default int sample(PackedCollection<?> distribution, int n) {
		double probabilities[] = distribution.toArray(0, n);
		double r = rand.nextDouble();
		double d = 0.0;

		for (int i = 0; i < n; i++) {
			d += probabilities[i];

			if (r < d) {
				return i;
			}
		}

		return n - 1;
	}

	/**
	 * Applies the softmax function to convert logits to probabilities.
	 * Uses max subtraction for numerical stability.
	 *
	 * <p>The softmax function is defined as: softmax(x_i) = exp(x_i) / sum(exp(x_j))</p>
	 *
	 * <p>This method subtracts the maximum value before exponentiation to prevent
	 * numerical overflow, a standard trick for stable softmax computation.</p>
	 *
	 * @param <T> the collection type
	 * @param input the input logits
	 * @return a producer that computes the softmax probabilities
	 */
	default <T extends PackedCollection<?>> CollectionProducer<T> softmax(CollectionProducer<T> input) {
		return softmax(input, true);
	}

	/**
	 * Applies the softmax function to convert logits to probabilities.
	 *
	 * @param <T> the collection type
	 * @param input the input logits
	 * @param subtractMax if true, subtracts the max value before exp for numerical stability
	 * @return a producer that computes the softmax probabilities
	 */
	default <T extends PackedCollection<?>> CollectionProducer<T> softmax(CollectionProducer<T> input, boolean subtractMax) {
		int size = shape(input).getSize();
		CollectionProducer<PackedCollection<?>> o = (CollectionProducer) input;

		if (subtractMax) {
			o = o.max();
			o = o.expand(size);
			o = input.traverse().subtractIgnoreZero(o);
		}

		o = o.expIgnoreZero().consolidate();
		o = o.divide(o.sum().expand(size));
		return (CollectionProducer) o;
	}
}
