/*
 * Copyright 2021 Michael Murray
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

import io.almostrealism.lifecycle.Setup;
import io.almostrealism.lifecycle.Lifecycle;
import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Producer;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.time.Temporal;

import java.util.function.Supplier;

/**
 * A {@link CellAdapter} that applies a {@link Factor} transformation to all
 * incoming data before forwarding it to the downstream receptor.
 *
 * <p>{@code FilteredCell} wraps any {@link Factor} implementation as a cell.
 * Setup, tick, and reset lifecycle events are delegated to the filter when it
 * implements the corresponding interface ({@link Setup}, {@link Temporal},
 * {@link Lifecycle}).</p>
 *
 * @param <T> the type of data processed, typically {@link org.almostrealism.collect.PackedCollection}
 * @see CellAdapter
 * @see CachedStateCell
 * @author Michael Murray
 */
public class FilteredCell<T> extends CellAdapter<T> implements Temporal {
	/** The factor that transforms incoming data before forwarding. */
	private Factor<T> filter;

	/**
	 * Creates a filtered cell with the specified transformation factor.
	 *
	 * @param filter the factor to apply to all incoming data
	 */
	public FilteredCell(Factor<T> filter) { this.filter = filter; }

	/**
	 * Updates the transformation factor applied to incoming data.
	 *
	 * @param filter the new factor
	 */
	protected void setFilter(Factor<T> filter) { this.filter = filter; }

	/**
	 * {@inheritDoc}
	 *
	 * <p>Delegates to the filter's setup if the filter implements {@link Setup}
	 * and is not this cell itself.</p>
	 *
	 * @return the filter's setup operation, or an empty operation list
	 */
	@Override
	public Supplier<Runnable> setup() {
		if (filter instanceof Setup && filter != this) {
			return ((Setup) filter).setup();
		} else {
			return new OperationList("FilteredCell Setup");
		}
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Applies the filter's transformation to the input before forwarding
	 * to the downstream receptor.</p>
	 *
	 * @param protein the incoming data producer
	 * @return a supplier that performs the filtered push
	 */
	@Override
	public Supplier<Runnable> push(Producer<T> protein) {
		return super.push(filter.getResultant(protein));
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Delegates to the filter's tick if the filter implements {@link Temporal}
	 * and is not this cell itself.</p>
	 *
	 * @return the filter's tick operation, or an empty operation list
	 */
	@Override
	public Supplier<Runnable> tick() {
		if (filter instanceof Temporal && filter != this) {
			return ((Temporal) filter).tick();
		} else {
			return new OperationList("FilteredCell Tick");
		}
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Resets the parent adapter and delegates to the filter's reset
	 * if the filter implements {@link Lifecycle} and is not this cell itself.</p>
	 */
	@Override
	public void reset() {
		super.reset();

		if (filter instanceof Lifecycle && filter != this) {
			((Lifecycle) filter).reset();
		}
	}
}
