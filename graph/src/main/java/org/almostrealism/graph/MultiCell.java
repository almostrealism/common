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

import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.almostrealism.graph.Cell;
import org.almostrealism.graph.CellAdapter;
import org.almostrealism.heredity.Factor;
import org.almostrealism.heredity.Gene;
import io.almostrealism.relation.Producer;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.heredity.IdentityFactor;
import org.almostrealism.time.Temporal;

public class MultiCell<T> extends CellAdapter<T> {
	private final List<Cell<T>> cells;
	private final Gene<T> gene;
	
	public MultiCell(List<Cell<T>> cells, Gene<T> gene) {
		this.cells = cells;
		this.gene = gene;
	}

	@Override
	public Supplier<Runnable> setup() {
		List<Runnable> r = cells.stream().map(Cell::setup).map(Supplier::get).collect(Collectors.toList());
		return () -> () -> r.forEach(Runnable::run);
	}

	@Override
	public Supplier<Runnable> push(Producer<T> protein) {
		OperationList push = new OperationList();

		Iterator<Cell<T>> itr = cells.iterator();

		i: for (int i = 0; itr.hasNext(); i++) {
			Factor<T> factor = gene.valueAt(i);
			if (factor == null) {
				itr.next(); continue i;
			}

			push.add(itr.next().push(factor.getResultant(protein)));
		}

		return push;
	}

	public static <T> CellPair<T> split(Cell<T> source, Cell<T> adapter, List<Cell<T>> destinations, Gene<T> transmission) {
		MultiCell<T> m = new MultiCell<>(destinations, transmission);

		CellPair<T> pair = new CellPair<>(source, m, null, new IdentityFactor<>());
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
