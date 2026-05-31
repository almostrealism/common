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

import io.almostrealism.collect.CollectionProducer;
import io.almostrealism.collect.PackedCollection;
import org.almostrealism.audio.BatchedPatternRenderer;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.time.TemporalFeatures;
import org.almostrealism.time.computations.MultiOrderFilter;
import org.almostrealism.util.FirFilterTestFeatures;

import java.io.File;
import java.util.Random;

/**
 * Base class for pattern rendering floor benchmarks containing shared constants
 * and helper methods used by all benchmark test classes.
 *
 * @see org.almostrealism.time.computations.MultiOrderFilter
 */
public abstract class PatternRenderingFloorBenchmarkBase
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
	 * a duplicate copy of the resample algorithm in this benchmark.
	 */
	protected static final BatchedPatternRenderer SINGLE_NOTE_RENDERER =
			new BatchedPatternRenderer(1, SOURCE_SIZE, NOTE_SIZE, SAMPLE_RATE, FILTER_ORDER);

	/**
	 * Notes-per-measure densities measured by every Dimension-1 / batched test:
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
		lpCoeffs.setMem(data);
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
	 * Formats a "vs {@value #THRESHOLD_MS}ms threshold" comparison string for the
	 * given mean-per-tick measurement: {@code BELOW (Nx headroom)} when under the
	 * real-time budget, {@code ABOVE (Nx overhead)} when over. Used by both the
	 * dimension-1 reporter ({@link #reportStats}) and the padded-FIR inline
	 * reporter ({@link #benchmarkBatchedChainWithPaddedFir}).
	 */
	protected static String formatVsThreshold(double meanMs) {
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
	protected void warmupSequential(String description, io.almostrealism.relation.Evaluable<PackedCollection> chain) {
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
	 * Returns the shape array for creating tensors.
	 */
	protected int[] shape(int... dims) {
		return dims;
	}
}
