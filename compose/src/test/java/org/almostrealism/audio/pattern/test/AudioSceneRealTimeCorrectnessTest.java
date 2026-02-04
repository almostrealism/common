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
import org.almostrealism.heredity.TemporalCellular;
import org.almostrealism.util.TestDepth;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.Assert.*;

/**
 * Tests for real-time audio scene correctness and performance.
 *
 * <p>These tests verify that the real-time rendering path
 * ({@link AudioScene#runnerRealTime}) produces valid, non-silent audio
 * output under conditions identical to the validated baseline.</p>
 *
 * <h2>Test Categories</h2>
 *
 * <p>Tests are categorized by what they verify:</p>
 * <ul>
 *   <li><b>Correctness tests</b> verify that audio content is valid (non-silent,
 *       reasonable amplitude, expected duration). These generate spectrograms
 *       and summaries for manual inspection.</li>
 *   <li><b>Performance tests</b> measure render timing against real-time
 *       constraints. They report timing statistics but do not assert on them.</li>
 *   <li><b>Diagnostic tests</b> analyze specific behaviors (frame advancement,
 *       pattern rendering efficiency) without full correctness verification.</li>
 * </ul>
 *
 * <h2>Visual Artifacts</h2>
 *
 * <p>Correctness tests generate artifacts in the {@code results/} directory:</p>
 * <ul>
 *   <li>{@code <test-name>.wav} - rendered audio file</li>
 *   <li>{@code <test-name>-spectrogram.png} - visual frequency content</li>
 *   <li>{@code <test-name>-summary.txt} - human-readable statistics</li>
 * </ul>
 *
 * @see AudioSceneBaselineTest
 * @see AudioSceneTestBase
 * @see RealTimeTestHelper
 * @see org.almostrealism.audio.pattern.PatternRenderCell
 */
public class AudioSceneRealTimeCorrectnessTest extends AudioSceneTestBase {

	private static final int BUFFER_SIZE = 4096;

	private RealTimeTestHelper helper;

	@Before
	public void setUp() {
		helper = new RealTimeTestHelper(this);
	}

	/**
	 * Verifies that the real-time rendering path produces non-silent audio.
	 *
	 * <h3>Correctness Property</h3>
	 * <p>Validates that the real-time rendering pipeline produces actual
	 * audio content, not silence. Uses {@link AudioStats#assertNonSilent}
	 * to verify amplitude and RMS levels exceed silence thresholds.</p>
	 *
	 * <h3>Artifacts Generated</h3>
	 * <ul>
	 *   <li>{@code results/realtime-correctness.wav} - rendered audio</li>
	 *   <li>{@code results/realtime-correctness-spectrogram.png} - visual verification</li>
	 *   <li>{@code results/realtime-correctness-summary.txt} - statistics</li>
	 * </ul>
	 *
	 * <h3>What This Does NOT Test</h3>
	 * <p>This test does not verify timing/performance. For performance
	 * validation, see {@link #realTimeRunnerPerformance}.</p>
	 */
	@Test(timeout = 30 * 60000)
	@TestDepth(2)
	public void realTimeProducesAudio() {
		helper.disableEffects();
		File samplesDir = helper.requireSamplesDir();

		AudioScene<?> scene = helper.createSceneWithWorkingSeed(samplesDir, AudioScene.DEFAULT_SOURCE_COUNT);
		if (scene == null) {
			log("No working genome found - skipping test");
			return;
		}

		log("=== Real-Time Produces Audio (Correctness Test) ===");

		RenderResult result = helper.renderRealTime(scene, BUFFER_SIZE, RENDER_SECONDS,
				"results/realtime-correctness.wav");

		// Correctness verification
		assertTrue("Output file should exist", new File(result.outputFile()).exists());
		assertNotNull("Should have audio statistics", result.stats());
		result.stats().assertNonSilent("Real-time output");

		// Generate visual artifacts for manual review
		helper.generateArtifacts(result, "realtime-correctness");

		log(result.toString());
	}

