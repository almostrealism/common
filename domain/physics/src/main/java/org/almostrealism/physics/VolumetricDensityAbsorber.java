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

import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.PackedCollection;

/**
 * {@link VolumetricDensityAbsorber} is an {@link Absorber} implementation that absorbs
 * and re-emits photons based on the law of reflection and Snell's law of refraction.
 * 
 * @author  Michael Murray
 */
public class VolumetricDensityAbsorber implements Absorber {
	private Volume volume;
	
	/** @param v  The Volume for this {@link VolumetricDensityAbsorber}. */
	public void setVolume(Volume v) { this.volume = v; }
	
	/** @return  The Volume used by this {@link VolumetricDensityAbsorber}. */
	public Volume getVolume() { return this.volume; }

	@Override
	public boolean absorb(Vector x, Vector p, double energy) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public Producer<PackedCollection> emit() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Clock getClock() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public double getEmitEnergy() {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public double getNextEmit() {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public void setClock(Clock c) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public Producer<PackedCollection> getEmitPosition() {
		// TODO Auto-generated method stub
		return null;
	}

}
