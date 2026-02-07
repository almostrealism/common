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

package org.almostrealism.audio.line.test;

import org.almostrealism.audio.AudioTestFeatures;
import org.almostrealism.audio.line.BufferDefaults;
import org.almostrealism.audio.line.LineUtilities;
import org.almostrealism.audio.line.MockOutputLine;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for {@link MockOutputLine} functionality.
 */
public class MockOutputLineTest extends TestSuiteBase implements AudioTestFeatures {

	@Test
	public void testDefaultConstruction() {
		MockOutputLine mock = new MockOutputLine();

		assertEquals(BufferDefaults.defaultBufferSize, mock.getBufferSize());
		assertEquals(OutputLine.sampleRate, mock.getSampleRate());
		assertEquals(0, mock.getFramesWritten());
		assertFalse(mock.isActive());
	}

	@Test
	public void testCustomBufferSize() {
		int customSize = 2048;
		MockOutputLine mock = new MockOutputLine(customSize);

		assertEquals(customSize, mock.getBufferSize());
		assertEquals(OutputLine.sampleRate, mock.getSampleRate());
	}

	@Test
	public void testCustomBufferSizeAndSampleRate() {
		int customSize = 4096;
		int customRate = 48000;
		MockOutputLine mock = new MockOutputLine(customSize, customRate);

		assertEquals(customSize, mock.getBufferSize());
		assertEquals(customRate, mock.getSampleRate());
	}

	@Test
	public void testWriteCountsFrames() {
		MockOutputLine mock = new MockOutputLine();

		PackedCollection samples = new PackedCollection(100);
		mock.write(samples);

		assertEquals(100, mock.getFramesWritten());

		mock.write(samples);
		assertEquals(200, mock.getFramesWritten());
	}

	@Test
	public void testDurationCalculation() {
		MockOutputLine mock = new MockOutputLine(1024, 44100);

		PackedCollection samples = new PackedCollection(44100);
		mock.write(samples);

		assertEquals(1.0, mock.getDurationWritten(), 0.001);
	}

	@Test
	public void testStartStop() {
		MockOutputLine mock = new MockOutputLine();

		assertFalse(mock.isActive());

		mock.start();
		assertTrue(mock.isActive());

		mock.stop();
		assertFalse(mock.isActive());
	}

	@Test
	public void testReadPositionAdvancesWithTime() throws InterruptedException {
		MockOutputLine mock = new MockOutputLine(44100, 44100);

		assertEquals(0, mock.getReadPosition());

		mock.start();

		Thread.sleep(100);

		int position = mock.getReadPosition();
		assertTrue("Read position should advance after start", position > 0);
		assertTrue("Read position should be reasonable",
				position < 44100);
	}

	@Test
	public void testReset() {
		MockOutputLine mock = new MockOutputLine();

		PackedCollection samples = new PackedCollection(100);
		mock.write(samples);
		assertEquals(100, mock.getFramesWritten());

		mock.reset();
		assertEquals(0, mock.getFramesWritten());
	}

	@Test
	public void testResetFrameCount() {
		MockOutputLine mock = new MockOutputLine();

		PackedCollection samples = new PackedCollection(100);
		mock.write(samples);
		assertEquals(100, mock.getFramesWritten());

		mock.resetFrameCount();
		assertEquals(0, mock.getFramesWritten());
	}

	@Test
	public void testDestroy() {
		MockOutputLine mock = new MockOutputLine();
		mock.start();

		PackedCollection samples = new PackedCollection(100);
		mock.write(samples);

		assertTrue(mock.isActive());
		assertEquals(100, mock.getFramesWritten());

		mock.destroy();

		assertFalse(mock.isActive());
		assertEquals(0, mock.getFramesWritten());
	}

	@Test
	public void testLineUtilitiesGetMockLine() {
		MockOutputLine mock = LineUtilities.getMockLine();
		assertNotNull(mock);
		assertEquals(BufferDefaults.defaultBufferSize, mock.getBufferSize());
	}

	@Test
	public void testLineUtilitiesGetMockLineWithSize() {
		int customSize = 8192;
		MockOutputLine mock = LineUtilities.getMockLine(customSize);
		assertNotNull(mock);
		assertEquals(customSize, mock.getBufferSize());
	}

	@Test
	public void testLineUtilitiesGetLineOrMock() {
		OutputLine line = LineUtilities.getLineOrMock();
		assertNotNull("getLineOrMock should never return null", line);
	}

	@Test
	public void testAudioTestFeaturesAssertions() {
		MockOutputLine mock = mockOutput();

		PackedCollection samples = new PackedCollection(1000);
		mock.write(samples);

		assertMinFramesWritten(mock, 1000);
	}
}
