/*
 * Copyright 2025 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.hardware.test;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.AggregationDepthTargetOptimization;
import io.almostrealism.compute.CascadingOptimizationStrategy;
import io.almostrealism.compute.ParallelismDiversityOptimization;
import io.almostrealism.compute.ParallelismTargetOptimization;
import io.almostrealism.compute.Process;
import io.almostrealism.compute.ProcessContext;
import io.almostrealism.compute.ProcessOptimizationStrategy;
import io.almostrealism.compute.TraversableDepthTargetOptimization;
import io.almostrealism.compute.ProcessContextBase;
import io.almostrealism.profile.OperationProfile;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestSuiteBase;
import org.junit.After;
import org.junit.Test;

/**
 * Performance tests for optimization strategies.
 *
 * <p>These tests demonstrate how different optimization strategies impact
 * compilation time, execution time, and memory usage under various conditions.
 * This serves as living documentation for understanding when each strategy
 * performs well versus poorly.</p>
 *
 * <h2>Test Categories</h2>
 * <ul>
 *   <li><strong>Deep Chain Tests</strong> - Nested operations creating deep expression trees</li>
 *   <li><strong>Wide Parallelism Tests</strong> - Operations with many children</li>
 *   <li><strong>Mixed Tests</strong> - Realistic workloads combining multiple patterns</li>
 *   <li><strong>Strategy Comparison Tests</strong> - Direct comparison of strategies</li>
 * </ul>
 *
 * <h2>Key Metrics</h2>
 * <ul>
 *   <li><strong>Optimize Time</strong> - Time spent in process.optimize()</li>
 *   <li><strong>Compile Time</strong> - Time spent in process.get()</li>
 *   <li><strong>Run Time</strong> - Time spent in runnable.run()</li>
 * </ul>
 *
 * <h2>Interpreting Results</h2>
 * <p>High compile times indicate expression tree explosion - the optimization
 * strategy is not isolating aggressively enough. High run times with low compile
 * times may indicate over-isolation (too many kernel launches).</p>
 *
 * @see ProcessOptimizationStrategy
 * @see ParallelismTargetOptimization
 * @see TraversableDepthTargetOptimization
 * @see AggregationDepthTargetOptimization
 * @see CascadingOptimizationStrategy
 *
 * @author Michael Murray
 */
public class OptimizationStrategyPerformanceTests extends TestSuiteBase {

	private static final boolean VERBOSE = true;

	/**
	 * Restores the default optimization strategy after each test to prevent
	 * global state pollution that would affect other test classes running
	 * in the same JVM (e.g., NormTests in the same CI test group).
	 */
	@After
	public void restoreDefaultStrategy() {
		ProcessContextBase.setDefaultOptimizationStrategy(new ParallelismTargetOptimization());
	}

	// =========================================================================
	// Deep Chain Tests - Demonstrate TraversableDepthTargetOptimization
	// =========================================================================

	/**
	 * Tests a chain of nested operations without optimization.
	 *
	 * <p><strong>Expected Behavior:</strong> Without proper isolation, the expression
	 * tree grows with each nesting level, causing exponential compilation time.</p>
	 *
	 * <p><strong>Demonstrates:</strong> The problem that optimization strategies solve.</p>
	 */
	@Test(timeout = 60000)
	public void deepChainNoOptimization() {
		ProcessContextBase.setDefaultOptimizationStrategy(null);

		int depth = 4; // Keep small to avoid timeout
		int size = 256;

		TimingResult result = measureDeepChain(depth, size, false);

		logResult("Deep Chain (depth=" + depth + ", no optimization):");
		logTiming(result);
	}

	/**
	 * Tests a chain of nested operations with default optimization.
	 *
	 * <p><strong>Expected Behavior:</strong> ParallelismTargetOptimization may not
	 * isolate effectively if parallelism is uniform across the chain.</p>
	 */
	@Test(timeout = 60000)
	public void deepChainDefaultOptimization() {
		ProcessContextBase.setDefaultOptimizationStrategy(new ParallelismTargetOptimization());

		int depth = 6;
		int size = 256;

		TimingResult result = measureDeepChain(depth, size, true);

		logResult("Deep Chain (depth=" + depth + ", ParallelismTargetOptimization):");
		logTiming(result);
	}

	/**
	 * Tests a chain of nested operations with depth-based optimization.
	 *
	 * <p><strong>Expected Behavior:</strong> TraversableDepthTargetOptimization should
	 * isolate at the configured depth limit, preventing exponential tree growth.</p>
	 *
	 * <p><strong>Demonstrates:</strong> When TraversableDepthTargetOptimization works well.</p>
	 */
	@Test(timeout = 60000)
	public void deepChainDepthOptimization() {
		ProcessContextBase.setDefaultOptimizationStrategy(
				new TraversableDepthTargetOptimization(4));

		int depth = 8;
		int size = 256;

		TimingResult result = measureDeepChain(depth, size, true);

		logResult("Deep Chain (depth=" + depth + ", TraversableDepthTargetOptimization(4)):");
		logTiming(result);
	}

