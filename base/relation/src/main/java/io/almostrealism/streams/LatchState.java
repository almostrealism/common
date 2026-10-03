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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The completion state behind a {@link LatchSemaphore}: a count of outstanding parts, the
 * first failure among them, and the callbacks to run once the count reaches zero.
 *
 * <p>Several {@link LatchSemaphore}s can share one state, so that a view of a completion
 * attributed to a different requester counts down, fails and settles together with the
 * original.</p>
 *
 * <p>Settlement callbacks are handed to {@link Semaphore#CALLBACK_EXECUTOR} once the count
 * reaches zero (at once, if it already has), so they never run under whatever the counting
 * thread holds. Nothing waits on a thread for them in the meantime, so any number of callbacks
 * can be registered on any number of pending latches.</p>
 */
public class LatchState {
	/** Counts the parts still outstanding. */
	private final CountDownLatch latch;

	/** The first failure recorded among the parts. */
	private final AtomicReference<Throwable> failure;

	/** Callbacks waiting for the count to reach zero; {@code null} once they have run. */
	private List<Runnable> settlementCallbacks;

	/**
	 * Creates a state with the given number of outstanding parts.
	 *
	 * @param count the number of parts that must count down before the state settles
	 */
	public LatchState(int count) {
		this.latch = new CountDownLatch(count);
		this.failure = new AtomicReference<>();
		this.settlementCallbacks = count > 0 ? new ArrayList<>() : null;
	}

	/**
	 * Counts one part down, running the settlement callbacks if it was the last.
	 */
	public void countDown() {
		latch.countDown();
		if (latch.getCount() > 0) return;

		List<Runnable> callbacks;
		synchronized (this) {
			callbacks = settlementCallbacks;
			settlementCallbacks = null;
		}

		if (callbacks != null) callbacks.forEach(Semaphore.CALLBACK_EXECUTOR::execute);
	}

	/**
	 * Records a failure of one part. Only the first failure is kept; {@code null} is ignored.
	 *
	 * @param t the failure, or {@code null}
	 */
	public void fail(Throwable t) {
		if (t != null) failure.compareAndSet(null, t);
	}

	/**
	 * Runs the given callback once the count has reached zero, whether or not a part failed.
	 *
	 * @param callback the callback to run on settlement
	 */
	public void whenSettled(Runnable callback) {
		synchronized (this) {
			if (settlementCallbacks != null) {
				settlementCallbacks.add(callback);
				return;
			}
		}

		Semaphore.CALLBACK_EXECUTOR.execute(callback);
	}

	/**
	 * Waits for the count to reach zero and rethrows the first recorded failure, if any.
	 */
	public void await() {
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
