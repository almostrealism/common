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

public class TemporalFactorFromCell<T> implements CellularTemporalFactor<T> {
	private Cell<T> cell;
	private Producer<T> destination;
	private Function<Producer<T>, Receptor<T>> assignment;
	private BiFunction<Producer<T>, Producer<T>, Producer<T>> combine;

	public TemporalFactorFromCell(Cell<T> cell, Producer<T> destination,
								  Function<Producer<T>, Receptor<T>> assignment,
								  BiFunction<Producer<T>, Producer<T>, Producer<T>> combine) {
		this.cell = Objects.requireNonNull(cell);
		this.destination = destination;
		this.assignment = Objects.requireNonNull(assignment);
		this.combine = Objects.requireNonNull(combine);
	}

	public Cell<T> getCell() { return cell; }

	@Override
	public Producer<T> getResultant(Producer<T> value) {
		cell.setReceptor(assignment.apply(destination));
		return combine.apply(destination, value);
	}

	@Override
	public Supplier<Runnable> setup() {
		return cell.setup();
	}

	@Override
	public Supplier<Runnable> tick() {
		String name = cell.getClass().getSimpleName();
		if (name == null || name.length() <= 0) name = "anonymous";
		OperationList tick = new OperationList("CellularTemporalFactor (from " + name + ") Tick");
		tick.add(cell.push(null));
		if (cell instanceof Temporal) tick.add(((Temporal) cell).tick());
		return tick;
	}

	@Override
	public void reset() {
		CellularTemporalFactor.super.reset();
		cell.reset();
	}
}
