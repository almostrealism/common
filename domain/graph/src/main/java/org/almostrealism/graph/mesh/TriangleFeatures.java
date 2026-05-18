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

package org.almostrealism.graph.mesh;

import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.expression.Expression;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.VectorFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.DefaultTraversableExpressionComputation;
import org.almostrealism.collect.computations.IndexProjectionProducerComputation;

/**
 * Feature interface for triangle mesh operations in 3D geometry.
 *
 * <p>{@code TriangleFeatures} extends {@link VectorFeatures} to provide
 * operations for creating and manipulating triangle meshes. Triangles are
 * represented as packed collections containing edge vectors, a position
 * vector, and a normal vector.</p>
 *
 * <p>Triangle data structure (4 vectors, 12 floats total):</p>
 * <ul>
 *   <li>abc (index 0): First edge vector (vertex2 - vertex1)</li>
 *   <li>def (index 1): Second edge vector (vertex3 - vertex1)</li>
 *   <li>jkl (index 2): Position vector (vertex1)</li>
 *   <li>normal (index 4): Unit normal vector (cross product of edges, normalized)</li>
 * </ul>
 *
 * <p>This representation is optimized for ray-triangle intersection tests
 * using the Moller-Trumbore algorithm.</p>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * TriangleFeatures tf = TriangleFeatures.getInstance();
 *
 * // Create triangle from three points
 * Producer<PackedCollection> p1 = tf.vector(0, 0, 0);
 * Producer<PackedCollection> p2 = tf.vector(1, 0, 0);
 * Producer<PackedCollection> p3 = tf.vector(0, 1, 0);
 * CollectionProducer triangle = tf.triangle(p1, p2, p3);
 *
 * // Access triangle components
 * CollectionProducer edge1 = tf.abc(triangleData);
 * CollectionProducer normal = tf.normal(triangleData);
 * }</pre>
 *
 * @author Michael Murray
 * @see VectorFeatures
 * @see TriangleIntersectAt
 */
public interface TriangleFeatures extends VectorFeatures {

	/**
	 * Extracts the first edge vector (abc) from a triangle.
	 *
	 * @param t the triangle data producer
	 * @return producer for the first edge vector (vertex2 - vertex1)
	 */
	default CollectionProducer abc(Producer<PackedCollection> t) {
		return vector(t, 0);
	}

	/**
	 * Extracts the second edge vector (def) from a triangle.
	 *
	 * @param t the triangle data producer
	 * @return producer for the second edge vector (vertex3 - vertex1)
	 */
	default CollectionProducer def(Producer<PackedCollection> t) {
		return vector(t, 1);
	}

	/**
	 * Extracts the position vector (jkl) from a triangle.
	 *
	 * @param t the triangle data producer
	 * @return producer for the position vector (vertex1)
	 */
	default CollectionProducer jkl(Producer<PackedCollection> t) {
		return vector(t, 2);
	}

	/**
	 * Extracts the normal vector from a triangle.
	 *
	 * @param t the triangle data producer
	 * @return producer for the unit normal vector
	 */
	default CollectionProducer normal(Producer<PackedCollection> t) {
		return vector(t, 4);
	}

	/**
	 * Creates a triangle from a collection of three points.
	 *
	 * @param points supplier for a packed collection containing 3 vertices (9 floats)
	 * @return producer for the triangle data structure
	 */
	default CollectionProducer triangle(Producer<PackedCollection> points) {
		return triangle(
				point(points, 0),
				point(points, 1),
				point(points, 2));
	}

	/**
	 * Creates a triangle from three vertex producers.
	 *
	 * <p>Computes edge vectors and normal automatically from the vertex positions.</p>
	 *
	 * @param p1 producer for the first vertex
	 * @param p2 producer for the second vertex
	 * @param p3 producer for the third vertex
	 * @return producer for the triangle data structure
	 */
	default CollectionProducer triangle(
			Producer<PackedCollection> p1, Producer<PackedCollection> p2, Producer<PackedCollection> p3) {
		CollectionProducer abc = subtract(p2, p1);
		CollectionProducer def = subtract(p3, p1);
		Producer jkl = p1;
		return triangle(abc, def, jkl, normalize(crossProduct(abc, def)));
	}

