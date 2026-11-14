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
import io.almostrealism.concurrent.DefaultLatchSemaphore;
import io.almostrealism.concurrent.Submittable;
import io.almostrealism.streams.Semaphore;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.mem.MemoryDataArgumentMap;
import org.almostrealism.hardware.metal.MetalCommand;
import org.almostrealism.hardware.metal.MetalCommandRunner;
import org.almostrealism.hardware.metal.MetalComputeContext;
import org.almostrealism.hardware.metal.MetalSemaphore;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

/**
 * Tests that {@link MetalCommandRunner} orders a dispatch after an earlier dispatch that has
 * already finished without encoding a GPU wait, and still encodes the wait while the earlier
 * dispatch may be running.
 *
 * <p>A wait for a timeline value the GPU has already reached does not hold the GPU back, but
 * encoded between two dispatches of a command buffer it still splits the buffer's work, which
 * costs the GPU far more than a small kernel. A loop that waits on the host at the end of every
 * step, and chains the next step on the last completion, would otherwise pay that for every
 * dependency of the new step on the previous one. The tests read the runner's
 * {@link MetalCommandRunner#getOrderingWaitCount() ordering wait} and
 * {@link MetalCommandRunner#getFinishedDependencyCount() finished dependency} counts, so they pin
 * what is encoded rather than how long it takes.</p>
 */
public class MetalCommandRunnerFinishedDependencyTest extends TestSuiteBase {
	/** A dispatch that encodes nothing. */
	private static final MetalCommand NOTHING = buffer -> { };

	/**
	 * A dependency on a dispatch whose command buffer a host wait has drained is honored without
	 * encoding a wait.
	 */
	@Test(timeout = 60000)
	public void dependentOfDrainedDispatchEncodesNoWait() {
		MetalComputeContext metal = SemaphoreChainBatchingTest.metalContext();
		if (metal == null) {
			log("skipping, no MetalComputeContext available");
			return;
		}

		MetalCommandRunner runner = new MetalCommandRunner(metal.getMtlQueue());

		try {
			MetalSemaphore first = runner.submit(null, NOTHING, null, null);
			first.waitFor();

			long waits = runner.getOrderingWaitCount();
			long finished = runner.getFinishedDependencyCount();

			MetalSemaphore dependent = runner.submit(null, NOTHING, first, null);
			Assert.assertNotSame(first.getCommandBuffer(), dependent.getCommandBuffer());
			Assert.assertEquals(waits, runner.getOrderingWaitCount());
			Assert.assertEquals(finished + 1, runner.getFinishedDependencyCount());

			dependent.waitFor();
		} finally {
			runner.destroy();
		}
	}

	/**
	 * A completion merged from a dispatch in a drained buffer and one in the open buffer carries the
	 * drained dispatch as its prior buffer value; a dependent encoded into the open buffer, where the
	 * prior value would be waited for between two dispatches, encodes no wait for it.
	 */
	@Test(timeout = 60000)
	public void mergedCompletionOverDrainedDispatchEncodesNoWait() {
		MetalComputeContext metal = SemaphoreChainBatchingTest.metalContext();
		if (metal == null) {
			log("skipping, no MetalComputeContext available");
			return;
		}

		MetalCommandRunner runner = new MetalCommandRunner(metal.getMtlQueue());

		try {
			MetalSemaphore drained = runner.submit(null, NOTHING, null, null);
			drained.waitFor();

			MetalSemaphore open = runner.submit(null, NOTHING, null, null);
			MetalSemaphore both = (MetalSemaphore) Semaphore.all(List.of(drained, open));
			Assert.assertSame(open.getCommandBuffer(), both.getCommandBuffer());
			Assert.assertEquals(drained.getValue(), both.getPriorBufferValue());

			long waits = runner.getOrderingWaitCount();
			long finished = runner.getFinishedDependencyCount();

			MetalSemaphore dependent = runner.submit(null, NOTHING, both, null);
			Assert.assertSame(open.getCommandBuffer(), dependent.getCommandBuffer());
			Assert.assertEquals(waits, runner.getOrderingWaitCount());
			Assert.assertEquals(finished + 1, runner.getFinishedDependencyCount());

			dependent.waitFor();
		} finally {
			runner.destroy();
		}
	}

