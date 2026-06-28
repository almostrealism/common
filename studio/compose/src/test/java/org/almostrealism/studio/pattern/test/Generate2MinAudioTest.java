/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.studio.pattern.test;

import org.almostrealism.audio.WaveOutput;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.heredity.TemporalCellular;
import org.almostrealism.music.pattern.PatternSystemManager;
import org.almostrealism.studio.AudioScene;
import org.almostrealism.studio.AudioSceneRealtimeRunner;
import org.almostrealism.studio.arrange.MixdownManager;
import org.almostrealism.studio.health.MultiChannelAudioOutput;
import org.almostrealism.util.TestDepth;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

/**
 * Renders two minutes of audio through the current real-time {@link AudioScene} PDSL pipeline
 * (a1 pattern generation, a2 batched render-ahead, a3 PDSL mixdown with full efx + reverb) to a
 * single listenable WAV file, and reports the wall time it took to generate — broken into the
 * one-time setup (scene load + kernel pre-warm) and the steady per-buffer generation loop — along
 * with the realtime multiple (audio seconds produced per wall-clock second).
 */
public class Generate2MinAudioTest extends AudioSceneTestBase {

	/** Tempo for the rendered arrangement. */
	private static final double BENCH_BPM = 120.0;

	/** Total measures in the arrangement (64 measures at 120 BPM is ~128 s, covering the 2 min). */
	private static final int BENCH_MEASURES = 64;

	/** Genome seed: the dense curated genome the breakdown harness renders (~1126 elements). */
	private static final long GENOME_SEED = 58;

	/** Frames per buffer. 8192 keeps the dispatch count (and tick count) low for file generation. */
	private static final int BUFFER_SIZE = 8192;

	/** Seconds of audio to generate. */
	private static final double TARGET_SECONDS = 120.0;

	/**
	 * Generates two minutes of audio to {@code results/generated-2min.wav} and logs the generation
	 * time. Asserts the render is non-silent so a fast render of silence cannot pass as success.
	 *
	 * @throws IOException if the curated scene cannot be loaded or the WAV cannot be written
	 */
	@Test(timeout = 900_000)
	@TestDepth(1)
	public void generateTwoMinutes() throws IOException {
		File library = getSamplesDir();
		File patternFactory = new File(PATTERN_FACTORY);
		if (library == null || !patternFactory.exists()) {
			log("Skipping generateTwoMinutes - need the curated library (" + SAMPLES_PATH
					+ ") and pattern factory (" + PATTERN_FACTORY + ")");
			return;
		}

		MixdownManager.enableMainFilterUp = true;
		MixdownManager.enableEfx = true;
		MixdownManager.enableEfxFilters = true;
		MixdownManager.enableReverb = true;
		MixdownManager.enablePdslMixdown = true;
		PatternSystemManager.enableWarnings = false;
		AudioSceneRealtimeRunner.renderAheadSlots = 24;

		AudioScene<?> scene = loadCuratedScene(library, patternFactory, BENCH_BPM, BENCH_MEASURES);
		applyGenome(scene, GENOME_SEED);
		log("scene elements=" + countElements(scene) + " seed=" + GENOME_SEED
				+ " bpm=" + BENCH_BPM + " measures=" + BENCH_MEASURES);

		File outFile = new File("results/generated-2min.wav");
		outFile.getParentFile().mkdirs();
		WaveOutput out = new WaveOutput(() -> outFile, 24, true);

		int ticks = (int) Math.ceil(TARGET_SECONDS * SAMPLE_RATE / BUFFER_SIZE);
		double audioSeconds = ticks * (double) BUFFER_SIZE / SAMPLE_RATE;

		TemporalCellular runner = scene.runnerRealTime(
				new MultiChannelAudioOutput(out), null, BUFFER_SIZE);
		Runnable setup = runner.setup().get();
		Runnable tick = runner.tick().get();

		try {
			long setupStart = System.nanoTime();
			setup.run();
			double setupSec = (System.nanoTime() - setupStart) / 1e9;

			long genStart = System.nanoTime();
			for (int i = 0; i < ticks; i++) {
				tick.run();
			}
			double genSec = (System.nanoTime() - genStart) / 1e9;

			out.write().get().run();

			double peak = peak(outFile.getPath());
			double totalSec = setupSec + genSec;
			log("audioSeconds=" + fmt(audioSeconds) + " ticks=" + ticks
					+ " bufferSize=" + BUFFER_SIZE);
			log("setupSeconds=" + fmt(setupSec) + " (scene load + kernel pre-warm)");
			log("generateSeconds=" + fmt(genSec)
					+ " generateRealtimeX=" + fmt(audioSeconds / genSec));
			log("totalSeconds=" + fmt(totalSec)
					+ " totalRealtimeX=" + fmt(audioSeconds / totalSec));
			log("renderedPeakAmplitude=" + fmt(peak)
					+ " file=" + outFile.getAbsolutePath());

			Assert.assertTrue("Generated audio is silent (peak=" + peak + ")", peak > 1e-3);
		} finally {
			out.reset();
			runner.reset();
		}
	}

	/**
	 * Returns the peak absolute sample over channel 0 of a rendered WAV.
	 *
	 * @param wavPath path to the rendered WAV
	 * @return the peak absolute sample in [0, 1]
	 * @throws IOException if the WAV cannot be read
	 */
	private double peak(String wavPath) throws IOException {
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
	 * Formats a value with two decimals.
	 *
	 * @param value the value to format
	 * @return the formatted value
	 */
	private String fmt(double value) {
		return String.format("%.2f", value);
	}
}
