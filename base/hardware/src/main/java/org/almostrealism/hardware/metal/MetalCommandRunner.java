/*
 * Copyright 2025 Michael Murray
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

import io.almostrealism.concurrent.ConfinedExecutor;
import io.almostrealism.concurrent.HandOffWindow;
import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.streams.Semaphore;
import io.almostrealism.profile.OperationMetadata;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.ctx.FailureRecord;
import org.almostrealism.hardware.ctx.SubmissionStream;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.DistributionMetric;
import org.almostrealism.io.SystemUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns the Metal command-buffer lifecycle for a {@link MetalComputeContext}.
 *
 * <p>Dispatches are <em>encoded</em> into an open command buffer (not committed per dispatch) on a
 * single-threaded executor, so independent dispatches accumulate into one command buffer. Each
 * dispatch returns a {@link MetalSemaphore} — the operation's single completion handle — carrying
 * the next value of a {@link MTLEvent} timeline. Ordering between dependent dispatches is expressed
 * at the GPU level: when a dispatch is submitted with a {@link MetalSemaphore} dependency of this
 * runner, nothing is committed. A dependency in the still-open buffer is ordered by in-buffer hazard
 * tracking and costs no wait (only a merged completion's
 * {@link MetalSemaphore#getPriorBufferValue() prior buffer value}, if any, is waited for); a
 * dependency in an earlier, committed buffer is honored by encoding a wait for its event value, so
 * the GPU serializes the dependent after the dependency across buffers without a host stall. Only a
 * foreign dependency closes the open buffer first (see {@link #submit}).</p>
 *
 * <h2>Closed and committed buffers</h2>
 *
 * <p>A buffer stops accepting dispatches when it is <em>closed</em> (a host wait, the
 * {@link #MAX_OPEN} bound, or a bridged dispatch that must start a fresh buffer), and is
 * <em>committed</em> to the GPU only once every host-signaled bridge encoded into it has been
 * signaled, in the order the buffers were closed. The GPU therefore never holds a buffer that waits
 * for the host: a caller may submit dispatches ahead of the foreign work they depend on, and the
 * buffers wait on the host side, where no watchdog applies, rather than on the GPU, where a buffer
 * that waits longer than the GPU timeout is killed and its dispatches never run. Up to
 * {@link #MAX_HELD} buffers wait in this way; beyond that, a dispatch that would open another
 * waits on its submitting thread for the foreign work (see {@link #encode}), so foreign work that
 * the same thread would only start later must be started before that bound is reached.</p>
 *
 * <h2>Timeline signals</h2>
 *
 * <p>A command buffer signals the timeline once, encoded when it is closed: the signal carries the value
 * of the last dispatch encoded into it and fires when all of the buffer's work has completed (see
 * {@link #commitOpenOnExecutor()}). The timeline is only ever waited on from a later command buffer,
 * for a dispatch in a buffer that has already been committed, so that one signal releases every
 * such wait — later than a signal after the dispatch itself would, by the rest of that buffer's
 * work. Signaling after every dispatch instead would end each dispatch with a command-buffer-level
 * signal command, which costs the GPU tens of microseconds per dispatch and, for a kernel as small as
 * most kernels of a model's forward pass, costs far more than the kernel itself.</p>
 *
 * <p>{@link MetalSemaphore#waitFor()} is the only thing that blocks the host: it commits the
 * dispatch's buffer if still open and waits for it (and every buffer committed before it, since the
 * queue is serial) to complete.</p>
 *
 * <h2>Failures</h2>
 *
 * <p>A command buffer that Metal reports as failed (a GPU fault, or the watchdog killing a
 * stalled buffer) ran none of its dispatches, wrote none of their destinations and never
 * signaled its timeline value. The failure is recorded in the {@link FailureRecord} of the
 * runner's {@link SubmissionStream} (the model every backend shares), keyed by the timeline
 * value of the first dispatch in the failed buffer, and every wait for a dispatch at or after
 * that value throws it as a {@link HardwareException} &mdash; a waiter that drained the buffer, a
 * concurrent one, and one that arrives after the buffer was drained alike. The record is sticky:
 * a later buffer that encoded a GPU wait for one of the failed values can still complete without
 * an error of its own (the shared timeline is released by any higher value an independent
 * buffer signals), so its own status cannot be trusted and only the record can report it. A
 * failure of the foreign work a bridged dispatch waits for is recorded the same way, for the
 * bridged dispatch's value (see {@link #submit}). A recorded failure is never cleared (see the
 * bound and reset policy of {@link FailureRecord}): the runner, and the context that owns it, is
 * poisoned for the rest of its life.</p>
 *
 * <h2>Memory lifetime</h2>
 *
 * <p>Memory referenced by an encoded kernel must stay alive until its command buffer has completed.
 * Callers pass an {@code onCommit} callback to {@link #submit} that releases that memory; the runner
 * runs it only after the buffer the dispatch was encoded into has completed.</p>
 *
 * <p>Objective-C memory: every executor task runs inside its own autorelease pool (see
 * {@link MTLAutoreleasePool#run}) so transient autoreleased Metal objects (encoders, the command
 * buffer's own autorelease reference) are drained per task and do not accumulate in the driver. The
 * open command buffer outlives its creating task, so it is retained explicitly when created
 * ({@link MTL#commandBuffer(long)}) and released ({@link MTLCommandBuffer#release()}) once it has
 * completed.</p>
 */
public class MetalCommandRunner extends SubmissionStream<MetalSemaphore> implements ConsoleFeatures {
	/** Maximum number of kernel arguments a single command may bind. */
	public static final int MAX_ARGS = 512;

	/**
	 * Maximum dispatches encoded into one open command buffer before it is committed. This is a
	 * memory bound (it caps how much encoded-but-uncompleted work and how large an autorelease pool
	 * accumulate), not a behavioural switch — correctness does not depend on its value.
	 */
	public static final int MAX_OPEN = 256;

	/**
	 * Maximum closed buffers that may wait for host-signaled bridges before a further dispatch
	 * that would open a new buffer waits, on its submitting thread, for the oldest of them to be
	 * committed. A command queue allows a bounded number of command buffers that have not
	 * completed (64 by default), and opening one beyond it blocks; this keeps the buffers waiting
	 * for the host well within that bound, so the executor can always open the buffer a dispatch
	 * needs. Like {@link #MAX_OPEN}, a resource bound, not a behavioural switch.
	 */
	public static final int MAX_HELD = 16;

	/** Total dispatches encoded across all runners since the last reset. */
	public static final AtomicLong totalDispatchCount = new AtomicLong();
	/** Total command-buffer commits across all runners since the last reset. */
	public static final AtomicLong totalCommitCount = new AtomicLong();

