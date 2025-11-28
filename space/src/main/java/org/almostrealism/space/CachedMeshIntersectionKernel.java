/*
 * Copyright 2024 Michael Murray
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

package org.almostrealism.space;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.ZeroVector;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.geometry.Ray;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.geometry.DimensionAware;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.computations.HardwareEvaluable;
import org.almostrealism.hardware.mem.MemoryDataDestination;

import java.util.stream.Stream;

/**
 * {@link CachedMeshIntersectionKernel} provides a caching layer for mesh-ray intersection
 * computations that is aware of render dimensions for efficient batch processing.
 *
 * <p>This class is designed to work with ray tracing renderers that process pixels in batches.
 * When rendering, the kernel first evaluates intersections for all rays in a batch and caches
 * the results. Subsequent queries for individual pixel positions retrieve cached values rather
 * than recomputing intersections.
 *
 * <p>The class implements {@link DimensionAware} to receive information about the render
 * dimensions (width, height, supersampling), which is used to map 2D pixel positions to
 * linear indices in the cache.
 *
 * <h3>Usage Pattern:</h3>
 * <pre>{@code
 * CachedMeshIntersectionKernel kernel = new CachedMeshIntersectionKernel(meshData, rayProducer);
 * kernel.setDimensions(width, height, ssw, ssh);
 *
 * // Batch evaluation (populates cache)
 * kernel.into(destinationBank).evaluate(args);
 *
 * // Individual queries (uses cache)
 * PackedCollection distance = kernel.evaluate(new Object[]{new Pair(x, y)});
 *
 * // Get normal at closest intersection
 * Vector normal = kernel.getClosestNormal().evaluate(args);
 * }</pre>
 *
 * @author Michael Murray
 * @see MeshData
 * @see Mesh
 * @see DimensionAware
 */
public class CachedMeshIntersectionKernel implements Evaluable<PackedCollection>, DimensionAware {
	private MeshData data;
	private Evaluable<Ray> ray;
	private Evaluable<Vector> closestNormal;

	private PackedCollection cache;

	private int width = -1, height = -1, ssw = -1, ssh = -1;

	/**
	 * Constructs a new {@link CachedMeshIntersectionKernel} for the specified mesh data and ray producer.
	 *
	 * @param data the mesh data containing triangle information
	 * @param ray  the ray producer that generates rays for intersection testing
	 */
	public CachedMeshIntersectionKernel(MeshData data, Producer<Ray> ray) {
		this.data = data;
		this.ray = ray.get();
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Sets the render dimensions used to compute cache indices from 2D pixel positions.
	 */
	@Override
	public void setDimensions(int width, int height, int ssw, int ssh) {
		this.width = width;
		this.height = height;
		this.ssw = ssw;
		this.ssh = ssh;
	}

	/**
	 * {@inheritDoc}
	 *
	 * @param size the number of scalars to allocate
	 * @return a new scalar memory bank of the specified size
	 */
	@Override
	public MemoryBank<PackedCollection> createDestination(int size) {
		return new PackedCollection(new TraversalPolicy(size, 1));
	}

	/**
	 * Creates an evaluable that computes intersections for all rays and stores results
	 * in the provided destination, while also populating the internal cache.
	 *
	 * @param destination the memory bank to store intersection distances
	 * @return an evaluable that performs batch intersection computation
	 */
	@Override
	public Evaluable into(Object destination) {
		return args -> {
			cache = Pair.bank(((MemoryBank) destination).getCount());
			data.evaluateIntersectionKernel(ray, cache, Stream.of(args).map(MemoryData.class::cast).toArray(MemoryData[]::new));
			for (int i = 0; i < cache.getCountLong(); i++) {
				((MemoryData) ((MemoryBank) destination).get(i)).setMem(cache.toDouble(i * 2), 1.0);
			}

			return destination;
		};
	}

	/**
	 * Evaluates the intersection distance for a ray at the specified position.
	 *
	 * <p>If the cache has been populated via a prior call to {@link #into(Object)},
	 * this method retrieves the cached result using the pixel position from args.
	 * Otherwise, it computes the intersection directly.
	 *
	 * @param args arguments containing a {@link Pair} with the pixel (x, y) position as the first element
	 * @return the intersection distance as a {@link PackedCollection}, or a negative value if no intersection
	 */
	@Override
	public PackedCollection evaluate(Object[] args) {
		PackedCollection result = new PackedCollection(1);
		if (cache == null) {
			result.setMem(0, data.evaluateIntersection(ray, args).getA());
		} else {
			Pair pos = (Pair) args[0];
			int n = DimensionAware.getPosition(pos.getX(), pos.getY(), width, height, ssw, ssh);
			result.setMem(0, cache.toDouble(n * 2));
		}
		return result;
	}

	/**
	 * Returns an evaluable that computes the surface normal at the closest intersection point.
	 *
	 * <p>The returned evaluable uses the cached intersection results to look up which triangle
	 * was hit and returns its precomputed face normal. If no intersection occurred at the
	 * queried position, a zero vector is returned.
	 *
	 * @return an evaluable that produces the normal vector at the intersection point
	 */
	public Evaluable<Vector> getClosestNormal() {
		if (closestNormal == null) {
			closestNormal = new HardwareEvaluable<Vector>(() -> args -> {
				if (cache == null) {
					return new Vector(data.range(new TraversalPolicy(3), ((int) data.evaluateIntersection(ray, args).getB()) * 12 + 9), 0);
				} else {
					Pair pos = (Pair) args[0];
					int n = DimensionAware.getPosition(pos.getX(), pos.getY(), width, height, ssw, ssh);
					if (n < 0) return new Vector(ZeroVector.getEvaluable().evaluate(), 0);
					int a = (int) cache.toDouble(n * 2 + 1);
					if (a < 0) return new Vector(ZeroVector.getEvaluable().evaluate(), 0);
					return new Vector(data.range(new TraversalPolicy(3), a * 12 + 9), 0);
				}
			}, new MemoryDataDestination<Vector>(size -> (MemoryBank) Vector.bank(size)), null, true);
		}

		return closestNormal;
	}
}
