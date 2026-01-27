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
import org.almostrealism.audio.arrange.MixdownManager;
import org.almostrealism.audio.health.AudioHealthScore;
import org.almostrealism.audio.health.StableDurationHealthComputation;
import org.almostrealism.audio.notes.FileNoteSource;
import org.almostrealism.audio.notes.NoteAudioChoice;
import org.almostrealism.audio.pattern.PatternElement;
import org.almostrealism.audio.pattern.PatternLayerManager;
import org.almostrealism.audio.pattern.PatternSystemManager;
import org.almostrealism.heredity.TemporalCellular;
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
	 * <p>This is the foundational baseline test. If this fails, there is no
	 * point testing real-time rendering.</p>
	 */
	@Test
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

		long workingSeed = findWorkingGenome(samplesDir);
		assertTrue("At least one genome out of " + MAX_GENOME_ATTEMPTS +
				" should produce audio", workingSeed >= 0);
	}

	/**
	 * Verifies that {@link StableDurationHealthComputation} can evaluate
	 * the baseline scene, confirming compatibility with the optimizer path.
	 */
	@Test
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

		AudioScene<?> scene = createBaselineScene(samplesDir);
		long seed = findWorkingGenomeSeed(scene, samplesDir);
		if (seed < 0) {
			log("No working genome found - skipping health computation test");
			return;
		}

		// Re-create scene with known working seed
		scene = createBaselineScene(samplesDir);
		applyGenome(scene, seed);

		int channelCount = AudioScene.DEFAULT_SOURCE_COUNT;

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

	/**
	 * Searches for a genome that produces non-silent audio output using
	 * {@link StableDurationHealthComputation}, exactly mirroring the
	 * rendering path used by {@link org.almostrealism.audio.optimize.AudioSceneOptimizer}.
	 *
	 * @param samplesDir directory containing sample WAV files
	 * @return the seed offset that produced audio, or -1 if none found
	 */
	private long findWorkingGenome(File samplesDir) {
		for (int attempt = 0; attempt < MAX_GENOME_ATTEMPTS; attempt++) {
			AudioScene<?> scene = createBaselineScene(samplesDir);
			long seed = 42 + attempt;
			applyGenome(scene, seed);

			int totalElements = countElements(scene);
			log("Seed " + seed + ": " + totalElements + " total pattern elements");

			if (totalElements > 0) {
				String outputFile = "results/baseline-seed-" + seed + ".wav";
				int channelCount = AudioScene.DEFAULT_SOURCE_COUNT;

				StableDurationHealthComputation health =
						new StableDurationHealthComputation(channelCount);
				health.setMaxDuration((long) RENDER_SECONDS);
				health.setOutputFile(outputFile);

				TemporalCellular runner = scene.runner(health.getOutput());
				health.setTarget(runner);

				AudioHealthScore score = health.computeHealth();
				log("Seed " + seed + ": health score=" + score.getScore() +
						", frames=" + score.getFrames());

				int expectedFrames = (int) (RENDER_SECONDS * SAMPLE_RATE);
				if (score.getScore() > 0 && score.getFrames() >= expectedFrames) {
					log("SUCCESS: Seed " + seed + " produces audio with " +
							totalElements + " elements, " + score.getFrames() + " frames");
					health.destroy();
					return seed;
				} else {
					log("Seed " + seed + " has " + totalElements +
							" elements but health score indicates silence or clipping");
				}

				health.destroy();
			}
		}

		return -1;
	}
}
