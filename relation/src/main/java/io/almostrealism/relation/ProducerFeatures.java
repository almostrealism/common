/*
 * Copyright 2023 Michael Murray
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

/**
 * A mixin interface providing utility methods for working with {@link Producer}s.
 *
 * <p>{@link ProducerFeatures} is designed to be implemented by classes that need
 * convenient methods for producer manipulation, particularly delegation and
 * substitution. This follows the "features" pattern common in the framework,
 * where interfaces provide default implementations of utility methods.</p>
 *
 * <h2>Key Operations</h2>
 * <ul>
 *   <li><b>Delegation:</b> Create a producer that delegates to another</li>
 *   <li><b>Substitution:</b> Record a mapping from one producer to its replacement</li>
 * </ul>
 *
 * <h2>Usage Pattern</h2>
 * <pre>{@code
 * public class MyComputationBuilder implements ProducerFeatures {
 *     public Producer<Tensor> build() {
 *         Producer<Tensor> original = createOriginal();
 *         Producer<Tensor> optimized = optimize(original);
 *
 *         // Create delegation relationship
 *         return delegate(original, optimized);
 *     }
 *
 *     @Override
 *     public <T> Producer<?> delegate(Producer<T> original, Producer<T> actual) {
 *         // Custom delegation implementation
 *         return new DelegatingProducer<>(original, actual);
 *     }
 * }
 * }</pre>
 *
 * <h2>Producer Substitution</h2>
 * <p>The {@link #substitute(Producer, Producer)} method creates a {@link ProducerSubstitution}
 * that records a mapping between an original producer and its replacement. This is
 * useful for optimization passes and computation graph transformations.</p>
 *
 * @see Producer
 * @see ProducerSubstitution
 * @see Delegated
 *
 * @author Michael Murray
 */
public interface ProducerFeatures {
	/**
	 * Creates a delegating producer for the given producer.
	 *
	 * <p>This convenience method calls {@link #delegate(Producer, Producer)}
	 * with {@code null} as the original producer.</p>
	 *
	 * @param <T> the result type of the producer
	 * @param producer the producer to delegate to
	 * @return a producer that delegates to the given producer
	 */
	default <T> Producer<?> delegate(Producer<T> producer) {
		return delegate(null, producer);
	}

	/**
	 * Creates a delegating producer that maps from an original to an actual producer.
	 *
	 * <p>This method creates a producer that delegates its behavior to {@code actual}
	 * while maintaining a reference to the {@code original}. This is useful for:</p>
	 * <ul>
	 *   <li>Tracking optimization transformations</li>
	 *   <li>Preserving computation graph structure during rewrites</li>
	 *   <li>Enabling rollback or debugging of transformations</li>
	 * </ul>
	 *
	 * @param <T> the result type of the producers
	 * @param original the original producer (may be {@code null})
	 * @param actual the producer to actually delegate to
	 * @return a producer that delegates to {@code actual}
	 */
	<T> Producer<?> delegate(Producer<T> original, Producer<T> actual);

	/**
	 * Creates a {@link ProducerSubstitution} recording a replacement mapping.
	 *
	 * <p>This method creates a substitution record that can be used to transform
	 * computation graphs by replacing occurrences of the original producer with
	 * the replacement.</p>
	 *
	 * @param <T> the result type of the producers
	 * @param original the producer to be replaced
	 * @param replacement the producer to use as replacement
	 * @return a substitution record mapping original to replacement
	 */
	default <T> ProducerSubstitution<T> substitute(Producer<T> original, Producer<T> replacement) {
		return new ProducerSubstitution<>(original, replacement);
	}
}
