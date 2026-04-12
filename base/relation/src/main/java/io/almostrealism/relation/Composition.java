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

package io.almostrealism.relation;

/**
 * A functional interface for combining two {@link Producer}s into a single producer.
 *
 * <p>{@link Composition} represents binary operations on producers. It takes two
 * independent producers and combines them into a new producer that computes a
 * result based on both inputs.</p>
 *
 * <h2>Common Use Cases</h2>
 * <ul>
 *   <li>Arithmetic operations (add, subtract, multiply, divide)</li>
 *   <li>Logical operations (and, or)</li>
 *   <li>Concatenation or merging of data</li>
 *   <li>Pairwise operations on collections</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Create an addition composition
 * Composition<Tensor> add = (a, b) -> {
 *     return createAddProducer(a, b);
 * };
 *
 * // Combine two producers
 * Producer<Tensor> sum = add.compose(tensorA, tensorB);
 * }</pre>
 *
 * <h2>Chaining with Factors</h2>
 * <p>Compositions can be followed by a {@link Factor} transformation using
 * {@link #andThen(Factor)}. This creates a new composition that first combines
 * the inputs, then applies the factor to the result.</p>
 *
 * <h2>Relationship to Other Types</h2>
 * <ul>
 *   <li>{@link Composition} - Combines two producers (binary operation)</li>
 *   <li>{@link Factor} - Transforms one producer (unary operation)</li>
 * </ul>
 *
 * @param <T> the type of the computation result
 *
 * @see Producer
 * @see Factor
 *
 * @author Michael Murray
 */
@FunctionalInterface
public interface Composition<T> {
	/**
	 * Combines two {@link Producer}s into a single {@link Producer}.
	 *
	 * <p>The semantics of how the producers are combined depends on the
	 * specific implementation. Common patterns include mathematical operations,
	 * data merging, or logical combinations.</p>
	 *
	 * @param a the first input producer
	 * @param b the second input producer
	 * @return a producer that combines both inputs
	 */
	Producer<T> compose(Producer<T> a, Producer<T> b);

	/**
	 * Creates a new composition that applies a {@link Factor} to the result.
	 *
	 * <p>The resulting composition first combines the two input producers
	 * using this composition, then applies the {@code next} factor to
	 * transform the result.</p>
	 *
	 * @param next the factor to apply after composition
	 * @return a new composition that includes the factor transformation
	 */
	default Composition<T> andThen(Factor<T> next) {
		return (a, b) -> next.getResultant(Composition.this.compose(a, b));
	}
}
