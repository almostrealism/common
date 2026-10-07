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
 *
 * <p>A semaphore may also stand for several dispatches of the same runner (see
 * {@link #merge(Semaphore)}), in which case it is the completion of the latest of them and
 * {@link #getPriorBufferValue()} records what else a dependent must wait for.</p>
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
	 * The highest event value signaled by a dispatch merged into this completion from an earlier
	 * command buffer than {@link #commandBuffer}, or {@code 0} when there is none.
	 */
	private final long priorBufferValue;

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
		this(requester, runner, commandBuffer, event, value, 0);
	}

	/**
	 * Creates a Metal completion semaphore that also covers dispatches from earlier command buffers.
	 *
	 * @param requester        metadata of the operation this completion belongs to, or {@code null}
	 * @param runner           the command runner that owns the command buffer
	 * @param commandBuffer    the command buffer the dispatch was encoded into
	 * @param event            the timeline event the dispatch signals
	 * @param value            the value the dispatch signals the event to
	 * @param priorBufferValue the highest value signaled by a covered dispatch from an earlier
	 *                         command buffer, or {@code 0} when there is none
	 */
	private MetalSemaphore(OperationMetadata requester, MetalCommandRunner runner,
						   MTLCommandBuffer commandBuffer, MTLEvent event, long value,
						   long priorBufferValue) {
		this.requester = requester;
		this.runner = runner;
		this.commandBuffer = commandBuffer;
		this.event = event;
		this.value = value;
		this.priorBufferValue = priorBufferValue;
	}

	/** Returns the command buffer the dispatch was encoded into. */
	public MTLCommandBuffer getCommandBuffer() { return commandBuffer; }

	/** Returns the timeline event the dispatch signals. */
	public MTLEvent getEvent() { return event; }

	/** Returns the value the dispatch signals the event to. */
	public long getValue() { return value; }

	/**
	 * Returns the highest event value signaled by a dispatch this completion covers from a command
	 * buffer earlier than {@link #getCommandBuffer()}, or {@code 0} when it covers none &mdash;
	 * always {@code 0} for the completion of a single dispatch. A dependent dispatch encoded into
	 * this completion's own command buffer is ordered after the dispatches of that buffer by the
	 * buffer itself, but must still wait on the event for this value (see
	 * {@link MetalCommandRunner#submit}).
	 *
	 * @return the value to wait for when chaining within this completion's command buffer, or
	 *         {@code 0}
	 */
	public long getPriorBufferValue() { return priorBufferValue; }

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

	/**
	 * Registers the callback with the command buffer's completion callbacks, exactly as
	 * {@link #whenComplete(Runnable)} does. A Metal command buffer runs those callbacks once
	 * it has completed whether or not it finished with an error status, so the same passive
	 * registration also satisfies the settlement contract — the callback runs on the failure
	 * path too — without the host-forced commit that the default {@link #waitFor()}-based
	 * implementation would impose per registration.
	 *
	 * @param r the callback to invoke once the buffer has settled
	 */
	@Override
	public void whenSettled(Runnable r) {
		runner.whenComplete(commandBuffer, r);
	}

	/**
	 * Merges with another completion from the same {@link MetalCommandRunner} into the
	 * completion of whichever dispatch was issued later, so that a dispatch depending on both
	 * chains on one completion of this runner instead of on a host-side composite. Completions
	 * from another runner, or from any other provider, are not merged.
	 *
	 * <p>A runner encodes its dispatches in order, each signaling the next value of the runner's
	 * timeline event after the work encoded before it in its command buffer, into command buffers
	 * committed to one serial queue. Waiting on the host for the later dispatch, or encoding a
	 * GPU wait for its value from another command buffer, therefore orders after the earlier one
	 * as well &mdash; the same ordering {@link MetalCommandRunner} relies on for any single
	 * dependency. The one ordering the later completion alone does not carry is that of an
	 * earlier dispatch from a different command buffer, for a dependent encoded into the later
	 * dispatch's own (still open) buffer, where the runner orders a single dependency by the
	 * buffer and encodes no wait. The merged completion records the value of such a dispatch as
	 * its {@link #getPriorBufferValue() prior buffer value}, for which the runner still encodes
	 * the wait.</p>
	 *
	 * @param other another completion
	 * @return the merged completion when both belong to this runner, otherwise {@code null}
	 */
	@Override
	public Semaphore merge(Semaphore other) {
		if (!(other instanceof MetalSemaphore)) return null;

		MetalSemaphore metal = (MetalSemaphore) other;
		if (metal.getRunner() != runner) return null;

		MetalSemaphore later = metal.getValue() > value ? metal : this;
		MetalSemaphore earlier = later == this ? metal : this;

		long prior = Math.max(later.priorBufferValue,
				earlier.commandBuffer == later.commandBuffer ? earlier.priorBufferValue : earlier.value);
		if (prior == later.priorBufferValue) return later;

		return new MetalSemaphore(later.requester, runner, later.commandBuffer, event, later.value, prior);
	}

	@Override
	public Semaphore withRequester(OperationMetadata requester) {
		return new MetalSemaphore(requester, runner, commandBuffer, event, value, priorBufferValue);
	}
}
