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
import io.almostrealism.scope.ArrayVariable;
import org.almostrealism.hardware.MemoryData;

import java.util.function.Function;

public class MemoryDataCacheManager implements ExpressionFeatures {
	private final int entrySize;
	private final Bytes data;
	private final ArrayVariable<?> variable;

	protected MemoryDataCacheManager(int entrySize, Bytes data, ArrayVariable<?> variable) {
		this.entrySize = entrySize;
		this.data = data;
		this.variable = variable;
	}

	public int getEntrySize() { return entrySize; }

	public int getMaxEntries() { return data.getCount(); }

	public void setValue(int index, double data[]) {
		if (data.length != entrySize) {
			throw new IllegalArgumentException();
		}

		this.data.setMem(entrySize * index, data);
	}

	public Expression<?> reference(int entry, Expression<?> index) {
		return variable.referenceAbsolute(e(entrySize * entry).add(index));
	}

	public static MemoryDataCacheManager create(int entrySize, int maxEntries,
												Function<MemoryData, ArrayVariable<?>> variableFactory) {
		long total = entrySize;
		total *= maxEntries;

		if (total < 0 || total > Integer.MAX_VALUE) {
			throw new IllegalArgumentException();
		}

		Bytes data = new Bytes((int) total, entrySize);
		return new MemoryDataCacheManager(entrySize, data, variableFactory.apply(data));
	}
}
