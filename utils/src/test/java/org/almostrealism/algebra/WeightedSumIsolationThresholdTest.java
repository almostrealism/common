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

package org.almostrealism.algebra;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.Process;
import io.almostrealism.compute.ProcessContext;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.computations.WeightedSumComputation;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.List;

/**
 * Systematic tests to determine the optimal isolation threshold for WeightedSumComputation.
 *
 * <p>Tests various group sizes and embedding scenarios to find where isolation
 * (native loops) becomes faster than expression tree construction.</p>
 */
public class WeightedSumIsolationThresholdTest extends TestSuiteBase implements AlgebraFeatures {

	/**
	 * A testable version of WeightedSumComputation that allows controlling isolation.
	 */
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
	 * Test standalone WeightedSumComputation with various group sizes.
	 * This simulates a dot product / reduction over groupSize elements.
	 */
	@Test
	public void testStandaloneGroupSizes() {
		System.out.println("=== Standalone WeightedSumComputation: Group Size vs Compilation Time ===\n");

		int[] groupSizes = {4, 8, 16, 32, 64, 128, 256};
		int outputSize = 16;

		System.out.println(String.format("%-12s %-15s %-15s %-10s", "GroupSize", "NoIsolation(ms)", "Isolated(ms)", "Speedup"));
		System.out.println("-".repeat(55));

		for (int groupSize : groupSizes) {
			long noIsolationTime = measureCompilation(outputSize, groupSize, false);
			long isolatedTime = measureCompilation(outputSize, groupSize, true);
			double speedup = (double) noIsolationTime / Math.max(isolatedTime, 1);

			System.out.println(String.format("%-12d %-15d %-15d %-10.2fx",
					groupSize, noIsolationTime, isolatedTime, speedup));

			// Stop if non-isolated takes too long
			if (noIsolationTime > 30000) {
				System.out.println("\nStopping - compilation time exceeds 30 seconds");
				break;
			}
		}

		System.out.println("\nConclusion: Find where speedup > 1.0 consistently");
	}

	/**
	 * Test WeightedSumComputation embedded in multiply operation.
	 */
	@Test
	public void testEmbeddedInMultiply() {
		System.out.println("=== WeightedSumComputation Embedded in Multiply ===\n");

		int[] groupSizes = {4, 8, 16, 32, 64, 128};
		int outputSize = 16;

		System.out.println(String.format("%-12s %-15s %-15s %-10s", "GroupSize", "NoIsolation(ms)", "Isolated(ms)", "Speedup"));
		System.out.println("-".repeat(55));

		for (int groupSize : groupSizes) {
			long noIsolationTime = measureEmbeddedMultiply(outputSize, groupSize, false);
			long isolatedTime = measureEmbeddedMultiply(outputSize, groupSize, true);
			double speedup = (double) noIsolationTime / Math.max(isolatedTime, 1);

			System.out.println(String.format("%-12d %-15d %-15d %-10.2fx",
					groupSize, noIsolationTime, isolatedTime, speedup));

			if (noIsolationTime > 30000) {
				System.out.println("\nStopping - compilation time exceeds 30 seconds");
				break;
			}
		}
	}

	/**
	 * Test WeightedSumComputation embedded in add operation.
	 */
	@Test
	public void testEmbeddedInAdd() {
		System.out.println("=== WeightedSumComputation Embedded in Add ===\n");

		int[] groupSizes = {4, 8, 16, 32, 64, 128};
		int outputSize = 16;

		System.out.println(String.format("%-12s %-15s %-15s %-10s", "GroupSize", "NoIsolation(ms)", "Isolated(ms)", "Speedup"));
		System.out.println("-".repeat(55));

		for (int groupSize : groupSizes) {
			long noIsolationTime = measureEmbeddedAdd(outputSize, groupSize, false);
			long isolatedTime = measureEmbeddedAdd(outputSize, groupSize, true);
			double speedup = (double) noIsolationTime / Math.max(isolatedTime, 1);

			System.out.println(String.format("%-12d %-15d %-15d %-10.2fx",
					groupSize, noIsolationTime, isolatedTime, speedup));

			if (noIsolationTime > 30000) {
				System.out.println("\nStopping - compilation time exceeds 30 seconds");
				break;
			}
		}
	}

