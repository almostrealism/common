package org.almostrealism.physics;

/**
 * Represents a chemical element from the periodic table.
 *
 * <p>An {@code Element} is a fundamental chemical substance that cannot be broken
 * down into simpler substances by chemical means. Each element is characterized by
 * its atomic number, which represents the number of protons in the nucleus and
 * determines the element's chemical properties.</p>
 *
 * <p>The chemistry module provides implementations for all 118 known chemical elements,
 * from Hydrogen (atomic number 1) to Oganesson (atomic number 118). Each element
 * implementation encapsulates:</p>
 * <ul>
 *   <li>The atomic number (number of protons)</li>
 *   <li>The electron shell configuration needed to construct an {@link org.almostrealism.physics.Atom}</li>
 * </ul>
 *
 *
 * @see Atomic
 * @see org.almostrealism.physics.Atom
 *
 * @author Michael Murray
 */
public interface Element extends Atomic {
	/**
	 * Returns the atomic number of this element.
	 *
	 * <p>The atomic number is the number of protons found in the nucleus of
	 * every atom of this element. It uniquely identifies the element and
	 * determines its position in the periodic table. For example:</p>
	 * <ul>
	 *   <li>Hydrogen: 1</li>
	 *   <li>Carbon: 6</li>
	 *   <li>Iron: 26</li>
	 *   <li>Gold: 79</li>
	 *   <li>Uranium: 92</li>
	 * </ul>
	 *
	 * @return the atomic number (Z) of this element, ranging from 1 (Hydrogen)
	 *         to 118 (Oganesson)
	 */
	int getAtomicNumber();
}