	/**
	 * Tests a chain of nested operations with cascading strategy.
	 *
	 * <p><strong>Expected Behavior:</strong> Cascading strategy checks depth first,
	 * then falls back to parallelism-based decisions.</p>
	 */
	@Test(timeout = 60000)
	public void deepChainCascadingOptimization() {
		ProcessContextBase.setDefaultOptimizationStrategy(
				new CascadingOptimizationStrategy(
						new TraversableDepthTargetOptimization(4),
						new ParallelismTargetOptimization()
				));

		int depth = 8;
		int size = 256;

		TimingResult result = measureDeepChain(depth, size, true);

		logResult("Deep Chain (depth=" + depth + ", CascadingOptimizationStrategy):");
		logTiming(result);
	}

	// =========================================================================
	// Wide Parallelism Tests - Demonstrate ParallelismTargetOptimization
	// =========================================================================

	/**
	 * Tests concatenation of many tensors (high fan-in).
	 *
	 * <p><strong>Expected Behavior:</strong> Without optimization, all input
	 * expressions get embedded in the concat expression.</p>
	 */
	@Test(timeout = 60000)
	public void wideConcatNoOptimization() {
		ProcessContextBase.setDefaultOptimizationStrategy(null);

		int numInputs = 8;
		int size = 1024;

		TimingResult result = measureWideConcat(numInputs, size, false);

		logResult("Wide Concat (inputs=" + numInputs + ", no optimization):");
		logTiming(result);
	}

	/**
	 * Tests concatenation with parallelism-based optimization.
	 *
	 * <p><strong>Expected Behavior:</strong> If children have high parallelism,
	 * ParallelismTargetOptimization should isolate them.</p>
	 */
	@Test(timeout = 60000)
	public void wideConcatParallelismOptimization() {
		ProcessContextBase.setDefaultOptimizationStrategy(new ParallelismTargetOptimization());

		int numInputs = 16;
		int size = 1024;

		TimingResult result = measureWideConcat(numInputs, size, true);

		logResult("Wide Concat (inputs=" + numInputs + ", ParallelismTargetOptimization):");
		logTiming(result);
	}

	/**
	 * Tests concatenation with aggregation-based optimization.
	 *
	 * <p><strong>Expected Behavior:</strong> AggregationDepthTargetOptimization is
	 * designed for high fan-in scenarios like concatenation.</p>
	 */
	@Test(timeout = 60000)
	public void wideConcatAggregationOptimization() {
		ProcessContextBase.setDefaultOptimizationStrategy(
				new CascadingOptimizationStrategy(
						new AggregationDepthTargetOptimization(8),
						new ParallelismTargetOptimization()
				));

		int numInputs = 16;
		int size = 1024;

		TimingResult result = measureWideConcat(numInputs, size, true);

		logResult("Wide Concat (inputs=" + numInputs + ", AggregationDepthTargetOptimization):");
		logTiming(result);
	}

	// =========================================================================
	// Parallelism Diversity Tests
	// =========================================================================

	/**
	 * Tests operations with vastly different parallelism levels.
	 *
	 * <p><strong>Expected Behavior:</strong> ParallelismDiversityOptimization should
	 * detect high variance in child parallelism and isolate.</p>
	 */
	@Test(timeout = 60000)
	public void diverseParallelismTest() {
		ProcessContextBase.setDefaultOptimizationStrategy(
				new CascadingOptimizationStrategy(
						new ParallelismDiversityOptimization(),
						new ParallelismTargetOptimization()
				));

		// Create operations with very different sizes
		PackedCollection small = new PackedCollection(shape(64));
		PackedCollection medium = new PackedCollection(shape(1024));
		PackedCollection large = new PackedCollection(shape(16384));

		small.fill(pos -> Math.random());
		medium.fill(pos -> Math.random());
		large.fill(pos -> Math.random());

		// Combine them in a way that creates diversity
		CollectionProducer smallOp = c(p(small)).multiply(2.0);
		CollectionProducer mediumOp = c(p(medium)).multiply(3.0);
		CollectionProducer largeOp = c(p(large)).multiply(4.0);

		TimingResult result = measureOperation(
				"Diverse parallelism",
				() -> {
					OperationList op = new OperationList("diverse", false);
					op.add(a(p(small), smallOp));
					op.add(a(p(medium), mediumOp));
					op.add(a(p(large), largeOp));
					return op;
				},
				true
		);

		logResult("Diverse Parallelism (64, 1024, 16384):");
		logTiming(result);
	}

