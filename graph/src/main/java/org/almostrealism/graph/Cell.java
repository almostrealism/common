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

package org.almostrealism.graph;

import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Producer;
import org.almostrealism.Ops;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.temporal.TemporalFactorFromCell;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.heredity.Cellular;
import org.almostrealism.heredity.CellularTemporalFactor;
import org.almostrealism.io.SystemUtils;
import org.almostrealism.layers.LayerFeatures;

import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

public interface Cell<T> extends Transmitter<T>, Receptor<T>, Cellular {
	boolean cellWarnings = SystemUtils.isEnabled("AR_GRAPH_CELL_WARNINGS").orElse(false);

	default Supplier<Runnable> setup() {
		return new OperationList();
	}

	default Cell<T> andThen(Cell<T> next) {
		setReceptor(next);

		return new Cell<>() {
			@Override
			public Supplier<Runnable> setup() {
				OperationList setup = new OperationList("Cell Setup");
				setup.add(Cell.this.setup());
				setup.add(next.setup());
				return setup;
			}

			@Override
			public Supplier<Runnable> push(Producer<T> protein) {
				return Cell.this.push(protein);
			}

			@Override
			public void setReceptor(Receptor<T> r) {
				next.setReceptor(r);
			}
		};
	}

	default Producer<T> apply(Producer<T> input) {
		return toFactor().getResultant(input);
	}

	default Factor<T> toFactor() {
		return input -> () -> {
			CaptureReceptor<T> capture = new CaptureReceptor<>();
			setReceptor(capture);

			Runnable r = push(input).get();
			Evaluable<T> result =
					Optional.ofNullable(capture.getReceipt())
					.map(Producer::get).orElse(null);

			return args -> {
				r.run();
				return Optional.ofNullable(result)
							.orElseGet(capture.getReceipt()::get)
							.evaluate(args);
			};
		};
	}

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
				String name = p.getClass().getSimpleName();
				if (name == null || name.length() <= 0) name = "anonymous";
				return new OperationList("Cell from " + name + " Setup");
			}

			@Override
			public Supplier<Runnable> push(Producer<T> protein) {
				String name = p.getClass().getSimpleName();
				if (name == null || name.length() <= 0) name = "anonymous";
				return r == null ? new OperationList("Cell from " + name + " Push") : r.push(p);
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
		Producer<T> destination = Ops.o().p(v);

		return new TemporalFactorFromCell<>(c, destination, assignment, combine);
	}

	static <T> Cell<T> branch(Cell<T>... cells) {
		return Cell.of((input, next) -> {
			OperationList op = new OperationList();
			for (Cell<T> cell : cells) op.add(cell.push(input));
			return op;
		});
	}
	static <T> Cell<T> of(Producer<T> value) {
		return new Cell<>() {
			private Receptor<T> r;

			@Override
			public Supplier<Runnable> setup() {
				return new OperationList();
			}

			@Override
			public Supplier<Runnable> push(Producer<T> protein) {
				return r == null ? new OperationList() : r.push(value);
			}

			@Override
			public void setReceptor(Receptor<T> r) {
				if (cellWarnings && this.r != null) {
					CollectionFeatures.console.features(Cell.class)
							.warn("Replacing receptor");
				}

				this.r = r;
			}
		};
	}

	static <T> Cell<T> of(Factor<T> func) {
		return new Cell<>() {
			private Receptor<T> r;

			@Override
			public Supplier<Runnable> setup() {
				return new OperationList();
			}

			@Override
			public Supplier<Runnable> push(Producer<T> protein) {
				return r == null ? new OperationList() : r.push(func.getResultant(protein));
			}

			@Override
			public void setReceptor(Receptor<T> r) {
				if (cellWarnings && this.r != null) {
					CollectionFeatures.console.features(Cell.class)
							.warn("Replacing receptor");
				}

				this.r = r;
			}
		};
	}

	static <T> Cell<T> of(Function<Producer<T>, Producer<T>> func) {
		return new Cell<>() {
			private Receptor<T> r;

			@Override
			public Supplier<Runnable> setup() {
				return new OperationList();
			}

			@Override
			public Supplier<Runnable> push(Producer<T> protein) {
				return r == null ? new OperationList() : r.push(func.apply(protein));
			}

			@Override
			public void setReceptor(Receptor<T> r) {
				if (this.r != null) {
					CollectionFeatures.console.features(Cell.class)
							.warn("Replacing receptor");
				}

				this.r = r;
			}
		};
	}

	static <T> Cell<T> of(BiFunction<Producer<T>, Receptor<T>, Supplier<Runnable>> func) {
		return new Cell<>() {
			private Receptor<T> r;

			@Override
			public Supplier<Runnable> setup() {
				return new OperationList();
			}

			@Override
			public Supplier<Runnable> push(Producer<T> protein) {
				return func.apply(protein, r);
			}

			@Override
			public void setReceptor(Receptor<T> r) {
				if (cellWarnings && this.r != null) {
					CollectionFeatures.console.features(Cell.class)
							.warn("Replacing receptor");
				}

				this.r = r;
			}
		};
	}


	class CaptureReceptor<T> implements Receptor<T> {
		private Producer<T> receipt;

		public Producer<T> getReceipt() { return receipt; }

		@Override
		public Supplier<Runnable> push(Producer<T> in) {
			receipt = in;
			return new OperationList();
		}
	}
}
