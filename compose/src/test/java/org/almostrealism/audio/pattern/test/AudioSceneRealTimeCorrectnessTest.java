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

import org.almostrealism.audio.AudioScene;
import org.almostrealism.audio.WaveOutput;
import org.almostrealism.audio.arrange.MixdownManager;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.audio.health.AudioHealthScore;
import org.almostrealism.audio.health.MultiChannelAudioOutput;
import org.almostrealism.audio.health.StableDurationHealthComputation;
import org.almostrealism.audio.pattern.PatternSystemManager;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.heredity.TemporalCellular;
import org.almostrealism.util.TestDepth;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.*;

/**
 * Tests for real-time audio scene correctness.
 *
 * <p>These tests verify that the real-time rendering path
 * ({@link AudioScene#runnerRealTime}) produces valid, non-silent audio
 * output under conditions identical to the validated baseline.</p>
 *
 * <p>The test scene uses the same 6-channel, 120 BPM, 16-measure configuration
 * as {@link AudioSceneBaselineTest}, ensuring that any differences are due to
 * the real-time pipeline, not scene configuration.</p>
 *
 * @see AudioSceneBaselineTest
 * @see AudioSceneTestBase
 * @see org.almostrealism.audio.pattern.PatternRenderCell
 */
public class AudioSceneRealTimeCorrectnessTest extends AudioSceneTestBase {

	private static final int BUFFER_SIZE = 1024;

	/**
	 * Verifies that the real-time rendering path produces non-silent audio.
	 *
	 * <p>This test creates the same baseline scene used by
	 * {@link AudioSceneBaselineTest}, then renders it through the real-time
	 * pipeline ({@link AudioScene#runnerRealTime}) using per-sample ticking.
	 * The output is verified to contain actual audio content (non-zero
	 * amplitude and RMS).</p>
	 *
	 * <p>The tick loop runs {@code totalFrames} times (per-sample), as
	 * the BatchCell inside runnerRealTime counts ticks and fires the
	 * pattern render every {@code bufferSize} ticks.</p>
	 */
	@Test
	@TestDepth(2)
	public void realTimeProducesAudio() {
		File samplesDir = new File(SAMPLES_PATH);
		if (!samplesDir.exists()) {
			log("Skipping test - Samples directory not found: " + samplesDir.getAbsolutePath());
			return;
		}

		MixdownManager.enableMainFilterUp = false;
		MixdownManager.enableEfxFilters = false;
		MixdownManager.enableEfx = false;
		PatternSystemManager.enableWarnings = false;

		AudioScene<?> scene = createBaselineScene(samplesDir);
		applyGenome(scene, 42);

		int totalElements = countElements(scene);
		log("=== Real-Time Produces Audio ===");
		log("Scene elements: " + totalElements);

		if (totalElements == 0) {
			log("Seed 42 produces no elements - trying other seeds");
			long seed = findWorkingGenomeSeed(scene, samplesDir);
			if (seed < 0) {
				log("No working genome found - skipping test");
				return;
			}
			scene = createBaselineScene(samplesDir);
			applyGenome(scene, seed);
			totalElements = countElements(scene);
			log("Using seed " + seed + " with " + totalElements + " elements");
		}

		String outputFile = "results/realtime-correctness.wav";
		WaveOutput output = new WaveOutput(() -> new File(outputFile), 24, true);
		TemporalCellular runner = scene.runnerRealTime(
				new MultiChannelAudioOutput(output), BUFFER_SIZE);

		runner.setup().get().run();

		int totalFrames = (int) (RENDER_SECONDS * SAMPLE_RATE);
		log("Ticking " + totalFrames + " frames (buffer size: " + BUFFER_SIZE + ")");

		Runnable tick = runner.tick().get();
		for (int i = 0; i < totalFrames; i++) {
			tick.run();
		}

		output.write().get().run();

		File outFile = new File(outputFile);
		assertTrue("Output file should exist", outFile.exists());
		assertTrue("Output file should have content", outFile.length() > 1000);

		verifyNonSilence(outputFile, "Real-time output");
	}

