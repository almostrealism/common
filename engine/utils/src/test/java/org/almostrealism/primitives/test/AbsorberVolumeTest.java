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
import org.almostrealism.physics.Volume;
import org.almostrealism.primitives.BoxVolume;
import org.almostrealism.primitives.Plane;
import org.almostrealism.primitives.SphericalVolume;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Tests {@link SphericalVolume}, {@link BoxVolume} and {@link Plane}: containment, intersection,
 * normals, and that surface coordinates and spatial coordinates are inverses.
 */
public class AbsorberVolumeTest extends TestSuiteBase {
	/** The tolerance for comparing coordinates and distances. */
	private static final double TOLERANCE = 1e-6;

	/**
	 * Returns a box 4 wide, 2 high and 6 deep with walls 0.1 thick.
	 *
	 * @param normal      the outward normal of the bottom
	 * @param orientation the direction from the back to the front
	 * @return the box
	 */
	private BoxVolume box(double[] normal, double[] orientation) {
		BoxVolume box = new BoxVolume();
		box.setWidth(4.0);
		box.setHeight(2.0);
		box.setDepth(6.0);
		box.setWallThickness(0.1);
		box.setSurfaceNormal(normal);
		box.setOrientation(orientation);
		return box;
	}

	/**
	 * Returns a slab in the xy plane, 4 wide along x and 2 high along y, with half-thickness 0.1.
	 *
	 * @return the slab
	 */
	private Plane slab() {
		Plane plane = new Plane();
		plane.setWidth(4.0);
		plane.setHeight(2.0);
		plane.setThickness(0.1);
		plane.setOrientation(new double[] {0.0, 1.0, 0.0});
		plane.setSurfaceNormal(v(new Vector(0.0, 0.0, 1.0)));
		return plane;
	}

	/**
	 * Returns whether the given point is inside the given volume.
	 *
	 * @param volume the volume
	 * @param x      x coordinate
	 * @param y      y coordinate
	 * @param z      z coordinate
	 * @return true if the point is inside
	 */
	private boolean inside(Volume<?> volume, double x, double y, double z) {
		return volume.inside(v(new Vector(x, y, z)));
	}

	/**
	 * Asserts that mapping each of several surface coordinates to space and back returns it.
	 *
	 * @param volume the volume
	 * @param uvs    the surface coordinates
	 */
	private void assertRoundTrip(Volume<?> volume, double[][] uvs) {
		for (double[] uv : uvs) {
			double[] xyz = volume.getSpatialCoords(uv);
			double[] back = volume.getSurfaceCoords(v(new Vector(xyz)));
			assertEquals("u for " + uv[0] + ", " + uv[1], uv[0], back[0], TOLERANCE);
			assertEquals("v for " + uv[0] + ", " + uv[1], uv[1], back[1], TOLERANCE);
		}
	}

	/** A ball is hit at its near surface from outside and its far surface from inside. */
	@Test(timeout = 30000)
	public void sphereIntersectsFromOutsideAndInside() {
		SphericalVolume ball = new SphericalVolume(2.0);
		Vector down = new Vector(0.0, 0.0, -1.0);

		assertEquals(3.0, ball.intersect(new Vector(0.0, 0.0, 5.0), down), TOLERANCE);
		assertEquals(2.0, ball.intersect(new Vector(0.0, 0.0, 0.0), down), TOLERANCE);
		assertEquals(Volume.MISS, ball.intersect(new Vector(0.0, 0.0, 5.0), down.minus()), 0.0);
	}

	/** A ray passing beside the ball reports a miss rather than NaN. */
	@Test(timeout = 30000)
	public void sphereMissIsNotNaN() {
		SphericalVolume ball = new SphericalVolume(2.0);
		assertEquals(Volume.MISS,
				ball.intersect(new Vector(0.0, 5.0, 5.0), new Vector(0.0, 0.0, -1.0)), 0.0);
	}

	/** Containment includes the surface, and the normal points away from the centre. */
	@Test(timeout = 30000)
	public void sphereContainmentAndNormal() {
		SphericalVolume ball = new SphericalVolume(2.0);
		assertTrue(inside(ball, 0.0, 0.0, 2.0));
		assertFalse(inside(ball, 0.0, 2.1, 0.0));

		Vector n = new Vector(ball.getNormalAt(v(new Vector(0.0, 3.0, 4.0))).get().evaluate(), 0);
		assertEquals(0.6, n.getY(), TOLERANCE);
		assertEquals(0.8, n.getZ(), TOLERANCE);
	}

	/** Surface coordinates on a ball, scaled by its radius, round-trip through space. */
	@Test(timeout = 30000)
	public void sphereSurfaceCoordinatesRoundTrip() {
		SphericalVolume ball = new SphericalVolume(2.0);
		assertRoundTrip(ball, new double[][] { {0.5, 0.5}, {0.25, 0.1}, {0.9, 0.75}, {0.1, 0.99} });
		assertEquals(2.0, new Vector(ball.getSpatialCoords(new double[] {0.3, 0.6})).length(), TOLERANCE);
	}

