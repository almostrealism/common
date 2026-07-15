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

import io.almostrealism.compute.ComputeRequirement;
import org.almostrealism.audio.WaveOutput;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.mem.MemoryDataArgumentMap;
import org.almostrealism.hardware.metal.MetalCommandRunner;
import org.almostrealism.hardware.metal.MetalComputeContext;
import org.almostrealism.heredity.TemporalCellular;
import org.almostrealism.music.pattern.BatchedPatternLayerRenderer;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Measures the real-time PDSL tick's hot-path wall time on a dense curated scene at both the
 * production (1024) and reference (4096) buffer sizes, and reports the pattern-render breakdown
 * (gather / eval / marshal) and the effective GPU batch size alongside, so per-buffer cost can be
 * directed by data rather than the (untrusted) prior performance docs.
 */
public class PdslHotPathBreakdownTest extends AudioSceneTestBase {

	/** Tempo for the benchmark scene (matches the cutover review render). */
	private static final double BENCH_BPM = 120.0;

	/** Total measures in the benchmark arrangement. */
	private static final int BENCH_MEASURES = 64;

	/** Ticks rendered (and discarded) after the runner is built, before measurement begins. */
	private static final int WARMUP_TICKS = 24;

	/** Steady-state ticks timed for the breakdown (a sustained sample: 200 ticks ~ 37 s at 8192). */
	private static final int PROFILE_TICKS = 200;

	/** Buffer sizes to measure: the production size (1024) and the 4096 reference. */
	private static final int[] BUFFER_SIZES = { 1024, 4096 };

