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

import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.AcceleratedComputationEvaluable;
import org.almostrealism.hardware.AcceleratedOperation;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.KernelBindingListener;
import org.almostrealism.hardware.computations.HardwareEvaluable;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Verifies that an {@link Evaluable} held across the eviction of its shared
 * instruction manager continues to produce correct results.
 *
 * <p>Holding a compiled {@link Evaluable} long-term is a recommended usage
 * pattern, but the compiled instructions it depends on live in a bounded
 * cache and can be evicted and destroyed at any time by unrelated activity.
 * The holder's argument bindings are derived from the destroyed manager's
 * compiled scope, so after eviction the holder must rebind against whatever
 * manager serves it next — executing with the old bindings silently reads
 * and writes the wrong memory. These tests exercise that lifecycle
 * deterministically using {@code DefaultComputer.evictInstructions}, which
 * removes and destroys a manager exactly as capacity pressure would.</p>
 */
public class InstructionEvictionRebindTest extends TestSuiteBase {

	/**
	 * Unwraps the compiled evaluable underlying the given wrapper.
	 *
	 * @param ev the evaluable produced for a compiled computation
	 * @return the underlying compiled evaluable
	 */
	private AcceleratedComputationEvaluable<?> kernel(Evaluable<?> ev) {
		Evaluable<?> inner = ev;

		while (inner instanceof HardwareEvaluable) {
			inner = ((HardwareEvaluable<?>) inner).getKernel().getValue();
		}

		return (AcceleratedComputationEvaluable<?>) inner;
	}

	/**
	 * A held {@link Evaluable} must keep producing correct results after its
	 * instruction manager is evicted and destroyed, including reflecting input
	 * data that changed after the eviction.
	 */
	@Test(timeout = 120000)
	public void heldEvaluableSurvivesEviction() {
		PackedCollection a = new PackedCollection(4);
		integers(1, 5).into(a.traverseEach()).evaluate();
		PackedCollection b = new PackedCollection(4);
		integers(1, 5).multiply(10.0).into(b.traverseEach()).evaluate();

		Evaluable<PackedCollection> held = cp(a).add(cp(b)).get();

		PackedCollection first = held.evaluate();
		for (int i = 0; i < 4; i++) {
			assertEquals(a.toDouble(i) + b.toDouble(i), first.toDouble(i));
		}

		String signature = kernel(held).signature();
		assertNotNull("A provider-based computation should have a signature", signature);

		Hardware.getLocalHardware().getComputer().evictInstructions(signature);

		integers(1, 5).multiply(100.0).into(b.traverseEach()).evaluate();
		PackedCollection second = held.evaluate();
		for (int i = 0; i < 4; i++) {
			assertEquals(a.toDouble(i) + b.toDouble(i), second.toDouble(i));
		}
	}

	/**
	 * Two holders of structurally identical computations across an eviction:
	 * after the shared manager is destroyed, the second holder's evaluation
	 * produces a replacement manager, and the first holder must rebind against
	 * it (or fail loudly) rather than execute the replacement kernel through
	 * bindings created against the destroyed manager.
	 */
	@Test(timeout = 120000)
	public void rebindsAfterReplacementManager() {
		PackedCollection a = new PackedCollection(4);
		integers(1, 5).into(a.traverseEach()).evaluate();
		PackedCollection b = new PackedCollection(4);
		integers(1, 5).multiply(10.0).into(b.traverseEach()).evaluate();
		PackedCollection c = new PackedCollection(4);
		integers(5, 9).into(c.traverseEach()).evaluate();
		PackedCollection d = new PackedCollection(4);
		integers(5, 9).multiply(10.0).into(d.traverseEach()).evaluate();

		Evaluable<PackedCollection> held = cp(a).add(cp(b)).get();
		Evaluable<PackedCollection> peer = cp(c).add(cp(d)).get();

		PackedCollection first = held.evaluate();
		for (int i = 0; i < 4; i++) {
			assertEquals(a.toDouble(i) + b.toDouble(i), first.toDouble(i));
		}

		String signature = kernel(held).signature();
		assertNotNull("A provider-based computation should have a signature", signature);

		Hardware.getLocalHardware().getComputer().evictInstructions(signature);

		// The peer's evaluation compiles the replacement manager for this signature
		PackedCollection peerResult = peer.evaluate();
		for (int i = 0; i < 4; i++) {
			assertEquals(c.toDouble(i) + d.toDouble(i), peerResult.toDouble(i));
		}

		integers(1, 5).multiply(-1.0).add(10.0).into(a.traverseEach()).evaluate();
		PackedCollection second = held.evaluate();
		for (int i = 0; i < 4; i++) {
			assertEquals(a.toDouble(i) + b.toDouble(i), second.toDouble(i));
		}
	}

