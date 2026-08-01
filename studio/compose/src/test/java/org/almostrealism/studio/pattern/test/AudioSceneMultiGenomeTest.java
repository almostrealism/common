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
import org.almostrealism.music.arrange.AudioSceneContext;
import org.almostrealism.studio.arrange.MixdownManager;
import org.almostrealism.music.data.ChannelInfo;
import org.almostrealism.studio.health.HealthComputationAdapter;
import org.almostrealism.studio.health.StableDurationHealthComputation;
import org.almostrealism.music.pattern.PatternSystemManager;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.heredity.TemporalCellular;
import org.almostrealism.util.TestDepth;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.util.List;
import java.util.function.Supplier;

/**
 * Multi-genome evaluation test for OOM investigation.
 *
 * <p>Directly exercises {@link org.almostrealism.music.pattern.PatternFeatures#render}
 * across multiple genome evaluations using real audio samples, without the full
 * effects pipeline. This isolates the per-note evaluation path that is the
 * suspected OOM root cause in {@link org.almostrealism.studio.optimize.AudioSceneOptimizer}.</p>
 *
 * <p>Run with JMX monitoring to identify per-genome object accumulation:</p>
 * <pre>{@code
 * mcp__ar-test-runner__start_test_run
 *   module: "compose"
 *   test_classes: ["AudioSceneMultiGenomeTest"]
 *   jmx_monitoring: true
 *   jvm_args: ["-Xmx4g"]
 *   timeout_minutes: 15
 * }</pre>
 *
 * @see org.almostrealism.studio.optimize.AudioScenePopulation
 * @see org.almostrealism.music.pattern.PatternFeatures
 */
public class AudioSceneMultiGenomeTest extends AudioSceneTestBase {

	/** Number of genomes to evaluate in sequence. */
	private static final int NUM_GENOMES = 100;

	/** Buffer size for each frame-range render. */
	private static final int BUFFER_SIZE = 1024;

	/** Number of buffers to render per genome (covers first 4 seconds). */
	private static final int BUFFERS_PER_GENOME = (int) (RENDER_SECONDS * SAMPLE_RATE / BUFFER_SIZE);

	/** Interval between JMX-friendly pauses (every N genomes). */
	private static final int JMX_PAUSE_INTERVAL = 20;

	/** Duration of each JMX pause in milliseconds. */
	private static final int JMX_PAUSE_MS = 3000;

	/** Initial pause to allow JMX attachment (milliseconds). */
	private static final int INITIAL_PAUSE_MS = 5000;

	/**
	 * Returns a formatted string with current heap memory usage.
	 *
	 * @param label description of the measurement point
	 * @return formatted memory report string
	 */
	private String heapReport(String label) {
		Runtime rt = Runtime.getRuntime();
		long used = rt.totalMemory() - rt.freeMemory();
		long total = rt.totalMemory();
		long max = rt.maxMemory();
		return String.format("[HEAP %s] used=%dMB total=%dMB max=%dMB",
				label, used / (1024 * 1024), total / (1024 * 1024), max / (1024 * 1024));
	}

