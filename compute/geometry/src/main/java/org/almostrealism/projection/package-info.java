/*
 * Copyright 2025 Michael Murray
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
 * Camera and projection models for 3D ray generation.
 * Contains camera implementations that generate rays for use in ray-tracing pipelines.
 *
 * <p>The hierarchy is:</p>
 * <ul>
 *   <li>{@link org.almostrealism.projection.OrthographicCamera} — orthographic projection</li>
 *   <li>{@link org.almostrealism.projection.PinholeCamera} — perspective projection with a single focal point</li>
 *   <li>{@link org.almostrealism.projection.ThinLensCamera} — depth-of-field simulation via lens sampling</li>
 * </ul>
 *
 * <p>Ray generation is expressed via {@link org.almostrealism.collect.CollectionProducer} so it can
 * be compiled to native GPU kernels for hardware-accelerated rendering.</p>
 */
package org.almostrealism.projection;
