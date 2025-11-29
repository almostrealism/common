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

import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.space.MeshData;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

public class PointExtractionTest implements TestFeatures {

	@Test
	public void batchEdgeComputation() {
		// Test computing edges from extracted vertices
		PackedCollection points = Vector.table(3, 2);

		// Triangle 0: vertices at (0,0,0), (1,0,0), (0,1,0)
		points.get(0).set(0, new Vector(0, 0, 0));
		points.get(0).set(1, new Vector(1, 0, 0));
		points.get(0).set(2, new Vector(0, 1, 0));

		// Triangle 1: vertices at (0,0,0), (1,0,0), (0,0,1)
		points.get(1).set(0, new Vector(0, 0, 0));
		points.get(1).set(1, new Vector(1, 0, 0));
		points.get(1).set(2, new Vector(0, 0, 1));

		log("\n=== Batch Edge Computation ===");

		// Flatten and reshape for batch processing
		PackedCollection reshaped = points.traverse(0).reshape(shape(2, 3, 3));

		// Extract vertices
		PackedCollection v0 = new PackedCollection(shape(2, 3));
		PackedCollection v1 = new PackedCollection(shape(2, 3));
		PackedCollection v2 = new PackedCollection(shape(2, 3));
		point(c(p(reshaped)), 0).get().into(v0.traverse(1)).evaluate();
		point(c(p(reshaped)), 1).get().into(v1.traverse(1)).evaluate();
		point(c(p(reshaped)), 2).get().into(v2.traverse(1)).evaluate();

		// Compute edges
		PackedCollection edge1 = new PackedCollection(shape(2, 3));
		PackedCollection edge2 = new PackedCollection(shape(2, 3));
		subtract(c(p(v1)), c(p(v0))).get().into(edge1.traverse(1)).evaluate();
		subtract(c(p(v2)), c(p(v0))).get().into(edge2.traverse(1)).evaluate();

		log("Triangle 0 edge1: [" + edge1.toDouble(0) + ", " + edge1.toDouble(1) + ", " + edge1.toDouble(2) + "]");
		log("Triangle 0 edge2: [" + edge2.toDouble(0) + ", " + edge2.toDouble(1) + ", " + edge2.toDouble(2) + "]");
		log("Triangle 1 edge1: [" + edge1.toDouble(3) + ", " + edge1.toDouble(4) + ", " + edge1.toDouble(5) + "]");
		log("Triangle 1 edge2: [" + edge2.toDouble(3) + ", " + edge2.toDouble(4) + ", " + edge2.toDouble(5) + "]");

		// Compute cross product
		PackedCollection cross = new PackedCollection(shape(2, 3));
		crossProduct(c(p(edge1)), c(p(edge2))).get().into(cross.traverse(1)).evaluate();

		log("Triangle 0 cross: [" + cross.toDouble(0) + ", " + cross.toDouble(1) + ", " + cross.toDouble(2) + "]");
		log("Triangle 1 cross: [" + cross.toDouble(3) + ", " + cross.toDouble(4) + ", " + cross.toDouble(5) + "]");

		// Compute normals
		PackedCollection normals = new PackedCollection(shape(2, 3));
		normalize(c(p(cross))).get().into(normals.traverse(1)).evaluate();

		log("Triangle 0 normal: [" + normals.toDouble(0) + ", " + normals.toDouble(1) + ", " + normals.toDouble(2) + "]");
		log("Triangle 1 normal: [" + normals.toDouble(3) + ", " + normals.toDouble(4) + ", " + normals.toDouble(5) + "]");

		// Triangle 0 should have normal [0, 0, 1]
		assertEquals(0.0, normals.toDouble(0));
		assertEquals(0.0, normals.toDouble(1));
		assertEquals(1.0, normals.toDouble(2));

		// Triangle 1 should have normal [0, -1, 0]
		assertEquals(0.0, normals.toDouble(3));
		assertEquals(-1.0, normals.toDouble(4));
		assertEquals(0.0, normals.toDouble(5));
	}

