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

import io.almostrealism.relation.Evaluable;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.AcceleratedOperation;
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Verifies that an {@link AcceleratedOperation} whose completion wait is cut short by an
 * interrupt reports the interrupt instead of returning as though the work had finished.
 *
 * <p>Completion waits return early, with the interrupt status set, when the waiting thread
 * is interrupted. If {@link AcceleratedOperation#run()} returned normally in that case, a
 * loop that runs an operation once per tick would keep dispatching every remaining tick
 * without waiting for any of them once its thread had been interrupted (by a test timeout,
 * for example), loading the device and the callback pool for whatever runs next.</p>
 */
public class AcceleratedOperationInterruptTest extends TestSuiteBase {
	/**
	 * How long the active-dispatch loop keeps dispatching before giving up on the interrupt,
	 * far longer than the delay before the interrupt is sent and shorter than the test timeout.
	 */
	private static final long LOOP_DEADLINE_NANOS = 30_000_000_000L;

	/**
	 * Running an operation on an interrupted thread fails with a {@link HardwareException},
	 * and leaves the interrupt status set for the caller.
	 */
	@Test(timeout = 60000)
	public void runOnInterruptedThreadFails() {
		PackedCollection src = new PackedCollection(16).fill(1.0);
		PackedCollection mid = new PackedCollection(16);
		PackedCollection dst = new PackedCollection(16);

		OperationList list = new OperationList("Interrupted run", true);
		list.add(a(each(p(mid)), cp(src).multiply(2.0)));
		list.add(a(each(p(dst)), cp(mid).add(0.0)));

		Runnable op = list.get();
		Assert.assertTrue("expected an AcceleratedOperation, got " + op.getClass().getName(),
				op instanceof AcceleratedOperation);

		// Compile and run once normally, so the interrupted run below exercises only the wait
		op.run();
		assertEquals(2.0, dst.toDouble(0));

		Thread.currentThread().interrupt();

		try {
			op.run();
			Assert.fail("run() returned normally on an interrupted thread");
		} catch (HardwareException expected) {
			Assert.assertTrue("interrupt status was cleared", Thread.currentThread().isInterrupted());
		} finally {
			Thread.interrupted();
		}
	}

	/**
	 * An interrupt that arrives while a loop is actively dispatching is reported the same way
	 * as one whose status was already set before the loop began, so a per-tick loop stops
	 * dispatching once its thread is interrupted rather than running the remaining ticks
	 * without waiting for any of them.
	 */
	@Test(timeout = 60000)
	public void interruptDuringActiveDispatchFails() throws InterruptedException {
		PackedCollection src = new PackedCollection(16).fill(1.0);
		PackedCollection dst = new PackedCollection(16);

		OperationList list = new OperationList("Interrupted dispatch", true);
		list.add(a(each(p(dst)), cp(src).multiply(2.0)));

		Runnable op = list.get();

		op.run();
		assertEquals(2.0, dst.toDouble(0));

		Thread target = Thread.currentThread();
		Thread interrupter = new Thread(() -> {
			try {
				Thread.sleep(50);
			} catch (InterruptedException e) {
				return;
			}
			target.interrupt();
		}, "AcceleratedOperationInterruptTest-interrupter");

		interrupter.start();

		try {
			// The loop is bounded by time rather than by a dispatch count, so a fast backend
			// cannot finish it before the interrupt arrives; the deadline is only a safety net
			long deadline = System.nanoTime() + LOOP_DEADLINE_NANOS;
			while (System.nanoTime() < deadline) {
				op.run();
			}
			Assert.fail("run() loop completed without reporting the interrupt");
		} catch (HardwareException expected) {
			Assert.assertTrue("interrupt status was cleared", Thread.currentThread().isInterrupted());
		} finally {
			// Clear the status before joining, since join() throws on an interrupted thread,
			// then clear it again in case the interrupter fired after the loop ended
			Thread.interrupted();
			interrupter.join();
			Thread.interrupted();
		}
	}

	/**
	 * Evaluating a computation on an interrupted thread fails rather than returning an output
	 * the kernel may not have finished writing.
	 */
	@Test(timeout = 60000)
	public void evaluateOnInterruptedThreadFails() {
		PackedCollection src = new PackedCollection(16).fill(1.0);

		Evaluable<PackedCollection> ev = cp(src).multiply(3.0).get();
		assertEquals(3.0, ev.evaluate().toDouble(0));

		Thread.currentThread().interrupt();

		try {
			ev.evaluate();
			Assert.fail("evaluate() returned normally on an interrupted thread");
		} catch (HardwareException expected) {
			Assert.assertTrue("interrupt status was cleared", Thread.currentThread().isInterrupted());
		} finally {
			Thread.interrupted();
		}
	}
}
