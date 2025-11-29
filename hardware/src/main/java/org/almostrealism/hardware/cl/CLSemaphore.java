/*
 * Copyright 2024 Michael Murray
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

import io.almostrealism.concurrent.Semaphore;
import io.almostrealism.profile.OperationMetadata;
import org.almostrealism.hardware.profile.RunData;
import org.jocl.cl_event;

import java.util.function.Consumer;

/**
 * {@link Semaphore} implementation backed by OpenCL {@link cl_event}.
 *
 * <p>Enables synchronization between OpenCL operations by wrapping {@link cl_event}
 * and waiting for event completion with optional profiling.</p>
 *
 * <h2>Basic Usage</h2>
 *
 * <pre>{@code
 * cl_event event = new cl_event();
 * CL.clEnqueueNDRangeKernel(..., event);
 *
 * CLSemaphore sem = new CLSemaphore(metadata, context, event, profile);
 *
 * // Wait for kernel completion
 * sem.waitFor();  // Calls processEvent(event, profile)
 * }</pre>
 *
 * @see CLComputeContext#processEvent(cl_event, Consumer)
 * @see Semaphore
 */
public class CLSemaphore implements Semaphore {
	/** Metadata identifying the operation that requested this semaphore. */
	private OperationMetadata requester;

	/** The compute context for processing events. */
	private CLComputeContext context;

	/** The OpenCL event to wait for. */
	private cl_event event;

	/** Optional consumer for profiling data when the event completes. */
	private Consumer<RunData> profile;

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
		this.requester = requester;
		this.context = context;
		this.event = event;
		this.profile = profile;
	}

	/**
	 * Returns the underlying OpenCL event.
	 *
	 * @return the OpenCL event
	 */
	public cl_event getEvent() { return event; }

	/** Returns the metadata identifying the operation that requested this semaphore. */
	@Override
	public OperationMetadata getRequester() { return requester; }

	/**
	 * Blocks until the OpenCL event completes.
	 * If a profile consumer was provided, it receives profiling data after completion.
	 */
	@Override
	public void waitFor() { context.processEvent(event, profile); }

	/**
	 * Creates a new semaphore with the same event but different requester metadata.
	 *
	 * @param requester the new requester metadata
	 * @return a new CLSemaphore with the updated requester
	 */
	@Override
	public Semaphore withRequester(OperationMetadata requester) {
		return new CLSemaphore(requester, context, event, profile);
	}
}
