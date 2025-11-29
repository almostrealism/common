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
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CollectionProducerComputation;
import org.almostrealism.collect.computations.CollectionZerosComputation;
import org.almostrealism.collect.computations.SingleConstantComputation;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

/**
 * Test cases demonstrating usage patterns and behavior of {@link CollectionZerosComputation}.
 * These tests show how CollectionZerosComputation creates collections filled with zero values
 * and how it behaves in various scenarios including reshaping, traversal, and optimization operations.
 * 
 * @author Michael Murray
 */
public class CollectionZerosComputationTest implements TestFeatures {

	/**
	 * Tests basic creation and evaluation of zero computations.
	 * Demonstrates how CollectionZerosComputation produces collections where
	 * every element is exactly 0.0.
	 */
	@Test
	public void basicZeroCreation() {
		// Test vector creation
		TraversalPolicy vectorShape = new TraversalPolicy(5);
		CollectionZerosComputation zeroVector =
				new CollectionZerosComputation(vectorShape);

		assertEquals("Vector should have correct size", 5, zeroVector.getShape().getSize());
		assertEquals("Vector should have 1 dimension", 1, zeroVector.getShape().getDimensions());
		assertTrue("Zero computation should be detected as zero", zeroVector.isZero());

		// Test matrix creation
		TraversalPolicy matrixShape = new TraversalPolicy(3, 4);
		CollectionZerosComputation zeroMatrix =
				new CollectionZerosComputation(matrixShape);

		assertEquals("Matrix should have correct size", 12, zeroMatrix.getShape().getSize());
		assertEquals("Matrix should have 2 dimensions", 2, zeroMatrix.getShape().getDimensions());
		assertTrue("Zero matrix should be detected as zero", zeroMatrix.isZero());
	}

	/**
	 * Tests the optimization methods for algebraic operations.
	 * Demonstrates how CollectionZerosComputation provides critical optimization
	 * information for mathematical operations.
	 */
	@Test
	public void optimizationMethods() {
		// Test zero detection
		CollectionZerosComputation zeros =
				new CollectionZerosComputation(new TraversalPolicy(10));
		assertTrue("Zero computation should always be detected as zero", zeros.isZero());

		// Test that zeros are never identity (identity would be 1.0, not 0.0)
		assertFalse("Zero should not be scalar identity", zeros.isIdentity(1));
		assertFalse("Zero should not be matrix identity", zeros.isIdentity(3));
	}

	/**
	 * Tests reshaping functionality while preserving the zero values.
	 * Demonstrates how CollectionZerosComputation can change its output shape
	 * without affecting the zero content that fills the collection.
	 */
	@Test
	public void reshapeOperation() {
		// Create initial zero vector
		TraversalPolicy originalShape = new TraversalPolicy(12);
		CollectionZerosComputation originalZeros =
				new CollectionZerosComputation(originalShape);

		// Reshape to matrix
		TraversalPolicy matrixShape = new TraversalPolicy(3, 4);
		CollectionProducerComputation reshapedZeros =
				originalZeros.reshape(matrixShape);

		assertTrue("Reshaped computation should still be zeros",
				reshapedZeros instanceof CollectionZerosComputation);
		assertTrue("Reshaped zeros should be detected as zero",
				((CollectionZerosComputation) reshapedZeros).isZero());
		assertEquals("Reshaped zeros should have new shape",
				matrixShape.getSize(), reshapedZeros.getShape().getSize());
		assertEquals("Reshaped zeros should have correct dimensions",
				2, reshapedZeros.getShape().getDimensions());
	}

	/**
	 * Tests traversal operations that transform the shape along specific axes.
	 * Demonstrates how CollectionZerosComputation handles axis-based transformations
	 * while maintaining the zero value semantics.
	 */
	@Test
	public void traverseOperation() {
		// Create 2D zero matrix
		TraversalPolicy matrixShape = new TraversalPolicy(3, 4);
		CollectionZerosComputation zeroMatrix =
				new CollectionZerosComputation(matrixShape);

		// Traverse along axis 1
		CollectionProducer traversedZeros = zeroMatrix.traverse(1);

		assertTrue("Traversed computation should still be zeros",
				traversedZeros instanceof CollectionZerosComputation);
		assertTrue("Traversed zeros should be detected as zero",
				((CollectionZerosComputation) traversedZeros).isZero());

		// The exact shape after traversal depends on the traversal policy implementation
		// but it should maintain the zero property
		assertNotNull("Traversed zeros should have a valid shape", traversedZeros.getShape());
	}

