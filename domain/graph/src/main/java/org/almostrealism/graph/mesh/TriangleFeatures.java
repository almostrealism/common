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

import java.util.function.UnaryOperator;

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
	 * Returns the connectivity of the regular grid tessellation produced by
	 * {@link #regularGridSurfacePoints}: for each triangle, in the same order, the flat
	 * indices ({@code i*ny + j}) of its three nodes, so that a vertex list built from
	 * {@link #regularGridSurfaceNodes} describes the same triangles. The first triangle of
	 * each cell spans the nodes {@code {v00, v11, v10}} and the second {@code {v00, v01, v11}}.
	 *
	 * @param nx the number of grid nodes along X
	 * @param ny the number of grid nodes along Z
	 * @return the node indices of each of the {@code 2*(nx-1)*(ny-1)} triangles, three per triangle
	 * @throws IllegalArgumentException if {@code nx} or {@code ny} is less than two
	 */
	default int[][] regularGridSurfaceTriangles(int nx, int ny) {
		requireGridNodes(nx, ny);
		int cellsX = nx - 1;
		int cellsZ = ny - 1;
		int[][] triangles = new int[2 * cellsX * cellsZ][];

		int t = 0;
		for (int i = 0; i < cellsX; i++) {
			for (int j = 0; j < cellsZ; j++) {
				int v00 = i * ny + j;
				int v10 = (i + 1) * ny + j;
				int v01 = i * ny + (j + 1);
				int v11 = (i + 1) * ny + (j + 1);
				triangles[t++] = new int[] { v00, v11, v10 };
				triangles[t++] = new int[] { v00, v01, v11 };
			}
		}
		return triangles;
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
	 * Computes the ray-triangle intersection distance of every ray against every triangle of a
	 * packed mesh in a single kernel, using the Moller-Trumbore algorithm.
	 *
	 * <p>Unlike {@link TriangleIntersectAt}, which tests one triangle, this tests a whole
	 * {@code (T, 4, 3)} mesh (rows {@code abc}, {@code def}, {@code jkl}, normal per triangle)
	 * against a batch of {@code R} rays and returns an {@code (R, T)} grid of distances — the
	 * parametric distance along ray {@code r} to triangle {@code t} where the ray hits the
	 * triangle at a distance of at least {@link Intersection#e}, or {@code -1} where it misses
	 * (including a crossing behind the ray origin). A single six-element ray is a batch of
	 * one. Every (ray, triangle) pair is one element of the kernel, so neither rays nor
	 * triangles are looped on the host; {@link #closestMeshIntersection} reduces this grid to the
	 * closest hit per ray.</p>
	 *
	 * @param mesh          the packed mesh, shaped {@code (T, 4, 3)}
	 * @param triangleCount the number of triangles {@code T}
	 * @param rays          the rays, six elements each (origin then direction), shaped
	 *                      {@code (R, 6)} or {@code (6)} for a single ray
	 * @return producer for the {@code (R, T)} per-ray, per-triangle intersection distances
	 */
	default CollectionProducer meshIntersectAt(Producer<PackedCollection> mesh, int triangleCount,
											   Producer<PackedCollection> rays) {
		return meshIntersectionGrid("meshIntersectAt", mesh, triangleCount, rays, t -> t, e(-1.0));
	}

	/**
	 * Computes the closest intersection of each ray in a batch with a packed mesh, entirely as a
	 * Producer graph: the per-(ray, triangle) Moller-Trumbore test, a per-ray maximum and
	 * index-of-maximum reduction over the triangles, and the assembly of the result.
	 *
	 * <p>The result has shape {@code (R, 2)}: for each ray the distance to the closest triangle
	 * it hits at a distance of at least {@link Intersection#e}, and that triangle's index in the
	 * mesh. A ray that hits nothing reports {@code (-1, -1)}. When several triangles are hit at
	 * exactly the same distance the lowest triangle index is reported. All rays are intersected
	 * in one evaluation, with no host loop over rays or triangles.</p>
	 *
	 * <p>Each (ray, triangle) pair is scored with its negated distance, so the closest hit has
	 * the largest score and the reduction is a maximum; a miss scores the most negative finite
	 * value of the target precision ({@link #minValue()}), which no finite hit distance can fall
	 * below, so a hit at any distance outranks every miss. Whether a ray hit anything is decided
	 * by a separate per-(ray, triangle) hit mask reduced with its own maximum, not by comparing
	 * the best score against a threshold.</p>
	 *
	 * <p>Optimize the result ({@code Process.optimized(...)}) before compiling it: the two
	 * reductions iterate over every triangle, and optimization isolates them into loops of
	 * their own; compiled unoptimized, they are unrolled into the kernel that assembles the
	 * result, which fails for all but the smallest meshes.</p>
	 *
	 * @param mesh          the packed mesh, shaped {@code (T, 4, 3)}
	 * @param triangleCount the number of triangles {@code T}
	 * @param rays          the rays, shaped {@code (R, 6)} or {@code (6)} for a single ray
	 * @return producer for the {@code (R, 2)} (distance, triangle index) closest hits
	 */
	default CollectionProducer closestMeshIntersection(Producer<PackedCollection> mesh, int triangleCount,
													   Producer<PackedCollection> rays) {
		int rayCount = rayCount(rays);
		CollectionProducer scores = meshIntersectionGrid("meshIntersectionScore",
				mesh, triangleCount, rays, Expression::minus, minValue());
		CollectionProducer hitMask = meshIntersectionGrid("meshIntersectionHit",
				mesh, triangleCount, rays, t -> e(1.0), e(0.0));

		CollectionProducer best = max(traverse(1, scores)).reshape(rayCount, 1);
		CollectionProducer index = indexOfMax(traverse(1, scores)).reshape(rayCount, 1);
		CollectionProducer anyHit = max(traverse(1, hitMask)).reshape(rayCount, 1);
		CollectionProducer half = c(0.5);

		return concat(shape(rayCount, 2),
				greaterThan(anyHit, half, best.minus(), c(-1.0)),
				greaterThan(anyHit, half, index, c(-1.0)));
	}

	/**
	 * Gathers, for each closest hit produced by {@link #closestMeshIntersection}, the face normal
	 * of the triangle that was hit, as a single kernel over the hits.
	 *
	 * <p>The result has shape {@code (R, 3)}; a miss (triangle index {@code -1}) yields the zero
	 * vector and never addresses the mesh outside its bounds.</p>
	 *
	 * @param mesh the packed mesh, shaped {@code (T, 4, 3)}
	 * @param hits the closest hits, shaped {@code (R, 2)} as (distance, triangle index)
	 * @return producer for the {@code (R, 3)} face normals of the hit triangles
	 */
	default CollectionProducer meshNormalAt(Producer<PackedCollection> mesh, Producer<PackedCollection> hits) {
		int rayCount = shape(hits).getTotalSize() / 2;
		TraversalPolicy outputShape = new TraversalPolicy(rayCount, 3).traverseEach();
		return new DefaultTraversableExpressionComputation("meshNormalAt", outputShape, args ->
			CollectionExpression.create(outputShape, idx -> {
				Expression triangle = args[2].getValueAt(idx.divide(e(3)).multiply(e(2)).add(e(1)));
				Expression row = triangle.max(e(0.0)).toInt().multiply(e(12)).add(e(9));
				Expression normal = args[1].getValueAt(row.add(idx.imod(3)));
				return triangle.greaterThanOrEqual(e(0.0)).conditional(normal, e(0.0));
			}), mesh, hits);
	}

	/**
	 * Computes the axis-aligned bounding box of every triangle of a packed mesh in a single
	 * kernel, for building a host acceleration structure over the mesh.
	 *
	 * <p>The result has shape {@code (T, 6)}: the minimum X, Y and Z of each triangle's three
	 * vertices followed by the maximum X, Y and Z. The vertices are recovered from the packed
	 * rows as {@code jkl}, {@code jkl + abc} and {@code jkl + def}.</p>
	 *
	 * @param mesh          the packed mesh, shaped {@code (T, 4, 3)}
	 * @param triangleCount the number of triangles {@code T}
	 * @return producer for the {@code (T, 6)} per-triangle bounds
	 */
	default CollectionProducer meshTriangleBounds(Producer<PackedCollection> mesh, int triangleCount) {
		TraversalPolicy outputShape = new TraversalPolicy(triangleCount, 6).traverseEach();
		return new DefaultTraversableExpressionComputation("meshTriangleBounds", outputShape, args ->
			CollectionExpression.create(outputShape, idx -> {
				Expression slot = idx.imod(6);
				Expression axis = slot.imod(3);
				Expression base = idx.divide(e(6)).multiply(e(12));
				Expression abc = args[1].getValueAt(base.add(axis));
				Expression def = args[1].getValueAt(base.add(axis).add(e(3)));
				Expression jkl = args[1].getValueAt(base.add(axis).add(e(6)));

				// Offsets of the three vertices from jkl along this axis are 0, abc and def
				Expression upper = abc.max(def).max(e(0.0));
				Expression lower = abc.minus().max(def.minus()).max(e(0.0)).minus();
				return slot.lessThan(e(3)).conditional(jkl.add(lower), jkl.add(upper));
			}), mesh);
	}

	/**
	 * Returns the number of six-element rays in a ray producer of shape {@code (R, 6)} or {@code (6)}.
	 *
	 * @param rays the rays
	 * @return the number of rays {@code R}
	 */
	private int rayCount(Producer<PackedCollection> rays) {
		return shape(rays).getTotalSize() / 6;
	}

	/**
	 * Builds an {@code (R, T)} grid with one element per (ray, triangle) pair of a packed mesh,
	 * holding a value derived from the Moller-Trumbore distance where the ray hits the triangle
	 * and a fixed value where it misses.
	 *
	 * @param name          the name of the computation
	 * @param mesh          the packed mesh, shaped {@code (T, 4, 3)}
	 * @param triangleCount the number of triangles {@code T}
	 * @param rays          the rays, shaped {@code (R, 6)} or {@code (6)} for a single ray
	 * @param hitValue      maps the hit distance to the element's value
	 * @param miss          the element's value where the ray misses the triangle
	 * @return producer for the {@code (R, T)} grid
	 */
	private CollectionProducer meshIntersectionGrid(String name, Producer<PackedCollection> mesh,
													int triangleCount, Producer<PackedCollection> rays,
													UnaryOperator<Expression> hitValue, Expression miss) {
		TraversalPolicy outputShape = new TraversalPolicy(rayCount(rays), triangleCount).traverseEach();
		return new DefaultTraversableExpressionComputation(name, outputShape, args ->
			CollectionExpression.create(outputShape, idx ->
				mollerTrumbore(args[1], idx.imod(triangleCount), args[2], idx.divide(e(triangleCount)),
						hitValue, miss)),
			mesh, rays);
	}

	/**
	 * Builds the Moller-Trumbore test of one ray against one triangle of a packed mesh. A hit
	 * requires the ray to pass through the triangle at a distance of at least
	 * {@link Intersection#e}, so a crossing behind the ray origin is a miss.
	 *
	 * @param mesh     the packed mesh, {@code (T, 4, 3)}
	 * @param triangle the index of the triangle
	 * @param rays     the rays, six elements each
	 * @param ray      the index of the ray
	 * @param hitValue maps the hit distance to the value of the expression on a hit
	 * @param miss     the value of the expression on a miss
	 * @return the expression for the hit or miss value
	 */
	private Expression mollerTrumbore(TraversableExpression mesh, Expression triangle,
									  TraversableExpression rays, Expression ray,
									  UnaryOperator<Expression> hitValue, Expression miss) {
		double eps = Intersection.e;
		Expression base = triangle.multiply(e(12));
		Expression abc0 = mesh.getValueAt(base);
		Expression abc1 = mesh.getValueAt(base.add(e(1)));
		Expression abc2 = mesh.getValueAt(base.add(e(2)));
		Expression def0 = mesh.getValueAt(base.add(e(3)));
		Expression def1 = mesh.getValueAt(base.add(e(4)));
		Expression def2 = mesh.getValueAt(base.add(e(5)));
		Expression jkl0 = mesh.getValueAt(base.add(e(6)));
		Expression jkl1 = mesh.getValueAt(base.add(e(7)));
		Expression jkl2 = mesh.getValueAt(base.add(e(8)));

		Expression r = ray.multiply(e(6));
		Expression ox = rays.getValueAt(r);
		Expression oy = rays.getValueAt(r.add(e(1)));
		Expression oz = rays.getValueAt(r.add(e(2)));
		Expression dx = rays.getValueAt(r.add(e(3)));
		Expression dy = rays.getValueAt(r.add(e(4)));
		Expression dz = rays.getValueAt(r.add(e(5)));

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

		Expression hit = bary.and(t.greaterThanOrEqual(e(eps))).conditional(hitValue.apply(t), miss);
		return f.greaterThan(e(eps)).conditional(hit,
				f.lessThan(e(-eps)).conditional(hit, miss));
	}

	/**
	 * Computes the node positions of a regular grid height field as a single Producer graph.
	 *
	 * <p>The result has shape {@code (nx*ny, 3)}: node {@code (i, j)}, at flat index
	 * {@code i*ny + j}, sits at {@code (originX + i*spacingX, originY + h, originZ + j*spacingZ)},
	 * where {@code h} is {@code heights[i*ny + j]}. This is the same node layout that
	 * {@link #regularGridSurfacePoints} tessellates.</p>
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
		TraversalPolicy outputShape = new TraversalPolicy(false, false, nx * ny, 3).traverse(1);

		return new DefaultTraversableExpressionComputation("regularGridSurfaceNodes", outputShape, args ->
			CollectionExpression.create(outputShape, idx -> {
				// Integer arithmetic only; see regularGridSurfacePoints on why not conditionals
				Expression node = idx.divide(e(3));
				Expression comp = idx.imod(3);
				Expression x = e(originX).add(node.divide(e(ny)).multiply(e(spacingX)));
				Expression z = e(originZ).add(node.imod(ny).multiply(e(spacingZ)));
				Expression y = e(originY).add(args[1].getValueAt(node));
				return conditional(comp.eq(e(0)), x, conditional(comp.eq(e(2)), z, y));
			}), heights);
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
