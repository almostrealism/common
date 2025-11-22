package org.almostrealism.chem;

import org.almostrealism.physics.Atom;
import org.almostrealism.physics.Shell;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Represents the chemical element Hydrogen (H).
 *
 * <p>Hydrogen is the lightest and most abundant element in the universe.
 * It has atomic number 1, meaning it has one proton in its nucleus and
 * one electron in its single electron shell (1s1 configuration).</p>
 *
 * <h2>Properties</h2>
 * <ul>
 *   <li><b>Symbol:</b> H</li>
 *   <li><b>Atomic Number:</b> 1</li>
 *   <li><b>Electron Configuration:</b> 1s1</li>
 *   <li><b>Category:</b> Reactive nonmetal</li>
 *   <li><b>Group:</b> 1 (but not an alkali metal)</li>
 *   <li><b>Period:</b> 1</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Access via PeriodicTable
 * Element hydrogen = PeriodicTable.Hydrogen;
 *
 * // Get atomic number
 * int z = hydrogen.getAtomicNumber();  // Returns 1
 *
 * // Construct an atom
 * Atom h = hydrogen.construct();
 * }</pre>
 *
 * @see Element
 * @see PeriodicTable
 * @see org.almostrealism.physics.Atom
 * @see org.almostrealism.physics.Shell
 *
 * @author Michael Murray
 */
public class Hydrogen implements Element {

	/**
	 * {@inheritDoc}
	 *
	 * @return 1 (the atomic number of Hydrogen)
	 */
	public int getAtomicNumber() { return 1; }

	/**
	 * Constructs a Hydrogen atom with a single electron in the 1s orbital.
	 *
	 * @return a new Atom instance representing a Hydrogen atom with
	 *         electron configuration 1s1
	 */
	public Atom construct() { return new Atom(getAtomicNumber(), getShells()); }

	/**
	 * Returns the electron shell configuration for Hydrogen.
	 *
	 * <p>Hydrogen has a single electron shell (K shell / n=1) containing
	 * one electron in the 1s orbital.</p>
	 *
	 * @return an unmodifiable list containing the single electron shell
	 */
	private List<Shell> getShells() {
		return Collections.unmodifiableList(Arrays.asList(Shell.first(1)));
	}
}
