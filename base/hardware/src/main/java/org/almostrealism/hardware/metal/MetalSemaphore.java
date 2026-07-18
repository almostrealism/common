/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.hardware.metal;

import io.almostrealism.concurrent.OperationSemaphore;
import io.almostrealism.streams.Semaphore;
import io.almostrealism.profile.OperationMetadata;

/**
 * {@link OperationSemaphore} backed by a Metal {@link MTLEvent} timeline value — the Metal analog of
 * {@link org.almostrealism.hardware.cl.CLSemaphore}. It is the single completion handle for one
 * dispatch: the dispatch was encoded into {@link #getCommandBuffer() a command buffer} and signals
 * the event to {@link #getValue() its value}.
 *
 * <p>{@link #waitFor()} ensures that command buffer is committed and completed (host completion).
 * A <em>dependent</em> dispatch instead orders itself after this one on the GPU by encoding a wait
 * for {@link #getValue()} on the {@link #getEvent() event} — no host stall (see
 * {@link MetalCommandRunner#submit}).</p>
 */
public class MetalSemaphore implements OperationSemaphore {
	/** Metadata identifying the operation this completion belongs to, or {@code null}. */
	private final OperationMetadata requester;
	/** The command runner that owns the command buffer. */
	private final MetalCommandRunner runner;
	/** The command buffer the dispatch was encoded into. */
	private final MTLCommandBuffer commandBuffer;
	/** The timeline event the dispatch signals. */
	private final MTLEvent event;
	/** The value the dispatch signals the event to. */
	private final long value;

	/**
	 * Creates a Metal completion semaphore.
	 *
	 * @param requester     metadata of the operation this completion belongs to, or {@code null}
	 * @param runner        the command runner that owns the command buffer
	 * @param commandBuffer the command buffer the dispatch was encoded into
	 * @param event         the timeline event the dispatch signals
	 * @param value         the value the dispatch signals the event to
	 */
	public MetalSemaphore(OperationMetadata requester, MetalCommandRunner runner,
						  MTLCommandBuffer commandBuffer, MTLEvent event, long value) {
		this.requester = requester;
		this.runner = runner;
		this.commandBuffer = commandBuffer;
		this.event = event;
		this.value = value;
	}

	/** Returns the command buffer the dispatch was encoded into. */
	public MTLCommandBuffer getCommandBuffer() { return commandBuffer; }

	/** Returns the timeline event the dispatch signals. */
	public MTLEvent getEvent() { return event; }

	/** Returns the value the dispatch signals the event to. */
	public long getValue() { return value; }

	/** Returns the command runner that owns the command buffer. */
	public MetalCommandRunner getRunner() { return runner; }

	@Override
	public OperationMetadata getRequester() { return requester; }

	/**
	 * Blocks until the dispatch's command buffer is committed and has completed on the GPU.
	 */
	@Override
	public void waitFor() {
		runner.complete(commandBuffer, requester);
	}

	/**
	 * Registers the callback with the command buffer's completion callbacks (see
	 * {@link MetalCommandRunner#whenComplete}) rather than waiting for it. The default
	 * implementation's waiting callback would invoke {@link #waitFor()}, which commits
	 * the buffer if it is still open — a host-forced commit per registration that
	 * defeats command-buffer batching. Registered this way, the callback runs when the
	 * buffer completes on its own schedule and imposes no commit.
	 */
	@Override
	public void whenComplete(Runnable r) {
		runner.whenComplete(commandBuffer, r);
	}

	@Override
	public Semaphore withRequester(OperationMetadata requester) {
		return new MetalSemaphore(requester, runner, commandBuffer, event, value);
	}
}
