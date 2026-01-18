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

/**
 * Comprehensive color representation, shading systems, and lighting models for rendering.
 *
 * <p>This package provides the core color infrastructure for the Almost Realism rendering
 * pipeline, including:</p>
 *
 * <h2>Color Representation</h2>
 * <ul>
 *   <li>{@link org.almostrealism.color.RGB} - Three-channel color with hardware-accelerated operations</li>
 *   <li>{@link org.almostrealism.color.RGBA} - RGB with alpha (transparency) channel</li>
 *   <li>{@link org.almostrealism.color.RGBFeatures} - Factory methods and utilities for colors</li>
 * </ul>
 *
 * <h2>Lighting System</h2>
 * <ul>
 *   <li>{@link org.almostrealism.color.Light} - Base interface for all light sources</li>
 *   <li>{@link org.almostrealism.color.PointLight} - Omnidirectional light with attenuation</li>
 *   <li>{@link org.almostrealism.color.AmbientLight} - Uniform environmental lighting</li>
 *   <li>{@link org.almostrealism.color.DirectionalAmbientLight} - Sunlight-like directional light</li>
 *   <li>{@link org.almostrealism.color.LightingContext} - State container for lighting calculations</li>
 * </ul>
 *
 * <h2>Shading System</h2>
 * <ul>
 *   <li>{@link org.almostrealism.color.Shader} - Base interface for shading algorithms</li>
 *   <li>{@link org.almostrealism.color.DiffuseShader} - Lambertian diffuse reflection</li>
 *   <li>{@link org.almostrealism.color.HighlightShader} - Phong specular highlights</li>
 *   <li>{@link org.almostrealism.color.BlendingShader} - Stylized cool-to-warm shading</li>
 *   <li>{@link org.almostrealism.color.SilhouetteShader} - Flat-color silhouette rendering</li>
 *   <li>{@link org.almostrealism.color.ShaderSet} - Composite shader for combining effects</li>
 *   <li>{@link org.almostrealism.color.ShaderContext} - Complete rendering context</li>
 * </ul>
 *
 * <h2>Surface Integration</h2>
 * <ul>
 *   <li>{@link org.almostrealism.color.Shadable} - Interface for objects that can be shaded</li>
 *   <li>{@link org.almostrealism.color.ShadableSurface} - Surface with front/back shading control</li>
 *   <li>{@link org.almostrealism.color.Colorable} - Interface for objects with color properties</li>
 * </ul>
 *
 * <h2>Quick Start</h2>
 * <pre>{@code
 * import static org.almostrealism.color.RGBFeatures.*;
 *
 * // Create colors
 * RGB red = new RGB(1.0, 0.0, 0.0);
 * CollectionProducer blue = rgb(0.0, 0.0, 1.0);
 *
 * // Set up lighting
 * PointLight light = new PointLight(new Vector(5, 5, 5), 1.0, new RGB(1, 1, 1));
 * light.setAttenuationCoefficients(1.0, 0.0, 0.0);  // Inverse-square falloff
 *
 * // Create shader
 * ShaderSet<ShaderContext> material = new ShaderSet<>();
 * material.add(new DiffuseShader());
 * material.add(new HighlightShader(white(), 32.0));
 * }</pre>
 *
 * @see org.almostrealism.texture
 * @author Michael Murray
 */
package org.almostrealism.color;
