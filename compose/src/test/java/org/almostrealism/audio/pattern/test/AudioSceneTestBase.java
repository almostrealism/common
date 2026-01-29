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
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.notes.FileNoteSource;
import org.almostrealism.audio.notes.NoteAudioChoice;
import org.almostrealism.audio.pattern.PatternElement;
import org.almostrealism.audio.pattern.PatternLayerManager;
import org.almostrealism.audio.tone.DefaultKeyboardTuning;
import org.almostrealism.audio.tone.WesternChromatic;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;

import java.io.File;
import java.util.List;

/**
 * Shared test infrastructure for AudioScene tests.
 *
 * <p>Provides factory methods for creating deterministic test scenes with
 * programmatic {@link NoteAudioChoice NoteAudioChoices} backed by
 * {@link FileNoteSource}. This base class is used by both baseline
 * and real-time correctness tests to ensure identical scene configurations.</p>
 *
 * @see AudioSceneBaselineTest
 * @see AudioSceneRealTimeCorrectnessTest
 */
public abstract class AudioSceneTestBase extends TestSuiteBase implements CellFeatures {

	/** Path to the Samples directory relative to the compose module. */
	protected static final String SAMPLES_PATH = "../../Samples";

	/** Sample rate used for all tests. */
	protected static final int SAMPLE_RATE = OutputLine.sampleRate;

	/** Maximum number of genome seeds to try before giving up. */
	protected static final int MAX_GENOME_ATTEMPTS = 20;

	/** Duration in seconds for rendered audio in tests. */
	protected static final double RENDER_SECONDS = 4.0;

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
	protected AudioScene<?> createBaselineScene(File samplesDir) {
		int sourceCount = AudioScene.DEFAULT_SOURCE_COUNT;
		int delayLayers = AudioScene.DEFAULT_DELAY_LAYERS;

		AudioScene<?> scene = new AudioScene<>(120.0, sourceCount, delayLayers, SAMPLE_RATE);
		scene.setTuning(new DefaultKeyboardTuning());

		addChoices(scene, samplesDir);

		AudioScene.Settings settings = AudioScene.Settings.defaultSettings(
				sourceCount,
				AudioScene.DEFAULT_PATTERNS_PER_CHANNEL,
				AudioScene.DEFAULT_ACTIVE_PATTERNS,
				AudioScene.DEFAULT_LAYERS,
				AudioScene.DEFAULT_LAYER_SCALE,
				AudioScene.DEFAULT_DURATION);
		scene.setSettings(settings);

		scene.setPatternActivityBias(1.0);
		scene.setTotalMeasures(16);

		return scene;
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
	protected void applyGenome(AudioScene<?> scene, long seed) {
		java.util.Random random = new java.util.Random(seed);

		PackedCollection genomeParams = scene.getGenome().getParameters();
		PackedCollection seededParams = new PackedCollection(genomeParams.getShape());
		for (int i = 0; i < seededParams.getMemLength(); i++) {
			seededParams.setMem(i, random.nextDouble());
		}

		scene.assignGenome(new org.almostrealism.heredity.ProjectedGenome(seededParams));
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
	protected void addChoices(AudioScene<?> scene, File samplesDir) {
		String dir = samplesDir.getAbsolutePath();

		NoteAudioChoice percs = NoteAudioChoice.fromSource(
				"Percs",
				new FileNoteSource(dir + "/Perc TAT 1.wav", WesternChromatic.C1),
				0, 9, false);
		percs.getSources().add(new FileNoteSource(dir + "/Perc TAT 2.wav", WesternChromatic.C1));
		percs.getSources().add(new FileNoteSource(dir + "/Perc TAT 3.wav", WesternChromatic.C1));

		NoteAudioChoice snares = NoteAudioChoice.fromSource(
				"Snares",
				new FileNoteSource(dir + "/PMMC_Snares_Lush.wav", WesternChromatic.C1),
				1, 9, false);
		snares.getSources().add(new FileNoteSource(dir + "/PMMC_Snares_Pop_Vibe.wav", WesternChromatic.C1));
		snares.getSources().add(new FileNoteSource(dir + "/PMMC_Snares_Tiger.wav", WesternChromatic.C1));
		snares.getSources().add(new FileNoteSource(dir + "/PMMC_Snares_Tight.wav", WesternChromatic.C1));

		NoteAudioChoice bass = NoteAudioChoice.fromSource(
				"Bass",
				new FileNoteSource(dir + "/DX Punch S612 C0.wav", WesternChromatic.A0),
				2, 9, true);
		bass.getSources().add(new FileNoteSource(dir + "/DX Punch S612 C1.wav", WesternChromatic.C1));

		NoteAudioChoice harmony = NoteAudioChoice.fromSource(
				"Harmony",
				new FileNoteSource(dir + "/Synth Guitar S612 C0.wav", WesternChromatic.A0),
				3, 9, true);
		harmony.getSources().add(new FileNoteSource(dir + "/Synth Guitar S612 C1.wav", WesternChromatic.C1));
		harmony.getSources().add(new FileNoteSource(dir + "/Synth Guitar S612 C2.wav", WesternChromatic.C2));
		harmony.getSources().add(new FileNoteSource(dir + "/Synth Guitar S612 C3.wav", WesternChromatic.C3));

		NoteAudioChoice lead = NoteAudioChoice.fromSource(
				"Lead",
				new FileNoteSource(dir + "/DX Punch S612 C2.wav", WesternChromatic.C2),
				4, 9, true);
		lead.getSources().add(new FileNoteSource(dir + "/DX Punch S612 C3.wav", WesternChromatic.C3));
		lead.getSources().add(new FileNoteSource(dir + "/DX Punch S612 C4.wav", WesternChromatic.C4));
		lead.getSources().add(new FileNoteSource(dir + "/DX Punch S612 C5.wav", WesternChromatic.C5));

		NoteAudioChoice accents = NoteAudioChoice.fromSource(
				"Accents",
				new FileNoteSource(dir + "/Snare Eclipse 1.wav", WesternChromatic.C1),
				5, 9, false);
		accents.getSources().add(new FileNoteSource(dir + "/Snare Eclipse 2.wav", WesternChromatic.C1));
		accents.getSources().add(new FileNoteSource(dir + "/Snare TripTrap 5.wav", WesternChromatic.C1));

		scene.getPatternManager().getChoices().addAll(
				List.of(percs, snares, bass, harmony, lead, accents));
	}

	/**
	 * Counts total pattern elements across all patterns in the scene.
	 *
	 * @param scene the AudioScene to count elements in
	 * @return total number of pattern elements
	 */
	protected int countElements(AudioScene<?> scene) {
		int total = 0;
		for (PatternLayerManager plm : scene.getPatternManager().getPatterns()) {
			List<PatternElement> elements =
					plm.getAllElements(0.0, plm.getDuration());
			total += elements.size();
		}
		return total;
	}

	/**
	 * Searches for a working genome seed without rendering audio.
	 * Returns the seed that produces the most pattern elements.
	 *
	 * @param scene the AudioScene to test (will be modified by assignGenome)
	 * @param samplesDir directory containing sample WAV files
	 * @return the seed that produced the most elements, or -1 if none found
	 */
	protected long findWorkingGenomeSeed(AudioScene<?> scene, File samplesDir) {
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
}
