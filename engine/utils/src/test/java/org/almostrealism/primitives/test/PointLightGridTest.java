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

import java.util.HashSet;
import java.util.Set;

/**
 * Tests {@link PointLightGrid}: the number of lights it builds, how it shares its total intensity
 * across them, how it propagates its colour, and the degenerate normal and intersection it reports.
 *
 * <p>The grid-shape tests use rectangular grids in both orientations: the lights of a grid are its
 * samples, so every cell must hold exactly one light at its own position, stored at index
 * {@code x * height + y}, with no empty entries for a lighting sum to reach.</p>
 */
public class PointLightGridTest extends TestSuiteBase {
	/** The tolerance for comparing intensities. */
	private static final double TOLERANCE = 1e-9;

	/** The tolerance for comparing colour channels, which may be held at single precision. */
	private static final double COLOR_TOLERANCE = 1e-6;

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
	 * The prototype constructor seeds each cell with the prototype's intensity and colour, so the grid
	 * total is that intensity times the number of cells and every cell carries the prototype colour.
	 */
	@Test(timeout = 30000)
	public void prototypeSeedsIntensityOfEveryCell() {
		PointLight prototype = new PointLight(2.0, new RGB(0.25, 0.5, 0.75));
		PointLightGrid grid = new PointLightGrid(2, 2, 1.0, 1.0, prototype);

		// The total intensity is the prototype intensity times the number of cells.
		assertEquals(8.0, grid.getIntensity(), TOLERANCE);

		for (PointLight light : grid.getLights()) {
			assertEquals(2.0, light.getIntensity(), TOLERANCE);

			// A grid with no colour of its own retains the prototype colour in every cell.
			RGB c = light.getColor();
			assertEquals(0.25, c.getRed(), TOLERANCE);
			assertEquals(0.5, c.getGreen(), TOLERANCE);
			assertEquals(0.75, c.getBlue(), TOLERANCE);
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

	/**
	 * Asserts that a grid of the given dimensions holds one light per cell, each at the
	 * position of its own cell and stored at index {@code x * height + y}, and that the total
	 * intensity is shared evenly.
	 *
	 * @param width   number of lights along the X axis
	 * @param height  number of lights along the Y axis
	 */
	private void assertGrid(int width, int height) {
		double xSpace = 2.0;
		double ySpace = 3.0;
		PointLightGrid grid = new PointLightGrid(width, height, xSpace, ySpace);
		PointLight[] samples = grid.getSamples();

		assertEquals(width * height, samples.length);

		Set<String> positions = new HashSet<>();
		for (int i = 0; i < samples.length; i++) {
			assertNotNull(samples[i]);
			positions.add(samples[i].getLocation().getX() + "," + samples[i].getLocation().getY());
			assertEquals(1.0 / samples.length, samples[i].getIntensity(), TOLERANCE);
		}

		for (int x = 0; x < width; x++) {
			for (int y = 0; y < height; y++) {
				assertTrue(positions.contains((x * xSpace) + "," + (y * ySpace)));
				PointLight light = samples[x * height + y];
				assertEquals(x * xSpace, light.getLocation().getX(), TOLERANCE);
				assertEquals(y * ySpace, light.getLocation().getY(), TOLERANCE);
			}
		}

		assertEquals(width * height, positions.size());
		assertTrue(samples == grid.getSamples(1));
		assertEquals(1.0, grid.getIntensity(), TOLERANCE);
	}

	/** A grid wider than it is tall has a light in every cell. */
	@Test(timeout = 30000)
	public void wideGridHasOneLightPerCell() {
		assertGrid(3, 2);
	}

	/** A grid taller than it is wide has a light in every cell. */
	@Test(timeout = 30000)
	public void tallGridHasOneLightPerCell() {
		assertGrid(2, 3);
	}

	/** A square grid, and the degenerate single-row and single-light grids. */
	@Test(timeout = 30000)
	public void squareAndDegenerateGrids() {
		assertGrid(3, 3);
		assertGrid(1, 4);
		assertGrid(4, 1);
		assertGrid(1, 1);
	}

	/**
	 * Rebuilding a grid from a prototype gives every light the intensity of the prototype and
	 * the colour of the grid, and the grid the sum of the intensities of its lights.
	 */
	@Test(timeout = 30000)
	public void updateGridCopiesPrototype() {
		int width = 3;
		int height = 2;
		PointLightGrid grid = new PointLightGrid(1, 1, 1.0, 1.0);
		grid.updateGrid(width, height, 1.0, 1.0, new PointLight(0.5, new RGB(0.2, 0.4, 0.6)));

		PointLight[] samples = grid.getSamples();
		assertEquals(width * height, samples.length);

		for (int x = 0; x < width; x++) {
			for (int y = 0; y < height; y++) {
				PointLight light = samples[x * height + y];
				assertEquals(x, light.getLocation().getX(), TOLERANCE);
				assertEquals(y, light.getLocation().getY(), TOLERANCE);
				assertEquals(0.5, light.getIntensity(), TOLERANCE);
				assertTrue(grid.getColor() == light.getColor());
			}
		}

		assertEquals(0.5 * width * height, grid.getIntensity(), TOLERANCE);
	}

	/**
	 * A grid built from a coloured prototype holds its own copy of the prototype colour, shared
	 * by every light, so a later change to the prototype's colour does not reach the grid; a
	 * grid built without a prototype is white.
	 */
	@Test(timeout = 30000)
	public void gridCopiesPrototypeColor() {
		RGB prototypeColor = new RGB(0.2, 0.4, 0.6);
		PointLightGrid grid = new PointLightGrid(2, 3, 1.0, 1.0, new PointLight(0.5, prototypeColor));

		assertTrue(prototypeColor != grid.getColor());
		assertEquals(0.2, grid.getColor().getRed(), COLOR_TOLERANCE);
		assertEquals(0.4, grid.getColor().getGreen(), COLOR_TOLERANCE);
		assertEquals(0.6, grid.getColor().getBlue(), COLOR_TOLERANCE);

		for (PointLight light : grid.getSamples()) {
			assertTrue(grid.getColor() == light.getColor());
		}

		prototypeColor.setRed(0.9);
		assertEquals(0.2, grid.getColor().getRed(), COLOR_TOLERANCE);

		RGB white = new PointLightGrid(2, 2, 1.0, 1.0).getColor();
		assertEquals(1.0, white.getRed(), COLOR_TOLERANCE);
		assertEquals(1.0, white.getGreen(), COLOR_TOLERANCE);
		assertEquals(1.0, white.getBlue(), COLOR_TOLERANCE);
	}

	/**
	 * Rebuilding a grid that already has an explicit colour replaces that colour with a copy
	 * of the new prototype's colour, shared by every rebuilt light, and a later
	 * {@link PointLightGrid#setColor(RGB)} recolours every rebuilt light.
	 */
	@Test(timeout = 30000)
	public void updateGridReplacesExplicitColor() {
		PointLightGrid grid = new PointLightGrid(2, 2, 1.0, 1.0);
		grid.setColor(new RGB(1.0, 0.0, 0.0));

		RGB prototypeColor = new RGB(0.1, 0.3, 0.5);
		grid.updateGrid(3, 2, 1.0, 1.0, new PointLight(0.25, prototypeColor));

		assertTrue(prototypeColor != grid.getColor());
		assertEquals(0.1, grid.getColor().getRed(), COLOR_TOLERANCE);
		assertEquals(0.3, grid.getColor().getGreen(), COLOR_TOLERANCE);
		assertEquals(0.5, grid.getColor().getBlue(), COLOR_TOLERANCE);

		for (PointLight light : grid.getSamples()) {
			assertTrue(grid.getColor() == light.getColor());
		}

		grid.setColor(new RGB(0.0, 1.0, 0.0));
		for (PointLight light : grid.getSamples()) {
			assertEquals(0.0, light.getColor().getRed(), COLOR_TOLERANCE);
			assertEquals(1.0, light.getColor().getGreen(), COLOR_TOLERANCE);
			assertEquals(0.0, light.getColor().getBlue(), COLOR_TOLERANCE);
		}
	}
}
