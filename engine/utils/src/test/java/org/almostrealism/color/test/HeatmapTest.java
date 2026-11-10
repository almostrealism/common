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

package org.almostrealism.color.test;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.color.RGB;
import org.almostrealism.color.RGBFeatures;
import org.almostrealism.texture.GraphicsConverter;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * Tests the colour-mapped heatmap field visualisation on {@link RGBFeatures}: the exact pixel
 * colours a known field maps to, clamping of a fixed range shared across frames, and a round trip
 * through a PNG file.
 */
public class HeatmapTest extends TestSuiteBase implements RGBFeatures {
	/** Tolerance for colour comparisons. */
	private static final double EPS = 1e-4;

	/**
	 * A 2x2 field with the default blue-to-red colour map and a fixed range maps each cell to the
	 * expected colour. Field values {@code 0, 1, 0.5, 2} over the range {@code [0, 2]} normalise to
	 * {@code t = 0, 0.5, 0.25, 1}, which map to {@code (t, 0, 1 - t)}: blue, purple, blue-purple,
	 * red.
	 */
	@Test(timeout = 60000)
	public void knownFieldColours() {
		PackedCollection field = pack(0.0, 1.0, 0.5, 2.0).reshape(shape(2, 2));

		PackedCollection image = heatmap(cp(field), 0.0, 2.0).evaluate();

		assertPixel(image, 0, 0, 0.0, 0.0, 1.0);
		assertPixel(image, 0, 1, 0.5, 0.0, 0.5);
		assertPixel(image, 1, 0, 0.25, 0.0, 0.75);
		assertPixel(image, 1, 1, 1.0, 0.0, 0.0);
	}

	/**
	 * Values outside the fixed range are clamped: a value below {@code min} maps to the low colour
	 * (here black) and a value above {@code max} maps to the high colour (here white), so frames
	 * sharing a range share a scale even when some frames exceed it.
	 */
	@Test(timeout = 60000)
	public void fixedRangeClampsOutOfRange() {
		PackedCollection field = pack(-5.0, 5.0, 0.0, 1.0).reshape(shape(2, 2));

		PackedCollection image = heatmap(cp(field), 0.0, 1.0,
				new RGB(0.0, 0.0, 0.0), new RGB(1.0, 1.0, 1.0)).evaluate();

		assertPixel(image, 0, 0, 0.0, 0.0, 0.0);
		assertPixel(image, 0, 1, 1.0, 1.0, 1.0);
		assertPixel(image, 1, 0, 0.0, 0.0, 0.0);
		assertPixel(image, 1, 1, 1.0, 1.0, 1.0);
	}

	/**
	 * Saving a heatmap to a PNG and reading it back recovers the mapped colours within the 8-bit
	 * quantisation of the file. The top-left pixel is the field's first cell (value {@code 0.0},
	 * blue) and the bottom-right pixel is its last cell (value {@code 2.0}, red).
	 */
	@Test(timeout = 60000)
	public void savedHeatmapRoundTrips() throws IOException {
		PackedCollection field = pack(0.0, 1.0, 0.5, 2.0).reshape(shape(2, 2));

		File file = File.createTempFile("heatmap-test", ".png");
		file.deleteOnExit();

		saveHeatmap(file, "png", cp(field), 0.0, 2.0).get().run();

		BufferedImage decoded = ImageIO.read(file);
		Assert.assertEquals(2, decoded.getWidth());
		Assert.assertEquals(2, decoded.getHeight());

		RGB topLeft = GraphicsConverter.convertToRGB(new Color(decoded.getRGB(0, 0)));
		RGB bottomRight = GraphicsConverter.convertToRGB(new Color(decoded.getRGB(1, 1)));

		Assert.assertEquals(0.0, topLeft.getRed(), 0.02);
		Assert.assertEquals(0.0, topLeft.getGreen(), 0.02);
		Assert.assertEquals(1.0, topLeft.getBlue(), 0.02);
		Assert.assertEquals(1.0, bottomRight.getRed(), 0.02);
		Assert.assertEquals(0.0, bottomRight.getGreen(), 0.02);
		Assert.assertEquals(0.0, bottomRight.getBlue(), 0.02);
	}

