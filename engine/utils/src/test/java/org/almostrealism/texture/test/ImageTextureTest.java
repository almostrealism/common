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
import org.junit.Assert;
import org.junit.Test;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URL;

/**
 * Tests for {@link ImageTexture}, covering constructor validation, the
 * pixel-not-loaded contract, pixel index resolution, and projection lookups.
 *
 * <p>The projection cases use a solid-color source image so their assertions depend
 * only on the projection selection; the direct {@code getColorAt} cases use an image
 * with distinct pixel colors so they pin down the pixel index arithmetic.</p>
 */
public class ImageTextureTest extends TextureTestBase {
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
		return writePng(image).toURI().toURL();
	}

	/**
	 * Writes a 3x3 image whose top-left pixel is red, the pixel to its right green,
	 * the pixel below it blue, and every other pixel white, so that sampled colors
	 * identify exactly which pixel index a lookup resolved to.
	 *
	 * @return a {@code file:} URL pointing at the written image
	 * @throws IOException if the file cannot be written
	 */
	private URL distinctPixelImage() throws IOException {
		BufferedImage image = new BufferedImage(3, 3, BufferedImage.TYPE_INT_RGB);
		for (int x = 0; x < 3; x++) {
			for (int y = 0; y < 3; y++) {
				image.setRGB(x, y, 0xFFFFFF);
			}
		}
		image.setRGB(0, 0, 0xFF0000);
		image.setRGB(1, 0, 0x00FF00);
		image.setRGB(0, 1, 0x0000FF);
		return writePng(image).toURI().toURL();
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
		assertColor(0.0, 0.0, 0.0, direct);

		RGB projected = texture.operate(new Vector(0.5, 0.7, 0.2));
		assertColor(0.0, 0.0, 0.0, projected);
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
	 * Verifies {@link ImageTexture#getColorAt(double, double)} on a loaded image maps
	 * {@code (u, v)} to pixel {@code (int(u * (width - 1)), int(v * (height - 1)))},
	 * and that the constructor's scale and offset are applied before the lookup.
	 */
	@Test(timeout = 60000)
	public void colorLookupResolvesPixelIndex() throws IOException {
		ImageTexture texture = new ImageTexture(ImageTexture.XY_PLANAR_PROJECTION, distinctPixelImage());
		assertColor(1.0, 0.0, 0.0, texture.getColorAt(0.0, 0.0));
		assertColor(0.0, 1.0, 0.0, texture.getColorAt(0.6, 0.0));
		assertColor(0.0, 0.0, 1.0, texture.getColorAt(0.0, 0.6));
		assertColor(1.0, 1.0, 1.0, texture.getColorAt(0.6, 0.6));

		// xScale=2 maps u=0.3 to 0.6 (column 1); yOff=0.5 maps v=0.1 to 0.6 (row 1)
		ImageTexture scaled = new ImageTexture(ImageTexture.XY_PLANAR_PROJECTION,
				distinctPixelImage(), 2.0, 1.0, 0.0, 0.5);
		assertColor(0.0, 0.0, 1.0, scaled.getColorAt(0.0, 0.1));
		assertColor(1.0, 1.0, 1.0, scaled.getColorAt(0.3, 0.1));
	}

	/**
	 * Verifies the explicit scale/offset overload of {@code getColorAt} on a loaded
	 * image applies its arguments (not the texture's own scale and offset), and that
	 * coordinates wrap modulo 1.
	 */
	@Test(timeout = 60000)
	public void scaledColorLookupAppliesArguments() throws IOException {
		ImageTexture texture = new ImageTexture(ImageTexture.XY_PLANAR_PROJECTION, distinctPixelImage());
		assertColor(1.0, 0.0, 0.0, texture.getColorAt(0.0, 0.0, 1.0, 1.0, 0.0, 0.0));
		assertColor(0.0, 1.0, 0.0, texture.getColorAt(0.3, 0.0, 2.0, 1.0, 0.0, 0.0));
		assertColor(0.0, 0.0, 1.0, texture.getColorAt(0.0, 0.1, 1.0, 1.0, 0.0, 0.5));
		// (1.6 % 1) = 0.6 wraps back into column 1
		assertColor(0.0, 1.0, 0.0, texture.getColorAt(1.6, 0.0, 1.0, 1.0, 0.0, 0.0));
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
		assertColor(1.0, 0.0, 0.0, color);
	}

	/**
	 * Verifies a spherical projection over a solid red image yields red at a sampled
	 * surface point.
	 */
	@Test(timeout = 60000)
	public void sphericalProjectionSamplesLoadedImage() throws IOException {
		ImageTexture texture = new ImageTexture(ImageTexture.SPHERICAL_PROJECTION, solidRedImage(4));

		RGB color = texture.operate(new Vector(1.0, 1.0, 1.0));
		assertColor(1.0, 0.0, 0.0, color);
	}

	/**
	 * Verifies the XZ planar projection samples the loaded image using the X and Z
	 * coordinates.
	 */
	@Test(timeout = 60000)
	public void xzPlanarProjectionSamplesLoadedImage() throws IOException {
		ImageTexture texture = new ImageTexture(ImageTexture.XZ_PLANAR_PROJECTION, solidRedImage(4));

		RGB color = texture.operate(new Vector(0.3, 0.9, 0.4));
		assertColor(1.0, 0.0, 0.0, color);
	}

	/**
	 * Verifies the YZ planar projection samples the loaded image using the Y and Z
	 * coordinates.
	 */
	@Test(timeout = 60000)
	public void yzPlanarProjectionSamplesLoadedImage() throws IOException {
		ImageTexture texture = new ImageTexture(ImageTexture.YZ_PLANAR_PROJECTION, solidRedImage(4));

		RGB color = texture.operate(new Vector(0.9, 0.3, 0.4));
		assertColor(1.0, 0.0, 0.0, color);
	}
}
