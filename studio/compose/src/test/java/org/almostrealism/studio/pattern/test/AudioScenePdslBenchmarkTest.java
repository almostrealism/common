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

import io.almostrealism.profile.OperationProfileNode;
import org.almostrealism.audio.WaveOutput;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.heredity.TemporalCellular;
import org.almostrealism.studio.AudioScene;
import org.almostrealism.studio.arrange.MixdownManager;
import org.almostrealism.studio.health.MultiChannelAudioOutput;
import org.almostrealism.music.pattern.BatchedPatternLayerRenderer;
import org.almostrealism.music.pattern.PatternSystemManager;
import org.almostrealism.util.TestDepth;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

/**
 * Steady-state realtime throughput benchmark for the PDSL mixdown path across an array of
 * genomes, against the realtime budget: a buffer of {@code n} frames must render in less
 * wall-clock time than the playback duration of {@code n} frames
 * ({@code n / sampleRate} seconds).
 *
 * <p>Methodology:</p>
 * <ul>
 *   <li>ONE curated scene (persisted settings, see {@link #SCENE_SETTINGS}) so results are
 *       reproducible across runs; genomes vary only the projected parameter vector and the
 *       resulting pattern density.</li>
 *   <li>Genome seeds are selected from the same deterministic pool used by
 *       {@link #findWorkingGenomeSeed}, spread from the densest to the sparsest viable
 *       arrangement so the benchmark covers a range of pattern loads.</li>
 *   <li>The runner is built and compiled ONCE per buffer size; genomes are swapped on the
 *       live runner via {@link #applyGenome} + {@code runner.reset()} (the protocol
 *       exercised by {@code AudioSceneMultiGenomeTest#multiGenomeFullRunner}). One-time
 *       compilation is reported separately and excluded from per-tick statistics.</li>
 *   <li>Warm-up ticks after each genome swap are excluded; the measured window is
 *       {@link #MEASURE_SECONDS} of audio per genome, reported as median / mean / p95 / max
 *       tick wall time and the realtime headroom factor (budget / median).</li>
 * </ul>
 *
 * <p>The CellList path is benchmarked for one genome at the default review buffer size as a
 * baseline for comparison.</p>
 *
 * @see AudioScenePdslCutoverTest
 * @see org.almostrealism.studio.AudioSceneRealtimeRunner
 */
public class AudioScenePdslBenchmarkTest extends AudioSceneTestBase {

	/** Tempo for the benchmark scene (matches the review render). */
	private static final double BENCH_BPM = 120.0;

	/** Total measures in the benchmark arrangement (matches the review render). */
	private static final int BENCH_MEASURES = 64;

	/** Primary buffer size, used for both paths (matches the review render). */
	private static final int PRIMARY_BUFFER = 8192;

	/** Secondary buffer size, single-genome PDSL pass to measure frame-count scaling. */
	private static final int SECONDARY_BUFFER = 4096;

	/** Ticks rendered (and discarded) after each genome swap before measurement begins. */
	private static final int WARMUP_TICKS = 2;

	/** Seconds of audio rendered per genome in the measured window. */
	private static final double MEASURE_SECONDS = 6.0;

	/** Maximum number of genomes to benchmark per buffer size. */
	private static final int MAX_BENCH_GENOMES = 3;

	/** Report file written alongside the cutover review artifacts. */
	private static final String REPORT_PATH = "results/pdsl-cutover/benchmark.txt";

	/** Steady-state ticks recorded under the operation profile. */
	private static final int PROFILE_TICKS = 5;

	/** Profile XML written alongside the cutover review artifacts. */
	private static final String PROFILE_PATH = "results/pdsl-cutover/pdsl_tick_profile.xml";

