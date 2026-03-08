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

import org.almostrealism.physics.Atomic;
import org.almostrealism.physics.Atom;
import org.almostrealism.physics.Electrons;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * @author  Michael Murray
 */
public class Valence {
    private List<Electrons> atoms;

    public Valence() { }

    public Valence(Atomic a) {
        this(a, 1);
    }

    public Valence(Atomic a, int samples) {
        atoms = new ArrayList<>();

        for (int i = 0; i < samples; i++) {
            Atom atom = a.construct();
            atoms.add(atom.getValenceShell().getElectrons(atom.getProtons()));
        }
    }

	public List<Electrons> getAtoms() { return atoms; }
	public void setAtoms(List<Electrons> atoms) { this.atoms = atoms; }

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

	public Photon emit() {
    	for (Electrons e : atoms) {
    		double eV = e.emit();
    		if (eV > 0) return Photon.withEnergy(eV);
		}

		return null;
	}
}
