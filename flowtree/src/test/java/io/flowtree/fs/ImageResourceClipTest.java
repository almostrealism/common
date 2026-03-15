/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.flowtree.fs;

import io.almostrealism.resource.Permissions;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for {@link ImageResource#clip(int, int, int, int)}, verifying that
 * the cx offset is correctly applied when extracting pixel sub-regions.
 */
public class ImageResourceClipTest extends TestSuiteBase {

	/**
	 * Creates a 4x3 image where each pixel value equals its linear index
	 * (row-major order). The data array format is [width, height, pixel0, pixel1, ...].
	 */
	private ImageResource createTestImage() {
		int width = 4;
		int height = 3;
		int[] data = new int[2 + width * height];
		data[0] = width;
		data[1] = height;
		for (int i = 0; i < width * height; i++) {
			data[2 + i] = i;
		}
		return new ImageResource("test", data, new Permissions());
	}

	/**
	 * Verifies that clipping the full image returns an identical copy.
	 */
	@Test
	public void clipFullImage() {
		ImageResource img = createTestImage();
		int[] result = img.clip(0, 0, 4, 3);
		Assert.assertEquals(4, result[0]);
		Assert.assertEquals(3, result[1]);
		Assert.assertEquals(2 + 4 * 3, result.length);
		for (int i = 0; i < 4 * 3; i++) {
			Assert.assertEquals(i, result[2 + i]);
		}
	}

	/**
	 * Verifies that a sub-region clip with a non-zero cx offset reads the
	 * correct columns. This is the regression test for the bug where
	 * {@code (i + cw)} was used instead of {@code (i + cx)}.
	 */
	@Test
	public void clipSubRegionWithXOffset() {
		ImageResource img = createTestImage();

		// Clip a 2x2 region starting at (1, 0)
		// Original 4x3 grid (row-major):
		//   row 0: 0  1  2  3
		//   row 1: 4  5  6  7
		//   row 2: 8  9  10 11
		// Expected 2x2 clip at (1,0): pixels at (1,0)=1, (2,0)=2, (1,1)=5, (2,1)=6
		int[] result = img.clip(1, 0, 2, 2);
		Assert.assertEquals(2, result[0]);
		Assert.assertEquals(2, result[1]);
		Assert.assertEquals(6, result.length);
		Assert.assertEquals(1, result[2]);
		Assert.assertEquals(2, result[3]);
		Assert.assertEquals(5, result[4]);
		Assert.assertEquals(6, result[5]);
	}

	/**
	 * Verifies clipping with both x and y offsets.
	 */
	@Test
	public void clipSubRegionWithXYOffset() {
		ImageResource img = createTestImage();

		// Clip a 2x2 region starting at (2, 1)
		// Expected: pixels at (2,1)=6, (3,1)=7, (2,2)=10, (3,2)=11
		int[] result = img.clip(2, 1, 2, 2);
		Assert.assertEquals(2, result[0]);
		Assert.assertEquals(2, result[1]);
		Assert.assertEquals(6, result[2]);
		Assert.assertEquals(7, result[3]);
		Assert.assertEquals(10, result[4]);
		Assert.assertEquals(11, result[5]);
	}

	/**
	 * Verifies that clipping a single pixel works correctly.
	 */
	@Test
	public void clipSinglePixel() {
		ImageResource img = createTestImage();

		// Clip 1x1 at (3, 2) -> pixel value 11
		int[] result = img.clip(3, 2, 1, 1);
		Assert.assertEquals(1, result[0]);
		Assert.assertEquals(1, result[1]);
		Assert.assertEquals(11, result[2]);
	}

	/**
	 * Verifies that the x/y image offset is subtracted from the clip coordinates.
	 */
	@Test
	public void clipWithImageOffset() {
		ImageResource img = createTestImage();
		img.setX(10);
		img.setY(20);

		// Clip 2x1 at absolute position (11, 20) -> relative (1, 0)
		// Expected: pixels at (1,0)=1, (2,0)=2
		int[] result = img.clip(11, 20, 2, 1);
		Assert.assertEquals(2, result[0]);
		Assert.assertEquals(1, result[1]);
		Assert.assertEquals(1, result[2]);
		Assert.assertEquals(2, result[3]);
	}

	/**
	 * Verifies that a full-row clip returns the correct pixels.
	 */
	@Test
	public void clipFullRow() {
		ImageResource img = createTestImage();

		// Clip row 1 (y=1, width=4, height=1)
		// Expected: 4, 5, 6, 7
		int[] result = img.clip(0, 1, 4, 1);
		Assert.assertEquals(4, result[0]);
		Assert.assertEquals(1, result[1]);
		Assert.assertEquals(4, result[2]);
		Assert.assertEquals(5, result[3]);
		Assert.assertEquals(6, result[4]);
		Assert.assertEquals(7, result[5]);
	}
}
