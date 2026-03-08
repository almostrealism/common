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
import org.almostrealism.algebra.Pair;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.geometry.Intersection;
import org.almostrealism.geometry.Ray;
import org.almostrealism.geometry.computations.RankedChoiceEvaluable;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.hardware.MemoryData;

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
 * <p>The class provides efficient batch ray intersection evaluation methods that can
 * operate on multiple rays simultaneously, leveraging hardware parallelism when available.
 * For memory-constrained environments, a partial kernel mode allows processing one ray
 * at a time.
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
	/**
	 * Flag to enable partial kernel mode for memory-constrained environments.
	 * When enabled, rays are processed one at a time instead of in a single batch.
	 * If there is not enough RAM to run the entire kernel at once, this flag
	 * allows processing one {@link Ray} at a time.
	 */
	public static boolean enablePartialKernel = true;

	private final PackedCollection distances;

	/**
	 * Constructs a new {@link MeshData} instance with capacity for the specified
	 * number of triangles.
	 *
	 * @param triangles the number of triangles this mesh data will hold
	 */
	public MeshData(int triangles) {
		super(new TraversalPolicy(triangles, 4, 3), 1, delegateSpec ->
				new PackedCollection(new TraversalPolicy(4, 3), 1, delegateSpec.getDelegate(), delegateSpec.getOffset()));
		distances = new PackedCollection(new TraversalPolicy(getCount(), 1));
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
		PackedCollection in = Ray.bank(1);
		PackedCollection out = Pair.bank(1);

		PackedCollection conf = Pair.bank(1);
		conf.setMem(0, new Pair(getCountLong(), Intersection.e), 0, 2);

		in.setMem(0, ray.evaluate(args), 0, 6);
		Triangle.intersectAt.into(distances).evaluate(in, this);
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
	 * <p>This method supports two modes based on the {@link #enablePartialKernel} flag:
	 * <ul>
	 *   <li>When enabled (default): Rays are processed one at a time, using less memory</li>
	 *   <li>When disabled: All rays are processed in a single kernel call for maximum performance</li>
	 * </ul>
	 *
	 * @param ray         the ray evaluable to test for intersection
	 * @param destination collection to receive intersection results (distance, triangle index pairs)
	 * @param args        additional arguments passed to the ray evaluable
	 */
	public void evaluateIntersectionKernel(Evaluable<Ray> ray, PackedCollection destination, MemoryData[] args) {
		long startTime = System.currentTimeMillis();
		PackedCollection rays = Ray.bank(destination.getCount());
		ray.into(rays).evaluate(args);

		if (HardwareOperator.enableVerboseLog) {
			log("Evaluated ray kernel in " + (System.currentTimeMillis() - startTime) + " msec");
		}

		PackedCollection dim = Pair.bank(1);
		dim.setMem(0, new Pair(this.getCount(), rays.getCount()), 0, 2);

		if (enablePartialKernel) {
			PackedCollection distances = new PackedCollection(new TraversalPolicy(getCount(), 1));
			PackedCollection in = Ray.bank(1);
			PackedCollection out = Pair.bank(1);

			PackedCollection conf = Pair.bank(1);
			conf.setMem(0, new Pair(getCount(), Intersection.e), 0, 2);

			for (int i = 0; i < rays.getCount(); i++) {
				in.setMem(0, rays.range(shape(6), i * 6), 0, 6);
				Triangle.intersectAt.into(distances).evaluate(in, this, dim);
				RankedChoiceEvaluable.highestRank.into(out).evaluate(distances, conf);
				destination.setMem(i * 2, out, 0, 2);
			}

			if (HardwareOperator.enableVerboseLog)
				log(rays.getCountLong() + " intersection kernels evaluated");
		} else {
			PackedCollection distances = new PackedCollection(new TraversalPolicy(this.getCount() * rays.getCount(), 1));

			startTime = System.currentTimeMillis();
			Triangle.intersectAt.into(distances).evaluate(rays, this, dim);
			log("Completed intersection kernel in " +
					(System.currentTimeMillis() - startTime) + " msec");
			// TODO Choose best with highestRank kernel
		}
	}
}
