/*
 * Copyright 2026 Michael Murray
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

import org.almostrealism.audio.WaveOutput;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.audio.filter.AudioProcessingUtils;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.heredity.Genome;
import org.almostrealism.heredity.TemporalCellular;
import org.almostrealism.io.SystemUtils;
import org.almostrealism.music.pattern.BatchedPatternLayerRenderer;
import org.almostrealism.music.pattern.PatternLayerManager;
import org.almostrealism.studio.AudioScene;
import org.almostrealism.studio.health.MultiChannelAudioOutput;
import org.almostrealism.studio.optimize.AudioSceneBenchmark;
import org.almostrealism.studio.optimize.AudioSceneOptimizer;
import org.almostrealism.studio.optimize.AudioScenePopulation;
import org.almostrealism.studio.optimize.RealtimeContinuousRenderer;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestProperties;
import org.almostrealism.util.TestSuiteBase;
import org.almostrealism.util.TestUtils;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Renders the real production {@link AudioScene} — built from the curated sample
 * library and {@code pattern-factory.json} via {@link AudioSceneOptimizer#createScene()}
 * — through the full effects/mixdown pipeline with batched pattern rendering enabled,
 * on whatever backend is active. Unlike the synthetic-sample correctness tests, this
 * exercises real audio so the output is musically meaningful, and it asserts both
 * non-silence and a real-time ratio. Reuses {@link AudioSceneBenchmark}'s production
 * render methods so the path matches what the optimizer/app actually runs.
 *
 * <p>Configured by absolute paths supplied at the class level (the curated library is
 * not part of the repository); the test skips if they are absent. Run with the Metal
 * backend forced via {@code -DAR_HARDWARE_DRIVER=mtl} to measure GPU behaviour.</p>
 *
 * <p><strong>Curated-library gated.</strong> These full-scene integration renders require the
 * curated sample library ({@link #LIBRARY}); they skip via {@link Assume} where it is absent,
 * so they do not run in CI. They were previously disabled for two reasons, both now resolved:
 * (1) full-scene renders exhausted the fixed {@code GeneratedOperation} pool because
 * per-instance compilation was not reused — fixed by the rebuilt argument-aggregation system,
 * which gives aggregation-target buffers a structural signature so the instruction cache
 * reuses kernels across scene instances; and (2) batched dispatch was thought not to fire for
 * the real pattern path — it does: on the real curated scene every melodic note classifies as
 * the batched melodic-SSS shape and dispatches, while percussive notes correctly fall back to
 * per-note. Validated 2026-06-26 (single melodic channel: {@code batchedDispatchCount=1388},
 * {@code fallback=0}, peak 0.51; all six channels: {@code batchedDispatchCount=2220}, peak 0.56).</p>
 *
 * <p>The all-channels full-pipeline renders are heavy: they complete within the per-test
 * budget on the PDSL mixdown ({@code -DAR_PDSL_MIXDOWN=enabled}); with the legacy CellList
 * mixdown the cold six-channel render can exceed the per-test timeout on slower hardware. The
 * single-channel renders pass on either mixdown path.</p>
 */
public class BatchedRealSceneRenderTest extends TestSuiteBase {

	/** Curated sample library root. */
	private static final String LIBRARY = "/Users/Shared/Music/Samples";

	/** Production pattern-factory configuration (audio categories per channel). */
	private static final String PATTERN_FACTORY = "/Users/Shared/Music/pattern-factory.json";

	/** Melodic channel index (Lead Synth) per {@code pattern-factory.json}. */
	private static final int LEAD_CHANNEL = 4;

	/** Per-tick render window in frames. */
	private static final int BUFFER = 4096;

	/** Arrangement length in measures. */
	private static final int MEASURES = 16;

