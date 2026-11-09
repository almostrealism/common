/*
 * Copyright 2026 Michael Murray
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

import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.space.Length;
import org.almostrealism.stats.SphericalProbabilityDistribution;

/**
 * A {@link SphericalProbabilityDistribution} that chooses an outgoing direction at a surface and
 * scales it by a multiplier.
 *
 * <p>Both arguments to {@link #getSample(double[], double[])} are unit vectors: {@code in} points
 * from the surface back towards where the photon came from, and {@code orient} is the surface
 * normal on the same side as {@code in}.</p>
 */
public abstract class DirectionDistribution implements SphericalProbabilityDistribution, Length, CodeFeatures {
	/** The factor every sampled direction is scaled by. */
	private double multiplier = 1.0;

	/**
	 * Chooses the outgoing direction, before it is scaled by the multiplier.
	 *
	 * @param in     the unit vector from the surface towards the photon's origin
	 * @param orient the unit surface normal on the same side as {@code in}
	 * @return the outgoing direction
	 */
	protected abstract Vector direction(Vector in, Vector orient);

	/**
	 * {@inheritDoc}
	 *
	 * <p>The sampled direction is returned by reference rather than as a constant computation:
	 * every sample has a distinct value, and a constant computation would compile a new kernel
	 * for each one.</p>
	 */
	@Override
	public Producer<PackedCollection> getSample(double[] in, double[] orient) {
		Vector r = direction(new Vector(in), new Vector(orient));
		if (multiplier != 1.0) r.multiplyBy(multiplier);
		return p(r);
	}

	@Override
	public double getMultiplier() { return multiplier; }

	@Override
	public void setMultiplier(double m) { this.multiplier = m; }
}
