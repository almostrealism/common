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
import org.almostrealism.color.PointLight;
import org.almostrealism.color.RGB;
import org.almostrealism.primitives.PointLightGrid;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Tests {@link PointLightGrid}: the number of lights it builds, how it shares its total intensity
 * across them, how it propagates its colour, and the degenerate normal and intersection it reports.
 */
// TODO(review): all tests fail on pre-existing bug — PointLightGrid.updateGrid NPEs because getTransform(true) returns null for a default geometry (see review-followup memory).
public class PointLightGridTest extends TestSuiteBase {
	/** The tolerance for comparing intensities. */
	private static final double TOLERANCE = 1e-9;

	/** A grid holds width times height lights, each carrying an equal share of the total intensity. */
	@Test(timeout = 30000)
	public void gridSharesIntensityEqually() {
		PointLightGrid grid = new PointLightGrid(3, 2, 1.0, 1.0);

		assertEquals(6, grid.getLights().length);
		assertEquals(1.0, grid.getIntensity(), TOLERANCE);

		for (PointLight light : grid.getLights()) {
			assertEquals(1.0 / 6.0, light.getIntensity(), TOLERANCE);
		}
	}

	/** Setting the total intensity redistributes it evenly across the lights. */
	@Test(timeout = 30000)
	public void settingIntensityRedistributes() {
		PointLightGrid grid = new PointLightGrid(2, 2, 1.0, 1.0);
		grid.setIntensity(8.0);

		assertEquals(8.0, grid.getIntensity(), TOLERANCE);
		for (PointLight light : grid.getLights()) {
			assertEquals(2.0, light.getIntensity(), TOLERANCE);
		}
	}

	/**
	 * The prototype constructor seeds each cell with the prototype's intensity, so the grid total is
	 * that intensity times the number of cells. (The prototype's colour is not retained: a grid with
	 * no colour of its own defaults every cell to white.)
	 */
	@Test(timeout = 30000)
	public void prototypeSeedsIntensityOfEveryCell() {
		PointLight prototype = new PointLight(2.0, new RGB(0.25, 0.5, 0.75));
		PointLightGrid grid = new PointLightGrid(2, 2, 1.0, 1.0, prototype);

		// The total intensity is the prototype intensity times the number of cells.
		assertEquals(8.0, grid.getIntensity(), TOLERANCE);

		for (PointLight light : grid.getLights()) {
			assertEquals(2.0, light.getIntensity(), TOLERANCE);

			// The grid defaulted its colour to white, overriding the prototype colour.
			RGB c = light.getColor();
			assertEquals(1.0, c.getRed(), TOLERANCE);
			assertEquals(1.0, c.getGreen(), TOLERANCE);
			assertEquals(1.0, c.getBlue(), TOLERANCE);
		}
	}

	/** Setting the colour recolours every light already in the grid. */
	@Test(timeout = 30000)
	public void settingColorRecolorsLights() {
		PointLightGrid grid = new PointLightGrid(2, 2, 1.0, 1.0);
		grid.setColor(new RGB(1.0, 0.0, 0.0));

		for (PointLight light : grid.getLights()) {
			RGB c = light.getColor();
			assertEquals(1.0, c.getRed(), TOLERANCE);
			assertEquals(0.0, c.getGreen(), TOLERANCE);
			assertEquals(0.0, c.getBlue(), TOLERANCE);
		}
	}

	/** A grid is a flat emitter with a zero normal and no ray intersection. */
	@Test(timeout = 30000)
	public void gridHasZeroNormalAndNoIntersection() {
		PointLightGrid grid = new PointLightGrid(2, 2, 1.0, 1.0);

		Vector normal = new Vector(grid.getNormalAt(vector(1.0, 2.0, 3.0)).get().evaluate(), 0);
		assertEquals(0.0, normal.getX(), TOLERANCE);
		assertEquals(0.0, normal.getY(), TOLERANCE);
		assertEquals(0.0, normal.getZ(), TOLERANCE);

		assertTrue(grid.intersectAt(null) == null);
		assertTrue(grid.expect() == null);
		assertTrue(grid.get() == null);
	}
}
