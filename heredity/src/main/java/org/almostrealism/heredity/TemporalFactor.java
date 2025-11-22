/*
 * Copyright 2021 Michael Murray
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
import org.almostrealism.hardware.OperationList;
import org.almostrealism.time.Temporal;

import java.util.function.Supplier;

/**
 * A {@link Factor} that can evolve over time through the {@link Temporal} interface.
 *
 * <p>This interface combines the transformation capabilities of a Factor with
 * the time-stepped evolution of a Temporal entity. It is useful for implementing
 * factors whose behavior changes over time, such as:
 * <ul>
 *   <li>Decaying scale factors</li>
 *   <li>Oscillating parameters</li>
 *   <li>Time-dependent transformations</li>
 * </ul>
 *
 * <h2>Example Usage</h2>
 * <pre>{@code
 * // Create a temporal factor
 * TemporalFactor<PackedCollection<?>> decayingFactor = new MyDecayingFactor(1.0);
 *
 * // Chain with another factor
 * Factor<PackedCollection<?>> combined = decayingFactor.andThen(otherFactor);
 *
 * // In time-stepped simulation:
 * decayingFactor.tick().get().run();  // Advance time by one step
 * }</pre>
 *
 * @param <T> the type of data this factor operates on
 * @see Factor
 * @see Temporal
 * @see CombinedFactor
 * @see CellularTemporalFactor
 */
public interface TemporalFactor<T> extends Factor<T>, Temporal {
	/**
	 * Creates a combined factor that applies this factor first, then the next factor.
	 *
	 * @param next the factor to apply after this one
	 * @return a new CombinedFactor that chains this and next
	 */
	default Factor<T> andThen(Factor<T> next) {
		return new CombinedFactor<>(this, next);
	}

	/**
	 * Returns an operation that advances this factor by one time step.
	 * <p>The default implementation returns an empty operation list.
	 * Subclasses should override this to implement time-dependent behavior.
	 *
	 * @return a supplier providing the tick operation
	 */
	default Supplier<Runnable> tick() { return new OperationList(); }
}
