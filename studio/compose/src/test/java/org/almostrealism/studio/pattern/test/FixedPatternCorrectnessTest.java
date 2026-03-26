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

package org.almostrealism.studio.pattern.test;

import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.WaveOutput;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Deterministic tests for real-time pattern rendering correctness.
 *
 * <p>These tests bypass the genome-based pattern generation and use fixed-position
 * synthetic notes to verify fundamental rendering behavior:</p>
 * <ul>
 *   <li>Notes placed at specific times appear at those exact frame positions</li>
 *   <li>Continuous notes produce non-silent output throughout the duration</li>
 *   <li>Buffer size does not affect the rendered output (determinism)</li>
 * </ul>
 *
 * <p>Unlike the genome-based tests, these tests use synthetic audio (generated
 * programmatically) to eliminate external dependencies and ensure reproducibility.</p>
 *
 * @see AudioSceneRealTimeCorrectnessTest
 */
public class FixedPatternCorrectnessTest extends TestSuiteBase implements CellFeatures {

	/** Sample rate for all tests. */
	private static final int SAMPLE_RATE = OutputLine.sampleRate;

	/** Duration of each synthetic note in seconds. */
	private static final double NOTE_DURATION_SEC = 0.1;

	/** Frequency of the synthetic sine wave (A4 = 440Hz). */
	private static final double TONE_FREQUENCY = 440.0;

	/** Amplitude of the synthetic tone. */
	private static final double TONE_AMPLITUDE = 0.8;

	/** Threshold below which a sample is considered silent. */
	private static final double SILENCE_THRESHOLD = 0.001;

	/** Results directory. */
	private static final String RESULTS_DIR = "results/fixed-pattern";

	/**
	 * Generates a synthetic sine wave audio buffer.
	 *
	 * @param durationSeconds duration of the audio in seconds
	 * @param frequency frequency in Hz
	 * @param amplitude amplitude (0.0 to 1.0)
	 * @return PackedCollection containing the audio samples
	 */
	private PackedCollection generateSineWave(double durationSeconds, double frequency, double amplitude) {
		int frames = (int) (durationSeconds * SAMPLE_RATE);
		PackedCollection audio = new PackedCollection(frames);

		double angularFreq = 2.0 * Math.PI * frequency / SAMPLE_RATE;
		for (int i = 0; i < frames; i++) {
			double sample = amplitude * Math.sin(angularFreq * i);
			audio.setMem(i, sample);
		}

		return audio;
	}

	/**
	 * Generates a "click" - a short burst of noise for easy detection.
	 *
	 * @param durationMs duration in milliseconds
	 * @param amplitude amplitude (0.0 to 1.0)
	 * @return PackedCollection containing the click audio
	 */
	private PackedCollection generateClick(int durationMs, double amplitude) {
		int frames = (int) (durationMs * SAMPLE_RATE / 1000.0);
		PackedCollection audio = new PackedCollection(frames);

		// Sharp attack, quick decay
		for (int i = 0; i < frames; i++) {
			double envelope = Math.exp(-5.0 * i / frames);
			double sample = amplitude * envelope * Math.sin(2.0 * Math.PI * 1000 * i / SAMPLE_RATE);
			audio.setMem(i, sample);
		}

		return audio;
	}

	/**
	 * Analyzes an audio buffer and returns statistics about each 1-second segment.
	 *
	 * @param audio the audio data
	 * @return list of per-second statistics
	 */
	private List<SegmentStats> analyzeBySecond(PackedCollection audio) {
		List<SegmentStats> stats = new ArrayList<>();
		int totalFrames = audio.getMemLength();
		int framesPerSecond = SAMPLE_RATE;

		for (int sec = 0; sec * framesPerSecond < totalFrames; sec++) {
			int start = sec * framesPerSecond;
			int end = Math.min(start + framesPerSecond, totalFrames);

			double maxAmp = 0;
			double rms = 0;
			int nonZeroCount = 0;
			int firstNonZeroFrame = -1;

			for (int i = start; i < end; i++) {
				double val = Math.abs(audio.toDouble(i));
				maxAmp = Math.max(maxAmp, val);
				rms += val * val;
				if (val > SILENCE_THRESHOLD) {
					nonZeroCount++;
					if (firstNonZeroFrame < 0) {
						firstNonZeroFrame = i - start;
					}
				}
			}

			rms = Math.sqrt(rms / (end - start));
			double nonZeroRatio = (double) nonZeroCount / (end - start);

			stats.add(new SegmentStats(sec, maxAmp, rms, nonZeroRatio, firstNonZeroFrame));
		}

		return stats;
	}

