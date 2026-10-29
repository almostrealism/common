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
import org.almostrealism.hardware.ctx.FailureRecord;
import org.almostrealism.hardware.ctx.SubmissionStream;
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
 * <p>The runner is a {@link SubmissionStream}, the coordination it shares with every other
 * backend: the stream supplies the submission ordinal (a submission's
 * {@link CudaSemaphore#getSequence() sequence}), same-runner ordering and merging, the
 * off-completion-thread wait guard, admission versus destruction, the hold bound and the
 * {@link FailureRecord}. The runner supplies only what is native to CUDA: launching on the
 * {@link CUStream}, recording and synchronizing {@link CUEvent}s.</p>
 *
 * <p>The stream executes in submission order, so a dependency on an earlier submission to this
 * same runner is already honored and costs nothing. Any other dependency (a <em>foreign</em> one:
 * another backend's completion, a host-side latch, a merge of several completions) is bridged on
 * the host without blocking the caller and without parking a thread: the submission is held, with
 * every submission after it, in a queue whose head is released by a callback registered with the
 * dependency's {@link Semaphore#onSettled(Runnable)} once it has settled. Holding the later
 * submissions keeps submission order, which a submission that depends on a held one relies on,
 * exactly as the GPU-side wait of a Metal bridge holds every command buffer committed after it.
 * Work already on the stream is never held back, so a foreign dependency that itself waits for
 * earlier work on this runner still completes. At most {@link #MAX_HELD} submissions are held; a
 * submission that would be held beyond that waits, on its submitting thread, for room. The one
 * exception is a submission made from a completion callback, which is held beyond the bound
 * instead: room is made by a dependency settling, which may itself wait for the completion thread
 * to observe earlier work.</p>
 *
 * <h2>Failures</h2>
 *
 * <p>A submission that fails &mdash; its foreign dependency failed, its launch failed, or waiting
 * for its completion event reported an error &mdash; is recorded in the stream's
 * {@link FailureRecord} at its sequence, and every submission at that sequence or later reports a
 * failure: its own if it has one, and otherwise the recorded one. A submission covered by the
 * record when it would launch is not launched at all; its completion callback still runs in order.
 * This is the rule Metal follows for its timeline values, and the same coarse rule: a same-runner
 * dependency is dropped at submission (see {@link #ordersAfter}), so the runner cannot tell a
 * submission that depended on the failed one from independent work that merely follows it, and
 * reports both as failed. The record is never cleared (see {@link FailureRecord}): once a
 * submission has failed, the runner is poisoned for the rest of its life. A completion callback
 * failure alone, when the work itself ran, is reported by that submission only.</p>
 *
 * <h2>Threads</h2>
 *
 * <p>The completion thread runs completion callbacks, so a callback must not wait for a later
 * submission to this runner; {@link CudaSemaphore#waitFor()} rejects such a wait there rather
 * than deadlock, while still allowing a wait for an earlier submission that has already settled.
 * Launches happen on the submitting thread, or on the callback thread that released a foreign
 * dependency, while holding this runner's monitor, which serializes them so that work from
 * different threads is never interleaved on the stream. Nothing done while holding the monitor
 * waits for a dependency, and a successful launch does not wait for the GPU. A failed launch is
 * the one exception: the stream is drained while the monitor is still held, so that no work
 * referencing the failed submission's memory is pending when its callback releases it.</p>
 */
public class CudaStreamRunner extends SubmissionStream<CudaSemaphore> implements ConsoleFeatures {
	/**
	 * Maximum submissions that may be held behind a foreign dependency before a further submission
	 * that would be held waits, on its submitting thread, for room. A resource bound, not a
	 * behavioural switch: it caps the memory held submissions and their reservations pin.
	 */
	public static final int MAX_HELD = 4096;

	/** The stream all work is submitted to. */
	private final CUStream stream;

	/**
	 * Submissions not yet launched, in submission order. Non-empty only while its head waits for
	 * a foreign dependency. Each one is counted as held in this stream's hold bound. Guarded by this
	 * runner's monitor.
	 */
	private final Deque<Submission> held = new ArrayDeque<>();

	/** Launched or failed submissions whose completion has not yet been observed, in order. */
	private final BlockingQueue<Completion> completions = new LinkedBlockingQueue<>();

	/** The thread that observes completions and runs completion callbacks. */
	private final Thread completionThread;

	/** Held by {@link #destroy()} while it waits for the device and releases the stream. */
	private final Object teardown = new Object();

	/** Whether the stream has been released. Guarded by {@link #teardown}. */
	private boolean tornDown;

	/**
	 * Creates a runner for the given stream, which it takes ownership of, and starts its
	 * completion thread.
	 *
	 * <p>The runner's ordinals are the sequence numbers of its submissions, which settle in that
	 * order (see {@link CudaSemaphore#merge}); it stops accepting work once {@link #destroy()} has
	 * started or the completion thread was interrupted. Its admission lock is always taken before
	 * this runner's monitor.</p>
	 *
	 * @param stream the stream to submit work to
	 */
	public CudaStreamRunner(CUStream stream) {
		super("CUDA stream runner", CudaSemaphore.class, MAX_HELD,
				"A completion callback cannot wait for work on its own CUDA stream");
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
	 * <p>Submissions settle in submission order, failed ones included: each submission's
	 * {@code onComplete} runs, and its semaphore settles, only after those of every earlier
	 * submission to this runner.</p>
	 *
	 * <p>If the work can be launched immediately and the command (or recording its completion)
	 * fails, the stream is drained so no work that references the submission's memory is still
	 * pending, and the failure is thrown from here once the submission has settled in its turn,
	 * so {@code onComplete} has run by then. That waits for the earlier submissions to complete
	 * first, but only on this failure path. The exception is a submission made from a completion
	 * callback, which cannot wait for its own turn on the completion thread: its failure is
	 * thrown at once and its {@code onComplete} runs afterwards, still in order. A submission held
	 * behind a foreign dependency cannot report to the caller, so the same failure is reported by
	 * its semaphore instead, after the same drain and callback. A submission made after an earlier
	 * one has failed is not launched and does not throw: its semaphore reports the recorded failure
	 * (see the class documentation).</p>
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
		return submit(requester, command, dependsOn, null, onComplete);
	}

	/**
	 * Submits {@code command} as {@link #submit(OperationMetadata, Consumer, Semaphore, Runnable)}
	 * does, and runs {@code onHeld} if the submission is held instead of being launched
	 * immediately.
	 *
	 * <p>A held submission can wait for as long as the foreign dependency at the head of the
	 * queue does, which can be longer than protection the caller took for the duration of a
	 * kernel lasts (such as a {@code KernelMemoryGuard} execution reservation, which is subject
	 * to a deferred-release backstop). {@code onHeld} lets the caller strengthen that protection
	 * only when it is needed, so a submission that launches immediately pays nothing for it.
	 * It runs on the submitting thread, before this method returns and before the submission
	 * can launch. If it throws, the submission is rejected exactly as a refused one is: it never
	 * launches, {@code onComplete} runs, and the failure is thrown from here.</p>
	 *
	 * @param requester  the operation submitting the work, or {@code null}
	 * @param command    enqueues the work on the stream
	 * @param dependsOn  work that must complete first, or {@code null}
	 * @param onHeld     run if the submission is held rather than launched immediately, or {@code null}
	 * @param onComplete run once the work has completed or failed, or {@code null}; must not wait
	 *                   for a later submission to this runner
	 * @return the submission's completion
	 * @throws IllegalStateException if the runner has been destroyed
	 */
	public Semaphore submit(OperationMetadata requester, Consumer<CUStream> command,
							Semaphore dependsOn, Runnable onHeld, Runnable onComplete) {
		Semaphore foreign = ordersAfter(dependsOn) ? null : dependsOn;
		Submission admitted;

		while (true) {
			admitted = admit(() -> admit(requester, command, foreign, onHeld, onComplete),
					refusal -> {
						throw new Submission(new CudaSemaphore(requester, this, nextOrdinal()),
								command, foreign, onComplete).reject(refusal);
					});

			if (admitted != null) break;
			awaitHoldCapacityUninterruptibly();
		}

		CudaSemaphore completion = admitted.completion;
		Throwable launchFailure = admitted.launchFailure;
		if (launchFailure == null) return completion;

		if (Thread.currentThread() != completionThread) {
			try {
				completion.waitFor();
			} catch (RuntimeException | Error settled) {
				// The same failure, now with any callback failure attached; thrown below
			}
		}

		if (launchFailure instanceof Error) throw (Error) launchFailure;
		throw (RuntimeException) launchFailure;
	}

	/**
	 * Admits one submission under this stream's admission lock: launches it at once
	 * when nothing it depends on is outstanding, settles it unlaunched when an earlier submission
	 * has failed, and otherwise holds it. A submission that would be held while {@link #MAX_HELD}
	 * already are is not admitted, and its caller waits for room before trying again, unless it is
	 * made from a completion callback: the completion thread must not wait for room, since the
	 * dependency that frees it may need that thread to observe an earlier completion, so such a
	 * submission is held beyond the bound.
	 *
	 * @param requester  the operation submitting the work, or {@code null}
	 * @param command    enqueues the work on the stream
	 * @param foreign    the foreign dependency, or {@code null}
	 * @param onHeld     the caller's hook for a held submission, or {@code null}
	 * @param onComplete run once the work has completed or failed, or {@code null}
	 * @return the admitted submission, with its immediate launch failure if any, or {@code null}
	 *         if it must wait for room to be held
	 */
	private synchronized Submission admit(OperationMetadata requester, Consumer<CUStream> command,
										  Semaphore foreign, Runnable onHeld, Runnable onComplete) {
		boolean immediate = foreign == null && held.isEmpty();
		if (!immediate && !hasHoldCapacity() && !isStreamThread()) return null;

		CudaSemaphore completion = new CudaSemaphore(requester, this, nextOrdinal());
		Submission submission = new Submission(completion, command, foreign, onComplete);
		Throwable poisoned = completion.getRecordedFailure();

		// A submission that must fail anyway waits for nothing, unless earlier ones are held
		if (poisoned != null && held.isEmpty()) {
			start(submission, poisoned);
		} else if (!immediate) {
			hold(submission, onHeld);
		} else {
			submission.launchFailure = start(submission, null);
		}

		return submission;
	}

	/**
	 * Holds a submission that cannot launch yet, behind its own foreign dependency or behind
	 * earlier held work, running the caller's {@code onHeld} first. If {@code onHeld} fails, the
	 * submission is rejected and the failure thrown. Must hold this runner's monitor.
	 *
	 * @param submission the submission to hold
	 * @param onHeld     the caller's hook for a held submission, or {@code null}
	 */
	private void hold(Submission submission, Runnable onHeld) {
		if (onHeld != null) {
			try {
				onHeld.run();
			} catch (RuntimeException e) {
				throw submission.reject(e);
			} catch (Error e) {
				throw submission.reject(e);
			}
		}

		held.add(submission);
		hold();
		if (held.size() == 1) awaitDependency(submission);
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
	 * Releases the given held submission once its foreign dependency has completed or failed, from
	 * a callback registered with the dependency's {@link Semaphore#onSettled(Runnable)}. The runner
	 * parks no thread of its own for the wait: a dependency that is told when its work settles
	 * holds no thread at all, and one that is not is observed the way its own {@code onSettled}
	 * observes it. The callback's {@link Semaphore#waitFor()} returns or throws at once, as the
	 * dependency has settled. Must hold this runner's monitor.
	 *
	 * @param head the submission at the head of {@link #held}
	 */
	private void awaitDependency(Submission head) {
		Semaphore dependency = head.dependsOn;

		dependency.onSettled(() -> {
			Throwable failure = null;

			try {
				dependency.waitFor();
			} catch (RuntimeException | Error e) {
				failure = e;
			}

			release(head, failure);
		});
	}

	/**
	 * Launches the head of {@link #held} now that its foreign dependency has settled, followed by
	 * every submission behind it up to the next one with a foreign dependency of its own, which
	 * becomes the new head. Does nothing if {@code head} was abandoned when the runner stopped
	 * accepting work.
	 *
	 * <p>A failed dependency is recorded at the head's sequence before anything is started, so the
	 * head and every submission held behind it settle with a failure and none of them is launched
	 * (see the class documentation).</p>
	 *
	 * @param head    the submission whose dependency has settled
	 * @param failure the dependency's failure, or {@code null} if it completed
	 */
	private synchronized void release(Submission head, Throwable failure) {
		if (held.peek() != head) return;

		if (failure != null) {
			head.completion.recordFailure(failure);
		}

		pollHeld();
		start(head, failure == null ? head.completion.getRecordedFailure() : failure);

		while (!held.isEmpty()) {
			Submission next = held.peek();
			Throwable poisoned = next.completion.getRecordedFailure();

			// A submission that must fail anyway is not kept waiting for its own dependency
			if (next.dependsOn != null && poisoned == null) {
				awaitDependency(next);
				return;
			}

			pollHeld();
			start(next, poisoned);
		}
	}

	/** Removes the head of {@link #held}, releasing its place in the hold bound. Must hold this runner's monitor. */
	private void pollHeld() {
		held.poll();
		release();
	}

	/**
	 * Launches a submission, or records why it cannot be, handing it to the completion thread
	 * either way so its callback runs in order. Must hold this runner's monitor.
	 *
	 * <p>A launch failure is recorded at the submission's sequence, so every later submission
	 * reports it too (see the class documentation).</p>
	 *
	 * @param submission the submission to start
	 * @param failure    why it must not launch (its dependency failed, an earlier submission failed,
	 *                   or the runner was destroyed), or {@code null}
	 * @return the failure the submission settles with: {@code failure}, the launch failure, or
	 *         {@code null} if it launched
	 */
	private Throwable start(Submission submission, Throwable failure) {
		CUEvent event = null;

		if (failure == null) {
			try {
				event = launch(submission.command);
			} catch (RuntimeException | Error e) {
				failure = e;
				submission.completion.recordFailure(e);
			}
		}

		completions.add(new Completion(submission.completion, event, submission.onComplete, failure));
		return failure;
	}

	/**
	 * Body of the completion thread: settles completions in order until {@link #destroy()}
	 * hands it {@link Completion#STOP}.
	 *
	 * <p>An interrupt stops the thread, and with it the only consumer of completions, so the
	 * runner first stops accepting work exactly as {@link #destroy()} does: later submissions are
	 * refused, and held ones are abandoned. Once no further completion can be queued, every one
	 * already queued is settled before the thread exits, so no caller is left waiting on a
	 * submission that will never be observed. {@link #destroy()} is still needed afterwards to
	 * release the stream.</p>
	 */
	private void observeCompletions() {
		while (true) {
			Completion next;

			try {
				next = completions.take();
			} catch (InterruptedException e) {
				warn("Completion thread interrupted with " + completions.size() + " completions pending");
				stopAccepting();

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
	 * Returns whether the calling thread is this runner's completion thread, which runs
	 * completion callbacks and so cannot wait for a submission still to be observed.
	 *
	 * @return true when called from the completion thread
	 */
	@Override
	protected boolean isStreamThread() {
		return Thread.currentThread() == completionThread;
	}

	/**
	 * Waits for all submitted work, runs every outstanding completion callback, stops the
	 * completion thread and releases the stream. Submissions still held behind a foreign
	 * dependency are not launched: their semaphores report that the runner was destroyed. Later
	 * submissions are refused.
	 *
	 * <p>The teardown happens once. A caller that arrives while another is still tearing down
	 * waits for it to finish, and a caller that arrives afterwards returns at once, so every
	 * caller returns only once the stream has been released. The stream is released even if
	 * waiting for it fails, in which case the failure is thrown to the caller that performed the
	 * teardown.</p>
	 *
	 * @throws IllegalStateException if called from the completion thread
	 */
	public void destroy() {
		requireOffStreamThread();
		stopAccepting();

		synchronized (teardown) {
			if (tornDown) return;
			tornDown = true;

			joinCompletionThread();

			try {
				stream.synchronize();
			} finally {
				stream.release();
			}
		}
	}

	/**
	 * Stops accepting work, if the runner has not already: this stream is closed, so later
	 * submissions are refused, every held submission is abandoned (reporting that the runner was
	 * destroyed, after its callback runs in order; a callback its dependency's
	 * {@link Semaphore#onSettled} later runs finds it gone and does nothing), and the completion
	 * thread is told to stop once it has settled everything queued before it. Must not hold this
	 * runner's monitor, which is taken after the admission lock.
	 */
	private void stopAccepting() {
		if (!close()) return;

		synchronized (this) {
			while (!held.isEmpty()) {
				Submission s = held.peek();
				pollHeld();
				start(s, new IllegalStateException("The CUDA stream runner was destroyed " +
						"before the submission's dependency completed"));
			}

			completions.add(Completion.STOP);
		}
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
		/** The failure of an immediate launch, set when the submission is admitted, or {@code null}. */
		private Throwable launchFailure;

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
		 * Rejects this submission before it was queued or launched (because the runner has been
		 * destroyed, or because the caller's {@code onHeld} failed): runs its completion callback,
		 * so whatever the callback releases is not leaked, and fails its semaphore. A failure of
		 * the callback is attached to the rejection as suppressed.
		 *
		 * @param rejection why the submission is rejected
		 * @param <T>       the type of the rejection
		 * @return the rejection, to throw to the submitting thread
		 */
		private <T extends Throwable> T reject(T rejection) {
			try {
				if (onComplete != null) onComplete.run();
			} catch (RuntimeException | Error e) {
				rejection.addSuppressed(e);
			} finally {
				completion.fail(rejection);
				completion.countDown();
			}

			return rejection;
		}
	}

	/** A launched (or failed) submission whose completion the completion thread will observe. */
	private static final class Completion {
		/** Tells the completion thread to stop. */
		private static final Completion STOP = new Completion(null, null, null, null);

		/** The submission's completion. */
		private final CudaSemaphore completion;
		/** The event recorded behind the work, or {@code null} if it was never launched. */
		private final CUEvent event;
		/** The completion callback, or {@code null}. */
		private final Runnable onComplete;
		/** The failure the submission settles with before its callback runs, or {@code null}. */
		private final Throwable failure;

		/**
		 * Creates a completion record.
		 *
		 * @param completion the submission's completion
		 * @param event      the event recorded behind the work, or {@code null}
		 * @param onComplete the completion callback, or {@code null}
		 * @param failure    the failure the submission already carries (a failed foreign dependency
		 *                   or a failed deferred launch), or {@code null}; reported as the primary
		 *                   failure so a later callback failure is suppressed onto it rather than lost
		 */
		private Completion(CudaSemaphore completion, CUEvent event, Runnable onComplete, Throwable failure) {
			this.completion = completion;
			this.event = event;
			this.onComplete = onComplete;
			this.failure = failure;
		}

		/**
		 * Waits for the work to finish on the GPU, releases the event, runs the callback and
		 * settles the semaphore. A failure at any step is reported by the semaphore and does
		 * not prevent the later steps. The first failure is the one reported; a later failure is
		 * attached to it as suppressed rather than replacing it, so the operation that actually
		 * failed is not hidden by cleanup or by the callback. The event is released exactly once,
		 * whether or not waiting for it failed.
		 *
		 * <p>A failure of the wait for the event is recorded at the submission's sequence before the
		 * callback runs, and work that ran without a failure of its own still reports a failure the
		 * record holds at or before its sequence: the completion thread settles in sequence order, so
		 * a failure of submission {@code K} reaches every completion of {@code K} or later.</p>
		 */
		private void settle() {
			Throwable failure = this.failure;

			if (event != null) {
				try {
					event.synchronize();
				} catch (RuntimeException | Error e) {
					failure = e;
					completion.recordFailure(e);
				}

				try {
					event.release();
				} catch (RuntimeException | Error e) {
					if (failure != null) failure.addSuppressed(e);
					else failure = e;
				}
			}

			if (failure == null) failure = completion.getRecordedFailure();

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