	/** A slab is hit at the face nearest the starting point, from either side. */
	@Test(timeout = 30000)
	public void planeIntersectsNearestFace() {
		Plane plane = slab();
		assertEquals(4.9, plane.intersect(new Vector(0.0, 0.0, 5.0), new Vector(0.0, 0.0, -1.0)), TOLERANCE);
		assertEquals(4.9, plane.intersect(new Vector(0.5, 0.5, -5.0), new Vector(0.0, 0.0, 1.0)), TOLERANCE);
		assertEquals(2.45, plane.intersect(new Vector(0.0, 0.0, 5.0), new Vector(0.0, 0.0, -2.0)), TOLERANCE);
	}

	/**
	 * A slab reports {@link Volume#MISS} for a ray pointing away from it, a ray that crosses it
	 * outside its active region, and a ray parallel to it.
	 */
	@Test(timeout = 30000)
	public void planeMisses() {
		Plane plane = slab();
		assertEquals("behind", Volume.MISS,
				plane.intersect(new Vector(0.0, 0.0, 5.0), new Vector(0.0, 0.0, 1.0)), 0.0);
		assertEquals("beyond width", Volume.MISS,
				plane.intersect(new Vector(2.5, 0.0, 5.0), new Vector(0.0, 0.0, -1.0)), 0.0);
		assertEquals("beyond height", Volume.MISS,
				plane.intersect(new Vector(0.0, 1.5, 5.0), new Vector(0.0, 0.0, -1.0)), 0.0);
		assertEquals("parallel", Volume.MISS,
				plane.intersect(new Vector(0.0, 0.0, 5.0), new Vector(1.0, 0.0, 0.0)), 0.0);
	}

	/** Each wall is inside the box, but the space the walls enclose is not. */
	@Test(timeout = 30000)
	public void boxContainsWallsOnly() {
		BoxVolume box = box(new double[] {0.0, -1.0, 0.0}, new double[] {0.0, 0.0, 1.0});

		assertTrue("bottom", inside(box, 0.0, -1.0, 0.0));
		assertTrue("top", inside(box, 0.0, 1.0, 0.0));
		assertTrue("side", inside(box, 2.0, 0.0, 0.0));
		assertTrue("side", inside(box, -2.0, 0.0, 0.0));
		assertTrue("end", inside(box, 0.0, 0.0, 3.0));
		assertTrue("end", inside(box, 0.0, 0.0, -3.0));
		assertFalse("centre", inside(box, 0.0, 0.0, 0.0));
		assertFalse("outside", inside(box, 0.0, 0.0, 10.0));
	}

	/** The walls follow the box's directions rather than fixed axes. */
	@Test(timeout = 30000)
	public void boxWallsFollowOrientation() {
		BoxVolume box = box(new double[] {0.0, 0.0, -1.0}, new double[] {0.0, 1.0, 0.0});

		assertTrue("bottom", inside(box, 0.0, 0.0, -1.0));
		assertTrue("side", inside(box, 2.0, 0.0, 0.0));
		assertTrue("end", inside(box, 0.0, 3.0, 0.0));
		assertFalse("height is along the normal", inside(box, 0.0, -1.0, 0.0));
	}

	/** A ray from the centre along the depth reaches the near face of the front wall. */
	@Test(timeout = 30000)
	public void boxIntersectsNearestWall() {
		BoxVolume box = box(new double[] {0.0, -1.0, 0.0}, new double[] {0.0, 0.0, 1.0});
		assertEquals(2.9, box.intersect(new Vector(0.0, 0.0, 0.0), new Vector(0.0, 0.0, 1.0)), TOLERANCE);
		assertEquals(0.9, box.intersect(new Vector(0.0, 0.0, 0.0), new Vector(0.0, -1.0, 0.0)), TOLERANCE);
	}

	/** The normal on a wall is that wall's outward normal. */
	@Test(timeout = 30000)
	public void boxNormalIsNearestWalls() {
		BoxVolume box = box(new double[] {0.0, -1.0, 0.0}, new double[] {0.0, 0.0, 1.0});

		Vector bottom = new Vector(box.getNormalAt(v(new Vector(0.5, -1.0, 1.0))).get().evaluate(), 0);
		assertEquals(-1.0, bottom.getY(), TOLERANCE);

		Vector side = new Vector(box.getNormalAt(v(new Vector(2.0, 0.2, -1.0))).get().evaluate(), 0);
		assertEquals(1.0, side.getX(), TOLERANCE);
	}

	/** Surface coordinates on every wall of a box round-trip through space. */
	@Test(timeout = 30000)
	public void boxSurfaceCoordinatesRoundTrip() {
		BoxVolume box = box(new double[] {0.0, -1.0, 0.0}, new double[] {0.0, 0.0, 1.0});

		double[][] uvs = new double[6][];
		for (int i = 0; i < uvs.length; i++) {
			uvs[i] = new double[] { (i + 0.3) / 6.0, 0.6 };
		}

		assertRoundTrip(box, uvs);
	}
}
