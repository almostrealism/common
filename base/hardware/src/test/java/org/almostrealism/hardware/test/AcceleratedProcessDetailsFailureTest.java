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
import io.almostrealism.streams.Semaphore;
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.mem.AcceleratedProcessDetails;
import org.almostrealism.hardware.mem.MemoryReplacementManager;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Verifies that a failure raised by a {@link AcceleratedProcessDetails#whenReady(Runnable)}
 * listener reaches the thread waiting in {@link AcceleratedProcessDetails#awaitReady()}.
 *
 * <p>When hardware is asynchronous the listener (which issues the kernel dispatch) runs on an
 * executor thread. The readiness latch is counted down even when the listener throws, so
 * without propagation the waiting thread would proceed as if the dispatch had been issued and
 * read an output that was never produced, while the failure was lost on the executor thread.</p>
 *
 * <p>The executor used here runs each listener on a new thread, which reproduces the
 * asynchronous path; when hardware is configured synchronous the listener runs on the calling
 * thread instead, and the failure surfaces from {@code whenReady} as well as from
 * {@code awaitReady}.</p>
 *
 * <p>Unlike most tests in this project, this one does not extend {@code TestSuiteBase}:
 * that class lives in the engine layer, which sits above this module.</p>
 */
public class AcceleratedProcessDetailsFailureTest {

	/** Runs each task on a new thread, as the asynchronous listener executor does. */
	private static final Executor SEPARATE_THREAD = r -> {
		Thread t = new Thread(r);
		t.setDaemon(true);
		t.setUncaughtExceptionHandler((thread, e) -> { });
		t.start();
	};

	/**
	 * Creates process details with no arguments and a readiness latch, as
	 * {@code AcceleratedOperation.apply} does.
	 */
	private static AcceleratedProcessDetails details() {
		AcceleratedProcessDetails details = new AcceleratedProcessDetails(
				new Object[0], 0, new MemoryReplacementManager(null, null, null), SEPARATE_THREAD);
		details.setReadyLatch(new DefaultLatchSemaphore((OperationMetadata) null, 1));
		return details;
	}

	/**
	 * Waits on {@code details} and returns the {@link HardwareException} it must throw.
	 */
	private static HardwareException awaitFailure(AcceleratedProcessDetails details) {
		try {
			details.awaitReady();
		} catch (HardwareException e) {
			return e;
		}

		Assert.fail("awaitReady should have reported the listener failure");
		return null;
	}

	/** A failing listener makes {@code awaitReady} throw with that failure as the cause. */
	@Test(timeout = 30000)
	public void listenerFailureReachesAwaitReady() {
		AcceleratedProcessDetails details = details();
		IllegalStateException failure = new IllegalStateException("launch failed");

		try {
			details.whenReady(() -> { throw failure; });
		} catch (IllegalStateException e) {
			// Synchronous hardware runs the listener on this thread
			Assert.assertSame(failure, e);
		}

		HardwareException thrown = awaitFailure(details);
		Assert.assertSame(failure, thrown.getCause());

		// The failure is sticky: a second wait reports it again rather than succeeding
		HardwareException again = awaitFailure(details);
		Assert.assertSame(failure, again.getCause());
	}

	/** An {@link Error} raised by a listener is propagated the same way. */
	@Test(timeout = 30000)
	public void listenerErrorReachesAwaitReady() {
		AcceleratedProcessDetails details = details();
		AssertionError failure = new AssertionError("device fault");

		try {
			details.whenReady(() -> { throw failure; });
		} catch (AssertionError e) {
			Assert.assertSame(failure, e);
		}

		HardwareException thrown = awaitFailure(details);
		Assert.assertSame(failure, thrown.getCause());
	}

	/**
	 * When the last argument is delivered asynchronously and processing the arguments fails,
	 * the listener that would issue the dispatch can never run. The failure must still settle
	 * the readiness latch and reach {@code awaitReady}, which otherwise waits for a dispatch
	 * that will never be issued (and, being uninterruptible, could never be released). The
	 * failure is also rethrown to the thread that delivered the argument.
	 */
	@Test(timeout = 30000)
	public void argumentProcessingFailureReachesAwaitReady() throws InterruptedException {
		IllegalStateException failure = new IllegalStateException("argument replacement failed");
		MemoryReplacementManager failing = new MemoryReplacementManager(null, null, null) {
			@Override
			public Object[] processArguments(Object[] args) {
				throw failure;
			}
		};

		AcceleratedProcessDetails details = new AcceleratedProcessDetails(
				new Object[1], 0, failing, SEPARATE_THREAD);
		details.setReadyLatch(new DefaultLatchSemaphore((OperationMetadata) null, 1));

		AtomicBoolean ran = new AtomicBoolean();
		details.whenReady(() -> ran.set(true));

		AtomicReference<Throwable> delivered = new AtomicReference<>();
		Thread producer = new Thread(() -> details.result(0, new Object()), "argument producer");
		producer.setUncaughtExceptionHandler((t, e) -> delivered.set(e));
		producer.setDaemon(true);
		producer.start();
		producer.join(10000);

		Assert.assertFalse(producer.isAlive());
		Assert.assertSame("the delivering thread must see the failure", failure, delivered.get());

		HardwareException thrown = awaitFailure(details);
		Assert.assertSame(failure, thrown.getCause());
		Assert.assertFalse("the listener must not run without processed arguments", ran.get());
	}

	/** A listener that completes normally leaves {@code awaitReady} returning normally. */
	@Test(timeout = 30000)
	public void successfulListenerDoesNotThrow() {
		AcceleratedProcessDetails details = details();
		AtomicBoolean ran = new AtomicBoolean();

		details.whenReady(() -> ran.set(true));
		details.awaitReady();

		Assert.assertTrue("the listener should have run before awaitReady returned", ran.get());
	}

	/**
	 * An interrupt does not end {@code awaitReady} before the dispatch has been issued: the
	 * waiter returns only once the readiness latch fires, so the completion it reads is the
	 * one the dispatch published rather than the unfired readiness latch, and its interrupt
	 * status is restored.
	 */
	@Test(timeout = 30000)
	public void interruptedAwaitReadyWaitsForIssuedDispatch() throws InterruptedException {
		AcceleratedProcessDetails details = new AcceleratedProcessDetails(
				new Object[0], 0, new MemoryReplacementManager(null, null, null), SEPARATE_THREAD);
		DefaultLatchSemaphore ready = new DefaultLatchSemaphore((OperationMetadata) null, 1);
		details.setReadyLatch(ready);

		Semaphore published = () -> { };
		AtomicReference<Semaphore> observed = new AtomicReference<>();
		AtomicBoolean interruptKept = new AtomicBoolean();

		Thread waiter = new Thread(() -> {
			Thread.currentThread().interrupt();
			details.awaitReady();
			observed.set(details.getSemaphore());
			interruptKept.set(Thread.currentThread().isInterrupted());
		}, "awaitReady waiter");
		waiter.setDaemon(true);
		waiter.start();

		waiter.join(200);
		Assert.assertTrue("an interrupt pending on entry must not end the wait", waiter.isAlive());
		waiter.interrupt();
		waiter.join(200);
		Assert.assertTrue("an interrupt during the wait must not end it", waiter.isAlive());

		details.setSemaphore(published);
		ready.countDown();
		waiter.join(10000);

		Assert.assertFalse(waiter.isAlive());
		Assert.assertSame("the waiter must read the issued dispatch's completion",
				published, observed.get());
		Assert.assertTrue("the interrupt status must be restored", interruptKept.get());
	}
}
