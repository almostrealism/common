package org.almostrealism.chem;


/**
 * A material representation for an {@link Alloy} that combines an {@link ElectronCloud}
 * and a {@link org.almostrealism.electrostatic.ProtonCloud} to model absorption and
 * emission behavior at the atomic level.
 * <p>
 * The electron cloud is constructed from the alloy's elemental composition using a
 * configurable number of atom samples. The proton cloud is a placeholder pending
 * a future implementation based on atomic numbers.
 * </p>
 */
public class AlloyMaterial extends Material {
	/** Default number of atom samples used when constructing the {@link ElectronCloud}. */
	public static final int defaultSamples = 10;

	/**
	 * Constructs an alloy material from the given alloy.
	 *
	 * @param m  the alloy whose material is represented
	 */
	public AlloyMaterial(Alloy m) {
		super(m);
	}
}
