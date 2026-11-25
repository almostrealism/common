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

import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.instructions.ScopeInstructionsManager;
import org.almostrealism.hardware.instructions.ScopeSignatureExecutionKey;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Verifies that {@link ScopeInstructionsManager}'s dispatch-retention lifecycle
 * ({@code retainForDispatch}/{@code releaseAfterDispatch}/{@code destroy}) is guarded by a
 * dedicated monitor rather than the instance monitor, and that the deferral semantics it provides
 * are preserved by that choice.
 *
 * <p>The lifecycle must not synchronize on {@code this}: {@code getOperator} holds the instance
 * monitor while its access listener inserts into the shared instruction cache, and a
 * capacity-pressure insertion evicts another manager and calls its {@code destroy()} from within
 * the cache monitor. Synchronizing {@code destroy()} on {@code this} would order
 * [manager monitor &rarr; cache monitor] against [cache monitor &rarr; manager monitor], so two
 * threads evicting each other's managers would deadlock. These tests pin that contract without
 * reproducing the (inherently timing-dependent) deadlock: the first confirms the lifecycle runs
 * while the instance monitor is held elsewhere, the others confirm the retain/defer/destroy
 * ordering is intact.</p>
 *
 * <p>Unlike most tests in this project, this one does not extend {@code TestSuiteBase}: that class
 * lives in the engine layer, which sits above this module.</p>
 */
public class ScopeInstructionsManagerLockTest {

	/**
	 * Creates a manager that is never compiled; its scope supplier is never invoked, so
	 * {@code destroy()} has no instructions or kernel-structure resources to release and runs only
	 * its registered destroy listeners.
	 */
	private static ScopeInstructionsManager<ScopeSignatureExecutionKey> manager() {
		return new ScopeInstructionsManager<>(
				Hardware.getLocalHardware().getComputeContext(), () -> null, null);
	}

	/**
	 * The retain/destroy/release lifecycle must complete even while another thread holds the
	 * manager's instance monitor, proving it is guarded by a dedicated lock rather than {@code this}.
	 * Were {@code destroy()} synchronized on {@code this}, this would block until the monitor is
	 * released &mdash; the same acquisition order that deadlocks against the instruction cache.
	 */
	@Test(timeout = 30000)
	public void lifecycleDoesNotUseInstanceMonitor() throws InterruptedException {
		ScopeInstructionsManager<ScopeSignatureExecutionKey> manager = manager();

		CountDownLatch holding = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);

		Thread monitorHolder = new Thread(() -> {
			synchronized (manager) {
				holding.countDown();
				try {
					release.await();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
		}, "monitor-holder");
		monitorHolder.setDaemon(true);
		monitorHolder.start();

		Assert.assertTrue("the holder thread did not acquire the instance monitor",
				holding.await(10, TimeUnit.SECONDS));

		CountDownLatch done = new CountDownLatch(1);
		Thread lifecycle = new Thread(() -> {
			manager.retainForDispatch();
			manager.destroy();
			manager.releaseAfterDispatch();
			done.countDown();
		}, "lifecycle");
		lifecycle.setDaemon(true);
		lifecycle.start();

		Assert.assertTrue("the retain/destroy lifecycle blocked on the held instance monitor",
				done.await(10, TimeUnit.SECONDS));

		release.countDown();
		monitorHolder.join(10000);
		Assert.assertFalse(monitorHolder.isAlive());
	}

	/**
	 * A {@code destroy()} that arrives while a dispatch is retained is deferred until the last
	 * dispatch releases, at which point it runs exactly once.
	 */
	@Test(timeout = 30000)
	public void destroyDeferredUntilDispatchReleased() {
		ScopeInstructionsManager<ScopeSignatureExecutionKey> manager = manager();
		AtomicBoolean destroyed = new AtomicBoolean();
		manager.addDestroyListener(() -> destroyed.set(true));

		manager.retainForDispatch();
		manager.destroy();
		Assert.assertFalse("destroy must be deferred while a dispatch is retained", destroyed.get());

		manager.releaseAfterDispatch();
		Assert.assertTrue("the deferred destroy must run when the last dispatch releases",
				destroyed.get());
	}

	/**
	 * With two dispatches retained, a {@code destroy()} runs only once the second releases, not the
	 * first, so the compiled instructions survive for every dispatch still reading them.
	 */
	@Test(timeout = 30000)
	public void destroyWaitsForTheLastOfSeveralDispatches() {
		ScopeInstructionsManager<ScopeSignatureExecutionKey> manager = manager();
		AtomicBoolean destroyed = new AtomicBoolean();
		manager.addDestroyListener(() -> destroyed.set(true));

		manager.retainForDispatch();
		manager.retainForDispatch();
		manager.destroy();

		manager.releaseAfterDispatch();
		Assert.assertFalse("destroy must wait for the last dispatch, not the first", destroyed.get());

		manager.releaseAfterDispatch();
		Assert.assertTrue("the deferred destroy must run once the last dispatch releases",
				destroyed.get());
	}

	/**
	 * A {@code destroy()} with no dispatch in flight runs immediately rather than being deferred.
	 */
	@Test(timeout = 30000)
	public void destroyRunsImmediatelyWithNoDispatchInFlight() {
		ScopeInstructionsManager<ScopeSignatureExecutionKey> manager = manager();
		AtomicBoolean destroyed = new AtomicBoolean();
		manager.addDestroyListener(() -> destroyed.set(true));

		manager.destroy();
		Assert.assertTrue("destroy must run immediately when no dispatch is retained", destroyed.get());
	}
}
