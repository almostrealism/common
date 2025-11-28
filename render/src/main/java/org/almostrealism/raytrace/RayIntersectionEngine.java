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

import org.almostrealism.color.RGB;
import org.almostrealism.color.ShaderContext;
import org.almostrealism.geometry.Curve;
import org.almostrealism.geometry.Ray;
import io.almostrealism.relation.Producer;
import org.almostrealism.space.Scene;
import org.almostrealism.color.ShadableSurface;
import org.almostrealism.geometry.DimensionAwareKernel;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link RayIntersectionEngine} is the primary implementation of the {@link Engine} interface,
 * using ray-surface intersection to determine visibility and compute lighting.
 *
 * <p>This engine implements the classic ray tracing algorithm:</p>
 * <ol>
 *   <li>For each ray, find intersections with all surfaces in the scene</li>
 *   <li>Determine the closest (nearest) intersection point</li>
 *   <li>Compute lighting contributions from all lights at that point</li>
 *   <li>Apply surface shading to produce final color</li>
 * </ol>
 *
 * <p>The actual ray-surface-light computation is delegated to {@link LightingEngineAggregator},
 * which creates a {@link IntersectionalLightingEngine} for each surface-light pair and uses
 * ranked choice selection to determine which surface is visible (closest to camera).</p>
 *
 * <p>Note: This implementation does not currently support features like reflection, refraction,
 * or recursive ray tracing (these would need to be added to the lighting/shader layer).</p>
 *
 * @author  Michael Murray
 * @see LightingEngineAggregator
 * @see IntersectionalLightingEngine
 */
public class RayIntersectionEngine implements Engine {
	public static boolean enableAcceleratedAggregator = false;

	private Scene<? extends ShadableSurface> scene;
	private ShaderContext sparams;
	private FogParameters fparams;
	
	/**
	 * Constructs a new {@link RayIntersectionEngine} for the given scene.
	 *
	 * @param s       The {@link Scene} containing surfaces and lights to render
	 * @param fparams Fog parameters for atmospheric effects (currently unused in core tracing)
	 */
	public RayIntersectionEngine(Scene<? extends ShadableSurface> s, FogParameters fparams) {
		this.scene = s;
		this.fparams = fparams;
	}

	/**
	 * Traces a ray through the scene by creating a {@link LightingEngineAggregator} that
	 * computes all possible surface-light interactions and selects the closest visible surface.
	 *
	 * <p>The aggregator creates {@link IntersectionalLightingEngine} instances for each
	 * surface-light pair in the scene. Each lighting engine computes the intersection distance
	 * (rank) and color contribution. The aggregator then uses ranked choice to select the
	 * surface with the smallest positive intersection distance.</p>
	 *
	 * @param r The ray to trace
	 * @return A {@link Producer} that computes the RGB color where the ray intersects the scene
	 */
	@Override
	public Producer<PackedCollection> trace(Producer<Ray> r) {
		List<Curve<PackedCollection>> surfaces = new ArrayList<>();
		for (ShadableSurface s : scene) surfaces.add(s);
		LightingEngineAggregator agg = new LightingEngineAggregator(r, surfaces, scene.getLights(), sparams, true);
		return enableAcceleratedAggregator ? () -> agg.getAccelerated() : new DimensionAwareKernel<>(agg);
	}
}
