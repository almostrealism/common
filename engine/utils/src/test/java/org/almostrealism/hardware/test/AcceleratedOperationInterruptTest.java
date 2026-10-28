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
import org.almostrealism.hardware.CompilationInterruptedException;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Verifies that an {@link AcceleratedOperation} whose completion wait is cut short by an
 * interrupt reports the interrupt instead of returning as though the work had finished.
 *
 * <p>Completion waits return early, with the interrupt status set, when the waiting thread
 * is interrupted. If {@link AcceleratedOperation#run()} returned normally in that case, a
 * loop that runs an operation once per tick would keep dispatching every remaining tick
 * without waiting for any of them once its thread had been interrupted (by a test timeout,
 * for example), loading the device and the callback pool for whatever runs next.</p>
 *
 * <p>The same holds for compilation: an interrupted thread does not start compiling a
 * kernel it has not compiled before, and fails with a {@link CompilationInterruptedException}
 * instead, so a loop that compiles a new kernel per iteration stops once its thread is
 * interrupted rather than continuing to compete for the compiler and the device.</p>
 */
public class AcceleratedOperationInterruptTest extends TestSuiteBase {
	/**
	 * How long the active-dispatch loop keeps dispatching before giving up on the interrupt,
	 * far longer than the delay before the interrupt is sent and shorter than the test timeout.
	 */
	private static final long LOOP_DEADLINE_NANOS = 30_000_000_000L;

	/**
	 * The number of distinct kernels the compile loop would build if nothing stopped it,
	 * far more than can be compiled before the test timeout on any backend.
	 */
	private static final int COMPILE_LOOP_KERNELS = 100_000;

	/**
	 * How many further kernels the compile loop may start after the interrupt is sent: the
	 * compilation (or dispatch) in progress when the interrupt arrives, plus the iteration in
	 * which the interrupter's {@code interrupt()} call races the loop's next check.
	 */
	private static final int ITERATIONS_AFTER_INTERRUPT = 2;

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

	/**
	 * Returns whether {@code t}, or any exception in its cause chain, is a
	 * {@link CompilationInterruptedException}.
	 */
	private static boolean causedByCancelledCompile(Throwable t) {
		for (Throwable c = t; c != null; c = c.getCause()) {
			if (c instanceof CompilationInterruptedException) return true;
			if (c.getCause() == c) break;
		}

		return false;
	}

	/**
	 * Builds a computation over a collection of the given size whose kernel has not
	 * been compiled by any other test, so evaluating it requires a compilation.
	 */
	private Evaluable<PackedCollection> uncompiled(PackedCollection src) {
		return cp(src).multiply(7.0).add(0.5).get();
	}

	/**
	 * Evaluating a computation that has never been compiled on an interrupted thread
	 * fails with a {@link CompilationInterruptedException} in the cause chain, rather than
	 * compiling the kernel, and leaves the interrupt status set. On a native lane this must
	 * be the new cancellation, not a failure reported by the native compiler's interrupted
	 * {@code Process.waitFor()}, which would show the check ran too late.
	 */
	@Test(timeout = 60000)
	public void firstCompileOnInterruptedThreadFails() {
		PackedCollection src = new PackedCollection(1013).fill(1.0);

		Thread.currentThread().interrupt();

		try {
			// get() is inside the try block: some producers compile in get() rather than in evaluate()
			uncompiled(src).evaluate();
			Assert.fail("A kernel was compiled and evaluated on an interrupted thread");
		} catch (HardwareException expected) {
			Assert.assertTrue("Expected a cancelled compilation, got: " + expected,
					causedByCancelledCompile(expected));
			Assert.assertTrue("interrupt status was cleared", Thread.currentThread().isInterrupted());
		} finally {
			Thread.interrupted();
		}
	}

	/**
	 * After a compilation cancelled by an interrupt, the same evaluable compiles and returns
	 * the correct value once the interrupt status is cleared. With instruction set reuse on,
	 * this reaches the same shared {@code ScopeInstructionsManager} the cancelled attempt used,
	 * so it shows the cancelled attempt left no half-registered scope state behind: no
	 * "Repeated attempt to retrieve Scope" warning is logged.
	 */
	@Test(timeout = 60000)
	public void compileAfterCancelledCompileSucceeds() {
		PackedCollection src = new PackedCollection(1019).fill(2.0);
		Evaluable<PackedCollection> ev = null;

		List<String> lines = new CopyOnWriteArrayList<>();
		Consumer<String> listener = lines::add;
		Hardware.console.addListener(listener);

		try {
			Thread.currentThread().interrupt();

			try {
				ev = uncompiled(src);
				ev.evaluate();
				Assert.fail("A kernel was compiled and evaluated on an interrupted thread");
			} catch (HardwareException expected) {
				Assert.assertTrue("Expected a cancelled compilation, got: " + expected,
						causedByCancelledCompile(expected));
			} finally {
				Thread.interrupted();
			}

			if (ev == null) {
				// The producer compiled inside get(); obtain the evaluable again
				ev = uncompiled(src);
			}

			PackedCollection result = ev.evaluate();
			assertEquals(14.5, result.toDouble(0));
			assertEquals(14.5, result.toDouble(1018));
		} finally {
			Hardware.console.removeListener(listener);
		}

		for (String line : lines) {
			Assert.assertFalse("The cancelled compile left scope state behind: " + line,
					line.contains("Repeated attempt to retrieve Scope"));
		}
	}

	/**
	 * A loop that compiles a distinct kernel in every iteration, interrupted from another
	 * thread, stops within a bounded number of iterations of the interrupt instead of
	 * running to completion.
	 */
	@Test(timeout = 120000)
	public void compileLoopStopsAtInterrupt() throws InterruptedException {
		Thread target = Thread.currentThread();
		int[] iteration = new int[1];
		int[] interruptedAt = { -1 };

		Thread interrupter = new Thread(() -> {
			try {
				Thread.sleep(500);
			} catch (InterruptedException e) {
				return;
			}

			synchronized (iteration) {
				interruptedAt[0] = iteration[0];
			}

			target.interrupt();
		}, "AcceleratedOperationInterruptTest-compile-interrupter");

		interrupter.start();

		int completed = -1;

		try {
			for (int i = 0; i < COMPILE_LOOP_KERNELS; i++) {
				synchronized (iteration) {
					iteration[0] = i;
				}

				// A distinct size per iteration, so no kernel is reused from an earlier one
				PackedCollection src = new PackedCollection(2003 + i);
				cp(src).multiply(3.0).add(0.25).get().evaluate();
			}

			Assert.fail("The compile loop ran to completion without reporting the interrupt");
		} catch (HardwareException expected) {
			synchronized (iteration) {
				completed = iteration[0];
			}
		} finally {
			Thread.interrupted();
			interrupter.join();
			Thread.interrupted();
		}

		Assert.assertTrue("The loop stopped before the interrupt was sent", interruptedAt[0] >= 0);
		Assert.assertTrue("The loop kept compiling for " + (completed - interruptedAt[0]) +
						" iterations after the interrupt",
				completed - interruptedAt[0] <= ITERATIONS_AFTER_INTERRUPT);
	}
}
