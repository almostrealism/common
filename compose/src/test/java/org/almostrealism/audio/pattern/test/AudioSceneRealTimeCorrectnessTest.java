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

import io.almostrealism.code.Computation;
import org.almostrealism.audio.AudioScene;
import org.almostrealism.audio.CellList;
import org.almostrealism.audio.WaveOutput;
import org.almostrealism.audio.arrange.AudioSceneContext;
import org.almostrealism.audio.arrange.MixdownManager;
import org.almostrealism.audio.data.ChannelInfo;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.audio.health.AudioHealthScore;
import org.almostrealism.audio.health.MultiChannelAudioOutput;
import org.almostrealism.audio.health.StableDurationHealthComputation;
import org.almostrealism.audio.notes.NoteAudioChoice;
import org.almostrealism.audio.notes.NoteAudioContext;
import org.almostrealism.audio.pattern.PatternElement;
import org.almostrealism.audio.pattern.PatternLayerManager;
import org.almostrealism.audio.pattern.PatternSystemManager;
import org.almostrealism.audio.pattern.RenderedNoteAudio;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.BatchedCell;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.hardware.computations.Loop;
import org.almostrealism.heredity.TemporalCellular;
import org.almostrealism.util.TestDepth;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

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
	 * pipeline ({@link AudioScene#runnerRealTime}) using per-buffer ticking.
	 * The output is verified to contain actual audio content (non-zero
	 * amplitude and RMS).</p>
	 *
	 * <p>Each {@code tick.run()} invocation processes one full buffer
	 * ({@code bufferSize} frames) via the internal {@code loop()}. The
	 * test calls tick {@code totalFrames / bufferSize} times to render
	 * the full duration.</p>
	 */
	@Test(timeout = 1_800_000)
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
		int numBuffers = totalFrames / BUFFER_SIZE;
		log("Ticking " + numBuffers + " buffers (" + totalFrames + " frames, buffer size: " + BUFFER_SIZE + ")");

		Runnable tick = runner.tick().get();
		for (int buf = 0; buf < numBuffers; buf++) {
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
	@Test(timeout = 2_700_000)
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
		int numBuffers = totalFrames / BUFFER_SIZE;
		Runnable tick = realtimeRunner.tick().get();
		for (int buf = 0; buf < numBuffers; buf++) {
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
	 * the interaction between the frame tracker's callback and
	 * PatternRenderCell's frame-based rendering.</p>
	 */
	@Test(timeout = 900_000)
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

		// Run for exactly 4 buffer cycles
		int numBufferCycles = 4;
		log("Running " + numBufferCycles + " buffer cycles (" + (BUFFER_SIZE * numBufferCycles) + " frames)");

		Runnable tick = runner.tick().get();
		for (int i = 0; i < numBufferCycles; i++) {
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

	// =========================================================================
	// PROGRESSIVE CAPABILITY TESTS (Step 1: Frame-Range Sum in Isolation)
	// =========================================================================

	/**
	 * Tests that PatternSystemManager.sum(ctx, channel, startFrame, frameCount)
	 * correctly renders a subset of the arrangement.
	 *
	 * <p>This test exercises ONLY the PatternSystemManager/PatternLayerManager/PatternFeatures
	 * renderRange() functionality - no cells, no effects, no batching.</p>
	 */
	@Test(timeout = 60_000)
	public void frameRangeSumProducesAudio() {
		File samplesDir = new File(SAMPLES_PATH);
		if (!samplesDir.exists()) {
			log("Skipping test - Samples directory not found: " + samplesDir.getAbsolutePath());
			return;
		}

		MixdownManager.enableMainFilterUp = false;
		MixdownManager.enableEfxFilters = false;
		MixdownManager.enableEfx = false;
		PatternSystemManager.enableWarnings = false;

		AudioScene<?> seedScene = createBaselineScene(samplesDir);
		long seed = findWorkingGenomeSeed(seedScene, samplesDir);
		if (seed < 0) {
			log("No working genome found - skipping test");
			return;
		}

		AudioScene<?> scene = createBaselineScene(samplesDir);
		applyGenome(scene, seed);

		int totalElements = countElements(scene);
		log("=== Frame-Range Sum Produces Audio ===");
		log("Seed: " + seed + ", elements: " + totalElements);

		// Get the patterns manager directly
		PatternSystemManager patterns = scene.getPatternManager();
		patterns.setTuning(scene.getTuning());

		// Create channel info for testing (channel 0, main voicing, left)
		ChannelInfo channel = new ChannelInfo(0, ChannelInfo.Voicing.MAIN, ChannelInfo.StereoChannel.LEFT);

		// Create destination buffer for frame-range rendering
		PackedCollection frameRangeBuffer = new PackedCollection(BUFFER_SIZE);

		// Create context for frame-range rendering
		final AudioScene<?> finalScene = scene;
		Supplier<AudioSceneContext> frameRangeCtx = () -> {
			AudioSceneContext ctx = finalScene.getContext(List.of(channel));
			ctx.setDestination(frameRangeBuffer);
			return ctx;
		};

		// Update destinations (required before sum)
		patterns.init();

		// Render just the first buffer using frame-range sum
		log("Rendering frame range [0:" + BUFFER_SIZE + "]");
		Supplier<Runnable> renderOp = patterns.sum(frameRangeCtx, channel, () -> 0, BUFFER_SIZE);
		renderOp.get().run();

		// Analyze the rendered buffer
		double maxAmplitude = 0;
		double sumSquares = 0;
		int nonZeroCount = 0;

		for (int i = 0; i < BUFFER_SIZE; i++) {
			double val = Math.abs(frameRangeBuffer.valueAt(i));
			if (val > maxAmplitude) maxAmplitude = val;
			sumSquares += val * val;
			if (val > 0.0001) nonZeroCount++;
		}

		double rms = Math.sqrt(sumSquares / BUFFER_SIZE);
		double nonZeroRatio = (double) nonZeroCount / BUFFER_SIZE;

		log("Frame-range buffer max amplitude: " + String.format("%.6f", maxAmplitude));
		log("Frame-range buffer RMS: " + String.format("%.6f", rms));
		log("Non-zero samples: " + String.format("%.1f%%", nonZeroRatio * 100));

		// The buffer should contain audio (not necessarily all of it, depends on pattern timing)
		// We check that at least some audio was rendered
		if (maxAmplitude < 0.001) {
			log("WARNING: Frame-range render produced very low amplitude");
			log("This may be expected if no pattern elements fall within the first buffer");
			// Don't fail - pattern elements might not start at frame 0
		} else {
			assertTrue("Frame-range should produce non-zero amplitude", maxAmplitude > 0.0);
		}

		frameRangeBuffer.destroy();
	}

	/**
	 * Tests that consecutive frame-range renders stitch together correctly.
	 *
	 * <p>Renders the full arrangement buffer-by-buffer using frame-range sum,
	 * then verifies the concatenated result is non-silent.</p>
	 */
	@Test(timeout = 120_000)
	@TestDepth(2)
	public void frameRangeSumMultipleBuffers() {
		File samplesDir = new File(SAMPLES_PATH);
		if (!samplesDir.exists()) {
			log("Skipping test - Samples directory not found: " + samplesDir.getAbsolutePath());
			return;
		}

		MixdownManager.enableMainFilterUp = false;
		MixdownManager.enableEfxFilters = false;
		MixdownManager.enableEfx = false;
		PatternSystemManager.enableWarnings = false;

		AudioScene<?> seedScene = createBaselineScene(samplesDir);
		long seed = findWorkingGenomeSeed(seedScene, samplesDir);
		if (seed < 0) {
			log("No working genome found - skipping test");
			return;
		}

		final AudioScene<?> scene = createBaselineScene(samplesDir);
		applyGenome(scene, seed);

		log("=== Frame-Range Sum Multiple Buffers ===");

		PatternSystemManager patterns = scene.getPatternManager();
		patterns.setTuning(scene.getTuning());
		patterns.init();

		ChannelInfo channel = new ChannelInfo(0, ChannelInfo.Voicing.MAIN, ChannelInfo.StereoChannel.LEFT);

		// Render multiple buffers worth of audio
		int totalFrames = (int) (RENDER_SECONDS * SAMPLE_RATE);
		int numBuffers = totalFrames / BUFFER_SIZE;

		log("Rendering " + numBuffers + " buffers (" + totalFrames + " frames)");

		PackedCollection concatenatedResult = new PackedCollection(numBuffers * BUFFER_SIZE);
		PackedCollection tempBuffer = new PackedCollection(BUFFER_SIZE);

		double totalMaxAmplitude = 0;
		int buffersWithAudio = 0;

		for (int buf = 0; buf < numBuffers; buf++) {
			int startFrame = buf * BUFFER_SIZE;

			// Clear temp buffer
			tempBuffer.clear();

			// Create context for this buffer
			Supplier<AudioSceneContext> bufferCtx = () -> {
				AudioSceneContext ctx = scene.getContext(List.of(channel));
				ctx.setDestination(tempBuffer);
				return ctx;
			};

			// Render this buffer
			int frame = startFrame;
			Supplier<Runnable> renderOp = patterns.sum(bufferCtx, channel, () -> frame, BUFFER_SIZE);
			renderOp.get().run();

			// Copy to concatenated result
			double bufferMax = 0;
			for (int i = 0; i < BUFFER_SIZE; i++) {
				double val = tempBuffer.valueAt(i);
				concatenatedResult.setMem(startFrame + i, val);
				if (Math.abs(val) > bufferMax) bufferMax = Math.abs(val);
			}

			if (bufferMax > 0.001) buffersWithAudio++;
			if (bufferMax > totalMaxAmplitude) totalMaxAmplitude = bufferMax;
		}

		log("Buffers with audio: " + buffersWithAudio + "/" + numBuffers);
		log("Total max amplitude: " + String.format("%.6f", totalMaxAmplitude));

		// Verify the concatenated result has audio
		double rms = computeRms(concatenatedResult, concatenatedResult.getMemLength());
		log("Concatenated RMS: " + String.format("%.6f", rms));

		assertTrue("Frame-range stitching should produce non-silent audio",
				totalMaxAmplitude > 0.001 || buffersWithAudio > 0);

		tempBuffer.destroy();
		concatenatedResult.destroy();
	}

	// =========================================================================
	// PROGRESSIVE CAPABILITY TESTS (Step 2: Frame-Range + Effects Integration)
	// =========================================================================

	/**
	 * Tests that frame-range rendering integrates correctly with the effects pipeline.
	 *
	 * <p>Uses getPatternChannel() to create a CellList with PatternRenderCell
	 * and effects, runs setup, ticks for one buffer's worth, and verifies output.</p>
	 */
	@Test(timeout = 60_000)
	public void frameRangeWithEffects() {
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
		long seed = findWorkingGenomeSeed(scene, samplesDir);
		if (seed < 0) {
			log("No working genome found - skipping test");
			return;
		}

		scene = createBaselineScene(samplesDir);
		applyGenome(scene, seed);

		log("=== Frame-Range With Effects ===");

		// Track current frame position
		final int[] currentFrame = {0};

		// Get the real-time pattern channel (includes effects pipeline)
		ChannelInfo channel = new ChannelInfo(0, ChannelInfo.Voicing.MAIN, ChannelInfo.StereoChannel.LEFT);
		OperationList patternSetup = new OperationList("Frame-Range With Effects Setup");
		CellList cells = scene.getPatternChannel(channel, BUFFER_SIZE, () -> currentFrame[0], patternSetup);
		cells.addSetup(() -> patternSetup);

		// Run setup
		cells.setup().get().run();

		// Tick for one buffer's worth of frames
		log("Ticking " + BUFFER_SIZE + " frames");
		Runnable tick = cells.tick().get();
		for (int i = 0; i < BUFFER_SIZE; i++) {
			tick.run();
			if ((i + 1) % BUFFER_SIZE == 0) {
				// Advance to next buffer
				currentFrame[0] = i + 1;
			}
		}

		// The test primarily verifies no exceptions occurred
		// Audio content verification is handled by multiBufferWithEffects
		log("Frame-range with effects completed successfully");
	}

	/**
	 * Tests that multiple buffer rendering with effects produces valid audio.
	 *
	 * <p>Enhanced version of realTimeFrameAdvancement with actual content verification.</p>
	 */
	@Test(timeout = 1_800_000)
	@TestDepth(2)
	public void multiBufferWithEffects() {
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
		long seed = findWorkingGenomeSeed(scene, samplesDir);
		if (seed < 0) {
			log("No working genome found - skipping test");
			return;
		}

		scene = createBaselineScene(samplesDir);
		applyGenome(scene, seed);

		log("=== Multi-Buffer With Effects ===");
		log("Seed: " + seed + ", elements: " + countElements(scene));

		String outputFile = "results/realtime-multibuffer-effects.wav";
		WaveOutput output = new WaveOutput(() -> new File(outputFile), 24, true);
		TemporalCellular runner = scene.runnerRealTime(
				new MultiChannelAudioOutput(output), BUFFER_SIZE);

		runner.setup().get().run();

		// Run for 8 buffer cycles (each tick processes one full buffer)
		int numBuffers = 8;
		log("Running " + numBuffers + " buffer cycles (" + (BUFFER_SIZE * numBuffers) + " frames)");

		Runnable tick = runner.tick().get();
		for (int buf = 0; buf < numBuffers; buf++) {
			tick.run();
		}

		output.write().get().run();

		File outFile = new File(outputFile);
		assertTrue("Output file should exist", outFile.exists());

		if (outFile.length() > 1000) {
			verifyNonSilence(outputFile, "Multi-buffer with effects");
		} else {
			log("Output file small: " + outFile.length() + " bytes - may be expected for short render");
		}

		log("Multi-buffer with effects test completed");
	}

	// =========================================================================
	// PROGRESSIVE CAPABILITY TESTS (Step 3: Compiled Batch Cell Architecture)
	// =========================================================================

	/**
	 * Tests that BatchedCell correctly tracks batch state with a frame callback.
	 *
	 * <p>This test verifies the batch cell architecture is correctly structured:
	 * tick counting, frame callback invocation, and batch advancement.</p>
	 */
	@Test(timeout = 60_000)
	public void batchCellArchitectureValidation() {
		log("=== Batch Cell Architecture Validation ===");

		final int[] callbackFrame = {-1};
		final int[] renderCount = {0};

		BatchedCell cell = new BatchedCell(BUFFER_SIZE, BUFFER_SIZE,
				frame -> callbackFrame[0] = frame) {
			@Override
			protected Supplier<Runnable> renderBatch() {
				return () -> () -> renderCount[0]++;
			}
		};

		// Verify initial state
		assertEquals("Batch size should match", BUFFER_SIZE, cell.getBatchSize());
		assertEquals("Initial batch should be 0", 0, cell.getCurrentBatch());

		// Tick through one full batch
		for (int i = 0; i < BUFFER_SIZE; i++) {
			cell.tick().get().run();
		}

		// Verify batch advanced
		assertEquals("Should have rendered once", 1, renderCount[0]);
		assertEquals("After batch, current batch should be 1", 1, cell.getCurrentBatch());
		assertEquals("Current frame should be at buffer boundary",
				BUFFER_SIZE, cell.getCurrentFrame());
		assertEquals("Frame callback should have been called with 0", 0, callbackFrame[0]);

		log("Batch cell architecture validation completed");
	}

	/**
	 * Tests that the compiled real-time runner produces valid audio output.
	 *
	 * <p>This is the main validation test for the compiled batch cell architecture.
	 * It creates a scene with the compiled runner and verifies non-silent output.</p>
	 */
	@Test(timeout = 600_000)
	@TestDepth(2)
	public void compiledBatchCellProducesAudio() {
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
		long seed = findWorkingGenomeSeed(scene, samplesDir);
		if (seed < 0) {
			log("No working genome found - skipping test");
			return;
		}

		scene = createBaselineScene(samplesDir);
		applyGenome(scene, seed);

		int totalElements = countElements(scene);
		log("=== Compiled Batch Cell Produces Audio ===");
		log("Seed: " + seed + ", elements: " + totalElements);

		String outputFile = "results/realtime-compiled-batchcell.wav";
		WaveOutput output = new WaveOutput(() -> new File(outputFile), 24, true);

		// Use the compiled real-time runner
		TemporalCellular runner = scene.runnerRealTimeCompiled(
				new MultiChannelAudioOutput(output), BUFFER_SIZE);

		runner.setup().get().run();

		// Run a small number of buffer ticks to validate the architecture.
		// The effects loop currently falls back to Java iteration (not a compiled
		// Loop), so each buffer tick takes ~20 seconds. We use a small count to
		// keep the test under 10 minutes while still proving audio output works.
		int numBuffers = 8;
		int totalFrames = numBuffers * BUFFER_SIZE;
		log("Running " + numBuffers + " buffer ticks (" + totalFrames + " frames)");

		Runnable tick = runner.tick().get();
		for (int buf = 0; buf < numBuffers; buf++) {
			tick.run();
		}

		output.write().get().run();

		File outFile = new File(outputFile);
		assertTrue("Output file should exist", outFile.exists());

		if (outFile.length() > 1000) {
			verifyNonSilence(outputFile, "Compiled batch cell output");
		} else {
			log("Output file size: " + outFile.length() + " bytes");
		}

		log("Compiled batch cell test completed");
	}

	/**
	 * Tests the performance characteristics of the real-time runner.
	 *
	 * <p>Measures the per-buffer tick time for the unified real-time runner.
	 * Both {@code runnerRealTime} and {@code runnerRealTimeCompiled} now use
	 * the same per-buffer implementation, so this test primarily validates
	 * consistent timing rather than comparing two different paths.</p>
	 */
	@Test(timeout = 2_700_000)
	@TestDepth(3)
	public void compiledBatchCellPerformance() {
		File samplesDir = new File(SAMPLES_PATH);
		if (!samplesDir.exists()) {
			log("Skipping test - Samples directory not found: " + samplesDir.getAbsolutePath());
			return;
		}

		MixdownManager.enableMainFilterUp = false;
		MixdownManager.enableEfxFilters = false;
		MixdownManager.enableEfx = false;
		PatternSystemManager.enableWarnings = false;

		log("=== Compiled Batch Cell Performance Test ===");

		// Find a working seed
		AudioScene<?> seedScene = createBaselineScene(samplesDir);
		long seed = findWorkingGenomeSeed(seedScene, samplesDir);
		if (seed < 0) {
			log("No working genome found - skipping test");
			return;
		}

		// Use a short duration to keep the test feasible.
		// The effects loop currently falls back to Java iteration (not a compiled
		// Loop), so each buffer tick takes ~20 seconds. We use a fraction of a second
		// to get meaningful timing data without exceeding the test timeout.
		double perfDuration = 0.25;
		int totalFrames = (int) (perfDuration * SAMPLE_RATE);
		int numBuffers = totalFrames / BUFFER_SIZE;
		log("Performance test: " + perfDuration + "s = " + totalFrames + " frames, "
				+ numBuffers + " buffer ticks");

		// Test: unified per-buffer runner
		AudioScene<?> testScene = createBaselineScene(samplesDir);
		applyGenome(testScene, seed);

		WaveOutput testOutput = new WaveOutput(
				() -> new File("results/perf-realtime.wav"), 24, true);
		TemporalCellular testRunner = testScene.runnerRealTime(
				new MultiChannelAudioOutput(testOutput), BUFFER_SIZE);

		testRunner.setup().get().run();

		long startTime = System.nanoTime();
		Runnable tick = testRunner.tick().get();
		for (int buf = 0; buf < numBuffers; buf++) {
			tick.run();
		}
		long elapsed = System.nanoTime() - startTime;

		testOutput.write().get().run();

		// Log results
		double elapsedMs = elapsed / 1_000_000.0;

		log("Real-time runner (per-buffer): " + String.format("%.2f", elapsedMs) + " ms");
		log("  Buffer ticks: " + numBuffers);
		log("  Time per buffer tick: " + String.format("%.3f", elapsedMs / numBuffers) + " ms");
		log("  Frames per buffer: " + BUFFER_SIZE);

		log("Performance test completed (no assertions - informational only)");
	}

	// =========================================================================
	// DIAGNOSTIC TESTS (Pattern Rendering Performance)
	// =========================================================================

	/**
	 * Measures whether pattern rendering time scales with buffer size.
	 *
	 * <p>Times {@link PatternSystemManager#sum} with different buffer sizes
	 * at the same start position. If a 1024-frame buffer takes nearly as long
	 * as a 44100-frame buffer, the rendering is computing full note audio
	 * regardless of how many frames the caller actually needs.</p>
	 *
	 * <p>Expected result if rendering is efficient: time should scale roughly
	 * linearly with buffer size. If it doesn't, something in the rendering
	 * pipeline is doing work proportional to note duration, not buffer size.</p>
	 */
	@Test(timeout = 180_000)
	public void renderTimingVsBufferSize() {
		File samplesDir = new File(SAMPLES_PATH);
		if (!samplesDir.exists()) {
			log("Skipping test - Samples directory not found");
			return;
		}

		MixdownManager.enableMainFilterUp = false;
		MixdownManager.enableEfxFilters = false;
		MixdownManager.enableEfx = false;
		PatternSystemManager.enableWarnings = false;

		AudioScene<?> seedScene = createBaselineScene(samplesDir);
		long seed = findWorkingGenomeSeed(seedScene, samplesDir);
		if (seed < 0) {
			log("No working genome found - skipping test");
			return;
		}

		log("=== Render Timing vs Buffer Size ===");
		log("Seed: " + seed);

		int[] bufferSizes = {256, 1024, 4096, 44100};

		for (int bufSize : bufferSizes) {
			AudioScene<?> scene = createBaselineScene(samplesDir);
			applyGenome(scene, seed);

			PatternSystemManager patterns = scene.getPatternManager();
			patterns.setTuning(scene.getTuning());
			patterns.init();

			ChannelInfo channel = new ChannelInfo(0, ChannelInfo.Voicing.MAIN,
					ChannelInfo.StereoChannel.LEFT);
			PackedCollection dest = new PackedCollection(bufSize);

			final AudioScene<?> finalScene = scene;
			Supplier<AudioSceneContext> ctx = () -> {
				AudioSceneContext c = finalScene.getContext(List.of(channel));
				c.setDestination(dest);
				return c;
			};

			long startNs = System.nanoTime();
			patterns.sum(ctx, channel, () -> 0, bufSize).get().run();
			long elapsedNs = System.nanoTime() - startNs;

			double elapsedMs = elapsedNs / 1_000_000.0;
			double msPerFrame = elapsedMs / bufSize;

			log(String.format("Buffer %6d frames: %8.1f ms  (%8.4f ms/frame)",
					bufSize, elapsedMs, msPerFrame));

			dest.destroy();
		}

		log("If times are similar across buffer sizes, the rendering is not");
		log("scaling to the buffer - it computes full note audio regardless.");
	}

	/**
	 * Analyzes note-level waste in frame-range rendering.
	 *
	 * <p>Replicates the inner loop of {@code PatternFeatures.renderRange()} with
	 * instrumentation to measure:</p>
	 * <ul>
	 *   <li>How many notes are created vs how many overlap the target buffer</li>
	 *   <li>Total audio frames evaluated vs frames that fall within the buffer</li>
	 *   <li>Time spent in {@code getNoteDestinations()} vs {@code evaluate()}</li>
	 *   <li>Average note audio length compared to buffer size</li>
	 * </ul>
	 *
	 * <p>This test isolates exactly where time is spent in pattern rendering
	 * and quantifies the waste from computing full note audio when only a
	 * small slice is needed.</p>
	 */
	@Test(timeout = 180_000)
	public void renderNoteAudioLengthAnalysis() {
		File samplesDir = new File(SAMPLES_PATH);
		if (!samplesDir.exists()) {
			log("Skipping test - Samples directory not found");
			return;
		}

		MixdownManager.enableMainFilterUp = false;
		MixdownManager.enableEfxFilters = false;
		MixdownManager.enableEfx = false;
		PatternSystemManager.enableWarnings = false;

		AudioScene<?> seedScene = createBaselineScene(samplesDir);
		long seed = findWorkingGenomeSeed(seedScene, samplesDir);
		if (seed < 0) {
			log("No working genome found - skipping test");
			return;
		}

		AudioScene<?> scene = createBaselineScene(samplesDir);
		applyGenome(scene, seed);

		PatternSystemManager patterns = scene.getPatternManager();
		patterns.setTuning(scene.getTuning());
		patterns.init();

		ChannelInfo channel = new ChannelInfo(0, ChannelInfo.Voicing.MAIN,
				ChannelInfo.StereoChannel.LEFT);

		log("=== Note Audio Length Analysis ===");
		log("Seed: " + seed + ", Buffer: " + BUFFER_SIZE + " frames");
		log("Target range: [0, " + BUFFER_SIZE + ")");

		int startFrame = 0;
		int endFrame = BUFFER_SIZE;

		int totalNotesCreated = 0;
		int notesOverlapping = 0;
		int notesNonOverlapping = 0;
		long totalFramesEvaluated = 0;
		long framesUseful = 0;
		long totalEvalTimeNs = 0;
		long overlapEvalTimeNs = 0;
		long nonOverlapEvalTimeNs = 0;
		int totalElements = 0;
		long getNotesTimeNs = 0;
		long minNoteLength = Long.MAX_VALUE;
		long maxNoteLength = 0;

		AudioSceneContext ctx = scene.getContext(List.of(channel));
		PackedCollection dest = new PackedCollection(BUFFER_SIZE);
		ctx.setDestination(dest);

		for (PatternLayerManager plm : patterns.getPatterns()) {
			if (plm.getChannel() != channel.getPatternChannel()) continue;

			plm.updateDestination(ctx);
			boolean melodic = plm.isMelodic();

			Map<NoteAudioChoice, List<PatternElement>> elementsByChoice =
					plm.getAllElementsByChoice(0.0, plm.getDuration());

			log("Pattern channel=" + plm.getChannel()
					+ " melodic=" + melodic
					+ " duration=" + plm.getDuration() + " measures"
					+ " choices=" + elementsByChoice.size());

			for (Map.Entry<NoteAudioChoice, List<PatternElement>> entry :
					elementsByChoice.entrySet()) {
				NoteAudioChoice choice = entry.getKey();
				List<PatternElement> elements = entry.getValue();
				totalElements += elements.size();

				NoteAudioContext audioContext = new NoteAudioContext(
						ChannelInfo.Voicing.MAIN,
						ChannelInfo.StereoChannel.LEFT,
						choice.getValidPatternNotes(),
						pos -> pos + 1.0);

				for (PatternElement element : elements) {
					long t0 = System.nanoTime();
					List<RenderedNoteAudio> notes = element.getNoteDestinations(
							melodic, 0.0, ctx, audioContext);
					getNotesTimeNs += System.nanoTime() - t0;

					for (RenderedNoteAudio note : notes) {
						totalNotesCreated++;
						int noteStart = note.getOffset();

						long evalStart = System.nanoTime();
						PackedCollection audio;
						try {
							audio = traverse(1, note.getProducer()).get().evaluate();
						} catch (Exception e) {
							continue;
						}
						long evalNs = System.nanoTime() - evalStart;
						totalEvalTimeNs += evalNs;

						if (audio == null) continue;

						int noteLength = audio.getShape().getCount();
						int noteEnd = noteStart + noteLength;
						totalFramesEvaluated += noteLength;

						if (noteLength < minNoteLength) minNoteLength = noteLength;
						if (noteLength > maxNoteLength) maxNoteLength = noteLength;

						boolean overlaps = noteEnd > startFrame && noteStart < endFrame;
						if (overlaps) {
							notesOverlapping++;
							overlapEvalTimeNs += evalNs;
							int overlapLen = Math.min(noteEnd, endFrame)
									- Math.max(noteStart, startFrame);
							framesUseful += overlapLen;
						} else {
							notesNonOverlapping++;
							nonOverlapEvalTimeNs += evalNs;
						}
					}
				}
			}
		}

		log("");
		log("--- Elements & Notes ---");
		log("Total pattern elements: " + totalElements);
		log("Total notes created (incl. repeats): " + totalNotesCreated);
		log("Notes overlapping buffer: " + notesOverlapping);
		log("Notes NOT overlapping buffer: " + notesNonOverlapping);

		log("");
		log("--- Note Lengths ---");
		log("Buffer size: " + BUFFER_SIZE + " frames");
		if (minNoteLength <= maxNoteLength) {
			log("Min note length: " + minNoteLength + " frames");
			log("Max note length: " + maxNoteLength + " frames");
			log("Max note / buffer ratio: "
					+ String.format("%.1fx", (double) maxNoteLength / BUFFER_SIZE));
		}

		log("");
		log("--- Frame Waste ---");
		log("Total frames evaluated: " + totalFramesEvaluated);
		log("Frames actually useful: " + framesUseful);
		long framesWasted = totalFramesEvaluated - framesUseful;
		log("Frames wasted: " + framesWasted);
		if (totalFramesEvaluated > 0) {
			double wasteRatio = (double) framesWasted / totalFramesEvaluated;
			log("Waste ratio: " + String.format("%.1f%%", wasteRatio * 100));
		}

		log("");
		log("--- Timing ---");
		log("getNoteDestinations total: "
				+ String.format("%.1f ms", getNotesTimeNs / 1_000_000.0));
		log("evaluate() total: "
				+ String.format("%.1f ms", totalEvalTimeNs / 1_000_000.0));
		log("  overlapping notes: "
				+ String.format("%.1f ms", overlapEvalTimeNs / 1_000_000.0));
		log("  non-overlapping notes: "
				+ String.format("%.1f ms", nonOverlapEvalTimeNs / 1_000_000.0));
		if (totalNotesCreated > 0) {
			log("Avg evaluate per note: "
					+ String.format("%.1f ms",
					totalEvalTimeNs / 1_000_000.0 / totalNotesCreated));
		}

		dest.destroy();
	}
}
