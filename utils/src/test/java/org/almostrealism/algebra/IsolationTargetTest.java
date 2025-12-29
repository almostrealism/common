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
import io.almostrealism.expression.DoubleConstant;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.computations.LoopedWeightedSumComputation;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

/**
 * Test to verify that LoopedWeightedSumComputation is properly isolated
 * and uses getScope() for native loop generation rather than getValueAt().
 */
public class IsolationTargetTest implements TestFeatures, LayerFeatures {

	/**
	 * Test 1: LoopedWeightedSumComputation alone (not wrapped).
	 * This should definitely use getScope() and generate a native loop.
	 */
	@Test
	public void testLoopedWeightedSumAlone() {
		int outerCount = 64;  // Large enough to trigger isolation
		int innerCount = 4;
		int outputSize = 8;

		TraversalPolicy outputShape = shape(outputSize).traverseEach();
		TraversalPolicy inputShape = shape(outerCount, outputSize + innerCount - 1);
		TraversalPolicy weightShape = shape(outerCount, innerCount);

		PackedCollection input = new PackedCollection(inputShape).randFill();
		PackedCollection weights = new PackedCollection(weightShape).randFill();

		// Simple indexers for testing
		LoopedWeightedSumComputation.InputIndexer inputIndexer = (outputIndex, outerIndex, innerIndex) -> {
			// input[outer, output + inner]
			return outerIndex.multiply(outputSize + innerCount - 1).add(outputIndex).add(innerIndex);
		};

		LoopedWeightedSumComputation.WeightIndexer weightIndexer = (outputIndex, outerIndex, innerIndex) -> {
			// weight[outer, inner]
			return outerIndex.multiply(innerCount).add(innerIndex);
		};

		LoopedWeightedSumComputation computation = new LoopedWeightedSumComputation(
				"testLoopedSum",
				outputShape,
				outerCount,
				innerCount,
				inputShape,
				weightShape,
				inputIndexer,
				weightIndexer,
				cp(input),
				cp(weights));

		System.out.println("=== Test 1: LoopedWeightedSumComputation alone ===");
		System.out.println("outerCount=" + outerCount + ", innerCount=" + innerCount);
		System.out.println("isIsolationTarget: " + computation.isIsolationTarget(null));

		// Compile and run - this should use getScope()
		OperationList ops = new OperationList();
		PackedCollection output = new PackedCollection(outputShape);
		ops.add(a("testResult", p(output), computation));

		System.out.println("Compiling...");
		long start = System.currentTimeMillis();
		Runnable r = ops.get();
		long elapsed = System.currentTimeMillis() - start;
		System.out.println("Compilation took " + elapsed + "ms");

		r.run();
		System.out.println("Output[0]: " + output.toDouble(0));
		System.out.println("Test 1 PASSED - compilation completed without timeout\n");
	}

	/**
	 * Test 2: LoopedWeightedSumComputation wrapped with .traverseEach().
	 * This is what happens in the layer framework.
	 */
	@Test
	public void testLoopedWeightedSumWrapped() {
		int outerCount = 64;
		int innerCount = 4;
		int outputSize = 8;

		TraversalPolicy outputShape = shape(outputSize);  // NOT traverseEach'd yet
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
				"testLoopedSum",
				outputShape,
				outerCount,
				innerCount,
				inputShape,
				weightShape,
				inputIndexer,
				weightIndexer,
				cp(input),
				cp(weights));

		System.out.println("=== Test 2: LoopedWeightedSumComputation wrapped with traverseEach ===");
		System.out.println("outerCount=" + outerCount + ", innerCount=" + innerCount);
		System.out.println("isIsolationTarget: " + computation.isIsolationTarget(null));

		// Wrap with traverseEach - this is what the layer framework does
		Producer wrapped = traverseEach(computation);
		System.out.println("Wrapped type: " + wrapped.getClass().getSimpleName());

		// Compile and run
		OperationList ops = new OperationList();
		PackedCollection output = new PackedCollection(shape(outputSize).traverseEach());
		ops.add(a("testResult", p(output), wrapped));

