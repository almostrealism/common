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

package org.almostrealism.studio;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.lifecycle.Destroyable;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.io.ConsoleFeatures;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Renders pattern audio <em>ahead</em> of playback on a dedicated producer thread so the
 * real-time mixdown never has to wait on a render.
 *
 * <p>The real-time pipeline is staged: note generation produces the notes, pattern rendering
 * turns those notes into audio, and the mixdown consumes the rendered audio per buffer. This
 * class owns the pattern-rendering stage. The defining property is that the mixdown hot path
 * contains <em>only</em> the mixdown: it must never trigger a render. To guarantee that, this
 * class renders successive buffers ahead of playback on a producer thread, depositing each
 * rendered buffer into a bounded ring. The mixdown consumer only ever takes an already-rendered
 * slot, mixes it, and releases it.</p>
 *
 * <h2>Why a separate thread is correct and fast</h2>
 * <p>The Metal backend shares one {@code MetalComputeContext} across threads and serializes
 * all GPU encoding through a single command runner, so driving rendering from the producer
 * thread while the mixdown runs on the consumer thread is safe (see
 * {@code org.almostrealism.hardware.metal.MetalDataContext}). The bulk of a render's cost is
 * Java orchestration — note gathering and producer-graph construction — which executes in
 * parallel with the consumer's GPU mixdown; only the comparatively small render kernels
 * interleave with the mixdown dispatch through the shared runner. The hot path therefore sees
 * nothing but the mixdown.</p>
 *
 * <h2>Ring discipline</h2>
 * <p>The ring holds {@code slots} buffers, each one buffer's worth of consolidated mixdown
 * input ({@code [inputChannels, bufferSize]}). A classic two-semaphore bounded buffer governs
 * it: the producer waits on {@code empty} before rendering into the next slot and signals
 * {@code filled} when done; the consumer waits on {@code filled}, mixes the slot, and signals
 * {@code empty} on release. The producer never runs more than {@code slots} buffers ahead
 * (back-pressure); the consumer blocks only if the producer falls behind (under-run).</p>
 *
 * <p>The producer advances its own {@code renderFrame} (the render-ahead position in frames),
 * which is distinct from the consumer's playback clock. The render depends only on the frame
 * parameter, so the two positions are free to diverge by up to {@code slots} buffers.</p>
 */
public class PatternRenderStream implements Destroyable, CollectionFeatures, ConsoleFeatures {

	/** How long {@link #stop()} waits for the producer before warning that a render is still in flight. */
	public static final long STOP_WARNING_MILLIS = 2000;

	/** Renders the working input for the current {@link #renderFrame}; run repeatedly by the producer. */
	private final Runnable renderOp;

	/** Render-ahead position in frames; set by the producer before each render to drive the render cells. */
	private final long[] renderFrame;

	/** The {@code [inputChannels, bufferSize]} view the render fills; copied into a ring slot per buffer. */
	private final PackedCollection workingInput;

	/** Backing storage for the ring: {@code slots} contiguous {@link #sliceSize}-element slots. */
	private final PackedCollection ring;

	/**
	 * Compiled per-slot {@link org.almostrealism.hardware.computations.Assignment} copies of
	 * {@link #workingInput} into each ring slot, built lazily on the slot's first use. An
	 * assignment kernel keeps the copy on the compute device (ordered by the device against
	 * the render's own kernels), where a host-mediated copy would stall the producer until
	 * every pending render dispatch completed.
	 */
	private final Runnable[] slotCopies;

	/** Shape of one ring slot ({@code [inputChannels, bufferSize]}). */
	private final TraversalPolicy slotShape;

	/** Number of buffers the ring holds (render-ahead depth). */
	private final int slots;
	/** Elements per ring slot ({@code inputChannels * bufferSize}). */
	private final int sliceSize;
	/** Frames per buffer. */
	private final int bufferSize;

	/** Slots available for the producer to write (starts full at {@link #slots}). */
	private final Semaphore empty;
	/** Slots filled and available for the consumer to read (starts empty). */
	private final Semaphore filled;

