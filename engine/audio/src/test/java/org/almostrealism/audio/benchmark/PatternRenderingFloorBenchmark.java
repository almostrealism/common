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
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.time.computations.MultiOrderFilter;
import org.almostrealism.util.TestDepth;
import org.junit.Test;


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
 *       fractional-position indices.</li>
 *   <li><b>Volume envelope</b> — element-wise multiply by a precomputed ADSR envelope.</li>
 *   <li><b>Filter envelope</b> — FIR low-pass convolution via {@link MultiOrderFilter}.</li>
 *   <li><b>Sum-into-output-buffer</b> — element-wise add into an accumulation buffer.</li>
 * </ol>
 *
 * <p>This class covers the core kernel benchmarks (Dimension 1 baseline) and
 * Additions 1–6 (optimization settings, per-phase breakdown, batched ceiling,
 * two-kernel batched chain, padded batched FIR, and reduction accumulate).
 * Envelope-specific benchmarks (E1/E2/E3) and the Java-side gather benchmark
 * (Addition 7) are in {@link PatternRenderingFloorBenchmarkAdditional}.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * mvn test -pl engine/audio -Dtest=PatternRenderingFloorBenchmark
 * }</pre>
 *
 * <p>Results are written to {@code engine/audio/results/pattern-rendering-floor.txt}.</p>
 *
 * @see PatternRenderingFloorBenchmarkBase
 * @see PatternRenderingFloorBenchmarkAdditional
 * @see org.almostrealism.time.computations.MultiOrderFilter
 */
public class PatternRenderingFloorBenchmark extends PatternRenderingFloorBenchmarkBase {

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
	 * so each {@code evaluate()} call uses the same source. Callers compile the returned
	 * producer via {@code .get()} or {@code Process.optimized(chain).get()} depending on
	 * whether graph restructuring before compilation is under measurement.</p>
	 */
	private CollectionProducer buildChain(PackedCollection source,
										  PackedCollection envelope,
										  PackedCollection lpCoeffs,
										  PackedCollection accumBuffer) {
		CollectionProducer resampled = buildResampleProducer(source);
		CollectionProducer volumed = resampled.multiply(cp(envelope));
		CollectionProducer filtered = MultiOrderFilter.create(traverseEach(volumed), cp(lpCoeffs));
		return c(filtered).reshape(shape(NOTE_SIZE)).add(cp(accumBuffer));
	}

	/**
	 * Builds the per-note linear-resample producer that maps a {@code [SOURCE_SIZE]}
	 * source array onto a {@code [NOTE_SIZE]} output via fractional-position lerp.
	 *
	 * <p>Delegates to {@link org.almostrealism.audio.BatchedPatternRenderer#buildResampleProducer}
	 * so the benchmark and production code share a single implementation. Returns the
	 * producer un-compiled so callers can chain additional kernels onto it.</p>
	 */
	private CollectionProducer buildResampleProducer(PackedCollection source) {
		return SINGLE_NOTE_RENDERER.buildResampleProducer(source, RESAMPLE_RATIO);
	}

	/**
	 * Verifies whether applying {@link Process#optimized(Object)} before {@code .get()} changes
	 * the per-note cost compared to the baseline {@link #benchmarkNotesPerMeasureSingleLayer}
	 * that calls {@code .get()} directly.
	 *
	 * <p>If optimization has no effect on sequential JNI execution, the per-note cost will match
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

	/**
	 * Times each of the four kernels individually for the central density (64 notes/measure,
	 * 2048 notes total). Reports a per-phase breakdown showing what fraction of the combined
	 * chain cost each kernel contributes.
	 *
	 * <p>Each kernel is built in isolation against pre-computed input. The sum of the four
	 * per-phase means should approximately equal or exceed the combined-chain mean at 2048
	 * notes; a significantly lower sum would indicate kernel fusion in the composed chain.</p>
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

		log("Pre-computing intermediate buffers for isolated kernel timing...");
		PackedCollection resampledInput = buildResampleOnly(source).evaluate();
		PackedCollection volumedInput = buildVolumeOnly(resampledInput, envelope).evaluate();
		PackedCollection filteredInput = buildFirOnly(volumedInput, lpCoeffs).evaluate();
		log("Intermediate buffers ready.");
		log("");

		log("Compiling individual kernels...");
		Evaluable<PackedCollection> k1 = buildResampleOnly(source);
		Evaluable<PackedCollection> k2 = buildVolumeOnly(resampledInput, envelope);
		Evaluable<PackedCollection> k3 = buildFirOnly(volumedInput, lpCoeffs);
		Evaluable<PackedCollection> k4 = buildAccumulateOnly(filteredInput, accumBuffer);
		log("Compilation complete.");
		log("");

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

	/**
	 * Builds and compiles a single-kernel resample-only chain from the given source.
	 */
	private Evaluable<PackedCollection> buildResampleOnly(PackedCollection source) {
		return buildResampleProducer(source).get();
	}

