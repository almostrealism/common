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

package org.almostrealism.hardware.cl;

import io.almostrealism.concurrent.OperationSemaphore;
import io.almostrealism.streams.Semaphore;
import io.almostrealism.profile.OperationMetadata;
import org.almostrealism.hardware.profile.RunData;
import org.jocl.cl_event;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * {@link OperationSemaphore} implementation backed by OpenCL {@link cl_event}.
 *
 * <p>Enables synchronization between OpenCL operations by wrapping {@link cl_event}
 * and waiting for event completion with optional profiling. A dependent OpenCL
 * dispatch orders itself after this one <em>inside the provider</em> by placing the
 * event in its enqueue wait-list (via {@link #whileValid(Consumer)}) rather than
 * blocking the host.</p>
 *
 * <p>{@link #waitFor()} is idempotent and may be invoked by multiple parties (a chain's
 * final wait, {@link org.almostrealism.hardware.mem.Heap} lifecycle draining, a direct
 * caller): the first wait processes and releases the event; later waits return
 * immediately. The event of a semaphore that is never waited is released only when the
 * OpenCL context is torn down, so completions should ultimately be waited at a genuine
 * boundary.</p>
 *
 * @see CLComputeContext#processEvent(cl_event, Consumer)
 * @see Semaphore
 */
public class CLSemaphore implements OperationSemaphore {
	/** Metadata identifying the operation that requested this semaphore. */
	private OperationMetadata requester;

	/** The compute context for processing events. */
	private CLComputeContext context;

	/** The OpenCL event to wait for. */
	private cl_event event;

	/** Optional consumer for profiling data when the event completes. */
	private Consumer<RunData> profile;

	/**
	 * Whether the event has been processed (and therefore released). Shared across
	 * {@link #withRequester(OperationMetadata)} copies, and used as the monitor
	 * guarding every use of {@link #event} so a wait-list reference can never race
	 * a concurrent waiter's release.
	 */
	private AtomicBoolean processed;

	/**
	 * Callback invoked exactly once, after the event has been processed &mdash; e.g. the
	 * dispatch's released-memory bookkeeping. Shared across
	 * {@link #withRequester(OperationMetadata)} copies; may be {@code null}.
	 */
	private Runnable onProcessed;

	/**
	 * Constructs a new CLSemaphore for the specified OpenCL event.
	 *
	 * @param requester the metadata identifying the requesting operation
	 * @param context   the compute context for event processing
	 * @param event     the OpenCL event to synchronize on
	 * @param profile   optional consumer for profiling data, or {@code null}
	 */
	public CLSemaphore(OperationMetadata requester, CLComputeContext context,
					   cl_event event, Consumer<RunData> profile) {
		this(requester, context, event, profile, null, new AtomicBoolean(false));
	}

	/**
	 * Constructs a new CLSemaphore for the specified OpenCL event, with a callback that
	 * runs once the event has been processed.
	 *
	 * @param requester   the metadata identifying the requesting operation
	 * @param context     the compute context for event processing
	 * @param event       the OpenCL event to synchronize on
	 * @param profile     optional consumer for profiling data, or {@code null}
	 * @param onProcessed callback invoked once after the event completes, or {@code null}
	 */
	public CLSemaphore(OperationMetadata requester, CLComputeContext context,
					   cl_event event, Consumer<RunData> profile, Runnable onProcessed) {
		this(requester, context, event, profile, onProcessed, new AtomicBoolean(false));
	}

	/**
	 * Constructs a semaphore sharing processing state with an existing instance, used by
	 * {@link #withRequester(OperationMetadata)} so copies never double-release the event.
	 *
	 * @param requester   the metadata identifying the requesting operation
	 * @param context     the compute context for event processing
	 * @param event       the OpenCL event to synchronize on
	 * @param profile     optional consumer for profiling data, or {@code null}
	 * @param onProcessed callback invoked once after the event completes, or {@code null}
	 * @param processed   the shared processing state
	 */
	protected CLSemaphore(OperationMetadata requester, CLComputeContext context,
						  cl_event event, Consumer<RunData> profile,
						  Runnable onProcessed, AtomicBoolean processed) {
		this.requester = requester;
		this.context = context;
		this.event = event;
		this.profile = profile;
		this.onProcessed = onProcessed;
		this.processed = processed;
	}

	/**
	 * Returns the underlying OpenCL event. Prefer {@link #whileValid(Consumer)} for any
	 * use that must not race a concurrent {@link #waitFor()} releasing the event.
	 *
	 * @return the OpenCL event
	 */
	public cl_event getEvent() { return event; }

	/** Returns the metadata identifying the operation that requested this semaphore. */
	@Override
	public OperationMetadata getRequester() { return requester; }

	/**
	 * Invokes {@code action} with the underlying event while holding this semaphore's
	 * processing lock, or with {@code null} when the event has already been processed
	 * (and released) by {@link #waitFor()} — in which case the operation this semaphore
	 * guards is complete and no ordering constraint remains. This lets a dependent
	 * enqueue place the event in its wait-list without racing a concurrent waiter.
	 *
	 * @param action the action to run with the still-valid event, or with {@code null}
	 */
	public void whileValid(Consumer<cl_event> action) {
		synchronized (processed) {
			action.accept(processed.get() ? null : event);
		}
	}

	/**
	 * Blocks until the OpenCL event completes, releasing the event afterward. Idempotent:
	 * only the first wait processes the event; subsequent (or concurrent) waits return
	 * once processing has finished. If a profile consumer was provided, it receives
	 * profiling data after completion.
	 */
	@Override
	public void waitFor() {
		synchronized (processed) {
			if (processed.get()) return;

			context.processEvent(event, profile);
			processed.set(true);

			if (onProcessed != null) {
				onProcessed.run();
			}
		}
	}

	/**
	 * Creates a new semaphore with the same event (and shared processing state) but
	 * different requester metadata.
	 *
	 * @param requester the new requester metadata
	 * @return a new CLSemaphore with the updated requester
	 */
	@Override
	public Semaphore withRequester(OperationMetadata requester) {
		return new CLSemaphore(requester, context, event, profile, onProcessed, processed);
	}
}