	/**
	 * Runs the breakdown on the densest curated genome at each buffer size, logging the hot-path
	 * timing and pattern-render breakdown and asserting the render is non-silent (a fast render of
	 * silence is not progress).
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

		// A deeper render-ahead ring absorbs transient a2 stalls so a3 waits less (hotAwait). If
		// a2 keeps up on average, this drives the sustained tick toward the per-tick minimum.
		AudioSceneRealtimeRunner.renderAheadSlots = 24;
		log("renderAheadSlots=" + AudioSceneRealtimeRunner.renderAheadSlots);

		// Single config (full efx+reverb), per-tick logging, generous warmup: diagnose whether
		// the forward STABILIZES (warmup/compile) or DRIFTS (thermal) and find the true warm value.
		for (int bufferSize : BUFFER_SIZES) {
			measure(scene, densestSeed, bufferSize, true);
		}
	}

	/**
	 * Sweeps the argument-aggregation length threshold
	 * ({@link MemoryDataArgumentMap#maxAggregateLength}, default 1024) across the values in the
	 * {@code AR_AGG_SWEEP} system property (comma separated; default {@code 2,4,8,32,128,512,1024})
	 * on the densest curated scene at 4096 frames, logging the batching and per-tick metrics at each
	 * so the performance impact of the aggregation condition can be mapped. Lowering the threshold
	 * aggregates fewer arguments — fewer per-op aggregation copy-out host waits, but more bound
	 * buffers per kernel (risking Metal's 31-buffer limit and a native fallback). A shallow
	 * render-ahead ring keeps each iteration's setup affordable; the comparison across thresholds is
	 * the result, not the absolute ratio. Each threshold is isolated so one failure still maps the rest.
	 *
	 * @throws IOException if the curated scene cannot be loaded
	 */
	@Test(timeout = 1_080_000)
	@TestDepth(2)
	public void aggregationThresholdSweep() throws IOException {
		File library = getSamplesDir();
		File patternFactory = new File(PATTERN_FACTORY);
		if (library == null || !patternFactory.exists()) {
			log("Skipping aggregationThresholdSweep - need the curated library (" + SAMPLES_PATH
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

		AudioSceneRealtimeRunner.renderAheadSlots = 8;

		String spec = System.getProperty("AR_AGG_SWEEP", "2,4,8,32,128,512,1024");
		int original = MemoryDataArgumentMap.maxAggregateLength;
		try {
			for (String part : spec.split(",")) {
				int threshold = Integer.parseInt(part.trim());
				MemoryDataArgumentMap.maxAggregateLength = threshold;
				log("threshold=" + threshold + " aggregationSweep begin (enableAggregation="
						+ MemoryDataArgumentMap.enableArgumentAggregation + ")");
				try {
					measure(scene, densestSeed, 4096, true);
				} catch (Throwable ex) {
					log("threshold=" + threshold + " aggregationSweep failed " + ex);
				}
			}
		} finally {
			MemoryDataArgumentMap.maxAggregateLength = original;
		}
	}

	/**
	 * Returns the {@link MetalCommandRunner} for the shared Metal compute context, or {@code null}
	 * when no Metal context is active (so the commit-cause attribution is simply skipped off-Metal).
	 *
	 * @return the Metal command runner, or {@code null}
	 */
	private static MetalCommandRunner metalRunner() {
		// getComputeContexts throws when no data context matches the requirement, so
		// probe via getDataContext (which returns null off-Metal) before listing.
		return Optional.ofNullable(Hardware.getLocalHardware()
						.getDataContext(false, true, ComputeRequirement.MTL))
				.stream().flatMap(dc -> dc.getComputeContexts().stream())
				.filter(MetalComputeContext.class::isInstance)
				.map(MetalComputeContext.class::cast)
				.map(MetalComputeContext::getCommandRunner)
				.findFirst().orElse(null);
	}

	/**
	 * Builds the runner at one buffer size, warms it, then times {@link #PROFILE_TICKS} steady
	 * ticks and logs the attributed breakdown plus the realtime ratio.
	 *
	 * @param scene      the shared curated scene
	 * @param seed       the genome seed to render
	 * @param bufferSize frames per buffer
	 * @param efx        when true the full {@code mixdown_master_wet} (main+efx+reverb) is used;
	 *                   when false the simpler main-bus-only {@code mixdown_master}
	 */
	private void measure(AudioScene<?> scene, long seed, int bufferSize, boolean efx) throws IOException {
		applyGenome(scene, seed);

		File scratch = new File("results/pdsl-cutover/hotpath_scratch.wav");
		WaveOutput out = new WaveOutput(() -> scratch, 24, true);

		boolean previous = MixdownManager.enablePdslMixdown;
		boolean previousEfx = MixdownManager.enableEfx;
		boolean previousReverb = MixdownManager.enableReverb;
		MixdownManager.enablePdslMixdown = true;
		MixdownManager.enableEfx = efx;
		MixdownManager.enableReverb = efx;
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
				MetalCommandRunner.resetBatchSizeCounters();

				MetalCommandRunner mtlRunner = metalRunner();
				long baseHostCommits = mtlRunner == null ? 0 : mtlRunner.getHostCompleteCommitCount();
				long baseMaxOpenCommits = mtlRunner == null ? 0 : mtlRunner.getMaxOpenCommitCount();
				long baseBridgeCommits = mtlRunner == null ? 0 : mtlRunner.getBridgeCommitCount();
				long baseDestroyCommits = mtlRunner == null ? 0 : mtlRunner.getDestroyCommitCount();
				long baseRunnerCommits = mtlRunner == null ? 0 : mtlRunner.getCommitCount();
				Map<String, Integer> baseRequesters =
						new HashMap<>(MetalCommandRunner.hostCompleteRequesters.getCounts());

				double totalTickMs = 0;
				double[] ticks = new double[PROFILE_TICKS];
				for (int i = 0; i < PROFILE_TICKS; i++) {
					long start = System.nanoTime();
					tick.run();
					double ms = (System.nanoTime() - start) / 1e6;
					totalTickMs += ms;
					ticks[i] = ms;
				}
				double[] sorted = ticks.clone();
				Arrays.sort(sorted);
				double budget = 1000.0 * bufferSize / SAMPLE_RATE;
				double p50 = sorted[PROFILE_TICKS / 2];
				double p95 = sorted[(int) (PROFILE_TICKS * 0.95)];
				double maxMs = sorted[PROFILE_TICKS - 1];
				int overBudget = 0;
				for (double t : ticks) if (t > budget) overBudget++;
				log("buffer=" + bufferSize + " SUSTAINED ticks=" + PROFILE_TICKS
						+ " p50Ms=" + fmt(p50) + " p95Ms=" + fmt(p95) + " maxMs=" + fmt(maxMs)
						+ " p50ratio=" + fmt(p50 / budget) + " p95ratio=" + fmt(p95 / budget)
						+ " overBudgetTicks=" + overBudget + "/" + PROFILE_TICKS);

				double budgetMs = 1000.0 * bufferSize / SAMPLE_RATE;
				double tickMs = totalTickMs / PROFILE_TICKS;
				double gatherMs = BatchedPatternLayerRenderer.gatherNanos.get() / 1e6 / PROFILE_TICKS;
				double evalMs = BatchedPatternLayerRenderer.evalNanos.get() / 1e6 / PROFILE_TICKS;
				double marshalMs = BatchedPatternLayerRenderer.marshalNanos.get() / 1e6 / PROFILE_TICKS;

				log("efx=" + efx + " buffer=" + bufferSize + " budgetMs=" + fmt(budgetMs)
						+ " tickMs=" + fmt(tickMs) + " realtimeX=" + fmt(budgetMs / tickMs)
						+ " ratioToRealtime=" + fmt(tickMs / budgetMs));
				log("buffer=" + bufferSize + " patternRender gatherMs=" + fmt(gatherMs)
						+ " evalMs=" + fmt(evalMs)
						+ " marshalMs=" + fmt(marshalMs)
						+ " totalMs=" + fmt(gatherMs + evalMs + marshalMs));
				log("buffer=" + bufferSize
						+ " batchedDispatchCount=" + BatchedPatternLayerRenderer.batchedDispatchCount.get()
						+ " fallbackCount=" + BatchedPatternLayerRenderer.fallbackCount.get());

				long metalDispatches = MetalCommandRunner.totalDispatchCount.get();
				long metalCommits = MetalCommandRunner.totalCommitCount.get();
				log("buffer=" + bufferSize + " METAL dispatches=" + metalDispatches
						+ " commits=" + metalCommits
						+ " dispatchesPerTick=" + fmt(metalDispatches / (double) PROFILE_TICKS)
						+ " commitsPerTick=" + fmt(metalCommits / (double) PROFILE_TICKS)
						+ " meanDispatchesPerCommit=" + fmt(MetalCommandRunner.meanBatchSize()));

				if (mtlRunner != null) {
					long hostCommits = mtlRunner.getHostCompleteCommitCount() - baseHostCommits;
					long maxOpenCommits = mtlRunner.getMaxOpenCommitCount() - baseMaxOpenCommits;
					long bridgeCommits = mtlRunner.getBridgeCommitCount() - baseBridgeCommits;
					long destroyCommits = mtlRunner.getDestroyCommitCount() - baseDestroyCommits;
					long runnerCommits = mtlRunner.getCommitCount() - baseRunnerCommits;
					log("buffer=" + bufferSize + " commitCause runnerCommits=" + runnerCommits
							+ " hostCompleteCommits=" + hostCommits
							+ " maxOpenCommits=" + maxOpenCommits
							+ " bridgeCommits=" + bridgeCommits
							+ " destroyCommits=" + destroyCommits
							+ " hostCompletePerTick=" + fmt(hostCommits / (double) PROFILE_TICKS)
							+ " bridgePerTick=" + fmt(bridgeCommits / (double) PROFILE_TICKS));

					Map<String, Integer> endRequesters =
							new HashMap<>(MetalCommandRunner.hostCompleteRequesters.getCounts());
					endRequesters.entrySet().stream()
							.map(e -> Map.entry(e.getKey(),
									e.getValue() - baseRequesters.getOrDefault(e.getKey(), 0)))
							.filter(e -> e.getValue() > 0)
							.sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
							.limit(15)
							.forEach(e -> log("buffer=" + bufferSize
									+ " requesterWaitsPerTick=" + fmt(e.getValue() / (double) PROFILE_TICKS)
									+ " requesterWaits=" + e.getValue()
									+ " requester=" + e.getKey()));
				}

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
			MixdownManager.enableEfx = previousEfx;
			MixdownManager.enableReverb = previousReverb;
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
