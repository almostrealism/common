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


package org.almostrealism.chem;

import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.electrostatic.PotentialMap;
import org.almostrealism.electrostatic.ProtonCloud;
import org.almostrealism.physics.Clock;
import org.almostrealism.physics.SphericalAbsorber;

/**
 * An {@link AtomicProtonCloud} represents the positively charged portion of an {@link Atom}.
 * 
 * @author Michael Murray
 */
// TODO  Compute charge based on atomic number
public class AtomicProtonCloud extends ProtonCloud implements SphericalAbsorber {
	/**
	 * Constructs a proton cloud for the given atom.
	 *
	 * @param a  the atom to represent
	 */
	public AtomicProtonCloud(Atom a) { }

	/**
	 * Sets the potential map for this proton cloud.
	 * <p>Not yet implemented.</p>
	 *
	 * @param m  the potential map to set
	 */
	@Override
	public void setPotentialMap(PotentialMap m) {
		// TODO Auto-generated method stub

	}

	/**
	 * Returns the potential map for this proton cloud.
	 * <p>Not yet implemented; always returns {@code null}.</p>
	 *
	 * @return {@code null}
	 */
	@Override
	public PotentialMap getPotentialMap() {
		// TODO Auto-generated method stub
		return null;
	}

	/**
	 * Sets the radius of this spherical absorber.
	 * <p>Not yet implemented.</p>
	 *
	 * @param r  the radius
	 */
	@Override
	public void setRadius(double r) {
		// TODO Auto-generated method stub

	}

	/**
	 * Returns the radius of this spherical absorber.
	 * <p>Not yet implemented; always returns {@code 0}.</p>
	 *
	 * @return {@code 0}
	 */
	@Override
	public double getRadius() {
		// TODO Auto-generated method stub
		return 0;
	}

	/**
	 * Returns the displacement producer for this absorber.
	 * <p>Not yet implemented; always returns {@code null}.</p>
	 *
	 * @return {@code null}
	 */
	@Override
	public Producer<PackedCollection> getDisplacement() {
		// TODO Auto-generated method stub
		return null;
	}

	/**
	 * Absorbs a photon at the given position and with the given direction and energy.
	 * <p>Not yet implemented; always returns {@code false}.</p>
	 *
	 * @param x       the position vector
	 * @param p       the direction vector
	 * @param energy  the photon energy
	 * @return        {@code false}
	 */
	@Override
	public boolean absorb(Vector x, Vector p, double energy) {
		// TODO Auto-generated method stub
		return false;
	}

	/**
	 * Returns a producer for the next emitted photon direction.
	 * <p>Not yet implemented; always returns {@code null}.</p>
	 *
	 * @return {@code null}
	 */
	@Override
	public Producer<PackedCollection> emit() {
		// TODO Auto-generated method stub
		return null;
	}

	/**
	 * Returns the energy of the next emitted photon.
	 * <p>Not yet implemented; always returns {@code 0}.</p>
	 *
	 * @return {@code 0}
	 */
	@Override
	public double getEmitEnergy() {
		// TODO Auto-generated method stub
		return 0;
	}

	/**
	 * Returns the time until the next emission event.
	 * <p>Not yet implemented; always returns {@code 0}.</p>
	 *
	 * @return {@code 0}
	 */
	@Override
	public double getNextEmit() {
		// TODO Auto-generated method stub
		return 0;
	}

	/**
	 * Sets the simulation clock for this absorber.
	 * <p>Not yet implemented.</p>
	 *
	 * @param c  the clock to set
	 */
	@Override
	public void setClock(Clock c) {
		// TODO Auto-generated method stub

	}

	/**
	 * Returns the simulation clock for this absorber.
	 * <p>Not yet implemented; always returns {@code null}.</p>
	 *
	 * @return {@code null}
	 */
	@Override
	public Clock getClock() {
		// TODO Auto-generated method stub
		return null;
	}

	/**
	 * Returns a producer for the position of the next emission event.
	 * <p>Not yet implemented; always returns {@code null}.</p>
	 *
	 * @return {@code null}
	 */
	@Override
	public Producer<PackedCollection> getEmitPosition() {
		// TODO Auto-generated method stub
		return null;
	}

}