	/**
	 * Evaluates multiple genomes by directly calling PatternSystemManager.sum()
	 * in a buffer loop, bypassing the effects pipeline.
	 *
	 * <p>This mirrors what happens inside
	 * {@link org.almostrealism.music.pattern.PatternAudioBuffer#renderBatch}
	 * when the setup phase calls {@code renderNow()}: the PatternSystemManager
	 * evaluates all notes in the current genome's pattern configuration,
	 * creating expression trees, compiling native code, and allocating
	 * {@link PackedCollection} results.</p>
	 *
	 * <p>Between genomes, the scene is re-assigned a new genome (changing the
	 * pattern configuration). Any Java heap objects that accumulate across
	 * genome evaluations are the OOM root cause.</p>
	 *
	 * <p>Memory is reported after every genome and a GC is forced periodically
	 * to distinguish true retention from transient garbage. Pauses are inserted
	 * every {@value #JMX_PAUSE_INTERVAL} genomes to allow JMX histogram snapshots.</p>
	 */
	@Test(timeout = 600_000)
	@TestDepth(2)
	public void multiGenomePatternRender() throws InterruptedException {
		File samplesDir = new File(SAMPLES_PATH);
		if (!samplesDir.exists()) {
			log("Skipping test - Samples directory not found: " + samplesDir.getAbsolutePath());
			return;
		}

		MixdownManager.enableMainFilterUp = false;
		MixdownManager.enableEfxFilters = false;
		MixdownManager.enableEfx = false;
		PatternSystemManager.enableWarnings = false;

		AudioScene<?> scene = createBaselineScene(samplesDir, 2);

		long initialSeed = findWorkingGenomeSeed(scene, samplesDir);
		if (initialSeed < 0) {
			log("No working genome found - skipping test");
			return;
		}

		log("=== Multi-Genome Pattern Render ===");
		log("Genomes: " + NUM_GENOMES + ", buffers/genome: " + BUFFERS_PER_GENOME);

		scene = createBaselineScene(samplesDir, 2);
		PatternSystemManager patterns = scene.getPatternManager();
		patterns.setTuning(scene.getTuning());

		ChannelInfo channel = new ChannelInfo(0, ChannelInfo.Voicing.MAIN, ChannelInfo.StereoChannel.LEFT);
		PackedCollection buffer = new PackedCollection(BUFFER_SIZE);

		System.gc();
		log(heapReport("baseline"));
		log("Pausing " + INITIAL_PAUSE_MS + "ms for JMX attachment...");
		Thread.sleep(INITIAL_PAUSE_MS);

		long[] usedAfterGc = new long[NUM_GENOMES];

		for (int g = 0; g < NUM_GENOMES; g++) {
			long seed = initialSeed + g;
			applyGenome(scene, seed);

			int totalElements = countElements(scene);
			long start = System.currentTimeMillis();

			patterns.init();

			int buffersWithAudio = 0;

			for (int buf = 0; buf < BUFFERS_PER_GENOME; buf++) {
				buffer.clear();

				int startFrame = buf * BUFFER_SIZE;
				AudioScene<?> finalScene = scene;
				Supplier<AudioSceneContext> ctx = () -> {
					AudioSceneContext c = finalScene.getContext(List.of(channel));
					c.setDestination(buffer);
					return c;
				};

				int frame = startFrame;
				Supplier<Runnable> renderOp = patterns.sum(ctx, channel, () -> frame, BUFFER_SIZE);
				renderOp.get().run();

				double maxAmp = 0;
				for (int i = 0; i < BUFFER_SIZE; i++) {
					double val = Math.abs(buffer.valueAt(i));
					if (val > maxAmp) maxAmp = val;
				}
				if (maxAmp > 0.001) buffersWithAudio++;
			}

			long elapsed = System.currentTimeMillis() - start;

			System.gc();
			Runtime rt = Runtime.getRuntime();
			usedAfterGc[g] = rt.totalMemory() - rt.freeMemory();

			log("Genome " + g + " (seed=" + seed + "): elements=" + totalElements +
					" buffersWithAudio=" + buffersWithAudio + "/" + BUFFERS_PER_GENOME +
					" time=" + elapsed + "ms " +
					heapReport("g" + g));

			if (g > 0 && g % JMX_PAUSE_INTERVAL == 0) {
				log("=== JMX pause at genome " + g + " (" + JMX_PAUSE_MS + "ms) ===");
				Thread.sleep(JMX_PAUSE_MS);
			}
		}

		buffer.destroy();

		log("=== All " + NUM_GENOMES + " genomes evaluated ===");
		log("=== Memory trend (used MB after GC) ===");
		StringBuilder trend = new StringBuilder();
		for (int g = 0; g < NUM_GENOMES; g++) {
			if (g > 0) trend.append(", ");
			trend.append(usedAfterGc[g] / (1024 * 1024));
		}
		log(trend.toString());

		long firstUsed = usedAfterGc[0];
		long lastUsed = usedAfterGc[NUM_GENOMES - 1];
		long growth = lastUsed - firstUsed;
		log(String.format("Growth: %dMB (first=%dMB, last=%dMB)",
				growth / (1024 * 1024),
				firstUsed / (1024 * 1024),
				lastUsed / (1024 * 1024)));
	}

	/** Number of genomes for the full runner lifecycle test. */
	private static final int RUNNER_GENOMES = 50;

	/** Health evaluation duration in seconds (short to keep test fast). */
	private static final int HEALTH_DURATION_SEC = 4;

