/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.collect.computations;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Test cases demonstrating usage patterns and behavior of {@link DynamicCollectionProducer}.
 * These tests validate the various constructor patterns, execution modes (kernel vs function),
 * and integration with input producers as documented in the class javadoc.
 *
 * @author Michael Murray
 */
public class DynamicCollectionProducerTest extends TestSuiteBase {
	/**
	 * Tests the simplest constructor that creates a collection with a static function.
	 * This validates the basic usage pattern shown in the class documentation.
	 */
	@Test(timeout = 10000)
	public void simpleStaticFunction() {
		TraversalPolicy shape = new TraversalPolicy(2, 3);

		// Create a producer that generates a collection filled with sequential values
		DynamicCollectionProducer producer =
				new DynamicCollectionProducer(shape, args -> {
					PackedCollection result = new PackedCollection(shape);
					for (int i = 0; i < result.getMemLength(); i++) {
						result.setMem(i, i + 1.0);
					}
					return result;
				});

		// Test basic properties
		assertEquals(shape, producer.getShape());
		assertEquals(6, producer.getOutputSize()); // 2 * 3 = 6
		assertTrue(producer.isFixedCount());

		// Test evaluation
		PackedCollection result = producer.get().evaluate();
		assertNotNull(result);
		assertEquals(shape, result.getShape());
		assertEquals(6, result.getMemLength());

		// Verify the sequential values
		for (int i = 0; i < 6; i++) {
			assertEquals(i + 1, result.toDouble(i));
		}
	}

	/**
	 * Tests the kernel vs non-kernel behavior by creating producers with different modes.
	 * This validates that the kernel parameter affects the execution path as documented.
	 */
	@Test(timeout = 10000)
	public void kernelVsNonKernelMode() {
		TraversalPolicy shape = new TraversalPolicy(2, 2);

		// Create function-mode producer (kernel=false)
		DynamicCollectionProducer functionProducer =
				new DynamicCollectionProducer(shape, args -> pack(shape, 1.0, 2.0, 3.0, 4.0), false);

		// Create kernel-mode producer (kernel=true) 
		DynamicCollectionProducer kernelProducer =
				new DynamicCollectionProducer(shape, args -> pack(shape, 1.0, 2.0, 3.0, 4.0), true);

		// Both should have the same basic properties
		assertEquals(shape, functionProducer.getShape());
		assertEquals(shape, kernelProducer.getShape());
		assertEquals(4, functionProducer.getOutputSize());
		assertEquals(4, kernelProducer.getOutputSize());

		// Both should be able to evaluate (though through different execution paths)
		PackedCollection functionResult = functionProducer.get().evaluate();
		PackedCollection kernelResult = kernelProducer.get().evaluate();

		assertNotNull(functionResult);
		assertNotNull(kernelResult);
		assertEquals(4, functionResult.getMemLength());
		assertEquals(4, kernelResult.getMemLength());
	}

	/**
	 * Tests the fixedCount parameter behavior.
	 * This validates that the fixedCount setting correctly affects the isFixedCount() method.
	 */
	@Test(timeout = 10000)
	public void fixedCountBehavior() {
		TraversalPolicy shape = new TraversalPolicy(3);

		// Create producer with fixed count
		DynamicCollectionProducer fixedProducer =
				new DynamicCollectionProducer(shape, args -> new PackedCollection(shape), false, true);

		// Create producer with non-fixed count
		DynamicCollectionProducer nonFixedProducer =
				new DynamicCollectionProducer(shape, args -> new PackedCollection(shape), false, false);

		assertTrue(fixedProducer.isFixedCount());
		assertFalse(nonFixedProducer.isFixedCount());
	}

	/**
	 * Tests reshape and traverse operations.
	 * This validates that the documented reshape and traverse methods work correctly.
	 */
	@Test(timeout = 10000)
	public void reshapeAndTraverse() {
		TraversalPolicy originalShape = new TraversalPolicy(2, 3);

		DynamicCollectionProducer producer =
				new DynamicCollectionProducer(originalShape, args -> new PackedCollection(originalShape));

		// Test reshape
		TraversalPolicy newShape = new TraversalPolicy(3, 2);
		CollectionProducer reshapedProducer = producer.reshape(newShape);
		assertNotNull(reshapedProducer);

		// Test traverse
		CollectionProducer traversedProducer = producer.traverse(0);
		assertNotNull(traversedProducer);

		// Original producer should be unchanged
		assertEquals(originalShape, producer.getShape());
	}

	/**
	 * Tests the producer-based input constructor.
	 * This validates the complex usage pattern with input dependencies as documented.
	 */
	@Test(timeout = 10000)
	public void inputBasedFunction() {
		TraversalPolicy shape = new TraversalPolicy(2);

		// Create a simple input producer that produces a constant collection
		Producer<PackedCollection> inputProducer = new DynamicCollectionProducer(
				shape, args -> pack(shape, 5.0, 10.0));

		// Create a producer that depends on the input producer
		// The function receives the input collections and creates a function that processes them
		DynamicCollectionProducer dependentProducer =
				new DynamicCollectionProducer(shape,
						inputs -> args -> {
							// inputs[0] is the result of evaluating inputProducer
							PackedCollection input = inputs[0];
							PackedCollection result = new PackedCollection(shape);
							// Double each value from the input
							for (int i = 0; i < input.getMemLength(); i++) {
								result.setMem(i, input.toDouble(i) * 2.0);
							}
							return result;
						},
						false, true, inputProducer);

		// Test evaluation
		PackedCollection result = dependentProducer.get().evaluate();
		assertNotNull(result);
		assertEquals(2, result.getMemLength());

		// Should contain doubled values: 5*2=10, 10*2=20
		double[] values = result.toArray();
		assertEquals(10.0, values[0]);
		assertEquals(20.0, values[1]);
	}
}