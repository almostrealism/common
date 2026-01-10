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

import org.almostrealism.audio.arrange.PatternRenderContext;
import org.almostrealism.audio.arrange.AudioSceneContext;
import org.almostrealism.audio.data.ChannelInfo;
import org.almostrealism.audio.pattern.BatchCell;
import org.almostrealism.audio.pattern.PatternRenderCell;
import org.almostrealism.audio.pattern.PatternSystemManager;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for real-time pattern rendering functionality.
 */
public class RealTimeRenderingTest extends TestSuiteBase {

	@Test
	public void testPatternRenderContextBasics() {
		// Create a mock AudioSceneContext
		AudioSceneContext baseContext = new AudioSceneContext();
		baseContext.setMeasures(16);
		baseContext.setFrames(44100 * 16); // 16 seconds at 44.1kHz
		baseContext.setFrameForPosition(pos -> (int) (pos * 44100));

		// Create render context for a 1024-frame buffer starting at frame 44100
		PatternRenderContext renderContext = new PatternRenderContext(baseContext, 44100, 1024);

		// Test basic frame range accessors
		assertEquals(44100, renderContext.getStartFrame());
		assertEquals(1024, renderContext.getFrameCount());
		assertEquals(44100 + 1024, renderContext.getEndFrame());

		// Test measure conversion
		double startMeasure = renderContext.frameToMeasure(44100);
		assertTrue("Start measure should be around 1.0", startMeasure > 0.9 && startMeasure < 1.1);

		// Test overlap detection
		assertTrue("Should overlap with measure range 0.5-1.5",
				renderContext.overlapsFrameRange(0.5, 1.5));
		assertFalse("Should not overlap with measure range 5.0-6.0",
				renderContext.overlapsFrameRange(5.0, 6.0));

		// Test buffer offset calculations
		int bufferOffset = renderContext.measureToBufferOffset(1.0);
		assertTrue("Buffer offset should be within range", bufferOffset >= 0 && bufferOffset < 1024);
	}

	@Test
	public void testBatchCellBasics() {
		final int[] executionCount = {0};
		final int[] lastFrame = {-1};

		// Create a simple temporal that just counts executions
		org.almostrealism.time.Temporal countingTemporal = () -> () -> () -> executionCount[0]++;

		// Wrap in a batch cell with batch size 100
		BatchCell batchCell = new BatchCell(countingTemporal, 100, frame -> lastFrame[0] = frame);

		// Tick 99 times - should not execute the wrapped operation
		for (int i = 0; i < 99; i++) {
			batchCell.tick().get().run();
		}
		assertEquals("Should not have executed yet", 0, executionCount[0]);

		// Tick once more - should execute
		batchCell.tick().get().run();
		assertEquals("Should have executed once", 1, executionCount[0]);
		assertEquals("Frame callback should have been called with 0", 0, lastFrame[0]);

		// Tick 100 more times - should execute again
		for (int i = 0; i < 100; i++) {
			batchCell.tick().get().run();
		}
		assertEquals("Should have executed twice", 2, executionCount[0]);
		assertEquals("Frame callback should have been called with 100", 100, lastFrame[0]);

		// Test reset
		batchCell.reset();
		assertEquals("Current frame should be 0 after reset", 0, batchCell.getCurrentFrame());
	}

	@Test
	public void testBatchCellFrameTracking() {
		BatchCell batchCell = new BatchCell(() -> new OperationList(), 256, null);

		// Initial state
		assertEquals(0, batchCell.getCurrentFrame());
		assertEquals(0, batchCell.getCurrentBatch());

		// Run through first batch
		for (int i = 0; i < 256; i++) {
			batchCell.tick().get().run();
		}
		assertEquals(256, batchCell.getCurrentFrame());
		assertEquals(1, batchCell.getCurrentBatch());

		// Run through second batch
		for (int i = 0; i < 256; i++) {
			batchCell.tick().get().run();
		}
		assertEquals(512, batchCell.getCurrentFrame());
		assertEquals(2, batchCell.getCurrentBatch());
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
		assertEquals(1024, renderCell.getBufferSize());
		assertEquals(channel, renderCell.getChannel());
	}

	@Test
	public void testPatternRenderContextDelegation() {
		// Verify that PatternRenderContext properly delegates to the base context
		AudioSceneContext baseContext = new AudioSceneContext();
		baseContext.setMeasures(8);
		baseContext.setFrames(44100 * 8);
		baseContext.setDestination(new PackedCollection(1024));

		PatternRenderContext renderContext = new PatternRenderContext(baseContext, 0, 1024);

		// Test delegation
		assertEquals(8, renderContext.getMeasures());
		assertEquals(44100 * 8, renderContext.getFrames());
		assertNotNull("Destination should be delegated", renderContext.getDestination());
	}

	@Test
	public void testOverlapCalculations() {
		AudioSceneContext baseContext = new AudioSceneContext();
		baseContext.setMeasures(16);
		baseContext.setFrames(44100 * 16);
		baseContext.setFrameForPosition(pos -> (int) (pos * 44100));

		// Test buffer at the start
		PatternRenderContext startContext = new PatternRenderContext(baseContext, 0, 1024);
		assertTrue(startContext.overlapsFrameRange(0.0, 0.5));
		assertFalse(startContext.overlapsFrameRange(1.0, 2.0));

		// Test buffer in the middle
		int middleFrame = 44100 * 8; // 8 seconds in
		PatternRenderContext middleContext = new PatternRenderContext(baseContext, middleFrame, 1024);
		assertTrue(middleContext.overlapsFrameRange(7.9, 8.1));
		assertFalse(middleContext.overlapsFrameRange(0.0, 1.0));
		assertFalse(middleContext.overlapsFrameRange(15.0, 16.0));

		// Test absolute frame overlap
		assertTrue(middleContext.overlapsAbsoluteFrameRange(middleFrame - 100, middleFrame + 100));
		assertFalse(middleContext.overlapsAbsoluteFrameRange(0, 1000));
	}
}
