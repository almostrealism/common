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

import io.almostrealism.relation.Validity;
import org.almostrealism.algebra.Tensor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Hashtable;
import java.util.List;

/**
 * Manages a collection of electrons and their collective excitation states.
 * <p>
 * The {@code Electrons} class models the quantum mechanical behavior of multiple electrons
 * in an atomic system, including photon absorption and emission. It tracks all possible
 * excitation configurations and their corresponding energy levels, enabling accurate
 * simulation of atomic spectra.
 * </p>
 *
 * <h2>Photon Absorption</h2>
 * <p>
 * When a photon with energy matching an allowed transition is absorbed, the electrons
 * transition to an excited configuration. The absorption is quantum-mechanical: the
 * photon energy must match an available transition within the {@link #spectralBandwidth}.
 * </p>
 *
 * <h2>Photon Emission</h2>
 * <p>
 * Excited electrons can emit photons when transitioning to lower energy configurations.
 * The emitted photon energy equals the energy difference between configurations.
 * </p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Get electrons from an atom's valence shell
 * Atom hydrogen = new Atom(1, List.of(Shell.first(1)));
 * Electrons electrons = hydrogen.getValenceShell().getElectrons(1);
 *
 * // Attempt to absorb a photon
 * boolean absorbed = electrons.absorb(10.2);  // Lyman-alpha energy
 *
 * // Emit a photon
 * double emittedEnergy = electrons.emit();
 * }</pre>
 *
 * @author Michael Murray
 * @see Electron
 * @see Shell
 * @see Atom
 */
public class Electrons {
	/**
	 * The maximum difference, in electron volts, between the energy specified to {@link #absorb(double)}
	 * and the energy subsequently returned by {@link #emit()}. The smaller this number is, the more exact
	 * the match must be between photon energy and the excitation energy of the group of electrons for
	 * {@link #absorb(double)} to return true.
	 */
	public static final double spectralBandwidth = 0.001;

	private Electron e[];

	private Tensor<Double> absorptionEnergies;
	private Hashtable<ExcitationConfiguration, Double> configurationMap;

	private List<ExcitationConfiguration> configurations[];
	private double energies[];

	private ExcitationConfiguration excited;

	/**
	 * Default constructor for bean deserialization.
	 * <p>
	 * This constructor is provided for serialization frameworks and should not be
	 * used directly. Use {@link Shell#getElectrons(int)} to create properly
	 * initialized instances.
	 * </p>
	 */
	public Electrons() { }

	/**
	 * Constructs an {@code Electrons} instance with the specified array of electrons.
	 * <p>
	 * This constructor initializes the absorption energy tensor and configuration map
	 * by calculating all possible excitation configurations for the given electrons.
	 * </p>
	 *
	 * @param e the array of electrons to manage
	 */
	protected Electrons(Electron e[]) {
		this.e = e;

		this.absorptionEnergies = new Tensor<>();
		this.configurationMap = new Hashtable<>();
		refreshAbsorptionEnergies();

		// TODO  If any pair of absorption energy categories
		//       is separated by less than the spectralBandwidth
		//       an exception should be thrown or a special
		//       case should be introduced
	}

	/** Returns the array of electrons managed by this instance. @return the electrons */
	public Electron[] getElectrons() { return e; }

	/** Sets the array of electrons. @param e the electrons to set */
	public void setElectrons(Electron[] e) { this.e = e; }

	/** Returns the tensor of absorption energies. @return the absorption energies tensor */
	public Tensor<Double> getAbsorptionEnergies() { return absorptionEnergies; }

	/** Sets the absorption energies tensor. @param absorptionEnergies the tensor to set */
	public void setAbsorptionEnergies(Tensor<Double> absorptionEnergies) { this.absorptionEnergies = absorptionEnergies; }

	/** Returns the map of configurations to energies. @return the configuration map */
	public Hashtable<ExcitationConfiguration, Double> getConfigurationMap() { return configurationMap; }

	/** Sets the configuration map. @param configurationMap the map to set */
	public void setConfigurationMap(Hashtable<ExcitationConfiguration, Double> configurationMap) { this.configurationMap = configurationMap; }

	/** Returns the array of configuration lists indexed by energy level. @return the configurations */
	public List<ExcitationConfiguration>[] getConfigurations() { return configurations; }

	/** Sets the configurations array. @param configurations the configurations to set */
	public void setConfigurations(List<ExcitationConfiguration>[] configurations) { this.configurations = configurations; }

	/** Returns the array of discrete energy levels. @return energy levels in electron volts */
	public double[] getEnergies() { return energies; }

	/** Sets the energy levels. @param energies the energies to set */
	public void setEnergies(double[] energies) { this.energies = energies; }

	/** Returns the current excited configuration, or null if in ground state. @return the excited configuration */
	public ExcitationConfiguration getExcited() { return excited; }

	/** Sets the excited configuration. @param excited the configuration to set */
	public void setExcited(ExcitationConfiguration excited) { this.excited = excited; }

	/**
	 * Attempts to absorb a photon with the specified energy.
	 * <p>
	 * Absorption succeeds if the photon energy matches an available transition within
	 * the {@link #spectralBandwidth}. Upon successful absorption, the electrons
	 * transition to the corresponding excited configuration.
	 * </p>
	 *
	 * @param eV the photon energy in electron volts
	 * @return {@code true} if the photon was absorbed, {@code false} otherwise
	 * @throws IllegalStateException if the electrons are already in an excited state
	 */
	public synchronized boolean absorb(double eV) {
		if (excited != null)
			throw new IllegalStateException("This set of electrons is already excited and cannot absorb another photon");

		ExcitationConfiguration c = random(eV);
		if (c == null) return false;

		this.excited = c;
		c.apply(e);
		return true;
	}

