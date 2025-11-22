/*
 * Copyright 2025 Michael Murray
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

import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Producer;
import org.almostrealism.time.Temporal;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.hardware.OperationList;

import java.util.function.Supplier;

/**
 * A stateful cell that maintains cached values across processing cycles.
 * CachedStateCell implements a double-buffering pattern where incoming data is
 * stored in a cached value, and on each tick, the cached value is transferred
 * to an output value that is then propagated downstream.
 *
 * <p>This cell is essential for temporal processing where values need to be
 * accumulated or delayed across time steps. It implements both {@link Temporal}
 * for time-based processing and {@link Factor} for functional composition.</p>
 *
 * <h2>Double-Buffering Pattern</h2>
 * <p>The cell maintains two values:</p>
 * <ul>
 *   <li><b>cachedValue</b> - Accumulates incoming data via {@link #push(Producer)}</li>
 *   <li><b>outValue</b> - The value propagated downstream, updated on each tick</li>
 * </ul>
 *
 * <p>On each {@link #tick()}, the cached value is copied to the output value,
 * then the cache is reset. This ensures stable output during a processing cycle
 * while allowing new values to accumulate.</p>
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>{@link #setup()} - Resets both cached and output values</li>
 *   <li>{@link #push(Producer)} - Stores incoming data to the cached value</li>
 *   <li>{@link #tick()} - Transfers cached to output, resets cache, and pushes output</li>
 *   <li>{@link #next()} - Returns a producer for the current output value</li>
 * </ol>
 *
 * <h2>Implementation Requirements</h2>
 * <p>Subclasses must implement:</p>
 * <ul>
 *   <li>{@link #assign(Producer, Producer)} - How to copy a value from one location to another</li>
 *   <li>{@link #reset(Producer)} - How to reset a value to its initial state</li>
 * </ul>
 *
 * @param <T> the type of data processed, typically {@link org.almostrealism.collect.PackedCollection}
 * @see FilteredCell
 * @see Temporal
 * @author Michael Murray
 */
public abstract class CachedStateCell<T> extends FilteredCell<T> implements Factor<T>, Source<T>, Temporal {
	private final T cachedValue;
	private final T outValue;

	/**
	 * Creates a new CachedStateCell with blank values for the cache and output.
	 *
	 * @param blank an evaluable that produces blank/empty values of type T
	 */
	public CachedStateCell(Evaluable<T> blank) {
		super(null);
		cachedValue = blank.evaluate();
		outValue = blank.evaluate();
		setFilter(this);
	}

	/**
	 * Sets the cached value by executing an assignment operation.
	 *
	 * @param v the value to assign to the cache
	 */
	public void setCachedValue(T v) { assign(p(cachedValue), p(v)).get().run(); }

	/**
	 * Returns the current cached value.
	 *
	 * @return the cached value that will be transferred to output on the next tick
	 */
	public T getCachedValue() { return cachedValue; }

	/**
	 * Returns the current output value.
	 *
	 * @return the output value that is propagated downstream
	 */
	protected T getOutputValue() { return outValue; }

	/**
	 * Returns a producer for the output value. The input parameter is ignored
	 * as this cell always outputs its stored output value.
	 *
	 * @param value ignored - the cell outputs its stored value regardless
	 * @return a producer for the output value
	 */
	@Override
	public Producer<T> getResultant(Producer<T> value) {
		return p(outValue);
	}

	/**
	 * Returns a producer for the next output value.
	 * Implements the {@link Source} interface.
	 *
	 * @return a producer for the output value
	 */
	@Override
	public Producer<T> next() { return getResultant(null); }

	/**
	 * Always returns false as this cell continues producing values indefinitely.
	 *
	 * @return false
	 */
	@Override
	public boolean isDone() { return false; }

	/**
	 * Stores the incoming data into the cached value.
	 * This overrides the default push behavior to accumulate data for the next tick.
	 *
	 * @param protein the incoming data producer
	 * @return operations to assign the incoming data to the cache
	 */
	@Override
	public Supplier<Runnable> push(Producer<T> protein) {
		return assign(p(cachedValue), protein);
	}

	/**
	 * Pushes the current value through the parent class's push mechanism.
	 * Used internally during tick processing.
	 *
	 * @return operations to push the value downstream
	 */
	protected Supplier<Runnable> pushValue() {
		return super.push(null);
	}

	/**
	 * Abstract method to assign a value from one producer to another.
	 * Subclasses must implement this to define how data is copied.
	 *
	 * @param out the destination producer
	 * @param in the source producer
	 * @return operations to perform the assignment
	 */
	protected abstract Supplier<Runnable> assign(Producer<T> out, Producer<T> in);

	/**
	 * Abstract method to reset a value to its initial state.
	 * Subclasses must implement this to define how data is cleared.
	 *
	 * @param out the producer to reset
	 * @return operations to perform the reset
	 */
	protected abstract Supplier<Runnable> reset(Producer<T> out);

	/**
	 * Resets both the cached and output values to their initial states.
	 *
	 * @return operations to reset both values
	 */
	@Override
	public Supplier<Runnable> setup() {
		String name = getClass().getSimpleName();
		if (name == null || name.length() <= 0) name = "anonymous";
		OperationList reset = new OperationList(name + " Setup");
		reset.add(reset(p(cachedValue)));
		reset.add(reset(p(outValue)));
		return reset;
	}

	/**
	 * Performs the tick operation: transfers cached value to output,
	 * resets the cache, and pushes the output downstream.
	 *
	 * <p>This is the core temporal processing method that should be called
	 * once per time step in temporal processing scenarios.</p>
	 *
	 * @return operations to perform the tick
	 */
	@Override
	public Supplier<Runnable> tick() {
		String name = getClass().getSimpleName();
		if (name == null || name.length() <= 0) name = "anonymous";
		OperationList tick = new OperationList("CachedStateCell (" + name + ") Tick");
		tick.add(assign(p(outValue), p(cachedValue)));
		tick.add(reset(p(cachedValue)));
		tick.add(super.push(null));
		return tick;
	}
}
