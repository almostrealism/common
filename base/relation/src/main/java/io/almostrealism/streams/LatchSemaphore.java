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

/**
 * A {@link Semaphore} implementation backed by a {@link CountDownLatch}.
 *
 * <p>The latch counts down to zero when the guarded operation (or a set of parallel
 * sub-operations) completes. Callers blocked in {@link #waitFor()} are released once the
 * count reaches zero. This is the metadata-free completion latch used by
 * {@link Semaphore#all(java.util.List)}; a subclass may attach requester attribution (see
 * {@code io.almostrealism.concurrent.DefaultLatchSemaphore}).</p>
 */
public class LatchSemaphore implements Semaphore {
	/** The underlying latch used for synchronization. */
	private final CountDownLatch latch;

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

	@Override
	public void waitFor() {
		try {
			latch.await();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
