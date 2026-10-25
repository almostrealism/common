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

import org.almostrealism.algebra.Vector;
import org.almostrealism.color.RGB;
import org.almostrealism.texture.ImageTexture;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URL;

/**
 * Tests for {@link ImageTexture}, covering constructor validation, the
 * pixel-not-loaded contract, and projection lookups against a solid-color image.
 *
 * <p>A solid-color source image is used for the loaded-image cases so the assertions
 * depend only on the projection selection, not on the exact pixel index arithmetic.</p>
 */
public class ImageTextureTest extends TestSuiteBase {
	/** Numerical tolerance for floating-point comparisons. */
	private static final double TOL = 1e-6;

	/**
	 * Writes a solid red image of the given size to a temporary PNG and returns its URL.
	 *
	 * @param size the width and height of the square image
	 * @return a {@code file:} URL pointing at the written image
	 * @throws IOException if the file cannot be written
	 */
	private URL solidRedImage(int size) throws IOException {
		BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
		for (int x = 0; x < size; x++) {
			for (int y = 0; y < size; y++) {
				image.setRGB(x, y, 0xFF0000);
			}
		}
		File file = File.createTempFile("image-texture", ".png");
		file.deleteOnExit();
		ImageIO.write(image, "png", file);
		return file.toURI().toURL();
	}

	/**
	 * Verifies the no-argument constructor sets a default URL and labels itself,
	 * without attempting to load any pixels.
	 */
	@Test(timeout = 30000)
	public void defaultConstructorHasUrlAndLabel() {
		ImageTexture texture = new ImageTexture();
		Assert.assertNotNull(texture.getURL());
		Assert.assertEquals("Image Texture", texture.toString());
		texture.compact();
	}

	/**
	 * Verifies that with no pixel data loaded, {@link ImageTexture#getColorAt(double, double)}
	 * returns black and a spherical {@link ImageTexture#operate(Vector)} does likewise.
	 */
	@Test(timeout = 30000)
	public void unloadedTextureReturnsBlack() {
		ImageTexture texture = new ImageTexture();

		RGB direct = texture.getColorAt(0.5, 0.5);
		Assert.assertEquals(0.0, direct.getRed(), TOL);
		Assert.assertEquals(0.0, direct.getGreen(), TOL);
		Assert.assertEquals(0.0, direct.getBlue(), TOL);

		RGB projected = texture.operate(new Vector(0.5, 0.7, 0.2));
		Assert.assertEquals(0.0, projected.getRed(), TOL);
		Assert.assertEquals(0.0, projected.getBlue(), TOL);
	}

	/**
	 * Verifies the scaled {@code getColorAt} overload throws when pixel data has not
	 * been loaded.
	 */
	@Test(timeout = 30000)
	public void scaledColorLookupRequiresPixels() {
		ImageTexture texture = new ImageTexture();

		boolean threw = false;
		try {
			texture.getColorAt(0.5, 0.5, 1.0, 1.0, 0.0, 0.0);
		} catch (NullPointerException e) {
			threw = true;
		}
		Assert.assertTrue(threw);
	}

	/**
	 * Verifies an out-of-range projection type is rejected by the constructor.
	 */
	@Test(timeout = 30000)
	public void invalidProjectionTypeIsRejected() throws IOException {
		URL url = solidRedImage(2);

		boolean threw = false;
		try {
			new ImageTexture(99, url);
		} catch (IllegalArgumentException e) {
			threw = true;
		}
		Assert.assertTrue(threw);
	}

	/**
	 * Verifies a planar projection over a solid red image yields red at a sampled
	 * surface point.
	 */
	@Test(timeout = 60000)
	public void planarProjectionSamplesLoadedImage() throws IOException {
		ImageTexture texture = new ImageTexture(ImageTexture.XY_PLANAR_PROJECTION, solidRedImage(4));

		RGB color = texture.operate(new Vector(0.3, 0.4, 0.0));
		Assert.assertEquals(1.0, color.getRed(), TOL);
		Assert.assertEquals(0.0, color.getGreen(), TOL);
		Assert.assertEquals(0.0, color.getBlue(), TOL);
	}

	/**
	 * Verifies a spherical projection over a solid red image yields red at a sampled
	 * surface point.
	 */
	@Test(timeout = 60000)
	public void sphericalProjectionSamplesLoadedImage() throws IOException {
		ImageTexture texture = new ImageTexture(ImageTexture.SPHERICAL_PROJECTION, solidRedImage(4));

		RGB color = texture.operate(new Vector(1.0, 1.0, 1.0));
		Assert.assertEquals(1.0, color.getRed(), TOL);
		Assert.assertEquals(0.0, color.getGreen(), TOL);
		Assert.assertEquals(0.0, color.getBlue(), TOL);
	}

	/**
	 * Verifies the XZ planar projection samples the loaded image using the X and Z
	 * coordinates.
	 */
	@Test(timeout = 60000)
	public void xzPlanarProjectionSamplesLoadedImage() throws IOException {
		ImageTexture texture = new ImageTexture(ImageTexture.XZ_PLANAR_PROJECTION, solidRedImage(4));

		RGB color = texture.operate(new Vector(0.3, 0.9, 0.4));
		Assert.assertEquals(1.0, color.getRed(), TOL);
		Assert.assertEquals(0.0, color.getGreen(), TOL);
		Assert.assertEquals(0.0, color.getBlue(), TOL);
	}

	/**
	 * Verifies the YZ planar projection samples the loaded image using the Y and Z
	 * coordinates.
	 */
	@Test(timeout = 60000)
	public void yzPlanarProjectionSamplesLoadedImage() throws IOException {
		ImageTexture texture = new ImageTexture(ImageTexture.YZ_PLANAR_PROJECTION, solidRedImage(4));

		RGB color = texture.operate(new Vector(0.9, 0.3, 0.4));
		Assert.assertEquals(1.0, color.getRed(), TOL);
		Assert.assertEquals(0.0, color.getGreen(), TOL);
		Assert.assertEquals(0.0, color.getBlue(), TOL);
	}
}