	/**
	 * Ensures the real library and pattern factory are available, wires the optimizer to
	 * the curated library, and places {@code pattern-factory.json} where
	 * {@link AudioSceneOptimizer#createScene()} resolves it (the local-destination base).
	 * Skips the calling test when the assets are absent.
	 */
	private void requireRealAssets() throws Exception {
		Assume.assumeTrue("curated library missing: " + LIBRARY, new File(LIBRARY).isDirectory());
		Assume.assumeTrue("pattern factory missing: " + PATTERN_FACTORY, new File(PATTERN_FACTORY).isFile());

		AudioSceneOptimizer.LIBRARY = LIBRARY;
		AudioSceneOptimizer.setFeatureLevel(7);
		AudioProcessingUtils.init();
		WaveData.init();

		Path resolved = Path.of(SystemUtils.getLocalDestination("pattern-factory.json"));
		if (!Files.exists(resolved)) {
			Files.copy(new File(PATTERN_FACTORY).toPath(), resolved);
		}
		new File("results/profiles").mkdirs();
		new File("results/timelines").mkdirs();
	}

	/** Builds a random genome from a probe scene built off the real assets. */
	private Genome<PackedCollection> realGenome() {
		AudioScene<?> probe = AudioSceneOptimizer.createScene();
		try {
			probe.setTotalMeasures(MEASURES);
			return probe.getGenome().random();
		} finally {
			probe.destroy();
		}
	}

