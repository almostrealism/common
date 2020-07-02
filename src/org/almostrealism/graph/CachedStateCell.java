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

public class CachedStateCell<T> extends FilteredCell<T> implements Factor<T>, Source<T>, Temporal {
	public static boolean enableWarning = true;
	
	private T cachedValue;
	private T outValue;
	
	public CachedStateCell() {
		this(null);
	}

	public CachedStateCell(ProteinCache<T> proteinCache) {
		super(null);
		setFilter(this);
		setProteinCache(proteinCache);
	}
	
	public void setCachedValue(T v) { this.cachedValue = v; }

	protected T getCachedValue() { return cachedValue; }

	@Override
	public T getResultant(T value) { return outValue; }

	@Override
	public T next() { return getResultant(null); }

	@Override
	public boolean isDone() { return false; }

	@Override
	public void push(long index) {
		if (cachedValue == null) {
			cachedValue = getProtein(index);
			System.out.println("Caching " + cachedValue);
		} else if (enableWarning) {
			System.out.println("Warning: Cached cell is pushed when full");
		}
	}

	@Override
	public void tick() { outValue = cachedValue; cachedValue = null; super.push(0); }
}
