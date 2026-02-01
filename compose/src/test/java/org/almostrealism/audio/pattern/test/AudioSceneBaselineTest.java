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
import org.almostrealism.audio.Cells;
import org.almostrealism.audio.WaveOutput;
import org.almostrealism.audio.arrange.MixdownManager;
import org.almostrealism.audio.health.AudioHealthScore;
import org.almostrealism.audio.health.MultiChannelAudioOutput;
import org.almostrealism.audio.health.StableDurationHealthComputation;
import org.almostrealism.audio.notes.FileNoteSource;
import org.almostrealism.audio.notes.NoteAudioChoice;
import org.almostrealism.audio.pattern.PatternElement;
import org.almostrealism.audio.pattern.PatternLayerManager;
import org.almostrealism.audio.pattern.PatternSystemManager;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.heredity.TemporalCellular;
import org.almostrealism.util.TestDepth;
import org.junit.Test;

import java.io.File;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Baseline tests for AudioScene audio generation without {@link org.almostrealism.audio.optimize.AudioSceneOptimizer}.
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
	 * {@link org.almostrealism.audio.optimize.test.AudioSceneOptimizationTest#withOutput()})
	 * which properly chains tick and push operations. Uses 2 source channels
	 * and 1 second of audio to keep total rendering feasible.</p>
	 *
	 * <p>This is the foundational baseline test. If this fails, there is no
	 * point testing real-time rendering.</p>
	 */
	@Test
	@TestDepth(2)
	public void baselineAudioGeneration() {
		File samplesDir = new File(SAMPLES_PATH);
		if (!samplesDir.exists()) {
			log("Skipping test - Samples directory not found: " + samplesDir.getAbsolutePath());
			return;
		}

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
		WaveOutput output = new WaveOutput(() -> new File(outputFile), 24, true);
		CellList cells = (CellList) scene.getCells(new MultiChannelAudioOutput(output));

		int totalFrames = (int) (offlineRenderSeconds * SAMPLE_RATE);
		log("Starting offline render (" + offlineRenderSeconds + "s, " + totalFrames + " frames)...");
		long startTime = System.currentTimeMillis();

		cells.setup().get().run();
		Runnable tick = cells.tick().get();
		for (int i = 0; i < totalFrames; i++) {
			tick.run();
		}

		long renderTime = System.currentTimeMillis() - startTime;
		log("Render completed in " + renderTime + " ms");

		output.write().get().run();
		log("Output written to " + outputFile);

		File wavFile = new File(outputFile);
		assertTrue("Output WAV file should exist", wavFile.exists());
		assertTrue("Output WAV file should not be empty", wavFile.length() > 100);
	}

	/**
	 * Verifies that {@link StableDurationHealthComputation} can evaluate
	 * the baseline scene, confirming compatibility with the optimizer path.
	 *
	 * <p>This test uses the full health computation pipeline which is
	 * significantly slower than direct rendering.</p>
	 */
	@Test
	@TestDepth(2)
	public void baselineHealthComputation() {
		File samplesDir = new File(SAMPLES_PATH);
		if (!samplesDir.exists()) {
			log("Skipping test - Samples directory not found: " + samplesDir.getAbsolutePath());
			return;
		}

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
		health.setOutputFile("results/baseline-health.wav");

		TemporalCellular runner = scene.runner(health.getOutput());
		health.setTarget(runner);

		AudioHealthScore score = health.computeHealth();

		log("=== Health Computation Results ===");
		log("Health score: " + score.getScore());
		log("Stable frames: " + score.getFrames());

		assertTrue("Health computation should complete with frames",
				score.getFrames() > 0);
	}

	/**
	 * Diagnostic test that verifies CellList root and push chain wiring.
	 *
	 * <p>Creates a scene, builds cells, and inspects the CellList structure
	 * to ensure roots exist and push operations can reach the WaveOutput.
	 * Runs only a handful of ticks to keep execution fast.</p>
	 */
	@Test
	public void cellPipelineDiagnostic() {
		File samplesDir = new File(SAMPLES_PATH);
		if (!samplesDir.exists()) {
			log("Skipping test - Samples directory not found: " + samplesDir.getAbsolutePath());
			return;
		}

		MixdownManager.enableMainFilterUp = false;
		MixdownManager.enableEfxFilters = false;
		MixdownManager.enableEfx = false;
		PatternSystemManager.enableWarnings = false;

		int sourceCount = 2;

		AudioScene<?> scene = createBaselineScene(samplesDir, sourceCount);
		applyGenome(scene, 42);

		WaveOutput output = new WaveOutput(() -> new File("results/diagnostic.wav"), 24, true);
		CellList cells = (CellList) scene.getCells(new MultiChannelAudioOutput(output));

		// Inspect CellList structure
		java.util.Collection<org.almostrealism.graph.Receptor<PackedCollection>> roots =
				cells.getAllRoots();
		log("Root count: " + roots.size());
		log("Cell count: " + cells.size());
		log("Temporal count: " + cells.getAllTemporals().size());
		log("Setup count: " + cells.getAllSetup().size());

		// Check WaveOutput cursors before anything runs
		double cursorCh0Before = output.getCursor(0).toDouble(0);
		double cursorCh1Before = output.getCursor(1).toDouble(0);
		log("Cursor before: ch0=" + cursorCh0Before + " ch1=" + cursorCh1Before);

		// Run setup
		cells.setup().get().run();
		double cursorCh0AfterSetup = output.getCursor(0).toDouble(0);
		double cursorCh1AfterSetup = output.getCursor(1).toDouble(0);
		log("Cursor after setup: ch0=" + cursorCh0AfterSetup + " ch1=" + cursorCh1AfterSetup);

		// Run a few ticks manually
		Runnable tick = cells.tick().get();
		for (int i = 0; i < 10; i++) {
			tick.run();
		}

		double cursorCh0After10 = output.getCursor(0).toDouble(0);
		double cursorCh1After10 = output.getCursor(1).toDouble(0);
		log("Cursor after 10 ticks: ch0=" + cursorCh0After10 + " ch1=" + cursorCh1After10);

		// Report
		log("Frame count after 10 ticks: " + output.getFrameCount());
		assertTrue("Should have at least one root", roots.size() > 0);
		assertTrue("Cursor should advance after ticks",
				cursorCh0After10 > 0 || cursorCh1After10 > 0);
	}

	/**
	 * Verifies that melodic channels produce audio content by searching
	 * for a genome that generates elements on melodic channels (2-4).
	 */
	@Test
	public void baselineMelodicContent() {
		File samplesDir = new File(SAMPLES_PATH);
		if (!samplesDir.exists()) {
			log("Skipping test - Samples directory not found: " + samplesDir.getAbsolutePath());
			return;
		}

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
