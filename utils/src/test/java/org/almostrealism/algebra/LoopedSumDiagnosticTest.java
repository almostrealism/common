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
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

/**
 * Diagnostic test to examine what code is actually generated
 * for LoopedWeightedSumComputation.
 */
public class LoopedSumDiagnosticTest implements TestFeatures, LayerFeatures {

	/**
	 * Small test with instruction set monitoring to see generated code.
	 */
	@Test
	public void testGeneratedCode() {
		// Enable verbose logging
		HardwareOperator.enableInstructionSetMonitoring = true;
		HardwareOperator.enableVerboseLog = true;

		int outerCount = 8;  // Small enough to see the pattern
		int innerCount = 4;
		int outputSize = 4;

		TraversalPolicy outputShape = shape(outputSize).traverseEach();
		TraversalPolicy inputShape = shape(outerCount, outputSize + innerCount - 1);
		TraversalPolicy weightShape = shape(outerCount, innerCount);

		PackedCollection input = new PackedCollection(inputShape).randFill();
		PackedCollection weights = new PackedCollection(weightShape).randFill();

		LoopedWeightedSumComputation.InputIndexer inputIndexer = (outputIndex, outerIndex, innerIndex) -> {
			return outerIndex.multiply(outputSize + innerCount - 1).add(outputIndex).add(innerIndex);
		};

		LoopedWeightedSumComputation.WeightIndexer weightIndexer = (outputIndex, outerIndex, innerIndex) -> {
			return outerIndex.multiply(innerCount).add(innerIndex);
		};

		LoopedWeightedSumComputation computation = new LoopedWeightedSumComputation(
				"diagnosticLoopedSum",
				outputShape,
				outerCount,
				innerCount,
				inputShape,
				weightShape,
				inputIndexer,
				weightIndexer,
				cp(input),
				cp(weights));

		System.out.println("=== Diagnostic Test ===");
		System.out.println("outerCount=" + outerCount + ", innerCount=" + innerCount);
		System.out.println("isIsolationTarget: " + computation.isIsolationTarget(null));
		System.out.println();

		OperationList ops = new OperationList();
		PackedCollection output = new PackedCollection(outputShape);
		ops.add(a("diagnosticResult", p(output), computation));

		System.out.println("Compiling...");
		long start = System.currentTimeMillis();
		Runnable r = ops.get();
		long elapsed = System.currentTimeMillis() - start;
		System.out.println("Compilation took " + elapsed + "ms");

		r.run();
		System.out.println("Output[0]: " + output.toDouble(0));
		System.out.println();

		// Reset
		HardwareOperator.enableInstructionSetMonitoring = false;
		HardwareOperator.enableVerboseLog = false;
	}

