package org.almostrealism.physics;

public class Electrons {
	/**
	 * The maximum difference, in electron volts, between the energy specified to {@link #absorb(double)}
	 * and the energy subsequently returned by {@link #emit()}. The smaller this number is, the more exact
	 * the match must be between photon energy and the excitation energy of the group of electrons for
	 * {@link #absorb(double)} to return true.
	 */
	public static final double spectralBandwidth = 0.001;

	private Electron e[];

	protected Electrons(Electron e[]) {
		this.e = e;
	}

	public boolean absorb(double eV) { return false; }

	public double emit() { return 0.0; }
}
