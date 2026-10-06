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

package org.almostrealism.hardware.test;

import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.concurrent.CompletionConsumer;
import io.almostrealism.concurrent.DefaultLatchSemaphore;
import io.almostrealism.streams.EvaluableStreamingAdapter;
import io.almostrealism.streams.Semaphore;
import io.almostrealism.concurrent.Submittable;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.streams.StreamingEvaluable;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.DestinationEvaluable;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.OperationListRunner;
import org.almostrealism.hardware.computations.Assignment;
import org.almostrealism.hardware.computations.HardwareEvaluable;
import org.almostrealism.hardware.mem.MemoryDataArgumentMap;
import org.almostrealism.hardware.metal.MTLCommandQueue;
import org.almostrealism.hardware.metal.MetalCommandRunner;
import org.almostrealism.hardware.metal.MetalComputeContext;
import org.almostrealism.hardware.metal.MetalSemaphore;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Validates the two guarantees that make {@link Semaphore} chaining safe to use everywhere,
 * so that internal machinery can always thread a {@code dependsOn} through
 * {@link Submittable#submit(Semaphore)} instead of blocking the host:
 *
 * <ul>
 *   <li>Chaining dispatches on one Metal runner does <em>not</em> defeat command-buffer
 *   batching — a dependency still in the open buffer is ordered by in-buffer hazard
 *   tracking and costs no commit ({@link #chainedMetalDispatchesShareCommandBuffer()}).</li>
 *   <li>{@link OperationListRunner} orders members across {@link io.almostrealism.code.ComputeContext}s
 *   by threading each member's completion into the next member's
 *   {@link Submittable#submit(Semaphore)} ({@link #mixedContextListOrdering()}) — the
 *   regression case where a Metal producer's uncommitted work was read by a synchronous
 *   native consumer.</li>
 * </ul>
 */
public class SemaphoreChainBatchingTest extends TestSuiteBase {

	/**
	 * Chains three Metal copy kernels via explicit {@link Submittable#submit(Semaphore)} and
	 * verifies that issuing the chain performs no command-buffer commits (the dependencies are
	 * in-buffer and therefore free) while the final wait commits exactly once and yields
	 * correct, fully-ordered results.
	 */
	@Test(timeout = 60000)
	public void chainedMetalDispatchesShareCommandBuffer() {
		MetalComputeContext metal = metalContext();
		if (metal == null) {
			log("skipping, no MetalComputeContext available");
			return;
		}

		boolean aggregation = MemoryDataArgumentMap.enableArgumentAggregation;
		MemoryDataArgumentMap.enableArgumentAggregation = false;

		try {
			int n = 16;

			PackedCollection src = new PackedCollection(n);
			PackedCollection mid = new PackedCollection(n);
			PackedCollection dst = new PackedCollection(n);
			rand(src.getShape()).add(1.0).into(src.traverseEach()).evaluate();

			Submittable op1 = copyKernel(src, mid, n, ComputeRequirement.MTL);
			Submittable op2 = copyKernel(mid, dst, n, ComputeRequirement.MTL);

			MetalCommandRunner runner = metal.getCommandRunner();
			long baseline = runner.getCommitCount();

			Semaphore s1 = op1.submit(null);
			Semaphore s2 = op2.submit(s1);

			long issued = runner.getCommitCount();
			assertEquals((double) baseline, (double) issued);

			if (s2 != null) {
				s2.waitFor();
			}

			for (int i = 0; i < n; i++) {
				assertEquals(src.toDouble(i), dst.toDouble(i));
			}

			assertEquals((double) (baseline + 1), (double) runner.getCommitCount());
		} finally {
			MemoryDataArgumentMap.enableArgumentAggregation = aggregation;
		}
	}

	/**
	 * Verifies that completions of one Metal runner merge rather than compose: merging two
	 * dispatches encoded into the same command buffer yields the later dispatch's completion
	 * itself, forces no command-buffer commit, and waiting on it covers the earlier dispatch.
	 * A host-side composite would instead wait for each member on a callback thread, and each
	 * of those waits commits the open buffer.
	 */
	@Test(timeout = 60000)
	public void sameRunnerCompletionsMergeWithoutCommit() {
		MetalComputeContext metal = metalContext();
		if (metal == null) {
			log("skipping, no MetalComputeContext available");
			return;
		}

		MetalCommandRunner runner = metal.getCommandRunner();

		// Start from a drained runner, so both dispatches below share one command buffer
		runner.submit(null, buffer -> { }, null, null).waitFor();
		long baseline = runner.getCommitCount();

		AtomicBoolean firstRan = new AtomicBoolean();
		Semaphore first = runner.submit(null, buffer -> { }, null, () -> firstRan.set(true));
		Semaphore second = runner.submit(null, buffer -> { }, null, null);

		Semaphore merged = Semaphore.all(List.of(first, second));
		assertTrue("Completions of one runner must merge into the later one", merged == second);
		assertEquals("Merging must not force a commit",
				(double) baseline, (double) runner.getCommitCount());

		merged.waitFor();
		assertTrue("Waiting on the merged completion must cover the earlier dispatch", firstRan.get());
	}

	/**
	 * Verifies that a merged completion spanning a commit boundary still orders a dependent
	 * dispatch after its earlier member. The earlier copy is held on the GPU by a foreign gate
	 * in a committed buffer while the later member is in the open buffer, where a dependency
	 * on the later member alone is ordered by the buffer and encodes no wait. The merged
	 * completion records the earlier member's value as its prior buffer value, so the
	 * dependent copy also waits for that value and reads the gated copy's result.
	 */
	@Test(timeout = 60000)
	public void mergedCompletionAcrossBuffersOrdersDependent() throws InterruptedException {
		MetalComputeContext metal = metalContext();
		if (metal == null) {
			log("skipping, no MetalComputeContext available");
			return;
		}

		boolean aggregation = MemoryDataArgumentMap.enableArgumentAggregation;
		MemoryDataArgumentMap.enableArgumentAggregation = false;

		try {
			int n = 16;

			PackedCollection src = new PackedCollection(n);
			PackedCollection mid = new PackedCollection(n);
			PackedCollection dst = new PackedCollection(n);
			rand(src.getShape()).add(1.0).into(src.traverseEach()).evaluate();

			Submittable fill = copyKernel(src, mid, n, ComputeRequirement.MTL);
			Submittable read = copyKernel(mid, dst, n, ComputeRequirement.MTL);
			MetalCommandRunner runner = metal.getCommandRunner();

			DefaultLatchSemaphore gate = new DefaultLatchSemaphore(
					new OperationMetadata("gate", "holds the earlier buffer on the GPU"), 1);
			MetalSemaphore gated = (MetalSemaphore) fill.submit(gate);

			// A foreign dependency commits the gated buffer, so this dispatch opens a new one
			MetalSemaphore later = runner.submit(null, buffer -> { }, () -> { }, null);
			assertTrue("The later dispatch must be in a new command buffer",
					later.getCommandBuffer() != gated.getCommandBuffer());

			MetalSemaphore both = (MetalSemaphore) Semaphore.all(List.of(gated, later));
			assertEquals((double) later.getValue(), (double) both.getValue());
			assertEquals((double) gated.getValue(), (double) both.getPriorBufferValue());

			Thread opener = new Thread(() -> {
				try {
					Thread.sleep(500);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}

				gate.countDown();
			}, "SemaphoreChainBatchingTest gate");
			opener.start();

			read.submit(both).waitFor();
			opener.join(10000);

			for (int i = 0; i < n; i++) {
				assertEquals(src.toDouble(i), dst.toDouble(i));
			}
		} finally {
			MemoryDataArgumentMap.enableArgumentAggregation = aggregation;
		}
	}

	/**
	 * Verifies the non-blocking foreign-dependency bridge: submitting a Metal dispatch that
	 * depends on a {@link Semaphore} from outside the runner must return while that dependency
	 * is still outstanding (the runner encodes a GPU wait on a host-signaled event rather than
	 * blocking; see {@link MetalCommandRunner#enableHostSignaledBridges}), must not force a
	 * commit, and must produce correct results once the dependency completes. Before the
	 * bridge existed this call blocked until the foreign dependency completed, which under
	 * this test's ordering (completion arrives only after submit returns) was a deadlock —
	 * the timeout guards that regression.
	 */
	@Test(timeout = 60000)
	public void foreignDependencyBridgesWithoutHostWait() {
		MetalComputeContext metal = metalContext();
		if (metal == null) {
			log("skipping, no MetalComputeContext available");
			return;
		}

		boolean aggregation = MemoryDataArgumentMap.enableArgumentAggregation;
		MemoryDataArgumentMap.enableArgumentAggregation = false;

		try {
			int n = 16;

			PackedCollection src = new PackedCollection(n);
			PackedCollection dst = new PackedCollection(n);
			rand(src.getShape()).add(1.0).into(src.traverseEach()).evaluate();

			Submittable op = copyKernel(src, dst, n, ComputeRequirement.MTL);

			MetalCommandRunner runner = metal.getCommandRunner();
			long baseline = runner.getCommitCount();

			DefaultLatchSemaphore foreign = new DefaultLatchSemaphore(
					new OperationMetadata("foreignWork", "foreign dependency for bridge test"), 1);

			Semaphore s = op.submit(foreign);

			// The dispatch was encoded while the foreign dependency is still outstanding,
			// with no commit forced
			assertEquals((double) baseline, (double) runner.getCommitCount());

			foreign.countDown();
			s.waitFor();

			for (int i = 0; i < n; i++) {
				assertEquals(src.toDouble(i), dst.toDouble(i));
			}
		} finally {
			MemoryDataArgumentMap.enableArgumentAggregation = aggregation;
		}
	}

	/**
	 * Regression: a dispatch bridged on a foreign completion that itself waits for one of the
	 * runner's own dispatches must complete when the host waits for it, without stalling until
	 * the GPU watchdog kills its buffer.
	 *
	 * <p>The foreign dependency reaches the runner only after the host wait for the bridged
	 * dispatch has begun, which is the losing side of the race a composite completion's member
	 * waits can run. When that host wait occupied the runner's single thread until the bridged
	 * buffer completed, the foreign wait queued behind it, the bridge was never signaled, and the
	 * buffer stalled until the watchdog killed it
	 * ({@code kIOGPUCommandBufferCallbackErrorTimeout}), so the dispatches in it never ran.</p>
	 */
	@Test(timeout = 60000)
	public void bridgedDependencyOnOwnDispatchCompletes() {
		MetalComputeContext metal = metalContext();
		if (metal == null) {
			log("skipping, no MetalComputeContext available");
			return;
		}

		MetalCommandRunner runner = metal.getCommandRunner();
		long errors = runner.getErrorCompletionCount();
		long lateSignals = runner.getLateBridgeSignalCount();

		Semaphore first = runner.submit(null, buffer -> { }, null, null);
		Semaphore late = () -> {
			try {
				Thread.sleep(500);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}

			first.waitFor();
		};

		AtomicBoolean ran = new AtomicBoolean();
		Semaphore dependent = runner.submit(null, buffer -> { }, late, () -> ran.set(true));
		dependent.waitFor();

		assertTrue("The bridged dispatch's buffer must complete", ran.get());
		assertEquals("No command buffer may finish with an error",
				(double) errors, (double) runner.getErrorCompletionCount());
		assertEquals("The bridge must be signaled before its buffer completes",
				(double) lateSignals, (double) runner.getLateBridgeSignalCount());
	}

	/**
	 * Regression: destroying a runner while threads are still waiting for one of its command
	 * buffers off the runner's thread must neither fail those waits nor leak the buffer.
	 *
	 * <p>The waited buffer is held on the GPU by a foreign bridge until {@code destroy()} is
	 * already in progress, so destruction drains it while every waiter is still registered.
	 * A waiter that woke after the runner's executor had shut down could not withdraw its
	 * registration: its wait failed with "The executor has been destroyed" and the native
	 * command buffer was never released. Several waiters are used so that some of them wake
	 * after the executor has gone.</p>
	 */
	@Test(timeout = 60000)
	public void destroyWhileWaitingReleasesBuffer() throws InterruptedException {
		MetalComputeContext metal = metalContext();
		if (metal == null) {
			log("skipping, no MetalComputeContext available");
			return;
		}

		MTLCommandQueue queue = metal.getMtlDevice().newCommandQueue();
		MetalCommandRunner runner = new MetalCommandRunner(queue);
		DefaultLatchSemaphore foreign = new DefaultLatchSemaphore(
				new OperationMetadata("foreignWork", "holds the waited buffer on the GPU"), 1);

		try {
			AtomicBoolean ran = new AtomicBoolean();
			MetalSemaphore dispatch = runner.submit(null, buffer -> { }, foreign, () -> ran.set(true));

			int waiterCount = 8;
			List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
			List<Thread> waiters = new ArrayList<>();
			for (int i = 0; i < waiterCount; i++) {
				Thread waiter = new Thread(() -> {
					try {
						dispatch.waitFor();
					} catch (Throwable t) {
						failures.add(t);
					}
				});
				waiters.add(waiter);
				waiter.start();
			}

			// Every waiter registers on the runner's thread before the bridge can be signaled
			Thread.sleep(500);
			assertEquals(1.0, (double) runner.getHostCompleteCommitCount());

			Thread destroyer = new Thread(runner::destroy);
			destroyer.start();
			Thread.sleep(200);
			assertTrue("destroy() must wait for the bridged buffer", destroyer.isAlive());

			foreign.countDown();
			destroyer.join(10000);
			for (Thread waiter : waiters) {
				waiter.join(10000);
			}

			assertFalse(destroyer.isAlive());
			assertTrue("Waiters must return normally when the runner is destroyed, but got " + failures,
					failures.isEmpty());
			assertTrue("The buffer's completion callbacks must run", ran.get());
			assertEquals(0.0, (double) runner.getErrorCompletionCount());
			assertTrue("The command buffer must be released once every waiter has withdrawn",
					dispatch.getCommandBuffer().isReleased());
		} finally {
			foreign.countDown();
			runner.destroy();
			queue.release();
		}
	}

	/**
	 * Regression: destroying a runner whose last buffer is held on the GPU by a bridge must let
	 * the bridge's foreign work keep using the runner, so the bridge is signaled and destruction
	 * finishes without a watchdog kill.
	 *
	 * <p>The foreign work waits for one of the runner's own earlier dispatches, as a composite
	 * completion does, and only reaches the runner after {@code destroy()} has begun. When
	 * destruction marked the executor inactive and then waited for the bridged buffer on the
	 * executor's only thread, that wait was refused, the foreign work never completed, the bridge
	 * was never signaled, and the buffer stalled until the GPU watchdog killed it.</p>
	 */
	@Test(timeout = 60000)
	public void destroyLetsBridgedForeignWorkUseRunner() throws InterruptedException {
		MetalComputeContext metal = metalContext();
		if (metal == null) {
			log("skipping, no MetalComputeContext available");
			return;
		}

		MTLCommandQueue queue = metal.getMtlDevice().newCommandQueue();
		MetalCommandRunner runner = new MetalCommandRunner(queue);
		DefaultLatchSemaphore bridge = new DefaultLatchSemaphore(
				new OperationMetadata("compositeWork", "waits for the runner's own dispatch"), 1);

		try {
			MetalSemaphore first = runner.submit(null, buffer -> { }, null, null);
			AtomicBoolean ran = new AtomicBoolean();
			MetalSemaphore bridged = runner.submit(null, buffer -> { }, bridge, () -> ran.set(true));
			assertEquals(1.0, (double) runner.getBridgeCommitCount());

			Thread destroyer = new Thread(runner::destroy);
			destroyer.start();
			Thread.sleep(200);
			assertTrue("destroy() must wait for the bridged buffer", destroyer.isAlive());

			List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
			Thread foreignWork = new Thread(() -> {
				try {
					first.waitFor();
					bridge.countDown();
				} catch (Throwable t) {
					failures.add(t);
				}
			});
			foreignWork.start();
			foreignWork.join(10000);
			destroyer.join(10000);

			assertFalse(destroyer.isAlive());
			assertTrue("The foreign work must be able to wait on the runner during destroy(), but got "
					+ failures, failures.isEmpty());
			assertTrue("The bridged buffer's completion callbacks must run", ran.get());
			assertEquals(0.0, (double) runner.getErrorCompletionCount());
			assertEquals(0.0, (double) runner.getLateBridgeSignalCount());
			assertTrue("The bridged command buffer must be released by destroy()",
					bridged.getCommandBuffer().isReleased());
		} finally {
			bridge.countDown();
			runner.destroy();
			queue.release();
		}
	}

	/**
	 * Regression: an interrupted {@code destroy()} must still wait for a bridged buffer off the
	 * runner's thread, so the bridge's foreign work can keep using the runner, and must leave the
	 * caller's interrupt status set.
	 *
	 * <p>When an interrupted caller skipped that wait, the final drain waited for the bridged
	 * buffer on the runner's only thread after the executor had stopped accepting work, so the
	 * foreign work's wait on the runner was refused and the bridge was never signaled.</p>
	 */
	@Test(timeout = 60000)
	public void interruptedDestroyLetsBridgedForeignWorkUseRunner() throws InterruptedException {
		MetalComputeContext metal = metalContext();
		if (metal == null) {
			log("skipping, no MetalComputeContext available");
			return;
		}

		MTLCommandQueue queue = metal.getMtlDevice().newCommandQueue();
		MetalCommandRunner runner = new MetalCommandRunner(queue);
		DefaultLatchSemaphore bridge = new DefaultLatchSemaphore(
				new OperationMetadata("compositeWork", "waits for the runner's own dispatch"), 1);

		try {
			MetalSemaphore first = runner.submit(null, buffer -> { }, null, null);
			AtomicBoolean ran = new AtomicBoolean();
			MetalSemaphore bridged = runner.submit(null, buffer -> { }, bridge, () -> ran.set(true));

			AtomicBoolean interruptedAfter = new AtomicBoolean();
			Thread destroyer = new Thread(() -> {
				Thread.currentThread().interrupt();
				runner.destroy();
				interruptedAfter.set(Thread.currentThread().isInterrupted());
			});
			destroyer.start();
			Thread.sleep(200);
			assertTrue("An interrupted destroy() must still wait for the bridged buffer", destroyer.isAlive());

			List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
			Thread foreignWork = new Thread(() -> {
				try {
					first.waitFor();
					bridge.countDown();
				} catch (Throwable t) {
					failures.add(t);
				}
			});
			foreignWork.start();
			foreignWork.join(10000);
			destroyer.join(10000);

			assertFalse(destroyer.isAlive());
			assertTrue("The foreign work must be able to wait on the runner during an interrupted "
					+ "destroy(), but got " + failures, failures.isEmpty());
			assertTrue("The bridged buffer's completion callbacks must run", ran.get());
			assertTrue("destroy() must restore the caller's interrupt status", interruptedAfter.get());
			assertEquals(0.0, (double) runner.getErrorCompletionCount());
			assertEquals(0.0, (double) runner.getLateBridgeSignalCount());
			assertTrue("The bridged command buffer must be released by destroy()",
					bridged.getCommandBuffer().isReleased());
		} finally {
			bridge.countDown();
			runner.destroy();
			queue.release();
		}
	}

	/**
	 * Regression: a bridged dispatch submitted while {@code destroy()} is waiting for an earlier
	 * buffer must also be waited for off the runner's thread, so its bridge's foreign work can
	 * still use the runner.
	 *
	 * <p>When destruction waited only for the buffer that was last committed when it began, the
	 * final drain committed the later bridged buffer and waited for it on the runner's only
	 * thread after the executor had stopped accepting work, so the foreign work's wait on the
	 * runner was refused and the bridge was never signaled.</p>
	 */
	@Test(timeout = 60000)
	public void destroyWaitsOffThreadForWorkSubmittedDuringWait() throws InterruptedException {
		MetalComputeContext metal = metalContext();
		if (metal == null) {
			log("skipping, no MetalComputeContext available");
			return;
		}

		MTLCommandQueue queue = metal.getMtlDevice().newCommandQueue();
		MetalCommandRunner runner = new MetalCommandRunner(queue);
		DefaultLatchSemaphore hold = new DefaultLatchSemaphore(
				new OperationMetadata("holdingWork", "holds the first waited buffer on the GPU"), 1);
		DefaultLatchSemaphore lateBridge = new DefaultLatchSemaphore(
				new OperationMetadata("compositeWork", "waits for the runner's own dispatch"), 1);

		try {
			MetalSemaphore first = runner.submit(null, buffer -> { }, null, null);
			AtomicBoolean ranHeld = new AtomicBoolean();
			runner.submit(null, buffer -> { }, hold, () -> ranHeld.set(true));

			Thread destroyer = new Thread(runner::destroy);
			destroyer.start();
			Thread.sleep(200);
			assertTrue("destroy() must wait for the held buffer", destroyer.isAlive());

			AtomicBoolean ranLate = new AtomicBoolean();
			MetalSemaphore late = runner.submit(null, buffer -> { }, lateBridge, () -> ranLate.set(true));
			hold.countDown();
			Thread.sleep(200);
			assertTrue("destroy() must wait for the buffer submitted during its wait", destroyer.isAlive());

			List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
			Thread foreignWork = new Thread(() -> {
				try {
					first.waitFor();
					lateBridge.countDown();
				} catch (Throwable t) {
					failures.add(t);
				}
			});
			foreignWork.start();
			foreignWork.join(10000);
			destroyer.join(10000);

			assertFalse(destroyer.isAlive());
			assertTrue("The foreign work must be able to wait on the runner during destroy(), but got "
					+ failures, failures.isEmpty());
			assertTrue("The held buffer's completion callbacks must run", ranHeld.get());
			assertTrue("The late bridged buffer's completion callbacks must run", ranLate.get());
			assertEquals(0.0, (double) runner.getErrorCompletionCount());
			assertEquals(0.0, (double) runner.getLateBridgeSignalCount());
			assertTrue("The late bridged command buffer must be released by destroy()",
					late.getCommandBuffer().isReleased());
		} finally {
			hold.countDown();
			lateBridge.countDown();
			runner.destroy();
			queue.release();
		}
	}

	/**
	 * Regression: an interrupt that reaches {@code destroy()} while it waits for a held buffer
	 * off the runner's thread must not cut short the rounds that follow, so a bridged dispatch
	 * submitted during that wait is still waited for off the runner's thread and its foreign
	 * work can keep using the runner; the interrupt is restored when destruction returns.
	 *
	 * <p>Only an interrupt pending on entry used to be cleared. One arriving during the GPU wait
	 * made the next round's commit task return before it ran, so the round saw no committed
	 * buffer, stopped the executor, and left the final task waiting for the late bridged buffer
	 * on the runner's only thread, where the foreign work's wait on the runner was refused.</p>
	 */
	@Test(timeout = 60000)
	public void destroyInterruptedDuringWaitStillWaitsOffThread() throws InterruptedException {
		MetalComputeContext metal = metalContext();
		if (metal == null) {
			log("skipping, no MetalComputeContext available");
			return;
		}

		MTLCommandQueue queue = metal.getMtlDevice().newCommandQueue();
		MetalCommandRunner runner = new MetalCommandRunner(queue);
		DefaultLatchSemaphore hold = new DefaultLatchSemaphore(
				new OperationMetadata("holdingWork", "holds the first waited buffer on the GPU"), 1);
		DefaultLatchSemaphore lateBridge = new DefaultLatchSemaphore(
				new OperationMetadata("compositeWork", "waits for the runner's own dispatch"), 1);

		try {
			MetalSemaphore first = runner.submit(null, buffer -> { }, null, null);
			AtomicBoolean ranHeld = new AtomicBoolean();
			runner.submit(null, buffer -> { }, hold, () -> ranHeld.set(true));

			AtomicBoolean interruptedAfter = new AtomicBoolean();
			Thread destroyer = new Thread(() -> {
				runner.destroy();
				interruptedAfter.set(Thread.currentThread().isInterrupted());
			});
			destroyer.start();
			Thread.sleep(200);
			assertTrue("destroy() must wait for the held buffer", destroyer.isAlive());

			AtomicBoolean ranLate = new AtomicBoolean();
			MetalSemaphore late = runner.submit(null, buffer -> { }, lateBridge, () -> ranLate.set(true));
			destroyer.interrupt();
			hold.countDown();
			Thread.sleep(200);
			assertTrue("An interrupted destroy() must still wait for the buffer submitted during its wait",
					destroyer.isAlive());

			List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
			Thread foreignWork = new Thread(() -> {
				try {
					first.waitFor();
					lateBridge.countDown();
				} catch (Throwable t) {
					failures.add(t);
				}
			});
			foreignWork.start();
			foreignWork.join(10000);
			destroyer.join(10000);

			assertFalse(destroyer.isAlive());
			assertTrue("The foreign work must be able to wait on the runner during an interrupted "
					+ "destroy(), but got " + failures, failures.isEmpty());
			assertTrue("The held buffer's completion callbacks must run", ranHeld.get());
			assertTrue("The late bridged buffer's completion callbacks must run", ranLate.get());
			assertTrue("destroy() must restore an interrupt received during destruction", interruptedAfter.get());
			assertEquals(0.0, (double) runner.getErrorCompletionCount());
			assertEquals(0.0, (double) runner.getLateBridgeSignalCount());
			assertTrue("The late bridged command buffer must be released by destroy()",
					late.getCommandBuffer().isReleased());
		} finally {
			hold.countDown();
			lateBridge.countDown();
			runner.destroy();
			queue.release();
		}
	}

	/**
	 * A completion callback running on the runner's own thread cannot submit work to the runner:
	 * the submission is rejected with an {@link IllegalStateException} rather than waiting for a
	 * task queued behind the callback, and the runner keeps working afterwards.
	 */
	@Test(timeout = 60000)
	public void submitFromCompletionCallbackIsRejected() {
		MetalComputeContext metal = metalContext();
		if (metal == null) {
			log("skipping, no MetalComputeContext available");
			return;
		}

		MTLCommandQueue queue = metal.getMtlDevice().newCommandQueue();
		MetalCommandRunner runner = new MetalCommandRunner(queue);

		try {
			AtomicReference<Throwable> rejection = new AtomicReference<>();
			MetalSemaphore dispatch = runner.submit(null, buffer -> { }, null, null);
			dispatch.whenComplete(() -> {
				try {
					runner.submit(null, buffer -> { }, null, null);
				} catch (Throwable t) {
					rejection.set(t);
				}
			});
			dispatch.waitFor();

			assertTrue("A submission from the runner's thread must be rejected, but got " + rejection.get(),
					rejection.get() instanceof IllegalStateException);

			AtomicBoolean ran = new AtomicBoolean();
			runner.submit(null, buffer -> { }, null, () -> ran.set(true)).waitFor();
			assertTrue("The runner must keep working after rejecting the submission", ran.get());
		} finally {
			runner.destroy();
			queue.release();
		}
	}

	/**
	 * Verifies commit-cause attribution: a host wait that forces a commit increments
	 * {@link MetalCommandRunner#getHostCompleteCommitCount()} and records the requesting
	 * operation in {@link MetalCommandRunner#hostCompleteRequesters}, while a repeated wait
	 * on the same (already completed) dispatch is free and attributes nothing.
	 */
	@Test(timeout = 60000)
	public void hostCompleteCommitAttribution() {
		MetalComputeContext metal = metalContext();
		if (metal == null) {
			log("skipping, no MetalComputeContext available");
			return;
		}

		boolean aggregation = MemoryDataArgumentMap.enableArgumentAggregation;
		MemoryDataArgumentMap.enableArgumentAggregation = false;

		try {
			int n = 16;

			PackedCollection src = new PackedCollection(n);
			PackedCollection dst = new PackedCollection(n);
			rand(src.getShape()).add(1.0).into(src.traverseEach()).evaluate();

			Submittable op = copyKernel(src, dst, n, ComputeRequirement.MTL);

			MetalCommandRunner runner = metal.getCommandRunner();
			long baseTotal = runner.getCommitCount();
			long baseHost = runner.getHostCompleteCommitCount();
			int baseAttributed = attributedWaits();

			Semaphore s = op.submit(null);
			assertEquals((double) baseHost, (double) runner.getHostCompleteCommitCount());

			s.waitFor();
			assertEquals((double) (baseTotal + 1), (double) runner.getCommitCount());
			assertEquals((double) (baseHost + 1), (double) runner.getHostCompleteCommitCount());
			assertEquals((double) (baseAttributed + 1), (double) attributedWaits());

			s.waitFor();
			assertEquals((double) (baseHost + 1), (double) runner.getHostCompleteCommitCount());
			assertEquals((double) (baseAttributed + 1), (double) attributedWaits());
		} finally {
			MemoryDataArgumentMap.enableArgumentAggregation = aggregation;
		}
	}

	/**
	 * Verifies the asynchronous argument delivery contract: when a streaming producer's
	 * downstream is a {@link CompletionConsumer}, {@link StreamingEvaluable#request} delivers
	 * the destination together with the dispatch's completion {@link Semaphore} without any
	 * host wait — so issuing the request performs no command-buffer commit — and the contents
	 * are valid once the delivered completion is waited.
	 */
	@Test(timeout = 60000)
	public void completionConsumerDeliveryAvoidsCommit() {
		MetalComputeContext metal = metalContext();
		if (metal == null) {
			log("skipping, no MetalComputeContext available");
			return;
		}

		int n = 16;

		PackedCollection a = new PackedCollection(n);
		rand(a.getShape()).add(1.0).into(a.traverseEach()).evaluate();

		Evaluable<PackedCollection> ev;
		Hardware.getLocalHardware().getComputer().pushRequirements(List.of(ComputeRequirement.MTL));

		try {
			ev = (Evaluable<PackedCollection>) (Evaluable) cp(a).multiply(2.0).get();
		} finally {
			Hardware.getLocalHardware().getComputer().popRequirements();
		}

		PackedCollection destination = new PackedCollection(n);
		StreamingEvaluable<PackedCollection> streaming =
				(StreamingEvaluable<PackedCollection>) ev.into(destination);

		Object[] delivered = new Object[1];
		Semaphore[] completion = new Semaphore[1];
		streaming.setDownstream((CompletionConsumer<PackedCollection>) (value, c) -> {
			delivered[0] = value;
			completion[0] = c;
		});

		MetalCommandRunner runner = metal.getCommandRunner();
		long baseline = runner.getCommitCount();

		streaming.request(new Object[0]);

		assertEquals((double) baseline, (double) runner.getCommitCount());
		assertTrue(delivered[0] instanceof PackedCollection);
		assertTrue(completion[0] != null);

		completion[0].waitFor();

		PackedCollection result = (PackedCollection) delivered[0];

		for (int i = 0; i < n; i++) {
			assertEquals(2.0 * a.toDouble(i), result.toDouble(i));
		}
	}

	/**
	 * Verifies the {@link HardwareEvaluable#setResultProcessor(java.util.function.UnaryOperator)
	 * result processor} contract that {@link org.almostrealism.collect.computations.PackedCollectionRepeat}
	 * and {@link org.almostrealism.collect.computations.ReshapeProducer} rely on: wrapping a
	 * kernel-backed evaluable and requesting it through the
	 * {@link HardwareEvaluable#request(Object[], Semaphore, java.util.function.Consumer)} overload
	 * must deliver the processed value together with the dispatch's completion, without forcing a
	 * host wait (no command-buffer commit), and the processed value must match what the processor
	 * would produce from a direct, synchronous evaluation.
	 */
	@Test(timeout = 60000)
	public void resultProcessorDeliveryAvoidsCommit() {
		MetalComputeContext metal = metalContext();
		if (metal == null) {
			log("skipping, no MetalComputeContext available");
			return;
		}

		int n = 16;

		PackedCollection a = new PackedCollection(n);
		rand(a.getShape()).add(1.0).into(a.traverseEach()).evaluate();

		Evaluable<PackedCollection> ev;
		Hardware.getLocalHardware().getComputer().pushRequirements(List.of(ComputeRequirement.MTL));

		try {
			ev = (Evaluable<PackedCollection>) (Evaluable) cp(a).multiply(2.0).get();
		} finally {
			Hardware.getLocalHardware().getComputer().popRequirements();
		}

		HardwareEvaluable<PackedCollection> wrapper = new HardwareEvaluable<>(() -> ev, null, null, false);
		wrapper.setResultProcessor(out -> out.repeat(2));

		Object[] delivered = new Object[1];
		Semaphore[] completion = new Semaphore[1];

		MetalCommandRunner runner = metal.getCommandRunner();
		long baseline = runner.getCommitCount();

		wrapper.request(new Object[0], null, (CompletionConsumer<PackedCollection>) (value, c) -> {
			delivered[0] = value;
			completion[0] = c;
		});

		assertEquals((double) baseline, (double) runner.getCommitCount());
		assertTrue(delivered[0] instanceof PackedCollection);
		assertTrue(completion[0] != null);

		completion[0].waitFor();

		PackedCollection direct = ev.evaluate(new Object[0]);
		PackedCollection expected = direct.repeat(2);
		PackedCollection result = (PackedCollection) delivered[0];

		assertEquals((double) expected.getMemLength(), (double) result.getMemLength());
		for (int i = 0; i < expected.getMemLength(); i++) {
			assertEquals(expected.toDouble(i), result.toDouble(i));
		}
	}

	/**
	 * Verifies that {@link HardwareEvaluable#withDestination(org.almostrealism.hardware.MemoryBank)}
	 * (reached through {@link HardwareEvaluable#into(Object)}) carries the
	 * {@link HardwareEvaluable#setResultProcessor(java.util.function.UnaryOperator) result processor}
	 * forward instead of discarding it: the underlying kernel writes its unprocessed result into
	 * the destination, and the value returned from {@code evaluate()} on the destination-bound
	 * evaluable must be the processed re-view of that destination, matching a direct evaluation
	 * followed by the same processor.
	 */
	@Test(timeout = 30000)
	public void withDestinationAppliesResultProcessor() {
		int n = 8;

		PackedCollection a = new PackedCollection(n);
		rand(a.getShape()).add(1.0).into(a.traverseEach()).evaluate();

		Evaluable<PackedCollection> ev = (Evaluable<PackedCollection>) (Evaluable) cp(a).multiply(2.0).get();

		HardwareEvaluable<PackedCollection> wrapper = new HardwareEvaluable<>(() -> ev, null, null, false);
		wrapper.setResultProcessor(out -> out.repeat(2));

		PackedCollection destination = new PackedCollection(n);
		Evaluable<PackedCollection> withDestination = wrapper.into(destination);
		PackedCollection result = withDestination.evaluate();

		PackedCollection direct = ev.evaluate();
		PackedCollection expected = direct.repeat(2);

		assertEquals((double) expected.getMemLength(), (double) result.getMemLength());
		for (int i = 0; i < expected.getMemLength(); i++) {
			assertEquals(expected.toDouble(i), result.toDouble(i));
		}
	}

	/**
	 * Verifies that a shared {@link DestinationEvaluable} kernel reached through two independent
	 * {@link HardwareEvaluable} wrappers -- exactly as two separate
	 * {@link org.almostrealism.collect.computations.ReshapeProducer}/
	 * {@link org.almostrealism.collect.computations.PackedCollectionRepeat} instances would reach
	 * a shared underlying kernel -- serves both wrappers'
	 * {@link HardwareEvaluable#request(Object[], Semaphore, Consumer)} calls without either
	 * throwing from {@link HardwareEvaluable#setDownstream}, and delivers each wrapper's own
	 * processed result to its own consumer.
	 */
	@Test(timeout = 30000)
	public void sharedDestinationEvaluableServesIndependentWrappers() {
		int n = 8;

		PackedCollection a = new PackedCollection(n);
		rand(a.getShape()).add(1.0).into(a.traverseEach()).evaluate();

		Evaluable<PackedCollection> kernel = (Evaluable<PackedCollection>) (Evaluable) cp(a).multiply(2.0).get();
		PackedCollection destination = new PackedCollection(n);
		Evaluable<PackedCollection> rawKernel = ((HardwareEvaluable<PackedCollection>) kernel).getKernel().getValue();
		DestinationEvaluable<PackedCollection> shared = new DestinationEvaluable<>(rawKernel, destination);

		HardwareEvaluable<PackedCollection> repeatWrapper = new HardwareEvaluable<>(() -> shared, null, null, false);
		repeatWrapper.setResultProcessor(out -> out.repeat(2));

		HardwareEvaluable<PackedCollection> reshapeWrapper = new HardwareEvaluable<>(() -> shared, null, null, false);
		reshapeWrapper.setResultProcessor(out -> out.reshape(1, n));

		PackedCollection direct = kernel.evaluate();
		PackedCollection expectedRepeat = direct.repeat(2);
		PackedCollection expectedReshape = direct.reshape(1, n);

		Object[] repeatResult = new Object[1];
		Semaphore[] repeatCompletion = new Semaphore[1];
		repeatWrapper.request(new Object[0], null, (CompletionConsumer<PackedCollection>) (value, c) -> {
			repeatResult[0] = value;
			repeatCompletion[0] = c;
		});
		if (repeatCompletion[0] != null) repeatCompletion[0].waitFor();

		Object[] reshapeResult = new Object[1];
		Semaphore[] reshapeCompletion = new Semaphore[1];
		reshapeWrapper.request(new Object[0], null, (CompletionConsumer<PackedCollection>) (value, c) -> {
			reshapeResult[0] = value;
			reshapeCompletion[0] = c;
		});
		if (reshapeCompletion[0] != null) reshapeCompletion[0].waitFor();

		PackedCollection actualRepeat = (PackedCollection) repeatResult[0];
		PackedCollection actualReshape = (PackedCollection) reshapeResult[0];

		assertEquals((double) expectedRepeat.getMemLength(), (double) actualRepeat.getMemLength());
		for (int i = 0; i < expectedRepeat.getMemLength(); i++) {
			assertEquals(expectedRepeat.toDouble(i), actualRepeat.toDouble(i));
		}

		for (int i = 0; i < n; i++) {
			assertEquals(expectedReshape.toDouble(i), actualReshape.toDouble(i));
		}
	}

	/**
	 * Verifies that {@link EvaluableStreamingAdapter#request(Object[], Semaphore, Consumer)} honors
	 * a non-null {@code dependsOn}: submission to the executor must not block the caller, but the
	 * submitted task must wait for {@code dependsOn} to complete before reading the arguments and
	 * evaluating, rather than reading them immediately and racing the dependency.
	 *
	 * <p>{@code dependsOn} is a {@link DefaultLatchSemaphore} subclass whose {@link
	 * DefaultLatchSemaphore#waitFor() waitFor()} counts down {@code waitEntered} the instant it is
	 * called, before blocking &mdash; so the test waits on that latch to prove the requester thread
	 * actually reached the wait, instead of racing a fixed delay against it. The state update and
	 * the dependency's release both happen only after that proof, so an implementation that ignored
	 * {@code dependsOn} and read {@code sharedState} immediately could not pass by scheduling luck.</p>
	 */
	@Test(timeout = 10000)
	public void streamingAdapterRequestWaitsForDependsOn() throws InterruptedException {
		int[] sharedState = { 0 };

		Evaluable<Integer> hostEvaluable = args -> sharedState[0];
		EvaluableStreamingAdapter<Integer> adapter = new EvaluableStreamingAdapter<>(hostEvaluable);

		CountDownLatch waitEntered = new CountDownLatch(1);
		DefaultLatchSemaphore dependsOn = new DefaultLatchSemaphore((OperationMetadata) null, 1) {
			@Override
			public void waitFor() {
				waitEntered.countDown();
				super.waitFor();
			}
		};
		CountDownLatch delivered = new CountDownLatch(1);
		Integer[] result = new Integer[1];

		Thread requester = new Thread(() ->
				adapter.request(new Object[0], dependsOn, (Consumer<Integer>) value -> {
					result[0] = value;
					delivered.countDown();
				}));
		requester.start();

		assertTrue(waitEntered.await(5, TimeUnit.SECONDS));
		assertEquals(1L, delivered.getCount());

		sharedState[0] = 42;
		dependsOn.countDown();

		assertTrue(delivered.await(5, TimeUnit.SECONDS));
		requester.join(5000);
		assertEquals(42, (int) result[0]);
	}

	/**
	 * Verifies that a single {@link EvaluableStreamingAdapter} reached through two independent
	 * requesters -- each calling {@link EvaluableStreamingAdapter#request(Object[], Semaphore,
	 * Consumer)} with its own downstream consumer, as two separate {@link HardwareEvaluable}
	 * wrappers over a shared host-evaluated kernel would -- delivers each request's result to its
	 * own consumer without either contending for {@link EvaluableStreamingAdapter#setDownstream}.
	 */
	@Test(timeout = 10000)
	public void sharedStreamingAdapterServesIndependentRequesters() throws InterruptedException {
		Evaluable<Integer> hostEvaluable = args -> ((Integer) args[0]) * 2;
		EvaluableStreamingAdapter<Integer> adapter = new EvaluableStreamingAdapter<>(hostEvaluable);

		CountDownLatch delivered = new CountDownLatch(2);
		Integer[] firstResult = new Integer[1];
		Integer[] secondResult = new Integer[1];

		adapter.request(new Object[] { 3 }, null, (Consumer<Integer>) value -> {
			firstResult[0] = value;
			delivered.countDown();
		});
		adapter.request(new Object[] { 5 }, null, (Consumer<Integer>) value -> {
			secondResult[0] = value;
			delivered.countDown();
		});

		assertTrue(delivered.await(5, TimeUnit.SECONDS));
		assertEquals(6, (int) firstResult[0]);
		assertEquals(10, (int) secondResult[0]);
	}

	/**
	 * Returns the total number of commit-forcing host waits recorded in
	 * {@link MetalCommandRunner#hostCompleteRequesters} across all requesters.
	 */
	private static int attributedWaits() {
		return MetalCommandRunner.hostCompleteRequesters.getCounts()
				.values().stream().mapToInt(Integer::intValue).sum();
	}

	/**
	 * Runs an {@link OperationListRunner} whose first member is a Metal copy kernel and whose
	 * second member is a CPU copy kernel reading the first member's output. Without the pending
	 * semaphore threaded between members, the synchronous native consumer reads the Metal
	 * producer's destination before its command buffer commits and sees stale zeros; with the
	 * chain in place the result matches the source.
	 */
	@Test(timeout = 60000)
	public void mixedContextListOrdering() {
		if (metalContext() == null) {
			log("skipping, no MetalComputeContext available");
			return;
		}

		int n = 16;

		PackedCollection src = new PackedCollection(n);
		PackedCollection temp = new PackedCollection(n);
		PackedCollection dst = new PackedCollection(n);
		rand(src.getShape()).add(1.0).into(src.traverseEach()).evaluate();

		Runnable op1 = (Runnable) copyKernel(src, temp, n, ComputeRequirement.MTL);
		Runnable op2 = (Runnable) copyKernel(temp, dst, n, ComputeRequirement.CPU);

		OperationListRunner runner = new OperationListRunner(
				new OperationMetadata("mixedContextChain", "mixed-context semaphore chain"),
				List.of(op1, op2), null, null);
		runner.run();

		for (int i = 0; i < n; i++) {
			assertEquals(src.toDouble(i), dst.toDouble(i));
		}
	}

	/**
	 * Verifies that an {@link EvaluableStreamingAdapter} wrapping an evaluable that is itself a
	 * {@link StreamingEvaluable} forwards the request -- arguments, dependency and downstream --
	 * to that evaluable's own {@link StreamingEvaluable#request(Object[], Semaphore, Consumer)}
	 * rather than calling its blocking {@link Evaluable#evaluate(Object...)}. This is what lets
	 * a compiled kernel reached through {@code Evaluable.async()} keep delivering its result with
	 * its completion instead of forcing a host wait on the executor's thread.
	 */
	@Test(timeout = 10000)
	public void streamingAdapterForwardsToStreamingEvaluable() {
		AtomicBoolean evaluated = new AtomicBoolean();
		AtomicReference<Semaphore> receivedDependsOn = new AtomicReference<>();
		Semaphore dependsOn = new DefaultLatchSemaphore((OperationMetadata) null, 0);

		/** A host evaluable that can also stream, recording which of the two paths the adapter takes. */
		class StreamingHost implements Evaluable<Integer>, StreamingEvaluable<Integer> {
			@Override
			public Integer evaluate(Object... args) {
				evaluated.set(true);
				return -1;
			}

			@Override
			public void request(Object[] args, Semaphore dependency) {
				throw new UnsupportedOperationException();
			}

			@Override
			public void request(Object[] args, Semaphore dependency, Consumer<Integer> downstream) {
				receivedDependsOn.set(dependency);
				downstream.accept(7);
			}

			@Override
			public void setDownstream(Consumer<Integer> consumer) {
				throw new UnsupportedOperationException();
			}
		}

		EvaluableStreamingAdapter<Integer> adapter = new EvaluableStreamingAdapter<>(new StreamingHost());
		Integer[] result = new Integer[1];
		adapter.request(new Object[0], dependsOn, (Consumer<Integer>) value -> result[0] = value);

		assertEquals(7, (int) result[0]);
		assertTrue(dependsOn == receivedDependsOn.get());
		assertFalse(evaluated.get());
	}

	/**
	 * Verifies that {@link EvaluableStreamingAdapter#isDispatchBacked()} reports the wrapped
	 * {@link StreamingEvaluable}'s own capability instead of unconditionally claiming {@code true}.
	 * The adapter forwards {@code dependsOn} to the wrapped evaluable's own request, so whether that
	 * dependency is actually chained -- rather than discarded -- is a fact about the wrapped
	 * implementation. Reporting {@code true} regardless would let {@code ProcessDetailsFactory} treat
	 * the adapter as dependency-safe even when the wrapped implementation is not, and start it against
	 * memory a preceding dispatch has not finished writing. Wrapping a plain synchronous evaluable is
	 * unaffected: the adapter itself waits for the dependency in that case, so it remains {@code true}.
	 */
	@Test(timeout = 10000)
	public void streamingAdapterReportsWrappedDispatchBackedCapability() {
		/** A streaming host whose {@code isDispatchBacked()} reports {@code false}. */
		class NotDispatchBackedStreamingHost implements Evaluable<Integer>, StreamingEvaluable<Integer> {
			@Override
			public Integer evaluate(Object... args) {
				return -1;
			}

			@Override
			public void request(Object[] args, Semaphore dependency) {
				request(args, dependency, null);
			}

			@Override
			public void request(Object[] args, Semaphore dependency, Consumer<Integer> downstream) {
				downstream.accept(7);
			}

			@Override
			public void setDownstream(Consumer<Integer> consumer) {
				throw new UnsupportedOperationException();
			}

			@Override
			public boolean isDispatchBacked() {
				return false;
			}
		}

		EvaluableStreamingAdapter<Integer> notDispatchBacked =
				new EvaluableStreamingAdapter<>(new NotDispatchBackedStreamingHost());
		assertFalse(notDispatchBacked.isDispatchBacked());

		/** The same host, but with {@code isDispatchBacked()} reporting {@code true}. */
		class DispatchBackedStreamingHost extends NotDispatchBackedStreamingHost {
			@Override
			public boolean isDispatchBacked() {
				return true;
			}
		}

		EvaluableStreamingAdapter<Integer> dispatchBacked =
				new EvaluableStreamingAdapter<>(new DispatchBackedStreamingHost());
		assertTrue(dispatchBacked.isDispatchBacked());

		Evaluable<Integer> hostOnly = args -> -1;
		EvaluableStreamingAdapter<Integer> wrappingPlainEvaluable = new EvaluableStreamingAdapter<>(hostOnly);
		assertTrue(wrappingPlainEvaluable.isDispatchBacked());
	}

	/**
	 * Verifies that a {@link HardwareEvaluable} short-circuit request with an outstanding
	 * {@code dependsOn} returns to the requester without waiting for it: the host evaluation is
	 * ordered after the completion via {@link Semaphore#onComplete(Semaphore, Runnable)} and
	 * delivered once the dependency fires, so {@code request} honors the non-blocking contract
	 * of {@link StreamingEvaluable#request(Object[], Semaphore, Consumer)} even on a path that
	 * cannot chain the dependency into a dispatch.
	 */
	@Test(timeout = 10000)
	public void hardwareEvaluableShortCircuitRequestDoesNotBlockOnDependsOn() throws InterruptedException {
		int[] sharedState = { 0 };
		Evaluable<Integer> shortCircuit = args -> sharedState[0];
		HardwareEvaluable<Integer> evaluable = new HardwareEvaluable<>(
				() -> { throw new UnsupportedOperationException("kernel should not be reached"); },
				null, shortCircuit, false);

		CountDownLatch waitEntered = new CountDownLatch(1);
		DefaultLatchSemaphore dependsOn = new DefaultLatchSemaphore((OperationMetadata) null, 1) {
			@Override
			public void waitFor() {
				waitEntered.countDown();
				super.waitFor();
			}
		};
		CountDownLatch delivered = new CountDownLatch(1);
		Integer[] result = new Integer[1];

		evaluable.request(new Object[0], dependsOn, value -> {
			result[0] = value;
			delivered.countDown();
		});

		// The request has returned while the dependency is still outstanding
		assertTrue(waitEntered.await(5, TimeUnit.SECONDS));
		assertEquals(1L, delivered.getCount());

		sharedState[0] = 42;
		dependsOn.countDown();

		assertTrue(delivered.await(5, TimeUnit.SECONDS));
		assertEquals(42, (int) result[0]);
	}

	/**
	 * Verifies the same non-blocking ordering for a {@link DestinationEvaluable} whose operation
	 * is a plain host {@link Evaluable} rather than an accelerated kernel: with a {@code dependsOn}
	 * outstanding, {@code request} returns at once and the element-wise host evaluation into the
	 * destination runs only after the dependency completes.
	 */
	@Test(timeout = 10000)
	public void destinationEvaluableHostRequestDoesNotBlockOnDependsOn() throws InterruptedException {
		PackedCollection element = new PackedCollection(1);
		PackedCollection destination = new PackedCollection(1);
		Evaluable<PackedCollection> operation = args -> element;
		DestinationEvaluable<PackedCollection> evaluable = new DestinationEvaluable<>(operation, destination);

		CountDownLatch waitEntered = new CountDownLatch(1);
		DefaultLatchSemaphore dependsOn = new DefaultLatchSemaphore((OperationMetadata) null, 1) {
			@Override
			public void waitFor() {
				waitEntered.countDown();
				super.waitFor();
			}
		};
		CountDownLatch delivered = new CountDownLatch(1);
		PackedCollection[] result = new PackedCollection[1];

		evaluable.request(new Object[0], dependsOn, value -> {
			result[0] = value;
			delivered.countDown();
		});

		assertTrue(waitEntered.await(5, TimeUnit.SECONDS));
		assertEquals(1L, delivered.getCount());

		element.setMem(0, 42.0);
		dependsOn.countDown();

		assertTrue(delivered.await(5, TimeUnit.SECONDS));
		assertTrue(destination == result[0]);
		assertEquals(42.0, destination.toDouble(0));
	}

	/**
	 * Returns the shared {@link MetalComputeContext}, or null when the current hardware
	 * configuration exposes no Metal backend (in which case these tests are skipped).
	 */
	static MetalComputeContext metalContext() {
		try {
			return Hardware.getLocalHardware()
					.getComputeContexts(false, true, ComputeRequirement.MTL).stream()
					.filter(MetalComputeContext.class::isInstance)
					.map(MetalComputeContext.class::cast)
					.findFirst().orElse(null);
		} catch (RuntimeException e) {
			return null;
		}
	}

	/**
	 * Builds a compiled, {@link Submittable} copy kernel assigning {@code from} into {@code to},
	 * pinned to the backend selected by {@code requirement}. The operands are wrapped as plain
	 * lambda {@link io.almostrealism.relation.Producer}s so {@link Assignment#get()} compiles a
	 * genuine kernel rather than short-circuiting to a direct memory copy, and the requirement
	 * is active during compilation so the kernel's {@link io.almostrealism.code.ComputeContext}
	 * is chosen deterministically.
	 *
	 * @param from        source buffer to copy from
	 * @param to          destination buffer to copy into
	 * @param len         number of elements to copy
	 * @param requirement backend the kernel must compile for
	 * @return the compiled, submittable copy kernel
	 */
	static Submittable copyKernel(PackedCollection from, PackedCollection to, int len,
								  ComputeRequirement requirement) {
		Assignment<MemoryData> assign = new Assignment<>(len,
				() -> (Evaluable<MemoryData>) args -> to,
				() -> (Evaluable<MemoryData>) args -> from,
				List.of(requirement));

		Hardware.getLocalHardware().getComputer().pushRequirements(List.of(requirement));

		try {
			return (Submittable) assign.get();
		} finally {
			Hardware.getLocalHardware().getComputer().popRequirements();
		}
	}
}
