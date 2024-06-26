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

package org.almostrealism.heredity;

import io.almostrealism.cycle.Setup;
import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Producer;
import io.almostrealism.lifecycle.Lifecycle;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.time.Temporal;

import java.util.function.Supplier;

public class CombinedFactor<T> implements CellularTemporalFactor<T> {
	private Factor<T> a, b;

	public CombinedFactor(Factor<T> a, Factor<T> b) {
		this.a = a;
		this.b = b;
	}

	public Factor<T> getA() { return a; }

	public Factor<T> getB() { return b; }

	@Override
	public Supplier<Runnable> setup() {
		OperationList setup = new OperationList("CombinedFactor Setup");
		if (a instanceof Setup) setup.add(((Setup) a).setup());
		if (b instanceof Setup) setup.add(((Setup) b).setup());
		return setup;
	}

	@Override
	public Producer<T> getResultant(Producer<T> value) {
		return b.getResultant(a.getResultant(value));
	}

	@Override
	public Supplier<Runnable> tick() {
		OperationList tick = new OperationList("CombinedFactor Tick");
		if (a instanceof Temporal) tick.add(((Temporal) a).tick());
		if (b instanceof Temporal) tick.add(((Temporal) b).tick());
		return tick;
	}

	@Override
	public void reset() {
		CellularTemporalFactor.super.reset();
		if (a instanceof Lifecycle) ((Lifecycle) a).reset();
		if (b instanceof Lifecycle) ((Lifecycle) b).reset();
	}
}
