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
 * A fractal turbulence texture that sums multiple octaves of {@link Noise} at increasing frequencies.
 *
 * <p>The turbulence value is computed as:
 * <pre>
 * T(u,v,w) = sum(i=0..itr-1) { noise(2^i * u, 2^i * v, 2^i * w) / 2^i }
 * </pre>
 *
 * <p>Higher iteration counts produce finer detail. The default configuration uses 8 octaves.</p>
 *
 * @see Noise
 * @see IntensityMap
 * @author Michael Murray
 */
// TODO  Move to rings
public class Turbulence implements IntensityMap {
	/** The base noise function whose octaves are summed. */
	private final Noise noise;

	/** The number of octaves (frequency doublings) to accumulate. */
	private int itr = 8;

	/**
	 * Constructs a default {@link Turbulence} with a new {@link Noise} instance and 8 octaves.
	 */
	public Turbulence() { this(new Noise(), 8); }

	/**
	 * Constructs a {@link Turbulence} with the specified noise source and iteration count.
	 *
	 * @param noise the base noise function
	 * @param itr   the number of octaves to accumulate
	 */
	public Turbulence(Noise noise, int itr) { this.noise = noise; this.itr = itr; }

	/**
	 * Returns the turbulence value at the given texture coordinates by summing
	 * {@code itr} octaves of noise at doubling frequencies.
	 *
	 * @param u the horizontal texture coordinate
	 * @param v the vertical texture coordinate
	 * @param w the depth texture coordinate
	 * @return the turbulence intensity at {@code (u, v, w)}
	 */
	public double getIntensity(double u, double v, double w) {
		double n = 0.0;
		
		for (int i = 0; i < this.itr; i++) {
			double m = Math.pow(2.0, i);
			n = n + this.noise.getIntensity(m * u, m * v, m * w) / m;
		}
		
		return n;
	}
}
