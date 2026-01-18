/*
 * Copyright 2017 Michael Murray
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

/**
 * Interface for objects that have a Bidirectional Reflectance Distribution Function (BRDF).
 *
 * <p>A BRDF describes how light is reflected at an opaque surface, defining the
 * relationship between incoming and outgoing light directions. BRDFs are fundamental
 * to physically-based rendering and realistic material simulation.</p>
 *
 * <p>This interface allows objects (typically materials or surfaces) to be associated
 * with a {@link SphericalProbabilityDistribution} that represents their reflection
 * characteristics.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * public class Material implements BRDF {
 *     private SphericalProbabilityDistribution brdf;
 *
 *     public Material(SphericalProbabilityDistribution brdf) {
 *         this.brdf = brdf;
 *     }
 *
 *     @Override
 *     public SphericalProbabilityDistribution getBRDF() {
 *         return brdf;
 *     }
 *
 *     @Override
 *     public void setBRDF(SphericalProbabilityDistribution brdf) {
 *         this.brdf = brdf;
 *     }
 * }
 * }</pre>
 *
 * @see SphericalProbabilityDistribution
 */
public interface BRDF {
	/**
	 * Returns the BRDF for this object.
	 *
	 * @return the bidirectional reflectance distribution function
	 */
	SphericalProbabilityDistribution getBRDF();

	/**
	 * Sets the BRDF for this object.
	 *
	 * @param brdf the bidirectional reflectance distribution function
	 */
	void setBRDF(SphericalProbabilityDistribution brdf);
}
