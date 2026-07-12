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

import io.almostrealism.relation.Evaluable;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.audio.BatchedPatternRenderer;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.time.TemporalFeatures;
import org.almostrealism.util.FirFilterTestFeatures;
import org.almostrealism.util.TestSuiteBase;

import java.io.File;
import java.util.Arrays;
import java.util.Random;

/**
 * Shared benchmark infrastructure for the pattern-rendering floor benchmark suite.
 *
 * <p>Provides all constants, timing utilities, ADSR builders, and logging helpers
 * that are shared between {@link PatternRenderingFloorBenchmark} (core kernel
 * benchmarks) and {@link PatternRenderingFloorBenchmarkAdditional} (envelope
 * and Java-side gather benchmarks). Neither subclass duplicates any of this
 * infrastructure.</p>
 *
 * <p>This class is abstract and contains no {@code @Test} methods; concrete
 * subclasses add the test methods appropriate to their domain.</p>
 */
public abstract class PatternRenderingFloorBenchmarkBase extends TestSuiteBase
		implements FirFilterTestFeatures, TemporalFeatures, ConsoleFeatures {

	/** Source samples per note before pitch resampling. */
	protected static final int SOURCE_SIZE = 2048;

	/** Output samples per note after resampling (determines kernel granularity). */
	protected static final int NOTE_SIZE = 1024;

	/** Resample ratio: SOURCE_SIZE / NOTE_SIZE = 2.0 (one octave up). */
	protected static final double RESAMPLE_RATIO = (double) SOURCE_SIZE / NOTE_SIZE;

	/** Audio sample rate — uses {@link OutputLine#sampleRate} so the benchmark adapts to any future default change. */
	protected static final int SAMPLE_RATE = OutputLine.sampleRate;

	/** FIR filter order matching EfxManager.filterOrder. */
	protected static final int FILTER_ORDER = 40;

	/** Measures rendered per tick, matching the profiled 32-measure arrangement. */
	protected static final int MEASURES_PER_TICK = 32;

	/** Low-pass cutoff frequency in Hz for the filter kernel. */
	protected static final double LP_CUTOFF_HZ = 8000.0;

	/** Warmup iterations before timing begins (each iteration = all notes for one tick). */
	protected static final int WARMUP_RUNS = 3;

	/** Timed iterations measured per configuration. */
	protected static final int TIMED_RUNS = 20;

	/**
	 * Sequential warmup count per pass: each warmup iteration evaluates the chain this
	 * many times before timing begins. Sized as 16 notes per measure × {@link #MEASURES_PER_TICK}
	 * measures so warmup exercises a realistic per-tick load.
	 */
	protected static final int WARMUP_NOTES = MEASURES_PER_TICK * 16;

	/** Real-time per-tick budget in milliseconds (32 measures at 60bpm worth of audio). */
	protected static final double THRESHOLD_MS = 92.9;

	/** Output path for the benchmark results log; shared by all test methods. */
	protected static final String RESULTS_PATH = "results/pattern-rendering-floor.txt";

	/**
	 * Shared single-note renderer used to delegate per-note resample-producer
	 * construction to the production {@link BatchedPatternRenderer}, avoiding
	 * a duplicate copy of the resample algorithm in the benchmark subclasses.
	 */
	protected static final BatchedPatternRenderer SINGLE_NOTE_RENDERER =
			new BatchedPatternRenderer(1, SOURCE_SIZE, NOTE_SIZE, SAMPLE_RATE, FILTER_ORDER);

	/**
	 * Notes-per-measure densities measured by every Dimension-1 and batched test:
	 * 16, 32, 64, 128, 256. Combined with {@link #MEASURES_PER_TICK} this produces
	 * total-note counts of 512, 1024, 2048, 4096, 8192 per tick.
	 */
	protected static final int[] NOTES_PER_MEASURE_VALUES = {16, 32, 64, 128, 256};

	/**
	 * Creates the {@code results/} directory and registers a file-output listener
	 * on {@link Console#root()} pointing at {@link #RESULTS_PATH}. Called at the
	 * top of every benchmark test method.
	 */
	protected static void setupResultsListener() {
		new File("results").mkdirs();
		Console.root().addListener(OutputFeatures.fileOutput(RESULTS_PATH));
	}

	/**
	 * Builds the lowpass FIR coefficients used by every chain that includes the
	 * filter kernel: order {@link #FILTER_ORDER}, cutoff {@link #LP_CUTOFF_HZ} Hz,
	 * sample rate {@link #SAMPLE_RATE} Hz.
	 */
	protected PackedCollection buildLpCoeffs() {
		double[] data = referenceLowPassCoefficients(LP_CUTOFF_HZ, SAMPLE_RATE, FILTER_ORDER);
		PackedCollection lpCoeffs = new PackedCollection(FILTER_ORDER + 1);
		a(cp(lpCoeffs), c(data)).get().run();
		return lpCoeffs;
	}

	/**
	 * Builds a random {@code [size]} signal in the range {@code [-1, 1]}, using a
	 * fixed seed so all benchmark runs see the same content (deterministic timing,
	 * no run-to-run content variation).
	 */
	protected PackedCollection buildRandomSource(int size) {
		Random rng = new Random(12345L);
		return createSignal(size, i -> rng.nextDouble() * 2.0 - 1.0);
	}

	/**
	 * Builds the ADSR envelope used by every chain that includes the volume kernel:
	 * a {@code [NOTE_SIZE]} piecewise-linear shape with 5% attack, 10% decay,
	 * 70% sustain level, 15% release.
	 */
	protected PackedCollection buildAdsrEnvelope() {
		double[] data = new double[NOTE_SIZE];
		fillAdsrShape(data, 0, NOTE_SIZE, 0.0, 1.0, 0.7, 0.0, 0.05, 0.10, 0.15);
		PackedCollection env = new PackedCollection(NOTE_SIZE);
		a(cp(env), c(data)).get().run();
		return env;
	}

	/**
	 * Fills a piecewise-linear ADSR shape into {@code data[offset..offset+size]}:
	 * attack ramp from {@code base} to {@code peak}, decay from {@code peak} to
	 * {@code sustain}, held at {@code sustain}, release ramp from {@code sustain}
	 * to {@code end}. ADSR section lengths come from {@code attackFrac},
	 * {@code decayFrac}, {@code releaseFrac} (fractions of {@code size}); the
	 * sustain section fills the remainder.
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

	/**
	 * Formats a "vs {@value #THRESHOLD_MS}ms threshold" comparison string for the
	 * given mean-per-tick measurement: {@code BELOW (Nx headroom)} when under the
	 * real-time budget, {@code ABOVE (Nx overhead)} when over.
	 */
	protected static String formatVsThreshold(double meanMs) {
		return meanMs < THRESHOLD_MS
				? String.format("BELOW (%.1fx headroom)", THRESHOLD_MS / meanMs)
				: String.format("ABOVE (%.1fx overhead)", meanMs / THRESHOLD_MS);
	}

	/**
	 * Performs the standard sequential warmup: {@link #WARMUP_RUNS} passes, each running
	 * {@link #WARMUP_NOTES} sequential {@code evaluate()} calls on the given chain.
	 */
	protected void warmupSequential(String description, Evaluable<PackedCollection> chain) {
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
	 * Runs {@link #TIMED_RUNS} timed iterations of the given chain, each iteration
	 * evaluating it {@code totalNotes} times sequentially, and returns the elapsed
	 * nanoseconds for each iteration.
	 */
	protected long[] runTimedIterations(Evaluable<PackedCollection> compiled, int totalNotes) {
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
	 * Computes mean, median, and p95 (in milliseconds) for the given nanosecond
	 * timing samples. Returns {@code [meanMs, medianMs, p95Ms]}.
	 */
	protected static double[] computeTimingStats(long[] timesNs) {
		long[] sorted = timesNs.clone();
		Arrays.sort(sorted);
		double meanMs = Arrays.stream(timesNs).average().orElse(0) / 1_000_000.0;
		double medianMs = sorted[sorted.length / 2] / 1_000_000.0;
		int p95Index = (int) Math.min(Math.ceil(sorted.length * 0.95) - 1, sorted.length - 1);
		double p95Ms = sorted[p95Index] / 1_000_000.0;
		return new double[]{meanMs, medianMs, p95Ms};
	}

	/**
	 * Logs mean, median, p95, std-dev, per-note, and threshold comparison for one
	 * density-level timing result from a sequential evaluation run.
	 */
	protected void reportStats(int notesPerMeasure, int totalNotes, long[] timesNs) {
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
	 * Logs mean, median, p95, amortized per-note, and batching speedup for one
	 * density-level timing result from a batched (single-evaluate) run.
	 */
	protected void reportBatchedStats(int notesPerMeasure, int totalNotes,
									  long[] timesNs, double seqPerNoteMs) {
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
}
