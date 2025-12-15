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

/**
 * Focused tests for the TriangleFeatures::point method.
 *
 * The point method extracts a specific vertex (0, 1, or 2) from triangle vertex data.
 * Input format: (N, 3, 3) - N triangles, 3 vertices per triangle, 3 components per vertex.
 * Output format: (N, 3) - N vectors, 3 components per vector.
 */
public class PointMethodTest implements TestFeatures {

	/**
	 * Test extracting vertex 0 from a single triangle with shape (1, 3, 3).
	 */
	@Test(timeout = 10000)
	public void singleTriangleVertex0() {
		// Create a single triangle's vertex data: shape (1, 3, 3) = 9 floats
		PackedCollection input = new PackedCollection(shape(1, 3, 3));

		// Triangle vertices: v0=(1,2,3), v1=(4,5,6), v2=(7,8,9)
		input.setMem(0, 1.0); input.setMem(1, 2.0); input.setMem(2, 3.0);   // v0
		input.setMem(3, 4.0); input.setMem(4, 5.0); input.setMem(5, 6.0);   // v1
		input.setMem(6, 7.0); input.setMem(7, 8.0); input.setMem(8, 9.0);   // v2

		log("=== Single Triangle Vertex 0 ===");
		log("Input shape: " + input.getShape());

		PackedCollection output = new PackedCollection(shape(1, 3));
		point(c(p(input)), 0).get().into(output.traverse(1)).evaluate();

		log("Output: [" + output.toDouble(0) + ", " + output.toDouble(1) + ", " + output.toDouble(2) + "]");

		// Should extract v0 = (1, 2, 3)
		assertEquals(1.0, output.toDouble(0));
		assertEquals(2.0, output.toDouble(1));
		assertEquals(3.0, output.toDouble(2));
	}

	/**
	 * Test extracting vertex 1 from a single triangle with shape (1, 3, 3).
	 */
	@Test(timeout = 10000)
	public void singleTriangleVertex1() {
		PackedCollection input = new PackedCollection(shape(1, 3, 3));

		// Triangle vertices: v0=(1,2,3), v1=(4,5,6), v2=(7,8,9)
		input.setMem(0, 1.0); input.setMem(1, 2.0); input.setMem(2, 3.0);   // v0
		input.setMem(3, 4.0); input.setMem(4, 5.0); input.setMem(5, 6.0);   // v1
		input.setMem(6, 7.0); input.setMem(7, 8.0); input.setMem(8, 9.0);   // v2

		log("=== Single Triangle Vertex 1 ===");

		PackedCollection output = new PackedCollection(shape(1, 3));
		point(c(p(input)), 1).get().into(output.traverse(1)).evaluate();

		log("Output: [" + output.toDouble(0) + ", " + output.toDouble(1) + ", " + output.toDouble(2) + "]");

		// Should extract v1 = (4, 5, 6)
		assertEquals(4.0, output.toDouble(0));
		assertEquals(5.0, output.toDouble(1));
		assertEquals(6.0, output.toDouble(2));
	}

	/**
	 * Test extracting vertex 2 from a single triangle with shape (1, 3, 3).
	 */
	@Test(timeout = 10000)
	public void singleTriangleVertex2() {
		PackedCollection input = new PackedCollection(shape(1, 3, 3));

		// Triangle vertices: v0=(1,2,3), v1=(4,5,6), v2=(7,8,9)
		input.setMem(0, 1.0); input.setMem(1, 2.0); input.setMem(2, 3.0);   // v0
		input.setMem(3, 4.0); input.setMem(4, 5.0); input.setMem(5, 6.0);   // v1
		input.setMem(6, 7.0); input.setMem(7, 8.0); input.setMem(8, 9.0);   // v2

		log("=== Single Triangle Vertex 2 ===");

		PackedCollection output = new PackedCollection(shape(1, 3));
		point(c(p(input)), 2).get().into(output.traverse(1)).evaluate();

		log("Output: [" + output.toDouble(0) + ", " + output.toDouble(1) + ", " + output.toDouble(2) + "]");

		// Should extract v2 = (7, 8, 9)
		assertEquals(7.0, output.toDouble(0));
		assertEquals(8.0, output.toDouble(1));
		assertEquals(9.0, output.toDouble(2));
	}

