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

/**
 * High-level ray tracing rendering pipeline for the Almost Realism engine.
 *
 * <p>This package provides the top-level orchestration classes that coordinate
 * camera ray generation, scene rendering, supersampling, and image output:</p>
 * <ul>
 *   <li>{@link org.almostrealism.render.RayTracedScene} — integrates engine, camera, and
 *       render parameters to produce a {@link org.almostrealism.color.RealizableImage}</li>
 *   <li>{@link org.almostrealism.render.RayTracer} — thin wrapper around an
 *       {@link org.almostrealism.raytrace.Engine} with optional thread-pool execution</li>
 *   <li>{@link org.almostrealism.render.SuperSampler} — anti-aliasing via multi-sample
 *       averaging within each pixel</li>
 *   <li>{@link org.almostrealism.render.Pixel} — per-pixel sample collection for
 *       supersampled rendering</li>
 * </ul>
 *
 * <p>The rendering pipeline is:</p>
 * <ol>
 *   <li>Camera generates rays for each pixel/supersample position</li>
 *   <li>RayTracer delegates each ray to the configured Engine</li>
 *   <li>Engine computes the color via surface intersection and lighting</li>
 *   <li>SuperSampler averages multiple samples per pixel for anti-aliasing</li>
 *   <li>RealizableImage assembles the results into a 2D pixel array</li>
 * </ol>
 *
 * @see org.almostrealism.raytrace
 * @see org.almostrealism.rayshade
 */
package org.almostrealism.render;
