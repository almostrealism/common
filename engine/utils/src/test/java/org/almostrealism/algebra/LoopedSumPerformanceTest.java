/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.algebra;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.algebra.computations.LoopedWeightedSumComputation;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Performance test for LoopedWeightedSumComputation.
 * Tests various outerCount/innerCount combinations and records compilation times.
 * Generates a CSV file for analysis.
 */
public class LoopedSumPerformanceTest extends TestSuiteBase {

	private static final int TIMEOUT_SECONDS = 600; // 10 minutes max per test
	private static final int OUTPUT_SIZE = 8;
	private static final String CSV_PATH = "target/looped_sum_performance.csv";

	/**
	 * Main performance test - generates CSV with timing data.
	 * Skipped by default (skipLongTests) due to very long runtime (many configurations tested,
	 * some taking several minutes each due to expression tree compilation).
	 * Run with AR_LONG_TESTS=true to enable.
	 */
	@Test(timeout = 3600000) // 1 hour - this test runs many configurations
	public void testPerformanceMatrix() throws IOException {
		if (skipLongTests) return;
		List<TestResult> results = new ArrayList<>();

		System.out.println("=== LoopedWeightedSumComputation Performance Analysis ===");
		System.out.println("Timeout per test: " + TIMEOUT_SECONDS + " seconds");
		System.out.println();

		// Phase 1: Low outerCount, increasing innerCount
		// This tests how innerCount scaling affects performance
		System.out.println("--- Phase 1: outerCount=16, varying innerCount ---");
		int[] innerCounts1 = {4, 8, 16, 32, 64, 128, 256, 512};
		for (int innerCount : innerCounts1) {
			TestResult result = runTimedTest(16, innerCount);
			results.add(result);
			printResult(result);
			if (result.timedOut) break; // Stop if we hit timeout
		}

		// Phase 2: Medium outerCount, increasing innerCount
		System.out.println("\n--- Phase 2: outerCount=64, varying innerCount ---");
		int[] innerCounts2 = {4, 8, 16, 32, 64, 128, 256};
		for (int innerCount : innerCounts2) {
			TestResult result = runTimedTest(64, innerCount);
			results.add(result);
			printResult(result);
			if (result.timedOut) break;
		}

		// Phase 3: Increasing outerCount with small innerCount (like our target)
		System.out.println("\n--- Phase 3: innerCount=16, varying outerCount ---");
		int[] outerCounts3 = {16, 32, 64, 128, 256, 512, 1024, 2048};
		for (int outerCount : outerCounts3) {
			TestResult result = runTimedTest(outerCount, 16);
			results.add(result);
			printResult(result);
			if (result.timedOut) break;
		}

		// Phase 4: Increasing outerCount with tiny innerCount
		System.out.println("\n--- Phase 4: innerCount=4, varying outerCount ---");
		int[] outerCounts4 = {16, 32, 64, 128, 256, 512, 1024, 2048};
		for (int outerCount : outerCounts4) {
			TestResult result = runTimedTest(outerCount, 4);
			results.add(result);
			printResult(result);
			if (result.timedOut) break;
		}

		// Phase 5: Target configuration and nearby
		System.out.println("\n--- Phase 5: Near target (outerCount=2048, innerCount=16) ---");
		int[][] targetConfigs = {
			{256, 16}, {512, 16}, {1024, 16}, {2048, 16},
			{2048, 4}, {2048, 8}, {2048, 32}
		};
		for (int[] config : targetConfigs) {
			// Skip if already tested
			boolean alreadyTested = false;
			for (TestResult r : results) {
				if (r.outerCount == config[0] && r.innerCount == config[1]) {
					alreadyTested = true;
					break;
				}
			}
			if (!alreadyTested) {
				TestResult result = runTimedTest(config[0], config[1]);
				results.add(result);
				printResult(result);
			}
		}

		// Phase 6: Subdivision candidates - what if we split the outer loop?
		System.out.println("\n--- Phase 6: Subdivision analysis ---");
		// If we split outerCount=2048 into chunks, what size chunks work well?
		int[] chunkSizes = {32, 64, 128, 256, 512};
		for (int chunk : chunkSizes) {
			// Skip if already tested
			boolean alreadyTested = false;
			for (TestResult r : results) {
				if (r.outerCount == chunk && r.innerCount == 16) {
					alreadyTested = true;
					break;
				}
			}
			if (!alreadyTested) {
				TestResult result = runTimedTest(chunk, 16);
				results.add(result);
				printResult(result);
			}
		}

		// Write CSV
		writeCSV(results);
		System.out.println("\n=== Results written to " + CSV_PATH + " ===");
	}

