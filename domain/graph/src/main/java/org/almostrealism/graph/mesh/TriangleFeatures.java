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
import org.almostrealism.geometry.Intersection;

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
 * <p>Like all {@code Features} interfaces, this is a mixin: a type that needs these
 * operations should <em>implement</em> this interface (the methods are stateless
 * {@code default} methods) rather than accept or hold a {@code Features} instance —
 * passing one around as an object defeats the purpose of the pattern.</p>
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
	 * Tessellates a regular grid height field into the triangle-corner buffer that a
	 * mesh intersection path expects, as a single Producer graph (one kernel), so a
	 * per-frame surface can be rebuilt without a host loop.
	 *
	 * <p>The surface spans {@code nx} by {@code ny} nodes. Node {@code (i, j)} sits at
	 * {@code (originX + i*spacingX, originY + h, originZ + j*spacingZ)}, where {@code h}
	 * is {@code heights[i*ny + j]} — height is the Y axis, the grid lies in the XZ plane.
	 * Each of the {@code (nx-1)} by {@code (ny-1)} cells becomes two triangles with a
	 * consistent winding whose face normals point towards {@code +Y}: the first spans the
	 * cell-relative node offsets {@code (0,0) (1,1) (1,0)}, the second {@code (0,0) (0,1)
	 * (1,1)}. The result has shape {@code (2*(nx-1)*(ny-1), 3, 3)} — the three corner
	 * positions of each triangle — suitable as input to {@link #triangle(Producer)}.</p>
	 *
	 * @param heights  the height field, shaped {@code (nx, ny)} (or any shape with
	 *                 {@code nx*ny} elements in row-major order)
	 * @param nx       the number of grid nodes along X
	 * @param ny       the number of grid nodes along Z
	 * @param originX  the X coordinate of node {@code (0, 0)}
	 * @param originY  the base height added to every node's height value
	 * @param originZ  the Z coordinate of node {@code (0, 0)}
	 * @param spacingX the node spacing along X
	 * @param spacingZ the node spacing along Z
	 * @return producer for the {@code (T, 3, 3)} triangle-corner buffer
	 */
	default CollectionProducer regularGridSurfacePoints(
			Producer<PackedCollection> heights, int nx, int ny,
			double originX, double originY, double originZ,
			double spacingX, double spacingZ) {
		int cellsX = nx - 1;
		int cellsZ = ny - 1;
		int triangleCount = 2 * cellsX * cellsZ;
		TraversalPolicy outputShape = new TraversalPolicy(false, false, triangleCount, 3, 3);

		return new DefaultTraversableExpressionComputation("regularGridSurfacePoints", outputShape, args ->
			CollectionExpression.create(outputShape, idx -> {
				Expression comp = idx.imod(3);
				Expression corner = idx.divide(e(3)).floor().imod(3);
				Expression tri = idx.divide(e(9)).floor();
				Expression cell = tri.divide(e(2)).floor();
				Expression sub = tri.imod(2);
				Expression ci = cell.divide(e(cellsZ)).floor();
				Expression cj = cell.imod(cellsZ);

				Expression cornerAtLeastOne = conditional(corner.eq(e(0)), e(0), e(1));
				Expression di = conditional(sub.eq(e(0)), cornerAtLeastOne, corner.divide(e(2)).floor());
				Expression dj = conditional(sub.eq(e(0)),
						conditional(corner.eq(e(1)), e(1), e(0)), cornerAtLeastOne);

				Expression ni = ci.add(di);
				Expression nj = cj.add(dj);
				Expression node = ni.multiply(e(ny)).add(nj);

				Expression x = e(originX).add(ni.multiply(e(spacingX)));
				Expression z = e(originZ).add(nj.multiply(e(spacingZ)));
				Expression y = e(originY).add(args[1].getValueAt(node));

				return conditional(comp.eq(e(0)), x, conditional(comp.eq(e(2)), z, y));
			}), heights);
	}

	/**
	 * Computes a per-vertex unit normal for every node of a regular grid height field,
	 * derived from the height gradient, as a single Producer graph.
	 *
	 * <p>For the surface {@code y = h(x, z)} the outward (upward) normal is
	 * {@code normalize(-dh/dx, 1, -dh/dz)}. The gradient is estimated with a central
	 * difference in the grid interior and a one-sided difference at the edges (the span
	 * covers two cell widths in the interior, one at an edge). The result has shape
	 * {@code (nx*ny, 3)}, one unit normal per node in the same row-major order as
	 * {@code heights}.</p>
	 *
	 * @param heights  the height field, shaped {@code (nx, ny)}
	 * @param nx       the number of grid nodes along X
	 * @param ny       the number of grid nodes along Z
	 * @param spacingX the node spacing along X
	 * @param spacingZ the node spacing along Z
	 * @return producer for the {@code (nx*ny, 3)} per-vertex unit normals
	 */
	default CollectionProducer regularGridSurfaceNormals(
			Producer<PackedCollection> heights, int nx, int ny,
			double spacingX, double spacingZ) {
		int nodeCount = nx * ny;
		TraversalPolicy outputShape = new TraversalPolicy(false, false, nodeCount, 3);

		CollectionProducer unnormalized = new DefaultTraversableExpressionComputation(
				"regularGridSurfaceNormalVectors", outputShape, args ->
			CollectionExpression.create(outputShape, idx -> {
				Expression node = idx.divide(e(3)).floor();
				Expression comp = idx.imod(3);
				Expression ni = node.divide(e(ny)).floor();
				Expression nj = node.imod(ny);

				Expression niPlus = conditional(ni.eq(e(nx - 1)), ni, ni.add(e(1)));
				Expression niMinus = conditional(ni.eq(e(0)), ni, ni.subtract(e(1)));
				Expression njPlus = conditional(nj.eq(e(ny - 1)), nj, nj.add(e(1)));
				Expression njMinus = conditional(nj.eq(e(0)), nj, nj.subtract(e(1)));

				Expression hxPlus = args[1].getValueAt(niPlus.multiply(e(ny)).add(nj));
				Expression hxMinus = args[1].getValueAt(niMinus.multiply(e(ny)).add(nj));
				Expression hzPlus = args[1].getValueAt(ni.multiply(e(ny)).add(njPlus));
				Expression hzMinus = args[1].getValueAt(ni.multiply(e(ny)).add(njMinus));

				Expression spanX = niPlus.subtract(niMinus).multiply(e(spacingX));
				Expression spanZ = njPlus.subtract(njMinus).multiply(e(spacingZ));
				Expression gradX = hxPlus.subtract(hxMinus).divide(spanX);
				Expression gradZ = hzPlus.subtract(hzMinus).divide(spanZ);

				return conditional(comp.eq(e(0)), gradX.minus(),
						conditional(comp.eq(e(2)), gradZ.minus(), e(1.0)));
			}), heights);

		return normalize(unnormalized);
	}

	/**
	 * Computes the ray-triangle intersection distance for every triangle of a packed mesh in
	 * a single kernel, using the Moller-Trumbore algorithm.
	 *
	 * <p>Unlike {@link TriangleIntersectAt}, which tests one triangle, this tests a whole
	 * {@code (T, 4, 3)} mesh (rows {@code abc}, {@code def}, {@code jkl}, normal per triangle)
	 * against a single ray and returns a {@code (T, 1)} column of distances — the parametric
	 * distance along the ray for each triangle it hits, or {@code -1} where it misses. The
	 * closest hit is then selected with {@code RankedChoiceEvaluable.highestRank}. The
	 * computation is kernelised over the triangle count, so the whole mesh is tested in one
	 * dispatch rather than a host loop over triangles.</p>
	 *
	 * @param mesh          the packed mesh, shaped {@code (T, 4, 3)}
	 * @param triangleCount the number of triangles {@code T}
	 * @param ray           the ray, a six-element vector of origin then direction
	 * @return producer for the {@code (T, 1)} per-triangle intersection distances
	 */
	default CollectionProducer meshIntersectAt(Producer<PackedCollection> mesh, int triangleCount,
											   Producer<PackedCollection> ray) {
		TraversalPolicy outputShape = new TraversalPolicy(triangleCount, 1);
		double eps = Intersection.e;

		return new DefaultTraversableExpressionComputation("meshIntersectAt", outputShape, args ->
			CollectionExpression.create(outputShape, idx -> {
				Expression base = idx.multiply(e(12));
				Expression abc0 = args[1].getValueAt(base);
				Expression abc1 = args[1].getValueAt(base.add(e(1)));
				Expression abc2 = args[1].getValueAt(base.add(e(2)));
				Expression def0 = args[1].getValueAt(base.add(e(3)));
				Expression def1 = args[1].getValueAt(base.add(e(4)));
				Expression def2 = args[1].getValueAt(base.add(e(5)));
				Expression jkl0 = args[1].getValueAt(base.add(e(6)));
				Expression jkl1 = args[1].getValueAt(base.add(e(7)));
				Expression jkl2 = args[1].getValueAt(base.add(e(8)));

				Expression ox = args[2].getValueAt(e(0));
				Expression oy = args[2].getValueAt(e(1));
				Expression oz = args[2].getValueAt(e(2));
				Expression dx = args[2].getValueAt(e(3));
				Expression dy = args[2].getValueAt(e(4));
				Expression dz = args[2].getValueAt(e(5));

				Expression h0 = dy.multiply(def2).subtract(dz.multiply(def1));
				Expression h1 = dz.multiply(def0).subtract(dx.multiply(def2));
				Expression h2 = dx.multiply(def1).subtract(dy.multiply(def0));
				Expression f = abc0.multiply(h0).add(abc1.multiply(h1)).add(abc2.multiply(h2));

				Expression s0 = ox.subtract(jkl0);
				Expression s1 = oy.subtract(jkl1);
				Expression s2 = oz.subtract(jkl2);
				Expression u = s0.multiply(h0).add(s1.multiply(h1)).add(s2.multiply(h2)).divide(f);

				Expression q0 = s1.multiply(abc2).subtract(s2.multiply(abc1));
				Expression q1 = s2.multiply(abc0).subtract(s0.multiply(abc2));
				Expression q2 = s0.multiply(abc1).subtract(s1.multiply(abc0));
				Expression v = dx.multiply(q0).add(dy.multiply(q1)).add(dz.multiply(q2)).divide(f);
				Expression t = def0.multiply(q0).add(def1.multiply(q1)).add(def2.multiply(q2)).divide(f);

				Expression bary = u.greaterThanOrEqual(e(0.0))
						.and(u.lessThanOrEqual(e(1.0)))
						.and(v.greaterThanOrEqual(e(0.0)))
						.and(u.add(v).lessThanOrEqual(e(1.0)));
				Expression hit = bary.conditional(t, e(-1.0));

				return f.greaterThan(e(eps)).conditional(hit,
						f.lessThan(e(-eps)).conditional(hit, e(-1.0)));
			}), mesh, ray);
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
