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

package org.almostrealism.geometry.test;

import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.geometry.Ray;
import org.almostrealism.util.TestFeatures;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests to verify batch processing behavior of ray operations like origin() and direction().
 *
 * This test investigates whether origin(rays) and direction(rays) preserve batch dimensions
 * when rays has shape (N, 6), or if they collapse to shape (3).
 */
public class RayBatchTest implements TestFeatures {

	@Test
	public void testOriginShapeWithBatch() {
		System.out.println("=== Testing origin() shape with batch ===");

		// Create a batch of 3 rays using variable-count shape
		Producer<Ray> rays = v(shape(-1, 6), 0);

		// Extract origin
		Producer<Vector> origins = origin(rays);

		// Create test data: 3 rays with different origins
		PackedCollection<?> rayData = new PackedCollection<>(shape(3, 6));
		rayData.setMem(0, 1, 2, 3, 0, 0, -1);     // Ray 0: origin (1, 2, 3)
		rayData.setMem(6, 4, 5, 6, 0, 0, -1);     // Ray 1: origin (4, 5, 6)
		rayData.setMem(12, 7, 8, 9, 0, 0, -1);    // Ray 2: origin (7, 8, 9)

		// Evaluate origins
		PackedCollection<?> originResults = origins.get().evaluate(rayData);

		System.out.println("Origin results shape: " + originResults.getShape());
		System.out.println("  Dimensions: " + originResults.getShape().getDimensions());
		System.out.println("  Total size: " + originResults.getShape().getTotalSize());
		System.out.println("  Count: " + originResults.getCount());

		System.out.println("Origin values:");
		for (int i = 0; i < Math.min(originResults.getMemLength(), 15); i++) {
			System.out.println("  [" + i + "] = " + originResults.toDouble(i));
		}

		// Expected: If batch-aware, should be (3, 3) = 9 elements
		// If NOT batch-aware, will be (3) = 3 elements
		System.out.println("\nExpected: (3, 3) shape with 9 elements if batch-aware");
		System.out.println("Got: " + originResults.getShape() + " with " + originResults.getMemLength() + " elements");
	}

	@Test
	public void testDirectionShapeWithBatch() {
		System.out.println("\n=== Testing direction() shape with batch ===");

		// Create a batch of 3 rays using variable-count shape
		Producer<Ray> rays = v(shape(-1, 6), 0);

		// Extract direction
		Producer<Vector> directions = direction(rays);

		// Create test data: 3 rays with different directions
		PackedCollection<?> rayData = new PackedCollection<>(shape(3, 6));
		rayData.setMem(0, 0, 0, 0, 1, 0, 0);      // Ray 0: direction (1, 0, 0)
		rayData.setMem(6, 0, 0, 0, 0, 1, 0);      // Ray 1: direction (0, 1, 0)
		rayData.setMem(12, 0, 0, 0, 0, 0, 1);     // Ray 2: direction (0, 0, 1)

		// Evaluate directions
		PackedCollection<?> directionResults = directions.get().evaluate(rayData);

		System.out.println("Direction results shape: " + directionResults.getShape());
		System.out.println("  Dimensions: " + directionResults.getShape().getDimensions());
		System.out.println("  Total size: " + directionResults.getShape().getTotalSize());
		System.out.println("  Count: " + directionResults.getCount());

		System.out.println("Direction values:");
		for (int i = 0; i < Math.min(directionResults.getMemLength(), 15); i++) {
			System.out.println("  [" + i + "] = " + directionResults.toDouble(i));
		}

		System.out.println("\nExpected: (3, 3) shape with 9 elements if batch-aware");
		System.out.println("Got: " + directionResults.getShape() + " with " + directionResults.getMemLength() + " elements");
	}

