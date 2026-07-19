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

import org.almostrealism.audio.line.SourceDataOutputLine;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestProperties;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Manual tests to verify the basic SourceDataOutputLine.write(PackedCollection)
 * mechanism can produce sound before attempting BufferedOutputScheduler integration.
 */
public class ManualPlaybackTest extends TestSuiteBase {

	/**
	 * Upper bound on how long a playback loop may run before it is treated as a stuck (non-draining)
	 * device. A working device plays the short clips below in real time (a few seconds); this bound is
	 * generous headroom above that, yet well under the {@code @Test} timeout so a stuck device fails
	 * fast with a diagnostic instead of hanging.
	 */
	private static final long PLAYBACK_TIMEOUT_MS = 20000;

	/**
	 * Tests manual playback by repeatedly calling write() with a sine wave.
	 * This proves the fundamental approach of PackedCollection -> toFrame() -> SourceDataLine works.
	 */
	@Test(timeout = 60000)
	@TestProperties(audioDeviceRequired = true)
	public void manualSineWavePlayback() throws Exception {
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
		line.open(format, 8192);
		line.start();

		SourceDataOutputLine outputLine = new SourceDataOutputLine(line, 1024);

		log("Starting manual sine wave playback...");
		log("Format: " + format);
		log("Buffer size: " + outputLine.getBufferSize());

		// Generate and play 440 Hz sine wave for 3 seconds
		int sampleRate = 44100;
		int bufferSizeFrames = 512; // frames per write
		int duration = 3; // seconds
		double frequency = 440.0; // A4
		double amplitude = 0.3;

		int totalFrames = sampleRate * duration;
		PackedCollection audio = monoTone(totalFrames, frequency, amplitude, sampleRate);

		playWithinTimeout(line, "manualSineWavePlayback", () -> {
			int framesWritten = 0;

			while (framesWritten < totalFrames) {
				int len = Math.min(bufferSizeFrames, totalFrames - framesWritten);
				outputLine.write(audio.range(shape(len), framesWritten));
				framesWritten += len;
			}

			line.drain();
		});

		outputLine.destroy();

		log("Manual sine wave playback test completed");
	}

	/**
	 * Simpler test with a shorter burst of tone to quickly verify audio output works.
	 */
	@Test(timeout = 60000)
	@TestProperties(audioDeviceRequired = true)
	public void manualToneBurst() throws Exception {
		AudioFormat format = new AudioFormat(44100, 16, 2, true, false);
		SourceDataLine line = AudioSystem.getSourceDataLine(format);
		line.open(format);
		line.start();

		SourceDataOutputLine outputLine = new SourceDataOutputLine(line);

		log("Playing 1 second tone burst...");

		// Play for 1 second
		int sampleRate = 44100;
		int bufferSize = 512;
		int totalFrames = sampleRate;
		PackedCollection audio = monoTone(totalFrames, 440.0, 0.3, sampleRate);

		playWithinTimeout(line, "manualToneBurst", () -> {
			int framesWritten = 0;

			while (framesWritten < totalFrames) {
				int len = Math.min(bufferSize, totalFrames - framesWritten);
				outputLine.write(audio.range(shape(len), framesWritten));
				framesWritten += len;
			}

			line.drain();
		});

		outputLine.destroy();

		log("Tone burst completed");
	}

	/**
	 * Generates a mono sine of {@code frames} frames on the device, so the samples fed to the output
	 * line are produced by the graph rather than written one at a time from the host. A one-dimensional
	 * (mono) collection is duplicated across the line's output channels by
	 * {@link org.almostrealism.audio.line.LineUtilities#toFrame}, so no interleaving is needed here.
	 *
	 * @param frames     the number of frames to generate
	 * @param frequency  the tone frequency in Hz
	 * @param amplitude  the peak amplitude (0-1)
	 * @param sampleRate the sample rate in Hz
	 * @return a {@code (frames)} collection of samples
	 */
	private PackedCollection monoTone(int frames, double frequency, double amplitude, int sampleRate) {
		double phaseIncrement = 2 * Math.PI * frequency / sampleRate;
		PackedCollection buffer = new PackedCollection(frames);
		a(cp(buffer.traverseEach()),
				sin(integers(0, frames).multiply(phaseIncrement)).multiply(amplitude)).get().run();
		return buffer;
	}

	/**
	 * Runs a playback loop on a worker thread and fails fast if it does not finish within
	 * {@link #PLAYBACK_TIMEOUT_MS}.
	 * <p>
	 * {@link SourceDataLine#write} blocks until the device consumes the samples, so on a runner whose
	 * audio device is not draining it would otherwise hang until the {@code @Test} timeout. On timeout
	 * the line is closed to unblock the in-progress write, and the test fails with a diagnostic that
	 * identifies the stuck device rather than reporting a bare 60-second timeout.
	 *
	 * @param line     the line whose write is being bounded; closed on timeout to unblock the worker
	 * @param label    the test label used in the failure message
	 * @param playback the playback loop to run
	 * @throws Exception if the playback loop itself throws
	 */
	private void playWithinTimeout(SourceDataLine line, String label, Runnable playback) throws Exception {
		ExecutorService executor = Executors.newSingleThreadExecutor();

		try {
			executor.submit(playback).get(PLAYBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			line.close();
			Assert.fail(label + " did not complete within " + PLAYBACK_TIMEOUT_MS
					+ " ms: the audio output device is not draining samples (SourceDataLine.write blocked). "
					+ "This indicates the runner has no usable audio output, not a code defect.");
		} finally {
			executor.shutdownNow();
		}
	}
}
