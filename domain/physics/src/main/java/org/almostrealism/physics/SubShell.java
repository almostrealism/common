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

/**
 * Represents a subshell containing electrons in a specific orbital.
 * <p>
 * A subshell consists of an {@link Orbital} and the electrons that occupy it.
 * According to the Pauli exclusion principle, each orbital can hold at most
 * two electrons with opposite spins ({@link Spin#Up} and {@link Spin#Down}).
 * </p>
 *
 * <h2>Electron Capacity</h2>
 * <p>
 * A subshell can contain:
 * </p>
 * <ul>
 *   <li><b>1 electron</b> - Only the spin-up electron is present</li>
 *   <li><b>2 electrons</b> - Both spin-up and spin-down electrons are present</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Create a subshell with one electron in the 1s orbital
 * SubShell subshell1 = new SubShell(Orbital.s1(), 1);
 *
 * // Or use the convenience method
 * SubShell subshell2 = Orbital.s1().populate(2);
 *
 * // Get the electron with spin-up from a carbon atom
 * Electron e = subshell2.getElectron(Spin.Up, 6);
 * }</pre>
 *
 * @author Michael Murray
 * @see Orbital
 * @see Shell
 * @see Electron
 * @see Spin
 */
public class SubShell {
	private Orbital o;
	private int e;

	/**
	 * Constructs a subshell with the specified orbital and electron count.
	 *
	 * @param o         the orbital for this subshell
	 * @param electrons the number of electrons (must be 1 or 2)
	 * @throws IllegalArgumentException if electrons is not 1 or 2
	 */
	public SubShell(Orbital o, int electrons) {
		if (electrons < 1 || electrons > 2) throw new IllegalArgumentException("A SubShell may only contain 1 or 2 electrons");
		this.o = o;
		this.e = electrons;
	}
	
	/**
	 * Returns the orbital associated with this subshell.
	 *
	 * @return the orbital
	 */
	public Orbital getOrbital() { return o; }

	/**
	 * Returns an electron from this subshell with the specified spin.
	 * <p>
	 * The electron is created with excitation options based on higher orbitals
	 * and bound to an atom with the specified number of protons.
	 * </p>
	 *
	 * @param s       the spin of the electron to retrieve
	 * @param protons the number of protons in the parent atom (affects energy levels)
	 * @return the electron with the specified spin, or {@code null} if not present
	 */
	public Electron getElectron(Spin s, int protons) {
		switch (s) {
			case Up:
				return e >= 1 ? new Electron(s, o.getHigherOrbitals(), protons) : null;

			case Down:
				return e >= 2 ? new Electron(s, o.getHigherOrbitals(), protons) : null;
		}

		return null;
	}

	/**
	 * Returns a string representation of this subshell.
	 *
	 * @return a string in the format "SubShell[n]" where n is the principal quantum number
	 */
	public String toString() { return "SubShell[" + o.getPrincipal() + "]"; }
}
