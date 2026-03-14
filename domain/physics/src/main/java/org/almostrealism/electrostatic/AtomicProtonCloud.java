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


package org.almostrealism.electrostatic;

import org.almostrealism.physics.SphericalAbsorber;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.physics.Atom;
import org.almostrealism.physics.Clock;

/**
 * An {@link AtomicProtonCloud} represents the positively charged portion of an {@link Atom}.
 * 
 * @author Michael Murray
 */
// TODO  Compute charge based on atomic number
public class AtomicProtonCloud extends ProtonCloud implements SphericalAbsorber {
	private final Atom atom;

	public AtomicProtonCloud(Atom a) { this.atom = a; }

	public void setPotentialMap(PotentialMap m) {
		// TODO Auto-generated method stub

	}

	public PotentialMap getPotentialMap() {
		// TODO Auto-generated method stub
		return null;
	}

	public void setRadius(double r) {
		// TODO Auto-generated method stub

	}

	public double getRadius() {
		// TODO Auto-generated method stub
		return 0;
	}

	public Producer<PackedCollection> getDisplacement() {
		// TODO Auto-generated method stub
		return null;
	}

	public boolean absorb(Vector x, Vector p, double energy) {
		// TODO Auto-generated method stub
		return false;
	}

	public Producer<PackedCollection> emit() {
		// TODO Auto-generated method stub
		return null;
	}

	public double getEmitEnergy() {
		// TODO Auto-generated method stub
		return 0;
	}

	public double getNextEmit() {
		// TODO Auto-generated method stub
		return 0;
	}

	public void setClock(Clock c) {
		// TODO Auto-generated method stub

	}

	public Clock getClock() {
		// TODO Auto-generated method stub
		return null;
	}

	public Producer<PackedCollection> getEmitPosition() {
		// TODO Auto-generated method stub
		return null;
	}

}
