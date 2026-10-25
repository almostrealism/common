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

import org.almostrealism.texture.URLImageSource;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URL;

/**
 * Tests for {@link URLImageSource}, which loads an image from a URL via AWT and
 * exposes its dimensions and packed-ARGB pixels.
 */
public class URLImageSourceTest extends TestSuiteBase {
	/**
	 * Writes a 3x2 image with a red top-left pixel to a temporary PNG and returns
	 * its URL.
	 *
	 * @return a {@code file:} URL pointing at the written image
	 * @throws IOException if the file cannot be written
	 */
	private URL sampleImageUrl() throws IOException {
		BufferedImage image = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
		for (int x = 0; x < 3; x++) {
			for (int y = 0; y < 2; y++) {
				image.setRGB(x, y, 0x202020);
			}
		}
		image.setRGB(0, 0, 0xFF0000);

		File file = File.createTempFile("url-image", ".png");
		file.deleteOnExit();
		ImageIO.write(image, "png", file);
		return file.toURI().toURL();
	}

	/**
	 * Verifies the loaded image reports its dimensions and is treated as opaque.
	 */
	@Test(timeout = 30000)
	public void reportsDimensionsAndOpacity() throws IOException {
		URLImageSource source = new URLImageSource(sampleImageUrl());
		Assert.assertEquals(3, source.getWidth());
		Assert.assertEquals(2, source.getHeight());
		Assert.assertFalse(source.isAlpha());
	}

	/**
	 * Verifies the grabbed pixel buffer has one entry per pixel, carries the known
	 * top-left color, and is cached across calls.
	 */
	@Test(timeout = 30000)
	public void grabsAndCachesPixels() throws IOException {
		URLImageSource source = new URLImageSource(sampleImageUrl());

		int[] pixels = source.getPixels();
		Assert.assertEquals(6, pixels.length);
		Assert.assertEquals(0xFF0000, pixels[0] & 0xFFFFFF);

		Assert.assertSame(pixels, source.getPixels());
	}

	/**
	 * Verifies an icon view of the source image is available.
	 */
	@Test(timeout = 30000)
	public void providesIcon() throws IOException {
		URLImageSource source = new URLImageSource(sampleImageUrl());
		Assert.assertNotNull(source.getIcon());
	}

	/**
	 * Verifies a URL that cannot be loaded causes construction to fail rather than
	 * yielding a half-initialized source.
	 */
	@Test(timeout = 30000)
	public void failedLoadThrows() throws IOException {
		URL missing = new File("/nonexistent/texture-does-not-exist.png").toURI().toURL();

		boolean threw = false;
		try {
			new URLImageSource(missing);
		} catch (RuntimeException e) {
			threw = true;
		}
		Assert.assertTrue(threw);
	}
}
