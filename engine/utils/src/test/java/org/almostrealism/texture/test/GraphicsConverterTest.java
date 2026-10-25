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

package org.almostrealism.texture.test;

import io.almostrealism.relation.Evaluable;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.color.RGB;
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
 * Tests for {@link GraphicsConverter}, the bridge between Almost Realism color types
 * and AWT graphics. Covers color conversion, pixel extraction, image loading into
 * {@link PackedCollection}, histogramming, and round-tripping back to AWT images.
 */
public class GraphicsConverterTest extends TestSuiteBase {
	/** Numerical tolerance for floating-point comparisons. */
	private static final double TOL = 1e-9;

	/**
	 * Writes a known 2x2 image (red, green, blue, white) to a temporary PNG file.
	 *
	 * @return the temporary file containing the image
	 * @throws IOException if the file cannot be written
	 */
	private File writeSampleImage() throws IOException {
		BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
		image.setRGB(0, 0, 0xFF0000);
		image.setRGB(1, 0, 0x00FF00);
		image.setRGB(0, 1, 0x0000FF);
		image.setRGB(1, 1, 0xFFFFFF);

		File file = File.createTempFile("texture-test", ".png");
		file.deleteOnExit();
		ImageIO.write(image, "png", file);
		return file;
	}

	/**
	 * Verifies AWT {@link Color} to {@link RGB} conversion scales each 0-255 channel
	 * into [0, 1].
	 */
	@Test(timeout = 30000)
	public void convertColorToRgb() {
		RGB red = GraphicsConverter.convertToRGB(new Color(255, 0, 0));
		Assert.assertEquals(1.0, red.getRed(), TOL);
		Assert.assertEquals(0.0, red.getGreen(), TOL);
		Assert.assertEquals(0.0, red.getBlue(), TOL);

		RGB gray = GraphicsConverter.convertToRGB(new Color(128, 64, 32));
		Assert.assertEquals(128 / 255d, gray.getRed(), TOL);
		Assert.assertEquals(64 / 255d, gray.getGreen(), TOL);
		Assert.assertEquals(32 / 255d, gray.getBlue(), TOL);
	}

	/**
	 * Verifies {@link RGB} to AWT {@link Color} conversion clamps the magnitude of
	 * each channel to [0, 1] (taking absolute value and capping at 1.0).
	 */
	@Test(timeout = 30000)
	public void convertRgbToAwtColorClamps() {
		Color color = GraphicsConverter.convertToAWTColor(new RGB(2.0, -0.5, 0.25));
		Assert.assertEquals(255, color.getRed());
		Assert.assertEquals((int) (0.5f * 255 + 0.5f), color.getGreen());
		Assert.assertEquals((int) (0.25f * 255 + 0.5f), color.getBlue());
	}

	/**
	 * Verifies 32-bit extraction writes width/height into the first two slots and
	 * the packed pixel colors thereafter.
	 */
	@Test(timeout = 30000)
	public void extract32BitImageHeaderAndPixels() {
		BufferedImage image = new BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB);
		image.setRGB(0, 0, 0xFF0000);
		image.setRGB(1, 0, 0x00FF00);

