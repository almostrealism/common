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
 * Represents the intrinsic angular momentum (spin) of an electron.
 * <p>
 * In quantum mechanics, electrons possess an intrinsic angular momentum called spin,
 * which can have one of two values: spin-up (+1/2) or spin-down (-1/2). This quantum
 * number determines the magnetic moment of the electron and is fundamental to the
 * Pauli exclusion principle, which states that no two electrons in an atom can have
 * the same set of quantum numbers.
 * </p>
 *
 * <h2>Pauli Exclusion Principle</h2>
 * <p>
 * Each orbital ({@link Orbital}) can hold a maximum of two electrons, but only if
 * they have opposite spins. This constraint is enforced by the {@link SubShell} class.
 * </p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Create an electron with spin-up
 * Electron e1 = new Electron(Spin.Up);
 *
 * // Create an electron with spin-down
 * Electron e2 = new Electron(Spin.Down);
 * }</pre>
 *
 * @author Michael Murray
 * @see Electron
 * @see SubShell
 * @see Orbital
 */
public enum Spin {
	/**
	 * Spin-up state, corresponding to spin quantum number +1/2.
	 * By convention, spin-up electrons fill orbitals first according to Hund's rule.
	 */
	Up,

	/**
	 * Spin-down state, corresponding to spin quantum number -1/2.
	 * Spin-down electrons pair with spin-up electrons in the same orbital.
	 */
	Down
}