	/**
	 * Emits a photon by transitioning to a lower energy configuration.
	 * <p>
	 * If the electrons are currently excited, this method randomly selects a lower
	 * energy configuration to transition to and returns the energy of the emitted photon.
	 * </p>
	 *
	 * @return the energy of the emitted photon in electron volts, or 0.0 if not excited
	 */
	public synchronized double emit() {
		if (excited == null) return 0.0;

		double d = this.configurationMap.get(excited);
		int index = restrict(d);

		// There should always be a lower
		// energy level to transition to
		assert index > 0;

		// Pick a new configuration with less energy than this one
		ExcitationConfiguration c = random(energies[(int) (StrictMath.random() * index)]);
		double eV = d - this.configurationMap.get(excited);
		c.apply(e);

		return eV;
	}

	/**
	 * Randomly selects a configuration matching the specified energy.
	 *
	 * @param eV the target energy in electron volts
	 * @return a random matching configuration, or null if none match
	 */
	protected ExcitationConfiguration random(double eV) {
		int index = restrict(eV);
		if (index < 0) return null;

		List<ExcitationConfiguration> c = configurations[index];
		if (c == null) return null;
		if (c.size() == 1) return c.get(0);
		return c.get((int) (StrictMath.random() * c.size()));
	}

	/**
	 * Finds the energy level index matching the specified energy.
	 *
	 * @param eV the energy to match in electron volts
	 * @return the index of the matching energy level, or -1 if no match within spectralBandwidth
	 */
	protected int restrict(double eV) {
		for (int i = 0; i < configurations.length; i++) {
			if (Math.abs(energies[i] - eV) < spectralBandwidth) {
				return i;
			}
		}

		return -1;
	}

	/**
	 * Resets all electrons to their ground state (excitation level 0).
	 */
	protected void ground() {
		for (Electron el : e) {
			el.setExcitation(0);
		}

		this.excited = null;
	}

	protected synchronized void refreshAbsorptionEnergies() {
		int cursor[] = new int[e.length];

		List<List<ExcitationConfiguration>> cl = new ArrayList<>();
		List<Double> el = new ArrayList<>();

		w: while (true) {
			double energy = 0;

			for (int i = 0; i < cursor.length; i++) {
				e[i].setExcitation(cursor[i]);
				energy += e[i].getExcitationEnergy();
			}

			this.absorptionEnergies.insert(energy, cursor);
			ExcitationConfiguration c = new ExcitationConfiguration(clone(cursor));

			if (c.isValid()) {
				System.out.println(c + ": " + energy);

				this.configurationMap.put(c, energy);

				int l = el.indexOf(energy);
				if (l < 0) {
					el.add(energy);
					List<ExcitationConfiguration> al = new ArrayList<>();
					al.add(c);
					cl.add(al);
				} else {
					cl.get(l).add(c);
				}
			}

			// Move to the getDependencies permutation of excitation levels
			boolean addOne = true;
			i: for (int i = 0; i < cursor.length; i++) {
				if (!addOne) break i;

				int next = e[i].getExcitation() + 1;

				if (next > e[i].getMaxExcitation()) {
					// Turn over this cursor index and
					// add move to the getDependencies cursor index
					cursor[i] = 0;
				} else {
					// Increment this cursor index
					cursor[i]++;
					addOne = false;
				}
			}

			// This indicates there are no more possible permutations
			if (addOne) break w;
		}

		// Restore ground state
		ground();

		// TODO  Sort configurations by energy level

		this.configurations = cl.toArray(new List[0]);
		this.energies = new double[el.size()];
		for (int i = 0; i < el.size(); i++) energies[i] = el.get(i);
	}

	private int[] clone(int c[]) {
		int d[] = new int[c.length];
		System.arraycopy(c, 0, d, 0, c.length);
		return d;
	}

	/**
	 * Represents a specific configuration of excitation levels for a group of electrons.
	 * <p>
	 * An excitation configuration specifies the excitation level index for each electron
	 * in the group. This is used to track and apply specific quantum states to electrons.
	 * </p>
	 */
	public static class ExcitationConfiguration implements Validity {
		private int cursor[];

		/** Default constructor for serialization. */
		public ExcitationConfiguration() { }

		/**
		 * Constructs a configuration with the specified excitation levels.
		 *
		 * @param cursor array of excitation level indices, one per electron
		 */
		public ExcitationConfiguration(int cursor[]) {
			this.cursor = cursor;
		}

		/** Returns the excitation level indices. @return the cursor array */
		public int[] getCursor() { return cursor; }

		/** Sets the excitation level indices. @param cursor the cursor to set */
		public void setCursor(int[] cursor) { this.cursor = cursor; }

		/**
		 * Applies this configuration to the specified electrons.
		 *
		 * @param e the electrons to configure
		 */
		public void apply(Electron e[]) {
			for (int i = 0; i < cursor.length; i++) {
				e[i].setExcitation(cursor[i]);
			}
		}

		/**
		 * Checks if this configuration is valid according to the Pauli exclusion principle.
		 *
		 * @return {@code true} if valid, {@code false} if two electrons of the same spin
		 *         occupy the same orbital
		 */
		public boolean isValid() {
			// TODO  If two electrons of the same spin occupy the same orbital
			//       in this configuration, return false
			return true;
		}

		/**
		 * Returns a string representation of this configuration.
		 *
		 * @return string showing the excitation levels
		 */
		public String toString() {
			return "ExcitationConfiguration" + Arrays.toString(cursor);
		}
	}
}
