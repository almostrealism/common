package org.almostrealism.chem;

import org.almostrealism.physics.Atom;
import org.almostrealism.physics.Shell;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents the chemical element Carbon (C).
 *
 * <p>Carbon is the basis of all organic chemistry and is essential to all known
 * life. It has atomic number 6, with six protons in its nucleus and six electrons
 * arranged in two shells. Its four valence electrons enable it to form up to
 * four covalent bonds, making it uniquely versatile for building complex molecules.</p>
 *
 * <h2>Properties</h2>
 * <ul>
 *   <li><b>Symbol:</b> C</li>
 *   <li><b>Atomic Number:</b> 6</li>
 *   <li><b>Electron Configuration:</b> 1s2 2s2 2p2 (or [He] 2s2 2p2)</li>
 *   <li><b>Category:</b> Reactive nonmetal</li>
 *   <li><b>Group:</b> 14 (Carbon group)</li>
 *   <li><b>Period:</b> 2</li>
 *   <li><b>Valence Electrons:</b> 4</li>
 * </ul>
 *
 * <h2>Electron Configuration</h2>
 * <p>Carbon builds upon Helium's noble gas core (1s2) and adds four more
 * electrons in the second shell: two in the 2s orbital and two in the 2p
 * orbital. This implementation reuses {@link Helium}'s shell configuration
 * for the inner shell.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Access via PeriodicTable
 * Element carbon = PeriodicTable.Carbon;
 *
 * // Get atomic number
 * int z = carbon.getAtomicNumber();  // Returns 6
 *
 * // Construct an atom
 * Atom c = carbon.construct();
 *
 * // Carbon is essential for organic chemistry
 * List<Element> organicElements = PeriodicTable.organics();
 * }</pre>
 *
 * @see Element
 * @see PeriodicTable
 * @see Helium
 * @see Hydrocarbon
 * @see org.almostrealism.physics.Atom
 * @see org.almostrealism.physics.Shell
 *
 * @author Michael Murray
 */
public class Carbon implements Element {

	/**
	 * {@inheritDoc}
	 *
	 * @return 6 (the atomic number of Carbon)
	 */
	public int getAtomicNumber() { return 6; }

	/**
	 * Constructs a Carbon atom with electrons in two shells.
	 *
	 * @return a new Atom instance representing a Carbon atom with
	 *         electron configuration [He] 2s2 2p2
	 */
	public Atom construct() { return new Atom(getAtomicNumber(), getShells()); }

	/**
	 * Returns the electron shell configuration for Carbon.
	 *
	 * <p>Carbon has two electron shells:</p>
	 * <ul>
	 *   <li>First shell (K shell / n=1): 2 electrons (1s2) - reused from Helium</li>
	 *   <li>Second shell (L shell / n=2): 4 electrons (2s2 2p2)</li>
	 * </ul>
	 *
	 * @return an unmodifiable list containing the two electron shells
	 */
	private List<Shell> getShells() {
		ArrayList<Shell> s = new ArrayList<Shell>();
		s.addAll(PeriodicTable.Helium.getShells());
		s.add(Shell.second(2, 2));
		return Collections.unmodifiableList(s);
	}
}
