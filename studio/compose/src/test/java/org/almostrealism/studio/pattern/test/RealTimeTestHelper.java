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
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.WaveOutput;
import org.almostrealism.studio.arrange.MixdownManager;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.studio.health.MultiChannelAudioOutput;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.music.pattern.PatternSystemManager;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.color.RGBFeatures;
import org.almostrealism.heredity.TemporalCellular;
import org.almostrealism.io.ConsoleFeatures;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/**
 * Shared test utilities for real-time AudioScene testing.
 *
 * <p>This helper consolidates common test patterns to reduce duplication
 * and ensure consistent artifact generation across all real-time tests.</p>
 *
 * <h2>Typical Usage</h2>
 * <pre>{@code
 * @Test(timeout = 60000)
 * public void myTest() {
 *     RealTimeTestHelper helper = new RealTimeTestHelper(this::log);
 *     helper.disableEffects();
 *     File samplesDir = helper.requireSamplesDir();
 *
 *     AudioScene<?> scene = helper.createSceneWithWorkingSeed(samplesDir, 2);
 *     RenderResult result = helper.renderRealTime(scene, 1024, 2.0, "results/my-test.wav");
 *
 *     result.stats().assertNonSilent("Output should contain audio");
 *     helper.generateArtifacts(result, "my-test");
 * }
 * }</pre>
 *
 * @see AudioSceneTestBase
 * @see AudioStats
 * @see RenderResult
 */
public class RealTimeTestHelper implements CellFeatures, RGBFeatures, ConsoleFeatures {

	/** Path to the Samples directory relative to the compose module. */
	public static final String SAMPLES_PATH = "../../Samples";

	/** Path to the Library directory (ringsdesktop). */
	public static final String LIBRARY_PATH = "../../ringsdesktop/Library";

	/** Sample rate used for all tests. */
	public static final int SAMPLE_RATE = OutputLine.sampleRate;

	/** Maximum number of genome seeds to try before giving up. */
	public static final int MAX_GENOME_ATTEMPTS = 20;

	private final AudioSceneTestBase testBase;

	/**
	 * Creates a new helper bound to the given test base.
	 *
	 * @param testBase the test instance for scene creation and logging
	 */
	public RealTimeTestHelper(AudioSceneTestBase testBase) {
		this.testBase = testBase;
	}

	/**
	 * Disables all audio effects for cleaner test comparisons.
	 *
	 * <p>Call this at the start of any test that needs predictable,
	 * effects-free output for comparison or verification.</p>
	 */
	public void disableEffects() {
		MixdownManager.enableMainFilterUp = false;
		MixdownManager.enableEfxFilters = false;
		MixdownManager.enableEfx = false;
		PatternSystemManager.enableWarnings = false;
	}

	/**
	 * Returns the Samples directory if it exists, or {@code null} if it
	 * does not. When the directory is absent,
	 * {@link AudioSceneTestBase#addChoices} will generate synthetic
	 * fallback samples automatically.
	 *
	 * @return the Samples directory, or {@code null}
	 */
	public File requireSamplesDir() {
		File dir = new File(SAMPLES_PATH);
		return dir.exists() ? dir : null;
	}

	/**
	 * Returns the Library directory if it exists, or skips the test.
	 *
	 * @return the Library directory
	 */
	public File requireLibraryDir() {
		File dir = new File(LIBRARY_PATH);
		assumeTrue("Library directory not found: " + dir.getAbsolutePath(), dir.exists());
		return dir;
	}

	/**
	 * Creates a scene and applies the genome seed that produces the most pattern elements.
	 *
	 * @param samplesDir  directory containing sample WAV files
	 * @param sourceCount number of source channels
	 * @return configured AudioScene, or null if no working seed found
	 */
	public AudioScene<?> createSceneWithWorkingSeed(File samplesDir, int sourceCount) {
		AudioScene<?> searchScene = testBase.createBaselineScene(samplesDir, sourceCount);
		long seed = testBase.findWorkingGenomeSeed(searchScene, samplesDir);

		if (seed < 0) {
			log("No working genome found after " + MAX_GENOME_ATTEMPTS + " attempts");
			return null;
		}

		AudioScene<?> scene = testBase.createBaselineScene(samplesDir, sourceCount);
		testBase.applyGenome(scene, seed);

		int elements = testBase.countElements(scene);
		log("Created scene with seed " + seed + " (" + elements + " pattern elements)");
		return scene;
	}

	/**
	 * Creates a scene with a specific genome seed.
	 *
	 * @param samplesDir  directory containing sample WAV files
	 * @param sourceCount number of source channels
	 * @param seed        the genome seed to apply
	 * @return configured AudioScene
	 */
	public AudioScene<?> createSceneWithSeed(File samplesDir, int sourceCount, long seed) {
		AudioScene<?> scene = testBase.createBaselineScene(samplesDir, sourceCount);
		testBase.applyGenome(scene, seed);

		int elements = testBase.countElements(scene);
		log("Created scene with seed " + seed + " (" + elements + " pattern elements)");
		return scene;
	}

