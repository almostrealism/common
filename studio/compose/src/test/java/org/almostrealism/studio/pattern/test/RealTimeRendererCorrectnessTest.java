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
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.studio.health.MultiChannelAudioOutput;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.heredity.TemporalCellular;
import org.almostrealism.util.TestDepth;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/**
 * Tests the real-time rendering pipeline for buffer-size invariance and
 * stable coverage over long durations.
 */
public class RealTimeRendererCorrectnessTest extends AudioSceneTestBase {

	private static final String RESULTS_DIR = "results/renderer-correctness";
	private static final double SILENCE_THRESHOLD = 0.001;

	private RealTimeTestHelper helper;
	private File samplesDir;

	@Before
	public void setup() {
		helper = new RealTimeTestHelper(this);
		helper.disableEffects();

		samplesDir = new File(SAMPLES_PATH);
		assumeTrue("Samples directory required", samplesDir.exists());

		// Ensure results directory exists
		new File(RESULTS_DIR).mkdirs();
	}

	/**
	 * Test: Buffer size consistency.
	 *
	 * <p>Verifies that different buffer sizes produce consistent results.</p>
	 */
	@Test(timeout = 300_000)
	@TestDepth(1)
	public void testBufferSizeConsistency() {
		log("=== Test: Buffer Size Consistency ===");

		long seed = 42;
		double duration = 4.0;
		int[] bufferSizes = {1024, 2048, 4096};

		List<AudioStats> results = new ArrayList<>();

		for (int bufferSize : bufferSizes) {
			AudioScene<?> scene = createBaselineScene(samplesDir, 2);
			applyGenome(scene, seed);

			String path = RESULTS_DIR + "/buffer-" + bufferSize + ".wav";
			AudioStats stats = renderRealTime(scene, bufferSize, duration, path);
			results.add(stats);

			log(String.format("Buffer %d: nonZero=%.1f%%, rms=%.4f",
					bufferSize, stats.nonZeroRatio * 100, stats.rms));
		}

		// Compare all against the first (baseline)
		AudioStats baseline = results.get(0);
		for (int i = 1; i < results.size(); i++) {
			AudioStats test = results.get(i);
			double ratioDiff = Math.abs(baseline.nonZeroRatio - test.nonZeroRatio);

			assertTrue("Buffer " + bufferSizes[i] + " should produce similar results to " +
							bufferSizes[0] + " (diff was " + String.format("%.1f%%", ratioDiff * 100) + ")",
					ratioDiff < 0.1);
		}
	}

	/**
	 * Test: Long-duration rendering (30 seconds).
	 *
	 * <p>Verifies that audio coverage doesn't degrade over longer durations.</p>
	 */
	@Test(timeout = 300_000)
	@TestDepth(1)
	public void testLongDurationCoverage() {
		log("=== Test: Long Duration Coverage (30s) ===");

		long seed = 42;
		double duration = 30.0;
		int bufferSize = 4096;

		AudioScene<?> scene = createBaselineScene(samplesDir, 2);
		applyGenome(scene, seed);

		String path = RESULTS_DIR + "/long-duration-30s.wav";
		AudioStats stats = renderRealTime(scene, bufferSize, duration, path);

		log("Total frames: " + stats.frames);
		log("Non-zero ratio: " + String.format("%.1f%%", stats.nonZeroRatio * 100));
		log("Max amplitude: " + String.format("%.4f", stats.maxAmplitude));

		// Analyze by 5-second segments
		log("\n=== 5-Second Segments ===");
		int silentSegments = 0;
		for (int seg = 0; seg < 6; seg++) {
			int startSec = seg * 5;
			int endSec = Math.min(startSec + 5, stats.perSecondNonZero.size());

			double segmentAvg = 0;
			for (int s = startSec; s < endSec; s++) {
				segmentAvg += stats.perSecondNonZero.get(s);
			}
			segmentAvg /= (endSec - startSec);

			boolean hasAudio = segmentAvg > 0.05;
			log(String.format("Segment %d-%ds: avg=%.1f%% %s",
					startSec, startSec + 5, segmentAvg * 100,
					hasAudio ? "OK" : "LOW"));

			if (!hasAudio) silentSegments++;
		}

		assertTrue("No more than 1 segment should be silent (had " + silentSegments + ")",
				silentSegments <= 1);
	}

	/**
	 * Renders using the real-time method.
	 */
	private AudioStats renderRealTime(AudioScene<?> scene, int bufferSize, double duration, String path) {
		// Ensure parent directory exists
		File outputFile = new File(path);
		outputFile.getParentFile().mkdirs();

		WaveOutput output = new WaveOutput(() -> outputFile, 24, true);
		TemporalCellular runner = scene.runnerRealTime(
				new MultiChannelAudioOutput(output), bufferSize);

		runner.setup().get().run();

		int totalFrames = (int) (duration * SAMPLE_RATE);
		int numBuffers = totalFrames / bufferSize;
		Runnable tick = runner.tick().get();

		for (int buf = 0; buf < numBuffers; buf++) {
			tick.run();
		}

		output.write().get().run();
		return analyzeFile(path);
	}

	/**
	 * Analyzes an audio file and returns statistics.
	 */
	private AudioStats analyzeFile(String path) {
		try {
			WaveData data = WaveData.load(new File(path));
			PackedCollection audio = data.getData();

			int frames = audio.getMemLength();
			double maxAmp = 0;
			double sumSq = 0;
			int nonZeroCount = 0;

			for (int i = 0; i < frames; i++) {
				double val = audio.toDouble(i);
				double absVal = Math.abs(val);
				maxAmp = Math.max(maxAmp, absVal);
				sumSq += val * val;
				if (absVal > SILENCE_THRESHOLD) nonZeroCount++;
			}

			double rms = Math.sqrt(sumSq / frames);
			double nonZeroRatio = (double) nonZeroCount / frames;

			// Per-second analysis
			List<Double> perSecond = new ArrayList<>();
			for (int sec = 0; sec * SAMPLE_RATE < frames; sec++) {
				int start = sec * SAMPLE_RATE;
				int end = Math.min(start + SAMPLE_RATE, frames);
				int secNonZero = 0;
				for (int i = start; i < end; i++) {
					if (Math.abs(audio.toDouble(i)) > SILENCE_THRESHOLD) secNonZero++;
				}
				perSecond.add((double) secNonZero / (end - start));
			}

			return new AudioStats(frames, maxAmp, rms, nonZeroRatio, perSecond);
		} catch (Exception e) {
			log("Failed to analyze: " + e.getMessage());
			return new AudioStats(0, 0, 0, 0, new ArrayList<>());
		}
	}

	/**
	 * Audio statistics.
	 */
	private static class AudioStats {
		final int frames;
		final double maxAmplitude;
		final double rms;
		final double nonZeroRatio;
		final List<Double> perSecondNonZero;

		AudioStats(int frames, double maxAmplitude, double rms, double nonZeroRatio, List<Double> perSecond) {
			this.frames = frames;
			this.maxAmplitude = maxAmplitude;
			this.rms = rms;
			this.nonZeroRatio = nonZeroRatio;
			this.perSecondNonZero = perSecond;
		}

		@Override
		public String toString() {
			return String.format("frames=%d, maxAmp=%.4f, rms=%.4f, nonZero=%.1f%%",
					frames, maxAmplitude, rms, nonZeroRatio * 100);
		}
	}
}