	/**
	 * Test extracting vertices from multiple triangles with shape (3, 3, 3).
	 * This is the key test for batch processing.
	 */
	@Test(timeout = 10000)
	public void multipleTrianglesVertex0() {
		// Create 3 triangles' vertex data: shape (3, 3, 3) = 27 floats
		PackedCollection input = new PackedCollection(shape(3, 3, 3));

		// Triangle 0: v0=(1,1,1), v1=(2,2,2), v2=(3,3,3)
		input.setMem(0, 1.0); input.setMem(1, 1.0); input.setMem(2, 1.0);   // v0
		input.setMem(3, 2.0); input.setMem(4, 2.0); input.setMem(5, 2.0);   // v1
		input.setMem(6, 3.0); input.setMem(7, 3.0); input.setMem(8, 3.0);   // v2

		// Triangle 1: v0=(10,10,10), v1=(20,20,20), v2=(30,30,30)
		input.setMem(9, 10.0); input.setMem(10, 10.0); input.setMem(11, 10.0);   // v0
		input.setMem(12, 20.0); input.setMem(13, 20.0); input.setMem(14, 20.0);   // v1
		input.setMem(15, 30.0); input.setMem(16, 30.0); input.setMem(17, 30.0);   // v2

		// Triangle 2: v0=(100,100,100), v1=(200,200,200), v2=(300,300,300)
		input.setMem(18, 100.0); input.setMem(19, 100.0); input.setMem(20, 100.0);   // v0
		input.setMem(21, 200.0); input.setMem(22, 200.0); input.setMem(23, 200.0);   // v1
		input.setMem(24, 300.0); input.setMem(25, 300.0); input.setMem(26, 300.0);   // v2

		log("=== Multiple Triangles Vertex 0 ===");
		log("Input shape: " + input.getShape());

		PackedCollection output = new PackedCollection(shape(3, 3));
		point(c(p(input)), 0).get().into(output.traverse(1)).evaluate();

		log("Triangle 0 v0: [" + output.toDouble(0) + ", " + output.toDouble(1) + ", " + output.toDouble(2) + "]");
		log("Triangle 1 v0: [" + output.toDouble(3) + ", " + output.toDouble(4) + ", " + output.toDouble(5) + "]");
		log("Triangle 2 v0: [" + output.toDouble(6) + ", " + output.toDouble(7) + ", " + output.toDouble(8) + "]");

		// Triangle 0, vertex 0 = (1, 1, 1)
		assertEquals(1.0, output.toDouble(0));
		assertEquals(1.0, output.toDouble(1));
		assertEquals(1.0, output.toDouble(2));

		// Triangle 1, vertex 0 = (10, 10, 10)
		assertEquals(10.0, output.toDouble(3));
		assertEquals(10.0, output.toDouble(4));
		assertEquals(10.0, output.toDouble(5));

		// Triangle 2, vertex 0 = (100, 100, 100)
		assertEquals(100.0, output.toDouble(6));
		assertEquals(100.0, output.toDouble(7));
		assertEquals(100.0, output.toDouble(8));
	}

	/**
	 * Test extracting vertex 1 from multiple triangles.
	 */
	@Test(timeout = 10000)
	public void multipleTrianglesVertex1() {
		PackedCollection input = new PackedCollection(shape(3, 3, 3));

		// Triangle 0: v0=(1,1,1), v1=(2,2,2), v2=(3,3,3)
		input.setMem(0, 1.0); input.setMem(1, 1.0); input.setMem(2, 1.0);
		input.setMem(3, 2.0); input.setMem(4, 2.0); input.setMem(5, 2.0);
		input.setMem(6, 3.0); input.setMem(7, 3.0); input.setMem(8, 3.0);

		// Triangle 1: v0=(10,10,10), v1=(20,20,20), v2=(30,30,30)
		input.setMem(9, 10.0); input.setMem(10, 10.0); input.setMem(11, 10.0);
		input.setMem(12, 20.0); input.setMem(13, 20.0); input.setMem(14, 20.0);
		input.setMem(15, 30.0); input.setMem(16, 30.0); input.setMem(17, 30.0);

		// Triangle 2: v0=(100,100,100), v1=(200,200,200), v2=(300,300,300)
		input.setMem(18, 100.0); input.setMem(19, 100.0); input.setMem(20, 100.0);
		input.setMem(21, 200.0); input.setMem(22, 200.0); input.setMem(23, 200.0);
		input.setMem(24, 300.0); input.setMem(25, 300.0); input.setMem(26, 300.0);

		log("=== Multiple Triangles Vertex 1 ===");

		PackedCollection output = new PackedCollection(shape(3, 3));
		point(c(p(input)), 1).get().into(output.traverse(1)).evaluate();

		log("Triangle 0 v1: [" + output.toDouble(0) + ", " + output.toDouble(1) + ", " + output.toDouble(2) + "]");
		log("Triangle 1 v1: [" + output.toDouble(3) + ", " + output.toDouble(4) + ", " + output.toDouble(5) + "]");
		log("Triangle 2 v1: [" + output.toDouble(6) + ", " + output.toDouble(7) + ", " + output.toDouble(8) + "]");

		// Triangle 0, vertex 1 = (2, 2, 2)
		assertEquals(2.0, output.toDouble(0));
		assertEquals(2.0, output.toDouble(1));
		assertEquals(2.0, output.toDouble(2));

		// Triangle 1, vertex 1 = (20, 20, 20)
		assertEquals(20.0, output.toDouble(3));
		assertEquals(20.0, output.toDouble(4));
		assertEquals(20.0, output.toDouble(5));

		// Triangle 2, vertex 1 = (200, 200, 200)
		assertEquals(200.0, output.toDouble(6));
		assertEquals(200.0, output.toDouble(7));
		assertEquals(200.0, output.toDouble(8));
	}

