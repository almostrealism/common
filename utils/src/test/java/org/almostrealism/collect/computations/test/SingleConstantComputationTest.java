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

package org.almostrealism.collect.computations.test;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.SingleConstantComputation;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

/**
 * Test cases demonstrating usage patterns and behavior of {@link SingleConstantComputation}.
 * These tests show how SingleConstantComputation creates collections filled with constant values
 * and how it behaves in various scenarios including reshaping and traversal operations.
 * 
 * @author Michael Murray
 */
public class SingleConstantComputationTest implements TestFeatures {

	/**
	 * Tests basic creation and evaluation of a SingleConstantComputation.
	 * Demonstrates how to create a computation that fills a collection with a constant value.
	 */
	@Test
	public void basicConstantCreation() {
		// Create a 2x3 matrix filled with the value 5.0
		TraversalPolicy shape = new TraversalPolicy(2, 3);
		SingleConstantComputation constant =
			new SingleConstantComputation(shape, 5.0);

		// Verify the constant value is stored correctly
		assertEquals(5.0, constant.getConstantValue());

		// Verify the shape is preserved
		assertEquals(shape.getTotalSize(), constant.getShape().getTotalSize());
		assertEquals(2, constant.getShape().getDimensions());
	}

	/**
	 * Tests the short-circuit evaluation functionality.
	 * Demonstrates how SingleConstantComputation can bypass the normal computation
	 * pipeline for efficiency by directly creating the result collection.
	 */
	@Test
	public void shortCircuitEvaluation() {
		TraversalPolicy shape = new TraversalPolicy(3);
		double constantValue = 7.5;
		SingleConstantComputation constant =
			new SingleConstantComputation(shape, constantValue);

		// Use short-circuit evaluation
		PackedCollection result = constant.getShortCircuit().evaluate();

		// Verify all elements have the constant value
		assertEquals(3, result.getMemLength());
		for (int i = 0; i < 3; i++) {
			assertEquals(constantValue, result.valueAt(i));
		}
	}

	/**
	 * Tests the optimization methods for algebraic operations.
	 * Demonstrates how SingleConstantComputation detects special values
	 * like zero and identity elements for optimization purposes.
	 */
	@Test
	public void optimizationMethods() {
		// Test zero detection
		SingleConstantComputation zero =
			new SingleConstantComputation(new TraversalPolicy(5), 0.0);
		assertTrue("Zero constant should be detected", zero.isZero());

		// Test non-zero
		SingleConstantComputation nonZero =
			new SingleConstantComputation(new TraversalPolicy(5), 3.14);
		assertFalse("Non-zero constant should not be detected as zero", nonZero.isZero());

		// Test identity detection (scalar 1.0)
		SingleConstantComputation identity =
			new SingleConstantComputation(new TraversalPolicy(1), 1.0);
		assertTrue("Scalar 1.0 should be detected as identity", identity.isIdentity(1));

		// Test non-identity cases
		SingleConstantComputation notIdentity1 =
			new SingleConstantComputation(new TraversalPolicy(1), 2.0);
		assertFalse("Scalar 2.0 should not be identity", notIdentity1.isIdentity(1));

		SingleConstantComputation notIdentity2 =
			new SingleConstantComputation(new TraversalPolicy(3), 1.0);
		assertFalse("Vector of 1.0s should not be scalar identity", notIdentity2.isIdentity(1));
	}

	/**
	 * Tests reshaping functionality while preserving the constant value.
	 * Demonstrates how SingleConstantComputation can change its output shape
	 * without affecting the constant value that fills the collection.
	 */
	@Test
	public void reshapeOperation() {
		double constantValue = 2.5;
		TraversalPolicy originalShape = new TraversalPolicy(2, 3); // 2x3 matrix
		SingleConstantComputation original =
			new SingleConstantComputation(originalShape, constantValue);

		// Reshape to a vector
		TraversalPolicy newShape = new TraversalPolicy(6); // 6-element vector
		SingleConstantComputation reshaped =
			(SingleConstantComputation) original.reshape(newShape);

		// Verify the constant value is preserved
		assertEquals(constantValue, reshaped.getConstantValue());

		// Verify the new shape
		assertEquals(6, reshaped.getShape().getTotalSize());
		assertEquals(1, reshaped.getShape().getDimensions());
	}

	/**
	 * Tests traversal operations that transform the shape along specific axes.
	 * Demonstrates how SingleConstantComputation handles axis-based transformations
	 * while maintaining the constant value semantics.
	 */
	@Test
	public void traverseOperation() {
		double constantValue = -1.5;
		TraversalPolicy originalShape = new TraversalPolicy(3, 4); // 3x4 matrix
		SingleConstantComputation original =
			new SingleConstantComputation(originalShape, constantValue);

		// Traverse along axis 1
		SingleConstantComputation traversed =
			(SingleConstantComputation) original.traverse(1);

		// Verify the constant value is preserved
		assertEquals(constantValue, traversed.getConstantValue());

		// The shape should be transformed according to traversal policy
		assertNotEquals(originalShape.getSize(), traversed.getShape().getSize());
	}

	/**
	 * Tests the string description functionality.
	 * Demonstrates how SingleConstantComputation provides a readable
	 * representation of the constant value.
	 */
	@Test
	public void description() {
		SingleConstantComputation constant =
			new SingleConstantComputation(new TraversalPolicy(2), 3.14159);

		String description = constant.description();
		assertNotNull("Description should not be null", description);
		assertTrue("Description should contain the constant value",
			description.contains("3.14") || description.contains("pi"));
	}

	/**
	 * Tests constructor with custom name.
	 * Demonstrates the protected constructor that allows custom naming
	 * of SingleConstantComputation instances.
	 */
	@Test
	public void customNameConstructor() {
		String customName = "myConstant";
		TraversalPolicy shape = new TraversalPolicy(2, 2);
		double value = 42.0;

		// Create a test subclass to access the protected constructor
		SingleConstantComputation constant =
			new SingleConstantComputation(customName, shape, value) {};

		assertEquals(value, constant.getConstantValue());
		assertEquals(shape.getTotalSize(), constant.getShape().getTotalSize());
	}
}