	/** Total buffers rendered (producer-advanced); also the next write slot index. */
	private final AtomicLong writeIndex = new AtomicLong(0);
	/** Total buffers consumed (consumer-advanced); also the next read slot index. */
	private final AtomicLong readIndex = new AtomicLong(0);

	/** Whether the producer loop should keep running. */
	private volatile boolean running;
	/** The first render failure observed by the producer, surfaced to the consumer; {@code null} if none. */
	private volatile Throwable producerError;
	/** The producer thread, or {@code null} when stopped. */
	private Thread producer;

	/**
	 * Creates a render-ahead stream over a freshly allocated ring.
	 *
	 * @param renderOp      compiled render operation that fills {@code workingInput} for the
	 *                      current {@code renderFrame}; invoked once per buffer by the producer
	 * @param renderFrame   single-element frame cursor the producer advances; the render cells'
	 *                      frame supplier must read {@code renderFrame[0]}
	 * @param workingInput  the {@code [inputChannels, bufferSize]} region the render fills
	 * @param slots         number of buffers the ring holds (render-ahead depth)
	 * @param inputChannels number of consolidated input channels (rows) per buffer
	 * @param bufferSize    frames per buffer
	 */
	public PatternRenderStream(Runnable renderOp, long[] renderFrame, PackedCollection workingInput,
							   int slots, int inputChannels, int bufferSize) {
		this.renderOp = renderOp;
		this.renderFrame = renderFrame;
		this.workingInput = workingInput;
		this.slots = slots;
		this.bufferSize = bufferSize;
		this.sliceSize = inputChannels * bufferSize;
		this.slotShape = new TraversalPolicy(inputChannels, bufferSize);
		this.ring = new PackedCollection(slots * sliceSize);
		this.slotCopies = new Runnable[slots];
		this.empty = new Semaphore(slots);
		this.filled = new Semaphore(0);
	}

	/**
	 * Starts the producer thread and blocks until at least {@code prefill} buffers have been
	 * rendered, so the first ticks find their input ready. A render failure during prefill is
	 * rethrown to the caller.
	 *
	 * @param prefill number of buffers to render before returning (clamped to {@link #slots})
	 */
	public void start(int prefill) {
		int target = Math.min(prefill, slots);
		running = true;
		producerError = null;
		producer = new Thread(this::produceLoop, "pattern-render-ahead");
		producer.setDaemon(true);
		producer.start();

		while (running && writeIndex.get() < target) {
			if (producerError != null) {
				throw new HardwareException("Pattern render-ahead failed during prefill", new RuntimeException(producerError));
			}
			Thread.onSpinWait();
		}
		if (producerError != null) {
			throw new HardwareException("Pattern render-ahead failed during prefill", new RuntimeException(producerError));
		}
	}

