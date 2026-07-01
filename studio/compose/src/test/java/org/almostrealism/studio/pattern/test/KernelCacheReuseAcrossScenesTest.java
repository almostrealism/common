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
import org.almostrealism.music.pattern.BatchedPatternLayerRenderer;
import org.almostrealism.music.pattern.PatternSystemManager;
import org.almostrealism.studio.AudioScene;
import org.almostrealism.studio.AudioSceneRealtimeRunner;
import org.almostrealism.studio.arrange.MixdownManager;
import org.almostrealism.studio.health.MultiChannelAudioOutput;
import org.almostrealism.util.TestDepth;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

/**
 * Answers whether the JVM-wide instruction cache ({@code DefaultComputer.instructionsCache},
 * keyed by computation signature) lets a <em>second</em> {@link AudioScene} reuse the GPU kernels
 * compiled by the first — i.e. whether the (large) one-time kernel-compilation penalty is paid
 * <em>once per JVM</em> or <em>once per scene</em>.
 *
 * <p>It reproduces the scenario directly: build a scene, render a fixed number of buffers (which
 * forces kernel compilation on first encounter, since pre-warm is disabled here), reset it, then
 * build and render a fresh scene with identical configuration — twice in one JVM. Kernel
 * compilation dominates the first scene's render wall time; if the cache covers cross-scene reuse,
 * later scenes render dramatically faster, which the per-scene render wall times reveal.</p>
 */
public class KernelCacheReuseAcrossScenesTest extends AudioSceneTestBase {

	/** Tempo for the probe arrangement. */
	private static final double BPM = 120.0;

	/** Measures in the arrangement. */
	private static final int MEASURES = 64;

	/** Genome seed (the dense curated genome the other harnesses use). */
	private static final long SEED = 58;

	/** Frames per buffer. */
	private static final int BUFFER = 8192;

	/** Buffers rendered per scene — enough to force a representative set of kernel compiles. */
	private static final int RENDER_TICKS = 180;

	/**
	 * Number of independent scenes built and rendered in one JVM. Two is sufficient to measure
	 * cross-scene kernel reuse: scene 0 compiles cold and scene 1 reveals, via its render wall
	 * time, whether those kernels are reused or recompiled.
	 */
	private static final int SCENES = 2;

	/**
	 * Builds and renders {@link #SCENES} identically-configured scenes in one JVM, logging the
	 * per-scene setup, first-tick, and render-loop wall times plus the distinct-kernel compile
	 * count, so the second and third scenes' times reveal how much the first scene's compiled
	 * kernels are reused.
	 *
	 * @throws IOException if the curated scene cannot be loaded
	 */
	@Test(timeout = 1_500_000)
	@TestDepth(1)
	public void reuseAcrossScenes() throws IOException {
		File library = getSamplesDir();
		File patternFactory = new File(PATTERN_FACTORY);
		if (library == null || !patternFactory.exists()) {
			log("Skipping reuseAcrossScenes - need the curated library (" + SAMPLES_PATH
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
		// Disable the full-arrangement pre-warm so kernels compile lazily inside the timed render
		// loop, making each scene's compile cost visible in its render wall time.
		AudioSceneRealtimeRunner.preWarmMaxSeconds = 0;

		for (int s = 0; s < SCENES; s++) {
			BatchedPatternLayerRenderer.resetCounters();

			AudioScene<?> scene = loadCuratedScene(library, patternFactory, BPM, MEASURES);
			applyGenome(scene, SEED);

			File outFile = new File("results/cache-probe-scene" + s + ".wav");
			outFile.getParentFile().mkdirs();
			WaveOutput out = new WaveOutput(() -> outFile, 24, true);

			TemporalCellular runner = scene.runnerRealTime(
					new MultiChannelAudioOutput(out), null, BUFFER);
			Runnable setup = runner.setup().get();
			Runnable tick = runner.tick().get();

			try {
				long t0 = System.nanoTime();
				setup.run();
				long t1 = System.nanoTime();
				tick.run();
				long t2 = System.nanoTime();
				for (int i = 1; i < RENDER_TICKS; i++) {
					tick.run();
				}
				long t3 = System.nanoTime();

				log("scene=" + s
						+ " setupMs=" + ms(t1 - t0)
						+ " firstTickMs=" + ms(t2 - t1)
						+ " renderLoopMs=" + ms(t3 - t2)
						+ " renderTotalMs=" + ms(t3 - t1)
						+ " ticks=" + RENDER_TICKS);
			} finally {
				out.reset();
				runner.reset();
			}
		}
	}

	/**
	 * Formats a nanosecond delta as milliseconds with one decimal.
	 *
	 * @param nanos the elapsed nanoseconds
	 * @return the elapsed time in milliseconds
	 */
	private String ms(long nanos) {
		return String.format("%.1f", nanos / 1e6);
	}
}
