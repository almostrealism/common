/*
 * Copyright 2023 Michael Murray
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
import org.almostrealism.collect.PackedCollection;

import io.almostrealism.relation.Producer;
import org.almostrealism.graph.Cell;
import org.almostrealism.graph.Receptor;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.heredity.CellularTemporalFactor;
import org.almostrealism.time.Temporal;

import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Adapter that wraps a {@link Cell} as a {@link CellularTemporalFactor}.
 *
 * <p>{@code TemporalFactorFromCell} bridges the cell-based computation model
 * with the factor-based processing model. It allows a cell to participate
 * in factor chains by wrapping the cell's output and combining it with
 * other factor values.</p>
 *
 * <p>The adapter works by:</p>
 * <ul>
 *   <li>Connecting the cell to a destination via a receptor assignment function</li>
 *   <li>Combining the cell's output with input values using a combine function</li>
 *   <li>Delegating lifecycle operations (setup, tick, reset) to the underlying cell</li>
 * </ul>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * Cell<PackedCollection> cell = new WaveCell(audioData, sampleRate);
 * Producer<PackedCollection> destination = ...;
 *
 * TemporalFactorFromCell<PackedCollection> factor = new TemporalFactorFromCell<>(
 *     cell,
 *     destination,
 *     dest -> new AssignmentReceptor(dest),
 *     (dest, input) -> add(dest, input)
 * );
 * }</pre>
 *
 * @param <T> the type of data processed by the cell
 * @author Michael Murray
 * @see CellularTemporalFactor
 * @see Cell
 */
public class TemporalFactorFromCell<T> implements CellularTemporalFactor<T> {
	private Cell<T> cell;
	private Producer<T> destination;
	private Function<Producer<T>, Receptor<T>> assignment;
	private BiFunction<Producer<T>, Producer<T>, Producer<T>> combine;

	/**
	 * Creates a new temporal factor wrapping the specified cell.
	 *
	 * @param cell        the cell to wrap (must not be null)
	 * @param destination the producer for the destination where cell output is stored
	 * @param assignment  function that creates a receptor for the destination (must not be null)
	 * @param combine     function that combines the destination value with input (must not be null)
	 * @throws NullPointerException if cell, assignment, or combine is null
	 */
	public TemporalFactorFromCell(Cell<T> cell, Producer<T> destination,
								  Function<Producer<T>, Receptor<T>> assignment,
								  BiFunction<Producer<T>, Producer<T>, Producer<T>> combine) {
		this.cell = Objects.requireNonNull(cell);
		this.destination = destination;
		this.assignment = Objects.requireNonNull(assignment);
		this.combine = Objects.requireNonNull(combine);
	}

	/**
	 * Returns the underlying cell.
	 *
	 * @return the wrapped cell
	 */
	public Cell<T> getCell() { return cell; }

	/**
	 * {@inheritDoc}
	 *
	 * <p>Connects the cell's receptor to the destination and returns a producer
	 * that combines the destination value with the input value.</p>
	 *
	 * @param value the input value to combine with the cell's output
	 * @return a producer that yields the combined result
	 */
	@Override
	public Producer<T> getResultant(Producer<T> value) {
		cell.setReceptor(assignment.apply(destination));
		return combine.apply(destination, value);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Delegates to the underlying cell's setup operation.</p>
	 *
	 * @return the cell's setup operation
	 */
	@Override
	public Supplier<Runnable> setup() {
		return cell.setup();
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Creates a tick operation that pushes data through the cell and,
	 * if the cell implements {@link Temporal}, also ticks the cell.</p>
	 *
	 * @return a combined tick operation
	 */
	@Override
	public Supplier<Runnable> tick() {
		String name = cell.getClass().getSimpleName();
		if (name == null || name.length() <= 0) name = "anonymous";
		OperationList tick = new OperationList("CellularTemporalFactor (from " + name + ") Tick");
		tick.add(cell.push(null));
		if (cell instanceof Temporal) tick.add(((Temporal) cell).tick());
		return tick;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Resets both the parent factor and the underlying cell.</p>
	 */
	@Override
	public void reset() {
		CellularTemporalFactor.super.reset();
		cell.reset();
	}
}
