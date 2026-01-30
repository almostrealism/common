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

package org.almostrealism.audio.pattern.test;

import org.almostrealism.audio.arrange.AudioSceneContext;
import org.almostrealism.audio.data.ChannelInfo;
import org.almostrealism.audio.pattern.PatternRenderCell;
import org.almostrealism.audio.pattern.PatternSystemManager;
import org.almostrealism.graph.BatchedCell;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.Assert.*;

/**
 * Tests for real-time pattern rendering functionality.
 */
public class RealTimeRenderingTest extends TestSuiteBase {

	@Test
	public void testBatchedCellBasics() {
		AtomicInteger renderCount = new AtomicInteger(0);
		final int[] lastFrame = {-1};

		BatchedCell cell = new BatchedCell(100, 100, frame -> lastFrame[0] = frame) {
			@Override
			protected Supplier<Runnable> renderBatch() {
				return () -> () -> renderCount.incrementAndGet();
			}
		};

		// Tick 99 times - should not render
		for (int i = 0; i < 99; i++) {
			cell.tick().get().run();
		}
		assertEquals("Should not have rendered yet", 0, renderCount.get());

		// Tick once more - should render
		cell.tick().get().run();
		assertEquals("Should have rendered once", 1, renderCount.get());
		assertEquals("Frame callback should have been called with 0", 0, lastFrame[0]);

		// Tick 100 more times - should render again
		for (int i = 0; i < 100; i++) {
			cell.tick().get().run();
		}
		assertEquals("Should have rendered twice", 2, renderCount.get());
		assertEquals("Frame callback should have been called with 100", 100, lastFrame[0]);

		// Test reset
		cell.reset();
		assertEquals("Current frame should be 0 after reset", 0, cell.getCurrentFrame());
	}

	@Test
	public void testBatchedCellFrameTracking() {
		BatchedCell cell = new BatchedCell(256, 256) {
			@Override
			protected Supplier<Runnable> renderBatch() {
				return () -> () -> {};
			}
		};

		// Initial state
		assertEquals(0, cell.getCurrentFrame());
		assertEquals(0, cell.getCurrentBatch());

		// Run through first batch
		for (int i = 0; i < 256; i++) {
			cell.tick().get().run();
		}
		assertEquals(256, cell.getCurrentFrame());
		assertEquals(1, cell.getCurrentBatch());

		// Run through second batch
		for (int i = 0; i < 256; i++) {
			cell.tick().get().run();
		}
		assertEquals(512, cell.getCurrentFrame());
		assertEquals(2, cell.getCurrentBatch());
	}

	@Test
	public void testPatternRenderCellSetup() {
		// Create a minimal pattern system manager
		PatternSystemManager patterns = new PatternSystemManager(new java.util.ArrayList<>());

		// Create context supplier
		AudioSceneContext baseContext = new AudioSceneContext();
		baseContext.setMeasures(4);
		baseContext.setFrames(44100 * 4);
		baseContext.setFrameForPosition(pos -> (int) (pos * 44100));

		java.util.function.Supplier<AudioSceneContext> contextSupplier = () -> baseContext;

		// Create render cell
		ChannelInfo channel = new ChannelInfo(0, ChannelInfo.Voicing.MAIN, ChannelInfo.StereoChannel.LEFT);
		PatternRenderCell renderCell = new PatternRenderCell(
				patterns, contextSupplier, channel, 1024, () -> 0);

		// Verify setup runs without error
		renderCell.setup().get().run();

		// Verify accessors
		assertEquals(1024, renderCell.getBatchSize());
		assertEquals(channel, renderCell.getChannel());
	}

}
