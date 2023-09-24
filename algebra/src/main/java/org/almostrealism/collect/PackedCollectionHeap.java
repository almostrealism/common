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

package org.almostrealism.collect;

import io.almostrealism.code.Memory;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.PooledMem;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * A collection of {@link PackedCollection}s stored in a single {@link io.almostrealism.code.Memory} instance.
 */
@Deprecated
public class PackedCollectionHeap {
	private static ThreadLocal<PackedCollectionHeap> defaultHeap = new ThreadLocal<>();

	private List<PackedCollection> entries;
	private PackedCollection data;
	private int end;

	public PackedCollectionHeap(int totalSize) {
		entries = new ArrayList<>();
		data = new PackedCollection(totalSize);
	}

	public synchronized PackedCollection allocate(int count) {
		if (end + count > data.getMemLength()) {
			throw new IllegalArgumentException("No room remaining in PackedCollectionHeap");
		}

		PackedCollection allocated = new PackedCollection(new TraversalPolicy(count), 0, data, end);
		end = end + count;
		entries.add(allocated);
		return allocated;
	}

	public PackedCollection get(int index) { return entries.get(index); }

	public Stream<PackedCollection> stream() { return entries.stream(); }

	public <T> Callable<T> wrap(Callable<T> r) {
		return () -> {
			PackedCollectionHeap old = defaultHeap.get();
			defaultHeap.set(this);

			try {
				return r.call();
			} finally {
				defaultHeap.set(old);
			}
		};
	}

	public void use(Runnable r) {
		PackedCollectionHeap old = defaultHeap.get();
		defaultHeap.set(this);

		try {
			r.run();
		} finally {
			defaultHeap.set(old);
		}
	}

	public <T> T use(Supplier<T> r) {
		PackedCollectionHeap old = defaultHeap.get();
		defaultHeap.set(this);

		try {
			return r.get();
		} finally {
			defaultHeap.set(old);
		}
	}

	public PooledMem asPool(int size) {
		return new PooledMem() {
			@Override
			public int reserveOffset(MemoryData owner) {
				return allocate(size).getDelegateOffset();
			}

			@Override
			public Memory getMem() {
				return data.getMem();
			}

			@Override
			public void reassign(Memory mem) {
				throw new UnsupportedOperationException();
			}

			@Override
			public int getMemLength() {
				return data.getMemLength();
			}

			@Override
			public void setDelegate(MemoryData m, int offset) {
				throw new UnsupportedOperationException();
			}

			@Override
			public MemoryData getDelegate() {
				return data;
			}

			@Override
			public int getDelegateOffset() {
				return 0;
			}

			@Override
			public void destroy() { }
		};
	}

	public synchronized void destroy() {
		entries.clear();
		end = 0;
		data.destroy();
	}

	public static PackedCollectionHeap getDefault() {
		return defaultHeap.get();
	}
}
