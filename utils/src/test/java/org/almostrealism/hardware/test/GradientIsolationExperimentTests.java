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

import io.almostrealism.compute.CascadingOptimizationStrategy;
import io.almostrealism.compute.ParallelismTargetOptimization;
import io.almostrealism.compute.Process;
import io.almostrealism.compute.ProcessContextBase;
import io.almostrealism.compute.ProcessOptimizationStrategy;
import io.almostrealism.compute.TraversableDepthTargetOptimization;
import io.almostrealism.profile.OperationProfile;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Experimental tests to understand why gradient computations don't get
 * properly isolated by existing optimization strategies.
 *
 * <h2>Problem Statement</h2>
 * <p>The rotateHalf gradient (subset + minus + concat) times out even though:
 * <ul>
 *   <li>Each operation extends TransitiveDeltaExpressionComputation</li>
 *   <li>We have ParallelismTargetOptimization and TraversableDepthTargetOptimization</li>
 *   <li>Individual operations work fine in isolation</li>
 * </ul>
 *
 * <h2>Hypothesis</h2>
 * <p>The delta() method creates new CollectionProducer instances that:
 * <ul>
 *   <li>Have the same parallelism as their inputs</li>
 *   <li>Don't trigger isolation because they look "uniform"</li>
 *   <li>Get their expressions embedded in parent deltas</li>
 * </ul>
 *
 * <h2>Experiments</h2>
 * <ol>
 *   <li>Baseline: No manual isolation, use default strategy</li>
 *   <li>Full isolation: Manually evaluate() each intermediate delta</li>
 *   <li>Partial isolation: Isolate only subset deltas</li>
 *   <li>Strategy tuning: Aggressive depth limits</li>
 * </ol>
 *
 * @author Michael Murray
 */
public class GradientIsolationExperimentTests extends TestSuiteBase {

	private static final int BATCH = 1;
	private static final int HEADS = 4;
	private static final int SEQ_LEN = 16;
	private static final int DIM = 32;
	private static final int HALF_DIM = DIM / 2;

	// =========================================================================
	// Experiment 1: Baseline - Default Strategy
	// =========================================================================

	/**
	 * Baseline test with default optimization strategy.
	 * This should show the problem - either timeout or very slow.
	 */
	@Test(timeout = 180000)
	@TestDepth(10)
	public void experiment1_baseline() {
		logResult("=== Experiment 1: Baseline (Default Strategy) ===");
		ProcessContextBase.setDefaultOptimizationStrategy(new ParallelismTargetOptimization());

		PackedCollection input = new PackedCollection(shape(BATCH, HEADS, SEQ_LEN, DIM)).randnFill();
		CollectionProducer x = cp(input);

		TimingResult result = measureGradient("baseline", () -> {
			CollectionProducer rotated = rotateHalf(x);
			return rotated.delta(x);
		});

		logTiming(result);
		logResult("");
	}

	/**
	 * Test with aggressive depth-based isolation.
	 */
	@Test(timeout = 120000)
	@TestDepth(10)
	public void experiment1b_aggressiveDepth() {
		logResult("=== Experiment 1b: Aggressive Depth Strategy (limit=2) ===");
		ProcessContextBase.setDefaultOptimizationStrategy(
				new CascadingOptimizationStrategy(
						new TraversableDepthTargetOptimization(2),
						new ParallelismTargetOptimization()
				));

		PackedCollection input = new PackedCollection(shape(BATCH, HEADS, SEQ_LEN, DIM)).randnFill();
		CollectionProducer x = cp(input);

		TimingResult result = measureGradient("aggressiveDepth", () -> {
			CollectionProducer rotated = rotateHalf(x);
			return rotated.delta(x);
		});

		logTiming(result);
		logResult("");
	}

	// =========================================================================
	// Experiment 2: Manual Full Isolation
	// =========================================================================