	/**
	 * Returns the mean number of dispatches per committed command buffer (the effective GPU batch
	 * size) since the last {@link #resetBatchSizeCounters()}, or {@code 0} when nothing has committed.
	 *
	 * @return mean dispatches per commit
	 */
	public static double meanBatchSize() {
		long commits = totalCommitCount.get();
		return commits == 0 ? 0 : totalDispatchCount.get() / (double) commits;
	}

	/** Resets the batch-size counters; behaviour-neutral. */
	public static void resetBatchSizeCounters() {
		totalDispatchCount.set(0);
		totalCommitCount.set(0);
	}

	/**
	 * Distribution of commit-forcing host waits across the operations that requested them, keyed by
	 * the requester's {@link OperationMetadata#getDisplayName() display name}. Only waits that
	 * actually forced a commit are recorded (a wait for an already-committed buffer costs nothing
	 * attributable), so this distribution identifies which operations are responsible for breaking
	 * command-buffer batching.
	 */
	public static DistributionMetric hostCompleteRequesters =
			Hardware.console.distribution("mtlHostCompleteRequesters");

	/**
	 * Bridges a foreign dependency (a {@link Semaphore} not produced by this runner) by encoding
	 * a GPU wait on a fresh {@link MTLEvent} that is signaled from the host when the foreign work
	 * completes, so {@link #submit} never blocks on foreign completions. When disabled, a foreign
	 * dependency is bridged the old way: a blocking wait before the dispatch is encoded.
	 */
	public static boolean enableHostSignaledBridges = true;

	/**
	 * How long, in microseconds, the runner's thread and a thread waiting on it poll for each other
	 * before parking (the {@link HandOffWindow} of {@link #executor}). Every dispatch is encoded on
	 * the runner's thread while the dispatching thread waits, and a workload of small kernels issues
	 * dispatches back to back, each encoded in a few microseconds &mdash; less than the scheduler
	 * takes to wake a parked thread. Polling across that gap lets consecutive dispatches pass between
	 * the threads without either being descheduled; a longer pause still parks both. Zero disables
	 * polling. Read when a runner is created. Controlled by {@code AR_HARDWARE_METAL_HANDOFF_SPIN}.
	 */
	public static int handOffSpinMicros = SystemUtils.getInt("AR_HARDWARE_METAL_HANDOFF_SPIN").orElse(50);

	/**
	 * Confines all command-buffer operations to one thread, running each inside its own
	 * autorelease pool ({@link MTLAutoreleasePool#run}), so transient autoreleased Metal objects
	 * (compute command encoders, and the command buffer's own autorelease reference) are drained
	 * when the task ends rather than accumulating in the driver until it stalls. The open command
	 * buffer survives across tasks because it is retained explicitly when created (see
	 * {@link MTL#commandBuffer(long)}) and released by {@link #drainOldestCommitted} once it has
	 * completed. A pool scoped to each task (rather than one spanning a buffer's whole open
	 * lifetime across several tasks) is required: spanning a pool across the executor's task
	 * boundaries wedged command-buffer completion.
	 */
	private final ConfinedExecutor executor = new ConfinedExecutor(MTLAutoreleasePool::run,
			new HandOffWindow(handOffSpinMicros, TimeUnit.MICROSECONDS));


	/** The command queue used to submit encoded Metal compute commands. */
	private final MTLCommandQueue queue;

	/** Timeline event used to order dependent dispatches across command buffers on the GPU. */
	private final MTLEvent event;

	/** The open (encoded, not yet committed) command buffer, or {@code null}. Executor-thread only. */
	private MTLCommandBuffer openBuffer;
	/** Total command-buffer commits. Written on the executor thread; volatile for diagnostic reads. */
	private volatile long commitCount;
	/** Commits forced by a host-side wait ({@link #complete}). Executor-thread written; volatile reads. */
	private volatile long hostCompleteCommits;
	/** Commits forced by the open buffer reaching {@link #MAX_OPEN} dispatches. Executor-thread written. */
	private volatile long maxOpenCommits;
	/** Commits performed while destroying the runner. Executor-thread written; volatile reads. */
	private volatile long destroyCommits;
	/** Commits forced so a bridged dispatch starts a fresh buffer. Executor-thread written. */
	private volatile long bridgeCommits;

	/**
	 * The host-signaled bridges encoded into the open buffer that have not been signaled yet.
	 * Replaced when the buffer is closed, so each closed buffer keeps the count of its own bridges.
	 * Executor-thread only.
	 */
	private PendingBridges openBridges = new PendingBridges();

	/**
	 * Foreign-completion signals that arrived after their bridge event was already
	 * released (the bridged buffer finished by an error or teardown path first).
	 * Written from completion-callback threads.
	 */
	private final AtomicLong lateBridgeSignals = new AtomicLong();

	/**
	 * Committed buffers that finished with the Metal error status, meaning their
	 * dispatches never executed and their destinations were never written.
	 * Written on the executor thread; read from diagnostic threads.
	 */
	private final AtomicLong errorCompletions = new AtomicLong();

	/** Timeline value of the first dispatch encoded into the open buffer. Executor-thread only. */
	private long openFirstValue;
	/** Number of dispatches encoded into the open buffer. Executor-thread only. */
	private int openCount;
	/** Released-memory callbacks for dispatches in the open buffer. Executor-thread only. */
	private List<Runnable> openOnComplete = new ArrayList<>();

	/**
	 * Closed-but-not-yet-completed buffers, in the order they were closed, which is also the order
	 * they are committed in: every buffer already committed comes before every buffer still waiting
	 * for its bridges. Executor-thread only.
	 */
	private final List<CommittedBuffer> committed = new ArrayList<>();

	/**
	 * The closed buffers not yet committed because they, or a buffer closed before them, still
	 * wait for a host-signaled bridge, oldest first: the tail of {@link #committed}. No buffer is
	 * opened while {@link #MAX_HELD} of them wait (see {@link #encode}), so there are at most one
	 * more than that: the buffer that was open when the bound was reached, once it is closed.
	 * Every buffer added here is counted as held in this stream's hold bound, and released from it when
	 * it is committed. Executor-thread only.
	 */
	private final ArrayDeque<CommittedBuffer> held = new ArrayDeque<>();

	/**
	 * True while an open (created but not yet closed) command buffer exists. Enforces the
	 * one-open-buffer-per-{@link MetalComputeContext} invariant: a single context must never have a
	 * second command buffer accepting dispatches (cross-buffer coordination is only meaningful
	 * <em>between</em> contexts, and a Computation tree compiles against a single Metal context).
	 * Tracked explicitly so an attempt to open a second buffer fails fast instead of silently
	 * producing the kind of wedged-completion state this work has repeatedly hit.
	 */
	private boolean bufferOpen;

