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

import io.almostrealism.code.DataContext;
import io.almostrealism.code.InstructionSet;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.scope.Scope;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.ctx.AbstractComputeContext;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reproduces the review finding that every {@link AbstractComputeContext} registers its
 * executor {@link ThreadGroup} in the static registry backing {@link
 * AbstractComputeContext#isAnyExecutorThread()}, but no {@code destroy()} path ever removed
 * it. A compute context scoped to a single {@code computeContext(...)} call (see {@code
 * MetalDataContext.computeContext}) is created and destroyed repeatedly, so without cleanup
 * the registry accumulates a permanently reachable {@link ThreadGroup} (and, once its pool
 * has started, worker threads) for every such call over the process lifetime.
 *
 * <p>Unlike most tests in this project, this one does not extend {@code TestSuiteBase}: that
 * class lives in the engine layer, which sits above this module.</p>
 *
 * @see AbstractComputeContext#isAnyExecutorThread()
 */
public class AbstractComputeContextExecutorLifecycleTest {

	/**
	 * Minimal {@link AbstractComputeContext} subclass used only to exercise the shared
	 * executor lifecycle logic; none of the abstract members it must supply are exercised
	 * by this test beyond the inherited {@link AbstractComputeContext#destroy()}.
	 */
	private static class TestComputeContext extends AbstractComputeContext<DataContext<MemoryData>> {
		/** Creates a context with no backing {@link DataContext}, which this test never reads. */
		TestComputeContext() {
			super(null);
		}

		@Override
		public LanguageOperations getLanguage() { return null; }

		@Override
		public InstructionSet deliver(Scope scope) { return null; }

		@Override
		public boolean isCPU() { return true; }
	}

	/**
	 * Before {@link AbstractComputeContext#destroy()}, a thread belonging to a context's
	 * executor pool is recognized by {@link AbstractComputeContext#isAnyExecutorThread()}.
	 * After {@code destroy()}, that same {@link ThreadGroup} must no longer be recognized,
	 * proving the registry entry was actually removed rather than left to accumulate.
	 */
	@Test(timeout = 10000)
	public void destroyRemovesExecutorGroupFromRegistry() throws InterruptedException {
		TestComputeContext context = new TestComputeContext();

		AtomicReference<ThreadGroup> workerGroup = new AtomicReference<>();
		AtomicReference<Boolean> recognizedBeforeDestroy = new AtomicReference<>();
		CountDownLatch ran = new CountDownLatch(1);

		context.runLater(() -> {
			workerGroup.set(Thread.currentThread().getThreadGroup());
			recognizedBeforeDestroy.set(AbstractComputeContext.isAnyExecutorThread());
			ran.countDown();
		});

		Assert.assertTrue("runLater task should have executed", ran.await(5, TimeUnit.SECONDS));
		Assert.assertTrue("a thread from the context's own pool must be recognized before destroy()",
				recognizedBeforeDestroy.get());

		context.destroy();

		CountDownLatch checked = new CountDownLatch(1);
		AtomicReference<Boolean> recognizedAfterDestroy = new AtomicReference<>();
		Thread probe = new Thread(workerGroup.get(), () -> {
			recognizedAfterDestroy.set(AbstractComputeContext.isAnyExecutorThread());
			checked.countDown();
		});
		probe.start();

		Assert.assertTrue("probe thread should have executed", checked.await(5, TimeUnit.SECONDS));
		Assert.assertFalse("the destroyed context's ThreadGroup must be removed from the registry, " +
						"otherwise it (and any worker threads started within it) remain permanently reachable",
				recognizedAfterDestroy.get());
	}

	/**
	 * {@link AbstractComputeContext#destroy()} must shut down the executor so that further
	 * work submitted through {@link AbstractComputeContext#runLater(Runnable)} is rejected
	 * rather than silently keeping the underlying thread pool alive.
	 */
	@Test(timeout = 10000)
	public void destroyShutsDownExecutor() {
		TestComputeContext context = new TestComputeContext();
		context.destroy();

		try {
			context.runLater(() -> { });
			Assert.fail("runLater() after destroy() should be rejected by the shut-down executor");
		} catch (RejectedExecutionException expected) {
			// Expected: the executor was shut down by destroy().
		}
	}
}
