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

import org.almostrealism.chem.Orbital;
import org.almostrealism.chem.Shell;
import org.almostrealism.chem.Spin;
import org.almostrealism.chem.SubShell;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Pins the per-orbital electron distribution produced by {@link Shell}'s subshell
 * builders, which fill the orbitals of a p, d or f subshell by Hund's rule: a single
 * electron is placed in every orbital before any orbital receives a second electron.
 * <p>
 * The distribution is produced by private helpers on {@link Shell} and is observed here
 * through the public {@code second}/{@code third}/{@code fourth} factory methods by reading
 * the occupancy of each resulting {@link SubShell}. The expected occupancy tables below are
 * written as literals so they continue to describe the intended behaviour independently of
 * however the distribution happens to be implemented.
 * </p>
 */
public class ShellElectronDistributionTest extends TestSuiteBase {

	/**
	 * Expected occupancy of the three p orbitals for every valid total count 0..6, indexed
	 * by the total electron count. Each row is ordered by magnetic quantum number (px, py, pz).
	 */
	private static final int[][] P_TABLE = {
			{ 0, 0, 0 },
			{ 1, 0, 0 },
			{ 1, 1, 0 },
			{ 1, 1, 1 },
			{ 2, 1, 1 },
			{ 2, 2, 1 },
			{ 2, 2, 2 }
	};

	/**
	 * Expected occupancy of the five d orbitals for every valid total count 0..10, indexed
	 * by the total electron count and ordered by magnetic quantum number.
	 */
	private static final int[][] D_TABLE = {
			{ 0, 0, 0, 0, 0 },
			{ 1, 0, 0, 0, 0 },
			{ 1, 1, 0, 0, 0 },
			{ 1, 1, 1, 0, 0 },
			{ 1, 1, 1, 1, 0 },
			{ 1, 1, 1, 1, 1 },
			{ 2, 1, 1, 1, 1 },
			{ 2, 2, 1, 1, 1 },
			{ 2, 2, 2, 1, 1 },
			{ 2, 2, 2, 2, 1 },
			{ 2, 2, 2, 2, 2 }
	};

	/**
	 * Expected occupancy of the seven f orbitals for every valid total count 0..14, indexed
	 * by the total electron count and ordered by magnetic quantum number.
	 */
	private static final int[][] F_TABLE = {
			{ 0, 0, 0, 0, 0, 0, 0 },
			{ 1, 0, 0, 0, 0, 0, 0 },
			{ 1, 1, 0, 0, 0, 0, 0 },
			{ 1, 1, 1, 0, 0, 0, 0 },
			{ 1, 1, 1, 1, 0, 0, 0 },
			{ 1, 1, 1, 1, 1, 0, 0 },
			{ 1, 1, 1, 1, 1, 1, 0 },
			{ 1, 1, 1, 1, 1, 1, 1 },
			{ 2, 1, 1, 1, 1, 1, 1 },
			{ 2, 2, 1, 1, 1, 1, 1 },
			{ 2, 2, 2, 1, 1, 1, 1 },
			{ 2, 2, 2, 2, 1, 1, 1 },
			{ 2, 2, 2, 2, 2, 1, 1 },
			{ 2, 2, 2, 2, 2, 2, 1 },
			{ 2, 2, 2, 2, 2, 2, 2 }
	};

	/**
	 * Reads the occupancy of the orbitals of a given angular momentum within a shell, ordered
	 * by magnetic quantum number. An unoccupied orbital contributes no {@link SubShell}, so its
	 * slot is left at zero; an occupied orbital reports one electron for its spin-up slot and a
	 * second for its spin-down slot. A proton count of zero is passed to
	 * {@link SubShell#getElectron(Spin, int)} because only occupancy is being inspected.
	 *
	 * @param shell    the shell to inspect
	 * @param angular  the angular momentum quantum number of the subshell (1=p, 2=d, 3=f)
	 * @param orbitals the number of orbitals in that subshell
	 * @return an occupancy array ordered by magnetic quantum number
	 */
	private static int[] occupancy(Shell shell, int angular, int orbitals) {
		int[] occ = new int[orbitals];

		for (SubShell sub : shell.subShells()) {
			Orbital o = sub.getOrbital();
			if (o.getAngular() != angular) continue;

			int index = o.getMagnetic() + angular;
			int count = 0;
			if (sub.getElectron(Spin.Up, 0) != null) count++;
			if (sub.getElectron(Spin.Down, 0) != null) count++;
			occ[index] = count;
		}

		return occ;
	}

