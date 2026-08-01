/*
 * Copyright 2020 Michael Murray
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
 * Light source models for photon-field-based rendering.
 *
 * <p>This package provides physical light source implementations that participate
 * in the photon field simulation. Light sources emit photons with physically
 * accurate energy distributions and spatial emission patterns.</p>
 *
 * <h2>Key Classes</h2>
 * <ul>
 *   <li>{@link org.almostrealism.light.LightBulb} - Base class for isotropic emitters
 *       with configurable power and spectral distribution</li>
 *   <li>{@link org.almostrealism.light.PlanarLight} - Planar area light with configurable
 *       orientation and size, supporting Snell-based emission patterns</li>
 *   <li>{@link org.almostrealism.light.CubeLight} - Volumetric light source using
 *       an intensity map to guide emission position sampling</li>
 *   <li>{@link org.almostrealism.light.MercuryXenonLamp} - Spectral light source
 *       modeled after a mercury-xenon discharge lamp spectrum</li>
 * </ul>
 */
package org.almostrealism.light;
