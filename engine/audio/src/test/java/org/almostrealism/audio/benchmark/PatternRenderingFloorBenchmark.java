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

	/** Audio sample rate matching the production pipeline. */
	private static final int SAMPLE_RATE = 44100;

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
	 * <p>Reused by every chain builder that needs the un-batched (single-note)
	 * resample step: {@link #buildChain}, {@link #buildResampleOnly}, and
	 * {@link #buildChain3Kernels}. Returns the producer un-compiled so callers can
	 * chain additional kernels onto it.</p>
	 */
	private CollectionProducer buildResampleProducer(PackedCollection source) {
		CollectionProducer srcPos = integers(0, NOTE_SIZE).multiply(c(RESAMPLE_RATIO));
		CollectionProducer fPos = floor(srcPos);
		CollectionProducer frac = srcPos.subtract(fPos);
		CollectionProducer s0 = c(shape(NOTE_SIZE), cp(source), fPos);
		CollectionProducer s1 = c(shape(NOTE_SIZE), cp(source), fPos.add(c(1.0)));
		return s0.add(frac.multiply(s1.subtract(s0)));
	}

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
	 * all four pre-materialize the same shape with different parameters per row.</p>
	 */
	private static void fillAdsrShape(double[] data, int offset, int size,
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

	// ===========================================================================================
	// ADDITION 1 — Optimization-settings verification
	// ===========================================================================================

	/**
	 * Verifies whether applying {@link Process#optimized(Object)} before {@code .get()} changes
	 * the per-note cost compared to the baseline {@link #buildChain} that calls {@code .get()}
	 * directly.
	 *
	 * <p>The existing {@link #buildChain} calls {@code output.get()} without first restructuring
	 * the computation graph via {@code Process.optimized()}. This test builds the same four-kernel
	 * chain using {@code Process.optimized(output).get()} and measures the same five densities.
	 * If optimization has no effect on sequential JNI execution, the per-note cost will match
	 * the baseline ~0.87 ms. Any meaningful delta is a finding.</p>
	 */
	@Test(timeout = 600000)
	@TestDepth(2)
	public void benchmarkOptimizedChain() throws Exception {
		setupResultsListener();

		log("==========================================================");
		log("  ADDITION 1 — Optimization-applied measurement");
		log("  Process.optimized() applied before .get()");
		log("  Baseline: ~0.87 ms/note (sequential JNI)");
		log("==========================================================");
		log("");

		PackedCollection envelope = buildAdsrEnvelope();
		PackedCollection lpCoeffs = buildLpCoeffs();
		PackedCollection accumBuffer = new PackedCollection(NOTE_SIZE);
		PackedCollection source = buildRandomSource(SOURCE_SIZE);

		log("Compiling optimized four-kernel chain (Process.optimized before .get())...");
		long compileStart = System.currentTimeMillis();
		Evaluable<PackedCollection> compiled = (Evaluable<PackedCollection>)
				Process.optimized(buildChain(source, envelope, lpCoeffs, accumBuffer)).get();
		long compileMs = System.currentTimeMillis() - compileStart;
		log("Compilation time: " + compileMs + " ms");
		log("");

		warmupSequential("optimized four-kernel chain", compiled);

		log("--- Addition 1: optimized chain, notes_per_measure × 32 measures, 1 layer ---");
		log("");

		for (int npm : NOTES_PER_MEASURE_VALUES) {
			int totalNotes = npm * MEASURES_PER_TICK;
			long[] timesNs = runTimedIterations(compiled, totalNotes);
			reportStats(npm, totalNotes, timesNs);
		}

		log("Note: baseline (no Process.optimized) per-note cost was ~0.87 ms.");
		log("If the above per-note cost matches that, optimization has no effect on sequential JNI.");
		log("==========================================================");
		log("Results appended to: " + RESULTS_PATH);
	}

	// ===========================================================================================
	// ADDITION 2 — Per-phase breakdown
	// ===========================================================================================

	/**
	 * Times each of the four kernels individually for the central density (64 notes/measure,
	 * 2048 notes total). Reports a per-phase breakdown showing what fraction of the combined
	 * chain cost each kernel contributes.
	 *
	 * <p>Each kernel is built in isolation: it receives pre-computed input (a fixed
	 * {@link PackedCollection} captured via {@code cp()}) and produces one output per
	 * {@code evaluate()} call. The sum of the four per-phase means should approximately equal
	 * or exceed the combined-chain mean at 2048 notes; a significantly lower sum would indicate
	 * kernel fusion happening in the composed chain.</p>
	 *
	 * <p>The most expensive kernel is the highest-leverage target for caching (Phase 2 work).
	 * Resampling in particular is cacheable when notes repeat the same pitch class — if it
	 * dominates, caching is high-value.</p>
	 */
	@Test(timeout = 600000)
	@TestDepth(2)
	public void benchmarkPerPhaseBreakdown() throws Exception {
		setupResultsListener();

		log("==========================================================");
		log("  ADDITION 2 — Per-phase kernel breakdown");
		log("  Central density: 64 notes/measure × 32 measures = 2048 notes");
		log("==========================================================");
		log("");

		int totalNotes = 64 * MEASURES_PER_TICK;

		PackedCollection envelope = buildAdsrEnvelope();
		PackedCollection lpCoeffs = buildLpCoeffs();
		PackedCollection accumBuffer = new PackedCollection(NOTE_SIZE);
		PackedCollection source = buildRandomSource(SOURCE_SIZE);

		// Pre-compute intermediate results for kernels 2-4 (fine in test/benchmark code)
		log("Pre-computing intermediate buffers for isolated kernel timing...");
		PackedCollection resampledInput = buildResampleOnly(source).evaluate();
		PackedCollection volumedInput = buildVolumeOnly(resampledInput, envelope).evaluate();
		PackedCollection filteredInput = buildFirOnly(volumedInput, lpCoeffs).evaluate();
		log("Intermediate buffers ready.");
		log("");

		// Compile each kernel
		log("Compiling individual kernels...");
		Evaluable<PackedCollection> k1 = buildResampleOnly(source);
		Evaluable<PackedCollection> k2 = buildVolumeOnly(resampledInput, envelope);
		Evaluable<PackedCollection> k3 = buildFirOnly(volumedInput, lpCoeffs);
		Evaluable<PackedCollection> k4 = buildAccumulateOnly(filteredInput, accumBuffer);
		log("Compilation complete.");
		log("");

		// Warmup each kernel
		int warmupNotes = 64 * 5;
		log("Warming up each kernel (" + warmupNotes + " notes)...");
		for (int w = 0; w < warmupNotes; w++) {
			k1.evaluate();
			k2.evaluate();
			k3.evaluate();
			k4.evaluate();
		}
		log("Warmup complete.");
		log("");

		// Time each kernel for totalNotes evaluations
		log("--- Per-phase timing at " + totalNotes + " notes (64 notes/m × 32 m) ---");
		log("");

		long[] k1Times = runTimedIterations(k1, totalNotes);
		double k1MeanMs = computeTimingStats(k1Times)[0];
		log("Kernel 1 (resample 2048→1024, linear lerp):");
		reportPhaseStats(k1Times, totalNotes);

		long[] k2Times = runTimedIterations(k2, totalNotes);
		double k2MeanMs = computeTimingStats(k2Times)[0];
		log("Kernel 2 (volume envelope multiply):");
		reportPhaseStats(k2Times, totalNotes);

		long[] k3Times = runTimedIterations(k3, totalNotes);
		double k3MeanMs = computeTimingStats(k3Times)[0];
		log("Kernel 3 (lowpass FIR, order=" + FILTER_ORDER + ", cutoff=" + (int) LP_CUTOFF_HZ + " Hz):");
		reportPhaseStats(k3Times, totalNotes);

		long[] k4Times = runTimedIterations(k4, totalNotes);
		double k4MeanMs = computeTimingStats(k4Times)[0];
		log("Kernel 4 (accumulate into output buffer):");
		reportPhaseStats(k4Times, totalNotes);

		double sumMs = k1MeanMs + k2MeanMs + k3MeanMs + k4MeanMs;

		log("--- Per-phase summary table ---");
		log("");
		log(String.format("  %-40s %8s %8s", "Kernel", "Mean(ms)", "% of sum"));
		log(String.format("  %-40s %8s %8s", "------", "--------", "--------"));
		log(formatPhaseRow("resample (2048→1024, lerp)", k1MeanMs, sumMs));
		log(formatPhaseRow("volume envelope multiply", k2MeanMs, sumMs));
		log(formatPhaseRow("lowpass FIR (order=" + FILTER_ORDER + ")", k3MeanMs, sumMs));
		log(formatPhaseRow("accumulate (add to buffer)", k4MeanMs, sumMs));
		log(String.format("  %-40s %8.2f %8s", "SUM", sumMs, "100%"));
		log("");
		log("Combined-chain baseline at 2048 notes: ~1804ms (from Dimension 1).");
		log("Sum-of-phases vs combined: " + String.format("%.0f", sumMs) + " ms vs 1804ms.");
		log("If sum < combined: kernel fusion reducing intermediate buffers in composed chain.");
		log("If sum > combined: per-phase overhead (separate evaluables) adds overhead.");
		log("==========================================================");
		log("Results appended to: " + RESULTS_PATH);
	}

	private Evaluable<PackedCollection> buildResampleOnly(PackedCollection source) {
		return buildResampleProducer(source).get();
	}

	private Evaluable<PackedCollection> buildVolumeOnly(PackedCollection resampledInput,
														PackedCollection envelope) {
		return cp(resampledInput).multiply(cp(envelope)).get();
	}

	private Evaluable<PackedCollection> buildFirOnly(PackedCollection volumedInput,
													 PackedCollection lpCoeffs) {
		CollectionProducer filtered = MultiOrderFilter.create(
				traverseEach(cp(volumedInput)), cp(lpCoeffs));
		return c(filtered).reshape(shape(NOTE_SIZE)).get();
	}

	private Evaluable<PackedCollection> buildAccumulateOnly(PackedCollection filteredInput,
															PackedCollection accumBuffer) {
		return cp(filteredInput).add(cp(accumBuffer)).get();
	}

	private void reportPhaseStats(long[] timesNs, int totalNotes) {
		double[] stats = computeTimingStats(timesNs);
		double meanMs = stats[0];
		log(String.format("  Mean=%.2fms  Median=%.2fms  P95=%.2fms  Per-note=%.4fms",
				meanMs, stats[1], stats[2], meanMs / totalNotes));
		log("");
	}

	/**
	 * Computes mean, median, and p95 (in milliseconds) for the given nanosecond
	 * timing samples. Returns {@code [meanMs, medianMs, p95Ms]} for use by the
	 * three reporting helpers ({@link #reportStats}, {@link #reportPhaseStats},
	 * {@link #reportBatchedStats}).
	 */
	private static double[] computeTimingStats(long[] timesNs) {
		long[] sorted = timesNs.clone();
		Arrays.sort(sorted);
		double meanMs = Arrays.stream(timesNs).average().orElse(0) / 1_000_000.0;
		double medianMs = sorted[sorted.length / 2] / 1_000_000.0;
		int p95Index = (int) Math.min(Math.ceil(sorted.length * 0.95) - 1, sorted.length - 1);
		double p95Ms = sorted[p95Index] / 1_000_000.0;
		return new double[]{meanMs, medianMs, p95Ms};
	}

	private String formatPhaseRow(String label, double meanMs, double sumMs) {
		double pct = sumMs > 0 ? (meanMs / sumMs) * 100.0 : 0.0;
		return String.format("  %-40s %8.2f %7.1f%%", label, meanMs, pct);
	}

	// ===========================================================================================
	// ADDITION 3 — Batched ceiling measurement
	// ===========================================================================================

	/**
	 * Measures the batched form of the four-kernel chain: a single {@link CollectionProducer}
	 * that processes all N notes for a given density in one {@code evaluate()} call rather than
	 * N sequential calls.
	 *
	 * <p>The batched chain covers kernels 1 (resample), 2 (volume envelope), and 4 (accumulate)
	 * over a flat {@code [N × NOTE_SIZE]} output. Kernel 3 ({@link MultiOrderFilter}) is
	 * <em>excluded</em> from the batched form because its convolution requires sequential
	 * per-sample state within each signal: naively applying {@code traverseEach} to a
	 * {@code [N, NOTE_SIZE]} 2D tensor would concatenate all N signals into one, causing
	 * FIR state to bleed across note boundaries. A properly batched FIR would require
	 * per-row processing semantics that {@link MultiOrderFilter#create} does not currently
	 * expose. This limitation is documented as a finding.</p>
	 *
	 * <p>The amortized-per-note cost from the batched form (batched-total / N) vs the
	 * sequential per-note cost from {@link #benchmarkNotesPerMeasureSingleLayer} gives the
	 * setup-cost amortization. On JNI/CPU the amortization is real (fewer JNI boundary
	 * crossings) even without GPU parallelism.</p>
	 */
	@Test(timeout = 600000)
	@TestDepth(2)
	public void benchmarkBatchedCeiling() throws Exception {
		setupResultsListener();

		log("==========================================================");
		log("  ADDITION 3 — Batched ceiling measurement");
		log("  One evaluate() per density level (not N sequential calls)");
		log("  Kernels: resample + volume + accumulate (3 of 4)");
		log("  FIR excluded — see note below on batching constraint");
		log("==========================================================");
		log("");
		log("FIR BATCHING NOTE: MultiOrderFilter uses sequential per-sample convolution state.");
		log("Applying traverseEach to a [N, NOTE_SIZE] tensor concatenates all N signals,");
		log("causing FIR state to bleed between note boundaries. A proper per-row batched FIR");
		log("would require per-row processing semantics not currently exposed by MultiOrderFilter.");
		log("The 3-kernel batched chain still measures setup amortization for the dominant kernels.");
		log("");

		PackedCollection envelope = buildAdsrEnvelope();
		PackedCollection accumBuffer = new PackedCollection(NOTE_SIZE);
		PackedCollection source = buildRandomSource(SOURCE_SIZE);

		// Sequential 3-kernel baseline (resample + volume + accumulate, no FIR) for comparison
		log("Compiling sequential 3-kernel chain (resample + volume + accumulate, no FIR)...");
		Evaluable<PackedCollection> seqChain3 = buildChain3Kernels(source, envelope, accumBuffer);
		warmupSequential("sequential 3-kernel chain", seqChain3);

		log("--- Sequential 3-kernel baseline (for comparison) ---");
		log("");
		double[] seqPerNoteMs = new double[NOTES_PER_MEASURE_VALUES.length];
		for (int d = 0; d < NOTES_PER_MEASURE_VALUES.length; d++) {
			int npm = NOTES_PER_MEASURE_VALUES[d];
			int totalNotes = npm * MEASURES_PER_TICK;
			long[] timesNs = runTimedIterations(seqChain3, totalNotes);
			seqPerNoteMs[d] = computeTimingStats(timesNs)[0] / totalNotes;
			reportStats(npm, totalNotes, timesNs);
		}

		log("--- Batched ceiling: 1 evaluate() per density level ---");
		log("");

		for (int d = 0; d < NOTES_PER_MEASURE_VALUES.length; d++) {
			int npm = NOTES_PER_MEASURE_VALUES[d];
			int totalNotes = npm * MEASURES_PER_TICK;

			log("Compiling batched chain for " + totalNotes + " notes (" + npm + " notes/m)...");
			long compileStart = System.currentTimeMillis();
			Evaluable<PackedCollection> batchedChain = buildBatchedChain(totalNotes, source, envelope, accumBuffer);
			long compileMs = System.currentTimeMillis() - compileStart;
			log("Compilation: " + compileMs + " ms");

			log("Warming up batched chain...");
			for (int w = 0; w < WARMUP_RUNS; w++) {
				batchedChain.evaluate();
			}
			log("Warmup complete.");

			long[] batchedTimesNs = runTimedIterations(batchedChain, 1);
			reportBatchedStats(npm, totalNotes, batchedTimesNs, seqPerNoteMs[d]);
		}

		log("INTERPRETATION:");
		log("- 'amortized per-note' = batch-total / N; compare to sequential per-note");
		log("- Speedup from batching = sequential-per-note / amortized-per-note");
		log("- On JNI/CPU: speedup > 1x means JNI boundary-crossing overhead was real.");
		log("- On Metal: much larger speedup expected (parallel kernel execution).");
		log("==========================================================");
		log("Results appended to: " + RESULTS_PATH);
	}

	/**
	 * Builds a 3-kernel chain (resample + volume envelope + accumulate, no FIR) for use
	 * as the sequential baseline in {@link #benchmarkBatchedCeiling}.
	 */
	private Evaluable<PackedCollection> buildChain3Kernels(PackedCollection source,
														   PackedCollection envelope,
														   PackedCollection accumBuffer) {
		CollectionProducer resampled = buildResampleProducer(source);
		CollectionProducer volumed = resampled.multiply(cp(envelope));
		return volumed.add(cp(accumBuffer)).get();
	}

	/**
	 * Builds the batched resample + volume-envelope portion of the chain over all
	 * {@code batchSize} notes as a flat {@code [batchSize × NOTE_SIZE]} producer.
	 *
	 * <p>The resample kernel uses modulo/floor arithmetic to map each flat output index
	 * back to a (note, sample) pair: {@code sampleIdx = outputIdx mod NOTE_SIZE} and
	 * {@code srcPos = sampleIdx × RESAMPLE_RATIO}. All notes draw from the same source
	 * (appropriate for a benchmark measuring kernel cost, not content variation). The
	 * volume envelope is tiled {@code batchSize} times to cover all notes.</p>
	 *
	 * <p>Reused by every batched test that starts from the same resample+volume base
	 * ({@link #buildBatchedChain}, {@link #buildBatchedPaddedFirChain}, and the two
	 * loops in {@link #benchmarkBatchedReductionAccumulate}).</p>
	 */
	private CollectionProducer buildBatchedResampleVolume(int batchSize,
														   PackedCollection source,
														   PackedCollection envelope) {
		int totalSamples = batchSize * NOTE_SIZE;

		// Batched resample: map flat output index → within-note sample → source position.
		// All batchSize notes draw from the same source (benchmark uses fixed source).
		CollectionProducer outIdx = integers(0, totalSamples);
		// noteIdx = floor(outIdx / NOTE_SIZE) — which note this output position belongs to
		CollectionProducer noteIdx = floor(outIdx.multiply(c(1.0 / NOTE_SIZE)));
		// sampleIdx = outIdx - noteIdx * NOTE_SIZE — sample position within the note
		CollectionProducer sampleIdx = outIdx.subtract(noteIdx.multiply(c((double) NOTE_SIZE)));
		// Source fractional position for the linear interpolation gather
		CollectionProducer srcPos = sampleIdx.multiply(c(RESAMPLE_RATIO));
		CollectionProducer fPos = floor(srcPos);
		CollectionProducer frac = srcPos.subtract(fPos);
		CollectionProducer batchedS0 = c(shape(totalSamples), cp(source), fPos);
		CollectionProducer batchedS1 = c(shape(totalSamples), cp(source), fPos.add(c(1.0)));
		CollectionProducer batchedResampled = batchedS0.add(frac.multiply(batchedS1.subtract(batchedS0)));

		// Volume envelope: tile the [NOTE_SIZE] envelope batchSize times → [totalSamples]
		CollectionProducer tiledEnvelope = cp(envelope).repeat(batchSize).reshape(shape(totalSamples));
		return batchedResampled.multiply(tiledEnvelope);
	}

	/**
	 * Builds a batched 3-kernel chain (resample + volume + accumulate) over all
	 * {@code batchSize} notes in a single {@link CollectionProducer} covering
	 * {@code [batchSize × NOTE_SIZE]} output positions. The resample+volume base is
	 * delegated to {@link #buildBatchedResampleVolume}; this method appends the
	 * accumulate kernel which adds to a tiled output buffer.
	 */
	private Evaluable<PackedCollection> buildBatchedChain(int batchSize,
														  PackedCollection source,
														  PackedCollection envelope,
														  PackedCollection accumBuffer) {
		int totalSamples = batchSize * NOTE_SIZE;
		CollectionProducer batchedVolumed = buildBatchedResampleVolume(batchSize, source, envelope);

		// Accumulate: add to per-note output buffer (tiled, not reduced — one slot per note-sample)
		CollectionProducer tiledAccum = cp(accumBuffer).repeat(batchSize).reshape(shape(totalSamples));
		return batchedVolumed.add(tiledAccum).get();
	}

	// ===========================================================================================
	// ADDITION 4 — Two-kernel batched chain (volume + accumulate, no resample, no FIR)
	// ===========================================================================================

	/**
	 * Measures the simplest meaningful batched form: volume envelope multiply followed by
	 * accumulate, with no resample and no FIR. Tests whether even a minimal 2-kernel
	 * batched chain captures most of the JNI-dispatch elimination, or whether more kernels
	 * are needed in the chain to amortize setup cost effectively.
	 *
	 * <p>Sequential 2-kernel baseline: each note runs {@code volume*envelope + accumBuffer}
	 * via {@code .evaluate()}. Batched form: all N notes run as one
	 * {@code [N × NOTE_SIZE]} output with one {@code .evaluate()} call.</p>
	 *
	 * <p>If the 2-kernel batched chain delivers a substantial fraction of the 3-kernel
	 * batched speedup (Addition 3, ~172× at 64 notes/m), batching is the right ordering
	 * regardless of the kernel count in the chain — just one batched dispatch per layer
	 * collapses N JNI crossings to 1. If the 2-kernel form is barely faster than
	 * sequential, the JNI overhead per dispatch is much smaller than expected and
	 * batching only pays off when many kernels are folded into one Producer.</p>
	 */
	@Test(timeout = 600000)
	@TestDepth(2)
	public void benchmarkBatched2KernelChain() throws Exception {
		setupResultsListener();

		log("==========================================================");
		log("  ADDITION 4 — Two-kernel batched chain");
		log("  Volume envelope multiply + accumulate (no resample, no FIR)");
		log("==========================================================");
		log("");

		PackedCollection envelope = buildAdsrEnvelope();
		PackedCollection accumBuffer = new PackedCollection(NOTE_SIZE);
		PackedCollection inputNote = buildRandomSource(NOTE_SIZE);

		// Sequential 2-kernel baseline
		log("Compiling sequential 2-kernel chain (volume * envelope + accumulate)...");
		Evaluable<PackedCollection> seqChain2 =
				cp(inputNote).multiply(cp(envelope)).add(cp(accumBuffer)).get();
		warmupSequential("sequential 2-kernel chain", seqChain2);

		log("--- Sequential 2-kernel baseline (for comparison) ---");
		log("");
		double[] seqPerNoteMs = new double[NOTES_PER_MEASURE_VALUES.length];
		for (int d = 0; d < NOTES_PER_MEASURE_VALUES.length; d++) {
			int npm = NOTES_PER_MEASURE_VALUES[d];
			int totalNotes = npm * MEASURES_PER_TICK;
			long[] timesNs = runTimedIterations(seqChain2, totalNotes);
			seqPerNoteMs[d] = computeTimingStats(timesNs)[0] / totalNotes;
			reportStats(npm, totalNotes, timesNs);
		}

		log("--- Batched 2-kernel ceiling: 1 evaluate() per density level ---");
		log("");

		for (int d = 0; d < NOTES_PER_MEASURE_VALUES.length; d++) {
			int npm = NOTES_PER_MEASURE_VALUES[d];
			int totalNotes = npm * MEASURES_PER_TICK;

			log("Compiling batched 2-kernel chain for " + totalNotes + " notes...");
			long compileStart = System.currentTimeMillis();
			int totalSamples = totalNotes * NOTE_SIZE;
			// Tile both inputs to [totalSamples], multiply, then add tiled accumBuffer.
			CollectionProducer tiledInput = cp(inputNote).repeat(totalNotes).reshape(shape(totalSamples));
			CollectionProducer tiledEnvelope = cp(envelope).repeat(totalNotes).reshape(shape(totalSamples));
			CollectionProducer tiledAccum = cp(accumBuffer).repeat(totalNotes).reshape(shape(totalSamples));
			Evaluable<PackedCollection> batchedChain2 =
					tiledInput.multiply(tiledEnvelope).add(tiledAccum).get();
			long compileMs = System.currentTimeMillis() - compileStart;
			log("Compilation: " + compileMs + " ms");

			log("Warming up batched 2-kernel chain...");
			for (int w = 0; w < WARMUP_RUNS; w++) {
				batchedChain2.evaluate();
			}
			log("Warmup complete.");

			long[] batchedTimesNs = runTimedIterations(batchedChain2, 1);
			reportBatchedStats(npm, totalNotes, batchedTimesNs, seqPerNoteMs[d]);
		}

		log("INTERPRETATION:");
		log("- Compare 2-kernel batched speedup to 3-kernel batched speedup (Addition 3).");
		log("- If 2-kernel speedup is similar to 3-kernel speedup, JNI dispatch is the cost driver");
		log("  regardless of chain length — batching ANY chain delivers the win.");
		log("- If 2-kernel speedup is substantially lower, the win requires longer chains to");
		log("  amortize setup cost. (Less likely given Addition 2's ~43% fusion benefit.)");
		log("==========================================================");
		log("Results appended to: " + RESULTS_PATH);
	}

	// ===========================================================================================
	// ADDITION 5 — Padded batched FIR — addresses the FIR batching question
	// ===========================================================================================

	/**
	 * Tests the hypothesis that {@link MultiOrderFilter} can be applied to a batched
	 * {@code [N, NOTE_SIZE + filterOrder]} input where each row has been zero-padded by
	 * {@code filterOrder/2} on each side, with the bleed across row boundaries falling
	 * into the padded zeros rather than into adjacent-note audio.
	 *
	 * <p>Background: {@link MultiOrderFilter#getScope} uses {@code kernel(context)} as a
	 * flat index across the entire input array, with boundary check
	 * {@code index >= 0 && index < input.length()}. For a 2D {@code [N, M]} input with
	 * {@code traverse(1)}, samples within {@code ±filterOrder/2} of a row boundary index
	 * into the adjacent row — which contains the previous note's audio. With each row
	 * pre-padded by {@code filterOrder/2} zeros on both sides, the boundary indices
	 * instead read into the padded zeros (still within the same row's slot in the flat
	 * array), preserving acoustic independence per note.</p>
	 *
	 * <p>This benchmark measures (a) does it run at all without a stateful-FIR error, and
	 * (b) what is the per-tick time of a 4-kernel batched chain when FIR is included via
	 * the padded approach? If the timing is comparable to the 3-kernel batched ceiling,
	 * FIR can be batched in production without modifying {@link MultiOrderFilter}.</p>
	 */
	@Test(timeout = 600000)
	@TestDepth(2)
	public void benchmarkBatchedChainWithPaddedFir() throws Exception {
		setupResultsListener();

		log("==========================================================");
		log("  ADDITION 5 — Batched 4-kernel chain with padded FIR");
		log("  Tests: can FIR be batched if each row is padded with");
		log("  filterOrder/2 zeros on both sides to absorb cross-row bleed?");
		log("==========================================================");
		log("");

		PackedCollection envelope = buildAdsrEnvelope();
		PackedCollection lpCoeffs = buildLpCoeffs();
		PackedCollection source = buildRandomSource(SOURCE_SIZE);

		int padHalf = FILTER_ORDER / 2;
		int paddedNoteSize = NOTE_SIZE + 2 * padHalf;
		log("Pad layout: each row = " + padHalf + " zeros + " + NOTE_SIZE
				+ " samples + " + padHalf + " zeros = " + paddedNoteSize + " elements");
		log("");

		for (int d = 0; d < NOTES_PER_MEASURE_VALUES.length; d++) {
			int npm = NOTES_PER_MEASURE_VALUES[d];
			int totalNotes = npm * MEASURES_PER_TICK;

			log("Compiling padded batched 4-kernel chain for " + totalNotes + " notes...");
			long compileStart = System.currentTimeMillis();
			Evaluable<PackedCollection> batchedChain;
			try {
				batchedChain = buildBatchedPaddedFirChain(totalNotes, padHalf,
						source, envelope, lpCoeffs);
			} catch (Exception ex) {
				log("FAILED to compile batched padded FIR chain at " + totalNotes + " notes: "
						+ ex.getClass().getSimpleName() + ": " + ex.getMessage());
				log("This is a finding — documents the limit of the padded approach.");
				log("");
				continue;
			}
			long compileMs = System.currentTimeMillis() - compileStart;
			log("Compilation: " + compileMs + " ms");

			log("Warming up...");
			for (int w = 0; w < WARMUP_RUNS; w++) {
				batchedChain.evaluate();
			}
			log("Warmup complete.");

			long[] batchedTimesNs = runTimedIterations(batchedChain, 1);
			double[] stats = computeTimingStats(batchedTimesNs);
			double meanMs = stats[0];

			log(String.format("notes_per_measure=%d  (total=%d notes/tick) [BATCHED 4-KERNEL + PADDED FIR]",
					npm, totalNotes));
			log(String.format("  Mean=%.2fms  Median=%.2fms  P95=%.2fms", meanMs, stats[1], stats[2]));
			log(String.format("  Amortized per-note: %.4f ms/note", meanMs / totalNotes));
			log(String.format("  vs %.1fms threshold: %s", THRESHOLD_MS, formatVsThreshold(meanMs)));
			log("");
		}

		log("INTERPRETATION:");
		log("- If timing at 64 notes/m is comparable to 3-kernel batched (~6.39ms),");
		log("  FIR can be added to the batched chain in production via padded rows.");
		log("- If significantly slower, the FIR per-row independent processing is more");
		log("  expensive than the cross-row bleed — alternate strategies needed.");
		log("- Either way, this measures whether MultiOrderFilter can be batched at all");
		log("  in a per-row independent fashion via the padding workaround.");
		log("==========================================================");
		log("Results appended to: " + RESULTS_PATH);
	}

	/**
	 * Builds a batched 3-kernel chain (resample + volume + FIR), with the FIR kernel
	 * applied to a row-padded {@code [batchSize, NOTE_SIZE + 2 * padHalf]} tensor.
	 *
	 * <p>The active audio occupies the central {@code NOTE_SIZE} of each row; the
	 * {@code padHalf} elements at front and back of each row are zeros (created by
	 * {@code pad()}). {@link MultiOrderFilter} reads {@code ±padHalf} samples around
	 * each output position, so cross-row bleed at row boundaries reads into the
	 * pre-zeroed pad zones rather than into adjacent-note audio. This tests whether
	 * the standard {@link MultiOrderFilter} can be batched per-row when the input is
	 * shaped to absorb the cross-row index reach.</p>
	 *
	 * <p>Accumulate is omitted from this chain to isolate the FIR contribution; the
	 * accumulate-as-reduction question is benchmarked separately in
	 * {@link #benchmarkBatchedReductionAccumulate}.</p>
	 */
	private Evaluable<PackedCollection> buildBatchedPaddedFirChain(int batchSize, int padHalf,
																   PackedCollection source,
																   PackedCollection envelope,
																   PackedCollection lpCoeffs) {
		// Resample + volume over the un-padded [batchSize × NOTE_SIZE] flat layout, then
		// reshape into [batchSize, NOTE_SIZE] so each row is one note's audio.
		CollectionProducer batchedVolumed = buildBatchedResampleVolume(batchSize, source, envelope)
				.reshape(shape(batchSize, NOTE_SIZE));

		// Pad each row by padHalf zeros on each side: shape [batchSize, NOTE_SIZE + 2*padHalf].
		// The 0 in the first axis means "no padding on the batch dim"; padHalf on the second
		// pads NOTE_SIZE on both sides.
		CollectionProducer padded = pad(batchedVolumed, 0, padHalf);

		// FIR via MultiOrderFilter on the padded 2D tensor. Boundary reads at row
		// transitions land in the pre-zeroed pad zones rather than adjacent notes.
		CollectionProducer filtered = MultiOrderFilter.create(traverseEach(padded), cp(lpCoeffs));

		return c(filtered).get();
	}

	// ===========================================================================================
	// ADDITION 6 — True scatter-accumulate (sum-along-axis) vs tile-and-add
	// ===========================================================================================

	/**
	 * The existing batched chains in Addition 3/4/5 produce a {@code [N × NOTE_SIZE]}
	 * output: each note keeps its own slot in the output array. In production, all N
	 * notes for a pattern layer's tick must sum into a single {@code [NOTE_SIZE]} buffer.
	 *
	 * <p>This benchmark contrasts two ways of expressing that final reduction:</p>
	 * <ul>
	 *   <li><b>Tile-and-add</b> (current): output is {@code [N, NOTE_SIZE]}. Java still
	 *       has to sum it down to {@code [NOTE_SIZE]} after the kernel returns. Memory:
	 *       {@code O(N × NOTE_SIZE)}.</li>
	 *   <li><b>True reduction</b>: output is {@code [NOTE_SIZE]} directly, computed as
	 *       {@code sum(traverse(0, batchedVolumed))}. Memory: {@code O(NOTE_SIZE)}.</li>
	 * </ul>
	 *
	 * <p>Reduces the per-tick output footprint by a factor of N and confirms that
	 * the framework can express the final accumulate as part of the batched compilation
	 * graph rather than a separate post-processing step.</p>
	 */
	@Test(timeout = 600000)
	@TestDepth(2)
	public void benchmarkBatchedReductionAccumulate() throws Exception {
		setupResultsListener();

		log("==========================================================");
		log("  ADDITION 6 — Tile-and-add vs true reduction accumulate");
		log("  Output shape: [N, NOTE_SIZE] (current) vs [NOTE_SIZE] (true reduce)");
		log("==========================================================");
		log("");

		PackedCollection envelope = buildAdsrEnvelope();
		PackedCollection source = buildRandomSource(SOURCE_SIZE);

		log("--- Tile-and-add (output = [N × NOTE_SIZE]) ---");
		log("");
		double[] tileMeans = new double[NOTES_PER_MEASURE_VALUES.length];
		for (int d = 0; d < NOTES_PER_MEASURE_VALUES.length; d++) {
			int npm = NOTES_PER_MEASURE_VALUES[d];
			int totalNotes = npm * MEASURES_PER_TICK;

			CollectionProducer batchedVolumed = buildBatchedResampleVolume(totalNotes, source, envelope);
			Evaluable<PackedCollection> tileChain = batchedVolumed.get();

			for (int w = 0; w < WARMUP_RUNS; w++) tileChain.evaluate();
			long[] times = runTimedIterations(tileChain, 1);
			tileMeans[d] = computeTimingStats(times)[0];
			log(String.format("notes_per_measure=%d  (total=%d notes) [TILE]  Mean=%.2fms  per-note=%.4fms",
					npm, totalNotes, tileMeans[d], tileMeans[d] / totalNotes));
		}
		log("");

		log("--- True reduction (output = [NOTE_SIZE], sum across notes) ---");
		log("");
		for (int d = 0; d < NOTES_PER_MEASURE_VALUES.length; d++) {
			int npm = NOTES_PER_MEASURE_VALUES[d];
			int totalNotes = npm * MEASURES_PER_TICK;

			CollectionProducer batchedVolumed = buildBatchedResampleVolume(totalNotes, source, envelope);

			// Reduce [N, NOTE_SIZE] to [NOTE_SIZE] by summing across the note axis.
			// Reshape to [N, NOTE_SIZE], permute to [NOTE_SIZE, N], then traverse(1).sum()
			// reduces each row of N notes to a single value, giving [NOTE_SIZE, 1] which
			// is reshaped back to [NOTE_SIZE]. This is the standard "sum across batch
			// axis" pattern in the framework (see ActivationFeatures, AttentionFeatures).
			CollectionProducer reshaped = batchedVolumed.reshape(shape(totalNotes, NOTE_SIZE));
			Evaluable<PackedCollection> reduceChain;
			try {
				CollectionProducer permuted = permute(reshaped, 1, 0);
				reduceChain = permuted.traverse(1).sum().reshape(shape(NOTE_SIZE)).get();
			} catch (Exception ex) {
				log("notes_per_measure=" + npm + " [REDUCE] FAILED to compile: "
						+ ex.getClass().getSimpleName() + ": " + ex.getMessage());
				log("  Tile-and-add fallback recommended; or use weightedSum-based primitives.");
				continue;
			}

			for (int w = 0; w < WARMUP_RUNS; w++) reduceChain.evaluate();
			long[] times = runTimedIterations(reduceChain, 1);
			double reduceMs = computeTimingStats(times)[0];
			log(String.format("notes_per_measure=%d  (total=%d notes) [REDUCE] Mean=%.2fms  vs tile=%.2fms (%.2fx)",
					npm, totalNotes, reduceMs, tileMeans[d], reduceMs / Math.max(tileMeans[d], 0.001)));
		}
		log("");

		log("INTERPRETATION:");
		log("- Tile-and-add output is [N × NOTE_SIZE]: 1024×N elements written per tick.");
		log("- True reduction output is [NOTE_SIZE]: 1024 elements written per tick.");
		log("- If reduction time ~ tile time: framework fuses sum into the kernel — Phase 3");
		log("  can produce a compact [NOTE_SIZE] output buffer per tick, no Java-side post-sum.");
		log("- If reduction time >> tile time: reduction is a separate pass; Phase 3 may need");
		log("  a different reduction primitive or stay with tile-and-add followed by a tight sum.");
		log("==========================================================");
		log("Results appended to: " + RESULTS_PATH);
	}

	private void reportBatchedStats(int notesPerMeasure, int totalNotes, long[] timesNs, double seqPerNoteMs) {
		double[] stats = computeTimingStats(timesNs);
		double meanMs = stats[0];
		double amortizedPerNoteMs = meanMs / totalNotes;
		double speedup = seqPerNoteMs > 0 ? seqPerNoteMs / amortizedPerNoteMs : 0.0;

		log(String.format("notes_per_measure=%d  (total=%d notes/tick) [BATCHED — 1 evaluate()]",
				notesPerMeasure, totalNotes));
		log(String.format("  Mean=%.2fms  Median=%.2fms  P95=%.2fms", meanMs, stats[1], stats[2]));
		log(String.format("  Amortized per-note: %.4f ms/note", amortizedPerNoteMs));
		log(String.format("  Sequential 3-kernel per-note: %.4f ms/note", seqPerNoteMs));
		log(String.format("  Batching speedup: %.2fx %s", speedup,
				speedup >= 1.0 ? "(setup overhead amortized)" : "(batching adds overhead — expected on JNI CPU)"));
		log("");
	}

	// ===========================================================================================
	// ENVELOPE-1 — Volume envelope alone, batched per-row
	// ===========================================================================================

	/**
	 * E1: isolated batched volume envelope kernel — per-row {@code [N, NOTE_SIZE]} audio
	 * multiplied elementwise by per-row {@code [N, NOTE_SIZE]} gain envelopes (each row's
	 * envelope shape independent of every other row's). One {@code evaluate()} per density.
	 *
	 * <p>Compared against a sequential per-note baseline where each note's audio×envelope
	 * runs in its own {@code evaluate()} (mirrors current production cost shape). The
	 * difference between sequential and batched is the JNI-amortization headroom.</p>
	 *
	 * <p>The volume envelope here is pre-materialized as a {@link PackedCollection} of
	 * shape {@code [N, NOTE_SIZE]} — varying ADSR shape per row. Production today uses
	 * {@code AudioProcessingUtils.getVolumeEnv()} which computes the envelope inside the
	 * kernel from 4 scalar ADSR params per note; this benchmark deliberately leaves the
	 * "pre-materialized vs in-kernel" decision for Part 2 of the replanning by measuring
	 * the kernel cost as a pure elementwise multiply against a per-row gain tensor.</p>
	 */
	@Test(timeout = 600000)
	@TestDepth(2)
	public void benchmarkVolumeEnvelopeBatched() throws Exception {
		setupResultsListener();

		log("==========================================================");
		log("  ENVELOPE-1 — Volume envelope alone, batched");
		log("  Input: [N, NOTE_SIZE] audio");
		log("  Envelope: [N, NOTE_SIZE] per-row gain (varied shape per row)");
		log("  Operation: elementwise multiply");
		log("==========================================================");
		log("");

		PackedCollection seqAudio = buildRandomSource(NOTE_SIZE);
		PackedCollection seqEnvelope = buildAdsrEnvelope();

		log("Compiling sequential per-note volume envelope kernel...");
		Evaluable<PackedCollection> seqChain = cp(seqAudio).multiply(cp(seqEnvelope)).get();
		warmupSequential("sequential volume envelope (audio × envelope)", seqChain);

		log("--- Sequential per-note baseline (for comparison) ---");
		log("");
		double[] seqPerNoteMs = new double[NOTES_PER_MEASURE_VALUES.length];
		for (int d = 0; d < NOTES_PER_MEASURE_VALUES.length; d++) {
			int npm = NOTES_PER_MEASURE_VALUES[d];
			int totalNotes = npm * MEASURES_PER_TICK;
			long[] timesNs = runTimedIterations(seqChain, totalNotes);
			seqPerNoteMs[d] = computeTimingStats(timesNs)[0] / totalNotes;
			reportStats(npm, totalNotes, timesNs);
		}

		log("--- Batched volume envelope: 1 evaluate() per density ---");
		log("");
		for (int d = 0; d < NOTES_PER_MEASURE_VALUES.length; d++) {
			int npm = NOTES_PER_MEASURE_VALUES[d];
			int totalNotes = npm * MEASURES_PER_TICK;
			int totalSamples = totalNotes * NOTE_SIZE;

			log("Compiling batched volume envelope for " + totalNotes + " notes...");
			long compileStart = System.currentTimeMillis();
			PackedCollection perRowAudio = buildPerRowAudio(totalNotes);
			PackedCollection perRowEnvelopes = buildPerRowVolumeEnvelopes(totalNotes);

			CollectionProducer flatAudio = cp(perRowAudio).reshape(shape(totalSamples));
			CollectionProducer flatEnv = cp(perRowEnvelopes).reshape(shape(totalSamples));
			Evaluable<PackedCollection> batched = flatAudio.multiply(flatEnv).get();
			long compileMs = System.currentTimeMillis() - compileStart;
			log("Compilation: " + compileMs + " ms");

			log("Warming up batched volume envelope...");
			for (int w = 0; w < WARMUP_RUNS; w++) batched.evaluate();
			log("Warmup complete.");

			long[] batchedTimesNs = runTimedIterations(batched, 1);
			reportBatchedStats(npm, totalNotes, batchedTimesNs, seqPerNoteMs[d]);
		}

		log("INTERPRETATION:");
		log("- Per-row independence holds (each row's envelope is content-independent).");
		log("- Volume envelope is the cheapest of the per-note envelopes — pure elementwise.");
		log("- Compare amortized per-note batched cost to sequential per-note cost for headroom.");
		log("==========================================================");
		log("Results appended to: " + RESULTS_PATH);
	}

	// ===========================================================================================
	// ENVELOPE-2 — Filter envelope alone, batched per-row (per-sample varying cutoff)
	// ===========================================================================================

	/**
	 * E2: isolated batched filter envelope kernel — per-row {@code [N, NOTE_SIZE]} audio,
	 * lowpass-filtered with per-row {@code [N, NOTE_SIZE]} cutoff envelope. Each row gets
	 * a different cutoff value per sample, different envelope shape per row. The filter
	 * primitive used is {@link MultiOrderFilter} via {@code lowPass(...)} — same primitive
	 * production uses inside {@link org.almostrealism.audio.filter.MultiOrderFilterEnvelopeProcessor}.
	 *
	 * <p>To preserve per-row independence under FIR convolution, each row is padded by
	 * {@code FILTER_ORDER/2} zero samples on each side (same workaround as Addition 5 for
	 * FIR batching). The cutoff tensor is padded with edge cutoff values so the filter
	 * sees a continuous control signal across the boundary.</p>
	 *
	 * <p>If this benchmark cannot be built — i.e. the existing {@code lowPass(input,
	 * cutoff, ...)} primitive does not handle a 2D batched cutoff input correctly — that
	 * IS the finding for Part 2. The benchmark catches the exception, logs it, and
	 * documents which primitive is missing. Sequential per-note baseline still runs in
	 * either case so amortization headroom can be estimated.</p>
	 */
	@Test(timeout = 600000)
	@TestDepth(2)
	public void benchmarkFilterEnvelopeBatched() throws Exception {
		setupResultsListener();

		log("==========================================================");
		log("  ENVELOPE-2 — Filter envelope alone, batched");
		log("  Input: [N, NOTE_SIZE] audio");
		log("  Cutoff: [N, NOTE_SIZE] per-row, per-sample (varied per row)");
		log("  Filter: MultiOrderFilter low-pass, order=" + FILTER_ORDER);
		log("==========================================================");
		log("");

		int padHalf = FILTER_ORDER / 2;
		int paddedNoteSize = NOTE_SIZE + 2 * padHalf;

		PackedCollection seqAudio = buildRandomSource(NOTE_SIZE);
		PackedCollection seqCutoff = buildAdsrCutoff(NOTE_SIZE);

		log("Compiling sequential per-note filter envelope kernel (lowPass with per-sample cutoff)...");
		Evaluable<PackedCollection> seqChain;
		try {
			MultiOrderFilter seqFilter = lowPass(traverseEach(cp(seqAudio)), cp(seqCutoff),
					SAMPLE_RATE, FILTER_ORDER);
			seqChain = c(seqFilter).reshape(shape(NOTE_SIZE)).get();
		} catch (Exception ex) {
			log("FAILED to compile sequential per-note lowPass with per-sample cutoff: "
					+ ex.getClass().getSimpleName() + ": " + ex.getMessage());
			log("This is a finding — the lowPass primitive may not accept Producer-valued");
			log("per-sample cutoff in this configuration. Skipping E2.");
			log("==========================================================");
			return;
		}

		warmupSequential("sequential filter envelope (lowPass, per-sample cutoff)", seqChain);

		log("--- Sequential per-note baseline (for comparison) ---");
		log("");
		double[] seqPerNoteMs = new double[NOTES_PER_MEASURE_VALUES.length];
		for (int d = 0; d < NOTES_PER_MEASURE_VALUES.length; d++) {
			int npm = NOTES_PER_MEASURE_VALUES[d];
			int totalNotes = npm * MEASURES_PER_TICK;
			long[] timesNs = runTimedIterations(seqChain, totalNotes);
			seqPerNoteMs[d] = computeTimingStats(timesNs)[0] / totalNotes;
			reportStats(npm, totalNotes, timesNs);
		}

		log("--- Batched filter envelope: 1 evaluate() per density ---");
		log("Pad layout: each row = " + padHalf + " zero samples + " + NOTE_SIZE
				+ " + " + padHalf + " = " + paddedNoteSize + " elements");
		log("");

		for (int d = 0; d < NOTES_PER_MEASURE_VALUES.length; d++) {
			int npm = NOTES_PER_MEASURE_VALUES[d];
			int totalNotes = npm * MEASURES_PER_TICK;

			log("Compiling batched filter envelope for " + totalNotes + " notes...");
			long compileStart;
			Evaluable<PackedCollection> batched;
			try {
				compileStart = System.currentTimeMillis();
				PackedCollection perRowAudio = buildPerRowAudio(totalNotes);
				PackedCollection perRowCutoff = buildPerRowFilterCutoffs(totalNotes);

				int paddedTotal = totalNotes * paddedNoteSize;

				// Pad each row by padHalf samples on each side: [N, NOTE_SIZE+2*padHalf].
				// pad(producer, 0, padHalf) puts 0 padding on axis 0 (batch) and padHalf on axis 1.
				CollectionProducer paddedAudio2D = pad(cp(perRowAudio), 0, padHalf);
				CollectionProducer paddedCutoff2D = pad(cp(perRowCutoff), 0, padHalf);

				// Flatten to 1D so MultiOrderFilter sees one long signal with the per-row
				// boundary samples already absorbed by the pad zones.
				CollectionProducer flatAudio = paddedAudio2D.reshape(shape(paddedTotal));
				CollectionProducer flatCutoff = paddedCutoff2D.reshape(shape(paddedTotal));

				MultiOrderFilter filter = lowPass(traverseEach(flatAudio), flatCutoff,
						SAMPLE_RATE, FILTER_ORDER);
				batched = c(filter).get();
			} catch (Exception ex) {
				log("FAILED to compile batched filter envelope at " + totalNotes + " notes: "
						+ ex.getClass().getSimpleName() + ": " + ex.getMessage());
				log("This is a finding — names what primitive is missing for batched filter envelope.");
				log("");
				continue;
			}
			long compileMs = System.currentTimeMillis() - compileStart;
			log("Compilation: " + compileMs + " ms");

			log("Warming up batched filter envelope...");
			for (int w = 0; w < WARMUP_RUNS; w++) batched.evaluate();
			log("Warmup complete.");

			long[] batchedTimesNs = runTimedIterations(batched, 1);
			reportBatchedStats(npm, totalNotes, batchedTimesNs, seqPerNoteMs[d]);
		}

		log("INTERPRETATION:");
		log("- E2 batched form requires per-row cutoff [N, NOTE_SIZE] flattened to [N*paddedNote].");
		log("- The lowPass primitive accepts a Producer cutoff signal; per-sample variation is");
		log("  driven by the cutoff tensor itself. Per-row independence is preserved by padding.");
		log("- If batched amortizes well below sequential, the per-note JNI cost dominates the");
		log("  filter envelope path just like it does for the resample/volume kernels.");
		log("==========================================================");
		log("Results appended to: " + RESULTS_PATH);
	}

	// ===========================================================================================
	// ENVELOPE-3 — Combined volume + filter envelope chain, batched
	// ===========================================================================================

	/**
	 * E3: combined batched envelope chain — per-row {@code [N, NOTE_SIZE]} audio →
	 * filter envelope (lowpass with per-row per-sample cutoff) → volume envelope multiply
	 * → output. Confirms that the two envelopes compose cleanly under batching.
	 *
	 * <p>Production stack per melodic note (see
	 * {@code studio/music/.../pattern/PatternElementFactory.java:266-271}) wraps the note
	 * audio with the filter envelope first, then the volume envelope on top. When audio
	 * is computed, the outermost (volume) envelope's apply() pulls from the inner
	 * (filter) envelope's apply() which pulls from raw audio — so the effective
	 * application order on raw audio is filter first, then volume. This benchmark
	 * mirrors that order.</p>
	 */
	@Test(timeout = 600000)
	@TestDepth(2)
	public void benchmarkCombinedEnvelopeChainBatched() throws Exception {
		setupResultsListener();

		log("==========================================================");
		log("  ENVELOPE-3 — Combined filter + volume envelope chain, batched");
		log("  Chain: audio -> lowPass(per-row cutoff) -> × volume_envelope");
		log("==========================================================");
		log("");

		int padHalf = FILTER_ORDER / 2;
		int paddedNoteSize = NOTE_SIZE + 2 * padHalf;

		PackedCollection seqAudio = buildRandomSource(NOTE_SIZE);
		PackedCollection seqEnvelope = buildAdsrEnvelope();
		PackedCollection seqCutoff = buildAdsrCutoff(NOTE_SIZE);

		log("Compiling sequential per-note combined chain (lowPass -> × envelope)...");
		Evaluable<PackedCollection> seqChain;
		try {
			MultiOrderFilter seqFilter = lowPass(traverseEach(cp(seqAudio)), cp(seqCutoff),
					SAMPLE_RATE, FILTER_ORDER);
			seqChain = c(seqFilter).reshape(shape(NOTE_SIZE)).multiply(cp(seqEnvelope)).get();
		} catch (Exception ex) {
			log("FAILED to compile sequential combined chain: "
					+ ex.getClass().getSimpleName() + ": " + ex.getMessage());
			log("Skipping E3.");
			log("==========================================================");
			return;
		}

		warmupSequential("sequential combined envelope chain", seqChain);

		log("--- Sequential per-note baseline (for comparison) ---");
		log("");
		double[] seqPerNoteMs = new double[NOTES_PER_MEASURE_VALUES.length];
		for (int d = 0; d < NOTES_PER_MEASURE_VALUES.length; d++) {
			int npm = NOTES_PER_MEASURE_VALUES[d];
			int totalNotes = npm * MEASURES_PER_TICK;
			long[] timesNs = runTimedIterations(seqChain, totalNotes);
			seqPerNoteMs[d] = computeTimingStats(timesNs)[0] / totalNotes;
			reportStats(npm, totalNotes, timesNs);
		}

		log("--- Batched combined envelope chain: 1 evaluate() per density ---");
		log("");

		for (int d = 0; d < NOTES_PER_MEASURE_VALUES.length; d++) {
			int npm = NOTES_PER_MEASURE_VALUES[d];
			int totalNotes = npm * MEASURES_PER_TICK;

			log("Compiling batched combined envelope chain for " + totalNotes + " notes...");
			long compileStart;
			Evaluable<PackedCollection> batched;
			try {
				compileStart = System.currentTimeMillis();
				PackedCollection perRowAudio = buildPerRowAudio(totalNotes);
				PackedCollection perRowEnvelopes = buildPerRowVolumeEnvelopes(totalNotes);
				PackedCollection perRowCutoff = buildPerRowFilterCutoffs(totalNotes);

				int totalSamples = totalNotes * NOTE_SIZE;
				int paddedTotal = totalNotes * paddedNoteSize;

				// Filter envelope first: pad each row, flatten, run lowPass, reshape back to [N, NOTE_SIZE].
				CollectionProducer paddedAudio2D = pad(cp(perRowAudio), 0, padHalf);
				CollectionProducer paddedCutoff2D = pad(cp(perRowCutoff), 0, padHalf);
				CollectionProducer flatPaddedAudio = paddedAudio2D.reshape(shape(paddedTotal));
				CollectionProducer flatPaddedCutoff = paddedCutoff2D.reshape(shape(paddedTotal));
				MultiOrderFilter filter = lowPass(traverseEach(flatPaddedAudio), flatPaddedCutoff,
						SAMPLE_RATE, FILTER_ORDER);
				CollectionProducer filtered2D = c(filter).reshape(shape(totalNotes, paddedNoteSize));

				// Drop the pad on each side: keep only the central NOTE_SIZE samples.
				// shape() arg = [totalNotes, NOTE_SIZE] window starting at axis-1 offset padHalf.
				CollectionProducer trimmed = subset(shape(totalNotes, NOTE_SIZE), filtered2D, 0, padHalf);

				// Volume envelope multiply on the [N*NOTE_SIZE] flat output.
				CollectionProducer flatTrimmed = trimmed.reshape(shape(totalSamples));
				CollectionProducer flatEnv = cp(perRowEnvelopes).reshape(shape(totalSamples));
				batched = flatTrimmed.multiply(flatEnv).get();
			} catch (Exception ex) {
				log("FAILED to compile batched combined chain at " + totalNotes + " notes: "
						+ ex.getClass().getSimpleName() + ": " + ex.getMessage());
				log("");
				continue;
			}
			long compileMs = System.currentTimeMillis() - compileStart;
			log("Compilation: " + compileMs + " ms");

			log("Warming up batched combined chain...");
			for (int w = 0; w < WARMUP_RUNS; w++) batched.evaluate();
			log("Warmup complete.");

			long[] batchedTimesNs = runTimedIterations(batched, 1);
			reportBatchedStats(npm, totalNotes, batchedTimesNs, seqPerNoteMs[d]);
		}

		log("INTERPRETATION:");
		log("- E3 confirms the two envelopes compose under batching.");
		log("- If E3 batched cost ≈ E1 + E2 batched cost, the kernels add linearly.");
		log("- If E3 batched cost < E1 + E2, the framework is fusing envelope + filter.");
		log("==========================================================");
		log("Results appended to: " + RESULTS_PATH);
	}

	// ===========================================================================================
	// Helpers shared by E1, E2, E3
	// ===========================================================================================

	/**
	 * Builds a deterministic per-row audio tensor of shape {@code [batchSize, NOTE_SIZE]}.
	 * Each row gets a different sinusoidal content driven by row index, so per-row
	 * filter independence can be verified visually if the result is dumped — content
	 * does not affect kernel timing.
	 */
	private PackedCollection buildPerRowAudio(int batchSize) {
		double[] data = new double[batchSize * NOTE_SIZE];
		for (int n = 0; n < batchSize; n++) {
			double freq = 220.0 + (n % 64) * 30.0;
			for (int i = 0; i < NOTE_SIZE; i++) {
				data[n * NOTE_SIZE + i] = Math.sin(2.0 * Math.PI * freq * i / SAMPLE_RATE);
			}
		}
		PackedCollection out = new PackedCollection(shape(batchSize, NOTE_SIZE));
		out.setMem(data);
		return out;
	}

	/**
	 * Builds a per-row volume envelope tensor of shape {@code [batchSize, NOTE_SIZE]}.
	 * Each row carries a distinct ADSR shape: attack/decay/release fractions and the
	 * sustain level vary deterministically with row index so no two rows share an
	 * envelope curve.
	 */
	private PackedCollection buildPerRowVolumeEnvelopes(int batchSize) {
		double[] data = new double[batchSize * NOTE_SIZE];
		for (int n = 0; n < batchSize; n++) {
			double sustainLevel = 0.4 + (n % 16) * (0.5 / 16.0);
			double attackFrac = 0.02 + (n % 8) * 0.01;
			double decayFrac = 0.05 + (n % 8) * 0.01;
			double releaseFrac = 0.10 + (n % 8) * 0.02;
			fillAdsrShape(data, n * NOTE_SIZE, NOTE_SIZE,
					0.0, 1.0, sustainLevel, 0.0,
					attackFrac, decayFrac, releaseFrac);
		}
		PackedCollection out = new PackedCollection(shape(batchSize, NOTE_SIZE));
		out.setMem(data);
		return out;
	}

	/**
	 * Builds a single-row ADSR-shaped cutoff envelope of shape {@code [size]} for use
	 * as the sequential per-note filter cutoff. Cutoff varies from 200 Hz at the start
	 * up to a peak of 8000 Hz then back down to 400 Hz, modelling a typical filter
	 * sweep envelope.
	 */
	private PackedCollection buildAdsrCutoff(int size) {
		double[] data = new double[size];
		fillAdsrShape(data, 0, size, 200.0, 8000.0, 1500.0, 400.0, 0.05, 0.10, 0.15);
		PackedCollection out = new PackedCollection(size);
		out.setMem(data);
		return out;
	}

	/**
	 * Builds a per-row filter cutoff envelope tensor of shape
	 * {@code [batchSize, NOTE_SIZE]}. Each row's cutoff envelope has a different peak
	 * frequency and sustain level, so per-row independence is exercised: row N's
	 * filter response at sample {@code i} reads only row N's cutoff at sample {@code i},
	 * never neighbouring rows'.
	 */
	private PackedCollection buildPerRowFilterCutoffs(int batchSize) {
		double[] data = new double[batchSize * NOTE_SIZE];
		for (int n = 0; n < batchSize; n++) {
			double peak = 4000.0 + (n % 16) * 600.0;
			double sustain = 800.0 + (n % 8) * 200.0;
			double base = 150.0 + (n % 4) * 50.0;
			double attackFrac = 0.03 + (n % 8) * 0.005;
			double decayFrac = 0.08 + (n % 8) * 0.005;
			double releaseFrac = 0.12 + (n % 8) * 0.005;
			fillAdsrShape(data, n * NOTE_SIZE, NOTE_SIZE,
					base, peak, sustain, base,
					attackFrac, decayFrac, releaseFrac);
		}
		PackedCollection out = new PackedCollection(shape(batchSize, NOTE_SIZE));
		out.setMem(data);
		return out;
	}

}
