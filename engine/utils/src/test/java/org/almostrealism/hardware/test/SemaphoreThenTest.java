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

import io.almostrealism.streams.DeferredSemaphore;
import io.almostrealism.streams.LatchSemaphore;
import io.almostrealism.streams.Semaphore;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertSame;

/**
 * Validates {@link Semaphore#then(java.util.function.Supplier)} and the
 * {@link DeferredSemaphore} it returns, the chaining primitive that lets a backend order a
 * dispatch after a completion its own queue cannot express without a host wait on the
 * submitting thread. These contracts control kernel dispatch ordering and memory-guard
 * release, so the failure paths are covered as well as the success path.
 */
public class SemaphoreThenTest extends TestSuiteBase {

	/**
	 * {@code then} returns before the dependency completes and does not run the work until
	 * the dependency has completed; once it does, waiting on the chained semaphore observes
	 * the work having run.
	 */
	@Test(timeout = 30000)
	public void submissionReturnsWhileDependencyPending() throws InterruptedException {
		LatchSemaphore dependency = new LatchSemaphore(1);
		AtomicBoolean workRan = new AtomicBoolean(false);

		Semaphore chained = dependency.then(() -> {
			workRan.set(true);
			return null;
		});

		// then() returned without waiting; while the dependency is held open the work must
		// not have run.
		Thread.sleep(250);
		assertFalse(workRan.get());

		dependency.countDown();
		chained.waitFor();
		assertTrue(workRan.get());
	}

	/**
	 * A dependency failure is rethrown from {@link Semaphore#waitFor()} and the work does not
	 * run after it.
	 */
	@Test(timeout = 30000)
	public void dependencyFailurePropagatesWithoutRunningWork() {
		Semaphore dependency = () -> { throw new IllegalStateException("dependency failed"); };
		AtomicBoolean workRan = new AtomicBoolean(false);

		Semaphore chained = dependency.then(() -> {
			workRan.set(true);
			return null;
		});

		boolean threw = false;
		try {
			chained.waitFor();
		} catch (IllegalStateException e) {
			threw = true;
			assertTrue(e.getMessage().contains("dependency failed"));
		}

		assertTrue(threw);
		// The work is only reachable after dependency.waitFor() returns, which it never did.
		assertFalse(workRan.get());
	}

	/**
	 * A failure of the work itself is rethrown from {@link Semaphore#waitFor()}.
	 */
	@Test(timeout = 30000)
	public void workFailurePropagates() {
		LatchSemaphore dependency = new LatchSemaphore(1);

		Semaphore chained = dependency.then(() -> {
			throw new IllegalStateException("work failed");
		});

		dependency.countDown();

		boolean threw = false;
		try {
			chained.waitFor();
		} catch (IllegalStateException e) {
			threw = true;
			assertTrue(e.getMessage().contains("work failed"));
		}

		assertTrue(threw);
	}

	/**
	 * When the work returns a child semaphore, waiting on the chained semaphore waits for
	 * that child too, not merely for the work to have started.
	 */
	@Test(timeout = 30000)
	public void childSemaphoreIsAwaited() throws InterruptedException {
		LatchSemaphore dependency = new LatchSemaphore(1);
		LatchSemaphore child = new LatchSemaphore(1);

		Semaphore chained = dependency.then(() -> child);
		dependency.countDown();

		AtomicBoolean released = new AtomicBoolean(false);
		Thread waiter = new Thread(() -> {
			chained.waitFor();
			released.set(true);
		}, "SemaphoreThenTest waiter");
		waiter.setDaemon(true);
		waiter.start();

		// The work has started (its child is published) but the child is still pending, so
		// the wait must not have returned.
		waiter.join(250);
		assertFalse(released.get());

		child.countDown();
		waiter.join(10000);
		assertTrue(released.get());
	}

	/**
	 * A work result of {@code null} (work that finished synchronously) completes the chain
	 * without any further wait.
	 */
	@Test(timeout = 30000)
	public void nullChildCompletesImmediately() {
		LatchSemaphore dependency = new LatchSemaphore(1);
		dependency.countDown();

		AtomicBoolean workRan = new AtomicBoolean(false);
		Semaphore chained = dependency.then(() -> {
			workRan.set(true);
			return null;
		});

		chained.waitFor();
		assertTrue(workRan.get());
	}

