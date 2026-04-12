/*
 * Copyright 2018 Michael Murray
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

package org.almostrealism.physics;

/**
 * Interface for absorbers that support optimized photon absorption with delayed processing.
 * <p>
 * The {@code Fast} interface extends the absorption model by allowing absorbers to specify
 * a delay between when a photon interaction is detected and when the actual absorption occurs.
 * This optimization allows for more efficient simulation of photon propagation by batching
 * absorption events or interpolating positions.
 * </p>
 *
 * <h2>Optimization Strategy</h2>
 * <p>
 * Instead of immediately processing each photon absorption, implementations can:
 * </p>
 * <ol>
 *   <li>Record the original position of the photon at detection time</li>
 *   <li>Calculate the time until actual absorption occurs</li>
 *   <li>Process the absorption at the correct future simulation time</li>
 * </ol>
 *
 * <h2>Usage</h2>
 * <p>
 * This interface is typically implemented by {@link Absorber} implementations that need
 * high-performance photon field simulations with accurate timing.
 * </p>
 *
 * @author Michael Murray
 * @see Absorber
 * @see PhotonField
 */
// TODO  getDependencies appears to have been injected by a refactor...
public interface Fast {
	/**
	 * Sets the time until the getDependencies photon should actually be absorbed.
	 * 
	 * @param time  Time until actual absorption (usually in microseconds).
	 */
	void setAbsorbDelay(double time);
	
	/**
	 * Sets the position of the getDependencies photon at the current time (before the
	 * "absorb delay" time specified by the setAbsorbDelay method).
	 * 
	 * @param x  {x, y, z} - Original position of the getDependencies photon.
	 */
	void setOrigPosition(double x[]);
}