	/**
	 * Benchmarks steady-state tick wall time for the PDSL path across genomes and buffer
	 * sizes, plus a one-genome CellList baseline, and writes a line-per-measurement report
	 * to {@link #REPORT_PATH}.
	 *
	 * @throws IOException if the scene cannot be loaded or the report cannot be written
	 */
	@Test(timeout = 1_080_000)
	@TestDepth(2)
	public void pdslRealtimeBenchmark() throws IOException {
		AudioScene<?> scene = loadBenchmarkScene("pdslRealtimeBenchmark");
		if (scene == null) return;

		List<long[]> genomes = selectBenchmarkGenomes(scene);
		Assert.assertFalse("No viable genomes found in the real arrangement", genomes.isEmpty());

		StringBuilder report = new StringBuilder();
		boolean previous = MixdownManager.enablePdslMixdown;

		try {
			// Both paths over the SAME seeds at the primary buffer size — the direct A/B —
			// then a single-genome PDSL pass at the secondary size to see frame-count scaling.
			MixdownManager.enablePdslMixdown = true;
			benchmarkRunner("pdsl", scene, genomes, PRIMARY_BUFFER, report);

			MixdownManager.enablePdslMixdown = false;
			benchmarkRunner("celllist", scene, genomes, PRIMARY_BUFFER, report);

			MixdownManager.enablePdslMixdown = true;
			benchmarkRunner("pdsl", scene, genomes.subList(0, 1), SECONDARY_BUFFER, report);
		} finally {
			MixdownManager.enablePdslMixdown = previous;
		}

		File reportFile = new File(REPORT_PATH);
		reportFile.getParentFile().mkdirs();
		Files.write(reportFile.toPath(), report.toString().getBytes(StandardCharsets.UTF_8));
		log("Benchmark report written to " + reportFile.getAbsolutePath());
	}

	/**
	 * Captures an {@link OperationProfileNode} over the PDSL runner's build and a short
	 * steady-state tick window, attributing the tick's wall time to its compiled
	 * operations (pattern prepare, automation refresh, model forward, output streaming,
	 * clock advance). The profile must be assigned to the hardware <em>before</em> the
	 * runner is built, because operations bind to the active profile at compile time.
	 * The saved XML is analyzed offline (ar-profile-analyzer) to localize the constant
	 * per-tick overhead the realtime benchmark exposed.
	 *
	 * @throws IOException if the scene cannot be loaded or the profile cannot be saved
	 */
	@Test(timeout = 1_080_000)
	@TestDepth(2)
	public void pdslTickProfile() throws IOException {
		AudioScene<?> scene = loadBenchmarkScene("pdslTickProfile");
		if (scene == null) return;

		long seed = findWorkingGenomeSeed(scene, getSamplesDir());
		Assert.assertTrue("No working genome found in the real arrangement", seed >= 0);
		applyGenome(scene, seed);

		OperationProfileNode profile = new OperationProfileNode("pdsl_tick");
		Hardware.getLocalHardware().assignProfile(profile);

		boolean previous = MixdownManager.enablePdslMixdown;
		File scratch = new File("results/pdsl-cutover/benchmark_scratch.wav");
		WaveOutput out = new WaveOutput(() -> scratch, 24, true);

		try {
			MixdownManager.enablePdslMixdown = true;
			TemporalCellular runner = scene.runnerRealTime(
					new MultiChannelAudioOutput(out), null, PRIMARY_BUFFER);
			Runnable setup = runner.setup().get();
			Runnable tick = runner.tick().get();

			try {
				setup.run();
				for (int i = 0; i < WARMUP_TICKS; i++) {
					tick.run();
				}

				long start = System.nanoTime();
				for (int i = 0; i < PROFILE_TICKS; i++) {
					tick.run();
				}
				double totalMs = (System.nanoTime() - start) / 1e6;
				log("profiledTicks=" + PROFILE_TICKS
						+ " totalMs=" + format(totalMs)
						+ " perTickMs=" + format(totalMs / PROFILE_TICKS));
			} finally {
				out.reset();
				runner.reset();
			}
		} finally {
			MixdownManager.enablePdslMixdown = previous;
			Hardware.getLocalHardware().assignProfile(null);
		}

		File profileFile = new File(PROFILE_PATH);
		profileFile.getParentFile().mkdirs();
		profile.save(profileFile.getPath());
		log("Tick profile written to " + profileFile.getAbsolutePath());
	}