	// =========================================================================
	// Strategy Comparison Tests
	// =========================================================================

	/**
	 * Compares all strategies on the same workload.
	 *
	 * <p><strong>Purpose:</strong> Direct comparison to understand trade-offs.</p>
	 */
	@Test(timeout = 180000)
	@TestDepth(2)
	public void strategyComparison() {
		int depth = 6;
		int size = 512;

		logResult("=== Strategy Comparison (depth=" + depth + ", size=" + size + ") ===");
		logResult("");

		// No optimization
		ProcessContextBase.setDefaultOptimizationStrategy(null);
		TimingResult noOpt = measureDeepChain(depth, size, false);
		logResult("No Optimization:");
		logTiming(noOpt);
		logResult("");

		// ParallelismTargetOptimization
		ProcessContextBase.setDefaultOptimizationStrategy(new ParallelismTargetOptimization());
		TimingResult parallelism = measureDeepChain(depth, size, true);
		logResult("ParallelismTargetOptimization:");
		logTiming(parallelism);
		logResult("");

		// TraversableDepthTargetOptimization
		ProcessContextBase.setDefaultOptimizationStrategy(new TraversableDepthTargetOptimization(4));
		TimingResult depth4 = measureDeepChain(depth, size, true);
		logResult("TraversableDepthTargetOptimization(4):");
		logTiming(depth4);
		logResult("");

		// TraversableDepthTargetOptimization with lower limit
		ProcessContextBase.setDefaultOptimizationStrategy(new TraversableDepthTargetOptimization(2));
		TimingResult depth2 = measureDeepChain(depth, size, true);
		logResult("TraversableDepthTargetOptimization(2):");
		logTiming(depth2);
		logResult("");

		// Cascading
		ProcessContextBase.setDefaultOptimizationStrategy(
				new CascadingOptimizationStrategy(
						new TraversableDepthTargetOptimization(4),
						new AggregationDepthTargetOptimization(8),
						new ParallelismDiversityOptimization(),
						new ParallelismTargetOptimization()
				));
		TimingResult cascading = measureDeepChain(depth, size, true);
		logResult("CascadingOptimizationStrategy (full):");
		logTiming(cascading);

		logResult("");
		logResult("=== Summary ===");
		logResult("Fastest compile: " + findFastest(noOpt, parallelism, depth4, depth2, cascading));
	}

	/**
	 * Tests the impact of depth limit on performance.
	 *
	 * <p><strong>Purpose:</strong> Find optimal depth limit for a given workload.</p>
	 */
	@Test(timeout = 120000)
	@TestDepth(2)
	public void depthLimitImpact() {
		int chainDepth = 8;
		int size = 256;

		logResult("=== Depth Limit Impact (chain depth=" + chainDepth + ") ===");
		logResult("");

		for (int limit = 2; limit <= 8; limit += 2) {
			ProcessContextBase.setDefaultOptimizationStrategy(
					new TraversableDepthTargetOptimization(limit));

			TimingResult result = measureDeepChain(chainDepth, size, true);

			logResult("Depth limit " + limit + ":");
			logTiming(result);
			logResult("");
		}
	}

	// =========================================================================
	// Realistic Workload Tests
	// =========================================================================

	/**
	 * Simulates a transformer attention-like pattern.
	 *
	 * <p><strong>Purpose:</strong> Test strategies on realistic ML workloads.</p>
	 */
	@Test(timeout = 60000)
	@TestDepth(2)
	public void attentionLikePattern() {
		int seqLen = 128;
		int heads = 8;
		int headSize = 64;

		ProcessContextBase.setDefaultOptimizationStrategy(
				new CascadingOptimizationStrategy(
						new TraversableDepthTargetOptimization(6),
						new ParallelismTargetOptimization()
				));

		PackedCollection q = new PackedCollection(shape(seqLen, heads, headSize));
		PackedCollection k = new PackedCollection(shape(seqLen, heads, headSize));
		PackedCollection v = new PackedCollection(shape(seqLen, heads, headSize));

		q.fill(pos -> Math.random());
		k.fill(pos -> Math.random());
		v.fill(pos -> Math.random());

		// Simplified attention pattern: reshape, matmul, reshape
		TimingResult result = measureOperation(
				"Attention-like pattern",
				() -> {
					CollectionProducer qp = c(p(q)).reshape(seqLen * heads, headSize);
					CollectionProducer kp = c(p(k)).reshape(seqLen * heads, headSize);
					CollectionProducer vp = c(p(v)).reshape(seqLen * heads, headSize);

					CollectionProducer scaled = qp.multiply(1.0 / Math.sqrt(headSize));
					CollectionProducer combined = scaled.add(kp).add(vp);

					OperationList op = new OperationList("attention", false);
					PackedCollection out = new PackedCollection(shape(seqLen * heads, headSize));
					op.add(a(p(out), combined));
					return op;
				},
				true
		);

		logResult("Attention-like Pattern:");
		logTiming(result);
	}

