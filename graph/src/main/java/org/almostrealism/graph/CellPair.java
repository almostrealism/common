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

import org.almostrealism.heredity.Factor;
import io.almostrealism.relation.Producer;
import org.almostrealism.hardware.OperationList;

import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * A {@link CellPair} connects the output of each cell to the input of the other.
 * These relationships are moderated by {@link Factor}s that filter the output
 * when it is sent from one cell to the other.
 *
 * @param <T>
 */
public class CellPair<T> implements Receptor<T> {
	private final Cell<T> cellA, cellB;
	private final Factor<T> factorA, factorB;

	private BiFunction<Producer<T>, Cell<T>, Supplier<Runnable>> adapterA, adapterB;
	
	public CellPair(Cell<T> cellA, Cell<T> cellB, Factor<T> factorA, Factor<T> factorB) {
		this.cellA = cellA;
		this.cellB = cellB;
		this.factorA = factorA;
		this.factorB = factorB;
		
		this.cellA.setReceptor(protein -> push(protein, false, true));
		this.cellB.setReceptor(protein -> push(protein, true, false));
	}

	public BiFunction<Producer<T>, Cell<T>, Supplier<Runnable>> getAdapterA() {
		return adapterA;
	}

	public void setAdapterA(BiFunction<Producer<T>, Cell<T>, Supplier<Runnable>> adapterA) {
		this.adapterA = adapterA;
	}

	public BiFunction<Producer<T>, Cell<T>, Supplier<Runnable>> getAdapterB() {
		return adapterB;
	}

	public void setAdapterB(BiFunction<Producer<T>, Cell<T>, Supplier<Runnable>> adapterB) {
		this.adapterB = adapterB;
	}

	@Override
	public Supplier<Runnable> push(Producer<T> protein) {
		return push(protein, true, true);
	}
	
	private Supplier<Runnable> push(Producer<T> protein, boolean toA, boolean toB) {
		OperationList push = new OperationList();

		if (toA && factorA != null) {
			Producer<T> r = factorA.getResultant(protein);

			if (adapterA == null) {
				push.add(cellA.push(r));
			} else {
				push.add(adapterA.apply(r, cellA));
			}
		}
		
		if (toB && factorB != null) {
			Producer<T> r = factorB.getResultant(protein);

			if (adapterB == null) {
				push.add(cellB.push(r));
			} else {
				push.add(adapterB.apply(r, cellB));
			}
		}
		
		return push;
	}
}
