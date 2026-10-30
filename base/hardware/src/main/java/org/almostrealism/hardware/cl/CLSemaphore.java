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
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.profile.RunData;
import org.jocl.cl_event;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
 * <p><b>Failure.</b> A command that did not execute (its event reports a negative execution
 * status, or the wait for it fails) fails the first wait, and the failure is kept: every later
 * or concurrent wait, on this semaphore or a {@link #withRequester(OperationMetadata) copy} of it,
 * reports it as well rather than returning as though the command had executed. The event is
 * released, the semaphore is marked processed and the {@code onProcessed} callback runs on the
 * failure path too. A dependent command that waited on this event through its wait list is failed
 * by OpenCL itself, and reports the failure through its own semaphore.</p>
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
	 * The failure processing the event ended with, or {@code null} while it executed (or has not
	 * been processed). Shared across {@link #withRequester(OperationMetadata)} copies.
	 */
	private AtomicReference<Throwable> failure;

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
		this(requester, context, event, profile, null, new AtomicBoolean(false), new AtomicReference<>());
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
		this(requester, context, event, profile, onProcessed, new AtomicBoolean(false), new AtomicReference<>());
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
	 * @param failure     the shared failure processing ended with
	 */
	protected CLSemaphore(OperationMetadata requester, CLComputeContext context,
						  cl_event event, Consumer<RunData> profile,
						  Runnable onProcessed, AtomicBoolean processed,
						  AtomicReference<Throwable> failure) {
		this.requester = requester;
		this.context = context;
		this.event = event;
		this.profile = profile;
		this.onProcessed = onProcessed;
		this.processed = processed;
		this.failure = failure;
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
	 * <p>A command that has been processed and found not to have executed can no longer fail its
	 * dependents through their wait lists, as its event is gone; ordering after it with no
	 * constraint would run the dependent on inputs that were never produced. Its failure is thrown
	 * instead, and {@code action} is not run.</p>
	 *
	 * @param action the action to run with the still-valid event, or with {@code null}
	 * @throws HardwareException if this semaphore's command has been processed and did not execute
	 */
	public void whileValid(Consumer<cl_event> action) {
		synchronized (processed) {
			if (!processed.get()) {
				action.accept(event);
				return;
			}

			Throwable recorded = failure.get();
			if (recorded != null) {
				throw new HardwareException("A dependency of this OpenCL dispatch did not execute: " +
						recorded.getMessage(), recorded);
			}

			action.accept(null);
		}
	}

	/**
	 * Blocks until the OpenCL event completes, releasing the event afterward. Idempotent:
	 * only the first wait processes the event; subsequent (or concurrent) waits return
	 * once processing has finished, or report the failure it ended with. If a profile
	 * consumer was provided, it receives profiling data after completion.
	 *
	 * @throws HardwareException if the event's command did not execute (see the class
	 *                           documentation)
	 */
	@Override
	public void waitFor() {
		synchronized (processed) {
			if (!processed.get()) {
				try {
					process();
				} catch (RuntimeException | Error e) {
					failure.set(e);
				} finally {
					processed.set(true);
					if (onProcessed != null) runProcessed();
				}
			}

			Throwable recorded = failure.get();
			if (recorded != null) {
				throw new HardwareException(recorded.getMessage(), recorded);
			}
		}
	}

	/**
	 * Runs the {@code onProcessed} callback, recording its failure alongside (or, after a command
	 * that executed, as) the failure the semaphore reports.
	 */
	private void runProcessed() {
		try {
			onProcessed.run();
		} catch (RuntimeException | Error e) {
			Throwable recorded = failure.get();
			if (recorded != null) {
				recorded.addSuppressed(e);
			} else {
				failure.set(e);
			}
		}
	}

	/**
	 * Waits for the event, checks that its command executed, and releases it (see
	 * {@link CLComputeContext#processEvent(cl_event, Consumer)}). Runs at most once per
	 * event, under the processing lock. This is the single point at which the semaphore
	 * reaches the device, so a subclass can report a failure the device did not produce
	 * and exercise the failure path deterministically.
	 *
	 * @throws HardwareException if the event's command did not execute
	 */
	protected void process() {
		context.processEvent(event, profile);
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
		return new CLSemaphore(requester, context, event, profile, onProcessed, processed, failure);
	}
}
