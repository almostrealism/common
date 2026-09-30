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

import io.almostrealism.concurrent.CompletionConsumer;
import io.almostrealism.concurrent.DefaultLatchSemaphore;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.FixedEvaluable;
import io.almostrealism.streams.Semaphore;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.mem.MemoryDataArgumentMap;
import org.almostrealism.hardware.mem.MemoryDataDestination;
import org.almostrealism.util.TestFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Verifies that kernel arguments whose value is fixed &mdash; a {@link FixedEvaluable}
 * such as the provider of a collection, or the handle that
 * {@link MemoryDataDestination#into(Object)} returns for a sized destination &mdash; are
 * bound directly when an invocation is constructed, instead of being requested
 * asynchronously.
 *
 * <p>An asynchronous request of a plain evaluable starts a dedicated thread per invocation,
 * and a destination handle treated as dispatch-backed first waits on the host for the
 * invocation's dependency. Neither is needed for a value that is already known, and both
 * sit on the path of every kernel invocation.</p>
 */
public class FixedArgumentBindingTest extends TestSuiteBase implements TestFeatures {
	/**
	 * The destination handle of a {@link MemoryDataDestination} is a {@link FixedEvaluable}
	 * that hands back exactly the memory it was given.
	 */
	@Test(timeout = 30000)
	public void destinationHandleIsFixed() {
		PackedCollection destination = new PackedCollection(shape(4));
		MemoryDataDestination<PackedCollection> producer =
				new MemoryDataDestination<>(size -> new PackedCollection(shape(size, 4)));

		Evaluable<PackedCollection> handle = producer.into(destination);
		Assert.assertTrue(handle instanceof FixedEvaluable);
		Assert.assertSame(destination, handle.evaluate());
	}

	/**
	 * Requests a compiled kernel, whose inputs are provided collections and whose output is
	 * a sized destination, with a dependency that has not completed. The result must be
	 * delivered, together with the dispatch's completion, while the dependency is still
	 * pending: binding the arguments reads none of the memory the dependency could be
	 * writing, so the only thing that has to wait for it is the kernel. That the kernel does
	 * wait is checked by changing an input after the request and before the dependency
	 * completes, which the kernel must observe.
	 *
	 * <p>The inputs are larger than {@link MemoryDataArgumentMap#maxAggregateLength}, so the
	 * kernel binds them directly. An aggregated input is copied into the aggregate first,
	 * and a backend without an asynchronous copy performs that copy by waiting for the
	 * dependency on the host; that is a property of the copy, not of argument binding.</p>
	 *
	 * @throws InterruptedException if interrupted while waiting for the delivery
	 */
	@Test(timeout = 60000)
	public void requestIsNotHeldUntilDependencyCompletes() throws InterruptedException {
		int size = 2 * MemoryDataArgumentMap.maxAggregateLength;

		PackedCollection a = new PackedCollection(shape(size));
		PackedCollection b = new PackedCollection(shape(size));
		a.fill(2.0);
		b.fill(3.0);

		Evaluable<PackedCollection> ev = add(traverseEach(p(a)), traverseEach(p(b))).get();

		PackedCollection primed = ev.evaluate();
		for (int i = 0; i < size; i++) {
			assertEquals(5.0, primed.toDouble(i));
		}

		DefaultLatchSemaphore pending = new DefaultLatchSemaphore(
				new OperationMetadata("pendingDependency", "Dependency that has not completed"), 1);
		CountDownLatch delivered = new CountDownLatch(1);
		AtomicReference<PackedCollection> result = new AtomicReference<>();
		AtomicReference<Semaphore> completion = new AtomicReference<>();

		try {
			ev.async().request(new Object[0], pending,
					(CompletionConsumer<PackedCollection>) (value, done) -> {
						result.set(value);
						completion.set(done);
						delivered.countDown();
					});

			Assert.assertTrue("The result was not delivered while the dependency was pending",
					delivered.await(30, TimeUnit.SECONDS));

			a.fill(7.0);
		} finally {
			pending.countDown();
		}

		if (completion.get() != null) {
			completion.get().waitFor();
		}

		for (int i = 0; i < size; i++) {
			assertEquals(10.0, result.get().toDouble(i));
		}
	}

	/**
	 * Evaluates a compiled kernel whose arguments are all fixed and confirms that the number of
	 * threads started does not scale with the number of evaluations.
	 *
	 * <p>Requesting a plain evaluable starts one thread per invocation (its {@link Evaluable#async()}
	 * executor starts a thread per request), so a regression that stopped binding fixed arguments
	 * directly would start threads in proportion to the evaluation count. Binding them directly
	 * starts none. The test measures a small batch and a batch four times larger from the same warm
	 * kernel: a per-invocation regression makes the larger batch start roughly four times as many
	 * threads as the smaller one, while direct binding leaves the two counts equal. Asserting that
	 * the larger batch starts no more than a small fixed slack above the smaller batch rejects the
	 * regression without depending on an exact zero &mdash; the JVM may start a few unrelated threads
	 * (JIT, GC) during either window &mdash; and, unlike an absolute threshold, cannot be satisfied by
	 * a regression that merely starts fewer threads per invocation.</p>
	 */
	@Test(timeout = 120000)
	public void repeatedEvaluationDoesNotStartThreadPerArgument() {
		int baseEvaluations = 100;
		int largeEvaluations = 4 * baseEvaluations;

		// Threads the JVM may start for reasons unrelated to argument binding (JIT, GC) during
		// either measurement window; far below the one-thread-per-invocation a regression starts.
		int slack = 16;

		PackedCollection a = new PackedCollection(shape(16));
		PackedCollection b = new PackedCollection(shape(16));
		a.fill(1.5);
		b.fill(0.25);

		Evaluable<PackedCollection> ev = multiply(traverseEach(p(a)), traverseEach(p(b))).get();

		// Warm up so compilation and the lazily created executor threads are excluded
		for (int i = 0; i < 10; i++) {
			ev.evaluate();
		}

		ThreadMXBean threads = ManagementFactory.getThreadMXBean();

		long baseStarted = evaluateAndCountStartedThreads(ev, baseEvaluations, threads);
		long largeStarted = evaluateAndCountStartedThreads(ev, largeEvaluations, threads);

		log("baseEvaluations=" + baseEvaluations + " baseThreadsStarted=" + baseStarted +
				" largeEvaluations=" + largeEvaluations + " largeThreadsStarted=" + largeStarted);

		Assert.assertTrue("Threads started scaled with evaluations: " + baseStarted + " over " +
						baseEvaluations + " but " + largeStarted + " over " + largeEvaluations,
				largeStarted <= baseStarted + slack);
	}

	/**
	 * Evaluates {@code ev} the given number of times and returns how many threads the JVM started
	 * during those evaluations, checking the result of each evaluation along the way.
	 *
	 * @param ev          the compiled kernel to evaluate
	 * @param evaluations the number of evaluations to perform
	 * @param threads     the thread management bean used to count started threads
	 * @return the number of threads started while performing the evaluations
	 */
	private long evaluateAndCountStartedThreads(Evaluable<PackedCollection> ev, int evaluations,
												ThreadMXBean threads) {
		long startedBefore = threads.getTotalStartedThreadCount();

		for (int i = 0; i < evaluations; i++) {
			PackedCollection result = ev.evaluate();
			for (int j = 0; j < 16; j++) {
				assertEquals(0.375, result.toDouble(j));
			}
		}

		return threads.getTotalStartedThreadCount() - startedBefore;
	}
}
