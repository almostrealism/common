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

package org.almostrealism.hardware.cuda;

import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.streams.Semaphore;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

/**
 * Submits work to the {@link CUStream} of one {@link CudaComputeContext} without blocking the
 * submitting thread: the CUDA counterpart of
 * {@link org.almostrealism.hardware.metal.MetalCommandRunner}.
 *
 * <p>Each submission returns a {@link Semaphore} (a {@link CudaSemaphore}). Work is enqueued on the stream as soon as
 * nothing it depends on is outstanding, and an event recorded behind it marks its completion. A
 * dedicated completion thread waits for those events in launch order and runs each submission's
 * completion callback before settling its semaphore, so callbacks run in submission order and
 * only once the GPU has finished with the memory they release.</p>
 *
 * <h2>Dependencies</h2>
 *
 * <p>The stream executes in submission order, so a dependency on an earlier submission to this
 * same runner is already honored and costs nothing. Any other dependency (a <em>foreign</em> one:
 * another backend's completion, a host-side latch, a merge of several completions) is bridged on
 * the host without blocking the caller: the submission is held, with every submission after it,
 * in a queue whose head is released from {@link Semaphore#CALLBACK_EXECUTOR} once the foreign
 * dependency has completed. Holding the later submissions keeps submission order, which a
 * submission that depends on a held one relies on, exactly as the GPU-side wait of a Metal bridge
 * holds every command buffer committed after it. Work already on the stream is never held back,
 * so a foreign dependency that itself waits for earlier work on this runner still completes.</p>
 *
 * <p>When a foreign dependency fails, the submission it guards is not launched: its semaphore
 * reports the failure, its completion callback still runs, and the submissions behind it proceed.
 * A failure is not propagated through same-runner dependencies, as it is not on Metal.</p>
 *
 * <h2>Threads</h2>
 *
 * <p>The completion thread runs completion callbacks, so a callback must not wait for a later
 * submission to this runner; {@link CudaSemaphore#waitFor()} rejects being called there rather
 * than deadlock. Launches happen on the submitting thread, or on the callback thread that
 * released a foreign dependency, while holding this runner's monitor, which serializes them so
 * that work from different threads is never interleaved on the stream. Nothing done while
 * holding the monitor waits for the GPU or for a dependency.</p>
 */
public class CudaStreamRunner implements ConsoleFeatures {
	/** The stream all work is submitted to. */
	private final CUStream stream;

	/**
	 * Submissions not yet launched, in submission order. Non-empty only while its head waits for
	 * a foreign dependency. Guarded by this runner's monitor.
	 */
	private final Deque<Submission> held = new ArrayDeque<>();

	/** Launched or failed submissions whose completion has not yet been observed, in order. */
	private final BlockingQueue<Completion> completions = new LinkedBlockingQueue<>();

	/** The thread that observes completions and runs completion callbacks. */
	private final Thread completionThread;

	/** Whether {@link #destroy()} has started. Guarded by this runner's monitor. */
	private boolean destroyed;

	/**
	 * Creates a runner for the given stream, which it takes ownership of, and starts its
	 * completion thread.
	 *
	 * @param stream the stream to submit work to
	 */
	public CudaStreamRunner(CUStream stream) {
		this.stream = stream;
		this.completionThread = new Thread(this::observeCompletions, "CUDA stream completions");
		this.completionThread.setDaemon(true);
		this.completionThread.start();
	}

	/** Returns the stream work is submitted to. */
	public CUStream getStream() { return stream; }

	/**
	 * Submits {@code command} to run against the stream once {@code dependsOn} has completed,
	 * without waiting for either on the calling thread.
	 *
	 * <p>If the work can be launched immediately and the command (or recording its completion)
	 * fails, the stream is drained so no work that references the submission's memory is still
	 * pending, {@code onComplete} runs, and the failure is thrown from here. A submission held
	 * behind a foreign dependency cannot report to the caller, so the same failure is reported by
	 * its semaphore instead, after the same drain and callback.</p>
	 *
	 * <p>A submission refused because the runner has been destroyed never launches, but its
	 * {@code onComplete} still runs before the refusal is thrown, so a resource the caller handed
	 * to the callback (such as a memory reservation) is released rather than leaked.</p>
	 *
	 * @param requester  the operation submitting the work, or {@code null}
	 * @param command    enqueues the work on the stream
	 * @param dependsOn  work that must complete first, or {@code null}
	 * @param onComplete run once the work has completed or failed, or {@code null}; must not wait
	 *                   for a later submission to this runner
	 * @return the submission's completion
	 * @throws IllegalStateException if the runner has been destroyed
	 */
	public Semaphore submit(OperationMetadata requester, Consumer<CUStream> command,
							Semaphore dependsOn, Runnable onComplete) {
		CudaSemaphore completion = new CudaSemaphore(requester, this);
		boolean sameRunner = dependsOn instanceof CudaSemaphore &&
				((CudaSemaphore) dependsOn).getRunner() == this;
		Submission submission = new Submission(completion, command,
				sameRunner ? null : dependsOn, onComplete);

		synchronized (this) {
			if (destroyed) {
				throw submission.refuse();
			}

			if (submission.dependsOn == null && held.isEmpty()) {
				launchOrThrow(submission);
				return completion;
			}

			held.add(submission);
			if (held.size() == 1) awaitDependency(submission);
		}

		return completion;
	}

