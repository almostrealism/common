/*
 * Copyright 2024 Michael Murray
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

import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.MemoryBank;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Base implementation of {@link MemoryBank} with configurable element caching.
 *
 * <p>{@link MemoryBankAdapter} provides a memory-efficient bank of {@link MemoryData} instances
 * stored in contiguous memory. It supports flexible caching strategies to balance memory usage
 * and access performance.</p>
 *
 * <p><b>DEPRECATED:</b> This class is deprecated in favor of direct use of {@link Bytes} and
 * typed collection classes. It remains for backward compatibility with existing code.</p>
 *
 * <h2>Cache Levels</h2>
 *
 * <p>The primary feature of {@link MemoryBankAdapter} is its configurable caching of elements:</p>
 *
 * <h3>CacheLevel.ALL</h3>
 * <p>Pre-creates all elements during initialization and stores them in a {@link List}:</p>
 * <pre>{@code
 * MemoryBankAdapter<MyData> bank = new MyBank(100, 1000, spec ->
 *     new MyData(spec.getDelegate(), spec.getOffset()),
 *     CacheLevel.ALL
 * );
 * // 1000 MyData instances created immediately
 * // Fast access: O(1) lookup in List
 * // High memory: 1000 Java objects + contiguous memory
 * }</pre>
 *
 * <h3>CacheLevel.ACCESSED</h3>
 * <p>Creates elements on-demand and caches them in a {@link HashMap} (default):</p>
 * <pre>{@code
 * MemoryBankAdapter<MyData> bank = new MyBank(100, 1000, spec ->
 *     new MyData(spec.getDelegate(), spec.getOffset()),
 *     CacheLevel.ACCESSED
 * );
 * // Elements created only when get() is called
 * // Moderate access: O(1) HashMap lookup after first access
 * // Moderate memory: Only accessed elements cached
 * }</pre>
 *
 * <h3>CacheLevel.NONE</h3>
 * <p>Creates new element instances on every {@code get()} call:</p>
 * <pre>{@code
 * MemoryBankAdapter<MyData> bank = new MyBank(100, 1000, spec ->
 *     new MyData(spec.getDelegate(), spec.getOffset()),
 *     CacheLevel.NONE
 * );
 * // New instance created on each get()
 * // Slowest access: Factory call on every get()
 * // Lowest memory: No Java object overhead
 * }</pre>
 *
 * <h2>Element Factory Pattern</h2>
 *
 * <p>Elements are created via a {@link Function} that receives a {@link DelegateSpec}:</p>
 * <pre>{@code
 * Function<DelegateSpec, MyData> factory = spec -> {
 *     // Create element delegating to bank memory at offset
 *     return new MyData(spec.getDelegate(), spec.getOffset());
 * };
 *
 * MemoryBankAdapter<MyData> bank = new MyBank(100, 1000, factory);
 * MyData element = bank.get(5);  // Factory creates MyData at offset 500
 * }</pre>
 *
 * <h2>Memory Layout</h2>
 *
 * <p>Elements are stored in contiguous memory with fixed atomic length:</p>
 * <pre>
 * MemoryBankAdapter(memLength=100, count=10)
 *
 * Memory Layout:
 * [Element 0: 100 bytes][Element 1: 100 bytes]...[Element 9: 100 bytes]
 *  Offset: 0             Offset: 100             Offset: 900
 *
 * get(0) -> DelegateSpec(offset=0)   -> MyData delegating to bank at offset 0
 * get(5) -> DelegateSpec(offset=500) -> MyData delegating to bank at offset 500
 * </pre>
 *
 * <h2>Common Usage Patterns</h2>
 *
 * <h3>High-Performance Access (CacheLevel.ALL)</h3>
 * <pre>{@code
 * // Frequently accessed, fixed-size collections
 * MemoryBankAdapter<Vector3> vertices = new VectorBank(
 *     12, 1000,  // 1000 vec3s (12 bytes each)
 *     spec -> new Vector3(spec.getDelegate(), spec.getOffset()),
 *     CacheLevel.ALL
 * );
 *
 * // Fast iteration (no allocations)
 * vertices.stream().forEach(v -> transform(v));
 * }</pre>
 *
 * <h3>Sparse Access (CacheLevel.ACCESSED)</h3>
 * <pre>{@code
 * // Large collections with sparse access
 * MemoryBankAdapter<Matrix> matrices = new MatrixBank(
 *     400, 100000,  // 100K matrices
 *     spec -> new Matrix(spec.getDelegate(), spec.getOffset()),
 *     CacheLevel.ACCESSED  // Only cache accessed matrices
 * );
 *
 * matrices.get(42);  // Created and cached
 * matrices.get(42);  // Retrieved from cache
 * }</pre>
 *
 * <h3>Transient Access (CacheLevel.NONE)</h3>
 * <pre>{@code
 * // One-time iteration over large collection
 * MemoryBankAdapter<LargeObject> objects = new ObjectBank(
 *     1000, 1000000,  // 1M large objects
 *     spec -> new LargeObject(spec.getDelegate(), spec.getOffset()),
 *     CacheLevel.NONE  // No caching overhead
 * );
 *
 * // Process once without caching
 * objects.stream().forEach(obj -> process(obj));
 * }</pre>
 *
 * @param <T> Element type, must extend {@link MemoryData}
 * @see MemoryBank
 * @see Bytes
 * @deprecated Use {@link Bytes} with typed wrappers or direct collection classes instead
 * @author Michael Murray
 */
