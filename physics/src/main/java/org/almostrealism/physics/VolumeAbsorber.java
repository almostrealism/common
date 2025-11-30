/*
 * Copyright 2020 Michael Murray
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

import io.almostrealism.relation.Producer;
import io.almostrealism.uml.Nameable;
import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorFeatures;
import org.almostrealism.collect.PackedCollection;

/**
 * A {@link VolumeAbsorber} is a wrapper for {@link Absorber} implementations that
 * restricts absorption to photons that are contained within a specified volume.
 * 
 * @author  Michael Murray
 */
public class VolumeAbsorber implements Absorber, Nameable, VectorFeatures {
	protected Volume<?> volume;
	protected Absorber absorber;
	protected String name;
	
	public VolumeAbsorber() { }
	
	/**
	 * Constructs a VolumeAbsorber to wrap the specified Absorber. This VolumeAbsorber
	 * instance will only allow photons to be absorbed by the absorber if those photons are
	 * within the specified volume.
	 */
	public VolumeAbsorber(Volume v, Absorber a) {
		this.volume = v;
		this.absorber = a;
	}

	@Override
	public String getName() { return this.name; }

	@Override
	public void setName(String name) { this.name = name; }

	public Absorber getAbsorber() { return absorber; }

	public void setAbsorber(Absorber absorber) { this.absorber = absorber; }

	/**
	 * Returns false if the vector x is not inside the volume stored by this
	 * {@link VolumeAbsorber}. Otherwise, the absorb method is called on the
	 * absorber
	 * wrapped by this {@link VolumeAbsorber}.
	 */
	@Override
	public boolean absorb(Vector x, Vector p, double energy) {
		if (this.volume.inside(v(x)))
			return this.absorber.absorb(x, p, energy);
		else
			return false;
	}
	
	/**
	 * Sets the Volume instance used by this VolumeAbsorber.
	 * 
	 * @param v  Volume to use.
	 */
	public void setVolume(Volume v) { this.volume = v; }
	
	/**
	 * @return  The Volume instance used by this VolumeAbsorber.
	 */
	public Volume getVolume() { return this.volume; }
	
	/**
	 * Calls the emit method on the Absorber instance wrapped by this VolumeAbsorber.
	 */
	@Override
	public Producer<PackedCollection> emit() { return this.absorber.emit(); }
	
	/**
	 * Calls the getEmitEnergy method on the Absorber instance wrapped by this VolumeAbsorber.
	 */
	@Override
	public double getEmitEnergy() { return this.absorber.getEmitEnergy(); }
	
	/**
	 * Calls the getNextEmit method on the Absorber instance wrapped by this VolumeAbsorber.
	 */
	@Override
	public double getNextEmit() { return this.absorber.getNextEmit(); }
	
	/**
	 * Calls the getEmitPosition method on the Absorber instance wrapped by this VolumeAbsorber.
	 */
	@Override
	public Producer<PackedCollection> getEmitPosition() { return this.absorber.getEmitPosition(); }
	
	/**
	 * Calls the setClock method on the Absorber instance wrapped by this VolumeAbsorber.
	 */
	@Override
	public void setClock(Clock c) { this.absorber.setClock(c); }
	
	/**
	 * Calls the getClock method on the Absorber instance wrapped by this VolumeAbsorber.
	 */
	@Override
	public Clock getClock() { return this.absorber.getClock(); }

	@Override
	public String toString() {
		if (this.name == null)
			return super.toString();
		else
			return this.name + " (" + super.hashCode() + ")";
	}
}
