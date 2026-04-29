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

import org.almostrealism.physics.HarmonicAbsorber;
import org.almostrealism.algebra.Vector;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * An absorber that models the collective electron cloud of an alloy material,
 * delegating photon absorption and emission to the valence electron layer.
 * <p>
 * Incoming photons are first offered to the {@link Valence} model. If a valence
 * electron absorbs the photon, the superclass {@link HarmonicAbsorber} is also
 * notified. Photons emitted during valence-electron de-excitation are queued
 * and returned on subsequent {@link #getEmitEnergy()} calls.
 * </p>
 */
public class ElectronCloud extends HarmonicAbsorber {
    /** Queue of photons pending emission from valence electron de-excitation. */
    private final Queue<Photon> toEmit = new ConcurrentLinkedQueue<Photon>();

    /** The valence-electron model used for photon absorption and emission decisions. */
    private Valence valence;

	/** Default constructor for bean deserialization. */
    public ElectronCloud() { }

	/**
	 * Constructs an electron cloud for the given alloy material, creating the specified
	 * number of atom samples in the {@link Valence} model.
	 *
	 * @param material  the alloy material whose electron cloud this represents
	 * @param samples   number of atom samples to use in the valence model
	 */
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

    /**
     * Returns 0 if there are photons queued for emission, or {@link Integer#MAX_VALUE} otherwise.
     * The superclass {@link HarmonicAbsorber#getNextEmit()} cannot be used here because it checks
     * displacement vs quanta ({@code d >= q}), whereas {@link ElectronCloud} emits photons from
     * valence electron transitions queued during {@link #absorb(Vector, Vector, double)}.
     */
    @Override
    public double getNextEmit() {
		return toEmit.isEmpty() ? Integer.MAX_VALUE : 0.0;
	}

    @Override
    public double getEmitEnergy() {
        return toEmit.remove().getEnergy();
    }
}