	/**
	 * Creates a Metal command runner.
	 *
	 * <p>The runner is the {@link SubmissionStream} of its queue: the timeline values of its
	 * dispatches are the stream's ordinals, the stream's {@link FailureRecord} holds the runner's
	 * failure, the stream's admission lock is held while a dispatch is encoded and by
	 * {@link #destroy()} while it decides the runner is idle and stops the executor (so no
	 * dispatch can be encoded between those two steps), and the stream's hold bound is
	 * {@link #MAX_HELD} closed buffers.</p>
	 *
	 * @param queue The {@link MTLCommandQueue} for submitting commands
	 */
	public MetalCommandRunner(MTLCommandQueue queue) {
		super("Metal command runner", MetalSemaphore.class, MAX_HELD, ConfinedExecutor.CONFINED_THREAD_WAIT);
		this.queue = queue;
		this.event = queue.getDevice().newSharedEvent();
	}

	/**
	 * Encodes a dispatch into the open command buffer and returns its completion semaphore.
	 *
	 * <p>When {@code dependsOn} is a {@link MetalSemaphore} from this runner, ordering costs
	 * nothing or almost nothing: a dependency still in the open command buffer is already
	 * ordered ahead of this dispatch by Metal's hazard tracking (every buffer is allocated
	 * with default tracking; see {@code MTL.cpp}), so it is simply dropped; a dependency in
	 * an earlier, committed buffer is honored by encoding a GPU wait for its event value.
	 * A dependency that {@link MetalSemaphore#merge(Semaphore) merges} several dispatches is
	 * honored the same way for the latest of them, plus a GPU wait for its
	 * {@link MetalSemaphore#getPriorBufferValue() prior buffer value} when the latest is still
	 * in the open buffer. Neither case blocks the host or forces a commit, so chaining
	 * completion semaphores through a sequence of dispatches preserves batching.</p>
	 *
	 * <p>A foreign dependency (any other {@link Semaphore}) is bridged without blocking when
	 * {@link #enableHostSignaledBridges} is set: the dispatch's buffer encodes a GPU wait on a
	 * fresh per-bridge {@link MTLEvent}, and the event is signaled from the host when the
	 * foreign work settles, via {@link Semaphore#onSettled(Runnable)}. The dispatch is
	 * therefore ordered after the foreign work with no host wait. The buffer holding it is not
	 * committed until the signal has been given (see the class documentation), so the GPU never
	 * waits for the host, however far ahead of the foreign work dispatches are submitted. Once
	 * {@link #MAX_HELD} closed buffers wait in this way, a dispatch that would open another
	 * waits, on the calling thread, for the oldest of them to be committed. If the
	 * foreign work never settles, the buffer is never committed, and a wait for it waits for the
	 * foreign work &mdash; the same exposure a blocking bridge has. With bridges disabled, the
	 * foreign dependency is waited on the calling thread before the dispatch is encoded.</p>
	 *
	 * <p>Neither that wait nor the wait for the dispatch to be encoded is ended by an interrupt
	 * of the calling thread, whose interrupt status is restored before this method returns: a
	 * dispatch encoded before its dependency completed would read stale memory, and a call that
	 * returned before encoding would have no completion to return.</p>
	 *
	 * <p><b>Ordering contract:</b> a dispatch is ordered after exactly what {@code dependsOn}
	 * names, and is encoded before this method returns, so a dispatch submitted after another
	 * {@code submit} has returned is encoded after it. Concurrent calls (neither having
	 * returned before the other began) are not ordered with respect to each other: they are
	 * encoded in whatever order they are admitted, and a call waiting for an unbridged foreign
	 * dependency admits others meanwhile. A dispatch that must follow another names that
	 * one's completion as its {@code dependsOn}.</p>
	 *
	 * <p><b>Bridge event lifecycle:</b> the per-bridge event is released with its buffer's
	 * completion callbacks. On the success path the buffer's encoded wait guarantees the host
	 * signal already ran before the buffer could complete. A buffer that finishes by another
	 * path — a GPU error or watchdog kill, or a teardown completion — releases the event while
	 * the foreign completion callback is still pending, so the callback signals through
	 * {@link MTLEvent#signal(long)}, which skips a released event instead of touching freed
	 * native state; such skipped signals are counted by {@link #getLateBridgeSignalCount()}.
	 * A skipped signal has no observer to lose: the event's only encoded wait was in the
	 * buffer whose completion released it.</p>
	 *
	 * <p><b>Foreign failure:</b> the bridge is signaled when the foreign work settles, whether
	 * it completed or failed, so a failed dependency never leaves the GPU waiting for a watchdog
	 * kill. The bridged dispatch then runs on inputs that were never produced, so the foreign
	 * failure is first recorded on this runner for the bridged dispatch's value (see the class
	 * documentation): a wait for that dispatch, or any later one, throws it.</p>
	 *
	 * @param requester  metadata of the operation the dispatch belongs to, or {@code null}; carried by
	 *                   the returned semaphore so a later commit-forcing wait can be attributed to it
	 * @param command   encodes the kernel into the supplied command buffer
	 * @param dependsOn  the completion this dispatch depends on (a {@link MetalSemaphore} of this
	 *                   runner, or any foreign {@link Semaphore}), or {@code null}
	 * @param onComplete released-memory callback to run after this dispatch's buffer completes, or null
	 * @return this dispatch's completion semaphore
	 */
	public MetalSemaphore submit(OperationMetadata requester, MetalCommand command,
								 Semaphore dependsOn, Runnable onComplete) {
		List<MetalSemaphore> result = new ArrayList<>(1);

		requireOffStreamThread();

		// An unbridged foreign dependency is waited here, on the caller's thread, never on the
		// executor's: settling it may need this runner (a composite over this runner's own
		// semaphores completes each of them through complete(), which runs on the executor).
		// An interrupt must not end the wait early, or the dispatch would be encoded before
		// the work it is ordered after has completed
		Semaphore order = dependsOn;
		if (order != null && !enableHostSignaledBridges && !ordersAfter(order)) {
			order.waitForUninterruptibly();
			order = null;
		}

		Semaphore ordered = order;

		while (true) {
			boolean blocked = admit(() -> encode(requester, command, ordered, onComplete, result),
					refusal -> { throw refusal; });

			if (!blocked) return result.get(0);
			awaitHoldCapacityUninterruptibly();
		}
	}

	/**
	 * Returns whether the calling thread is the executor's confined thread, which encodes and
	 * commits every command buffer and so cannot wait for a dispatch of this runner.
	 *
	 * @return true when called from the executor's thread
	 */
	@Override
	protected boolean isStreamThread() {
		return executor.isConfinedThread();
	}