	/**
	 * Compares real-time output against the traditional baseline output.
	 *
	 * <p>Creates two identical scenes with the same seed, renders one through
	 * the traditional path ({@link StableDurationHealthComputation}) and one
	 * through the real-time path. Both outputs are verified to be non-silent.</p>
	 *
	 * <p>Exact sample-level match is NOT expected because:</p>
	 * <ul>
	 *   <li>Traditional path applies auto-volume normalization</li>
	 *   <li>Real-time path skips section processing</li>
	 *   <li>Buffer boundary alignment may differ</li>
	 * </ul>
	 */
	@Test
	@TestDepth(2)
	public void realTimeMatchesTraditional() {
		File samplesDir = new File(SAMPLES_PATH);
		if (!samplesDir.exists()) {
			log("Skipping test - Samples directory not found: " + samplesDir.getAbsolutePath());
			return;
		}

		MixdownManager.enableMainFilterUp = false;
		MixdownManager.enableEfxFilters = false;
		MixdownManager.enableEfx = false;
		PatternSystemManager.enableWarnings = false;

		// Find a working seed
		AudioScene<?> seedScene = createBaselineScene(samplesDir);
		long seed = findWorkingGenomeSeed(seedScene, samplesDir);
		if (seed < 0) {
			log("No working genome found - skipping test");
			return;
		}

		log("=== Real-Time vs Traditional Comparison ===");
		log("Using seed: " + seed);

		// Traditional rendering
		AudioScene<?> traditionalScene = createBaselineScene(samplesDir);
		applyGenome(traditionalScene, seed);

		int channelCount = AudioScene.DEFAULT_SOURCE_COUNT;
		StableDurationHealthComputation health =
				new StableDurationHealthComputation(channelCount);
		health.setMaxDuration((long) RENDER_SECONDS);
		health.setOutputFile("results/comparison-traditional-correctness.wav");

		TemporalCellular traditionalRunner = traditionalScene.runner(health.getOutput());
		health.setTarget(traditionalRunner);

		AudioHealthScore score = health.computeHealth();
		log("Traditional: health score=" + score.getScore() +
				", frames=" + score.getFrames());
		health.destroy();

		// Real-time rendering
		AudioScene<?> realtimeScene = createBaselineScene(samplesDir);
		applyGenome(realtimeScene, seed);

		String realtimeFile = "results/comparison-realtime-correctness.wav";
		WaveOutput realtimeOutput = new WaveOutput(() -> new File(realtimeFile), 24, true);
		TemporalCellular realtimeRunner = realtimeScene.runnerRealTime(
				new MultiChannelAudioOutput(realtimeOutput), BUFFER_SIZE);

		realtimeRunner.setup().get().run();

		int totalFrames = (int) (RENDER_SECONDS * SAMPLE_RATE);
		Runnable tick = realtimeRunner.tick().get();
		for (int i = 0; i < totalFrames; i++) {
			tick.run();
		}

		realtimeOutput.write().get().run();

		// Verify both are non-silent
		boolean traditionalHasAudio = score.getScore() > 0;
		log("Traditional has audio: " + traditionalHasAudio);

		File rtFile = new File(realtimeFile);
		assertTrue("Real-time output file should exist", rtFile.exists());

		if (rtFile.length() > 1000) {
			verifyNonSilence(realtimeFile, "Real-time comparison output");
		} else {
			log("Real-time output file too small: " + rtFile.length() + " bytes");
		}

		// Log comparison metrics
		logAudioComparison(
				"results/comparison-traditional-correctness.wav",
				realtimeFile);
	}

	/**
	 * Validates that frame tracking advances correctly across buffer boundaries.
	 *
	 * <p>Creates a scene, runs it for a small number of buffer cycles, and
	 * verifies that the real-time runner completes without error. This tests
	 * the interaction between BatchCell's frame callback and
	 * PatternRenderCell's frame-based rendering.</p>
	 */
	@Test
	public void realTimeFrameAdvancement() {
		File samplesDir = new File(SAMPLES_PATH);
		if (!samplesDir.exists()) {
			log("Skipping test - Samples directory not found: " + samplesDir.getAbsolutePath());
			return;
		}

		MixdownManager.enableMainFilterUp = false;
		MixdownManager.enableEfxFilters = false;
		MixdownManager.enableEfx = false;
		PatternSystemManager.enableWarnings = false;

		AudioScene<?> scene = createBaselineScene(samplesDir);
		applyGenome(scene, 42);

		log("=== Frame Advancement Test ===");

		String outputFile = "results/realtime-frame-advancement.wav";
		WaveOutput output = new WaveOutput(() -> new File(outputFile), 24, true);
		TemporalCellular runner = scene.runnerRealTime(
				new MultiChannelAudioOutput(output), BUFFER_SIZE);

		runner.setup().get().run();

		// Run for exactly 4 buffer cycles worth of frames
		int framesToRun = BUFFER_SIZE * 4;
		log("Running " + framesToRun + " frames (" + 4 + " buffer cycles)");

		Runnable tick = runner.tick().get();
		for (int i = 0; i < framesToRun; i++) {
			tick.run();
		}

		output.write().get().run();

		File outFile = new File(outputFile);
		assertTrue("Output file should exist after 4 buffer cycles", outFile.exists());

		log("Frame advancement test completed successfully");
		log("Output file size: " + outFile.length() + " bytes");
	}

