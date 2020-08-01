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

import java.util.ArrayList;
import java.util.List;
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

	private List<T> entries;

	/**
	 * Initialize RAM with room for the indicated number of items,
	 * each of the indicated size. Units are all in the size of
	 * {@link org.jocl.Sizeof#cl_double}. The specified {@link Supplier}
	 * is used to generated new instances of the target type.
	 */
	protected MemoryBankAdapter(int memLength, int count, Function<DelegateSpec, T> supply) {
		this.memLength = memLength;
		this.count = count;
		this.totalMemLength = memLength * count;
		this.supply = supply;
		init();
	}

	protected void init() {
		super.init();
		
		entries = IntStream.range(0, count)
				.map(i -> i * memLength)
				.mapToObj(DelegateSpec::new)
				.map(supply)
				.collect(Collectors.toList());
	}

	@Override
	public T get(int index) { return entries.get(index); }

	@Override
	public void set(int index, T value) {
		setMem(getOffset() + index * getMemLength(), (MemWrapperAdapter) value, value.getOffset(), getMemLength());
	}

	@Override
	public int getMemLength() { return totalMemLength; }

	public class DelegateSpec {
		private int offset;

		public DelegateSpec(int offset) {
			setOffset(offset);
		}

		public MemWrapper getDelegate() { return MemoryBankAdapter.this; }

		public int getOffset() { return offset; }
		public void setOffset(int offset) { this.offset = offset; }
	}
}