	/**
	 * Renders audio using the real-time runner with timing measurements.
	 *
	 * @param scene           the AudioScene to render
	 * @param bufferSize      frames per buffer
	 * @param durationSeconds how many seconds to render
	 * @param outputFile      path to write the WAV file
	 * @return render result with timing and audio statistics
	 */
	public RenderResult renderRealTime(AudioScene<?> scene, int bufferSize,
									   double durationSeconds, String outputFile) {
		File file = new File(outputFile);
		file.getParentFile().mkdirs();

		WaveOutput output = new WaveOutput(() -> file, 24, true);
		TemporalCellular runner = scene.runnerRealTime(
				new MultiChannelAudioOutput(output), bufferSize);

		runner.setup().get().run();

		int totalFrames = (int) (durationSeconds * SAMPLE_RATE);
		int numBuffers = totalFrames / bufferSize;
		double bufferDurationMs = (double) bufferSize / SAMPLE_RATE * 1000;

		List<Long> bufferTimings = new ArrayList<>();
		Runnable tick = runner.tick().get();

		long startTime = System.nanoTime();
		for (int buf = 0; buf < numBuffers; buf++) {
			long bufferStart = System.nanoTime();
			tick.run();
			bufferTimings.add(System.nanoTime() - bufferStart);
		}
		long totalTime = System.nanoTime() - startTime;

		output.write().get().run();

		TimingStats timing = new TimingStats(bufferTimings, bufferDurationMs, totalTime);
		AudioStats stats = analyzeAudio(outputFile);

		return new RenderResult(outputFile, stats, timing, numBuffers, totalFrames);
	}

	/**
	 * Analyzes an audio file and returns statistics.
	 *
	 * @param filePath path to the WAV file
	 * @return audio statistics, or null if file cannot be read
	 */
	public AudioStats analyzeAudio(String filePath) {
		try {
			File file = new File(filePath);
			if (!file.exists() || file.length() < 100) {
				return null;
			}

			WaveData data = WaveData.load(file);
			return AudioStats.fromWaveData(data, SAMPLE_RATE);
		} catch (IOException e) {
			log("Failed to analyze audio: " + e.getMessage());
			return null;
		}
	}

	/**
	 * Generates visual artifacts (spectrogram, waveform summary) for a render result.
	 *
	 * <p>Creates files in the results directory:</p>
	 * <ul>
	 *   <li>{testName}-spectrogram.png - frequency content over time</li>
	 *   <li>{testName}-summary.txt - human-readable audio statistics</li>
	 * </ul>
	 *
	 * @param result   the render result
	 * @param testName base name for output files
	 */
	public void generateArtifacts(RenderResult result, String testName) {
		// Generate spectrogram
		String spectrogramPath = "results/" + testName + "-spectrogram.png";
		testBase.generateSpectrogram(result.outputFile(), spectrogramPath);

		// Write summary
		String summaryPath = "results/" + testName + "-summary.txt";
		writeSummary(result, summaryPath, testName);

		log("Generated artifacts: " + spectrogramPath + ", " + summaryPath);
	}

	/**
	 * Writes a human-readable summary of the render result.
	 */
	private void writeSummary(RenderResult result, String path, String testName) {
		StringBuilder sb = new StringBuilder();
		sb.append("=== ").append(testName).append(" ===\n\n");

		AudioStats stats = result.stats();
		if (stats != null) {
			sb.append("Audio Statistics:\n");
			sb.append("  Duration: ").append(String.format("%.2f", stats.durationSeconds())).append(" s\n");
			sb.append("  Frames: ").append(stats.frameCount()).append("\n");
			sb.append("  Max Amplitude: ").append(String.format("%.6f", stats.maxAmplitude())).append("\n");
			sb.append("  RMS Level: ").append(String.format("%.6f", stats.rmsLevel())).append("\n");
			sb.append("  Non-Zero Ratio: ").append(String.format("%.1f%%", stats.nonZeroRatio() * 100)).append("\n");
			sb.append("  Is Silent: ").append(stats.isSilent() ? "YES" : "NO").append("\n");
			sb.append("\n");
		}

		TimingStats timing = result.timing();
		if (timing != null) {
			sb.append("Timing Statistics:\n");
			sb.append("  Buffer Count: ").append(result.bufferCount()).append("\n");
			sb.append("  Target Buffer Time: ").append(String.format("%.3f", timing.targetBufferMs())).append(" ms\n");
			sb.append("  Avg Buffer Time: ").append(String.format("%.3f", timing.avgBufferMs())).append(" ms\n");
			sb.append("  Min Buffer Time: ").append(String.format("%.3f", timing.minBufferMs())).append(" ms\n");
			sb.append("  Max Buffer Time: ").append(String.format("%.3f", timing.maxBufferMs())).append(" ms\n");
			sb.append("  Real-Time Ratio: ").append(String.format("%.2fx", timing.realTimeRatio())).append("\n");
			sb.append("  Overruns: ").append(timing.overrunCount()).append("\n");
			sb.append("  Meets Real-Time: ").append(timing.meetsRealTime() ? "YES" : "NO").append("\n");
		}

		try {
			Files.writeString(Path.of(path), sb.toString());
		} catch (IOException e) {
			log("Failed to write summary: " + e.getMessage());
		}
	}

	/**
	 * Logs a comparison between two render results.
	 *
	 * @param label1  description of first result
	 * @param result1 first render result
	 * @param label2  description of second result
	 * @param result2 second render result
	 */
	public void logComparison(String label1, RenderResult result1,
							  String label2, RenderResult result2) {
		log("=== Audio Comparison ===");

		AudioStats s1 = result1.stats();
		AudioStats s2 = result2.stats();

		if (s1 != null && s2 != null) {
			log(label1 + ": RMS=" + String.format("%.6f", s1.rmsLevel()) +
					", Max=" + String.format("%.6f", s1.maxAmplitude()));
			log(label2 + ": RMS=" + String.format("%.6f", s2.rmsLevel()) +
					", Max=" + String.format("%.6f", s2.maxAmplitude()));

			if (s1.rmsLevel() > 0 && s2.rmsLevel() > 0) {
				double rmsRatio = s2.rmsLevel() / s1.rmsLevel();
				log("RMS Ratio (" + label2 + "/" + label1 + "): " + String.format("%.4f", rmsRatio));
			}
		}
	}
}
