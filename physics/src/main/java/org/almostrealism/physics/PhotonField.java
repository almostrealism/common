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

/*
 * Copyright (C) 2006  Mike Murray
 *
 *  All rights reserved.
 *  This document may not be reused without
 *  express written permission from Mike Murray.
 */

package org.almostrealism.physics;

/**
 * A PhotonField instance represents a collection of photons in three dimensional space.
 * Each photon has a position, direction of propagation, and energy value. The coordinate
 * system is usually scaled so that one unit of position is one micrometer. The direction
 * of propagation for a photon is always a unit length vector, because a photon always
 * travels at the speed of light. Energy is usually measured in electron volts. A photon
 * in a photon field represents a fraction of "real" photon. The photon field keeps track
 * of a granularity, which is the number of photon field photons required to make one "real"
 * photon. A "real" photon is a quantity of energy that can be absorbed by one electron.
 * For more information about the "real" photon, see the documentation section on the quantum
 * requirements for the photon field model.
 * 
 * The photon field also keeps track of an absorber, which it must test for photon absorption
 * for each photon in the photon field each time the clock tick event occurs. This is done
 * by calling the absorb method of the absorber. If the method returns true, the photon was
 * absorbed and should be removed from the field.
 * 
 * The photon field must also check for photon emission by the absorber. This is done by checking
 * the value returned by the getNextEmit method of the absorber. If this method returns zero,
 * the photon field should check the value returned by the getEmitEnergy method of the absorber
 * and add a photon with that energy value to the photon field. The direction of propagation
 * for the photon is given by the value returned by the emit method, which should be called
 * to notify the absorber that emission has occured.
 * 
 * @author  Michael Murray
 */
public interface PhotonField {
	
	void setClock(Clock c);
	Clock getClock();
	
	/**
	 * Calculates the total energy stored by photons contained in the specified
	 * spherical volume.
	 * 
	 * @param x  {x, y, z} - Center of spherical volume.
	 * @param radius  Radius of spherical volume.
	 * @return  The energy contained in the specified spherical volume
	 *          (usually measured in electron volts).
	 */
	double getEnergy(double x[], double radius);
	
	/**
	 * Method called when a clock tick event occurs.
	 * 
	 * @param d  Duration of tick (usually in microseconds).
	 */
	void tick(double d);
	
	/**
	 * @param a  Absorber to use for this photon field.
	 */
	void setAbsorber(Absorber a);
	
	/**
	 * @return  The absorber used by this photon field.
	 */
	Absorber getAbsorber();
	
	/**
	 * @param delta  The granularity, or number of field photons per "real" photon
	 *               for this photon field.
	 */
	void setGranularity(long delta);
	
	/**
	 * @return  The granularity, or number of field photons per "real" photon
	 *          for this photon field.
	 */
	long getGranularity();
	
	/**
	 * @return  The total number of photons maintained by this PhotonField at the time
	 *          the method is called.
	 */
	long getSize();
	
	/**
	 * Sets the longest duration of time that a photon can exist in this PhotonField.
	 * 
	 * @param l  Maximum lifetime for one photon (usually in microseconds).
	 */
	void setMaxLifetime(double l);
	
	/**
	 * Returns the longest duration of time that a photon can exist in this PhotonField.
	 * 
	 * @return  Maximum lifetime for one photon (usually in microseconds).
	 */
	double getMaxLifetime();
	
	/**
	 * Adds a photon to this field. This is a fractional photon, not a "real" photon.
	 * 
	 * @param x  {x, y, z} - The position of the photon to add.
	 * @param p  {x, y, z} - The direction of propagation for the photon (should be a unit vector).
	 * @param energy  The energy of the photon (usually in electron volts).
	 */
	void addPhoton(double x[], double p[], double energy);
	
	/**
	 * Removes all photons contained in the specified spherical volume.
	 * 
	 * @param x  {x, y, z} - The center of the spherical volume.
	 * @param radius  The radius of the spherical volume.
	 * @return  The number of fractional photons removed.
	 */
	int removePhotons(double x[], double radius);
}
