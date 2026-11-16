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
import org.almostrealism.primitives.PinholeCameraAbsorber;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Tests {@link PinholeCameraAbsorber}: how it assembles a pinhole aperture and an absorption-plane
 * film, the pose and dimensions it exposes, and that it absorbs, intersects and maps coordinates by
 * deferring to those two components.
 */
public class PinholeCameraAbsorberTest extends TestSuiteBase {
	/** The tolerance for comparing positions, distances, and coordinates. */
	private static final double TOLERANCE = 1e-6;

	/** The focal length used to offset the film plane behind the aperture. */
	private static final double FOCAL_LENGTH = 5.0;

	/**
	 * Returns a pinhole oriented so that its surface normal is {@code (0, 0, 1)}, its up axis is
	 * {@code (0, 1, 0)}, and its hole has the given radius.
	 *
	 * @param radius the hole radius
	 * @return the pinhole
	 */
	private Pinhole pinhole(double radius) {
		Pinhole pinhole = new Pinhole();
		pinhole.setSurfaceNormal(vector(0.0, 0.0, 1.0));
		pinhole.setOrientation(new double[] { 0.0, 1.0, 0.0 });
		pinhole.setRadius(radius);
		return pinhole;
	}

	/**
	 * Returns a four-by-four absorption plane with unit pixels, oriented to match {@link #pinhole},
	 * with the AWT display suppressed.
	 *
	 * @return the film plane
	 */
	private AbsorptionPlane film() {
		AbsorptionPlane plane = new AbsorptionPlane();
		plane.disableDisplay();
		plane.setSurfaceNormal(vector(0.0, 0.0, 1.0));
		plane.setOrientation(new double[] { 0.0, 1.0, 0.0 });
		plane.setPixelSize(1.0);
		plane.setWidth(4.0);
		plane.setHeight(4.0);
		return plane;
	}

	/**
	 * Returns a camera assembled from a fresh pinhole and film with the shared focal length.
	 *
	 * @return the camera
	 */
	private PinholeCameraAbsorber camera() {
		return new PinholeCameraAbsorber(pinhole(1.0), film(), FOCAL_LENGTH);
	}

	/** Assembly offsets the film one focal length behind the aperture along the inverse normal. */
	@Test(timeout = 30000)
	public void assemblyOffsetsFilmBehindAperture() {
		PinholeCameraAbsorber cam = camera();

		double[] planePos = cam.getPlanePosition();
		assertEquals(0.0, planePos[0], TOLERANCE);
		assertEquals(0.0, planePos[1], TOLERANCE);
		assertEquals(-FOCAL_LENGTH, planePos[2], TOLERANCE);

		assertTrue(cam.getPinhole() != null);
		assertTrue(cam.getAbsorptionPlane() != null);
	}

	/** The pose and dimension accessors read through to the film plane and aperture. */
	@Test(timeout = 30000)
	public void poseAndDimensionsAreExposed() {
		PinholeCameraAbsorber cam = camera();

		cam.setLocation(new Vector(1.0, 2.0, 3.0));
		assertEquals(2.0, cam.getLocation().getY(), TOLERANCE);

		cam.setWidth(8);
		cam.setHeight(6);
		assertEquals(8, cam.getWidth());
		assertEquals(6, cam.getHeight());

		cam.setPixelSize(2.0);
		assertEquals(2.0, cam.getPixelSize(), TOLERANCE);

		assertEquals(1.0, cam.getViewingDirection().getZ(), TOLERANCE);
		assertEquals(1.0, cam.getUpDirection().getY(), TOLERANCE);

		// The f-number is the focal length over the aperture diameter (twice the hole radius).
		cam.setFocalLength(4.0);
		assertEquals(2.0, cam.getFNumber(), TOLERANCE);

		cam.setPlanePosition(new double[] { 0.0, 0.0, -9.0 });
		assertEquals(-9.0, cam.getPlanePosition()[2], TOLERANCE);
	}

	/** A point inside the slab is inside the camera; a distant point is not. */
	@Test(timeout = 30000)
	public void insideFollowsComponents() {
		PinholeCameraAbsorber cam = camera();
		assertTrue(cam.inside(vector(0.5, 0.0, 0.0)));
		assertFalse(cam.inside(vector(0.0, 0.0, 100.0)));
	}

	/** The intersection distance is the nearest hit of the aperture or film plane. */
	@Test(timeout = 30000)
	public void intersectIsNearestComponent() {
		PinholeCameraAbsorber cam = camera();
		double d = cam.intersect(new Vector(0.0, 0.0, 5.0), new Vector(0.0, 0.0, -1.0));
		assertEquals(4.5, d, TOLERANCE);
	}

	/** A photon outside the hole but within the aperture slab is absorbed at the pinhole. */
	@Test(timeout = 30000)
	public void absorbUsesPinholeFirst() {
		PinholeCameraAbsorber cam = camera();
		assertTrue(cam.absorb(new Vector(2.0, 0.0, 0.0), new Vector(0.0, 0.0, 1.0), 1.0));
	}

	/** Coordinate mapping is delegated to the film plane. */
	@Test(timeout = 30000)
	public void spatialCoordsDelegateToFilm() {
		PinholeCameraAbsorber cam = camera();
		double[] xyz = cam.getSpatialCoords(new double[] { 1.0, 0.0 });
		assertEquals(2.0, xyz[0], TOLERANCE);
		assertEquals(2.0, xyz[1], TOLERANCE);
		assertEquals(0.0, xyz[2], TOLERANCE);
	}
}
