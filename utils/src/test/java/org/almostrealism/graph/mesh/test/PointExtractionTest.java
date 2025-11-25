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

package org.almostrealism.graph.mesh.test;

import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

public class PointExtractionTest implements TestFeatures {

	@Test
	public void batchPointExtraction() {
		// Create test data: 2 triangles with distinct vertices
		PackedCollection<PackedCollection<Vector>> points = Vector.table(3, 2);

		// Triangle 0: vertices at (1,0,0), (0,1,0), (0,0,1)
		points.get(0).set(0, new Vector(1, 0, 0));
		points.get(0).set(1, new Vector(0, 1, 0));
		points.get(0).set(2, new Vector(0, 0, 1));

		// Triangle 1: vertices at (2,0,0), (0,2,0), (0,0,2)
		points.get(1).set(0, new Vector(2, 0, 0));
		points.get(1).set(1, new Vector(0, 2, 0));
		points.get(1).set(2, new Vector(0, 0, 2));

		System.out.println("Input shape: " + points.getShape());
		System.out.println("Triangle 0, vertex 0: " + points.get(0).get(0));
		System.out.println("Triangle 1, vertex 0: " + points.get(1).get(0));

		// Flatten and reshape for batch processing
		PackedCollection<?> reshaped = points.traverse(0).reshape(shape(2, 3, 3));
		System.out.println("Reshaped shape: " + reshaped.getShape());
		System.out.println("Reshaped data (first 18 values): ");
		for (int i = 0; i < 18; i++) {
			System.out.print(reshaped.toDouble(i) + " ");
			if ((i + 1) % 9 == 0) System.out.println();
		}

		// Extract vertex 0 from both triangles
		PackedCollection<?> output = new PackedCollection<>(shape(2, 3));
		point(c(p(reshaped)), 0).get().into(output.traverse(1)).evaluate();

		System.out.println("\nExtracted vertex 0 from both triangles:");
		System.out.println("Triangle 0, vertex 0: [" + output.toDouble(0) + ", " + output.toDouble(1) + ", " + output.toDouble(2) + "]");
		System.out.println("Triangle 1, vertex 0: [" + output.toDouble(3) + ", " + output.toDouble(4) + ", " + output.toDouble(5) + "]");

		// Verify triangle 0, vertex 0 = (1, 0, 0)
		assertEquals(1.0, output.toDouble(0));
		assertEquals(0.0, output.toDouble(1));
		assertEquals(0.0, output.toDouble(2));

		// Verify triangle 1, vertex 0 = (2, 0, 0)
		assertEquals(2.0, output.toDouble(3));
		assertEquals(0.0, output.toDouble(4));
		assertEquals(0.0, output.toDouble(5));
	}

	@Test
	public void batchTriangleComputation() {
		// Create test data: 2 triangles with different orientations
		PackedCollection<PackedCollection<Vector>> points = Vector.table(3, 2);

		// Triangle 0: flat on XY plane, normal = [0, 0, 1]
		// vertices: (0,0,0), (1,0,0), (0,1,0)
		points.get(0).set(0, new Vector(0, 0, 0));
		points.get(0).set(1, new Vector(1, 0, 0));
		points.get(0).set(2, new Vector(0, 1, 0));

		// Triangle 1: tilted, should have different normal
		// vertices: (0,0,0), (1,0,0), (0,0,1)
		points.get(1).set(0, new Vector(0, 0, 0));
		points.get(1).set(1, new Vector(1, 0, 0));
		points.get(1).set(2, new Vector(0, 0, 1));

		System.out.println("\n=== Batch Triangle Computation ===");
		System.out.println("Input shape: " + points.getShape());

		// Flatten and reshape for batch processing
		PackedCollection<?> reshaped = points.traverse(0).reshape(shape(2, 3, 3));
		System.out.println("Reshaped shape: " + reshaped.getShape());
		System.out.println("Input data (first 18 values):");
		for (int i = 0; i < 18; i++) {
			System.out.print(reshaped.toDouble(i) + " ");
			if ((i + 1) % 9 == 0) System.out.println();
		}

		// Debug: Extract vertices to see if batch extraction works
		PackedCollection<?> v0 = new PackedCollection<>(shape(2, 3));
		PackedCollection<?> v1 = new PackedCollection<>(shape(2, 3));
		PackedCollection<?> v2 = new PackedCollection<>(shape(2, 3));
		point(c(p(reshaped)), 0).get().into(v0.traverse(1)).evaluate();
		point(c(p(reshaped)), 1).get().into(v1.traverse(1)).evaluate();
		point(c(p(reshaped)), 2).get().into(v2.traverse(1)).evaluate();

		System.out.println("\nExtracted vertices:");
		System.out.println("Triangle 0: v0=[" + v0.toDouble(0) + "," + v0.toDouble(1) + "," + v0.toDouble(2) + "]");
		System.out.println("Triangle 0: v1=[" + v1.toDouble(0) + "," + v1.toDouble(1) + "," + v1.toDouble(2) + "]");
		System.out.println("Triangle 0: v2=[" + v2.toDouble(0) + "," + v2.toDouble(1) + "," + v2.toDouble(2) + "]");
		System.out.println("Triangle 1: v0=[" + v0.toDouble(3) + "," + v0.toDouble(4) + "," + v0.toDouble(5) + "]");
		System.out.println("Triangle 1: v1=[" + v1.toDouble(3) + "," + v1.toDouble(4) + "," + v1.toDouble(5) + "]");
		System.out.println("Triangle 1: v2=[" + v2.toDouble(3) + "," + v2.toDouble(4) + "," + v2.toDouble(5) + "]");

		// Compute triangle data using triangle() method
		org.almostrealism.space.MeshData output = new org.almostrealism.space.MeshData(2);
		triangle(c(p(reshaped))).get().into(output.traverse(1)).evaluate();

		System.out.println("\nTriangle 0 normal: [" +
			output.get(0).get(3).toDouble(0) + ", " +
			output.get(0).get(3).toDouble(1) + ", " +
			output.get(0).get(3).toDouble(2) + "]");
		System.out.println("Triangle 1 normal: [" +
			output.get(1).get(3).toDouble(0) + ", " +
			output.get(1).get(3).toDouble(1) + ", " +
			output.get(1).get(3).toDouble(2) + "]");

		// Triangle 0 should have normal [0, 0, 1]
		assertEquals(0.0, output.get(0).get(3).toDouble(0));
		assertEquals(0.0, output.get(0).get(3).toDouble(1));
		assertEquals(1.0, output.get(0).get(3).toDouble(2));

		// Triangle 1 should have normal [0, -1, 0] (perpendicular to XZ plane)
		assertEquals(0.0, output.get(1).get(3).toDouble(0));
		assertEquals(-1.0, output.get(1).get(3).toDouble(1));
		assertEquals(0.0, output.get(1).get(3).toDouble(2));
	}
}
