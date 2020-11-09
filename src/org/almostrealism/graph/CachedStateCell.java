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
import org.almostrealism.time.Temporal;
import org.almostrealism.util.CodeFeatures;
import org.almostrealism.util.Producer;
import org.almostrealism.util.Provider;
import org.almostrealism.util.RunnableList;
import org.almostrealism.util.StaticProducer;

public abstract class CachedStateCell<T> extends FilteredCell<T> implements Factor<T>, Source<T>, Temporal, CodeFeatures {
	private final T cachedValue;
	private final T outValue;

	public CachedStateCell(Producer<T> blank) {
		super(null);
		cachedValue = blank.evaluate();
		outValue = blank.evaluate();
		setFilter(this);
	}
	
	public void setCachedValue(T v) { assign(p(cachedValue), v(v)).run(); }

	public T getCachedValue() { return cachedValue; }

	@Override
	public Producer<T> getResultant(Producer<T> value) {
		return new Provider<>(outValue);
	}

	@Override
	public Producer<T> next() { return getResultant(null); }

	@Override
	public boolean isDone() { return false; }

	@Override
	public Runnable push(Producer<T> protein) {
		return assign(p(cachedValue), protein);
	}

	protected abstract Runnable assign(Provider<T> out, Producer<T> in);

	protected abstract Runnable reset(Provider<T> out);

	@Override
	public Runnable tick() {
		Runnable push = super.push(null);

		return () -> {
			assign(p(outValue), v(cachedValue)).run();
			reset(p(cachedValue)).run();
			push.run();
		};

//		RunnableList tick = new RunnableList();
//		tick.add(assign(p(outValue), v(cachedValue)));
//		tick.add(reset(p(cachedValue)));
//		tick.add(super.push(null));
//		return tick;
	}
}
