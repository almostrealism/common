package org.almostrealism.chem;

/**
 * Represents a hydrocarbon molecule composed of carbon and hydrogen atoms.
 *
 * <p>A {@code Hydrocarbon} is an organic compound consisting entirely of
 * hydrogen and carbon atoms. Hydrocarbons are the simplest organic compounds
 * and form the basis of organic chemistry. They include:</p>
 * <ul>
 *   <li><b>Alkanes</b> - Saturated hydrocarbons (single bonds only): methane, ethane, propane</li>
 *   <li><b>Alkenes</b> - Unsaturated hydrocarbons with C=C double bonds: ethene, propene</li>
 *   <li><b>Alkynes</b> - Unsaturated hydrocarbons with C-C triple bonds: ethyne (acetylene)</li>
 *   <li><b>Aromatic</b> - Cyclic hydrocarbons with delocalized electrons: benzene, toluene</li>
 * </ul>
 *
 * <h2>Current Limitations</h2>
 * <p>This class is currently a stub implementation. It implements the {@link Molecule}
 * interface but does not yet provide:</p>
 * <ul>
 *   <li>Specific carbon chain structure</li>
 *   <li>Bond type information (single, double, triple)</li>
 *   <li>Molecular formula calculation</li>
 *   <li>Isomer representation</li>
 * </ul>
 *
 * <p>The default {@link Molecule} implementations will throw
 * {@link UnsupportedOperationException} for graph operations until
 * this class is fully implemented.</p>
 *
 * <h2>Intended Future Usage</h2>
 * <pre>{@code
 * // Conceptual - not yet implemented
 * Hydrocarbon methane = new Hydrocarbon("CH4");  // Methane
 * Hydrocarbon ethane = new Hydrocarbon("C2H6");  // Ethane
 * Hydrocarbon benzene = new Hydrocarbon("C6H6"); // Benzene (aromatic)
 *
 * int atomCount = methane.countNodes();  // Would return 5 (1 C + 4 H)
 * }</pre>
 *
 * @see Molecule
 * @see Element
 * @see PeriodicTable#Carbon
 * @see PeriodicTable#Hydrogen
 *
 * @author Michael Murray
 */
public class Hydrocarbon implements Molecule {
}
