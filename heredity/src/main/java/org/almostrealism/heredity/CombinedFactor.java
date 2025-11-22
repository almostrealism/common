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

import io.almostrealism.cycle.Setup;
import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Producer;
import io.almostrealism.lifecycle.Lifecycle;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.time.Temporal;

import java.util.function.Supplier;

/**
 * A {@link Factor} that chains two factors together in sequence.
 *
 * <p>This class composes two factors such that the output of the first factor
 * becomes the input of the second factor. This enables building complex
 * transformations from simpler components.
 *
 * <p>The combined factor also properly propagates lifecycle events (setup, tick, reset)
 * to both underlying factors if they support those interfaces.
 *
 * <h2>Transformation Order</h2>
 * <p>For factors A and B: {@code result = B(A(input))}
 * <p>Factor A is applied first, then factor B is applied to A's output.
 *
 * <h2>Example Usage</h2>
 * <pre>{@code
 * // Create two factors
 * Factor<PackedCollection<?>> scale = new ScaleFactor(2.0);
 * Factor<PackedCollection<?>> offset = ...;  // Some offset factor
 *
 * // Combine them: first scale, then offset
 * CombinedFactor<PackedCollection<?>> combined = new CombinedFactor<>(scale, offset);
 *
 * // Apply combined factor
 * Producer<PackedCollection<?>> result = combined.getResultant(input);
 * // Equivalent to: offset.getResultant(scale.getResultant(input))
 * }</pre>
 *
 * @param <T> the type of data this factor operates on
 * @see TemporalFactor#andThen(Factor)
 * @see Factor
 * @see CellularTemporalFactor
 */
public class CombinedFactor<T> implements CellularTemporalFactor<T> {
	private Factor<T> a, b;

	/**
	 * Constructs a new {@code CombinedFactor} that chains factors A and B.
	 * <p>Factor A is applied first, then factor B.
	 *
	 * @param a the first factor to apply
	 * @param b the second factor to apply (receives output from A)
	 */
	public CombinedFactor(Factor<T> a, Factor<T> b) {
		this.a = a;
		this.b = b;
	}

	/**
	 * Returns the first factor in the chain.
	 *
	 * @return factor A
	 */
	public Factor<T> getA() { return a; }

	/**
	 * Returns the second factor in the chain.
	 *
	 * @return factor B
	 */
	public Factor<T> getB() { return b; }

	/**
	 * Returns a setup operation that initializes both factors if they support setup.
	 *
	 * @return a runnable supplier that performs setup on both factors
	 */
	@Override
	public Supplier<Runnable> setup() {
		OperationList setup = new OperationList("CombinedFactor Setup");
		if (a instanceof Setup) setup.add(((Setup) a).setup());
		if (b instanceof Setup) setup.add(((Setup) b).setup());
		return setup;
	}

	/**
	 * Applies both factors in sequence to the input.
	 * <p>Factor A is applied first, then factor B is applied to A's output.
	 *
	 * @param value the input producer
	 * @return a producer representing B(A(value))
	 */
	@Override
	public Producer<T> getResultant(Producer<T> value) {
		return b.getResultant(a.getResultant(value));
	}

	/**
	 * Returns a tick operation that advances both factors if they are temporal.
	 *
	 * @return a runnable supplier that ticks both factors
	 */
	@Override
	public Supplier<Runnable> tick() {
		OperationList tick = new OperationList("CombinedFactor Tick");
		if (a instanceof Temporal) tick.add(((Temporal) a).tick());
		if (b instanceof Temporal) tick.add(((Temporal) b).tick());
		return tick;
	}

	/**
	 * Resets both factors if they implement the Lifecycle interface.
	 */
	@Override
	public void reset() {
		CellularTemporalFactor.super.reset();
		if (a instanceof Lifecycle) ((Lifecycle) a).reset();
		if (b instanceof Lifecycle) ((Lifecycle) b).reset();
	}
}
