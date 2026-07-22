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

import io.almostrealism.code.Memory;
import io.almostrealism.code.MemoryProvider;
import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import io.almostrealism.streams.Semaphore;
import io.almostrealism.concurrent.Submittable;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CollectionProducerComputation;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.ProcessDetailsFactory;
import org.almostrealism.hardware.computations.Assignment;
import org.almostrealism.hardware.mem.HardwareMemoryProvider;
import org.almostrealism.hardware.mem.MemoryDataArgumentMap;
import org.almostrealism.hardware.metal.MetalCommandRunner;
import org.almostrealism.hardware.metal.MetalComputeContext;
import org.almostrealism.util.TestFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Verifies completion-gated destination reuse in {@link ProcessDetailsFactory}.
 *
 * <p>Sized argument destinations — the buffers created during argument preparation for
 * kernel arguments that are themselves dispatches (an explicitly isolated subtree, for
 * example) — were formerly allocated fresh on every invocation, since the removal of the
 * {@code ThreadLocal} destination provider left no reuse mechanism that was safe under
 * asynchronous argument evaluation. {@link ProcessDetailsFactory.DestinationSlot} restores
 * reuse gated on each invocation's completion chain instead of thread identity: a slot's
 * buffer is checked out per invocation and returned when the completion adopted by
 * {@code AcceleratedOperation.apply} fires, and an overlapping invocation that finds the
 * slot leased falls back to a fresh, unpooled allocation.</p>
 *
 * <p>The end-to-end tests drive the same construction path as production
 * ({@code ProcessDetailsFactory.construct} with an isolated argument, the pattern from
 * {@link ArgumentPreparationChainTest}), asserting result correctness on every iteration
 * and comparing live-allocation growth with reuse enabled and disabled. The slot tests
 * exercise the checkout contract directly.</p>
 */
public class DestinationReuseTest extends TestSuiteBase implements TestFeatures {

	/** Elements in each test vector. */
	private static final int SIZE = 256;

	/** Iterations for the repeated-evaluation loops. */
	private static final int ITERATIONS = 40;

	/** Destination-reuse flag captured before each test so the production default is restored after. */
	private boolean savedDestinationReuse;

	/**
	 * Enables destination reuse for the duration of each test. Reuse is disabled by default in
	 * production because it can deadlock the Metal completion path under concurrent multi-channel
	 * dispatch (see
	 * {@link org.almostrealism.hardware.mem.AcceleratedProcessDetails#releaseDestinationLeases()}),
	 * but this class exists to exercise the reuse mechanism, so it turns the flag on regardless of the
	 * default and {@link #restoreDestinationReuse() restores} it afterward.
	 */
	@Before
	public void enableDestinationReuse() {
		savedDestinationReuse = ProcessDetailsFactory.enableDestinationReuse;
		ProcessDetailsFactory.enableDestinationReuse = true;
	}

	/**
	 * Restores the destination-reuse flag to the value it held before the test (the production
	 * default), so enabling it here is not leaked to other test classes.
	 */
	@After
	public void restoreDestinationReuse() {
		ProcessDetailsFactory.enableDestinationReuse = savedDestinationReuse;
	}

	/**
	 * Builds an evaluable whose argument preparation must create a sized destination:
	 * the multiply is an explicitly isolated subtree, so it is evaluated as a nested
	 * dispatch during argument preparation rather than inlined into the reduction.
	 *
	 * @param weights the first input
	 * @param values  the second input
	 * @return an evaluable computing {@code sum(weights * values)}
	 */
	private Evaluable<PackedCollection> isolatedDotProduct(PackedCollection weights, PackedCollection values) {
		CollectionProducer mul = multiply(
				traverseEach(p(weights)), traverseEach(p(values)));
		Producer<PackedCollection> isolated =
				new CollectionProducerComputation.IsolatedProcess(mul);
		CollectionProducer reduce = sum(traverse(0, isolated));
		return reduce.get();
	}

	/**
	 * Returns the total number of live memory blocks across all hardware providers.
	 *
	 * @return total currently-allocated block count
	 */
	private int liveBlocks() {
		int total = 0;
		for (MemoryProvider<? extends Memory> provider :
				Hardware.getLocalHardware().getDataContext().getMemoryProviders()) {
			if (provider instanceof HardwareMemoryProvider) {
				total += ((HardwareMemoryProvider<?>) provider).getAllocatedCount();
			}
		}
		return total;
	}