	// =========================================================================
	// Helper Methods
	// =========================================================================

	/**
	 * Creates a chain of nested multiply operations.
	 */
	private CollectionProducer createDeepChain(PackedCollection input, int depth) {
		CollectionProducer current = c(p(input));

		for (int i = 0; i < depth; i++) {
			current = current.multiply(1.0 + i * 0.1);
		}

		return current;
	}

	/**
	 * Measures timing for a deep chain computation.
	 */
	private TimingResult measureDeepChain(int depth, int size, boolean optimize) {
		PackedCollection input = new PackedCollection(shape(size));
		PackedCollection output = new PackedCollection(shape(size));
		input.fill(pos -> Math.random());

		return measureOperation(
				"Deep chain depth=" + depth,
				() -> {
					CollectionProducer chain = createDeepChain(input, depth);
					OperationList op = new OperationList("deepChain", false);
					op.add(a(p(output), chain));
					return op;
				},
				optimize
		);
	}

	/**
	 * Measures timing for wide concatenation.
	 */
	private TimingResult measureWideConcat(int numInputs, int size, boolean optimize) {
		PackedCollection[] inputs = new PackedCollection[numInputs];
		for (int i = 0; i < numInputs; i++) {
			inputs[i] = new PackedCollection(shape(size));
			inputs[i].fill(pos -> Math.random());
		}

		PackedCollection output = new PackedCollection(shape(numInputs * size));

		return measureOperation(
				"Wide concat inputs=" + numInputs,
				() -> {
					// Create producers that do some work before concat
					CollectionProducer[] producers = new CollectionProducer[numInputs];
					for (int i = 0; i < numInputs; i++) {
						producers[i] = c(p(inputs[i])).multiply(1.0 + i * 0.1);
					}

					// Concat all producers
					CollectionProducer result = producers[0];
					for (int i = 1; i < numInputs; i++) {
						result = concat(result, producers[i]);
					}

					OperationList op = new OperationList("wideConcat", false);
					op.add(a(p(output), result));
					return op;
				},
				optimize
		);
	}

	/**
	 * Measures timing for a custom operation builder.
	 */
	private TimingResult measureOperation(
			String name,
			java.util.function.Supplier<OperationList> opBuilder,
			boolean optimize) {

		// Build the operation
		long buildStart = System.nanoTime();
		OperationList op = opBuilder.get();
		long buildTime = System.nanoTime() - buildStart;

		// Optimize if requested
		long optimizeStart = System.nanoTime();
		Process<?, ?> optimized = optimize ? op.optimize() : op;
		long optimizeTime = System.nanoTime() - optimizeStart;

		// Compile
		OperationProfile profile = new OperationProfile();
		long compileStart = System.nanoTime();
		Runnable runnable = ((OperationList) optimized).get(profile);
		long compileTime = System.nanoTime() - compileStart;

		// Run
		long runStart = System.nanoTime();
		runnable.run();
		long runTime = System.nanoTime() - runStart;

		return new TimingResult(buildTime, optimizeTime, compileTime, runTime, profile);
	}

	private void logResult(String message) {
		if (VERBOSE) {
			log(message);
		}
	}

	private void logTiming(TimingResult result) {
		logResult(String.format("  Build:    %8.2f ms", result.buildTimeMs()));
		logResult(String.format("  Optimize: %8.2f ms", result.optimizeTimeMs()));
		logResult(String.format("  Compile:  %8.2f ms", result.compileTimeMs()));
		logResult(String.format("  Run:      %8.2f ms", result.runTimeMs()));
		logResult(String.format("  Total:    %8.2f ms", result.totalTimeMs()));
	}

	private String findFastest(TimingResult... results) {
		double minCompile = Double.MAX_VALUE;
		int minIndex = 0;

		for (int i = 0; i < results.length; i++) {
			if (results[i].compileTimeMs() < minCompile) {
				minCompile = results[i].compileTimeMs();
				minIndex = i;
			}
		}

		String[] names = {
				"No Optimization",
				"ParallelismTargetOptimization",
				"TraversableDepthTargetOptimization(4)",
				"TraversableDepthTargetOptimization(2)",
				"CascadingOptimizationStrategy"
		};

		return names[minIndex] + " (" + String.format("%.2f", minCompile) + " ms)";
	}

}
