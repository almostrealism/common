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
import org.almostrealism.primitives.AbsorptionPlane;
import org.almostrealism.primitives.Pinhole;
import org.almostrealism.primitives.Plane;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Pins the shared "across" coordinate-axis behaviour of {@link Plane} and its
 * subclasses {@link AbsorptionPlane} and {@link Pinhole}. The across axis is
 * defined as {@code up} cross {@code normal} and is consumed by the plane
 * membership test, the spatial/surface coordinate mappings, and the photon
 * absorption tests. These assertions record the current behaviour of every one
 * of those call sites so that consolidating the duplicated lazy-initialisation
 * of the across axis into {@link Plane#getAcross()} preserves it exactly.
 */
public class AbsorptionPlaneGeometryTest extends TestSuiteBase {

	/**
	 * Configures a plane whose up axis is {@code (0, 1, 0)} and whose surface
	 * normal is {@code (0, 0, 1)}, so the derived across axis is {@code (1, 0, 0)}.
	 *
	 * @param plane the plane to configure
	 */
	protected void configure(Plane plane) {
		plane.setOrientation(new double[] { 0.0, 1.0, 0.0 });
		plane.setSurfaceNormal(vector(0.0, 0.0, 1.0));
	}

	/**
	 * The across axis is the cross product of up and normal.
	 */
	@Test(timeout = 60000)
	public void acrossAxis() {
		Plane plane = new Plane();
		configure(plane);

		double[] across = plane.getAcross();
		assertEquals(1.0, across[0]);
		assertEquals(0.0, across[1]);
		assertEquals(0.0, across[2]);
	}

	/**
	 * The across axis is cached on first access but recomputed after either
	 * {@link Plane#setOrientation(double[])} or
	 * {@link Plane#setSurfaceNormal(io.almostrealism.relation.Producer)} invalidates
	 * the cache. This invalidation is the contract every {@link Plane#getAcross()}
	 * call site depends on, so it is pinned here directly.
	 */
	@Test(timeout = 60000)
	public void acrossRecomputesAfterReorientation() {
		Plane plane = new Plane();
		configure(plane);

		double[] first = plane.getAcross();
		assertEquals(1.0, first[0]);
		assertEquals(0.0, first[1]);
		assertEquals(0.0, first[2]);

		// Flip the up axis: across = up x normal = (0,-1,0) x (0,0,1) = (-1,0,0).
		plane.setOrientation(new double[] { 0.0, -1.0, 0.0 });
		double[] afterUp = plane.getAcross();
		assertEquals(-1.0, afterUp[0]);
		assertEquals(0.0, afterUp[1]);
		assertEquals(0.0, afterUp[2]);

		// Flip the normal: across = up x normal = (0,-1,0) x (0,0,-1) = (1,0,0).
		plane.setSurfaceNormal(vector(0.0, 0.0, -1.0));
		double[] afterNormal = plane.getAcross();
		assertEquals(1.0, afterNormal[0]);
		assertEquals(0.0, afterNormal[1]);
		assertEquals(0.0, afterNormal[2]);
	}

	/**
	 * {@link Plane#getSpatialCoords(double[])} maps unit-square coordinates onto
	 * a point expressed along the across and up axes.
	 */
	@Test(timeout = 60000)
	public void spatialCoords() {
		Plane plane = new Plane();
		configure(plane);
		plane.setWidth(4.0);
		plane.setHeight(2.0);

		double[] xyz = plane.getSpatialCoords(new double[] { 1.0, 0.0 });
		assertEquals(2.0, xyz[0]);
		assertEquals(1.0, xyz[1]);
		assertEquals(0.0, xyz[2]);
	}

	/**
	 * {@link Plane#getSurfaceCoords(io.almostrealism.relation.Producer)} inverts
	 * {@link Plane#getSpatialCoords(double[])}.
	 */
	@Test(timeout = 60000)
	public void surfaceCoords() {
		Plane plane = new Plane();
		configure(plane);
		plane.setWidth(4.0);
		plane.setHeight(2.0);

		double[] uv = plane.getSurfaceCoords(vector(2.0, 1.0, 0.0));
		assertEquals(1.0, uv[0]);
		assertEquals(0.0, uv[1]);
	}

	/**
	 * {@link Plane#inside(io.almostrealism.relation.Producer)} accepts points
	 * within the slab thickness and bounded extents, and rejects points outside
	 * them along the normal and across axes.
	 */
	@Test(timeout = 60000)
	public void inside() {
		Plane plane = new Plane();
		configure(plane);
		plane.setWidth(4.0);
		plane.setHeight(2.0);

		assertTrue(plane.inside(vector(1.0, 0.5, 0.2)));
		assertFalse(plane.inside(vector(3.0, 0.0, 0.0)));
		assertFalse(plane.inside(vector(0.0, 0.0, 1.0)));
	}

	/**
	 * {@link Pinhole#absorb(Vector, Vector, double)} passes photons through the
	 * hole, absorbs those outside the hole but within the slab, and ignores
	 * those beyond the slab thickness.
	 */
	@Test(timeout = 60000)
	public void pinholeAbsorb() {
		Pinhole pinhole = new Pinhole();
		configure(pinhole);
		pinhole.setRadius(1.0);

		// Inside the hole: passes through (not absorbed).
		assertFalse(pinhole.absorb(new Vector(0.5, 0.0, 0.0), new Vector(0.0, 0.0, 1.0), 1.0));
		// Outside the hole but within the slab: absorbed.
		assertTrue(pinhole.absorb(new Vector(2.0, 0.0, 0.0), new Vector(0.0, 0.0, 1.0), 1.0));
		// Beyond the slab thickness: not absorbed.
		assertFalse(pinhole.absorb(new Vector(0.0, 0.0, 1.0), new Vector(0.0, 0.0, 1.0), 1.0));
	}

	/**
	 * {@link AbsorptionPlane#absorb(Vector, Vector, double)} rejects photons
	 * beyond the slab thickness before the across axis is consulted, and rejects
	 * photons projected outside the sensor grid along the across axis. The latter
	 * case exercises the across axis: only because it is {@code (1, 0, 0)} does a
	 * photon at {@code x = 10} on that axis map outside the grid. (A photon that
	 * lands inside the grid follows a separate colour-recording path that is not
	 * exercised here.)
	 */
	@Test(timeout = 60000)
	public void absorptionPlaneAbsorb() {
		AbsorptionPlane plane = new AbsorptionPlane();
		configure(plane);
		plane.disableDisplay();
		plane.setPixelSize(1.0);
		plane.setWidth(4.0);
		plane.setHeight(4.0);

		// Beyond the slab thickness: not absorbed (returns before across).
		assertFalse(plane.absorb(new Vector(0.0, 0.0, 1.0), new Vector(0.0, 0.0, 1.0), 1.0));

		// Within the slab but far outside the grid along the across axis.
		assertFalse(plane.absorb(new Vector(10.0, 0.0, 0.0), new Vector(0.0, 0.0, 1.0), 1.0));
	}
}
