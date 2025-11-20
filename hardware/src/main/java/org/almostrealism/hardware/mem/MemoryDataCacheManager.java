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

/**
 * Lazy-initialized cache for fixed-size memory entries used in generated kernel code.
 *
 * <p>{@link MemoryDataCacheManager} manages a contiguous memory block divided into fixed-size
 * entries. It provides expression-based references for code generation, allowing kernels to
 * access cached data via generated array indices.</p>
 *
 * <h2>Entry-Based Storage</h2>
 *
 * <p>The cache stores a fixed number of equal-sized entries in contiguous memory:</p>
 * <pre>{@code
 * MemoryDataCacheManager cache = MemoryDataCacheManager.create(
 *     4,    // entrySize: 4 elements per entry
 *     100,  // maxEntries: 100 entries
 *     data -> new ArrayVariable<>(data, 4)
 * );
 *
 * Memory Layout:
 * [Entry 0: 4 elements][Entry 1: 4 elements]...[Entry 99: 4 elements]
 *  Total: 400 elements (100 * 4)
 * }</pre>
 *
 * <h2>Lazy Initialization</h2>
 *
 * <p>Memory is allocated only when first accessed via {@link #getData()} or {@link #setValue(int, double[])}:</p>
 * <pre>{@code
 * MemoryDataCacheManager cache = create(4, 100, factory);
 * // No memory allocated yet
 *
 * cache.setValue(0, new double[]{1, 2, 3, 4});
 * // Memory allocated: 100 entries * 4 elements = 400 total
 * }</pre>
 *
 * <h2>Expression References for Code Generation</h2>
 *
 * <p>The primary use case is generating array references in kernel code:</p>
 * <pre>{@code
 * MemoryDataCacheManager cache = create(4, 100,
 *     data -> new ArrayVariable<>(data, 4));
 *
 * cache.setValue(5, new double[]{10, 20, 30, 40});
 *
 * // Generate expression referencing entry 5, element 2
 * Expression<?> ref = cache.reference(5, e(2));
 * // Generates: cache_array[5 * 4 + 2] -> cache_array[22]
 *
 * // Use in kernel code generation
 * // result = cache_array[22];  // Value: 30
 * }</pre>
 *
 * <h2>Common Usage Pattern: Kernel Caching</h2>
 *
 * <pre>{@code
 * // Cache for intermediate computation results
 * MemoryDataCacheManager cache = create(vectorSize, maxVectors,
 *     data -> new ArrayVariable<>(data, vectorSize));
 *
 * // Store precomputed vectors
 * for (int i = 0; i < count; i++) {
 *     cache.setValue(i, computeVector(i));
 * }
 *
 * // Reference in generated kernel
 * Expression<?> cachedValue = cache.reference(vectorIndex, elementIndex);
 * // Kernel can now access cached data efficiently
 * }</pre>
 *
 * @see Bytes
 * @see ArrayVariable
 */
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
