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
 * Renders {@link #TARGET_SECONDS} of audio through the current real-time {@link AudioScene} PDSL
 * pipeline (pattern generation, batched render-ahead, PDSL mixdown with full efx + reverb) to a
 * single listenable WAV file, and reports the wall time it took to generate — broken into the
 * one-time setup (scene load + kernel pre-warm) and the steady per-buffer generation loop — along
 * with the realtime multiple (audio seconds produced per wall-clock second).
 */
public class GenerateAudioFileTest extends AudioSceneTestBase {

	/** Tempo for the rendered arrangement. */
	private static final double BENCH_BPM = 120.0;

	/** Total measures in the arrangement (64 measures at 120 BPM is ~128 s of audio). */
	private static final int BENCH_MEASURES = 64;

	/** Genome seed: the dense curated genome the breakdown harness renders (~1126 elements). */
	private static final long GENOME_SEED = 58;

	/**
	 * Frames per buffer — the production default, so listening evaluations of this
	 * render exercise the same buffer-dependent behaviour (automation ramp rate, drift
	 * step size, ring geometry) as the real-time app. Offline generation does not need
	 * real-time headroom, so the smaller buffer only lengthens the render.
	 */
	private static final int BUFFER_SIZE = AudioScene.DEFAULT_REALTIME_BUFFER_SIZE;

	/** Seconds of audio to generate. */
	private static final double TARGET_SECONDS = 120.0;

	/**
	 * Generates audio to {@code results/generated-audio.wav} and logs the generation time. Asserts
	 * the render is non-silent so a fast render of silence cannot pass as success.
	 *
	 * @throws IOException if the curated scene cannot be loaded or the WAV cannot be written
	 */
	@Test(timeout = 900_000)
	@TestDepth(1)
	public void generateAudioFile() throws IOException {
		File library = getSamplesDir();
		File patternFactory = new File(PATTERN_FACTORY);
		if (library == null || !patternFactory.exists()) {
			log("Skipping generateAudioFile - need the curated library (" + SAMPLES_PATH
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

		File outFile = new File("results/generated-audio.wav");
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

			double peak = peakAmplitude(outFile.getPath());
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
	 * Formats a value with two decimals.
	 *
	 * @param value the value to format
	 * @return the formatted value
	 */
	private String fmt(double value) {
		return String.format("%.2f", value);
	}
}
