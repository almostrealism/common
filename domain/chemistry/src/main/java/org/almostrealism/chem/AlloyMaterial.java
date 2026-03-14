package org.almostrealism.chem;

import org.almostrealism.electrostatic.ProtonCloud;

public class AlloyMaterial extends Material {
	public static final int defaultSamples = 10;

	private final ElectronCloud electrons; // TODO  Replace with ElectronDensityAbsorber
	private ProtonCloud protons;  // TODO

	public AlloyMaterial(Alloy m) {
		super(m);
		this.electrons = new ElectronCloud(m, defaultSamples);
	}
}
