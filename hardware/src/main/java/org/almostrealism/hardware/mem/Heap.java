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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * A collection of {@link Bytes}s stored in a single {@link io.almostrealism.code.Memory} instance.
 */
public class Heap {
	private static ThreadLocal<Heap> defaultHeap = new ThreadLocal<>();

	private List<Bytes> entries;
	private Bytes data;
	private int end;

	public Heap(int totalSize) {
		entries = new ArrayList<>();
		data = new Bytes(totalSize);
	}

	public synchronized Bytes allocate(int count) {
		if (end + count > data.getMemLength()) {
			throw new IllegalArgumentException("No room remaining in PackedCollectionHeap");
		}

		Bytes allocated = new Bytes(count, data, end);
		end = end + count;
		entries.add(allocated);
		return allocated;
	}

	public Bytes get(int index) { return entries.get(index); }

	public Stream<Bytes> stream() { return entries.stream(); }

	public <T> Callable<T> wrap(Callable<T> r) {
		return () -> {
			Heap old = defaultHeap.get();
			defaultHeap.set(this);

			try {
				return r.call();
			} finally {
				defaultHeap.set(old);
			}
		};
	}

	public void use(Runnable r) {
		Heap old = defaultHeap.get();
		defaultHeap.set(this);

		try {
			r.run();
		} finally {
			defaultHeap.set(old);
		}
	}

	public <T> T use(Supplier<T> r) {
		Heap old = defaultHeap.get();
		defaultHeap.set(this);

		try {
			return r.get();
		} finally {
			defaultHeap.set(old);
		}
	}

	public synchronized void destroy() {
		entries.clear();
		end = 0;
		data.destroy();
	}

	public static Heap getDefault() {
		return defaultHeap.get();
	}
}