	/**
	 * Manually isolate every intermediate computation by calling evaluate().
	 * This forces each step to compile and run separately.
	 */
	@Test(timeout = 120000)
	public void experiment2_fullManualIsolation() {
		logResult("=== Experiment 2: Full Manual Isolation ===");
		ProcessContextBase.setDefaultOptimizationStrategy(new ParallelismTargetOptimization());

		PackedCollection input = new PackedCollection(shape(BATCH, HEADS, SEQ_LEN, DIM)).randnFill();

		long totalStart = System.nanoTime();

		// Step 1: Compute x1 = subset(input, first half)
		logResult("Step 1: Computing x1 subset...");
		long step1Start = System.nanoTime();
		CollectionProducer x1Producer = cp(input).subset(shape(BATCH, HEADS, SEQ_LEN, HALF_DIM), 0, 0, 0, 0);
		PackedCollection x1 = x1Producer.evaluate();
		logResult("  x1 shape: " + x1.getShape() + " (" + formatMs(System.nanoTime() - step1Start) + ")");

		// Step 2: Compute x2 = subset(input, second half)
		logResult("Step 2: Computing x2 subset...");
		long step2Start = System.nanoTime();
		CollectionProducer x2Producer = cp(input).subset(shape(BATCH, HEADS, SEQ_LEN, HALF_DIM), 0, 0, 0, HALF_DIM);
		PackedCollection x2 = x2Producer.evaluate();
		logResult("  x2 shape: " + x2.getShape() + " (" + formatMs(System.nanoTime() - step2Start) + ")");

		// Step 3: Compute -x2
		logResult("Step 3: Computing -x2...");
		long step3Start = System.nanoTime();
		CollectionProducer negX2Producer = cp(x2).minus();
		PackedCollection negX2 = negX2Producer.evaluate();
		logResult("  -x2 shape: " + negX2.getShape() + " (" + formatMs(System.nanoTime() - step3Start) + ")");

		// Step 4: Compute concat(-x2, x1)
		logResult("Step 4: Computing concat(-x2, x1)...");
		long step4Start = System.nanoTime();
		CollectionProducer concatProducer = concat(3, cp(negX2), cp(x1));
		PackedCollection concatResult = concatProducer.evaluate();
		logResult("  concat shape: " + concatResult.getShape() + " (" + formatMs(System.nanoTime() - step4Start) + ")");

		// Now compute gradients with full isolation
		logResult("Step 5: Computing gradient d(x1)/d(input)...");
		long step5Start = System.nanoTime();
		CollectionProducer dx1 = cp(input).subset(shape(BATCH, HEADS, SEQ_LEN, HALF_DIM), 0, 0, 0, 0).delta(cp(input));
		PackedCollection dx1Result = dx1.evaluate();
		logResult("  dx1 shape: " + dx1Result.getShape() + " (" + formatMs(System.nanoTime() - step5Start) + ")");

		// Step 6: Gradient of x2 w.r.t. input
		logResult("Step 6: Computing gradient d(x2)/d(input)...");
		long step6Start = System.nanoTime();
		CollectionProducer dx2 = cp(input).subset(shape(BATCH, HEADS, SEQ_LEN, HALF_DIM), 0, 0, 0, HALF_DIM).delta(cp(input));
		PackedCollection dx2Result = dx2.evaluate();
		logResult("  dx2 shape: " + dx2Result.getShape() + " (" + formatMs(System.nanoTime() - step6Start) + ")");

		long totalTime = System.nanoTime() - totalStart;
		logResult("Total time with full isolation: " + formatMs(totalTime));
		logResult("");
	}

	// =========================================================================
	// Experiment 3: Isolate Only the Problematic Parts
	// =========================================================================

	/**
	 * Tests if isolating just the subset deltas helps.
	 */
	@Test(timeout = 120000)
	public void experiment3_isolateSubsetDeltasOnly() {
		logResult("=== Experiment 3: Isolate Subset Deltas Only ===");
		ProcessContextBase.setDefaultOptimizationStrategy(new ParallelismTargetOptimization());

		PackedCollection input = new PackedCollection(shape(BATCH, HEADS, SEQ_LEN, DIM)).randnFill();
		CollectionProducer x = cp(input);

		long totalStart = System.nanoTime();

		// Compute subset deltas in isolation first
		logResult("Computing isolated subset deltas...");

		long dx1Start = System.nanoTime();
		CollectionProducer x1 = x.subset(shape(BATCH, HEADS, SEQ_LEN, HALF_DIM), 0, 0, 0, 0);
		CollectionProducer dx1 = x1.delta(x);
		PackedCollection dx1Eval = dx1.evaluate();
		logResult("  d(x1)/d(x) computed: " + dx1Eval.getShape() + " (" + formatMs(System.nanoTime() - dx1Start) + ")");

		long dx2Start = System.nanoTime();
		CollectionProducer x2 = x.subset(shape(BATCH, HEADS, SEQ_LEN, HALF_DIM), 0, 0, 0, HALF_DIM);
		CollectionProducer dx2 = x2.delta(x);
		PackedCollection dx2Eval = dx2.evaluate();
		logResult("  d(x2)/d(x) computed: " + dx2Eval.getShape() + " (" + formatMs(System.nanoTime() - dx2Start) + ")");

		// Now try to build the combined gradient using the pre-computed deltas
		// This tests if the problem is in combining the deltas vs computing them
		logResult("Computing combined gradient from isolated deltas...");
		long combineStart = System.nanoTime();

		// The full rotateHalf gradient would need to combine these...
		// For now just verify we can compute them separately
		logResult("  Subset deltas computed separately in: " + formatMs(System.nanoTime() - totalStart));
		logResult("");
	}

