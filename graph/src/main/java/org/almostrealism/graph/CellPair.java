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

import io.almostrealism.uml.Lifecycle;
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
public class CellPair<T> implements Receptor<T>, Temporal, Lifecycle {
	public static final boolean enableAdapters = true;

	private final Cell<T> cellA, cellB;
	private final Factor<T> factorA, factorB;

	private Function<Cell<T>, Receptor<T>> adapterA, adapterB;
	private Receptor<T> adA, adB;

	private TemporalList temporals;
	
	public CellPair(Cell<T> cellA, Cell<T> cellB, Factor<T> factorA, Factor<T> factorB) {
		this.cellA = cellA;
		this.cellB = cellB;
		this.factorA = factorA;
		this.factorB = factorB;
		this.temporals = new TemporalList();
	}

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

	public Function<Cell<T>, Receptor<T>> getAdapterA() {
		return adapterA;
	}

	public void setAdapterA(Function<Cell<T>, Receptor<T>> adapterA) {
		this.adapterA = adapterA;
	}

	public Function<Cell<T>, Receptor<T>> getAdapterB() {
		return adapterB;
	}

	public void setAdapterB(Function<Cell<T>, Receptor<T>> adapterB) {
		this.adapterB = adapterB;
	}

	@Override
	public Supplier<Runnable> push(Producer<T> protein) {
		return push(protein, true, true);
	}
	
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

	@Override
	public Supplier<Runnable> tick() { return temporals.tick(); }

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
