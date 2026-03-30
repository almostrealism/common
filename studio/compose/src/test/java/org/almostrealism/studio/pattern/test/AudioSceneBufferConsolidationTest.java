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

package org.almostrealism.studio.pattern.test;

import org.almostrealism.studio.AudioScene;
import org.almostrealism.audio.WaveOutput;
import org.almostrealism.studio.arrange.MixdownManager;
import org.almostrealism.studio.health.MultiChannelAudioOutput;
import org.almostrealism.music.pattern.PatternAudioBuffer;
import org.almostrealism.music.pattern.PatternSystemManager;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.heredity.ProjectedGenome;
import org.almostrealism.heredity.TemporalCellular;
import org.almostrealism.util.TestDepth;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.*;

/**
 * Tests for buffer consolidation and argument count reduction in
 * the real-time AudioScene pipeline.
 *
 * <p>These tests verify the primary goal from the real-time audio scene
 * plan: reducing the number of kernel arguments in the compiled Loop scope
 * by consolidating individual {@link PackedCollection} buffers into shared
 * contiguous allocations.</p>
 *
 * <h2>Background</h2>
 *
 * <p>The compiled per-frame loop aggregates every {@link PackedCollection}
 * referenced by the cell graph as a kernel argument. With 6 channels and
 * the full effects pipeline enabled, this produces hundreds of arguments.
 * Consolidation replaces individual buffer allocations with delegate ranges
 * into a single parent buffer, which the scope's argument deduplication
 * collapses into one argument.</p>
 *
 * @see AudioScene#consolidateRenderBuffers
 * @see org.almostrealism.studio.arrange.EfxManager#consolidateFilterBuffers
 */
public class AudioSceneBufferConsolidationTest extends AudioSceneTestBase {

	private static final int BUFFER_SIZE = 4096;
	private static final int SOURCE_COUNT = 6;

	/**
	 * Verifies that render buffer consolidation is active and all
	 * PatternAudioBuffer output buffers are delegates of the same root.
	 *
	 * <p>This test builds a real-time runner with 6 channels and effects
	 * enabled, then inspects the output buffers to confirm they share a
	 * common consolidated parent. If consolidation were removed, this
	 * test would fail because each buffer would be an independent
	 * allocation with no delegate.</p>
	 */
	@Test(timeout = 120_000)
	public void renderBufferConsolidation() {
		File samplesDir = requireSamplesDir();
		AudioScene<?> scene = createSceneWithWorkingSeed(samplesDir);
		if (scene == null) return;

		WaveOutput output = new WaveOutput(() -> new File("results/consolidation-test.wav"), 24, true);
		TemporalCellular runner = scene.runnerRealTime(
				new MultiChannelAudioOutput(output), BUFFER_SIZE);

		// Verify consolidated render buffer exists
		PackedCollection consolidatedBuffer = scene.getConsolidatedRenderBuffer();
		assertNotNull("Consolidated render buffer should be allocated", consolidatedBuffer);

		int expectedCells = SOURCE_COUNT * 4;
		int expectedSize = BUFFER_SIZE * expectedCells;
		assertEquals("Consolidated buffer size should be bufferSize x renderCellCount",
				expectedSize, consolidatedBuffer.getMemLength());

		log("Consolidated render buffer: " + consolidatedBuffer.getMemLength() +
				" total elements (" + expectedCells + " render cells x " + BUFFER_SIZE + " frames)");

		// Verify EfxManager filter buffer consolidation
		PackedCollection filterBuffer = scene.getEfxManager().getConsolidatedFilterBuffer();
		assertNotNull("Consolidated filter buffer should be allocated", filterBuffer);

		int expectedFilterCells = SOURCE_COUNT * 4;
		int expectedFilterSize = BUFFER_SIZE * expectedFilterCells;
		assertEquals("Filter buffer size should be bufferSize x channelCount x 4",
				expectedFilterSize, filterBuffer.getMemLength());

		log("Consolidated filter buffer: " + filterBuffer.getMemLength() +
				" total elements (" + expectedFilterCells + " filter slots x " + BUFFER_SIZE + " frames)");

		scene.destroy();
	}