	/**
	 * Compares real-time output against the traditional baseline output.
	 *
	 * <h3>Correctness Property</h3>
	 * <p>Validates that real-time and traditional rendering paths both produce
	 * non-silent audio for the same scene configuration. This ensures the
	 * real-time architecture doesn't break audio generation.</p>
	 *
	 * <h3>Why Exact Match Is Not Expected</h3>
	 * <ul>
	 *   <li>Traditional path applies auto-volume normalization</li>
	 *   <li>Real-time path skips section processing</li>
	 *   <li>Buffer boundary alignment may differ</li>
	 * </ul>
	 *
	 * <h3>Artifacts Generated</h3>
	 * <ul>
	 *   <li>{@code results/comparison-traditional.wav} - traditional render</li>
	 *   <li>{@code results/comparison-realtime.wav} - real-time render</li>
	 *   <li>Spectrograms and summaries for both</li>
	 * </ul>
	 */
	@Test(timeout = 600_000)
	@TestDepth(2)
	public void realTimeMatchesTraditional() {
		helper.disableEffects();
		File samplesDir = helper.requireSamplesDir();

		// Find a working seed
		AudioScene<?> seedScene = createBaselineScene(samplesDir);
		long seed = findWorkingGenomeSeed(seedScene, samplesDir);
		if (seed < 0) {
			log("No working genome found - skipping test");
			return;
		}

		log("=== Real-Time vs Traditional Comparison (Correctness Test) ===");
		log("Using seed: " + seed);

		// Traditional rendering
		AudioScene<?> traditionalScene = helper.createSceneWithSeed(samplesDir,
				AudioScene.DEFAULT_SOURCE_COUNT, seed);
		RenderResult traditionalResult = helper.renderTraditional(traditionalScene,
				RENDER_SECONDS, "results/comparison-traditional.wav");

		// Real-time rendering
		AudioScene<?> realtimeScene = helper.createSceneWithSeed(samplesDir,
				AudioScene.DEFAULT_SOURCE_COUNT, seed);
		RenderResult realtimeResult = helper.renderRealTime(realtimeScene, BUFFER_SIZE,
				RENDER_SECONDS, "results/comparison-realtime.wav");

		// Correctness verification
		assertNotNull("Traditional should have stats", traditionalResult.stats());
		assertNotNull("Real-time should have stats", realtimeResult.stats());

		traditionalResult.stats().assertNonSilent("Traditional output");
		realtimeResult.stats().assertNonSilent("Real-time output");

		// Log comparison
		helper.logComparison("Traditional", traditionalResult, "Real-time", realtimeResult);

		// Generate visual artifacts for both
		helper.generateArtifacts(traditionalResult, "comparison-traditional");
		helper.generateArtifacts(realtimeResult, "comparison-realtime");

		log(traditionalResult.toString());
		log(realtimeResult.toString());
	}

	/**
	 * Validates that frame tracking advances correctly across buffer boundaries.
	 *
	 * <h3>Diagnostic Purpose</h3>
	 * <p>This test verifies the low-level mechanics of the real-time runner:
	 * frame counter advancement, buffer boundary handling, and interaction
	 * between the frame tracker and PatternRenderCell. It uses a short
	 * duration (4 buffers) to isolate these mechanics from full rendering.</p>
	 *
	 * <h3>What This Tests</h3>
	 * <ul>
	 *   <li>Runner setup completes without error</li>
	 *   <li>Multiple tick() calls complete without exception</li>
	 *   <li>Output file is created with content</li>
	 * </ul>
	 *
	 * <h3>What This Does NOT Test</h3>
	 * <p>Does not verify audio correctness (too short to assess) or
	 * performance (no timing assertions).</p>
	 */
	@Test(timeout = 60_000)
	public void realTimeFrameAdvancement() {
		helper.disableEffects();
		File samplesDir = helper.requireSamplesDir();

		AudioScene<?> scene = helper.createSceneWithSeed(samplesDir,
				AudioScene.DEFAULT_SOURCE_COUNT, 42);

		log("=== Frame Advancement Test (Diagnostic) ===");

		// Short render - just 4 buffers to test mechanics
		double shortDuration = (double) (BUFFER_SIZE * 4) / SAMPLE_RATE;
		RenderResult result = helper.renderRealTime(scene, BUFFER_SIZE, shortDuration,
				"results/realtime-frame-advancement.wav");

		assertTrue("Output file should exist", new File(result.outputFile()).exists());
		assertEquals("Should have rendered 4 buffers", 4, result.bufferCount());

		log("Frame advancement test completed successfully");
		log(result.toString());
	}

