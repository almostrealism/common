/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.space.test;

import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.geometry.Ray;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.space.BoundingVolumeHierarchy;
import org.almostrealism.space.DefaultVertexData;
import org.almostrealism.space.Mesh;
import org.almostrealism.space.MeshData;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Verifies that the {@link BoundingVolumeHierarchy}-accelerated intersection returns the
 * same closest hit as the unaccelerated linear scan for many random rays against a bumpy
 * height-field mesh.
 */
public class MeshAccelerationTest extends TestSuiteBase implements GridIndexFeatures {
	/** Node spacing used for the test surface. */
	private static final double SPACING = 0.5;

	/**
	 * Builds a bumpy height field as a device Producer graph, indexing the grid with
	 * {@code integers()} so no host-computed values are staged.
	 *
	 * @param nx the number of nodes along X
	 * @param ny the number of nodes along Z
	 * @return a producer for the {@code (nx*ny)} height field in row-major order
	 */
	private CollectionProducer bumpyHeights(int nx, int ny) {
		CollectionProducer[] ij = gridIndices(nx, ny);
		return sin(ij[0].multiply(0.6)).multiply(0.6).add(cos(ij[1].multiply(0.5)).multiply(0.3));
	}

	/** Diagnostic: a ray that hits only the second of two triangles must report triangle index 1. */
	@Test(timeout = 60000)
	public void multiTriangleScan() {
		DefaultVertexData data = new DefaultVertexData(6, 2);
		data.getVertices().set(0, new Vector(100.0, 1.0, 0.0));
		data.getVertices().set(1, new Vector(99.0, -1.0, 0.0));
		data.getVertices().set(2, new Vector(101.0, -1.0, 0.0));
		data.setTriangle(0, 0, 1, 2);
		data.getVertices().set(3, new Vector(0.0, 100.0, 0.0));
		data.getVertices().set(4, new Vector(-100.0, -100.0, 0.0));
		data.getVertices().set(5, new Vector(100.0, -100.0, 0.0));
		data.setTriangle(1, 3, 4, 5);

		Mesh mesh = new Mesh(data);
		MeshData md = mesh.getMeshData();

		Producer<Ray> rp = (Producer) ray(vector(0.0, 1.0, 1.0), vector(0.0, 0.0, -1.0));
		Evaluable<Ray> re = rp.get();

		Pair single = md.evaluateIntersection(re, new Object[0]);

		PackedCollection dest = Pair.bank(1);
		md.evaluateIntersectionKernel(re, dest, new MemoryData[0]);

		log("single=(" + single.getA() + "," + single.getB() + ") partial=("
				+ dest.toDouble(0) + "," + dest.toDouble(1) + ")");

		Assert.assertEquals("partial distance", 1.0, dest.toDouble(0), 1e-4);
		Assert.assertEquals("partial triangle", 1.0, dest.toDouble(1), 1e-9);
		Assert.assertEquals("single distance", 1.0, single.getA(), 1e-4);
		Assert.assertEquals("single triangle", 1.0, single.getB(), 1e-9);
	}

	/**
	 * The hierarchy must return candidates in ascending triangle-index order regardless of the
	 * internal spatial sort, so that an intersection-distance tie resolves to the same triangle
	 * the linear scan (which tests in mesh-index order) would pick.
	 */
	@Test(timeout = 30000)
	public void candidatesReturnedInAscendingIndexOrder() {
		// Eight coplanar-footprint triangles stacked along Z, with list index deliberately
		// shuffled relative to height so the BVH's internal median split reorders them.
		int[] zByIndex = { 5, 2, 7, 0, 4, 1, 6, 3 };
		List<Vector[]> triangles = new ArrayList<>();
		for (int z : zByIndex) {
			triangles.add(new Vector[] {
					new Vector(0.0, 0.0, z), new Vector(1.0, 0.0, z), new Vector(0.0, 1.0, z) });
		}

		BoundingVolumeHierarchy bvh = BoundingVolumeHierarchy.build(triangles);

		// A ray dropping straight down through the shared XY footprint pierces every box.
		int[] candidates = bvh.candidates(0.25, 0.25, 100.0, 0.0, 0.0, -1.0);

		Assert.assertEquals(zByIndex.length, candidates.length);
		for (int i = 0; i < candidates.length; i++) {
			Assert.assertEquals("candidate " + i, i, candidates[i]);
		}
	}

	/** The accelerated closest hit equals the linear-scan closest hit for every random ray. */
	@Test(timeout = 180000)
	public void acceleratedMatchesLinearScan() {
		int nx = 16;
		int ny = 16;
		Vector origin = new Vector(0.0, 0.0, 0.0);

		PackedCollection heights = bumpyHeights(nx, ny).evaluate();
		MeshData md = MeshData.gridSurface(cp(heights), nx, ny, origin, SPACING, SPACING);

		double extentX = (nx - 1) * SPACING;
		double extentZ = (ny - 1) * SPACING;

		Random random = new Random(1234);
		int rays = 48;
		int hits = 0;

		for (int r = 0; r < rays; r++) {
			double x = origin.getX() + (0.2 + 0.6 * random.nextDouble()) * extentX;
			double z = origin.getZ() + (0.2 + 0.6 * random.nextDouble()) * extentZ;
			double tiltX = (random.nextDouble() - 0.5) * 0.02;
			double tiltZ = (random.nextDouble() - 0.5) * 0.02;

			// The ray is supplied as data rather than as a new producer per iteration, so the
			// loop does not compile a separate ray kernel for every random ray.
			Ray value = new Ray(new Vector(x, 5.0, z), new Vector(tiltX, -1.0, tiltZ));
			Evaluable<Ray> re = args -> value;

			Pair linear = md.evaluateIntersection(re, new Object[0]);
			Pair accelerated = md.evaluateIntersectionAccelerated(re, new Object[0]);

			Assert.assertEquals("distance for ray " + r, linear.getA(), accelerated.getA(), 1e-4);
			Assert.assertEquals("triangle for ray " + r, linear.getB(), accelerated.getB(), 1e-9);

			if (linear.getA() > 0.0) hits++;
		}

		Assert.assertTrue("expected most downward rays to hit the surface, hit " + hits,
				hits > rays / 2);
	}
}
