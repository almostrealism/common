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

package org.almostrealism.graph;

import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Producer;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.heredity.Gene;
import org.almostrealism.heredity.IdentityFactor;

import java.util.Iterator;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * A cell that fans out input to a list of downstream cells, modulating each
 * output through a corresponding {@link Gene} factor.
 *
 * <p>{@code MultiCell} distributes a single input producer to multiple downstream
 * cells in parallel. Each cell receives the input transformed by the factor at the
 * corresponding index in the {@link Gene}. If the gene returns {@code null} for an
 * index, that cell is skipped for that push.</p>
 *
 * <p>The static factory method {@link #split(Cell, Cell, List, Gene)} creates
 * a {@link CellPair} that routes a source cell's output through this distribution
 * mechanism.</p>
 *
 * @param <T> the type of data processed, typically {@link org.almostrealism.collect.PackedCollection}
 * @see CellAdapter
 * @see Gene
 * @see CellPair
 * @author Michael Murray
 */
public class MultiCell<T> extends CellAdapter<T> {
	/** The list of downstream cells that each receive a copy of the input. */
	private final List<Cell<T>> cells;

	/** The gene providing per-cell modulation factors. */
	private final Gene<T> gene;

	/**
	 * Creates a new MultiCell that distributes input to the given cells
	 * using the provided gene for per-cell modulation.
	 *
	 * @param cells the downstream cells to receive input
	 * @param gene  the gene providing factors for each cell, or null for identity
	 */
	public MultiCell(List<Cell<T>> cells, Gene<T> gene) {
		this.cells = cells;
		this.gene = gene;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Runs the setup operation for all downstream cells.</p>
	 *
	 * @return a combined setup operation for all cells
	 */
	@Override
	public Supplier<Runnable> setup() {
		List<Runnable> r = cells.stream().map(Cell::setup).map(Supplier::get).collect(Collectors.toList());
		return () -> () -> r.forEach(Runnable::run);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Fans out the input to each downstream cell, applying the gene's factor
	 * for each cell. Cells for which the gene returns {@code null} are skipped.</p>
	 *
	 * @param protein the input data producer
	 * @return a combined push operation for all active cells
	 */
	@Override
	public Supplier<Runnable> push(Producer<T> protein) {
		OperationList push = new OperationList("MultiCell Push");

		Iterator<Cell<T>> itr = cells.iterator();

		i: for (int i = 0; itr.hasNext(); i++) {
			Factor<T> factor = gene == null ? new IdentityFactor<>() : gene.valueAt(i);
			if (factor == null) {
				itr.next(); continue i;
			}

			push.add(itr.next().push(factor.getResultant(protein)));
		}

		return push;
	}
	/**
	 * Creates a {@link CellPair} that routes a source cell's output to multiple
	 * destination cells via a MultiCell.
	 *
	 * @param <T>          the data type
	 * @param source       the source cell providing input
	 * @param adapter      an optional adapter cell to interpose on the MultiCell path, or null
	 * @param destinations the destination cells to receive the distributed input
	 * @param transmission the gene providing per-destination modulation factors
	 * @return a CellPair connecting the source to the multi-destination fan-out
	 */
	public static <T> CellPair<T> split(Cell<T> source, Cell<T> adapter, List<Cell<T>> destinations, Gene<T> transmission) {
		return split(source, adapter, destinations, transmission, null);
	}

	/**
	 * Creates a {@link CellPair} that routes a source cell's output to multiple
	 * destination cells via a MultiCell, with an optional passthrough cell.
	 *
	 * @param <T>          the data type
	 * @param source       the source cell providing input
	 * @param adapter      an optional adapter cell to interpose on the MultiCell path, or null
	 * @param destinations the destination cells to receive the distributed input
	 * @param transmission the gene providing per-destination modulation factors
	 * @param passthrough  an optional cell to receive a copy of the source output in parallel, or null
	 * @return a CellPair connecting the source to the multi-destination fan-out
	 */
	public static <T> CellPair<T> split(Cell<T> source, Cell<T> adapter, List<Cell<T>> destinations, Gene<T> transmission, Cell<T> passthrough) {
		MultiCell<T> m = new MultiCell<>(destinations, transmission);

		Cell<T> afterPassthrough = source;

		if (passthrough != null) {
			afterPassthrough = new ReceptorCell<>(null);
			source.setReceptor(Receptor.to(passthrough, afterPassthrough));
		}

		CellPair<T> pair = new CellPair(afterPassthrough, m, null, new IdentityFactor<>());
		if (adapter != null) {
			pair.setAdapterB(cell -> {
				adapter.setReceptor(cell);
				return adapter;
			});
		}

		pair.init();
		return pair;
	}
}
