/*
 * Copyright 2025 Michael Murray
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

package io.almostrealism.concurrent;

import io.almostrealism.profile.OperationMetadata;

/**
 * A synchronization primitive used to coordinate completion of asynchronous hardware
 * executions with their waiting callers.
 *
 * <p>A semaphore carries an optional {@link OperationMetadata} that identifies the
 * operation waiting on it, enabling diagnostic attribution of blocked threads.</p>
 */
public interface Semaphore {
	/**
	 * Returns the metadata describing the operation that is waiting on this semaphore,
	 * or {@code null} if no requester metadata is available.
	 *
	 * @return the requester metadata, or {@code null}
	 */
	OperationMetadata getRequester();

	/**
	 * Blocks the calling thread until the guarded operation has completed.
	 */
	void waitFor();

	/**
	 * Returns a new semaphore backed by the same synchronization state as this one
	 * but attributed to the given requester.
	 *
	 * @param requester the metadata of the new requester
	 * @return a semaphore with the updated requester
	 */
	Semaphore withRequester(OperationMetadata requester);

	/**
	 * Returns an opaque token identifying the provider submission batch this completion was encoded
	 * into — for example a Metal command buffer or an OpenCL command-queue marker — or {@code null}
	 * when the provider exposes no such grouping (e.g. fully synchronous execution, where there is
	 * nothing to batch).
	 *
	 * <p>Two semaphores whose batch tokens are both non-null and not {@link Object#equals equal}
	 * were encoded into different batches. This lets a caller that submitted a group of dispatches
	 * which must share a batch — so the provider's in-batch hazard tracking can order their
	 * read-after-write dependencies — detect after the fact that the group was split across batches
	 * (e.g. because it exceeded the provider's per-batch limit), in which case that ordering is no
	 * longer guaranteed.</p>
	 *
	 * @return an opaque batch token, or {@code null} if the provider exposes no batch grouping
	 */
	default Object getBatch() {
		return null;
	}

	/**
	 * Registers a callback to be invoked on a background thread once the guarded
	 * operation has completed.
	 *
	 * @param r the callback to invoke after {@link #waitFor()} returns
	 */
	default void onComplete(Runnable r) {
		new Thread(() -> {
			waitFor();
			r.run();
		}, "Semaphore onComplete").start();
	}
}
