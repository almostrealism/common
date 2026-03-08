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

import io.almostrealism.uml.Signature;

/**
 * A functional interface for transforming one {@link Producer} into another
 * while preserving the result type.
 *
 * <p>{@link Factor} represents a unary transformation on producers. Unlike
 * {@link Composition} which combines two producers, a {@link Factor} takes
 * a single producer and produces a modified version.</p>
 *
 * <h2>Common Use Cases</h2>
 * <ul>
 *   <li>Applying mathematical operations (negation, normalization, etc.)</li>
 *   <li>Wrapping producers with additional behavior (caching, logging)</li>
 *   <li>Optimization transformations</li>
 *   <li>Type conversions that preserve the result type</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Create a factor that normalizes tensors
 * Factor<Tensor> normalize = input -> {
 *     Producer<Tensor> norm = computeNorm(input);
 *     return divide(input, norm);
 * };
 *
 * // Apply the transformation
 * Producer<Tensor> normalized = normalize.getResultant(rawData);
 * }</pre>
 *
 * <h2>Chaining Factors</h2>
 * <p>Factors can be chained using {@link #andThen(Factor)} to create
 * composite transformations that apply multiple operations in sequence.</p>
 *
 * <h2>Relationship to Other Types</h2>
 * <ul>
 *   <li>{@link Composition} - Combines two producers (binary operation)</li>
 *   <li>{@link Factor} - Transforms one producer (unary operation)</li>
 * </ul>
 *
 * @param <T> the type of the computation result (preserved through transformation)
 *
 * @see Producer
 * @see Composition
 *
 * @author Michael Murray
 */
@FunctionalInterface
public interface Factor<T> extends Function<T, T>, Signature {
	/**
	 * Transforms the given {@link Producer} into a new {@link Producer}.
	 *
	 * <p>The transformation should preserve the semantic meaning of the
	 * computation while potentially optimizing, decorating, or modifying
	 * its behavior.</p>
	 *
	 * @param value the input producer to transform
	 * @return the transformed producer
	 */
	Producer<T> getResultant(Producer<T> value);

	/**
	 * Creates a composite factor that applies this transformation followed
	 * by another.
	 *
	 * <p>The resulting factor first applies this factor's transformation,
	 * then applies the {@code next} factor to the result.</p>
	 *
	 * @param next the factor to apply after this one
	 * @return a composite factor
	 * @throws UnsupportedOperationException if chaining is not supported
	 */
	default Factor<T> andThen(Factor<T> next) {
		throw new UnsupportedOperationException();
	}
}
