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
import io.almostrealism.streams.CompositeSemaphore;
import io.almostrealism.streams.LatchState;
import io.almostrealism.streams.Semaphore;

import java.util.List;

/**
 * A {@link CompositeSemaphore} that carries the {@link OperationMetadata} of the operation
 * waiting on the group, making it an {@link OperationSemaphore}.
 */
public class DefaultCompositeSemaphore extends CompositeSemaphore implements OperationSemaphore {
	/** The metadata describing the operation waiting on this group. */
	private final OperationMetadata requester;

	/**
	 * Creates the completion of the given members, attributed to the given requester.
	 *
	 * @param requester the metadata of the operation waiting on the group, or {@code null}
	 * @param members   the semaphores to combine; none may be {@code null}
	 */
	public DefaultCompositeSemaphore(OperationMetadata requester, List<Semaphore> members) {
		super(members);
		this.requester = requester;
	}

	/**
	 * Creates a view of an existing composite attributed to a different requester, sharing its
	 * members and settlement.
	 *
	 * @param requester  the new requester metadata
	 * @param members    the members of the existing composite
	 * @param settlement the settlement state of the existing composite
	 */
	protected DefaultCompositeSemaphore(OperationMetadata requester, List<Semaphore> members,
										LatchState settlement) {
		super(members, settlement);
		this.requester = requester;
	}

	@Override
	public OperationMetadata getRequester() { return requester; }

	@Override
	public Semaphore withRequester(OperationMetadata requester) {
		return new DefaultCompositeSemaphore(requester, getMembers(), getSettlement());
	}
}
