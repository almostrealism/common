package org.almostrealism.physics;

import org.almostrealism.algebra.Tensor;

import java.util.Arrays;

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

	protected Electrons(Electron e[]) {
		this.e = e;

		this.absorptionEnergies = new Tensor<>();
		refreshAbsorptionEnergies();
	}

	public synchronized boolean absorb(double eV) { return false; }

	public synchronized double emit() { return 0.0; }

	protected synchronized void refreshAbsorptionEnergies() {
		int cursor[] = new int[e.length];

		w: while (true) {
			double energy = 0;

			for (int i = 0; i < cursor.length; i++) {
				e[i].setExcitation(cursor[i]);
				energy += e[i].getExcitationEnergy();
			}

			this.absorptionEnergies.insert(energy, cursor);

			System.out.println(Arrays.toString(cursor) + ": " + energy);

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
		for (int i = 0; i < e.length; i++) {
			e[i].setExcitation(0);
		}
	}
}
