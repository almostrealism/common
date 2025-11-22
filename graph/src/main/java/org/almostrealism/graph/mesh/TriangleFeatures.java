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
import io.almostrealism.collect.IndexProjectionExpression;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorFeatures;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.CollectionProducerComputationBase;
import org.almostrealism.collect.computations.DefaultTraversableExpressionComputation;

import java.util.function.Function;
import java.util.function.Supplier;

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
 * Producer<Vector> p1 = tf.vector(0, 0, 0);
 * Producer<Vector> p2 = tf.vector(1, 0, 0);
 * Producer<Vector> p3 = tf.vector(0, 1, 0);
 * CollectionProducer<PackedCollection<Vector>> triangle = tf.triangle(p1, p2, p3);
 *
 * // Access triangle components
 * CollectionProducer<Vector> edge1 = tf.abc(triangleData);
 * CollectionProducer<Vector> normal = tf.normal(triangleData);
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
	default CollectionProducer<Vector> abc(Producer<PackedCollection<?>> t) {
		return vector(t, 0);
	}

	/**
	 * Extracts the second edge vector (def) from a triangle.
	 *
	 * @param t the triangle data producer
	 * @return producer for the second edge vector (vertex3 - vertex1)
	 */
	default CollectionProducer<Vector> def(Producer<PackedCollection<?>> t) {
		return vector(t, 1);
	}

	/**
	 * Extracts the position vector (jkl) from a triangle.
	 *
	 * @param t the triangle data producer
	 * @return producer for the position vector (vertex1)
	 */
	default CollectionProducer<Vector> jkl(Producer<PackedCollection<?>> t) {
		return vector(t, 2);
	}

	/**
	 * Extracts the normal vector from a triangle.
	 *
	 * @param t the triangle data producer
	 * @return producer for the unit normal vector
	 */
	default CollectionProducer<Vector> normal(Producer<PackedCollection<?>> t) {
		return vector(t, 4);
	}

	/**
	 * Creates a triangle from a collection of three points.
	 *
	 * @param points supplier for a packed collection containing 3 vertices (9 floats)
	 * @return producer for the triangle data structure
	 */
	default CollectionProducer<PackedCollection<Vector>> triangle(Supplier<Evaluable<? extends PackedCollection<?>>> points) {
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
	default CollectionProducer<PackedCollection<Vector>> triangle(Producer<Vector> p1,
																	 Producer<Vector> p2,
																	 Producer<Vector> p3) {
		Producer<Vector> abc = subtract(p2, p1);
		Producer<Vector> def = subtract(p3, p1);
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
	default CollectionProducer<PackedCollection<Vector>> triangle(Producer<Vector> abc, Producer<Vector> def,
																  Producer<Vector> jkl, Producer<Vector> normal) {
		return concat(shape(4, 3),
				reshape(shape(1, 3), abc),
				reshape(shape(1, 3), def),
				reshape(shape(1, 3), jkl),
				reshape(shape(1, 3), normal));
	}

	/**
	 * Extracts a single point from a packed collection of vertices.
	 *
	 * @param points supplier for the vertex collection
	 * @param index  the vertex index (0, 1, or 2)
	 * @return producer for the extracted point as a Vector
	 */
	default CollectionProducerComputationBase<Vector, Vector> point(Supplier<Evaluable<? extends PackedCollection<?>>> points, int index) {
		return new DefaultTraversableExpressionComputation<>("point", shape(3),
				(Function<TraversableExpression[], CollectionExpression>) args ->
						new IndexProjectionExpression(shape(3),
							idx -> e(index * 3).add(idx.imod(3)), args[1]),
						(Producer) points)
				.setPostprocessor(Vector.postprocessor());
	}

	/**
	 * Packs three vertex producers into a single 3x3 collection.
	 *
	 * @param p1 producer for the first vertex
	 * @param p2 producer for the second vertex
	 * @param p3 producer for the third vertex
	 * @return producer for the packed vertex collection
	 */
	default CollectionProducer<PackedCollection<Vector>> points(Producer<Vector> p1,
																Producer<Vector> p2,
																Producer<Vector> p3) {
		return concat(shape(3, 3), (Producer) p1, (Producer) p2, (Producer)  p3);
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
