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
import org.junit.Test;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;

/**
 * Manual tests to verify the basic SourceDataOutputLine.write(PackedCollection)
 * mechanism can produce sound before attempting BufferedOutputScheduler integration.
 */
public class ManualPlaybackTest {

	/**
	 * Tests manual playback by repeatedly calling write() with a sine wave.
	 * This proves the fundamental approach of PackedCollection -> toFrame() -> SourceDataLine works.
	 */
	@Test
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

		System.out.println("Starting manual sine wave playback...");
		System.out.println("Format: " + format);
		System.out.println("Buffer size: " + outputLine.getBufferSize());

		// Generate and play 440 Hz sine wave for 3 seconds
		int sampleRate = 44100;
		int channels = 2; // stereo
		int bufferSizeFrames = 512; // frames per write
		int duration = 3; // seconds
		double frequency = 440.0; // A4
		double amplitude = 0.3;

		int totalFrames = sampleRate * duration;
		int framesWritten = 0;

		while (framesWritten < totalFrames) {
			// Create a PackedCollection for interleaved stereo samples
			// Each frame has 2 samples (left, right)
			PackedCollection buffer = new PackedCollection(bufferSizeFrames * channels);

			// Generate sine wave samples
			for (int frame = 0; frame < bufferSizeFrames; frame++) {
				double t = (framesWritten + frame) / (double) sampleRate;
				double sample = amplitude * Math.sin(2 * Math.PI * frequency * t);

				// Interleaved stereo: left, right, left, right, ...
				buffer.setMem(frame * 2, sample);     // left channel
				buffer.setMem(frame * 2 + 1, sample); // right channel
			}

			// Write to the line
			outputLine.write(buffer);
			framesWritten += bufferSizeFrames;

			// Log progress every second
			if (framesWritten % sampleRate < bufferSizeFrames) {
				System.out.println("Frames written: " + framesWritten + " / " + totalFrames);
				System.out.println("Read position: " + outputLine.getReadPosition());
			}
		}

		// Wait for audio to finish playing
		line.drain();

		System.out.println("Total frames written: " + framesWritten);
		System.out.println("Final read position: " + outputLine.getReadPosition());

		// Clean up
		outputLine.destroy();

		System.out.println("Manual sine wave playback test completed");
	}

	/**
	 * Simpler test with a shorter burst of tone to quickly verify audio output works.
	 */
	@Test
	public void manualToneBurst() throws Exception {
		AudioFormat format = new AudioFormat(44100, 16, 2, true, false);
		SourceDataLine line = AudioSystem.getSourceDataLine(format);
		line.open(format);
		line.start();

		SourceDataOutputLine outputLine = new SourceDataOutputLine(line);

		System.out.println("Playing 1 second tone burst...");

		// Play for 1 second
		int sampleRate = 44100;
		int channels = 2;
		int bufferSize = 512;
		int writes = sampleRate / bufferSize; // ~86 writes for 1 second

		for (int w = 0; w < writes; w++) {
			PackedCollection buffer = new PackedCollection(bufferSize * channels);

			for (int i = 0; i < bufferSize; i++) {
				double t = (w * bufferSize + i) / (double) sampleRate;
				double sample = 0.3 * Math.sin(2 * Math.PI * 440.0 * t);
				buffer.setMem(i * 2, sample);
				buffer.setMem(i * 2 + 1, sample);
			}

			outputLine.write(buffer);
		}

		line.drain();
		outputLine.destroy();

		System.out.println("Tone burst completed");
	}
}
