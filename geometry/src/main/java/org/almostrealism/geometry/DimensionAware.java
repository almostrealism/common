/*
 * Copyright 2020 Michael Murray
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

package org.almostrealism.geometry;

/**
 * An interface for objects that are aware of rendering dimensions and supersampling settings.
 * This is typically used by computations that need to know the output image size and
 * supersampling parameters for pixel position calculations.
 *
 * <p>Supersampling is a technique for anti-aliasing where multiple samples are taken
 * per pixel and averaged. The ssw (supersample width) and ssh (supersample height)
 * parameters specify the number of samples in each direction.</p>
 *
 * @author Michael Murray
 * @see DimensionAwareKernel
 */
public interface DimensionAware {
	/**
	 * Sets the rendering dimensions and supersampling parameters.
	 *
	 * @param width the width of the output image in pixels
	 * @param height the height of the output image in pixels
	 * @param ssw the number of horizontal supersamples per pixel
	 * @param ssh the number of vertical supersamples per pixel
	 */
	void setDimensions(int width, int height, int ssw, int ssh);

	/**
	 * Calculates the linear position index for a given (x, y) coordinate
	 * in a supersampled image buffer.
	 *
	 * <p>The formula accounts for supersampling by computing the position
	 * in a linearized buffer where each pixel is represented by
	 * (ssw * ssh) samples.</p>
	 *
	 * @param x the x-coordinate (can be fractional for subsample positions)
	 * @param y the y-coordinate (can be fractional for subsample positions)
	 * @param width the image width in pixels
	 * @param height the image height in pixels
	 * @param ssw the number of horizontal supersamples per pixel
	 * @param ssh the number of vertical supersamples per pixel
	 * @return the linear index in the supersampled buffer
	 * @throws IllegalArgumentException if any dimension parameter is negative
	 */
	static int getPosition(double x, double y, int width, int height, int ssw, int ssh) {
		if (width < 0) throw new IllegalArgumentException("Width cannot be less than zero");
		if (height < 0) throw new IllegalArgumentException("Height cannot be less than zero");
		if (ssw < 0) throw new IllegalArgumentException("Supersample width cannot be less than zero");
		if (ssh < 0) throw new IllegalArgumentException("Supersample height cannot be less than zero");
		return (int) (y * width * ssw * ssh) + (int) (x * ssh);
	}
}
