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

/**
 * The core processing unit in the Almost Realism computation graph architecture.
 * A Cell represents a node in a directed graph that can both receive data (as a {@link Receptor})
 * and transmit data to downstream components (as a {@link Transmitter}).
 *
 * <p>Cells form the foundation of the neural network layer system. They process input data
 * through a push-based data flow model where data "proteins" are pushed through a chain of
 * connected cells. Each cell transforms its input and forwards the result to its connected
 * receptor.</p>
 *
 * <h2>Data Flow Model</h2>
 * <p>The Cell interface uses a biological metaphor where:</p>
 * <ul>
 *   <li><b>Proteins</b> - Data values wrapped as {@link Producer} instances that flow through the graph</li>
 *   <li><b>Receptors</b> - Downstream cells that receive processed output</li>
 *   <li><b>Push operations</b> - The mechanism for propagating data through the graph</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Create a cell that doubles its input
 * Cell<PackedCollection> doubler = Cell.of(input -> multiply(input, c(2.0)));
 *
 * // Chain cells together
 * Cell<PackedCollection> pipeline = doubler.andThen(anotherCell);
 *
 * // Execute the pipeline
 * pipeline.push(inputProducer).get().run();
 * }</pre>
 *
 * <h2>Factory Methods</h2>
 * <p>The interface provides several static factory methods for creating cells:</p>
 * <ul>
 *   <li>{@link #of(Producer)} - Creates a cell that always outputs a fixed value</li>
 *   <li>{@link #of(Factor)} - Creates a cell from a transformation factor</li>
 *   <li>{@link #of(Function)} - Creates a cell from a producer transformation function</li>
 *   <li>{@link #of(BiFunction)} - Creates a cell with access to both input and receptor</li>
 *   <li>{@link #from(Producer)} - Creates a cell that outputs the producer regardless of input</li>
 *   <li>{@link #branch(Cell[])} - Creates a cell that broadcasts input to multiple downstream cells</li>
 * </ul>
 *
 * @param <T> the type of data processed by this cell, typically {@link PackedCollection}
 * @see Receptor
 * @see Transmitter
 * @see CellAdapter
 * @see org.almostrealism.model.Block
 * @see org.almostrealism.layers.CellularLayer
 * @author Michael Murray
 */
public interface Cell<T> extends Transmitter<T>, Receptor<T>, Cellular {
	/**
	 * Flag to enable warnings when a receptor is replaced on a cell.
	 * Controlled by the {@code AR_GRAPH_CELL_WARNINGS} system property.
	 */
	boolean cellWarnings = SystemUtils.isEnabled("AR_GRAPH_CELL_WARNINGS").orElse(false);

	/**
	 * Performs any initialization required before the cell can process data.
	 * This method should be called before pushing data through the cell.
	 *
	 * @return a supplier of a runnable that performs the setup operations
	 */
	default Supplier<Runnable> setup() {
		return new OperationList();
	}

	/**
	 * Chains this cell with another cell, creating a pipeline where this cell's
	 * output flows into the next cell's input.
	 *
	 * <p>This method connects the cells by setting this cell's receptor to the
	 * next cell, then returns a composite cell that:</p>
	 * <ul>
	 *   <li>Combines setup operations from both cells</li>
	 *   <li>Pushes input through this cell (which forwards to next)</li>
	 *   <li>Delegates receptor setting to the next cell</li>
	 * </ul>
	 *
	 * @param next the cell to receive this cell's output
	 * @return a composite cell representing the chained pipeline
	 */
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

	/**
	 * Applies this cell's transformation to the given input producer.
	 * This is a convenience method that converts the cell to a {@link Factor}
	 * and retrieves the resultant producer.
	 *
	 * @param input the input data producer
	 * @return a producer representing the cell's output for the given input
	 */
	default Producer<T> apply(Producer<T> input) {
		return toFactor().getResultant(input);
	}

	/**
	 * Converts this cell to a {@link Factor} that can be used for functional
	 * composition. The returned factor captures the cell's transformation logic
	 * and can produce evaluable results.
	 *
	 * <p>This method uses a {@link CaptureReceptor} to intercept the cell's output
	 * and wrap it in an evaluable form.</p>
	 *
	 * @return a factor representing this cell's transformation
	 */
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

	/**
	 * Converts this cell to a {@link CellularTemporalFactor} for use in temporal processing.
	 *
	 * @param value the supplier providing the initial value
	 * @param assignment a function that creates a receptor for assigning values
	 * @return a cellular temporal factor wrapping this cell
	 */
	default CellularTemporalFactor<T> toFactor(Supplier<T> value, Function<Producer<T>, Receptor<T>> assignment) {
		return toFactor(this, value, assignment);
	}

	/**
	 * Converts this cell to a {@link CellularTemporalFactor} with a custom combine function.
	 *
	 * @param value the supplier providing the initial value
	 * @param assignment a function that creates a receptor for assigning values
	 * @param combine a function to combine input and accumulated values
	 * @return a cellular temporal factor wrapping this cell
	 */
	default CellularTemporalFactor<T> toFactor(Supplier<T> value, Function<Producer<T>, Receptor<T>> assignment,
											   BiFunction<Producer<T>, Producer<T>, Producer<T>> combine) {
		return toFactor(this, value, assignment, combine);
	}

