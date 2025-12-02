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

package org.almostrealism.layers.test;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.layers.Layer;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.util.TestFeatures;
import org.junit.Assert;
import org.junit.Test;

/**
 * Unit tests for shape validation infrastructure in LayerFeatures.
 * These tests validate the behavior of {@link LayerFeatures#isShapeCompatible(TraversalPolicy, TraversalPolicy)}
 * and {@link LayerFeatures#validateFactorShape(String, TraversalPolicy, TraversalPolicy, Factor)}.
 *
 * @author Michael Murray
 */
public class ShapeValidationTest implements LayerFeatures, TestFeatures {

	/**
	 * Test that identical shapes are compatible.
	 */
	@Test
	public void testIdenticalShapesAreCompatible() {
		TraversalPolicy shape1 = shape(2, 3, 4);
		TraversalPolicy shape2 = shape(2, 3, 4);

		Assert.assertTrue("Identical shapes should be compatible",
				isShapeCompatible(shape1, shape2));
	}

	/**
	 * Test that shapes with different dimensions are incompatible.
	 */
	@Test
	public void testDifferentDimensionsAreIncompatible() {
		TraversalPolicy shape1 = shape(2, 3);
		TraversalPolicy shape2 = shape(3, 2);

		// Same total size but different dimensions
		Assert.assertFalse("Transposed shapes should be incompatible",
				isShapeCompatible(shape1, shape2));
	}

	/**
	 * Test that shapes with different total sizes are incompatible.
	 */
	@Test
	public void testDifferentSizesAreIncompatible() {
		TraversalPolicy shape1 = shape(2, 3);
		TraversalPolicy shape2 = shape(2, 4);

		Assert.assertFalse("Shapes with different sizes should be incompatible",
				isShapeCompatible(shape1, shape2));
	}

	/**
	 * Test that shapes with different traversal axes but same dimensions are compatible.
	 */
	@Test
	public void testTraversalAxisIsIgnored() {
		TraversalPolicy shape1 = shape(2, 3, 4).traverse(1);
		TraversalPolicy shape2 = shape(2, 3, 4).traverse(2);

		Assert.assertTrue("Shapes differing only in traversal axis should be compatible",
				isShapeCompatible(shape1, shape2));
	}

	/**
	 * Test that validateFactorShape passes for a correct operator.
	 */
	@Test
	public void testValidateFactorShape_correctShape() {
		TraversalPolicy inputShape = shape(1, 4);
		TraversalPolicy outputShape = shape(1, 4);

		// Identity operator - output shape matches input
		Factor<PackedCollection> identity = input -> input;

		// Should not throw
		validateFactorShape("identity_test", inputShape, outputShape, identity);
	}

	/**
	 * Test that validateFactorShape throws for mismatched shapes.
	 */
	@Test
	public void testValidateFactorShape_mismatchedShape() {
		TraversalPolicy inputShape = shape(1, 4);
		TraversalPolicy outputShape = shape(1, 8);  // Wrong size

		// Identity operator - output shape won't match declared output
		Factor<PackedCollection> identity = input -> input;

		try {
			validateFactorShape("identity_test", inputShape, outputShape, identity);
			Assert.fail("Should have thrown IllegalArgumentException for mismatched shapes");
		} catch (IllegalArgumentException e) {
			Assert.assertTrue("Error message should mention the layer name",
					e.getMessage().contains("identity_test"));
			Assert.assertTrue("Error message should mention the actual shape",
					e.getMessage().contains("(1, 4)"));
			Assert.assertTrue("Error message should mention the expected shape",
					e.getMessage().contains("(1, 8)"));
		}
	}

	/**
	 * Test that validateFactorShape works with reshape operators.
	 */
	@Test
	public void testValidateFactorShape_withReshape() {
		TraversalPolicy inputShape = shape(2, 4);
		TraversalPolicy outputShape = shape(8);  // Flattened

		// Reshape operator
		Factor<PackedCollection> flatten = input -> reshape(outputShape, input);

		// Should not throw - shapes are compatible after reshape
		validateFactorShape("flatten_test", inputShape, outputShape, flatten);
	}

	/**
	 * Test strict mode flag with into() method.
	 * When strict mode is enabled, into() should throw for mismatched shapes.
	 */
	@Test
	public void testInto_strictMode() {
		// If strict mode is enabled, verify exception behavior
		Producer<PackedCollection> in = cp(new PackedCollection(shape(1, 4)));
		Producer<PackedCollection> out = cp(new PackedCollection(shape(4, 1)));  // Transposed

		try {
			into("test_strict", in, out, false);
			Assert.fail("Should have thrown for mismatched shapes in strict mode");
		} catch (IllegalArgumentException e) {
			// Expected
			Assert.assertTrue(e.getMessage().contains("Shape mismatch"));
		}
	}

	/**
	 * Test into() with matching shapes works in all modes.
	 */
	@Test
	public void testInto_matchingShapes() {
		Producer<PackedCollection> in = cp(new PackedCollection(shape(1, 4)));
		Producer<PackedCollection> out = cp(new PackedCollection(shape(1, 4)));

		// Should never throw for matching shapes
		into("test_match", in, out, false);
	}

	/**
	 * Test isShapeCompatible with 1D shapes.
	 */
	@Test
	public void testIsShapeCompatible_1D() {
		TraversalPolicy shape1 = shape(10);
		TraversalPolicy shape2 = shape(10);

		Assert.assertTrue("Same 1D shapes should be compatible",
				isShapeCompatible(shape1, shape2));
	}

	/**
	 * Test isShapeCompatible with different number of dimensions.
	 */
	@Test
	public void testIsShapeCompatible_differentDimensionCount() {
		TraversalPolicy shape1 = shape(2, 3);
		TraversalPolicy shape2 = shape(6);

		// Same total size, different dimension count
		Assert.assertFalse("Shapes with different dimension counts should be incompatible",
				isShapeCompatible(shape1, shape2));
	}
}