	/**
	 * Times each stage of the PDSL runner's tick {@link OperationList} individually —
	 * the per-buffer frame-index reset, the per-cell pattern prepares, the automation
	 * refresh, the model forward, the output-streaming loop, and the clock advance —
	 * to attribute the tick's wall time to a stage. The full-tick profile showed the
	 * compiled operations' recorded run time accounts for almost none of the tick wall
	 * time, so this isolates where the Java-side time goes.
	 *
	 * @throws IOException if the scene cannot be loaded
	 */
	@Test(timeout = 1_080_000)
	@TestDepth(2)
	public void pdslTickStageTiming() throws IOException {
		AudioScene<?> scene = loadBenchmarkScene("pdslTickStageTiming");
		if (scene == null) return;

		long seed = findWorkingGenomeSeed(scene, getSamplesDir());
		Assert.assertTrue("No working genome found in the real arrangement", seed >= 0);
		applyGenome(scene, seed);
		for (int c = 0; c < AudioScene.DEFAULT_SOURCE_COUNT; c++) {
			log("channelElements c=" + c + " elements=" + countElements(scene, c));
		}

		boolean previous = MixdownManager.enablePdslMixdown;
		File scratch = new File("results/pdsl-cutover/benchmark_scratch.wav");
		WaveOutput out = new WaveOutput(() -> scratch, 24, true);

		try {
			MixdownManager.enablePdslMixdown = true;
			TemporalCellular runner = scene.runnerRealTime(
					new MultiChannelAudioOutput(out), null, PRIMARY_BUFFER);
			runner.setup().get().run();

			Supplier<Runnable> tickSupplier = runner.tick();
			Assert.assertTrue("Expected the PDSL tick to be an OperationList",
					tickSupplier instanceof OperationList);
			OperationList tick = (OperationList) tickSupplier;

			List<String> names = new ArrayList<>();
			List<Runnable> stages = new ArrayList<>();
			for (Supplier<Runnable> child : tick) {
				names.add(child instanceof OperationList ?
						((OperationList) child).getDescription() : child.getClass().getSimpleName());
				stages.add(child.get());
			}

			try {
				for (int w = 0; w < 24; w++) {
					stages.forEach(Runnable::run);
				}

				BatchedPatternLayerRenderer.resetCounters();
				long[] stageNanos = new long[stages.size()];
				for (int i = 0; i < PROFILE_TICKS; i++) {
					for (int s = 0; s < stages.size(); s++) {
						long start = System.nanoTime();
						stages.get(s).run();
						stageNanos[s] += System.nanoTime() - start;
					}
				}
				log("batchedDispatchCount=" + BatchedPatternLayerRenderer.batchedDispatchCount.get()
						+ " fallbackCount=" + BatchedPatternLayerRenderer.fallbackCount.get()
						+ " gatherMsPerTick=" + format(BatchedPatternLayerRenderer.gatherNanos.get() / 1e6 / PROFILE_TICKS));
				log("marshalMsPerTick=" + format(BatchedPatternLayerRenderer.marshalNanos.get() / 1e6 / PROFILE_TICKS)
						+ " evalMsPerTick=" + format(BatchedPatternLayerRenderer.evalNanos.get() / 1e6 / PROFILE_TICKS));

				double totalMs = 0;
				for (int s = 0; s < stages.size(); s++) {
					double perTickMs = stageNanos[s] / 1e6 / PROFILE_TICKS;
					totalMs += perTickMs;
					log("stage=" + s + " name=" + names.get(s)
							+ " perTickMs=" + format(perTickMs));
				}
				log("stageTotalPerTickMs=" + format(totalMs));

				// Anti-cheat: a fast render is worthless if it is silent. Flush the streamed
				// master and assert the decoupled PDSL render actually produced audio on this
				// deterministic working scene — timing is only meaningful over real output.
				out.write().get().run();
				double renderedPeak = peakAmplitude(scratch.getPath());
				log("renderedPeakAmplitude=" + format(renderedPeak));
				Assert.assertTrue("decoupled PDSL render produced silence (peak=" + renderedPeak + ")",
						renderedPeak > 1e-3);
			} finally {
				out.reset();
				runner.reset();
			}
		} finally {
			MixdownManager.enablePdslMixdown = previous;
		}
	}

	/**
	 * Loads the curated benchmark scene with the production flag configuration, failing the test
	 * (see {@link #requireCuratedLibrary()}) when the curated library or pattern factory is not
	 * available on this host.
	 *
	 * @param caller test name for the log line
	 * @return the loaded scene
	 * @throws IOException if the scene cannot be loaded
	 */
	private AudioScene<?> loadBenchmarkScene(String caller) throws IOException {
		log(caller + " - loading curated benchmark scene");
		File library = requireCuratedLibrary();
		File patternFactory = new File(PATTERN_FACTORY);

		// Production flag configuration, mirroring the review render so the benchmark
		// measures the same compiled mixdown the cutover validated.
		MixdownManager.enableMainFilterUp = true;
		MixdownManager.enableEfx = true;
		MixdownManager.enableEfxFilters = true;
		MixdownManager.enableReverb = true;
		PatternSystemManager.enableWarnings = false;

		return loadCuratedScene(library, patternFactory, BENCH_BPM, BENCH_MEASURES);
	}

