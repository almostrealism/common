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

package io.almostrealism.concurrent;

import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.streams.Semaphore;

import java.util.List;

/**
 * A {@link Semaphore} that carries the {@link OperationMetadata} identifying the operation
 * waiting on it, enabling diagnostic attribution of blocked threads.
 *
 * <p>Every concrete completion handle in the framework is an {@link OperationSemaphore}: the
 * metadata-aware operations gathered here &mdash; requester attribution ({@link #getRequester()},
 * {@link #withRequester(OperationMetadata)}) and the merge primitive ({@link #all}) &mdash; all
 * reference {@link OperationMetadata}, so they live in this module rather than on the plain
 * {@link Semaphore} type, which must remain reachable from
 * {@code io.almostrealism.streams.StreamingEvaluable} without a dependency on the
 * operation-metadata model.</p>
 *
 * @see Semaphore
 */
public interface OperationSemaphore extends Semaphore {
	/**
	 * Returns the metadata describing the operation that is waiting on this semaphore,
	 * or {@code null} if no requester metadata is available.
	 *
	 * @return the requester metadata, or {@code null}
	 */
	OperationMetadata getRequester();

	/**
	 * Returns a new semaphore backed by the same synchronization state as this one
	 * but attributed to the given requester.
	 *
	 * @param requester the metadata of the new requester
	 * @return a semaphore with the updated requester
	 */
	Semaphore withRequester(OperationMetadata requester);

	/**
	 * Returns a {@link Semaphore} that completes once every one of the given semaphores
	 * has completed &mdash; the merge primitive for an operation that depends on several
	 * prior completions (multiple asynchronously evaluated arguments, a group of copies,
	 * a join across parallel work).
	 *
	 * <p>This is the metadata-attributing counterpart of {@link Semaphore#all(List)}: the
	 * composite is attributed to {@code requester}, so a thread blocked on it can be
	 * diagnosed. The degenerate cases (nothing to wait for, or a single passed-through
	 * completion) behave identically.</p>
	 *
	 * @param requester  the metadata of the operation that will wait on the composite
	 * @param semaphores the completions to merge; may contain nulls
	 * @return a semaphore completing after all of the given semaphores, or {@code null}
	 *         when there is nothing to wait for
	 */
	static Semaphore all(OperationMetadata requester, List<Semaphore> semaphores) {
		return Semaphore.all(semaphores, count -> new DefaultLatchSemaphore(requester, count));
	}
}