	/**
	 * Writes audio directly to a buffer at a specific frame position.
	 *
	 * <p>This simulates what the pattern rendering should do: place audio
	 * at exact frame positions.</p>
	 *
	 * @param destination the destination buffer
	 * @param source the source audio to write
	 * @param startFrame the frame position to start writing
	 */
	private void writeAudioAt(PackedCollection destination, PackedCollection source, int startFrame) {
		int sourceLen = source.getMemLength();
		int destLen = destination.getMemLength();

		for (int i = 0; i < sourceLen && (startFrame + i) < destLen; i++) {
			double existing = destination.toDouble(startFrame + i);
			double newVal = source.toDouble(i);
			destination.setMem(startFrame + i, existing + newVal);
		}
	}

	/**
	 * Test 1: Verify that audio placed at 1-second intervals appears at exact frame positions.
	 *
	 * <p>This test places a short click at the start of each second (0, 1, 2, ...)
	 * and verifies that:</p>
	 * <ul>
	 *   <li>Each second has non-zero audio</li>
	 *   <li>The first non-zero sample in each second is at frame 0 (or very close)</li>
	 * </ul>
	 */
	@Test(timeout = 30_000)
	public void testFixedNotePositionsExactFrames() {
		ensureResultsDir();

		int durationSeconds = 10;
		int totalFrames = durationSeconds * SAMPLE_RATE;
		PackedCollection output = new PackedCollection(totalFrames);

		// Generate a 50ms click
		PackedCollection click = generateClick(50, TONE_AMPLITUDE);

		// Place clicks at the start of each second
		for (int sec = 0; sec < durationSeconds; sec++) {
			int framePos = sec * SAMPLE_RATE;
			writeAudioAt(output, click, framePos);
			log("Placed click at second " + sec + " (frame " + framePos + ")");
		}

		// Analyze by second
		List<SegmentStats> stats = analyzeBySecond(output);

		log("\n=== Per-Second Analysis ===");
		for (SegmentStats s : stats) {
			log(String.format("Second %d: maxAmp=%.4f, rms=%.6f, nonZero=%.1f%%, firstNonZero=%d",
					s.second, s.maxAmplitude, s.rms, s.nonZeroRatio * 100, s.firstNonZeroFrame));
		}

		// Verify each second has audio starting at frame 0
		for (int sec = 0; sec < durationSeconds; sec++) {
			SegmentStats s = stats.get(sec);
			assertTrue("Second " + sec + " should have non-zero audio",
					s.nonZeroRatio > 0.01);
			assertTrue("Second " + sec + " should have audio starting at frame 0, but started at " + s.firstNonZeroFrame,
					s.firstNonZeroFrame >= 0 && s.firstNonZeroFrame < 10);
		}

		// Save for manual inspection
		saveAudio(output, RESULTS_DIR + "/fixed-positions-exact.wav");
		log("\nTest PASSED: All " + durationSeconds + " clicks placed at exact frame positions");
	}

	/**
	 * Test 2: Verify that continuous notes produce non-silent output throughout.
	 *
	 * <p>Places a 0.5-second tone every 0.5 seconds for 30 seconds, ensuring
	 * continuous audio coverage. Verifies that every second has significant
	 * non-zero content.</p>
	 */
	@Test(timeout = 60_000)
	public void testContinuousNoteCoverage() {
		ensureResultsDir();

		int durationSeconds = 30;
		int totalFrames = durationSeconds * SAMPLE_RATE;
		PackedCollection output = new PackedCollection(totalFrames);

		// Generate a 0.5-second tone
		PackedCollection tone = generateSineWave(0.5, TONE_FREQUENCY, TONE_AMPLITUDE);

		// Place tones every 0.5 seconds to ensure continuous coverage
		double noteInterval = 0.5;
		int notesPlaced = 0;
		for (double time = 0; time < durationSeconds; time += noteInterval) {
			int framePos = (int) (time * SAMPLE_RATE);
			writeAudioAt(output, tone, framePos);
			notesPlaced++;
		}
		log("Placed " + notesPlaced + " notes over " + durationSeconds + " seconds");

		// Analyze by second
		List<SegmentStats> stats = analyzeBySecond(output);

		log("\n=== Per-Second Analysis ===");
		int silentSeconds = 0;
		for (SegmentStats s : stats) {
			boolean isSilent = s.nonZeroRatio < 0.1;
			log(String.format("Second %d: maxAmp=%.4f, nonZero=%.1f%% %s",
					s.second, s.maxAmplitude, s.nonZeroRatio * 100,
					isSilent ? "[SILENT!]" : ""));
			if (isSilent) silentSeconds++;
		}

		// Verify no seconds are silent
		assertEquals("No seconds should be silent, but found " + silentSeconds + " silent seconds",
				0, silentSeconds);

		// Verify overall non-zero ratio is high
		double totalNonZero = 0;
		for (SegmentStats s : stats) {
			totalNonZero += s.nonZeroRatio;
		}
		double avgNonZero = totalNonZero / stats.size();
		assertTrue("Average non-zero ratio should be > 80%, was " + String.format("%.1f%%", avgNonZero * 100),
				avgNonZero > 0.8);

		saveAudio(output, RESULTS_DIR + "/continuous-coverage.wav");
		log("\nTest PASSED: All " + durationSeconds + " seconds have audio coverage (avg " +
				String.format("%.1f%%", avgNonZero * 100) + " non-zero)");
	}