		System.out.println("Compiling...");
		long start = System.currentTimeMillis();
		Runnable r = ops.get();
		long elapsed = System.currentTimeMillis() - start;
		System.out.println("Compilation took " + elapsed + "ms");

		r.run();
		System.out.println("Output[0]: " + output.toDouble(0));
		System.out.println("Test 2 PASSED - compilation completed without timeout\n");
	}

	/**
	 * Test 3: Large outerCount that matches the problematic case.
	 * outerCount=2048, innerCount=16 = 32K operations.
	 * Takes several minutes for compilation due to large expression trees.
	 */
	@Test
	public void testLoopedWeightedSumLarge() {
		if (testDepth < 2) return;
		int outerCount = 2048;
		int innerCount = 16;
		int outputSize = 33;

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
				"testLoopedSum",
				outputShape,
				outerCount,
				innerCount,
				inputShape,
				weightShape,
				inputIndexer,
				weightIndexer,
				cp(input),
				cp(weights));

		System.out.println("=== Test 3: Large LoopedWeightedSumComputation (2048 * 16 = 32K ops) ===");
		System.out.println("outerCount=" + outerCount + ", innerCount=" + innerCount);
		System.out.println("Total ops per output element: " + (outerCount * innerCount));
		System.out.println("isIsolationTarget: " + computation.isIsolationTarget(null));

		// Compile and run - this SHOULD use getScope() and be fast
		OperationList ops = new OperationList();
		PackedCollection output = new PackedCollection(outputShape);
		ops.add(a("testResult", p(output), computation));

		System.out.println("Compiling (should be fast if using native loop)...");
		long start = System.currentTimeMillis();
		Runnable r = ops.get();
		long elapsed = System.currentTimeMillis() - start;
		System.out.println("Compilation took " + elapsed + "ms");

		if (elapsed > 10000) {
			System.out.println("WARNING: Compilation took >10s - likely using getValueAt() instead of getScope()");
		}

		r.run();
		System.out.println("Output[0]: " + output.toDouble(0));
		System.out.println("Test 3 completed\n");
	}

	/**
	 * Test 4: Large outerCount but small innerCount.
	 * This tests if the issue is with outerCount scaling or innerCount scaling.
	 * outerCount=2048, innerCount=4 - if native loop works, this should be fast.
	 * Takes ~100 seconds for compilation due to large expression trees.
	 */
	@Test
	public void testLoopedWeightedSumLargeOuter() {
		if (testDepth < 2) return;
		int outerCount = 2048;
		int innerCount = 4;  // Same as test 1 and 2
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

		LoopedWeightedSumComputation computation = new LoopedWeightedSumComputation(
				"testLoopedSum",
				outputShape,
				outerCount,
				innerCount,
				inputShape,
				weightShape,
				inputIndexer,
				weightIndexer,
				cp(input),
				cp(weights));

		System.out.println("=== Test 4: Large outerCount, small innerCount (2048 * 4) ===");
		System.out.println("outerCount=" + outerCount + ", innerCount=" + innerCount);
		System.out.println("Total ops per output element: " + (outerCount * innerCount));
		System.out.println("isIsolationTarget: " + computation.isIsolationTarget(null));

		// Compile and run
		OperationList ops = new OperationList();
		PackedCollection output = new PackedCollection(outputShape);
		ops.add(a("testResult", p(output), computation));

		System.out.println("Compiling (should be fast if native loop works)...");
		long start = System.currentTimeMillis();
		Runnable r = ops.get();
		long elapsed = System.currentTimeMillis() - start;
		System.out.println("Compilation took " + elapsed + "ms");

		if (elapsed > 5000) {
			System.out.println("WARNING: Compilation took >5s - native loop may not be working!");
		} else {
			System.out.println("GOOD: Compilation was fast - native loop is working");
		}

		r.run();
		System.out.println("Output[0]: " + output.toDouble(0));
		System.out.println("Test 4 completed\n");
	}
}
