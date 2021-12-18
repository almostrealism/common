/*
 * Copyright 2021 Michael Murray
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
import org.almostrealism.hardware.PooledMem;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.Function;
import java.util.stream.IntStream;

/**
 * A {@link MemoryPool} is the standard implementation of {@link PooledMem},
 * which uses {@link WeakReference}s to determine if a segment of the reserved
 * memory can be reused.
 *
 * @author  Michael Murray
 */
public class MemoryPool<T extends MemoryData> extends MemoryBankAdapter<T> implements PooledMem<T> {
	public static boolean enableLog = false;

	private ArrayBlockingQueue<Integer> available;
	private List<Owner> owners;
	private int defaultGc;
	private boolean destroying;

	/**
	 * Create a {@link MemoryPool}.
	 *
	 * @param memLength  The size of the reservable segments.
	 * @param size  The number of reservable segments.
	 */
	public MemoryPool(int memLength, int size) {
		this(memLength, size, null);
	}

	/**
	 * Create a {@link MemoryPool}.
	 *
	 * @param memLength  The size of the reservable segments.
	 * @param size  The number of reservable segments.
	 */
	protected MemoryPool(int memLength, int size, Function<DelegateSpec, T> supply) {
		super(memLength, size, supply, CacheLevel.NONE);
		defaultGc = size > 200 ? size / 200 : 1;
		initQueue();
	}

	protected void initQueue() {
		if (available != null) return;

		this.available = new ArrayBlockingQueue<>(getCount());
		IntStream.range(0, getCount()).map(i -> i * getAtomicMemLength()).forEach(available::add);
		owners = new ArrayList<>();

		startGcThread();
	}

	/**
	 * Reserve a segment of memory.
	 *
	 * @param owner  The referencer that should be used to determine when
	 *               the memory segment will be available for reuse.
	 * @return  The index of the start of the reserved segment.
	 */
	@Override
	public synchronized int reserveOffset(T owner) {
		if (destroying) {
			throw new UnsupportedOperationException();
		}

		Owner o = owner(owner);
		owners.add(o);
		return o.offset;
	}

	protected synchronized Owner owner(T owner) {
		try {
			Owner o = new Owner();
			o.reference = new WeakReference<>(owner);

			Integer next = available.poll();
			if (next == null) {
				gc();
				o.offset = available.remove();
			} else {
				o.offset = next;
			}

			return o;
		} catch (Exception e) {
			throw new RuntimeException("Pool exhausted", e);
		}
	}

	@Override
	public synchronized void destroy() {
		destroying = true;
		owners.forEach(o -> {
			T target = o.reference.get();
			if (target != null) target.destroy();
		});
		owners.clear();
		super.destroy();
	}

	public synchronized void gc() { gc(defaultGc); }

	public synchronized void gc(int demand) {
		int freed = 0;

		Iterator<Owner> itr = owners.iterator();
		w: while (itr.hasNext()) {
			Owner o = itr.next();
			if (o.reference.get() != null) continue w;

			itr.remove();
			available.add(o.offset);
			freed++;

			if (freed >= demand) break w;
		}

		if (enableLog) System.out.println(gcLog(freed, demand));
	}

	private String gcLog(int freed, int demand) {
		return getClass().getSimpleName() + ": Freed " + freed + "/" + demand;
	}

	private void startGcThread() {
		Thread t = new Thread(() -> {
			try {
				Thread.sleep(30000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}

			if (destroying) {
				return;
			}

			gc();
		}, getClass().getSimpleName() + " GC Thread");
		t.setDaemon(true);
		t.start();
	}

	private class Owner {
		public WeakReference<T> reference;
		public Integer offset;
	}
}