	/**
	 * Launches a submission on the submitting thread. On failure the stream has been drained by
	 * {@link #launch}, so the callback runs and the semaphore settles here before the failure is
	 * rethrown; a failure of the callback is attached to it as suppressed rather than replacing it.
	 * Must hold this runner's monitor.
	 *
	 * @param submission the submission to launch
	 */
	private void launchOrThrow(Submission submission) {
		CUEvent event;

		try {
			event = launch(submission.command);
		} catch (RuntimeException | Error e) {
			try {
				if (submission.onComplete != null) submission.onComplete.run();
			} catch (RuntimeException | Error callbackFailure) {
				e.addSuppressed(callbackFailure);
			} finally {
				submission.completion.fail(e);
				submission.completion.countDown();
			}

			throw e;
		}

		completions.add(new Completion(submission.completion, event, submission.onComplete));
	}

	/**
	 * Enqueues the command and records an event behind it. If either fails, work the command
	 * did enqueue may still reference the submission's memory, so the stream is drained before
	 * the failure is rethrown; a drain failure is attached to it as suppressed. Must hold this
	 * runner's monitor.
	 *
	 * @param command enqueues the work on the stream
	 * @return the event marking the work's completion
	 */
	private CUEvent launch(Consumer<CUStream> command) {
		try {
			command.accept(stream);
			return stream.recordEvent();
		} catch (RuntimeException | Error e) {
			try {
				stream.synchronize();
			} catch (RuntimeException | Error drainFailure) {
				e.addSuppressed(drainFailure);
			}

			throw e;
		}
	}

	/**
	 * Releases the given held submission from a callback thread once its foreign dependency has
	 * completed or failed. Must hold this runner's monitor.
	 *
	 * @param head the submission at the head of {@link #held}
	 */
	private void awaitDependency(Submission head) {
		Semaphore.CALLBACK_EXECUTOR.execute(() -> {
			Throwable failure = null;

			try {
				head.dependsOn.waitFor();
			} catch (RuntimeException | Error e) {
				failure = e;
			}

			release(head, failure);
		});
	}

	/**
	 * Launches the head of {@link #held} now that its foreign dependency has settled, followed by
	 * every submission behind it up to the next one with a foreign dependency of its own, which
	 * becomes the new head. Does nothing if {@code head} was abandoned by {@link #destroy()}.
	 *
	 * @param head    the submission whose dependency has settled
	 * @param failure the dependency's failure, or {@code null} if it completed
	 */
	private synchronized void release(Submission head, Throwable failure) {
		if (held.peek() != head) return;

		held.poll();
		start(head, failure);

		while (!held.isEmpty()) {
			Submission next = held.peek();

			if (next.dependsOn != null) {
				awaitDependency(next);
				return;
			}

			held.poll();
			start(next, null);
		}
	}

	/**
	 * Launches a released submission, or records why it cannot be, handing it to the completion
	 * thread either way so its callback runs in order. Must hold this runner's monitor.
	 *
	 * @param submission the submission to start
	 * @param failure    the failure of its dependency, or {@code null}
	 */
	private void start(Submission submission, Throwable failure) {
		CUEvent event = null;

		if (failure == null) {
			try {
				event = launch(submission.command);
			} catch (RuntimeException | Error e) {
				failure = e;
			}
		}

		if (failure != null) submission.completion.fail(failure);
		completions.add(new Completion(submission.completion, event, submission.onComplete));
	}

	/**
	 * Body of the completion thread: settles completions in order until {@link #destroy()}
	 * hands it {@link Completion#STOP}. An interrupt still settles every completion already
	 * queued before the thread exits, so no caller is left waiting on a submission that will
	 * never be observed.
	 */
	private void observeCompletions() {
		while (true) {
			Completion next;

			try {
				next = completions.take();
			} catch (InterruptedException e) {
				warn("Completion thread interrupted with " + completions.size() + " completions pending");
				settlePending();
				Thread.currentThread().interrupt();
				return;
			}

			if (next == Completion.STOP) return;
			next.settle();
		}
	}

	/**
	 * Settles every completion still queued, so a thread interrupt does not leave callers of
	 * {@link CudaSemaphore#waitFor()} waiting on a submission the completion thread will never
	 * observe. {@link Completion#STOP} is skipped rather than settled, as it has no semaphore.
	 */
	private void settlePending() {
		Completion next;

		while ((next = completions.poll()) != null) {
			if (next != Completion.STOP) next.settle();
		}
	}