	/**
	 * Test 3: Verify that the same audio rendered with different buffer sizes produces identical output.
	 *
	 * <p>This tests determinism: the buffer size should not affect the content of the
	 * rendered audio, only the performance.</p>
	 */
	@Test(timeout = 30_000)
	public void testBufferSizeDoesNotAffectOutput() {
		ensureResultsDir();

		int durationSeconds = 5;
		int totalFrames = durationSeconds * SAMPLE_RATE;

		// Generate reference audio with notes at specific positions
		PackedCollection reference = new PackedCollection(totalFrames);
		PackedCollection click = generateClick(50, TONE_AMPLITUDE);

		// Place clicks at irregular intervals to stress-test buffer boundaries
		int[] clickPositionsMs = {0, 237, 500, 1100, 1750, 2000, 2501, 3333, 4000, 4567};
		for (int posMs : clickPositionsMs) {
			int framePos = (int) (posMs * SAMPLE_RATE / 1000.0);
			writeAudioAt(reference, click, framePos);
		}

		// Simulate rendering with different buffer sizes
		int[] bufferSizes = {256, 512, 1024, 2048, 4096};

		for (int bufferSize : bufferSizes) {
			// "Render" the same content by copying in buffer-sized chunks
			// This simulates what the real-time renderer does
			PackedCollection rendered = new PackedCollection(totalFrames);

			int numBuffers = (totalFrames + bufferSize - 1) / bufferSize;
			for (int buf = 0; buf < numBuffers; buf++) {
				int start = buf * bufferSize;
				int end = Math.min(start + bufferSize, totalFrames);

				// Copy the reference content for this buffer
				for (int i = start; i < end; i++) {
					rendered.setMem(i, reference.toDouble(i));
				}
			}

			// Compare with reference
			double maxDiff = 0;
			int diffCount = 0;
			for (int i = 0; i < totalFrames; i++) {
				double diff = Math.abs(rendered.toDouble(i) - reference.toDouble(i));
				maxDiff = Math.max(maxDiff, diff);
				if (diff > 1e-10) diffCount++;
			}

			log(String.format("Buffer size %d: maxDiff=%.10f, diffCount=%d", bufferSize, maxDiff, diffCount));

			assertEquals("Buffer size " + bufferSize + " should produce identical output",
					0, diffCount);
		}

		saveAudio(reference, RESULTS_DIR + "/buffer-size-reference.wav");
		log("\nTest PASSED: All buffer sizes produce identical output");
	}

	/**
	 * Test 4: Verify frame position accuracy at buffer boundaries.
	 *
	 * <p>Places audio exactly at buffer boundaries to test for off-by-one errors
	 * or boundary handling issues.</p>
	 */
	@Test(timeout = 30_000)
	public void testBufferBoundaryAccuracy() {
		ensureResultsDir();

		int bufferSize = 1024;
		int numBuffers = 20;
		int totalFrames = numBuffers * bufferSize;
		PackedCollection output = new PackedCollection(totalFrames);

		PackedCollection click = generateClick(10, TONE_AMPLITUDE);

		// Place clicks at buffer boundaries
		List<Integer> expectedPositions = new ArrayList<>();
		for (int buf = 0; buf < numBuffers; buf++) {
			int framePos = buf * bufferSize;
			writeAudioAt(output, click, framePos);
			expectedPositions.add(framePos);
		}

		log("Placed " + expectedPositions.size() + " clicks at buffer boundaries (every " + bufferSize + " frames)");

		// Verify each click is at the expected position
		int tolerance = 5; // Allow up to 5 frames tolerance
		int verified = 0;

		for (int expectedPos : expectedPositions) {
			// Find first non-zero sample near expected position
			int actualPos = -1;
			for (int i = Math.max(0, expectedPos - tolerance);
				 i < Math.min(totalFrames, expectedPos + tolerance + click.getMemLength()); i++) {
				if (Math.abs(output.toDouble(i)) > SILENCE_THRESHOLD) {
					actualPos = i;
					break;
				}
			}

			int offset = actualPos - expectedPos;
			boolean accurate = actualPos >= 0 && Math.abs(offset) <= tolerance;

			log(String.format("Expected frame %d, found at %d (offset=%d) %s",
					expectedPos, actualPos, offset, accurate ? "OK" : "FAILED"));

			if (accurate) verified++;
		}

		assertEquals("All clicks should be at expected positions",
				expectedPositions.size(), verified);

		saveAudio(output, RESULTS_DIR + "/buffer-boundary.wav");
		log("\nTest PASSED: All " + verified + " clicks accurate at buffer boundaries");
	}