	/**
	 * Test with optimization enabled to verify that isolation works.
	 */
	@Test
	public void testWithOptimization() {
		// Enable verbose logging
		HardwareOperator.enableInstructionSetMonitoring = true;
		HardwareOperator.enableVerboseLog = true;

		// ENABLE OPTIMIZATION - this should trigger isIsolationTarget() to be respected
		boolean previousOptimization = OperationList.enableAutomaticOptimization;
		OperationList.enableAutomaticOptimization = true;

		try {
			int outerCount = 8;  // Small enough to see the pattern
			int innerCount = 4;
			int outputSize = 4;

			TraversalPolicy outputShape = shape(outputSize).traverseEach();
			TraversalPolicy inputShape = shape(outerCount, outputSize + innerCount - 1);
			TraversalPolicy weightShape = shape(outerCount, innerCount);

			PackedCollection input = new PackedCollection(inputShape).randFill();
			PackedCollection weights = new PackedCollection(weightShape).randFill();

			LoopedWeightedSumComputation.InputIndexer inputIndexer = (outputIndex, outerIndex, innerIndex) -> {
				return outerIndex.multiply(outputSize + innerCount - 1).add(outputIndex).add(innerIndex);
			};

			LoopedWeightedSumComputation.WeightIndexer weightIndexer = (outputIndex, outerIndex, innerIndex) -> {
				return outerIndex.multiply(innerCount).add(innerIndex);
			};

			LoopedWeightedSumComputation computation = new LoopedWeightedSumComputation(
					"optimizedLoopedSum",
					outputShape,
					outerCount,
					innerCount,
					inputShape,
					weightShape,
					inputIndexer,
					weightIndexer,
					cp(input),
					cp(weights));

			System.out.println("=== Test With Optimization Enabled ===");
			System.out.println("outerCount=" + outerCount + ", innerCount=" + innerCount);
			System.out.println("isIsolationTarget: " + computation.isIsolationTarget(null));
			System.out.println("enableAutomaticOptimization: " + OperationList.enableAutomaticOptimization);
			System.out.println();

			OperationList ops = new OperationList();
			PackedCollection output = new PackedCollection(outputShape);
			ops.add(a("optimizedResult", p(output), computation));

			System.out.println("isUniform: " + ops.isUniform());
			System.out.println("Note: Uniform lists skip automatic optimization, calling optimize() explicitly");
			System.out.println();

			// Explicitly call optimize() to trigger isolation logic
			// The automatic optimization only runs for non-uniform lists (line 684 in OperationList)
			System.out.println("Optimizing...");
			OperationList optimized = (OperationList) ops.optimize();

			System.out.println("Compiling optimized version...");
			long start = System.currentTimeMillis();
			Runnable r = optimized.get();
			long elapsed = System.currentTimeMillis() - start;
			System.out.println("Compilation took " + elapsed + "ms");

			r.run();
			System.out.println("Output[0]: " + output.toDouble(0));
			System.out.println();
		} finally {
			// Reset
			OperationList.enableAutomaticOptimization = previousOptimization;
			HardwareOperator.enableInstructionSetMonitoring = false;
			HardwareOperator.enableVerboseLog = false;
		}
	}

	/**
	 * Compare timing of scope generation vs expression building.
	 */
	@Test
	public void testTimingBreakdown() {
		int outerCount = 64;
		int innerCount = 16;
		int outputSize = 8;

		TraversalPolicy outputShape = shape(outputSize).traverseEach();
		TraversalPolicy inputShape = shape(outerCount, outputSize + innerCount - 1);
		TraversalPolicy weightShape = shape(outerCount, innerCount);

		PackedCollection input = new PackedCollection(inputShape).randFill();
		PackedCollection weights = new PackedCollection(weightShape).randFill();

		LoopedWeightedSumComputation.InputIndexer inputIndexer = (outputIndex, outerIndex, innerIndex) -> {
			return outerIndex.multiply(outputSize + innerCount - 1).add(outputIndex).add(innerIndex);
		};

		LoopedWeightedSumComputation.WeightIndexer weightIndexer = (outputIndex, outerIndex, innerIndex) -> {
			return outerIndex.multiply(innerCount).add(innerIndex);
		};

		System.out.println("=== Timing Breakdown Test ===");
		System.out.println("outerCount=" + outerCount + ", innerCount=" + innerCount);
		System.out.println();

		// Time the computation creation
		long createStart = System.currentTimeMillis();
		LoopedWeightedSumComputation computation = new LoopedWeightedSumComputation(
				"timingTest",
				outputShape,
				outerCount,
				innerCount,
				inputShape,
				weightShape,
				inputIndexer,
				weightIndexer,
				cp(input),
				cp(weights));
		long createTime = System.currentTimeMillis() - createStart;
		System.out.println("Computation creation: " + createTime + "ms");

		// Time the operation list setup
		long opsStart = System.currentTimeMillis();
		OperationList ops = new OperationList();
		PackedCollection output = new PackedCollection(outputShape);
		ops.add(a("timingResult", p(output), computation));
		long opsTime = System.currentTimeMillis() - opsStart;
		System.out.println("OperationList setup: " + opsTime + "ms");

		// Time the get() call which does the actual compilation
		System.out.println("Calling ops.get() (this is where compilation happens)...");
		long compileStart = System.currentTimeMillis();
		Runnable r = ops.get();
		long compileTime = System.currentTimeMillis() - compileStart;
		System.out.println("ops.get() compilation: " + compileTime + "ms");

		// Time the execution
		long runStart = System.currentTimeMillis();
		r.run();
		long runTime = System.currentTimeMillis() - runStart;
		System.out.println("Execution: " + runTime + "ms");

		System.out.println();
		System.out.println("Output[0]: " + output.toDouble(0));
	}
}
