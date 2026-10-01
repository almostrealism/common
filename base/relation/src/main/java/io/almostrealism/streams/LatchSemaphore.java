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

package io.almostrealism.streams;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A {@link Semaphore} implementation backed by a {@link CountDownLatch}.
 *
 * <p>The latch counts down to zero when the guarded operation (or a set of parallel
 * sub-operations) completes. Callers blocked in {@link #waitFor()} are released once the
 * count reaches zero. This is the metadata-free completion latch used by
 * {@link Semaphore#all(java.util.List)}; a subclass may attach requester attribution (see
 * {@code io.almostrealism.concurrent.DefaultLatchSemaphore}).</p>
 *
 * <p>A merged member that fails is recorded with {@link #fail(Throwable)} and still counts
 * down, so the latch is never pinned by a failure; the first such failure is rethrown from
 * {@link #waitFor()} once the count reaches zero, so a group failure reaches the waiter
 * rather than being lost.</p>
 */
public class LatchSemaphore implements Semaphore {
	/** The underlying latch used for synchronization. */
	private final CountDownLatch latch;

	/** The first failure observed among merged members, rethrown by {@link #waitFor()}. */
	private final AtomicReference<Throwable> failure = new AtomicReference<>();

	/**
	 * Constructs a semaphore whose {@link #waitFor()} returns after {@code count}
	 * {@link #countDown()} calls.
	 *
	 * @param count the number of {@link #countDown()} calls required before
	 *              {@link #waitFor()} returns
	 */
	public LatchSemaphore(int count) {
		this(new CountDownLatch(count));
	}

	/**
	 * Constructs a semaphore sharing an existing latch, allowing a subclass to reuse the
	 * synchronization state under a different attribution.
	 *
	 * @param latch the existing {@link CountDownLatch} to reuse
	 */
	protected LatchSemaphore(CountDownLatch latch) {
		this.latch = latch;
	}

	/**
	 * Returns the underlying latch, so a subclass sharing this synchronization state can
	 * pass it to {@link #LatchSemaphore(CountDownLatch)}.
	 *
	 * @return the underlying latch
	 */
	protected CountDownLatch getLatch() { return latch; }

	/**
	 * Decrements the latch count, releasing waiters once it reaches zero.
	 */
	public void countDown() { latch.countDown(); }

	/**
	 * Records a failure observed while merging a member's completion, so it can be rethrown
	 * from {@link #waitFor()} once the latch releases. Only the first failure is retained;
	 * later ones are ignored. A {@code null} argument is a no-op, so this may be called
	 * unconditionally from a settlement callback.
	 *
	 * @param t the failure to record, or {@code null} if the member completed normally
	 */
	public void fail(Throwable t) {
		if (t != null) failure.compareAndSet(null, t);
	}

	@Override
	public void waitFor() {
		try {
			latch.await();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}

		Throwable t = failure.get();
		if (t == null) return;
		if (t instanceof RuntimeException) throw (RuntimeException) t;
		if (t instanceof Error) throw (Error) t;
		throw new RuntimeException(t);
	}
}