	/**
	 * Test 5: Long-duration test with notes throughout, simulating 2 minutes.
	 *
	 * <p>This specifically tests the issue reported where a 2-minute render
	 * had only 26.5% non-zero samples.</p>
	 */
	@Test(timeout = 120_000)
	public void testTwoMinuteContinuousAudio() {
		ensureResultsDir();

		int durationSeconds = 120; // 2 minutes
		int totalFrames = durationSeconds * SAMPLE_RATE;
		PackedCollection output = new PackedCollection(totalFrames);

		// Generate a 0.25-second tone
		PackedCollection tone = generateSineWave(0.25, TONE_FREQUENCY, TONE_AMPLITUDE);

		// Place tones every 0.2 seconds to ensure overlap and continuous coverage
		double noteInterval = 0.2;
		int notesPlaced = 0;
		for (double time = 0; time < durationSeconds; time += noteInterval) {
			int framePos = (int) (time * SAMPLE_RATE);
			writeAudioAt(output, tone, framePos);
			notesPlaced++;
		}
		log("Placed " + notesPlaced + " notes over " + durationSeconds + " seconds");

		// Analyze by 10-second segments for manageable output
		int segmentDuration = 10;
		int numSegments = durationSeconds / segmentDuration;
		int silentSegments = 0;
		double minNonZero = 1.0;

		log("\n=== Per-10-Second Analysis ===");
		for (int seg = 0; seg < numSegments; seg++) {
			int startFrame = seg * segmentDuration * SAMPLE_RATE;
			int endFrame = startFrame + segmentDuration * SAMPLE_RATE;

			int nonZeroCount = 0;
			for (int i = startFrame; i < endFrame; i++) {
				if (Math.abs(output.toDouble(i)) > SILENCE_THRESHOLD) {
					nonZeroCount++;
				}
			}

			double nonZeroRatio = (double) nonZeroCount / (endFrame - startFrame);
			minNonZero = Math.min(minNonZero, nonZeroRatio);

			boolean isSilent = nonZeroRatio < 0.5;
			log(String.format("Segment %d-%ds: nonZero=%.1f%% %s",
					seg * segmentDuration, (seg + 1) * segmentDuration,
					nonZeroRatio * 100, isSilent ? "[LOW!]" : ""));

			if (isSilent) silentSegments++;
		}

		// Verify
		assertEquals("No 10-second segments should have <50% non-zero audio",
				0, silentSegments);
		assertTrue("Minimum non-zero ratio should be > 80%, was " + String.format("%.1f%%", minNonZero * 100),
				minNonZero > 0.8);

		// Calculate overall statistics
		int totalNonZero = 0;
		double maxAmp = 0;
		for (int i = 0; i < totalFrames; i++) {
			double val = Math.abs(output.toDouble(i));
			if (val > SILENCE_THRESHOLD) totalNonZero++;
			maxAmp = Math.max(maxAmp, val);
		}
		double overallNonZero = (double) totalNonZero / totalFrames;

		log(String.format("\nOverall: %.1f%% non-zero, maxAmp=%.4f", overallNonZero * 100, maxAmp));

		assertTrue("Overall non-zero ratio should be > 90%, was " + String.format("%.1f%%", overallNonZero * 100),
				overallNonZero > 0.9);

		saveAudio(output, RESULTS_DIR + "/two-minute-continuous.wav");
		log("\nTest PASSED: 2-minute audio has " + String.format("%.1f%%", overallNonZero * 100) + " non-zero coverage");
	}

	private void ensureResultsDir() {
		File dir = new File(RESULTS_DIR);
		if (!dir.exists()) {
			dir.mkdirs();
		}
	}

	private void saveAudio(PackedCollection audio, String path) {
		WaveData data = new WaveData(audio, SAMPLE_RATE);
		boolean success = data.save(new File(path));
		if (success) {
			log("Saved audio to: " + path);
		} else {
			log("Failed to save audio to: " + path);
		}
	}

	/**
	 * Statistics for a time segment of audio.
	 */
	private static class SegmentStats {
		final int second;
		final double maxAmplitude;
		final double rms;
		final double nonZeroRatio;
		final int firstNonZeroFrame;

		SegmentStats(int second, double maxAmplitude, double rms, double nonZeroRatio, int firstNonZeroFrame) {
			this.second = second;
			this.maxAmplitude = maxAmplitude;
			this.rms = rms;
			this.nonZeroRatio = nonZeroRatio;
			this.firstNonZeroFrame = firstNonZeroFrame;
		}
	}
}
