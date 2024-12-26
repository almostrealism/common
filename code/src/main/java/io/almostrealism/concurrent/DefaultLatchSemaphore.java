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

import io.almostrealism.code.OperationMetadata;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;

public class DefaultLatchSemaphore implements Semaphore {
	private final OperationMetadata requester;
	private final CountDownLatch latch;

	public DefaultLatchSemaphore(Semaphore parent, int count) {
		this(Optional.ofNullable(parent).map(Semaphore::getRequester).orElse(null), count);
	}

	public DefaultLatchSemaphore(OperationMetadata requester, int count) {
		this(requester, new CountDownLatch(count));
	}

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
