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
import io.almostrealism.collect.TraversableExpression;
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
			// Input is (N, 3), output should be (N, 4, 3), one kernel thread per triangle
			int batchSize = inputShape.length(0);
			outputShape = new TraversalPolicy(false, false, batchSize, 4, 3).traverse(1);
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
	 * <p>The cell, triangle, corner and node indices are non-negative integers and are
	 * derived with integer arithmetic only: integer division (which already truncates)
	 * and closed-form corner offsets rather than conditionals. A conditional whose
	 * branches end up with different integer types (as happens once the kernel compiler
	 * hoists one branch into a 64-bit variable) is typed as floating-point, which would
	 * make the node index floating-point and break the integer index arithmetic used to
	 * read {@code heights} for some grid sizes.</p>
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
	 * @throws IllegalArgumentException if {@code nx} or {@code ny} is less than two
	 */
	default CollectionProducer regularGridSurfacePoints(
			Producer<PackedCollection> heights, int nx, int ny,
			double originX, double originY, double originZ,
			double spacingX, double spacingZ) {
		requireGridNodes(nx, ny);
		int cellsX = nx - 1;
		int cellsZ = ny - 1;
		int triangleCount = 2 * cellsX * cellsZ;
		TraversalPolicy outputShape = new TraversalPolicy(false, false, triangleCount, 3, 3).traverse(1);

		return new DefaultTraversableExpressionComputation("regularGridSurfacePoints", outputShape, args ->
			CollectionExpression.create(outputShape, idx -> {
				Expression comp = idx.imod(3);
				Expression corner = idx.divide(e(3)).imod(3);
				Expression tri = idx.divide(e(9));
				Expression cell = tri.divide(e(2));
				Expression sub = tri.imod(2);
				Expression ci = cell.divide(e(cellsZ));
				Expression cj = cell.imod(cellsZ);

				// Corner offsets {(0,0) (1,1) (1,0)} for sub 0 and {(0,0) (0,1) (1,1)} for sub 1
				Expression di = corner.add(e(1)).subtract(sub).divide(e(2));
				Expression dj = corner.multiply(e(2).subtract(corner)).add(sub.multiply(corner.divide(e(2))));

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
	 * Computes the node positions of a regular grid height field as a single Producer graph,
	 * so the host grid-surface geometry is a genuine tensor built by the same kernelised
	 * arithmetic as the device tessellation rather than a hand-written host loop over
	 * {@link org.almostrealism.algebra.Vector} objects.
	 *
	 * <p>The surface spans {@code nx} by {@code ny} nodes. Node {@code (i, j)} — flattened in
	 * row-major order as {@code i*ny + j} — sits at {@code (originX + i*spacingX, originY + h,
	 * originZ + j*spacingZ)}, where {@code h} is {@code heights[i*ny + j]}; height is the Y axis
	 * and the grid lies in the XZ plane, matching {@link #regularGridSurfacePoints} and
	 * {@link #regularGridSurfaceNormals}. The result has shape {@code (nx*ny, 3)} — one position
	 * per unique node — and a single {@link org.almostrealism.algebra.Vector} can be read back
	 * from it with {@link org.almostrealism.collect.PackedCollection#get(int)} or
	 * {@link org.almostrealism.collect.PackedCollection#get(int, TraversalPolicy)} where host
	 * geometry is needed.</p>
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
	 * @return producer for the {@code (nx*ny, 3)} node positions
	 * @throws IllegalArgumentException if {@code nx} or {@code ny} is less than two
	 */
	default CollectionProducer regularGridSurfaceNodes(
			Producer<PackedCollection> heights, int nx, int ny,
			double originX, double originY, double originZ,
			double spacingX, double spacingZ) {
		requireGridNodes(nx, ny);
		int nodeCount = nx * ny;
		TraversalPolicy outputShape = new TraversalPolicy(false, false, nodeCount, 3).traverse(1);

		return new DefaultTraversableExpressionComputation("regularGridSurfaceNodes", outputShape, args ->
			CollectionExpression.create(outputShape, idx -> {
				// Integer arithmetic only; see regularGridSurfacePoints on why not conditionals
				Expression comp = idx.imod(3);
				Expression node = idx.divide(e(3));
				Expression ni = node.divide(e(ny));
				Expression nj = node.imod(ny);

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
	 * @throws IllegalArgumentException if {@code nx} or {@code ny} is less than two
	 */
	default CollectionProducer regularGridSurfaceNormals(
			Producer<PackedCollection> heights, int nx, int ny,
			double spacingX, double spacingZ) {
		requireGridNodes(nx, ny);
		int nodeCount = nx * ny;
		TraversalPolicy outputShape = new TraversalPolicy(false, false, nodeCount, 3).traverse(1);

		CollectionProducer unnormalized = new DefaultTraversableExpressionComputation(
				"regularGridSurfaceNormalVectors", outputShape, args ->
			CollectionExpression.create(outputShape, idx -> {
				// Integer arithmetic only; see regularGridSurfacePoints on why not conditionals
				Expression node = idx.divide(e(3));
				Expression comp = idx.imod(3);
				Expression ni = node.divide(e(ny));
				Expression nj = node.imod(ny);

				Expression niPlus = clampedNeighbor(ni, nx, true);
				Expression niMinus = clampedNeighbor(ni, nx, false);
				Expression njPlus = clampedNeighbor(nj, ny, true);
				Expression njMinus = clampedNeighbor(nj, ny, false);

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
	 * Returns the neighbouring node index of {@code i} along an axis of {@code n} nodes,
	 * clamped to the grid edge, as integer arithmetic: {@code min(i + 1, n - 1)} when
	 * {@code next}, otherwise {@code max(i - 1, 0)}. For {@code 0 <= i <= n - 1} the
	 * quotients below are each {@code 0} or {@code 1}, so no conditional is needed.
	 *
	 * @param i    the node index along the axis, in {@code [0, n)}
	 * @param n    the number of nodes along the axis, at least two
	 * @param next {@code true} for the following node, {@code false} for the preceding one
	 * @return the clamped neighbouring index
	 */
	private Expression clampedNeighbor(Expression i, int n, boolean next) {
		if (next) {
			return i.add(e(1)).subtract(i.divide(e(n - 1)));
		}

		return i.subtract(e(1)).add(e(n - 1).subtract(i).divide(e(n - 1)));
	}

	/**
	 * Validates that a regular grid has at least two nodes along each axis, the minimum
	 * that forms a cell.
	 *
	 * @param nx the number of grid nodes along X
	 * @param ny the number of grid nodes along Z
	 * @throws IllegalArgumentException if {@code nx} or {@code ny} is less than two
	 */
	private static void requireGridNodes(int nx, int ny) {
		if (nx < 2 || ny < 2) {
			throw new IllegalArgumentException(
					"A regular grid needs at least 2 nodes per axis, got " + nx + " x " + ny);
		}
	}

	/**
	 * Builds the Moller-Trumbore ray-triangle intersection distance as an {@link Expression},
	 * shared by the single-ray ({@link #meshIntersectAt}) and batched ({@link #meshIntersectAll})
	 * mesh kernels so the two cannot drift apart.
	 *
	 * <p>The result is the parametric distance {@code t} along the ray at which it meets the
	 * triangle when the barycentric coordinates lie inside it ({@code u >= 0}, {@code v >= 0},
	 * {@code u + v <= 1}) and the ray is not near-parallel to the triangle plane
	 * ({@code |f| > eps}); otherwise {@code -1}. The determinant guard is two-sided, matching
	 * {@link TriangleIntersectAt}; a distance behind the ray ({@code t < 0}) is returned as-is and
	 * excluded downstream by the closest-positive-hit selection.</p>
	 *
	 * @param tri the nine triangle components in {@code (abc, def, jkl)} order: edge {@code v2-v1},
	 *            edge {@code v3-v1}, then vertex {@code v1}, three components each
	 * @param ray the six ray components in {@code (origin, direction)} order, three components each
	 * @param eps the near-parallel determinant threshold ({@link Intersection#e})
	 * @return the hit-distance expression, {@code -1} on a miss
	 */
	private Expression mollerTrumboreDistance(Expression[] tri, Expression[] ray, double eps) {
		Expression abc0 = tri[0], abc1 = tri[1], abc2 = tri[2];
		Expression def0 = tri[3], def1 = tri[4], def2 = tri[5];
		Expression jkl0 = tri[6], jkl1 = tri[7], jkl2 = tri[8];
		Expression ox = ray[0], oy = ray[1], oz = ray[2];
		Expression dx = ray[3], dy = ray[4], dz = ray[5];

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
	}

	/**
	 * Reads the nine triangle components {@code (abc, def, jkl)} of triangle {@code triangleIndex}
	 * from a packed {@code (T, 4, 3)} mesh argument, as the input to {@link #mollerTrumboreDistance}.
	 *
	 * @param mesh          the packed-mesh traversable argument
	 * @param triangleIndex the triangle's base index expression
	 * @return the nine triangle component expressions
	 */
	private Expression[] triangleComponents(TraversableExpression mesh, Expression triangleIndex) {
		Expression base = triangleIndex.multiply(e(12));
		return new Expression[] {
				mesh.getValueAt(base), mesh.getValueAt(base.add(e(1))), mesh.getValueAt(base.add(e(2))),
				mesh.getValueAt(base.add(e(3))), mesh.getValueAt(base.add(e(4))), mesh.getValueAt(base.add(e(5))),
				mesh.getValueAt(base.add(e(6))), mesh.getValueAt(base.add(e(7))), mesh.getValueAt(base.add(e(8))) };
	}

	/**
	 * Reads the six components {@code (origin, direction)} of ray {@code rayIndex} from a packed
	 * ray argument, as the input to {@link #mollerTrumboreDistance}.
	 *
	 * @param rays     the packed-ray traversable argument
	 * @param rayIndex the ray's base index expression
	 * @return the six ray component expressions
	 */
	private Expression[] rayComponents(TraversableExpression rays, Expression rayIndex) {
		Expression base = rayIndex.multiply(e(6));
		return new Expression[] {
				rays.getValueAt(base), rays.getValueAt(base.add(e(1))), rays.getValueAt(base.add(e(2))),
				rays.getValueAt(base.add(e(3))), rays.getValueAt(base.add(e(4))), rays.getValueAt(base.add(e(5))) };
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
		TraversalPolicy outputShape = new TraversalPolicy(triangleCount, 1).traverse(1);
		double eps = Intersection.e;

		return new DefaultTraversableExpressionComputation("meshIntersectAt", outputShape, args ->
			CollectionExpression.create(outputShape, idx ->
				mollerTrumboreDistance(
						triangleComponents(args[1], idx),
						rayComponents(args[2], e(0)), eps)),
			mesh, ray);
	}

	/**
	 * Computes the ray-triangle intersection distance for every (ray, triangle) pair of a packed
	 * mesh and a batch of rays in a single kernel, using the Moller-Trumbore algorithm.
	 *
	 * <p>This is the batched counterpart of {@link #meshIntersectAt}: where that tests one ray
	 * against a {@code (T, 4, 3)} mesh, this tests {@code R} rays at once and returns a
	 * {@code (R, T)} matrix of parametric distances — entry {@code (r, t)} is the distance along
	 * ray {@code r} at which it meets triangle {@code t}, or {@code -1} where it misses or the
	 * triangle is behind the ray. The whole {@code R*T} grid is one kernel dispatch, so a batch
	 * of rays (every pixel of a render, say) is intersected against the mesh without a host loop
	 * over rays. The closest hit per ray is then a reduction over the triangle axis (the
	 * {@code (R, T)} matrix traversed at axis 1): the least positive distance and its triangle
	 * index.</p>
	 *
	 * <p>The determinant guard is two-sided ({@code |f| > eps}), matching {@link #meshIntersectAt}
	 * and {@link TriangleIntersectAt}, so a batched query reproduces the per-ray scan exactly.</p>
	 *
	 * @param rays          the rays, shaped {@code (R, 6)} (origin then direction per ray)
	 * @param rayCount      the number of rays {@code R}
	 * @param mesh          the packed mesh, shaped {@code (T, 4, 3)}
	 * @param triangleCount the number of triangles {@code T}
	 * @return producer for the {@code (R, T)} per-(ray, triangle) intersection distances
	 */
	default CollectionProducer meshIntersectAll(Producer<PackedCollection> rays, int rayCount,
												Producer<PackedCollection> mesh, int triangleCount) {
		TraversalPolicy outputShape = new TraversalPolicy(rayCount, triangleCount).traverse(1);
		double eps = Intersection.e;

		return new DefaultTraversableExpressionComputation("meshIntersectAll", outputShape, args ->
			CollectionExpression.create(outputShape, idx ->
				mollerTrumboreDistance(
						triangleComponents(args[2], idx.imod(e(triangleCount))),
						rayComponents(args[1], idx.divide(e(triangleCount))), eps)),
			rays, mesh);
	}

	/**
	 * Computes the closest ray-triangle intersection for every ray of a batch against a packed
	 * mesh, entirely on-device, as a single Producer graph with no host loop over rays.
	 *
	 * <p>Building on {@link #meshIntersectAll} (which produces the {@code (R, T)} per-(ray,
	 * triangle) distances in one kernel), the closest hit per ray is found by a reduction over
	 * the triangle axis rather than a host-side scan: each hit at distance {@code t} is ranked
	 * as {@code -t} (so the nearest hit is the largest rank) and a miss ranks below every hit, so
	 * the per-row maximum rank is the nearest hit and its {@link #indexOfMax index} is the hit
	 * triangle. The result is an {@code (R, 2)} collection, one {@code (distance, triangleIndex)}
	 * pair per ray — the same contract as the per-ray {@code evaluateIntersection} closest-hit
	 * selection — with {@code (-1, -1)} for a ray that misses the whole mesh. Ties on distance
	 * resolve to the lowest triangle index (the first maximum {@code indexOfMax} keeps), matching
	 * the linear scan.</p>
	 *
	 * @param rays          the rays, shaped {@code (R, 6)} (origin then direction per ray)
	 * @param rayCount      the number of rays {@code R}
	 * @param mesh          the packed mesh, shaped {@code (T, 4, 3)}
	 * @param triangleCount the number of triangles {@code T}
	 * @return producer for the {@code (R, 2)} closest {@code (distance, triangleIndex)} per ray
	 */
	default CollectionProducer meshClosestHit(Producer<PackedCollection> rays, int rayCount,
											  Producer<PackedCollection> mesh, int triangleCount) {
		double eps = Intersection.e;
		double miss = -1.0e30;

		CollectionProducer distances = meshIntersectAll(rays, rayCount, mesh, triangleCount);

		// Rank each entry: a hit at distance t ranks as -t (nearest hit = largest rank);
		// a miss (or behind-ray) ranks below every hit at the miss sentinel.
		CollectionProducer rank = greaterThan(distances, c(eps), distances.minus(), c(miss), true);

		CollectionProducer bestRank = rank.max(1).reshape(rayCount, 1);
		CollectionProducer bestIndex = rank.traverse(1).indexOfMax().reshape(rayCount, 1);

		// A row whose best rank is still the miss sentinel saw no hit: report (-1, -1).
		CollectionProducer distance = greaterThan(bestRank, c(miss / 2.0), bestRank.minus(), c(-1.0));
		CollectionProducer index = greaterThan(bestRank, c(miss / 2.0), bestIndex, c(-1.0));

		return concat(1, distance, index);
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
