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

package org.almostrealism.audio.benchmark;

import io.almostrealism.compute.Process;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.audio.BatchedPatternRenderer;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.time.TemporalFeatures;
import org.almostrealism.time.computations.MultiOrderFilter;
import org.almostrealism.util.FirFilterTestFeatures;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.Random;

/**
 * Establishes the optimistic floor for the four-kernel pattern rendering chain —
 * what the kernels cost in isolation, independent of PatternLayerManager /
 * PatternFeatures orchestration overhead.
 *
 * <p>The benchmark answers: "if we kept all four kernels but removed all existing
 * pattern orchestration, what is the best per-tick time achievable?" That number
 * is the lower bound for Phase 2 targeted fixes. If the floor is well below the
 * 92.9ms real-time threshold, Phase 2 targeted fixes have headroom. If the floor
 * is at or above the threshold, Phase 3 structural restructuring becomes mandatory.</p>
 *
 * <h2>The Four Kernels</h2>
 * <ol>
 *   <li><b>Pitch interpolation / resample</b> — linear gather from a
 *       {@code [SOURCE_SIZE]} source to {@code [NOTE_SIZE]} via precomputed
 *       fractional-position indices. A new kernel with no existing PDSL primitive.</li>
 *   <li><b>Volume envelope</b> — element-wise multiply by a precomputed ADSR envelope,
 *       mirroring the {@code scale} PDSL primitive with a producer-valued gain.</li>
 *   <li><b>Filter envelope</b> — FIR low-pass convolution via {@link MultiOrderFilter},
 *       mirroring the {@code lowpass} PDSL primitive.</li>
 *   <li><b>Sum-into-output-buffer</b> — element-wise add of the filtered note audio into
 *       an accumulation buffer, modelling the per-note contribution step.</li>
 * </ol>
 *
 * <h2>Dimensions</h2>
 * <p><b>Dimension 1 (primary):</b> notes-per-measure × 1 layer, monophonic.
 * Values: 16, 32, 64, 128, 256. Total notes per tick = notes_per_measure × 32
 * measures.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * mvn test -pl engine/audio -Dtest=PatternRenderingFloorBenchmark
 * }</pre>
 *
 * <p>Results are written to {@code engine/audio/results/pattern-rendering-floor.txt}.
 * Linux/CPU results establish the rough shape; Metal numbers from a Mac run give
 * the production-relevant floor.</p>
 *
 * @see org.almostrealism.time.computations.MultiOrderFilter
 */