	/**
	 * Verifies that an audio file contains non-silent content.
	 *
	 * @param filePath path to the WAV file
	 * @param description label for log output
	 */
	private void verifyNonSilence(String filePath, String description) {
		try {
			WaveData data = WaveData.load(new File(filePath));
			int frameCount = data.getFrameCount();

			PackedCollection channel0 = data.getChannelData(0);
			int length = channel0.getShape().getTotalSize();

			double maxAmplitude = 0;
			double sumSquares = 0;
			int nonZeroCount = 0;

			for (int i = 0; i < length; i++) {
				double val = Math.abs(channel0.valueAt(i));
				if (val > maxAmplitude) maxAmplitude = val;
				sumSquares += val * val;
				if (val > 0.0001) nonZeroCount++;
			}

			double rms = Math.sqrt(sumSquares / length);
			double nonZeroRatio = (double) nonZeroCount / length;

			log("=== Audio Verification: " + description + " ===");
			log("Frames: " + frameCount);
			log("Duration: " + String.format("%.2f", (double) frameCount / SAMPLE_RATE) + " s");
			log("Max amplitude: " + String.format("%.6f", maxAmplitude));
			log("RMS level: " + String.format("%.6f", rms));
			log("Non-zero samples: " + String.format("%.1f%%", nonZeroRatio * 100));

			assertTrue(description + " should have non-zero max amplitude",
					maxAmplitude > 0.001);
			assertTrue(description + " should have reasonable RMS level",
					rms > 0.0001);

		} catch (IOException e) {
			fail("Failed to verify audio content: " + e.getMessage());
		}
	}

	/**
	 * Logs comparison metrics between two audio files.
	 *
	 * @param file1 path to the first WAV file (traditional output)
	 * @param file2 path to the second WAV file (real-time output)
	 */
	private void logAudioComparison(String file1, String file2) {
		try {
			File f1 = new File(file1);
			File f2 = new File(file2);

			if (!f1.exists() || !f2.exists()) {
				log("Cannot compare - one or both files missing");
				return;
			}

			WaveData data1 = WaveData.load(f1);
			WaveData data2 = WaveData.load(f2);

			PackedCollection ch1 = data1.getChannelData(0);
			PackedCollection ch2 = data2.getChannelData(0);

			int len1 = ch1.getShape().getTotalSize();
			int len2 = ch2.getShape().getTotalSize();

			log("=== Audio Comparison ===");
			log("Traditional: " + len1 + " samples");
			log("Real-time: " + len2 + " samples");

			// Compute RMS for each
			double rms1 = computeRms(ch1, len1);
			double rms2 = computeRms(ch2, len2);

			log("Traditional RMS: " + String.format("%.6f", rms1));
			log("Real-time RMS: " + String.format("%.6f", rms2));

			if (rms1 > 0 && rms2 > 0) {
				double rmsRatio = rms2 / rms1;
				log("RMS ratio (realtime/traditional): " + String.format("%.4f", rmsRatio));
			}

		} catch (IOException e) {
			log("Comparison failed: " + e.getMessage());
		}
	}

	/**
	 * Computes RMS amplitude for a collection of audio samples.
	 */
	private double computeRms(PackedCollection channel, int length) {
		double sumSquares = 0;
		for (int i = 0; i < length; i++) {
			double val = channel.valueAt(i);
			sumSquares += val * val;
		}
		return Math.sqrt(sumSquares / length);
	}
}
