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
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Validates {@link Semaphore#then(java.util.function.Supplier)} and the
 * {@link DeferredSemaphore} it returns, the chaining primitive that lets a backend order a
 * dispatch after a completion its own queue cannot express without a host wait on the
 * submitting thread. These contracts control kernel dispatch ordering and memory-guard
 * release, so the failure paths are covered as well as the success path.
 */
public class SemaphoreThenTest extends TestSuiteBase {

	/**
	 * Regression: work deferred behind a pending dependency occupies no thread while it waits.
	 * Hundreds of operations queued behind one long dependency, with settlement callbacks on
	 * each and on a composite of them all, must not start a callback thread per operation; every
	 * one of them still runs, and settles, once the dependency completes.
	 */
	@Test(timeout = 30000)
	public void pendingWorkOccupiesNoThreads() throws InterruptedException {
		int operations = 300;
		LatchSemaphore dependency = new LatchSemaphore(1);
		AtomicInteger ran = new AtomicInteger();
		AtomicInteger settled = new AtomicInteger();
		List<Semaphore> chained = new ArrayList<>();

		int before = Thread.activeCount();
		for (int i = 0; i < operations; i++) {
			Semaphore work = dependency.then(() -> {
				ran.incrementAndGet();
				return null;
			});
			work.whenSettled(settled::incrementAndGet);
			chained.add(work);
		}

		Semaphore all = Semaphore.all(chained);
		CountDownLatch groupSettled = new CountDownLatch(1);
		all.whenSettled(groupSettled::countDown);

		assertTrue("Pending deferred work must not occupy a thread per operation; active threads grew by " +
				(Thread.activeCount() - before), Thread.activeCount() - before < operations / 10);

		dependency.countDown();
		all.waitFor();
		Assert.assertEquals(operations, ran.get());
		assertTrue(groupSettled.await(10, TimeUnit.SECONDS));
		Assert.assertEquals(operations, settled.get());
	}

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
}
