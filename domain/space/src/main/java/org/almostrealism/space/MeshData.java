/*
 * Copyright 2020 Michael Murray
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

package org.almostrealism.space;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.geometry.Intersection;
import org.almostrealism.geometry.Ray;
import org.almostrealism.geometry.computations.RankedChoiceEvaluable;
import org.almostrealism.graph.mesh.TriangleFeatures;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.hardware.Input;
import org.almostrealism.hardware.MemoryData;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * {@link MeshData} is a hardware-accelerated data structure that stores triangle mesh data
 * in a format optimized for ray-mesh intersection computations.
 *
 * <p>This class extends {@link PackedCollection} to store triangle data in a contiguous
 * memory layout suitable for GPU/hardware acceleration. Each triangle is represented as
 * a 4x3 matrix containing:
 * <ul>
 *   <li>Three rows for the three vertex positions</li>
 *   <li>One row for the precomputed face normal</li>
 * </ul>
 *
 * <p>The class provides ray intersection evaluation methods for both single rays and
 * batches. Each ray is tested against the whole mesh in one compiled {@code meshIntersectAt}
 * kernel and the closest hit is selected; the batch methods loop the rays on the host. An
 * optional {@link BoundingVolumeHierarchy} path ({@link #evaluateIntersectionAccelerated})
 * restricts each ray to its candidate triangles while running the same per-triangle kernel.
 *
 * <h3>Usage Example:</h3>
 * <pre>{@code
 * MeshData meshData = new MeshData(triangleCount);
 * // Populate with triangle data from a Mesh
 * Triangle.dataProducer.into(meshData).evaluate(mesh.getMeshPointData());
 *
 * // Evaluate intersection for a single ray
 * Pair result = meshData.evaluateIntersection(rayEvaluable, args);
 * // result.getA() = intersection distance
 * // result.getB() = triangle index
 * }</pre>
 *
 * @author Michael Murray
 * @see Mesh
 * @see Triangle
 * @see CachedMeshIntersectionKernel
 */
public class MeshData extends PackedCollection {
	/** Temporary buffer for per-triangle intersection distances during kernel evaluation. */
	private final PackedCollection distances;

	/** A degenerate (zero-area) triangle used to clear unused candidate-buffer slots. */
	private static final PackedCollection DEGENERATE = new PackedCollection(new TraversalPolicy(4, 3));

	/** Reusable compact triangle buffer holding the current ray's candidates. */
	private MeshData candidateBuffer;

	/** Per-triangle distance buffer sized to the candidate buffer. */
	private PackedCollection candidateDistances;

	/** Maps each candidate-buffer slot to its triangle index in this mesh, or {@code -1} if unused. */
	private int[] candidateGlobal;

	/** The number of candidate-buffer slots currently holding a live triangle. */
	private int candidateActive;

	/** Compiled whole-mesh intersection kernel, built on first use. */
	private Evaluable<PackedCollection> intersection;

	/** Compiled candidate-buffer intersection kernel, rebuilt when the buffer grows. */
	private Evaluable<PackedCollection> candidateIntersection;

	/** Acceleration structure over this mesh's own triangles, built on first use. */
	private BoundingVolumeHierarchy boundingVolumeHierarchy;

	/**
	 * Constructs a new {@link MeshData} instance with capacity for the specified
	 * number of triangles.
	 *
	 * @param triangles the number of triangles this mesh data will hold
	 */
	public MeshData(int triangles) {
		super(new TraversalPolicy(triangles, 4, 3), 1, delegateSpec ->
				new PackedCollection(new TraversalPolicy(4, 3), 1, delegateSpec.getDelegate(), delegateSpec.getOffset()));
		distances = new PackedCollection(new TraversalPolicy(getCount(), 1).traverse(1));
	}

