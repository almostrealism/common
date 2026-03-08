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

import org.almostrealism.algebra.VectorMath;
import org.almostrealism.physics.Absorber;

/**
 * A ProtonCloud is an absorber that provides a potential map that uses coulombs
 * law to calculate the potential energy for all points in space assuming that
 * a positive charge is located at the origin of the internal coordinate system
 * for the absorber. The magnitude of the positive charge is, by default 1.0
 * (meaning the opposite of the charge on an electron).
 * 
 * @author Mike Murray
 */
public abstract class ProtonCloud implements Absorber, PotentialMap {
	public static double k = 1.0;
	
	private double charge = 1.0;
	
	/**
	 * @param c  The charge value to use for this ProtonCloud. (Usually measured
	 *           in units such that -1.0 is the charge on an electron).
	 */
	public void setCharge(double c) { this.charge = c; }
	
	/**
	 * @return c  The charge value used by this ProtonCloud. (Usually measured
	 *            in units such that -1.0 is the charge on an electron).
	 */
	public double getCharge() { return this.charge; }
	
	/**
	 * @param p  {x, y, z} - A position in space relative to the internal coordinate
	 *           system for this absorber.
	 * @return  The potential at the specified point (usually measured in volts).
	 */
	public double getPotential(double[] p) {
		return ProtonCloud.k * this.charge / VectorMath.length(p);
	}
}
