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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Represents an atomic model with electron shells based on quantum mechanics.
 * <p>
 * The {@code Atom} class models the basic structure of an atom, consisting of a nucleus
 * with a specified number of protons and one or more electron {@link Shell}s. The electron
 * shells are organized according to their energy levels, and shells with the same principal
 * quantum number are automatically merged during construction.
 * </p>
 *
 * <h2>Atomic Structure</h2>
 * <p>
 * Atoms are built by specifying:
 * </p>
 * <ul>
 *   <li><b>Proton count</b> - Determines the atomic number and nuclear charge</li>
 *   <li><b>Electron shells</b> - Collections of {@link SubShell}s at specific energy levels</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Create a nitrogen atom (7 protons, 7 electrons)
 * Atom nitrogen = new Atom(7, Arrays.asList(
 *     Shell.first(2),        // K shell: 1s^2
 *     Shell.second(2, 3)     // L shell: 2s^2 2p^3
 * ));
 *
 * // Access valence shell for chemical bonding
 * Shell valence = nitrogen.getValenceShell();
 * Electrons electrons = valence.getElectrons(nitrogen.getProtons());
 * }</pre>
 *
 * <h2>Shell Merging</h2>
 * <p>
 * When multiple shells with the same energy level (principal quantum number) are provided,
 * they are automatically merged into a single shell. This allows for flexible atom construction
 * while maintaining proper quantum mechanical structure.
 * </p>
 *
 * @author Michael Murray
 * @see Shell
 * @see SubShell
 * @see Orbital
 * @see Electron
 */
public class Atom {
	private int protons;
	private List<Shell> shells;
	
	/**
	 * Constructs a new {@code Atom} with the specified number of protons and electron shells.
	 * <p>
	 * Shells with the same energy level (principal quantum number) are automatically merged.
	 * The resulting shell list is stored as an unmodifiable list.
	 * </p>
	 *
	 * @param protons the number of protons in the nucleus (atomic number)
	 * @param shells  the list of electron shells to populate this atom; shells with the same
	 *                energy level will be merged together
	 * @throws IllegalArgumentException if shells contain subshells with mismatched principal quantum numbers
	 */
	public Atom(int protons, List<Shell> shells) {
		this.protons = protons;

		List<Shell> unmerged = new ArrayList<>();
		unmerged.addAll(shells);
		List<Shell> merged = new ArrayList<>();

		while (unmerged.size() > 0) {
			Shell s = unmerged.get(0);

			Iterator<Shell> itr = unmerged.iterator();

			s: while (itr.hasNext()) {
				Shell sh = itr.next();

				if (s == sh) {
					itr.remove();
				} else if (s.getEnergyLevel() == sh.getEnergyLevel()) {
					s.merge(sh);
					itr.remove();
				}
			}

			merged.add(s);
		}

		this.shells = Collections.unmodifiableList(merged);
	}

	/**
	 * Returns the number of protons in this atom's nucleus.
	 *
	 * @return the atomic number (proton count)
	 */
	public int getProtons() { return protons; }

	/**
	 * Returns the valence shell of this atom.
	 * <p>
	 * The valence shell is the outermost electron shell, which has the highest energy level
	 * (principal quantum number). This shell determines the atom's chemical bonding behavior
	 * and reactivity.
	 * </p>
	 *
	 * @return the shell with the highest energy level, or {@code null} if no shells exist
	 */
	public Shell getValenceShell() {
		int highestEnergy = 0;
		Shell sh = null;
		
		for (Shell s : shells) {
			if (s.getEnergyLevel() > highestEnergy) {
				highestEnergy = s.getEnergyLevel();
				sh = s;
			}
		}
		
		return sh;
	}
}
