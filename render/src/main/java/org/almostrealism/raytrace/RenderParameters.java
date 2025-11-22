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

package org.almostrealism.raytrace;

import org.almostrealism.algebra.Pair;

import java.util.function.Function;

/**
 * {@link RenderParameters} encapsulates all configuration parameters for a ray traced render,
 * including image dimensions, supersampling settings, and render region bounds.
 *
 * <p>This class is used by {@link org.almostrealism.render.RayTracedScene} to configure the
 * rendering process. The parameters control:</p>
 * <ul>
 *   <li><b>Image size:</b> {@link #width} and {@link #height} define the full output image dimensions</li>
 *   <li><b>Supersampling:</b> {@link #ssWidth} and {@link #ssHeight} define the number of samples per pixel
 *       for anti-aliasing (e.g., 2x2 = 4 samples averaged per pixel)</li>
 *   <li><b>Render region:</b> {@link #x}, {@link #y}, {@link #dx}, {@link #dy} define a sub-region to render,
 *       useful for tiled or progressive rendering</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * RenderParameters params = new RenderParameters();
 * params.width = 1920;
 * params.height = 1080;
 * params.ssWidth = 2;   // 2x2 supersampling
 * params.ssHeight = 2;
 * params.x = 0;
 * params.y = 0;
 * params.dx = 1920;     // Full width
 * params.dy = 1080;     // Full height
 *
 * RayTracedScene scene = new RayTracedScene(engine, camera, params);
 * RealizableImage image = scene.realize(params);
 * }</pre>
 *
 * <h2>Supersampling</h2>
 * <p>Supersampling (anti-aliasing) works by casting multiple rays per pixel and averaging the results.
 * The total samples per pixel is {@code ssWidth * ssHeight}. Higher values produce smoother edges
 * but increase render time proportionally.</p>
 *
 * <h2>Render Regions</h2>
 * <p>The render region parameters ({@code x}, {@code y}, {@code dx}, {@code dy}) allow rendering
 * a portion of the full image. This is useful for:</p>
 * <ul>
 *   <li>Distributed rendering across multiple machines</li>
 *   <li>Progressive rendering (render tiles as they complete)</li>
 *   <li>Preview rendering of a selected region</li>
 * </ul>
 *
 * @see org.almostrealism.render.RayTracedScene
 * @see org.almostrealism.color.RealizableImage
 * @author Michael Murray
 */
public class RenderParameters {

	/**
	 * Creates a new {@link RenderParameters} with default values.
	 * Default supersampling is 2x2 (4 samples per pixel).
	 */
	public RenderParameters() { }

	/**
	 * Creates a new {@link RenderParameters} for a full image render with the specified dimensions
	 * and supersampling settings.
	 *
	 * @param w   The width of the output image in pixels
	 * @param h   The height of the output image in pixels
	 * @param ssw The number of horizontal samples per pixel (supersampling width)
	 * @param ssh The number of vertical samples per pixel (supersampling height)
	 */
	public RenderParameters(int w, int h, int ssw, int ssh) {
		this(0, 0, w, h, w, h, ssw, ssh);
	}

	/**
	 * Creates a new {@link RenderParameters} for rendering a specific region of an image.
	 *
	 * @param x   The x-coordinate of the upper-left corner of the render region
	 * @param y   The y-coordinate of the upper-left corner of the render region
	 * @param dx  The width of the region to render
	 * @param dy  The height of the region to render
	 * @param w   The full image width in pixels
	 * @param h   The full image height in pixels
	 * @param ssw The number of horizontal samples per pixel (supersampling width)
	 * @param ssh The number of vertical samples per pixel (supersampling height)
	 */
	public RenderParameters(int x, int y, int dx, int dy, int w, int h, int ssw, int ssh) {
		this.x = x;
		this.y = y;
		this.dx = dx;
		this.dy = dy;
		this.width = w;
		this.height = h;
		this.ssWidth = ssw;
		this.ssHeight = ssh;
	}
	
	/**
	 * Full image dimensions in pixels.
	 * These define the total size of the output image, regardless of what region is being rendered.
	 */
	public int width, height;

	/**
	 * Supersampling dimensions (samples per pixel in each direction).
	 * <p>Total samples per pixel = {@code ssWidth * ssHeight}.</p>
	 * <p>Default is 2x2 (4 samples per pixel).</p>
	 */
	public int ssWidth = 2, ssHeight = 2;

	/**
	 * Coordinates of the upper-left corner of the render region within the full image.
	 * Used for partial/tiled rendering. For full image rendering, set to (0, 0).
	 */
	public int x, y;

	/**
	 * Dimensions of the region to render (viewable/active rendering area).
	 * For full image rendering, these should equal {@link #width} and {@link #height}.
	 */
	public int dx, dy;

	/**
	 * Returns a function that converts pixel indices to screen positions.
	 * <p>The function accounts for the render region offset and flips the Y-axis
	 * to convert from image coordinates (origin at top-left) to screen coordinates.</p>
	 *
	 * @return A function that takes a {@link Pair} of indices (i, j) and returns
	 *         the corresponding screen position as a {@link Pair}
	 */
	public Function<Pair, Pair> positionForIndices() {
		return p -> new Pair(x + p.getX(), height - 1 - p.getY() - y);
	}
}