	/**
	 * Builds and compiles a single-kernel volume-envelope multiply chain.
	 */
	private Evaluable<PackedCollection> buildVolumeOnly(PackedCollection resampledInput,
														PackedCollection envelope) {
		return cp(resampledInput).multiply(cp(envelope)).get();
	}

	/**
	 * Builds and compiles a single-kernel FIR lowpass filter chain, reshaped to {@link #NOTE_SIZE}.
	 */
	private Evaluable<PackedCollection> buildFirOnly(PackedCollection volumedInput,
													  PackedCollection lpCoeffs) {
		CollectionProducer filtered = MultiOrderFilter.create(
				traverseEach(cp(volumedInput)), cp(lpCoeffs));
		return c(filtered).reshape(shape(NOTE_SIZE)).get();
	}

	/**
	 * Builds and compiles a single-kernel accumulate chain (elementwise add of filtered
	 * input into the accumulation buffer).
	 */
	private Evaluable<PackedCollection> buildAccumulateOnly(PackedCollection filteredInput,
															PackedCollection accumBuffer) {
		return cp(filteredInput).add(cp(accumBuffer)).get();
	}

	/**
	 * Logs mean, median, p95, and per-note timing for one individual kernel phase.
	 */
	private void reportPhaseStats(long[] timesNs, int totalNotes) {
		double[] stats = computeTimingStats(timesNs);
		double meanMs = stats[0];
		log(String.format("  Mean=%.2fms  Median=%.2fms  P95=%.2fms  Per-note=%.4fms",
				meanMs, stats[1], stats[2], meanMs / totalNotes));
		log("");
	}

	/**
	 * Formats one row of the per-phase summary table: label, mean in ms, and
	 * percentage of the total sum.
	 */
	private String formatPhaseRow(String label, double meanMs, double sumMs) {
		double pct = sumMs > 0 ? (meanMs / sumMs) * 100.0 : 0.0;
		return String.format("  %-40s %8.2f %7.1f%%", label, meanMs, pct);
	}

	/**
	 * Measures the batched form of the four-kernel chain: a single {@link CollectionProducer}
	 * that processes all N notes for a given density in one {@code evaluate()} call rather than
	 * N sequential calls.
	 *
	 * <p>The batched chain covers kernels 1 (resample), 2 (volume envelope), and 4 (accumulate)
	 * over a flat {@code [N × NOTE_SIZE]} output. Kernel 3 ({@link MultiOrderFilter}) is
	 * excluded from the batched form because its convolution requires sequential per-sample
	 * state within each signal — naively batching it causes FIR state to bleed across note
	 * boundaries. The padded-FIR approach for including kernel 3 is measured in
	 * {@link #benchmarkBatchedChainWithPaddedFir}.</p>
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

		log("Speedup interpretation:");
		log("- 'amortized per-note' = batch-total / N; compare to sequential per-note");
		log("- Speedup from batching = sequential-per-note / amortized-per-note");
		log("- On JNI/CPU: speedup > 1x means JNI boundary-crossing overhead was real.");
		log("- On Metal: much larger speedup expected (parallel kernel execution).");
		log("==========================================================");
		log("Results appended to: " + RESULTS_PATH);
	}

	/**
	 * Builds a 3-kernel sequential chain (resample + volume envelope + accumulate,
	 * no FIR) for use as the sequential baseline in {@link #benchmarkBatchedCeiling}.
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
	 * back to a (note, sample) pair. All notes draw from the same source (appropriate
	 * for a benchmark measuring kernel cost, not content variation). The volume envelope
	 * is tiled {@code batchSize} times to cover all notes.</p>
	 */
	protected CollectionProducer buildBatchedResampleVolume(int batchSize,
															PackedCollection source,
															PackedCollection envelope) {
		int totalSamples = batchSize * NOTE_SIZE;

		CollectionProducer outIdx = integers(0, totalSamples);
		CollectionProducer noteIdx = floor(outIdx.multiply(c(1.0 / NOTE_SIZE)));
		CollectionProducer sampleIdx = outIdx.subtract(noteIdx.multiply(c((double) NOTE_SIZE)));
		CollectionProducer srcPos = sampleIdx.multiply(c(RESAMPLE_RATIO));
		CollectionProducer fPos = floor(srcPos);
		CollectionProducer frac = srcPos.subtract(fPos);
		CollectionProducer batchedS0 = c(shape(totalSamples), cp(source), fPos);
		CollectionProducer batchedS1 = c(shape(totalSamples), cp(source), fPos.add(c(1.0)));
		CollectionProducer batchedResampled = batchedS0.add(frac.multiply(batchedS1.subtract(batchedS0)));

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
		CollectionProducer tiledAccum = cp(accumBuffer).repeat(batchSize).reshape(shape(totalSamples));
		return batchedVolumed.add(tiledAccum).get();
	}