	@Test
	public void batchSubtract() {
		// Test batch subtraction
		PackedCollection v1 = new PackedCollection(shape(2, 3));
		PackedCollection v2 = new PackedCollection(shape(2, 3));

		// Pair 0: [1,2,3] - [0,1,0] = [1,1,3]
		v1.setMem(0, 1.0); v1.setMem(1, 2.0); v1.setMem(2, 3.0);
		v2.setMem(0, 0.0); v2.setMem(1, 1.0); v2.setMem(2, 0.0);

		// Pair 1: [5,6,7] - [2,3,4] = [3,3,3]
		v1.setMem(3, 5.0); v1.setMem(4, 6.0); v1.setMem(5, 7.0);
		v2.setMem(3, 2.0); v2.setMem(4, 3.0); v2.setMem(5, 4.0);

		log("\n=== Batch Subtract Test ===");

		// Compute differences
		PackedCollection result = new PackedCollection(shape(2, 3));
		subtract(c(p(v1)), c(p(v2))).get().into(result.traverse(1)).evaluate();

		log("Pair 0 difference: [" + result.toDouble(0) + ", " + result.toDouble(1) + ", " + result.toDouble(2) + "]");
		log("Pair 1 difference: [" + result.toDouble(3) + ", " + result.toDouble(4) + ", " + result.toDouble(5) + "]");

		// Pair 0 should be [1, 1, 3]
		assertEquals(1.0, result.toDouble(0));
		assertEquals(1.0, result.toDouble(1));
		assertEquals(3.0, result.toDouble(2));

		// Pair 1 should be [3, 3, 3]
		assertEquals(3.0, result.toDouble(3));
		assertEquals(3.0, result.toDouble(4));
		assertEquals(3.0, result.toDouble(5));
	}

	@Test
	public void batchPointExtractionVertex1() {
		// Test extracting vertex 1 from batch
		PackedCollection points = Vector.table(3, 2);

		// Triangle 0: vertices at (1,0,0), (0,1,0), (0,0,1)
		points.get(0).set(0, new Vector(1, 0, 0));
		points.get(0).set(1, new Vector(0, 1, 0));  // vertex 1
		points.get(0).set(2, new Vector(0, 0, 1));

		// Triangle 1: vertices at (2,0,0), (0,2,0), (0,0,2)
		points.get(1).set(0, new Vector(2, 0, 0));
		points.get(1).set(1, new Vector(0, 2, 0));  // vertex 1
		points.get(1).set(2, new Vector(0, 0, 2));

		log("\n=== Batch Point Extraction - Vertex 1 ===");
		log("Input shape: " + points.getShape());

		// Flatten and reshape for batch processing
		PackedCollection reshaped = points.traverse(0).reshape(shape(2, 3, 3));
		log("Reshaped shape: " + reshaped.getShape());
		log("Reshaped data (first 18 values): ");
		reshaped.print();

		// Extract vertex 1 from both triangles
		PackedCollection output = new PackedCollection(shape(2, 3));
		point(c(p(reshaped)), 1).get().into(output.traverse(1)).evaluate();

		log("\nExtracted vertex 1 from both triangles:");
		log("Triangle 0, vertex 1: [" + output.toDouble(0) + ", " + output.toDouble(1) + ", " + output.toDouble(2) + "]");
		log("Triangle 1, vertex 1: [" + output.toDouble(3) + ", " + output.toDouble(4) + ", " + output.toDouble(5) + "]");

		// Verify triangle 0, vertex 1 = (0, 1, 0)
		assertEquals(0.0, output.toDouble(0));
		assertEquals(1.0, output.toDouble(1));
		assertEquals(0.0, output.toDouble(2));

		// Verify triangle 1, vertex 1 = (0, 2, 0)
		assertEquals(0.0, output.toDouble(3));
		assertEquals(2.0, output.toDouble(4));
		assertEquals(0.0, output.toDouble(5));
	}