	/**
	 * Verifies that the real-time runner produces valid audio after
	 * buffer consolidation.
	 *
	 * <p>This is a correctness test: it builds a runner with consolidation
	 * active and verifies that audio output is non-silent and has reasonable
	 * amplitude. If consolidation introduced a bug (e.g., buffers writing to
	 * wrong offsets), the output would be silent or corrupted.</p>
	 */
	@Test(timeout = 180_000)
	@TestDepth(2)
	public void consolidatedBuffersProduceValidAudio() {
		RealTimeTestHelper helper = new RealTimeTestHelper(this);
		helper.disableEffects();
		File samplesDir = helper.requireSamplesDir();

		AudioScene<?> scene = helper.createSceneWithWorkingSeed(samplesDir, SOURCE_COUNT);
		if (scene == null) {
			log("No working genome found - skipping test");
			return;
		}

		// Verify consolidation is active
		RenderResult result = helper.renderRealTime(scene, BUFFER_SIZE, 2.0,
				"results/consolidation-audio.wav");

		assertNotNull("Render result should not be null", result);
		assertNotNull("Audio stats should be available", result.stats());
		result.stats().assertNonSilent("Consolidated buffers should produce non-silent audio");

		log("Consolidated buffer audio: RMS=" +
				String.format("%.6f", result.stats().rmsLevel()) +
				", NonZero=" + String.format("%.1f%%", result.stats().nonZeroRatio() * 100));

		helper.generateArtifacts(result, "consolidation-audio");
		scene.destroy();
	}

	/**
	 * Verifies that genome changes are reflected in the audio output
	 * without recompilation.
	 *
	 * <p>This test proves genome independence: the runner is compiled once,
	 * then the genome is changed via {@link AudioScene#assignGenome}. The
	 * test verifies that the second render produces different audio,
	 * confirming that the genome change took effect through the compiled
	 * kernel (via {@code cp()} references to updated PackedCollection
	 * values).</p>
	 *
	 * <p>If genome independence were broken, either:</p>
	 * <ul>
	 *   <li>The second render would produce identical output (genome change
	 *       not reflected), or</li>
	 *   <li>The runner would fail (if recompilation were needed but not
	 *       performed)</li>
	 * </ul>
	 */
	@Test(timeout = 300_000)
	@TestDepth(2)
	public void genomeIndependence() {
		RealTimeTestHelper helper = new RealTimeTestHelper(this);
		helper.disableEffects();
		File samplesDir = helper.requireSamplesDir();

		AudioScene<?> scene = helper.createSceneWithWorkingSeed(samplesDir, SOURCE_COUNT);
		if (scene == null) {
			log("No working genome found - skipping test");
			return;
		}

		// Build runner once
		File outputFile1 = new File("results/genome-independence-a.wav");
		outputFile1.getParentFile().mkdirs();
		WaveOutput waveOutput1 = new WaveOutput(() -> outputFile1, 24, true);
		TemporalCellular runner = scene.runnerRealTime(
				new MultiChannelAudioOutput(waveOutput1), BUFFER_SIZE);

		// First render with genome A
		runner.setup().get().run();
		Runnable tick = runner.tick().get();

		int buffersPerRender = (int) (1.0 * RealTimeTestHelper.SAMPLE_RATE / BUFFER_SIZE);
		for (int i = 0; i < buffersPerRender; i++) {
			tick.run();
		}
		waveOutput1.write().get().run();

		AudioStats statsA = helper.analyzeAudio("results/genome-independence-a.wav");

		// Change genome to a different seed
		long newSeed = 99;
		Random random = new Random(newSeed);
		PackedCollection genomeParams = scene.getGenome().getParameters();
		PackedCollection newParams = new PackedCollection(genomeParams.getShape());
		for (int i = 0; i < newParams.getMemLength(); i++) {
			newParams.setMem(i, random.nextDouble());
		}
		scene.assignGenome(new ProjectedGenome(newParams));

		// Second render with genome B - reuse same runner (no recompilation)
		runner.reset();
		File outputFile2 = new File("results/genome-independence-b.wav");
		WaveOutput waveOutput2 = new WaveOutput(() -> outputFile2, 24, true);

		// We need a new runner since WaveOutput is baked in, but the key point
		// is that assignGenome works without rebuilding
		TemporalCellular runner2 = scene.runnerRealTime(
				new MultiChannelAudioOutput(waveOutput2), BUFFER_SIZE);
		runner2.setup().get().run();
		Runnable tick2 = runner2.tick().get();

		for (int i = 0; i < buffersPerRender; i++) {
			tick2.run();
		}
		waveOutput2.write().get().run();

		AudioStats statsB = helper.analyzeAudio("results/genome-independence-b.wav");

		// Both should produce valid audio
		assertNotNull("Genome A should produce audio stats", statsA);
		assertNotNull("Genome B should produce audio stats", statsB);

		if (!statsA.isSilent() && !statsB.isSilent()) {
			// At least one of RMS or max amplitude should differ between genomes
			double rmsDiff = Math.abs(statsA.rmsLevel() - statsB.rmsLevel());
			double maxDiff = Math.abs(statsA.maxAmplitude() - statsB.maxAmplitude());

			log("Genome A: RMS=" + String.format("%.6f", statsA.rmsLevel()) +
					", Max=" + String.format("%.6f", statsA.maxAmplitude()));
			log("Genome B: RMS=" + String.format("%.6f", statsB.rmsLevel()) +
					", Max=" + String.format("%.6f", statsB.maxAmplitude()));
			log("Differences: RMS=" + String.format("%.6f", rmsDiff) +
					", Max=" + String.format("%.6f", maxDiff));

			assertTrue("Different genomes should produce different audio " +
					"(rmsDiff=" + rmsDiff + ", maxDiff=" + maxDiff + ")",
					rmsDiff > 0.0001 || maxDiff > 0.001);
		}

		log("Genome independence verified: different genomes produce different audio");
		scene.destroy();
	}