	/**
	 * Evaluates multiple genomes through the full runner lifecycle:
	 * {@code scene.runnerRealTime()} to {@link TemporalCellular} to
	 * {@link StableDurationHealthComputation} to compile to setup to tick loop.
	 *
	 * <p>This exercises code paths NOT covered by {@link #multiGenomePatternRender()}:
	 * the effects pipeline (MixdownManager, filters), TemporalRunner compilation,
	 * Loop/OperationList compilation, and WaveOutput accumulation. These are the
	 * remaining suspects for the OOM after Session 3 cleared the pattern evaluation
	 * path.</p>
	 *
	 * <p>Run with JMX monitoring:</p>
	 * <pre>{@code
	 * mcp__ar-test-runner__start_test_run
	 *   module: "compose"
	 *   test_classes: ["AudioSceneMultiGenomeTest"]
	 *   test_methods: [{"class": "AudioSceneMultiGenomeTest", "method": "multiGenomeFullRunner"}]
	 *   jmx_monitoring: true
	 *   jvm_args: ["-Xmx1g"]
	 *   depth: 2
	 *   timeout_minutes: 15
	 * }</pre>
	 */
	@Test(timeout = 600_000)
	@TestDepth(2)
	public void multiGenomeFullRunner() throws InterruptedException {
		File samplesDir = new File(SAMPLES_PATH);
		if (!samplesDir.exists()) {
			log("Skipping test - Samples directory not found: " + samplesDir.getAbsolutePath());
			return;
		}

		MixdownManager.enableMainFilterUp = false;
		MixdownManager.enableEfxFilters = false;
		MixdownManager.enableEfx = false;
		PatternSystemManager.enableWarnings = false;

		HealthComputationAdapter.setStandardDuration(HEALTH_DURATION_SEC);

		AudioScene<?> scene = createBaselineScene(samplesDir, 2);

		long initialSeed = findWorkingGenomeSeed(scene, samplesDir);
		if (initialSeed < 0) {
			log("No working genome found - skipping test");
			return;
		}

		scene = createBaselineScene(samplesDir, 2);

		StableDurationHealthComputation health =
				new StableDurationHealthComputation(2, true);

		applyGenome(scene, initialSeed);
		TemporalCellular temporal = scene.runnerRealTime(
				health.getOutput(), null, health.getBatchSize());

		log("=== Multi-Genome Full Runner ===");
		log("Genomes: " + RUNNER_GENOMES + ", health duration: " + HEALTH_DURATION_SEC + "s");

		System.gc();
		log(heapReport("baseline"));
		log("Pausing " + INITIAL_PAUSE_MS + "ms for JMX attachment...");
		Thread.sleep(INITIAL_PAUSE_MS);

		long[] usedAfterGc = new long[RUNNER_GENOMES];

		for (int g = 0; g < RUNNER_GENOMES; g++) {
			long seed = initialSeed + g;
			applyGenome(scene, seed);
			temporal.reset();

			int totalElements = countElements(scene);
			long start = System.currentTimeMillis();

			health.setTarget(temporal);
			health.computeHealth();
			health.reset();

			long elapsed = System.currentTimeMillis() - start;

			System.gc();
			Runtime rt = Runtime.getRuntime();
			usedAfterGc[g] = rt.totalMemory() - rt.freeMemory();

			log("Genome " + g + " (seed=" + seed + "): elements=" + totalElements +
					" time=" + elapsed + "ms " +
					heapReport("g" + g));

			if (g > 0 && g % JMX_PAUSE_INTERVAL == 0) {
				log("=== JMX pause at genome " + g + " (" + JMX_PAUSE_MS + "ms) ===");
				Thread.sleep(JMX_PAUSE_MS);
			}
		}

		log("=== All " + RUNNER_GENOMES + " genomes evaluated ===");
		log("=== Memory trend (used MB after GC) ===");
		StringBuilder trend = new StringBuilder();
		for (int g = 0; g < RUNNER_GENOMES; g++) {
			if (g > 0) trend.append(", ");
			trend.append(usedAfterGc[g] / (1024 * 1024));
		}
		log(trend.toString());

		long firstUsed = usedAfterGc[0];
		long lastUsed = usedAfterGc[RUNNER_GENOMES - 1];
		long growth = lastUsed - firstUsed;
		long growthMb = growth / (1024 * 1024);
		log(String.format("Growth: %dMB (first=%dMB, last=%dMB)",
				growthMb,
				firstUsed / (1024 * 1024),
				lastUsed / (1024 * 1024)));

		long maxAllowedGrowthMb = 256;
		Assert.assertTrue(
				"Java heap growth across " + RUNNER_GENOMES +
						" genome evaluations should stay under " + maxAllowedGrowthMb +
						"MB (was " + growthMb + "MB)",
				growthMb < maxAllowedGrowthMb);
	}
}