	/**
	 * Saving without an explicit range scales the colours to the field's own minimum and maximum:
	 * the cell holding the field minimum is blue and the cell holding the field maximum is red.
	 */
	@Test(timeout = 60000)
	public void autoRangeScalesToField() throws IOException {
		PackedCollection field = pack(0.0, 1.0, 0.5, 2.0).reshape(shape(2, 2));

		File file = File.createTempFile("heatmap-auto-test", ".png");
		file.deleteOnExit();

		saveHeatmap(file, "png", cp(field)).get().run();

		BufferedImage decoded = ImageIO.read(file);
		RGB minCell = GraphicsConverter.convertToRGB(new Color(decoded.getRGB(0, 0)));
		RGB maxCell = GraphicsConverter.convertToRGB(new Color(decoded.getRGB(1, 1)));

		Assert.assertEquals(1.0, minCell.getBlue(), 0.02);
		Assert.assertEquals(0.0, minCell.getRed(), 0.02);
		Assert.assertEquals(1.0, maxCell.getRed(), 0.02);
		Assert.assertEquals(0.0, maxCell.getBlue(), 0.02);
	}

	/**
	 * A flat field has no range of its own, so auto-range saving gives it a unit range and every
	 * cell maps to the low colour (blue) rather than dividing by a zero range. This exercises the
	 * degenerate-range guard in {@code saveHeatmap(File, String, Producer)}.
	 */
	@Test(timeout = 60000)
	public void autoRangeFlatFieldMapsToLow() throws IOException {
		PackedCollection field = pack(3.0, 3.0, 3.0, 3.0).reshape(shape(2, 2));

		File file = File.createTempFile("heatmap-flat-test", ".png");
		file.deleteOnExit();

		saveHeatmap(file, "png", cp(field)).get().run();

		BufferedImage decoded = ImageIO.read(file);
		for (int y = 0; y < decoded.getHeight(); y++) {
			for (int x = 0; x < decoded.getWidth(); x++) {
				RGB cell = GraphicsConverter.convertToRGB(new Color(decoded.getRGB(x, y)));
				Assert.assertEquals("red at " + x + "," + y, 0.0, cell.getRed(), 0.02);
				Assert.assertEquals("green at " + x + "," + y, 0.0, cell.getGreen(), 0.02);
				Assert.assertEquals("blue at " + x + "," + y, 1.0, cell.getBlue(), 0.02);
			}
		}
	}

	/**
	 * A range whose {@code max} is not greater than its {@code min} is rejected with
	 * {@link IllegalArgumentException} when the heatmap is built, so a degenerate scale cannot
	 * silently divide by zero: both {@code max == min} and {@code max < min} are refused.
	 */
	@Test(timeout = 60000)
	public void rejectsInvalidRange() {
		PackedCollection field = pack(0.0, 1.0, 0.5, 2.0).reshape(shape(2, 2));

		assertThrowsIllegalArgument(() -> heatmap(cp(field), 1.0, 1.0));
		assertThrowsIllegalArgument(() -> heatmap(cp(field), 2.0, 1.0));
	}

	/**
	 * A field that is not 2D is rejected with {@link IllegalArgumentException}: a heatmap needs a
	 * {@code (height, width)} field, so both a 1D field and a 3D field are refused.
	 */
	@Test(timeout = 60000)
	public void rejectsNonTwoDimensionalField() {
		PackedCollection oneD = pack(0.0, 1.0, 2.0, 3.0);
		PackedCollection threeD = pack(0.0, 1.0, 2.0, 3.0).reshape(shape(2, 2, 1));

		assertThrowsIllegalArgument(() -> heatmap(cp(oneD), 0.0, 1.0));
		assertThrowsIllegalArgument(() -> heatmap(cp(threeD), 0.0, 1.0));
	}

	/**
	 * Asserts that {@code action} throws {@link IllegalArgumentException}.
	 *
	 * @param action the action expected to throw
	 */
	private static void assertThrowsIllegalArgument(Runnable action) {
		try {
			action.run();
			Assert.fail("Expected an IllegalArgumentException");
		} catch (IllegalArgumentException expected) {
			// expected
		}
	}

	/**
	 * Asserts that the pixel at {@code (row, col)} of a {@code (height, width, 3)} image has the
	 * expected colour.
	 *
	 * @param image the evaluated heatmap, shape {@code (height, width, 3)}
	 * @param row   the pixel row
	 * @param col   the pixel column
	 * @param r     the expected red channel
	 * @param g     the expected green channel
	 * @param b     the expected blue channel
	 */
	private void assertPixel(PackedCollection image, int row, int col, double r, double g, double b) {
		assertEquals(r, image.valueAt(row, col, 0), EPS);
		assertEquals(g, image.valueAt(row, col, 1), EPS);
		assertEquals(b, image.valueAt(row, col, 2), EPS);
	}
}
