/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism.primitives;

import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.physics.Absorber;
import org.almostrealism.physics.Clock;
import org.almostrealism.physics.Fast;

/**
 * A Pinhole is similar to an AbsorptionPlane except a hole with a specified radius
 * is present in the middle of the plane through which photons may pass without being
 * absorbed. It is useful to place a Pinhole in front of an AbsorptionPlane to create
 * a simple perspective camera.
 * 
 * @author  Michael Murray
 */
public class Pinhole extends Plane implements Absorber, Fast {
	/** Verbosity threshold controlling debug output during absorption (log-scale, very small by default). */
	public static double verbose = Math.pow(10.0, -7.0);

	/** Radius of the hole through which photons may pass unabsorbed (typically in micrometres). */
	private double radius;

	/** The physics clock used to track simulation time. */
	private Clock clock;
	
	/**
	 * Sets the radius of the pinhole.
	 *
	 * @param r  The radius of the pinhole (usually measured in micrometers).
	 */
	public void setRadius(double r) { this.radius = r; }
	
	/**
	 * Returns the radius of the pinhole (usually measured in micrometers).
	 */
	public double getRadius() { return this.radius; }
	
	/**
	 * Returns {@code true} (absorbs) if the photon is within the plane slab but
	 * outside the pinhole radius; returns {@code false} (passes through) if the
	 * photon is inside the hole.
	 *
	 * @param x      the position of the photon
	 * @param p      the direction of the photon
	 * @param energy the energy of the photon
	 * @return {@code true} if the photon is absorbed, {@code false} if it passes through
	 */
	@Override
	public boolean absorb(Vector x, Vector p, double energy) {
		double d = Math.abs(x.dotProduct(new Vector(this.normal.get().evaluate(), 0)));
		if (d > this.thick) return false;

		double y = Math.abs(x.dotProduct(new Vector(this.up)));

		if (this.across == null)
			this.across = new Vector(this.up).crossProduct(new Vector(this.normal.get().evaluate(), 0)).toArray();
		
		double z = Math.abs(x.dotProduct(new Vector(this.across)));

		return Math.sqrt(y * y + z * z) > this.radius;
	}

	/** No-op: absorption delay is not used by this absorber. */
	@Override
	public void setAbsorbDelay(double t) { }

	/** No-op: original position tracking is not used by this absorber. */
	@Override
	public void setOrigPosition(double[] x) { }

	/** Returns {@code null}: this absorber does not emit photons. */
	@Override
	public Producer<PackedCollection> emit() { return null; }

	/** Sets the physics clock used by this absorber. */
	@Override
	public void setClock(Clock c) { this.clock = c; }

	/** Returns the physics clock used by this absorber. */
	@Override
	public Clock getClock() { return this.clock; }

	/** Returns {@code 0.0}: this absorber does not emit energy. */
	@Override
	public double getEmitEnergy() { return 0.0; }

	/** Returns {@code null}: this absorber has no emission position. */
	@Override
	public Producer<PackedCollection> getEmitPosition() { return null; }

	/** Returns {@link Integer#MAX_VALUE}: this absorber never emits. */
	@Override
	public double getNextEmit() { return Integer.MAX_VALUE; }
}