	/**
	 * Encodes one dispatch on the executor's thread for {@link #submit}, adding its completion
	 * semaphore to {@code result}, unless the dispatch would open a new command buffer while
	 * {@link #MAX_HELD} closed buffers already wait for a host-signaled bridge. A dispatch opens a
	 * buffer when it is bridged (it closes the open buffer and starts another) or when no buffer
	 * is open (the last one was closed at {@link #MAX_OPEN} or by a host wait). Every buffer closed
	 * behind one that waits for its bridge waits as well, whatever closed it, and a command queue
	 * allows only a bounded number of command buffers that have not completed: opening one beyond
	 * that blocks the executor's thread until one completes, and a buffer waiting for its bridges
	 * never completes while the executor that would commit it is blocked. The dispatch is then not
	 * encoded, and the caller waits, on its own thread, for room in this stream's hold bound
	 * (which the commit of the oldest waiting buffer makes) before trying again. A dispatch that
	 * joins the still-open buffer is never held back, so work the foreign dependency itself
	 * encodes there can always proceed.
	 *
	 * @param requester  metadata of the operation the dispatch belongs to, or {@code null}
	 * @param command    encodes the kernel into the supplied command buffer
	 * @param dependsOn  a prior {@link Semaphore} this dispatch depends on, or {@code null}
	 * @param onComplete released-memory callback to run after this dispatch's buffer completes, or null
	 * @param result     receives the dispatch's completion semaphore
	 * @return true if the dispatch was not encoded because the hold bound was reached, false once
	 *         it is encoded
	 */
	private boolean encode(OperationMetadata requester, MetalCommand command,
						   Semaphore dependsOn, Runnable onComplete, List<MetalSemaphore> result) {
		AtomicBoolean blocked = new AtomicBoolean();

		// Uninterruptible: submit returns the completion this task adds to result
		executor.runUninterruptibly(() -> {
			MetalSemaphore dependency = own(dependsOn);
			Semaphore foreign = dependsOn != null && dependency == null ? dependsOn : null;

			// A bridged dispatch always opens a fresh buffer (see below); any other opens one
			// when the previous buffer was closed
			boolean opensBuffer = foreign != null || openBuffer == null;
			if (opensBuffer && !hasHoldCapacity()) {
				blocked.set(true);
				return;
			}

			// The timeline value to wait for, or 0; see the submit javadoc for the open-buffer case
			long waitValue = 0;
			if (dependency != null) {
				waitValue = dependency.getCommandBuffer() == openBuffer ?
						dependency.getPriorBufferValue() : dependency.getValue();
			}

			// A bridged dispatch must start a fresh buffer: the foreign completion may itself
			// require waiting for dispatches already encoded here (a composite completion over
			// this runner's own semaphores resolves by completing their whole buffer), and a
			// buffer that contains both those dispatches and the bridge wait can never complete.
			// The GPU watchdog then kills the stalled buffer and the gated work silently never
			// runs. Closing first makes the cycle impossible.
			if (foreign != null && openCount > 0 && commitOpenOnExecutor()) {
				bridgeCommits++;
			}

			ensureOpenBuffer();

			if (waitValue > 0) {
				openBuffer.encodeWaitForEvent(event, waitValue);
			}

			if (foreign != null) {
				// Each bridge uses its own event; sharing one would let an out-of-order
				// foreign completion release another bridge's wait (see the bridging
				// lifecycle in this method's javadoc).
				MTLEvent bridge = queue.getDevice().newSharedEvent();
				openBuffer.encodeWaitForEvent(bridge, 1);
				openOnComplete.add(bridge::release);

				PendingBridges pending = openBridges;
				pending.add();

				// The value this dispatch takes below
				long bridgedValue = lastOrdinal() + 1;
				foreign.onSettled(() -> {
					// Recorded before the signal, so no waiter of the bridged dispatch can
					// observe its completion without the failure
					recordForeignFailure(foreign, bridgedValue);

					if (!bridge.signal(1)) {
						lateBridgeSignals.incrementAndGet();
					}

					executor.runOrElseUninterruptibly(() -> {
						pending.signaled();
						commitReadyOnExecutor();
					}, () -> { });
				});
			}

			command.encode(openBuffer);

			// The buffer signals this value when it is committed (see commitOpenOnExecutor)
			long value = nextOrdinal();
			openCount++;
			totalDispatchCount.incrementAndGet();
			if (onComplete != null) openOnComplete.add(onComplete);

			result.add(new MetalSemaphore(requester, this, openBuffer, event, value));

			if (openCount >= MAX_OPEN && commitOpenOnExecutor()) {
				maxOpenCommits++;
			}
		});

		return blocked.get();
	}

	/**
	 * Commits (if still open) the buffer the given dispatch was encoded into and blocks until it,
	 * and every buffer committed before it, has completed; then runs their released-memory
	 * callbacks. When the wait forces a commit, the commit is attributed to the dispatch's
	 * {@link MetalSemaphore#getRequester() requester} in {@link #hostCompleteRequesters} so
	 * batching-breaking waits can be traced to the operations responsible for them. Invoked by
	 * {@link MetalSemaphore#waitFor()}.
	 *
	 * <p>The wait for the GPU happens on the calling thread, never on the executor's thread. A
	 * committed buffer can be held on the GPU by a host-signaled bridge (see {@link #submit}),
	 * and the host work that signals the bridge may itself need this runner — to wait for one of
	 * this runner's own dispatches, as a composite completion over them does, or to encode more
	 * work. If the single executor thread were blocked waiting for that buffer, every such task
	 * would queue behind it, the bridge would never be signaled, and the buffer would stall until
	 * the GPU watchdog killed it. Only committing (before the wait) and draining an
	 * already-completed buffer (after it) run on the executor's thread, so it is never occupied
	 * while the GPU is still working.</p>
	 *
	 * <p>An interrupted caller abandons the wait, as {@link ConfinedExecutor#run} does. The
	 * waiter registration may still be queued at that point, so its withdrawal is queued behind
	 * it rather than skipped, and the buffer is released once some later wait drains it. If the
	 * runner is destroyed before the withdrawal can be queued, the withdrawal runs on the calling
	 * thread only after the executor has run every queued task, so the registration it withdraws
	 * has been made by then.</p>
	 *
	 * <p>A buffer that is closed but still waits for a host-signaled bridge is not on the GPU yet,
	 * so the caller first waits, on its own thread, until the bridge has been signaled and the
	 * buffer committed (see the class documentation); an interrupt during that wait abandons it
	 * as well.</p>
	 *
	 * <p>{@link #destroy()} may run while a caller is still waiting here. Its final task drains
	 * every committed buffer, including the one being waited for, but leaves it retained for the
	 * waiter; the executor is then gone, so the waiter withdraws itself on its own thread (see
	 * {@link ConfinedExecutor#runOrElse}) and the buffer is released there. The caller returns
	 * normally.</p>
	 *
	 * <p><b>Failure.</b> A wait for a dispatch at or after the value of a recorded failure (see
	 * the class documentation) throws it, before waiting when it is already recorded, and
	 * otherwise once the buffers have been drained, which is what records one. The caller waits
	 * for every committed buffer up to its own in queue order and stops at the first that
	 * failed: a buffer that encoded a GPU wait for a failed buffer's value would otherwise stall
	 * until the watchdog killed it too, and only then would the earlier failure be seen. An
	 * interrupted caller returns without throwing; the failure stays recorded for the next
	 * waiter.</p>
	 *
	 * @param dispatch the completion of a dispatch this runner encoded (see {@link #submit})
	 * @throws HardwareException if the dispatch, or one it may depend on, did not execute
	 */
	public void complete(MetalSemaphore dispatch) {
		long value = dispatch.getValue();
		OperationMetadata requester = dispatch.getRequester();
		getFailures().require(value);

		AtomicReference<List<Waiter>> registered = new AtomicReference<>();
		executor.run(() -> registered.set(commitForWait(dispatch.getCommandBuffer(), requester)));

		if (Thread.currentThread().isInterrupted()) {
			Runnable withdrawal = () -> {
				List<Waiter> abandoned = registered.get();
				if (abandoned != null) abandoned.forEach(Waiter::withdraw);
			};
			executor.runOrElse(withdrawal, withdrawal);
			return;
		}

		List<Waiter> pending = registered.get();
		if (pending != null && !awaitInOrder(pending, requester)) return;

		getFailures().require(value);
	}

