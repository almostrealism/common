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

package org.almostrealism.color;

/**
 * Implemented by objects that have an associated spectral emission or reflectance profile
 * represented as a {@link ProbabilityDistribution}.
 *
 * <p>Materials and light sources that emit or reflect light with a specific spectral
 * distribution implement this interface to expose their spectral data for physically-based
 * rendering calculations such as wavelength sampling and color integration.</p>
 *
 * @see ProbabilityDistribution
 * @author Mike Murray
 */
public interface Spectrum {
	/**
	 * Returns the {@link ProbabilityDistribution} describing the spectral emission or
	 * reflectance of this object.
	 *
	 * @return the spectral probability distribution
	 */
	ProbabilityDistribution getSpectra();
}
