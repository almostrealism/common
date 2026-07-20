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

import io.almostrealism.compute.ComputeRequirement;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.studio.AudioScene;
import org.almostrealism.studio.AudioSceneLoader;
import org.almostrealism.audio.AudioTestFeatures;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.music.notes.FileNoteSource;
import org.almostrealism.music.notes.NoteAudioChoice;
import org.almostrealism.music.pattern.PatternElement;
import org.almostrealism.music.pattern.PatternLayerManager;
import org.almostrealism.audio.tone.DefaultKeyboardTuning;
import org.almostrealism.audio.tone.WesternChromatic;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.color.RGBFeatures;
import org.almostrealism.heredity.ProjectedGenome;
import org.almostrealism.io.SystemUtils;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Assume;

import org.almostrealism.heredity.ProjectedGenome;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Random;

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

	/**
	 * Curated sample library location, overridable via {@code AR_RINGS_LIBRARY}. Defaults to
	 * the absolute curated-library path (the same directory that holds {@link #PATTERN_FACTORY}).
	 * Kept absolute so no repo-root symlink to an out-of-repo location is required; when the
	 * library is absent (e.g. on CI) {@link #getSamplesDir()} returns {@code null} and synthetic
	 * fallback samples are used.
	 */
	protected static final String SAMPLES_PATH =
			SystemUtils.getProperty("AR_RINGS_LIBRARY", "/Users/Shared/Music/Samples");

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

	/**
	 * Returns the curated sample library, skipping or failing the test when the library (or its
	 * pattern factory) is not present on this host — the choice depends on whether a GPU driver
	 * is available.
	 * <p>
	 * The real-sample media benchmarks once skipped silently — logging a message and returning — when
	 * the library was absent, which a runner that never mounts {@link #SAMPLES_PATH} reports as a pass,
	 * hiding the difference between hosts. The GPU runners (the Metal {@code test-media-mac} job) mount
	 * the library and are expected to run the real workload; the CPU-only Linux runners never have. So:
	 * <ul>
	 *   <li>library present → return it and run the real workload;</li>
	 *   <li>library absent and <em>no</em> GPU driver available → {@code Assume}-skip (a CPU-only host
	 *       that is not expected to mount the library, e.g. the native Linux jobs);</li>
	 *   <li>library absent but a GPU driver <em>is</em> available → {@link Assert#fail} (a GPU host is
	 *       expected to mount the library; a miss is a real misconfiguration that must not report a
	 *       false pass).</li>
	 * </ul>
	 *
	 * @return the curated sample library directory (its {@link #PATTERN_FACTORY} is guaranteed to exist)
	 */
	protected File requireCuratedLibrary() {
		File library = getSamplesDir();
		if (library != null && new File(PATTERN_FACTORY).exists()) {
			return library;
		}

		String detail = "Curated sample library " + SAMPLES_PATH + " / pattern factory "
				+ PATTERN_FACTORY + " not available on this host.";
		Assume.assumeTrue(detail + " No GPU driver is available, so this is a CPU-only host that is not"
				+ " expected to mount the library; skipping rather than failing.", isGpuAvailable());
		Assert.fail(detail + " A GPU driver IS available, so this host is expected to mount the curated"
				+ " library; failing rather than reporting a false pass for a workload it did not run.");
		return library;
	}

	/**
	 * Returns whether a Metal data context is available. Render tests scale their
	 * durations and skip Metal-specific measurements off-Metal: OpenCL is not a
	 * primary backend and runs the mixdown several times slower, so CL-only
	 * environments (the {@code test-media-cl} job) get shorter smoke renders
	 * rather than the full Metal-length evaluation renders.
	 *
	 * @return whether a Metal data context is available
	 */
	protected boolean isMetalAvailable() {
		return Hardware.getLocalHardware()
				.getDataContext(false, true, ComputeRequirement.MTL) != null;
	}

	/**
	 * Returns whether a GPU compute driver — Metal or OpenCL — is available on this host, used by
	 * {@link #requireCuratedLibrary()} to decide whether a missing sample library is an expected skip
	 * (CPU-only host) or a genuine failure (a GPU host that should have mounted the library).
	 *
	 * @return whether a Metal or OpenCL data context is available
	 */
	protected boolean isGpuAvailable() {
		Hardware hardware = Hardware.getLocalHardware();
		return hardware.getDataContext(false, true, ComputeRequirement.MTL) != null
				|| hardware.getDataContext(false, true, ComputeRequirement.CL) != null;
	}

	/** Curated pattern factory; the real arrangement that decides which samples play where. */
	protected static final String PATTERN_FACTORY =
			SystemUtils.getProperty("AR_RINGS_PATTERNS", "/Users/Shared/Music/pattern-factory.json");

	/**
	 * Persisted scene settings making the real-scene arrangement reproducible across JVM runs.
	 * Loading with a {@code null} settings file falls back to
	 * {@code PatternSystemManager.Settings.defaultSettings}, which draws fresh random selection
	 * functions ({@code ParameterFunction.random()}) for every pattern — a different arrangement
	 * every run. The first run writes the constructed settings here; later runs load them, so the
	 * same genome seed reproduces the identical arrangement and render.
	 */
	protected static final String SCENE_SETTINGS =
			SystemUtils.getProperty("AR_RINGS_SETTINGS", "results/pdsl-cutover/scene-settings.json");

	/** Sample rate used for all tests. */
	protected static final int SAMPLE_RATE = OutputLine.sampleRate;

	/** Maximum number of genome seeds to try before giving up. */
	protected static final int MAX_GENOME_ATTEMPTS = 20;

	/** Duration in seconds for rendered audio in tests. */
	protected static final double RENDER_SECONDS = 4.0;

	/** Minimum peak amplitude below which a rendered signal is considered silent. */
	protected static final double SILENCE_THRESHOLD = 1e-4;

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
	 * {@link org.almostrealism.studio.optimize.AudioSceneOptimizer#createScene()}
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

		AudioSceneLoader.Settings settings = AudioSceneLoader.Settings.defaultSettings(
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
	 * Loads a fresh scene from the real curated arrangement (recursive library + pattern
	 * factory), mirroring {@link org.almostrealism.studio.optimize.AudioSceneOptimizer#createScene()}
	 * but with explicit paths so it does not depend on the working directory. Settings are
	 * persisted to {@link #SCENE_SETTINGS} on first load so every later run (and every later
	 * session) reconstructs the identical arrangement.
	 *
	 * @param library        the recursive sample library root
	 * @param patternFactory the curated pattern factory JSON
	 * @param bpm            tempo for the scene
	 * @param totalMeasures  total measures in the arrangement
	 * @return a freshly loaded scene
	 * @throws IOException if the scene cannot be loaded
	 */
	protected AudioScene<?> loadCuratedScene(File library, File patternFactory,
											 double bpm, int totalMeasures) throws IOException {
		File settings = new File(SCENE_SETTINGS);
		AudioScene<?> scene = AudioScene.load(
				settings.exists() ? settings.getAbsolutePath() : null,
				patternFactory.getAbsolutePath(),
				library.getAbsolutePath(), bpm, SAMPLE_RATE);
		scene.setTotalMeasures(totalMeasures);

		if (!settings.exists()) {
			// First run: persist the randomly-drawn settings so every later run (and every
			// later session) reconstructs this exact arrangement. See SCENE_SETTINGS.
			settings.getParentFile().mkdirs();
			scene.saveSettings(settings);
			log("Persisted scene settings for reproducibility: " + settings.getAbsolutePath());
		}

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
		scene.assignGenome(fixedGenome(scene, seed));
	}

	/**
	 * Builds a deterministic {@link ProjectedGenome} for the scene from a fixed seed, without
	 * assigning it. The genome's parameter vector matches the shape of the scene's genome and
	 * is filled from a seeded {@link Random}, so the same seed reproduces the identical genome
	 * across runs and across population instances. Use this when a genome must be reused (e.g.
	 * rendered through several channel configurations) rather than regenerated.
	 *
	 * @param scene the AudioScene whose genome shape determines the parameter vector length
	 * @param seed  the seed for generating deterministic genome parameters
	 * @return a deterministic genome ready for {@link AudioScene#assignGenome(ProjectedGenome)}
	 *         or inclusion in an {@code AudioScenePopulation}
	 */
	protected ProjectedGenome fixedGenome(AudioScene<?> scene, long seed) {
		Random random = new Random(seed);

		PackedCollection genomeParams = scene.getGenome().getParameters();
		PackedCollection seededParams = new PackedCollection(genomeParams.getShape());
		for (int i = 0; i < seededParams.getMemLength(); i++) {
			seededParams.setMem(i, random.nextDouble());
		}

		return new ProjectedGenome(seededParams);
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
	 * Counts pattern elements on a single channel, so a test can verify the selected channel
	 * actually carries content for a given genome before rendering it.
	 *
	 * @param scene   the AudioScene to count elements in
	 * @param channel the channel index to restrict the count to
	 * @return the number of pattern elements on {@code channel}
	 */
	protected int countElements(AudioScene<?> scene, int channel) {
		int total = 0;
		for (PatternLayerManager plm : scene.getPatternManager().getPatterns()) {
			if (plm.getChannel() != channel) continue;
			total += plm.getAllElements(0.0, plm.getDuration()).size();
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

	/**
	 * Returns the peak absolute sample over channel 0 of a rendered WAV — the shared
	 * non-silence gate for render tests (a fast render of silence is not progress).
	 *
	 * @param wavPath path to the rendered WAV
	 * @return the peak absolute sample value in [0, 1]
	 * @throws IOException if the WAV cannot be read
	 */
	protected double peakAmplitude(String wavPath) throws IOException {
		WaveData data = WaveData.load(new File(wavPath));
		try {
			PackedCollection channel = data.getChannelData(0);
			double peak = 0.0;
			int n = channel.getShape().getTotalSize();
			for (int i = 0; i < n; i++) {
				double v = Math.abs(channel.valueAt(i));
				if (v > peak) peak = v;
			}
			return peak;
		} finally {
			data.destroy();
		}
	}

	/**
	 * Loads a WAV file and returns its first-channel samples as a {@code double[]}.
	 *
	 * @param file the WAV file to read
	 * @return the mono sample values
	 * @throws IOException if the file cannot be read
	 */
	protected double[] readWavSamples(File file) throws IOException {
		WaveData wav = WaveData.load(file);
		try {
			PackedCollection data = wav.getData();
			int n = data.getMemLength();
			double[] samples = new double[n];
			for (int i = 0; i < n; i++) {
				samples[i] = data.toDouble(i);
			}
			return samples;
		} finally {
			wav.destroy();
		}
	}

	/**
	 * Computes the root-mean-square of one sample range, skipping non-finite values.
	 *
	 * @param samples the samples
	 * @param start   inclusive start index
	 * @param end     exclusive end index
	 * @return the RMS of the range
	 */
	protected double windowRms(double[] samples, int start, int end) {
		double energy = 0.0;
		for (int i = start; i < end; i++) {
			if (Double.isFinite(samples[i])) energy += samples[i] * samples[i];
		}
		return Math.sqrt(energy / Math.max(1, end - start));
	}

	/**
	 * Logs a windowed RMS comparison of two renders so level parity can be verified over time.
	 * A single whole-file RMS can hide a divergence that grows across the duration (e.g. as
	 * automation sweeps), so the renders are split into aligned windows and the per-window RMS
	 * of each plus their ratio is logged, followed by the whole-file ratio.
	 *
	 * @param label    label for the log lines
	 * @param expected samples from the reference render (denominator of the ratio)
	 * @param actual   samples from the comparison render (numerator of the ratio)
	 */
	protected void reportWindowedParity(String label, double[] expected, double[] actual) {
		int n = Math.min(expected.length, actual.length);
		int window = Math.max(1, n / 8);
		for (int start = 0; start < n; start += window) {
			int end = Math.min(n, start + window);
			double ea = windowRms(expected, start, end);
			double aa = windowRms(actual, start, end);
			log(String.format("parity %s window=%d-%d expected=%.4f actual=%.4f ratio=%.3f",
					label, start, end, ea, aa, ea > 0 ? aa / ea : Double.NaN));
		}
		double eAll = windowRms(expected, 0, n);
		double aAll = windowRms(actual, 0, n);
		log(String.format("parity %s overall expected=%.4f actual=%.4f ratio=%.3f",
				label, eAll, aAll, eAll > 0 ? aAll / eAll : Double.NaN));
	}

	/**
	 * Logs the peak amplitude, RMS, and non-finite count of a rendered signal.
	 *
	 * @param label   the variant label
	 * @param samples the rendered samples
	 */
	protected void reportLevels(String label, double[] samples) {
		double peak = 0.0;
		double energy = 0.0;
		int nonFinite = 0;
		for (double sample : samples) {
			if (!Double.isFinite(sample)) {
				nonFinite++;
				continue;
			}
			peak = Math.max(peak, Math.abs(sample));
			energy += sample * sample;
		}
		double rms = Math.sqrt(energy / Math.max(1, samples.length));
		log(String.format("levels stage=%s peak=%.4f rms=%.4f nonFinite=%d",
				label, peak, rms, nonFinite));
	}

	/**
	 * Asserts that the given samples are all finite and that the peak amplitude exceeds
	 * {@link #SILENCE_THRESHOLD}.
	 *
	 * @param label   path label for assertion messages
	 * @param samples the rendered samples
	 */
	protected void assertFiniteNonSilent(String label, double[] samples) {
		Assert.assertTrue(label + " produced no samples", samples.length > 0);

		double peak = 0.0;
		for (double sample : samples) {
			Assert.assertTrue(label + " produced a non-finite sample", Double.isFinite(sample));
			peak = Math.max(peak, Math.abs(sample));
		}

		log(label + " path: samples=" + samples.length + " peak=" + peak);
		Assert.assertTrue(label + " output is silent (peak=" + peak + ")",
				peak > SILENCE_THRESHOLD);
	}
}