	/**
	 * Tessellates a regular grid height field into {@link MeshData} ready for the ray
	 * intersection path, building the triangle buffer in a single kernel so the surface
	 * can be rebuilt each frame without a host loop.
	 *
	 * <p>Height is the Y axis and the grid lies in the XZ plane; see
	 * {@link TriangleFeatures#regularGridSurfacePoints} for the node layout and winding.
	 * The returned buffer holds {@code 2*(nx-1)*(ny-1)} triangles.</p>
	 *
	 * @param heights  the height field, shaped {@code (nx, ny)} in row-major order
	 * @param nx       the number of grid nodes along X
	 * @param ny       the number of grid nodes along Z
	 * @param origin   the position of node {@code (0, 0)}; its Y is added to every height
	 * @param spacingX the node spacing along X
	 * @param spacingZ the node spacing along Z
	 * @return the tessellated mesh data
	 */
	public static MeshData gridSurface(Producer<PackedCollection> heights, int nx, int ny,
									   Vector origin, double spacingX, double spacingZ) {
		int triangleCount = 2 * (nx - 1) * (ny - 1);
		MeshData data = new MeshData(triangleCount);

		TriangleFeatures tf = TriangleFeatures.getInstance();
		CollectionProducer points = tf.regularGridSurfacePoints(heights, nx, ny,
				origin.getX(), origin.getY(), origin.getZ(), spacingX, spacingZ);
		tf.triangle(points).get().into(data.traverse(1)).evaluate();
		return data;
	}

	/**
	 * Evaluates the intersection of a single ray with all triangles in this mesh,
	 * returning the closest intersection point.
	 *
	 * <p>This method is thread-safe (synchronized) and suitable for single-ray queries.
	 * For batch ray processing, use {@link #evaluateIntersectionKernel} instead.
	 *
	 * @param ray  the ray evaluable to test for intersection
	 * @param args additional arguments passed to the ray evaluable
	 * @return a {@link Pair} where {@code getA()} is the intersection distance
	 *         (or negative if no intersection) and {@code getB()} is the triangle index
	 */
	public synchronized Pair evaluateIntersection(Evaluable<Ray> ray, Object[] args) {
		ensureIntersection();

		MemoryData r = ray.evaluate(args);
		intersection.into(distances).evaluate(this, r);

		PackedCollection conf = Pair.bank(1);
		conf.setFrom(0, new Pair(getCountLong(), Intersection.e), 0, 2);

		PackedCollection out = Pair.bank(1);
		RankedChoiceEvaluable.highestRank.into(out).evaluate(distances, conf);
		PackedCollection result = out.range(shape(2), 0);
		return new Pair(result.toDouble(0), result.toDouble(1));
	}

	/**
	 * Evaluates ray-mesh intersections for multiple rays, storing only the scalar
	 * distance values in the destination collection.
	 *
	 * @param ray         the ray evaluable to test for intersection
	 * @param destination collection to receive scalar intersection distances
	 * @param args        additional arguments passed to the ray evaluable
	 */
	public void evaluateIntersectionKernelScalar(Evaluable<Ray> ray, PackedCollection destination, MemoryData[] args) {
		PackedCollection result = Pair.bank(destination.getCount());
		evaluateIntersectionKernel(ray, result, args);
		for (int i = 0; i < result.getCountLong(); i++) {
			destination.setMem(i, result.toDouble(i * 2));
		}
	}

	/**
	 * Evaluates ray-mesh intersections for multiple rays in batch, storing both
	 * distance and triangle index information.
	 *
	 * <p>Each ray is tested against the whole mesh in one {@code meshIntersectAt} kernel and
	 * the closest hit is selected; the rays are looped on the host.
	 *
	 * @param ray         the ray evaluable to test for intersection
	 * @param destination collection to receive intersection results (distance, triangle index pairs)
	 * @param args        additional arguments passed to the ray evaluable
	 */
	public synchronized void evaluateIntersectionKernel(Evaluable<Ray> ray, PackedCollection destination, MemoryData[] args) {
		ensureIntersection();

		long startTime = System.currentTimeMillis();
		PackedCollection rays = Ray.bank(destination.getCount());
		ray.into(rays).evaluate(args);

		if (HardwareOperator.enableVerboseLog) {
			log("Evaluated ray kernel in " + (System.currentTimeMillis() - startTime) + " msec");
		}

		PackedCollection in = Ray.bank(1);
		PackedCollection out = Pair.bank(1);

		PackedCollection conf = Pair.bank(1);
		conf.setFrom(0, new Pair(getCount(), Intersection.e), 0, 2);

		for (int i = 0; i < rays.getCount(); i++) {
			in.setFrom(0, rays, i * 6, 6);
			intersection.into(distances).evaluate(this, in);
			RankedChoiceEvaluable.highestRank.into(out).evaluate(distances, conf);
			destination.setFrom(i * 2, out, 0, 2);
		}

		if (HardwareOperator.enableVerboseLog)
			log(rays.getCountLong() + " intersection kernels evaluated");
	}