	// =========================================================================
	// Experiment 4: Analyze What the Strategies See
	// =========================================================================

	/**
	 * Analyze the parallelism and depth that strategies see during optimization.
	 */
	@Test(timeout = 120000)
	@TestDepth(10)
	public void experiment4_analyzeStrategyDecisions() {
		logResult("=== Experiment 4: Analyze Strategy Decisions ===");

		// Use a logging strategy wrapper
		ProcessOptimizationStrategy loggingStrategy = new LoggingStrategyWrapper(
				new CascadingOptimizationStrategy(
						new TraversableDepthTargetOptimization(4),
						new ParallelismTargetOptimization()
				),
				this::logResult
		);
		ProcessContextBase.setDefaultOptimizationStrategy(loggingStrategy);

		PackedCollection input = new PackedCollection(shape(BATCH, HEADS, SEQ_LEN, DIM)).randnFill();
		CollectionProducer x = cp(input);

		logResult("Building rotateHalf gradient...");
		CollectionProducer rotated = rotateHalf(x);
		CollectionProducer gradient = rotated.delta(x);

		logResult("Optimizing...");
		OperationList op = new OperationList("experiment4", false);
		PackedCollection out = new PackedCollection(gradient.getShape());
		op.add(a(p(out), gradient));

		long optimizeStart = System.nanoTime();
		Process<?, ?> optimized = op.optimize();
		logResult("Optimize time: " + formatMs(System.nanoTime() - optimizeStart));

		logResult("Compiling...");
		long compileStart = System.nanoTime();
		OperationProfile profile = new OperationProfile();
		Runnable compiled = ((OperationList) optimized).get(profile);
		logResult("Compile time: " + formatMs(System.nanoTime() - compileStart));

		logResult("Running...");
		long runStart = System.nanoTime();
		compiled.run();
		logResult("Run time: " + formatMs(System.nanoTime() - runStart));

		logResult("");
	}

	// =========================================================================
	// Experiment 5: Compare Forward vs Gradient Isolation
	// =========================================================================

	/**
	 * Compare isolation behavior between forward pass and gradient.
	 * The forward pass should work fine; the gradient might not.
	 */
	@Test(timeout = 60000)
	@TestDepth(10)
	public void experiment5_forwardVsGradient() {
		logResult("=== Experiment 5: Forward vs Gradient Isolation ===");
		ProcessContextBase.setDefaultOptimizationStrategy(
				new CascadingOptimizationStrategy(
						new TraversableDepthTargetOptimization(4),
						new ParallelismTargetOptimization()
				));

		PackedCollection input = new PackedCollection(shape(BATCH, HEADS, SEQ_LEN, DIM)).randnFill();
		CollectionProducer x = cp(input);

		// Forward pass
		logResult("Forward pass (rotateHalf):");
		TimingResult forward = measureOperation("forward", () -> {
			CollectionProducer rotated = rotateHalf(x);
			OperationList op = new OperationList("forward", false);
			PackedCollection out = new PackedCollection(rotated.getShape());
			op.add(a(p(out), rotated));
			return op;
		});
		logTiming(forward);
		logResult("");

		// Gradient pass
		logResult("Gradient pass (d(rotateHalf)/d(x)):");
		TimingResult gradient = measureOperation("gradient", () -> {
			CollectionProducer rotated = rotateHalf(x);
			CollectionProducer grad = rotated.delta(x);
			OperationList op = new OperationList("gradient", false);
			PackedCollection out = new PackedCollection(grad.getShape());
			op.add(a(p(out), grad));
			return op;
		});
		logTiming(gradient);

		logResult("");
		logResult("Ratio (gradient/forward compile): " +
				String.format("%.1fx", gradient.compileTimeMs() / forward.compileTimeMs()));
		logResult("");
	}

