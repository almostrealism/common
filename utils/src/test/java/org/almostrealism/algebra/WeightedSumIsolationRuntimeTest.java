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
import io.almostrealism.compute.Process;
import io.almostrealism.compute.ProcessContext;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.computations.WeightedSumComputation;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.List;

/**
 * Tests to measure the RUNTIME performance tradeoff of isolation.
 *
 * <p>Key insight: Compilation happens once, execution happens many times.
 * We need to find the break-even point where isolation overhead during
 * execution outweighs compilation time savings.</p>
 */
public class WeightedSumIsolationRuntimeTest extends TestSuiteBase implements AlgebraFeatures {

	private static final int WARMUP_ITERATIONS = 100;
	private static final int MEASUREMENT_ITERATIONS = 1000;

	static class TestableWeightedSumComputation extends WeightedSumComputation {
		private final boolean forceIsolation;

		public TestableWeightedSumComputation(TraversalPolicy resultShape,
											  TraversalPolicy inputPositions,
											  TraversalPolicy weightPositions,
											  TraversalPolicy inputGroupShape,
											  TraversalPolicy weightGroupShape,
											  Producer<PackedCollection> input,
											  Producer<PackedCollection> weights,
											  boolean forceIsolation) {
			super(resultShape, inputPositions, weightPositions, inputGroupShape, weightGroupShape, input, weights);
			this.forceIsolation = forceIsolation;
		}

		@Override
		public boolean isIsolationTarget(ProcessContext context) {
			return forceIsolation;
		}

		@Override
		public TestableWeightedSumComputation generate(List<Process<?, ?>> children) {
			return new TestableWeightedSumComputation(
					getShape(),
					getInputTraversal().getPositions(),
					getWeightsTraversal().getPositions(),
					getInputTraversal().getGroupShape(),
					getWeightsTraversal().getGroupShape(),
					(Producer<PackedCollection>) children.get(1),
					(Producer<PackedCollection>) children.get(2),
					forceIsolation);
		}
	}

	/**
	 * Comprehensive test measuring both compilation AND execution time.
	 * Calculates break-even point for various execution counts.
	 */
	@Test
	public void testCompilationVsExecutionTradeoff() {
		System.out.println("=== Compilation vs Execution Tradeoff Analysis ===\n");
		System.out.println("Measuring compilation time + execution time for " + MEASUREMENT_ITERATIONS + " iterations\n");

		int[] groupSizes = {8, 16, 32, 64, 128, 256, 512, 1024};
		int outputSize = 16;

		System.out.println(String.format("%-10s %-12s %-12s %-12s %-12s %-15s %-15s",
				"GroupSize", "Compile(NI)", "Compile(I)", "Exec/iter(NI)", "Exec/iter(I)",
				"Break-even", "100k total"));
		System.out.println("-".repeat(100));

		for (int groupSize : groupSizes) {
			Result noIsolation = measureFullCycle(outputSize, groupSize, false);
			Result isolated = measureFullCycle(outputSize, groupSize, true);

			if (noIsolation.compileTimeMs < 0 || isolated.compileTimeMs < 0) {
				System.out.println(groupSize + " - FAILED");
				continue;
			}

			// Calculate break-even point
			// Total time = compile + (execPerIter * numIterations)
			// Break-even when: compileNI + execNI*N = compileI + execI*N
			// N = (compileNI - compileI) / (execI - execNI)

			double compileSavings = noIsolation.compileTimeMs - isolated.compileTimeMs;
			double execOverhead = isolated.execTimePerIterUs - noIsolation.execTimePerIterUs;

			long breakEvenIterations;
			String breakEvenStr;
			if (execOverhead <= 0) {
				// Isolation is faster or equal at runtime too - always better
				breakEvenStr = "Always better";
				breakEvenIterations = 0;
			} else {
				// compileSavings is in ms, execOverhead is in us
				// breakEven = compileSavings(ms) * 1000 / execOverhead(us)
				breakEvenIterations = (long) ((compileSavings * 1000) / execOverhead);
				if (breakEvenIterations < 0) breakEvenIterations = Long.MAX_VALUE;
				breakEvenStr = formatNumber(breakEvenIterations);
			}

			// Calculate total time for 100k iterations
			double total100kNoIsolation = noIsolation.compileTimeMs + (noIsolation.execTimePerIterUs * 100000 / 1000);
			double total100kIsolated = isolated.compileTimeMs + (isolated.execTimePerIterUs * 100000 / 1000);
			String winner100k = total100kNoIsolation < total100kIsolated ? "NI wins" : "I wins";

			System.out.println(String.format("%-10d %-12d %-12d %-12.2f %-12.2f %-15s %-15s",
					groupSize,
					noIsolation.compileTimeMs,
					isolated.compileTimeMs,
					noIsolation.execTimePerIterUs,
					isolated.execTimePerIterUs,
					breakEvenStr,
					winner100k + String.format(" (%.0f vs %.0f ms)", total100kNoIsolation, total100kIsolated)));
		}

		System.out.println("\nLegend:");
		System.out.println("  NI = No Isolation, I = Isolated");
		System.out.println("  Compile times in ms, Exec times in us per iteration");
		System.out.println("  Break-even = iterations needed for isolation to pay off");
	}

