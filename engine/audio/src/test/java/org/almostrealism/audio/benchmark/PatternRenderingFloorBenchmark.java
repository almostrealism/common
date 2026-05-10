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
		new File("results").mkdirs();
		String resultsPath = "results/pattern-rendering-floor.txt";
		Console.root().addListener(OutputFeatures.fileOutput(resultsPath));

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
		double[] lpCoeffsData = referenceLowPassCoefficients(LP_CUTOFF_HZ, SAMPLE_RATE, FILTER_ORDER);
		PackedCollection lpCoeffs = new PackedCollection(FILTER_ORDER + 1);
		lpCoeffs.setMem(lpCoeffsData);
		PackedCollection accumBuffer = new PackedCollection(NOTE_SIZE);
		Random rng = new Random(12345L);
		PackedCollection source = createSignal(SOURCE_SIZE, i -> rng.nextDouble() * 2.0 - 1.0);

		log("Compiling four-kernel chain (no backprop — inference path)...");
		long compileStart = System.currentTimeMillis();
		Evaluable<PackedCollection> compiled = buildChain(source, envelope, lpCoeffs, accumBuffer).get();
		long compileMs = System.currentTimeMillis() - compileStart;
		log("Compilation time: " + compileMs + " ms");
		log("");

		int warmupNotes = MEASURES_PER_TICK * 16;
		log("Warming up (" + warmupNotes + " notes × " + WARMUP_RUNS + " passes)...");
		for (int w = 0; w < WARMUP_RUNS; w++) {
			for (int n = 0; n < warmupNotes; n++) {
				compiled.evaluate();
			}
		}
		log("Warmup complete.");
		log("");

		log("--- Dimension 1: notes_per_measure × 32 measures, 1 layer, monophonic ---");
		log("");

		int[] notesPerMeasureValues = {16, 32, 64, 128, 256};
		for (int npm : notesPerMeasureValues) {
			int totalNotes = npm * MEASURES_PER_TICK;
			long[] timesNs = runTimedIterations(compiled, totalNotes);
			reportStats(npm, totalNotes, timesNs);
		}

		log("==========================================================");
		log("Results written to: " + resultsPath);
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
		// Positions 0..NOTE_SIZE-1 scaled by RESAMPLE_RATIO map into the source array.
		CollectionProducer srcPos = integers(0, NOTE_SIZE)
				.multiply(c(RESAMPLE_RATIO));
		CollectionProducer fPos = floor(srcPos);
		CollectionProducer frac = srcPos.subtract(fPos);
		CollectionProducer s0 = c(shape(NOTE_SIZE), cp(source), fPos);
		CollectionProducer s1 = c(shape(NOTE_SIZE), cp(source), fPos.add(c(1.0)));
		CollectionProducer resampled = s0.add(frac.multiply(s1.subtract(s0)));

		// Kernel 2: volume envelope — elementwise multiply by ADSR shape
		CollectionProducer volumed = resampled.multiply(cp(envelope));

		// Kernel 3: lowpass FIR filter with precomputed coefficients
		// traverseEach makes the 1D signal traverse element-by-element as MultiOrderFilter expects
		CollectionProducer filtered = MultiOrderFilter.create(traverseEach(volumed), cp(lpCoeffs));

		// Kernel 4: accumulate into output buffer (elementwise add)
		// Reshape to NOTE_SIZE in case MultiOrderFilter changes traversal axis
		return c(filtered).reshape(shape(NOTE_SIZE)).add(cp(accumBuffer));
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
		long[] sorted = timesNs.clone();
		Arrays.sort(sorted);

		double meanMs = Arrays.stream(timesNs).average().orElse(0) / 1_000_000.0;
		double medianMs = sorted[sorted.length / 2] / 1_000_000.0;
		int p95Index = (int) Math.min(Math.ceil(sorted.length * 0.95) - 1, sorted.length - 1);
		double p95Ms = sorted[p95Index] / 1_000_000.0;
		double variance = Arrays.stream(timesNs).mapToDouble(t -> {
			double diff = t / 1_000_000.0 - meanMs;
			return diff * diff;
		}).average().orElse(0);
		double stdMs = Math.sqrt(variance);

		double thresholdMs = 92.9;
		String vsThreshold;
		if (meanMs < thresholdMs) {
			vsThreshold = String.format("BELOW (%.1fx headroom)", thresholdMs / meanMs);
		} else {
			vsThreshold = String.format("ABOVE (%.1fx overhead)", meanMs / thresholdMs);
		}

		log(String.format("notes_per_measure=%d  (total=%d notes/tick)", notesPerMeasure, totalNotes));
		log(String.format("  Mean=%.2fms  Median=%.2fms  P95=%.2fms  StdDev=%.2fms",
				meanMs, medianMs, p95Ms, stdMs));
		log(String.format("  Per-note: %.4f ms avg", meanMs / totalNotes));
		log(String.format("  vs 92.9ms threshold: %s", vsThreshold));
		log("");
	}

	private PackedCollection buildAdsrEnvelope() {
		double[] data = new double[NOTE_SIZE];
		int attackSamples = (int) (NOTE_SIZE * 0.05);
		int decaySamples = (int) (NOTE_SIZE * 0.10);
		int releaseSamples = (int) (NOTE_SIZE * 0.15);
		int sustainSamples = NOTE_SIZE - attackSamples - decaySamples - releaseSamples;
		double sustainLevel = 0.7;
		int idx = 0;
		for (int i = 0; i < attackSamples; i++) {
			data[idx++] = (double) i / attackSamples;
		}
		for (int i = 0; i < decaySamples; i++) {
			data[idx++] = 1.0 - (1.0 - sustainLevel) * i / decaySamples;
		}
		for (int i = 0; i < sustainSamples; i++) {
			data[idx++] = sustainLevel;
		}
		for (int i = 0; i < releaseSamples; i++) {
			data[idx++] = sustainLevel * (1.0 - (double) i / releaseSamples);
		}
		PackedCollection env = new PackedCollection(NOTE_SIZE);
		env.setMem(data);
		return env;
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
		new File("results").mkdirs();
		String resultsPath = "results/pattern-rendering-floor.txt";
		Console.root().addListener(OutputFeatures.fileOutput(resultsPath));

		log("==========================================================");
		log("  ADDITION 1 — Optimization-applied measurement");
		log("  Process.optimized() applied before .get()");
		log("  Baseline: ~0.87 ms/note (sequential JNI)");
		log("==========================================================");
		log("");

		PackedCollection envelope = buildAdsrEnvelope();
		double[] lpCoeffsData = referenceLowPassCoefficients(LP_CUTOFF_HZ, SAMPLE_RATE, FILTER_ORDER);
		PackedCollection lpCoeffs = new PackedCollection(FILTER_ORDER + 1);
		lpCoeffs.setMem(lpCoeffsData);
		PackedCollection accumBuffer = new PackedCollection(NOTE_SIZE);
		Random rng = new Random(12345L);
		PackedCollection source = createSignal(SOURCE_SIZE, i -> rng.nextDouble() * 2.0 - 1.0);

		log("Compiling optimized four-kernel chain (Process.optimized before .get())...");
		long compileStart = System.currentTimeMillis();
		Evaluable<PackedCollection> compiled = (Evaluable<PackedCollection>)
				Process.optimized(buildChain(source, envelope, lpCoeffs, accumBuffer)).get();
		long compileMs = System.currentTimeMillis() - compileStart;
		log("Compilation time: " + compileMs + " ms");
		log("");

		int warmupNotes = MEASURES_PER_TICK * 16;
		log("Warming up (" + warmupNotes + " notes × " + WARMUP_RUNS + " passes)...");
		for (int w = 0; w < WARMUP_RUNS; w++) {
			for (int n = 0; n < warmupNotes; n++) {
				compiled.evaluate();
			}
		}
		log("Warmup complete.");
		log("");

		log("--- Addition 1: optimized chain, notes_per_measure × 32 measures, 1 layer ---");
		log("");

		int[] notesPerMeasureValues = {16, 32, 64, 128, 256};
		for (int npm : notesPerMeasureValues) {
			int totalNotes = npm * MEASURES_PER_TICK;
			long[] timesNs = runTimedIterations(compiled, totalNotes);
			reportStats(npm, totalNotes, timesNs);
		}

		log("Note: baseline (no Process.optimized) per-note cost was ~0.87 ms.");
		log("If the above per-note cost matches that, optimization has no effect on sequential JNI.");
		log("==========================================================");
		log("Results appended to: " + resultsPath);
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
		new File("results").mkdirs();
		String resultsPath = "results/pattern-rendering-floor.txt";
		Console.root().addListener(OutputFeatures.fileOutput(resultsPath));

		log("==========================================================");
		log("  ADDITION 2 — Per-phase kernel breakdown");
		log("  Central density: 64 notes/measure × 32 measures = 2048 notes");
		log("==========================================================");
		log("");

		int totalNotes = 64 * MEASURES_PER_TICK;

		PackedCollection envelope = buildAdsrEnvelope();
		double[] lpCoeffsData = referenceLowPassCoefficients(LP_CUTOFF_HZ, SAMPLE_RATE, FILTER_ORDER);
		PackedCollection lpCoeffs = new PackedCollection(FILTER_ORDER + 1);
		lpCoeffs.setMem(lpCoeffsData);
		PackedCollection accumBuffer = new PackedCollection(NOTE_SIZE);
		Random rng = new Random(12345L);
		PackedCollection source = createSignal(SOURCE_SIZE, i -> rng.nextDouble() * 2.0 - 1.0);

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
		double k1MeanMs = Arrays.stream(k1Times).average().orElse(0) / 1_000_000.0;
		log("Kernel 1 (resample 2048→1024, linear lerp):");
		reportPhaseStats(k1Times, totalNotes);

		long[] k2Times = runTimedIterations(k2, totalNotes);
		double k2MeanMs = Arrays.stream(k2Times).average().orElse(0) / 1_000_000.0;
		log("Kernel 2 (volume envelope multiply):");
		reportPhaseStats(k2Times, totalNotes);

		long[] k3Times = runTimedIterations(k3, totalNotes);
		double k3MeanMs = Arrays.stream(k3Times).average().orElse(0) / 1_000_000.0;
		log("Kernel 3 (lowpass FIR, order=" + FILTER_ORDER + ", cutoff=" + (int) LP_CUTOFF_HZ + " Hz):");
		reportPhaseStats(k3Times, totalNotes);

		long[] k4Times = runTimedIterations(k4, totalNotes);
		double k4MeanMs = Arrays.stream(k4Times).average().orElse(0) / 1_000_000.0;
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
		log("Results appended to: " + resultsPath);
	}

	private Evaluable<PackedCollection> buildResampleOnly(PackedCollection source) {
		CollectionProducer srcPos = integers(0, NOTE_SIZE).multiply(c(RESAMPLE_RATIO));
		CollectionProducer fPos = floor(srcPos);
		CollectionProducer frac = srcPos.subtract(fPos);
		CollectionProducer s0 = c(shape(NOTE_SIZE), cp(source), fPos);
		CollectionProducer s1 = c(shape(NOTE_SIZE), cp(source), fPos.add(c(1.0)));
		return s0.add(frac.multiply(s1.subtract(s0))).get();
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
		long[] sorted = timesNs.clone();
		Arrays.sort(sorted);
		double meanMs = Arrays.stream(timesNs).average().orElse(0) / 1_000_000.0;
		double medianMs = sorted[sorted.length / 2] / 1_000_000.0;
		int p95Index = (int) Math.min(Math.ceil(sorted.length * 0.95) - 1, sorted.length - 1);
		double p95Ms = sorted[p95Index] / 1_000_000.0;
		log(String.format("  Mean=%.2fms  Median=%.2fms  P95=%.2fms  Per-note=%.4fms",
				meanMs, medianMs, p95Ms, meanMs / totalNotes));
		log("");
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
		new File("results").mkdirs();
		String resultsPath = "results/pattern-rendering-floor.txt";
		Console.root().addListener(OutputFeatures.fileOutput(resultsPath));

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
		Random rng = new Random(12345L);
		PackedCollection source = createSignal(SOURCE_SIZE, i -> rng.nextDouble() * 2.0 - 1.0);

		int[] notesPerMeasureValues = {16, 32, 64, 128, 256};

		// Sequential 3-kernel baseline (resample + volume + accumulate, no FIR) for comparison
		log("Compiling sequential 3-kernel chain (resample + volume + accumulate, no FIR)...");
		Evaluable<PackedCollection> seqChain3 = buildChain3Kernels(source, envelope, accumBuffer);
		int warmupNotes = MEASURES_PER_TICK * 16;
		log("Warming up sequential 3-kernel chain...");
		for (int w = 0; w < WARMUP_RUNS; w++) {
			for (int n = 0; n < warmupNotes; n++) {
				seqChain3.evaluate();
			}
		}
		log("Warmup complete.");
		log("");

		log("--- Sequential 3-kernel baseline (for comparison) ---");
		log("");
		double[] seqPerNoteMs = new double[notesPerMeasureValues.length];
		for (int d = 0; d < notesPerMeasureValues.length; d++) {
			int npm = notesPerMeasureValues[d];
			int totalNotes = npm * MEASURES_PER_TICK;
			long[] timesNs = runTimedIterations(seqChain3, totalNotes);
			seqPerNoteMs[d] = Arrays.stream(timesNs).average().orElse(0) / 1_000_000.0 / totalNotes;
			reportStats(npm, totalNotes, timesNs);
		}

		log("--- Batched ceiling: 1 evaluate() per density level ---");
		log("");

		for (int d = 0; d < notesPerMeasureValues.length; d++) {
			int npm = notesPerMeasureValues[d];
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

			long[] batchedTimesNs = runBatchedTimedIterations(batchedChain);
			reportBatchedStats(npm, totalNotes, batchedTimesNs, seqPerNoteMs[d]);
		}

		log("INTERPRETATION:");
		log("- 'amortized per-note' = batch-total / N; compare to sequential per-note");
		log("- Speedup from batching = sequential-per-note / amortized-per-note");
		log("- On JNI/CPU: speedup > 1x means JNI boundary-crossing overhead was real.");
		log("- On Metal: much larger speedup expected (parallel kernel execution).");
		log("==========================================================");
		log("Results appended to: " + resultsPath);
	}

	/**
	 * Builds a 3-kernel chain (resample + volume envelope + accumulate, no FIR) for use
	 * as the sequential baseline in {@link #benchmarkBatchedCeiling}.
	 */
	private Evaluable<PackedCollection> buildChain3Kernels(PackedCollection source,
														   PackedCollection envelope,
														   PackedCollection accumBuffer) {
		CollectionProducer srcPos = integers(0, NOTE_SIZE).multiply(c(RESAMPLE_RATIO));
		CollectionProducer fPos = floor(srcPos);
		CollectionProducer frac = srcPos.subtract(fPos);
		CollectionProducer s0 = c(shape(NOTE_SIZE), cp(source), fPos);
		CollectionProducer s1 = c(shape(NOTE_SIZE), cp(source), fPos.add(c(1.0)));
		CollectionProducer resampled = s0.add(frac.multiply(s1.subtract(s0)));
		CollectionProducer volumed = resampled.multiply(cp(envelope));
		return volumed.add(cp(accumBuffer)).get();
	}

	/**
	 * Builds a batched 3-kernel chain (resample + volume + accumulate) over all
	 * {@code batchSize} notes in a single {@link CollectionProducer} covering
	 * {@code [batchSize × NOTE_SIZE]} output positions.
	 *
	 * <p>The resample kernel uses modulo/floor arithmetic to map each flat output index
	 * back to a (note, sample) pair: {@code sampleIdx = outputIdx mod NOTE_SIZE} and
	 * {@code srcPos = sampleIdx × RESAMPLE_RATIO}. All notes draw from the same source
	 * (appropriate for a benchmark measuring kernel cost, not content variation). The
	 * volume envelope is tiled {@code batchSize} times to cover all notes. The accumulate
	 * kernel adds to a similarly tiled output buffer.</p>
	 */
	private Evaluable<PackedCollection> buildBatchedChain(int batchSize,
														  PackedCollection source,
														  PackedCollection envelope,
														  PackedCollection accumBuffer) {
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
		CollectionProducer batchedVolumed = batchedResampled.multiply(tiledEnvelope);

		// Accumulate: add to per-note output buffer (tiled, not reduced — one slot per note-sample)
		CollectionProducer tiledAccum = cp(accumBuffer).repeat(batchSize).reshape(shape(totalSamples));
		CollectionProducer batchedOutput = batchedVolumed.add(tiledAccum);

		return batchedOutput.get();
	}

	private long[] runBatchedTimedIterations(Evaluable<PackedCollection> compiled) {
		long[] results = new long[TIMED_RUNS];
		for (int r = 0; r < TIMED_RUNS; r++) {
			long t0 = System.nanoTime();
			compiled.evaluate();
			results[r] = System.nanoTime() - t0;
		}
		return results;
	}

	private void reportBatchedStats(int notesPerMeasure, int totalNotes, long[] timesNs, double seqPerNoteMs) {
		long[] sorted = timesNs.clone();
		Arrays.sort(sorted);
		double meanMs = Arrays.stream(timesNs).average().orElse(0) / 1_000_000.0;
		double medianMs = sorted[sorted.length / 2] / 1_000_000.0;
		int p95Index = (int) Math.min(Math.ceil(sorted.length * 0.95) - 1, sorted.length - 1);
		double p95Ms = sorted[p95Index] / 1_000_000.0;
		double amortizedPerNoteMs = meanMs / totalNotes;
		double speedup = seqPerNoteMs > 0 ? seqPerNoteMs / amortizedPerNoteMs : 0.0;

		log(String.format("notes_per_measure=%d  (total=%d notes/tick) [BATCHED — 1 evaluate()]",
				notesPerMeasure, totalNotes));
		log(String.format("  Mean=%.2fms  Median=%.2fms  P95=%.2fms", meanMs, medianMs, p95Ms));
		log(String.format("  Amortized per-note: %.4f ms/note", amortizedPerNoteMs));
		log(String.format("  Sequential 3-kernel per-note: %.4f ms/note", seqPerNoteMs));
		log(String.format("  Batching speedup: %.2fx %s", speedup,
				speedup >= 1.0 ? "(setup overhead amortized)" : "(batching adds overhead — expected on JNI CPU)"));
		log("");
	}

}
