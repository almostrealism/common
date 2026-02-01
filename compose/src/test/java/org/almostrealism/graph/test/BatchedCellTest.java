/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.graph.test;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.BatchedCell;
import org.almostrealism.graph.Receptor;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Unit tests for {@link BatchedCell}.
 *
 * <p>Tests verify the core batching contract: tick() counts to N then fires
 * renderBatch(), push() never triggers rendering, and renderNow() bypasses
 * the counter.</p>
 */
public class BatchedCellTest extends TestSuiteBase {

	/**
	 * Verifies that renderBatch() is called exactly once after batchSize ticks,
	 * and not before.
	 */
	@Test
	public void testTickCountingTriggersRenderAtBatchBoundary() {
		int batchSize = 4;
		AtomicInteger renderCount = new AtomicInteger(0);
		BatchedCell cell = createTestCell(batchSize, batchSize, renderCount);

		// Tick batchSize - 1 times: no render should happen
		for (int i = 0; i < batchSize - 1; i++) {
			cell.tick().get().run();
		}
		Assert.assertEquals("renderBatch should not fire before batchSize ticks",
				0, renderCount.get());

		// One more tick completes the batch
		cell.tick().get().run();
		Assert.assertEquals("renderBatch should fire at batchSize ticks",
				1, renderCount.get());

		// Another full batch
		for (int i = 0; i < batchSize; i++) {
			cell.tick().get().run();
		}
		Assert.assertEquals("renderBatch should fire again at next batch boundary",
				2, renderCount.get());
	}

	/**
	 * Verifies that push() does not trigger renderBatch().
	 */
	@Test
	public void testPushDoesNotTriggerRender() {
		int batchSize = 8;
		AtomicInteger renderCount = new AtomicInteger(0);
		BatchedCell cell = createTestCell(batchSize, batchSize, renderCount);

		for (int i = 0; i < 100; i++) {
			cell.push(null).get().run();
		}

		Assert.assertEquals("push should never trigger renderBatch",
				0, renderCount.get());
	}

	/**
	 * Verifies that renderNow() triggers renderBatch() without requiring ticks.
	 */
	@Test
	public void testRenderNowBypassesCounting() {
		int batchSize = 1024;
		AtomicInteger renderCount = new AtomicInteger(0);
		BatchedCell cell = createTestCell(batchSize, batchSize, renderCount);

		cell.renderNow().get().run();
		Assert.assertEquals("renderNow should trigger renderBatch immediately",
				1, renderCount.get());

		// Verify tick counter is unaffected (still need full batch to trigger)
		for (int i = 0; i < batchSize - 1; i++) {
			cell.tick().get().run();
		}
		Assert.assertEquals("renderNow should not affect tick counting",
				1, renderCount.get());

		cell.tick().get().run();
		Assert.assertEquals("tick counting should work independently of renderNow",
				2, renderCount.get());
	}

	/**
	 * Verifies that getOutputSize() returns the constructor value.
	 */
	@Test
	public void testOutputSizeAccessor() {
		int outputSize = 512;
		BatchedCell cell = createTestCell(1024, outputSize, new AtomicInteger(0));

		Assert.assertEquals("getOutputSize should return constructor value",
				outputSize, cell.getOutputSize());
		Assert.assertNotNull("getOutputBuffer should not be null",
				cell.getOutputBuffer());
	}

	/**
	 * Verifies that getCurrentFrame() advances correctly after completed batches.
	 */
	@Test
	public void testGetCurrentFrame() {
		int batchSize = 100;
		AtomicInteger renderCount = new AtomicInteger(0);
		BatchedCell cell = createTestCell(batchSize, batchSize, renderCount);

		Assert.assertEquals("Initial frame should be 0", 0, cell.getCurrentFrame());
		Assert.assertEquals("Initial batch should be 0", 0, cell.getCurrentBatch());

		// Complete 3 batches
		for (int batch = 0; batch < 3; batch++) {
			for (int i = 0; i < batchSize; i++) {
				cell.tick().get().run();
			}
		}

		Assert.assertEquals("After 3 batches, getCurrentBatch should be 3",
				3, cell.getCurrentBatch());
		Assert.assertEquals("After 3 batches, getCurrentFrame should be 3 * batchSize",
				3 * batchSize, cell.getCurrentFrame());
	}

	/**
	 * Verifies that setup() and reset() clear counters and output.
	 */
	@Test
	public void testSetupAndResetClearState() {
		int batchSize = 4;
		AtomicInteger renderCount = new AtomicInteger(0);
		BatchedCell cell = createTestCell(batchSize, batchSize, renderCount);

		// Complete a batch to advance counters
		for (int i = 0; i < batchSize; i++) {
			cell.tick().get().run();
		}
		Assert.assertEquals(1, cell.getCurrentBatch());

		// Reset should clear counters
		cell.reset();
		Assert.assertEquals("reset should clear batch counter",
				0, cell.getCurrentBatch());
		Assert.assertEquals("reset should clear frame counter",
				0, cell.getCurrentFrame());

		// Complete another batch via setup path
		cell.setup().get().run();
		for (int i = 0; i < batchSize; i++) {
			cell.tick().get().run();
		}
		Assert.assertEquals("setup should allow fresh start",
				1, cell.getCurrentBatch());
	}

	/**
	 * Verifies that push() forwards output to receptor.
	 */
	@Test
	public void testPushForwardsToReceptor() {
		int batchSize = 4;
		AtomicInteger pushCount = new AtomicInteger(0);
		BatchedCell cell = createTestCell(batchSize, batchSize, new AtomicInteger(0));

		Receptor<PackedCollection> receptor = protein -> {
			pushCount.incrementAndGet();
			return () -> () -> {};
		};
		cell.setReceptor(receptor);

		cell.push(null).get().run();
		Assert.assertEquals("push should forward to receptor",
				1, pushCount.get());
	}

	/**
	 * Creates a test subclass of BatchedCell that counts renderBatch() calls.
	 */
	private BatchedCell createTestCell(int batchSize, int outputSize,
									   AtomicInteger renderCount) {
		return new BatchedCell(batchSize, outputSize) {
			@Override
			protected Supplier<Runnable> renderBatch() {
				return () -> () -> renderCount.incrementAndGet();
			}
		};
	}
}