	/**
	 * Waits, on the calling thread, for each of the given committed buffers in queue order,
	 * stopping after the first that failed, then drains every buffer up to the last one waited
	 * for and withdraws every waiter. Part of {@link #complete}.
	 *
	 * @param waiters   waiters registered on the oldest committed buffers, in queue order, the
	 *                  last on the buffer being waited for
	 * @param requester metadata of the operation waiting for completion, or {@code null}
	 * @return true if the wait ended normally, false if it was abandoned by an interrupt
	 */
	private boolean awaitInOrder(List<Waiter> waiters, OperationMetadata requester) {
		Runnable withdrawal = () -> waiters.forEach(Waiter::withdraw);
		Waiter completed = null;
		boolean interrupted = false;

		try {
			for (Waiter w : waiters) {
				if (!w.getCommitted().awaitSubmitted()) {
					interrupted = true;
					break;
				}

				w.getBuffer().waitUntilCompleted();
				completed = w;
				if (errorOf(w.getBuffer()) != null) break;
			}
		} finally {
			CommittedBuffer through = completed == null ? null : completed.getCommitted();
			if (through == null) {
				executor.runOrElse(withdrawal, withdrawal);
			} else {
				executor.runOrElse(() -> drainThrough(through, requester, waiters), withdrawal);
			}
		}

		return !interrupted;
	}

	/**
	 * Records the failure of the foreign work a bridged dispatch waits for, if it failed, for the
	 * bridged dispatch's value. Called once the foreign work has settled, so its
	 * {@link Semaphore#waitFor()} returns or throws at once.
	 *
	 * @param foreign      the settled foreign completion
	 * @param bridgedValue the timeline value of the dispatch bridged on it
	 */
	private void recordForeignFailure(Semaphore foreign, long bridgedValue) {
		try {
			foreign.waitFor();
		} catch (RuntimeException | Error e) {
			warn("bridgedDependencyFailed value=" + bridgedValue, e);
			getFailures().record(bridgedValue, new HardwareException(
					"The foreign dependency of a bridged Metal dispatch failed: " + e.getMessage(), e));
		}
	}

	/**
	 * Returns the error a completed command buffer finished with, or {@code null} if it
	 * executed. A failed buffer ran none of its dispatches (see the class documentation). This
	 * is the single point at which the runner reads a buffer's Metal status, so a subclass can
	 * report a failure the GPU did not produce and exercise the failure path deterministically.
	 *
	 * @param buffer a command buffer that has completed
	 * @return the Metal error description, or {@code null} if the buffer executed
	 */
	protected String errorOf(MTLCommandBuffer buffer) {
		return buffer.isError() ? buffer.getError() : null;
	}

	/**
	 * Registers a callback to run once the given dispatch's command buffer has completed,
	 * without committing the buffer or waiting for it. The callback joins the buffer's
	 * released-memory callbacks — the same list a dispatch's own {@code onComplete}
	 * argument joins (see {@link #submit}) — so it runs when the buffer completes on its
	 * own schedule ({@link #MAX_OPEN} cadence or a genuine host wait). A buffer that has
	 * already completed and been drained runs the callback immediately. Invoked by
	 * {@link MetalSemaphore#whenComplete(Runnable)}.
	 *
	 * @param dispatch the completion of a dispatch this runner encoded (see {@link #submit})
	 * @param callback the callback to run after the dispatch's buffer completes
	 */
	public void whenComplete(MetalSemaphore dispatch, Runnable callback) {
		MTLCommandBuffer commandBuffer = dispatch.getCommandBuffer();
		executor.run(() -> {
			if (commandBuffer == openBuffer) {
				openOnComplete.add(callback);
				return;
			}

			for (CommittedBuffer c : committed) {
				if (c.getBuffer() == commandBuffer) {
					c.whenComplete(callback);
					return;
				}
			}

			callback.run();
		});
	}

	/** Opens a fresh command buffer if none is currently open. */
	private void ensureOpenBuffer() {
		if (openBuffer == null) {
			if (bufferOpen) {
				throw new IllegalStateException("A second open command buffer was requested while " +
						"one is still open on this ComputeContext — the one-open-buffer-per-context " +
						"invariant is violated");
			}

			openBuffer = queue.commandBuffer();
			openFirstValue = lastOrdinal() + 1;
			openCount = 0;
			bufferOpen = true;
		}
	}

	/**
	 * Returns the number of command buffers this runner has committed. Batching diagnostics:
	 * a group of dispatches that was expected to share one command buffer can be checked by
	 * comparing this count before and after the group is issued.
	 *
	 * @return the total number of command-buffer commits so far
	 */
	public long getCommitCount() { return commitCount; }

	/**
	 * Returns the number of commits forced by a host-side completion wait ({@link #complete}).
	 * Together with {@link #getMaxOpenCommitCount()}, {@link #getBridgeCommitCount()} and
	 * {@link #getDestroyCommitCount()} this partitions {@link #getCommitCount()} by cause:
	 * host waits are the commits that break batching on demand, while {@link #MAX_OPEN}
	 * commits are the expected steady-state cadence. The causes are counted when a buffer is
	 * closed, and {@link #getCommitCount()} when a closed buffer is committed, so while closed
	 * buffers are held for a host-signaled bridge the causes sum to more than the total.
	 *
	 * @return the number of commits caused by host completion waits
	 */
	public long getHostCompleteCommitCount() { return hostCompleteCommits; }

