/*
 * Copyright 2022 Michael Murray
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

package org.almostrealism.graph.temporal;

import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Provider;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.CollectionCachedStateCell;
import org.almostrealism.hardware.OperationList;

import java.util.function.Supplier;

/**
 * Abstract base class for temporal cells that process collections over time.
 *
 * <p>{@code CollectionTemporalCellAdapter} extends {@link CollectionCachedStateCell}
 * to provide a foundation for cells that perform time-based processing on
 * {@link PackedCollection} data. It manages setup operations and provides
 * common constants for audio/signal processing.</p>
 *
 * <p>This class serves as the base for audio processing components like
 * {@link WaveCell}, providing:</p>
 * <ul>
 *   <li>A setup operation list for initialization sequences</li>
 *   <li>Common mathematical constants (PI) for signal processing</li>
 *   <li>A depth parameter for controlling processing intensity</li>
 *   <li>Factory method for creating simple adapter instances</li>
 * </ul>
 *
 * <p>Subclasses should implement the {@code push} method to define how
 * input values are processed and forwarded.</p>
 *
 * @author Michael Murray
 * @see CollectionCachedStateCell
 * @see WaveCell
 */
public abstract class CollectionTemporalCellAdapter extends CollectionCachedStateCell {
	/** Mathematical constant PI for signal processing calculations. */
	public static final double PI = Math.PI;

	/** Global depth parameter for controlling processing intensity. Default is 1.0. */
	public static double depth = 1.0;

	private final OperationList setup;

	/**
	 * Creates a new temporal cell adapter with an empty setup operation list.
	 */
	public CollectionTemporalCellAdapter() {
		setup = new OperationList("ScalarTemporalCellAdapter Setup");
	}

	/**
	 * Adds a setup operation to be executed during initialization.
	 *
	 * @param setup the setup operation to add
	 */
	public void addSetup(Supplier<Runnable> setup) {
		this.setup.add(setup);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Returns a combined setup operation that includes the parent
	 * setup and all operations added via {@link #addSetup}.</p>
	 *
	 * @return a supplier that provides the combined setup operation
	 */
	@Override
	public Supplier<Runnable> setup() {
		OperationList setup = new OperationList("ScalarTemporalCellAdapter Setup");
		setup.add(super.setup());
		setup.add(this.setup);
		return setup;
	}

	/**
	 * Creates a simple temporal cell adapter from a producer.
	 *
	 * <p>The returned adapter assigns the producer's value to the cached
	 * value storage when {@code push} is called.</p>
	 *
	 * @param p the producer to wrap
	 * @return a new temporal cell adapter that reads from the producer
	 */
	public static CollectionTemporalCellAdapter from(Producer<PackedCollection> p) {
		return new CollectionTemporalCellAdapter() {
			@Override
			public Supplier<Runnable> push(Producer<PackedCollection> protein) {
				return assign(() -> new Provider<>(getCachedValue()), p);
			}
		};
	}
}
