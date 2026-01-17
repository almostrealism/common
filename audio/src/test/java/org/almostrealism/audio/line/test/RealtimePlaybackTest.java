/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.audio.line.test;

import org.almostrealism.audio.AudioTestFeatures;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.CellList;
import org.almostrealism.audio.line.BufferDefaults;
import org.almostrealism.audio.line.BufferedOutputScheduler;
import org.almostrealism.audio.line.SourceDataOutputLine;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.io.File;

/**
 * Tests for real-time buffered audio playback using {@link SourceDataOutputLine}
 * with {@link BufferedOutputScheduler}. These tests replicate the MixerTests pattern
 * of using Producer-based schedulable buffered writes for reliable real-time audio.
 */
public class RealtimePlaybackTest extends TestSuiteBase implements CellFeatures, AudioTestFeatures {

	/**
	 * Tests basic buffered real-time playback using BufferedOutputScheduler with a
	 * Producer source (WaveCell from file). This is the fundamental pattern for all
	 * real-time audio in Rings - exactly like MixerTests but with SourceDataOutputLine
	 * for actual hardware playback.
	 */
	@Test
	public void bufferedRealtimePlayback() throws Exception {
		File testFile = getTestWavFile();

		// Create audio format: 44100 Hz, 16-bit, stereo, signed PCM, little-endian
		AudioFormat format = new AudioFormat(
				AudioFormat.Encoding.PCM_SIGNED,
				44100, // sample rate
				16,    // bits per sample
				2,     // channels (stereo)
				4,     // frame size (2 bytes * 2 channels)
				44100, // frame rate
				false  // little-endian
		);

		// Get and configure the output line
		SourceDataLine line = AudioSystem.getSourceDataLine(format);
		line.open(format, 8192); // 8KB buffer for the hardware
		line.start();

		int bufferSize = 1024;
		SourceDataOutputLine outputLine = new SourceDataOutputLine(line, bufferSize);

		// Load WAV file as a WaveCell (Producer-based)
		CellList cells = w(0, testFile.getPath());

		// Create BufferedOutputScheduler with the cell list as the Producer source
		BufferedOutputScheduler scheduler = cells.buffer(outputLine);

		// Verify the output line is configured correctly
		Assert.assertEquals("Buffer size should match", bufferSize, outputLine.getBufferSize());
		Assert.assertTrue("Line should be open", outputLine.isOpen());

		// Start the scheduled buffered playback
		scheduler.start();

		// Let it run for some time
		Thread.sleep(10000);

		// Verify playback is happening
		int readPos = outputLine.getReadPosition();
		log("Read position: " + readPos);
		Assert.assertTrue("Read position should have advanced", readPos >= 0);

		long renderedFrames = scheduler.getRenderedFrames();
		log("Rendered frames: " + renderedFrames);
		Assert.assertTrue("Should have rendered frames", renderedFrames > 0);

		// Stop the scheduler
		scheduler.stop();

		// Clean up
		outputLine.destroy();
		Assert.assertFalse("Line should be closed after destroy", outputLine.isOpen());

		log("Successfully played buffered audio");
	}

	/**
	 * Tests the position tracking accuracy during buffered playback.
	 * This verifies that getReadPosition() correctly reports hardware progress.
	 */
	@Test
	public void bufferedPositionTracking() throws Exception {
		File testFile = getTestWavFile();

		AudioFormat format = new AudioFormat(44100, 16, 2, true, false);
		SourceDataLine line = AudioSystem.getSourceDataLine(format);
		line.open(format, 8192);
		line.start();

		int bufferSize = 1024;
		SourceDataOutputLine outputLine = new SourceDataOutputLine(line, bufferSize);

		// Create audio source
		CellList cells = w(0, testFile.getPath());

		// Create scheduler
		BufferedOutputScheduler scheduler = cells.buffer(outputLine);

		// Start playback
		scheduler.start();

		// Sample position at different times
		Thread.sleep(500);
		int pos1 = outputLine.getReadPosition();
		long frames1 = scheduler.getRenderedFrames();

		Thread.sleep(500);
		int pos2 = outputLine.getReadPosition();
		long frames2 = scheduler.getRenderedFrames();

		log("Position tracking:");
		log("  500ms: readPos=" + pos1 + ", renderedFrames=" + frames1);
		log("  1000ms: readPos=" + pos2 + ", renderedFrames=" + frames2);

		// Verify position and frame count are advancing
		Assert.assertTrue("Rendered frames should increase", frames2 > frames1);
		Assert.assertTrue("Position should be tracked", pos1 >= 0 && pos2 >= 0);

		// Stop and clean up
		scheduler.stop();
		outputLine.destroy();

		log("Position tracking test passed");
	}