	/**
	 * A dependency on a dispatch whose buffer has not finished &mdash; here a buffer held back from
	 * the GPU by a host-signaled bridge that has not been signaled &mdash; is still honored by a GPU
	 * wait, and the dependent completes once the bridge is signaled.
	 */
	@Test(timeout = 60000)
	public void dependentOfUnfinishedDispatchStillWaits() {
		MetalComputeContext metal = SemaphoreChainBatchingTest.metalContext();
		if (metal == null) {
			log("skipping, no MetalComputeContext available");
			return;
		}

		boolean bridges = MetalCommandRunner.enableHostSignaledBridges;
		MetalCommandRunner.enableHostSignaledBridges = true;
		MetalCommandRunner runner = new MetalCommandRunner(metal.getMtlQueue());
		DefaultLatchSemaphore gate = new DefaultLatchSemaphore((Semaphore) null, 1);
		DefaultLatchSemaphore secondGate = new DefaultLatchSemaphore((Semaphore) null, 1);

		try {
			MetalSemaphore gated = runner.submit(null, NOTHING, gate, null);

			// A second bridged dispatch closes the gated buffer, which waits for its bridge
			MetalSemaphore other = runner.submit(null, NOTHING, secondGate, null);
			Assert.assertNotSame(gated.getCommandBuffer(), other.getCommandBuffer());

			long waits = runner.getOrderingWaitCount();
			long finished = runner.getFinishedDependencyCount();

			MetalSemaphore dependent = runner.submit(null, NOTHING, gated, null);
			Assert.assertSame(other.getCommandBuffer(), dependent.getCommandBuffer());
			Assert.assertEquals(waits + 1, runner.getOrderingWaitCount());
			Assert.assertEquals(finished, runner.getFinishedDependencyCount());

			gate.countDown();
			secondGate.countDown();
			dependent.waitFor();
		} finally {
			gate.countDown();
			secondGate.countDown();
			runner.destroy();
			MetalCommandRunner.enableHostSignaledBridges = bridges;
		}
	}

	/**
	 * A kernel chained on a copy that a host wait has already completed reads the copy's result,
	 * through the context's own runner, with no wait encoded for the copy.
	 */
	@Test(timeout = 60000)
	public void kernelAfterHostWaitReadsCompletedResult() {
		MetalComputeContext metal = SemaphoreChainBatchingTest.metalContext();
		if (metal == null) {
			log("skipping, no MetalComputeContext available");
			return;
		}

		boolean aggregation = MemoryDataArgumentMap.enableArgumentAggregation;
		MemoryDataArgumentMap.enableArgumentAggregation = false;

		int n = 16;

		try (PackedCollection src = new PackedCollection(n);
			 PackedCollection mid = new PackedCollection(n);
			 PackedCollection dst = new PackedCollection(n)) {
			rand(src.getShape()).add(1.0).into(src.traverseEach()).evaluate();

			Submittable fill = SemaphoreChainBatchingTest.copyKernel(src, mid, n, ComputeRequirement.MTL);
			Submittable read = SemaphoreChainBatchingTest.copyKernel(mid, dst, n, ComputeRequirement.MTL);
			MetalCommandRunner runner = metal.getCommandRunner();

			Semaphore filled = fill.submit(null);
			filled.waitFor();

			long waits = runner.getOrderingWaitCount();
			read.submit(filled).waitFor();
			Assert.assertEquals(waits, runner.getOrderingWaitCount());

			for (int i = 0; i < n; i++) {
				Assert.assertEquals(src.toDouble(i), dst.toDouble(i), 0.0);
			}
		} finally {
			MemoryDataArgumentMap.enableArgumentAggregation = aggregation;
		}
	}
}
