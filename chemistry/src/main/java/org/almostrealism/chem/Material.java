package org.almostrealism.chem;

import org.almostrealism.physics.Atomic;
import org.almostrealism.physics.Element;

/**
 * A simple wrapper class for atomic substances used in material science contexts.
 *
 * <p>The {@code Material} class provides a basic container for an {@link Atomic}
 * substance. It serves as a foundation for more complex material modeling that
 * may include additional properties such as physical characteristics, thermal
 * properties, or structural information.</p>
 *
 * <h2>Current Limitations</h2>
 * <p>This class is currently a minimal implementation with basic wrapper functionality.
 * It stores a reference to an {@link Atomic} substance but does not yet expose
 * methods for accessing or manipulating it. Future enhancements may include:</p>
 * <ul>
 *   <li>Physical property accessors (density, melting point, etc.)</li>
 *   <li>Thermal and electrical conductivity information</li>
 *   <li>Crystal structure data</li>
 *   <li>Integration with physics simulation modules</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Create a material from a single element
 * Material copper = new Material(PeriodicTable.Copper);
 *
 * // Create a material from an alloy
 * Alloy bronze = new Alloy(
 *     Arrays.asList(PeriodicTable.Copper, PeriodicTable.Tin),
 *     0.88, 0.12
 * );
 * Material bronzeMaterial = new Material(bronze);
 * }</pre>
 *
 * @see Atomic
 * @see Element
 * @see Alloy
 *
 * @author Michael Murray
 */
public class Material {

	/** The atomic substance that comprises this material. */
	private final Atomic s;

	/**
	 * Creates a new material from the specified atomic substance.
	 *
	 * <p>The atomic substance can be a single {@link Element} or a composite
	 * substance like an {@link Alloy}.</p>
	 *
	 * @param s the atomic substance that makes up this material
	 */
	public Material(Atomic s) {
		this.s = s;
	}
}
