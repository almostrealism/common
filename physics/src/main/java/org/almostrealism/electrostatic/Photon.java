package org.almostrealism.electrostatic;

import io.almostrealism.relation.Evaluable;
import org.almostrealism.algebra.Pair;
import org.almostrealism.color.RGB;
import org.almostrealism.physics.PhysicalConstants;

public class Photon extends Pair implements PhysicalConstants {
	public Photon(double wavelength, double phase) {
		setWavelength(wavelength);
		setPhase(phase);
	}

	/** Wavelength in micrometers. */
	public double getWavelength() { return theta(); }

	/** Phase between -1.0 and 1.0 */
	public double getPhase() { return phi(); }

	public void setWavelength(double wavelength) { setTheta(wavelength); }
	public void setPhase(double phase) { setPhi(phase); }

	/**
	 * Returns the energy in electron volts.
	 *
	 * @see  PhysicalConstants#HC
	 */
	public double getEnergy() { return HC / getWavelength(); }

	public static Evaluable<RGB> merge(Photon... p) {
		// TODO Combine Photons taking phase into account so interference patterns are reproduced
		throw new RuntimeException("Not implemented");
	}

	public static Photon withEnergy(double e) {
		return withEnergy(e, (StrictMath.random() * 2.0) - 1.0);
	}

	public static Photon withEnergy(double e, double phase) {
		return new Photon(HC / e, phase);
	}
}
