/*
 * Copyright 2024 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.almostrealism.concurrent;

import io.almostrealism.profile.OperationMetadata;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;

/**
 * A {@link Semaphore} implementation backed by a {@link CountDownLatch}.
 *
 * <p>The latch counts down to zero when the guarded operation (or a set of
 * parallel sub-operations) completes. Callers blocked in {@link #waitFor()} are
 * released once the count reaches zero.</p>
 */
public class DefaultLatchSemaphore implements Semaphore {
	/** The metadata describing the operation waiting on this semaphore. */
	private final OperationMetadata requester;

	/** The underlying latch used for synchronization. */
	private final CountDownLatch latch;

	/**
	 * Constructs a semaphore inheriting the requester metadata from a parent semaphore.
	 *
	 * @param parent the parent semaphore whose requester is used, or {@code null}
	 * @param count  the number of {@link #countDown()} calls required before {@link #waitFor()} returns
	 */
	public DefaultLatchSemaphore(Semaphore parent, int count) {
		this(Optional.ofNullable(parent).map(Semaphore::getRequester).orElse(null), count);
	}

	/**
	 * Constructs a semaphore with the given requester metadata and initial count.
	 *
	 * @param requester the metadata of the operation waiting on this semaphore
	 * @param count     the initial latch count
	 */
	public DefaultLatchSemaphore(OperationMetadata requester, int count) {
		this(requester, new CountDownLatch(count));
	}

	/**
	 * Constructs a semaphore sharing an existing latch with a new requester, used by
	 * {@link #withRequester(OperationMetadata)}.
	 *
	 * @param requester the new requester metadata
	 * @param latch     the existing {@link CountDownLatch} to reuse
	 */
	protected DefaultLatchSemaphore(OperationMetadata requester, CountDownLatch latch) {
		this.requester = requester;
		this.latch = latch;
	}

	@Override
	public OperationMetadata getRequester() { return requester; }

	public void countDown() { latch.countDown(); }

	@Override
	public void waitFor() {
		try {
			latch.await();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	@Override
	public Semaphore withRequester(OperationMetadata requester) {
		return new DefaultLatchSemaphore(requester, latch);
	}
}