public class PatternRenderingFloorBenchmark extends TestSuiteBase
		implements FirFilterTestFeatures, TemporalFeatures, ConsoleFeatures {

	/** Source samples per note before pitch resampling. */
	private static final int SOURCE_SIZE = 2048;

	/** Output samples per note after resampling (determines kernel granularity). */
	private static final int NOTE_SIZE = 1024;

	/** Resample ratio: SOURCE_SIZE / NOTE_SIZE = 2.0 (one octave up). */
	private static final double RESAMPLE_RATIO = (double) SOURCE_SIZE / NOTE_SIZE;

	/** Audio sample rate — uses {@link OutputLine#sampleRate} so the benchmark adapts to any future default change. */
	private static final int SAMPLE_RATE = OutputLine.sampleRate;

	/** FIR filter order matching EfxManager.filterOrder. */
	private static final int FILTER_ORDER = 40;

	/** Measures rendered per tick, matching the profiled 32-measure arrangement. */
	private static final int MEASURES_PER_TICK = 32;

	/** Low-pass cutoff frequency in Hz for the filter kernel. */
	private static final double LP_CUTOFF_HZ = 8000.0;

	/** Warmup iterations before timing begins (each iteration = all notes for one tick). */
	private static final int WARMUP_RUNS = 3;

	/** Timed iterations measured per configuration. */
	private static final int TIMED_RUNS = 20;

	/**
	 * Sequential warmup count per pass: each warmup iteration evaluates the chain this
	 * many times before timing begins. Sized as 16 notes per measure × {@link #MEASURES_PER_TICK}
	 * measures so warmup exercises a realistic per-tick load.
	 */
	private static final int WARMUP_NOTES = MEASURES_PER_TICK * 16;

	/** Real-time per-tick budget in milliseconds (32 measures at 60bpm worth of audio). */
	private static final double THRESHOLD_MS = 92.9;

	/** Output path for the benchmark results log; shared by all test methods. */
	private static final String RESULTS_PATH = "results/pattern-rendering-floor.txt";

	/**
	 * Shared single-note renderer used to delegate per-note resample-producer
	 * construction to the production {@link BatchedPatternRenderer}, avoiding
	 * a duplicate copy of the resample algorithm in this benchmark.
	 */
	private static final BatchedPatternRenderer SINGLE_NOTE_RENDERER =
			new BatchedPatternRenderer(1, SOURCE_SIZE, NOTE_SIZE, SAMPLE_RATE, FILTER_ORDER);

	/**
	 * Notes-per-measure densities measured by every Dimension-1 / batched test:
	 * 16, 32, 64, 128, 256. Combined with {@link #MEASURES_PER_TICK} this produces
	 * total-note counts of 512, 1024, 2048, 4096, 8192 per tick.
	 */
	private static final int[] NOTES_PER_MEASURE_VALUES = {16, 32, 64, 128, 256};

	/**
	 * Creates the {@code results/} directory and registers a file-output listener
	 * on {@link Console#root()} pointing at {@link #RESULTS_PATH}. Called at the
	 * top of every benchmark test method.
	 */
	private static void setupResultsListener() {
		new File("results").mkdirs();
		Console.root().addListener(OutputFeatures.fileOutput(RESULTS_PATH));
	}

	/**
	 * Builds the lowpass FIR coefficients used by every chain that includes the
	 * filter kernel: order {@link #FILTER_ORDER}, cutoff {@link #LP_CUTOFF_HZ} Hz,
	 * sample rate {@link #SAMPLE_RATE} Hz.
	 */
	private PackedCollection buildLpCoeffs() {
		double[] data = referenceLowPassCoefficients(LP_CUTOFF_HZ, SAMPLE_RATE, FILTER_ORDER);
		PackedCollection lpCoeffs = new PackedCollection(FILTER_ORDER + 1);
		lpCoeffs.setMem(data);
		return lpCoeffs;
	}

	/**
	 * Builds a random {@code [size]} signal in the range {@code [-1, 1]}, using a
	 * fixed seed so all benchmark runs see the same content (deterministic timing,
	 * no run-to-run content variation).
	 */
	private PackedCollection buildRandomSource(int size) {
		Random rng = new Random(12345L);
		return createSignal(size, i -> rng.nextDouble() * 2.0 - 1.0);
	}

	/**
	 * Formats a "vs {@value #THRESHOLD_MS}ms threshold" comparison string for the
	 * given mean-per-tick measurement: {@code BELOW (Nx headroom)} when under the
	 * real-time budget, {@code ABOVE (Nx overhead)} when over. Used by both the
	 * dimension-1 reporter ({@link #reportStats}) and the padded-FIR inline
	 * reporter ({@link #benchmarkBatchedChainWithPaddedFir}).
	 */
	private static String formatVsThreshold(double meanMs) {
		return meanMs < THRESHOLD_MS
				? String.format("BELOW (%.1fx headroom)", THRESHOLD_MS / meanMs)
				: String.format("ABOVE (%.1fx overhead)", meanMs / THRESHOLD_MS);
	}

	/**
	 * Performs the standard sequential warmup: {@link #WARMUP_RUNS} passes, each running
	 * {@link #WARMUP_NOTES} sequential {@code evaluate()} calls on the given chain. The
	 * {@code description} is interpolated into the "Warming up &lt;description&gt; (...)"
	 * log line so each call site retains a meaningful label.
	 */
	private void warmupSequential(String description, Evaluable<PackedCollection> chain) {
		log("Warming up " + description + " (" + WARMUP_NOTES + " notes × " + WARMUP_RUNS + " passes)...");
		for (int w = 0; w < WARMUP_RUNS; w++) {
			for (int n = 0; n < WARMUP_NOTES; n++) {
				chain.evaluate();
			}
		}
		log("Warmup complete.");
		log("");
	}

	/**
	 * Dimension 1: notes-per-measure, single layer, monophonic.
	 *
	 * <p>This is the load-bearing measurement. For each notes-per-measure value
	 * (16, 32, 64, 128, 256), the benchmark runs 32 × notes_per_measure forward
	 * passes of the compiled four-kernel chain, measures wall-clock time, and
	 * reports mean/median/p95 per tick along with comparison against the 92.9ms
	 * real-time threshold.</p>
	 *
	 * <p>Linux/CPU baseline. Metal numbers from Mac runs needed for production decisions.</p>
	 */
	@Test(timeout = 600000)
	@TestDepth(2)
	public void benchmarkNotesPerMeasureSingleLayer() throws Exception {
		setupResultsListener();

		log("==========================================================");
		log("  PATTERN RENDERING FLOOR BENCHMARK — Dimension 1");
		log("  Platform: Linux/CPU baseline");
		log("  Metal numbers from Mac runs needed for production decisions");
		log("==========================================================");
		log("");
		log("Kernel chain: resample [" + SOURCE_SIZE + " -> " + NOTE_SIZE + " samples]"
				+ " -> volume_envelope -> lowpass_fir(order=" + FILTER_ORDER + ",cutoff=" + (int) LP_CUTOFF_HZ + "Hz)"
				+ " -> accumulate");
		log("Resample ratio: " + RESAMPLE_RATIO + "x (one octave up)");
		log("Measures per tick: " + MEASURES_PER_TICK);
		log("Warmup runs: " + WARMUP_RUNS + " | Timed runs: " + TIMED_RUNS);
		log("");

		PackedCollection envelope = buildAdsrEnvelope();
		PackedCollection lpCoeffs = buildLpCoeffs();
		PackedCollection accumBuffer = new PackedCollection(NOTE_SIZE);
		PackedCollection source = buildRandomSource(SOURCE_SIZE);

		log("Compiling four-kernel chain (no backprop — inference path)...");
		long compileStart = System.currentTimeMillis();
		Evaluable<PackedCollection> compiled = buildChain(source, envelope, lpCoeffs, accumBuffer).get();
		long compileMs = System.currentTimeMillis() - compileStart;
		log("Compilation time: " + compileMs + " ms");
		log("");

		warmupSequential("four-kernel chain", compiled);

		log("--- Dimension 1: notes_per_measure × 32 measures, 1 layer, monophonic ---");
		log("");

		for (int npm : NOTES_PER_MEASURE_VALUES) {
			int totalNotes = npm * MEASURES_PER_TICK;
			long[] timesNs = runTimedIterations(compiled, totalNotes);
			reportStats(npm, totalNotes, timesNs);
		}

		log("==========================================================");
		log("Results written to: " + RESULTS_PATH);
	}

	/**
	 * Builds the four-kernel chain as a {@link CollectionProducer} without compiling it.
	 *
	 * <p>The source {@link PackedCollection} is captured by reference via {@code cp(source)},
	 * so each {@code evaluate()} call uses the same source. For timing purposes this is
	 * correct — the kernels' cost is dominated by sample count, not content.</p>
	 *
	 * <p>Callers compile the returned producer via {@code .get()} or
	 * {@code Process.optimized(chain).get()} depending on whether graph restructuring
	 * before compilation is under measurement.</p>
	 */
	private CollectionProducer buildChain(PackedCollection source,
										  PackedCollection envelope,
										  PackedCollection lpCoeffs,
										  PackedCollection accumBuffer) {
		// Kernel 1: pitch interpolation via linear resample (SOURCE_SIZE -> NOTE_SIZE)
		CollectionProducer resampled = buildResampleProducer(source);

		// Kernel 2: volume envelope — elementwise multiply by ADSR shape
		CollectionProducer volumed = resampled.multiply(cp(envelope));

		// Kernel 3: lowpass FIR filter with precomputed coefficients
		// traverseEach makes the 1D signal traverse element-by-element as MultiOrderFilter expects
		CollectionProducer filtered = MultiOrderFilter.create(traverseEach(volumed), cp(lpCoeffs));

		// Kernel 4: accumulate into output buffer (elementwise add)
		// Reshape to NOTE_SIZE in case MultiOrderFilter changes traversal axis
		return c(filtered).reshape(shape(NOTE_SIZE)).add(cp(accumBuffer));
	}

	/**
	 * Builds the per-note linear-resample producer that maps a {@code [SOURCE_SIZE]}
	 * source array onto a {@code [NOTE_SIZE]} output via fractional-position lerp.
	 *
	 * <p>Delegates to {@link BatchedPatternRenderer#buildResampleProducer} so the
	 * benchmark and production code share a single implementation of the resample
	 * algorithm. Returns the producer un-compiled so callers can chain additional
	 * kernels onto it.</p>
	 *
	 * <p>Reused by every chain builder that needs the un-batched (single-note)
	 * resample step: {@link #buildChain}, {@link #buildResampleOnly}, and
	 * {@link #buildChain3Kernels}.</p>
	 */
	private CollectionProducer buildResampleProducer(PackedCollection source) {
		return SINGLE_NOTE_RENDERER.buildResampleProducer(source, RESAMPLE_RATIO);
	}

	/**
	 * Runs {@link #TIMED_RUNS} timed iterations of the compiled chain, invoking
	 * {@code evaluate()} {@code totalNotes} times per iteration and recording the
	 * wall-clock elapsed time in nanoseconds for each run.
	 */
	private long[] runTimedIterations(Evaluable<PackedCollection> compiled, int totalNotes) {
		long[] results = new long[TIMED_RUNS];
		for (int r = 0; r < TIMED_RUNS; r++) {
			long t0 = System.nanoTime();
			for (int n = 0; n < totalNotes; n++) {
				compiled.evaluate();
			}
			results[r] = System.nanoTime() - t0;
		}
		return results;
	}

	/**
	 * Computes and logs timing statistics for one density level: mean, median, p95,
	 * and standard deviation in milliseconds, plus amortized per-note cost and
	 * comparison against the {@link #THRESHOLD_MS} real-time budget.
	 */
	private void reportStats(int notesPerMeasure, int totalNotes, long[] timesNs) {
		double[] stats = computeTimingStats(timesNs);
		double meanMs = stats[0];
		double medianMs = stats[1];
		double p95Ms = stats[2];
		double variance = Arrays.stream(timesNs).mapToDouble(t -> {
			double diff = t / 1_000_000.0 - meanMs;
			return diff * diff;
		}).average().orElse(0);
		double stdMs = Math.sqrt(variance);

		log(String.format("notes_per_measure=%d  (total=%d notes/tick)", notesPerMeasure, totalNotes));
		log(String.format("  Mean=%.2fms  Median=%.2fms  P95=%.2fms  StdDev=%.2fms",
				meanMs, medianMs, p95Ms, stdMs));
		log(String.format("  Per-note: %.4f ms avg", meanMs / totalNotes));
		log(String.format("  vs %.1fms threshold: %s", THRESHOLD_MS, formatVsThreshold(meanMs)));
		log("");
	}

	/**
	 * Builds a single-row ADSR volume envelope of shape {@code [NOTE_SIZE]} filled
	 * with a standard envelope shape: fast attack (5%), decay (10%), sustain at 0.7,
	 * release (15%).
	 */
	private PackedCollection buildAdsrEnvelope() {
		double[] data = new double[NOTE_SIZE];
		fillAdsrShape(data, 0, NOTE_SIZE, 0.0, 1.0, 0.7, 0.0, 0.05, 0.10, 0.15);
		PackedCollection env = new PackedCollection(NOTE_SIZE);
		env.setMem(data);
		return env;
	}

	/**
	 * Fills a piecewise-linear ADSR shape into {@code data[offset..offset+size]}:
	 * attack ramp from {@code base} to {@code peak}, decay from {@code peak} to
	 * {@code sustain}, held at {@code sustain}, release ramp from {@code sustain}
	 * to {@code end}. ADSR section lengths come from {@code attackFrac},
	 * {@code decayFrac}, {@code releaseFrac} (fractions of {@code size}); the
	 * sustain section fills the remainder.
	 *
	 * <p>Shared by {@link #buildAdsrEnvelope}, {@link #buildAdsrCutoff},
	 * {@link #buildPerRowVolumeEnvelopes}, and {@link #buildPerRowFilterCutoffs} —
	 * all four pre-materialize the same shape with different parameters per row.
	 * Also used by {@code BatchedPatternRendererTest} in the same module.</p>
	 */
	public static void fillAdsrShape(double[] data, int offset, int size,
									  double base, double peak, double sustain, double end,
									  double attackFrac, double decayFrac, double releaseFrac) {
		int attackSamples = (int) (size * attackFrac);
		int decaySamples = (int) (size * decayFrac);
		int releaseSamples = (int) (size * releaseFrac);
		int sustainSamples = size - attackSamples - decaySamples - releaseSamples;
		int idx = offset;
		for (int i = 0; i < attackSamples; i++) {
			data[idx++] = base + (peak - base) * i / attackSamples;
		}
		for (int i = 0; i < decaySamples; i++) {
			data[idx++] = peak - (peak - sustain) * i / decaySamples;
		}
		for (int i = 0; i < sustainSamples; i++) {
			data[idx++] = sustain;
		}
		for (int i = 0; i < releaseSamples; i++) {
			data[idx++] = sustain - (sustain - end) * i / releaseSamples;
		}
	}
}
