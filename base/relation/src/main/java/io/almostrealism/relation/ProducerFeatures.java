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
 * @see Producer
 * @see Delegated
 *
 * @author Michael Murray
 */
public interface ProducerFeatures {

}
