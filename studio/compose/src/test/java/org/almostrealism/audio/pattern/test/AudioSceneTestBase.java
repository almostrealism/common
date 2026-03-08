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
import org.almostrealism.audio.AudioTestFeatures;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.notes.FileNoteSource;
import org.almostrealism.audio.notes.NoteAudioChoice;
import org.almostrealism.audio.pattern.PatternElement;
import org.almostrealism.audio.pattern.PatternLayerManager;
import org.almostrealism.audio.tone.DefaultKeyboardTuning;
import org.almostrealism.audio.tone.WesternChromatic;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.color.RGBFeatures;
import org.almostrealism.io.SystemUtils;
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
public abstract class AudioSceneTestBase extends TestSuiteBase implements CellFeatures, RGBFeatures, AudioTestFeatures {

	/** Path to the Samples directory relative to the compose module. */
	protected static final String SAMPLES_PATH =
			SystemUtils.getProperty("AR_RINGS_LIBRARY", "../../Samples");

	/**
	 * Returns the Samples directory if it exists, or {@code null} if it
	 * does not. When the directory is absent, {@link #addChoices} will
	 * generate synthetic fallback samples automatically.
	 *
	 * @return the Samples directory, or {@code null}
	 */
	protected File getSamplesDir() {
		File dir = new File(SAMPLES_PATH);
		return dir.exists() ? dir : null;
	}

	/** Sample rate used for all tests. */
	protected static final int SAMPLE_RATE = OutputLine.sampleRate;

	/** Maximum number of genome seeds to try before giving up. */
	protected static final int MAX_GENOME_ATTEMPTS = 20;

	/** Duration in seconds for rendered audio in tests. */
	protected static final double RENDER_SECONDS = 4.0;

	/**
	 * Creates a baseline AudioScene with the default source count (6 channels).
	 *
	 * @param samplesDir directory containing sample WAV files
	 * @return configured AudioScene ready for genome assignment
	 *
	 * @see #createBaselineScene(File, int)
	 */
	protected AudioScene<?> createBaselineScene(File samplesDir) {
		return createBaselineScene(samplesDir, AudioScene.DEFAULT_SOURCE_COUNT);
	}