	/**
	 * Throws if the calling thread is this runner's completion thread, where waiting for a
	 * submission would wait for the thread itself.
	 *
	 * @throws IllegalStateException if called from the completion thread
	 */
	public void requireOffCompletionThread() {
		if (Thread.currentThread() == completionThread) {
			throw new IllegalStateException("A completion callback cannot wait for work on its own CUDA stream");
		}
	}

	/**
	 * Waits for all submitted work, runs every outstanding completion callback, stops the
	 * completion thread and releases the stream. Submissions still held behind a foreign
	 * dependency are not launched: their semaphores report that the runner was destroyed. Later
	 * submissions are refused. Destroying a runner twice does nothing.
	 *
	 * @throws IllegalStateException if called from the completion thread
	 */
	public void destroy() {
		requireOffCompletionThread();

		synchronized (this) {
			if (destroyed) return;
			destroyed = true;

			for (Submission s : held) {
				start(s, new IllegalStateException("The CUDA stream runner was destroyed " +
						"before the submission's dependency completed"));
			}

			held.clear();
			completions.add(Completion.STOP);
		}

		joinCompletionThread();

		stream.synchronize();
		stream.release();
	}

	/** Waits for the completion thread to finish, restoring any interrupt received meanwhile. */
	private void joinCompletionThread() {
		boolean interrupted = false;

		while (completionThread.isAlive()) {
			try {
				completionThread.join();
			} catch (InterruptedException e) {
				interrupted = true;
			}
		}

		if (interrupted) Thread.currentThread().interrupt();
	}

	@Override
	public Console console() { return Hardware.console; }

	/** A submission that has not been launched yet. */
	private static final class Submission {
		/** The submission's completion. */
		private final CudaSemaphore completion;
		/** Enqueues the work on the stream. */
		private final Consumer<CUStream> command;
		/** The foreign dependency to wait for first, or {@code null}. */
		private final Semaphore dependsOn;
		/** The completion callback, or {@code null}. */
		private final Runnable onComplete;

		/**
		 * Creates a submission.
		 *
		 * @param completion the submission's completion
		 * @param command    enqueues the work on the stream
		 * @param dependsOn  the foreign dependency to wait for first, or {@code null}
		 * @param onComplete the completion callback, or {@code null}
		 */
		private Submission(CudaSemaphore completion, Consumer<CUStream> command,
						   Semaphore dependsOn, Runnable onComplete) {
			this.completion = completion;
			this.command = command;
			this.dependsOn = dependsOn;
			this.onComplete = onComplete;
		}

		/**
		 * Refuses this submission because the runner has been destroyed: runs its completion
		 * callback, so whatever the callback releases is not leaked, and fails its semaphore. A
		 * failure of the callback is attached to the refusal as suppressed.
		 *
		 * @return the refusal to throw to the submitting thread
		 */
		private IllegalStateException refuse() {
			IllegalStateException refusal =
					new IllegalStateException("The CUDA stream runner has been destroyed");

			try {
				if (onComplete != null) onComplete.run();
			} catch (RuntimeException | Error e) {
				refusal.addSuppressed(e);
			} finally {
				completion.fail(refusal);
				completion.countDown();
			}

			return refusal;
		}
	}

	/** A launched (or failed) submission whose completion the completion thread will observe. */
	private static final class Completion {
		/** Tells the completion thread to stop. */
		private static final Completion STOP = new Completion(null, null, null);

		/** The submission's completion. */
		private final CudaSemaphore completion;
		/** The event recorded behind the work, or {@code null} if it was never launched. */
		private final CUEvent event;
		/** The completion callback, or {@code null}. */
		private final Runnable onComplete;

		/**
		 * Creates a completion record.
		 *
		 * @param completion the submission's completion
		 * @param event      the event recorded behind the work, or {@code null}
		 * @param onComplete the completion callback, or {@code null}
		 */
		private Completion(CudaSemaphore completion, CUEvent event, Runnable onComplete) {
			this.completion = completion;
			this.event = event;
			this.onComplete = onComplete;
		}

		/**
		 * Waits for the work to finish on the GPU, releases the event, runs the callback and
		 * settles the semaphore. A failure at any step is reported by the semaphore and does
		 * not prevent the later steps. The first failure is the one reported; a later failure is
		 * attached to it as suppressed rather than replacing it, so the operation that actually
		 * failed is not hidden by cleanup or by the callback. The event is released exactly once,
		 * whether or not waiting for it failed.
		 */
		private void settle() {
			Throwable failure = null;

			if (event != null) {
				try {
					event.synchronize();
				} catch (RuntimeException | Error e) {
					failure = e;
				}

				try {
					event.release();
				} catch (RuntimeException | Error e) {
					if (failure != null) failure.addSuppressed(e);
					else failure = e;
				}
			}

			try {
				if (onComplete != null) onComplete.run();
			} catch (RuntimeException | Error e) {
				if (failure != null) failure.addSuppressed(e);
				else failure = e;
			} finally {
				completion.fail(failure);
				completion.countDown();
			}
		}
	}
}