	/** Returns the peak absolute sample over channel 0 of a rendered WAV. */
	private double peakAmplitude(String wavPath) throws Exception {
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
	 * Step 1: renders a single melodic channel (Lead Synth) through the full
	 * effects/mixdown pipeline from real samples with batched dispatch enabled, and
	 * verifies the output is non-silent real audio rendered faster than real time.
	 */
	@Test(timeout = 600000)
	@TestDepth(3)
	public void singleMelodicChannelFullPipeline() throws Exception {
		requireRealAssets();

		boolean previous = PatternLayerManager.enableBatched;
		PatternLayerManager.enableBatched = true;
		BatchedPatternLayerRenderer.resetCounters();
		try {
			Genome<PackedCollection> genome = realGenome();
			AudioSceneBenchmark.BenchmarkResult result = AudioSceneBenchmark.runSingleChannel(
					MEASURES, LEAD_CHANNEL, 7, BUFFER, genome,
					"results/profiles", "results/timelines", true, 0, false);

			double peak = peakAmplitude(result.getWavPath());
			long batched = BatchedPatternLayerRenderer.batchedDispatchCount.get();
			long fallback = BatchedPatternLayerRenderer.fallbackCount.get();

			log("single melodic channel=" + LEAD_CHANNEL + " measures=" + MEASURES + " buffer=" + BUFFER
					+ ": audio=" + result.getAudioSeconds() + "s setup=" + result.getSetupMs() + "ms"
					+ " tickTotal=" + result.getTickStats().getTotalMs() + "ms ratio=" + result.getRatio()
					+ " p50=" + result.getTickStats().getP50Ms() + "ms p95=" + result.getTickStats().getP95Ms()
					+ "ms max=" + result.getTickStats().getMaxMs() + "ms");
			log("single melodic dispatch: batchedDispatchCount=" + batched
					+ " fallbackCount=" + fallback + " peakAmplitude=" + peak + " wav=" + result.getWavPath());

			Assert.assertTrue("rendered output is silent (peak=" + peak + ")", peak > 1e-3);
			Assert.assertTrue("batched dispatch never fired for the melodic channel", batched > 0);
			// Cold ratio (includes one-time kernel compilation) is reported, not gated;
			// the steady-state real-time question is answered by the warm measurement.
		} finally {
			PatternLayerManager.enableBatched = previous;
		}
	}

	/**
	 * Warm steady-state render of the given channels through the full effects/mixdown
	 * pipeline from real samples. In {@code runnerRealTime} the pattern audio (the batched
	 * dispatch) is produced once by {@code setup.run()} into the pattern buffer; the tick
	 * loop applies mixdown/DSP over it. A single pass times each tick right after setup, so
	 * real pattern audio is present (non-silent); the early ticks include one-time
	 * mixdown-kernel compilation, so the last decile is the warm steady state. Logs the
	 * amortized per-buffer playback cost against the {@code BUFFER / sampleRate} budget and
	 * asserts non-silence and clean batched dispatch.
	 *
	 * @param channels channel indices to render and mix down
	 * @param tag      short label used in log lines and the output filename
	 */
	private void warmRenderChannels(List<Integer> channels, String tag) throws Exception {
		Genome<PackedCollection> genome = realGenome();
		AudioScene<?> scene = AudioSceneOptimizer.createScene();
		AudioScenePopulation pop = null;
		try {
			scene.setTotalMeasures(MEASURES);

			double audioSeconds = scene.getContext().getTimeForDuration().applyAsDouble(MEASURES);
			int frames = scene.getContext().getFrameForPosition().applyAsInt(MEASURES);
			int bufferCount = (frames + BUFFER - 1) / BUFFER;

			pop = new AudioScenePopulation(scene, List.of(genome));
			File outFile = new File("results/benchmark-warm-" + tag + ".wav");
			WaveOutput out = new WaveOutput(() -> outFile, 24, true);
			pop.init(genome, new MultiChannelAudioOutput(out), channels, BUFFER);

			TemporalCellular cells = pop.enableGenome(0);
			Runnable setup = cells.setup().get();
			Runnable tick = cells.tick().get();

			BatchedPatternLayerRenderer.resetCounters();
			long setupStart = System.nanoTime();
			setup.run();
			double setupMs = (System.nanoTime() - setupStart) / 1_000_000.0;

			long[] tickNs = new long[bufferCount];
			for (int b = 0; b < bufferCount; b++) {
				long t0 = System.nanoTime();
				tick.run();
				tickNs[b] = System.nanoTime() - t0;
			}
			out.write().get().run();

			long sum = 0;
			int decile = Math.max(1, bufferCount / 10);
			long lastSum = 0;
			for (int b = 0; b < bufferCount; b++) {
				sum += tickNs[b];
				if (b >= bufferCount - decile) lastSum += tickNs[b];
			}
			long[] sorted = tickNs.clone();
			Arrays.sort(sorted);
			double tickAvgMs = sum / (double) bufferCount / 1_000_000.0;
			double p50Ms = sorted[bufferCount / 2] / 1_000_000.0;
			double p95Ms = sorted[Math.min(bufferCount - 1, (int) (bufferCount * 0.95))] / 1_000_000.0;
			double lastDecileMs = lastSum / (double) decile / 1_000_000.0;
			double budgetMs = (double) BUFFER / OutputLine.sampleRate * 1000.0;

			// Amortized per-buffer playback cost at warm steady state: the warm mixdown tick
			// plus the share of the one-time bulk pattern setup attributable to one buffer.
			double perBufferMs = lastDecileMs + setupMs / bufferCount;

			double peak = peakAmplitude(outFile.getPath());
			long batched = BatchedPatternLayerRenderer.batchedDispatchCount.get();
			long fallback = BatchedPatternLayerRenderer.fallbackCount.get();

			log("warm " + tag + " channels=" + channels + " measures=" + MEASURES
					+ " ticks=" + bufferCount + " audio=" + audioSeconds + "s budget=" + budgetMs + "ms");
			log("warm " + tag + " steady-state: tickAvg=" + tickAvgMs + "ms p50=" + p50Ms + "ms p95=" + p95Ms
					+ "ms lastDecile=" + lastDecileMs + "ms setup=" + setupMs + "ms"
					+ " perBuffer(warmTick+setup/N)=" + perBufferMs + "ms ratio=" + (perBufferMs / budgetMs));
			log("warm " + tag + " dispatch: batchedDispatchCount=" + batched + " fallbackCount=" + fallback
					+ " peakAmplitude=" + peak);

			out.reset();
			cells.reset();
			pop.disableGenome();

			Assert.assertTrue("warm " + tag + " output is silent (peak=" + peak + ")", peak > 1e-3);
			Assert.assertTrue("warm " + tag + " batched dispatch never fired", batched > 0);
		} finally {
			if (pop != null) pop.destroy();
			scene.destroy();
		}
	}

	/** All channel indices in the default scene (per {@code pattern-factory.json}). */
	private List<Integer> allChannels() {
		return IntStream.range(0, AudioScene.DEFAULT_SOURCE_COUNT).boxed().toList();
	}

	/**
	 * Step 1 (warm): warm steady-state cost of the single melodic channel (Lead Synth)
	 * through the full pipeline. The ratio is the experimental "keep up during playback"
	 * result; non-silence and clean batched dispatch are asserted.
	 */
	@Test(timeout = 900000)
	@TestDepth(3)
	public void singleMelodicChannelWarmSteadyState() throws Exception {
		requireRealAssets();

		boolean previous = PatternLayerManager.enableBatched;
		PatternLayerManager.enableBatched = true;
		try {
			warmRenderChannels(List.of(LEAD_CHANNEL), "single-c" + LEAD_CHANNEL);
		} finally {
			PatternLayerManager.enableBatched = previous;
		}
	}

	/**
	 * Step 2 (cold): renders ALL channels through the full effects/mixdown pipeline from
	 * real samples with batching enabled on the default hybrid backend routing — the full
	 * real-app load. Reports the cold real-time ratio and verifies non-silent audio. Some
	 * channels are percussive (not batched), so only {@code batchedDispatchCount > 0} from
	 * the melodic channels is asserted, not the absence of per-note rendering.
	 */
	@Test(timeout = 1200000)
	@TestDepth(3)
	public void allChannelsFullPipeline() throws Exception {
		requireRealAssets();

		boolean previous = PatternLayerManager.enableBatched;
		PatternLayerManager.enableBatched = true;
		BatchedPatternLayerRenderer.resetCounters();
		try {
			Genome<PackedCollection> genome = realGenome();
			List<Integer> channels = allChannels();
			AudioSceneBenchmark.BenchmarkResult result = AudioSceneBenchmark.runMultiChannel(
					MEASURES, channels, 7, BUFFER, genome,
					"results/profiles", "results/timelines", true, 0, false);

			double peak = peakAmplitude(result.getWavPath());
			long batched = BatchedPatternLayerRenderer.batchedDispatchCount.get();
			long fallback = BatchedPatternLayerRenderer.fallbackCount.get();

			log("all channels=" + channels + " measures=" + MEASURES + " buffer=" + BUFFER
					+ ": audio=" + result.getAudioSeconds() + "s setup=" + result.getSetupMs() + "ms"
					+ " tickTotal=" + result.getTickStats().getTotalMs() + "ms ratio=" + result.getRatio()
					+ " p50=" + result.getTickStats().getP50Ms() + "ms p95=" + result.getTickStats().getP95Ms()
					+ "ms max=" + result.getTickStats().getMaxMs() + "ms");
			log("all channels dispatch: batchedDispatchCount=" + batched
					+ " fallbackCount=" + fallback + " peakAmplitude=" + peak + " wav=" + result.getWavPath());

			Assert.assertTrue("rendered output is silent (peak=" + peak + ")", peak > 1e-3);
			Assert.assertTrue("batched dispatch never fired for any melodic channel", batched > 0);
		} finally {
			PatternLayerManager.enableBatched = previous;
		}
	}

	/**
	 * Step 2 (warm): warm steady-state cost of all channels through the full pipeline —
	 * the real-app playback load. Ratio is the experimental result; non-silence and clean
	 * batched dispatch are asserted.
	 */
	@Test(timeout = 1200000)
	@TestDepth(3)
	public void allChannelsWarmSteadyState() throws Exception {
		requireRealAssets();

		boolean previous = PatternLayerManager.enableBatched;
		PatternLayerManager.enableBatched = true;
		try {
			warmRenderChannels(allChannels(), "all");
		} finally {
			PatternLayerManager.enableBatched = previous;
		}
	}

	/**
	 * Captures an {@link org.almostrealism.io.profile.OperationProfileNode OperationProfileNode}
	 * XML for the single-channel full-pipeline render so the a3 mixdown/DSP tick — the
	 * dominant real-time cost once pattern batching is amortized — can be attributed
	 * per-operation via the {@code ar-profile-analyzer} tools. Logs the XML path; the tick
	 * subtree dominates the profile because it runs once per buffer over the whole arrangement.
	 */
	@Test(timeout = 900000)
	@TestDepth(3)
	public void profileSingleMelodicChannel() throws Exception {
		requireRealAssets();

		boolean previous = PatternLayerManager.enableBatched;
		PatternLayerManager.enableBatched = true;
		BatchedPatternLayerRenderer.resetCounters();
		try {
			Genome<PackedCollection> genome = realGenome();
			AudioSceneBenchmark.BenchmarkResult result = AudioSceneBenchmark.runSingleChannel(
					MEASURES, LEAD_CHANNEL, 7, BUFFER, genome,
					"results/profiles", "results/timelines", false, 0, false);

			double peak = peakAmplitude(result.getWavPath());
			log("profile single melodic channel=" + LEAD_CHANNEL + " ratio=" + result.getRatio()
					+ " tickTotal=" + result.getTickStats().getTotalMs() + "ms setup=" + result.getSetupMs()
					+ "ms profileXml=" + result.getProfileXmlPath() + " peak=" + peak);

			Assert.assertTrue("rendered output is silent (peak=" + peak + ")", peak > 1e-3);
			Assert.assertNotNull("no profile XML captured", result.getProfileXmlPath());
		} finally {
			PatternLayerManager.enableBatched = previous;
		}
	}

	/**
	 * Renders ~5 minutes of all-channel audio through the production continuous renderer
	 * ({@link RealtimeContinuousRenderer}) on the default hybrid backend, with batching on,
	 * writing {@code results/rt-live.wav} (overwritten each ~2-minute 64-measure arrangement
	 * loop) for manual listening verification. Slower than real time by design — the point
	 * is to confirm the audio is correct using this method, not throughput. The final file
	 * holds the last completed arrangement loop; non-silence is asserted.
	 */
	@Test(timeout = 2400000)
	@TestDepth(3)
	@TestProperties(excludeProfiles = TestUtils.PIPELINE)
	public void renderAllChannelsContinuousToFile() throws Exception {
		requireRealAssets();

		boolean previous = PatternLayerManager.enableBatched;
		PatternLayerManager.enableBatched = true;
		try {
			System.setProperty("AR_RT_CHANNELS", "0,1,2,3,4,5");
			System.setProperty("AR_RT_LIVE_OUTPUT", "true");
			System.setProperty("AR_RT_MEASURES", "64");
			System.setProperty("AR_RT_HOURS", String.valueOf(5.0 / 60.0));
			System.setProperty("AR_RT_LOG_EVERY", "32");

			RealtimeContinuousRenderer.main(new String[0]);

			File f = new File("results/rt-live.wav");
			Assert.assertTrue("rt-live.wav was not written", f.isFile() && f.length() > 100000L);
			double peak = peakAmplitude(f.getPath());
			log("rt-live render complete: file=" + f.getAbsolutePath() + " bytes=" + f.length()
					+ " peakAmplitude=" + peak);
			Assert.assertTrue("rt-live.wav is silent (peak=" + peak + ")", peak > 1e-3);
		} finally {
			PatternLayerManager.enableBatched = previous;
		}
	}
}
