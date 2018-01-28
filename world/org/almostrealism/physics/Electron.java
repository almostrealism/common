package org.almostrealism.physics;

public class Electron {
	private Spin s;
	private int excitation;
	private double excitationEnergyLevels[];

	protected Electron(Spin s, Iterable<Orbital> excitationOptions) {

	}

	public int getExcitation() { return excitation; }
	public void setExcitation(int e) { this.excitation = e; }
	public int getMaxExcitation() { return excitationEnergyLevels.length - 1; }

	public double getExcitationEnergy() { return this.excitationEnergyLevels[excitation]; }
}