	/**
	 * Builds the whole-mesh intersection kernel on first use.
	 */
	private void ensureIntersection() {
		if (intersection == null) {
			intersection = TriangleFeatures.getInstance().meshIntersectAt(
					Input.value(new TraversalPolicy(getCount(), 4, 3), 0), getCount(),
					Input.value(new TraversalPolicy(false, false, 6), 1)).get();
		}
	}

	/**
	 * Returns a {@link BoundingVolumeHierarchy} over this mesh's own triangles, building it
	 * from the packed triangle data on first use and caching it. The hierarchy indexes this
	 * mesh's triangle rows directly, so its candidate indices map straight back to this mesh;
	 * it accelerates the {@link #evaluateIntersectionAccelerated(Evaluable, Object[])} path.
	 *
	 * @return the acceleration structure for this mesh
	 */
	public synchronized BoundingVolumeHierarchy getBoundingVolumeHierarchy() {
		if (boundingVolumeHierarchy == null) {
			boundingVolumeHierarchy = BoundingVolumeHierarchy.build(triangleVertices());
		}

		return boundingVolumeHierarchy;
	}

	/**
	 * Reconstructs the three vertex positions of every triangle from the packed edge
	 * representation ({@code v1 = jkl}, {@code v2 = jkl + abc}, {@code v3 = jkl + def}),
	 * reading the host copy of this mesh's data.
	 *
	 * @return one three-element vertex array per triangle, in triangle-index order
	 */
	private List<Vector[]> triangleVertices() {
		int count = getCount();
		List<Vector[]> triangles = new ArrayList<>(count);

		for (int i = 0; i < count; i++) {
			int base = i * 12;
			double v1x = toDouble(base + 6);
			double v1y = toDouble(base + 7);
			double v1z = toDouble(base + 8);
			Vector v1 = new Vector(v1x, v1y, v1z);
			Vector v2 = new Vector(v1x + toDouble(base), v1y + toDouble(base + 1), v1z + toDouble(base + 2));
			Vector v3 = new Vector(v1x + toDouble(base + 3), v1y + toDouble(base + 4), v1z + toDouble(base + 5));
			triangles.add(new Vector[] { v1, v2, v3 });
		}

		return triangles;
	}

	/**
	 * Evaluates the closest intersection of a single ray using this mesh's own
	 * {@link BoundingVolumeHierarchy} to restrict the triangle test to the ray's candidates.
	 *
	 * <p>The result is identical to the unaccelerated {@link #evaluateIntersection(Evaluable, Object[])}
	 * linear scan — the same compiled intersection kernel runs over a conservative candidate
	 * set that always contains the truly-closest triangle — but far fewer triangles are tested.
	 * The returned triangle index is relative to this mesh, matching the linear scan.</p>
	 *
	 * @param ray  the ray evaluable to test for intersection
	 * @param args additional arguments passed to the ray evaluable
	 * @return a {@link Pair} of (intersection distance, triangle index), or a negative distance
	 *         with index {@code -1} when the ray misses
	 */
	public synchronized Pair evaluateIntersectionAccelerated(Evaluable<Ray> ray, Object[] args) {
		PackedCollection raySix = Ray.bank(1);
		raySix.setFrom(0, ray.evaluate(args), 0, 6);
		return queryAccelerated(raySix);
	}