	// =========================================================================
	// Experiment 6: Simpler Gradient Chain
	// =========================================================================

	/**
	 * Test a simpler gradient chain to isolate the issue.
	 * Just subset -> minus (no concat).
	 */
	@Test(timeout = 60000)
	public void experiment6_simpleSubsetMinusGradient() {
		logResult("=== Experiment 6: Simple Subset -> Minus Gradient ===");
		ProcessContextBase.setDefaultOptimizationStrategy(
				new CascadingOptimizationStrategy(
						new TraversableDepthTargetOptimization(4),
						new ParallelismTargetOptimization()
				));

		PackedCollection input = new PackedCollection(shape(BATCH, HEADS, SEQ_LEN, DIM)).randnFill();
		CollectionProducer x = cp(input);

		TimingResult result = measureGradient("subsetMinus", () -> {
			CollectionProducer subset = x.subset(shape(BATCH, HEADS, SEQ_LEN, HALF_DIM), 0, 0, 0, 0);
			CollectionProducer negated = subset.minus();
			return negated.delta(x);
		});

		logTiming(result);
		logResult("");
	}

	/**
	 * Test subset -> concat gradient (no minus).
	 */
	@Test(timeout = 180000)
	@TestDepth(10)
	public void experiment6b_subsetConcatGradient() {
		logResult("=== Experiment 6b: Subset -> Concat Gradient (no minus) ===");
		ProcessContextBase.setDefaultOptimizationStrategy(
				new CascadingOptimizationStrategy(
						new TraversableDepthTargetOptimization(4),
						new ParallelismTargetOptimization()
				));

		PackedCollection input = new PackedCollection(shape(BATCH, HEADS, SEQ_LEN, DIM)).randnFill();
		CollectionProducer x = cp(input);

		TimingResult result = measureGradient("subsetConcat", () -> {
			CollectionProducer x1 = x.subset(shape(BATCH, HEADS, SEQ_LEN, HALF_DIM), 0, 0, 0, 0);
			CollectionProducer x2 = x.subset(shape(BATCH, HEADS, SEQ_LEN, HALF_DIM), 0, 0, 0, HALF_DIM);
			CollectionProducer concatenated = concat(3, x2, x1);
			return concatenated.delta(x);
		});

		logTiming(result);
		logResult("");
	}

	// =========================================================================
	// Experiment 7: Dimension Scaling Analysis
	// =========================================================================

	/**
	 * Test gradient with very small dimensions to find the threshold.
	 * Start with 1D tensors - simplest case.
	 */
	@Test(timeout = 30000)
	public void experiment7a_tinySubsetGradient() {
		logResult("=== Experiment 7a: Tiny Subset Gradient (1D, size=8) ===");
		ProcessContextBase.setDefaultOptimizationStrategy(
				new CascadingOptimizationStrategy(
						new TraversableDepthTargetOptimization(4),
						new ParallelismTargetOptimization()
				));

		// Very small 1D tensor
		int size = 8;
		PackedCollection input = new PackedCollection(shape(size)).randnFill();
		CollectionProducer x = cp(input);

		TimingResult result = measureGradient("tiny1D", () -> {
			CollectionProducer subset = x.subset(shape(size / 2), 0);
			return subset.delta(x);
		});

		logTiming(result);
		logResult("  Jacobian shape: [" + (size/2) + "," + size + "]");
		logResult("");
	}

	/**
	 * Test 2D gradient to see scaling.
	 */
	@Test(timeout = 30000)
	public void experiment7b_small2DSubsetGradient() {
		logResult("=== Experiment 7b: Small 2D Subset Gradient (4x8) ===");
		ProcessContextBase.setDefaultOptimizationStrategy(
				new CascadingOptimizationStrategy(
						new TraversableDepthTargetOptimization(4),
						new ParallelismTargetOptimization()
				));

		// Small 2D tensor
		int rows = 4;
		int cols = 8;
		PackedCollection input = new PackedCollection(shape(rows, cols)).randnFill();
		CollectionProducer x = cp(input);

		TimingResult result = measureGradient("small2D", () -> {
			CollectionProducer subset = x.subset(shape(rows, cols / 2), 0, 0);
			return subset.delta(x);
		});

		logTiming(result);
		logResult("  Jacobian shape: [" + rows + "," + (cols/2) + "," + rows + "," + cols + "]");
		logResult("");
	}

