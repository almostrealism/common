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

package org.almostrealism.math;

import org.jocl.CLException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * A {@link MemoryBankAdapter} is the default implementation for tracking
 * a section of RAM to store a collection of {@link MemWrapper}s in a
 * single {@link org.jocl.cl_mem}.
 *
 * @author  Michael Murray
 */
public abstract class MemoryBankAdapter<T extends MemWrapper> extends MemWrapperAdapter implements MemoryBank<T> {
	private int memLength, count;
	private int totalMemLength;

	private Function<DelegateSpec, T> supply;

	private List<T> entriesList;
	private Map<Integer, T> entriesMap;

	private CacheLevel cacheLevel;

	/**
	 * Initialize RAM with room for the indicated number of items,
	 * each of the indicated size. Units are all in the size of
	 * {@link org.jocl.Sizeof#cl_double}. The specified {@link Supplier}
	 * is used to generated new instances of the target type.
	 * This uses {@link CacheLevel#ALL}.
	 */
	protected MemoryBankAdapter(int memLength, int count, Function<DelegateSpec, T> supply) {
		this(memLength, count, supply, CacheLevel.ALL);
	}

	/**
	 * Initialize RAM with room for the indicated number of items,
	 * each of the indicated size. Units are all in the size of
	 * {@link org.jocl.Sizeof#cl_double}. The specified {@link Supplier}
	 * is used to generated new instances of the target type.
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
	 * each of the indicated size. Units are all in the size of
	 * {@link org.jocl.Sizeof#cl_double}. The specified {@link Supplier}
	 * is used to generated new instances of the target type.
	 * This uses {@link CacheLevel#ALL}.
	 */
	protected MemoryBankAdapter(int memLength, int count, Function<DelegateSpec, T> supply,
								MemWrapper delegate, int delegateOffset) {
		this(memLength, count, supply, delegate, delegateOffset, CacheLevel.ALL);
	}

	/**
	 * Initialize RAM with room for the indicated number of items,
	 * each of the indicated size. Units are all in the size of
	 * {@link org.jocl.Sizeof#cl_double}. The specified {@link Supplier}
	 * is used to generated new instances of the target type.
	 */
	protected MemoryBankAdapter(int memLength, int count, Function<DelegateSpec, T> supply,
								MemWrapper delegate, int delegateOffset, CacheLevel cacheLevel) {
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
		if (cacheLevel == CacheLevel.ALL) {
			return entriesList.get(index);
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
		setMem(getOffset() + index * getAtomicMemLength(),
				(MemWrapperAdapter) value, 0,
				getAtomicMemLength());
	}

	@Override
	public int getMemLength() { return totalMemLength; }

	@Override
	public int getAtomicMemLength() { return memLength; }

	@Override
	public int getCount() { return count; }

	public class DelegateSpec {
		private int offset;

		public DelegateSpec(int offset) {
			setOffset(offset);
		}

		public MemWrapper getDelegate() { return MemoryBankAdapter.this; }

		public int getOffset() { return offset; }
		public void setOffset(int offset) { this.offset = offset; }
	}

	public enum CacheLevel {
		NONE, ACCESSED, ALL
	}
}
