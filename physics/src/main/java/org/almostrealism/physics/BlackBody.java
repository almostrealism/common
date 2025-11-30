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
 * A {@link BlackBody} absorbs all photons it detects and keeps track of
 * a running total average flux.
 * 
 * @author  Michael Murray
 */
public class BlackBody implements Absorber, PhysicalConstants {
//	public static double verbose = 0.08;
	public static double verbose = 1.1;

	protected double energy;
	private Clock clock;

	@Override
	public boolean absorb(Vector x, Vector p, double energy) {
		this.energy += energy;
		return true;
	}
	
	/**
	 * Returns the running total average energy (eV) per microsecond absorbed
	 * by this BlackBody. This can be converted to watts by multiplying the value
	 * by BlockBody.evMsecToWatts.
	 */
	public double getFlux() {
		if (this.clock == null) return 0.0;
		return this.energy / this.clock.getTime();
	}

	@Override
	public Producer<PackedCollection> emit() { return null; }

	@Override
	public double getEmitEnergy() { return 0; }

	@Override
	public Producer<PackedCollection> getEmitPosition() { return null; }

	@Override
	public double getNextEmit() { return Integer.MAX_VALUE; }

	@Override
	public void setClock(Clock c) { this.clock = c; }

	@Override
	public Clock getClock() { return this.clock; }
}
