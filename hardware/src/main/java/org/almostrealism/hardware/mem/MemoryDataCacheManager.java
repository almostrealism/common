/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism.hardware.mem;

import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.expression.Expression;
import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.scope.ArrayVariable;
import org.almostrealism.hardware.MemoryData;

import java.util.function.Function;

public class MemoryDataCacheManager implements Destroyable, ExpressionFeatures {
	private final int maxEntries;
	private final int entrySize;
	private final Function<MemoryData, ArrayVariable<?>> variableFactory;

	private Bytes data;
	private ArrayVariable<?> variable;

	protected MemoryDataCacheManager(int maxEntries, int entrySize,
									 Function<MemoryData, ArrayVariable<?>> variableFactory) {
		this.maxEntries = maxEntries;
		this.entrySize = entrySize;
		this.variableFactory = variableFactory;
	}

	public int getEntrySize() { return entrySize; }
	public int getMaxEntries() { return maxEntries; }

	protected Bytes getData() {
		if (data == null) {
			long total = getMaxEntries() * (long) entrySize;
			data = new Bytes(Math.toIntExact(total), entrySize);
			variable = variableFactory.apply(data);
		}

		return data;
	}

	public void setValue(int index, double data[]) {
		if (data.length != entrySize) {
			throw new IllegalArgumentException();
		}

		getData().setMem(entrySize * index, data);
	}

	public Expression<?> reference(int entry, Expression<?> index) {
		if (variable == null) {
			throw new IllegalArgumentException("Cannot reference series variable when nothing has been cached");
		}

		return variable.reference(e(entrySize * entry).add(index));
	}

	@Override
	public void destroy() {
		if (data == null) return;

		data.destroy();
	}

	public static MemoryDataCacheManager create(int entrySize, int maxEntries,
												Function<MemoryData, ArrayVariable<?>> variableFactory) {
		return new MemoryDataCacheManager(maxEntries, entrySize, variableFactory);
	}
}
