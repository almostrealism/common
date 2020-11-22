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

import org.almostrealism.algebra.Vector;
import org.almostrealism.time.Clock;
import org.almostrealism.util.Evaluable;

/**
 * An Absorber instance represents a solid object that absorbs and emits energy
 * in a photon field. An Absorber implementation must provide a method for absorbing
 * and emitting energy. An absorber will be initialized with a reference to a Clock
 * object which is used to keep track of when an emmission should occur (see the
 * getNextEmit method). Because of conservation of energy, the absorber must only emit
 * a quantity of energy no greater than the energy it has absorbed. The common unit for
 * measuring energy is electron volts. The common unit for measuring distance is micrometers,
 * meaning that a vector of unit length is one micrometer long.
 * 
 * Different Absorber implementations are provided most of which make reference to a Volume
 * instance, which describes the actually 3D space occupied by the solid. If an Absorber
 * implementation maintains reference to a Volume instance, photons should only be absorbed
 * and emitted within the bounds described by the Volume instance.
 * 
 * @author  Michael Murray
 */
public interface Absorber {
	/**
	 * Method called when this absorber interacts with a photon.
	 * 
	 * @param x  {x, y, z} - The position of the photon to be absorbed relative
	 *           to the coordinate system of this absorber.
	 * @param p  {x, y, z} - The direction of propagation of the photon to be absorbed.
	 *           This will be a unit vector, because all photons travel at the speed of light.
	 * @param energy  The energy of the photon to be absorbed.
	 * @return  True if the photon was absorbed by this absorber, false otherwise.
	 */
	boolean absorb(Vector x, Vector p, double energy);
	
	/**
	 * Method called when this absorber is to emit a photon.
	 * 
	 * @return  {x, y, z} - The direction of propagation of the emitted photon.
	 *          This should be a unit vector, because all photons travel at
	 *          100 percent of the speed of light.
	 */
	Evaluable<Vector> emit();
	
	/**
	 * @return  The quantity of energy that would be emitted by this absorber if the emit
	 *          method were invoked right now. (Usually measured in electron volts).
	 */
	double getEmitEnergy();
	
	/**
	 * @return  The time until this absorber will getDependencies emit a photon. (Usually measured in
	 *          microseconds).
	 */
	double getNextEmit();


	/**
	 * @return  {x, y, z} - The position of the photon that will getDependencies be emitted by this
	 *          Absorber.
	 */
	Evaluable<Vector> getEmitPosition();
	
	/** @param c  The Clock instance for this absorber to use to keep time. */
	void setClock(Clock c);
	
	/** @return  The Clock instance used by this absorber to keep time. */
	Clock getClock();
}
