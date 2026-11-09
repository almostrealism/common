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
 * Refracts according to Snell's law into a medium with the configured refractive index, relative
 * to the medium the photon arrives from. When the photon cannot enter the medium (total internal
 * reflection, possible only for an index below one), it is reflected instead.
 */
public class RefractiveProbabilityDistribution extends DirectionDistribution {
	/** The refractive index of the medium entered, relative to the medium left. */
	private double refractiveIndex = 1.0;

	@Override
	protected Vector direction(Vector in, Vector orient) {
		double cosIn = in.dotProduct(orient);
		Vector tangent = in.subtract(orient.multiply(cosIn));
		double sinIn = tangent.length();
		double sinOut = sinIn / refractiveIndex;

		if (sinOut > 1.0) {
			return ReflectiveProbabilityDistribution.reflect(in, orient);
		}

		Vector out = orient.multiply(-Math.sqrt(1.0 - sinOut * sinOut));
		if (sinIn > 0.0) out = out.subtract(tangent.multiply(sinOut / sinIn));
		return out;
	}

	/**
	 * Sets the refractive index of the medium entered, relative to the medium left.
	 *
	 * @param n the relative refractive index
	 */
	public void setRefractiveIndex(double n) { this.refractiveIndex = n; }

	/**
	 * Returns the refractive index of the medium entered, relative to the medium left.
	 *
	 * @return the relative refractive index
	 */
	public double getRefractiveIndex() { return this.refractiveIndex; }

	@Override
	public String toString() { return "Refractive Distribution"; }
}
