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
import org.almostrealism.studio.AudioScene;
import org.almostrealism.studio.arrange.MixdownManager;
import org.almostrealism.studio.health.MultiChannelAudioOutput;
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
		File library = getSamplesDir();
		File patternFactory = new File(PATTERN_FACTORY);
		if (library == null || !patternFactory.exists()) {
			log("Skipping pdslRealtimeBenchmark - need the curated library (" + SAMPLES_PATH
					+ ") and pattern factory (" + PATTERN_FACTORY + ")");
			return;
		}

		// Production flag configuration, mirroring the review render so the benchmark
		// measures the same compiled mixdown the cutover validated.
		MixdownManager.enableMainFilterUp = true;
		MixdownManager.enableEfx = true;
		MixdownManager.enableEfxFilters = true;
		MixdownManager.enableReverb = true;
		PatternSystemManager.enableWarnings = false;

		AudioScene<?> scene = loadCuratedScene(library, patternFactory, BENCH_BPM, BENCH_MEASURES);
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
