package org.almostrealism.chem;

import org.almostrealism.electrostatic.ProtonCloud;

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

	/** The electron cloud model for this alloy material. */
	private final ElectronCloud electrons; // TODO  Replace with ElectronDensityAbsorber

	/** The proton cloud model for this alloy material (not yet implemented). */
	private ProtonCloud protons;  // TODO

	/**
	 * Constructs an alloy material from the given alloy, initializing the electron cloud
	 * with the default number of atom samples.
	 *
	 * @param m  the alloy whose material is represented
	 */
	public AlloyMaterial(Alloy m) {
		super(m);
		this.electrons = new ElectronCloud(m, defaultSamples);
	}
}