	/**
	 * Evaluates the isolated dot product repeatedly with reuse enabled (the default),
	 * asserting the result is correct on every iteration — a reused destination that
	 * carried stale data or collided with an in-flight invocation would corrupt the
	 * reduction. Live-allocation growth over the warm loop is logged for reference;
	 * it is not asserted, because caller-owned outputs (which must stay fresh) and
	 * garbage awaiting collection make the live count an unreliable proxy for slot
	 * effectiveness. Slot reuse itself is pinned by {@link #destinationSlotContract()}.
	 */
	@Test(timeout = 120000)
	public void repeatedEvaluationRemainsCorrectWithReuse() {
		Assert.assertTrue(ProcessDetailsFactory.enableDestinationReuse);

		PackedCollection weights = new PackedCollection(shape(SIZE));
		PackedCollection values = new PackedCollection(shape(SIZE));
		PackedCollection out = new PackedCollection(shape(1));
		weights.fill(2.0);

		Evaluable<PackedCollection> ev = isolatedDotProduct(weights, values);

		for (int iter = 0; iter < ITERATIONS; iter++) {
			double v = 1.0 + iter;
			values.fill(v);

			ev.into(out).evaluate();
			assertEquals(2.0 * v * SIZE, out.toDouble(0));
		}

		int before = liveBlocks();
		for (int iter = 0; iter < ITERATIONS; iter++) {
			ev.into(out).evaluate();
		}
		log("liveBlockGrowth=" + (liveBlocks() - before)
				+ " over " + ITERATIONS + " warm iterations");
	}

	/**
	 * Runs the same warm loop with reuse disabled, pinning the flag as a genuine
	 * kill switch: results stay correct, and the loop completes on the legacy
	 * fresh-allocation path.
	 */
	@Test(timeout = 120000)
	public void disablingReuseRestoresPerInvocationAllocation() {
		PackedCollection weights = new PackedCollection(shape(SIZE));
		PackedCollection values = new PackedCollection(shape(SIZE));
		PackedCollection out = new PackedCollection(shape(1));
		weights.fill(3.0);
		values.fill(5.0);

		try {
			ProcessDetailsFactory.enableDestinationReuse = false;

			Evaluable<PackedCollection> ev = isolatedDotProduct(weights, values);

			for (int iter = 0; iter < ITERATIONS; iter++) {
				ev.into(out).evaluate();
				assertEquals(3.0 * 5.0 * SIZE, out.toDouble(0));
			}
		} finally {
			ProcessDetailsFactory.enableDestinationReuse = true;
		}
	}

	/**
	 * Verifies that plain {@code evaluate()} results are caller-owned: the output
	 * destination must never come from a reuse slot, since the caller may hold the
	 * returned collection across later invocations. A leased output produced exactly
	 * the failure {@code CpMemoryReuseProbeTest} caught in CI — the second
	 * evaluation overwrote the first evaluation's returned buffer.
	 */
	@Test(timeout = 120000)
	public void plainEvaluationReturnsCallerOwnedResults() {
		Assert.assertTrue(ProcessDetailsFactory.enableDestinationReuse);
		Assert.assertTrue("Portable results (strict mode) must be the default",
				ProcessDetailsFactory.enablePortableResults);

		PackedCollection weights = new PackedCollection(shape(SIZE));
		PackedCollection values = new PackedCollection(shape(SIZE));
		weights.fill(2.0);
		values.fill(3.0);

		Evaluable<PackedCollection> ev = isolatedDotProduct(weights, values);

		PackedCollection first = ev.evaluate();
		assertEquals(2.0 * 3.0 * SIZE, first.toDouble(0));

		values.fill(5.0);
		PackedCollection second = ev.evaluate();
		assertEquals(2.0 * 5.0 * SIZE, second.toDouble(0));

		Assert.assertNotSame("evaluate() must return a fresh result each invocation",
				first, second);
		assertEquals(2.0 * 3.0 * SIZE, first.toDouble(0));
	}

	/**
	 * Exercises the non-strict contract: with portable results disabled, plain
	 * {@code evaluate()} results may be served from a reuse slot, so a result is
	 * only guaranteed valid until the same operation's next invocation. What the
	 * contract does promise — a correct value on every immediate read — is asserted
	 * across a mutating loop; nothing here holds a result across invocations.
	 */
	@Test(timeout = 120000)
	public void transientResultsRemainCorrectOnImmediateRead() {
		PackedCollection weights = new PackedCollection(shape(SIZE));
		PackedCollection values = new PackedCollection(shape(SIZE));
		weights.fill(2.0);

		try {
			ProcessDetailsFactory.enablePortableResults = false;

			Evaluable<PackedCollection> ev = isolatedDotProduct(weights, values);

			for (int iter = 0; iter < ITERATIONS; iter++) {
				double v = 1.0 + iter;
				values.fill(v);

				PackedCollection result = ev.evaluate();
				assertEquals(2.0 * v * SIZE, result.toDouble(0));
			}
		} finally {
			ProcessDetailsFactory.enablePortableResults = true;
		}
	}

	/**
	 * Evaluates the same evaluable from two threads concurrently. Overlapping
	 * invocations contend for the same destination slots; whichever loses the
	 * checkout must fall back to a fresh allocation rather than sharing, so both
	 * threads must observe correct results on every iteration.
	 *
	 * @throws Exception if a worker thread fails
	 */
	@Test(timeout = 120000)
	public void concurrentEvaluationNeverSharesLeasedDestinations() throws Exception {
		PackedCollection weights = new PackedCollection(shape(SIZE));
		PackedCollection values = new PackedCollection(shape(SIZE));
		weights.fill(2.0);
		values.fill(7.0);

		Evaluable<PackedCollection> ev = isolatedDotProduct(weights, values);
		PackedCollection warm = new PackedCollection(shape(1));
		ev.into(warm).evaluate();

		Thread[] workers = new Thread[2];
		Throwable[] failures = new Throwable[workers.length];

		for (int t = 0; t < workers.length; t++) {
			int index = t;
			workers[t] = new Thread(() -> {
				PackedCollection out = new PackedCollection(shape(1));
				try {
					for (int iter = 0; iter < ITERATIONS; iter++) {
						ev.into(out).evaluate();
						assertEquals(2.0 * 7.0 * SIZE, out.toDouble(0));
					}
				} catch (Throwable e) {
					failures[index] = e;
				}
			});
			workers[t].start();
		}

		for (Thread worker : workers) worker.join();

		for (Throwable failure : failures) {
			if (failure != null) {
				throw new AssertionError("Concurrent evaluation failed", failure);
			}
		}
	}