	/**
	 * Tests the delta/derivative computation functionality.
	 * Demonstrates how the derivative of zero is always zero, with expanded dimensions
	 * to account for the target variable's shape.
	 */
	@Test
	public void deltaComputation() {
		// Create zero vector
		TraversalPolicy zerosShape = new TraversalPolicy(3);
		CollectionZerosComputation zeros =
				new CollectionZerosComputation(zerosShape);

		// Create a mock target with known shape
		TraversalPolicy targetShape = new TraversalPolicy(2);
		Producer<?> mockTarget = new SingleConstantComputation(targetShape, 1.0);

		// Compute delta (derivative)
		CollectionProducer delta = zeros.delta(mockTarget);

		assertTrue("Delta of zeros should still be zeros", delta instanceof CollectionZerosComputation);
		assertTrue("Delta should be detected as zero",
				((CollectionZerosComputation) delta).isZero());

		// The delta should have expanded shape: original [3] + target [2] = [3,2]
		TraversalPolicy expectedShape = zerosShape.append(targetShape);
		assertEquals("Delta should have expanded shape",
				expectedShape.getSize(), delta.getShape().getSize());
	}

	/**
	 * Tests the parallel process generation functionality.
	 * Demonstrates how zero computations serve as their own parallel processes
	 * since they have no dependencies.
	 */
	@Test
	public void parallelProcessGeneration() {
		CollectionZerosComputation zeros =
				new CollectionZerosComputation(new TraversalPolicy(5));

		// Generate parallel process (should return self)
		var parallelProcess = zeros.generate(java.util.Collections.emptyList());

		assertSame("Zero computation should serve as its own parallel process",
				zeros, parallelProcess);
		assertTrue("Parallel process should still be zero",
				((CollectionZerosComputation) parallelProcess).isZero());
	}

	/**
	 * Tests the isolation operation behavior.
	 * Demonstrates that zero computations cannot be isolated as they are already
	 * optimally isolated by nature.
	 */
	@Test(expected = UnsupportedOperationException.class)
	public void isolationNotSupported() {
		CollectionZerosComputation zeros =
				new CollectionZerosComputation(new TraversalPolicy(3));

		// This should throw UnsupportedOperationException
		zeros.isolate();
	}

	/**
	 * Tests the name and description functionality.
	 * Demonstrates how zero computations are properly identified in
	 * computation graphs and debugging output.
	 */
	@Test
	public void nameAndDescription() {
		CollectionZerosComputation zeros =
				new CollectionZerosComputation(new TraversalPolicy(3));

		// The computation should be named "zeros"
		assertNotNull("Computation should have a name", zeros.getName());
		assertTrue("Name should indicate zero content",
				zeros.getName().toLowerCase().contains("zero"));
	}

	/**
	 * Tests edge cases and boundary conditions.
	 * Demonstrates behavior with special shapes and configurations.
	 */
	@Test
	public void edgeCases() {
		// Test scalar zeros (single element)
		TraversalPolicy scalarShape = new TraversalPolicy(1);
		CollectionZerosComputation scalarZeros =
				new CollectionZerosComputation(scalarShape);

		assertTrue("Scalar zeros should be detected as zero", scalarZeros.isZero());
		assertEquals("Scalar should have size 1", 1, scalarZeros.getShape().getSize());

		// Test high-dimensional zeros
		TraversalPolicy highDimShape = new TraversalPolicy(2, 3, 4, 5);
		CollectionZerosComputation highDimZeros =
				new CollectionZerosComputation(highDimShape);

		assertTrue("High-dimensional zeros should be detected as zero", highDimZeros.isZero());
		assertEquals("High-dimensional zeros should have correct size",
				120, highDimZeros.getShape().getSize());
		assertEquals("Should have 4 dimensions", 4, highDimZeros.getShape().getDimensions());
	}

	/**
	 * Tests mathematical properties and invariants.
	 * Demonstrates that zero computations maintain their mathematical properties
	 * through various transformations.
	 */
	@Test
	public void mathematicalProperties() {
		TraversalPolicy shape = new TraversalPolicy(4, 4);
		CollectionZerosComputation zeros =
				new CollectionZerosComputation(shape);

		// Zero property should be preserved through reshaping
		CollectionProducerComputation reshapedZeros =
				zeros.reshape(new TraversalPolicy(2, 8));
		assertTrue("Reshaped zeros should maintain zero property",
				((CollectionZerosComputation) reshapedZeros).isZero());

		// Zero property should be preserved through traversal
		CollectionProducer traversedZeros = zeros.traverse(1);
		assertTrue("Traversed zeros should maintain zero property",
				((CollectionZerosComputation) traversedZeros).isZero());

		// Delta of zeros should be zeros
		Producer<?> target = new SingleConstantComputation(new TraversalPolicy(2), 1.0);
		CollectionProducer deltaZeros = zeros.delta(target);
		assertTrue("Delta of zeros should be zero",
				((CollectionZerosComputation) deltaZeros).isZero());
	}

	private void assertSame(String msg, Object expected, Object actual) {
		if (expected != actual) {
			throw new AssertionError(msg + ": Expected same object instance");
		}
	}
}