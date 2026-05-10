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
		Evaluable<PackedCollection> compiled = buildChain(source, envelope, lpCoeffs, accumBuffer);
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
	 * Builds the four-kernel chain as a raw {@link CollectionProducer} and compiles it
	 * to an {@link Evaluable} without any backpropagation infrastructure.
	 *
	 * <p>The source {@link PackedCollection} is captured by reference via {@code cp(source)},
	 * so each {@code evaluate()} call uses the same source. For timing purposes this is
	 * correct — the kernels' cost is dominated by sample count, not content.</p>
	 */
	private Evaluable<PackedCollection> buildChain(PackedCollection source,
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
		CollectionProducer output = c(filtered).reshape(shape(NOTE_SIZE)).add(cp(accumBuffer));

		return output.get();
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

}
