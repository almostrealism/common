/*
 * Copyright 2026 Michael Murray
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

import io.almostrealism.lifecycle.Destroyable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Native allocations a memory provider has released but kept for reuse, by size in bytes.
 *
 * <p>Allocating and freeing native memory can cost far more than the work done with it. A
 * CUDA managed allocation, for example, is slow to create and its release synchronizes the
 * device, and a workload such as a training loop creates and drops the same sizes of
 * intermediate result on every step. A provider that holds one of these caches keeps what it
 * releases instead of freeing it, and hands it back out for the next allocation of the same
 * size.</p>
 *
 * <p>A provider adopts it in three places:</p>
 * <ul>
 *   <li>{@link #take(long)} before making a native allocation, using the returned handle
 *       instead when there is one;</li>
 *   <li>{@link #offer(long, Object)} instead of freeing a handle, freeing it only if the
 *       cache declines it;</li>
 *   <li>{@link #flush(Consumer)} when it needs the memory back (for example, when an
 *       allocation would otherwise exceed its reservation), and {@link #close(Consumer)}
 *       before it is destroyed, after which nothing is kept.</li>
 * </ul>
 *
 * <p>Handles are cached rather than the memory objects that wrapped them, because a
 * {@code MemoryData} that was destroyed may still refer to its old memory object; each reuse
 * is wrapped afresh. Reusing a handle is exactly as safe as freeing it would have been: the
 * provider offers a handle only when it would otherwise free it, which is only once no
 * kernel is still using it (see {@link KernelMemoryGuard}).</p>
 *
 * <p>The cache holds at most {@code capacity} bytes in total, and never a single handle
 * larger than {@code maxEntry} bytes: large allocations are rare and expensive to hold idle,
 * and are freed as before.</p>
 *
 * @param <H> the provider's native handle type
 */
public class AllocationCache<H> {
	/** The most bytes the cache holds at once. */
	private final long capacity;

	/** The largest handle, in bytes, the cache holds. */
	private final long maxEntry;

	/** Released handles, by size in bytes. Guarded by this cache's monitor. */
	private final Map<Long, Deque<H>> released = new HashMap<>();

	/** The total size of the held handles, in bytes. Guarded by this cache's monitor. */
	private long held;

	/** Whether the cache has been closed and holds nothing more. Guarded by this cache's monitor. */
	private boolean closed;

	/**
	 * Creates a cache.
	 *
	 * @param capacity the most bytes to hold at once
	 * @param maxEntry the largest handle, in bytes, to hold
	 */
	public AllocationCache(long capacity, long maxEntry) {
		this.capacity = capacity;
		this.maxEntry = maxEntry;
	}

	/**
	 * Removes and returns a held handle of exactly the given size, or {@code null} if there is
	 * none, in which case the provider allocates as usual.
	 *
	 * @param bytes the size of the allocation
	 * @return a released handle of that size, or {@code null}
	 */
	public synchronized H take(long bytes) {
		Deque<H> handles = released.get(bytes);
		if (handles == null || handles.isEmpty()) return null;

		held -= bytes;
		return handles.pop();
	}

	/**
	 * Keeps a released handle for reuse, if there is room for it and the cache is open. A
	 * handle the cache declines must be freed by the provider.
	 *
	 * @param bytes  the size of the handle
	 * @param handle the released handle
	 * @return true if the cache kept the handle, false if the provider must free it
	 */
	public synchronized boolean offer(long bytes, H handle) {
		if (closed || bytes > maxEntry || held + bytes > capacity) return false;

		released.computeIfAbsent(bytes, b -> new ArrayDeque<>()).push(handle);
		held += bytes;
		return true;
	}

	/** Returns the total size of the held handles, in bytes. */
	public synchronized long getHeldBytes() { return held; }

	/**
	 * Frees every held handle with {@code release}. Every handle is released even if releasing
	 * one fails; the first failure is rethrown once all have been attempted.
	 *
	 * @param release frees a handle
	 */
	public void flush(Consumer<H> release) {
		List<H> handles = new ArrayList<>();

		synchronized (this) {
			released.values().forEach(handles::addAll);
			released.clear();
			held = 0;
		}

		List<Runnable> releases = new ArrayList<>(handles.size());
		for (H handle : handles) {
			releases.add(() -> release.accept(handle));
		}

		Destroyable.releaseAll(releases);
	}

	/**
	 * Closes the cache, so that it keeps nothing it is offered from now on, and frees every
	 * handle it holds with {@code release}.
	 *
	 * @param release frees a handle
	 */
	public void close(Consumer<H> release) {
		synchronized (this) {
			closed = true;
		}

		flush(release);
	}
}