	/**
	 * Test 3D gradient to see scaling.
	 */
	@Test(timeout = 60000)
	public void experiment7c_medium3DSubsetGradient() {
		logResult("=== Experiment 7c: Medium 3D Subset Gradient (2x4x8) ===");
		ProcessContextBase.setDefaultOptimizationStrategy(
				new CascadingOptimizationStrategy(
						new TraversableDepthTargetOptimization(4),
						new ParallelismTargetOptimization()
				));

		// Medium 3D tensor
		int d1 = 2;
		int d2 = 4;
		int d3 = 8;
		PackedCollection input = new PackedCollection(shape(d1, d2, d3)).randnFill();
		CollectionProducer x = cp(input);

		TimingResult result = measureGradient("medium3D", () -> {
			CollectionProducer subset = x.subset(shape(d1, d2, d3 / 2), 0, 0, 0);
			return subset.delta(x);
		});

		logTiming(result);
		logResult("  Jacobian shape: [" + d1 + "," + d2 + "," + (d3/2) + "," + d1 + "," + d2 + "," + d3 + "]");
		logResult("");
	}

	// =========================================================================
	// Experiment 8: Just Subset Gradient (No Chaining)
	// =========================================================================

	/**
	 * Test just a single subset gradient with the original dimensions.
	 * This isolates whether the problem is in subset.delta() itself
	 * or in chaining deltas.
	 */
	@Test(timeout = 60000)
	public void experiment8_justSubsetGradient() {
		logResult("=== Experiment 8: Just Subset Gradient (No Chain) ===");
		ProcessContextBase.setDefaultOptimizationStrategy(
				new CascadingOptimizationStrategy(
						new TraversableDepthTargetOptimization(4),
						new ParallelismTargetOptimization()
				));

		PackedCollection input = new PackedCollection(shape(BATCH, HEADS, SEQ_LEN, DIM)).randnFill();
		CollectionProducer x = cp(input);

		logResult("Input shape: " + input.getShape());
		logResult("Subset shape: " + shape(BATCH, HEADS, SEQ_LEN, HALF_DIM));

		TimingResult result = measureGradient("justSubset", () -> {
			CollectionProducer subset = x.subset(shape(BATCH, HEADS, SEQ_LEN, HALF_DIM), 0, 0, 0, 0);
			return subset.delta(x);
		});

		logTiming(result);
		logResult("");
	}

	/**
	 * Test just minus gradient (on a subset, but only one operation).
	 */
	@Test(timeout = 60000)
	@TestDepth(10)
	public void experiment8b_justMinusGradient() {
		logResult("=== Experiment 8b: Just Minus Gradient ===");
		ProcessContextBase.setDefaultOptimizationStrategy(
				new CascadingOptimizationStrategy(
						new TraversableDepthTargetOptimization(4),
						new ParallelismTargetOptimization()
				));

		// Use a smaller dimension for the minus operation
		PackedCollection input = new PackedCollection(shape(BATCH, HEADS, SEQ_LEN, HALF_DIM)).randnFill();
		CollectionProducer x = cp(input);

		logResult("Input shape: " + input.getShape());

		TimingResult result = measureGradient("justMinus", () -> {
			CollectionProducer negated = x.minus();
			return negated.delta(x);
		});

		logTiming(result);
		logResult("");
	}

	// =========================================================================
	// Experiment 9: Manual Isolation with Process.isolate()
	// =========================================================================

