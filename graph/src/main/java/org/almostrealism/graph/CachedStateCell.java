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

import org.almostrealism.CodeFeatures;
import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Producer;
import org.almostrealism.time.Temporal;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.hardware.OperationList;

import java.util.function.Supplier;

public abstract class CachedStateCell<T> extends FilteredCell<T> implements Factor<T>, Source<T>, Temporal, CodeFeatures {
	private final T cachedValue;
	private final T outValue;

	public CachedStateCell(Evaluable<T> blank) {
		super(null);
		cachedValue = blank.evaluate();
		outValue = blank.evaluate();
		setFilter(this);
	}
	
	public void setCachedValue(T v) { assign(p(cachedValue), p(v)).get().run(); }

	public T getCachedValue() { return cachedValue; }

	protected T getOutputValue() { return outValue; }

	@Override
	public Producer<T> getResultant(Producer<T> value) {
		return p(outValue);
	}

	@Override
	public Producer<T> next() { return getResultant(null); }

	@Override
	public boolean isDone() { return false; }

	@Override
	public Supplier<Runnable> push(Producer<T> protein) {
		return assign(p(cachedValue), protein);
	}

	protected Supplier<Runnable> pushValue() {
		return super.push(null);
	}

	protected abstract Supplier<Runnable> assign(Supplier<Evaluable<? extends T>> out, Supplier<Evaluable<? extends T>> in);

	protected abstract Supplier<Runnable> reset(Supplier<Evaluable<? extends T>> out);

	@Override
	public Supplier<Runnable> setup() {
		String name = getClass().getSimpleName();
		if (name == null || name.length() <= 0) name = "anonymous";
		OperationList reset = new OperationList(name + " Setup");
		reset.add(reset(p(cachedValue)));
		reset.add(reset(p(outValue)));
		return reset;
	}

	@Override
	public Supplier<Runnable> tick() {
		String name = getClass().getSimpleName();
		if (name == null || name.length() <= 0) name = "anonymous";
		OperationList tick = new OperationList("CachedStateCell (" + name + ") Tick");
		tick.add(assign(p(outValue), p(cachedValue)));
		tick.add(reset(p(cachedValue)));
		tick.add(super.push(null));
		return tick;
	}
}
