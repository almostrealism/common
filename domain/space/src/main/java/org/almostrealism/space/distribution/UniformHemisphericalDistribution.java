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
import org.almostrealism.geometry.UniformSphericalRandom;

/**
 * Scatters uniformly over the hemisphere on the side of the surface normal, regardless of the
 * incoming direction.
 */
public class UniformHemisphericalDistribution extends DirectionDistribution {
	@Override
	protected Vector direction(Vector in, Vector orient) {
		Vector r = UniformSphericalRandom.getInstance().evaluate(new Object[0]);
		if (orient.dotProduct(r) < 0) r.multiplyBy(-1.0);
		return r;
	}

	@Override
	public String toString() { return "Hemispherical Distribution"; }
}