	/**
	 * Returns the number of commits forced by the open buffer reaching {@link #MAX_OPEN} dispatches.
	 *
	 * @return the number of commits caused by the open-buffer dispatch bound
	 */
	public long getMaxOpenCommitCount() { return maxOpenCommits; }

	/**
	 * Returns the number of commits performed while destroying the runner.
	 *
	 * @return the number of commits caused by {@link #destroy()}
	 */
	public long getDestroyCommitCount() { return destroyCommits; }

	/**
	 * Returns the number of commits forced so that a dispatch bridging a foreign dependency
	 * could start a fresh command buffer (see {@link #enableHostSignaledBridges}).
	 *
	 * @return the number of commits caused by foreign-dependency bridges
	 */
	public long getBridgeCommitCount() { return bridgeCommits; }

	/**
	 * Returns the number of foreign-completion signals that arrived after their bridge
	 * event had already been released — the bridged buffer finished by an error or
	 * teardown path before the foreign work completed. Each is skipped safely by
	 * {@link MTLEvent#signal(long)}; a persistently non-zero count indicates bridged
	 * dispatches whose gated work never ran.
	 *
	 * @return the number of skipped late bridge signals
	 */
	public long getLateBridgeSignalCount() { return lateBridgeSignals.get(); }

	/**
	 * Closes the open buffer (if any), moves it to the committed list, and commits it at once
	 * unless it, or a buffer closed before it, still waits for a host-signaled bridge (see
	 * {@link #commitReadyOnExecutor()}). Does not wait for completion. Must run on the executor
	 * thread.
	 *
	 * <p>On closing, the buffer is made to signal the timeline to the value of the last dispatch
	 * encoded so far. That signal fires once every command before it in the buffer has completed,
	 * which is what releases a later buffer's wait for any of this buffer's dispatches (see the
	 * class documentation). The value never decreases from one buffer to the next, and a buffer
	 * closed without a dispatch of its own signals the previous buffer's value again.</p>
	 *
	 * @return true if a buffer was closed, false if no buffer was open
	 */
	private boolean commitOpenOnExecutor() {
		if (openBuffer == null) return false;

		openBuffer.encodeSignalEvent(event, lastOrdinal());
		bufferOpen = false;

		CommittedBuffer closed = new CommittedBuffer(openBuffer, openFirstValue, openOnComplete, openBridges);
		committed.add(closed);
		held.addLast(closed);
		hold();
		openBuffer = null;
		openCount = 0;
		openOnComplete = new ArrayList<>();
		openBridges = new PendingBridges();

		commitReadyOnExecutor();
		return true;
	}

	/**
	 * Commits, in the order they were closed, every closed buffer whose host-signaled bridges have
	 * all been signaled, stopping at the first that still waits for one: a buffer committed ahead
	 * of one closed before it would run ahead of it on the queue. Must run on the executor thread.
	 */
	private void commitReadyOnExecutor() {
		while (!held.isEmpty() && held.peekFirst().isReady()) {
			submitOldestHeld();
			totalCommitCount.incrementAndGet();
			commitCount++;
		}
	}

	/**
	 * Commits the oldest held buffer and releases its place in this stream's hold bound,
	 * waking a submitter waiting for room. Must run on the executor thread.
	 */
	private void submitOldestHeld() {
		try {
			held.removeFirst().submit();
		} finally {
			release();
		}
	}

	/**
	 * Commits the target buffer if it is still open and registers a waiter on it and on every
	 * buffer committed before it, so that none of them is released while the caller waits for
	 * them, in queue order, off the executor thread. Must run on the executor thread.
	 *
	 * @param target    the buffer to wait for
	 * @param requester metadata of the operation waiting for completion, or {@code null}
	 * @return the waiters registered on the oldest committed buffers up to and including the
	 *         target, in queue order, or {@code null} if the target has already completed and
	 *         been drained by an earlier wait
	 */
	private List<Waiter> commitForWait(MTLCommandBuffer target, OperationMetadata requester) {
		if (target == openBuffer && commitOpenOnExecutor()) {
			hostCompleteCommits++;
			hostCompleteRequesters.addEntry(
					requester == null ? "unknown" : requester.getDisplayName(), 1);
		}

		for (int i = 0; i < committed.size(); i++) {
			if (committed.get(i).getBuffer() == target) {
				List<Waiter> waiters = new ArrayList<>(i + 1);
				for (int j = 0; j <= i; j++) {
					waiters.add(new Waiter(committed.get(j)));
				}
				return waiters;
			}
		}

		return null;
	}

