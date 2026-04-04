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

package org.almostrealism.electrostatic;

import java.util.HashSet;

/**
 * A {@link PotentialMapHashSet} is an implementation of {@link PotentialMapSet} that uses
 * a {@link HashSet} to store the child potential maps.
 * 
 * @author Michael Murray
 */
public class PotentialMapHashSet extends HashSet implements PotentialMapSet {

	/**
	 * Adds a potential map to this set at the specified position.
	 *
	 * @param m  the potential map to add
	 * @param x  {x, y, z} position of the map in space
	 * @return   the new size of the set (not yet implemented, returns 0)
	 */
	public int addPotentialMap(PotentialMap m, double[] x) {
		// TODO Auto-generated method stub
		return 0;
	}

	/**
	 * Removes all potential maps within the specified spherical volume.
	 *
	 * @param x       {x, y, z} center of the removal volume
	 * @param radius  radius of the removal volume
	 * @return        the number of maps removed (not yet implemented, returns 0)
	 */
	public int removePotentialMaps(double[] x, double radius) {
		// TODO Auto-generated method stub
		return 0;
	}

	/**
	 * Removes the specified potential map from this set.
	 *
	 * @param m  the potential map to remove
	 * @return   the new size of the set (not yet implemented, returns 0)
	 */
	public int removePotentialMap(PotentialMap m) {
		// TODO Auto-generated method stub
		return 0;
	}

	/**
	 * Sets the maximum distance between neighboring maps in this set.
	 *
	 * @param radius  the maximum proximity radius
	 */
	public void setMaxProximity(double radius) {
		// TODO Auto-generated method stub

	}

	/**
	 * Returns the maximum distance between neighboring maps in this set.
	 *
	 * @return the maximum proximity radius (not yet implemented, returns 0)
	 */
	public double getMaxProximity() {
		// TODO Auto-generated method stub
		return 0;
	}

	/**
	 * Returns the combined electrostatic potential at the specified point by
	 * summing contributions from all child potential maps.
	 *
	 * @param p  {x, y, z} the position at which to evaluate the potential
	 * @return   the total potential at the specified point (not yet implemented, returns 0)
	 */
	public double getPotential(double[] p) {
		// TODO Auto-generated method stub
		return 0;
	}

}
