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

package org.almostrealism.hardware;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.stream.IntStream;

public class MemoryPool<T extends MemWrapper> extends MemoryBankAdapter<T> implements PooledMem<T> {
	private ArrayBlockingQueue<Integer> available;
	private List<Owner> owners;
	private int defaultGc;

	public MemoryPool(int memLength, int size) {
		super(memLength, size, null, CacheLevel.NONE);
		defaultGc = size > 100 ? size / 100 : 1;
		initQueue();
	}

	protected void initQueue() {
		if (available != null) return;

		this.available = new ArrayBlockingQueue<>(getCount());
		IntStream.range(0, getCount()).map(i -> i * getAtomicMemLength()).forEach(available::add);
		owners = new ArrayList<>();

		startGcThread();
	}

	@Override
	public synchronized int reserveOffset(T owner) {
		Owner o = owner(owner);
		owners.add(o);
		return o.offset;
	}

	public synchronized Owner owner(T owner) {
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

		System.out.println(gcLog(freed, demand));
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
