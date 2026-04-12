/*
 * Copyright 2020 Michael Murray
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

package org.almostrealism.graph;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;

import java.util.function.Supplier;

/**
 * A cell that computes a running average of received values within each tick period.
 *
 * <p>{@code RunningAverageCell} accumulates all values pushed to it between ticks,
 * computing their mean and storing it as the cached value. On each tick, the average
 * is transferred downstream and the accumulators are reset.</p>
 *
 * <p>This is useful for smoothing noisy signals or computing per-frame averages
 * in temporal processing pipelines.</p>
 *
 * @see CollectionCachedStateCell
 * @author Michael Murray
 */
public class RunningAverageCell extends CollectionCachedStateCell {
	/** Running total of all values accumulated since the last tick. */
	private double total;

	/** Count of values pushed since the last tick. */
	private int pushes;

	/**
	 * {@inheritDoc}
	 *
	 * <p>Accumulates the incoming value into the running total and updates
	 * the cached value to the current running average.</p>
	 *
	 * @param protein the data producer to accumulate
	 * @return a supplier that performs the accumulation
	 */
	@Override
	public Supplier<Runnable> push(Producer<PackedCollection> protein) {
		return () -> () -> {
			this.total = total + protein.get().evaluate().toArray(0, 1)[0];
			this.pushes++;

			// Update the cached value to the current
			// running average of values received
			PackedCollection result = new PackedCollection(1);
			result.setMem(0, this.total / pushes);
			setCachedValue(result);
		};
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Resets the running total and push count, then delegates to the
	 * parent tick to transfer the average downstream.</p>
	 *
	 * @return a supplier that performs the tick and resets the accumulators
	 */
	@Override
	public Supplier<Runnable> tick() {
		Supplier<Runnable> tick = super.tick();
		
		return () -> () -> {
			this.total = 0;
			this.pushes = 0;
			tick.get().run();
		};
	}
}
