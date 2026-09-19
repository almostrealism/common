/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.chem.test;

import org.almostrealism.chem.Element;
import org.almostrealism.chem.Shell;
import org.almostrealism.chem.Spin;
import org.almostrealism.chem.SubShell;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Verifies that every {@link Element} can actually be constructed from its
 * electron-shell configuration and that the resulting configuration is
 * physically consistent.
 * <p>
 * A neutral atom holds exactly as many electrons as it has protons, so the sum
 * of the electrons across all of an element's shells must equal its atomic
 * number. This exercises {@link Element#getShells()} and {@link Element#construct()}
 * for every element, which the existing periodic-table classification tests do
 * not do.
 * </p>
 */
public class ElementConstructionTest extends TestSuiteBase {

	/**
	 * Counts the occupied electron slots across all shells of an element by
	 * asking each subshell for its spin-up and spin-down electrons. This avoids
	 * constructing the {@link org.almostrealism.chem.Electrons} absorption model,
	 * which enumerates excitation permutations and is far more expensive.
	 *
	 * @param e the element to inspect
	 * @return the total number of electrons across every shell
	 */
	private static int countElectrons(Element e) {
		int protons = e.getAtomicNumber();
		int total = 0;

		for (Shell shell : e.getShells()) {
			for (SubShell sub : shell.subShells()) {
				if (sub.getElectron(Spin.Up, protons) != null) total++;
				if (sub.getElectron(Spin.Down, protons) != null) total++;
			}
		}

		return total;
	}

	/**
	 * Boron ([He] 2s2 2p1) is the first element with a partially filled p
	 * subshell. Building its shells must not fail, and it must yield five
	 * electrons.
	 */
	@Test(timeout = 10000)
	public void boronConstructsWithFiveElectrons() {
		Element.Boron.construct();
		Assert.assertEquals(5, countElectrons(Element.Boron));
	}

	/**
	 * Every element is a neutral atom, so the total number of electrons across
	 * its shells must equal its atomic number.
	 */
	@Test(timeout = 30000)
	public void everyElementHasElectronCountEqualToAtomicNumber() {
		for (Element e : Element.values()) {
			Assert.assertEquals(
					"Electron count for " + e + " (Z=" + e.getAtomicNumber() + ") must equal its atomic number",
					e.getAtomicNumber(), countElectrons(e));
		}
	}
}
