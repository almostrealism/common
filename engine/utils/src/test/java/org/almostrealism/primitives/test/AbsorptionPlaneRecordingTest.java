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
import org.almostrealism.color.RGB;
import org.almostrealism.primitives.AbsorptionPlane;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Tests the photon-recording side of {@link AbsorptionPlane}: before anything is absorbed it has no
 * image, a photon landing on the sensor grid is recorded into the image and energy map, and repeated
 * absorptions in the same cell accumulate their energy.
 */
// TODO(review): recording tests fail on pre-existing bug — AbsorptionPlane.absorb throws IllegalArgumentException constructing new RGB(wavelength) (see review-followup memory).
public class AbsorptionPlaneRecordingTest extends TestSuiteBase {
	/** The tolerance for comparing accumulated energy values. */
	private static final double TOLERANCE = 1e-9;

	/** The direction of an arriving photon; it is ignored by the absorber. */
	private static final Vector INCOMING = new Vector(0.0, 0.0, 1.0);

	/**
	 * Returns a four-by-four absorption plane with unit pixels, oriented so that its across axis is
	 * {@code (1, 0, 0)} and its slab lies in the xy plane, with the AWT display suppressed.
	 *
	 * @return the plane
	 */
	private AbsorptionPlane plane() {
		AbsorptionPlane plane = new AbsorptionPlane();
		plane.disableDisplay();
		plane.setOrientation(new double[] { 0.0, 1.0, 0.0 });
		plane.setSurfaceNormal(vector(0.0, 0.0, 1.0));
		plane.setPixelSize(1.0);
		plane.setWidth(4.0);
		plane.setHeight(4.0);
		return plane;
	}

	/** A fresh plane has no image or energy yet, so both come back as the empty one-by-zero array. */
	@Test(timeout = 30000)
	public void freshPlaneHasNoImage() {
		AbsorptionPlane plane = plane();

		assertFalse(plane.imageAvailable());
		assertEquals(1, plane.getImage().length);
		assertEquals(0, plane.getImage()[0].length);
		assertEquals(1, plane.getEnergyMap().length);
		assertEquals(0, plane.getEnergyMap()[0].length);
	}

	/** A photon landing on the grid is recorded, making the image available with a filled cell. */
	@Test(timeout = 30000)
	public void photonOnGridIsRecorded() {
		AbsorptionPlane plane = plane();

		// A photon at the centre of the slab maps to the centre cell (2, 2) of the grid.
		assertTrue(plane.absorb(new Vector(0.0, 0.0, 0.0), INCOMING, 2.0));
		assertTrue(plane.imageAvailable());

		RGB[][] image = plane.getImage();
		assertEquals(4, image.length);
		assertEquals(4, image[0].length);
		assertTrue(image[2][2] != null);

		// The energy map, normalised by the unit maximum, holds the deposited energy.
		assertEquals(2.0, plane.getEnergyMap()[2][2].getRed(), TOLERANCE);
	}

	/** Two photons in the same cell accumulate their energy there. */
	@Test(timeout = 30000)
	public void repeatedAbsorptionAccumulatesEnergy() {
		AbsorptionPlane plane = plane();

		assertTrue(plane.absorb(new Vector(0.0, 0.0, 0.0), INCOMING, 2.0));
		assertTrue(plane.absorb(new Vector(0.0, 0.0, 0.0), INCOMING, 3.0));

		assertEquals(5.0, plane.getEnergyMap()[2][2].getRed(), TOLERANCE);
	}

	/** A photon beyond the slab thickness is not recorded and leaves the image empty. */
	@Test(timeout = 30000)
	public void photonBeyondSlabIsNotRecorded() {
		AbsorptionPlane plane = plane();

		assertFalse(plane.absorb(new Vector(0.0, 0.0, 1.0), INCOMING, 2.0));
		assertFalse(plane.imageAvailable());
	}
}
