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

import org.almostrealism.algebra.Tensor;
import org.almostrealism.bean.Validity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Hashtable;
import java.util.List;

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

	/** Available for bean decoding, do not use. */
	public Electrons() { }

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

	public Electron[] getElectrons() { return e; }
	public void setElectrons(Electron[] e) { this.e = e; }
	public Tensor<Double> getAbsorptionEnergies() { return absorptionEnergies; }
	public void setAbsorptionEnergies(Tensor<Double> absorptionEnergies) { this.absorptionEnergies = absorptionEnergies; }
	public Hashtable<ExcitationConfiguration, Double> getConfigurationMap() { return configurationMap; }
	public void setConfigurationMap(Hashtable<ExcitationConfiguration, Double> configurationMap) { this.configurationMap = configurationMap; }
	public List<ExcitationConfiguration>[] getConfigurations() { return configurations; }
	public void setConfigurations(List<ExcitationConfiguration>[] configurations) { this.configurations = configurations; }
	public double[] getEnergies() { return energies; }
	public void setEnergies(double[] energies) { this.energies = energies; }
	public ExcitationConfiguration getExcited() { return excited; }
	public void setExcited(ExcitationConfiguration excited) { this.excited = excited; }

	public synchronized boolean absorb(double eV) {
		if (excited != null)
			throw new IllegalStateException("This set of electrons is already excited and cannot absorb another photon");

		ExcitationConfiguration c = random(eV);
		if (c == null) return false;

		this.excited = c;
		c.apply(e);
		return true;
	}

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

	protected ExcitationConfiguration random(double eV) {
		int index = restrict(eV);
		if (index < 0) return null;

		List<ExcitationConfiguration> c = configurations[index];
		if (c == null) return null;
		if (c.size() == 1) return c.get(0);
		return c.get((int) (StrictMath.random() * c.size()));
	}

	protected int restrict(double eV) {
		for (int i = 0; i < configurations.length; i++) {
			if (Math.abs(energies[i] - eV) < spectralBandwidth) {
				return i;
			}
		}

		return -1;
	}

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

			// Move to the next permutation of excitation levels
			boolean addOne = true;
			i: for (int i = 0; i < cursor.length; i++) {
				if (!addOne) break i;

				int next = e[i].getExcitation() + 1;

				if (next > e[i].getMaxExcitation()) {
					// Turn over this cursor index and
					// add move to the next cursor index
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

	protected class ExcitationConfiguration implements Validity {
		private int cursor[];

		public ExcitationConfiguration(int cursor[]) {
			this.cursor = cursor;
		}

		public void apply(Electron e[]) {
			for (int i = 0; i < cursor.length; i++) {
				e[i].setExcitation(cursor[i]);
			}
		}

		public boolean isValid() {
			// TODO  If two electrons of the same spin occupy the same orbital
			//       in this configuration, return false
			return true;
		}

		public String toString() {
			return "ExcitationConfiguration" + Arrays.toString(cursor);
		}
	}
}
