/*
 * Copyright 2020 Michael Murray
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

import org.almostrealism.heredity.Factor;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Provider;
import org.almostrealism.time.Temporal;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.hardware.OperationList;

import java.util.function.Supplier;

public abstract class CachedStateCell<T> extends FilteredCell<T> implements Factor<T>, Source<T>, Temporal {
	private final T cachedValue;
	private final T outValue;

	public CachedStateCell(Evaluable<T> blank) {
		super(null);
		cachedValue = blank.evaluate();
		outValue = blank.evaluate();
		setFilter(this);
	}
	
	public void setCachedValue(T v) { assign(() -> new Provider<>(cachedValue), () -> new Provider<>(v)).get().run(); }

	public T getCachedValue() { return cachedValue; }

	@Override
	public Producer<T> getResultant(Producer<T> value) {
		return () -> new Provider<>(outValue);
	}

	@Override
	public Producer<T> next() { return getResultant(null); }

	@Override
	public boolean isDone() { return false; }

	@Override
	public Supplier<Runnable> push(Producer<T> protein) {
		return assign(() -> new Provider<>(cachedValue), protein);
	}

	protected abstract Supplier<Runnable> assign(Supplier<Evaluable<? extends T>> out, Supplier<Evaluable<? extends T>> in);

	protected abstract Supplier<Runnable> reset(Supplier<Evaluable<? extends T>> out);

	@Override
	public Supplier<Runnable> tick() {
		OperationList tick = new OperationList();
		tick.add(assign(() -> new Provider<>(outValue), () -> new Provider<>(cachedValue)));
		tick.add(reset(() -> new Provider<>(cachedValue)));
		tick.add(super.push(null));
		return tick;
	}

	@Override
	public void reset() {
		super.reset();
		reset(() -> new Provider<>(cachedValue));
		reset(() -> new Provider<>(outValue));
	}
}