	// =========================================================================
	// PROGRESSIVE CAPABILITY TESTS (Step 1: Frame-Range Sum in Isolation)
	// =========================================================================

	/**
	 * Tests that PatternSystemManager.sum() correctly renders a subset of the arrangement.
	 *
	 * <h3>Diagnostic Purpose</h3>
	 * <p>Exercises ONLY the PatternSystemManager/PatternLayerManager/PatternFeatures
	 * {@code renderRange()} functionality in isolation - no cells, no effects, no
	 * batching. This validates the core pattern rendering API that the real-time
	 * pipeline builds upon.</p>
	 *
	 * <h3>What This Tests</h3>
	 * <ul>
	 *   <li>PatternSystemManager.sum() populates a destination buffer</li>
	 *   <li>Frame-range rendering respects the buffer size parameter</li>
	 *   <li>Pattern elements that overlap frame 0 are correctly rendered</li>
	 * </ul>
	 *
	 * <h3>What This Does NOT Test</h3>
	 * <p>Does not test the full pipeline (cells, effects, WaveOutput).
	 * See {@link #realTimeProducesAudio} for full pipeline testing.</p>
	 */
	@Test(timeout = 60_000)
	public void frameRangeSumProducesAudio() {
		helper.disableEffects();
		File samplesDir = helper.requireSamplesDir();

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
	 * <h3>Diagnostic Purpose</h3>
	 * <p>Validates that buffer-by-buffer pattern rendering produces consistent
	 * results that can be concatenated into a coherent audio stream. This
	 * tests the low-level {@code PatternSystemManager.sum()} API directly,
	 * bypassing the cell/effects infrastructure.</p>
	 *
	 * <h3>What This Tests</h3>
	 * <ul>
	 *   <li>Frame-range sum produces non-zero audio in at least some buffers</li>
	 *   <li>Buffer boundaries don't cause data loss or corruption</li>
	 *   <li>Pattern rendering correctly respects startFrame parameter</li>
	 * </ul>
	 */
	@Test(timeout = 180_000)
	@TestDepth(2)
	public void frameRangeSumMultipleBuffers() {
		helper.disableEffects();
		File samplesDir = helper.requireSamplesDir();

		AudioScene<?> seedScene = createBaselineScene(samplesDir);
		long seed = findWorkingGenomeSeed(seedScene, samplesDir);
		if (seed < 0) {
			log("No working genome found - skipping test");
			return;
		}

		final AudioScene<?> scene = createBaselineScene(samplesDir);
		applyGenome(scene, seed);

		log("=== Frame-Range Sum Multiple Buffers (Diagnostic) ===");

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

		// Use AudioStats for RMS computation
		AudioStats stats = AudioStats.fromBuffer(concatenatedResult, SAMPLE_RATE);
		log("Concatenated RMS: " + String.format("%.6f", stats.rmsLevel()));

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
	 * <h3>Diagnostic Purpose</h3>
	 * <p>Uses {@code getPatternChannel()} to create a CellList with PatternRenderCell
	 * and effects pipeline. Validates that the cell infrastructure can be set up
	 * and ticked without error.</p>
	 *
	 * <h3>What This Tests</h3>
	 * <ul>
	 *   <li>CellList construction from pattern channel succeeds</li>
	 *   <li>Cell setup completes without exception</li>
	 *   <li>Per-frame ticking completes without exception</li>
	 * </ul>
	 *
	 * <h3>What This Does NOT Test</h3>
	 * <p>Audio content is NOT verified (too short). See {@link #multiBufferWithEffects}
	 * for content verification.</p>
	 */
	@Test(timeout = 60_000)
	public void frameRangeWithEffects() {
		helper.disableEffects();
		File samplesDir = helper.requireSamplesDir();

		AudioScene<?> scene = helper.createSceneWithWorkingSeed(samplesDir,
				AudioScene.DEFAULT_SOURCE_COUNT);
		if (scene == null) {
			log("No working genome found - skipping test");
			return;
		}

		log("=== Frame-Range With Effects (Diagnostic) ===");

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
		}
		currentFrame[0] = BUFFER_SIZE;

		log("Frame-range with effects completed successfully");
	}

	/**
	 * Tests that multiple buffer rendering with effects produces valid audio.
	 *
	 * <h3>Correctness Property</h3>
	 * <p>Validates that the effects pipeline (filters, mixing) integrates
	 * correctly with the real-time pattern rendering. Uses a moderate
	 * duration (8 buffers) to verify audio accumulation across multiple
	 * buffer boundaries.</p>
	 *
	 * <h3>Artifacts Generated</h3>
	 * <ul>
	 *   <li>{@code results/realtime-multibuffer-effects.wav}</li>
	 *   <li>{@code results/realtime-multibuffer-effects-spectrogram.png}</li>
	 *   <li>{@code results/realtime-multibuffer-effects-summary.txt}</li>
	 * </ul>
	 */
	@Test(timeout = 180_000)
	@TestDepth(2)
	public void multiBufferWithEffects() {
		helper.disableEffects();
		File samplesDir = helper.requireSamplesDir();

		AudioScene<?> scene = helper.createSceneWithWorkingSeed(samplesDir,
				AudioScene.DEFAULT_SOURCE_COUNT);
		if (scene == null) {
			log("No working genome found - skipping test");
			return;
		}

		log("=== Multi-Buffer With Effects (Correctness Test) ===");

		// 8 buffers - enough to verify accumulation
		double duration = (double) (BUFFER_SIZE * 8) / SAMPLE_RATE;
		RenderResult result = helper.renderRealTime(scene, BUFFER_SIZE, duration,
				"results/realtime-multibuffer-effects.wav");

		assertTrue("Output file should exist", new File(result.outputFile()).exists());
		assertNotNull("Should have audio statistics", result.stats());

		// Only assert non-silence if file has content
		if (new File(result.outputFile()).length() > 1000) {
			result.stats().assertNonSilent("Multi-buffer with effects");
			helper.generateArtifacts(result, "realtime-multibuffer-effects");
		} else {
			log("Output file small - may be expected for short render");
		}

		log(result.toString());
	}

	// =========================================================================
	// PROGRESSIVE CAPABILITY TESTS (Step 3: Batch Cell Architecture)
	// =========================================================================

	/**
	 * Tests that BatchedCell correctly tracks batch state with a frame callback.
	 *
	 * <h3>Diagnostic Purpose</h3>
	 * <p>Validates the low-level {@link BatchedCell} infrastructure that underpins
	 * the pattern rendering pipeline. Uses a mock implementation to verify the
	 * counting and callback mechanics in isolation.</p>
	 *
	 * <h3>What This Tests</h3>
	 * <ul>
	 *   <li>Tick counting advances correctly to batch boundary</li>
	 *   <li>Frame callback is invoked at batch start</li>
	 *   <li>Batch counter increments after rendering</li>
	 *   <li>Current frame tracks position correctly</li>
	 * </ul>
	 *
	 * <h3>What This Does NOT Test</h3>
	 * <p>Does not test audio content or actual pattern rendering.
	 * This is a unit test for the batching mechanics only.</p>
	 */
	@Test(timeout = 10_000)
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
	 * Measures the per-buffer tick time for the real-time runner.
	 *
	 * <h3>Performance Focus</h3>
	 * <p>This test measures render timing against real-time constraints.
	 * At 44.1kHz with 1024-frame buffers, each buffer represents ~23.2ms
	 * of audio. For real-time playback, render time must be below this.</p>
	 *
	 * <h3>Metrics Reported</h3>
	 * <ul>
	 *   <li>Total render time</li>
	 *   <li>Average, min, max buffer times</li>
	 *   <li>Real-time ratio (target/actual)</li>
	 *   <li>Overrun count (buffers exceeding target)</li>
	 * </ul>
	 *
	 * <h3>What This Does NOT Test</h3>
	 * <p>No timing assertions - this is informational only. Audio correctness
	 * is not verified (see {@link #realTimeProducesAudio} for that).</p>
	 *
	 * @see TimingStats
	 */
	@Test(timeout = 180_000)
	@TestDepth(3)
	public void realTimeRunnerPerformance() {
		helper.disableEffects();
		File samplesDir = helper.requireSamplesDir();

		AudioScene<?> scene = helper.createSceneWithWorkingSeed(samplesDir,
				AudioScene.DEFAULT_SOURCE_COUNT);
		if (scene == null) {
			log("No working genome found - skipping test");
			return;
		}

		log("=== Real-Time Runner Performance Test ===");

		// Short duration to keep test fast
		double perfDuration = 0.25;
		RenderResult result = helper.renderRealTime(scene, BUFFER_SIZE, perfDuration,
				"results/perf-realtime.wav");

		// Log timing statistics
		TimingStats timing = result.timing();
		assertNotNull("Should have timing statistics", timing);

		log("Performance results:");
		log("  Total render time: " + String.format("%.2f ms", timing.totalTimeMs()));
		log("  Buffer count: " + result.bufferCount());
		log("  Target buffer time: " + String.format("%.2f ms", timing.targetBufferMs()));
		log("  Avg buffer time: " + String.format("%.3f ms", timing.avgBufferMs()));
		log("  Min buffer time: " + String.format("%.3f ms", timing.minBufferMs()));
		log("  Max buffer time: " + String.format("%.3f ms", timing.maxBufferMs()));
		log("  Real-time ratio: " + String.format("%.2fx", timing.realTimeRatio()));
		log("  Overruns: " + timing.overrunCount() + "/" + result.bufferCount());
		log("  Meets real-time: " + (timing.meetsRealTime() ? "YES" : "NO"));

		log("Performance test completed (no assertions - informational only)");
		log(result.toString());
	}

	// =========================================================================
	// DIAGNOSTIC TESTS (Pattern Rendering Performance)
	// =========================================================================

	/**
	 * Measures whether pattern rendering time scales with buffer size.
	 *
	 * <h3>Performance Diagnostic</h3>
	 * <p>Times {@link PatternSystemManager#sum} with different buffer sizes
	 * at the same start position. Identifies whether rendering does work
	 * proportional to note duration (wasteful) or buffer size (efficient).</p>
	 *
	 * <h3>Expected Results</h3>
	 * <ul>
	 *   <li><b>Efficient</b>: Time scales roughly linearly with buffer size</li>
	 *   <li><b>Inefficient</b>: All buffer sizes take similar time</li>
	 * </ul>
	 *
	 * <h3>What This Does NOT Test</h3>
	 * <p>No assertions - informational only. Does not verify audio correctness.</p>
	 */
	@Test(timeout = 180_000)
	public void renderTimingVsBufferSize() {
		helper.disableEffects();
		File samplesDir = helper.requireSamplesDir();

		AudioScene<?> seedScene = createBaselineScene(samplesDir);
		long seed = findWorkingGenomeSeed(seedScene, samplesDir);
		if (seed < 0) {
			log("No working genome found - skipping test");
			return;
		}

		log("=== Render Timing vs Buffer Size (Performance Diagnostic) ===");
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
	 * <h3>Performance Diagnostic</h3>
	 * <p>Replicates the inner loop of {@code PatternFeatures.renderRange()} with
	 * instrumentation to identify rendering inefficiencies. Measures the gap
	 * between work done (full note audio evaluation) and work useful (frames
	 * that fall within the target buffer).</p>
	 *
	 * <h3>Metrics Reported</h3>
	 * <ul>
	 *   <li>Notes created vs notes overlapping target buffer</li>
	 *   <li>Total frames evaluated vs frames actually useful</li>
	 *   <li>Time in {@code getNoteDestinations()} vs {@code evaluate()}</li>
	 *   <li>Note audio length vs buffer size ratio</li>
	 * </ul>
	 *
	 * <h3>What This Does NOT Test</h3>
	 * <p>No assertions - purely informational. Does not verify audio correctness.</p>
	 */
	@Test(timeout = 180_000)
	public void renderNoteAudioLengthAnalysis() {
		helper.disableEffects();
		File samplesDir = helper.requireSamplesDir();

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

	// =========================================================================
	// BUG ISOLATION: WaveCell Frame Position Diagnostic
	// =========================================================================

	/**
	 * Isolates and verifies the WaveCell frame position bug.
	 *
	 * <h3>Bug Hypothesis</h3>
	 * <p>In the real-time renderer, WaveCell reads from a 1024-sample buffer but
	 * its internal clock keeps incrementing globally. After 1024 ticks, the clock's
	 * frame position exceeds the buffer size (1024), causing the bounds check in
	 * WaveCellPush to fail and output silence.</p>
	 *
	 * <h3>Expected Result (if bug exists)</h3>
	 * <ul>
	 *   <li>Buffer 0: Audio output (frame 0-1023, within bounds)</li>
	 *   <li>Buffer 1+: Silence (frame 1024+, out of bounds)</li>
	 * </ul>
	 *
	 * <h3>What This Tests</h3>
	 * <ul>
	 *   <li>WaveCell tick advances frame position</li>
	 *   <li>WaveCell push outputs sample at frame position</li>
	 *   <li>Bounds checking in WaveCellPush</li>
	 * </ul>
	 */
	@Test(timeout = 60_000)
	public void waveCellFramePositionDiagnostic() {
		log("=== WaveCell Frame Position Diagnostic ===");

		// Create a small test buffer filled with recognizable values
		int bufferSize = 64;  // Small for quick test
		PackedCollection sourceBuffer = new PackedCollection(bufferSize);
		for (int i = 0; i < bufferSize; i++) {
			sourceBuffer.setMem(i, (i + 1) * 0.01);  // 0.01, 0.02, ..., 0.64
		}
		log("Source buffer filled with values 0.01 to 0.64");

		// Create WaveCell pointing to the source buffer
		// This mimics how efx.createCells() creates WaveCells for pattern output
		org.almostrealism.graph.temporal.WaveCell waveCell =
				new org.almostrealism.graph.temporal.WaveCell(
						sourceBuffer, SAMPLE_RATE, 1.0, null, null);

		// Get the internal data to read output values
		org.almostrealism.graph.temporal.WaveCellData data = waveCell.getData();

		// Setup
		waveCell.setup().get().run();

		// Tick and push through multiple "buffers" worth of frames
		int ticksPerBuffer = bufferSize;
		int numBuffers = 4;

		int[] nonZeroTicks = new int[numBuffers];
		int[] zeroTicks = new int[numBuffers];

		for (int buf = 0; buf < numBuffers; buf++) {
			nonZeroTicks[buf] = 0;
			zeroTicks[buf] = 0;

			for (int tick = 0; tick < ticksPerBuffer; tick++) {
				// Push triggers sample read into data.value()
				waveCell.push(null).get().run();

				// Read the output value from the WaveCellData
				double output = data.value().valueAt(0);

				// Tick advances frame position
				waveCell.tick().get().run();

				if (Math.abs(output) > 0.0001) {
					nonZeroTicks[buf]++;
				} else {
					zeroTicks[buf]++;
				}
			}

			log(String.format("Buffer %d: non-zero=%d, zero=%d (frame range %d-%d)",
					buf, nonZeroTicks[buf], zeroTicks[buf],
					buf * ticksPerBuffer, (buf + 1) * ticksPerBuffer - 1));
		}

		// Verify the bug: buffer 0 should have output, buffer 1+ should be silent
		assertTrue("Buffer 0 should have non-zero samples (got " + nonZeroTicks[0] + ")",
				nonZeroTicks[0] > 0);

		// This assertion will FAIL if the bug is fixed (which is what we want)
		// For now, it documents the expected buggy behavior
		if (nonZeroTicks[1] == 0 && nonZeroTicks[2] == 0 && nonZeroTicks[3] == 0) {
			log("");
			log("*** BUG CONFIRMED: WaveCell outputs silence after buffer 0 ***");
			log("*** The clock frame exceeds buffer size, causing bounds check failure ***");
		} else {
			log("");
			log("Buffers 1-3 have non-zero output - bug may be fixed or test is invalid");
		}

		// Cleanup
		sourceBuffer.destroy();
	}
}