	/**
	 * Ensures the reusable candidate buffer can hold at least {@code required} triangles,
	 * reallocating (and so recompiling the intersection kernel once) only when it must grow.
	 *
	 * @param required the number of candidate triangles the current query needs to hold
	 */
	private void ensureCandidateCapacity(int required) {
		if (candidateBuffer != null && candidateBuffer.getCount() >= required) return;

		candidateBuffer = new MeshData(required);
		candidateDistances = new PackedCollection(new TraversalPolicy(required, 1).traverse(1));
		candidateGlobal = new int[required];
		Arrays.fill(candidateGlobal, -1);
		candidateActive = 0;
		candidateIntersection = TriangleFeatures.getInstance().meshIntersectAt(
				Input.value(new TraversalPolicy(required, 4, 3), 0), required,
				Input.value(new TraversalPolicy(false, false, 6), 1)).get();
	}

	/**
	 * Evaluates ray-mesh intersections for multiple rays using this mesh's own
	 * {@link BoundingVolumeHierarchy}, storing (distance, triangle index) pairs in the destination.
	 *
	 * <p>Rays are looped on the host, each restricted to its hierarchy candidates; the per-triangle
	 * math is the same compiled kernel used by the linear scan, so results match it exactly. This
	 * is the accelerated counterpart of {@link #evaluateIntersectionKernel(Evaluable, PackedCollection, MemoryData[])}.</p>
	 *
	 * @param ray         the ray evaluable to test for intersection
	 * @param destination collection to receive (distance, triangle index) pairs
	 * @param args        additional arguments passed to the ray evaluable
	 */
	public synchronized void evaluateIntersectionKernelAccelerated(Evaluable<Ray> ray, PackedCollection destination,
										   MemoryData[] args) {
		PackedCollection rays = Ray.bank(destination.getCount());
		ray.into(rays).evaluate(args);

		PackedCollection raySix = Ray.bank(1);
		for (int i = 0; i < rays.getCount(); i++) {
			raySix.setFrom(0, rays, i * 6, 6);
			destination.setFrom(i * 2, queryAccelerated(raySix), 0, 2);
		}
	}

	/**
	 * Intersects a single ray against this mesh's hierarchy candidates and returns the closest hit.
	 *
	 * @param raySix a one-ray bank holding the ray's six components (origin, direction)
	 * @return a {@link Pair} of (distance, triangle index), or {@code (-1, -1)} on a miss
	 */
	private Pair queryAccelerated(PackedCollection raySix) {
		BoundingVolumeHierarchy bvh = getBoundingVolumeHierarchy();
		int[] candidates = bvh.candidates(raySix.toDouble(0), raySix.toDouble(1), raySix.toDouble(2),
				raySix.toDouble(3), raySix.toDouble(4), raySix.toDouble(5));
		if (candidates.length == 0) return new Pair(-1.0, -1.0);

		ensureCandidateCapacity(candidates.length);
		int capacity = candidateBuffer.getCount();

		for (int i = 0; i < candidates.length; i++) {
			candidateBuffer.setFrom(i * 12, this, candidates[i] * 12, 12);
			candidateGlobal[i] = candidates[i];
		}
		for (int i = candidates.length; i < candidateActive; i++) {
			candidateBuffer.setFrom(i * 12, DEGENERATE, 0, 12);
			candidateGlobal[i] = -1;
		}
		candidateActive = candidates.length;

		candidateIntersection.into(candidateDistances).evaluate(candidateBuffer, raySix);

		PackedCollection conf = Pair.bank(1);
		conf.setFrom(0, new Pair(capacity, Intersection.e), 0, 2);

		PackedCollection out = Pair.bank(1);
		RankedChoiceEvaluable.highestRank.into(out).evaluate(candidateDistances, conf);

		int slot = (int) out.toDouble(1);
		double global = slot < 0 ? -1.0 : candidateGlobal[slot];
		return new Pair(out.toDouble(0), global);
	}
}
