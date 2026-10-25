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
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * Shared fixtures for the texture tests: writing source images to temporary PNG
 * files and comparing sampled {@link RGB} colors channel by channel.
 */
public abstract class TextureTestBase extends TestSuiteBase {
	/** Tolerance for comparing sampled color channels. */
	protected static final double COLOR_TOL = 1e-6;

	/**
	 * Writes the given image to a temporary PNG file that is deleted on exit.
	 *
	 * @param image the image to write
	 * @return the temporary file containing the image
	 * @throws IOException if the file cannot be written
	 */
	protected File writePng(BufferedImage image) throws IOException {
		File file = File.createTempFile("texture-test", ".png");
		file.deleteOnExit();
		ImageIO.write(image, "png", file);
		return file;
	}

	/**
	 * Asserts that a sampled color matches the expected channels within
	 * {@link #COLOR_TOL}.
	 *
	 * @param r expected red channel
	 * @param g expected green channel
	 * @param b expected blue channel
	 * @param actual the sampled color
	 */
	protected void assertColor(double r, double g, double b, RGB actual) {
		Assert.assertEquals(r, actual.getRed(), COLOR_TOL);
		Assert.assertEquals(g, actual.getGreen(), COLOR_TOL);
		Assert.assertEquals(b, actual.getBlue(), COLOR_TOL);
	}
}