	@Test
	public void batchPointExtraction() {
		// Create test data: 2 triangles with distinct vertices
		PackedCollection points = Vector.table(3, 2);

		// Triangle 0: vertices at (1,0,0), (0,1,0), (0,0,1)
		points.get(0).set(0, new Vector(1, 0, 0));
		points.get(0).set(1, new Vector(0, 1, 0));
		points.get(0).set(2, new Vector(0, 0, 1));

		// Triangle 1: vertices at (2,0,0), (0,2,0), (0,0,2)
		points.get(1).set(0, new Vector(2, 0, 0));
		points.get(1).set(1, new Vector(0, 2, 0));
		points.get(1).set(2, new Vector(0, 0, 2));

		log("Input shape: " + points.getShape());
		log("Triangle 0, vertex 0: " + points.get(0).get(0));
		log("Triangle 1, vertex 0: " + points.get(1).get(0));

		// Flatten and reshape for batch processing
		PackedCollection reshaped = points.traverse(0).reshape(shape(2, 3, 3));
		log("Reshaped shape: " + reshaped.getShape());
		log("Reshaped data (first 18 values): ");
		reshaped.print();

		// Extract vertex 0 from both triangles
		PackedCollection output = new PackedCollection(shape(2, 3));
		point(c(p(reshaped)), 0).get().into(output.traverse(1)).evaluate();

		log("\nExtracted vertex 0 from both triangles:");
		log("Triangle 0, vertex 0: [" + output.toDouble(0) + ", " + output.toDouble(1) + ", " + output.toDouble(2) + "]");
		log("Triangle 1, vertex 0: [" + output.toDouble(3) + ", " + output.toDouble(4) + ", " + output.toDouble(5) + "]");

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
		PackedCollection points = Vector.table(3, 2);

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

		log("\n=== Batch Triangle Computation ===");
		log("Input shape: " + points.getShape());

		// Flatten and reshape for batch processing
		PackedCollection reshaped = points.traverse(0).reshape(shape(2, 3, 3));
		log("Reshaped shape: " + reshaped.getShape());
		log("Input data (first 18 values):");
		reshaped.print();

		// Debug: Extract vertices to see if batch extraction works
		PackedCollection v0 = new PackedCollection(shape(2, 3));
		PackedCollection v1 = new PackedCollection(shape(2, 3));
		PackedCollection v2 = new PackedCollection(shape(2, 3));
		point(c(p(reshaped)), 0).get().into(v0.traverse(1)).evaluate();
		point(c(p(reshaped)), 1).get().into(v1.traverse(1)).evaluate();
		point(c(p(reshaped)), 2).get().into(v2.traverse(1)).evaluate();

		log("\nExtracted vertices:");
		log("Triangle 0: v0=[" + v0.toDouble(0) + "," + v0.toDouble(1) + "," + v0.toDouble(2) + "]");
		log("Triangle 0: v1=[" + v1.toDouble(0) + "," + v1.toDouble(1) + "," + v1.toDouble(2) + "]");
		log("Triangle 0: v2=[" + v2.toDouble(0) + "," + v2.toDouble(1) + "," + v2.toDouble(2) + "]");
		log("Triangle 1: v0=[" + v0.toDouble(3) + "," + v0.toDouble(4) + "," + v0.toDouble(5) + "]");
		log("Triangle 1: v1=[" + v1.toDouble(3) + "," + v1.toDouble(4) + "," + v1.toDouble(5) + "]");
		log("Triangle 1: v2=[" + v2.toDouble(3) + "," + v2.toDouble(4) + "," + v2.toDouble(5) + "]");

		// Debug: Compute edges and normals separately to see intermediate values
		PackedCollection abcDebug = new PackedCollection(shape(2, 3));
		PackedCollection defDebug = new PackedCollection(shape(2, 3));
		PackedCollection crossDebug = new PackedCollection(shape(2, 3));
		PackedCollection normalDebug = new PackedCollection(shape(2, 3));

		Producer<PackedCollection> rawP = p(reshaped);
		log("rawP class: " + rawP.getClass().getSimpleName());
		if (rawP instanceof io.almostrealism.collect.Shape) {
			log("rawP shape: " + ((io.almostrealism.collect.Shape) rawP).getShape());
		}
		CollectionProducer cP = c(rawP);
		log("cP class: " + cP.getClass().getSimpleName());
		log("cP shape: " + cP.getShape());

		CollectionProducer p1 = point(cP, 0);
		CollectionProducer p2 = point(cP, 1);
		CollectionProducer p3 = point(cP, 2);
		log("p1 shape: " + p1.getShape());
		log("p2 shape: " + p2.getShape());
		log("p3 shape: " + p3.getShape());
		CollectionProducer abcProducer = subtract(p2, p1);
		CollectionProducer defProducer = subtract(p3, p1);
		log("abcProducer shape: " + abcProducer.getShape());
		log("defProducer shape: " + defProducer.getShape());

		abcProducer.get().into(abcDebug.traverse(1)).evaluate();
		defProducer.get().into(defDebug.traverse(1)).evaluate();

		CollectionProducer crossProducer = crossProduct(abcProducer, defProducer);
		log("crossProducer shape: " + crossProducer.getShape());
		crossProducer.get().into(crossDebug.traverse(1)).evaluate();

		// Debug lengthSq components
		PackedCollection squaredDebug = new PackedCollection(shape(2, 3));
		PackedCollection lengthSqDebug = new PackedCollection(shape(2, 1));
		PackedCollection lengthDebug = new PackedCollection(shape(2, 1));

		multiply(crossProducer, crossProducer).get().into(squaredDebug.traverse(1)).evaluate();
		lengthSq(crossProducer).get().into(lengthSqDebug.traverse(1)).evaluate();
		length(crossProducer).get().into(lengthDebug.traverse(1)).evaluate();

		normalize(crossProducer).get().into(normalDebug.traverse(1)).evaluate();

		log("\nDebug intermediate values:");
		log("abc (v1-v0) Triangle 0: [" + abcDebug.toDouble(0) + "," + abcDebug.toDouble(1) + "," + abcDebug.toDouble(2) + "]");
		log("def (v2-v0) Triangle 0: [" + defDebug.toDouble(0) + "," + defDebug.toDouble(1) + "," + defDebug.toDouble(2) + "]");
		log("cross Triangle 0: [" + crossDebug.toDouble(0) + "," + crossDebug.toDouble(1) + "," + crossDebug.toDouble(2) + "]");
		log("squared Triangle 0: [" + squaredDebug.toDouble(0) + "," + squaredDebug.toDouble(1) + "," + squaredDebug.toDouble(2) + "]");
		log("lengthSq Triangle 0: " + lengthSqDebug.toDouble(0));
		log("length Triangle 0: " + lengthDebug.toDouble(0));
		log("normal Triangle 0: [" + normalDebug.toDouble(0) + "," + normalDebug.toDouble(1) + "," + normalDebug.toDouble(2) + "]");

		// Compute triangle data using triangle() method
		MeshData output = new MeshData(2);
		triangle(c(p(reshaped))).get().into(output.traverse(1)).evaluate();

		log("\nTriangle 0 normal: [" +
			output.get(0).get(3).toDouble(0) + ", " +
			output.get(0).get(3).toDouble(1) + ", " +
			output.get(0).get(3).toDouble(2) + "]");
		log("Triangle 1 normal: [" +
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

	@Test
	public void batchCrossProduct() {
		// Create test data: 2 pairs of vectors to cross
		PackedCollection vec1 = new PackedCollection(shape(2, 3));
		PackedCollection vec2 = new PackedCollection(shape(2, 3));

		// Pair 0: [1,0,0] x [0,1,0] = [0,0,1]
		vec1.setMem(0, 1.0);
		vec1.setMem(1, 0.0);
		vec1.setMem(2, 0.0);
		vec2.setMem(0, 0.0);
		vec2.setMem(1, 1.0);
		vec2.setMem(2, 0.0);

		// Pair 1: [1,0,0] x [0,0,1] = [0,-1,0]
		vec1.setMem(3, 1.0);
		vec1.setMem(4, 0.0);
		vec1.setMem(5, 0.0);
		vec2.setMem(3, 0.0);
		vec2.setMem(4, 0.0);
		vec2.setMem(5, 1.0);

		log("\n=== Batch Cross Product Test ===");
		log("Vec1 shape: " + vec1.getShape());
		log("Vec2 shape: " + vec2.getShape());

		// Compute cross products
		PackedCollection result = new PackedCollection(shape(2, 3));
		crossProduct(c(p(vec1)), c(p(vec2))).get().into(result.traverse(1)).evaluate();

		log("\nResults:");
		log("Pair 0 cross product: [" + result.toDouble(0) + ", " + result.toDouble(1) + ", " + result.toDouble(2) + "]");
		log("Pair 1 cross product: [" + result.toDouble(3) + ", " + result.toDouble(4) + ", " + result.toDouble(5) + "]");

		// Pair 0 should be [0, 0, 1]
		assertEquals(0.0, result.toDouble(0));
		assertEquals(0.0, result.toDouble(1));
		assertEquals(1.0, result.toDouble(2));

		// Pair 1 should be [0, -1, 0]
		assertEquals(0.0, result.toDouble(3));
		assertEquals(-1.0, result.toDouble(4));
		assertEquals(0.0, result.toDouble(5));
	}

	@Test
	public void batchNormalize() {
		// Create test data: 2 vectors with different magnitudes
		PackedCollection vecs = new PackedCollection(shape(2, 3));

		// Vector 0: [3, 0, 4] with magnitude 5
		vecs.setMem(0, 3.0);
		vecs.setMem(1, 0.0);
		vecs.setMem(2, 4.0);

		// Vector 1: [0, 5, 12] with magnitude 13
		vecs.setMem(3, 0.0);
		vecs.setMem(4, 5.0);
		vecs.setMem(5, 12.0);

		log("\n=== Batch Normalize Test ===");
		log("Input shape: " + vecs.getShape());
		log("Vec 0: [" + vecs.toDouble(0) + ", " + vecs.toDouble(1) + ", " + vecs.toDouble(2) + "]");
		log("Vec 1: [" + vecs.toDouble(3) + ", " + vecs.toDouble(4) + ", " + vecs.toDouble(5) + "]");

		// Test lengthSq first
		PackedCollection lengthSqResult = new PackedCollection(shape(2, 1));
		lengthSq(c(p(vecs))).get().into(lengthSqResult.traverse(1)).evaluate();
		log("\nLength squared:");
		log("Vec 0: " + lengthSqResult.toDouble(0) + " (expected 25)");
		log("Vec 1: " + lengthSqResult.toDouble(1) + " (expected 169)");

		// Test length
		PackedCollection lengthResult = new PackedCollection(shape(2, 1));
		length(c(p(vecs))).get().into(lengthResult.traverse(1)).evaluate();
		log("\nLength:");
		log("Vec 0: " + lengthResult.toDouble(0) + " (expected 5)");
		log("Vec 1: " + lengthResult.toDouble(1) + " (expected 13)");

		// Test normalize
		PackedCollection normalized = new PackedCollection(shape(2, 3));
		normalize(c(p(vecs))).get().into(normalized.traverse(1)).evaluate();

		log("\nNormalized vectors:");
		log("Vec 0: [" + normalized.toDouble(0) + ", " + normalized.toDouble(1) + ", " + normalized.toDouble(2) + "]");
		log("Vec 1: [" + normalized.toDouble(3) + ", " + normalized.toDouble(4) + ", " + normalized.toDouble(5) + "]");

		// Check magnitudes of normalized vectors
		PackedCollection normalizedLengths = new PackedCollection(shape(2, 1));
		length(c(p(normalized))).get().into(normalizedLengths.traverse(1)).evaluate();
		log("\nNormalized vector lengths:");
		log("Vec 0: " + normalizedLengths.toDouble(0) + " (expected 1.0)");
		log("Vec 1: " + normalizedLengths.toDouble(1) + " (expected 1.0)");

		// Verify normalized vectors have magnitude 1
		assertEquals(1.0, normalizedLengths.toDouble(0));
		assertEquals(1.0, normalizedLengths.toDouble(1));

		// Verify individual components
		assertEquals(3.0 / 5.0, normalized.toDouble(0));
		assertEquals(0.0, normalized.toDouble(1));
		assertEquals(4.0 / 5.0, normalized.toDouble(2));

		assertEquals(0.0, normalized.toDouble(3));
		assertEquals(5.0 / 13.0, normalized.toDouble(4));
		assertEquals(12.0 / 13.0, normalized.toDouble(5));
	}

	@Test
	public void meshDataStructure() {
		// Test with proper (N, 3, 3) shape: N triangles, 3 vertices per triangle, 3 components per vertex
		PackedCollection flatData = new PackedCollection(shape(3 * 3 * 3));  // 27 scalars total

		// Triangle 0: vertices (0,1,0), (-1,-1,0), (1,-1,0)
		// Occupies indices 0-8 (9 scalars)
		flatData.setMem(0, 0.0); flatData.setMem(1, 1.0); flatData.setMem(2, 0.0);    // v0
		flatData.setMem(3, -1.0); flatData.setMem(4, -1.0); flatData.setMem(5, 0.0);  // v1
		flatData.setMem(6, 1.0); flatData.setMem(7, -1.0); flatData.setMem(8, 0.0);   // v2

		// Triangle 1: vertices (-1,1,-1), (-1,-1,0), (0,1,0)
		// Occupies indices 9-17
		flatData.setMem(9, -1.0); flatData.setMem(10, 1.0); flatData.setMem(11, -1.0);   // v0
		flatData.setMem(12, -1.0); flatData.setMem(13, -1.0); flatData.setMem(14, 0.0);  // v1
		flatData.setMem(15, 0.0); flatData.setMem(16, 1.0); flatData.setMem(17, 0.0);    // v2

		// Triangle 2: vertices (0,1,0), (1,-1,0), (1,1,-1)
		// Occupies indices 18-26
		flatData.setMem(18, 0.0); flatData.setMem(19, 1.0); flatData.setMem(20, 0.0);    // v0
		flatData.setMem(21, 1.0); flatData.setMem(22, -1.0); flatData.setMem(23, 0.0);   // v1
		flatData.setMem(24, 1.0); flatData.setMem(25, 1.0); flatData.setMem(26, -1.0);   // v2

		log("\n=== Mesh Data Structure Test ===");

		// Reshape to (3, 3, 3)
		PackedCollection reshaped = flatData.reshape(shape(3, 3, 3));
		log("Reshaped shape: " + reshaped.getShape());
		log("First 9 values (triangle 0):");
		reshaped.range(shape(3, 3)).print();

		// Use triangle() method directly for proper batch processing with shape (3, 3, 3)
		// Note: Triangle.dataProducer expects single triangle (3, 3) input, so we use triangle() directly
		MeshData result = new MeshData(3);
		CollectionProducer triangleProducer = triangle(c(p(reshaped)));
		triangleProducer.get().into(result.traverse(1)).evaluate();

		// Triangle 0 should have normal [0, 0, 1]
		assertEquals(0.0, result.get(0).get(3).toDouble(0));
		assertEquals(0.0, result.get(0).get(3).toDouble(1));
		assertEquals(1.0, result.get(0).get(3).toDouble(2));

		// Triangle 1 and 2 should also have correct normals now
		assertEquals(-2.0 / 3.0, result.get(1).get(3).toDouble(0));
		assertEquals(1.0 / 3.0, result.get(1).get(3).toDouble(1));
		assertEquals(2.0 / 3.0, result.get(1).get(3).toDouble(2));

		assertEquals(2.0 / 3.0, result.get(2).get(3).toDouble(0));
		assertEquals(1.0 / 3.0, result.get(2).get(3).toDouble(1));
		assertEquals(2.0 / 3.0, result.get(2).get(3).toDouble(2));
	}
}