	/**
	 * Evicting an operation's instructions while another thread is dispatching the same operation
	 * must not clear that operation's argument bindings out from under the running dispatch.
	 *
	 * <p>The eviction destroy listener and {@code AcceleratedOperation.apply} run on different
	 * threads, and before the fix the listener cleared the bindings immediately, even while a
	 * dispatch was reading them — the dispatch then executed against partially cleared state and
	 * wrote nothing, or the wrong memory. This test pauses a dispatch inside {@code apply} using the
	 * {@link AcceleratedOperation#bindingListener} dispatch hook, evicts the instructions from the
	 * main thread while the dispatch is paused, and asserts the bindings are still intact; the reset
	 * must be deferred until the dispatch completes. It is made deterministic with latches rather
	 * than timing, and fails reliably before the fix (the bindings are cleared during the pause).</p>
	 *
	 * @throws InterruptedException if the test thread is interrupted while coordinating
	 */
	@Test(timeout = 120000)
	public void evictionDeferredDuringConcurrentApply() throws InterruptedException {
		PackedCollection a = new PackedCollection(4);
		integers(1, 5).into(a.traverseEach()).evaluate();
		PackedCollection b = new PackedCollection(4);
		integers(1, 5).multiply(10.0).into(b.traverseEach()).evaluate();

		Evaluable<PackedCollection> held = cp(a).add(cp(b)).get();

		PackedCollection first = held.evaluate();
		for (int i = 0; i < 4; i++) {
			assertEquals(a.toDouble(i) + b.toDouble(i), first.toDouble(i));
		}

		AcceleratedComputationEvaluable<?> op = kernel(held);
		String signature = op.signature();
		assertNotNull("A provider-based computation should have a signature", signature);
		assertNotNull("The operation should be bound after its first evaluation", op.getArguments());

		CountDownLatch dispatchPaused = new CountDownLatch(1);
		CountDownLatch evictionComplete = new CountDownLatch(1);
		AtomicBoolean armed = new AtomicBoolean(false);
		AtomicReference<Throwable> dispatchFailure = new AtomicReference<>();

		KernelBindingListener previousListener = AcceleratedOperation.bindingListener;
		AcceleratedOperation.bindingListener = new KernelBindingListener() {
			@Override
			public void dispatched(OperationMetadata metadata, boolean aggregating) {
				if (armed.compareAndSet(true, false)) {
					dispatchPaused.countDown();
					try {
						evictionComplete.await();
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				}
			}
		};

		boolean clearedDuringApply;

		try {
			armed.set(true);

			Thread dispatcher = new Thread(() -> {
				try {
					held.evaluate();
				} catch (Throwable t) {
					dispatchFailure.set(t);
				}
			}, "eviction-concurrent-dispatch");
			dispatcher.start();

			assertTrue("The dispatch did not reach apply within the timeout",
					dispatchPaused.await(60, TimeUnit.SECONDS));

			// Evict while the paused dispatch is in flight; the reset must be deferred.
			Hardware.getLocalHardware().getComputer().evictInstructions(signature);

			clearedDuringApply = op.getArguments() == null;

			evictionComplete.countDown();
			dispatcher.join(60000);
			assertFalse("The dispatch thread is still running", dispatcher.isAlive());
		} finally {
			AcceleratedOperation.bindingListener = previousListener;
			evictionComplete.countDown();
		}

		if (dispatchFailure.get() != null) {
			throw new AssertionError("The concurrent dispatch failed", dispatchFailure.get());
		}
		assertFalse("Eviction cleared the operation's bindings while a dispatch was in flight",
				clearedDuringApply);

		// The deferred reset ran once the dispatch completed; the held evaluable rebinds and
		// still reflects input changed after the eviction.
		integers(1, 5).multiply(100.0).into(b.traverseEach()).evaluate();
		PackedCollection second = held.evaluate();
		for (int i = 0; i < 4; i++) {
			assertEquals(a.toDouble(i) + b.toDouble(i), second.toDouble(i));
		}
	}
}