	/**
	 * Detailed test for a single group size showing the full picture.
	 */
	@Test
	public void testDetailedAnalysis() {
		System.out.println("=== Detailed Analysis for GroupSize=64 ===\n");

		int groupSize = 64;
		int outputSize = 16;

		Result noIsolation = measureFullCycle(outputSize, groupSize, false);
		Result isolated = measureFullCycle(outputSize, groupSize, true);

		System.out.println("No Isolation:");
		System.out.println("  Compilation time: " + noIsolation.compileTimeMs + " ms");
		System.out.println("  Execution time per iteration: " + String.format("%.2f", noIsolation.execTimePerIterUs) + " us");
		System.out.println();

		System.out.println("Isolated:");
		System.out.println("  Compilation time: " + isolated.compileTimeMs + " ms");
		System.out.println("  Execution time per iteration: " + String.format("%.2f", isolated.execTimePerIterUs) + " us");
		System.out.println();

		// Calculate totals for various iteration counts
		System.out.println("Total time (compile + execute) for N iterations:");
		System.out.println(String.format("%-15s %-20s %-20s %-10s", "Iterations", "No Isolation", "Isolated", "Winner"));
		System.out.println("-".repeat(70));

		long[] iterCounts = {1, 10, 100, 1000, 10000, 100000, 1000000};
		for (long n : iterCounts) {
			double totalNI = noIsolation.compileTimeMs + (noIsolation.execTimePerIterUs * n / 1000);
			double totalI = isolated.compileTimeMs + (isolated.execTimePerIterUs * n / 1000);
			String winner = totalNI < totalI ? "No Isolation" : "Isolated";

			System.out.println(String.format("%-15s %-20s %-20s %-10s",
					formatNumber(n),
					String.format("%.2f ms", totalNI),
					String.format("%.2f ms", totalI),
					winner));
		}
	}

