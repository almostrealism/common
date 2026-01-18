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

package org.almostrealism.stats;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;

/**
 * Represents a probability distribution over directions on a sphere.
 *
 * <p>Spherical probability distributions are used in graphics and physics to model
 * directional phenomena such as:</p>
 * <ul>
 *   <li>Light reflection (BRDFs)</li>
 *   <li>Light transmission (BTDFs)</li>
 *   <li>Scattering distributions</li>
 *   <li>Emission patterns</li>
 * </ul>
 *
 * <p>The distribution is parameterized by an incoming direction and surface orientation,
 * and produces samples of outgoing directions according to the distribution.</p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Define a BRDF as a spherical distribution
 * SphericalProbabilityDistribution brdf = new MyBRDF();
 *
 * // Given an incoming light direction and surface orientation
 * double[] incomingDir = {0.0, 1.0, 0.0};  // From above
 * double[] surfaceNormal = {0.0, 0.0, 1.0}; // Pointing up
 *
 * // Sample an outgoing reflection direction
 * Producer<PackedCollection> outgoingDir = brdf.getSample(incomingDir, surfaceNormal);
 * }</pre>
 *
 * <h2>Common Implementations</h2>
 * <ul>
 *   <li>Lambertian (diffuse) reflection - cosine-weighted hemisphere</li>
 *   <li>Specular reflection - perfect mirror reflection</li>
 *   <li>Glossy reflection - distribution around specular direction</li>
 *   <li>Transmission - refraction through transparent materials</li>
 * </ul>
 *
 * @see BRDF
 */
public interface SphericalProbabilityDistribution {
	/**
	 * Generates a sample direction from this spherical distribution.
	 *
	 * <p>The sample is generated based on the incoming direction and surface orientation,
	 * and represents a probabilistic outgoing direction according to this distribution.</p>
	 *
	 * @param in the incoming direction vector (typically normalized)
	 * @param orient the surface orientation/normal vector (typically normalized)
	 * @return a producer that generates a sampled outgoing direction
	 */
	Producer<PackedCollection> getSample(double[] in, double[] orient);
}
