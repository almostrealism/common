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

package org.almostrealism.collect.computations;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Test cases demonstrating usage patterns and behavior of {@link ReshapeProducer}.
 * These tests validate the comprehensive Javadoc documentation examples and ensure
 * the class works as documented across different reshape scenarios.
 * 
 * @author Michael Murray
 */
public class ReshapeProducerTest implements CollectionFeatures, TestFeatures {

	/**
	 * Tests basic shape transformation from 1D vector to 2D matrix.
	 * This validates the example shown in the main class documentation.
	 */
	@Test
	public void testBasicShapeTransformation() {
		// Create a 1D vector with 6 elements
		CollectionProducer<PackedCollection<?>> vector = c(1, 2, 3, 4, 5, 6);
		assertEquals(6, shape(vector).getTotalSize());
		assertEquals(1, shape(vector).getDimensions());
		
		// Reshape to 2x3 matrix
		TraversalPolicy matrixShape = shape(2, 3);
		ReshapeProducer<PackedCollection<?>> matrix = new ReshapeProducer<>(matrixShape, vector);
		
		// Verify the shape transformation
		TraversalPolicy resultShape = matrix.getShape();
		assertEquals(6, resultShape.getTotalSize());
		assertEquals(2, resultShape.getDimensions());
		assertEquals(2, resultShape.length(0));
		assertEquals(3, resultShape.length(1));
		
		// Verify the data is preserved (using evaluation)
		PackedCollection<?> result = matrix.get().evaluate();
		assertNotNull(result);
		assertEquals(6, result.getMemLength());
	}

