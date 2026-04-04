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
 * A procedural Perlin-style noise function implementing {@link IntensityMap}.
 *
 * <p>This class generates smooth pseudo-random noise by combining random gradient vectors
 * at integer lattice points. The noise value at a point is computed as a weighted sum of
 * gradient dot products over the surrounding 2×2×2 lattice cube, using a cubic smoothing
 * kernel (omega function).</p>
 *
 * <p>The UV coordinates are scaled by 10 before noise evaluation, giving approximately
 * 10 noise periods over the unit UV domain.</p>
 *
 * @see Turbulence
 * @see IntensityMap
 * @author Michael Murray
 */
// TODO  Move to common
public class Noise implements IntensityMap {
	/** Scale factor applied to the U coordinate before noise evaluation. */
	private final double scaleU = 10.0;

	/** Scale factor applied to the V coordinate before noise evaluation. */
	private final double scaleV = 10.0;

	/** Scale factor applied to the W coordinate before noise evaluation. */
	private final double scaleW = 10.0;

	/** Random gradient vectors at each lattice point, normalised to the unit sphere. */
	private final double[][] g;

	/** Permutation table used to hash lattice coordinates to gradient indices. */
	private final int[] p;

	/**
	 * Constructs a {@link Noise} instance with 256 lattice points.
	 */
	public Noise() { this(256); }

	/**
	 * Constructs a {@link Noise} instance with {@code n} lattice points.
	 *
	 * @param n the number of lattice points; higher values reduce pattern repetition
	 */
	public Noise(int n) {
		this.p = new int[n];
		
		for (int i = 1; i < n; i++) {
			int j = (int) (Math.random() * n);
			while (this.p[j] > 0) j = (j + 1) % n;
			this.p[j] = i;
		}
		
		this.g = new double[n][3];
		
		for (int i = 0; i < n; i++) {
			double x = 1.0;
			double y = 1.0;
			double z = 1.0;
			
			while (x * x + y * y + z * z > 1) {
				x = Math.random() * 2.0 - 1.0;
				y = Math.random() * 2.0 - 1.0;
				z = Math.random() * 2.0 - 1.0;
			}
			
			this.g[i][0] = x;
			this.g[i][1] = y;
			this.g[i][2] = z;
		}
	}
	
	/**
	 * Returns the noise value at the scaled position {@code (scaleU * u, scaleV * v, scaleW * w)}.
	 *
	 * <p>The noise is computed by summing weighted gradient contributions from
	 * all eight corners of the enclosing lattice cube.</p>
	 *
	 * @param u the horizontal texture coordinate
	 * @param v the vertical texture coordinate
	 * @param w the depth texture coordinate
	 * @return the noise value at the given position
	 */
	public double getIntensity(double u, double v, double w) {
		u = this.scaleU * u;
		v = this.scaleV * v;
		w = this.scaleW * w;
		double x = Math.floor(u);
		double y = Math.floor(v);
		double z = Math.floor(w);
		
		double n = 0;
		
		for (int i = 0; i <= 1; i++) {
			for (int j = 0; j <= 1; j++) {
				for (int k = 0; k <= 1; k++) {
					n = n + this.omega(x + i, y + j, z + k,
										u - x - i, v - y - j, w - z - k);
				}
			}
		}
		
		return n;
	}
	
	/**
	 * Computes the weighted gradient contribution from the lattice point at {@code (i, j, k)}.
	 *
	 * @param i the integer lattice X coordinate
	 * @param j the integer lattice Y coordinate
	 * @param k the integer lattice Z coordinate
	 * @param x the fractional X offset from the lattice point
	 * @param y the fractional Y offset from the lattice point
	 * @param z the fractional Z offset from the lattice point
	 * @return the contribution of this lattice point to the noise value
	 */
	protected double omega(double i, double j, double k, double x, double y, double z) {
		return this.omega(x) * this.omega(y) * this.omega(z) *
					this.gamma((int) i, (int) j, (int) k, x, y, z);
	}
	
	/**
	 * Looks up the gradient vector at lattice position {@code (i, j, k)} and computes
	 * its dot product with the offset {@code (u, v, w)}.
	 *
	 * @param i the integer lattice X index
	 * @param j the integer lattice Y index
	 * @param k the integer lattice Z index
	 * @param u the X component of the offset
	 * @param v the Y component of the offset
	 * @param w the Z component of the offset
	 * @return the dot product of the gradient at {@code (i,j,k)} with {@code (u,v,w)}
	 */
	protected double gamma(int i, int j, int k, double u, double v, double w) {
		double[] x = this.g[this.phi(i + this.phi(j + this.phi(k)))];
		return x[0] * u + x[1] * v + x[2] * w;
	}
	
	/**
	 * Maps an integer coordinate through the permutation table.
	 *
	 * @param t the input integer coordinate
	 * @return the permuted index into the gradient table
	 */
	protected int phi(int t) { return this.p[t % this.p.length]; }

	/**
	 * Evaluates the cubic smoothing kernel used to weight gradient contributions.
	 *
	 * <p>Returns {@code 2t^3 - 3t^2 + 1} for {@code |t| < 1}, and {@code 0} otherwise.</p>
	 *
	 * @param t the input value
	 * @return the smoothing weight for this distance
	 */
	protected double omega(double t) {
		t = Math.abs(t);
		
		if (t < 1) {
			return 2.0 * t * t * t - 3 * t * t + 1;
		} else {
			return 0.0;
		}
	}
}