@Deprecated
public abstract class MemoryBankAdapter<T extends MemoryData> extends MemoryDataAdapter implements MemoryBank<T> {
	public static final CacheLevel defaultCacheLevel = CacheLevel.ACCESSED;

	static {
		if (defaultCacheLevel == CacheLevel.ALL) {
			System.out.println("WARN: Default CacheLevel is ALL");
		}
	}

	private int memLength, count;
	private int totalMemLength;

	private Function<DelegateSpec, T> supply;

	private List<T> entriesList;
	private Map<Integer, T> entriesMap;

	private CacheLevel cacheLevel;

	/**
	 * Initialize RAM with room for the indicated number of items,
	 * each of the indicated size. Units are all in the size
	 * determined by {@link io.almostrealism.code.MemoryProvider#getNumberSize()}.
	 * The specified {@link Supplier} is used to generated new instances of the
	 * target type.
	 * This uses {@link CacheLevel#ALL}.
	 */
	protected MemoryBankAdapter(int memLength, int count, Function<DelegateSpec, T> supply) {
		this(memLength, count, supply, defaultCacheLevel);
	}

	/**
	 * Initialize RAM with room for the indicated number of items,
	 * each of the indicated size. Units are all in the size
	 * determined by {@link io.almostrealism.code.MemoryProvider#getNumberSize()}.
	 * The specified {@link Supplier} is used to generated new instances of the
	 * target type.
	 */
	protected MemoryBankAdapter(int memLength, int count, Function<DelegateSpec, T> supply, CacheLevel cacheLevel) {
		this.memLength = memLength;
		this.count = count;
		this.totalMemLength = memLength * count;
		this.supply = supply;
		this.cacheLevel = cacheLevel;
		init();
	}

	/**
	 * Initialize RAM with room for the indicated number of items,
	 * each of the indicated size. Units are all in the size
	 * determined by {@link io.almostrealism.code.MemoryProvider#getNumberSize()}.
	 * The specified {@link Supplier} is used to generated new instances of the
	 * target type.
	 * This uses {@link CacheLevel#ALL}.
	 */
	protected MemoryBankAdapter(int memLength, int count, Function<DelegateSpec, T> supply,
								MemoryData delegate, int delegateOffset) {
		this(memLength, count, supply, delegate, delegateOffset, defaultCacheLevel);
	}

	/**
	 * Initialize RAM with room for the indicated number of items,
	 * each of the indicated size. Units are all in the size of
	 * determined by {@link io.almostrealism.code.MemoryProvider#getNumberSize()}.
	 * The specified {@link Supplier} is used to generated new instances of the
	 * target type.
	 */
	protected MemoryBankAdapter(int memLength, int count, Function<DelegateSpec, T> supply,
								MemoryData delegate, int delegateOffset, CacheLevel cacheLevel) {
		this.memLength = memLength;
		this.count = count;
		this.totalMemLength = memLength * count;
		this.supply = supply;
		this.cacheLevel = cacheLevel;
		setDelegate(delegate, delegateOffset);
		init();
	}

	@Override
	protected void init() {
		super.init();

		if (cacheLevel == CacheLevel.ALL) {
			entriesList = IntStream.range(0, count)
					.map(i -> i * getAtomicMemLength())
					.mapToObj(DelegateSpec::new)
					.map(supply)
					.collect(Collectors.toList());
		} else {
			entriesMap = new HashMap<>();
		}
	}

	@Override
	public T get(int index) {
		if (index >= getCountLong()) {
			throw new IllegalArgumentException(index + " is beyond the range of this bank (" + getCountLong() + ")");
		}

		if (cacheLevel == CacheLevel.ALL) {
			return Optional.ofNullable(index < entriesList.size() ? entriesList.get(index) : null).orElseThrow(UnsupportedOperationException::new);
		} else if (cacheLevel == CacheLevel.ACCESSED) {
			T value = entriesMap.get(Integer.valueOf(index));
			if (value == null) {
				value = supply.apply(new DelegateSpec(index * getAtomicMemLength()));
				entriesMap.put(Integer.valueOf(index), value);
			}
			return value;
		} else {
			return supply.apply(new DelegateSpec(index * getAtomicMemLength()));
		}
	}

	@Override
	public void set(int index, T value) {
		setMem(index * getAtomicMemLength(),
				value, 0,
				getAtomicMemLength());
	}

	public void set(int index, double... values) {
		setMem(index * getAtomicMemLength(), values, 0, values.length);
	}

	@Override
	public int getMemLength() { return totalMemLength; }

	@Override
	public int getAtomicMemLength() { return memLength; }

	@Override
	public long getCountLong() { return count; }

	public Stream<T> stream() {
		return IntStream.range(0, getCount()).mapToObj(this::get);
	}

	public void forEach(Consumer<T> consumer) {
		stream().forEach(consumer);
	}

	public class DelegateSpec {
		private int offset;

		public DelegateSpec(int offset) {
			setOffset(offset);
		}

		public MemoryData getDelegate() { return MemoryBankAdapter.this; }

		public int getOffset() { return offset; }
		public void setOffset(int offset) { this.offset = offset; }
	}

	public enum CacheLevel {
		NONE, ACCESSED, ALL
	}
}
