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

import io.almostrealism.compute.Process;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.geometry.Ray;
import org.almostrealism.graph.mesh.TriangleFeatures;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.space.BoundingVolumeHierarchy;
import org.almostrealism.space.CachedMeshIntersectionKernel;
import org.almostrealism.space.DefaultVertexData;
import org.almostrealism.space.Mesh;
import org.almostrealism.space.MeshData;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

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
	 * Builds a two-triangle mesh in the {@code z = 0} plane: a small triangle far along X
	 * (index 0) and a large triangle covering the origin (index 1).
	 *
	 * @return the device mesh data for the two triangles
	 */
	private static MeshData twoTriangleMesh() {
		DefaultVertexData data = new DefaultVertexData(6, 2);
		data.getVertices().set(0, new Vector(100.0, 1.0, 0.0));
		data.getVertices().set(1, new Vector(99.0, -1.0, 0.0));
		data.getVertices().set(2, new Vector(101.0, -1.0, 0.0));
		data.setTriangle(0, 0, 1, 2);
		data.getVertices().set(3, new Vector(0.0, 100.0, 0.0));
		data.getVertices().set(4, new Vector(-100.0, -100.0, 0.0));
		data.getVertices().set(5, new Vector(100.0, -100.0, 0.0));
		data.setTriangle(1, 3, 4, 5);
		return new Mesh(data).getMeshData();
	}

	/**
	 * Without a populated cache, {@link CachedMeshIntersectionKernel#getClosestNormal()} must
	 * return the hit triangle's face normal for a hit and the zero vector for a miss, whether
	 * intersection runs as a linear scan or through the acceleration structure. A miss reports
	 * triangle index {@code -1}, which must not be turned into a mesh-data offset.
	 */
	@Test(timeout = 60000)
	public void uncachedClosestNormalHitAndMiss() {
		MeshData md = twoTriangleMesh();

		for (boolean accelerated : new boolean[] { false, true }) {
			Producer<Ray> hitRay = (Producer) ray(vector(0.0, 1.0, 1.0), vector(0.0, 0.0, -1.0));
			Vector hit = new CachedMeshIntersectionKernel(md, hitRay, accelerated)
					.getClosestNormal().evaluate();
			Assert.assertEquals("hit normal x, accelerated=" + accelerated, 0.0, hit.getX(), 1e-6);
			Assert.assertEquals("hit normal y, accelerated=" + accelerated, 0.0, hit.getY(), 1e-6);
			Assert.assertEquals("hit normal |z|, accelerated=" + accelerated,
					1.0, Math.abs(hit.getZ()), 1e-6);

			Producer<Ray> missRay = (Producer) ray(vector(0.0, 1.0, 1.0), vector(0.0, 0.0, 1.0));
			Vector miss = new CachedMeshIntersectionKernel(md, missRay, accelerated)
					.getClosestNormal().evaluate();
			Assert.assertEquals("miss normal x, accelerated=" + accelerated, 0.0, miss.getX(), 0.0);
			Assert.assertEquals("miss normal y, accelerated=" + accelerated, 0.0, miss.getY(), 0.0);
			Assert.assertEquals("miss normal z, accelerated=" + accelerated, 0.0, miss.getZ(), 0.0);
		}
	}

	/** Diagnostic: a ray that hits only the second of two triangles must report triangle index 1. */
	@Test(timeout = 60000)
	public void multiTriangleScan() {
		MeshData md = twoTriangleMesh();

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
		DefaultVertexData data = new DefaultVertexData(3 * zByIndex.length, zByIndex.length);
		for (int t = 0; t < zByIndex.length; t++) {
			int z = zByIndex[t];
			data.getVertices().set(3 * t, new Vector(0.0, 0.0, z));
			data.getVertices().set(3 * t + 1, new Vector(1.0, 0.0, z));
			data.getVertices().set(3 * t + 2, new Vector(0.0, 1.0, z));
			data.setTriangle(t, 3 * t, 3 * t + 1, 3 * t + 2);
		}

		BoundingVolumeHierarchy bvh = new Mesh(data).getMeshData().getBoundingVolumeHierarchy();

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

	/**
	 * The batch {@link MeshData#evaluateIntersectionKernelAccelerated} must produce the same
	 * (distance, triangle index) pairs as the batch linear scan
	 * {@link MeshData#evaluateIntersectionKernel}, for both a ray that hits the mesh (a non-empty
	 * candidate set) and a ray that misses it entirely (an empty candidate set, which must report
	 * the {@code (-1, -1)} miss result rather than a stale or negative triangle index).
	 */
	@Test(timeout = 120000)
	public void acceleratedKernelMatchesLinearKernel() {
		MeshData md = twoTriangleMesh();

		// A ray dropping onto the large triangle (index 1), and a ray pointing away from it.
		Producer<Ray> hitRay = (Producer) ray(vector(0.0, 1.0, 1.0), vector(0.0, 0.0, -1.0));
		Producer<Ray> missRay = (Producer) ray(vector(0.0, 1.0, 1.0), vector(0.0, 0.0, 1.0));

		PackedCollection linearHit = Pair.bank(1);
		md.evaluateIntersectionKernel(hitRay.get(), linearHit, new MemoryData[0]);
		PackedCollection accelHit = Pair.bank(1);
		md.evaluateIntersectionKernelAccelerated(hitRay.get(), accelHit, new MemoryData[0]);

		Assert.assertEquals("hit distance", 1.0, accelHit.toDouble(0), 1e-4);
		Assert.assertEquals("hit triangle", 1.0, accelHit.toDouble(1), 1e-9);
		Assert.assertEquals("hit distance matches linear", linearHit.toDouble(0), accelHit.toDouble(0), 1e-4);
		Assert.assertEquals("hit triangle matches linear", linearHit.toDouble(1), accelHit.toDouble(1), 1e-9);

		PackedCollection accelMiss = Pair.bank(1);
		md.evaluateIntersectionKernelAccelerated(missRay.get(), accelMiss, new MemoryData[0]);

		Assert.assertEquals("miss distance", -1.0, accelMiss.toDouble(0), 1e-9);
		Assert.assertEquals("miss triangle", -1.0, accelMiss.toDouble(1), 1e-9);
	}

	/**
	 * Intersecting a whole batch of rays in one evaluation of
	 * {@link TriangleFeatures#closestMeshIntersection} gives, ray by ray, the same closest hit as
	 * intersecting each ray on its own, including rays that miss the surface entirely.
	 */
	@Test(timeout = 180000)
	public void batchedClosestHitMatchesPerRay() {
		int nx = 8;
		int ny = 8;
		Vector origin = new Vector(0.0, 0.0, 0.0);
		MeshData md = MeshData.gridSurface(bumpyHeights(nx, ny), nx, ny, origin, SPACING, SPACING);

		int rays = 12;
		double extent = (nx - 1) * SPACING;
		Random random = new Random(42);
		PackedCollection bank = Ray.bank(rays);
		Ray[] values = new Ray[rays];
		for (int r = 0; r < rays; r++) {
			// Every fourth ray starts beyond the surface edge and so misses it
			double x = r % 4 == 3 ? extent + 10.0 : (0.1 + 0.8 * random.nextDouble()) * extent;
			double z = (0.1 + 0.8 * random.nextDouble()) * extent;
			values[r] = new Ray(new Vector(x, 5.0, z), new Vector(0.0, -1.0, 0.0));
			bank.setFrom(r * 6, values[r], 0, 6);
		}

		PackedCollection hits = Process.optimized(TriangleFeatures.getInstance()
				.closestMeshIntersection(cp(md), md.getCount(), cp(bank))).get().evaluate();
		Assert.assertEquals(rays * 2, hits.getMemLength());

		int misses = 0;
		for (int r = 0; r < rays; r++) {
			Ray value = values[r];
			Pair single = md.evaluateIntersection(args -> value, new Object[0]);
			Assert.assertEquals("distance for ray " + r, single.getA(), hits.toDouble(r * 2), 1e-4);
			Assert.assertEquals("triangle for ray " + r, single.getB(), hits.toDouble(r * 2 + 1), 1e-9);
			if (hits.toDouble(r * 2 + 1) < 0) misses++;
		}

		Assert.assertEquals("rays beyond the surface edge must miss", rays / 4, misses);
	}

	/**
	 * {@link TriangleFeatures#meshNormalAt} gathers the face normal of the triangle each hit
	 * reports, and the zero vector for a miss.
	 */
	@Test(timeout = 60000)
	public void meshNormalAtGathersHitTriangleNormal() {
		MeshData md = twoTriangleMesh();
		TriangleFeatures tf = TriangleFeatures.getInstance();

		PackedCollection bank = Ray.bank(2);
		bank.setFrom(0, new Ray(new Vector(0.0, 1.0, 1.0), new Vector(0.0, 0.0, -1.0)), 0, 6);
		bank.setFrom(6, new Ray(new Vector(0.0, 1.0, 1.0), new Vector(0.0, 0.0, 1.0)), 0, 6);

		PackedCollection hits = Process.optimized(
				tf.closestMeshIntersection(cp(md), md.getCount(), cp(bank))).get().evaluate();
		PackedCollection normals = tf.meshNormalAt(cp(md), cp(hits)).evaluate();

		Assert.assertEquals("hit triangle", 1.0, hits.toDouble(1), 1e-9);
		Assert.assertEquals("hit normal x", 0.0, normals.toDouble(0), 1e-6);
		Assert.assertEquals("hit normal y", 0.0, normals.toDouble(1), 1e-6);
		Assert.assertEquals("hit normal |z|", 1.0, Math.abs(normals.toDouble(2)), 1e-6);

		Assert.assertEquals("miss triangle", -1.0, hits.toDouble(3), 1e-9);
		for (int k = 0; k < 3; k++) {
			Assert.assertEquals("miss normal " + k, 0.0, normals.toDouble(3 + k), 0.0);
		}
	}

	/** {@link TriangleFeatures#meshTriangleBounds} bounds each triangle's three vertices exactly. */
	@Test(timeout = 60000)
	public void meshTriangleBoundsCoverVertices() {
		MeshData md = twoTriangleMesh();
		PackedCollection bounds = TriangleFeatures.getInstance()
				.meshTriangleBounds(cp(md), md.getCount()).evaluate();

		double[][] expected = {
				{ 99.0, -1.0, 0.0, 101.0, 1.0, 0.0 },
				{ -100.0, -100.0, 0.0, 100.0, 100.0, 0.0 }
		};

		for (int t = 0; t < expected.length; t++) {
			for (int k = 0; k < 6; k++) {
				Assert.assertEquals("triangle " + t + " bound " + k,
						expected[t][k], bounds.toDouble(t * 6 + k), 1e-4);
			}
		}
	}
}
