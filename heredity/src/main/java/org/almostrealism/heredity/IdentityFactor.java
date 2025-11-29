/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.heredity;

import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Producer;

/**
 * A {@link Factor} implementation that returns the input unchanged.
 *
 * <p>This factor acts as a pass-through, useful as a placeholder or default
 * when a factor is required but no transformation should be applied.
 * It is also useful in genetic algorithms when you want to represent the
 * absence of an effect or a neutral mutation.
 *
 * <h2>Example Usage</h2>
 * <pre>{@code
 * // Create an identity factor
 * Factor<PackedCollection> passThrough = new IdentityFactor<>();
 *
 * // Apply to input - returns the same producer
 * Producer<PackedCollection> input = ...;
 * Producer<PackedCollection> result = passThrough.getResultant(input);
 * // result == input
 * }</pre>
 *
 * @param <T> the type of data this factor operates on
 * @see Factor
 * @see ScaleFactor
 */
public class IdentityFactor<T> implements Factor<T> {
	/**
	 * Returns the input producer unchanged.
	 *
	 * @param value the input producer
	 * @return the same input producer, unchanged
	 */
	@Override
	public Producer<T> getResultant(Producer<T> value) { return value; }
}
