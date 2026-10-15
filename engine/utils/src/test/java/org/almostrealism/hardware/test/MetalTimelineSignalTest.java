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
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.streams.Semaphore;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.mem.MemoryDataArgumentMap;
import org.almostrealism.hardware.metal.MetalCommandRunner;
import org.almostrealism.hardware.metal.MetalComputeContext;
import org.almostrealism.hardware.metal.MetalSemaphore;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Verifies how {@link MetalCommandRunner} releases dependent dispatches that wait on the GPU for
 * dispatches in an earlier, already committed command buffer.
 *
 * <p>The runner gives every dispatch its own timeline value but signals the timeline once per
 * command buffer, when the buffer is committed, to the value of the buffer's last dispatch. A
 * dependent encoded into a later buffer waits for its dependency's own value, which is therefore
 * reached when the dependency's whole buffer has completed. Each test interleaves two copy chains
 * in one command buffer, commits it, and then issues the second step of each chain from a later
 * buffer: one waits on the buffer's first dispatch, the other on its last. If the buffer did not
 * signal at least the value of its last dispatch, one of those waits would never be released and
 * its dispatch would never complete.</p>
 */
public class MetalTimelineSignalTest extends TestSuiteBase {
	/** Elements copied by each kernel. */
	private static final int SIZE = 16;

	/**
	 * Dependents in a later command buffer, waiting on the first and on the last dispatch of a
	 * committed buffer, are both released and read their dependencies' results.
	 */
	@Test(timeout = 60000)
	public void dependentsOfCommittedBufferComplete() {
		MetalComputeContext metal = SemaphoreChainBatchingTest.metalContext();
		if (metal == null) {
			log("skipping, no MetalComputeContext available");
			return;
		}

		boolean aggregation = MemoryDataArgumentMap.enableArgumentAggregation;
		MemoryDataArgumentMap.enableArgumentAggregation = false;

		try {
			CopyChain first = new CopyChain();
			CopyChain last = new CopyChain();
			MetalCommandRunner runner = metal.getCommandRunner();

			// Start from a drained runner, so both first steps share one command buffer
			runner.submit(null, buffer -> { }, null, null).waitFor();

			MetalSemaphore firstWritten = (MetalSemaphore) first.fill.submit(null);
			MetalSemaphore lastWritten = (MetalSemaphore) last.fill.submit(null);
			commitSharedBuffer(runner, firstWritten, lastWritten);

			Semaphore firstRead = first.read.submit(firstWritten);
			Semaphore lastRead = last.read.submit(lastWritten);
			firstRead.waitFor();
			lastRead.waitFor();

			first.assertCopied();
			last.assertCopied();
		} finally {
			MemoryDataArgumentMap.enableArgumentAggregation = aggregation;
		}
	}

	/**
	 * The same dependents, with the committed buffer held on the GPU by a foreign gate: neither
	 * is released before the gate opens and the earlier buffer has completed, and both then read
	 * their dependencies' results.
	 *
	 * @throws InterruptedException if waiting for the gate thread is interrupted
	 */
	// TODO(review): javadoc claims dependents are not released before the gate opens, but nothing asserts that ordering
	@Test(timeout = 60000)
	public void dependentsOfGatedBufferWaitForGate() throws InterruptedException {
		MetalComputeContext metal = SemaphoreChainBatchingTest.metalContext();
		if (metal == null) {
			log("skipping, no MetalComputeContext available");
			return;
		}

		boolean aggregation = MemoryDataArgumentMap.enableArgumentAggregation;
		MemoryDataArgumentMap.enableArgumentAggregation = false;

		try {
			CopyChain first = new CopyChain();
			CopyChain last = new CopyChain();
			MetalCommandRunner runner = metal.getCommandRunner();

			// A foreign dependency starts a fresh command buffer, held on the GPU by the gate
			DefaultLatchSemaphore gate = new DefaultLatchSemaphore(
					new OperationMetadata("gate", "holds the earlier buffer on the GPU"), 1);
			MetalSemaphore firstWritten = (MetalSemaphore) first.fill.submit(gate);
			MetalSemaphore lastWritten = (MetalSemaphore) last.fill.submit(null);
			commitSharedBuffer(runner, firstWritten, lastWritten);

			Thread opener = new Thread(() -> {
				try {
					Thread.sleep(500);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}

				gate.countDown();
			}, "MetalTimelineSignalTest gate");
			opener.start();

			Semaphore firstRead = first.read.submit(firstWritten);
			Semaphore lastRead = last.read.submit(lastWritten);
			firstRead.waitFor();
			lastRead.waitFor();
			opener.join(10000);

			first.assertCopied();
			last.assertCopied();
		} finally {
			MemoryDataArgumentMap.enableArgumentAggregation = aggregation;
		}
	}

	/**
	 * Checks that {@code earlier} and {@code later} were encoded into one command buffer in that
	 * order, then commits that buffer by submitting a dispatch with an already complete foreign
	 * dependency, so that whatever is submitted next is encoded into a later buffer.
	 *
	 * @param runner  the runner both dispatches were submitted to
	 * @param earlier the first dispatch of the buffer
	 * @param later   the last dispatch of the buffer
	 */
	private void commitSharedBuffer(MetalCommandRunner runner, MetalSemaphore earlier, MetalSemaphore later) {
		assertTrue("Both dispatches must share a command buffer",
				earlier.getCommandBuffer() == later.getCommandBuffer());
		assertTrue("The dispatches must have increasing timeline values",
				earlier.getValue() < later.getValue());

		// A foreign dependency commits the open buffer before it is encoded
		MetalSemaphore next = runner.submit(null, buffer -> { }, () -> { }, null);
		assertTrue("The shared buffer must have been committed",
				next.getCommandBuffer() != earlier.getCommandBuffer());
	}

	/**
	 * Two Metal copy kernels in sequence: {@link #fill} copies the source into an intermediate
	 * collection and {@link #read} copies the intermediate collection into the destination, so
	 * the destination holds the source values only if {@link #read} ran after {@link #fill}.
	 */
	private final class CopyChain {
		/** Values copied through the intermediate collection. */
		private final PackedCollection source = new PackedCollection(SIZE);
		/** Written by {@link #fill} and read by {@link #read}. */
		private final PackedCollection intermediate = new PackedCollection(SIZE);
		/** Written by {@link #read}. */
		private final PackedCollection destination = new PackedCollection(SIZE);
		/** Copies the source into the intermediate collection. */
		private final Submittable fill;
		/** Copies the intermediate collection into the destination. */
		private final Submittable read;

		/** Fills the source with values in {@code [1, 2)} and compiles both copy kernels. */
		private CopyChain() {
			rand(source.getShape()).add(1.0).into(source.traverseEach()).evaluate();
			fill = SemaphoreChainBatchingTest.copyKernel(source, intermediate, SIZE, ComputeRequirement.MTL);
			read = SemaphoreChainBatchingTest.copyKernel(intermediate, destination, SIZE, ComputeRequirement.MTL);
		}

		/** Requires the destination to hold the source values. */
		private void assertCopied() {
			for (int i = 0; i < SIZE; i++) {
				assertEquals(source.toDouble(i), destination.toDouble(i));
			}
		}
	}
}
