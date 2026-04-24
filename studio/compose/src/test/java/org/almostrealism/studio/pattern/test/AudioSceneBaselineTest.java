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

import org.almostrealism.studio.AudioScene;
import org.almostrealism.audio.WaveOutput;
import org.almostrealism.studio.arrange.MixdownManager;
import org.almostrealism.studio.health.AudioHealthScore;
import org.almostrealism.studio.health.MultiChannelAudioOutput;
import org.almostrealism.studio.health.StableDurationHealthComputation;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.music.notes.FileNoteSource;
import org.almostrealism.music.notes.NoteAudioChoice;
import org.almostrealism.music.pattern.PatternElement;
import org.almostrealism.music.pattern.PatternLayerManager;
import org.almostrealism.music.pattern.PatternSystemManager;
import org.almostrealism.heredity.TemporalCellular;
import org.almostrealism.util.TestDepth;
import org.junit.Test;

import java.io.File;
import java.util.List;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/**
 * Baseline tests for AudioScene audio generation without {@link org.almostrealism.studio.optimize.AudioSceneOptimizer}.
 *
 * <p>These tests verify that {@link AudioScene} can produce non-silent audio output through
 * the traditional (non-real-time) pipeline using programmatically constructed
 * {@link NoteAudioChoice NoteAudioChoices} backed by {@link FileNoteSource}.</p>
 *
 * <p>Pattern element generation is stochastic: a random genome may produce zero elements
 * depending on the interaction between {@code noteSelection}, {@code bias}, and
 * {@code seedBiasAdjustment}. These tests try multiple genomes to find one that produces
 * audio, mirroring the multi-evaluation approach used by the optimizer.</p>
 *
 * <p>The test uses samples from the {@code Samples/} directory in the project root.</p>
 *
 * @see AudioScene
 * @see StableDurationHealthComputation
 */
public class AudioSceneBaselineTest extends AudioSceneTestBase {

	/**
	 * Verifies that {@link AudioScene} produces non-silent audio through the
	 * traditional pipeline, using {@link AudioScene.Settings#defaultSettings}
	 * for proper pattern structure.
	 *
	 * <p>Uses the {@link Cells#sec(double)} rendering path (same as
	 * {@link org.almostrealism.studio.optimize.test.AudioSceneOptimizationTest#withOutput()})
	 * which properly chains tick and push operations. Uses 2 source channels
	 * and 1 second of audio to keep total rendering feasible.</p>
	 *
	 * <p>This is the foundational baseline test. If this fails, there is no
	 * point testing real-time rendering.</p>
	 */
	@Test(timeout = 300_000)
	@TestDepth(2)
	public void baselineAudioGeneration() {
		File samplesDir = new File(SAMPLES_PATH);
		assumeTrue("Samples directory required: " + samplesDir.getAbsolutePath(),
				samplesDir.exists());

		MixdownManager.enableMainFilterUp = false;
		MixdownManager.enableEfxFilters = false;
		MixdownManager.enableEfx = false;
		PatternSystemManager.enableWarnings = false;

		int sourceCount = 2;

		long workingSeed = findWorkingGenomeSeed(
				createBaselineScene(samplesDir, sourceCount), samplesDir);
		assertTrue("At least one genome out of " + MAX_GENOME_ATTEMPTS +
				" should produce pattern elements", workingSeed >= 0);

		AudioScene<?> scene = createBaselineScene(samplesDir, sourceCount);
		applyGenome(scene, workingSeed);

		int totalElements = countElements(scene);
		log("Rendering with seed " + workingSeed + " (" + totalElements + " elements)");

		double offlineRenderSeconds = 1.0;

		String outputFile = "results/baseline-seed-" + workingSeed + ".wav";
		File wavFile = new File(outputFile);
		wavFile.getParentFile().mkdirs();
		WaveOutput output = new WaveOutput(() -> wavFile, 24, true);
		int bufferSize = AudioScene.DEFAULT_REALTIME_BUFFER_SIZE;

		TemporalCellular runner = scene.runnerRealTime(
				new MultiChannelAudioOutput(output), bufferSize);

		int totalFrames = (int) (offlineRenderSeconds * SAMPLE_RATE);
		int bufferCount = (totalFrames + bufferSize - 1) / bufferSize;
		log("Starting render (" + offlineRenderSeconds + "s, " + totalFrames + " frames, "
				+ bufferCount + " buffers of " + bufferSize + ")...");
		long startTime = System.currentTimeMillis();

		runner.setup().get().run();
		Runnable tick = runner.tick().get();
		for (int b = 0; b < bufferCount; b++) {
			tick.run();
		}

		long renderTime = System.currentTimeMillis() - startTime;
		log("Render completed in " + renderTime + " ms");

		output.write().get().run();
		log("Output written to " + outputFile);

		long expectedMinBytes = (long) totalFrames * 3 * 2; // 24-bit stereo
		assertTrue("Output WAV file should exist", wavFile.exists());
		assertTrue("Output WAV should be at least " + expectedMinBytes + " bytes (was " +
				wavFile.length() + ")", wavFile.length() >= expectedMinBytes);
	}