	private TestResult runTimedTest(int outerCount, int innerCount) {
		TestResult result = new TestResult();
		result.outerCount = outerCount;
		result.innerCount = innerCount;
		result.totalOps = outerCount * innerCount;

		ExecutorService executor = Executors.newSingleThreadExecutor();
		Future<Long> future = executor.submit(() -> {
			return compileAndTime(outerCount, innerCount);
		});

		try {
			result.compilationTimeMs = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			result.timedOut = false;
		} catch (TimeoutException e) {
			result.timedOut = true;
			result.compilationTimeMs = TIMEOUT_SECONDS * 1000L;
			future.cancel(true);
		} catch (Exception e) {
			result.timedOut = true;
			result.compilationTimeMs = -1;
			result.error = e.getMessage();
		} finally {
			executor.shutdownNow();
		}

		return result;
	}

	private long compileAndTime(int outerCount, int innerCount) {
		TraversalPolicy outputShape = shape(OUTPUT_SIZE).traverseEach();
		TraversalPolicy inputShape = shape(outerCount, OUTPUT_SIZE + innerCount - 1);
		TraversalPolicy weightShape = shape(outerCount, innerCount);

		PackedCollection input = new PackedCollection(inputShape).randFill();
		PackedCollection weights = new PackedCollection(weightShape).randFill();

		LoopedWeightedSumComputation.InputIndexer inputIndexer = (outputIndex, outerIndex, innerIndex) -> {
			return outerIndex.multiply(OUTPUT_SIZE + innerCount - 1).add(outputIndex).add(innerIndex);
		};

		LoopedWeightedSumComputation.WeightIndexer weightIndexer = (outputIndex, outerIndex, innerIndex) -> {
			return outerIndex.multiply(innerCount).add(innerIndex);
		};

		LoopedWeightedSumComputation computation = new LoopedWeightedSumComputation(
				"perfTest",
				outputShape,
				outerCount,
				innerCount,
				inputShape,
				weightShape,
				inputIndexer,
				weightIndexer,
				cp(input),
				cp(weights));

		OperationList ops = new OperationList();
		PackedCollection output = new PackedCollection(outputShape);
		ops.add(a("perfTest", p(output), computation));

		// CRITICAL: Call optimize() before get() to trigger isolation
		ops = (OperationList) ops.optimize();

		long start = System.currentTimeMillis();
		Runnable r = ops.get();
		long elapsed = System.currentTimeMillis() - start;

		// Run once to verify it works
		r.run();

		return elapsed;
	}

	private void printResult(TestResult result) {
		if (result.timedOut) {
			System.out.printf("  outer=%4d, inner=%4d, total=%7d ops -> TIMEOUT (>%ds)%n",
					result.outerCount, result.innerCount, result.totalOps, TIMEOUT_SECONDS);
		} else {
			System.out.printf("  outer=%4d, inner=%4d, total=%7d ops -> %6dms%n",
					result.outerCount, result.innerCount, result.totalOps, result.compilationTimeMs);
		}
	}

	private void writeCSV(List<TestResult> results) throws IOException {
		try (PrintWriter writer = new PrintWriter(new FileWriter(CSV_PATH))) {
			writer.println("outerCount,innerCount,totalOps,compilationTimeMs,timedOut,error");
			for (TestResult r : results) {
				writer.printf("%d,%d,%d,%d,%s,%s%n",
						r.outerCount, r.innerCount, r.totalOps, r.compilationTimeMs,
						r.timedOut, r.error != null ? "\"" + r.error + "\"" : "");
			}
		}
	}

	private static class TestResult {
		int outerCount;
		int innerCount;
		int totalOps;
		long compilationTimeMs;
		boolean timedOut;
		String error;
	}
}