	/**
	 * Creates a triangle from explicit edge vectors, position, and normal.
	 *
	 * @param abc    the first edge vector
	 * @param def    the second edge vector
	 * @param jkl    the position vector (first vertex)
	 * @param normal the unit normal vector
	 * @return producer for the triangle data structure as a 4x3 packed collection
	 */
	default CollectionProducer triangle(Producer<PackedCollection> abc, Producer<PackedCollection> def,
										Producer<PackedCollection> jkl, Producer<PackedCollection> normal) {
		// For batch processing, inputs have shape (N, 3) and we need output shape (N, 4, 3)
		// Determine batch size from input shape
		TraversalPolicy inputShape = shape(abc);
		TraversalPolicy outputShape;
		if (inputShape.getDimensions() >= 2) {
			// Input is (N, 3), output should be (N, 4, 3)
			int batchSize = inputShape.length(0);
			outputShape = new TraversalPolicy(false, false, batchSize, 4, 3);
		} else {
			// Single triangle: input is (3), output is (4, 3)
			outputShape = new TraversalPolicy(false, false, 4, 3);
		}

		// Create custom computation to arrange the 4 vectors into triangle structure
		return new DefaultTraversableExpressionComputation("triangle", outputShape, args ->
			CollectionExpression.create(outputShape, idx -> {
				// idx ranges from 0 to N*12-1 (N triangles * 4 vectors * 3 components)
				// For each output position:
				// - triangleIdx = idx / 12 (which triangle)
				// - vectorIdx = (idx % 12) / 3 (which of the 4 vectors: 0=abc, 1=def, 2=jkl, 3=normal)
				// - componentIdx = idx % 3 (which component: x, y, or z)

				Expression triangleIdx = idx.divide(e(12)).floor();
				Expression localIdx = idx.imod(12);
				Expression vectorIdx = localIdx.divide(e(3)).floor();
				Expression componentIdx = localIdx.imod(3);

				// Base index in each input array (N, 3)
				Expression inputIdx = triangleIdx.multiply(e(3)).add(componentIdx);

				// Select from appropriate input based on vectorIdx
				Expression fromAbc = args[1].getValueAt(inputIdx);
				Expression fromDef = args[2].getValueAt(inputIdx);
				Expression fromJkl = args[3].getValueAt(inputIdx);
				Expression fromNormal = args[4].getValueAt(inputIdx);

				// Return the appropriate value based on vectorIdx
				Expression result = conditional(vectorIdx.eq(1), fromDef, fromAbc);
				result = conditional(vectorIdx.eq(2), fromJkl, result);
				result = conditional(vectorIdx.eq(3), fromNormal, result);

				return result;
			}), abc, def, jkl, normal);
	}

	/**
	 * Extracts a vertex from a packed collection of points.
	 *
	 *
	 * @param points supplier for the vertex collection
	 * @param vertexIndex  the vertex index within each group
	 * @return producer for the extracted vertex/vertices as Vector(s)
	 */
	default CollectionProducer point(Producer<PackedCollection> points, int vertexIndex) {
		TraversalPolicy inputShape = shape(points);

		// stride = number of floats per batch item (vertices per group * 3 components)
		// For shapes like (N, M, 3), stride = M * 3
		// For shapes like (M, 3), stride = M * 3
		int stride;
		TraversalPolicy outputShape;
		if (inputShape.getDimensions() >= 3) {
			// Input is (N, M, 3) - batch of triangles
			// Output is (N, 3) - batch of vertices
			int batchDim = inputShape.getDimensions() - 3;
			int batchSize = inputShape.length(batchDim);
			int group = inputShape.getDimensions() - 2;
			stride = inputShape.length(group) * 3;
			outputShape = new TraversalPolicy(false, false, batchSize, 3);
		} else if (inputShape.getDimensions() == 2) {
			// Input is (M, 3) - single triangle's vertices
			// Output is (3) - single vertex
			stride = inputShape.length(0) * 3;
			outputShape = new TraversalPolicy(false, false, 3);
		} else {
			stride = 3;  // Single vector
			outputShape = new TraversalPolicy(false, false, 3);
		}

		int vertexOffset = vertexIndex * 3;
		IndexProjectionProducerComputation projection = new IndexProjectionProducerComputation(
				"point_v" + vertexIndex + "_s" + stride, outputShape,
				idx -> idx.divide(e(3)).floor().multiply(e(stride)).add(e(vertexOffset)).add(idx.imod(3)),
				points);
		return projection;
	}

	/**
	 * Packs three vertex producers into a single 3x3 collection.
	 *
	 * @param p1 producer for the first vertex
	 * @param p2 producer for the second vertex
	 * @param p3 producer for the third vertex
	 * @return producer for the packed vertex collection
	 */
	default CollectionProducer points(Producer<PackedCollection> p1,
									  Producer<PackedCollection> p2,
									  Producer<PackedCollection> p3) {
		return concat(shape(3, 3), p1, p2, p3);
	}

	/**
	 * Returns a singleton instance of TriangleFeatures.
	 *
	 * @return a TriangleFeatures instance
	 */
	static TriangleFeatures getInstance() {
		return new TriangleFeatures() { };
	}
}
