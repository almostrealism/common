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

package io.almostrealism.concurrent.test;

import io.almostrealism.concurrent.HandOffWindow;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tests {@link HandOffWindow}: a negative window is rejected, polling returns as soon as the
 * condition holds, gives up once the window has elapsed, never runs for an empty window, and
 * ends at once on an interrupt without clearing it.
 */
public class HandOffWindowTest extends TestSuiteBase {

	/** A negative hand-off window is rejected when it is created, naming the rejected value. */
	@Test(timeout = 10000)
	public void negativeWindowIsRejected() {
		try {
			new HandOffWindow(-1, TimeUnit.NANOSECONDS);
			Assert.fail("A negative hand-off window must be rejected");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage().contains("-1"));
		}
	}

	/**
	 * Polling returns true as soon as the condition holds, without evaluating it again or waiting
	 * for the rest of a long window.
	 */
	@Test(timeout = 10000)
	public void pollUntilReturnsOnceConditionHolds() {
		AtomicInteger evaluations = new AtomicInteger();

		assertTrue(new HandOffWindow(30, TimeUnit.SECONDS)
				.pollUntil(() -> evaluations.incrementAndGet() >= 100));
		assertEquals(100, evaluations.get());
	}

	/**
	 * Polling for a condition that never holds gives up and returns false once the window has
	 * elapsed, and not before.
	 */
	@Test(timeout = 10000)
	public void pollUntilGivesUpOnceWindowElapses() {
		HandOffWindow window = new HandOffWindow(20, TimeUnit.MILLISECONDS);

		long start = System.nanoTime();
		boolean held = window.pollUntil(() -> false);
		long elapsed = System.nanoTime() - start;

		assertFalse(held);
		assertTrue("Polling must last the whole window, but lasted " + elapsed + "ns",
				elapsed >= TimeUnit.MILLISECONDS.toNanos(20));
		assertTrue("Polling must stop once the window has elapsed, but lasted " + elapsed + "ns",
				elapsed < TimeUnit.SECONDS.toNanos(5));
	}

	/** An empty window returns false at once without evaluating the condition at all. */
	@Test(timeout = 10000)
	public void emptyWindowNeverPolls() {
		AtomicInteger evaluations = new AtomicInteger();

		assertFalse(HandOffWindow.NONE.pollUntil(() -> evaluations.incrementAndGet() > 0));
		assertFalse(new HandOffWindow(0, TimeUnit.SECONDS).pollUntil(() -> evaluations.incrementAndGet() > 0));
		assertEquals(0, evaluations.get());
	}

	/**
	 * A thread interrupted before it polls does not poll at all, and one interrupted while polling
	 * stops at once rather than polling for the rest of a long window. Either way the interrupt
	 * stays pending, so the blocking wait that follows sees it.
	 */
	@Test(timeout = 10000)
	public void interruptEndsPollingAndStaysPending() throws InterruptedException {
		HandOffWindow window = new HandOffWindow(30, TimeUnit.SECONDS);

		AtomicInteger evaluations = new AtomicInteger();
		AtomicBoolean pendingHeld = new AtomicBoolean(true);
		AtomicBoolean pendingKept = new AtomicBoolean();
		Thread pending = new Thread(() -> {
			Thread.currentThread().interrupt();
			pendingHeld.set(window.pollUntil(() -> evaluations.incrementAndGet() > 0));
			pendingKept.set(Thread.currentThread().isInterrupted());
		});
		pending.start();
		pending.join(5000);

		assertFalse(pending.isAlive());
		assertFalse(pendingHeld.get());
		assertEquals(0, evaluations.get());
		assertTrue("The interrupt must stay pending", pendingKept.get());

		CountDownLatch polling = new CountDownLatch(1);
		AtomicBoolean arrivingHeld = new AtomicBoolean(true);
		AtomicBoolean arrivingKept = new AtomicBoolean();
		Thread arriving = new Thread(() -> {
			arrivingHeld.set(window.pollUntil(() -> {
				polling.countDown();
				return false;
			}));
			arrivingKept.set(Thread.currentThread().isInterrupted());
		});
		arriving.start();

		assertTrue(polling.await(5, TimeUnit.SECONDS));
		arriving.interrupt();
		arriving.join(5000);

		assertFalse("An interrupt must end polling at once", arriving.isAlive());
		assertFalse(arrivingHeld.get());
		assertTrue("The interrupt must stay pending", arrivingKept.get());
	}
}
