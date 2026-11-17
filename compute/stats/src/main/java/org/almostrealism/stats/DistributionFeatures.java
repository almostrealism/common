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
 *   <li>Computing the softmax of a collection of logits, and a masked variant that treats
 *       zero-valued elements as padding</li>
 * </ul>
 *
 * <h2>Discrete Sampling</h2>
 * <pre>{@code
 * DistributionFeatures features = new DistributionFeatures() {};
 *
 * PackedCollection probs = new PackedCollection(3);
 * probs.set(0, 0.2);  // 20%
 * probs.set(1, 0.5);  // 50%
 * probs.set(2, 0.3);  // 30%
 *
 * int index = features.sample(probs);  // Returns 0, 1, or 2 based on probabilities
 * }</pre>
 *
 * <h2>Softmax</h2>
 * <pre>{@code
 * // Convert logits to probabilities: exp(x_i) / sum(exp(x_j)) for every logit, including 0.0
 * CollectionProducer logits = ...;
 * CollectionProducer probs = features.softmax(logits);
 *
 * // Without max subtraction (same result, but can overflow for large logits)
 * CollectionProducer unshifted = features.softmax(logits, false);
 *
 * // Elements equal to exactly 0.0 are padding: they receive probability 0
 * CollectionProducer masked = features.softmaxIgnoreZero(logits, true);
 * }</pre>
 *
 * <p>Like all {@code Features} interfaces, this is a mixin: a type that needs these
 * operations should <em>implement</em> this interface (the methods are stateless
 * {@code default} methods) rather than accept or hold a {@code Features} instance —
 * passing one around as an object defeats the purpose of the pattern.</p>
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
	default int sample(PackedCollection distribution) {
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
	default int sample(PackedCollection distribution, int n) {
		double[] probabilities = distribution.toArray(0, n);
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
	 * Applies the softmax function to convert logits to probabilities, subtracting the
	 * maximum logit before exponentiation for numerical stability.
	 *
	 * <p>The result is {@code softmax(x_i) = exp(x_i) / sum(exp(x_j))} for every finite
	 * logit, including {@code 0.0}. Subtracting {@code max(x)} from every logit leaves the
	 * result unchanged mathematically but keeps every exponent at or below zero, which
	 * prevents overflow for large logits.</p>
	 *
	 * @param <T> the collection type
	 * @param input the input logits
	 * @return a producer that computes the softmax probabilities
	 * @see #softmaxIgnoreZero(CollectionProducer, boolean)
	 */
	default <T extends PackedCollection> CollectionProducer softmax(CollectionProducer input) {
		return softmax(input, true);
	}

	/**
	 * Applies the softmax function {@code exp(x_i) / sum(exp(x_j))} to convert logits to
	 * probabilities. Every element of the input, including one equal to {@code 0.0}, is a
	 * logit that takes part in the distribution. Each item at the traversal axis of the
	 * input (for example each row of {@code traverse(1, matrix)}) is normalized on its own.
	 *
	 * @param <T> the collection type
	 * @param input the input logits
	 * @param subtractMax if true, subtracts the maximum logit before exponentiation, which
	 *                    leaves the result unchanged but prevents overflow for large logits
	 * @return a producer that computes the softmax probabilities
	 * @see #softmaxIgnoreZero(CollectionProducer, boolean)
	 */
	default <T extends PackedCollection> CollectionProducer softmax(CollectionProducer input, boolean subtractMax) {
		int size = shape(input).getSize();
		CollectionProducer o = input.traverse();
		if (subtractMax) {
			o = o.subtract(input.max().expand(size));
		}

		o = o.exp().consolidate();
		return o.divide(o.sum().expand(size));
	}

	/**
	 * Applies a softmax in which every input element equal to exactly {@code 0.0} is treated
	 * as absent (padding) rather than as a logit: such an element receives probability
	 * {@code 0} and is excluded from the normalizing sum, so the remaining elements form the
	 * distribution on their own.
	 *
	 * <p>This is built from {@code subtractIgnoreZero} and {@code expIgnoreZero}. Because the
	 * mask is the value {@code 0.0} itself, a genuine logit of {@code 0.0} cannot be
	 * represented and is masked as well; use {@link #softmax(CollectionProducer, boolean)}
	 * for an ordinary softmax. When {@code subtractMax} is true the maximum is taken over
	 * every element, padding included, so for a row whose remaining logits are all negative
	 * the subtracted value is {@code 0.0}.</p>
	 *
	 * @param <T> the collection type
	 * @param input the input logits, with {@code 0.0} marking absent elements
	 * @param subtractMax if true, subtracts the maximum element before exponentiation
	 * @return a producer that computes the masked softmax probabilities
	 */
	default <T extends PackedCollection> CollectionProducer softmaxIgnoreZero(CollectionProducer input, boolean subtractMax) {
		int size = shape(input).getSize();
		CollectionProducer o = input.traverse();
		if (subtractMax) {
			o = o.subtractIgnoreZero(input.max().expand(size));
		}

		o = o.expIgnoreZero().consolidate();
		return o.divide(o.sum().expand(size));
	}
}