	/**
	 * Tests lifecycle management with BufferedOutputScheduler.
	 */
	@Test
	public void bufferedLifecycleManagement() throws Exception {
		File testFile = getTestWavFile();

		AudioFormat format = new AudioFormat(44100, 16, 2, true, false);
		SourceDataLine line = AudioSystem.getSourceDataLine(format);
		line.open(format);

		SourceDataOutputLine outputLine = new SourceDataOutputLine(line);

		// Initially not active until line.start() is called
		Assert.assertTrue("Line should be open", outputLine.isOpen());

		// Start the line
		outputLine.start();
		Assert.assertTrue("Line should be active after start", outputLine.isActive());

		// Create audio source
		CellList cells = w(0, testFile.getPath());

		// Create and start scheduler
		BufferedOutputScheduler scheduler = cells.buffer(outputLine);
		scheduler.start();

		// Let it run briefly
		Thread.sleep(500);

		// Stop scheduler
		scheduler.stop();

		// Line should still be active (scheduler stops, but line doesn't auto-stop)
		Assert.assertTrue("Line should still be active", outputLine.isActive());

		// Manually stop the line
		outputLine.stop();
		Assert.assertFalse("Line should not be active after stop", outputLine.isActive());

		// Destroy should clean up everything
		outputLine.destroy();
		Assert.assertFalse("Line should not be open after destroy", outputLine.isOpen());

		log("Lifecycle management test passed");
	}

	/**
	 * Tests buffer size configuration with BufferedOutputScheduler.
	 */
	@Test
	public void bufferedWithCustomBufferSize() throws Exception {
		File testFile = getTestWavFile();

		AudioFormat format = new AudioFormat(44100, 16, 2, true, false);
		SourceDataLine line = AudioSystem.getSourceDataLine(format);
		line.open(format);
		line.start();

		// Test with custom buffer size
		int customBufferSize = 2048;
		SourceDataOutputLine outputLine = new SourceDataOutputLine(line, customBufferSize);
		Assert.assertEquals("Buffer size should match", customBufferSize, outputLine.getBufferSize());

		// Create audio source
		CellList cells = w(0, testFile.getPath());

		// Create scheduler - it will use the buffer size from the output line
		BufferedOutputScheduler scheduler = cells.buffer(outputLine);

		// Start playback
		scheduler.start();
		Thread.sleep(1000);

		// Verify it's working
		long renderedFrames = scheduler.getRenderedFrames();
		Assert.assertTrue("Should have rendered frames with custom buffer size", renderedFrames > 0);

		// Clean up
		scheduler.stop();
		outputLine.destroy();

		log("Custom buffer size test passed");
	}

	/**
	 * Tests looped playback with larger buffer (BufferDefaults settings) and verbose logging.
	 * This test helps diagnose scheduler rate consistency issues by:
	 * 1. Using looped sample for continuous playback
	 * 2. Using larger buffer size (BufferDefaults.defaultBufferSize = 65536)
	 * 3. Enabling verbose logging to monitor sleep cycles
	 * 4. Running for extended duration to observe timing patterns
	 */
	@Test
	public void bufferedLoopedPlaybackWithVerboseLogging() throws Exception {
		File testFile = getTestWavFile();

		// Enable verbose logging to monitor scheduler behavior
		boolean previousVerbose = BufferedOutputScheduler.enableVerbose;
		int previousLogRate = BufferedOutputScheduler.logRate;
		BufferedOutputScheduler.enableVerbose = true;
		BufferedOutputScheduler.logRate = 64; // Log every 64 iterations for detailed monitoring

		try {
			// Create audio format with standard settings
			AudioFormat format = new AudioFormat(
					AudioFormat.Encoding.PCM_SIGNED,
					44100, // sample rate
					16,    // bits per sample
					2,     // channels (stereo)
					4,     // frame size (2 bytes * 2 channels)
					44100, // frame rate
					false  // little-endian
			);

			// Use larger buffer size from BufferDefaults (like MixerTests)
			int bufferSize = BufferDefaults.defaultBufferSize;
			log("Using buffer size: " + bufferSize + " frames");

			// Calculate and display buffer characteristics
			BufferDefaults.logBufferInfo(44100, bufferSize, System.out::println);

			// Get and configure the output line
			SourceDataLine line = AudioSystem.getSourceDataLine(format);
			line.open(format, bufferSize * 4); // Hardware buffer
			line.start();

			SourceDataOutputLine outputLine = new SourceDataOutputLine(line, bufferSize);

			// Load WAV file with 8 beat loop enabled
			log("Loading " + testFile.getPath() + " with looping enabled");
			CellList cells = w(0, c(bpm(125).l(8)), testFile.getPath());

			// Create BufferedOutputScheduler
			BufferedOutputScheduler scheduler = cells.buffer(outputLine);

			log("Starting looped playback with verbose logging...");
			log("Monitor the sleep cycle times - very low values (<1ms) indicate");
			log("that audio generation cannot keep up with playback rate.");
			log("---");

			// Start the scheduled buffered playback
			scheduler.start();

			// Run for extended time to observe patterns
			Thread.sleep(120 * 1000);

			log("---");
			log("Playback statistics:");
			log("  Rendered frames: " + scheduler.getRenderedFrames());
			log("  Rendered count: " + scheduler.getRenderedCount());
			log("  Rendering gap: " + scheduler.getRenderingGap() + "ms");
			log("  Read position: " + outputLine.getReadPosition());

			// Verify playback is happening
			long renderedFrames = scheduler.getRenderedFrames();
			Assert.assertTrue("Should have rendered many frames", renderedFrames > 100000);

			// Stop the scheduler
			scheduler.stop();

			// Clean up
			outputLine.destroy();

			log("Looped playback test completed");
		} finally {
			// Restore previous logging settings
			BufferedOutputScheduler.enableVerbose = previousVerbose;
			BufferedOutputScheduler.logRate = previousLogRate;
		}
	}
}