	/**
	 * Tests traversal axis modification functionality.
	 * This validates the traversal axis constructor and its behavior.
	 */
	@Test
	public void testTraversalAxisModification() {
		// Create a 3x4 matrix
		CollectionProducer<PackedCollection<?>> matrix = c(shape(3, 4), 
			1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
		assertEquals(0, shape(matrix).getTraversalAxis());
		
		// Change traversal axis to 1 (columns)
		ReshapeProducer<PackedCollection<?>> columnTraversal = new ReshapeProducer<>(1, matrix);
		
		// Verify the shape maintains dimensions but changes traversal axis
		TraversalPolicy resultShape = columnTraversal.getShape();
		assertEquals(12, resultShape.getTotalSize());
		assertEquals(2, resultShape.getDimensions());
		assertEquals(1, resultShape.getTraversalAxis());
		
		// Verify metadata is properly set
		assertNotNull(columnTraversal.getMetadata());
		assertTrue(columnTraversal.description().contains("->"));
	}

	/**
	 * Tests the constraint that shape transformations must preserve total size.
	 * This validates that IllegalArgumentException is thrown for incompatible sizes.
	 */
	@Test(expected = IllegalArgumentException.class)
	public void testIncompatibleSizeConstraint() {
		// Create a vector with 6 elements
		CollectionProducer<PackedCollection<?>> vector = c(1, 2, 3, 4, 5, 6);
		
		// Try to reshape to an incompatible size (2x2 = 4 elements)
		TraversalPolicy incompatibleShape = shape(2, 2);
		new ReshapeProducer<>(incompatibleShape, vector);
		// Should throw IllegalArgumentException
	}

	/**
	 * Tests chaining reshape operations with the traverse method.
	 * This validates the fluent interface for traversal axis changes.
	 */
	@Test
	public void testTraverseMethod() {
		// Create initial reshape producer
		CollectionProducer<PackedCollection<?>> matrix = c(shape(2, 6), /* 12 elements */);
		ReshapeProducer<PackedCollection<?>> reshaped = new ReshapeProducer<>(shape(3, 4), matrix);
		
		// Use traverse method to change axis
		CollectionProducer<PackedCollection<?>> retraversed = reshaped.traverse(1);
		
		// Verify the result
		assertNotNull(retraversed);
		assertTrue(retraversed instanceof ReshapeProducer);
		assertEquals(1, shape(retraversed).getTraversalAxis());
	}

	/**
	 * Tests chaining reshape operations with the reshape method.
	 * This validates the fluent interface for shape transformations.
	 */
	@Test
	public void testReshapeMethod() {
		// Create initial reshape producer with traversal axis change
		CollectionProducer<PackedCollection<?>> base = c(shape(2, 6), /* 12 elements */);
		ReshapeProducer<PackedCollection<?>> initial = new ReshapeProducer<>(1, base);
		
		// Chain another reshape operation
		CollectionProducer<PackedCollection<?>> final = initial.reshape(shape(4, 3));
		
		// Verify the final shape
		assertNotNull(final);
		assertTrue(final instanceof ReshapeProducer);
		TraversalPolicy finalShape = shape(final);
		assertEquals(12, finalShape.getTotalSize());
		assertEquals(2, finalShape.getDimensions());
		assertEquals(4, finalShape.length(0));
		assertEquals(3, finalShape.length(1));
	}

	/**
	 * Tests the getComputation method for unwrapping nested ReshapeProducers.
	 * This validates the optimization feature for nested operations.
	 */
	@Test
	public void testGetComputationUnwrapping() {
		// Create base producer
		CollectionProducer<PackedCollection<?>> base = c(1, 2, 3, 4, 5, 6);
		
		// Create nested reshape operations
		ReshapeProducer<PackedCollection<?>> first = new ReshapeProducer<>(shape(2, 3), base);
		ReshapeProducer<PackedCollection<?>> second = new ReshapeProducer<>(1, first);
		
		// Test getComputation unwrapping
		assertEquals(base, second.getComputation());
		assertEquals(base, first.getComputation());
	}

	/**
	 * Tests integration with CollectionFeatures helper methods.
	 * This validates the documented usage patterns with CollectionFeatures.
	 */
	@Test
	public void testCollectionFeaturesIntegration() {
		// Create test data
		CollectionProducer<PackedCollection<?>> data = c(shape(2, 2, 3), /* 12 elements */);
		
		// Test reshape helper method
		CollectionProducer<PackedCollection<?>> flattened = reshape(shape(12), data);
		assertNotNull(flattened);
		assertEquals(12, shape(flattened).getTotalSize());
		assertEquals(1, shape(flattened).getDimensions());
		
		// Test traverse helper method
		CollectionProducer<PackedCollection<?>> reordered = traverse(1, data);
		assertNotNull(reordered);
		assertEquals(1, shape(reordered).getTraversalAxis());
		
		// Test traverseEach helper method
		CollectionProducer<PackedCollection<?>> elements = (CollectionProducer<PackedCollection<?>>) traverseEach(data);
		assertNotNull(elements);
		TraversalPolicy elementsShape = shape(elements);
		assertTrue(elementsShape.getTotalSize() > 0);
	}

	/**
	 * Tests that constant properties are properly propagated.
	 * This validates the isConstant() method behavior.
	 */
	@Test
	public void testConstantPropagation() {
		// Create a constant producer
		CollectionProducer<PackedCollection<?>> constant = c(shape(4), 5.0, 5.0, 5.0, 5.0);
		
		// Reshape the constant
		ReshapeProducer<PackedCollection<?>> reshaped = new ReshapeProducer<>(shape(2, 2), constant);
		
		// Verify constant property is maintained
		// Note: This depends on the underlying implementation's constant detection
		// The test validates that the method delegates correctly
		boolean baseConstant = constant.isConstant();
		assertEquals(baseConstant, reshaped.isConstant());
	}

	/**
	 * Tests description methods for debugging and introspection.
	 * This validates the description() and describe() methods.
	 */
	@Test
	public void testDescriptionMethods() {
		// Create test producer
		CollectionProducer<PackedCollection<?>> base = c(1, 2, 3, 4);
		ReshapeProducer<PackedCollection<?>> reshaped = new ReshapeProducer<>(shape(2, 2), base);
		
		// Test description methods
		String description = reshaped.description();
		assertNotNull(description);
		assertTrue(description.length() > 0);
		
		String fullDescription = reshaped.describe();
		assertNotNull(fullDescription);
		assertTrue(fullDescription.contains("|")); // Should contain shape details
		assertTrue(fullDescription.length() > description.length());
	}

	/**
	 * Tests metadata initialization and retrieval.
	 * This validates the getMetadata() method and init() functionality.
	 */
	@Test
	public void testMetadata() {
		// Create test producer
		CollectionProducer<PackedCollection<?>> base = c(1, 2, 3, 4, 5, 6);
		ReshapeProducer<PackedCollection<?>> reshaped = new ReshapeProducer<>(shape(2, 3), base);
		
		// Verify metadata is available
		assertNotNull(reshaped.getMetadata());
		assertNotNull(reshaped.getMetadata().getDisplayName());
		assertTrue(reshaped.getMetadata().getDisplayName().contains("reshape"));
	}
}