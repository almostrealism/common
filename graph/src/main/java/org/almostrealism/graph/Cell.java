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

package org.almostrealism.graph;

import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Provider;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.heredity.Cellular;
import org.almostrealism.heredity.CellularTemporalFactor;
import org.almostrealism.time.Temporal;

import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

public interface Cell<T> extends Transmitter<T>, Receptor<T>, Cellular {
	default CellularTemporalFactor<T> toFactor(Supplier<T> value, Function<Producer<T>, Receptor<T>> assignment) {
		return toFactor(this, value, assignment);
	}

	default CellularTemporalFactor<T> toFactor(Supplier<T> value, Function<Producer<T>, Receptor<T>> assignment,
											   BiFunction<Producer<T>, Producer<T>, Producer<T>> combine) {
		return toFactor(this, value, assignment, combine);
	}

	static <T> Cell<T> from(Producer<T> p) {
		return new Cell<T>() {
			private Receptor<T> r;

			@Override
			public Supplier<Runnable> setup() {
				return new OperationList("Cell from " + p.getClass().getSimpleName() + " Setup");
			}

			@Override
			public Supplier<Runnable> push(Producer<T> protein) {
				return r == null ? new OperationList("Cell from " + p.getClass().getSimpleName() + " Push") : r.push(p);
			}

			@Override
			public void setReceptor(Receptor<T> r) {
				this.r = r;
			}
		};
	}

	static <T> CellularTemporalFactor<T> toFactor(Cell<T> c, Supplier<T> value, Function<Producer<T>, Receptor<T>> assignment) {
		return toFactor(c, value, assignment, (a, b) -> a);
	}

	static <T> CellularTemporalFactor<T> toFactor(Cell<T> c, Supplier<T> value,
												  Function<Producer<T>, Receptor<T>> assignment,
												  BiFunction<Producer<T>, Producer<T>, Producer<T>> combine) {
		T v = value.get();
		Producer<T> destination = () -> new Provider<>(v);

		return new CellularTemporalFactor<T>() {
			@Override
			public Producer<T> getResultant(Producer<T> value) {
				c.setReceptor(assignment.apply(destination));
				return combine.apply(destination, value);
			}

			@Override
			public Supplier<Runnable> setup() {
				return c.setup();
			}

			@Override
			public Supplier<Runnable> tick() {
				OperationList tick = new OperationList("CellularTemporalFactor from " + c.getClass().getSimpleName() + " Tick");
				tick.add(c.push(null));
				if (c instanceof Temporal) tick.add(((Temporal) c).tick());
				return tick;
			}

			@Override
			public void reset() {
				CellularTemporalFactor.super.reset();
				c.reset();
			}
		};
	}
}