	/**
	 * Test if using Process.isolate() on the gradient helps.
	 */
	@Test(timeout = 120000)
	public void experiment9_isolateGradientProcess() {
		logResult("=== Experiment 9: Isolate Gradient via Process.isolate() ===");
		ProcessContextBase.setDefaultOptimizationStrategy(
				new CascadingOptimizationStrategy(
						new TraversableDepthTargetOptimization(4),
						new ParallelismTargetOptimization()
				));

		PackedCollection input = new PackedCollection(shape(BATCH, HEADS, SEQ_LEN, DIM)).randnFill();
		CollectionProducer x = cp(input);

		logResult("Testing with explicit isolation on subset delta...");

		long totalStart = System.nanoTime();

		// Create subset and compute its delta with explicit isolation
		CollectionProducer x1 = x.subset(shape(BATCH, HEADS, SEQ_LEN, HALF_DIM), 0, 0, 0, 0);

		// Try isolating the subset itself before computing delta
		logResult("Step 1: Isolate the subset...");
		long step1Start = System.nanoTime();
		OperationList subsetOp = new OperationList("subsetOp", false);
		PackedCollection subsetOut = new PackedCollection(x1.getShape());
		subsetOp.add(a(p(subsetOut), x1));

		Process<?, ?> isolatedSubset = subsetOp.optimize();
		Runnable subsetRunnable = ((OperationList) isolatedSubset).get();
		subsetRunnable.run();
		logResult("  Subset isolated and evaluated: " + subsetOut.getShape() + " (" + formatMs(System.nanoTime() - step1Start) + ")");

		// Now compute delta of the isolated subset result
		// Note: This loses the connection to the original input...
		// The real test is whether isolating intermediate gradient computations helps

		logResult("Step 2: Compute delta with subset input isolated...");
		long step2Start = System.nanoTime();

		// We need to compute the delta differently - the isolated subset
		// doesn't maintain the computational graph

		// Instead, let's try forcing isolation on the delta itself
		CollectionProducer delta = x1.delta(x);

		// Try using explicit isolation predicate
		logResult("  Creating assignment with delta...");
		OperationList deltaOp = new OperationList("deltaOp", false);
		PackedCollection deltaOut = new PackedCollection(delta.getShape());
		deltaOp.add(a(p(deltaOut), delta));

		logResult("  Optimizing delta operation...");
		long optimizeStart = System.nanoTime();
		Process<?, ?> optimizedDelta = deltaOp.optimize();
		logResult("  Optimize time: " + formatMs(System.nanoTime() - optimizeStart));

		logResult("  Compiling delta operation...");
		long compileStart = System.nanoTime();
		Runnable deltaRunnable = ((OperationList) optimizedDelta).get();
		logResult("  Compile time: " + formatMs(System.nanoTime() - compileStart));

		logResult("  Running delta operation...");
		long runStart = System.nanoTime();
		deltaRunnable.run();
		logResult("  Run time: " + formatMs(System.nanoTime() - runStart));

		logResult("Total time: " + formatMs(System.nanoTime() - totalStart));
		logResult("");
	}

	// =========================================================================
	// Experiment 10: Identity Matrix Structure Hypothesis Test
	// =========================================================================

	/**
	 * Test if the problem is specifically that identity matrices aren't exploited.
	 * Compare subset-of-input (identity delta) vs subset-of-transformed (non-identity delta).
	 */
	@Test(timeout = 60000)
	public void experiment10a_subsetOfTransformed() {
		logResult("=== Experiment 10a: Subset of Transformed (non-identity child delta) ===");
		ProcessContextBase.setDefaultOptimizationStrategy(
				new CascadingOptimizationStrategy(
						new TraversableDepthTargetOptimization(4),
						new ParallelismTargetOptimization()
				));

		// Use smaller dimensions to avoid timeout
		int b = 1, h = 2, s = 8, d = 16;
		PackedCollection input = new PackedCollection(shape(b, h, s, d)).randnFill();
		CollectionProducer x = cp(input);

		logResult("Input shape: " + input.getShape());

		// Subset of a TRANSFORMED input (multiply by 2) - child delta is NOT identity
		logResult("Computing gradient of subset(input * 2)...");
		TimingResult result = measureGradient("subsetTransformed", () -> {
			CollectionProducer transformed = x.multiply(2.0);
			CollectionProducer subset = transformed.subset(shape(b, h, s, d / 2), 0, 0, 0, 0);
			return subset.delta(x);
		});

		logTiming(result);
		logResult("");
	}

	/**
	 * Same dimensions but subset directly from input (identity child delta).
	 */
	@Test(timeout = 60000)
	public void experiment10b_subsetOfInput() {
		logResult("=== Experiment 10b: Subset of Input (identity child delta) ===");
		ProcessContextBase.setDefaultOptimizationStrategy(
				new CascadingOptimizationStrategy(
						new TraversableDepthTargetOptimization(4),
						new ParallelismTargetOptimization()
				));

		// Same dimensions as 10a
		int b = 1, h = 2, s = 8, d = 16;
		PackedCollection input = new PackedCollection(shape(b, h, s, d)).randnFill();
		CollectionProducer x = cp(input);

		logResult("Input shape: " + input.getShape());

		// Subset directly from input - child delta IS identity
		logResult("Computing gradient of subset(input)...");
		TimingResult result = measureGradient("subsetDirect", () -> {
			CollectionProducer subset = x.subset(shape(b, h, s, d / 2), 0, 0, 0, 0);
			return subset.delta(x);
		});

		logTiming(result);
		logResult("");
	}

