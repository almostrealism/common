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

import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.concurrent.Submittable;
import io.almostrealism.streams.LatchSemaphore;
import io.almostrealism.streams.Semaphore;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.DestinationEvaluable;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.hardware.OperationListRunner;
import org.almostrealism.hardware.metal.MetalCommandRunner;
import org.almostrealism.hardware.metal.MetalComputeContext;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Verifies that an {@link OperationList} chains its assignments of compiled kernels into
 * provider destinations instead of completing each of them on the host.
 *
 * <p>{@link org.almostrealism.hardware.computations.Assignment#get()} returns a
 * {@link DestinationEvaluable} for such an assignment. As a {@link Submittable}, it is issued by
 * {@link OperationListRunner} after the previous member's completion, and only the end of the
 * list waits: the results must still reflect the order of the list, and on Metal the whole list
 * must share one command buffer rather than commit once for every member.</p>
 */
public class DestinationEvaluableChainingTest extends TestSuiteBase {
	/** Number of elements in each buffer. */
	private static final int SIZE = 16;

	/**
	 * A second assignment reads the destination the first one writes, so its result is only
	 * correct if the chain orders it after the first. Both members must be issued as
	 * {@link DestinationEvaluable}s, which is the path this test exists to cover.
	 */
	@Test(timeout = 60000)
	public void chainedDestinationAssignmentsAreOrdered() {
		PackedCollection src = new PackedCollection(SIZE);
		PackedCollection mid = new PackedCollection(SIZE);
		PackedCollection dst = new PackedCollection(SIZE);
		rand(src.getShape()).add(1.0).into(src.traverseEach()).evaluate();

		OperationListRunner runner = chain(src, mid, dst, null);

		for (Runnable member : runner.getOperations()) {
			assertTrue("Each member must be a DestinationEvaluable, but found " +
					member.getClass().getSimpleName(), member instanceof DestinationEvaluable);
			assertTrue(member instanceof Submittable);
		}

		runner.run();
		assertChained(src, dst);
	}

	/**
	 * On Metal, chaining the members of the list keeps them in one command buffer: running the
	 * list commits exactly once, for the wait at its end, where completing each member on the
	 * host would commit for every member.
	 */
	@Test(timeout = 60000)
	public void chainedDestinationAssignmentsCommitOnce() {
		MetalComputeContext metal = SemaphoreChainBatchingTest.metalContext();
		Assume.assumeTrue("Requires a Metal compute context", metal != null);

		PackedCollection src = new PackedCollection(SIZE);
		PackedCollection mid = new PackedCollection(SIZE);
		PackedCollection dst = new PackedCollection(SIZE);
		rand(src.getShape()).add(1.0).into(src.traverseEach()).evaluate();

		OperationListRunner runner = chain(src, mid, dst, List.of(ComputeRequirement.MTL));

		// The first run compiles and prepares every member
		runner.run();
		assertChained(src, dst);

		dst.clear();

		MetalCommandRunner commands = metal.getCommandRunner();
		long baseline = commands.getCommitCount();

		runner.run();

		assertEquals((double) (baseline + 1), (double) commands.getCommitCount());
		assertChained(src, dst);
	}

	/**
	 * A {@link DestinationEvaluable} whose operation is evaluated on the host has no device
	 * dispatch to chain into, so {@link DestinationEvaluable#submit} must wait for its dependency
	 * before evaluating, complete the evaluation, and return no completion. The dependency here
	 * supplies the value the operation reads, so a result written before the dependency was
	 * waited would be zero rather than the supplied value.
	 */
	@Test(timeout = 60000)
	public void hostEvaluationWaitsForDependencyAndCompletes() {
		PackedCollection value = new PackedCollection(1);
		PackedCollection dst = new PackedCollection(shape(SIZE, 1).traverse(1));
		DestinationEvaluable<PackedCollection> evaluable =
				new DestinationEvaluable<>(args -> value, dst);

		AtomicInteger waits = new AtomicInteger();
		Semaphore dependsOn = new Semaphore() {
			@Override
			public void waitFor() {
				waits.incrementAndGet();
				value.setMem(0, 7.0);
			}
		};

		Assert.assertNull(evaluable.submit(dependsOn));
		assertEquals(1, waits.get());
		for (int i = 0; i < SIZE; i++) {
			assertEquals(7.0, dst.toDouble(i));
		}

		value.setMem(0, 3.0);
		Assert.assertNull("Beginning a chain on the host must also complete without a handle",
				evaluable.submit(null));
		assertEquals(1, waits.get());
		for (int i = 0; i < SIZE; i++) {
			assertEquals(3.0, dst.toDouble(i));
		}
	}

	/**
	 * An interrupt of the submitting thread must not let a host evaluation run before its
	 * dependency has fired: {@link DestinationEvaluable#submit} would otherwise evaluate memory
	 * the dependency has not yet produced, and the next member of the list would read a stale
	 * result. The dependency here supplies the value the operation reads and fires only after
	 * the submitting thread has been interrupted both before and during the wait, so a result of
	 * zero means the evaluation ran early. The interrupt status must be restored afterwards.
	 */
	@Test(timeout = 60000)
	public void interruptedHostEvaluationWaitsForDependency() throws InterruptedException {
		PackedCollection value = new PackedCollection(1);
		PackedCollection dst = new PackedCollection(shape(SIZE, 1).traverse(1));
		DestinationEvaluable<PackedCollection> evaluable =
				new DestinationEvaluable<>(args -> value, dst);

		LatchSemaphore dependsOn = new LatchSemaphore(1);
		AtomicReference<Semaphore> returned = new AtomicReference<>(dependsOn);
		AtomicBoolean interruptKept = new AtomicBoolean();
		AtomicReference<Throwable> failure = new AtomicReference<>();

		Thread submitter = new Thread(() -> {
			try {
				Thread.currentThread().interrupt();
				returned.set(evaluable.submit(dependsOn));
				interruptKept.set(Thread.interrupted());
			} catch (Throwable e) {
				failure.set(e);
			}
		}, "host submit");
		submitter.setDaemon(true);
		submitter.start();

		submitter.join(200);
		Assert.assertTrue("an interrupt pending on entry must not end the wait", submitter.isAlive());
		submitter.interrupt();
		submitter.join(200);
		Assert.assertTrue("an interrupt during the wait must not end it", submitter.isAlive());

		value.setMem(0, 5.0);
		dependsOn.countDown();
		submitter.join(30000);

		Assert.assertFalse(submitter.isAlive());
		Assert.assertNull(failure.get());
		Assert.assertNull(returned.get());
		Assert.assertTrue("the interrupt status must be restored", interruptKept.get());
		for (int i = 0; i < SIZE; i++) {
			assertEquals(5.0, dst.toDouble(i));
		}
	}

	/**
	 * Builds the uncompiled list {@code mid = 2 * src; dst = mid + 1} and returns its runner.
	 *
	 * @param src          the buffer the first assignment reads
	 * @param mid          the buffer the first assignment writes and the second reads
	 * @param dst          the buffer the second assignment writes
	 * @param requirements the compute requirements for the list, or {@code null} for none
	 * @return the runner that executes the two assignments in order
	 */
	private OperationListRunner chain(PackedCollection src, PackedCollection mid, PackedCollection dst,
									  List<ComputeRequirement> requirements) {
		OperationList list = new OperationList("Chained destination assignments", false);
		list.add(a(p(mid), cp(src).multiply(2.0)));
		list.add(a(p(dst), cp(mid).add(1.0)));

		if (requirements != null) {
			list.setComputeRequirements(requirements);
		}

		Runnable runnable = list.get();
		assertTrue("The list must run its members separately, but compiled to " +
				runnable.getClass().getSimpleName(), runnable instanceof OperationListRunner);
		return (OperationListRunner) runnable;
	}

	/**
	 * Asserts that every element of {@code dst} is {@code 2 * src + 1}.
	 *
	 * @param src the input buffer
	 * @param dst the output buffer
	 */
	private void assertChained(PackedCollection src, PackedCollection dst) {
		for (int i = 0; i < SIZE; i++) {
			assertEquals(2.0 * src.toDouble(i) + 1.0, dst.toDouble(i));
		}
	}
}
