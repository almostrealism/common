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

import org.almostrealism.color.RGB;
import org.almostrealism.texture.ImageCanvas;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Tests for {@link ImageCanvas}, an off-screen RGB raster with plotting and several
 * image-encoding formats.
 */
public class ImageCanvasTest extends TestSuiteBase {
	/** Numerical tolerance for floating-point comparisons. */
	private static final double TOL = 1e-9;

	/**
	 * Verifies the scale/offset accessors round-trip and the default constructor
	 * allocates a cleared (all-black) image of the requested dimensions.
	 */
	@Test(timeout = 30000)
	public void constructionAndAccessors() {
		ImageCanvas canvas = new ImageCanvas(4, 3);

		Assert.assertEquals(1.0, canvas.getXScale(), TOL);
		Assert.assertEquals(1.0, canvas.getYScale(), TOL);
		Assert.assertEquals(0.0, canvas.getXOffset(), TOL);
		Assert.assertEquals(0.0, canvas.getYOffset(), TOL);

		RGB[][] data = canvas.getImageData();
		Assert.assertEquals(4, data.length);
		Assert.assertEquals(3, data[0].length);
		Assert.assertEquals(0.0, data[2][1].getRed(), TOL);

		canvas.setXScale(2.0);
		canvas.setYScale(3.0);
		canvas.setXOffset(0.5);
		canvas.setYOffset(-0.5);
		Assert.assertEquals(2.0, canvas.getXScale(), TOL);
		Assert.assertEquals(3.0, canvas.getYScale(), TOL);
		Assert.assertEquals(0.5, canvas.getXOffset(), TOL);
		Assert.assertEquals(-0.5, canvas.getYOffset(), TOL);
	}

	/**
	 * Verifies plotting the origin lands at the center of the raster under the
	 * default unit scale and zero offset.
	 */
	@Test(timeout = 30000)
	public void plotOriginMapsToCenter() {
		ImageCanvas canvas = new ImageCanvas(4, 4);
		RGB color = new RGB(1.0, 0.0, 0.0);

		canvas.plot(0.0, 0.0, color);
		Assert.assertSame(color, canvas.getImageData()[2][2]);
	}

	/**
	 * Verifies plotting outside the raster bounds is silently ignored rather than
	 * throwing.
	 */
	@Test(timeout = 30000)
	public void plotOutOfBoundsIsIgnored() {
		ImageCanvas canvas = new ImageCanvas(4, 4);
		canvas.plot(1000.0, 1000.0, new RGB(1.0, 1.0, 1.0));

		for (int i = 0; i < 4; i++) {
			for (int j = 0; j < 4; j++) {
				Assert.assertEquals(0.0, canvas.getImageData()[i][j].getRed(), TOL);
			}
		}
	}

	/**
	 * Verifies the explicit pixel and whole-image setters update the stored raster.
	 */
	@Test(timeout = 30000)
	public void imageDataSetters() {
		ImageCanvas canvas = new ImageCanvas(2, 2);
		RGB pixel = new RGB(0.1, 0.2, 0.3);
		canvas.setImageData(1, 0, pixel);
		Assert.assertSame(pixel, canvas.getImageData()[1][0]);

		RGB[][] replacement = new RGB[][] {{new RGB(0.5, 0.5, 0.5)}};
		canvas.setImageData(replacement);
		Assert.assertSame(replacement, canvas.getImageData());
	}

	/**
	 * Verifies PPM encoding emits the {@code P3} magic header and the expected pixel
	 * triplet for a white pixel.
	 */
	@Test(timeout = 30000)
	public void writeImagePpm() throws IOException {
		RGB[][] image = new RGB[][] {{new RGB(1.0, 1.0, 1.0)}};
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageCanvas.writeImage(image, out, ImageCanvas.PPMEncoding);

		String text = out.toString(StandardCharsets.UTF_8);
		Assert.assertTrue(text.startsWith("P3"));
		Assert.assertTrue(text.contains("255 255 255"));
	}

	/**
	 * Verifies PIX encoding writes the fixed-size byte buffer with the dimension
	 * header and 24-bit depth marker.
	 */
	@Test(timeout = 30000)
	public void writeImagePix() throws IOException {
		RGB[][] image = new RGB[][] {{new RGB(1.0, 1.0, 1.0)}};
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageCanvas.writeImage(image, out, ImageCanvas.PIXEncoding);

		byte[] bytes = out.toByteArray();
		Assert.assertEquals(4 * 1 * 1 + 10, bytes.length);
		Assert.assertEquals(0, bytes[0]);
		Assert.assertEquals(1, bytes[1]);
		Assert.assertEquals(24, bytes[9]);
	}

	/**
	 * Verifies the serialized RGB-list encoding round-trips a pixel's channels back
	 * through {@link RGB#readExternal}.
	 */
	@Test(timeout = 30000)
	public void writeImageRgbListRoundTrips() throws IOException {
		RGB[][] image = new RGB[][] {{new RGB(0.25, 0.5, 0.75)}};
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageCanvas.writeImage(image, out, ImageCanvas.RGBListEncoding);

		try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(out.toByteArray()))) {
			RGB decoded = new RGB();
			decoded.readExternal(in);
			Assert.assertEquals(0.25, decoded.getRed(), 1e-3);
			Assert.assertEquals(0.5, decoded.getGreen(), 1e-3);
			Assert.assertEquals(0.75, decoded.getBlue(), 1e-3);
		}
	}

	/**
	 * Verifies JPEG encoding (written as PNG) produces a decodable image of the
	 * original dimensions.
	 */
	@Test(timeout = 30000)
	public void writeImageJpegIsDecodable() throws IOException {
		RGB[][] image = new RGB[2][2];
		for (int i = 0; i < 2; i++) {
			for (int j = 0; j < 2; j++) {
				image[i][j] = new RGB(1.0, 0.0, 0.0);
			}
		}

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageCanvas.writeImage(image, out, ImageCanvas.JPEGEncoding);

		BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(out.toByteArray()));
		Assert.assertNotNull(decoded);
		Assert.assertEquals(2, decoded.getWidth());
		Assert.assertEquals(2, decoded.getHeight());
	}

	/**
	 * Verifies {@link ImageCanvas#encodeImageFile(RGB[][], File, int)} writes a PPM
	 * file whose contents begin with the {@code P3} header.
	 */
	@Test(timeout = 30000)
	public void encodeImageFileWritesPpm() throws IOException {
		RGB[][] image = new RGB[][] {{new RGB(0.0, 0.0, 0.0)}};
		File file = File.createTempFile("canvas-test", ".ppm");
		file.deleteOnExit();

		ImageCanvas.encodeImageFile(image, file, ImageCanvas.PPMEncoding);

		String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
		Assert.assertTrue(text.startsWith("P3"));
		Assert.assertTrue(text.contains("0 0 0"));
	}
}
