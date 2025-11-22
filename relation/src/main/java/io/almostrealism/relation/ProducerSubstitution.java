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

package io.almostrealism.relation;

import java.util.function.Supplier;

/**
 * A mapping from an original {@link Producer} to its replacement.
 *
 * <p>{@link ProducerSubstitution} records a substitution relationship between
 * two producers. This is used during computation graph transformations to
 * track which producers should be replaced with alternatives.</p>
 *
 * <h2>Use Cases</h2>
 * <ul>
 *   <li>Optimization passes that replace inefficient patterns</li>
 *   <li>Variable binding during computation graph instantiation</li>
 *   <li>Caching transformations that replace computation with lookups</li>
 *   <li>Testing with mock producers</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Create a substitution for optimization
 * ProducerSubstitution<Tensor> sub = new ProducerSubstitution<>(
 *     expensiveComputation,
 *     cachedResult
 * );
 *
 * // During graph traversal, check for matches
 * if (sub.match(currentProducer)) {
 *     use(sub.getReplacement());
 * }
 * }</pre>
 *
 * <h2>Identity Matching</h2>
 * <p>The {@link #match(Supplier)} method uses reference equality ({@code ==})
 * to determine if a producer matches the original. This ensures that only the
 * exact original instance is substituted, not structurally equal copies.</p>
 *
 * @param <T> the result type of the producers
 *
 * @see Producer
 * @see ProducerFeatures#substitute(Producer, Producer)
 *
 * @author Michael Murray
 */
public class ProducerSubstitution<T> {
	private Producer<T> original;
	private Producer<T> replacement;

	/**
	 * Creates a new substitution mapping from original to replacement.
	 *
	 * @param original the producer to be replaced
	 * @param replacement the producer to use as a replacement
	 */
	public ProducerSubstitution(Producer<T> original, Producer<T> replacement) {
		this.original = original;
		this.replacement = replacement;
	}

	/**
	 * Returns the original producer that should be replaced.
	 *
	 * @return the original producer
	 */
	public Producer<T> getOriginal() { return original; }

	/**
	 * Returns the replacement producer to use instead of the original.
	 *
	 * @return the replacement producer
	 */
	public Producer<T> getReplacement() { return replacement; }

	/**
	 * Tests whether a producer matches the original in this substitution.
	 *
	 * <p>This method uses reference equality ({@code ==}) to determine
	 * if the given producer is the same instance as the original.</p>
	 *
	 * @param <V> the result type of the producer being tested
	 * @param producer the producer to test for matching
	 * @return {@code true} if the producer is the same instance as the original
	 */
	public <V> boolean match(Supplier<Evaluable<? extends V>> producer) {
		return producer == (Supplier) original;
	}
}