	/**
	 * Analysis specifically targeting 10K-100K iteration use case.
	 * Finds the optimal threshold where isolation starts to make sense.
	 */
	@Test
	public void testThresholdForTypicalUsage() {
		System.out.println("=== Threshold Analysis for 10K-100K Iterations ===\n");
		System.out.println("Finding where isolation becomes beneficial for typical application usage.\n");

		int[] groupSizes = {32, 64, 128, 256, 512, 1024, 2048};
		int outputSize = 16;

		System.out.println(String.format("%-10s %-12s %-12s %-12s %-12s %-12s %-12s",
				"GroupSize", "Break-even", "10K winner", "50K winner", "100K winner", "Overhead%", "Verdict"));
		System.out.println("-".repeat(90));

		for (int groupSize : groupSizes) {
			Result noIsolation = measureFullCycle(outputSize, groupSize, false);
			Result isolated = measureFullCycle(outputSize, groupSize, true);

			if (noIsolation.compileTimeMs < 0 || isolated.compileTimeMs < 0) {
				System.out.println(groupSize + " - FAILED (compilation error)");
				continue;
			}

			double compileSavings = noIsolation.compileTimeMs - isolated.compileTimeMs;
			double execOverhead = isolated.execTimePerIterUs - noIsolation.execTimePerIterUs;

			long breakEvenIterations;
			String breakEvenStr;
			if (execOverhead <= 0) {
				breakEvenStr = "Always I";
				breakEvenIterations = Long.MAX_VALUE;
			} else {
				breakEvenIterations = (long) ((compileSavings * 1000) / execOverhead);
				breakEvenStr = formatNumber(breakEvenIterations);
			}

			// Calculate total time for 10K, 50K, 100K iterations
			double total10kNI = noIsolation.compileTimeMs + (noIsolation.execTimePerIterUs * 10000 / 1000);
			double total10kI = isolated.compileTimeMs + (isolated.execTimePerIterUs * 10000 / 1000);
			String winner10k = total10kNI < total10kI ? "NI" : "I";

			double total50kNI = noIsolation.compileTimeMs + (noIsolation.execTimePerIterUs * 50000 / 1000);
			double total50kI = isolated.compileTimeMs + (isolated.execTimePerIterUs * 50000 / 1000);
			String winner50k = total50kNI < total50kI ? "NI" : "I";

			double total100kNI = noIsolation.compileTimeMs + (noIsolation.execTimePerIterUs * 100000 / 1000);
			double total100kI = isolated.compileTimeMs + (isolated.execTimePerIterUs * 100000 / 1000);
			String winner100k = total100kNI < total100kI ? "NI" : "I";

			// Calculate execution overhead percentage
			double overheadPct = (execOverhead / noIsolation.execTimePerIterUs) * 100;

			// Verdict: Is isolation good for typical 10K-100K usage?
			String verdict;
			if (winner10k.equals("I") && winner50k.equals("I") && winner100k.equals("I")) {
				verdict = "ISOLATE";
			} else if (winner10k.equals("NI") && winner50k.equals("NI") && winner100k.equals("NI")) {
				verdict = "NO ISOLATE";
			} else {
				verdict = "MARGINAL";
			}

			System.out.println(String.format("%-10d %-12s %-12s %-12s %-12s %-12.1f%% %-12s",
					groupSize, breakEvenStr, winner10k, winner50k, winner100k, overheadPct, verdict));

			// Stop if compilation takes too long (non-isolated)
			if (noIsolation.compileTimeMs > 60000) {
				System.out.println("\nStopping - compilation time exceeds 60 seconds");
				break;
			}
		}

		System.out.println("\nLegend:");
		System.out.println("  NI = No Isolation wins, I = Isolation wins");
		System.out.println("  Overhead% = Extra execution time per iteration when isolated");
		System.out.println("  ISOLATE = Isolation is beneficial for 10K-100K iterations");
		System.out.println("  NO ISOLATE = No isolation is better for 10K-100K iterations");
		System.out.println("  MARGINAL = Mixed results, depends on iteration count");
	}

	private Result measureFullCycle(int outputSize, int groupSize, boolean isolate) {
		int c1 = outputSize;
		int c2 = 1;
		int r = groupSize;

		TraversalPolicy inputShape = shape(r, c1, 1);
		TraversalPolicy weightShape = shape(r, 1, c2);
		TraversalPolicy resultShape = shape(1, c1, c2);

		TraversalPolicy inputPositions = inputShape.repeat(2, c2);
		TraversalPolicy weightPositions = weightShape.repeat(1, c1);
		TraversalPolicy groupShape = shape(r, 1, 1);

		PackedCollection input = new PackedCollection(inputShape).randFill();
		PackedCollection weights = new PackedCollection(weightShape).randFill();

		TestableWeightedSumComputation computation = new TestableWeightedSumComputation(
				resultShape, inputPositions, weightPositions, groupShape, groupShape,
				cp(input), cp(weights), isolate);

		OperationList ops = new OperationList();
		PackedCollection output = new PackedCollection(resultShape.traverseEach());
		ops.add(a("result", p(output), traverseEach(computation)));

		ops = (OperationList) ops.optimize();

		// Measure compilation
		long compileStart = System.currentTimeMillis();
		Runnable compiled;
		try {
			compiled = ops.get();
		} catch (Exception e) {
			return new Result(-1, -1);
		}
		long compileTimeMs = System.currentTimeMillis() - compileStart;

		// Warmup
		for (int i = 0; i < WARMUP_ITERATIONS; i++) {
			compiled.run();
		}

		// Measure execution
		long execStart = System.nanoTime();
		for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
			compiled.run();
		}
		long execTimeNs = System.nanoTime() - execStart;
		double execTimePerIterUs = (double) execTimeNs / MEASUREMENT_ITERATIONS / 1000.0;

		return new Result(compileTimeMs, execTimePerIterUs);
	}

	private String formatNumber(long n) {
		if (n >= 1_000_000_000) return String.format("%.1fB", n / 1_000_000_000.0);
		if (n >= 1_000_000) return String.format("%.1fM", n / 1_000_000.0);
		if (n >= 1_000) return String.format("%.1fK", n / 1_000.0);
		return String.valueOf(n);
	}

	private static class Result {
		final long compileTimeMs;
		final double execTimePerIterUs;

		Result(long compileTimeMs, double execTimePerIterUs) {
			this.compileTimeMs = compileTimeMs;
			this.execTimePerIterUs = execTimePerIterUs;
		}
	}
}
