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

public class Electron {
	private Spin s;
	private int excitation;
	private double excitationEnergyLevels[];

	/** Create a free electron, not a member of any Atom. */
	public Electron(Spin s) {
		this(s, null, 0);
	}

	/**
	 * Construct an electron that is a member of an atom with the
	 * specified number of protons.
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

	public int getExcitation() { return excitation; }
	public void setExcitation(int e) { this.excitation = e; }
	public int getMaxExcitation() { return excitationEnergyLevels.length - 1; }

	public double getExcitationEnergy() { return this.excitationEnergyLevels[excitation]; }
}