	/**
	 * Every valid p total (0..6) must distribute across the three 2p orbitals exactly as
	 * {@link #P_TABLE} specifies when supplied through {@link Shell#second(int, int)}.
	 */
	@Test(timeout = 10000)
	public void pSubshellFollowsHundsRule() {
		for (int p = 0; p < P_TABLE.length; p++) {
			Shell shell = Shell.second(1, p);
			Assert.assertArrayEquals("2p occupancy for total " + p,
					P_TABLE[p], occupancy(shell, 1, 3));
		}
	}

	/**
	 * Every valid d total (0..10) must distribute across the five 3d orbitals exactly as
	 * {@link #D_TABLE} specifies when supplied through {@link Shell#third(int, int, int)}.
	 */
	@Test(timeout = 10000)
	public void dSubshellFollowsHundsRule() {
		for (int d = 0; d < D_TABLE.length; d++) {
			Shell shell = Shell.third(1, 0, d);
			Assert.assertArrayEquals("3d occupancy for total " + d,
					D_TABLE[d], occupancy(shell, 2, 5));
		}
	}

	/**
	 * Every valid f total (0..14) must distribute across the seven 4f orbitals exactly as
	 * {@link #F_TABLE} specifies when supplied through {@link Shell#fourth(int, int, int, int)}.
	 */
	@Test(timeout = 10000)
	public void fSubshellFollowsHundsRule() {
		for (int f = 0; f < F_TABLE.length; f++) {
			Shell shell = Shell.fourth(1, 0, 0, f);
			Assert.assertArrayEquals("4f occupancy for total " + f,
					F_TABLE[f], occupancy(shell, 3, 7));
		}
	}

	/**
	 * A p total outside the valid 0..6 range contributes no p electrons at all: the builders
	 * silently drop it rather than overfilling the subshell. This pins the switch fall-through
	 * so the consolidated distribution preserves it instead of saturating out-of-range counts.
	 */
	@Test(timeout = 10000)
	public void outOfRangePTotalContributesNoElectrons() {
		Shell shell = Shell.second(1, 7);
		Assert.assertArrayEquals("an out-of-range p total of 7 must add no p electrons",
				new int[] { 0, 0, 0 }, occupancy(shell, 1, 3));
	}

	/**
	 * A negative subshell total contributes no electrons at all, exactly as an above-capacity
	 * total does. This exercises the lower side of the distribution's range guard (a total below
	 * one) for the p, d and f subshells, which the above-capacity {@link
	 * #outOfRangePTotalContributesNoElectrons()} case does not reach.
	 */
	@Test(timeout = 10000)
	public void negativeTotalContributesNoElectrons() {
		int[] pOcc = occupancy(Shell.second(1, -1), 1, 3);
		Assert.assertArrayEquals("a negative p total must add no p electrons",
				new int[] { 0, 0, 0 }, pOcc);

		int[] dOcc = occupancy(Shell.third(1, 0, -3), 2, 5);
		Assert.assertArrayEquals("a negative d total must add no d electrons",
				new int[] { 0, 0, 0, 0, 0 }, dOcc);

		int[] fOcc = occupancy(Shell.fourth(1, 0, 0, -5), 3, 7);
		Assert.assertArrayEquals("a negative f total must add no f electrons",
				new int[] { 0, 0, 0, 0, 0, 0, 0 }, fOcc);
	}
}
