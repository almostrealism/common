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

import io.almostrealism.code.MemoryProvider;
import io.almostrealism.code.OperationAdapter;
import io.almostrealism.lifecycle.Destroyable;
import org.almostrealism.hardware.ProducerCache;

import java.util.ArrayList;
import java.util.List;
import java.util.Stack;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * A collection of {@link Bytes}s stored in a single {@link io.almostrealism.code.Memory} instance.
 */
public class Heap {
	private static ThreadLocal<Heap> defaultHeap = new ThreadLocal<>();

	private MemoryProvider memory;
	private int stageSize;

	private HeapStage root;
	private Stack<HeapStage> stages;

	public Heap(int size) {
		this(null, size, size / 4);
	}

	public Heap(int rootSize, int stageSize) {
		this(null, rootSize, stageSize);
	}

	public Heap(MemoryProvider memory, int rootSize, int stageSize) {
		this.memory = memory;
		this.stageSize = stageSize;
		this.root = new HeapStage(rootSize);
	}

	public HeapStage getStage() {
		return (stages == null || stages.isEmpty()) ? root : stages.peek();
	}

	public Bytes allocate(int count) {
		return getStage().allocate(count);
	}

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

	public Heap use(Runnable r) {
		Heap old = defaultHeap.get();
		defaultHeap.set(this);

		try {
			r.run();
		} finally {
			defaultHeap.set(old);
		}

		return this;
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

	protected void push() {
		if (stages == null) {
			stages = new Stack<>();
		}

		stages.push(new HeapStage(stageSize));
	}

	protected void pop() {
		if (stages != null && !stages.isEmpty()) {
			stages.pop().destroy();
		}
	}

	public synchronized void destroy() {
		if (stages != null) {
			while (!stages.isEmpty()) {
				stages.pop().destroy();
			}
		}

		root.destroy();
	}

	private List<Supplier> getDependentOperations() {
		if (stages != null && !stages.isEmpty()) {
			return stages.peek().dependencies.dependentOperations;
		}

		return root.dependencies == null ? null : root.dependencies.dependentOperations;
	}

	private List<OperationAdapter> getCompiledDependencies() {
		if (stages != null && !stages.isEmpty()) {
			return stages.peek().dependencies.compiledDependencies;
		}

		return root.dependencies == null ? null : root.dependencies.compiledDependencies;
	}

	public class HeapStage implements Destroyable {
		private List<Bytes> entries;
		private Bytes data;
		private int end;

		HeapDependencies dependencies;

		public HeapStage(int size) {
			entries = new ArrayList<>();
			data = memory == null ? new Bytes(size) : Bytes.of(memory.allocate(size), size);
			dependencies = new HeapDependencies();
		}

		public synchronized Bytes allocate(int count) {
			if (end + count > data.getMemLength()) {
				throw new IllegalArgumentException("No room remaining in Heap");
			}

			Bytes allocated = new Bytes(count, data, end);
			end = end + count;
			entries.add(allocated);
			return allocated;
		}

		public Bytes get(int index) { return entries.get(index); }

		public Bytes getBytes() { return data; }

		public Stream<Bytes> stream() { return entries.stream(); }

		@Override
		public void destroy() {
			entries.clear();
			end = 0;
			data.destroy();

			if (dependencies != null) {
				dependencies.destroy();
				dependencies = null;
			}
		}
	}

	private class HeapDependencies implements Destroyable {
		private List<Supplier> dependentOperations;
		private List<OperationAdapter> compiledDependencies;

		public HeapDependencies() {
			dependentOperations = new ArrayList<>();
			compiledDependencies = new ArrayList<>();
		}

		@Override
		public void destroy() {
			if (dependentOperations != null) {
				dependentOperations.forEach(ProducerCache::purgeEvaluableCache);
				dependentOperations.forEach(o -> {
					if (o instanceof Destroyable)
						((Destroyable) o).destroy();
				});
				dependentOperations = null;
			}

			if (compiledDependencies != null) {
				compiledDependencies.forEach(OperationAdapter::destroy);
				compiledDependencies = null;
			}
		}
	}

	public static Heap getDefault() {
		return defaultHeap.get();
	}

	public static void stage(Runnable r) {
		Heap defaultHeap = getDefault();

		if (defaultHeap == null) {
			r.run();
			return;
		}

		try {
			defaultHeap.push();
			r.run();
		} finally {
			defaultHeap.pop();
		}
	}

	public static <T> Supplier<T> addOperation(Supplier<T> operation) {
		if (getDefault() != null) {
			getDefault().getDependentOperations().add(operation);
		}

		return operation;
	}

	public static <T extends OperationAdapter> T addCompiled(T operation) {
		if (getDefault() != null) {
			getDefault().getCompiledDependencies().add(operation);
		}

		return operation;
	}
}