	/**
	 * Creates a baseline AudioScene with programmatic NoteAudioChoices.
	 *
	 * <p>Mirrors the structure of
	 * {@link org.almostrealism.audio.optimize.AudioSceneOptimizer#createScene()}
	 * but uses {@link FileNoteSource} instead of TreeNoteSource for portability.</p>
	 *
	 * <p>The {@code sourceCount} parameter controls how many channels are created.
	 * Offline rendering tests should use a small count (e.g., 2) because the full
	 * arrangement is rendered for every channel/voicing/stereo combination during
	 * setup. Real-time tests can use the default (6) because they render only small
	 * buffers per tick.</p>
	 *
	 * @param samplesDir  directory containing sample WAV files
	 * @param sourceCount number of source channels to create
	 * @return configured AudioScene ready for genome assignment
	 */
	protected AudioScene<?> createBaselineScene(File samplesDir, int sourceCount) {
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
	 * <p>When a sample file does not exist on disk, a synthetic fallback
	 * is generated at runtime via {@link AudioTestFeatures#getNamedTestWavFile}.
	 * Percussive channels receive noise-burst sounds with exponential decay,
	 * while melodic channels receive sine waves at the frequency implied by
	 * the root {@link WesternChromatic} key. This allows the full test
	 * pipeline to run in CI without requiring real audio samples.</p>
	 *
	 * @param scene the AudioScene to add choices to
	 * @param samplesDir directory containing sample WAV files (may be {@code null}
	 *                   or non-existent, in which case all samples are synthesized)
	 */
	protected void addChoices(AudioScene<?> scene, File samplesDir) {
		String dir = samplesDir == null ? null : samplesDir.getAbsolutePath();

		NoteAudioChoice percs = NoteAudioChoice.fromSource(
				"Percs",
				new FileNoteSource(resolveSample(dir, "Perc TAT 1.wav", 200, true), WesternChromatic.C1),
				0, 9, false);
		percs.getSources().add(new FileNoteSource(resolveSample(dir, "Perc TAT 2.wav", 250, true), WesternChromatic.C1));
		percs.getSources().add(new FileNoteSource(resolveSample(dir, "Perc TAT 3.wav", 300, true), WesternChromatic.C1));

		NoteAudioChoice snares = NoteAudioChoice.fromSource(
				"Snares",
				new FileNoteSource(resolveSample(dir, "PMMC_Snares_Lush.wav", 180, true), WesternChromatic.C1),
				1, 9, false);
		snares.getSources().add(new FileNoteSource(resolveSample(dir, "PMMC_Snares_Pop_Vibe.wav", 220, true), WesternChromatic.C1));
		snares.getSources().add(new FileNoteSource(resolveSample(dir, "PMMC_Snares_Tiger.wav", 260, true), WesternChromatic.C1));
		snares.getSources().add(new FileNoteSource(resolveSample(dir, "PMMC_Snares_Tight.wav", 320, true), WesternChromatic.C1));

		NoteAudioChoice bass = NoteAudioChoice.fromSource(
				"Bass",
				new FileNoteSource(resolveSample(dir, "DX Punch S612 C0.wav", 27.5, false), WesternChromatic.A0),
				2, 9, true);
		bass.getSources().add(new FileNoteSource(resolveSample(dir, "DX Punch S612 C1.wav", 32.7, false), WesternChromatic.C1));

		NoteAudioChoice harmony = NoteAudioChoice.fromSource(
				"Harmony",
				new FileNoteSource(resolveSample(dir, "Synth Guitar S612 C0.wav", 27.5, false), WesternChromatic.A0),
				3, 9, true);
		harmony.getSources().add(new FileNoteSource(resolveSample(dir, "Synth Guitar S612 C1.wav", 32.7, false), WesternChromatic.C1));
		harmony.getSources().add(new FileNoteSource(resolveSample(dir, "Synth Guitar S612 C2.wav", 65.4, false), WesternChromatic.C2));
		harmony.getSources().add(new FileNoteSource(resolveSample(dir, "Synth Guitar S612 C3.wav", 130.8, false), WesternChromatic.C3));

		NoteAudioChoice lead = NoteAudioChoice.fromSource(
				"Lead",
				new FileNoteSource(resolveSample(dir, "DX Punch S612 C2.wav", 65.4, false), WesternChromatic.C2),
				4, 9, true);
		lead.getSources().add(new FileNoteSource(resolveSample(dir, "DX Punch S612 C3.wav", 130.8, false), WesternChromatic.C3));
		lead.getSources().add(new FileNoteSource(resolveSample(dir, "DX Punch S612 C4.wav", 261.6, false), WesternChromatic.C4));
		lead.getSources().add(new FileNoteSource(resolveSample(dir, "DX Punch S612 C5.wav", 523.3, false), WesternChromatic.C5));

		NoteAudioChoice accents = NoteAudioChoice.fromSource(
				"Accents",
				new FileNoteSource(resolveSample(dir, "Snare Eclipse 1.wav", 350, true), WesternChromatic.C1),
				5, 9, false);
		accents.getSources().add(new FileNoteSource(resolveSample(dir, "Snare Eclipse 2.wav", 400, true), WesternChromatic.C1));
		accents.getSources().add(new FileNoteSource(resolveSample(dir, "Snare TripTrap 5.wav", 280, true), WesternChromatic.C1));

		scene.getPatternManager().getChoices().addAll(
				List.of(percs, snares, bass, harmony, lead, accents));
	}

	/**
	 * Resolves a sample file path, falling back to a synthetic WAV if the
	 * file does not exist on disk. The fallback is generated via
	 * {@link AudioTestFeatures#getNamedTestWavFile} and cached by name
	 * for the lifetime of the JVM.
	 *
	 * @param dir        base directory for real samples (may be {@code null})
	 * @param filename   sample file name (e.g. "Perc TAT 1.wav")
	 * @param frequency  frequency hint for synthetic fallback generation
	 * @param percussive whether the fallback should be percussive or melodic
	 * @return absolute path to either the real or synthetic WAV file
	 */
	private String resolveSample(String dir, String filename,
								 double frequency, boolean percussive) {
		if (dir != null) {
			File real = new File(dir, filename);
			if (real.exists()) {
				return real.getAbsolutePath();
			}
		}

		return getNamedTestWavPath(filename, frequency, 2.0, percussive);
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

	/**
	 * Generates a spectrogram image from a WAV file and saves it as a PNG.
	 *
	 * <p>Uses hardware-accelerated spectrogram generation via {@link WaveData#spectrogram(int)}.</p>
	 *
	 * @param wavPath    path to the WAV file
	 * @param outputPath path to save the spectrogram PNG
	 */
	protected void generateSpectrogram(String wavPath, String outputPath) {
		try {
			WaveData waveData = WaveData.load(new File(wavPath));
			PackedCollection spectrogram = waveData.spectrogram(0);

			int bins = spectrogram.getShape().length(0);
			int timeSlices = spectrogram.getShape().length(1);

			saveRgb(outputPath, cp(spectrogram)).get().run();
			log("Generated spectrogram: " + outputPath + " (" + bins + "x" + timeSlices + ")");

			spectrogram.destroy();
			waveData.destroy();
		} catch (Exception e) {
			log("Failed to generate spectrogram for " + wavPath + ": " + e.getMessage());
		}
	}
}
