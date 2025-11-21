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

package org.almostrealism.calculus;

import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

/**
 * Interface for producers that can provide an alternative delta (gradient) computation strategy.
 *
 * <p>
 * {@link DeltaAlternate} allows a producer to override the default automatic differentiation
 * behavior by providing a custom gradient computation. This is useful for:
 * <ul>
 *   <li>Operations with known analytical gradients that are more efficient than automatic differentiation</li>
 *   <li>Custom operations where the standard chain rule doesn't apply cleanly</li>
 *   <li>Optimized gradient computations for specific mathematical functions</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <p>
 * When a producer implements this interface, the automatic differentiation system will call
 * {@link #getDeltaAlternate()} instead of using the standard delta computation chain:
 * </p>
 * <pre>{@code
 * public class CustomOperation<T extends PackedCollection<?>>
 *         extends CollectionProducerComputationBase<T, T>
 *         implements DeltaAlternate<T> {
 *
 *     @Override
 *     public CollectionProducer<T> getDeltaAlternate() {
 *         // Return a custom gradient computation
 *         return efficientGradient();
 *     }
 * }
 * }</pre>
 *
 * <h2>Example: Exponential Function</h2>
 * <pre>{@code
 * // For f(x) = e^x, the derivative is also e^x
 * // More efficient to reuse the forward computation than to apply chain rule
 * public class ExponentialComputation extends ... implements DeltaAlternate<T> {
 *     @Override
 *     public CollectionProducer<T> getDeltaAlternate() {
 *         // Gradient of e^x is e^x, so just return this computation
 *         return this;
 *     }
 * }
 * }</pre>
 *
 * @param <T>  the packed collection type
 * @author  Michael Murray
 * @see org.almostrealism.collect.CollectionProducer#delta
 * @see DeltaFeatures
 */
public interface DeltaAlternate<T extends PackedCollection<?>> {
	/**
	 * Returns an alternative delta (gradient) computation for this producer.
	 *
	 * <p>
	 * This method is called by the automatic differentiation system when it encounters
	 * a producer that implements {@link DeltaAlternate}, allowing the producer to provide
	 * an optimized or specialized gradient computation instead of using the standard
	 * chain rule approach.
	 * </p>
	 *
	 * @return the alternative gradient computation
	 */
	CollectionProducer<T> getDeltaAlternate();
}
