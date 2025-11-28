/*
 * Copyright 2025 Michael Murray
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
import org.almostrealism.collect.PackedCollection;

import io.almostrealism.relation.Producer;
import org.almostrealism.color.RGB;
import org.almostrealism.geometry.Ray;

/**
 * The {@link Engine} interface defines the core abstraction for ray tracing engines.
 * An engine is responsible for tracing a ray through a scene and producing the color
 * that should be displayed for that ray.
 *
 * <p>Implementations of this interface encapsulate the strategy for determining how
 * rays interact with scene geometry and lighting. Common implementations include:</p>
 * <ul>
 *   <li>{@link RayIntersectionEngine} - Standard ray-surface intersection based tracing</li>
 *   <li>DistanceEstimationLightingEngine - Distance field based ray marching (alternative approach)</li>
 * </ul>
 *
 * <p>The engine returns a {@link Producer} rather than a concrete RGB value to support
 * lazy evaluation and compilation to accelerated hardware (GPU).</p>
 *
 * @see RayIntersectionEngine
 * @author  Michael Murray
 */
public interface Engine {
	/**
	 * Traces the given ray through the scene and returns a {@link Producer} that will
	 * compute the color for that ray.
	 *
	 * <p>The returned Producer encapsulates a computation graph that:</p>
	 * <ol>
	 *   <li>Finds intersections between the ray and scene surfaces</li>
	 *   <li>Determines which surface is closest to the camera</li>
	 *   <li>Computes lighting at the intersection point</li>
	 *   <li>Applies shading based on surface material and lights</li>
	 *   <li>Returns the final RGB color for the ray</li>
	 * </ol>
	 *
	 * @param r A {@link Producer} that generates the ray to trace
	 * @return A {@link Producer} that will compute the RGB color for the ray
	 */
	Producer<PackedCollection> trace(Producer<Ray> r);
}