	/**
	 * Creates a cell from a producer that ignores its input and always outputs
	 * the given producer's value.
	 *
	 * @param <T> the data type
	 * @param p the producer whose value will always be output
	 * @return a cell that outputs the producer's value
	 */
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

	/**
	 * Static helper to convert a cell to a temporal factor with default combine behavior.
	 *
	 * @param <T> the data type
	 * @param c the cell to convert
	 * @param value the supplier providing the initial value
	 * @param assignment a function that creates a receptor for assigning values
	 * @return a cellular temporal factor wrapping the cell
	 */
	static <T> CellularTemporalFactor<T> toFactor(Cell<T> c, Supplier<T> value, Function<Producer<T>, Receptor<T>> assignment) {
		return toFactor(c, value, assignment, (a, b) -> a);
	}

	/**
	 * Static helper to convert a cell to a temporal factor with custom combine behavior.
	 *
	 * @param <T> the data type
	 * @param c the cell to convert
	 * @param value the supplier providing the initial value
	 * @param assignment a function that creates a receptor for assigning values
	 * @param combine a function to combine input and accumulated values
	 * @return a cellular temporal factor wrapping the cell
	 */
	static <T> CellularTemporalFactor<T> toFactor(Cell<T> c, Supplier<T> value,
												  Function<Producer<T>, Receptor<T>> assignment,
												  BiFunction<Producer<T>, Producer<T>, Producer<T>> combine) {
		T v = value.get();
		Producer<T> destination = Ops.o().p(v);

		return new TemporalFactorFromCell<>(c, destination, assignment, combine);
	}

	/**
	 * Creates a cell that broadcasts its input to multiple downstream cells in parallel.
	 * All downstream cells will receive the same input when push is called.
	 *
	 * @param <T> the data type
	 * @param cells the downstream cells to receive the input
	 * @return a branching cell that distributes input to all provided cells
	 */
	static <T> Cell<T> branch(Cell<T>... cells) {
		return Cell.of((input, next) -> {
			OperationList op = new OperationList();
			for (Cell<T> cell : cells) op.add(cell.push(input));
			return op;
		});
	}

	/**
	 * Creates a cell that always outputs a fixed value, ignoring its input.
	 *
	 * @param <T> the data type
	 * @param value the producer whose value will always be output
	 * @return a cell that outputs the fixed value
	 */
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

	/**
	 * Creates a cell from a {@link Factor} transformation function.
	 * The factor's resultant is applied to the input and forwarded to the receptor.
	 *
	 * @param <T> the data type
	 * @param func the factor that defines the transformation
	 * @return a cell that applies the factor transformation
	 */
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

	/**
	 * Creates a cell from a function that transforms producers.
	 * This is the most common way to create simple transformation cells.
	 *
	 * <p>Example usage:</p>
	 * <pre>{@code
	 * Cell<PackedCollection> doubler = Cell.of(input -> multiply(input, c(2.0)));
	 * }</pre>
	 *
	 * @param <T> the data type
	 * @param func the function that transforms input producers to output producers
	 * @return a cell that applies the transformation function
	 */
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

	/**
	 * Creates a cell with full control over both input processing and receptor interaction.
	 * This is the most flexible factory method, allowing custom handling of the push operation.
	 *
	 * <p>The function receives both the input producer and the downstream receptor,
	 * and must return a supplier of the operation to execute.</p>
	 *
	 * <p>Example usage:</p>
	 * <pre>{@code
	 * Cell<PackedCollection> custom = Cell.of((input, receptor) -> {
	 *     OperationList ops = new OperationList();
	 *     ops.add(receptor.push(transform(input)));
	 *     ops.add(sideEffect());
	 *     return ops;
	 * });
	 * }</pre>
	 *
	 * @param <T> the data type
	 * @param func the function that handles push operations
	 * @return a cell with custom push behavior
	 */
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

	/**
	 * A utility receptor that captures the producer pushed to it for later retrieval.
	 * This is used internally to intercept cell outputs when converting cells to factors
	 * or when capturing intermediate results.
	 *
	 * <p>When a producer is pushed to this receptor, it stores the producer reference
	 * and returns an empty operation list. The captured producer can then be retrieved
	 * via {@link #getReceipt()}.</p>
	 *
	 * @param <T> the type of data being captured
	 */
	class CaptureReceptor<T> implements Receptor<T> {
		private Producer<T> receipt;

		/**
		 * Returns the most recently captured producer.
		 *
		 * @return the captured producer, or null if no producer has been pushed
		 */
		public Producer<T> getReceipt() { return receipt; }

		/**
		 * Captures the given producer for later retrieval.
		 *
		 * @param in the producer being pushed
		 * @return an empty operation list (no actual operation is performed)
		 */
		@Override
		public Supplier<Runnable> push(Producer<T> in) {
			receipt = in;
			return new OperationList();
		}
	}
}
