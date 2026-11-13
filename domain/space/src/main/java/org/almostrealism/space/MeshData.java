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
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.geometry.Ray;
import org.almostrealism.graph.mesh.TriangleFeatures;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.hardware.Input;
import org.almostrealism.hardware.MemoryData;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * {@link MeshData} is a hardware-accelerated data structure that stores triangle mesh data
 * in a format optimized for ray-mesh intersection computations.
 *
 * <p>This class extends {@link PackedCollection} to store triangle data in a contiguous
 * memory layout suitable for GPU/hardware acceleration. Each triangle is represented as
 * a 4x3 matrix containing:
 * <ul>
 *   <li>Two edge vectors and the first vertex position (the Moller-Trumbore form)</li>
 *   <li>One row for the precomputed face normal</li>
 * </ul>
 *
 * <p>Intersection is a Producer graph,
 * {@link TriangleFeatures#closestMeshIntersection(Producer, int, Producer)}: every ray of a
 * batch is tested against every triangle and reduced to its closest hit in one evaluation,
 * with no host loop over rays or triangles. The methods here compile that graph against this
 * mesh (once per ray-batch size) and evaluate it. An optional {@link BoundingVolumeHierarchy}
 * path ({@link #evaluateIntersectionAccelerated}) restricts each ray to its candidate
 * triangles while running the same graph over them.</p>
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
	/** A degenerate (zero-area) triangle used to clear unused candidate-buffer slots. */
	private static final PackedCollection DEGENERATE = new PackedCollection(new TraversalPolicy(4, 3));

	/** Reusable compact triangle buffer holding the current ray's candidates. */
	private MeshData candidateBuffer;

	/** Maps each candidate-buffer slot to its triangle index in this mesh, or {@code -1} if unused. */
	private int[] candidateGlobal;

	/** The number of candidate-buffer slots currently holding a live triangle. */
	private int candidateActive;

	/** Compiled closest-hit graphs over this mesh, keyed by ray-batch size. */
	private final Map<Integer, Evaluable<? extends PackedCollection>> closest = new HashMap<>();

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
	 * Returns this mesh's compiled closest-hit graph for the given ray-batch size, compiling
	 * it on first use with the mesh as argument {@code 0} and the rays as argument {@code 1}.
	 * The graph is optimized before compilation so that its per-ray reductions over the
	 * triangles run as loops of their own rather than being unrolled into the kernel that
	 * assembles the result.
	 *
	 * @param rayCount the number of rays intersected together
	 * @return the compiled closest-hit evaluable, producing {@code (rayCount, 2)} hits
	 */
	private Evaluable<? extends PackedCollection> closestIntersection(int rayCount) {
		return closest.computeIfAbsent(rayCount, n -> {
			int triangleCount = getCount();
			return Process.optimized(TriangleFeatures.getInstance().closestMeshIntersection(
					Input.value(new TraversalPolicy(triangleCount, 4, 3), 0), triangleCount,
					Input.value(new TraversalPolicy(n, 6), 1))).get();
		});
	}

	/**
	 * Intersects a single ray with this mesh using its compiled closest-hit graph.
	 *
	 * @param ray the ray's six components (origin, direction)
	 * @return the {@code (1, 2)} closest hit as (distance, triangle index)
	 */
	private PackedCollection closestHit(PackedCollection ray) {
		return closestIntersection(1).evaluate(this, ray);
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
		PackedCollection hit = closestHit(ray.evaluate(args));
		return new Pair(hit.toDouble(0), hit.toDouble(1));
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
	 * <p>The rays are evaluated into one bank and the whole batch is intersected with the
	 * whole mesh in a single evaluation of the closest-hit graph.</p>
	 *
	 * @param ray         the ray evaluable to test for intersection
	 * @param destination collection to receive intersection results (distance, triangle index pairs)
	 * @param args        additional arguments passed to the ray evaluable
	 */
	public synchronized void evaluateIntersectionKernel(Evaluable<Ray> ray, PackedCollection destination, MemoryData[] args) {
		long startTime = System.currentTimeMillis();
		int count = destination.getCount();
		PackedCollection rays = Ray.bank(count);
		ray.into(rays).evaluate(args);

		closestIntersection(count).into(destination).evaluate(this, rays);

		if (HardwareOperator.enableVerboseLog) {
			log(count + " rays intersected in " + (System.currentTimeMillis() - startTime) + " msec");
		}
	}

	/**
	 * Returns a {@link BoundingVolumeHierarchy} over this mesh's own triangles, building it
	 * on first use from per-triangle bounds computed by a single kernel
	 * ({@link TriangleFeatures#meshTriangleBounds}) and caching it. The hierarchy indexes this
	 * mesh's triangle rows directly, so its candidate indices map straight back to this mesh;
	 * it accelerates the {@link #evaluateIntersectionAccelerated(Evaluable, Object[])} path.
	 *
	 * @return the acceleration structure for this mesh
	 */
	public synchronized BoundingVolumeHierarchy getBoundingVolumeHierarchy() {
		if (boundingVolumeHierarchy == null) {
			TriangleFeatures tf = TriangleFeatures.getInstance();
			boundingVolumeHierarchy = BoundingVolumeHierarchy.build(
					tf.meshTriangleBounds(tf.cp(this), getCount()).evaluate());
		}

		return boundingVolumeHierarchy;
	}

	/**
	 * Evaluates the closest intersection of a single ray using this mesh's own
	 * {@link BoundingVolumeHierarchy} to restrict the triangle test to the ray's candidates.
	 *
	 * <p>The result is identical to the unaccelerated {@link #evaluateIntersection(Evaluable, Object[])}
	 * linear scan — the same closest-hit graph runs over a conservative candidate set that
	 * always contains the truly-closest triangle — but far fewer triangles are tested.
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
	 * reallocating (and so compiling the closest-hit graph for the new size) only when it
	 * must grow.
	 *
	 * @param required the number of candidate triangles the current query needs to hold
	 */
	private void ensureCandidateCapacity(int required) {
		if (candidateBuffer != null && candidateBuffer.getCount() >= required) return;

		candidateBuffer = new MeshData(required);
		candidateGlobal = new int[required];
		Arrays.fill(candidateGlobal, -1);
		candidateActive = 0;
	}

	/**
	 * Evaluates ray-mesh intersections for multiple rays using this mesh's own
	 * {@link BoundingVolumeHierarchy}, storing (distance, triangle index) pairs in the destination.
	 *
	 * <p>Rays are looped on the host, each restricted to its hierarchy candidates; the per-triangle
	 * math is the same closest-hit graph used by the linear scan, so results match it exactly. This
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

		for (int i = 0; i < candidates.length; i++) {
			candidateBuffer.setFrom(i * 12, this, candidates[i] * 12, 12);
			candidateGlobal[i] = candidates[i];
		}
		for (int i = candidates.length; i < candidateActive; i++) {
			candidateBuffer.setFrom(i * 12, DEGENERATE, 0, 12);
			candidateGlobal[i] = -1;
		}
		candidateActive = candidates.length;

		PackedCollection hit = candidateBuffer.closestHit(raySix);

		int slot = (int) hit.toDouble(1);
		double global = slot < 0 ? -1.0 : candidateGlobal[slot];
		return new Pair(hit.toDouble(0), global);
	}
}
