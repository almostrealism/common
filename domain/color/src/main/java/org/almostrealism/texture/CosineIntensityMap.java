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
 * An {@link IntensityMap} that modulates a base map using a cosine function, producing
 * smooth periodic intensity variations.
 *
 * <p>The intensity is computed as:</p>
 * <pre>
 * z = map.getIntensity(tau * u, tau * v, tau * w)
 * t = 1 + cos(min(alpha * v + beta * z, 2π))
 * intensity = t / 2
 * </pre>
 *
 * <p>The result is always in [0, 1]. The {@code alpha} and {@code beta} parameters control
 * the frequency and depth of the cosine modulation; {@code tau} scales the UV input to the
 * base map.</p>
 *
 * @see IntensityMap
 * @see Noise
 * @author Michael Murray
 */
public class CosineIntensityMap implements IntensityMap {
	/** Controls the spatial frequency of the cosine modulation along the V axis. */
	private final double alpha;

	/** Scales the contribution of the base map intensity to the cosine argument. */
	private final double beta;

	/** Scale factor applied to UV coordinates when sampling the base intensity map. */
	private final double tau;

	/** The base intensity map evaluated at the scaled UV position. */
	private IntensityMap map;

	/**
	 * Constructs a {@link CosineIntensityMap} with default parameters and no base map.
	 */
	public CosineIntensityMap() {
		this(null);
	}

	/**
	 * Constructs a {@link CosineIntensityMap} with default parameters ({@code alpha=3.5, beta=2.5, tau=2.0}).
	 *
	 * @param map the base intensity map
	 */
	public CosineIntensityMap(IntensityMap map) {
		this(3.5, 2.5, 2.0, map);
	}

	/**
	 * Constructs a {@link CosineIntensityMap} with the specified parameters.
	 *
	 * @param alpha the cosine frequency coefficient along the V axis
	 * @param beta  the base-map contribution coefficient to the cosine argument
	 * @param tau   the UV scale factor applied before querying the base map
	 * @param map   the base intensity map
	 */
	public CosineIntensityMap(double alpha, double beta, double tau, IntensityMap map) {
		this.alpha = alpha;
		this.beta = beta;
		this.tau = tau;
		this.map = map;
	}

	/**
	 * Sets the base intensity map.
	 *
	 * @param map the new base intensity map
	 */
	public void setIntensityMap(IntensityMap map) { this.map = map; }

	/**
	 * Returns the base intensity map.
	 *
	 * @return the base intensity map
	 */
	public IntensityMap getIntensityMap() { return this.map; }

	/**
	 * Returns the cosine-modulated intensity at the given texture coordinates.
	 *
	 * @param u the horizontal texture coordinate
	 * @param v the vertical texture coordinate
	 * @param w the depth texture coordinate
	 * @return the intensity in [0, 1]
	 */
	public double getIntensity(double u, double v, double w) {
		double z = this.map.getIntensity(this.tau * u, this.tau * v, this.tau * w);
		double t = 1 + Math.cos(Math.min(this.alpha * v + this.beta * z, 2.0 * Math.PI));
		return t / 2.0;
	}
}