	/**
	 * Pins the {@link Semaphore#whenComplete(Runnable)} contract on Metal directly:
	 * registering the callback must not commit the still-open command buffer or wait
	 * for it (the callback has not run and no commit was recorded), and the callback
	 * must run once the buffer genuinely completes. The eager
	 * {@link Semaphore#onComplete(Runnable)} default would fail the first half —
	 * its waiting thread forces a host commit — which is how lease release collapsed
	 * command-buffer batching for realtime playback on the mac CI runner.
	 *
	 * @throws Exception if interrupted while waiting for the callback
	 */
	@Test(timeout = 120000)
	public void whenCompleteImposesNoCommit() throws Exception {
		MetalComputeContext metal = SemaphoreChainBatchingTest.metalContext();
		if (metal == null) {
			log("skipping, no MetalComputeContext available");
			return;
		}

		PackedCollection weights = new PackedCollection(shape(SIZE));
		PackedCollection values = new PackedCollection(shape(SIZE));
		PackedCollection out = new PackedCollection(shape(1));
		weights.fill(2.0);
		values.fill(3.0);

		CollectionProducer mul = multiply(
				traverseEach(p(weights)), traverseEach(p(values)));
		Assignment<PackedCollection> assign = new Assignment<>(1,
				() -> (Evaluable<PackedCollection>) args -> out,
				sum(traverse(0, mul)), List.of(ComputeRequirement.MTL));

		boolean aggregation = MemoryDataArgumentMap.enableArgumentAggregation;
		MemoryDataArgumentMap.enableArgumentAggregation = false;

		try {
			Hardware.getLocalHardware().getComputer()
					.pushRequirements(List.of(ComputeRequirement.MTL));
			Submittable op;

			try {
				op = (Submittable) assign.get();
			} finally {
				Hardware.getLocalHardware().getComputer().popRequirements();
			}

			Semaphore warm = op.submit(null);
			if (warm != null) warm.waitFor();

			MetalCommandRunner runner = metal.getCommandRunner();
			long total0 = runner.getCommitCount();

			Semaphore sem = op.submit(null);
			Assert.assertNotNull(sem);

			CountDownLatch ran = new CountDownLatch(1);
			sem.whenComplete(ran::countDown);

			Assert.assertTrue("Registering a whenComplete callback must not commit",
					runner.getCommitCount() == total0);
			Assert.assertTrue("The callback must not have run while the buffer is open",
					ran.getCount() == 1);

			sem.waitFor();
			Assert.assertTrue("The callback must run once the buffer completes",
					ran.await(10, TimeUnit.SECONDS));
		} finally {
			MemoryDataArgumentMap.enableArgumentAggregation = aggregation;
		}
	}

	/**
	 * Exercises the slot checkout contract directly: the same buffer is reused across
	 * sequential acquire/release cycles, a leased slot refuses a second checkout, a
	 * size change replaces the buffer, and an externally destroyed buffer is detected
	 * and replaced.
	 */
	@Test(timeout = 120000)
	public void destinationSlotContract() {
		ProcessDetailsFactory.DestinationSlot slot = new ProcessDetailsFactory.DestinationSlot();

		MemoryData first = slot.acquire(SIZE, s -> new PackedCollection(shape(s)));
		Assert.assertNotNull(first);
		Assert.assertNull("A leased slot must refuse a second checkout",
				slot.acquire(SIZE, s -> new PackedCollection(shape(s))));

		slot.release();
		MemoryData second = slot.acquire(SIZE, s -> new PackedCollection(shape(s)));
		Assert.assertSame("Same-size checkout after release must reuse the buffer",
				first, second);
		slot.release();

		MemoryData resized = slot.acquire(SIZE * 2, s -> new PackedCollection(shape(s)));
		Assert.assertNotSame("A size change must replace the buffer", first, resized);
		Assert.assertNull("The replaced buffer must have been destroyed", first.getMem());
		slot.release();

		resized.destroy();
		MemoryData replaced = slot.acquire(SIZE * 2, s -> new PackedCollection(shape(s)));
		Assert.assertNotNull(replaced);
		Assert.assertNotSame("An externally destroyed buffer must be replaced",
				resized, replaced);
		Assert.assertNotNull(replaced.getMem());
		slot.release();

		slot.destroy();
		Assert.assertNull("Slot destroy must release the cached buffer", replaced.getMem());
	}
}
