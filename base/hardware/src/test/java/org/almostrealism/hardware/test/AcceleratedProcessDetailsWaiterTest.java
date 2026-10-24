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

package org.almostrealism.hardware.test;

import io.almostrealism.concurrent.DefaultLatchSemaphore;
import io.almostrealism.profile.OperationMetadata;
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.mem.AcceleratedProcessDetails;
import org.almostrealism.hardware.mem.MemoryReplacementManager;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Verifies that a thread waiting in {@link AcceleratedProcessDetails#awaitReady()} issues a ready
 * dispatch itself instead of parking until the listener executor gets to it.
 *
 * <p>Every caller of {@code AcceleratedOperation.apply} waits in {@code awaitReady()} straight
 * away. When the {@link AcceleratedProcessDetails#whenReady(Runnable) whenReady} listener (which
 * issues the dispatch) is only handed to an executor, each dispatch costs two cross-thread
 * hand-offs: one to wake an executor thread and one to wake the waiter again. These tests give the
 * details an executor that holds its tasks until the test runs them, so a waiter that does not run
 * the listener itself never returns.</p>
 *
 * <p>Each expectation also holds when hardware is configured synchronous, where
 * {@code whenReady} and {@code result} run the listener on the calling thread before any waiter
 * arrives.</p>
 *
 * <p>Unlike most tests in this project, this one does not extend {@code TestSuiteBase}:
 * that class lives in the engine layer, which sits above this module.</p>
 */
public class AcceleratedProcessDetailsWaiterTest {

	/**
	 * Creates process details over {@code args}, whose arguments are passed through unreplaced,
	 * with a readiness latch, as {@code AcceleratedOperation.apply} does.
	 *
	 * @param args     the original arguments, {@code null} where an argument is still to come
	 * @param executor the executor the details hand ready listeners to
	 * @return the process details
	 */
	private static AcceleratedProcessDetails details(Object[] args, Executor executor) {
		AcceleratedProcessDetails details = new AcceleratedProcessDetails(
				args, 0, new MemoryReplacementManager(null, null, null), executor);
		details.setReadyLatch(new DefaultLatchSemaphore((OperationMetadata) null, 1));
		return details;
	}

	/**
	 * Waits until {@code thread} is blocked, so that work done afterwards is known to happen
	 * while it waits.
	 *
	 * @param thread the thread to wait for
	 */
	private static void awaitBlocked(Thread thread) {
		long deadline = System.currentTimeMillis() + 10000;

		while (thread.getState() != Thread.State.WAITING && thread.getState() != Thread.State.TIMED_WAITING) {
			Assert.assertTrue("the waiting thread did not block", System.currentTimeMillis() < deadline);
			Assert.assertTrue("the waiting thread ended before blocking", thread.isAlive());
			Thread.onSpinWait();
		}
	}

	/**
	 * A dispatch that is ready when its waiter arrives is issued by the waiter, exactly once,
	 * even though the executor it was handed to never runs it in time.
	 */
	@Test(timeout = 30000)
	public void waiterIssuesReadyDispatch() {
		HeldExecutor executor = new HeldExecutor();
		AcceleratedProcessDetails details = details(new Object[] { new Object() }, executor);

		AtomicInteger runs = new AtomicInteger();
		AtomicReference<Thread> ranOn = new AtomicReference<>();

		details.whenReady(() -> {
			runs.incrementAndGet();
			ranOn.set(Thread.currentThread());
		});

		details.awaitReady();

		Assert.assertEquals("the dispatch should have been issued before awaitReady returned", 1, runs.get());
		Assert.assertSame("the waiting thread should issue the dispatch", Thread.currentThread(), ranOn.get());

		executor.runHeld();
		Assert.assertEquals("the executor must not issue the dispatch again", 1, runs.get());
	}

	/**
	 * A waiter that arrives before the arguments are complete never runs the listener early: the
	 * listener runs once, after the last argument has been delivered, and not on the waiter.
	 */
	@Test(timeout = 30000)
	public void waiterDoesNotIssueDispatchBeforeArguments() throws InterruptedException {
		HeldExecutor executor = new HeldExecutor();
		AcceleratedProcessDetails details = details(new Object[1], executor);

		AtomicInteger runs = new AtomicInteger();
		AtomicReference<Thread> ranOn = new AtomicReference<>();

		details.whenReady(() -> {
			runs.incrementAndGet();
			ranOn.set(Thread.currentThread());
		});

		Thread waiter = new Thread(details::awaitReady, "awaitReady waiter");
		waiter.setDaemon(true);
		waiter.start();
		awaitBlocked(waiter);

		Assert.assertEquals("the dispatch must wait for its arguments", 0, runs.get());

		details.result(0, new Object());
		executor.runHeld();
		waiter.join(10000);

		Assert.assertFalse(waiter.isAlive());
		Assert.assertEquals(1, runs.get());
		Assert.assertNotSame("a waiter that was already blocked does not issue the dispatch",
				waiter, ranOn.get());
	}

	/**
	 * An interrupted waiter leaves the dispatch to the executor, as before, and its interrupt
	 * status is kept.
	 */
	@Test(timeout = 30000)
	public void interruptedWaiterLeavesDispatchToExecutor() throws InterruptedException {
		HeldExecutor executor = new HeldExecutor();
		AcceleratedProcessDetails details = details(new Object[] { new Object() }, executor);

		AtomicInteger runs = new AtomicInteger();
		AtomicReference<Thread> ranOn = new AtomicReference<>();
		AtomicBoolean interruptKept = new AtomicBoolean();

		details.whenReady(() -> {
			runs.incrementAndGet();
			ranOn.set(Thread.currentThread());
		});

		Thread waiter = new Thread(() -> {
			Thread.currentThread().interrupt();
			details.awaitReady();
			interruptKept.set(Thread.currentThread().isInterrupted());
		}, "interrupted awaitReady waiter");
		waiter.setDaemon(true);
		waiter.start();

		if (runs.get() == 0) {
			awaitBlocked(waiter);
			executor.runHeld();
		}

		waiter.join(10000);

		Assert.assertFalse(waiter.isAlive());
		Assert.assertEquals(1, runs.get());
		Assert.assertNotSame("an interrupted waiter must not issue the dispatch", waiter, ranOn.get());
		Assert.assertTrue("the interrupt status must be kept", interruptKept.get());
	}

	/**
	 * A failure raised by a dispatch the waiter issued itself is reported by {@code awaitReady}
	 * the same way as one raised on the executor, including on a later wait.
	 */
	@Test(timeout = 30000)
	public void failureOfWaiterIssuedDispatchReachesAwaitReady() {
		HeldExecutor executor = new HeldExecutor();
		AcceleratedProcessDetails details = details(new Object[] { new Object() }, executor);
		IllegalStateException failure = new IllegalStateException("launch failed");

		try {
			details.whenReady(() -> { throw failure; });
		} catch (IllegalStateException e) {
			// Synchronous hardware runs the listener on this thread
			Assert.assertSame(failure, e);
		}

		for (int i = 0; i < 2; i++) {
			try {
				details.awaitReady();
				Assert.fail("awaitReady should have reported the listener failure");
			} catch (HardwareException e) {
				Assert.assertSame(failure, e.getCause());
			}
		}

		executor.runHeld();
	}

	/**
	 * An {@link Executor} that holds the tasks it is given until {@link #runHeld()} runs them on
	 * the calling thread, standing in for a listener executor whose threads have not yet been
	 * scheduled.
	 */
	private static final class HeldExecutor implements Executor {
		/** Tasks received and not yet run. */
		private final List<Runnable> held = new ArrayList<>();

		/**
		 * Holds {@code task} until {@link #runHeld()}.
		 *
		 * @param task the task to hold
		 */
		@Override
		public synchronized void execute(Runnable task) {
			held.add(task);
		}

		/** Runs, on the calling thread, every task held so far. */
		void runHeld() {
			List<Runnable> tasks;

			synchronized (this) {
				tasks = new ArrayList<>(held);
				held.clear();
			}

			tasks.forEach(Runnable::run);
		}
	}
}
