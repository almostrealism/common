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

package org.almostrealism.hardware.metal;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One thread's registration as a waiter on the closed {@link CommandBatch}es it waits for off a
 * {@link MetalCommandRunner}'s executor thread, in queue order, the last of them its
 * {@link #getTarget() target}: the batch of the dispatch being waited for. The command buffer of
 * every one of them stays retained until the registration is withdrawn, which happens exactly once
 * and from all of them together (see {@link CommandBatch#addWaiter()}).
 *
 * <p>Withdrawal can be requested from more than one path — the drain after the wait, the queued
 * withdrawal of an interrupted caller, or the calling thread itself once the executor has been
 * destroyed — so it is guarded to take effect only the first time.</p>
 */
final class CommandBatchWaiter {
	/** The closed batches this waiter is registered on, in queue order. */
	private final List<CommandBatch> batches;
	/** Whether this registration has already been withdrawn. */
	private final AtomicBoolean withdrawn = new AtomicBoolean();

	/**
	 * Registers a waiter on each of the given closed batches. Executor-thread only, like the list
	 * of closed batches they are taken from.
	 *
	 * @param batches the closed batches to wait on, in queue order, the last the target
	 */
	CommandBatchWaiter(List<CommandBatch> batches) {
		this.batches = List.copyOf(batches);
		this.batches.forEach(CommandBatch::addWaiter);
	}

	/**
	 * Returns the closed batches this waiter is registered on.
	 *
	 * @return the batches being waited on, in queue order
	 */
	List<CommandBatch> getBatches() { return batches; }

	/**
	 * Returns the batch this waiter waits for: the last it is registered on.
	 *
	 * @return the batch being waited for
	 */
	CommandBatch getTarget() { return batches.get(batches.size() - 1); }

	/** Withdraws this registration from every batch, unless it has already been withdrawn. */
	void withdraw() {
		if (withdrawn.compareAndSet(false, true)) batches.forEach(CommandBatch::removeWaiter);
	}
}
