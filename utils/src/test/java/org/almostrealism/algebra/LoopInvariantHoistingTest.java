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
import io.almostrealism.scope.Repeated;
import org.almostrealism.algebra.computations.LoopedWeightedSumComputation;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for Loop-Invariant Code Motion (LICM) in {@link Repeated} scopes.
 *
 * <p>These tests verify that the LICM optimization in {@link Repeated#simplify}
 * correctly identifies and hoists loop-invariant expressions while preserving
 * correct behavior for loop-variant expressions. The tests specifically target
 * scenarios involving declaration dependency chains, where one declaration
 * references another that is itself loop-variant.</p>
 *
 * @see Repeated
 */
public class LoopInvariantHoistingTest extends TestSuiteBase {

	/**
	 * Verifies that LICM is enabled by default.
	 */
	@Test(timeout = 10000)
	public void licmIsEnabledByDefault() {
		Assert.assertTrue("LICM should be enabled by default",
				Repeated.enableLoopInvariantHoisting);
	}

	/**
	 * Tests that a LoopedWeightedSum computation produces correct results
	 * with LICM enabled. This computation internally creates a {@link Repeated}
	 * scope with both loop-invariant (weight loading) and loop-variant
	 * (accumulation) expressions.
	 *
	 * <p>The computation computes: output[k] = sum over i,j of input[f(k,i,j)] * weight[g(i,j)]
	 * The weight indexing expressions are loop-invariant with respect to the output index
	 * but the accumulation is loop-variant.</p>
	 */
	@Test(timeout = 60000)
	public void loopedSumWithLicm() {
		boolean previous = Repeated.enableLoopInvariantHoisting;
		Repeated.enableLoopInvariantHoisting = true;

		try {
			int outerCount = 4;
			int innerCount = 3;
			int outputSize = 2;

			TraversalPolicy outputShape = shape(outputSize).traverseEach();
			TraversalPolicy inputShape = shape(outerCount, outputSize + innerCount - 1);
			TraversalPolicy weightShape = shape(outerCount, innerCount);

			PackedCollection input = new PackedCollection(inputShape);
			PackedCollection weights = new PackedCollection(weightShape);

			// Set known values for deterministic verification
			for (int i = 0; i < inputShape.getTotalSize(); i++) {
				input.setMem(i, (i + 1) * 0.1);
			}
			for (int i = 0; i < weightShape.getTotalSize(); i++) {
				weights.setMem(i, (i + 1) * 0.01);
			}

			LoopedWeightedSumComputation.InputIndexer inputIndexer = (outputIndex, outerIndex, innerIndex) ->
					outerIndex.multiply(outputSize + innerCount - 1).add(outputIndex).add(innerIndex);

			LoopedWeightedSumComputation.WeightIndexer weightIndexer = (outputIndex, outerIndex, innerIndex) ->
					outerIndex.multiply(innerCount).add(innerIndex);

			LoopedWeightedSumComputation computation = new LoopedWeightedSumComputation(
					"licmTest",
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
			ops.add(a("licmResult", p(output), computation));

			Runnable r = ops.get();
			r.run();

			// Compute expected values manually
			double[] expected = new double[outputSize];
			for (int k = 0; k < outputSize; k++) {
				for (int i = 0; i < outerCount; i++) {
					for (int j = 0; j < innerCount; j++) {
						int inputIdx = i * (outputSize + innerCount - 1) + k + j;
						int weightIdx = i * innerCount + j;
						expected[k] += input.toDouble(inputIdx) * weights.toDouble(weightIdx);
					}
				}
			}

			for (int k = 0; k < outputSize; k++) {
				Assert.assertEquals("Output[" + k + "] mismatch with LICM enabled",
						expected[k], output.toDouble(k), 1e-6);
			}
		} finally {
			Repeated.enableLoopInvariantHoisting = previous;
		}
	}

	/**
	 * Verifies that LICM-enabled execution produces the same results as
	 * LICM-disabled execution. This is a differential test that catches
	 * cases where hoisting changes the output.
	 */
	@Test(timeout = 120000)
	@TestDepth(2)
	public void licmMatchesNonLicm() {
		int outerCount = 4;
		int innerCount = 3;
		int outputSize = 2;

		TraversalPolicy outputShape = shape(outputSize).traverseEach();
		TraversalPolicy inputShape = shape(outerCount, outputSize + innerCount - 1);
		TraversalPolicy weightShape = shape(outerCount, innerCount);

		PackedCollection input = new PackedCollection(inputShape).randFill();
		PackedCollection weights = new PackedCollection(weightShape).randFill();

		LoopedWeightedSumComputation.InputIndexer inputIndexer = (outputIndex, outerIndex, innerIndex) ->
				outerIndex.multiply(outputSize + innerCount - 1).add(outputIndex).add(innerIndex);

		LoopedWeightedSumComputation.WeightIndexer weightIndexer = (outputIndex, outerIndex, innerIndex) ->
				outerIndex.multiply(innerCount).add(innerIndex);

		// Run with LICM disabled
		double[] withoutLicm;
		{
			boolean previous = Repeated.enableLoopInvariantHoisting;
			Repeated.enableLoopInvariantHoisting = false;
			try {
				LoopedWeightedSumComputation computation = new LoopedWeightedSumComputation(
						"noLicm",
						outputShape, outerCount, innerCount,
						inputShape, weightShape,
						inputIndexer, weightIndexer,
						cp(input), cp(weights));

				OperationList ops = new OperationList();
				PackedCollection output = new PackedCollection(outputShape);
				ops.add(a("noLicmResult", p(output), computation));
				ops.get().run();

				withoutLicm = new double[outputSize];
				for (int k = 0; k < outputSize; k++) {
					withoutLicm[k] = output.toDouble(k);
				}
			} finally {
				Repeated.enableLoopInvariantHoisting = previous;
			}
		}

		// Run with LICM enabled
		double[] withLicm;
		{
			boolean previous = Repeated.enableLoopInvariantHoisting;
			Repeated.enableLoopInvariantHoisting = true;
			try {
				LoopedWeightedSumComputation computation = new LoopedWeightedSumComputation(
						"withLicm",
						outputShape, outerCount, innerCount,
						inputShape, weightShape,
						inputIndexer, weightIndexer,
						cp(input), cp(weights));

				OperationList ops = new OperationList();
				PackedCollection output = new PackedCollection(outputShape);
				ops.add(a("withLicmResult", p(output), computation));
				ops.get().run();

				withLicm = new double[outputSize];
				for (int k = 0; k < outputSize; k++) {
					withLicm[k] = output.toDouble(k);
				}
			} finally {
				Repeated.enableLoopInvariantHoisting = previous;
			}
		}

		// Compare
		for (int k = 0; k < outputSize; k++) {
			Assert.assertEquals(
					"Output[" + k + "] differs between LICM-enabled and LICM-disabled",
					withoutLicm[k], withLicm[k], 1e-6);
		}
	}
}
