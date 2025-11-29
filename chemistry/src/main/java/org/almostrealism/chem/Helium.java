package org.almostrealism.chem;

import org.almostrealism.physics.Atom;
import org.almostrealism.physics.Shell;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Represents the chemical element Helium (He).
 *
 * <p>Helium is the second lightest element and the second most abundant in the
 * observable universe. It has atomic number 2, with two protons in its nucleus
 * and two electrons completing the first electron shell (1s2 configuration).
 * This filled shell makes Helium chemically inert - a noble gas.</p>
 *
 * <h2>Properties</h2>
 * <ul>
 *   <li><b>Symbol:</b> He</li>
 *   <li><b>Atomic Number:</b> 2</li>
 *   <li><b>Electron Configuration:</b> 1s2</li>
 *   <li><b>Category:</b> Noble gas</li>
 *   <li><b>Group:</b> 18</li>
 *   <li><b>Period:</b> 1</li>
 * </ul>
 *
 * <h2>Noble Gas Core</h2>
 * <p>Helium's electron configuration serves as the noble gas core for Period 2
 * elements. Other elements in the chemistry module reuse Helium's shell
 * configuration as the inner shell when building heavier atoms.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Access via PeriodicTable
 * Element helium = PeriodicTable.Helium;
 *
 * // Get atomic number
 * int z = helium.getAtomicNumber();  // Returns 2
 *
 * // Construct an atom
 * Atom he = helium.construct();
 * }</pre>
 *
 * @see Element
 * @see PeriodicTable
 * @see org.almostrealism.physics.Atom
 * @see org.almostrealism.physics.Shell
 *
 * @author Michael Murray
 */
public class Helium implements Element {

	/**
	 * {@inheritDoc}
	 *
	 * @return 2 (the atomic number of Helium)
	 */
	public int getAtomicNumber() { return 2; }

	/**
	 * Constructs a Helium atom with two electrons in the 1s orbital.
	 *
	 * @return a new Atom instance representing a Helium atom with
	 *         electron configuration 1s2
	 */
	public Atom construct() { return new Atom(getAtomicNumber(), getShells()); }

	/**
	 * Returns the electron shell configuration for Helium.
	 *
	 * <p>Helium has a single electron shell (K shell / n=1) containing
	 * two electrons, completing the 1s orbital. This configuration is
	 * reused by heavier elements as their inner (noble gas core) shell.</p>
	 *
	 * <p>This method is protected to allow other element classes to
	 * reuse Helium's shell configuration when building their electron
	 * structures.</p>
	 *
	 * @return an unmodifiable list containing the single filled electron shell
	 */
	protected List<Shell> getShells() {
		return Collections.unmodifiableList(List.of(Shell.first(2)));
	}
}
