/*
 * Copyright 2021 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.graph;

import io.almostrealism.cycle.Setup;
import io.almostrealism.lifecycle.Lifecycle;
import org.almostrealism.hardware.OperationList;
import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Producer;
import org.almostrealism.time.Temporal;

import java.util.function.Supplier;

public class FilteredCell<T> extends CellAdapter<T> implements Temporal {
	private Factor<T> filter;
	
	public FilteredCell(Factor<T> filter) { this.filter = filter; }
	
	protected void setFilter(Factor<T> filter) { this.filter = filter; }

	@Override
	public Supplier<Runnable> setup() {
		if (filter instanceof Setup && filter != this) {
			return ((Setup) filter).setup();
		} else {
			return new OperationList("FilteredCell Setup");
		}
	}

	@Override
	public Supplier<Runnable> push(Producer<T> protein) {
		return super.push(filter.getResultant(protein));
	}

	@Override
	public Supplier<Runnable> tick() {
		if (filter instanceof Temporal && filter != this) {
			return ((Temporal) filter).tick();
		} else {
			return new OperationList("FilteredCell Tick");
		}
	}

	@Override
	public void reset() {
		super.reset();

		if (filter instanceof Lifecycle && filter != this) {
			((Lifecycle) filter).reset();
		}
	}
}