	/**
	 * Builds and compiles a runner once for the given buffer size, then measures
	 * steady-state tick wall time for each genome, appending one report line per genome.
	 *
	 * @param label      DSP path label for report lines ("pdsl" / "celllist")
	 * @param scene      the shared curated scene
	 * @param genomes    {@code [seed, elementCount]} pairs to benchmark
	 * @param bufferSize frames per buffer
	 * @param report     accumulating report
	 */
	private void benchmarkRunner(String label, AudioScene<?> scene, List<long[]> genomes,
								 int bufferSize, StringBuilder report) {
		File scratch = new File("results/pdsl-cutover/benchmark_scratch.wav");
		WaveOutput out = new WaveOutput(() -> scratch, 24, true);
		MultiChannelAudioOutput output = new MultiChannelAudioOutput(out);

		applyGenome(scene, genomes.get(0)[0]);
		long buildStart = System.nanoTime();
		TemporalCellular runner = scene.runnerRealTime(output, null, bufferSize);
		Runnable setup = runner.setup().get();
		Runnable tick = runner.tick().get();
		double buildMs = (System.nanoTime() - buildStart) / 1e6;

		double budgetMs = 1000.0 * bufferSize / SAMPLE_RATE;
		int measuredTicks = (int) Math.ceil(MEASURE_SECONDS * SAMPLE_RATE / bufferSize);
		reportLine(report, "path=" + label + " buffer=" + bufferSize
				+ " budgetMs=" + format(budgetMs)
				+ " buildMs=" + format(buildMs)
				+ " measuredTicks=" + measuredTicks);

		try {
			for (long[] genome : genomes) {
				applyGenome(scene, genome[0]);
				runner.reset();
				setup.run();
				for (int i = 0; i < WARMUP_TICKS; i++) {
					tick.run();
				}

				double[] tickMs = new double[measuredTicks];
				for (int i = 0; i < measuredTicks; i++) {
					long start = System.nanoTime();
					tick.run();
					tickMs[i] = (System.nanoTime() - start) / 1e6;
				}

				Arrays.sort(tickMs);
				double median = tickMs[measuredTicks / 2];
				double mean = Arrays.stream(tickMs).average().orElse(0.0);
				double p95 = tickMs[Math.min(measuredTicks - 1,
						(int) Math.ceil(measuredTicks * 0.95) - 1)];
				double max = tickMs[measuredTicks - 1];

				reportLine(report, "path=" + label + " buffer=" + bufferSize
						+ " seed=" + genome[0] + " elements=" + genome[1]
						+ " medianMs=" + format(median) + " meanMs=" + format(mean)
						+ " p95Ms=" + format(p95) + " maxMs=" + format(max)
						+ " realtimeX=" + format(budgetMs / median));
			}
		} finally {
			out.reset();
			runner.reset();
		}
	}

	/**
	 * Scans the deterministic seed pool (the same one {@link #findWorkingGenomeSeed} draws
	 * from), keeps the seeds that produce at least one pattern element, and returns up to
	 * {@link #MAX_BENCH_GENOMES} of them spread evenly from the densest to the sparsest
	 * arrangement.
	 *
	 * @param scene the scene to probe (its genome is reassigned during the scan)
	 * @return {@code [seed, elementCount]} pairs ordered from most to fewest elements
	 */
	private List<long[]> selectBenchmarkGenomes(AudioScene<?> scene) {
		List<long[]> viable = new ArrayList<>();

		for (int attempt = 0; attempt < MAX_GENOME_ATTEMPTS; attempt++) {
			long seed = 42 + attempt;
			applyGenome(scene, seed);
			int elements = countElements(scene);
			if (elements > 0) {
				viable.add(new long[] { seed, elements });
			}
		}

		viable.sort((a, b) -> Long.compare(b[1], a[1]));
		if (viable.size() <= MAX_BENCH_GENOMES) {
			return viable;
		}

		List<long[]> selected = new ArrayList<>();
		for (int i = 0; i < MAX_BENCH_GENOMES; i++) {
			int index = (int) Math.round(
					i * (viable.size() - 1) / (double) (MAX_BENCH_GENOMES - 1));
			selected.add(viable.get(index));
		}
		return selected;
	}

	/**
	 * Appends a line to the report and echoes it to the log.
	 *
	 * @param report the accumulating report
	 * @param line   the measurement line
	 */
	private void reportLine(StringBuilder report, String line) {
		report.append(line).append('\n');
		log(line);
	}

	/**
	 * Formats a millisecond (or ratio) value with two decimal places.
	 *
	 * @param value the value to format
	 * @return the formatted value
	 */
	private String format(double value) {
		return String.format("%.2f", value);
	}
}