	/**
	 * Test extracting vertex 2 from multiple triangles.
	 */
	@Test(timeout = 10000)
	public void multipleTrianglesVertex2() {
		PackedCollection input = new PackedCollection(shape(3, 3, 3));

		// Triangle 0: v0=(1,1,1), v1=(2,2,2), v2=(3,3,3)
		input.setMem(0, 1.0); input.setMem(1, 1.0); input.setMem(2, 1.0);
		input.setMem(3, 2.0); input.setMem(4, 2.0); input.setMem(5, 2.0);
		input.setMem(6, 3.0); input.setMem(7, 3.0); input.setMem(8, 3.0);

		// Triangle 1: v0=(10,10,10), v1=(20,20,20), v2=(30,30,30)
		input.setMem(9, 10.0); input.setMem(10, 10.0); input.setMem(11, 10.0);
		input.setMem(12, 20.0); input.setMem(13, 20.0); input.setMem(14, 20.0);
		input.setMem(15, 30.0); input.setMem(16, 30.0); input.setMem(17, 30.0);

		// Triangle 2: v0=(100,100,100), v1=(200,200,200), v2=(300,300,300)
		input.setMem(18, 100.0); input.setMem(19, 100.0); input.setMem(20, 100.0);
		input.setMem(21, 200.0); input.setMem(22, 200.0); input.setMem(23, 200.0);
		input.setMem(24, 300.0); input.setMem(25, 300.0); input.setMem(26, 300.0);

		log("=== Multiple Triangles Vertex 2 ===");

		PackedCollection output = new PackedCollection(shape(3, 3));
		point(c(p(input)), 2).get().into(output.traverse(1)).evaluate();

		log("Triangle 0 v2: [" + output.toDouble(0) + ", " + output.toDouble(1) + ", " + output.toDouble(2) + "]");
		log("Triangle 1 v2: [" + output.toDouble(3) + ", " + output.toDouble(4) + ", " + output.toDouble(5) + "]");
		log("Triangle 2 v2: [" + output.toDouble(6) + ", " + output.toDouble(7) + ", " + output.toDouble(8) + "]");

		// Triangle 0, vertex 2 = (3, 3, 3)
		assertEquals(3.0, output.toDouble(0));
		assertEquals(3.0, output.toDouble(1));
		assertEquals(3.0, output.toDouble(2));

		// Triangle 1, vertex 2 = (30, 30, 30)
		assertEquals(30.0, output.toDouble(3));
		assertEquals(30.0, output.toDouble(4));
		assertEquals(30.0, output.toDouble(5));

		// Triangle 2, vertex 2 = (300, 300, 300)
		assertEquals(300.0, output.toDouble(6));
		assertEquals(300.0, output.toDouble(7));
		assertEquals(300.0, output.toDouble(8));
	}

	/**
	 * Test with flat (3, 3) input - a single triangle represented as 3 vectors.
	 * This tests the legacy non-batch case.
	 */
	@Test(timeout = 10000)
	public void flatSingleTriangle() {
		// Create vertex data in flat (3, 3) format - single triangle
		PackedCollection input = new PackedCollection(shape(3, 3));

		// Vertices: v0=(1,2,3), v1=(4,5,6), v2=(7,8,9)
		input.setMem(0, 1.0); input.setMem(1, 2.0); input.setMem(2, 3.0);   // v0
		input.setMem(3, 4.0); input.setMem(4, 5.0); input.setMem(5, 6.0);   // v1
		input.setMem(6, 7.0); input.setMem(7, 8.0); input.setMem(8, 9.0);   // v2

		log("=== Flat Single Triangle ===");
		log("Input shape: " + input.getShape());

		// Extract each vertex
		PackedCollection v0 = point(c(p(input)), 0).get().evaluate();
		PackedCollection v1 = point(c(p(input)), 1).get().evaluate();
		PackedCollection v2 = point(c(p(input)), 2).get().evaluate();

		log("v0: " + v0);
		log("v1: " + v1);
		log("v2: " + v2);

		assertEquals(new Vector(1.0, 2.0, 3.0), v0);
		assertEquals(new Vector(4.0, 5.0, 6.0), v1);
		assertEquals(new Vector(7.0, 8.0, 9.0), v2);
	}

	/**
	 * Test with MeshPointData-style input (N, 9, 3) where only first 3 vectors are used.
	 * This documents the incompatibility between getMeshPointData() output and point() method input.
	 *
	 * NOTE: DefaultVertexData.getMeshPointData() returns shape (N, 9, 3) but the point() method
	 * expects shape (N, 3, 3). This test documents the issue.
	 */
	@Test(timeout = 10000)
	public void meshPointDataDocumentation() {
		log("=== MeshPointData Documentation ===");
		log("DefaultVertexData.getMeshPointData() returns shape (N, 9, 3)");
		log("TriangleFeatures.point() expects shape (N, 3, 3)");
		log("This mismatch causes incorrect data access for triangles beyond index 0");
		log("The fix should be to change getMeshPointData() to return (N, 3, 3)");

		// This test doesn't assert anything - it just documents the issue
		// The real fix is in DefaultVertexData.getMeshPointData()
	}
}
