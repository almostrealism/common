/*
 * Copyright 2020 Michael Murray
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

package org.almostrealism.space.distribution;

import org.almostrealism.algebra.Vector;

/**
 * Reflects as a mirror does: the outgoing direction is the incoming direction mirrored about the
 * surface normal.
 */
public class ReflectiveProbabilityDistribution extends DirectionDistribution {
	@Override
	protected Vector direction(Vector in, Vector orient) {
		return reflect(in, orient);
	}

	/**
	 * Mirrors a direction about a surface normal, giving {@code 2 (in . orient) orient - in},
	 * normalized.
	 *
	 * @param in     the unit vector from the surface towards the photon's origin
	 * @param orient the unit surface normal on the same side as {@code in}
	 * @return the unit mirror direction
	 */
	public static Vector reflect(Vector in, Vector orient) {
		Vector r = orient.multiply(2.0 * in.dotProduct(orient)).subtract(in);
		r.normalize();
		return r;
	}

	@Override
	public String toString() { return "Reflective Distribution"; }
}
