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


import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Models the valence-shell electrons of an atomic substance by sampling one or more
 * constructed {@link Atom} instances and aggregating their valence electron groups.
 *
 * @author  Michael Murray
 */
public class Valence {
	/** The sampled valence electron groups, one per constructed atom instance. */
    private List<Electrons> atoms;

	/** Default constructor for bean deserialization. */
    public Valence() { }

	/**
	 * Constructs a valence model from the given atomic material with a single sample.
	 *
	 * @param a  the atomic material whose valence shell is modeled
	 */
    public Valence(Atomic a) {
        this(a, 1);
    }

	/**
	 * Constructs a valence model by sampling the given atomic material the specified number of times.
	 * <p>
	 * Each sample constructs a new {@link Atom} via {@link Atomic#construct()} and extracts its
	 * valence-shell electrons. Multiple samples improve statistical accuracy of absorption predictions.
	 * </p>
	 *
	 * @param a        the atomic material whose valence shell is modeled
	 * @param samples  number of atom samples to create
	 */
    public Valence(Atomic a, int samples) {
        atoms = new ArrayList<>();

        for (int i = 0; i < samples; i++) {
            Atom atom = a.construct();
            atoms.add(atom.getValenceShell().getElectrons(atom.getProtons()));
        }
    }

	public List<Electrons> getAtoms() { return atoms; }
	public void setAtoms(List<Electrons> atoms) { this.atoms = atoms; }

	/**
	 * Attempts to absorb the given photon into one of the sampled electron groups.
	 * <p>
	 * Each atom sample is tried in random order. The first sample that successfully absorbs
	 * the photon's energy causes this method to return {@code true}. If no sample absorbs the
	 * photon, returns {@code false}.
	 * </p>
	 *
	 * @param p  the photon to absorb
	 * @return   {@code true} if the photon was absorbed by a valence electron group
	 */
	public boolean absorb(Photon p) {
    	Set<Integer> excludes = new HashSet<>();

    	w: while (excludes.size() < atoms.size()) {
    		int index = (int) (StrictMath.random() * atoms.size());
    		if (excludes.contains(index)) continue w;

    		if (atoms.get(index).absorb(p.getEnergy())) {
				return true;
			} else {
    			excludes.add(index);
			}
		}

		return false;
	}

	/**
	 * Emits a photon from the first electron group that has energy to release.
	 * <p>
	 * Iterates through all sampled electron groups and returns a photon for the first
	 * group that returns a positive emission energy. Returns {@code null} if no group
	 * is ready to emit.
	 * </p>
	 *
	 * @return  the emitted photon, or {@code null} if no emission is available
	 */
	public Photon emit() {
    	for (Electrons e : atoms) {
    		double eV = e.emit();
    		if (eV > 0) return Photon.withEnergy(eV);
		}

		return null;
	}
}