	/**
	 * The producer loop: render the next buffer ahead into a free ring slot, blocking when the
	 * ring is full. Terminates on {@link #stop()} or on the first render failure.
	 */
	private void produceLoop() {
		try {
			while (running) {
				empty.acquire();
				if (!running) {
					break;
				}

				long w = writeIndex.get();
				renderFrame[0] = w * (long) bufferSize;
				renderOp.run();
				slotCopy((int) (w % slots)).run();

				writeIndex.incrementAndGet();
				filled.release();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (Throwable t) {
			producerError = t;
			running = false;
			// Wake any consumer waiting on a slot so it can observe the failure.
			filled.release();
		}
	}

	/**
	 * Returns the compiled assignment that copies {@link #workingInput} into the given ring
	 * slot, building it on the slot's first use — the ring depth is fixed, so each compiles
	 * exactly once.
	 *
	 * @param slot the ring slot index
	 * @return the compiled copy for that slot
	 */
	private Runnable slotCopy(int slot) {
		if (slotCopies[slot] == null) {
			PackedCollection dest = ring.range(slotShape, slot * sliceSize);
			slotCopies[slot] = a(traverseEach(cp(dest)), traverseEach(cp(workingInput))).get();
		}

		return slotCopies[slot];
	}

	/**
	 * Returns a view of the oldest filled ring slot, blocking until the producer has rendered
	 * it. The caller must mix the returned slot and then call {@link #release()}. The view stays
	 * valid until {@code release()} frees the slot for reuse.
	 *
	 * @return a {@code [inputChannels, bufferSize]} view of the next buffer's rendered input
	 */
	public PackedCollection awaitSlot() {
		try {
			filled.acquire();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new HardwareException("Interrupted awaiting rendered pattern buffer");
		}
		if (producerError != null) {
			throw new HardwareException("Pattern render-ahead failed", new RuntimeException(producerError));
		}
		return ring.range(slotShape, (int) (readIndex.get() % slots) * sliceSize);
	}

	/**
	 * Releases the slot returned by the most recent {@link #awaitSlot()}, freeing it for the
	 * producer to overwrite. Call only after the slot's data has been consumed (mixed).
	 */
	public void release() {
		readIndex.incrementAndGet();
		empty.release();
	}

	/** Returns the number of buffers rendered so far (producer progress). */
	public long buffersRendered() {
		return writeIndex.get();
	}

	/** Returns the number of buffers consumed so far (playback progress). */
	public long buffersConsumed() {
		return readIndex.get();
	}

	/**
	 * Stops the producer thread and resets the ring to empty so the stream can be restarted via
	 * {@link #start(int)}. The backing storage is retained.
	 *
	 * <p>This does not return until the producer thread has terminated. A render in progress
	 * when the stream is stopped still reads the render cells and writes {@link #ring}, so
	 * returning while it runs would let a caller release those resources (as
	 * {@link #destroy()} and the owning runner's teardown do immediately afterwards) underneath
	 * it. A render that outlasts {@link #STOP_WARNING_MILLIS} is reported and then awaited.</p>
	 *
	 * <p>Shutdown must be driven from a thread other than the producer. Calling this (or
	 * {@link #destroy()}) from the producer thread itself — for instance from {@code renderOp}
	 * or a callback it reaches — would make the producer wait for its own termination, which can
	 * never arrive while the callback is on the stack. Such a call is rejected with
	 * {@link IllegalStateException} rather than skipped, because skipping the wait would let
	 * {@code destroy()} release the ring while the render that triggered the call is still using
	 * it.</p>
	 *
	 * @throws IllegalStateException if invoked on the producer thread
	 */
	public void stop() {
		if (producer == Thread.currentThread()) {
			throw new IllegalStateException("stop() must not be called on the render-ahead producer thread");
		}

		running = false;
		if (producer != null) {
			producer.interrupt();
			awaitTermination(producer);
			producer = null;
		}
		writeIndex.set(0);
		readIndex.set(0);
		empty.drainPermits();
		empty.release(slots);
		filled.drainPermits();
	}

	/**
	 * Blocks until the given producer thread has terminated, warning once if it is still
	 * running after {@link #STOP_WARNING_MILLIS}. An interrupt of the calling thread does not
	 * cut the wait short, since the producer may still be using the stream's resources; the
	 * interrupt status is restored once the producer has exited. {@link #stop()} rejects a
	 * producer-thread shutdown before reaching here, so {@code thread} is never the caller.
	 *
	 * @param thread the producer thread to wait for
	 */
	private void awaitTermination(Thread thread) {
		boolean interrupted = false;
		boolean warned = false;

		while (thread.isAlive()) {
			try {
				thread.join(STOP_WARNING_MILLIS);
			} catch (InterruptedException e) {
				interrupted = true;
			}

			if (!warned && thread.isAlive()) {
				warn("Render-ahead producer still running " + STOP_WARNING_MILLIS
						+ "ms after stop; waiting for the in-flight render to finish");
				warned = true;
			}
		}

		if (interrupted) {
			Thread.currentThread().interrupt();
		}
	}

	@Override
	public void destroy() {
		stop();

		for (int i = 0; i < slotCopies.length; i++) {
			Destroyable.destroy(slotCopies[i]);
			slotCopies[i] = null;
		}

		if (ring != null) {
			ring.destroy();
		}
	}
}
