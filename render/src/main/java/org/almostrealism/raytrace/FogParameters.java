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

package org.almostrealism.raytrace;

import org.almostrealism.color.RGBA;

/**
 * {@link FogParameters} encapsulates configuration parameters for atmospheric fog effects
 * in ray traced rendering.
 *
 * <p>Fog creates depth cues by blending surface colors with a fog color based on distance
 * from the camera. This simulates the effect of light scattering through particles in the
 * atmosphere (haze, mist, dust).</p>
 *
 * <h2>Fog Model</h2>
 * <p>The fog effect is computed using a blend between the surface color and fog color:</p>
 * <pre>
 * finalColor = surfaceColor * (1 - fogFactor) + fogColor * fogFactor
 * </pre>
 * <p>where {@code fogFactor} depends on distance and density.</p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * FogParameters fog = new FogParameters();
 * fog.fogColor = new RGBA(0.8, 0.8, 0.9, 1.0);  // Light blue-gray
 * fog.fogDensity = 0.05;                         // Light fog
 * fog.fogRatio = 0.5;                            // Blend factor
 *
 * RayIntersectionEngine engine = new RayIntersectionEngine(scene, fog);
 * }</pre>
 *
 * <h2>Configuration Parameters</h2>
 * <ul>
 *   <li>{@link #fogColor} - The color surfaces fade to at maximum fog</li>
 *   <li>{@link #fogDensity} - Controls how quickly fog accumulates with distance (0 = no fog)</li>
 *   <li>{@link #fogRatio} - Base blend ratio between surface and fog color</li>
 * </ul>
 *
 * @see RayIntersectionEngine
 * @author Michael Murray
 */
public class FogParameters {

	/**
	 * The color that surfaces blend toward at maximum fog distance.
	 * Typically a light gray or desaturated sky color.
	 */
	public RGBA fogColor;

	/**
	 * Controls the rate at which fog accumulates with distance.
	 * <ul>
	 *   <li>0.0 = no fog effect</li>
	 *   <li>Higher values = denser fog (surfaces fade faster)</li>
	 * </ul>
	 * Default is 0.0 (fog disabled).
	 */
	public double fogDensity = 0.0;

	/**
	 * Base blending ratio between surface color and fog color.
	 * <ul>
	 *   <li>0.0 = no fog blending</li>
	 *   <li>0.5 = equal blend at reference distance</li>
	 *   <li>1.0 = maximum fog dominance</li>
	 * </ul>
	 * Default is 0.5.
	 */
	public double fogRatio = 0.5;
}