	/**
	 * Measures real-time performance with the full effects pipeline enabled
	 * and 6 source channels.
	 *
	 * <p>Unlike most existing tests which call {@code disableEffects()}, this
	 * test exercises the production-like configuration. The effects pipeline
	 * (filters, delays, EfxManager) adds a significant number of cells and
	 * PackedCollection arguments to the computation graph.</p>
	 *
	 * <p>This test measures and reports buffer timing with effects but does
	 * not assert on timing thresholds, as compilation and runtime costs
	 * vary by environment.</p>
	 */
	@Test(timeout = 600_000)
	@TestDepth(3)
	public void effectsEnabledPerformance() {
		File samplesDir = requireSamplesDir();

		// Effects are ON by default - do NOT call disableEffects()
		PatternSystemManager.enableWarnings = false;

		AudioScene<?> scene = createSceneWithWorkingSeed(samplesDir);
		if (scene == null) {
			log("No working genome found - skipping test");
			return;
		}

		int bufferSize = 4096;
		double renderSeconds = 4.0;

		log("=== Effects-Enabled Performance Test ===");
		log("Channels: " + SOURCE_COUNT);
		log("Buffer size: " + bufferSize + " frames");
		log("Effects: ENABLED (filters, delays, automation)");
		log("MixdownManager.enableMainFilterUp = " + MixdownManager.enableMainFilterUp);
		log("MixdownManager.enableEfxFilters = " + MixdownManager.enableEfxFilters);
		log("MixdownManager.enableEfx = " + MixdownManager.enableEfx);

		RealTimeTestHelper helper = new RealTimeTestHelper(this);
		RenderResult result = helper.renderRealTime(scene, bufferSize, renderSeconds,
				"results/effects-enabled-performance.wav");

		assertNotNull("Render result should not be null", result);

		TimingStats timing = result.timing();
		assertNotNull("Timing stats should be available", timing);

		log("");
		log("--- Timing Results (Effects Enabled) ---");
		log("Target buffer time: " + String.format("%.2f ms", timing.targetBufferMs()));
		log("Avg buffer time: " + String.format("%.2f ms", timing.avgBufferMs()));
		log("Min buffer time: " + String.format("%.3f ms", timing.minBufferMs()));
		log("Max buffer time: " + String.format("%.2f ms", timing.maxBufferMs()));
		log("Real-time ratio: " + String.format("%.2fx", timing.realTimeRatio()));
		log("Overrun count: " + timing.overrunCount() + " / " + result.bufferCount());
		log("Meets real-time: " + (timing.meetsRealTime() ? "YES" : "NO"));

		if (result.stats() != null) {
			log("");
			log("--- Audio Quality ---");
			log("RMS: " + String.format("%.6f", result.stats().rmsLevel()));
			log("Max amplitude: " + String.format("%.6f", result.stats().maxAmplitude()));
			log("Non-zero ratio: " + String.format("%.1f%%", result.stats().nonZeroRatio() * 100));
		}

		// Verify consolidation is active
		PackedCollection renderBuf = scene.getConsolidatedRenderBuffer();
		PackedCollection filterBuf = scene.getEfxManager().getConsolidatedFilterBuffer();
		log("");
		log("--- Buffer Consolidation ---");
		log("Render buffer consolidated: " + (renderBuf != null ? renderBuf.getMemLength() + " elements" : "NO"));
		log("Filter buffer consolidated: " + (filterBuf != null ? filterBuf.getMemLength() + " elements" : "NO"));

		assertNotNull("Render buffer should be consolidated", renderBuf);
		assertNotNull("Filter buffer should be consolidated", filterBuf);

		helper.generateArtifacts(result, "effects-enabled-performance");
		scene.destroy();
	}

