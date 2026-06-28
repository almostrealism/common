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
import org.almostrealism.music.pattern.BatchedPatternLayerRenderer;
import org.almostrealism.music.pattern.NoteAudioCache;
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
 * Attributes the real-time PDSL tick's hot-path wall time on a dense curated scene into its
 * components — the a2 wait ({@code awaitSlot}) versus the a3 mixdown forward
 * ({@code compiled.forward}) — and reports the full a2 producer breakdown alongside, at both the
 * preferred (4096) and default (8192) buffer sizes.
 *
 * <p>This is the Phase-1 decomposition measurement for the run-ahead-streams plan: it answers
 * whether the per-buffer cost is the a3 forward, the a2 wait, or the a2 placement, so the fix is
 * directed by data rather than the (untrusted) prior performance docs. The {@code awaitSlot}
 * versus {@code forward} split (read from {@link AudioSceneRealtimeRunner#hotAwaitNanos} /
 * {@link AudioSceneRealtimeRunner#hotForwardNanos}) tells whether a3 is waiting on a2 or doing
 * its own work; the {@link NoteAudioCache} hit/miss counts confirm synthesis is render-once.</p>
 */
public class PdslHotPathBreakdownTest extends AudioSceneTestBase {

	/** Tempo for the benchmark scene (matches the cutover review render). */
	private static final double BENCH_BPM = 120.0;

	/** Total measures in the benchmark arrangement. */
	private static final int BENCH_MEASURES = 64;

	/** Ticks rendered (and discarded) after the runner is built, before measurement begins. */
	private static final int WARMUP_TICKS = 8;

	/** Steady-state ticks timed for the breakdown. */
	private static final int PROFILE_TICKS = 8;

	/** Buffer sizes to measure: the preferred (4096) and the default (8192). */
	private static final int[] BUFFER_SIZES = { 4096, 8192 };

	/**
	 * Runs the decomposition on the densest curated genome at each buffer size, logging the
	 * a2/a3 attribution and asserting the render is non-silent (a fast render of silence is not
	 * progress).
	 *
	 * @throws IOException if the curated scene cannot be loaded
	 */
	@Test(timeout = 1_080_000)
	@TestDepth(2)
	public void hotPathBreakdown() throws IOException {
		File library = getSamplesDir();
		File patternFactory = new File(PATTERN_FACTORY);
		if (library == null || !patternFactory.exists()) {
			log("Skipping hotPathBreakdown - need the curated library (" + SAMPLES_PATH
					+ ") and pattern factory (" + PATTERN_FACTORY + ")");
			return;
		}

		MixdownManager.enableMainFilterUp = true;
		MixdownManager.enableEfx = true;
		MixdownManager.enableEfxFilters = true;
		MixdownManager.enableReverb = true;
		PatternSystemManager.enableWarnings = false;

		AudioScene<?> scene = loadCuratedScene(library, patternFactory, BENCH_BPM, BENCH_MEASURES);

		long densestSeed = -1;
		int densestElements = -1;
		for (int attempt = 0; attempt < MAX_GENOME_ATTEMPTS; attempt++) {
			long seed = 42 + attempt;
			applyGenome(scene, seed);
			int elements = countElements(scene);
			if (elements > densestElements) {
				densestElements = elements;
				densestSeed = seed;
			}
		}
		Assert.assertTrue("No viable genome found", densestSeed >= 0 && densestElements > 0);
		log("densestSeed=" + densestSeed + " densestElements=" + densestElements);

		for (int bufferSize : BUFFER_SIZES) {
			measure(scene, densestSeed, bufferSize);
		}
	}

	/**
	 * Builds the runner at one buffer size, warms it, then times {@link #PROFILE_TICKS} steady
	 * ticks and logs the attributed breakdown plus the realtime ratio.
	 *
	 * @param scene      the shared curated scene
	 * @param seed       the genome seed to render
	 * @param bufferSize frames per buffer
	 */
	private void measure(AudioScene<?> scene, long seed, int bufferSize) throws IOException {
		applyGenome(scene, seed);

		File scratch = new File("results/pdsl-cutover/hotpath_scratch.wav");
		WaveOutput out = new WaveOutput(() -> scratch, 24, true);

		boolean previous = MixdownManager.enablePdslMixdown;
		MixdownManager.enablePdslMixdown = true;
		try {
			TemporalCellular runner = scene.runnerRealTime(
					new MultiChannelAudioOutput(out), null, bufferSize);
			Runnable setup = runner.setup().get();
			Runnable tick = runner.tick().get();

			try {
				setup.run();
				for (int i = 0; i < WARMUP_TICKS; i++) {
					tick.run();
				}

				BatchedPatternLayerRenderer.resetCounters();
				NoteAudioCache.resetCounters();
				AudioSceneRealtimeRunner.resetHotPathTimers();

				double totalTickMs = 0;
				for (int i = 0; i < PROFILE_TICKS; i++) {
					long start = System.nanoTime();
					tick.run();
					totalTickMs += (System.nanoTime() - start) / 1e6;
				}

				double budgetMs = 1000.0 * bufferSize / SAMPLE_RATE;
				double tickMs = totalTickMs / PROFILE_TICKS;
				double awaitMs = AudioSceneRealtimeRunner.hotAwaitNanos.get() / 1e6 / PROFILE_TICKS;
				double forwardMs = AudioSceneRealtimeRunner.hotForwardNanos.get() / 1e6 / PROFILE_TICKS;
				double a2GatherMs = BatchedPatternLayerRenderer.gatherNanos.get() / 1e6 / PROFILE_TICKS;
				double a2EvalMs = BatchedPatternLayerRenderer.evalNanos.get() / 1e6 / PROFILE_TICKS;
				double a2PerNoteMs = BatchedPatternLayerRenderer.perNoteNanos.get() / 1e6 / PROFILE_TICKS;
				double a2MarshalMs = BatchedPatternLayerRenderer.marshalNanos.get() / 1e6 / PROFILE_TICKS;

				log("buffer=" + bufferSize + " budgetMs=" + fmt(budgetMs)
						+ " tickMs=" + fmt(tickMs) + " realtimeX=" + fmt(budgetMs / tickMs)
						+ " ratioToRealtime=" + fmt(tickMs / budgetMs));
				log("buffer=" + bufferSize + " HOTPATH hotAwaitMs=" + fmt(awaitMs)
						+ " hotForwardMs=" + fmt(forwardMs));
				log("buffer=" + bufferSize + " A2 gatherMs=" + fmt(a2GatherMs)
						+ " evalMs=" + fmt(a2EvalMs) + " perNoteMs=" + fmt(a2PerNoteMs)
						+ " marshalMs=" + fmt(a2MarshalMs)
						+ " a2TotalMs=" + fmt(a2GatherMs + a2EvalMs + a2PerNoteMs + a2MarshalMs));
				log("buffer=" + bufferSize + " cacheHits=" + NoteAudioCache.cacheHits.get()
						+ " cacheMisses=" + NoteAudioCache.cacheMisses.get()
						+ " batchedDispatchCount=" + BatchedPatternLayerRenderer.batchedDispatchCount.get()
						+ " fallbackCount=" + BatchedPatternLayerRenderer.fallbackCount.get());

				out.write().get().run();
				double peak = peakAmplitude(scratch.getPath());
				log("buffer=" + bufferSize + " renderedPeakAmplitude=" + fmt(peak));
				Assert.assertTrue("PDSL render produced silence at buffer=" + bufferSize
						+ " (peak=" + peak + ")", peak > 1e-3);
			} finally {
				out.reset();
				runner.reset();
			}
		} finally {
			MixdownManager.enablePdslMixdown = previous;
		}
	}

	/**
	 * Returns the peak absolute sample over channel 0 of a rendered WAV.
	 *
	 * @param wavPath path to the rendered WAV
	 * @return the peak absolute sample in [0, 1]
	 * @throws IOException if the WAV cannot be read
	 */
	private double peakAmplitude(String wavPath) throws IOException {
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
	 * Formats a millisecond or ratio value with two decimals.
	 *
	 * @param value the value to format
	 * @return the formatted value
	 */
	private String fmt(double value) {
		return String.format("%.2f", value);
	}
}
