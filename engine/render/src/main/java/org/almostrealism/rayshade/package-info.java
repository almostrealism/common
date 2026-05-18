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
 * Ray-based shading effects for the Almost Realism rendering engine.
 *
 * <p>This package provides physically-based shaders that simulate optical phenomena at
 * surface boundaries:</p>
 * <ul>
 *   <li>{@link org.almostrealism.rayshade.ReflectionShader} — Fresnel-based specular reflection
 *       using Schlick's approximation, with support for recursive reflection bounces,
 *       reflection blur, and environment mapping</li>
 *   <li>{@link org.almostrealism.rayshade.RefractionShader} — Snell's law refraction for
 *       transparent materials (glass, water, crystals), with color attenuation and
 *       variable index of refraction support</li>
 * </ul>
 *
 * <p>Both shaders implement the {@link org.almostrealism.color.Shader} interface and are
 * typically added to surfaces via the {@link org.almostrealism.color.ShaderSet} mechanism.
 * Recursive ray tracing depth is controlled by {@link org.almostrealism.rayshade.ReflectionShader#maxReflections}.</p>
 *
 * @see org.almostrealism.render
 * @see org.almostrealism.raytrace
 */
package org.almostrealism.rayshade;