	/**
	 * {@link Semaphore#whenSettled(Runnable)} runs its callback even when the semaphore fails,
	 * which is what releasing a resource held for the operation depends on &mdash; unlike
	 * {@link Semaphore#onComplete(Runnable)}, whose callback is skipped on the failure path.
	 */
	@Test(timeout = 30000)
	public void whenSettledRunsOnFailure() throws InterruptedException {
		Semaphore failing = () -> { throw new IllegalStateException("failed"); };
		CountDownLatch settled = new CountDownLatch(1);

		failing.whenSettled(settled::countDown);

		assertTrue(settled.await(10, TimeUnit.SECONDS));
	}

	/**
	 * A waiter interrupted while the dependency is still pending returns promptly with its
	 * interrupt status set, rather than staying blocked until the work starts; the chained
	 * semaphore is unaffected and can still be waited for once the dependency completes.
	 */
	@Test(timeout = 30000)
	public void interruptedWaitReturnsWhileDependencyPending() throws InterruptedException {
		LatchSemaphore dependency = new LatchSemaphore(1);
		AtomicBoolean workRan = new AtomicBoolean(false);

		Semaphore chained = dependency.then(() -> {
			workRan.set(true);
			return null;
		});

		AtomicBoolean returned = new AtomicBoolean(false);
		AtomicBoolean interruptKept = new AtomicBoolean(false);
		Thread waiter = new Thread(() -> {
			chained.waitFor();
			interruptKept.set(Thread.currentThread().isInterrupted());
			returned.set(true);
		}, "SemaphoreThenTest waiter");

		waiter.start();
		Thread.sleep(100);
		waiter.interrupt();
		waiter.join(10000);

		assertFalse("An interrupted waiter must not stay blocked on a pending dependency", waiter.isAlive());
		assertTrue(returned.get());
		assertTrue("The interrupt status must survive the wait", interruptKept.get());
		assertFalse(workRan.get());

		dependency.countDown();
		chained.waitFor();
		assertTrue(workRan.get());
	}

	/**
	 * {@link Semaphore#waitForUninterruptibly()} returns only once the semaphore has completed,
	 * through an interrupt pending on entry and another arriving during the wait, and restores
	 * the interrupt status afterwards. {@link Semaphore#waitFor()} would return on the first.
	 */
	@Test(timeout = 30000)
	public void waitForUninterruptiblyWaitsThroughInterrupts() throws InterruptedException {
		LatchSemaphore pending = new LatchSemaphore(1);
		AtomicBoolean released = new AtomicBoolean(false);
		AtomicBoolean completedFirst = new AtomicBoolean(false);
		AtomicBoolean interruptKept = new AtomicBoolean(false);

		Thread waiter = new Thread(() -> {
			Thread.currentThread().interrupt();
			pending.waitForUninterruptibly();
			completedFirst.set(released.get());
			interruptKept.set(Thread.currentThread().isInterrupted());
		}, "SemaphoreThenTest uninterruptible waiter");

		waiter.start();
		waiter.join(200);
		assertTrue("An interrupt pending on entry must not end the wait", waiter.isAlive());
		waiter.interrupt();
		waiter.join(200);
		assertTrue("An interrupt arriving during the wait must not end it", waiter.isAlive());

		released.set(true);
		pending.countDown();
		waiter.join(10000);

		assertFalse(waiter.isAlive());
		assertTrue("The wait must return only after the semaphore completed", completedFirst.get());
		assertTrue("The interrupt status must be restored", interruptKept.get());
	}

	/**
	 * {@link Semaphore#waitForUninterruptibly()} on a semaphore that has already completed
	 * returns at once even when the caller is interrupted, and a failure is rethrown exactly as
	 * by {@link Semaphore#waitFor()}; in both cases the interrupt status is restored.
	 */
	@Test(timeout = 30000)
	public void waitForUninterruptiblyReturnsCompletedAndRethrowsFailure() {
		LatchSemaphore completed = new LatchSemaphore(1);
		completed.countDown();

		Thread.currentThread().interrupt();
		completed.waitForUninterruptibly();
		assertTrue("The interrupt status must be restored", Thread.interrupted());

		LatchSemaphore failed = new LatchSemaphore(1);
		IllegalStateException failure = new IllegalStateException("member failed");
		failed.fail(failure);
		failed.countDown();

		Thread.currentThread().interrupt();
		IllegalStateException thrown = null;
		try {
			failed.waitForUninterruptibly();
		} catch (IllegalStateException e) {
			thrown = e;
		}

		assertTrue("The interrupt status must be restored after a failure", Thread.interrupted());
		assertSame(failure, thrown);
	}
}