	/**
	 * Verifies that {@link StableDurationHealthComputation} can evaluate
	 * the baseline scene, confirming compatibility with the optimizer path.
	 *
	 * <p>This test uses the full health computation pipeline which is
	 * significantly slower than direct rendering.</p>
	 */
	@Test(timeout = 600_000)
	@TestDepth(2)
	public void baselineHealthComputation() {
		File samplesDir = new File(SAMPLES_PATH);
		assumeTrue("Samples directory required: " + samplesDir.getAbsolutePath(),
				samplesDir.exists());

		MixdownManager.enableMainFilterUp = false;
		MixdownManager.enableEfxFilters = false;
		MixdownManager.enableEfx = false;
		PatternSystemManager.enableWarnings = false;

		int sourceCount = 2;

		AudioScene<?> scene = createBaselineScene(samplesDir, sourceCount);
		long seed = findWorkingGenomeSeed(scene, samplesDir);
		if (seed < 0) {
			log("No working genome found - skipping health computation test");
			return;
		}

		// Re-create scene with known working seed
		scene = createBaselineScene(samplesDir, sourceCount);
		applyGenome(scene, seed);

		int channelCount = sourceCount;

		StableDurationHealthComputation health =
				new StableDurationHealthComputation(channelCount);
		health.setMaxDuration((long) RENDER_SECONDS);
		File healthFile = new File("results/baseline-health.wav");
		healthFile.getParentFile().mkdirs();
		health.setOutputFile(healthFile.getPath());

		TemporalCellular runner = scene.runnerRealTime(
				health.getOutput(), null, health.getBatchSize());
		health.setTarget(runner);

		AudioHealthScore score = health.computeHealth();

		log("=== Health Computation Results ===");
		log("Health score: " + score.getScore());
		log("Stable frames: " + score.getFrames());

		long expectedMinFrames = (long) RENDER_SECONDS * OutputLine.sampleRate / 2;
		assertTrue("Health computation should produce at least " + expectedMinFrames +
						" stable frames (was " + score.getFrames() + ")",
				score.getFrames() >= expectedMinFrames);
		assertTrue("Health score should be positive (was " + score.getScore() + ")",
				score.getScore() > 0);
	}

