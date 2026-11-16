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

package org.almostrealism.primitives.test;

import org.almostrealism.algebra.Vector;
import org.almostrealism.geometry.BoundingSolid;
import org.almostrealism.geometry.Ray;
import org.almostrealism.space.Mesh;
import org.almostrealism.primitives.Sphere;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Tests the host-side geometry of {@link Sphere}: its normal, index of refraction, distance
 * estimate, bounding solid, and triangulation, none of which go through the intersection kernel.
 */
public class SphereGeometryTest extends TestSuiteBase {
	/** The tolerance for comparing coordinates and distances. */
	private static final double TOLERANCE = 1e-6;

	/** The outward normal points from the centre of the sphere to the queried point. */
	@Test(timeout = 30000)
	public void normalPointsOutward() {
		Sphere s = new Sphere(new Vector(0.0, 0.0, 0.0), 1.0);
		Vector n = new Vector(s.getNormalAt(vector(2.0, 0.0, 0.0)).get().evaluate(), 0);

		assertEquals(2.0, n.getX(), TOLERANCE);
		assertEquals(0.0, n.getY(), TOLERANCE);
		assertEquals(0.0, n.getZ(), TOLERANCE);
	}

	/** The index of refraction of the material applies inside the sphere and air applies outside. */
	@Test(timeout = 30000)
	public void indexOfRefractionDependsOnContainment() {
		Sphere s = new Sphere(new Vector(0.0, 0.0, 0.0), 1.0);
		s.setIndexOfRefraction(1.5);

		assertEquals(1.5, s.getIndexOfRefraction(new Vector(0.0, 0.0, 0.5)), TOLERANCE);
		assertEquals(1.0, s.getIndexOfRefraction(new Vector(0.0, 0.0, 2.0)), TOLERANCE);
	}

	/** The distance estimate is the gap between the ray origin and the surface along the centre. */
	@Test(timeout = 30000)
	public void distanceEstimateIsGapToSurface() {
		Sphere s = new Sphere(new Vector(0.0, 0.0, 0.0), 1.0);
		Ray r = new Ray(new Vector(0.0, 0.0, 5.0), new Vector(0.0, 0.0, -1.0));

		assertEquals(4.0, s.estimateDistance(r), TOLERANCE);
	}

	/** The bounding solid is the axis-aligned cube of half-width equal to the radius about the centre. */
	@Test(timeout = 30000)
	public void boundingSolidWrapsSphere() {
		Sphere s = new Sphere(new Vector(1.0, 2.0, 3.0), 2.0);
		BoundingSolid b = s.calculateBoundingSolid();

		assertEquals(-1.0, b.minX, TOLERANCE);
		assertEquals(3.0, b.maxX, TOLERANCE);
		assertEquals(0.0, b.minY, TOLERANCE);
		assertEquals(4.0, b.maxY, TOLERANCE);
		assertEquals(1.0, b.minZ, TOLERANCE);
		assertEquals(5.0, b.maxZ, TOLERANCE);
	}

	/** Triangulating the sphere produces the eight-faced octahedral approximation with its six poles. */
	@Test(timeout = 30000)
	public void triangulateBuildsOctahedron() {
		Sphere s = new Sphere(new Vector(0.0, 0.0, 0.0), 1.0);
		Mesh m = s.triangulate();

		assertEquals(8, m.getTriangles().length);

		// The six axis poles are added as mesh points.
		assertTrue(m.getIndex(new Vector(0.0, 1.0, 0.0)) >= 0);
		assertTrue(m.getIndex(new Vector(0.0, 0.0, -1.0)) >= 0);
	}
}
