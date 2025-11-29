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

import org.almostrealism.hardware.OperationList;

import java.util.function.Supplier;

/**
 * A {@link TemporalFactor} that also has {@link Cellular} lifecycle management.
 *
 * <p>This interface combines:
 * <ul>
 *   <li>{@link TemporalFactor} - Time-evolving factor transformation</li>
 *   <li>{@link Cellular} - Graph connectivity and lifecycle management</li>
 * </ul>
 *
 * <p>Use this interface for factors that need to:
 * <ul>
 *   <li>Transform input values (Factor)</li>
 *   <li>Evolve over time (Temporal)</li>
 *   <li>Participate in computation graphs (Node)</li>
 *   <li>Support initialization and reset (Setup, Lifecycle)</li>
 * </ul>
 *
 * <h2>Example Implementation</h2>
 * <pre>{@code
 * public class AdaptiveFactor implements CellularTemporalFactor<PackedCollection> {
 *     private double scale = 1.0;
 *
 *     @Override
 *     public Producer<PackedCollection> getResultant(Producer<PackedCollection> value) {
 *         return multiply(value, c(scale));
 *     }
 *
 *     @Override
 *     public Supplier<Runnable> tick() {
 *         return () -> () -> scale *= 0.99;  // Decay over time
 *     }
 *
 *     @Override
 *     public void reset() {
 *         scale = 1.0;  // Reset to initial state
 *     }
 * }
 * }</pre>
 *
 * @param <T> the type of data this factor operates on
 * @see TemporalFactor
 * @see Cellular
 * @see CombinedFactor
 */
public interface CellularTemporalFactor<T> extends TemporalFactor<T>, Cellular {
	/**
	 * Returns an operation that performs setup for this factor.
	 * <p>The default implementation returns an empty operation list.
	 * Subclasses should override this if initialization is needed.
	 *
	 * @return a supplier providing the setup operation
	 */
	@Override
	default Supplier<Runnable> setup() {
		return new OperationList();
	}
}
