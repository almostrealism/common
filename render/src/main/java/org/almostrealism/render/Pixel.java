/*
 * Copyright 2018 Michael Murray
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

package org.almostrealism.render;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;

/**
 * {@link Pixel} represents a single pixel in a ray traced image, containing multiple
 * color samples for anti-aliasing via supersampling.
 *
 * <p>A Pixel extends {@link SuperSampler} to hold a 2D grid of color producers (one for
 * each supersample). When evaluated, the SuperSampler averages all samples to produce
 * the final pixel color.</p>
 *
 * <h2>Supersampling</h2>
 * <p>For a pixel with ssWidth=2 and ssHeight=2 (2x2 supersampling), the pixel contains
 * 4 sample positions, each offset slightly from the pixel center:</p>
 * <pre>
 * +-------+-------+
 * | (0,0) | (1,0) |
 * +-------+-------+
 * | (0,1) | (1,1) |
 * +-------+-------+
 * </pre>
 * <p>Each sample traces a ray through a different sub-pixel position, and the results
 * are averaged for the final color.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * Pixel pixel = new Pixel(2, 2);  // 2x2 supersampling
 *
 * // Set samples from traced rays
 * for (int i = 0; i < 2; i++) {
 *     for (int j = 0; j < 2; j++) {
 *         Producer<PackedCollection> color = engine.trace(ray[i][j]);
 *         pixel.setSample(i, j, color);
 *     }
 * }
 *
 * // Evaluate to get averaged color
 * RGB finalColor = pixel.get().evaluate(position);
 * }</pre>
 *
 * @see SuperSampler
 * @see org.almostrealism.render.RayTracedScene
 * @author Michael Murray
 */
public class Pixel extends SuperSampler {

	/**
	 * Creates a new Pixel with the specified supersampling grid dimensions.
	 *
	 * @param ssWidth  The number of horizontal samples (columns in the sample grid)
	 * @param ssHeight The number of vertical samples (rows in the sample grid)
	 */
	public Pixel(int ssWidth, int ssHeight) {
		super(new Producer[ssWidth][ssHeight]);
	}

	/**
	 * Sets the color producer for a specific sample position within the pixel.
	 *
	 * <p>This method is synchronized to support concurrent sample assignment
	 * during parallel rendering.</p>
	 *
	 * @param sx The horizontal sample index (0 to ssWidth-1)
	 * @param sy The vertical sample index (0 to ssHeight-1)
	 * @param s  The color producer for this sample position (must not be null)
	 * @throws IllegalArgumentException if the sample producer is null
	 */
	public synchronized void setSample(int sx, int sy, Producer<PackedCollection> s) {
		if (s == null) {
			throw new IllegalArgumentException("Null sample not supported");
		}

		samples[sx][sy] = s;
	}
}