	/**
	 * Measures the simplest meaningful batched form: volume envelope multiply followed by
	 * accumulate, with no resample and no FIR, to test whether even a minimal 2-kernel
	 * batched chain captures most of the JNI-dispatch elimination benefit.
	 *
	 * <p>If the 2-kernel batched chain delivers a substantial fraction of the 3-kernel
	 * batched speedup (Addition 3), batching pays off regardless of the kernel count.
	 * If barely faster than sequential, the JNI overhead per dispatch is smaller than
	 * expected and batching only pays off with many kernels folded into one Producer.</p>
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

		log("Speedup interpretation:");
		log("- Compare 2-kernel batched speedup to 3-kernel batched speedup (Addition 3).");
		log("- If 2-kernel speedup is similar to 3-kernel speedup, JNI dispatch is the cost driver");
		log("  regardless of chain length — batching ANY chain delivers the win.");
		log("- If 2-kernel speedup is substantially lower, the win requires longer chains to");
		log("  amortize setup cost. (Less likely given Addition 2's ~43% fusion benefit.)");
		log("==========================================================");
		log("Results appended to: " + RESULTS_PATH);
	}

	/**
	 * Tests the hypothesis that {@link MultiOrderFilter} can be applied to a batched
	 * {@code [N, NOTE_SIZE + filterOrder]} input where each row has been zero-padded by
	 * {@code filterOrder/2} on each side, with cross-row bleed falling into the padded zeros.
	 *
	 * <p>Measures (a) whether it runs at all without a stateful-FIR error, and (b) what is
	 * the per-tick time of a 4-kernel batched chain when FIR is included via the padded
	 * approach. If the timing is comparable to the 3-kernel batched ceiling, FIR can be
	 * batched in production without modifying {@link MultiOrderFilter}.</p>
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
				log("Compilation failed for batched padded FIR chain at " + totalNotes + " notes: "
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

		log("Padded FIR batching interpretation:");
		log("- If timing at 64 notes/m is comparable to 3-kernel batched (~6.39ms),");
		log("  FIR can be added to the batched chain in production via padded rows.");
		log("- If significantly slower, the FIR per-row independent processing is more");
		log("  expensive than the cross-row bleed — alternate strategies needed.");
		log("==========================================================");
		log("Results appended to: " + RESULTS_PATH);
	}

	/**
	 * Builds a batched 3-kernel chain (resample + volume + FIR), with the FIR kernel
	 * applied to a row-padded {@code [batchSize, NOTE_SIZE + 2 * padHalf]} tensor.
	 *
	 * <p>The active audio occupies the central {@code NOTE_SIZE} of each row; the
	 * {@code padHalf} elements at front and back are zeros. {@link MultiOrderFilter}
	 * reads {@code ±padHalf} samples around each output position, so cross-row bleed
	 * at row boundaries reads into the pre-zeroed pad zones rather than adjacent-note
	 * audio. Accumulate is omitted to isolate the FIR contribution.</p>
	 */
	private Evaluable<PackedCollection> buildBatchedPaddedFirChain(int batchSize, int padHalf,
																   PackedCollection source,
																   PackedCollection envelope,
																   PackedCollection lpCoeffs) {
		CollectionProducer batchedVolumed = buildBatchedResampleVolume(batchSize, source, envelope)
				.reshape(shape(batchSize, NOTE_SIZE));
		CollectionProducer padded = pad(batchedVolumed, 0, padHalf);
		CollectionProducer filtered = MultiOrderFilter.create(traverseEach(padded), cp(lpCoeffs));
		return c(filtered).get();
	}

	/**
	 * Contrasts tile-and-add (output {@code [N, NOTE_SIZE]}) versus true scatter-accumulate
	 * (output {@code [NOTE_SIZE]}, summed along the note axis) to determine whether the
	 * framework can express the final per-tick reduction as part of the compiled graph.
	 *
	 * <p>A true reduction output reduces per-tick memory by a factor of N and eliminates
	 * a separate post-processing step on the Java side. If reduction time approximates tile
	 * time, the framework fuses the sum into the kernel — Phase 3 can produce a compact
	 * {@code [NOTE_SIZE]} output buffer per tick.</p>
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

			CollectionProducer reshaped = batchedVolumed.reshape(shape(totalNotes, NOTE_SIZE));
			Evaluable<PackedCollection> reduceChain;
			try {
				CollectionProducer permuted = permute(reshaped, 1, 0);
				reduceChain = permuted.traverse(1).sum().reshape(shape(NOTE_SIZE)).get();
			} catch (Exception ex) {
				log("notes_per_measure=" + npm + " [REDUCE] Compilation failed: "
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

		log("Reduction interpretation:");
		log("- Tile-and-add output is [N × NOTE_SIZE]: 1024×N elements written per tick.");
		log("- True reduction output is [NOTE_SIZE]: 1024 elements written per tick.");
		log("- If reduction time ~ tile time: framework fuses sum into the kernel — Phase 3");
		log("  can produce a compact [NOTE_SIZE] output buffer per tick, no Java-side post-sum.");
		log("- If reduction time >> tile time: reduction is a separate pass; Phase 3 may need");
		log("  a different reduction primitive or stay with tile-and-add followed by a tight sum.");
		log("==========================================================");
		log("Results appended to: " + RESULTS_PATH);
	}
}