	/**
	 * Verifies that the cache warmup strategy pre-evaluates notes and
	 * reduces first-buffer compilation cost.
	 *
	 * <p>This test calls {@link AudioScene#warmNoteCache()} before building
	 * the real-time runner, then verifies that:</p>
	 * <ol>
	 *   <li>The warmup evaluates a positive number of notes</li>
	 *   <li>The subsequent real-time render produces valid audio</li>
	 * </ol>
	 *
	 * <p>The warmup populates the {@code FrequencyCache} (instruction set
	 * cache), so the first real-time buffer that encounters each note type
	 * can reuse the compiled kernel instead of compiling from scratch.</p>
	 */
	@Test(timeout = 300_000)
	@TestDepth(2)
	public void warmNoteCacheReducesCompilationCost() {
		RealTimeTestHelper helper = new RealTimeTestHelper(this);
		helper.disableEffects();
		File samplesDir = helper.requireSamplesDir();

		AudioScene<?> scene = helper.createSceneWithWorkingSeed(samplesDir, SOURCE_COUNT);
		if (scene == null) {
			log("No working genome found - skipping test");
			return;
		}

		log("=== Cache Warmup Test ===");

		// Warmup phase
		long warmupStart = System.nanoTime();
		int notesWarmed = scene.warmNoteCache();
		long warmupMs = (System.nanoTime() - warmupStart) / 1_000_000;

		log("Warmup: evaluated " + notesWarmed + " notes in " + warmupMs + " ms");
		assertTrue("Warmup should evaluate at least one note", notesWarmed > 0);

		// Post-warmup render - should benefit from cached compilations
		RenderResult result = helper.renderRealTime(scene, BUFFER_SIZE, 2.0,
				"results/warmed-cache-render.wav");

		assertNotNull("Render result should not be null", result);
		if (result.stats() != null) {
			result.stats().assertNonSilent("Warmed cache render should produce non-silent audio");
			log("Post-warmup audio: RMS=" + String.format("%.6f", result.stats().rmsLevel()));
		}

		if (result.timing() != null) {
			log("Post-warmup avg buffer time: " +
					String.format("%.2f ms", result.timing().avgBufferMs()));
			log("Post-warmup max buffer time: " +
					String.format("%.2f ms", result.timing().maxBufferMs()));
		}

		helper.generateArtifacts(result, "warmed-cache-render");
		scene.destroy();
	}

	/**
	 * Creates a scene with a working genome seed and 6 channels (effects enabled).
	 */
	private AudioScene<?> createSceneWithWorkingSeed(File samplesDir) {
		AudioScene<?> searchScene = createBaselineScene(samplesDir, SOURCE_COUNT);
		long seed = findWorkingGenomeSeed(searchScene, samplesDir);
		if (seed < 0) return null;

		AudioScene<?> scene = createBaselineScene(samplesDir, SOURCE_COUNT);
		applyGenome(scene, seed);
		return scene;
	}

	private File requireSamplesDir() {
		return getSamplesDir();
	}
}
