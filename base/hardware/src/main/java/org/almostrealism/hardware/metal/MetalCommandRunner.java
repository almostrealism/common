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
import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.streams.Semaphore;
import io.almostrealism.profile.OperationMetadata;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.DistributionMetric;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns the Metal command-buffer lifecycle for a {@link MetalComputeContext}.
 *
 * <p>Dispatches are <em>encoded</em> into an open command buffer (not committed per dispatch) on a
 * single-threaded executor, so independent dispatches accumulate into one command buffer. Each
 * dispatch returns a {@link MetalSemaphore} — the operation's single completion handle — and
 * signals a {@link MTLEvent} timeline value. Ordering between dependent dispatches is expressed at
 * the GPU level: when a dispatch is submitted with a {@link MetalSemaphore} dependency of this
 * runner, nothing is committed. A dependency in the still-open buffer is ordered by in-buffer hazard
 * tracking and costs no wait (only a merged completion's
 * {@link MetalSemaphore#getPriorBufferValue() prior buffer value}, if any, is waited for); a
 * dependency in an earlier, committed buffer is honored by encoding a wait for its event value, so
 * the GPU serializes the dependent after the dependency across buffers without a host stall. Only a
 * foreign dependency commits the open buffer first (see {@link #submit}).</p>
 *
 * <p>{@link MetalSemaphore#waitFor()} is the only thing that blocks the host: it commits the
 * dispatch's buffer if still open and waits for it (and every buffer committed before it, since the
 * queue is serial) to complete.</p>
 *
 * <h2>Memory lifetime</h2>
 *
 * <p>Memory referenced by an encoded kernel must stay alive until its command buffer has completed.
 * Callers pass an {@code onCommit} callback to {@link #submit} that releases that memory; the runner
 * runs it only after the buffer the dispatch was encoded into has completed.</p>
 *
 * <p>Objective-C memory: every executor task runs inside its own autorelease pool (see
 * {@link #runInPool}) so transient autoreleased Metal objects (encoders, the command buffer's own
 * autorelease reference) are drained per task and do not accumulate in the driver. The open command
 * buffer outlives its creating task, so it is retained explicitly when created
 * ({@link MTL#commandBuffer(long)}) and released ({@link MTLCommandBuffer#release()}) once it has
 * completed.</p>
 */
public class MetalCommandRunner implements ConsoleFeatures {
	/** Maximum number of kernel arguments a single command may bind. */
	public static final int MAX_ARGS = 512;

	/**
	 * Maximum dispatches encoded into one open command buffer before it is committed. This is a
	 * memory bound (it caps how much encoded-but-uncompleted work and how large an autorelease pool
	 * accumulate), not a behavioural switch — correctness does not depend on its value.
	 */
	private static final int MAX_OPEN = 256;

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
	 * Confines all command-buffer operations to one thread, running each inside its own
	 * autorelease pool (see {@link #runInPool}).
	 */
	private final ConfinedExecutor executor = new ConfinedExecutor(this::runInPool);

	/**
	 * Held while a dispatch is encoded, and by {@link #destroy()} while it decides the runner is
	 * idle and stops the executor, so no dispatch can be encoded between those two steps.
	 */
	private final Object admission = new Object();

	/** The command queue used to submit encoded Metal compute commands. */
	private final MTLCommandQueue queue;

	/** Timeline event used to order dependent dispatches across command buffers on the GPU. */
	private final MTLEvent event;

	/** Monotonic value last signaled on {@link #event}. Executor-thread only. */
	private long signaled;

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
	/** Number of dispatches encoded into the open buffer. Executor-thread only. */
	private int openCount;
	/** Released-memory callbacks for dispatches in the open buffer. Executor-thread only. */
	private List<Runnable> openOnComplete = new ArrayList<>();

	/** Committed-but-not-yet-completed buffers, in commit (queue) order. Executor-thread only. */
	private final List<CommittedBuffer> committed = new ArrayList<>();

	/**
	 * True while an open (created but not yet committed) command buffer exists. Enforces the
	 * one-open-buffer-per-{@link MetalComputeContext} invariant: a single context must never have a
	 * second uncommitted command buffer in flight (cross-buffer coordination is only meaningful
	 * <em>between</em> contexts, and a Computation tree compiles against a single Metal context).
	 * Tracked explicitly so an attempt to open a second buffer fails fast instead of silently
	 * producing the kind of wedged-completion state this work has repeatedly hit.
	 */
	private boolean bufferOpen;

	/**
	 * Creates a Metal command runner.
	 *
	 * @param queue The {@link MTLCommandQueue} for submitting commands
	 */
	public MetalCommandRunner(MTLCommandQueue queue) {
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
	 * foreign work completes, via {@link Semaphore#onComplete(Runnable)}. The dispatch is
	 * therefore ordered after the foreign work with no host wait and no forced commit. If the
	 * foreign work never completes, the buffer never completes — the same exposure a blocking
	 * bridge has, moved onto the GPU. With bridges disabled, the foreign dependency is waited
	 * before the dispatch is encoded.</p>
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
	 * @param requester  metadata of the operation the dispatch belongs to, or {@code null}; carried by
	 *                   the returned semaphore so a later commit-forcing wait can be attributed to it
	 * @param command   encodes the kernel into the supplied command buffer
	 * @param dependsOn  a prior {@link MetalSemaphore} this dispatch depends on, or {@code null}
	 * @param onComplete released-memory callback to run after this dispatch's buffer completes, or null
	 * @return this dispatch's completion semaphore
	 */
	public MetalSemaphore submit(OperationMetadata requester, MetalCommand command,
								 Semaphore dependsOn, Runnable onComplete) {
		List<MetalSemaphore> result = new ArrayList<>(1);

		executor.requireOffConfinedThread();

		// An unbridged foreign dependency is waited here, on the caller's thread, never on the
		// executor's: settling it may need this runner (a composite over this runner's own
		// semaphores completes each of them through complete(), which runs on the executor)
		Semaphore order = dependsOn;
		if (order != null && !enableHostSignaledBridges && !ordersAfter(order)) {
			order.waitFor();
			order = null;
		}

		synchronized (admission) {
			encode(requester, command, order, onComplete, result);
		}

		return result.get(0);
	}

	/**
	 * Returns true if {@code dependsOn} is the completion of a dispatch submitted to this runner,
	 * which {@link #submit} orders on the GPU rather than bridging as a foreign dependency.
	 *
	 * @param dependsOn a completion, or {@code null}
	 * @return true if {@code dependsOn} is a {@link MetalSemaphore} of this runner
	 */
	public boolean ordersAfter(Semaphore dependsOn) {
		return dependsOn instanceof MetalSemaphore && ((MetalSemaphore) dependsOn).getRunner() == this;
	}

	/**
	 * Encodes one dispatch on the executor's thread for {@link #submit}, adding its completion
	 * semaphore to {@code result}.
	 *
	 * @param requester  metadata of the operation the dispatch belongs to, or {@code null}
	 * @param command    encodes the kernel into the supplied command buffer
	 * @param dependsOn  a prior {@link Semaphore} this dispatch depends on, or {@code null}
	 * @param onComplete released-memory callback to run after this dispatch's buffer completes, or null
	 * @param result     receives the dispatch's completion semaphore
	 */
	private void encode(OperationMetadata requester, MetalCommand command,
						Semaphore dependsOn, Runnable onComplete, List<MetalSemaphore> result) {
		executor.run(() -> {
			boolean sameRunner = ordersAfter(dependsOn);
			MetalSemaphore dependency = sameRunner ? (MetalSemaphore) dependsOn : null;
			Semaphore foreign = dependsOn != null && !sameRunner ? dependsOn : null;

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
			// runs. Committing first makes the cycle impossible; it costs the same commit the
			// old blocking bridge caused, without blocking the host.
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
				foreign.onComplete(() -> {
					if (!bridge.signal(1)) {
						lateBridgeSignals.incrementAndGet();
					}
				});
			}

			command.encode(openBuffer);

			signaled++;
			openBuffer.encodeSignalEvent(event, signaled);
			openCount++;
			totalDispatchCount.incrementAndGet();
			if (onComplete != null) openOnComplete.add(onComplete);

			result.add(new MetalSemaphore(requester, this, openBuffer, event, signaled));

			if (openCount >= MAX_OPEN && commitOpenOnExecutor()) {
				maxOpenCommits++;
			}
		});
	}

	/**
	 * Commits (if still open) the buffer the given dispatch was encoded into and blocks until it,
	 * and every buffer committed before it, has completed; then runs their released-memory
	 * callbacks. Invoked by {@link MetalSemaphore#waitFor()}.
	 *
	 * @param commandBuffer the dispatch's command buffer
	 */
	public void complete(MTLCommandBuffer commandBuffer) {
		complete(commandBuffer, null);
	}

	/**
	 * Commits (if still open) the buffer the given dispatch was encoded into and blocks until it,
	 * and every buffer committed before it, has completed; then runs their released-memory
	 * callbacks. When the wait forces a commit, the commit is attributed to {@code requester} in
	 * {@link #hostCompleteRequesters} so batching-breaking waits can be traced to the operations
	 * responsible for them.
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
	 * <p>{@link #destroy()} may run while a caller is still waiting here. Its final task drains
	 * every committed buffer, including the one being waited for, but leaves it retained for the
	 * waiter; the executor is then gone, so the waiter withdraws itself on its own thread (see
	 * {@link ConfinedExecutor#runOrElse}) and the buffer is released there. The caller returns
	 * normally.</p>
	 *
	 * @param commandBuffer the dispatch's command buffer
	 * @param requester     metadata of the operation waiting for completion, or {@code null}
	 */
	public void complete(MTLCommandBuffer commandBuffer, OperationMetadata requester) {
		AtomicReference<Waiter> registered = new AtomicReference<>();
		executor.run(() -> registered.set(commitForWait(commandBuffer, requester)));

		if (Thread.currentThread().isInterrupted()) {
			Runnable withdrawal = () -> {
				Waiter abandoned = registered.get();
				if (abandoned != null) abandoned.withdraw();
			};
			executor.runOrElse(withdrawal, withdrawal);
			return;
		}

		Waiter pending = registered.get();
		if (pending == null) return;

		try {
			pending.getBuffer().waitUntilCompleted();
		} finally {
			executor.runOrElse(() -> drainThrough(pending, requester), pending::withdraw);
		}
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
	 * @param commandBuffer the dispatch's command buffer
	 * @param callback      the callback to run after that buffer completes
	 */
	public void whenComplete(MTLCommandBuffer commandBuffer, Runnable callback) {
		executor.run(() -> {
			if (commandBuffer == openBuffer) {
				openOnComplete.add(callback);
				return;
			}

			for (CommittedBuffer c : committed) {
				if (c.buffer == commandBuffer) {
					c.onComplete.add(callback);
					return;
				}
			}

			callback.run();
		});
	}

	/**
	 * Runs {@code task} on the executor's thread inside a per-task Objective-C autorelease pool, so
	 * transient autoreleased Metal objects (compute command encoders, and the command buffer's own
	 * autorelease reference) are drained when the task ends rather than accumulating in the driver
	 * until it stalls. The open command buffer survives across tasks because it is retained
	 * explicitly when created (see {@link MTL#commandBuffer(long)}) and released by
	 * {@link #drainOldestCommitted} once it has completed. A pool scoped to each task (rather than one
	 * spanning a buffer's whole open lifetime across several tasks) is required: spanning a pool
	 * across the executor's task boundaries wedged command-buffer completion.
	 *
	 * @param task the work to run inside a fresh autorelease pool
	 */
	private void runInPool(Runnable task) {
		long pool = MTL.autoreleasePoolPush();

		try {
			task.run();
		} finally {
			MTL.autoreleasePoolPop(pool);
		}
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
	 * commits are the expected steady-state cadence.
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
	 * Commits the open buffer (if any) and moves it to the committed list. Does not wait for
	 * completion. Must run on the executor thread.
	 *
	 * @return true if a commit was performed, false if no buffer was open
	 */
	private boolean commitOpenOnExecutor() {
		if (openBuffer == null) return false;

		totalCommitCount.incrementAndGet();
		commitCount++;

		openBuffer.commit();
		bufferOpen = false;

		committed.add(new CommittedBuffer(openBuffer, openOnComplete));
		openBuffer = null;
		openCount = 0;
		openOnComplete = new ArrayList<>();
		return true;
	}

	/**
	 * Commits the target buffer if it is still open and registers a waiter on it, so that it is
	 * not released while the waiter waits for it off the executor thread. Must run on the
	 * executor thread.
	 *
	 * @param target    the buffer to wait for
	 * @param requester metadata of the operation waiting for completion, or {@code null}
	 * @return the waiter registered on the target, or {@code null} if it has already completed
	 *         and been drained by an earlier wait
	 */
	private Waiter commitForWait(MTLCommandBuffer target, OperationMetadata requester) {
		if (target == openBuffer && commitOpenOnExecutor()) {
			hostCompleteCommits++;
			hostCompleteRequesters.addEntry(
					requester == null ? "unknown" : requester.getDisplayName(), 1);
		}

		for (CommittedBuffer c : committed) {
			if (c.buffer == target) {
				return new Waiter(c);
			}
		}

		return null;
	}

	/**
	 * Drains every committed buffer up to and including {@code waiter}'s, which has completed,
	 * running their callbacks in order, and then withdraws the waiter registered by
	 * {@link #commitForWait}. Must run on the executor thread.
	 *
	 * <p>The command queue is serial, so every buffer committed before a completed buffer has
	 * completed too and none of these drains waits for the GPU. A buffer another waiter already
	 * drained is not drained again. Every buffer is drained through
	 * {@link Destroyable#releaseAll} so a drain that throws (an aggregated callback failure from
	 * one buffer) does not skip the callbacks and buffer release of the later ones; the first
	 * failure is rethrown once all have drained, with any later ones attached as suppressed.</p>
	 *
	 * @param waiter    the waiter whose buffer the caller has finished waiting for
	 * @param requester metadata of the operation that waited, or {@code null}
	 */
	private void drainThrough(Waiter waiter, OperationMetadata requester) {
		int count = committed.indexOf(waiter.committed) + 1;
		List<Runnable> drains = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			drains.add(() -> drainOldestCommitted(requester));
		}
		Destroyable.releaseAll(drains, waiter::withdraw);
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
	 * here is what turns that silent-wrong-data state into a visible failure.</p>
	 *
	 * @param requester metadata of the operation whose wait forced this drain, or null
	 */
	private void drainOldestCommitted(OperationMetadata requester) {
		CommittedBuffer c = committed.remove(0);
		c.buffer.waitUntilCompleted();

		if (c.buffer.isError()) {
			errorCompletions.incrementAndGet();
			warn("commandBufferError=\"" + c.buffer.getError() + "\" requester=" +
					(requester == null ? "unknown" : requester.getDisplayName()) +
					" commitCount=" + commitCount +
					" errorCompletions=" + errorCompletions.get());
		}

		Destroyable.releaseAll(c.onComplete, c::drained);
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
		executor.requireOffConfinedThread();

		boolean interrupted = Thread.interrupted();
		AtomicReference<Waiter> last = new AtomicReference<>();
		Runnable withdrawal = () -> {
			Waiter registered = last.get();
			if (registered != null) registered.withdraw();
		};

		try {
			while (awaitLastCommittedOrDestroy(last)) {
				Waiter pending = last.get();
				executor.runOrElseUninterruptibly(() -> drainThrough(pending, null), pending::withdraw);
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
	 * {@link #admission}, which {@link #submit} also holds, so no dispatch can be encoded between
	 * the runner being found idle and the executor refusing further work. The wait itself happens
	 * without holding it, so work that the waited buffer's bridge depends on can still be
	 * submitted.</p>
	 *
	 * @param last receives the waiter registered on the last committed buffer, or {@code null}
	 * @return true if a buffer was waited for and must now be drained, false once the executor
	 *         has been destroyed
	 */
	private boolean awaitLastCommittedOrDestroy(AtomicReference<Waiter> last) {
		synchronized (admission) {
			last.set(null);
			executor.runOrElseUninterruptibly(() -> {
				if (commitOpenOnExecutor()) destroyCommits++;
				if (!committed.isEmpty()) {
					last.set(new Waiter(committed.get(committed.size() - 1)));
				}
			}, () -> { });

			if (last.get() == null) {
				destroyExecutor();
				return false;
			}
		}

		last.get().getBuffer().waitUntilCompleted();
		return true;
	}

	/**
	 * Destroys the executor with a final task that commits anything still open, drains every
	 * committed buffer running its callbacks, and releases the timeline event. Destroying an
	 * executor that is already destroyed does nothing.
	 */
	private void destroyExecutor() {
		executor.destroy(() -> {
			if (commitOpenOnExecutor()) destroyCommits++;

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
		/** The committed command buffer. */
		private final MTLCommandBuffer buffer;
		/** Released-memory callbacks to run once the buffer completes. */
		private final List<Runnable> onComplete;
		/** Threads currently waiting for the buffer outside the executor thread. */
		private int waiters;
		/** Whether the buffer has been drained (its callbacks have run). */
		private boolean drained;

		/**
		 * Creates a committed-buffer record.
		 *
		 * @param buffer     the committed command buffer
		 * @param onComplete callbacks to run once it completes
		 */
		private CommittedBuffer(MTLCommandBuffer buffer, List<Runnable> onComplete) {
			this.buffer = buffer;
			this.onComplete = onComplete;
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
		 * Returns the command buffer being waited on.
		 *
		 * @return the committed command buffer
		 */
		private MTLCommandBuffer getBuffer() { return committed.buffer; }

		/** Withdraws this registration, unless it has already been withdrawn. */
		private void withdraw() {
			if (withdrawn.compareAndSet(false, true)) committed.removeWaiter();
		}
	}
}
