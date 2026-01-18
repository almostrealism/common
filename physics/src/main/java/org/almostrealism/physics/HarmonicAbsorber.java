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

import org.almostrealism.algebra.VectorFeatures;
import org.almostrealism.electrostatic.PotentialMap;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.ZeroVector;
import org.almostrealism.collect.PackedCollection;

/**
 * A HarmonicAbsorber object represents a spherical absorber that stores
 * energy proportional to the square of the displacement vector.
 * 
 * @author  Michael Murray
 */
public class HarmonicAbsorber implements SphericalAbsorber, VectorFeatures {
	public static double verbose = Math.pow(10.0, -3.0);
	
	private Clock clock;
	private double energy, radius, k, q, d;
	private Producer<PackedCollection> dp;
	private Vector place;

	public HarmonicAbsorber() {
		this.place = new Vector(ZeroVector.getEvaluable().evaluate(), 0);
	}

	@Override
	public void setPotentialMap(PotentialMap m) { }

	@Override
	public PotentialMap getPotentialMap() { return null; }
	
	public void setRigidity(double k) { this.k = k; }
	public double getRigidity() { return this.k; }

	@Override
	public void setRadius(double r) { this.radius = r; }

	@Override
	public double getRadius() { return this.radius; }
	
	public void setQuanta(double q) { this.q = q; }
	public double getQuanta() { return this.q; }

	@Override
	public Producer<PackedCollection> getDisplacement() {
		return multiply(p(place), dp);
	}
	
	protected void updateDisplacement() {
		this.d = radius * Math.sqrt(energy / k);
		double off = d / place.length();
		this.dp = vector(off, off, off);
	}
	
	public boolean absorb(Vector x, Vector p, double energy) {
		if (x.length() > this.radius) return false;
		
		if (Math.random() < verbose)
			System.out.println("HarmonicAbsorber: Absorb energy = " + energy);

		place = (Vector) add(v(place), v(p)).get().evaluate();
		this.energy += energy;
		
		this.updateDisplacement();
		
		return true;
	}

	@Override
	public Producer<PackedCollection> emit() {
		double e = this.getEmitEnergy();
		this.energy -= e;

		// TODO  Perform computation within the returned producer

		double pd = place.length();
		Vector p = place.divide(pd);
		
		this.updateDisplacement();
		
		return v(p);
	}

	@Override
	public double getEmitEnergy() {
		double dq = this.d - this.q;
		double e = this.energy - this.k * dq * dq;
		
		if (Math.random() < verbose)
			System.out.println("HarmonicAbsorber: Emit energy = " + e);
		
		return e;
	}

	@Override
	public Producer<PackedCollection> getEmitPosition() { return this.getDisplacement(); }

	@Override
	public double getNextEmit() {
		if (Math.random() < HarmonicAbsorber.verbose)
			System.out.println("HarmonicAbsorber: D = " + this.d);
		
		if (this.d >= this.q)
			return 0.0;
		else
			return Integer.MAX_VALUE;
	}
	
	public void setClock(Clock c) { this.clock = c; }
	public Clock getClock() { return this.clock; }
}
