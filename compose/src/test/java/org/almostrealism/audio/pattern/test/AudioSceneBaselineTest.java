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
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.arrange.MixdownManager;
import org.almostrealism.audio.health.AudioHealthScore;
import org.almostrealism.audio.health.StableDurationHealthComputation;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.notes.FileNoteSource;
import org.almostrealism.audio.notes.NoteAudioChoice;
import org.almostrealism.audio.pattern.PatternElement;
import org.almostrealism.audio.pattern.PatternLayerManager;
import org.almostrealism.audio.pattern.PatternSystemManager;
import org.almostrealism.audio.tone.DefaultKeyboardTuning;
import org.almostrealism.audio.tone.WesternChromatic;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.heredity.TemporalCellular;
import org.almostrealism.util.TestSuiteBase;
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
public class AudioSceneBaselineTest extends TestSuiteBase implements CellFeatures {

	private static final String SAMPLES_PATH = "../../Samples";
	private static final int SAMPLE_RATE = OutputLine.sampleRate;
	private static final int MAX_GENOME_ATTEMPTS = 20;
	private static final double RENDER_SECONDS = 4.0;

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

	/**
	 * Searches for a working genome seed without rendering audio.
	 * Returns the seed that produces the most pattern elements.
	 *
	 * @param scene the AudioScene to test (will be modified by assignGenome)
	 * @param samplesDir directory containing sample WAV files
	 * @return the seed offset that produced the most elements, or -1 if none found
	 */
	private long findWorkingGenomeSeed(AudioScene<?> scene, File samplesDir) {
		long bestSeed = -1;
		int bestElements = 0;

		for (int attempt = 0; attempt < MAX_GENOME_ATTEMPTS; attempt++) {
			long seed = 42 + attempt;
			applyGenome(scene, seed);

			int totalElements = countElements(scene);
			if (totalElements > bestElements) {
				bestElements = totalElements;
				bestSeed = seed;
			}
		}

		if (bestSeed >= 0) {
			log("Best seed: " + bestSeed + " with " + bestElements + " elements");
		}

		return bestSeed;
	}

	/**
	 * Applies a genome to the scene using a deterministic random seed.
	 *
	 * <p>{@link org.almostrealism.heredity.ProjectedGenome#random()} uses
	 * {@link PackedCollection#randFill()} which is not seeded. To get
	 * deterministic behavior, we create a genome with values derived
	 * from a known seed.</p>
	 *
	 * @param scene the AudioScene
	 * @param seed the seed for generating deterministic genome parameters
	 */
	private void applyGenome(AudioScene<?> scene, long seed) {
		java.util.Random random = new java.util.Random(seed);

		// Get the genome shape from the scene and fill with seeded random values
		PackedCollection genomeParams = scene.getGenome().getParameters();
		PackedCollection seededParams = new PackedCollection(genomeParams.getShape());
		for (int i = 0; i < seededParams.getMemLength(); i++) {
			seededParams.setMem(i, random.nextDouble());
		}

		scene.assignGenome(new org.almostrealism.heredity.ProjectedGenome(seededParams));
	}

	/**
	 * Counts total pattern elements across all patterns in the scene.
	 */
	private int countElements(AudioScene<?> scene) {
		int total = 0;
		for (PatternLayerManager plm : scene.getPatternManager().getPatterns()) {
			List<PatternElement> elements =
					plm.getAllElements(0.0, plm.getDuration());
			total += elements.size();
		}
		return total;
	}

	/**
	 * Creates a baseline AudioScene with programmatic NoteAudioChoices.
	 *
	 * <p>Mirrors the structure of
	 * {@link org.almostrealism.audio.optimize.AudioSceneOptimizer#createScene()}
	 * but uses {@link FileNoteSource} instead of TreeNoteSource for portability.</p>
	 *
	 * @param samplesDir directory containing sample WAV files
	 * @return configured AudioScene ready for genome assignment
	 */
	private AudioScene<?> createBaselineScene(File samplesDir) {
		int sourceCount = AudioScene.DEFAULT_SOURCE_COUNT;
		int delayLayers = AudioScene.DEFAULT_DELAY_LAYERS;

		AudioScene<?> scene = new AudioScene<>(120.0, sourceCount, delayLayers, SAMPLE_RATE);
		scene.setTuning(new DefaultKeyboardTuning());

		// Add NoteAudioChoices for each channel using FileNoteSource
		addChoices(scene, samplesDir);

		// Apply default settings (creates 36 patterns with proper structure)
		AudioScene.Settings settings = AudioScene.Settings.defaultSettings(
				sourceCount,
				AudioScene.DEFAULT_PATTERNS_PER_CHANNEL,
				AudioScene.DEFAULT_ACTIVE_PATTERNS,
				AudioScene.DEFAULT_LAYERS,
				AudioScene.DEFAULT_LAYER_SCALE,
				AudioScene.DEFAULT_DURATION);
		scene.setSettings(settings);

		// Increase activity bias to make more patterns produce elements
		scene.setPatternActivityBias(1.0);

		// Override to shorter duration for testing
		scene.setTotalMeasures(16);

		return scene;
	}

