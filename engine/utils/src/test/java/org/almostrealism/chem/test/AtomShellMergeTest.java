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

import org.almostrealism.chem.Atom;
import org.almostrealism.chem.Shell;
import org.almostrealism.chem.Spin;
import org.almostrealism.chem.SubShell;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

/**
 * Verifies that {@link Atom} honours its documented contract of merging shells
 * that share a principal quantum number.
 * <p>
 * {@link Atom}'s class documentation states that "when multiple shells with the
 * same energy level (principal quantum number) are provided, they are
 * automatically merged into a single shell." A subshell that is supplied in a
 * separate {@link Shell} of the same energy level must therefore survive
 * construction and remain reachable through {@link Atom#getValenceShell()}, the
 * accessor real consumers (for example {@code Valence}) use to read an atom's
 * outermost electrons.
 * </p>
 */
public class AtomShellMergeTest extends TestSuiteBase {

	/**
	 * Counts the occupied electron slots across the subshells of a single shell
	 * by asking each subshell for its spin-up and spin-down electrons. A proton
	 * count of zero is passed to {@link SubShell#getElectron(Spin, int)} because
	 * only occupancy is being checked, not excitation energy levels.
	 *
	 * @param shell the shell to inspect
	 * @return the total number of electrons across every subshell of the shell
	 */
	private static int countElectrons(Shell shell) {
		int total = 0;

		for (SubShell sub : shell.subShells()) {
			if (sub.getElectron(Spin.Up, 0) != null) total++;
			if (sub.getElectron(Spin.Down, 0) != null) total++;
		}

		return total;
	}

	/**
	 * A carbon-like L shell supplied as two separate {@code n=2} shells (a 2s
	 * shell and a 2p shell) must merge into a single valence shell that retains
	 * all four L-shell electrons. Before the fix the second shell's electrons
	 * are dropped, so the valence shell reports only the two 2s electrons.
	 */
	@Test(timeout = 10000)
	public void sameEnergyShellsMergeIntoValenceShell() {
		Atom atom = new Atom(6, List.of(
				Shell.first(2),        // 1s2
				Shell.s2(2),           // 2s2  (n = 2)
				Shell.p2(1, 1, 0)));   // 2px1 2py1  (n = 2)

		Shell valence = atom.getValenceShell();

		Assert.assertNotNull("The atom must expose a valence shell", valence);
		Assert.assertEquals("The valence shell must be the merged n=2 shell",
				2, valence.getEnergyLevel());
		Assert.assertEquals(
				"The 2s and 2p shells share n=2 and must merge; all four L-shell "
						+ "electrons must survive in the valence shell",
				4, countElectrons(valence));
	}

	/**
	 * The number of distinct subshells at a shared energy level must be preserved
	 * by merging: three occupied orbitals (2s, 2px, 2py) supplied across two
	 * shells must all appear in the merged valence shell.
	 */
	@Test(timeout = 10000)
	public void mergedValenceShellRetainsAllSubShells() {
		Atom atom = new Atom(6, List.of(
				Shell.first(2),
				Shell.s2(2),
				Shell.p2(1, 1, 0)));

		Shell valence = atom.getValenceShell();

		int subShells = 0;
		for (SubShell ignored : valence.subShells()) subShells++;

		Assert.assertEquals("Merging must retain the 2s, 2px and 2py subshells",
				3, subShells);
	}
}
