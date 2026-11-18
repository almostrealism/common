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

package org.almostrealism.audio.line.test;

import org.almostrealism.audio.line.SimulatedOutputLine;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the simulated read-position contract shared by every {@link SimulatedOutputLine}
 * (the behaviour both {@link org.almostrealism.audio.line.MockOutputLine} and
 * {@link org.almostrealism.audio.line.BufferOutputLine} inherit). A minimal concrete line that
 * discards written samples is used so the test exercises only the inherited lifecycle and
 * read-position logic, independent of any subclass-specific capture or counting.
 */
public class SimulatedOutputLineTest extends TestSuiteBase {

	/** A minimal concrete simulated line that discards everything written to it. */
	private static class RecordingLine extends SimulatedOutputLine {
		/**
		 * Creates a concrete simulated line with the given buffer size and sample rate.
		 *
		 * @param bufferSize The buffer size in frames
		 * @param sampleRate The sample rate in Hz
		 */
		RecordingLine(int bufferSize, int sampleRate) {
			super(bufferSize, sampleRate);
		}

		@Override
		public void write(PackedCollection sample) {
			// discard; these tests only exercise the read-position contract
		}
	}

	/**
	 * Tests that a line reports a read position of zero before it has been started.
	 */
	@Test(timeout = 30000)
	public void testNotStartedReturnsZero() {
		RecordingLine line = new RecordingLine(1024, 44100);
		assertEquals(0, line.getReadPosition());
	}

	/**
	 * Tests that a line reports zero once stopped, even after having advanced while active.
	 */
	@Test(timeout = 30000)
	public void testStoppedReturnsZero() throws InterruptedException {
		RecordingLine line = new RecordingLine(1024, 44100);

		line.start();
		Thread.sleep(50);
		line.stop();

		assertEquals(0, line.getReadPosition());
	}

	/**
	 * Tests that the read position advances proportionally to elapsed time when it does not
	 * exceed the buffer size (no wrap).
	 */
	@Test(timeout = 30000)
	public void testAdvancesWithoutWrap() throws InterruptedException {
		RecordingLine line = new RecordingLine(1_000_000, 1000);

		line.start();
		Thread.sleep(100);

		int position = line.getReadPosition();
		assertTrue("Read position should advance after start, was " + position, position > 0);
		assertTrue("Read position should not have wrapped, was " + position, position < 1_000_000);
	}

	/**
	 * Tests that the position is bounded by the buffer via the modulo once enough time has
	 * elapsed to exceed the buffer size.
	 */
	@Test(timeout = 30000)
	public void testWrapsWithinBuffer() throws InterruptedException {
		RecordingLine line = new RecordingLine(300, 10000);

		line.start();
		Thread.sleep(100);

		int position = line.getReadPosition();
		assertTrue("Wrapped position should be within the buffer, was " + position,
				position >= 0 && position < 300);
	}

	/**
	 * Tests that a zero sample rate yields a zero position regardless of elapsed time, since
	 * no frames are consumed.
	 */
	@Test(timeout = 30000)
	public void testZeroSampleRate() throws InterruptedException {
		RecordingLine line = new RecordingLine(1024, 0);

		line.start();
		Thread.sleep(50);

		assertEquals(0, line.getReadPosition());
	}
}