	@Test
	public void testMultiplyOriginDirection() {
		System.out.println("\n=== Testing origin(rays).multiply(direction(rays)) ===");

		// Create a batch of 3 rays
		Producer<Ray> rays = v(shape(-1, 6), 0);

		// Compute origin * direction element-wise
		Producer<?> product = multiply(origin(rays), direction(rays));

		// Create test data
		PackedCollection<?> rayData = new PackedCollection<>(shape(3, 6));
		rayData.setMem(0, 1, 2, 3, 2, 3, 4);      // Ray 0: (1,2,3) * (2,3,4) = (2,6,12)
		rayData.setMem(6, 1, 1, 1, 1, 1, 1);      // Ray 1: (1,1,1) * (1,1,1) = (1,1,1)
		rayData.setMem(12, 0, 0, 0, 5, 5, 5);     // Ray 2: (0,0,0) * (5,5,5) = (0,0,0)

		// Evaluate
		PackedCollection<?> productResults = ((Evaluable<PackedCollection<?>>) product.get()).evaluate(rayData);

		System.out.println("Product results shape: " + productResults.getShape());
		System.out.println("Product values:");
		for (int i = 0; i < Math.min(productResults.getMemLength(), 15); i++) {
			System.out.println("  [" + i + "] = " + productResults.toDouble(i));
		}
	}

	@Test
	public void testDotProductWithBatch() {
		System.out.println("\n=== Testing dotProduct(origin(rays), direction(rays)) ===");

		// Create a batch of 3 rays
		Producer<Ray> rays = v(shape(-1, 6), 0);

		// Compute dot product
		Producer<?> dotProd = dotProduct(origin(rays), direction(rays));

		// Create test data
		PackedCollection<?> rayData = new PackedCollection<>(shape(3, 6));
		rayData.setMem(0, 0, 0, 3, 0, 0, -1);     // Ray 0: (0,0,3) dot (0,0,-1) = -3
		rayData.setMem(6, 1, 0, 0, 1, 0, 0);      // Ray 1: (1,0,0) dot (1,0,0) = 1
		rayData.setMem(12, 1, 2, 3, 2, 3, 4);     // Ray 2: (1,2,3) dot (2,3,4) = 2+6+12 = 20

		// Evaluate
		PackedCollection<?> dotResults = ((Evaluable<PackedCollection<?>>) dotProd.get()).evaluate(rayData);

		System.out.println("Dot product results shape: " + dotResults.getShape());
		System.out.println("  Count: " + dotResults.getCount());
		System.out.println("  MemLength: " + dotResults.getMemLength());

		System.out.println("Dot product values:");
		for (int i = 0; i < Math.min(dotResults.getMemLength(), 10); i++) {
			System.out.println("  [" + i + "] = " + dotResults.toDouble(i));
		}

		System.out.println("\nExpected if batch-aware:");
		System.out.println("  Ray 0: -3.0");
		System.out.println("  Ray 1: 1.0");
		System.out.println("  Ray 2: 20.0");
	}

	@Test
	public void testODotDWithInto() {
		System.out.println("\n=== Testing oDotd() with into() for proper batch evaluation ===");

		// Create a batch of 3 rays
		Producer<Ray> rays = v(shape(-1, 6), 0);

		// Create test data with traverse(1) for batch processing
		PackedCollection<?> rayData = new PackedCollection<>(shape(3, 6).traverse(1));
		rayData.setMem(0, 0, 0, 3, 0, 0, -1);     // Ray 0: (0,0,3) dot (0,0,-1) = -3
		rayData.setMem(6, 1, 0, 0, 1, 0, 0);      // Ray 1: (1,0,0) dot (1,0,0) = 1
		rayData.setMem(12, 1, 2, 3, 2, 3, 4);     // Ray 2: (1,2,3) dot (2,3,4) = 20

		// Create destination with traverse(1) for batch output
		PackedCollection<?> destination = new PackedCollection<>(shape(3, 1).traverse(1));

		// Evaluate using into() with .each() for batch evaluation
		Evaluable<?> ev = oDotd(rays).get();
		ev.into(destination.each()).evaluate(rayData);

		System.out.println("Destination shape: " + destination.getShape());
		System.out.println("Results:");
		System.out.println("  Ray 0: " + destination.valueAt(0, 0) + " (expected -3.0)");
		System.out.println("  Ray 1: " + destination.valueAt(1, 0) + " (expected 1.0)");
		System.out.println("  Ray 2: " + destination.valueAt(2, 0) + " (expected 20.0)");

		// Assertions
		Assert.assertEquals(-3.0, destination.valueAt(0, 0), 0.01);
		Assert.assertEquals(1.0, destination.valueAt(1, 0), 0.01);
		Assert.assertEquals(20.0, destination.valueAt(2, 0), 0.01);
	}
}