	/**
	 * Adds {@link NoteAudioChoice NoteAudioChoices} backed by {@link FileNoteSource}
	 * for all 6 channels.
	 *
	 * <p>Channel mapping follows the default convention:</p>
	 * <ul>
	 *   <li>Channels 0, 1: Non-melodic (percussion)</li>
	 *   <li>Channels 2, 3, 4: Melodic (pitched instruments)</li>
	 *   <li>Channel 5: Non-melodic (atmosphere/effects)</li>
	 * </ul>
	 *
	 * @param scene the AudioScene to add choices to
	 * @param samplesDir directory containing sample WAV files
	 */
	private void addChoices(AudioScene<?> scene, File samplesDir) {
		String dir = samplesDir.getAbsolutePath();

		// Channel 0 (non-melodic): Percussion hits
		NoteAudioChoice percs = NoteAudioChoice.fromSource(
				"Percs",
				new FileNoteSource(dir + "/Perc TAT 1.wav", WesternChromatic.C1),
				0, 9, false);
		percs.getSources().add(new FileNoteSource(dir + "/Perc TAT 2.wav", WesternChromatic.C1));
		percs.getSources().add(new FileNoteSource(dir + "/Perc TAT 3.wav", WesternChromatic.C1));

		// Channel 1 (non-melodic): Snare drums
		NoteAudioChoice snares = NoteAudioChoice.fromSource(
				"Snares",
				new FileNoteSource(dir + "/PMMC_Snares_Lush.wav", WesternChromatic.C1),
				1, 9, false);
		snares.getSources().add(new FileNoteSource(dir + "/PMMC_Snares_Pop_Vibe.wav", WesternChromatic.C1));
		snares.getSources().add(new FileNoteSource(dir + "/PMMC_Snares_Tiger.wav", WesternChromatic.C1));
		snares.getSources().add(new FileNoteSource(dir + "/PMMC_Snares_Tight.wav", WesternChromatic.C1));

		// Channel 2 (melodic): Bass synth
		NoteAudioChoice bass = NoteAudioChoice.fromSource(
				"Bass",
				new FileNoteSource(dir + "/DX Punch S612 C0.wav", WesternChromatic.A0),
				2, 9, true);
		bass.getSources().add(new FileNoteSource(dir + "/DX Punch S612 C1.wav", WesternChromatic.C1));

		// Channel 3 (melodic): Harmony / Chord
		NoteAudioChoice harmony = NoteAudioChoice.fromSource(
				"Harmony",
				new FileNoteSource(dir + "/Synth Guitar S612 C0.wav", WesternChromatic.A0),
				3, 9, true);
		harmony.getSources().add(new FileNoteSource(dir + "/Synth Guitar S612 C1.wav", WesternChromatic.C1));
		harmony.getSources().add(new FileNoteSource(dir + "/Synth Guitar S612 C2.wav", WesternChromatic.C2));
		harmony.getSources().add(new FileNoteSource(dir + "/Synth Guitar S612 C3.wav", WesternChromatic.C3));

		// Channel 4 (melodic): Lead synth
		NoteAudioChoice lead = NoteAudioChoice.fromSource(
				"Lead",
				new FileNoteSource(dir + "/DX Punch S612 C2.wav", WesternChromatic.C2),
				4, 9, true);
		lead.getSources().add(new FileNoteSource(dir + "/DX Punch S612 C3.wav", WesternChromatic.C3));
		lead.getSources().add(new FileNoteSource(dir + "/DX Punch S612 C4.wav", WesternChromatic.C4));
		lead.getSources().add(new FileNoteSource(dir + "/DX Punch S612 C5.wav", WesternChromatic.C5));

		// Channel 5 (non-melodic): Accent percussion
		NoteAudioChoice accents = NoteAudioChoice.fromSource(
				"Accents",
				new FileNoteSource(dir + "/Snare Eclipse 1.wav", WesternChromatic.C1),
				5, 9, false);
		accents.getSources().add(new FileNoteSource(dir + "/Snare Eclipse 2.wav", WesternChromatic.C1));
		accents.getSources().add(new FileNoteSource(dir + "/Snare TripTrap 5.wav", WesternChromatic.C1));

		scene.getPatternManager().getChoices().addAll(
				List.of(percs, snares, bass, harmony, lead, accents));
	}

}
