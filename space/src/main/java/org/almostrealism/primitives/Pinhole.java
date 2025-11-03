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
import org.almostrealism.physics.Absorber;
import org.almostrealism.physics.Fast;
import org.almostrealism.physics.Clock;

/**
 * A Pinhole is similar to an AbsorptionPlane except a hole with a specified radius
 * is present in the middle of the plane through which photons may pass without being
 * absorbed. It is useful to place a Pinhole in front of an AbsorptionPlane to create
 * a simple perspective camera.
 * 
 * @author  Michael Murray
 */
public class Pinhole extends Plane implements Absorber, Fast {
	public static double verbose = Math.pow(10.0, -7.0);
	
	private double radius;
	private Clock clock;
	
	/**
	 * @param r  The radius of the pinhole (usually measured in micrometers).
	 */
	public void setRadius(double r) { this.radius = r; }
	
	/**
	 * Returns the radius of the pinhole (usually measured in micrometers).
	 */
	public double getRadius() { return this.radius; }
	
	public boolean absorb(Vector x, Vector p, double energy) {
		double d = Math.abs(x.dotProduct(this.normal.get().evaluate()));
		if (d > this.thick) return false;
		
		double y = Math.abs(x.dotProduct(new Vector(this.up)));
		
		if (this.across == null)
			this.across = new Vector(this.up).crossProduct(this.normal.get().evaluate()).toArray();
		
		double z = Math.abs(x.dotProduct(new Vector(this.across)));
		
		if (Math.sqrt(y * y + z * z) > this.radius)
			return true;
		else
			return false;
	}

	@Override
	public void setAbsorbDelay(double t) { }

	@Override
	public void setOrigPosition(double[] x) { }

	@Override
	public Producer<Vector> emit() { return null; }

	@Override
	public void setClock(Clock c) { this.clock = c; }

	@Override
	public Clock getClock() { return this.clock; }

	@Override
	public double getEmitEnergy() { return 0.0; }

	@Override
	public Producer<Vector> getEmitPosition() { return null; }

	@Override
	public double getNextEmit() { return Integer.MAX_VALUE; }
}
