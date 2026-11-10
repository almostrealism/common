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
import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.geometry.Ray;
import org.almostrealism.space.BoundingVolumeHierarchy;
import org.almostrealism.space.Mesh;
import org.almostrealism.space.MeshData;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Records ray-triangle intersection cost against triangle count for the linear scan and the
 * {@link BoundingVolumeHierarchy}-accelerated path.
 *
 * <p>The cost metric is the number of triangles each ray is tested against — the backend-independent
 * work that acceleration reduces. The linear scan tests every triangle ({@code rayCount * T}); the
 * accelerated path tests only a ray's hierarchy candidates. Because the surface uses a fixed node
 * spacing, each larger grid covers a larger area at the same density, so a downward ray crosses a
 * roughly constant number of cells and keeps a roughly constant candidate count however large the
 * mesh grows. The test asserts the accelerated work grows sub-linearly against the triangle count,
 * and additionally logs the measured device wall time of each path at one resolution.</p>
 */
public class MeshIntersectionScalingTest extends TestSuiteBase {
	/** Node spacing, held fixed so triangle count grows with grid extent. */
	private static final double SPACING = 0.3;

	/** The accelerated intersection work grows far slower than the triangle count. */
	@Test(timeout = 240000)
	@TestDepth(2)
	public void acceleratedScalesSubLinearly() {
		int[] resolutions = { 16, 24, 32, 40, 48 };
		int rayCount = 24;
		Vector origin = new Vector(0.0, 0.0, 0.0);

		int firstTriangles = 0;
		int lastTriangles = 0;
		long firstCandidates = 0;
		long lastCandidates = 0;

		for (int r = 0; r < resolutions.length; r++) {
			int n = resolutions[r];
			BoundingVolumeHierarchy bvh = BoundingVolumeHierarchy.build(
					Mesh.gridSurfaceTriangles(GridSurfaceTessellationTest.bumpy(n, n), origin, SPACING, SPACING));
			int triangles = bvh.getTriangleCount();
			double extent = (n - 1) * SPACING;

			long candidates = 0;
			for (int k = 0; k < rayCount; k++) {
				double fx = 0.15 + 0.7 * (k + 0.5) / rayCount;
				double fz = 0.15 + 0.7 * ((k * 7) % rayCount + 0.5) / rayCount;
				candidates += bvh.candidates(origin.getX() + fx * extent, 5.0,
						origin.getZ() + fz * extent, 0.0, -1.0, 0.0).length;
			}

			log("triangles=" + triangles + " linearTested=" + ((long) rayCount * triangles)
					+ " candidatesTested=" + candidates);

			if (r == 0) {
				firstTriangles = triangles;
				firstCandidates = candidates;
			}
			if (r == resolutions.length - 1) {
				lastTriangles = triangles;
				lastCandidates = candidates;
			}
		}

		double triangleRatio = (double) lastTriangles / firstTriangles;
		double candidateRatio = (double) lastCandidates / firstCandidates;
		log("triangleRatio=" + triangleRatio + " candidateRatio=" + candidateRatio);

		Assert.assertTrue("accelerated work should grow sub-linearly: candidateRatio="
						+ candidateRatio + " triangleRatio=" + triangleRatio,
				candidateRatio < triangleRatio / 2.0);

		logDeviceTiming(origin, 20, rayCount);
	}

	/**
	 * Measures and logs the device wall time of the linear and accelerated intersection paths at a
	 * single resolution, after a warm-up that excludes one-time kernel compilation.
	 *
	 * @param origin   the position of node {@code (0, 0)}
	 * @param n        the grid resolution
	 * @param rayCount the number of rays to time
	 */
	private void logDeviceTiming(Vector origin, int n, int rayCount) {
		// A flat field is enough to time the paths; the sub-linearity assertion above uses the
		// bumpy surface. The mesh data builds its own hierarchy over this flat field.
		MeshData md = MeshData.gridSurface(cp(new PackedCollection(shape(n, n))),
				n, n, origin, SPACING, SPACING);
		double extent = (n - 1) * SPACING;

		Evaluable<Ray>[] rays = new Evaluable[rayCount];
		for (int k = 0; k < rayCount; k++) {
			double fx = 0.15 + 0.7 * (k + 0.5) / rayCount;
			double fz = 0.15 + 0.7 * ((k * 7) % rayCount + 0.5) / rayCount;
			// Supplied as data, so the timed loops do not include compiling a kernel per ray.
			Ray value = new Ray(new Vector(origin.getX() + fx * extent, 5.0,
					origin.getZ() + fz * extent), new Vector(0.0, -1.0, 0.0));
			rays[k] = args -> value;
		}

		md.evaluateIntersection(rays[0], new Object[0]);
		md.evaluateIntersectionAccelerated(rays[0], new Object[0]);

		long linearStart = System.nanoTime();
		for (int k = 0; k < rayCount; k++) md.evaluateIntersection(rays[k], new Object[0]);
		long linearMs = (System.nanoTime() - linearStart) / 1_000_000;

		long accelStart = System.nanoTime();
		Pair last = null;
		for (int k = 0; k < rayCount; k++) last = md.evaluateIntersectionAccelerated(rays[k], new Object[0]);
		long accelMs = (System.nanoTime() - accelStart) / 1_000_000;

		log("deviceTiming triangles=" + md.getCount() + " rays=" + rayCount
				+ " linearMs=" + linearMs + " accelMs=" + accelMs + " lastHit=" + (last == null ? -1 : last.getB()));
	}
}