	/**
	 * Drains every committed buffer up to and including {@code through}, which has completed,
	 * running their callbacks in order, and then withdraws the waiters registered for the wait.
	 * Must run on the executor thread.
	 *
	 * <p>The command queue is serial, so every buffer committed before a completed buffer has
	 * completed too and none of these drains waits for the GPU. A buffer another waiter already
	 * drained is not drained again. Every buffer is drained through
	 * {@link Destroyable#releaseAll} so a drain that throws (an aggregated callback failure from
	 * one buffer) does not skip the callbacks and buffer release of the later ones; the first
	 * failure is rethrown once all have drained, with any later ones attached as suppressed.</p>
	 *
	 * @param through   the buffer the caller has finished waiting for
	 * @param requester metadata of the operation that waited, or {@code null}
	 * @param waiters   the waiters registered for the wait
	 */
	private void drainThrough(CommittedBuffer through, OperationMetadata requester, List<Waiter> waiters) {
		int count = committed.indexOf(through) + 1;
		List<Runnable> drains = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			drains.add(() -> drainOldestCommitted(requester));
		}
		Destroyable.releaseAll(drains, () -> waiters.forEach(Waiter::withdraw));
	}

	/**
	 * Waits for the oldest committed buffer, verifies it actually executed, runs its
	 * callbacks, and releases it. Must run on the executor thread.
	 *
	 * <p>{@link MTLCommandBuffer#waitUntilCompleted()} returns identically for a buffer
	 * that executed and for one killed by a GPU fault or watchdog — in the killed case
	 * every dispatch in the buffer silently never ran, its destinations were never
	 * written, and its timeline-event signals never fired, which permanently stalls any
	 * later dispatch that encoded a wait on those values. Detecting the error status
	 * here is what turns that silent-wrong-data state into a visible failure: it is
	 * recorded on the runner for the buffer's first timeline value, so that every wait
	 * for that dispatch or a later one throws it (see {@link #complete}). Only the
	 * record is made here; the buffer is drained exactly as one that executed, so its
	 * memory and bridge events are still released.</p>
	 *
	 * @param requester metadata of the operation whose wait forced this drain, or null
	 */
	private void drainOldestCommitted(OperationMetadata requester) {
		CommittedBuffer c = committed.remove(0);
		MTLCommandBuffer buffer = c.getBuffer();
		buffer.waitUntilCompleted();

		String error = errorOf(buffer);
		if (error != null) {
			errorCompletions.incrementAndGet();
			String name = requester == null ? "unknown" : requester.getDisplayName();
			warn("commandBufferError=\"" + error + "\" requester=" + name +
					" commitCount=" + commitCount +
					" errorCompletions=" + errorCompletions.get());
			getFailures().record(c.getFirstValue(), new HardwareException("Metal command buffer failed and " +
					"none of its dispatches executed: " + error + " (requester " + name + ")"));
		}

		c.drain();
	}

	/**
	 * Returns the number of committed buffers that finished with the Metal error status.
	 *
	 * @return the error-completion count
	 */
	public long getErrorCompletionCount() { return errorCompletions.get(); }

	/**
	 * Destroys this command runner and releases all resources.
	 *
	 * <p>Destruction is separated the same way {@link #complete} is: the open buffer is committed
	 * on the executor's thread, the wait for the GPU happens on the calling thread, and only the
	 * drain of already-completed buffers runs on the executor's thread. A committed buffer can be
	 * held on the GPU by a host-signaled bridge whose foreign work itself needs this runner — to
	 * wait for one of its earlier dispatches, as a composite completion does. Waiting for that
	 * buffer on the executor's single thread, or after the executor has stopped accepting work,
	 * would leave the foreign work unable to finish, so the bridge would never be signaled and
	 * the buffer would stall until the GPU watchdog killed it. The executor therefore keeps
	 * accepting work until every buffer committed has completed.</p>
	 *
	 * <p>Work submitted while that wait is in progress may commit newer buffers, so destruction
	 * repeats the commit-and-wait until a round finds nothing committed. Only then, atomically
	 * with that finding (see {@link #awaitLastCommittedOrDestroy}), does the executor stop
	 * accepting work; its final task releases the timeline event and has no buffer left to wait
	 * for. If a drain fails, the executor is destroyed anyway: the final task commits and drains
	 * whatever remains through {@link Destroyable#releaseAll}, so a drain that throws cannot leak
	 * the remaining buffers or the shared event, and the thread is shut down whether or not that
	 * task succeeds.</p>
	 *
	 * <p>Destruction is not abandoned when the caller is interrupted, unlike {@link #complete}:
	 * neither the wait for the GPU nor any of the commit, drain and withdrawal tasks it
	 * coordinates with the executor (see {@link ConfinedExecutor#runOrElseUninterruptibly}),
	 * nor the executor's final drain, whose failure is rethrown rather than lost.
	 * Proceeding before such a task had run would let a round see no committed buffer that is
	 * really there, stop the executor, and leave the final task to wait on the executor's thread,
	 * which is exactly the stall described above. An interrupt pending on entry, or arriving at
	 * any point during destruction, is restored afterwards. Destroying a runner that is already
	 * destroyed does nothing.</p>
	 *
	 * @throws IllegalStateException if called from a task running on the runner's own thread,
	 *                               such as a completion callback
	 */
	public void destroy() {
		requireOffStreamThread();

		boolean interrupted = Thread.interrupted();
		AtomicReference<Waiter> last = new AtomicReference<>();
		Runnable withdrawal = () -> {
			Waiter registered = last.get();
			if (registered != null) registered.withdraw();
		};

		try {
			while (awaitLastCommittedOrDestroy(last)) {
				Waiter pending = last.get();
				executor.runOrElseUninterruptibly(() -> drainThrough(pending.getCommitted(), null,
						List.of(pending)), pending::withdraw);
			}
		} finally {
			try {
				destroyExecutor();
			} finally {
				// The executor is gone by now, so this runs on the calling thread once every
				// queued task has run, including a registration whose wait was abandoned
				executor.runOrElseUninterruptibly(withdrawal, withdrawal);
				if (interrupted) Thread.currentThread().interrupt();
			}
		}
	}

	/**
	 * One round of {@link #destroy()}: commits the open buffer and, if any committed buffer
	 * remains, waits for the last of them on the calling thread while the executor still accepts
	 * work; otherwise stops accepting work and destroys the executor.
	 *
	 * <p>The check for remaining buffers and the destruction happen while holding
	 * this stream's admission lock (see {@link SubmissionStream#closeIf}), which {@link #submit}
	 * also holds, so no dispatch can be encoded between the runner being found idle and the
	 * stream and executor refusing further work. The wait itself happens
	 * without holding it, so work that the waited buffer's bridge depends on can still be
	 * submitted.</p>
	 *
	 * @param last receives the waiter registered on the last committed buffer, or {@code null}
	 * @return true if a buffer was waited for and must now be drained, false once the executor
	 *         has been destroyed
	 */
	private boolean awaitLastCommittedOrDestroy(AtomicReference<Waiter> last) {
		boolean closed = closeIf(() -> {
			last.set(null);
			executor.runOrElseUninterruptibly(() -> {
				if (commitOpenOnExecutor()) destroyCommits++;
				if (!committed.isEmpty()) {
					last.set(new Waiter(committed.get(committed.size() - 1)));
				}
			}, () -> { });

			if (last.get() != null) return false;

			destroyExecutor();
			return true;
		});

		if (closed) return false;

		last.get().getCommitted().awaitSubmittedUninterruptibly();
		last.get().getBuffer().waitUntilCompleted();
		return true;
	}

	/**
	 * Destroys the executor with a final task that commits anything still open, drains every
	 * committed buffer running its callbacks, and releases the timeline event. Destroying an
	 * executor that is already destroyed does nothing.
	 *
	 * <p>A closed buffer whose bridge was never signaled is committed by the final task as well:
	 * left uncommitted, draining it would wait forever, while committed it completes (by error,
	 * once the GPU gives up on the wait) like any other.</p>
	 */
	private void destroyExecutor() {
		executor.destroy(() -> {
			if (commitOpenOnExecutor()) destroyCommits++;

			while (!held.isEmpty()) submitOldestHeld();

			List<Runnable> drains = new ArrayList<>(committed.size());
			for (int i = committed.size(); i > 0; i--) {
				drains.add(() -> drainOldestCommitted(null));
			}
			Destroyable.releaseAll(drains, event::release);
		});
	}

	/** Returns the console for logging. */
	@Override
	public Console console() { return Hardware.console; }

	/**
	 * A committed command buffer and the released-memory callbacks to run once it completes.
	 *
	 * <p>Threads waiting for the buffer off the executor thread (see {@link #complete}) are
	 * counted, and the native buffer is released only once it has been drained and no such
	 * thread is still waiting on it. The list and callbacks are executor-thread only; the
	 * waiter count and drained flag are guarded by this record's monitor, because a waiter
	 * outliving a destroyed executor withdraws on its own thread.</p>
	 */
	private static final class CommittedBuffer {
		/** The closed command buffer. */
		private final MTLCommandBuffer buffer;
		/** The timeline value of the first dispatch encoded into the buffer. */
		private final long firstValue;
		/** Released-memory callbacks to run once the buffer completes. */
		private final List<Runnable> onComplete;
		/** The host-signaled bridges encoded into the buffer. */
		private final PendingBridges bridges;
		/** Released once the buffer has been committed to the GPU. */
		private final CountDownLatch submitted = new CountDownLatch(1);
		/** Threads currently waiting for the buffer outside the executor thread. */
		private int waiters;
		/** Whether the buffer has been drained (its callbacks have run). */
		private boolean drained;

		/**
		 * Creates a record for a buffer that has just been closed.
		 *
		 * @param buffer     the closed command buffer
		 * @param firstValue the timeline value of the first dispatch encoded into it
		 * @param onComplete callbacks to run once it completes
		 * @param bridges    the host-signaled bridges encoded into it
		 */
		private CommittedBuffer(MTLCommandBuffer buffer, long firstValue, List<Runnable> onComplete,
								PendingBridges bridges) {
			this.buffer = buffer;
			this.firstValue = firstValue;
			this.onComplete = onComplete;
			this.bridges = bridges;
		}

		/**
		 * Returns the timeline value of the first dispatch encoded into the buffer, the lowest
		 * value that does not execute if the buffer fails.
		 *
		 * @return the buffer's first timeline value
		 */
		private long getFirstValue() { return firstValue; }

		/**
		 * Returns whether every host-signaled bridge in the buffer has been signaled, so that
		 * committing it cannot leave the GPU waiting for the host. Executor-thread only.
		 *
		 * @return true if the buffer may be committed
		 */
		private boolean isReady() { return bridges.isSignaled(); }

		/** Commits the buffer to the GPU and releases any thread awaiting that. Executor-thread only. */
		private void submit() {
			buffer.commit();
			submitted.countDown();
		}

		/**
		 * Blocks until the buffer has been committed, which is what makes waiting for its
		 * completion meaningful. Ends early if the calling thread is interrupted, restoring the
		 * interrupt status.
		 *
		 * @return true once the buffer has been committed, false if the wait was interrupted
		 */
		private boolean awaitSubmitted() {
			try {
				submitted.await();
				return true;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
		}

		/**
		 * Blocks until the buffer has been committed, as {@link #awaitSubmitted()} does, without
		 * being ended by an interrupt; an interrupt received meanwhile is restored afterwards.
		 */
		private void awaitSubmittedUninterruptibly() {
			boolean interrupted = Thread.interrupted();
			while (!awaitSubmitted()) {
				Thread.interrupted();
				interrupted = true;
			}
			if (interrupted) Thread.currentThread().interrupt();
		}

		/**
		 * Returns the committed command buffer.
		 *
		 * @return the command buffer this record tracks
		 */
		private MTLCommandBuffer getBuffer() { return buffer; }

		/**
		 * Adds a released-memory callback to run once the buffer completes. Executor-thread only.
		 *
		 * @param callback the callback to run when the buffer is drained
		 */
		private void whenComplete(Runnable callback) {
			onComplete.add(callback);
		}

		/**
		 * Runs the released-memory callbacks of the completed buffer, then records it as drained,
		 * releasing it unless a thread still waits on it. Every callback runs and the buffer is
		 * released even if a callback fails; the first failure is rethrown afterwards (see
		 * {@link Destroyable#releaseAll}). Executor-thread only.
		 */
		private void drain() {
			Destroyable.releaseAll(onComplete, this::drained);
		}

		/** Records that the buffer has been drained, releasing it unless a thread still waits on it. */
		private synchronized void drained() {
			drained = true;
			releaseIfUnused();
		}

		/** Registers one waiter, keeping the buffer retained until it is withdrawn. */
		private synchronized void addWaiter() {
			waiters++;
		}

		/** Withdraws one waiter, releasing the buffer if it was the last and the buffer is drained. */
		private synchronized void removeWaiter() {
			waiters--;
			releaseIfUnused();
		}

		/** Releases the native buffer once it is drained and no thread waits on it. */
		private void releaseIfUnused() {
			if (drained && waiters == 0) buffer.release();
		}
	}

	/**
	 * The number of host-signaled bridges encoded into one command buffer that have not been
	 * signaled yet. Executor-thread only: a bridge is counted when it is encoded, and its signal is
	 * counted by a task the foreign completion's callback runs on the executor.
	 */
	private static final class PendingBridges {
		/** Bridges encoded and not yet signaled. */
		private int pending;

		/** Counts a bridge encoded into the buffer. */
		private void add() { pending++; }

		/** Counts the signal of one of the buffer's bridges. */
		private void signaled() { pending--; }

		/**
		 * Returns whether every bridge encoded into the buffer has been signaled.
		 *
		 * @return true if no bridge is still pending
		 */
		private boolean isSignaled() { return pending == 0; }
	}

	/**
	 * One thread's registration as a waiter on a {@link CommittedBuffer}, withdrawn exactly once.
	 *
	 * <p>Withdrawal can be requested from more than one path — the drain after the wait, the
	 * queued withdrawal of an interrupted caller, or the calling thread itself once the executor
	 * has been destroyed — so it is guarded to take effect only the first time.</p>
	 */
	private static final class Waiter {
		/** The committed buffer this waiter is registered on. */
		private final CommittedBuffer committed;
		/** Whether this registration has already been withdrawn. */
		private final AtomicBoolean withdrawn = new AtomicBoolean();

		/**
		 * Registers a waiter on the given committed buffer.
		 *
		 * @param committed the committed buffer to wait on
		 */
		private Waiter(CommittedBuffer committed) {
			this.committed = committed;
			committed.addWaiter();
		}

		/**
		 * Returns the committed buffer this waiter is registered on.
		 *
		 * @return the committed-buffer record being waited on
		 */
		private CommittedBuffer getCommitted() { return committed; }

		/**
		 * Returns the command buffer being waited on.
		 *
		 * @return the committed command buffer
		 */
		private MTLCommandBuffer getBuffer() { return committed.getBuffer(); }

		/** Withdraws this registration, unless it has already been withdrawn. */
		private void withdraw() {
			if (withdrawn.compareAndSet(false, true)) committed.removeWaiter();
		}
	}
}
