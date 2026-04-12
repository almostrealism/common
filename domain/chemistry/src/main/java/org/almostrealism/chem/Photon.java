package org.almostrealism.chem;

import io.almostrealism.relation.Evaluable;
import org.almostrealism.algebra.Pair;
import org.almostrealism.color.RGB;
import org.almostrealism.physics.PhysicalConstants;

/**
 * Represents a single photon as a pair of (wavelength, phase) values.
 * <p>
 * Wavelength is stored in micrometers. Phase is a dimensionless value between
 * -1.0 and 1.0 representing the oscillation state of the electromagnetic wave.
 * Energy is derived from wavelength using the relation {@code E = HC / wavelength},
 * where {@link PhysicalConstants#HC} is Planck's constant times the speed of light.
 * </p>
 */
public class Photon extends Pair implements PhysicalConstants {
	/**
	 * Constructs a photon with the specified wavelength and phase.
	 *
	 * @param wavelength  the wavelength in micrometers
	 * @param phase       the phase between -1.0 and 1.0
	 */
	public Photon(double wavelength, double phase) {
		setWavelength(wavelength);
		setPhase(phase);
	}

	/** Wavelength in micrometers. */
	public double getWavelength() { return theta(); }

	/** Phase between -1.0 and 1.0 */
	public double getPhase() { return phi(); }

	/**
	 * Sets the wavelength of this photon in micrometers.
	 *
	 * @param wavelength  the wavelength in micrometers
	 */
	public void setWavelength(double wavelength) { setTheta(wavelength); }

	/**
	 * Sets the phase of this photon.
	 *
	 * @param phase  the phase between -1.0 and 1.0
	 */
	public void setPhase(double phase) { setPhi(phase); }

	/**
	 * Returns the energy in electron volts.
	 *
	 * @see  PhysicalConstants#HC
	 */
	public double getEnergy() { return HC / getWavelength(); }

	/**
	 * Merges multiple photons into a single RGB color value, taking phase into account.
	 * <p>
	 * This method is not yet implemented; calling it throws a {@link RuntimeException}.
	 * The intent is to combine photons such that wave interference patterns are reproduced.
	 * </p>
	 *
	 * @param p  the photons to merge
	 * @return   an evaluable RGB value (not yet implemented)
	 * @throws RuntimeException always, as this method is not yet implemented
	 */
	public static Evaluable<RGB> merge(Photon... p) {
		// TODO Combine Photons taking phase into account so interference patterns are reproduced
		throw new RuntimeException("Not implemented");
	}

	/**
	 * Creates a photon with the specified energy (in electron volts) and a random phase.
	 *
	 * @param e  the photon energy in electron volts
	 * @return   a new photon with wavelength derived from the given energy and a random phase
	 */
	public static Photon withEnergy(double e) {
		return withEnergy(e, (StrictMath.random() * 2.0) - 1.0);
	}

	/**
	 * Creates a photon with the specified energy (in electron volts) and phase.
	 *
	 * @param e      the photon energy in electron volts
	 * @param phase  the phase between -1.0 and 1.0
	 * @return       a new photon with wavelength {@code HC / e} and the given phase
	 */
	public static Photon withEnergy(double e, double phase) {
		return new Photon(HC / e, phase);
	}
}
