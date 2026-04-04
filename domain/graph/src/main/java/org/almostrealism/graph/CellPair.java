/*
 * Copyright 2016 Michael Murray
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

import io.almostrealism.lifecycle.Lifecycle;
import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Producer;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.time.Temporal;
import org.almostrealism.time.TemporalList;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A {@link CellPair} connects the output of each cell to the input of the other.
 * These relationships are moderated by {@link Factor}s that filter the output
 * when it is sent from one cell to the other.
 *
 * @param <T>
 */
/**
 * {@inheritDoc}
 *
 * <p>{@code CellPair} connects cells A and B bidirectionally. Each cell's output
 * is moderated by a {@link Factor} before being forwarded to the other cell.
 * Optional adapter functions allow interposing additional logic on each data path.</p>
 */
public class CellPair<T> implements Receptor<T>, Temporal, Lifecycle {
	/** Flag to enable optional adapter functions on each cell's output path. */
	public static final boolean enableAdapters = true;

	/** The two cells forming the bidirectional connection. */
	private final Cell<T> cellA, cellB;

	/** Factors moderating the output from each cell to the other. */
	private final Factor<T> factorA, factorB;

	/** Optional adapter factories for customizing output paths from each cell. */
	private Function<Cell<T>, Receptor<T>> adapterA, adapterB;

	/** Instantiated adapters derived from the adapter factories after {@link #init()}. */
	private Receptor<T> adA, adB;

	/** Temporal components from adapters that require ticking. */
	private final TemporalList temporals;

	/**
	 * Creates a new CellPair connecting two cells with the given moderation factors.
	 *
	 * @param cellA   the first cell
	 * @param cellB   the second cell
	 * @param factorA the factor moderating cellA's input (data flowing to A)
	 * @param factorB the factor moderating cellB's input (data flowing to B)
	 */
	public CellPair(Cell<T> cellA, Cell<T> cellB, Factor<T> factorA, Factor<T> factorB) {
		this.cellA = cellA;
		this.cellB = cellB;
		this.factorA = factorA;
		this.factorB = factorB;
		this.temporals = new TemporalList();
	}

	/**
	 * Initializes the cell pair by connecting each cell's receptor to the other
	 * and instantiating any configured adapters.
	 */
	public void init() {
		this.cellA.setReceptor(protein -> push(protein, false, true));
		this.cellB.setReceptor(protein -> push(protein, true, false));

		if (enableAdapters) {
			this.adA = Optional.ofNullable(adapterA).map(a -> a.apply(cellA)).orElse(null);
			this.adB = Optional.ofNullable(adapterB).map(a -> a.apply(cellB)).orElse(null);
		}

		if (adA instanceof Temporal) temporals.add((Temporal) adA);
		if (adB instanceof Temporal) temporals.add((Temporal) adB);
	}

	/**
	 * Returns the adapter factory for cell A's output path.
	 *
	 * @return the adapter factory, or null if not configured
	 */
	public Function<Cell<T>, Receptor<T>> getAdapterA() {
		return adapterA;
	}

	/**
	 * Sets the adapter factory for cell A's output path.
	 * Must be called before {@link #init()}.
	 *
	 * @param adapterA the adapter factory function
	 */
	public void setAdapterA(Function<Cell<T>, Receptor<T>> adapterA) {
		this.adapterA = adapterA;
	}

	/**
	 * Returns the adapter factory for cell B's output path.
	 *
	 * @return the adapter factory, or null if not configured
	 */
	public Function<Cell<T>, Receptor<T>> getAdapterB() {
		return adapterB;
	}

	/**
	 * Sets the adapter factory for cell B's output path.
	 * Must be called before {@link #init()}.
	 *
	 * @param adapterB the adapter factory function
	 */
	public void setAdapterB(Function<Cell<T>, Receptor<T>> adapterB) {
		this.adapterB = adapterB;
	}

	/**
	 * Pushes data to both cells, moderating through their respective factors.
	 *
	 * @param protein the data producer to push to both cells
	 * @return a combined operation that pushes to both cells
	 */
	@Override
	public Supplier<Runnable> push(Producer<T> protein) {
		return push(protein, true, true);
	}

	/**
	 * Pushes data to the specified cells, applying moderation factors.
	 *
	 * @param protein the data producer to push
	 * @param toA     whether to push to cell A
	 * @param toB     whether to push to cell B
	 * @return a combined operation for the selected cells
	 */
	private Supplier<Runnable> push(Producer<T> protein, boolean toA, boolean toB) {
		OperationList push = new OperationList("CellPair Push");

		if (toA && factorA != null) {
			Producer<T> r = factorA.getResultant(protein);

			if (adA == null) {
				push.add(cellA.push(r));
			} else {
				push.add(adA.push(r));
			}
		}
		
		if (toB && factorB != null) {
			Producer<T> r = factorB.getResultant(protein);

			if (adB == null) {
				push.add(cellB.push(r));
			} else {
				push.add(adB.push(r));
			}
		}
		
		return push;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Ticks all temporal adapter components registered during {@link #init()}.</p>
	 *
	 * @return a tick operation for the temporal adapters
	 */
	@Override
	public Supplier<Runnable> tick() { return temporals.tick(); }

	/**
	 * {@inheritDoc}
	 *
	 * <p>Resets both cells, both factors (if they implement {@link Lifecycle}),
	 * and both adapters (if they implement {@link Lifecycle}).</p>
	 */
	@Override
	public void reset() {
		Lifecycle.super.reset();
		cellA.reset();
		cellB.reset();
		if (factorA instanceof Lifecycle) ((Lifecycle) factorA).reset();
		if (factorB instanceof Lifecycle) ((Lifecycle) factorB).reset();
		if (adA instanceof Lifecycle) ((Lifecycle) adA).reset();
		if (adB instanceof Lifecycle) ((Lifecycle) adB).reset();
	}
}
