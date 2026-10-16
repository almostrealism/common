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

import org.almostrealism.chem.Electron;
import org.almostrealism.chem.Electrons;
import org.almostrealism.chem.Spin;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for {@link Electrons#emit()}.
 *
 * <p>When a group of electrons is excited by absorbing a photon, {@link Electrons#emit()}
 * is documented to return "the energy of the emitted photon" which "equals the energy
 * difference between configurations". These tests build a single-electron group with a
 * ground state at 0 eV and a single excited state at 10 eV, so the transition back to the
 * ground state is deterministic and the emitted photon energy must be exactly 10 eV.</p>
 */
public class ElectronsEmitTest extends TestSuiteBase {

	/** Exposes the protected {@link Electrons} constructor for test construction. */
	private static final class TestElectrons extends Electrons {
		/**
		 * Constructs a test electron group from the given electrons.
		 *
		 * @param e the electrons to manage
		 */
		private TestElectrons(Electron[] e) {
			super(e);
		}
	}

	/**
	 * Builds a single-electron group with excitation energy levels {@code {0.0, 10.0}}.
	 *
	 * @return a freshly constructed, ground-state electron group
	 */
	private Electrons singleElectron() {
		Electron electron = new Electron();
		electron.setSpin(Spin.Up);
		electron.setExcitationEnergyLevels(new double[] { 0.0, 10.0 });
		return new TestElectrons(new Electron[] { electron });
	}

	/**
	 * After absorbing a 10 eV photon, emitting must return a photon carrying the 10 eV lost
	 * in the transition back to the ground state, not 0.
	 */
	@Test(timeout = 5000)
	public void emitReturnsTransitionEnergy() {
		Electrons electrons = singleElectron();

		Assert.assertTrue("10 eV photon should be absorbed", electrons.absorb(10.0));

		double emitted = electrons.emit();
		Assert.assertEquals("Emitted photon energy must equal the 10 eV transition",
				10.0, emitted, Electrons.spectralBandwidth);
	}

	/**
	 * Emitting back to the ground state must clear the excitation so the group can absorb
	 * again; otherwise a repeated-emission loop (as in {@code ElectronCloud}) never terminates.
	 */
	@Test(timeout = 5000)
	public void emitReturnsToGroundState() {
		Electrons electrons = singleElectron();

		electrons.absorb(10.0);
		electrons.emit();

		Assert.assertNull("Group should be back in the ground state after emission",
				electrons.getExcited());
		Assert.assertTrue("A ground-state group should be able to absorb again",
				electrons.absorb(10.0));
	}
}