		int[] rgb = GraphicsConverter.extract32BitImage(image);
		Assert.assertEquals(4, rgb.length);
		Assert.assertEquals(2, rgb[0]);
		Assert.assertEquals(1, rgb[1]);
		Assert.assertEquals(0xFF0000, rgb[2] & 0xFFFFFF);
		Assert.assertEquals(0x00FF00, rgb[3] & 0xFFFFFF);
	}

	/**
	 * Verifies 8-bit extraction encodes dimensions as big-endian headers and packs
	 * each pixel into a 6-bit (2 bits per channel) value.
	 */
	@Test(timeout = 30000)
	public void extract8BitImageHeaderAndPixels() {
		BufferedImage image = new BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB);
		image.setRGB(0, 0, 0xFF0000);
		image.setRGB(1, 0, 0x00FF00);

		byte[] rgb = GraphicsConverter.extract8BitImage(image);
		Assert.assertEquals(10, rgb.length);

		int width = (rgb[0] << 24) | (rgb[1] << 16) | (rgb[2] << 8) | (rgb[3] & 0xFF);
		int height = (rgb[4] << 24) | (rgb[5] << 16) | (rgb[6] << 8) | (rgb[7] & 0xFF);
		Assert.assertEquals(2, width);
		Assert.assertEquals(1, height);

		// red: r=255/4=63 -> (63<<4) = 1008; green: g=255/4=63 -> (63<<2) = 252
		Assert.assertEquals(1008 & 0xFF, rgb[8] & 0xFF);
		Assert.assertEquals(252, rgb[9] & 0xFF);
	}

	/**
	 * Verifies a flat packed-ARGB array is unpacked into a column-major 2D RGB array.
	 */
	@Test(timeout = 30000)
	public void convertFlatArrayToRgbArray() {
		int[] pixels = new int[] {0xFF0000, 0x00FF00, 0x0000FF, 0xFFFFFF};
		RGB[][] rgb = GraphicsConverter.convertToRGBArray(pixels, 0, 0, 0, 2, 2, 2);

		Assert.assertEquals(1.0, rgb[0][0].getRed(), TOL);
		Assert.assertEquals(1.0, rgb[1][0].getGreen(), TOL);
		Assert.assertEquals(1.0, rgb[0][1].getBlue(), TOL);
		Assert.assertEquals(1.0, rgb[1][1].getRed(), TOL);
		Assert.assertEquals(1.0, rgb[1][1].getGreen(), TOL);
		Assert.assertEquals(1.0, rgb[1][1].getBlue(), TOL);
	}

	/**
	 * Verifies channels-last loading produces shape [height, width, 3] with each
	 * pixel's channels in [0, 1].
	 */
	@Test(timeout = 30000)
	public void loadRgbChannelsLast() throws IOException {
		PackedCollection image = GraphicsConverter.loadRgb(writeSampleImage());

		Assert.assertEquals(2, image.getShape().length(0));
		Assert.assertEquals(2, image.getShape().length(1));
		Assert.assertEquals(3, image.getShape().length(2));

		Assert.assertEquals(1.0, image.valueAt(0, 0, 0), TOL);
		Assert.assertEquals(0.0, image.valueAt(0, 0, 1), TOL);
		Assert.assertEquals(1.0, image.valueAt(0, 1, 1), TOL);
		Assert.assertEquals(1.0, image.valueAt(1, 0, 2), TOL);
	}

	/**
	 * Verifies channels-first loading produces shape [3, height, width] with the
	 * same per-pixel values addressed as [channel, row, col].
	 */
	@Test(timeout = 30000)
	public void loadChannelsFirst() throws IOException {
		PackedCollection image = GraphicsConverter.loadChannels(writeSampleImage());

		Assert.assertEquals(3, image.getShape().length(0));
		Assert.assertEquals(2, image.getShape().length(1));
		Assert.assertEquals(2, image.getShape().length(2));

		Assert.assertEquals(1.0, image.valueAt(0, 0, 0), TOL);
		Assert.assertEquals(1.0, image.valueAt(1, 0, 1), TOL);
		Assert.assertEquals(1.0, image.valueAt(2, 1, 0), TOL);
	}

	/**
	 * Verifies grayscale loading averages the three channels per pixel.
	 */
	@Test(timeout = 30000)
	public void loadGrayscaleAveragesChannels() throws IOException {
		PackedCollection image = GraphicsConverter.loadGrayscale(writeSampleImage());

		Assert.assertEquals(2, image.getShape().length(0));
		Assert.assertEquals(2, image.getShape().length(1));
		Assert.assertEquals(1, image.getShape().length(2));

		Assert.assertEquals(1 / 3d, image.valueAt(0, 0, 0), TOL);
		Assert.assertEquals(1.0, image.valueAt(1, 1, 0), TOL);
	}

	/**
	 * Verifies loading into a destination of the wrong dimensionality is rejected.
	 */
	@Test(timeout = 30000)
	public void loadRgbRejectsWrongDimensions() {
		BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
		PackedCollection twoDimensional = new PackedCollection(2, 2);

		boolean threw = false;
		try {
			GraphicsConverter.loadRgb(twoDimensional, image, 0, 0, 1, 1, false);
		} catch (IllegalArgumentException e) {
			threw = true;
		}
		Assert.assertTrue(threw);
	}

	/**
	 * Verifies grayscale loading into a destination of the wrong dimensionality is
	 * rejected.
	 */
	@Test(timeout = 30000)
	public void loadGrayscaleRejectsWrongDimensions() {
		BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
		PackedCollection twoDimensional = new PackedCollection(2, 2);

		boolean threw = false;
		try {
			GraphicsConverter.loadGrayscale(twoDimensional, image, 0, 0, 1, 1);
		} catch (IllegalArgumentException e) {
			threw = true;
		}
		Assert.assertTrue(threw);
	}

	/**
	 * Verifies the luminance histogram bins every pixel of a solid-white image into
	 * the top bucket.
	 */
	@Test(timeout = 30000)
	public void histogramBinsByLuminance() {
		BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
		for (int x = 0; x < 2; x++) {
			for (int y = 0; y < 2; y++) {
				image.setRGB(x, y, 0xFFFFFF);
			}
		}

		double[] histogram = GraphicsConverter.histogram(image, 0, 0, 2, 2, 4);
		Assert.assertEquals(0.0, histogram[0], TOL);
		Assert.assertEquals(0.0, histogram[1], TOL);
		Assert.assertEquals(0.0, histogram[2], TOL);
		Assert.assertEquals(4.0, histogram[3], TOL);
	}

	/**
	 * Verifies converting a channels-last {@link PackedCollection} back to an AWT
	 * {@link BufferedImage} reproduces the original pixel colors.
	 */
	@Test(timeout = 30000)
	public void packedCollectionRoundTripsToAwtImage() throws IOException {
		PackedCollection image = GraphicsConverter.loadRgb(writeSampleImage());
		BufferedImage out = GraphicsConverter.convertToAWTImage(image, false);

		Assert.assertEquals(2, out.getWidth());
		Assert.assertEquals(2, out.getHeight());
		Assert.assertEquals(0xFF0000, out.getRGB(0, 0) & 0xFFFFFF);
		Assert.assertEquals(0x00FF00, out.getRGB(1, 0) & 0xFFFFFF);
		Assert.assertEquals(0x0000FF, out.getRGB(0, 1) & 0xFFFFFF);
		Assert.assertEquals(0xFFFFFF, out.getRGB(1, 1) & 0xFFFFFF);
	}

	/**
	 * Verifies converting a channels-first {@link PackedCollection} back to an AWT
	 * {@link BufferedImage} reproduces the original pixel colors.
	 */
	@Test(timeout = 30000)
	public void channelsFirstRoundTripsToAwtImage() throws IOException {
		PackedCollection image = GraphicsConverter.loadChannels(writeSampleImage());
		BufferedImage out = GraphicsConverter.convertToAWTImage(image, true);

		Assert.assertEquals(0xFF0000, out.getRGB(0, 0) & 0xFFFFFF);
		Assert.assertEquals(0xFFFFFF, out.getRGB(1, 1) & 0xFFFFFF);
	}

	/**
	 * Verifies evaluating an array of color producers yields the corresponding RGB
	 * values.
	 */
	@Test(timeout = 30000)
	public void convertEvaluableArrayToRgbArray() {
		RGB color = new RGB(0.25, 0.5, 0.75);
		Evaluable<PackedCollection>[][] image = new Evaluable[1][1];
		image[0][0] = args -> color;

		RGB[][] result = GraphicsConverter.convertToRGBArray(image);
		Assert.assertEquals(0.25, result[0][0].getRed(), TOL);
		Assert.assertEquals(0.5, result[0][0].getGreen(), TOL);
		Assert.assertEquals(0.75, result[0][0].getBlue(), TOL);
	}
}
