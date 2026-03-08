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

import org.almostrealism.electrostatic.Photon;
import org.almostrealism.physics.HarmonicAbsorber;
import org.almostrealism.electrostatic.Valence;
import org.almostrealism.algebra.Vector;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

// TODO  I'm not sure how this should interact with the parent class
//       To be honest, I'm not quite sure what the parent class does
public class ElectronCloud extends HarmonicAbsorber {
    private final Queue<Photon> toEmit = new ConcurrentLinkedQueue<Photon>();

    private Valence valence;

    public ElectronCloud() { }

    public ElectronCloud(Alloy material, int samples) {
        this.valence = new Valence(material, samples);
    }

	public Valence getValence() { return valence; }
	public void setValence(Valence valence) { this.valence = valence; }

	@Override
    public boolean absorb(Vector x, Vector p, double e) {
    	// Try absorbing the photon
        Photon ph = Photon.withEnergy(e);
		boolean absorbed = valence.absorb(ph);
		if (!absorbed) return false;

		// If the parent class doesn't indicate absorption the
		// emitted photons will not be added to the queue
		boolean a = super.absorb(x, p, e);

		Photon emit;

		// Collect all the photons that will be emitted
		// by the valence electrons to return all atoms
		// to the ground state
		while ((emit = valence.emit()) != null) {
			if (a) toEmit.add(emit);
		}

        return a;
    }

    @Override
    public double getNextEmit() { // TODO  Superclass implementation should work...
		return toEmit.isEmpty() ? Integer.MAX_VALUE : 0.0;
	}

    @Override
    public double getEmitEnergy() {
        return toEmit.remove().getEnergy();
    }
}