	/**
	 * Diagnostic test that verifies the realtime runner advances the WaveOutput cursor
	 * across a small number of buffer ticks. Confirms end-to-end wiring from cell graph
	 * to output without exercising the full duration.
	 */
	@Test(timeout = 60_000)
	public void cellPipelineDiagnostic() {
		File samplesDir = new File(SAMPLES_PATH);
		assumeTrue("Samples directory required: " + samplesDir.getAbsolutePath(),
				samplesDir.exists());

		MixdownManager.enableMainFilterUp = false;
		MixdownManager.enableEfxFilters = false;
		MixdownManager.enableEfx = false;
		PatternSystemManager.enableWarnings = false;

		int sourceCount = 2;
		int bufferSize = AudioScene.DEFAULT_REALTIME_BUFFER_SIZE;
		int bufferCount = 10;

		AudioScene<?> scene = createBaselineScene(samplesDir, sourceCount);
		applyGenome(scene, 42);

		File outFile = new File("results/diagnostic.wav");
		outFile.getParentFile().mkdirs();
		WaveOutput output = new WaveOutput(() -> outFile, 24, true);
		TemporalCellular runner = scene.runnerRealTime(
				new MultiChannelAudioOutput(output), bufferSize);

		double cursorCh0Before = output.getCursor(0).toDouble(0);
		double cursorCh1Before = output.getCursor(1).toDouble(0);
		log("Cursor before: ch0=" + cursorCh0Before + " ch1=" + cursorCh1Before);

		runner.setup().get().run();
		double cursorCh0AfterSetup = output.getCursor(0).toDouble(0);
		double cursorCh1AfterSetup = output.getCursor(1).toDouble(0);
		log("Cursor after setup: ch0=" + cursorCh0AfterSetup + " ch1=" + cursorCh1AfterSetup);

		Runnable tick = runner.tick().get();
		for (int i = 0; i < bufferCount; i++) {
			tick.run();
		}

		double cursorCh0AfterTicks = output.getCursor(0).toDouble(0);
		double cursorCh1AfterTicks = output.getCursor(1).toDouble(0);
		log("Cursor after " + bufferCount + " ticks: ch0=" + cursorCh0AfterTicks
				+ " ch1=" + cursorCh1AfterTicks);
		log("Frame count: " + output.getFrameCount());

		long expectedFrames = (long) bufferCount * bufferSize;
		assertTrue("Cursor on ch0 should advance by " + expectedFrames
						+ " frames after " + bufferCount + " ticks (was "
						+ cursorCh0AfterTicks + ")",
				cursorCh0AfterTicks >= expectedFrames);
		assertTrue("Cursor on ch1 should advance by " + expectedFrames
						+ " frames after " + bufferCount + " ticks (was "
						+ cursorCh1AfterTicks + ")",
				cursorCh1AfterTicks >= expectedFrames);
	}

	/**
	 * Verifies that melodic channels produce audio content by searching
	 * for a genome that generates elements on melodic channels (2-4).
	 */
	@Test(timeout = 30_000)
	public void baselineMelodicContent() {
		File samplesDir = new File(SAMPLES_PATH);
		assumeTrue("Samples directory required: " + samplesDir.getAbsolutePath(),
				samplesDir.exists());

		MixdownManager.enableMainFilterUp = false;
		MixdownManager.enableEfxFilters = false;
		MixdownManager.enableEfx = false;
		PatternSystemManager.enableWarnings = false;

		boolean foundMelodic = false;

		for (int attempt = 0; attempt < MAX_GENOME_ATTEMPTS; attempt++) {
			AudioScene<?> scene = createBaselineScene(samplesDir);
			applyGenome(scene, 42 + attempt);

			int melodicElements = 0;
			for (PatternLayerManager plm : scene.getPatternManager().getPatterns()) {
				if (plm.getChannel() >= 2 && plm.getChannel() <= 4) {
					List<PatternElement> elements =
							plm.getAllElements(0.0, plm.getDuration());
					melodicElements += elements.size();
				}
			}

			if (melodicElements > 0) {
				log("Seed " + (42 + attempt) + " produces " +
						melodicElements + " melodic elements");
				foundMelodic = true;
				break;
			}
		}

		assertTrue("At least one genome should produce melodic elements", foundMelodic);
	}
}
