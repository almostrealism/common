/*
 * Copyright 2016 Michael Murray
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

package org.almostrealism.texture;

/**
 * Provides raw pixel data and dimensional information for an image.
 *
 * <p>Implementations supply the flat pixel array and dimensions used by
 * {@link ImageTexture} and {@link ImageLayers} to map textures onto surfaces.</p>
 *
 * @see URLImageSource
 * @see ImageLayers
 * @author Michael Murray
 */
public interface ImageSource {
	/**
	 * Returns the flat pixel data array for this image.
	 *
	 * <p>Pixels are stored in row-major order as packed ARGB integers.</p>
	 *
	 * @return the pixel array
	 */
	int[] getPixels();

	/**
	 * Returns the width of the image in pixels.
	 *
	 * @return the image width
	 */
	int getWidth();

	/**
	 * Returns the height of the image in pixels.
	 *
	 * @return the image height
	 */
	int getHeight();

	/**
	 * Returns {@code true} if this image has an alpha (transparency) channel.
	 *
	 * @return {@code true} if the image supports transparency
	 */
	boolean isAlpha();
}