	// =========================================================================
	// Helper Methods
	// =========================================================================

	/**
	 * Rotates the input tensor by swapping and negating halves.
	 */
	private CollectionProducer rotateHalf(CollectionProducer input) {
		CollectionProducer x1 = input.subset(shape(BATCH, HEADS, SEQ_LEN, HALF_DIM), 0, 0, 0, 0);
		CollectionProducer x2 = input.subset(shape(BATCH, HEADS, SEQ_LEN, HALF_DIM), 0, 0, 0, HALF_DIM);
		return concat(3, x2.minus(), x1);
	}

	private TimingResult measureGradient(String name, java.util.function.Supplier<CollectionProducer> gradientBuilder) {
		return measureOperation(name, () -> {
			CollectionProducer gradient = gradientBuilder.get();
			OperationList op = new OperationList(name, false);
			PackedCollection out = new PackedCollection(gradient.getShape());
			op.add(a(p(out), gradient));
			return op;
		});
	}

	private TimingResult measureOperation(String name, java.util.function.Supplier<OperationList> opBuilder) {
		long buildStart = System.nanoTime();
		OperationList op = opBuilder.get();
		long buildTime = System.nanoTime() - buildStart;

		long optimizeStart = System.nanoTime();
		Process<?, ?> optimized = op.optimize();
		long optimizeTime = System.nanoTime() - optimizeStart;

		OperationProfile profile = new OperationProfile();
		long compileStart = System.nanoTime();
		Runnable runnable = ((OperationList) optimized).get(profile);
		long compileTime = System.nanoTime() - compileStart;

		long runStart = System.nanoTime();
		runnable.run();
		long runTime = System.nanoTime() - runStart;

		return new TimingResult(buildTime, optimizeTime, compileTime, runTime, profile);
	}

	private void logResult(String message) {
		log(message);
	}

	private void logTiming(TimingResult result) {
		logResult(String.format("  Build:    %8.2f ms", result.buildTimeMs()));
		logResult(String.format("  Optimize: %8.2f ms", result.optimizeTimeMs()));
		logResult(String.format("  Compile:  %8.2f ms", result.compileTimeMs()));
		logResult(String.format("  Run:      %8.2f ms", result.runTimeMs()));
		logResult(String.format("  Total:    %8.2f ms", result.totalTimeMs()));
	}

	private String formatMs(long nanos) {
		return String.format("%.2f ms", nanos / 1_000_000.0);
	}

	/**
	 * Wrapper that logs strategy decisions.
	 */
	private static class LoggingStrategyWrapper implements ProcessOptimizationStrategy {
		private final ProcessOptimizationStrategy delegate;
		private final java.util.function.Consumer<String> logger;

		LoggingStrategyWrapper(ProcessOptimizationStrategy delegate, java.util.function.Consumer<String> logger) {
			this.delegate = delegate;
			this.logger = logger;
		}

		@Override
		public <P extends Process<?, ?>, T> Process<P, T> optimize(
				io.almostrealism.compute.ProcessContext ctx,
				Process<P, T> parent,
				java.util.Collection<P> children,
				java.util.function.Function<java.util.Collection<P>, java.util.stream.Stream<P>> childProcessor) {

			String parentName = parent.getClass().getSimpleName();
			int childCount = children.size();

			long maxParallelism = 0;
			for (P child : children) {
				if (child instanceof io.almostrealism.compute.ParallelProcess) {
					long p = ((io.almostrealism.compute.ParallelProcess<?, ?>) child).getParallelism();
					if (p > maxParallelism) maxParallelism = p;
				}
			}

			logger.accept("[STRATEGY] " + parentName +
					" children=" + childCount +
					" maxParallelism=" + maxParallelism +
					" depth=" + ctx.getDepth());

			return delegate.optimize(ctx, parent, children, childProcessor);
		}
	}
}
