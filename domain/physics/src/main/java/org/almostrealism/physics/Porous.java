/*
 * Copyright 2018 Michael Murray
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

package org.almostrealism.physics;

/**
 * Interface for materials or objects that have porosity.
 * <p>
 * Porosity is a measure of the void (empty) spaces in a material, expressed as a fraction
 * or percentage of the total volume. This property is important for simulating how light,
 * sound, or other physical phenomena interact with porous materials such as sponges,
 * foams, or granular media.
 * </p>
 *
 * <h2>Porosity Values</h2>
 * <ul>
 *   <li><b>0.0</b> - Completely solid material with no voids</li>
 *   <li><b>1.0</b> - Completely empty (100% void space)</li>
 *   <li><b>Typical values</b> - Most porous materials range from 0.2 to 0.8</li>
 * </ul>
 *
 * <h2>Applications</h2>
 * <ul>
 *   <li>Light absorption and scattering through porous media</li>
 *   <li>Sound absorption characteristics</li>
 *   <li>Fluid flow through permeable materials</li>
 * </ul>
 *
 * @author Michael Murray
 * @see Absorber
 */
public interface Porous {

	/**
	 * Returns the porosity of this material.
	 * <p>
	 * Porosity is defined as the ratio of void volume to total volume,
	 * typically expressed as a value between 0.0 (completely solid)
	 * and 1.0 (completely void).
	 * </p>
	 *
	 * @return the porosity value, ranging from 0.0 to 1.0
	 */
	double getPorosity();
}
