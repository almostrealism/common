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
import java.util.List;

/**
 * Represents an electron with quantum mechanical properties including spin and excitation state.
 * <p>
 * Each electron has an intrinsic {@link Spin} (up or down) and can exist in various
 * excitation states. When bound to an atom, electrons have discrete energy levels they
 * can occupy, and transitions between these levels correspond to photon absorption
 * or emission.
 * </p>
 *
 * <h2>Excitation States</h2>
 * <p>
 * Electrons can be excited to higher energy levels by absorbing photons with specific
 * energies. The available excitation energy levels depend on:
 * </p>
 * <ul>
 *   <li>The number of protons in the parent atom (affects binding energy)</li>
 *   <li>The available higher orbitals from the current orbital</li>
 *   <li>The Pauli exclusion principle (restricting available states)</li>
 * </ul>
 *
 * <h2>Usage Examples</h2>
 * <pre>{@code
 * // Create a free electron
 * Electron free = new Electron(Spin.Up);
 *
 * // Electrons bound to atoms are typically created via SubShell
 * SubShell subshell = Orbital.s1().populate(1);
 * Electron bound = subshell.getElectron(Spin.Up, 6);  // Carbon atom
 * }</pre>
 *
 * @author Michael Murray
 * @see Spin
 * @see Orbital
 * @see SubShell
 * @see Electrons
 */
public class Electron {
	private Spin s;
	private int excitation;
	private double excitationEnergyLevels[];

	/**
	 * Default constructor for bean deserialization.
	 * <p>
	 * This constructor is available for serialization frameworks but should not
	 * be used directly. Use {@link #Electron(Spin)} for free electrons or create
	 * bound electrons through {@link SubShell#getElectron(Spin, int)}.
	 * </p>
	 */
	public Electron() { }

	/**
	 * Creates a free electron with the specified spin.
	 * <p>
	 * Free electrons are not bound to any atom and do not have defined
	 * excitation energy levels.
	 * </p>
	 *
	 * @param s the spin state of the electron ({@link Spin#Up} or {@link Spin#Down})
	 */
	public Electron(Spin s) {
		this(s, null, 0);
	}

	/**
	 * Creates an electron bound to an atom with specified excitation options.
	 * <p>
	 * This constructor calculates the available excitation energy levels based on
	 * the higher orbitals accessible from the current orbital and the atomic number
	 * (proton count) which affects binding energies.
	 * </p>
	 *
	 * @param s                  the spin state of the electron
	 * @param excitationOptions  the orbitals this electron can be excited to
	 * @param protons            the number of protons in the parent atom
	 */
	protected Electron(Spin s, Iterable<Orbital> excitationOptions, int protons) {
		this.s = s;

		if (excitationOptions !=  null && protons > 0) {
			List<Double> e = new ArrayList<>();
			for (Orbital o : excitationOptions) {
				e.add(o.getEnergy(protons));
			}

			excitationEnergyLevels = new double[e.size() + 1];
			int i = 0;
			excitationEnergyLevels[i++] = 0; // Ground state
			for (Double d : e) excitationEnergyLevels[i++] = d;
		} else {
			// Include one excitation energy?
		}
	}

	/**
	 * Returns the current excitation level index.
	 *
	 * @return the excitation level index (0 = ground state)
	 */
	public int getExcitation() { return excitation; }

	/**
	 * Sets the excitation level of this electron.
	 *
	 * @param e the excitation level index (0 = ground state)
	 */
	public void setExcitation(int e) { this.excitation = e; }

	/**
	 * Returns the spin state of this electron.
	 *
	 * @return the spin ({@link Spin#Up} or {@link Spin#Down})
	 */
	public Spin getSpin() { return s; }

	/**
	 * Sets the spin state of this electron.
	 *
	 * @param s the spin state to set
	 */
	public void setSpin(Spin s) { this.s = s; }

	/**
	 * Returns the array of available excitation energy levels.
	 * <p>
	 * Index 0 represents the ground state (energy = 0). Higher indices
	 * represent increasingly excited states.
	 * </p>
	 *
	 * @return array of energy levels in electron volts (eV)
	 */
	public double[] getExcitationEnergyLevels() { return excitationEnergyLevels; }

	/**
	 * Sets the excitation energy levels for this electron.
	 *
	 * @param excitationEnergyLevels the energy levels in electron volts
	 */
	public void setExcitationEnergyLevels(double[] excitationEnergyLevels) { this.excitationEnergyLevels = excitationEnergyLevels; }

	/**
	 * Returns the maximum excitation level available to this electron.
	 *
	 * @return the highest excitation level index
	 */
	public int getMaxExcitation() { return excitationEnergyLevels.length - 1; }

	/**
	 * Returns the energy of the current excitation state.
	 *
	 * @return the current excitation energy in electron volts (eV)
	 */
	public double getExcitationEnergy() { return this.excitationEnergyLevels[excitation]; }
}