	/**
	 * Fine-grained test around suspected threshold to find exact crossover.
	 */
	@Test
	public void testFineGrainedThreshold() {
		System.out.println("=== Fine-Grained Threshold Analysis ===\n");

		int[] groupSizes = {8, 12, 16, 20, 24, 28, 32, 40, 48, 56, 64};
		int outputSize = 16;

		System.out.println(String.format("%-12s %-15s %-15s %-10s %-15s",
				"GroupSize", "NoIsolation(ms)", "Isolated(ms)", "Speedup", "Recommendation"));
		System.out.println("-".repeat(70));

		for (int groupSize : groupSizes) {
			long noIsolationTime = measureCompilation(outputSize, groupSize, false);
			long isolatedTime = measureCompilation(outputSize, groupSize, true);
			double speedup = (double) noIsolationTime / Math.max(isolatedTime, 1);

			String recommendation;
			if (speedup > 1.5) {
				recommendation = "ISOLATE";
			} else if (speedup > 1.0) {
				recommendation = "marginal";
			} else {
				recommendation = "no isolation";
			}

			System.out.println(String.format("%-12d %-15d %-15d %-10.2fx %-15s",
					groupSize, noIsolationTime, isolatedTime, speedup, recommendation));

			if (noIsolationTime > 60000) {
				System.out.println("\nStopping - compilation time exceeds 60 seconds");
				break;
			}
		}

		System.out.println("\nThe optimal threshold is where 'ISOLATE' recommendations start consistently.");
	}

	/**
	 * Creates a weighted sum computation that sums over groupSize elements.
	 * Pattern based on sumColumn test - a reduction over the first dimension.
	 *
	 * <p>For each (1, i, j) in result, compute sum over r of input[r, i, 1] * weight[r, 1, j]</p>
	 */
	private long measureCompilation(int outputSize, int groupSize, boolean isolate) {
		int c1 = outputSize;
		int c2 = 1;  // Simplified to 1 for cleaner test
		int r = groupSize;  // This is what we're summing over

		// 3D shapes to match the sumColumn pattern
		TraversalPolicy inputShape = shape(r, c1, 1);
		TraversalPolicy weightShape = shape(r, 1, c2);
		TraversalPolicy resultShape = shape(1, c1, c2);

		// Position policies - expand to cover all input elements
		TraversalPolicy inputPositions = inputShape.repeat(2, c2);   // Repeat along dim 2
		TraversalPolicy weightPositions = weightShape.repeat(1, c1); // Repeat along dim 1

		// Group shape - dimension to reduce over (first dimension of size r)
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

		long start = System.currentTimeMillis();
		try {
			ops.get();
		} catch (Exception e) {
			System.err.println("Compilation failed for groupSize=" + groupSize + ", isolate=" + isolate + ": " + e.getMessage());
			return -1;
		}
		return System.currentTimeMillis() - start;
	}

	private long measureEmbeddedMultiply(int outputSize, int groupSize, boolean isolate) {
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
		PackedCollection multiplier = new PackedCollection(resultShape).randFill();

		TestableWeightedSumComputation computation = new TestableWeightedSumComputation(
				resultShape, inputPositions, weightPositions, groupShape, groupShape,
				cp(input), cp(weights), isolate);

		// Embed in multiply: weightedSum(...) * multiplier
		CollectionProducer embedded = c(traverseEach(computation)).multiply(cp(multiplier).traverseEach());

		OperationList ops = new OperationList();
		PackedCollection output = new PackedCollection(resultShape.traverseEach());
		ops.add(a("result", p(output), embedded));

		ops = (OperationList) ops.optimize();

		long start = System.currentTimeMillis();
		try {
			ops.get();
		} catch (Exception e) {
			System.err.println("Compilation failed for groupSize=" + groupSize + ", isolate=" + isolate + ": " + e.getMessage());
			return -1;
		}
		return System.currentTimeMillis() - start;
	}

	private long measureEmbeddedAdd(int outputSize, int groupSize, boolean isolate) {
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
		PackedCollection addend = new PackedCollection(resultShape).randFill();

		TestableWeightedSumComputation computation = new TestableWeightedSumComputation(
				resultShape, inputPositions, weightPositions, groupShape, groupShape,
				cp(input), cp(weights), isolate);

		// Embed in add: weightedSum(...) + addend
		CollectionProducer embedded = c(traverseEach(computation)).add(cp(addend).traverseEach());

		OperationList ops = new OperationList();
		PackedCollection output = new PackedCollection(resultShape.traverseEach());
		ops.add(a("result", p(output), embedded));

		ops = (OperationList) ops.optimize();

		long start = System.currentTimeMillis();
		try {
			ops.get();
		} catch (Exception e) {
			System.err.println("Compilation failed for groupSize=" + groupSize + ", isolate=" + isolate + ": " + e.getMessage());
			return -1;
		}
		return System.currentTimeMillis() - start;
	}
}
